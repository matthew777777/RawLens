// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "lkr_denoise.h"

namespace lkr {
// Preserve the first pass's broad opponent colour; the phase prediction is
// allowed to change fine chroma. A small noise-guided filter of the correction suppresses phase
// interleaving patterns. This is a conservative frequency constraint, not a
// white-balance correction or proof that the reference colour is ground truth.
inline bool GuardPhotoChroma(const uint16_t* reference, uint16_t* candidate,
                            int w, int h, const std::array<float, 3>& wb,
                            float a, float b, int band = 128) {
  if (!reference || !candidate || w <= 0 || h <= 0 || band < 1 ||
      !std::isfinite(a) || !std::isfinite(b) || a < 0 || b < 0) return false;
  for (float gain : wb) if (!std::isfinite(gain) || gain <= 0) return false;
  constexpr int radius = 4, passes = 3, reach = radius * passes;
  constexpr float wr = .2126f, wg = .7152f, wb_weight = .0722f;
  std::vector<uint16_t> result(candidate, candidate + size_t(w) * h * 4);
  auto smooth = [&](int x, int y, float* uv) {
    const size_t center = (size_t(y) * w + x) * 4;
    double su = 0, sv = 0, total = 0;
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx) {
      const int sy = std::clamp(y+dy, 0, h-1), sx = std::clamp(x+dx, 0, w-1);
      const size_t i = (size_t(sy) * w + sx) * 4;
      double distance = 0;
      for (int c = 0; c < 3; ++c) {
        const double p = ReadHalf(reference[center+c]), q = ReadHalf(reference[i+c]);
        const double variance = a * .5 * (std::max(p, 0.) + std::max(q, 0.)) + b + 1e-10;
        distance += (p-q)*(p-q)/variance;
      }
      const double weight = (2-std::abs(dx))*(2-std::abs(dy))/(1+distance/8);
      // Filter only the proposed correction. Filtering absolute chroma
      // damages real detail even when both predictions agree exactly.
      const double r = wb[0]*(ReadHalf(candidate[i])-ReadHalf(reference[i]));
      const double g = wb[1]*(ReadHalf(candidate[i+1])-ReadHalf(reference[i+1]));
      const double blue = wb[2]*(ReadHalf(candidate[i+2])-ReadHalf(reference[i+2]));
      su += weight*(r-g); sv += weight*(blue-g); total += weight;
    }
    uv[0] = float(su/total); uv[1] = float(sv/total);
  };
  // Band halos cover all three radius-4 box passes. Scratch is bounded by
  // image width and band height rather than full-resolution float images.
  for (int y0 = 0; y0 < h; y0 += band) {
    const int y1 = std::min(h, y0+band), top = std::max(0,y0-reach), bottom = std::min(h,y1+reach);
    const int rows = bottom-top;
    std::vector<float> delta(size_t(rows)*w*2), scratch(delta.size());
    std::vector<float> filtered(size_t(y1-y0)*w*2);
    for (int y = top; y < bottom; ++y) for (int x = 0; x < w; ++x) {
      float uv[2]; smooth(x,y,uv);
      const size_t j = (size_t(y-top)*w+x)*2;
      delta[j] = uv[0];
      delta[j+1] = uv[1];
      if (!std::isfinite(delta[j]) || !std::isfinite(delta[j+1])) return false;
      if (y >= y0 && y < y1) {
        const size_t k = (size_t(y-y0)*w+x)*2;
        filtered[k] = uv[0]; filtered[k+1] = uv[1];
      }
    }
    for (int pass = 0; pass < passes; ++pass) {
      for (int y = 0; y < rows; ++y) for (int c = 0; c < 2; ++c) {
        double sum = 0;
        for (int dx = -radius; dx <= radius; ++dx) sum += delta[(size_t(y)*w+std::clamp(dx,0,w-1))*2+c];
        for (int x = 0; x < w; ++x) {
          scratch[(size_t(y)*w+x)*2+c] = float(sum/(2*radius+1));
          sum += delta[(size_t(y)*w+std::min(w-1,x+radius+1))*2+c] -
                 delta[(size_t(y)*w+std::max(0,x-radius))*2+c];
        }
      }
      for (int x = 0; x < w; ++x) for (int c = 0; c < 2; ++c) {
        double sum = 0;
        for (int dy = -radius; dy <= radius; ++dy) sum += scratch[(size_t(std::clamp(dy,0,rows-1))*w+x)*2+c];
        for (int y = 0; y < rows; ++y) {
          delta[(size_t(y)*w+x)*2+c] = float(sum/(2*radius+1));
          sum += scratch[(size_t(std::min(rows-1,y+radius+1))*w+x)*2+c] -
                 scratch[(size_t(std::max(0,y-radius))*w+x)*2+c];
        }
      }
    }
    for (int y = y0; y < y1; ++y) for (int x = 0; x < w; ++x) {
      const size_t i = (size_t(y)*w+x)*4, j = (size_t(y-top)*w+x)*2, k = (size_t(y-y0)*w+x)*2;
      const float correction_u = filtered[k]-delta[j], correction_v = filtered[k+1]-delta[j+1];
      if (correction_u == 0 && correction_v == 0 &&
          candidate[i] == reference[i] && candidate[i+1] == reference[i+1] &&
          candidate[i+2] == reference[i+2]) continue;
      const float reference_g = wb[1]*ReadHalf(reference[i+1]);
      const float u = wb[0]*ReadHalf(reference[i])-reference_g+correction_u;
      const float v = wb[2]*ReadHalf(reference[i+2])-reference_g+correction_v;
      const float luminance = wr*wb[0]*ReadHalf(candidate[i]) + wg*wb[1]*ReadHalf(candidate[i+1]) +
                              wb_weight*wb[2]*ReadHalf(candidate[i+2]);
      const float g = luminance-wr*u-wb_weight*v;
      const float rgb[3] = {(g+u)/wb[0],g/wb[1],(g+v)/wb[2]};
      for (int c = 0; c < 3; ++c) {
        if (!std::isfinite(rgb[c]) || std::abs(rgb[c]) > 65504.f) return false;
        result[i+c] = WriteHalf(rgb[c]);
      }
    }
  }
  std::copy(result.begin(),result.end(),candidate);
  return true;
}
} // namespace lkr
