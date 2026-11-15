// SPDX-License-Identifier: Apache-2.0
// Known smooth local motion on identical dark/bright texture. The old fixed
// Hessian floor fails the dark case (~0.36 px versus ~0.052 px corrected).
#include "lkrl_solver.h"
#include <cmath>
#include <cstdio>
float scene(float x, float y) {
  return .006f + .001f * std::sin(x * .37f) + .001f * std::sin(y * .29f) +
         .0005f * std::sin((x + y) * .61f);
}
int main() {
  lkrl::Gpu gpu;
  if (!gpu.ok())
    return 2;
  lkrl::LiteSolver solver(&gpu);
  int h = 128, w = 128, K = 2;
  std::vector<float> frames(K * h * w);
  for (int k = 0; k < K; ++k)
    for (int y = 0; y < h; ++y)
      for (int x = 0; x < w; ++x) {
        float dx = k * .7f * std::sin((y + .5f) * 6.2831853f / h),
              dy = k * .6f * std::sin((x + .5f) * 6.2831853f / w);
        frames[(k * h + y) * w + x] = scene(x + .5f + dx, y + .5f + dy);
      }
  for (float brightness : {1.f, 16.f}) {
    std::vector<float> scaled = frames;
    for (float &v : scaled)
      v *= brightness;
    float variance = 1e-10f * brightness * brightness;
    lkrl::LiteParams p;
    p.s = 1;
    p.schedule = "GGGGGG";
    p.chroma_from_avg = 0;
    p.noise_b = variance;
    p.kernelnet_denoise = 0;
    p.auto_local_lambda = false;
    std::vector<float> m(K * 6, 0), d, z;
    for (int k = 0; k < K; ++k) {
      m[k * 6] = m[k * 6 + 4] = 1;
    }
    lkrl::LiteStats stats;
    std::string err;
    if (!solver.solve(scaled.data(), K, h, w, m, d, p, &z, &stats, &err)) {
      std::puts(err.c_str());
      return 1;
    }
    double mse = 0;
    int n = 0;
    for (int by = 1; by < 7; ++by)
      for (int bx = 1; bx < 7; ++bx) {
        float x = bx * 16 + 8, y = by * 16 + 8,
              dx = .7f * std::sin(y * 6.2831853f / h),
              dy = .6f * std::sin(x * 6.2831853f / w);
        int idx = (64 + by * 8 + bx) * 2;
        double ex = m[6] * x + m[7] * y + m[8] + d[idx] - x - dx,
               ey = m[9] * x + m[10] * y + m[11] + d[idx + 1] - y - dy;
        mse += ex * ex + ey * ey;
        ++n;
      }
    double rmse = std::sqrt(mse / n);
    std::printf("brightness %.0f motion RMSE %.9g px\n", brightness, rmse);
    if (!std::isfinite(rmse) || rmse > .12)
      return 1;
  }
}
