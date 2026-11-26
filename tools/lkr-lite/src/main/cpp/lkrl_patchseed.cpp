// SPDX-License-Identifier: Apache-2.0
#include "lkrl_patchseed.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace lkrl {
namespace {

struct Img { const float* a; int n; };

// Mean absolute residual of block window [x0, x1) x [y0, y1) at offset (dx, dy) grey px, step st.
// Fewer than 60 % usable samples -> a large cost.
inline float Cost(const Img& R, const Img& M, const double p[6], int x0, int y0, int x1, int y1, int st, float dx, float dy,
                  int* support = nullptr) {
  float sum = 0;
  int cnt = 0, all = 0;
  const float a0 = float(p[0]), b0 = float(p[1]), a1 = float(p[3]), b1 = float(p[4]);
  for (int y = y0; y < y1; y += st) {
    const float uy = y + 0.5f;
    float fx = a0 * (x0 + 0.5f) + b0 * uy + float(p[2]) + dx - 0.5f, fy = a1 * (x0 + 0.5f) + b1 * uy + float(p[5]) + dy - 0.5f;
    for (int x = x0; x < x1; x += st, fx += a0 * st, fy += a1 * st) {
      ++all;
      if (!(fx >= 0 && fy >= 0)) continue;
      const int ix = int(fx), iy = int(fy);
      if (ix + 1 >= R.n || iy + 1 >= R.n) continue;
      const float ax = fx - ix, ay = fy - iy;
      const float* r = R.a + size_t(iy) * R.n + ix;
      const float v = (r[0] + (r[1] - r[0]) * ax) * (1 - ay) + (r[R.n] + (r[R.n + 1] - r[R.n]) * ax) * ay;
      const float e = std::fabs(v - M.a[size_t(y) * M.n + x]);
      if (!(e < 1e6f)) continue;   // NaN
      sum += e;
      ++cnt;
    }
  }
  if (support) *support = cnt;
  return cnt * 10 < all * 6 || cnt < 4 ? 1e9f : sum / float(cnt);
}

// Translational inverse-compositional LK of the window, outliers limited.
inline void Refine(const Img& R, const Img& M, const double p[6], int x0, int y0, int x1, int y1, float lim, int iters,
                   float* dx, float* dy) {
  x0 = std::max(x0, 1); y0 = std::max(y0, 1); x1 = std::min(x1, M.n - 1); y1 = std::min(y1, M.n - 1);
  for (int it = 0; it < iters; ++it) {
    float h00 = 0, h01 = 0, h11 = 0, g0 = 0, g1 = 0;
    for (int y = y0; y < y1; ++y)
      for (int x = x0; x < x1; ++x) {
        const float ux = x + 0.5f, uy = y + 0.5f;
        const float fx = float(p[0]) * ux + float(p[1]) * uy + float(p[2]) + *dx - 0.5f;
        const float fy = float(p[3]) * ux + float(p[4]) * uy + float(p[5]) + *dy - 0.5f;
        if (!(fx >= 0 && fy >= 0)) continue;
        const int ix = int(fx), iy = int(fy);
        if (ix + 1 >= R.n || iy + 1 >= R.n) continue;
        const float ax = fx - ix, ay = fy - iy;
        const float* r = R.a + size_t(iy) * R.n + ix;
        const float* m = M.a + size_t(y) * M.n + x;
        const float v = (r[0] + (r[1] - r[0]) * ax) * (1 - ay) + (r[R.n] + (r[R.n + 1] - r[R.n]) * ax) * ay;
        const float e = v - m[0], gx = 0.5f * (m[1] - m[-1]), gy = 0.5f * (m[M.n] - m[-M.n]);
        if (!std::isfinite(e + gx + gy)) continue;
        const float w = std::fabs(e) > lim ? lim / std::fabs(e) : 1.0f;
        h00 += w * gx * gx; h01 += w * gx * gy; h11 += w * gy * gy; g0 += w * gx * e; g1 += w * gy * e;
      }
    const float l = 1e-3f * (h00 + h11) + 1e-9f;
    h00 += l; h11 += l;
    const float det = h00 * h11 - h01 * h01;
    if (!(det > 1e-18f)) return;
    const float ddx = std::clamp(-(h11 * g0 - h01 * g1) / det, -1.0f, 1.0f), ddy = std::clamp(-(h00 * g1 - h01 * g0) / det, -1.0f, 1.0f);
    *dx += ddx; *dy += ddy;
    if (std::fabs(ddx) + std::fabs(ddy) < 0.02f) return;
  }
}

std::vector<float> Down4(const float* a, int n, int* m) {   // 4x4 mean (grey -> 8 LR px)
  *m = n / 4;
  std::vector<float> o(size_t(*m) * *m);
  for (int y = 0; y < *m; ++y)
    for (int x = 0; x < *m; ++x) {
      float s = 0;
      for (int j = 0; j < 4; ++j) for (int i = 0; i < 4; ++i) s += a[size_t(4 * y + j) * n + 4 * x + i];
      o[size_t(y) * *m + x] = s / 16.0f;
    }
  return o;
}

}  // namespace

