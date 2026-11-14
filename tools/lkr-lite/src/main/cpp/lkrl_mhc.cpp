// SPDX-License-Identifier: Apache-2.0
#include "lkrl_mhc.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace lkrl {

namespace {

constexpr float OUTPUT_CLAMP_HI = 4.0f;   // MhcReference.OUTPUT_CLAMP_HI

// MhcReference.Engine.halfGreen: bounded nonnegative green rounded exactly to
// binary16 storage (subnormal range by scaling, else round-half-even on bits).
float halfGreen(float v) {
  if (v < 0.00006103515625f) return std::nearbyint(v * 16777216.0f) / 16777216.0f;   // Kotlin round: ties to even
  uint32_t b;
  std::memcpy(&b, &v, 4);
  b = (b + 0xfffu + ((b >> 13) & 1u)) & 0xFFFFE000u;   // (b + 0xfff + ((b ushr 13) and 1)) and -8192
  float o;
  std::memcpy(&o, &b, 4);
  return o;
}

float clampf(float v, float lo, float hi) { return std::min(std::max(v, lo), hi); }

// Engine.runCoherent over fetchN / colorAt (both in crop coordinates, fetchN
// already mirrored + normalised + clamped to [0,1] like the Kotlin).
template <class Fetch, class Color, class Emit>
void RunCoherent(int width, int height, Fetch fetchN, Color colorAt, Emit emit) {
  auto mirrorX = [&](int x) { return x < 0 ? -x : (x >= width ? 2 * width - 2 - x : x); };
  auto mirrorY = [&](int y) { return y < 0 ? -y : (y >= height ? 2 * height - 2 - y : y); };
  std::vector<float> green(size_t(width) * height);
  for (int y = 0; y < height; ++y)
    for (int x = 0; x < width; ++x) {
      const float c = fetchN(x, y);
      float g;
      if (colorAt(x, y) == 1) {
        g = c;
      } else {
        const float n = fetchN(x, y - 1), south = fetchN(x, y + 1);
        const float w = fetchN(x - 1, y), e = fetchN(x + 1, y);
        const float ch = 2.0f * c - fetchN(x - 2, y) - fetchN(x + 2, y);
        const float cv = 2.0f * c - fetchN(x, y - 2) - fetchN(x, y + 2);
        const float gh = 0.5f * (w + e) + 0.25f * ch, gv = 0.5f * (n + south) + 0.25f * cv;
        const float dh = std::fabs(w - e) + 0.5f * std::fabs(ch), dv = std::fabs(n - south) + 0.5f * std::fabs(cv);
        const float hh = dh * dh + 1e-8f, vv = dv * dv + 1e-8f;
        g = std::max(0.0f, (gh * vv + gv * hh) / (hh + vv));
      }
      green[size_t(y) * width + x] = halfGreen(g);
    }
  auto gAt = [&](int x, int y) { return green[size_t(mirrorY(y)) * width + mirrorX(x)]; };
  for (int y = 0; y < height; ++y)
    for (int x = 0; x < width; ++x) {
      const int col = colorAt(x, y);
      const float c = fetchN(x, y);
      const float g = col == 1 ? c : gAt(x, y);
      float red, blue;
      if (col == 1) {
        const float w = fetchN(x - 1, y), e = fetchN(x + 1, y);
        const float n = fetchN(x, y - 1), south = fetchN(x, y + 1);
        const float gw = gAt(x - 1, y), ge = gAt(x + 1, y);
        const float gn = gAt(x, y - 1), gs = gAt(x, y + 1);
        const float ww = 0.001f + std::fabs(g - ge) + std::fabs(c - fetchN(x + 2, y));
        const float we = 0.001f + std::fabs(g - gw) + std::fabs(c - fetchN(x - 2, y));
        const float wn = 0.001f + std::fabs(g - gs) + std::fabs(c - fetchN(x, y + 2));
        const float ws = 0.001f + std::fabs(g - gn) + std::fabs(c - fetchN(x, y - 2));
        const float ew = g + (ww * (w - gw) + we * (e - ge)) / (ww + we);
        const float ns = g + (wn * (n - gn) + ws * (south - gs)) / (wn + ws);
        const bool redEW = colorAt(x + 1, y) == 0;
        red = redEW ? ew : ns;
        blue = redEW ? ns : ew;
      } else {
        const float nw = fetchN(x - 1, y - 1), ne = fetchN(x + 1, y - 1);
        const float sw = fetchN(x - 1, y + 1), se = fetchN(x + 1, y + 1);
        const float gnw = gAt(x - 1, y - 1), gne = gAt(x + 1, y - 1);
        const float gsw = gAt(x - 1, y + 1), gse = gAt(x + 1, y + 1);
        const float wnw = 1.0f / (0.001f + std::fabs(g - gnw) + std::fabs(nw - se));
        const float wse = 1.0f / (0.001f + std::fabs(g - gse) + std::fabs(nw - se));
        const float wne = 1.0f / (0.001f + std::fabs(g - gne) + std::fabs(ne - sw));
        const float wsw = 1.0f / (0.001f + std::fabs(g - gsw) + std::fabs(ne - sw));
        const float opposite =
            g + (wnw * (nw - gnw) + wne * (ne - gne) + wsw * (sw - gsw) + wse * (se - gse)) / (wnw + wne + wsw + wse);
        red = col == 0 ? c : opposite;
        blue = col == 2 ? c : opposite;
      }
      emit(x, y, clampf(red, 0.0f, OUTPUT_CLAMP_HI), clampf(g, 0.0f, OUTPUT_CLAMP_HI),
           clampf(blue, 0.0f, OUTPUT_CLAMP_HI));
    }
}

}  // namespace

