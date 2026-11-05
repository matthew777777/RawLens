// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <vector>

namespace lkr {
inline float ReadHalf(uint16_t bits) {
  _Float16 value;
  std::memcpy(&value, &bits, 2);
  return float(value);
}
inline uint16_t WriteHalf(float value) {
  const _Float16 half = _Float16(value);
  uint16_t bits;
  std::memcpy(&bits, &half, 2);
  return bits;
}
// Clamped extension preserves the 2x2 pixel-unshuffle phase because every
// tile starts on an even coordinate. 32px halo exceeds the 24px receptive
// radius of the shipped 12-layer, half-resolution convolution network.
using DenoiseTile = std::function<bool(const float*, float*, int, int)>;
inline bool DenoiseRgbaHalf(uint16_t* rgba, int w, int h, float strength,
                            const DenoiseTile& denoise, int tile = 512) {
  if (!rgba || w <= 0 || h <= 0 || !std::isfinite(strength) || strength < 0 || strength > 1 ||
      tile < 66 || tile % 2) return false;
  if (strength == 0) return true;
  constexpr int halo = 32;
  const int step = tile - 2 * halo;
  const size_t n = size_t(tile) * tile;
  // One half-float output copy gives atomic commit without a full float RGB image.
  std::vector<uint16_t> result(rgba, rgba + size_t(w) * h * 4);
  std::vector<float> in(3 * n), out(3 * n);
  for (int y0 = 0; y0 < h; y0 += step) for (int x0 = 0; x0 < w; x0 += step) {
    for (int y = 0; y < tile; ++y) for (int x = 0; x < tile; ++x) {
      const int sy = std::clamp(y0 + y - halo, 0, h - 1);
      const int sx = std::clamp(x0 + x - halo, 0, w - 1);
      for (int c = 0; c < 3; ++c) {
        const float v = ReadHalf(rgba[(size_t(sy) * w + sx) * 4 + c]);
        if (!std::isfinite(v)) return false;
        in[c * n + size_t(y) * tile + x] = v;
      }
    }
    if (!denoise(in.data(), out.data(), tile, tile)) return false;
    for (int y = 0; y < std::min(step, h - y0); ++y)
      for (int x = 0; x < std::min(step, w - x0); ++x)
        for (int c = 0; c < 3; ++c) {
          const size_t dst = (size_t(y0 + y) * w + x0 + x) * 4 + c;
          const float v = out[c * n + size_t(y + halo) * tile + x + halo];
          if (!std::isfinite(v)) return false;
          const float mixed = ReadHalf(rgba[dst]) * (1 - strength) + v * strength;
          if (!std::isfinite(mixed) || std::abs(mixed) > 65504.f) return false;
          result[dst] = WriteHalf(mixed);
        }
  }
  std::memcpy(rgba, result.data(), result.size() * sizeof(uint16_t));
  return true;
}
// Decorrelation adapter: denoise each stride-spaced RGB phase independently,
// then transfer only its WB-camera chroma to the first pass. Luminance detail
// comes from baseline by default; experimental luma in [0,1] transfers that
// fraction of phase luminance too. Keep staging so failure leaves it untouched.
inline bool DenoiseChromaRgbaHalf(const uint16_t* source, uint16_t* baseline,
                                  int w, int h, const std::array<float, 3>& wb,
                                  const DenoiseTile& denoise, int stride = 4, int tile = 512, float luma = 0.f) {
  if (!std::isfinite(luma) || luma < 0 || luma > 1) return false;
  if (!source || !baseline || w <= 0 || h <= 0 || (stride != 2 && stride != 4) || tile < 66 || tile % 2) return false;
  for (float gain : wb) if (!std::isfinite(gain) || gain <= 0) return false;
  constexpr int halo = 32;
  constexpr float weights[3] = {.2126f, .7152f, .0722f};
  const int step = tile - 2 * halo;
  const size_t n = size_t(tile) * tile;
  std::vector<uint16_t> result(baseline, baseline + size_t(w) * h * 4);
  std::vector<float> in(3 * n), out(3 * n);
  for (int py = 0; py < std::min(stride, h); ++py)
    for (int px = 0; px < std::min(stride, w); ++px) {
      const int sw = (w - px + stride - 1) / stride;
      const int sh = (h - py + stride - 1) / stride;
      for (int y0 = 0; y0 < sh; y0 += step) for (int x0 = 0; x0 < sw; x0 += step) {
        for (int y = 0; y < tile; ++y) for (int x = 0; x < tile; ++x) {
          const int sy = std::clamp(y0 + y - halo, 0, sh - 1) * stride + py;
          const int sx = std::clamp(x0 + x - halo, 0, sw - 1) * stride + px;
          for (int c = 0; c < 3; ++c) {
            const float v = ReadHalf(source[(size_t(sy) * w + sx) * 4 + c]);
            if (!std::isfinite(v)) return false;
            in[c * n + size_t(y) * tile + x] = v;
          }
        }
        if (!denoise(in.data(), out.data(), tile, tile)) return false;
        for (int y = 0; y < std::min(step, sh - y0); ++y)
          for (int x = 0; x < std::min(step, sw - x0); ++x) {
            const size_t i = (size_t((y0 + y) * stride + py) * w + (x0 + x) * stride + px) * 4;
            const size_t j = size_t(y + halo) * tile + x + halo;
            float base_y = 0, phase_y = 0;
            for (int c = 0; c < 3; ++c) {
              const float b = ReadHalf(baseline[i+c]), p = out[c*n+j];
              if (!std::isfinite(b) || !std::isfinite(p)) return false;
              base_y += weights[c] * wb[c] * b;
              phase_y += weights[c] * wb[c] * p;
            }
            for (int c = 0; c < 3; ++c) {
              const float v = out[c*n+j] + (1-luma) * (base_y - phase_y) / wb[c];
              if (!std::isfinite(v) || std::abs(v) > 65504.f) return false;
              result[i+c] = WriteHalf(v);
            }
          }
      }
    }
  std::memcpy(baseline, result.data(), result.size() * sizeof(uint16_t));
  return true;
}
} // namespace lkr