PatchSeedStats PatchSeed(const float* ref, const float* mov, int n, const double p[6], const PatchSeedParams& prm,
                         float* field, int nbx, int nby) {
  PatchSeedStats S;
  const Img R{ref, n}, M{mov, n};
  const int nb = nbx * nby;
  S.blocks = nb;
  struct B { float cost, noise; int sup; bool act; };
  std::vector<B> bl(static_cast<size_t>(nb));
  auto win = [&](int bx, int by, int m, int* x0, int* y0, int* x1, int* y1) {
    *x0 = std::max(bx * 8 - m, 0); *y0 = std::max(by * 8 - m, 0);
    *x1 = std::min(bx * 8 + 8 + m, n); *y1 = std::min(by * 8 + 8 + m, n);
  };
  int active = 0;
  for (int b = 0; b < nb; ++b) {
    int x0, y0, x1, y1;
    win(b % nbx, b / nbx, 1, &x0, &y0, &x1, &y1);
    double s = 0, g = 0; int c = 0;
    for (int y = y0; y < y1; ++y) for (int x = x0; x < x1; ++x) {
      const float v = mov[size_t(y) * n + x];
      if (v != v) continue;
      s += v; ++c;
      if (x + 1 < n && y + 1 < n) { const float q = std::fabs(mov[size_t(y) * n + x + 1] - v) + std::fabs(mov[size_t(y + 1) * n + x] - v); if (q == q) g += q; }
    }
    // |N(0, s)| has mean 0.8 s; residual variance: a cell mean of 4 samples on each side (bilinear ~ x 0.6).
    bl[size_t(b)].noise = 0.8f * std::sqrt(std::max(prm.noise_a * std::max(float(c ? s / c : 0), 0.0f) + prm.noise_b, 1e-12f) * 0.25f * 1.6f);
    bl[size_t(b)].cost = Cost(R, M, p, x0, y0, x1, y1, 1, 0.5f * field[2 * b], 0.5f * field[2 * b + 1], &bl[size_t(b)].sup);
    // Aliasing and sub-pixel error on sharp texture are left to the Gauss-Newton steps: only a residual
    // beyond noise plus prm.slack px of motion (mean |gradient| per grey px) starts a search.
    // A block whose seed leaves the image is left alone: its few samples cannot tell a better warp.
    bl[size_t(b)].act = bl[size_t(b)].cost < 1e8f && bl[size_t(b)].cost > prm.trigger * bl[size_t(b)].noise + prm.slack * float(c ? 0.5 * g / c : 0);
    active += bl[size_t(b)].act;
  }
  S.checked = active;
  if (!active) return S;
  int m4 = 0;
  std::vector<float> r4, v4;
  if (prm.search > 0) { r4 = Down4(ref, n, &m4); v4 = Down4(mov, n, &m4); }
  const Img R4{r4.data(), m4}, M4{v4.data(), m4};
  const double p4[6] = {p[0], p[1], p[2] * 0.25, p[3], p[4], p[5] * 0.25};
  std::vector<uint8_t> changed(size_t(nb), 0);
  const float maxg = 0.5f * prm.max_offset;
  for (int sweep = 0; sweep < 2; ++sweep)
    for (int q = 0; q < nb; ++q) {
      const int b = sweep == 0 ? q : nb - 1 - q;
      B& s = bl[size_t(b)];
      if (!s.act) continue;
      const int bx = b % nbx, by = b / nbx;
      int x0, y0, x1, y1;
      win(bx, by, 1, &x0, &y0, &x1, &y1);
      float best = s.cost, bdx = 0.5f * field[2 * b], bdy = 0.5f * field[2 * b + 1];
      const float sx = bdx, sy = bdy;
      auto tryat = [&](float dx, float dy, bool refine) {
        if (refine) Refine(R, M, p, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 4 * s.noise, 4, &dx, &dy);
        if (std::fabs(dx) > maxg || std::fabs(dy) > maxg) return;
        int sup = 0;
        const float c = Cost(R, M, p, x0, y0, x1, y1, 1, dx, dy, &sup);
        if (c < best && sup * 10 >= s.sup * 9) { best = c; bdx = dx; bdy = dy; }
      };
      const int nbr[4] = {bx > 0 ? b - 1 : -1, by > 0 ? b - nbx : -1, bx + 1 < nbx ? b + 1 : -1, by + 1 < nby ? b + nbx : -1};
      for (int o : nbr) if (o >= 0) tryat(0.5f * field[2 * o], 0.5f * field[2 * o + 1], false);
      if (sweep == 0) {
        tryat(0, 0, false);
        if (prm.search > 0 && best > 2 * prm.trigger * s.noise) {   // coarse exhaustive search, 8 LR px steps
          const int cx = (bx * 2), cy = (by * 2);
          const int X0 = std::max(cx - 2, 0), Y0 = std::max(cy - 2, 0), X1 = std::min(cx + 4, m4), Y1 = std::min(cy + 4, m4);
          const float bx4 = std::round(0.25f * bdx), by4 = std::round(0.25f * bdy);
          float c4 = Cost(R4, M4, p4, X0, Y0, X1, Y1, 1, bx4, by4), kx = bx4, ky = by4;
          const float base = c4;
          for (int j = -prm.search; j <= prm.search; ++j)
            for (int i = -prm.search; i <= prm.search; ++i) {
              const float c = Cost(R4, M4, p4, X0, Y0, X1, Y1, 1, bx4 + i, by4 + j);
              if (c < c4) { c4 = c; kx = bx4 + i; ky = by4 + j; }
            }
          if (c4 < 0.7f * base) {   // refine at the coarse scale, then here
            Refine(R4, M4, p4, X0, Y0, X1, Y1, 1e3f, 3, &kx, &ky);
            tryat(4 * kx, 4 * ky, true);
          }
        }
      }
      if (best > 1.2f * s.noise) tryat(bdx, bdy, true);
      if (best < s.cost && (bdx != sx || bdy != sy)) {
        // small corrections may follow any improvement; a real jump needs a clear one
        const bool jump = std::fabs(bdx - sx) + std::fabs(bdy - sy) > 1.0f;
        bool take = !jump || best < prm.gain * s.cost;
        if (take && jump && prm.unique > 0) {
          // A jump needs a match that stands out from its surroundings: 3 px off in any direction the fit must be
          // worse by unique standard errors of a mean |residual| (flat and edge-only blocks fit anywhere).
          float off = 1e9f;
          for (int j = -1; j <= 1; ++j)
            for (int i = -1; i <= 1; ++i)
              if (i || j) off = std::min(off, Cost(R, M, p, x0, y0, x1, y1, 1, bdx + 1.5f * i, bdy + 1.5f * j));
          const float se = 0.75f * 1.4142f * s.noise / std::sqrt(float(std::max(s.sup, 1)));
          take = off - best > prm.unique * se;
        }
        if (take) {
          field[2 * b] = 2 * bdx; field[2 * b + 1] = 2 * bdy;
          s.cost = best;
          changed[size_t(b)] = 1;
        }
      }
    }
  for (uint8_t c : changed) S.changed += c;
  return S;
}

