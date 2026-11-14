// SPDX-License-Identifier: Apache-2.0
#include "lkrl_image.h"

#include <algorithm>
#include <cstddef>

namespace lkrl {

void DemosaicBilinear(const float* raw, int h, int w, float* rgb) {
  static const float k[3][3] = {{0.25f, 0.5f, 0.25f}, {0.5f, 1.0f, 0.5f}, {0.25f, 0.5f, 0.25f}};
  auto mir = [](int i, int n) { return i < 0 ? -i : (i >= n ? 2 * n - 2 - i : i); };
  auto chan = [](int i, int j) {
    bool ei = (i & 1) == 0, ej = (j & 1) == 0;
    return (ei && ej) ? 0 : ((!ei && !ej) ? 2 : 1);
  };
  for (int i = 0; i < h; ++i)
    for (int j = 0; j < w; ++j) {
      double num[3] = {0, 0, 0}, den[3] = {0, 0, 0};
      for (int di = -1; di <= 1; ++di)
        for (int dj = -1; dj <= 1; ++dj) {
          const int ii = mir(i + di, h), jj = mir(j + dj, w);
          const int c = chan(ii, jj);
          const float kv = k[di + 1][dj + 1];
          num[c] += double(kv) * raw[size_t(ii) * w + jj];
          den[c] += kv;
        }
      for (int c = 0; c < 3; ++c) rgb[(size_t(c) * h + i) * w + j] = float(num[c] / den[c]);
    }
}

// torch.nn.functional.interpolate(mode='bilinear', align_corners=False):
// src = (dst + 0.5) / s - 0.5, clamped at 0; upper neighbour clamped to n-1.
void UpsampleBilinear(const float* src, int C, int h, int w, int s, float* dst) {
  const int H = s * h, W = s * w;
  for (int c = 0; c < C; ++c)
    for (int Y = 0; Y < H; ++Y) {
      float sy = std::max((Y + 0.5f) / s - 0.5f, 0.0f);
      int y0 = int(sy), y1 = std::min(y0 + 1, h - 1);
      float ay = sy - y0;
      for (int X = 0; X < W; ++X) {
        float sx = std::max((X + 0.5f) / s - 0.5f, 0.0f);
        int x0 = int(sx), x1 = std::min(x0 + 1, w - 1);
        float ax = sx - x0;
        const float* P = src + size_t(c) * h * w;
        float top = P[y0 * w + x0] * (1 - ax) + P[y0 * w + x1] * ax;
        float bot = P[y1 * w + x0] * (1 - ax) + P[y1 * w + x1] * ax;
        dst[(size_t(c) * H + Y) * W + X] = top * (1 - ay) + bot * ay;
      }
    }
}

}  // namespace lkrl
