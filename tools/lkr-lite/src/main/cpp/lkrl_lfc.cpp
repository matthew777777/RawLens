// SPDX-License-Identifier: Apache-2.0
// Low-frequency colour noise (2026-11-20, night look). The 8 x 8 DCT colour shrink cannot see colour
// blotches larger than its block: on night bursts (IMG_20260921_194618_520) the road kept purple / green
// mottle at ~8-30 px (blotch-band colour noise -17 % for k 2 -> 12). Run once on the finished image:
//  1. quarter resolution (box means): Y, U = R - Y, V = B - Y (white-balanced, Y = (R + 2G + B) / 4);
//  2. colour refit to luma (guided filter, He et al. 2010) over 9 x 9 low-res px: Cg = A Y + B, so the
//     colour follows luma edges (no bleeding across them);
//  3. the residual Rl = C - Cg, smoothed (blotch band), shrunk by a Wiener weight w = e / (e + k s2):
//     e = local energy, s2 = its noise variance MEASURED on this image (low percentile of local variance:
//     flat areas are pure noise, texture only raises it). Real colour structure (e >> s2) is kept, so
//     clean daylight images are hardly changed;
//  4. full resolution: C += up((w - 1) Rs). Luma is never changed; fine colour detail stays.
#include "lkrl_lfc.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace lkrl {
namespace {

// Separable box mean, radius r, clamp-to-edge.
void Box(const std::vector<float>& in, std::vector<float>* out, int h, int w, int r) {
  std::vector<float> tmp(in.size());
  const float inv = 1.0f / float(2 * r + 1);
  for (int y = 0; y < h; ++y) {
    const float* row = &in[size_t(y) * w];
    float s = 0;
    for (int t = -r; t <= r; ++t) s += row[std::clamp(t, 0, w - 1)];
    for (int x = 0; x < w; ++x) {
      tmp[size_t(y) * w + x] = s * inv;
      s += row[std::min(x + r + 1, w - 1)] - row[std::max(x - r, 0)];
    }
  }
  out->resize(in.size());
  for (int x = 0; x < w; ++x) {
    float s = 0;
    for (int t = -r; t <= r; ++t) s += tmp[size_t(std::clamp(t, 0, h - 1)) * w + x];
    for (int y = 0; y < h; ++y) {
      (*out)[size_t(y) * w + x] = s * inv;
      s += tmp[size_t(std::min(y + r + 1, h - 1)) * w + x] - tmp[size_t(std::max(y - r, 0)) * w + x];
    }
  }
}

float Percentile(std::vector<float> v, float q) {
  if (v.empty()) return 0.0f;
  const size_t k = size_t(q * float(v.size() - 1));
  std::nth_element(v.begin(), v.begin() + k, v.end());
  return v[k];
}

template <class F>
void Rows(int h, F f) {
  const int n = std::max(1, std::min(8, int(std::thread::hardware_concurrency())));
  std::vector<std::thread> th;
  for (int t = 0; t < n; ++t) th.emplace_back([&, t] { for (int y = t; y < h; y += n) f(y); });
  for (auto& x : th) x.join();
}

}  // namespace