int PatchSeedFine(const float* ref, const float* mov, int n, const double p[6], const PatchSeedParams& prm,
                  const float* field, int nbx, int nby, float* extra, int nfx, int nfy) {
  const Img R{ref, n}, M{mov, n};
  const size_t nf = size_t(nfx) * nfy;
  std::fill(extra, extra + 2 * nf, 0.0f);
  const float maxg = 0.5f * prm.max_offset;
  int changed = 0;
  for (int by = 0; by < nfy; ++by)
    for (int bx = 0; bx < nfx; ++bx) {
      const int x0 = std::max(bx * 4 - 2, 0), y0 = std::max(by * 4 - 2, 0), x1 = std::min(bx * 4 + 6, n), y1 = std::min(by * 4 + 6, n);
      const float cx = (bx * 8 + 4) / 16.0f - 0.5f, cy = (by * 8 + 4) / 16.0f - 0.5f;
      const int ix = int(std::floor(cx)), iy = int(std::floor(cy));
      auto F = [&](int x, int y) { return field + 2 * (size_t(std::clamp(y, 0, nby - 1)) * nbx + std::clamp(x, 0, nbx - 1)); };
      float base[2];
      for (int d = 0; d < 2; ++d) {
        const float fx = cx - ix, fy = cy - iy;
        base[d] = 0.5f * ((F(ix, iy)[d] * (1 - fx) + F(ix + 1, iy)[d] * fx) * (1 - fy) + (F(ix, iy + 1)[d] * (1 - fx) + F(ix + 1, iy + 1)[d] * fx) * fy);
      }
      int sup0 = 0;
      const float c0 = Cost(R, M, p, x0, y0, x1, y1, 1, base[0], base[1], &sup0);
      if (c0 >= 1e8f) continue;
      double s = 0, g = 0; int c = 0;
      for (int y = y0; y < y1; ++y) for (int x = x0; x < x1; ++x) {
        const float v = mov[size_t(y) * n + x];
        if (v != v) continue;
        s += v; ++c;
        if (x + 1 < n && y + 1 < n) { const float q = std::fabs(mov[size_t(y) * n + x + 1] - v) + std::fabs(mov[size_t(y + 1) * n + x] - v); if (q == q) g += q; }
      }
      if (!c) continue;
      const float noise = 0.8f * std::sqrt(std::max(prm.noise_a * std::max(float(s / c), 0.0f) + prm.noise_b, 1e-12f) * 0.25f * 1.6f);
      if (c0 <= prm.trigger * noise + prm.slack * float(0.5 * g / c)) continue;
      float best = c0, bd[2] = {base[0], base[1]};
      auto tryat = [&](float dx, float dy) {
        if (std::fabs(dx) > maxg || std::fabs(dy) > maxg) return;
        int sup = 0;
        const float v = Cost(R, M, p, x0, y0, x1, y1, 1, dx, dy, &sup);
        if (v < best && sup * 10 >= sup0 * 9) { best = v; bd[0] = dx; bd[1] = dy; }
      };
      const int hx = bx / 2, hy = by / 2;
      for (int j = -1; j <= 1; ++j) for (int i = -1; i <= 1; ++i) tryat(0.5f * F(hx + i, hy + j)[0], 0.5f * F(hx + i, hy + j)[1]);
      tryat(0, 0);
      auto prev = [&](int x, int y) {   // a fine neighbour's final motion, seen from here
        const float* e = extra + 2 * (size_t(y) * nfx + x);
        if (e[0] != 0 || e[1] != 0) tryat(base[0] + 0.5f * e[0], base[1] + 0.5f * e[1]);
      };
      if (bx) prev(bx - 1, by);
      if (by) prev(bx, by - 1);
      float dx = bd[0], dy = bd[1];
      Refine(R, M, p, x0, y0, x1, y1, 4 * noise, 4, &dx, &dy);
      tryat(dx, dy);
      // clear in relative terms and against the noise of the mean residual of so few samples (best of many)
      if (best < prm.gain * c0 && best < c0 - 0.3f * noise) {
        if (prm.fine_unique > 0) {   // repeats, edges and flat areas fit anywhere: not a motion
          float off = 1e9f;
          for (int j = -1; j <= 1; ++j)
            for (int i = -1; i <= 1; ++i)
              if (i || j) off = std::min(off, Cost(R, M, p, x0, y0, x1, y1, 1, bd[0] + 1.5f * i, bd[1] + 1.5f * j));
          if (!(off - best > prm.fine_unique * 0.75f * 1.4142f * noise / std::sqrt(float(std::max(sup0, 1))))) continue;
        }
        float* e = extra + 2 * (size_t(by) * nfx + bx);
        e[0] = 2 * (bd[0] - base[0]); e[1] = 2 * (bd[1] - base[1]);
        ++changed;
      }
    }
  if (prm.fine_agree > 0) {   // moving objects are connected; single false matches are not
    const std::vector<float> e0(extra, extra + 2 * nf);
    for (int by = 0; by < nfy; ++by)
      for (int bx = 0; bx < nfx; ++bx) {
        const float* c = e0.data() + 2 * (size_t(by) * nfx + bx);
        if (c[0] == 0 && c[1] == 0) continue;
        int n_ok = 0;
        for (int j = std::max(by - 1, 0); j <= std::min(by + 1, nfy - 1); ++j)
          for (int i = std::max(bx - 1, 0); i <= std::min(bx + 1, nfx - 1); ++i) {
            const float* o = e0.data() + 2 * (size_t(j) * nfx + i);
            if ((i != bx || j != by) && (o[0] != 0 || o[1] != 0) && std::fabs(o[0] - c[0]) + std::fabs(o[1] - c[1]) < 2.0f) ++n_ok;
          }
        if (n_ok < prm.fine_agree) { extra[2 * (size_t(by) * nfx + bx)] = extra[2 * (size_t(by) * nfx + bx) + 1] = 0; --changed; }
      }
  }
  return changed;
}

}  // namespace lkrl
