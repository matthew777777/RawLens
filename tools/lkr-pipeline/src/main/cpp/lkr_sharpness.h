// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace lkr {
// BurstSharpness.measure, preserving its float subtraction and row-major
// double accumulation. No thumbnail, frame copy, or altered selection rule.
inline double RawSharpness(const uint16_t* raw, int w, int h, int stride,
                           const int cfa[4], const float black[4]) {
  double grad = 0, level = 0;
  auto value = [&](int x, int y) {
    return float(raw[size_t(y) * stride + x]) - black[((y & 1) << 1) | (x & 1)];
  };
  for (int y = h / 4; y < h - h / 4 - 1; ++y)
    for (int x = w / 4; x < w - w / 4 - 1; ++x) {
      if (cfa[((y & 1) << 1) | (x & 1)] != 1) continue;
      const float c = value(x, y);
      const float d = std::fabs(value(x + 1, y + 1) - c) + std::fabs(value(x - 1, y + 1) - c);
      grad += d;
      level += c;
    }
  return level > 0 ? grad / level : 0;
}
}  // namespace lkr
