// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop parity/benchmark run of the Vulkan LK-Reloaded solver against the
// python/lkr reference (python/lkr/parity.py writes the fixtures).
//
//   lkr_parity DIR [prior=none]
// DIR holds frames.npy (K,h,w), p_init.npy (K,6), params.npy
// [s, T, z_steps, sigma_noise, sigma_max, sigma_end, L, refine, prior_every, step_scale]
// (L = 0: Schur bound computed on the GPU, L < 0: power iteration).
// Writes x_vk.npy (3,H,W) and p_vk.npy (K,6) next to them.
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "lkr_solver.h"
#include "npy.h"

static bool SaveNpy(const std::string& path, const std::vector<uint64_t>& shape, const float* d) {
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

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr, "usage: lkr_parity DIR\n");
    return 2;
  }
  const std::string dir = argv[1];
  srp_proof::NpyArray fr, pi, pa;
  if (!srp_proof::LoadNpy((dir + "/frames.npy").c_str(), &fr) ||
      !srp_proof::LoadNpy((dir + "/p_init.npy").c_str(), &pi) ||
      !srp_proof::LoadNpy((dir + "/params.npy").c_str(), &pa))
    return 1;
  const int K = int(fr.shape[0]), h = int(fr.shape[1]), w = int(fr.shape[2]);
  lkr::SolveParams prm;
  prm.s = int(pa.data[0]);
  prm.T = int(pa.data[1]);
  prm.z_steps = int(pa.data[2]);
  prm.sigma_noise = pa.data[3];
  prm.sigma_max = pa.data[4];
  prm.sigma_end = pa.data[5];
  prm.L = pa.data[6];
  prm.refine_motion = pa.data[7] != 0;
  if (pa.data.size() > 9) prm.step_scale = pa.data[9];
  const int every = int(pa.data[8]);
  if (every > 1)
    for (int t = every - 1; t < prm.T; t += every) prm.prior_at.push_back(t);

  // Paper-exact solver vs python: no DSLR-look extras.
  prm.prior_gain = 1.0f; prm.grain = 0.0f; prm.notch = false; prm.clip = 1e9f; prm.robust = false; prm.local_block = 0; prm.mhc_init = false; prm.robust_spread = 0; prm.mean_preserve = 0; prm.temporal_k = 0; prm.shadow_boost = 0; prm.shadow_chroma = 0; prm.motion_every = 1; prm.highlight_desat = false;
  if (std::getenv("LKR_SCATTER")) prm.gather = false;
  if (const char* e = std::getenv("LKR_POWER_ITERS")) prm.power_iters = std::atoi(e);
  if (const char* e = std::getenv("LKR_POWER_TOL")) prm.power_tol = float(std::atof(e));
  lkr::Gpu gpu;
  if (!gpu.ok()) {
    std::fprintf(stderr, "no Vulkan device\n");
    return 1;
  }
  std::printf("device: %s\n", gpu.info().c_str());
  lkr::Solver solver(&gpu);
  if (!solver.ok()) return 1;
  std::vector<float> p(pi.data), x;
  lkr::SolveStats st;
  lkr::PriorFn prior;   // empty = 'none'
  if (!solver.solve(fr.data.data(), K, h, w, p.data(), prm, prior, &x, &st)) {
    std::fprintf(stderr, "solve failed\n");
    return 1;
  }
  std::printf("power_iters %d  ", st.power_iters_used);
  std::printf("K=%d %dx%d s=%d T=%d zs=%d L=%.4f  total %.1f ms (gpu %.1f, prior %.1f) submits %d dispatches %llu\n",
              K, h, w, prm.s, prm.T, prm.z_steps, st.L, st.total_ms, st.gpu_ms, st.prior_ms, st.submits,
              (unsigned long long)st.dispatches);
  const uint64_t H = uint64_t(prm.s) * h, W = uint64_t(prm.s) * w;
  SaveNpy(dir + "/x_vk.npy", {3, H, W}, x.data());
  SaveNpy(dir + "/p_vk.npy", {uint64_t(K), 6}, p.data());
  return 0;
}
