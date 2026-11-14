// SPDX-License-Identifier: Apache-2.0
#include "lkrl_api.h"
#include "lkrl_image.h"
#include "lkrl_parallel.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>

#include "lkrl_mhc.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define LKR_FAIL(msg) (__android_log_print(ANDROID_LOG_ERROR, "RawLensLkr", "solve: %s", msg), false)
#else
#define LKR_FAIL(msg) (std::fprintf(stderr, "solve: %s\n", msg), false)
#endif
namespace lkrl {

namespace {
// Separable running-sum box blur (radius r, clamped edges), in place.
void BoxBlur(float* a, int H, int W, int r, std::vector<float>& tmp) {
  tmp.resize(size_t(std::max(H, W)));
  for (int i = 0; i < H; ++i) {
    float* row = a + size_t(i) * W;
    double s = 0;
    for (int j = -r; j <= r; ++j) s += row[std::min(std::max(j, 0), W - 1)];
    for (int j = 0; j < W; ++j) {
      tmp[j] = float(s / (2 * r + 1));
      s += row[std::min(j + r + 1, W - 1)] - row[std::max(j - r, 0)];
    }
    std::copy(tmp.begin(), tmp.begin() + W, row);
  }
  for (int j = 0; j < W; ++j) {
    double s = 0;
    for (int i = -r; i <= r; ++i) s += a[size_t(std::min(std::max(i, 0), H - 1)) * W + j];
    for (int i = 0; i < H; ++i) {
      tmp[i] = float(s / (2 * r + 1));
      s += a[size_t(std::min(i + r + 1, H - 1)) * W + j] - a[size_t(std::max(i - r, 0)) * W + j];
    }
    for (int i = 0; i < H; ++i) a[size_t(i) * W + j] = tmp[i];
  }
}
// x += lowpass(z - x), per plane (two box passes ~ Gaussian).
void MeanPreserve(const float* z, float* x, int H, int W, int r) {
  const size_t HW = size_t(H) * W;
  std::vector<float> d(HW), tmp;
  for (int c = 0; c < 3; ++c) {
    for (size_t i = 0; i < HW; ++i) d[i] = z[c * HW + i] - x[c * HW + i];
    BoxBlur(d.data(), H, W, r, tmp);
    BoxBlur(d.data(), H, W, r, tmp);
    for (size_t i = 0; i < HW; ++i) x[c * HW + i] += d[i];
  }
}
}  // namespace

namespace {
// 0..1 shadow weight per HR pixel from the local luma of z (box r=4) and our
// noise model: ratio of relative noise at L to that at mid-grey 0.18,
// ramped over 1.5..5 (day: ~L 0.07 .. 0.015).
void ShadowWeight(const float* z, int H, int W, const SolveParams& prm, std::vector<float>* out) {
  const size_t HW = size_t(H) * W;
  const float q[3] = {0.25f * prm.wb[0], 0.5f * prm.wb[1], 0.25f * prm.wb[2]};
  std::vector<float> L(HW), tmp;
  for (size_t i = 0; i < HW; ++i) L[i] = q[0] * z[i] + q[1] * z[HW + i] + q[2] * z[2 * HW + i];
  BoxBlur(L.data(), H, W, 4, tmp);
  const float a = prm.noise_a, b = std::max(prm.noise_b, 1e-12f);
  const float rel_mid = std::sqrt(a * 0.18f + b) / 0.18f;
  out->resize(HW);
  for (size_t i = 0; i < HW; ++i) {
    const float l = std::max(L[i], 1e-5f);
    const float rho = std::sqrt(a * l + b) / l / rel_mid;
    (*out)[i] = std::min(std::max((rho - 1.5f) / 3.5f, 0.0f), 1.0f);
  }
}
}  // namespace


static void NotchBayerPeriod(float* P, int H, int W) {
  std::vector<float> m(size_t(H) * W), t(size_t(H) * W);
  // B3 spline [1 4 6 4 1]/16: symmetric (no shift) and exactly zero at the
  // Bayer period, so the demodulated amplitude carries no image DC.
  static const float k5[5] = {1 / 16.f, 4 / 16.f, 6 / 16.f, 4 / 16.f, 1 / 16.f};
  auto refl = [](int i, int n) { return i < 0 ? -i : (i >= n ? 2 * n - 2 - i : i); };
  auto box5 = [&](std::vector<float>& a) {
    for (int y = 0; y < H; ++y) {
      const float* r = a.data() + size_t(y) * W;
      float* o = t.data() + size_t(y) * W;
      for (int x = 0; x < W; ++x) {
        float s = 0;
        if (x >= 2 && x + 2 < W) for (int d = -2; d <= 2; ++d) s += k5[d + 2] * r[x + d];
        else for (int d = -2; d <= 2; ++d) s += k5[d + 2] * r[refl(x + d, W)];
        o[x] = s;
      }
    }
    for (int y = 0; y < H; ++y) {
      const float* rows[5];
      for (int d = -2; d <= 2; ++d) rows[d + 2] = t.data() + size_t(refl(y + d, H)) * W;
      float* o = a.data() + size_t(y) * W;
      for (int x = 0; x < W; ++x) {
        float s = 0;
        for (int d = 0; d < 5; ++d) s += k5[d] * rows[d][x];
        o[x] = s;
      }
    }
  };
  for (int mode = 0; mode < 3; ++mode) {
    auto sg = [mode](int x, int y) { return ((mode == 0 ? x : mode == 1 ? y : x + y) & 1) ? -1.0f : 1.0f; };
    for (int y = 0; y < H; ++y)
      for (int x = 0; x < W; ++x) m[size_t(y) * W + x] = P[size_t(y) * W + x] * sg(x, y);
    box5(m);
    for (int y = 0; y < H; ++y)
      for (int x = 0; x < W; ++x) P[size_t(y) * W + x] -= m[size_t(y) * W + x] * sg(x, y);
  }
}

// DSLR-look finish (not in the paper): colour of x without Bayer-period
// structure, plus luminance-only grain = grain * notch(L(z - x)).
void FinishLook(const float* z, float* x, int H, int W, const SolveParams& prm, const float* noise_scale,
                const float* shadow) {
  if (!prm.notch && prm.grain <= 0.0f && !shadow && !prm.highlight_desat && prm.fallback_chroma <= 0.0f) return;
  const size_t HW = size_t(H) * W;
  const float q[3] = {0.25f * prm.wb[0], 0.5f * prm.wb[1], 0.25f * prm.wb[2]};
  std::vector<float> L(HW), C(HW);
  // Clip halo (IMG_20261107_132932 chrome glints): the Bayer-period notches
  // ring around a clipped point -- negative (black) pixels 1-3 px away -- and
  // pull the clipped core below the clip level, so the neutraliser below
  // missed it and it stayed magenta (raw-clipped R,B times wb). Keep the
  // solved values within 3 px of any near-clipped pixel.
  std::vector<uint8_t> halo;
  std::vector<float> keep;
  if (prm.notch) {
    std::vector<uint8_t> hot(HW, 0);
    bool any = false;
    for (size_t i = 0; i < HW; ++i)
      if (std::max(x[i], std::max(x[HW + i], x[2 * HW + i])) >= 0.92f * prm.clip) { hot[i] = 1; any = true; }
    if (any) {
      halo.assign(HW, 0);
      constexpr int r = 3;
      for (int Y = 0; Y < H; ++Y)
        for (int X = 0; X < W; ++X) {
          if (!hot[size_t(Y) * W + X]) continue;
          for (int dy = -r; dy <= r; ++dy)
            for (int dx = -r; dx <= r; ++dx) {
              const int yy = Y + dy, xx = X + dx;
              if (yy >= 0 && yy < H && xx >= 0 && xx < W) halo[size_t(yy) * W + xx] = 1;
            }
        }
      keep.assign(x, x + 3 * HW);
    }
  }
  if (prm.notch) {
    for (size_t i = 0; i < HW; ++i) L[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    for (int c = 0; c < 3; ++c) {
      for (size_t i = 0; i < HW; ++i) C[i] = prm.wb[c] * x[c * HW + i] - L[i];
      NotchBayerPeriod(C.data(), H, W);
      for (size_t i = 0; i < HW; ++i) x[c * HW + i] = (L[i] + C[i]) / prm.wb[c];
    }
  }
  // Luma Bayer-period notch ONLY where frames were rejected: there each
  // Bayer site is measured by a different subset of frames, which leaves a
  // regular site-to-site brightness pattern (dot grid on moving grass/cars,
  // measured in the data fit itself). Strength follows the contributing-frame
  // factor: 0 where every frame contributed (static detail untouched).
  if (prm.notch && noise_scale) {
    // The chroma notch has consumed L/C; reuse those planes for the luma
    // notch. Grain and shadow processing below recompute their own luma.
    std::vector<float>& Lx = L;
    std::vector<float>& Ln = C;
    for (size_t i = 0; i < HW; ++i) Lx[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    Ln = Lx;
    NotchBayerPeriod(Ln.data(), H, W);
    for (size_t i = 0; i < HW; ++i) {
      const float t = std::min(std::max((noise_scale[i] - 1.0f) / 0.4142f, 0.0f), 1.0f);   // full at f >= sqrt(2)
      if (t <= 0.0f) continue;
      const float d = t * (Ln[i] - Lx[i]);
      for (int c = 0; c < 3; ++c) x[c * HW + i] += d / prm.wb[c];
    }
  }
  // Fallback false-colour repair: only where frames were lost (noise_scale > 1), chroma toward
  // its median. The reference frame alone aliases thin lines into colour stripes.
  if (prm.fallback_chroma > 0.0f && noise_scale) {
    std::vector<float> Lf(HW), Cc(HW), Cm(HW);
    for (size_t i = 0; i < HW; ++i) Lf[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    auto med3 = [&](const std::vector<float>& a, std::vector<float>& o) {
      float v[9];
      for (int Y = 0; Y < H; ++Y)
        for (int X = 0; X < W; ++X) {
          int n = 0;
          for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx)
              v[n++] = a[size_t(std::clamp(Y + dy, 0, H - 1)) * W + std::clamp(X + dx, 0, W - 1)];
          std::nth_element(v, v + 4, v + 9);
          o[size_t(Y) * W + X] = v[4];
        }
    };
    for (int c = 0; c < 3; ++c) {
      for (size_t i = 0; i < HW; ++i) Cc[i] = prm.wb[c] * x[c * HW + i] - Lf[i];
      med3(Cc, Cm);
      std::vector<float> Cm2(HW);
      med3(Cm, Cm2);
      for (size_t i = 0; i < HW; ++i) {
        const float t = prm.fallback_chroma * std::min(std::max((noise_scale[i] - 1.0f) / 0.4142f, 0.0f), 1.0f);
        if (t > 0.0f) x[c * HW + i] = (Lf[i] + Cc[i] + t * (Cm2[i] - Cc[i])) / prm.wb[c];
      }
    }
  }
  if (!halo.empty())
    for (size_t i = 0; i < HW; ++i)
      if (halo[i]) for (int c = 0; c < 3; ++c) x[c * HW + i] = keep[c * HW + i];
  if (prm.grain > 0.0f) {
    for (size_t i = 0; i < HW; ++i)
      L[i] = q[0] * (z[i] - x[i]) + q[1] * (z[HW + i] - x[HW + i]) + q[2] * (z[2 * HW + i] - x[2 * HW + i]);
    if (prm.notch) NotchBayerPeriod(L.data(), H, W);
    // Grain where fewer frames contributed is noisier by noise_scale: return
    // proportionally less, so the grain level matches across the image.
    for (int c = 0; c < 3; ++c)
      for (size_t i = 0; i < HW; ++i)
        x[c * HW + i] += prm.grain * L[i] / (noise_scale ? 1.0f + prm.equalize_strength * (noise_scale[i] - 1.0f) : 1.0f) /
                         (shadow ? 1.0f + prm.shadow_boost * shadow[i] : 1.0f) / prm.wb[c];
  }
  // Shadow chroma: blend chroma (wb-space, minus luma) toward its local mean
  // (two box passes r=3) by shadow_chroma * shadow weight. Luma untouched.
  if (shadow && prm.shadow_chroma > 0.0f) {
    for (size_t i = 0; i < HW; ++i) L[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    std::vector<float> tmp;
    std::vector<float> Cc(HW);
    for (int c = 0; c < 3; ++c) {
      for (size_t i = 0; i < HW; ++i) Cc[i] = prm.wb[c] * x[c * HW + i] - L[i];
      C = Cc;
      BoxBlur(C.data(), H, W, 3, tmp);
      BoxBlur(C.data(), H, W, 3, tmp);
      for (size_t i = 0; i < HW; ++i) {
        const float t = prm.shadow_chroma * shadow[i];
        x[c * HW + i] = (L[i] + Cc[i] + t * (C[i] - Cc[i])) / prm.wb[c];
      }
    }
  }
  // Neutralise only pixels with no remaining unsaturated colour channel.
  // One-channel clipping is common in sunsets: the other channels still
  // contain valid colour. Do not expand this mask into coloured neighbours.
  if (prm.highlight_desat) {
    const float lo = 0.92f * prm.clip, hi = prm.clip;
    for (size_t i = 0; i < HW; ++i) {
      const float mn = std::min(x[i], std::min(x[HW + i], x[2 * HW + i]));
      const float m = std::clamp((mn - lo) / std::max(hi - lo, 1e-6f), 0.0f, 1.0f);
      if (m <= 0.0f) continue;
      const float L = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];   // wb-space luma
      for (int c = 0; c < 3; ++c) {
        const float neutral = L / prm.wb[c];
        x[c * HW + i] += m * (neutral - x[c * HW + i]);
      }
    }
  }
}

// scipy.ndimage.convolve(raw*mask, k, mode='mirror') / convolve(mask, k):
// 'mirror' reflects about the edge sample (d c b | a b c d | c b a).

// LK-R motion look (lkr-pipeline lkr_solver.cpp, Solver::solve "if (msmooth)" block, copied
// 2026-11-13; SolveParams::motion_smooth, motion_smooth_sr). rr: final per-sample residuals
// (K x h x w, 0 = invalid), wtb: final robust weights, pm: K x 6 affine, dl: K x nb x 2 local.
void MotionLook(const float* frames, int K, int h, int w, const float* rr, const float* wtb, const float* pm,
                const float* dl, const SolveParams& prm, float* z, float* x) {
  const int s = prm.s, H = s * h, W = s * w;
  (void)H;
  static const bool tdbg = std::getenv("LKRL_MLT") != nullptr;
  auto tnow = [] { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); };
  const double t0 = tnow();

    // Motion look. Where the accepted frames disagree with the merged image by more than their
    // noise (wind-moved foliage, anything the confidence let through), every HR site holds a
    // different frame's version of the scene: 2 x 2 blocks at the Bayer period. There the image
    // is replaced by the time average of the frames (below) in proportion to the excess, g = 1 - 1/E with
    // E = weighted mean of r^2 / sigma^2 over the frames (the weight_e variance, registration
    // tolerance included; 5 x 5 LR mean). Consistent areas have E <= 1 and are not touched.
    const float* rv = rr;
    const float* wv = wtb;
    
    const size_t n = size_t(h) * w;
    std::vector<float> num(n, 0.0f), den(n, 0.0f), tmp(n);
    const float tau2 = prm.robust_tau * prm.robust_tau;
    for (int k = 1; k < K; ++k) {
      const float* yk = frames + size_t(k) * n;
      const float* rk = rv + size_t(k) * n;
      const float* wk = wv + size_t(k) * n;
      const float* pk = pm + size_t(k) * 6;
      for (int i = 0; i < h; ++i)
        for (int j = 0; j < w; ++j) {
          const float wt = wk[size_t(i) * w + j], r = rk[size_t(i) * w + j];
          if (wt <= 0.0f || r == 0.0f) continue;
          auto Y = [&](int ii, int jj) { return yk[size_t(std::min(std::max(ii, 0), h - 1)) * w + std::min(std::max(jj, 0), w - 1)]; };
          const float c = Y(i, j);
          const float gx = (Y(i, j + 2) - Y(i, j - 2)) * 0.25f, gy = (Y(i + 2, j) - Y(i - 2, j)) * 0.25f;
          float lo = c, hi = c;
          for (int di = -2; di <= 2; di += 2)
            for (int dj = -2; dj <= 2; dj += 2) { const float v = Y(i + di, j + dj); lo = std::min(lo, v); hi = std::max(hi, v); }
          const float rg = (hi - lo) * 0.25f;
          const float var = std::max(prm.noise_a * std::max(c, 0.0f) + prm.noise_b + tau2 * std::max(gx * gx + gy * gy, rg * rg), 1e-12f);
          const float ux = j + 0.5f, uy = i + 0.5f;   // to the reference frame (tile affine)
          const int rj = int(std::floor(pk[0] * ux + pk[1] * uy + pk[2])), ri = int(std::floor(pk[3] * ux + pk[4] * uy + pk[5]));
          if (rj < 0 || ri < 0 || rj >= w || ri >= h) continue;
          num[size_t(ri) * w + rj] += wt * r * r / var;
          den[size_t(ri) * w + rj] += wt;
        }
    }
    auto box = [&](std::vector<float>& a) {   // 5 x 5 sum, clamped
      for (int i = 0; i < h; ++i)
        for (int j = 0; j < w; ++j) {
          float sum = 0.0f;
          for (int d = -2; d <= 2; ++d) sum += a[size_t(i) * w + std::min(std::max(j + d, 0), w - 1)];
          tmp[size_t(i) * w + j] = sum;
        }
      for (int i = 0; i < h; ++i)
        for (int j = 0; j < w; ++j) {
          float sum = 0.0f;
          for (int d = -2; d <= 2; ++d) sum += tmp[size_t(std::min(std::max(i + d, 0), h - 1)) * w + j];
          a[size_t(i) * w + j] = sum;
        }
    };
    if (tdbg) std::fprintf(stderr, "mlt emap %.1f\n", tnow() - t0);
    box(num);
    box(den);
    std::vector<float> glr(n), g(size_t(H) * W);
    bool any = false;
    for (size_t i = 0; i < n; ++i) {
      const float E = den[i] > 0.0f ? num[i] / den[i] : 0.0f;
      glr[i] = E > 1.0f ? std::min(prm.motion_smooth * (1.0f - 1.0f / E), 1.0f) : 0.0f;
      any = any || glr[i] > 0.0f;
    }
    if (any) {
      UpsampleBilinear(glr.data(), 1, h, w, s, g.data());
      // What a long exposure records is the time average of the scene: each frame demosaiced on
      // its own, aligned like the merge aligns it (tile affine + local block), and averaged with
      // the merge's own weights (the reference counts 1, frames the confidence rejected stay
      // out, so a passing car leaves no trail). The blur then follows the real motion instead of
      // being a spatial filter. Only the flagged pixels are computed.
      const size_t HW = size_t(H) * W;
      std::vector<uint32_t> idx;
      for (size_t i = 0; i < HW; ++i) if (g[i] > 0.0f) idx.push_back(uint32_t(i));
      std::vector<float> acc3(idx.size() * 3, 0.0f), accw(idx.size(), 0.0f), dem(size_t(3) * n);
      
      const int B = prm.local_block;
      const int lnbx = B > 0 ? (w + B - 1) / B : 0, lnby = B > 0 ? (h + B - 1) / B : 0;
      // Lite: each frame is demosaiced only over the bounding box of the samples read (plus a margin
      // wider than the MHC / bilinear support, crop origin kept even for the Bayer phase), not the
      // whole tile: same values (mirroring only at the real tile edges), ~10x less CPU on Mali.
      // Per frame (in parallel, each frame owns its slot): sample positions, weights and the
      // demosaiced crop; then per flagged pixel (in parallel) the frames are summed in frame order
      // exactly as the serial loop did: bit-identical.
      constexpr int kMargin = 8;
      struct FrameSamples { std::vector<float> fx, fy, wt, dem; int cx0 = 0, cy0 = 0, cw = 0; bool any = false; };
      std::vector<FrameSamples> fs(static_cast<size_t>(K));
      ParallelFor(K, [&](int k0, int k1) {
        std::vector<float> raw;
        for (int k = k0; k < k1; ++k) {
          FrameSamples& F = fs[size_t(k)];
          const float* yk = frames + size_t(k) * n;
          const float* pk = pm + size_t(k) * 6;
          const float det = pk[0] * pk[4] - pk[1] * pk[3];
          if (std::fabs(det) < 1e-6f) continue;
          const float i00 = pk[4] / det, i01 = -pk[1] / det, i10 = -pk[3] / det, i11 = pk[0] / det;
          const float* wk = wv + size_t(k) * n;
          F.fx.assign(idx.size(), 0.0f); F.fy.assign(idx.size(), 0.0f); F.wt.assign(idx.size(), 0.0f);
          int bx0 = w, by0 = h, bx1 = -1, by1 = -1;
          for (size_t q = 0; q < idx.size(); ++q) {
            const int Y = int(idx[q] / uint32_t(W)), X = int(idx[q] % uint32_t(W));
            const float rx = (X + 0.5f) / s, ry = (Y + 0.5f) / s;   // reference LR coordinates
            float ux = i00 * (rx - pk[2]) + i01 * (ry - pk[5]), uy = i10 * (rx - pk[2]) + i11 * (ry - pk[5]);
            if (B > 0)
              for (int it = 0; it < 2; ++it) {   // the block is addressed in frame coordinates
                const int bx = std::min(std::max(int(ux) / B, 0), lnbx - 1), by = std::min(std::max(int(uy) / B, 0), lnby - 1);
                const float* d = dl + (size_t(k) * lnby + by) * lnbx * 2 + size_t(bx) * 2;
                ux = i00 * (rx - pk[2] - d[0]) + i01 * (ry - pk[5] - d[1]);
                uy = i10 * (rx - pk[2] - d[0]) + i11 * (ry - pk[5] - d[1]);
              }
            const float fx = ux - 0.5f, fy = uy - 0.5f;
            const int x0 = int(std::floor(fx)), y0 = int(std::floor(fy));
            if (x0 < 0 || y0 < 0 || x0 + 1 >= w || y0 + 1 >= h) continue;
            const float wt = k == 0 ? 1.0f : wk[size_t(std::min(std::max(int(uy), 0), h - 1)) * w + std::min(std::max(int(ux), 0), w - 1)];
            if (wt <= 0.0f) continue;
            F.fx[q] = fx; F.fy[q] = fy; F.wt[q] = wt;
            bx0 = std::min(bx0, x0); by0 = std::min(by0, y0); bx1 = std::max(bx1, x0 + 1); by1 = std::max(by1, y0 + 1);
          }
          if (bx1 < 0) continue;
          if (std::getenv("LKRL_FULL_DEMOSAIC")) { bx0 = by0 = 0; bx1 = w - 1; by1 = h - 1; }   // A/B: whole tile
          const int cx0 = std::max(0, bx0 - kMargin) & ~1, cy0 = std::max(0, by0 - kMargin) & ~1;
          const int cx1 = std::min(w, bx1 + 1 + kMargin), cy1 = std::min(h, by1 + 1 + kMargin);
          const int cw = cx1 - cx0, ch = cy1 - cy0;
          const size_t cn = size_t(cw) * ch;
          raw.resize(cn);
          for (int yy = 0; yy < ch; ++yy)
            std::copy(yk + size_t(cy0 + yy) * w + cx0, yk + size_t(cy0 + yy) * w + cx1, raw.begin() + size_t(yy) * cw);
          F.dem.resize(3 * cn);
          if (prm.mhc_init) MhcCoherentRggb(raw.data(), ch, cw, F.dem.data()); else DemosaicBilinear(raw.data(), ch, cw, F.dem.data());
          F.cx0 = cx0; F.cy0 = cy0; F.cw = cw; F.any = true;
        }
      }, 1);   // 1: measured slower on Mali with 4 (3 workers already; 1x 19.7 -> 21.5-25.2 s)
      ParallelFor(int(idx.size()), [&](int q0, int q1) {
        for (int k = 0; k < K; ++k) {
          const FrameSamples& F = fs[size_t(k)];
          if (!F.any) continue;
          const size_t cn = F.dem.size() / 3;
          const int cw = F.cw;
          for (int qq = q0; qq < q1; ++qq) {
            const size_t q = size_t(qq);
            const float wt = F.wt[q];
            if (wt <= 0.0f) continue;
            const float fx = F.fx[q], fy = F.fy[q];
            const int x0 = int(std::floor(fx)), y0 = int(std::floor(fy));
            const float ax = fx - x0, ay = fy - y0;
            const size_t o = size_t(y0 - F.cy0) * cw + (x0 - F.cx0);
            for (int c = 0; c < 3; ++c) {
              const float* D = F.dem.data() + size_t(c) * cn + o;
              acc3[q * 3 + c] += wt * ((D[0] * (1 - ax) + D[1] * ax) * (1 - ay) + (D[cw] * (1 - ax) + D[cw + 1] * ax) * ay);
            }
            accw[q] += wt;
          }
        }
      }, 1);
      if (tdbg) std::fprintf(stderr, "mlt accum %.1f (flagged %zu of %zu)\n", tnow() - t0, idx.size(), HW);
      const bool keep_sr = prm.motion_smooth_sr;
      std::vector<float> D, Bm, mask, Bw;
      if (keep_sr) { D.assign(HW, 0.0f); Bm.assign(HW, 0.0f); mask.assign(HW, 0.0f); Bw.assign(HW, 0.0f); }
      const int P = 2 * s;   // Bayer period in HR pixels
      for (float* img : {z, x})
        for (int c = 0; c < 3; ++c) {
          float* V = img + size_t(c) * HW;
          if (!keep_sr) {
            for (size_t q = 0; q < idx.size(); ++q)
              if (accw[q] > 0.0f) V[idx[q]] += g[idx[q]] * (acc3[q * 3 + c] / accw[q] - V[idx[q]]);
            continue;
          }
          // Super-resolved variant: the merge minus the time average is the resolved detail plus
          // the per-site blocks. The blocks repeat with the Bayer period; the detail does not.
          // Remove from that difference its local same-site mean (3 x 3 sites one period apart)
          // less the local mean over all sites, i.e. only what is locked to the Bayer grid.
          std::fill(D.begin(), D.end(), 0.0f);
          std::fill(mask.begin(), mask.end(), 0.0f);
          for (size_t q = 0; q < idx.size(); ++q)
            if (accw[q] > 0.0f) { D[idx[q]] = V[idx[q]] - acc3[q * 3 + c] / accw[q]; mask[idx[q]] = 1.0f; }
          for (size_t q = 0; q < idx.size(); ++q) {   // P x P box sums around each flagged pixel
            const int Y = int(idx[q] / uint32_t(W)), X = int(idx[q] % uint32_t(W));
            float sd = 0.0f, sm = 0.0f;
            for (int dy = -P / 2; dy < P - P / 2; ++dy) {
              const int yy = Y + dy; if (yy < 0 || yy >= H) continue;
              for (int dx = -P / 2; dx < P - P / 2; ++dx) {
                const int xx = X + dx; if (xx < 0 || xx >= W) continue;
                sd += D[size_t(yy) * W + xx]; sm += mask[size_t(yy) * W + xx];
              }
            }
            Bm[idx[q]] = sd; Bw[idx[q]] = sm;
          }
          for (size_t q = 0; q < idx.size(); ++q) {
            if (accw[q] <= 0.0f) continue;
            const int Y = int(idx[q] / uint32_t(W)), X = int(idx[q] % uint32_t(W));
            float cs = 0.0f, cw = 0.0f, ms = 0.0f, mw = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
              const int yy = Y + dy * P; if (yy < 0 || yy >= H) continue;
              for (int dx = -1; dx <= 1; ++dx) {
                const int xx = X + dx * P; if (xx < 0 || xx >= W) continue;
                const float t = (dy == 0 ? 2.0f : 1.0f) * (dx == 0 ? 2.0f : 1.0f);
                const size_t o = size_t(yy) * W + xx;
                cs += t * D[o] * mask[o]; cw += t * mask[o];
                ms += t * Bm[o]; mw += t * Bw[o];
              }
            }
            if (cw <= 0.0f || mw <= 0.0f) continue;
            V[idx[q]] -= g[idx[q]] * (cs / cw - ms / mw);
          }
        }
    }
    if (const char* dump = std::getenv("LKR_DUMP_MS")) {
      FILE* f = std::fopen(dump, "wb");
      if (f) { std::fwrite(glr.data(), 4, glr.size(), f); std::fclose(f); }
    }
  }

}  // namespace lkrl
