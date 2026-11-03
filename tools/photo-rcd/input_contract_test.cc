// SPDX-License-Identifier: GPL-3.0-or-later
// Meaningful boundary tests for the actual native RAW16/output adapter.
#include "photo_rcd_input.h"
#include <iostream>
#include <limits>
#include <stdexcept>
static void require(bool value, const char *message) {
  if (!value)
    throw std::runtime_error(message);
}
int main() {
  try {
    // All finite binary16 encodings must survive an exact round trip.
    size_t tested = 0;
    for (unsigned h = 0; h < 65536; ++h)
      if ((h & 0x7c00) != 0x7c00) {
        require(photo_rcd::halfRoundEven(photo_rcd::halfToFloat(uint16_t(h))) ==
                    h,
                "half round trip");
        ++tested;
      }
    // Compare independent compiler IEEE-half rounding at every adjacent-half
    // midpoint, and immediately below/above it, including the subnormal edge.
    for (unsigned h = 0; h < 0x7bff; ++h) {
      float a = photo_rcd::halfToFloat(h), b = photo_rcd::halfToFloat(h + 1),
            mid = (a + b) * .5f;
      for (float value :
           {std::nextafter(mid, -std::numeric_limits<float>::infinity()), mid,
            std::nextafter(mid, std::numeric_limits<float>::infinity())}) {
        _Float16 half = (_Float16)value;
        uint16_t expected;
        std::memcpy(&expected, &half, 2);
        require(photo_rcd::halfRoundEven(value) == expected,
                "half midpoint rounding");
      }
    }
    constexpr int sw = 24, sh = 25, stride = 104, pixel = 4;
    std::vector<uint8_t> bytes((sh - 1) * stride + (sw - 1) * pixel + 2, 0xa5);
    for (int y = 0; y < sh; ++y)
      for (int x = 0; x < sw; ++x) {
        unsigned code = 32 + (x + y * sw) * 9;
        auto offset = y * stride + x * pixel;
        bytes[offset] = code & 255;
        bytes[offset + 1] = code >> 8;
      }
    std::array<float, 4> blacks{{64, 80, 96, 112}};
    for (int top = 0; top < 2; ++top)
      for (int left = 0; left < 2; ++left) {
        auto normalized = photo_rcd::normalizeRaw16(bytes.data(), bytes.size(),
                                                    sw, sh, stride, pixel, left,
                                                    top, 21, 23, blacks, 4095);
        for (int y = 0; y < 23; ++y)
          for (int x = 0; x < 21; ++x) {
            int code = 32 + ((x + left) + (y + top) * sw) * 9,
                q = ((y + top) & 1) * 2 + ((x + left) & 1);
            float expected =
                std::max(0.f, std::min(1.f, (float(code) - blacks[q]) /
                                                (4095 - blacks[q])));
            require(normalized[y * 21 + x] == expected,
                    "RAW stride/crop/black phase");
          }
      }
    int rejected = 0;
    for (int kind = 0; kind < 7; ++kind) {
      auto black = blacks;
      float white = 4095;
      size_t capacity = bytes.size();
      int row = stride, w = 21, left = 0;
      if (kind == 0)
        capacity--;
      if (kind == 1)
        row = 20;
      if (kind == 2)
        black[1] = white;
      if (kind == 3)
        white = std::numeric_limits<float>::quiet_NaN();
      if (kind == 4)
        w = 18;
      if (kind == 5)
        left = 24;
      if (kind == 6)
        black[0] = -1;
      try {
        photo_rcd::normalizeRaw16(bytes.data(), capacity, sw, sh, row, pixel,
                                  left, 0, w, 23, black, white);
      } catch (const std::invalid_argument &) {
        ++rejected;
      }
    }
    require(rejected == 7, "invalid RAW metadata rejection");
    std::vector<float> rgba(19 * 19 * 4, .25f);
    for (size_t i = 3; i < rgba.size(); i += 4)
      rgba[i] = 1;
    photo_rcd::productCorrections(
        rgba.data(), 19, 19, 1, 1, {{2, 1, 4}}, {1, 1, 0, 0, 24, 25},
        std::vector<uint16_t>(4, photo_rcd::halfRoundEven(2)));
    for (size_t i = 0; i < rgba.size(); i += 4)
      require(rgba[i] == .5f && rgba[i + 1] == .5f && rgba[i + 2] == .5f,
              "shade must follow white protection");
    std::fill(rgba.begin(), rgba.end(), 1.f);
    photo_rcd::productCorrections(rgba.data(), 19, 19, 0, 0, {{2, 1, 4}}, {},
                                  {});
    require(rgba[0] == .5f && rgba[1] == 1 && rgba[2] == .25f,
            "sensor white neutral before WB");
    bool badShade = false;
    try {
      photo_rcd::productCorrections(rgba.data(), 19, 19, 0, 0, {{1, 1, 1}},
                                    {1, 1, 0, 0, 24, 25},
                                    std::vector<uint16_t>(4, 0x7c00));
    } catch (const std::invalid_argument &) {
      badShade = true;
    }
    require(badShade, "invalid shading gain");
    std::fill(rgba.begin(), rgba.end(), .1f);
    std::vector<uint16_t> map;
    for (int y = 0; y < 2; ++y)
      for (int x = 0; x < 2; ++x)
        for (float gain : {float(1 + x + y), float(2 + 2 * x), float(4 + 2 * y),
                           float(3 + x + 2 * y)})
          map.push_back(photo_rcd::halfRoundEven(gain));
    photo_rcd::productCorrections(rgba.data(), 19, 19, 12, 12, {{1, 1, 1}},
                                  {2, 2, 0, 0, 24, 24}, map);
    require(std::fabs(rgba[0] - .2f) < 1e-7f &&
                std::fabs(rgba[1] - .4f) < 1e-7f &&
                std::fabs(rgba[2] - .45f) < 1e-7f,
            "bilinear shading channel mapping / sensor origin");
    std::cout
        << "PASS: " << tested
        << " finite-half round trips, adjacent-half midpoint rounding, odd "
           "RAW16 crops/padded pixel and row strides, per-site black levels, "
           "seven invalid inputs, correction order and invalid shading\n";
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 2;
  }
}
