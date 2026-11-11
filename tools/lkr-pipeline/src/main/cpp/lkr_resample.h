// SPDX-License-Identifier: Apache-2.0
#pragma once
// Exact area-average resample of interleaved RGB (zoom 1.5x: the x2 solve of
// the centre crop, 5440x4080, down to the sensor size 4080x3060). Each output
// pixel averages the source area it covers with fractional edge weights, so
// it keeps the solved detail up to the output Nyquist without aliasing.
// Separable (rows, then columns).

#include <algorithm>
#include <cmath>
#include <vector>

namespace lkr {

namespace detail {
// taps[o] = list of (source index, weight), weights summing to 1.
inline void AreaTaps(int n_src, int n_dst, std::vector<int>* first, std::vector<std::vector<float>>* w) {
  const double r = double(n_src) / n_dst;
  first->resize(size_t(n_dst));
  w->assign(size_t(n_dst), {});
  for (int o = 0; o < n_dst; ++o) {
    const double a = o * r, b = (o + 1) * r;
    const int i0 = int(std::floor(a)), i1 = std::min(n_src - 1, int(std::ceil(b)) - 1);
    (*first)[size_t(o)] = i0;
    for (int i = i0; i <= i1; ++i) {
      const double lo = std::max(a, double(i)), hi = std::min(b, double(i + 1));
      (*w)[size_t(o)].push_back(float((hi - lo) / r));
    }
  }
}
}  // namespace detail

inline void AreaResampleRgb(const std::vector<float>& src, int sw, int sh, int dw, int dh, std::vector<float>* dst) {
  std::vector<int> fx, fy;
  std::vector<std::vector<float>> wx, wy;
  detail::AreaTaps(sw, dw, &fx, &wx);
  detail::AreaTaps(sh, dh, &fy, &wy);
  std::vector<float> tmp(size_t(dw) * sh * 3);
  for (int y = 0; y < sh; ++y) {
    const float* s = src.data() + size_t(y) * sw * 3;
    float* t = tmp.data() + size_t(y) * dw * 3;
    for (int o = 0; o < dw; ++o) {
      float acc[3] = {0, 0, 0};
      const auto& ww = wx[size_t(o)];
      for (size_t k = 0; k < ww.size(); ++k) {
        const float* p = s + size_t(fx[size_t(o)] + int(k)) * 3;
        acc[0] += ww[k] * p[0]; acc[1] += ww[k] * p[1]; acc[2] += ww[k] * p[2];
      }
      t[o * 3] = acc[0]; t[o * 3 + 1] = acc[1]; t[o * 3 + 2] = acc[2];
    }
  }
  dst->assign(size_t(dw) * dh * 3, 0.0f);
  for (int o = 0; o < dh; ++o) {
    const auto& ww = wy[size_t(o)];
    float* d = dst->data() + size_t(o) * dw * 3;
    for (size_t k = 0; k < ww.size(); ++k) {
      const float* t = tmp.data() + size_t(fy[size_t(o)] + int(k)) * dw * 3;
      for (int i = 0; i < dw * 3; ++i) d[i] += ww[k] * t[i];
    }
  }
}

}  // namespace lkr