void MhcCoherentCodes(const uint16_t* codes, int /*sensor_w*/, int /*sensor_h*/, int pitch, int left, int top,
                      int width, int height, const int channels[4], const float levels[4], float white, float* rgb) {
  float invRange[4], black[4];
  for (int i = 0; i < 4; ++i) {
    invRange[i] = 1.0f / std::max(1.0f, white - levels[i]);
    black[i] = levels[i];
  }
  auto mirrorX = [&](int x) { return x < 0 ? -x : (x >= width ? 2 * width - 2 - x : x); };
  auto mirrorY = [&](int y) { return y < 0 ? -y : (y >= height ? 2 * height - 2 - y : y); };
  auto quadOf = [&](int x, int y) { return (((y + top) & 1) << 1) | ((x + left) & 1); };
  auto fetchN = [&](int x, int y) {
    const int cx = mirrorX(x), cy = mirrorY(y);
    const int q = quadOf(cx, cy);
    const float code = float(codes[size_t(cy + top) * pitch + (cx + left)]);
    return clampf((code - black[q]) * invRange[q], 0.0f, 1.0f);
  };
  auto colorAt = [&](int x, int y) {
    const int q = quadOf(x, y);
    if (channels[0] == q) return 0;
    if (channels[1] == q || channels[2] == q) return 1;
    return 2;
  };
  RunCoherent(width, height, fetchN, colorAt, [&](int x, int y, float r, float g, float b) {
    float* o = rgb + (size_t(y) * width + x) * 3;
    o[0] = r; o[1] = g; o[2] = b;
  });
}

void MhcCoherentRggb(const float* raw, int h, int w, float* rgb) {
  const size_t HW = size_t(h) * w;
  auto mir = [](int i, int n) { return i < 0 ? -i : (i >= n ? 2 * n - 2 - i : i); };
  auto fetchN = [&](int x, int y) { return clampf(raw[size_t(mir(y, h)) * w + mir(x, w)], 0.0f, 1.0f); };
  auto colorAt = [](int x, int y) {
    const bool ex = (x & 1) == 0, ey = (y & 1) == 0;
    return (ex && ey) ? 0 : ((!ex && !ey) ? 2 : 1);
  };
  RunCoherent(w, h, fetchN, colorAt, [&](int x, int y, float r, float g, float b) {
    const size_t i = size_t(y) * w + x;
    rgb[i] = r; rgb[HW + i] = g; rgb[2 * HW + i] = b;
  });
}

}  // namespace lkrl