void LowFreqChroma(float* rgb, int w, int h, const float wb[3], float k, float na, float nb, int scale, LfcStats* st) {
  // blotches are ~2-8 sensor px: at 2x output they are twice as many output px, so the low-res grid follows
  const int f = 4 * std::max(scale, 1);
  constexpr int r = 4;
  const int lw = (w + f - 1) / f, lh = (h + f - 1) / f;
  if (lw < 2 * r + 2 || lh < 2 * r + 2 || k <= 0.0f) return;
  const size_t ln = size_t(lw) * lh;
  std::vector<float> Yl(ln, 0.0f), Ul(ln, 0.0f), Vl(ln, 0.0f);
  // 1. quarter-resolution means (partial edge blocks averaged over their own pixels)
  Rows(lh, [&](int ly) {
    for (int lx = 0; lx < lw; ++lx) {
      double sy = 0, su = 0, sv = 0; int n = 0;
      for (int y = ly * f; y < std::min(ly * f + f, h); ++y)
        for (int x = lx * f; x < std::min(lx * f + f, w); ++x) {
          const float* p = rgb + (size_t(y) * w + x) * 3;
          const float R = p[0] * wb[0], G = p[1] * wb[1], B = p[2] * wb[2];
          const float Y = 0.25f * R + 0.5f * G + 0.25f * B;
          sy += Y; su += R - Y; sv += B - Y; ++n;
        }
      const size_t i = size_t(ly) * lw + lx;
      Yl[i] = float(sy / n); Ul[i] = float(su / n); Vl[i] = float(sv / n);
    }
  });
  // 2. guided refit; eps = luma noise variance of the window, measured (2nd percentile of local variance)
  std::vector<float> my, yy(ln), vy(ln);
  Box(Yl, &my, lh, lw, r);
  for (size_t i = 0; i < ln; ++i) yy[i] = Yl[i] * Yl[i];
  Box(yy, &vy, lh, lw, r);
  for (size_t i = 0; i < ln; ++i) vy[i] = std::max(vy[i] - my[i] * my[i], 0.0f);
  // chi^2(80)/80 at the 2nd percentile ~ 0.68: the lowest local variances under-read pure noise
  constexpr float kQ = 0.02f, kChi = 0.68f;
  // Noise follows brightness (night: shot noise dominates): every measured variance is taken relative to
  // the noise model m = a Y + b at that place, so the flattest blocks calibrate the scale and the level
  // then follows Y (the darkest-flattest blocks alone under-read a brighter road).
  std::vector<float> mdl(ln), ratio;
  for (size_t i = 0; i < ln; ++i) mdl[i] = std::max(na * std::max(my[i], 0.0f) + nb, 1e-12f);
  // Measured only where noise is real: not near clip (blown areas are flat, variance 0) and not black.
  const float yclip = 0.85f * std::min(wb[0], std::min(wb[1], wb[2]));
  std::vector<uint8_t> use(ln);
  for (size_t i = 0; i < ln; ++i) use[i] = my[i] > 1e-5f && my[i] < yclip && Yl[i] < yclip;
  auto scaleOf = [&](const std::vector<float>& var) {
    ratio.clear();
    for (size_t i = 0; i < ln; ++i) if (use[i]) ratio.push_back(var[i] / mdl[i]);
    return ratio.size() >= 64 ? Percentile(ratio, kQ) / kChi : 0.0f;
  };
  const float gY = scaleOf(vy);
  const float epsY = gY * Percentile(mdl, 0.5f);
  std::vector<float> dU(ln), dV(ln);
  float s2out[2] = {0, 0}, wmean[2] = {0, 0};
  for (int c = 0; c < 2; ++c) {
    const std::vector<float>& C = c == 0 ? Ul : Vl;
    std::vector<float> mc, cy(ln), ccy, a(ln), b(ln), A, B;
    Box(C, &mc, lh, lw, r);
    for (size_t i = 0; i < ln; ++i) cy[i] = C[i] * Yl[i];
    Box(cy, &ccy, lh, lw, r);
    for (size_t i = 0; i < ln; ++i) {
      a[i] = (ccy[i] - my[i] * mc[i]) / (vy[i] + gY * mdl[i] + 1e-12f);
      b[i] = mc[i] - a[i] * my[i];
    }
    Box(a, &A, lh, lw, r);
    Box(b, &B, lh, lw, r);
    // 3. residual, blotch band (box 3 twice ~ gaussian sigma 1 low px = 4 px), Wiener weight
    std::vector<float> R(ln), R1, Rs;
    for (size_t i = 0; i < ln; ++i) R[i] = C[i] - (A[i] * Yl[i] + B[i]);
    Box(R, &R1, lh, lw, 1);
    Box(R1, &Rs, lh, lw, 1);
    // Noise variance of the blotch band itself, relative to the model, from the flattest 10 % of places.
    // (Low-frequency colour noise is spatially correlated: deriving it from R's per-sample variance and
    // the smoothing kernel under-read the road 3x. Correlated noise also has few degrees of freedom per
    // window, so a low percentile is used directly, not a chi^2 correction.)
    std::vector<float> rr(ln), mr, vr;
    Box(Rs, &mr, lh, lw, r);
    for (size_t i = 0; i < ln; ++i) rr[i] = Rs[i] * Rs[i];
    Box(rr, &vr, lh, lw, r);
    for (size_t i = 0; i < ln; ++i) vr[i] = std::max(vr[i] - mr[i] * mr[i], 0.0f);
    ratio.clear();
    for (size_t i = 0; i < ln; ++i) if (use[i]) ratio.push_back(vr[i] / mdl[i]);
    const float g2 = ratio.size() >= 64 ? Percentile(ratio, 0.10f) : 0.0f;
    const float s2 = g2 * Percentile(mdl, 0.5f);
    std::vector<float> e2(ln), e;
    for (size_t i = 0; i < ln; ++i) e2[i] = Rs[i] * Rs[i];
    Box(e2, &e, lh, lw, 1);
    std::vector<float>& d = c == 0 ? dU : dV;
    double ws = 0;
    for (size_t i = 0; i < ln; ++i) {
      const float wgt = e[i] / (e[i] + k * g2 * mdl[i] + 1e-20f);
      d[i] = (wgt - 1.0f) * Rs[i];
      ws += wgt;
    }
    s2out[c] = s2; wmean[c] = float(ws / double(ln));
  }
  // 4. full resolution: bilinear upsample of the colour change, luma kept
  Rows(h, [&](int y) {
    const float fy = std::clamp((y + 0.5f) / f - 0.5f, 0.0f, float(lh - 1));
    const int y0 = int(fy), y1 = std::min(y0 + 1, lh - 1);
    const float ty = fy - float(y0);
    for (int x = 0; x < w; ++x) {
      const float fx = std::clamp((x + 0.5f) / f - 0.5f, 0.0f, float(lw - 1));
      const int x0 = int(fx), x1 = std::min(x0 + 1, lw - 1);
      const float tx = fx - float(x0);
      auto bl = [&](const std::vector<float>& m) {
        const float a0 = m[size_t(y0) * lw + x0] + tx * (m[size_t(y0) * lw + x1] - m[size_t(y0) * lw + x0]);
        const float a1 = m[size_t(y1) * lw + x0] + tx * (m[size_t(y1) * lw + x1] - m[size_t(y1) * lw + x0]);
        return a0 + ty * (a1 - a0);
      };
      float* p = rgb + (size_t(y) * w + x) * 3;
      const float R = p[0] * wb[0], G = p[1] * wb[1], B = p[2] * wb[2];
      const float Y = 0.25f * R + 0.5f * G + 0.25f * B;
      const float R2 = R + bl(dU), B2 = B + bl(dV);
      const float G2 = (Y - 0.25f * R2 - 0.25f * B2) * 2.0f;
      p[0] = R2 / wb[0]; p[1] = G2 / wb[1]; p[2] = B2 / wb[2];
    }
  });
  if (st) { st->s2u = s2out[0]; st->s2v = s2out[1]; st->wu = wmean[0]; st->wv = wmean[1]; st->epsY = epsY; }
}

}  // namespace lkrl
