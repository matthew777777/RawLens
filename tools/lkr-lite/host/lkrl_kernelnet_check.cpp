// SPDX-License-Identifier: Apache-2.0
#include "lkrl_solver.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>

// Synthetic Vulkan regression: the enabled daylight path is byte-identical and
// submits no additional GPU work; noisy output actually runs the bundled model.
int main() {
  lkrl::Gpu gpu;
  if (!gpu.ok()) return 2;
  lkrl::LiteSolver solver(&gpu);
  if (!solver.ok()) return 2;
  constexpr int h = 64, w = 64, K = 4;
  std::mt19937 random(17);
  std::normal_distribution<float> normal(0.f, 1.f);
  for (int scale : {1, 2}) for (float relative : {0.02f, 0.06f, 0.10f}) {
    const float sigma = 0.18f * relative;
    std::vector<float> frames(K*h*w);
    for (float& v : frames) v = 0.18f + sigma * normal(random);
    std::vector<float> result[2];
    lkrl::LiteStats stats[2];
    for (int enabled = 0; enabled < 2; ++enabled) {
      lkrl::LiteParams prm;
      prm.s = scale;
      prm.noise_b = sigma * sigma;
      prm.kernelnet_denoise = float(enabled);
      std::vector<float> motion(K*6, 0.f), local, noise;
      for (int k = 0; k < K; ++k) { motion[k*6] = 1.f; motion[k*6+4] = 1.f; }
      std::string error;
      if (!solver.solve(frames.data(), K, h, w, motion, local, prm,
                        &result[enabled], &stats[enabled], &error, &noise)) {
        std::fprintf(stderr, "%s\n", error.c_str()); return 1;
      }
      for (float v : result[enabled]) if (!std::isfinite(v)) return 1;
    }
    const bool equal = result[0].size() == result[1].size() &&
        std::memcmp(result[0].data(), result[1].data(), result[0].size()*sizeof(float)) == 0;
    if (relative <= 0.04f) {
      if (!equal || stats[0].submits != stats[1].submits) return 1;
    } else if (equal || stats[1].submits <= stats[0].submits) return 1;
    double variance[2] = {};
    for (int mode = 0; mode < 2; ++mode) {
      const int W = scale*w, H = scale*h;
      double sum = 0, sum2 = 0; int n = 0;
      for (int y = 8; y < H-8; ++y) for (int x = 8; x < W-8; ++x) {
        const float v = result[mode][y*W+x]; sum += v; sum2 += v*v; ++n;
      }
      variance[mode] = sum2/n - (sum/n)*(sum/n);
    }
    if (relative > 0.04f && variance[1] >= variance[0]) return 1;
    std::printf("PASS %dx noise %.0f%%: identical=%d, submits %d/%d, variance %.8g/%.8g\n",
                scale, relative*100, equal, stats[0].submits, stats[1].submits, variance[0], variance[1]);
  }
}
