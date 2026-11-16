// SPDX-License-Identifier: Apache-2.0
// CPU mechanism audit, not a replacement for the zupd GPU regression.
// Manufactured noiseless observations use the documented pixel-area forward
// model and exact, fixed motion. Production MHC + bilinear initialization is
// shared; this file independently evaluates data loss after each update.
// --baseline reproduces the unbounded update and must exit 1.
// Geometries: 0 identical, 1 diverse fractional translations, 2 half-pixel
// grid, 3 near-identical. No rejection, finishing, or display transform.
#include "lkrl_image.h"
#include "lkrl_mhc.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
using V = std::vector<float>;
struct Row {
  std::array<int, 16> t;
  std::array<float, 16> a;
  float y;
};
int main(int argc, char **argv) {
  const bool bounded = !(argc == 2 && std::strcmp(argv[1], "--baseline") == 0);
  bool ok = true;
  const int h = 96, w = 96, s = 2, H = s * h, W = s * w, n = H * W, K = 8;
  for (int mode = 0; mode < 4; ++mode) {
    V truth(3 * n), raw(K * h * w), seed(3 * n), rgb(3 * h * w), cov(3 * n),
        div(3 * n);
    for (int y = 0; y < H; ++y)
      for (int x = 0; x < W; ++x) {
        float xx = (x + .5f) / s, yy = (y + .5f) / s;
        float val = .18f + .03f * std::sin(.43f * xx + .27f * yy) +
                    .02f * std::cos(.13f * xx - .51f * yy) +
                    .04f * std::tanh((xx - .63f * yy - 28) / 2);
        for (int c = 0; c < 3; ++c)
          truth[c * n + y * W + x] = val;
      }
    std::vector<Row> rows;
    for (int k = 0; k < K; ++k)
      for (int i = 0; i < h; ++i)
        for (int j = 0; j < w; ++j) {
          int c = !(i & 1) && !(j & 1) ? 0 : ((i & 1) && (j & 1) ? 2 : 1);
          Row row{};
          int m = 0;
          bool valid = true;
          float obs = 0;
          float dx = mode == 0 ? 0
                               : (mode == 1   ? k * .37f
                                  : mode == 2 ? (k % 4) * .5f
                                              : k * .001f),
                dy = mode == 0 ? 0
                               : (mode == 1   ? k * .23f
                                  : mode == 2 ? (k / 4) * .5f
                                              : k * .002f);
          for (int oy = 0; oy < s; ++oy)
            for (int ox = 0; ox < s; ++ox) {
              float x = s * (j + (ox + .5f) / s + dx) - .5f,
                    y = s * (i + (oy + .5f) / s + dy) - .5f;
              int ix = int(std::floor(x)), iy = int(std::floor(y));
              float ax = x - ix, ay = y - iy;
              valid &= ix >= 0 && iy >= 0 && ix + 1 < W && iy + 1 < H;
              for (int ty = 0; ty < 2; ++ty)
                for (int tx = 0; tx < 2; ++tx) {
                  int t = c * n + std::clamp(iy + ty, 0, H - 1) * W +
                          std::clamp(ix + tx, 0, W - 1);
                  float a = (tx ? ax : 1 - ax) * (ty ? ay : 1 - ay) / (s * s);
                  row.t[m] = t;
                  row.a[m++] = a;
                  obs += a * truth[t];
                }
            }
          row.y = obs;
          raw[(k * h + i) * w + j] = obs;
          if (valid) {
            rows.push_back(row);
            for (int t = 0; t < 16; ++t)
              cov[row.t[t]] += row.a[t];
          }
        }
    lkrl::MhcCoherentRggb(raw.data(), h, w, rgb.data());
    lkrl::UpsampleBilinear(rgb.data(), 3, h, w, s, seed.data());
    for (int c = 0; c < 3; ++c)
      for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
          float sum = 0, nn = 0;
          for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
              int yy = y + dy, xx = x + dx;
              if (yy >= 0 && xx >= 0 && yy < H && xx < W) {
                sum += cov[c * n + yy * W + xx];
                ++nn;
              }
            }
          float cv = sum / nn;
          float gain = 1 - std::pow(std::abs(1 - cv), 3);
          div[c * n + y * W + x] =
              cv > 0 ? std::max(cv / std::max(gain, 1e-4f), 1e-6f) : 1e-6f;
        }
    auto measure = [&](const V &z, int step) {
      double mse = 0, ch = 0, dr = 0;
      size_t cnt = 0;
      for (int y = 16; y < H - 16; ++y)
        for (int x = 16; x < W - 16; ++x) {
          int t = y * W + x;
          for (int c = 0; c < 3; ++c) {
            double e = z[c * n + t] - truth[c * n + t];
            mse += e * e;
            ++cnt;
          }
          double v = z[t] - z[n + t];
          ch += v * v;
        }
      for (const auto &r : rows) {
        double v = -r.y;
        for (int m = 0; m < 16; ++m)
          v += r.a[m] * z[r.t[m]];
        dr += v * v;
      }
      std::printf(
          "geometry %d step %d truthRMSE %.9g colourRMSE %.9g dataRMSE %.9g\n",
          mode, step, std::sqrt(mse / cnt), std::sqrt(ch / (cnt / 3)),
          std::sqrt(dr / rows.size()));
    };
    if (bounded)
      for (int t = 0; t < 3 * n; ++t)
        div[t] = std::max(div[t], cov[t] / 1.8f);
    auto loss = [&](const V &z) {
      double loss = 0;
      for (const auto &r : rows) {
        double e = -r.y;
        for (int m = 0; m < 16; ++m)
          e += r.a[m] * z[r.t[m]];
        loss += e * e;
      }
      return loss;
    };
    V z = seed;
    measure(z, 0);
    double previous = loss(z);
    for (int it = 0; it < 64; ++it) {
      V grad(3 * n);
      for (const auto &r : rows) {
        float e = -r.y;
        for (int m = 0; m < 16; ++m)
          e += r.a[m] * z[r.t[m]];
        for (int m = 0; m < 16; ++m)
          grad[r.t[m]] += r.a[m] * e;
      }
      for (int t = 0; t < 3 * n; ++t)
        z[t] -= grad[t] / div[t];
      double current = loss(z);
      if (!std::isfinite(current) || current > previous * 1.0001 + 1e-12)
        ok = false;
      previous = current;
      if (it == 0 || it == 7 || it == 31 || it == 63)
        measure(z, it + 1);
    }
  }
  std::printf("%s: weighted data loss must not increase at any update\n",
              ok ? "PASS" : "FAIL");
  return ok ? 0 : 1;
}
