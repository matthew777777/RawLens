// SPDX-License-Identifier: Apache-2.0
#include "lkrl_host.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <numeric>

#include "lkrl_image.h"
#include "srp_dng.h"

namespace lkrl {

bool LoadBurstCrop(const std::vector<std::string>& paths, int crop, int cx, int cy, Burst* out,
                   std::string* err) {
  if (paths.empty() || crop < 8 || (crop & 1)) {
    if (err) *err = "need >=1 DNG and an even crop >= 8";
    return false;
  }
  srp::SrpDngMeta m;
  if (srp::SrpReadDngMeta(paths[0].c_str(), &m) != srp::DngReadStatus::kOk) {
    if (err) *err = "cannot read DNG metadata: " + paths[0];
    return false;
  }
  // CFA (LibRaw order, 0=R 1=G 2=B): origin parity putting R at (0,0).
  int ox = -1, oy = -1;
  for (int k = 0; k < 4; ++k)
    if (m.cfa[k] == 0) { ox = k & 1; oy = k >> 1; }
  if (ox < 0) {
    if (err) *err = "unsupported CFA";
    return false;
  }
  const int W = int(m.width), H = int(m.height);
  if (cx < 0) cx = W / 2;
  if (cy < 0) cy = H / 2;
  // python: x0 = (cx - crop//2)//2*2 + ox  (same for y)
  auto fl2 = [](int v) { return (v >= 0 ? v / 2 : (v - 1) / 2) * 2; };
  int x0 = fl2(cx - crop / 2) + ox, y0 = fl2(cy - crop / 2) + oy;
  x0 = std::clamp(x0, ox, W - crop - 2 + ox);
  y0 = std::clamp(y0, oy, H - crop - 2 + oy);
  // Even-aligned region one cell larger, then the parity offset.
  const int rx = x0 - ox, ry = y0 - oy, rw = crop + 2, rh = crop + 2;
  out->K = int(paths.size());
  out->h = out->w = crop;
  out->x0 = x0;
  out->y0 = y0;
  out->frames.assign(size_t(out->K) * crop * crop, 0.0f);
  std::vector<uint16_t> px(size_t(rw) * rh);
  for (int k = 0; k < out->K; ++k) {
    if (srp::SrpReadDngPixels(paths[k].c_str(), px.data(), rx, ry, rw, rh) != srp::DngReadStatus::kOk) {
      if (err) *err = "cannot decode " + paths[k];
      return false;
    }
    float* f = out->frames.data() + size_t(k) * crop * crop;
    for (int i = 0; i < crop; ++i)
      for (int j = 0; j < crop; ++j) {
        const int cell = (((i + y0) & 1) << 1) | ((j + x0) & 1);
        const double black = m.black_level_precise[cell];
        f[size_t(i) * crop + j] = float((double(px[size_t(i + oy) * rw + j + ox]) - black) /
                                       (double(m.white_level) - black));
      }
  }
  if (m.has_as_shot_neutral && m.as_shot_neutral[0] > 0 && m.as_shot_neutral[2] > 0) {
    const double g = m.as_shot_neutral[1];
    out->wb = {float(g / m.as_shot_neutral[0]), 1.0f, float(g / m.as_shot_neutral[2])};
  }
  return true;
}

static double median(std::vector<double>& v) {
  if (v.empty()) return 0.0;
  const size_t n = v.size(), m = n / 2;
  std::nth_element(v.begin(), v.begin() + m, v.end());
  double hi = v[m];
  if (n & 1) return hi;
  double lo = *std::max_element(v.begin(), v.begin() + m);
  return 0.5 * (lo + hi);
}

static double quantile(std::vector<double> v, double q) {   // numpy 'linear'
  std::sort(v.begin(), v.end());
  const double pos = q * double(v.size() - 1);
  const size_t i = size_t(std::floor(pos));
  const double f = pos - double(i);
  return i + 1 < v.size() ? v[i] * (1 - f) + v[i + 1] * f : v[i];
}

float NoiseSigma(const float* raw, int h, int w) {
  const int gh = h / 2, gw = w / 2;
  auto g = [&](int i, int j) { return double(raw[size_t(2 * i) * w + 2 * j + 1]); };
  std::vector<double> d;
  d.reserve(size_t(gh) * gw);
  for (int i = 1; i < gh - 1; ++i)
    for (int j = 1; j < gw - 1; ++j)
      d.push_back(4 * g(i, j) - g(i - 1, j) - g(i + 1, j) - g(i, j - 1) - g(i, j + 1));
  std::vector<double> c = d;
  const double med = median(c);
  for (double& v : d) v = std::fabs(v - med);
  return float(1.4826 * median(d) / std::sqrt(20.0));
}

void NoiseAB(const float* raw, int h, int w, float* a_out, float* b_out, int* bins_out) {
  if (bins_out) *bins_out = 0;
  constexpr int B = 8;
  std::vector<double> ms, vs;
  for (int plane = 0; plane < 2; ++plane) {          // greens at (0,1) and (1,0)
    const int py = plane, px = 1 - plane;
    const int gh = (h - py + 1) / 2, gw = (w - px + 1) / 2;
    auto g = [&](int i, int j) { return double(raw[size_t(2 * i + py) * w + 2 * j + px]); };
    const int dh = gh - 2, dw = gw - 2;
    const int Hb = dh / B, Wb = dw / B;
    std::vector<double> d2(B * B);
    for (int bi = 0; bi < Hb; ++bi)
      for (int bj = 0; bj < Wb; ++bj) {
        double sum = 0;
        int n = 0;
        double peak = 0;
        for (int y = 0; y < B; ++y)
          for (int x = 0; x < B; ++x) {
            const int i = 1 + bi * B + y, j = 1 + bj * B + x;
            const double d = (4 * g(i, j) - g(i - 1, j) - g(i + 1, j) - g(i, j - 1) - g(i, j + 1)) / std::sqrt(20.0);
            d2[n++] = d * d;
            sum += g(i, j);
            peak = std::max({peak, g(i, j), g(i - 1, j), g(i + 1, j), g(i, j - 1), g(i, j + 1)});
          }
        // A block touching the white level has its variance clipped away (burst
        // 0039 sky: 27 % clipped, fit collapsed to a=0 b=1e-10 and the robust
        // term then rejected every frame). Mark it unusable.
        ms.push_back(peak > 0.97 ? -1.0 : sum / (B * B));
        // Use energy, not the median of squared quantised differences. Near
        // black the latter locks to a handful of code values; selecting its
        // lower tail can collapse the fitted read-noise intercept to zero.
        double energy = 0;
        for (double v : d2) energy += v;
        vs.push_back(energy / (B * B));
      }
  }
  std::vector<double> m, v;
  for (size_t i = 0; i < ms.size(); ++i)
    if (ms[i] > 0.002 && ms[i] < 0.9) { m.push_back(ms[i]); v.push_back(vs[i]); }
  if (m.size() < 40) { *a_out = 0; *b_out = 1e-6f; return; }
  // Small crops (zoom, sweep tiles) have too few blocks for 16 levels of
  // >= 20 blocks each: every bin was dropped and the fit returned zero noise.
  const int BINS = int(std::clamp<size_t>(m.size() / 40, 2, 16));
  std::vector<double> edges(BINS + 1);
  for (int e = 0; e <= BINS; ++e) edges[e] = quantile(m, double(e) / BINS);
  std::vector<double> X, Y, Wt;
  for (int e = 0; e < BINS; ++e) {
    std::vector<double> sm, sv;
    for (size_t i = 0; i < m.size(); ++i)
      if (m[i] >= edges[e] && m[i] <= edges[e + 1]) { sm.push_back(m[i]); sv.push_back(v[i]); }
    if (sm.size() < 20) continue;
    X.push_back(median(sm));
    // Flattest 5% of blocks per level (texture is never flatter than noise);
    // 0.67 approximates the 5% quantile of 8x8 mean Laplacian energy under
    // white Gaussian noise, including adjacent-filter correlations.
    // Quantized/constant patches can have exactly zero measured variance.
    // The relative-error fit divides by Y: keep its weights finite at the
    // same read-noise floor used for the returned model.
    Y.push_back(std::max(quantile(sv, 0.05) / 0.67, 1e-10));
    Wt.push_back(std::sqrt(double(sm.size())));
  }
  // Noise variance never falls as the level rises. A bin at < half the variance
  // of every brighter bin is corrupt (crushed / smoothed shadows); with the 1/Y
  // weights it alone pulled b to the 1e-10 floor (074919: 1.2e-7 vs 1.5e-6 next),
  // underestimating shadow noise ~3.6x and rejecting static detail as motion.
  {
    std::vector<double> X2, Y2, W2;
    double floor_above = 1e300;
    std::vector<bool> keep(X.size(), true);
    for (size_t i = X.size(); i-- > 0;) {
      if (Y[i] < 0.5 * floor_above) keep[i] = floor_above == 1e300;
      floor_above = std::min(floor_above, Y[i]);
    }
    for (size_t i = 0; i < X.size(); ++i)
      if (keep[i]) { X2.push_back(X[i]); Y2.push_back(Y[i]); W2.push_back(Wt[i]); }
    if (X2.size() >= 2) { X.swap(X2); Y.swap(Y2); Wt.swap(W2); }
  }
  // Weighted NNLS on rows (X, 1) * (Wt/Y) = Wt: 2 unknowns, so enumerate the
  // active sets {a,b}, {a}, {b} and keep the best feasible one.
  auto fit = [&](bool ua, bool ub, double* a, double* b) {
    double s11 = 0, s12 = 0, s22 = 0, r1 = 0, r2 = 0;
    for (size_t i = 0; i < X.size(); ++i) {
      const double c = Wt[i] / Y[i], x1 = ua ? X[i] * c : 0, x2 = ub ? c : 0;
      s11 += x1 * x1; s12 += x1 * x2; s22 += x2 * x2; r1 += x1 * Wt[i]; r2 += x2 * Wt[i];
    }
    *a = *b = 0;
    if (ua && ub) {
      const double det = s11 * s22 - s12 * s12;
      if (std::fabs(det) < 1e-300) return false;
      *a = (r1 * s22 - r2 * s12) / det;
      *b = (s11 * r2 - s12 * r1) / det;
    } else if (ua) {
      *a = s11 > 0 ? r1 / s11 : 0;
    } else {
      *b = s22 > 0 ? r2 / s22 : 0;
    }
    return *a >= 0 && *b >= 0;
  };
  auto resid = [&](double a, double b) {
    double r = 0;
    for (size_t i = 0; i < X.size(); ++i) {
      const double e = (a * X[i] + b) * Wt[i] / Y[i] - Wt[i];
      r += e * e;
    }
    return r;
  };
  double best = 1e300, ba = 0, bb = 1e-10;
  const bool sets[3][2] = {{true, true}, {true, false}, {false, true}};
  for (const auto& st : sets) {
    double a, b;
    if (fit(st[0], st[1], &a, &b)) {
      const double r = resid(a, b);
      if (r < best) { best = r; ba = a; bb = b; }
    }
  }
  if (bins_out) *bins_out = int(X.size());
  *a_out = float(ba);
  *b_out = float(std::max(bb, 1e-10));
}

void Grey(const float* raw, int h, int w, float* out) {
  std::vector<float> rgb(size_t(3) * h * w);
  DemosaicBilinear(raw, h, w, rgb.data());
  const size_t n = size_t(h) * w;
  for (size_t i = 0; i < n; ++i) out[i] = (rgb[i] + rgb[n + i] + rgb[2 * n + i]) / 3.0f;
}

namespace {

using Img = std::vector<double>;

// scipy.ndimage.gaussian_filter(sigma=1, truncate=4, mode='reflect')
Img gauss1(const Img& a, int h, int w) {
  double k[9];
  double s = 0;
  for (int i = -4; i <= 4; ++i) s += (k[i + 4] = std::exp(-0.5 * i * i));
  for (double& v : k) v /= s;
  auto refl = [](int i, int n) {
    while (i < 0 || i >= n) i = i < 0 ? -i - 1 : 2 * n - 1 - i;
    return i;
  };
  Img t(a.size()), o(a.size());
  for (int i = 0; i < h; ++i) {
    const double* row = a.data() + size_t(i) * w;
    for (int j = 0; j < w; ++j) {
      double v = 0;
      if (j >= 4 && j + 4 < w) for (int d = -4; d <= 4; ++d) v += k[d + 4] * row[j + d];
      else for (int d = -4; d <= 4; ++d) v += k[d + 4] * row[refl(j + d, w)];
      t[size_t(i) * w + j] = v;
    }
  }
  for (int i = 0; i < h; ++i) {
    const double* rows[9];
    for (int d = -4; d <= 4; ++d) rows[d + 4] = t.data() + size_t(refl(i + d, h)) * w;
    for (int j = 0; j < w; ++j) {
      double v = 0;
      for (int d = 0; d < 9; ++d) v += k[d] * rows[d][j];
      o[size_t(i) * w + j] = v;
    }
  }
  return o;
}

// scipy.ndimage.zoom(a, 0.5, order=1): out n = round(n/2); corner-aligned map.
Img zoomHalf(const Img& a, int h, int w, int* oh, int* ow) {
  const int H = int(std::lround(h * 0.5)), W = int(std::lround(w * 0.5));
  Img o(size_t(H) * W);
  const double sy = H > 1 ? double(h - 1) / (H - 1) : 0, sx = W > 1 ? double(w - 1) / (W - 1) : 0;
  for (int i = 0; i < H; ++i) {
    const double y = i * sy;
    const int y0 = std::min(int(y), h - 1), y1 = std::min(y0 + 1, h - 1);
    const double fy = y - y0;
    for (int j = 0; j < W; ++j) {
      const double x = j * sx;
      const int x0 = std::min(int(x), w - 1), x1 = std::min(x0 + 1, w - 1);
      const double fx = x - x0;
      o[size_t(i) * W + j] = (a[size_t(y0) * w + x0] * (1 - fx) + a[size_t(y0) * w + x1] * fx) * (1 - fy) +
                             (a[size_t(y1) * w + x0] * (1 - fx) + a[size_t(y1) * w + x1] * fx) * fy;
    }
  }
  *oh = H;
  *ow = W;
  return o;
}

// lkr._resample: sample img at A u + t (pixel centres), python validity rule.
double sampleAt(const Img& img, int h, int w, double qx, double qy, bool* valid) {
  const double fx = qx - 0.5, fy = qy - 0.5;
  const int x0 = int(std::floor(fx)), y0 = int(std::floor(fy));
  *valid = x0 >= 0 && y0 >= 0 && x0 + 1 < w && y0 + 1 < h;
  if (!*valid) return 0.0;
  const double ax = fx - x0, ay = fy - y0;
  const size_t o = size_t(y0) * w + x0;
  return (img[o] * (1 - ax) + img[o + 1] * ax) * (1 - ay) + (img[o + w] * (1 - ax) + img[o + w + 1] * ax) * ay;
}

bool solve6(double A[6][6], double b[6], double x[6]) {
  double M[6][7];
  for (int i = 0; i < 6; ++i) {
    for (int j = 0; j < 6; ++j) M[i][j] = A[i][j];
    M[i][6] = b[i];
  }
  for (int c = 0; c < 6; ++c) {
    int p = c;
    for (int r = c + 1; r < 6; ++r) if (std::fabs(M[r][c]) > std::fabs(M[p][c])) p = r;
    if (std::fabs(M[p][c]) < 1e-300) return false;
    for (int k = 0; k < 7; ++k) std::swap(M[c][k], M[p][k]);
    for (int r = c + 1; r < 6; ++r) {
      const double f = M[r][c] / M[c][c];
      for (int k = c; k < 7; ++k) M[r][k] -= f * M[c][k];
    }
  }
  for (int i = 5; i >= 0; --i) {
    double v = M[i][6];
    for (int j = i + 1; j < 6; ++j) v -= M[i][j] * x[j];
    x[i] = v / M[i][i];
  }
  return true;
}

}  // namespace

// Bilinear tap of sampleAt for a position known to be valid.
// The same bilinear expression as tap(), on component c of the interleaved (r, gx, gy) array.
static inline double tap3(const double* a, size_t o, int w, int c, double ax, double ay) {
  const double* p = a + 3 * o + c;
  return (p[0] * (1 - ax) + p[3] * ax) * (1 - ay) + (p[3 * size_t(w)] * (1 - ax) + p[3 * size_t(w) + 3] * ax) * ay;
}
static inline double tap(const double* a, size_t o, int w, double ax, double ay) {
  return (a[o] * (1 - ax) + a[o + 1] * ax) * (1 - ay) + (a[o + w] * (1 - ax) + a[o + w + 1] * ax) * ay;
}

LkRef LkPrepare(const float* ref, int h, int w, int levels) {
  LkRef R;
  R.r.resize(size_t(levels));
  R.gx.resize(size_t(levels));
  R.gy.resize(size_t(levels));
  R.hs.resize(size_t(levels));
  R.ws.resize(size_t(levels));
  R.r[0].assign(ref, ref + size_t(h) * w);
  R.hs[0] = h;
  R.ws[0] = w;
  for (int l = 1; l < levels; ++l)
    R.r[size_t(l)] = zoomHalf(gauss1(R.r[size_t(l - 1)], R.hs[size_t(l - 1)], R.ws[size_t(l - 1)]), R.hs[size_t(l - 1)],
                              R.ws[size_t(l - 1)], &R.hs[size_t(l)], &R.ws[size_t(l)]);
  for (int l = 0; l < levels; ++l) {   // np.gradient (central, one-sided edges)
    const int H = R.hs[size_t(l)], W = R.ws[size_t(l)];
    const Img& r = R.r[size_t(l)];
    Img& gx = R.gx[size_t(l)];
    Img& gy = R.gy[size_t(l)];
    gx.resize(r.size());
    gy.resize(r.size());
    for (int i = 0; i < H; ++i)
      for (int j = 0; j < W; ++j) {
        const size_t o = size_t(i) * W + j;
        gx[o] = j == 0 ? r[o + 1] - r[o] : (j == W - 1 ? r[o] - r[o - 1] : 0.5 * (r[o + 1] - r[o - 1]));
        gy[o] = i == 0 ? r[o + W] - r[o] : (i == H - 1 ? r[o] - r[o - W] : 0.5 * (r[o + W] - r[o - W]));
      }
  }
  R.rgg.resize(R.r.size());
  for (size_t l = 0; l < R.r.size(); ++l) {
    const size_t n = R.r[l].size();
    R.rgg[l].resize(3 * n);
    for (size_t q = 0; q < n; ++q) {
      R.rgg[l][3 * q] = R.r[l][q];
      R.rgg[l][3 * q + 1] = R.gx[l][q];
      R.rgg[l][3 * q + 2] = R.gy[l][q];
    }
  }
  return R;
}

double LkResidual(const LkRef& ref, const float* mov, const std::array<double, 6>& p) {
  const int h = ref.hs[0], w = ref.ws[0];
  const double* r = ref.r[0].data();
  double e = 0;
  size_t n = 0;
  for (int i = 0; i < h; ++i)
    for (int j = 0; j < w; ++j) {
      const double ux = j + 0.5, uy = i + 0.5;
      const double fx = p[0] * ux + p[1] * uy + p[2] - 0.5, fy = p[3] * ux + p[4] * uy + p[5] - 0.5;
      const int x0 = int(std::floor(fx)), y0 = int(std::floor(fy));
      if (!(x0 >= 0 && y0 >= 0 && x0 + 1 < w && y0 + 1 < h)) continue;
      e += std::fabs(tap(r, size_t(y0) * w + x0, w, fx - x0, fy - y0) - mov[size_t(i) * w + j]);
      ++n;
    }
  return n ? e / n : 1e30;
}

double LkResidual(const float* ref, const float* mov, int h, int w, const std::array<double, 6>& p) {
  return LkResidual(LkPrepare(ref, h, w, 1), mov, p);
}

std::vector<std::vector<double>> LkMovPyramid(const LkRef& ref, const float* mov) {
  const int levels = int(ref.r.size());
  std::vector<Img> M(static_cast<size_t>(levels));
  M[0].assign(mov, mov + size_t(ref.hs[0]) * ref.ws[0]);
  for (int l = 1; l < levels; ++l) {
    int nh, nw;
    M[size_t(l)] = zoomHalf(gauss1(M[size_t(l - 1)], ref.hs[size_t(l - 1)], ref.ws[size_t(l - 1)]), ref.hs[size_t(l - 1)],
                            ref.ws[size_t(l - 1)], &nh, &nw);
  }
  return M;
}

bool LkSolve6(double A[6][6], double b[6], double x[6]) { return solve6(A, b, x); }

std::array<double, 6> LkAffine(const LkRef& ref, const float* mov, int iters, const std::array<double, 6>* p0) {
  const int levels = int(ref.r.size());
  std::array<double, 6> p{1, 0, 0, 0, 1, 0};
  if (p0) p = *p0;
  std::vector<Img> M(static_cast<size_t>(levels));
  M[0].assign(mov, mov + size_t(ref.hs[0]) * ref.ws[0]);
  for (int l = 1; l < levels; ++l) {
    int nh, nw;
    M[size_t(l)] = zoomHalf(gauss1(M[size_t(l - 1)], ref.hs[size_t(l - 1)], ref.ws[size_t(l - 1)]), ref.hs[size_t(l - 1)],
                            ref.ws[size_t(l - 1)], &nh, &nw);
  }
  p[2] /= std::pow(2.0, levels - 1);
  p[5] /= std::pow(2.0, levels - 1);
  for (int lv = levels - 1; lv >= 0; --lv) {
    const int H = ref.hs[size_t(lv)], W = ref.ws[size_t(lv)];
    const double* rg = ref.rgg[size_t(lv)].data();
    const double* m = M[size_t(lv)].data();
    for (int it = 0; it < iters; ++it) {
      // J = (sx ux, sx uy, sx, sy ux, sy uy, sy): J J^T and J e are the three gradient products
      // (and two residual products) times monomials of (ux, uy). uy is constant along a row, so a
      // row accumulates only the ux moments and the uy factors are applied once per row.
      double A[6][6] = {}, b[6] = {};
      for (int i = 0; i < H; ++i) {
        const double uy = i + 0.5;
        double xx[3] = {}, xy[3] = {}, yy[3] = {}, ex[2] = {}, ey[2] = {};   // sums of q, q ux, q ux^2
        const double bx = p[1] * uy + p[2] - 0.5, by = p[4] * uy + p[5] - 0.5;
        const double* mrow = m + size_t(i) * W;
        for (int j = 0; j < W; ++j) {
          const double ux = j + 0.5;
          const double fx = p[0] * ux + bx, fy = p[3] * ux + by;
          if (fx < 0 || fy < 0) continue;
          const int x0 = int(fx), y0 = int(fy);
          if (x0 + 1 >= W || y0 + 1 >= H) continue;
          const double ax = fx - x0, ay = fy - y0;
          const size_t o = size_t(y0) * W + x0;
          const double wr = tap3(rg, o, W, 0, ax, ay), sx = tap3(rg, o, W, 1, ax, ay), sy = tap3(rg, o, W, 2, ax, ay);
          const double e = mrow[j] - wr;
          const double qxx = sx * sx, qxy = sx * sy, qyy = sy * sy, qex = sx * e, qey = sy * e;
          const double u2 = ux * ux;
          xx[0] += qxx; xx[1] += qxx * ux; xx[2] += qxx * u2;
          xy[0] += qxy; xy[1] += qxy * ux; xy[2] += qxy * u2;
          yy[0] += qyy; yy[1] += qyy * ux; yy[2] += qyy * u2;
          ex[0] += qex; ex[1] += qex * ux;
          ey[0] += qey; ey[1] += qey * ux;
        }
        const double v2 = uy * uy;
        A[0][0] += xx[2]; A[0][1] += xx[1] * uy; A[0][2] += xx[1]; A[1][1] += xx[0] * v2; A[1][2] += xx[0] * uy; A[2][2] += xx[0];
        A[0][3] += xy[2]; A[0][4] += xy[1] * uy; A[0][5] += xy[1]; A[1][3] += xy[1] * uy; A[1][4] += xy[0] * v2;
        A[1][5] += xy[0] * uy; A[2][3] += xy[1]; A[2][4] += xy[0] * uy; A[2][5] += xy[0];
        A[3][3] += yy[2]; A[3][4] += yy[1] * uy; A[3][5] += yy[1]; A[4][4] += yy[0] * v2; A[4][5] += yy[0] * uy; A[5][5] += yy[0];
        b[0] += ex[1]; b[1] += ex[0] * uy; b[2] += ex[0];
        b[3] += ey[1]; b[4] += ey[0] * uy; b[5] += ey[0];
      }
      for (int a = 0; a < 6; ++a) {
        for (int c = 0; c < a; ++c) A[a][c] = A[c][a];
        A[a][a] += 1e-6;
      }
      double dp[6];
      if (!solve6(A, b, dp)) break;
      for (int a = 0; a < 6; ++a) p[a] += dp[a];
      if (std::max(std::fabs(dp[2]), std::fabs(dp[5])) < 1e-3) { if (std::getenv("LKRL_LK_STATS")) std::fprintf(stderr, "lkstat lv%d W%d it%d\n", lv, W, it + 1); break; }
      if (it + 1 == iters && std::getenv("LKRL_LK_STATS")) std::fprintf(stderr, "lkstat lv%d W%d it%d cap\n", lv, W, it + 1);
    }
    if (lv) {
      p[2] *= 2;
      p[5] *= 2;
    }
  }
  return p;
}

std::array<double, 6> LkAffine(const float* ref, const float* mov, int h, int w, int iters, int levels,
                               const std::array<double, 6>* p0) {
  return LkAffine(LkPrepare(ref, h, w, levels), mov, iters, p0);
}

}  // namespace lkrl
