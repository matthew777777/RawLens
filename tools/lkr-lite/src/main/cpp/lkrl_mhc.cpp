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

namespace lkrl {

// Edge-directed start image (2026-11-19 test, LKRL_RCD=1): Ratio Corrected Demosaicing (L. Sanz
// Rodriguez, RCD v2.3 idea) on a normalised RGGB tile. Green at R/B from four ratio-corrected
// directional estimates blended by inverse gradients and a smoothed V/H discriminator; R/B from
// colour differences along the diagonals (at B/R) and the axes (at G). Borders (4 px) keep MHC.
// Interpolates along edges like AMaZE: thin near-axis lines get no lattice-period colour.
void RcdRggb(const float* raw, int h, int w, float* rgb) {
  MhcCoherentRggb(raw, h, w, rgb);
  if (h < 12 || w < 12) return;
  const size_t n = size_t(h) * w;
  const float eps = 1e-5f, epssq = 1e-10f;
  auto col = [](int y, int x) { return (y & 1) == 0 ? ((x & 1) == 0 ? 0 : 1) : ((x & 1) == 0 ? 1 : 2); };
  std::vector<float> cfa(raw, raw + n), vh(n, 0.5f), pq(n, 0.5f), lpf(n, 0.0f);
  std::vector<float> R(rgb, rgb + n), G(rgb + n, rgb + 2 * n), B(rgb + 2 * n, rgb + 3 * n);
  float* C[3] = {R.data(), G.data(), B.data()};
  const int W = w, W2 = 2 * w, W3 = 3 * w, W4 = 4 * w;
  // 1. V / H discriminator from 1-D high-pass energy (3 taps along the axis).
  for (int y = 3; y < h - 3; ++y)
    for (int x = 3; x < w - 3; ++x) {
      const size_t i = size_t(y) * w + x;
      float vs = epssq, hs = epssq;
      for (int k = -1; k <= 1; ++k) {
        const size_t jv = i + k * W, jh = i + k;
        float a = cfa[jv - W] - cfa[jv + W], b = 2.0f * cfa[jv] - cfa[jv - W2] - cfa[jv + W2];
        vs += a * a + b * b;
        float c = cfa[jh - 1] - cfa[jh + 1], d = 2.0f * cfa[jh] - cfa[jh - 2] - cfa[jh + 2];
        hs += c * c + d * d;
      }
      vh[i] = vs / (vs + hs);   // large = vertical variation -> trust the horizontal estimate
    }
  // 2. Low-pass of the CFA (luminance-like) for the ratio correction.
  for (int y = 1; y < h - 1; ++y)
    for (int x = 1; x < w - 1; ++x) {
      const size_t i = size_t(y) * w + x;
      lpf[i] = cfa[i] + 0.5f * (cfa[i - W] + cfa[i + W] + cfa[i - 1] + cfa[i + 1]) +
               0.25f * (cfa[i - W - 1] + cfa[i - W + 1] + cfa[i + W - 1] + cfa[i + W + 1]);
    }
  auto disc = [&](const std::vector<float>& d, size_t i, size_t a, size_t b, size_t c, size_t e) {
    const float cen = d[i], nb = 0.25f * (d[a] + d[b] + d[c] + d[e]);
    return std::fabs(0.5f - cen) < std::fabs(0.5f - nb) ? nb : cen;
  };
  // 3. Green at R and B.
  for (int y = 4; y < h - 4; ++y)
    for (int x = 4 + ((col(y, 4) == 1) ? 1 : 0); x < w - 4; x += 2) {
      const size_t i = size_t(y) * w + x;
      const float c0 = cfa[i];
      const float Ng = eps + std::fabs(cfa[i - W] - cfa[i + W]) + std::fabs(c0 - cfa[i - W2]) + std::fabs(cfa[i - W] - cfa[i - W3]) + std::fabs(cfa[i - W2] - cfa[i - W4]);
      const float Sg = eps + std::fabs(cfa[i - W] - cfa[i + W]) + std::fabs(c0 - cfa[i + W2]) + std::fabs(cfa[i + W] - cfa[i + W3]) + std::fabs(cfa[i + W2] - cfa[i + W4]);
      const float Wg = eps + std::fabs(cfa[i - 1] - cfa[i + 1]) + std::fabs(c0 - cfa[i - 2]) + std::fabs(cfa[i - 1] - cfa[i - 3]) + std::fabs(cfa[i - 2] - cfa[i - 4]);
      const float Eg = eps + std::fabs(cfa[i - 1] - cfa[i + 1]) + std::fabs(c0 - cfa[i + 2]) + std::fabs(cfa[i + 1] - cfa[i + 3]) + std::fabs(cfa[i + 2] - cfa[i + 4]);
      const float l0 = lpf[i];
      const float Ne = cfa[i - W] * 2.0f * l0 / (eps + l0 + lpf[i - W2]);
      const float Se = cfa[i + W] * 2.0f * l0 / (eps + l0 + lpf[i + W2]);
      const float We = cfa[i - 1] * 2.0f * l0 / (eps + l0 + lpf[i - 2]);
      const float Ee = cfa[i + 1] * 2.0f * l0 / (eps + l0 + lpf[i + 2]);
      const float Ve = (Sg * Ne + Ng * Se) / (Ng + Sg), He = (Wg * Ee + Eg * We) / (Eg + Wg);
      const float d = disc(vh, i, i - W - 1, i - W + 1, i + W - 1, i + W + 1);
      G[i] = std::max(0.0f, d * He + (1.0f - d) * Ve);
    }
  // 4. Diagonal discriminator, then R at B and B at R from diagonal colour differences.
  for (int y = 3; y < h - 3; ++y)
    for (int x = 3; x < w - 3; ++x) {
      const size_t i = size_t(y) * w + x;
      float ps = epssq, qs = epssq;
      for (int k = -1; k <= 1; ++k) {
        const size_t jp = i + k * (W + 1), jq = i + k * (W - 1);
        float a = cfa[jp - W - 1] - cfa[jp + W + 1], b = 2.0f * cfa[jp] - cfa[jp - 2 * W - 2] - cfa[jp + 2 * W + 2];
        ps += a * a + b * b;
        float c = cfa[jq - W + 1] - cfa[jq + W - 1], e = 2.0f * cfa[jq] - cfa[jq - 2 * W + 2] - cfa[jq + 2 * W - 2];
        qs += c * c + e * e;
      }
      pq[i] = ps / (ps + qs);   // large = variation along NW-SE -> trust NE-SW
    }
  for (int y = 4; y < h - 4; ++y)
    for (int x = 4; x < w - 4; ++x) {
      const int cc = col(y, x);
      if (cc == 1) continue;
      const size_t i = size_t(y) * w + x;
      float* X = C[2 - cc];   // the missing one of R / B
      const size_t nw = i - W - 1, ne = i - W + 1, sw = i + W - 1, se = i + W + 1;
      const float NWg = eps + std::fabs(X[nw] - X[se]) + std::fabs(X[nw] - X[nw - W - 1]) + std::fabs(G[i] - G[nw - W - 1]);
      const float SEg = eps + std::fabs(X[nw] - X[se]) + std::fabs(X[se] - X[se + W + 1]) + std::fabs(G[i] - G[se + W + 1]);
      const float NEg = eps + std::fabs(X[ne] - X[sw]) + std::fabs(X[ne] - X[ne - W + 1]) + std::fabs(G[i] - G[ne - W + 1]);
      const float SWg = eps + std::fabs(X[ne] - X[sw]) + std::fabs(X[sw] - X[sw + W - 1]) + std::fabs(G[i] - G[sw + W - 1]);
      const float NWe = X[nw] - G[nw], SEe = X[se] - G[se], NEe = X[ne] - G[ne], SWe = X[sw] - G[sw];
      const float Pe = (NWg * SEe + SEg * NWe) / (NWg + SEg), Qe = (NEg * SWe + SWg * NEe) / (NEg + SWg);
      const float d = disc(pq, i, i - W - 1, i - W + 1, i + W - 1, i + W + 1);
      X[i] = std::max(0.0f, G[i] + d * Qe + (1.0f - d) * Pe);
    }
  // 5. R and B at green from axis colour differences.
  for (int y = 4; y < h - 4; ++y)
    for (int x = 4; x < w - 4; ++x) {
      if (col(y, x) != 1) continue;
      const size_t i = size_t(y) * w + x;
      const float d = disc(vh, i, i - W - 1, i - W + 1, i + W - 1, i + W + 1);
      for (int c = 0; c < 3; c += 2) {
        float* X = C[c];
        const float Ng = eps + std::fabs(G[i] - G[i - W2]) + std::fabs(X[i - W] - X[i + W]) + std::fabs(X[i - W] - X[i - W3]);
        const float Sg = eps + std::fabs(G[i] - G[i + W2]) + std::fabs(X[i - W] - X[i + W]) + std::fabs(X[i + W] - X[i + W3]);
        const float Wg = eps + std::fabs(G[i] - G[i - 2]) + std::fabs(X[i - 1] - X[i + 1]) + std::fabs(X[i - 1] - X[i - 3]);
        const float Eg = eps + std::fabs(G[i] - G[i + 2]) + std::fabs(X[i - 1] - X[i + 1]) + std::fabs(X[i + 1] - X[i + 3]);
        const float Ne = X[i - W] - G[i - W], Se = X[i + W] - G[i + W], We = X[i - 1] - G[i - 1], Ee = X[i + 1] - G[i + 1];
        const float Ve = (Ng * Se + Sg * Ne) / (Ng + Sg), He = (Eg * We + Wg * Ee) / (Eg + Wg);
        X[i] = std::max(0.0f, G[i] + d * He + (1.0f - d) * Ve);
      }
    }
  std::copy(R.begin(), R.end(), rgb);
  std::copy(G.begin(), G.end(), rgb + n);
  std::copy(B.begin(), B.end(), rgb + 2 * n);
}

}  // namespace lkrl
