// SPDX-License-Identifier: Apache-2.0
// LK-R Lite desktop runner.
//   lkrl_run --trace PREFIX OUT.f32 [--schedule GGGJGGJ] [--guide 2] [--gmode 1] [--damp D] [--mhc M]
//            [--notch R] [--gnw 1] [--profile 1] [--repeat N]   (negative --damp / --mhc: by scale)
// --trace: inputs from an LK-R solver trace (LKR_TRACE_PREFIX=... lkr_full_run): normalised
// frames, initial affine and local motion, noise fit and robust settings from _meta.txt, so
// Lite and LK-R are compared on identical inputs. OUT.f32: z, 3 x H x W planar float32.
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "lkrl_gpu.h"
#include "lkrl_solver.h"

static bool readF32(const std::string& path, std::vector<float>* v, size_t count) {
  FILE* f = std::fopen(path.c_str(), "rb");
  if (!f) return false;
  v->resize(count);
  const size_t n = std::fread(v->data(), 4, count, f);
  std::fclose(f);
  return n == count;
}

int main(int argc, char** argv) {
  std::string trace, out, schedule;
  bool profile = false;
  int repeat = 1, gnw = 1, gmode = 1;
  float guide = 2.0f, damp = -1.0f;
  float mhc = -1.0f;
  int notch = 0, covr = -1;
  for (int i = 1; i < argc; ++i) {
    std::string k = argv[i];
    if (k == "--trace" && i + 2 < argc) { trace = argv[++i]; out = argv[++i]; }
    else if (k == "--schedule" && i + 1 < argc) schedule = argv[++i];
    else if (k == "--profile" && i + 1 < argc) profile = std::atoi(argv[++i]) != 0;
    else if (k == "--gnw" && i + 1 < argc) gnw = std::atoi(argv[++i]);
    else if (k == "--guide" && i + 1 < argc) guide = float(std::atof(argv[++i]));
    else if (k == "--gmode" && i + 1 < argc) gmode = std::atoi(argv[++i]);
    else if (k == "--damp" && i + 1 < argc) damp = float(std::atof(argv[++i]));
    else if (k == "--mhc" && i + 1 < argc) mhc = float(std::atof(argv[++i]));
    else if (k == "--notch" && i + 1 < argc) notch = std::atoi(argv[++i]);
    else if (k == "--covr" && i + 1 < argc) covr = std::atoi(argv[++i]);
    else if (k == "--repeat" && i + 1 < argc) repeat = std::atoi(argv[++i]);
    else { std::fprintf(stderr, "unknown argument %s\n", k.c_str()); return 2; }
  }
  if (trace.empty()) {
    std::fprintf(stderr, "usage: lkrl_run --trace PREFIX OUT.f32 [--schedule S] [--profile 1] [--repeat N]\n");
    return 2;
  }
  std::map<std::string, std::string> meta;
  {
    std::ifstream f(trace + "_meta.txt");
    std::string tok;
    while (f >> tok) {
      const size_t e = tok.find('=');
      if (e != std::string::npos) meta[tok.substr(0, e)] = tok.substr(e + 1);
    }
  }
  if (meta.empty()) { std::fprintf(stderr, "no %s_meta.txt\n", trace.c_str()); return 1; }
  auto num = [&](const char* k) { return std::atof(meta[k].c_str()); };
  const int K = int(num("K")), h = int(num("h")), w = int(num("w")), H = int(num("H"));
  const int nb = ((w + 15) / 16) * ((h + 15) / 16);
  lkrl::LiteParams prm;
  prm.s = H / h;
  prm.schedule = schedule;
  prm.noise_a = float(num("a")); prm.noise_b = float(num("b"));
  prm.clip = float(num("clip")); prm.robust_k = float(num("robust_k")); prm.robust_tau = float(num("robust_tau"));
  prm.profile = profile;
  prm.gn_weights = gnw;
  prm.guide = guide;
  prm.jacobi_damp = damp;
  prm.mhc_mix = mhc;
  prm.start_notch = notch;
  prm.cov_smooth = covr;
  prm.guide_mode = gmode;
  std::vector<float> frames, p0, d0;
  if (!readF32(trace + "_frames.f32", &frames, size_t(K) * h * w) ||
      !readF32(trace + "_motion_initial.f32", &p0, size_t(K) * 6) ||
      !readF32(trace + "_local_initial.f32", &d0, size_t(K) * nb * 2)) {
    std::fprintf(stderr, "trace files missing\n");
    return 1;
  }
  lkrl::Gpu gpu;
  if (!gpu.ok()) { std::fprintf(stderr, "no Vulkan device\n"); return 1; }
  lkrl::LiteSolver solver(&gpu);
  std::vector<float> z;
  lkrl::LiteStats st;
  std::string err;
  std::vector<float> p, d;
  for (int it = 0; it < repeat; ++it) {
    p = p0; d = d0;
    if (!solver.solve(frames.data(), K, h, w, p, d, prm, &z, &st, &err)) {
      std::fprintf(stderr, "solve failed: %s\n", err.c_str());
      return 1;
    }
    std::printf("lite %dx K=%d %dx%d schedule %s: %.1f ms (host %.1f ms, %d submits)\n", prm.s, K, w, h,
                schedule.c_str(), st.ms_total, st.ms_host, st.submits);
  }
  if (profile) std::printf("%s\n", st.profile.c_str());
  FILE* f = std::fopen(out.c_str(), "wb");
  if (!f || std::fwrite(z.data(), 4, z.size(), f) != z.size()) return 1;
  std::fclose(f);
  f = std::fopen((out + ".motion").c_str(), "wb");   // final affine K x 6, then local K x nb x 2
  if (f) { std::fwrite(p.data(), 4, p.size(), f); std::fwrite(d.data(), 4, d.size(), f); std::fclose(f); }
  return 0;
}
