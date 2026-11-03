// SPDX-License-Identifier: GPL-3.0-or-later
// Independent RAW16 adapter and explicit FP16 output boundary, RawLens/Codex
// 2026.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>
namespace photo_rcd {
inline void validateRaw16(const uint8_t *data, size_t bytes, int sensorW,
                          int sensorH, int rowBytes, int pixelBytes, int left,
                          int top, int width, int height,
                          const std::array<float, 4> &black, float white) {
  if (!data || sensorW <= 0 || sensorH <= 0 || rowBytes <= 0 ||
      pixelBytes < 2 || left < 0 || top < 0 || width < 19 || height < 19 ||
      width > sensorW || height > sensorH || left > sensorW - width ||
      top > sensorH - height || uint64_t(width) * height > 16000000 ||
      width > 16384 || height > 16384 ||
      uint64_t(sensorW - 1) * pixelBytes + 2 > uint64_t(rowBytes) ||
      uint64_t(sensorH - 1) * rowBytes + uint64_t(sensorW - 1) * pixelBytes +
              2 >
          bytes ||
      uint64_t(sensorH - 1) * rowBytes + uint64_t(sensorW - 1) * pixelBytes +
              2 >
          0x7ffffffcULL)
    throw std::invalid_argument(
        "invalid RAW16 dimensions, crop, strides or capacity");
  if (!std::isfinite(white) || white <= 0 || white > 65535)
    throw std::invalid_argument("invalid RAW16 white level");
  for (float b : black)
    if (!std::isfinite(b) || b < 0 || b >= white)
      throw std::invalid_argument("invalid RAW16 black level");
}
inline std::vector<float>
normalizeRaw16(const uint8_t *data, size_t bytes, int sensorW, int sensorH,
               int rowBytes, int pixelBytes, int left, int top, int width,
               int height, const std::array<float, 4> &black, float white) {
  validateRaw16(data, bytes, sensorW, sensorH, rowBytes, pixelBytes, left, top,
                width, height, black, white);
  std::vector<float> result(size_t(width) * height);
  for (int y = 0; y < height; ++y)
    for (int x = 0; x < width; ++x) {
      const auto *sample =
          data + size_t(y + top) * rowBytes + size_t(x + left) * pixelBytes;
      const uint32_t code = uint32_t(sample[0]) | (uint32_t(sample[1]) << 8);
      int site = (((y + top) & 1) * 2) + ((x + left) & 1);
      result[size_t(y) * width + x] =
          std::max(0.f, std::min(1.f, (float(code) - black[site]) /
                                          (white - black[site])));
    }
  return result;
}
inline uint16_t halfRoundEven(float value) {
  // Explicit round-to-nearest, ties-to-even; final storage only, no FP16 math.
  uint32_t bits;
  std::memcpy(&bits, &value, 4);
  uint32_t sign = (bits >> 16) & 0x8000, exponent = (bits >> 23) & 255,
           mantissa = bits & 0x7fffff;
  if (exponent == 255)
    return uint16_t(sign | 0x7c00 | (mantissa ? 0x200 : 0));
  int e = int(exponent) - 127 + 15;
  if (e >= 31)
    return uint16_t(sign | 0x7c00);
  if (e <= 0) {
    if (e < -10)
      return uint16_t(sign);
    mantissa |= 0x800000;
    uint32_t shift = uint32_t(14 - e), truncated = mantissa >> shift,
             remainder = mantissa & ((1u << shift) - 1),
             mid = 1u << (shift - 1);
    return uint16_t(sign + truncated +
                    (remainder > mid || (remainder == mid && (truncated & 1))));
  }
  uint32_t truncated = (uint32_t(e) << 10) | (mantissa >> 13),
           remainder = mantissa & 0x1fff;
  return uint16_t(
      sign + truncated +
      (remainder > 0x1000 || (remainder == 0x1000 && (truncated & 1))));
}
inline float halfToFloat(uint16_t value) {
  uint32_t sign = (uint32_t(value) & 0x8000) << 16, e = (value >> 10) & 31,
           m = value & 1023, bits;
  if (!e) {
    if (!m)
      bits = sign;
    else {
      int exponent = -14;
      while (!(m & 1024)) {
        m <<= 1;
        --exponent;
      }
      bits = sign | (uint32_t(exponent + 127) << 23) | ((m & 1023) << 13);
    }
  } else
    bits = sign | (e == 31 ? 0x7f800000u : (e + 112) << 23) | (m << 13);
  float result;
  std::memcpy(&result, &bits, 4);
  return result;
}
inline void productCorrections(float *rgba, int width, int height, int left,
                               int top, const std::array<float, 3> &wb,
                               const std::vector<int> &shadeDims,
                               const std::vector<uint16_t> &shade) {
  for (float v : wb)
    if (!std::isfinite(v) || v <= 0)
      throw std::invalid_argument("invalid WB gains");
  if (!shadeDims.empty()) {
    if (shadeDims.size() != 6 || shadeDims[0] < 1 || shadeDims[1] < 1 ||
        size_t(shadeDims[0]) * shadeDims[1] > 65536 ||
        shadeDims[4] <= shadeDims[2] || shadeDims[5] <= shadeDims[3] ||
        shade.size() != size_t(shadeDims[0]) * shadeDims[1] * 4)
      throw std::invalid_argument("invalid shading metadata");
    for (auto v : shade)
      if (!std::isfinite(halfToFloat(v)) || halfToFloat(v) <= 0)
        throw std::invalid_argument("invalid shading gain");
  } else if (!shade.empty())
    throw std::invalid_argument("shading values without dimensions");
  const float minimum = std::min(wb[0], std::min(wb[1], wb[2]));
  for (int y = 0; y < height; ++y)
    for (int x = 0; x < width; ++x) {
      float *pixel = rgba + (size_t(y) * width + x) * 4;
      float blend = 0;
      for (int c = 0; c < 3; ++c) {
        float t =
            std::max(0.f, std::min(1.f, (pixel[c] - .70f) / (.99f - .70f)));
        blend = std::max(blend, t * t * (3 - 2 * t));
      }
      // Application extension: sensor-white protection, then post-demosaic LSC;
      // WB/CCM/exposure and profile/LUT remain in the separate grade stage.
      for (int c = 0; c < 3; ++c)
        pixel[c] = pixel[c] + (minimum / wb[c] - pixel[c]) * blend;
      if (!shadeDims.empty()) {
        float nx =
            std::max(0.f,
                     std::min(1.f, float(x + left - shadeDims[2]) /
                                       float(shadeDims[4] - shadeDims[2]))) *
            (shadeDims[1] - 1);
        float ny =
            std::max(0.f,
                     std::min(1.f, float(y + top - shadeDims[3]) /
                                       float(shadeDims[5] - shadeDims[3]))) *
            (shadeDims[0] - 1);
        int x0 = int(nx), y0 = int(ny), x1 = std::min(x0 + 1, shadeDims[1] - 1),
            y1 = std::min(y0 + 1, shadeDims[0] - 1);
        float gains[4];
        for (int q = 0; q < 4; ++q) {
          auto at = [&](int sx, int sy) {
            return halfToFloat(shade[(size_t(sy) * shadeDims[1] + sx) * 4 + q]);
          };
          float a = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * (nx - x0);
          float b = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * (nx - x0);
          gains[q] = a + (b - a) * (ny - y0);
        }
        pixel[0] *= gains[0];
        pixel[1] *= .5f * (gains[1] + gains[2]);
        pixel[2] *= gains[3];
      }
      for (int c = 0; c < 3; ++c)
        if (!std::isfinite(pixel[c]) || pixel[c] < 0 || pixel[c] > 65504)
          throw std::invalid_argument("FP16 output outside supported domain");
      pixel[3] = 1;
    }
}
} // namespace photo_rcd
