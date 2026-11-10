// SPDX-License-Identifier: GPL-3.0-or-later
// End-to-end desktop run: DNG burst directory -> LK-Reloaded crop -> outputs.
//
//   lkr_run BURST_DIR OUT_DIR [--cx X --cy Y --crop N --s 2 --T 16 --zs 3
//                              --every 2 --smax 10 --end 1 --frames K]
// Writes OUT_DIR/lkr.dng (linear RGB, camera space, AsShotNeutral kept),
// x.npy (3,H,W), p.npy, p_init.npy and prints timings per stage.
#include <dirent.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "lkr_host.h"
#include "lkr_solver.h"
#include "srp_dng.h"

namespace {
double now_ms() {
  using namespace std::chrono;
  return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}
bool SaveNpy(const std::string& path, const std::vector<uint64_t>& shape, const float* d) {
  std::string shp = "(";
  size_t n = 1;
  for (size_t i = 0; i < shape.size(); ++i) {
    shp += std::to_string(shape[i]) + (shape.size() == 1 || i + 1 < shape.size() ? "," : "");
    n *= shape[i];
  }
  shp += ")";
  std::string hdr = "{'descr': '<f4', 'fortran_order': False, 'shape': " + shp + ", }";
  while ((10 + hdr.size() + 1) % 64) hdr += ' ';
  hdr += '\n';
  FILE* f = std::fopen(path.c_str(), "wb");
  if (!f) return false;
  const unsigned char magic[8] = {0x93, 'N', 'U', 'M', 'P', 'Y', 1, 0};
  std::fwrite(magic, 1, 8, f);
  const uint16_t hl = uint16_t(hdr.size());
  std::fwrite(&hl, 2, 1, f);
  std::fwrite(hdr.data(), 1, hdr.size(), f);
  std::fwrite(d, 4, n, f);
  std::fclose(f);
  return true;
}
}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    std::fprintf(stderr, "usage: lkr_run BURST_DIR OUT_DIR [--cx --cy --crop --s --T --zs --every --smax --end --frames]\n");
    return 2;
  }
  const std::string in = argv[1], out = argv[2];
  int cx = -1, cy = -1, crop = 512, s = 2, T = 16, zs = 3, every = 2, frames = 0;
  float smax = 10, end = 1;
  std::string prior_kind = "none";
  for (int i = 3; i + 1 < argc; i += 2) {
    const std::string k = argv[i];
    const double v = std::atof(argv[i + 1]);
    if (k == "--cx") cx = int(v); else if (k == "--cy") cy = int(v);
    else if (k == "--crop") crop = int(v); else if (k == "--s") s = int(v);
    else if (k == "--T") T = int(v); else if (k == "--zs") zs = int(v);
    else if (k == "--every") every = int(v); else if (k == "--smax") smax = float(v);
    else if (k == "--end") end = float(v); else if (k == "--frames") frames = int(v);
    else if (k == "--prior") prior_kind = argv[i + 1];
  }
  std::vector<std::string> paths;
  if (DIR* d = opendir(in.c_str())) {
    while (dirent* e = readdir(d)) {
      std::string n = e->d_name;
      if (n.size() > 4 && (n.substr(n.size() - 4) == ".dng" || n.substr(n.size() - 4) == ".DNG"))
        paths.push_back(in + "/" + n);
    }
    closedir(d);
  }
  std::sort(paths.begin(), paths.end());
  if (frames > 0 && int(paths.size()) > frames) paths.resize(frames);

  const double t0 = now_ms();
  lkr::Burst b;
  std::string err;
  if (!lkr::LoadBurstCrop(paths, crop, cx, cy, &b, &err)) {
    std::fprintf(stderr, "load: %s\n", err.c_str());
    return 1;
  }
  const double t_load = now_ms();
  const size_t n = size_t(b.h) * b.w;
  const float sig = lkr::NoiseSigma(b.frames.data(), b.h, b.w);
  float na = 0, nb = 0;
  lkr::NoiseAB(b.frames.data(), b.h, b.w, &na, &nb);
  const double t_noise = now_ms();
  std::vector<float> p(size_t(b.K) * 6);
  {
    std::vector<std::vector<float>> g(b.K, std::vector<float>(n));
    for (int k = 0; k < b.K; ++k) lkr::Grey(b.frames.data() + k * n, b.h, b.w, g[k].data());
    for (int k = 0; k < b.K; ++k) {
      std::array<double, 6> q{1, 0, 0, 0, 1, 0};
      if (k > 0) q = lkr::LkAffine(g[0].data(), g[k].data(), b.h, b.w);
      for (int i = 0; i < 6; ++i) p[k * 6 + i] = float(q[i]);
    }
  }
  const double t_lk = now_ms();
  std::vector<float> p_init = p;

  lkr::Gpu gpu;
  if (!gpu.ok()) { std::fprintf(stderr, "no Vulkan device\n"); return 1; }
  lkr::Solver solver(&gpu);
  lkr::SolveParams prm;
  prm.s = s; prm.T = T; prm.z_steps = zs;
  prm.sigma_noise = sig; prm.sigma_max = smax * sig; prm.sigma_end = end * sig;
  if (every > 1) for (int t = every - 1; t < T; t += every) prm.prior_at.push_back(t);
  lkr::PriorFn prior;
  if (prior_kind != "none" && !prior) {
    std::fprintf(stderr, "prior '%s' unavailable in this build\n", prior_kind.c_str());
    return 1;
  }
  const double t_gpu0 = now_ms();
  std::vector<float> x;
  lkr::SolveStats st;
  if (!solver.ok() || !solver.solve(b.frames.data(), b.K, b.h, b.w, p.data(), prm, prior, &x, &st)) {
    std::fprintf(stderr, "solve failed\n");
    return 1;
  }
  const double t_solve = now_ms();
  const int H = s * b.h, W = s * b.w;
  std::vector<float> rgb(size_t(3) * H * W);           // planar -> chunky
  for (size_t i = 0; i < size_t(H) * W; ++i)
    for (int c = 0; c < 3; ++c) rgb[i * 3 + c] = std::clamp(x[size_t(c) * H * W + i], 0.0f, 1.0f);
  srp::SrpDngMeta meta;
  srp::SrpReadDngColorMeta(paths[0].c_str(), &meta);
  srp::DngColorMeta color;
  std::memcpy(color.color_matrix1, meta.color_matrix1, sizeof(color.color_matrix1));
  color.has_color_matrix1 = meta.color_matrix_present;
  std::memcpy(color.as_shot_neutral, meta.as_shot_neutral, sizeof(color.as_shot_neutral));
  color.has_as_shot_neutral = meta.has_as_shot_neutral;
  if (meta.calibration_illuminant1) color.calibration_illuminant1 = meta.calibration_illuminant1;
  std::string werr;
  srp::DngWriteLinearRgb16((out + "/lkr.dng").c_str(), W, H, rgb.data(), nullptr, 0, &color, &werr);
  const double t_save = now_ms();
  SaveNpy(out + "/x.npy", {3, uint64_t(H), uint64_t(W)}, x.data());
  SaveNpy(out + "/p.npy", {uint64_t(b.K), 6}, p.data());
  SaveNpy(out + "/p_init.npy", {uint64_t(b.K), 6}, p_init.data());
  const float nn[3] = {sig, na, nb};
  SaveNpy(out + "/noise.npy", {3}, nn);
  std::printf("device %s  prior %s%s\n", gpu.info().c_str(), prior_kind.c_str(), "");
  std::printf("burst %d x %dx%d at (%d,%d) s=%d -> %dx%d  sigma %.5f  a %.3g b %.3g  L %.4f\n", b.K, b.w, b.h,
              b.x0, b.y0, s, W, H, sig, na, nb, st.L);
  std::printf("ms: load %.1f  noise %.1f  coarseLK %.1f  gpu-init %.1f  solve %.1f (gpu %.1f prior %.1f, %d submits)  save %.1f  TOTAL %.1f\n",
              t_load - t0, t_noise - t_load, t_lk - t_noise, t_gpu0 - t_lk, t_solve - t_gpu0, st.gpu_ms,
              st.prior_ms, st.submits, t_save - t_solve, t_save - t0);
  return 0;
}
