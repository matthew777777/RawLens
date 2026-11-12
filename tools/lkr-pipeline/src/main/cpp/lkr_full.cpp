// SPDX-License-Identifier: Apache-2.0
#include "lkr_full.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <cmath>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <thread>

#include "lkr_blocksearch.h"
#include "lkr_pack.h"
#include "lkr_host.h"

namespace lkr {

namespace {

double now_ms() {
  using namespace std::chrono;
  return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

struct Grid {          // RGGB-phase LR working grid inside the sensor
  int x0, y0, w, h;    // sensor origin (R at (x0,y0)) and even size
};

// Normalised RGGB-phase tile (n x n at grid offset (gx, gy)) of one frame.
// Samples [x0, x0 + n) of sensor row y: straight from a plain frame, unpacked into tmp from a staged one.
inline const uint16_t* RowSamples(const RawFrame& f, int y, int x0, int n, uint16_t* tmp) {
  if (f.packed_bits <= 0) return f.data + size_t(y) * f.row_stride_px + x0;
  const uint8_t* base = reinterpret_cast<const uint8_t*>(f.data);
  UnpackRow(base + size_t(y) * PackedRowBytes(f.row_stride_px, f.packed_bits), x0, n, f.packed_bits, tmp);
  return tmp;
}

void LoadTile(const RawFrame& f, const Grid& g, int gx, int gy, int tw, int th, const float black[4],
              float white, float* out) {
  std::vector<uint16_t> unpacked(f.packed_bits > 0 ? size_t(tw) : 0);
  for (int i = 0; i < th; ++i) {
    const int y = g.y0 + gy + i;
    const uint16_t* row = RowSamples(f, y, g.x0 + gx, tw, unpacked.data());
    for (int j = 0; j < tw; ++j) {
      const int x = g.x0 + gx + j;
      const int cell = ((y & 1) << 1) | (x & 1);
      out[size_t(i) * tw + j] = (float(row[j]) - black[cell]) / (white - black[cell]);
    }
  }
}

// Grey at 1/bin of the grid (bin even): mean of all bin x bin raw samples
// (each 2x2 cell contributes R + 2G + B equally, i.e. luminance-like).
std::vector<float> BinnedGrey(const RawFrame& f, const Grid& g, int bin, const float black[4], float white,
                              int* bw, int* bh) {
  *bw = g.w / bin;
  *bh = g.h / bin;
  std::vector<float> out(size_t(*bw) * *bh);
  if (f.packed_bits > 0) {   // staged frame: unpack each row once, same sums in the same order
    const int wn = *bw * bin;
    std::vector<uint16_t> rows(size_t(bin) * wn);
    for (int bi = 0; bi < *bh; ++bi) {
      for (int i = 0; i < bin; ++i) RowSamples(f, g.y0 + bi * bin + i, g.x0, wn, rows.data() + size_t(i) * wn);
      for (int bj = 0; bj < *bw; ++bj) {
        double s = 0;
        for (int i = 0; i < bin; ++i) {
          const int y = g.y0 + bi * bin + i;
          const uint16_t* row = rows.data() + size_t(i) * wn;
          for (int j = 0; j < bin; ++j) {
            const int x = g.x0 + bj * bin + j;
            const int cell = ((y & 1) << 1) | (x & 1);
            s += (double(row[bj * bin + j]) - black[cell]) / (white - black[cell]);
          }
        }
        out[size_t(bi) * *bw + bj] = float(s / (bin * bin));
      }
    }
    return out;
  }
  for (int bi = 0; bi < *bh; ++bi)
    for (int bj = 0; bj < *bw; ++bj) {
      double s = 0;
      for (int i = 0; i < bin; ++i) {
        const int y = g.y0 + bi * bin + i;
        const uint16_t* row = f.data + size_t(y) * f.row_stride_px;
        for (int j = 0; j < bin; ++j) {
          const int x = g.x0 + bj * bin + j;
          const int cell = ((y & 1) << 1) | (x & 1);
          s += (double(row[x]) - black[cell]) / (white - black[cell]);
        }
      }
      out[size_t(bi) * *bw + bj] = float(s / (bin * bin));
    }
  return out;
}

}  // namespace

// In-place separable box blur of a square grey patch (radius r, clamped edges).
static void BoxBlurGrey(std::vector<float>* a, int n, int r) {
  std::vector<float> t(size_t(n) * n);
  for (int pass = 0; pass < 2; ++pass) {
    const std::vector<float>& s = pass == 0 ? *a : t;
    std::vector<float>& d = pass == 0 ? t : *a;
    for (int i = 0; i < n; ++i)
      for (int j = 0; j < n; ++j) {
        double acc = 0;
        for (int k = -r; k <= r; ++k) {
          const int jj = std::min(std::max(j + k, 0), n - 1);
          acc += pass == 0 ? s[size_t(i) * n + jj] : s[size_t(jj) * n + i];
        }
        d[pass == 0 ? size_t(i) * n + j : size_t(j) * n + i] = float(acc / (2 * r + 1));
      }
  }
}

bool MergeFull(Gpu* gpu, const std::vector<RawFrame>& frames_in, int W, int H, const int cfa[4],
               const float black[4], float white, const std::array<float, 3>& wb, const FullParams& prm,
               const PriorFactory& make_prior, std::vector<float>* rgb_out, int* out_w, int* out_h,
               FullStats* st, std::string* err) {
  const double t0 = now_ms();
  FullStats S;
  std::vector<RawFrame> frames = frames_in;   // reordered below: reference first
  const int K = int(frames.size());
  if (K < 2 || W < 64 || H < 64) {
    if (err) *err = "need >= 2 frames of >= 64 px";
    return false;
  }
  const bool holdout = prm.holdout >= 0;
  if (holdout) {   // held-out frame last: every per-frame step below treats it like the others
    if (K < 3 || prm.holdout < 1 || prm.holdout >= K) {
      if (err) *err = "holdout needs >= 3 frames and an index in 1..K-1";
      return false;
    }
    std::rotate(frames.begin() + prm.holdout, frames.begin() + prm.holdout + 1, frames.end());
  }
  int ox = -1, oy = -1;
  for (int k = 0; k < 4; ++k)
    if (cfa[k] == 0) { ox = k & 1; oy = k >> 1; }
  if (ox < 0) {
    if (err) *err = "unsupported CFA";
    return false;
  }
  // Region (sensor px) and its RGGB-phase grid.
  int rx = 0, ry = 0, rw = W, rh = H;
  if (prm.crop_w > 0 && prm.crop_h > 0) {
    rw = std::min(prm.crop_w, W);
    rh = std::min(prm.crop_h, H);
    rx = std::clamp(prm.crop_x < 0 ? (W - rw) / 2 : prm.crop_x, 0, W - rw);
    ry = std::clamp(prm.crop_y < 0 ? (H - rh) / 2 : prm.crop_y, 0, H - rh);
  }
  Grid g;
  // Phase-align by stepping LEFT/UP one sample when needed, so the grid keeps
  // the full even size of the region (fixed tile shapes,
  // stay exact); step right only at the sensor edge.
  g.x0 = rx - ((rx - ox) & 1);
  g.y0 = ry - ((ry - oy) & 1);
  if (g.x0 < 0) g.x0 += 2;
  if (g.y0 < 0) g.y0 += 2;
  g.w = std::min(rw & ~1, (W - g.x0) & ~1);
  g.h = std::min(rh & ~1, (H - g.y0) & ~1);
  const int s = prm.s;

  // 0) Sharpest frame as the reference (moved to index 0; the rest keep order).
  if (prm.sharpest_ref && !prm.paper_parity && K > 1 && !holdout) {
    std::vector<double> sharp(size_t(K), 0.0);
    std::vector<std::thread> pool;
    const int nthr = std::max(1, std::min(K, int(std::thread::hardware_concurrency()) / 2));
    for (int tt = 0; tt < nthr; ++tt)
      pool.emplace_back([&, tt] {
        for (int k = tt; k < K; k += nthr) {
          int bw = 0, bh = 0;
          const std::vector<float> gk = BinnedGrey(frames[size_t(k)], g, 2, black, white, &bw, &bh);
          double lap = 0.0, mean = 0.0;
          for (int y = 1; y + 1 < bh; ++y)
            for (int x = 1; x + 1 < bw; ++x) {
              const size_t i = size_t(y) * bw + x;
              lap += std::fabs(4.0 * gk[i] - gk[i - 1] - gk[i + 1] - gk[i - bw] - gk[i + bw]);
              mean += gk[i];
            }
          sharp[size_t(k)] = mean > 0 ? lap / mean : 0.0;
        }
      });
    for (auto& th : pool) th.join();
    int best = 0;
    for (int k = 1; k < K; ++k) if (sharp[size_t(k)] > sharp[size_t(best)] * 1.005) best = k;   // ties keep the first
    S.ref_index = best;
    if (best != 0) std::rotate(frames.begin(), frames.begin() + best, frames.begin() + best + 1);
  }

  // 1) Our own noise fit over the WHOLE reference (a centre crop can be
  // all fine texture -- a sunlit rock slope read as 2x the noise, which made
  // the robust threshold too lenient and smeared a moving car).
  // Runs beside the coarse alignment below, which does not use it.
  double t = now_ms();
  std::thread noise_fit([&] {
    const double tn = now_ms();
    std::vector<float> c(size_t(g.w) * g.h);
    LoadTile(frames[0], g, 0, 0, g.w, g.h, black, white, c.data());
    S.sigma = NoiseSigma(c.data(), g.h, g.w);
    int bins = 0;
    NoiseAB(c.data(), g.h, g.w, &S.a, &S.b, &bins);
    S.fit_a = S.a;
    S.fit_b = S.b;
    // Learned per-device model (app): prior mixed with this burst's fit by its
    // confidence, as HDR+ does. Before the degenerate-fit fallback, so a
    // trained model replaces a collapsed fit instead of the MAD sigma.
    if (prm.noise_refine && prm.noise_refine(&S.a, &S.b, bins)) S.noise_learned = true;
    // Degenerate fit (too few unclipped flat blocks): a zero noise model makes
    // every robust / temporal test reject all frames. Fall back to the
    // level-independent MAD sigma as read noise.
    if (S.a <= 0 && S.b < 0.25f * S.sigma * S.sigma) S.b = S.sigma * S.sigma;
    S.ms_noise = now_ms() - tn;
  });

  // 2) Global coarse LK on a binned grey frame (threads over frames).
  t = now_ms();
  const int bin = std::max(g.w, g.h) >= 2048 ? 4 : 2;
  std::vector<std::array<double, 6>> P(K, {1, 0, 0, 0, 1, 0});
  // Per-frame exposure gain (AE steps inside a burst: up to 4% measured),
  // ratio of medians of the binned grey frames -- an uncompensated step
  // biases the merge and makes every robust / temporal test see "motion".
  std::vector<float> gain(K, 1.0f);
  auto median_of = [](const std::vector<float>& v) {
    std::vector<float> u;
    u.reserve(v.size());
    for (float x : v) if (x > 0.01f && x < 0.9f) u.push_back(x);
    if (u.empty()) return 0.0f;
    std::nth_element(u.begin(), u.begin() + u.size() / 2, u.end());
    return u[u.size() / 2];
  };
  auto alignmentGrey = [&](const RawFrame& frame, int* bw, int* bh) {
    if (!prm.paper_parity) return BinnedGrey(frame, g, bin, black, white, bw, bh);
    // Paper section 3.4: coarse forward-additive LK on bilinear-demosaiced
    // grayscale, rather than averaging Bayer cells before demosaicking.
    *bw = g.w; *bh = g.h;
    std::vector<float> raw(size_t(g.w)*g.h), grey(raw.size());
    LoadTile(frame, g, 0, 0, g.w, g.h, black, white, raw.data());
    Grey(raw.data(), g.h, g.w, grey.data());
    return grey;
  };
  {
    int bw = 0, bh = 0;
    std::vector<float> ref = alignmentGrey(frames[0], &bw, &bh);
    const float mref = median_of(ref);
    const LkRef lref = LkPrepare(ref.data(), bh, bw, 3);
    const int nthr = std::max(1, std::min(K - 1, int(std::thread::hardware_concurrency()) / 2));
    std::vector<std::thread> pool;
    for (int tt = 0; tt < nthr; ++tt)
      pool.emplace_back([&, tt] {
        for (int k = 1 + tt; k < K; k += nthr) {
          int w2, h2;
          std::vector<float> mov = alignmentGrey(frames[k], &w2, &h2);
          if (!prm.paper_parity) {
            const float mm = median_of(mov);
            if (mref > 0 && mm > 0) {
              const float gk = mref / mm;
              if (gk > 0.8f && gk < 1.25f && std::fabs(gk - 1.0f) > 0.002f) {
                gain[k] = gk;
                for (float& v : mov) v *= gk;
              }
            }
          }
          std::array<double, 6> q = LkAffine(lref, mov.data(), 40);
          const int alignment_scale = prm.paper_parity ? 1 : bin;
          q[2] *= alignment_scale;
          q[5] *= alignment_scale;
          P[k] = q;
        }
      });
    for (auto& th : pool) th.join();
  }
  noise_fit.join();
  S.ms_coarse = now_ms() - t;   // wall, noise fit included

  // 3) Overlapping tiles, feathered accumulation into the interleaved output.
  t = now_ms();
  const PriorFn prior = make_prior ? make_prior(S.a, S.b, S.sigma, wb) : PriorFn();
  // The DCT x-step needs the solved tile (s * tile) to be a multiple of 4.
  const int tile = std::min({prm.tile, g.w, g.h}) & (((prm.solve.dct_prior || prm.solve.dct_fallback) && prm.s == 1) ? ~3 : ~1);
  const int ov = std::min(prm.overlap, tile / 4) & ~1;
  const int step = tile - ov;
  auto starts = [&](int len) {
    std::vector<int> v;
    if (len <= tile) { v.push_back(0); return v; }
    for (int p = 0; p + tile < len; p += step) v.push_back(p);
    v.push_back(len - tile);   // even: len, tile even
    return v;
  };
  const std::vector<int> xs = starts(g.w), ys = starts(g.h);
  const int GW = s * g.w, GH = s * g.h;
  *out_w = s * rw;
  *out_h = s * rh;
  // A caller-owned output (the app hands this straight to Java) avoids a final full-image copy.
  float* const rgb = prm.out_alloc ? prm.out_alloc(*out_w, *out_h) : nullptr;
  if (rgb) rgb_out->clear(); else rgb_out->assign(size_t(*out_w) * *out_h * 3, 0.0f);
  float* const rgb_data = rgb ? rgb : rgb_out->data();
  // Diagnostics: LKR_DEBUG_MAPS=<path> -> <path> (kDebugMaps x GH x GW float32,
  // see SolveStats::dbg), feather-blended across tiles like the image.
  const char* dbg_path = std::getenv("LKR_DEBUG_MAPS");
  std::vector<float> dmaps;
  if (dbg_path) dmaps.assign(size_t(kDebugMaps) * GW * GH, 0.0f);
  // Grid -> output offset (HR px): the grid starts (g.x0 - rx) sensor px in.
  const int offx = s * (g.x0 - rx), offy = s * (g.y0 - ry);
  SolveParams sp = prm.solve;
  sp.s = s;
  sp.sigma_noise = S.sigma;
  sp.sigma_max = prm.smax_x * S.sigma;
  sp.sigma_end = prm.end_x * S.sigma;
  sp.wb = wb;
  if (prm.paper_parity) {
    sp.prior_gain = 1.0f;
    sp.grain = 0.0f;
    sp.notch = false;
    sp.clip = 1e9f;
    sp.robust = false;
    sp.equalize_noise = false;
    sp.local_block = 0;
    sp.robust_spread = 0;
    sp.mean_preserve = 0;
    sp.temporal_k = 0;
    sp.shadow_boost = 0;
    sp.shadow_chroma = 0;
    sp.highlight_desat = false;
    sp.motion_every = 1;
    sp.mhc_init = false;
  }
  sp.noise_a = S.a;
  sp.noise_b = S.b;
  sp.holdout = holdout;
  if (holdout) {
    S.holdout_w = g.w;
    S.holdout_h = g.h;
    S.holdout.assign(size_t(3) * g.w * g.h, 0.0f);
  }
  float look_t = 0.0f;   // 0 clean daylight .. 1 night (auto look), also gates sub-tile alignment
  if (prm.auto_look && !prm.paper_parity) {
    // Look level from OUR noise MODEL at mid-grey (flat-block fit, not fooled
    // by fine texture like the MAD sigma): day <= 0.0025, night >= 0.012.
    const float sl = S.a > 0 ? std::sqrt(S.a * 0.18f + S.b) : S.sigma;
    S.sigma_look = sl;
    const float t = std::clamp(float((std::log(std::max(sl, 1e-6f)) - std::log(0.0025f)) /
                                     (std::log(0.012f) - std::log(0.0025f))), 0.0f, 1.0f);
    sp.prior_gain = prm.look_day_prior + t * (prm.look_night_prior - prm.look_day_prior);
    sp.grain = prm.look_day_grain + t * (prm.look_night_grain - prm.look_day_grain);
    sp.shadow_boost = prm.solve.shadow_boost * (1.0f - t);   // daylight shadows only
    look_t = t;
    sp.equalize_strength = prm.equalize_day + t * (prm.equalize_night - prm.equalize_day);
    sp.robust_k = prm.robust_k_day + t * (prm.robust_k_night - prm.robust_k_day);
    sp.robust_pow = prm.robust_pow_day + t * (prm.robust_pow_night - prm.robust_pow_day);
    sp.shadow_chroma = prm.solve.shadow_chroma * (1.0f - t);
  }
  // Local-motion regularisation follows OUR noise level: weak in clean
  // daylight (small sways count), stiff at high ISO where per-block
  // Gauss-Newton would otherwise fit noise (flat areas got noisier).
  if (prm.auto_local_lambda && !prm.paper_parity) {
    const float sl = S.a > 0 ? std::sqrt(S.a * 0.18f + S.b) : S.sigma;
    sp.local_lambda = std::min(5.0f, sp.local_lambda * std::max(1.0f, (sl / 0.0025f) * (sl / 0.0025f)));
  }
  S.local_lambda = sp.local_lambda;
  S.prior_gain = sp.prior_gain;
  S.grain = sp.grain;
  const int n = tile, HN = s * n, ovh = s * ov;
  struct TileJob { int gx, gy; bool L, R, T, B; };
  std::vector<TileJob> jobs;
  for (size_t yi = 0; yi < ys.size(); ++yi)
    for (size_t xi = 0; xi < xs.size(); ++xi)
      jobs.push_back({xs[xi], ys[yi], xi > 0, xi + 1 < xs.size(), yi > 0, yi + 1 < ys.size()});
  // Feather weights are a row factor times a column factor on a regular tile
  // grid, so their per-pixel sum is (sum over tile rows) x (sum over tile
  // columns): two small tables instead of a full-resolution weight map.
  auto wgt = [&](int v, bool lo, bool hi) {
    float w = 1.0f;
    if (lo && ovh > 0) w = std::min(w, (v + 0.5f) / ovh);
    if (hi && ovh > 0) w = std::min(w, (HN - v - 0.5f) / ovh);
    return std::max(w, 1e-4f);
  };
  std::vector<float> wrow(size_t(GH), 0.0f), wcol(size_t(GW), 0.0f);
  for (size_t yi = 0; yi < ys.size(); ++yi)
    for (int Y = 0; Y < HN; ++Y) wrow[size_t(s * ys[yi] + Y)] += wgt(Y, yi > 0, yi + 1 < ys.size());
  for (size_t xi = 0; xi < xs.size(); ++xi)
    for (int X = 0; X < HN; ++X) wcol[size_t(s * xs[xi] + X)] += wgt(X, xi > 0, xi + 1 < xs.size());

  // Two workers, each with its own Vulkan context and solver buffers: while
  // one waits on the prior, the other keeps the GPU busy. Tiles are
  // blended strictly in job order (ticket), so the output is deterministic.
  int nwork = std::max(1, std::min<int>(prm.workers, int(jobs.size())));
  if (prm.solver_budget_mb > 0) {
    // 11 HR float planes x 3 colours + 13 LR float buffers per frame (lkr_solver.cpp cache).
    const double hr = 3.0 * HN * HN, lr = double(K) * n * n;
    const double per_mb = (hr * 44.0 + lr * 52.0) / (1024.0 * 1024.0);
    nwork = std::clamp(int(prm.solver_budget_mb / per_mb), std::min(std::max(prm.min_workers, 1), nwork), nwork);
  }
  const double t_setup = now_ms();
  std::vector<std::unique_ptr<Gpu>> own;
  std::vector<Gpu*> gpus{gpu};
  for (int w = 1; w < nwork; ++w) {
    own.emplace_back(new Gpu);
    if (!own.back()->ok()) { own.pop_back(); break; }
    gpus.push_back(own.back().get());
  }
  S.workers = int(gpus.size());
  S.ms_setup = now_ms() - t_setup;
  std::atomic<size_t> next{0};
  std::mutex mu;
  std::condition_variable cv;
  size_t ticket = 0;
  bool failed = false;
  std::string fail_msg, prof_names;
  // A finished tile: blended strictly in job order (deterministic sums where tiles overlap), but
  // by whichever worker closes the gap, so nobody idles waiting for an earlier tile.
  struct Done {
    std::vector<float> x, yk;   // solved tile; held-out frame's samples (holdout only)
    SolveStats ss;
  };
  std::map<size_t, Done> pending;
  auto blend = [&](size_t ji, const Done& d) {
    const TileJob& jb = jobs[ji];
    const int gx = jb.gx, gy = jb.gy;
    // Feather only on sides shared with a neighbour tile.
    const size_t HW = size_t(HN) * HN;
    for (int Y = 0; Y < HN; ++Y) {
      const float wy = wgt(Y, jb.T, jb.B);
      const int oyy = s * gy + Y + offy;
      if (oyy < 0 || oyy >= *out_h) continue;
      for (int X = 0; X < HN; ++X) {
        const int oxx = s * gx + X + offx;
        if (oxx < 0 || oxx >= *out_w) continue;
        const float w = wy * wgt(X, jb.L, jb.R);
        float* o = rgb_data + (size_t(oyy) * *out_w + oxx) * 3;
        const size_t i = size_t(Y) * HN + X;
        o[0] += w * d.x[i];
        o[1] += w * d.x[HW + i];
        o[2] += w * d.x[2 * HW + i];
        if (dbg_path && d.ss.dbg.size() == size_t(kDebugMaps) * HW)
          for (int m = 0; m < kDebugMaps; ++m)
            dmaps[size_t(m) * GW * GH + size_t(s * gy + Y) * GW + s * gx + X] += w * d.ss.dbg[size_t(m) * HW + i];
      }
    }
    if (holdout && d.ss.ho_r.size() == size_t(n) * n) {
      // Expected variance of a held-out sample: a * signal + b in the frame's own units, with
      // the prediction as the signal (less noisy than the sample) and the exposure gain undone.
      const float gk = gain[size_t(K - 1)];
      const float* yk = d.yk.data();
      const size_t plane = size_t(g.w) * g.h;
      for (int i = 0; i < n; ++i) {
        const float wy = wgt(s * i, jb.T, jb.B);
        for (int j = 0; j < n; ++j) {
          const size_t q = size_t(i) * n + j;
          if (d.ss.ho_valid[q] < 0.5f) continue;
          const float w = wy * wgt(s * j, jb.L, jb.R);
          const float r = d.ss.ho_r[q];
          const float var = std::max(gk * S.a * std::max(r + yk[q], 0.0f) + gk * gk * S.b, 1e-12f);
          const size_t o = size_t(gy + i) * g.w + gx + j;
          S.holdout[o] += w * r * r / var;
          S.holdout[plane + o] += w;
          S.holdout[2 * plane + o] += w * r / std::sqrt(var);
        }
      }
    }
  };
  auto worker = [&](Gpu* wg) {
    const double t_solver = now_ms();
    Solver solver(wg);
    { std::lock_guard<std::mutex> l(mu); S.ms_setup += now_ms() - t_solver; }
    if (!solver.ok()) {
      std::lock_guard<std::mutex> l(mu);
      failed = true; fail_msg = "solver pipelines unavailable";
      cv.notify_all();
      return;
    }
    if (wg == gpu) { std::lock_guard<std::mutex> l(mu); prof_names = solver.profileNames(); }
    std::vector<float> tf(size_t(K) * n * n), x, field;
    BlockSearch bsearch(wg);
    for (;;) {
      const size_t ji = next.fetch_add(1);
      if (ji >= jobs.size()) return;
      const TileJob& jb = jobs[ji];
      const double tprep = now_ms();
      const int gx = jb.gx, gy = jb.gy;
      const double tl0 = now_ms();
      for (int k = 0; k < K; ++k) {
        float* fk = tf.data() + size_t(k) * n * n;
        LoadTile(frames[k], g, gx, gy, n, n, black, white, fk);
        if (gain[k] != 1.0f)   // clipped samples stay clipped (>= clip -> 1)
          for (size_t i = 0; i < size_t(n) * n; ++i) fk[i] = fk[i] >= prm.solve.clip ? 1.0f : fk[i] * gain[k];
      }
      const double tl1 = now_ms();
      std::vector<float> p(size_t(K) * 6);
      // Per-tile coarse LK, started from the global affine re-expressed in tile
      // coordinates (t' = A o + t - o): one global affine is off by more than
      // the solver's Gauss-Newton basin at the frame corners (rotation, lens
      // distortion, rolling shutter) -> smeared corners. Half-res cell grey,
      // 2 levels; kept only if it lowers the alignment error (flat tiles).
      std::vector<float> gref, gmov;
      const int g2 = n / 2;
      auto cellGrey = [&](const float* t, std::vector<float>& o) {
        o.resize(size_t(g2) * g2);
        for (int ii = 0; ii < g2; ++ii)
          for (int jj = 0; jj < g2; ++jj) {
            const float* r0 = t + size_t(2 * ii) * n + 2 * jj;
            o[size_t(ii) * g2 + jj] = 0.25f * (r0[0] + r0[1] + r0[n] + r0[n + 1]);
          }
      };
      LkRef tref;
      if (prm.tile_lk && !prm.paper_parity) {
        cellGrey(tf.data(), gref);
        tref = LkPrepare(gref.data(), g2, g2, 2);
      }
      // Quadrant size and pre-blur follow the noise level: small, sharp patches in
      // daylight; larger, smoothed ones at night (small noisy patches misaligned
      // and blurred the night treeline).
      const int sub_size = look_t > 0.5f ? prm.sub_lk_night : prm.sub_lk;
      const int sub_blur = int(std::lround(prm.sub_lk_blur_night * look_t));
      const bool use_sub = sub_size >= 64 && prm.tile_lk && !prm.paper_parity && sp.local_block == 16 &&
                           n % sub_size == 0 && look_t <= prm.sub_lk_max_noise;
      const int sub_nbx = (n + 15) / 16, sub_nby = (n + 15) / 16;
      std::vector<float> sub_field;
      if (use_sub) sub_field.assign(size_t(K) * sub_nbx * sub_nby * 2, 0.0f);
      std::vector<LkRef> qref;   // quadrant references, row-major in the order align_frame visits them
      if (use_sub) {
        const int gs = sub_size / 2;
        std::vector<float> sr(size_t(gs) * gs);
        for (int qy = 0; qy + gs <= g2; qy += gs)
          for (int qx = 0; qx + gs <= g2; qx += gs) {
            for (int i = 0; i < gs; ++i)
              for (int j = 0; j < gs; ++j) sr[size_t(i) * gs + j] = gref[size_t(qy + i) * g2 + qx + j];
            if (sub_blur > 0) BoxBlurGrey(&sr, gs, sub_blur);
            qref.push_back(LkPrepare(sr.data(), gs, gs, 2));
          }
      }
      // Frames are independent: the worker plus up to 2 helper threads (3
      // workers x 3 <= 9 threads on the 8-core phone; the solver threads mostly
      // wait on the GPU meanwhile). Per-frame results are identical.
      auto align_frame = [&](int k, std::vector<float>& gm) {
        const auto& q = P[k];
        std::array<double, 6> pt{q[0], q[1], q[0] * gx + q[1] * gy + q[2] - gx,
                                 q[3], q[4], q[3] * gx + q[4] * gy + q[5] - gy};
        if (k > 0 && prm.tile_lk && !prm.paper_parity) {
          cellGrey(tf.data() + size_t(k) * n * n, gm);
          std::array<double, 6> pg{pt[0], pt[1], pt[2] * 0.5, pt[3], pt[4], pt[5] * 0.5};   // LR -> grey px
          const std::array<double, 6> pr = LkAffine(tref, gm.data(), 30, &pg);
          if (LkResidual(tref, gm.data(), pr) < LkResidual(tref, gm.data(), pg))
            pt = {pr[0], pr[1], pr[2] * 2.0, pr[3], pr[4], pr[5] * 2.0};
        }
        for (int c = 0; c < 6; ++c) p[k * 6 + c] = float(pt[c]);
        // Sub-tile alignment (parallax): quadrant affine relative to the tile's,
        // written as the local motion start d(u) = (A' u + t') - (A u + t), LR px.
        if (k > 0 && use_sub) {
          const int gs = sub_size / 2;   // quadrant edge in grey px
          std::vector<float> sm(size_t(gs) * gs);
          size_t qi = 0;
          for (int qy = 0; qy + gs <= g2; qy += gs)
            for (int qx = 0; qx + gs <= g2; qx += gs) {
              const LkRef& sr = qref[qi++];
              for (int i = 0; i < gs; ++i)
                for (int j = 0; j < gs; ++j) sm[size_t(i) * gs + j] = gm[size_t(qy + i) * g2 + qx + j];
              if (sub_blur > 0) BoxBlurGrey(&sm, gs, sub_blur);
              const double tgx = pt[2] * 0.5, tgy = pt[5] * 0.5;   // tile affine in grey px
              std::array<double, 6> ps{pt[0], pt[1], pt[0] * qx + pt[1] * qy + tgx - qx,
                                       pt[3], pt[4], pt[3] * qx + pt[4] * qy + tgy - qy};
              const std::array<double, 6> pq = LkAffine(sr, sm.data(), 30, &ps);
              const double r_new = LkResidual(sr, sm.data(), pq);
              const double r_old = LkResidual(sr, sm.data(), ps);
              if (std::getenv("LKR_DEBUG_SUB"))
                std::fprintf(stderr, "sub k%d q(%d,%d) size %d blur %d: residual %.5f -> %.5f (%s)\n", k, qx, qy,
                             sub_size, sub_blur, r_old, r_new, r_new < prm.sub_lk_gain * r_old ? "kept" : "rejected");
              if (!(r_new < prm.sub_lk_gain * r_old))
                continue;   // no clear improvement: keep the tile affine here
              // Quadrant affine in tile LR coords: t_LR = 2 (t' + o - A' o).
              const double ax = pq[0], bx_ = pq[1], ay = pq[3], by_ = pq[4];
              const double tx = 2.0 * (pq[2] + qx - (ax * qx + bx_ * qy));
              const double ty = 2.0 * (pq[5] + qy - (ay * qx + by_ * qy));
              const int b0x = 2 * qx / 16, b1x = 2 * (qx + gs) / 16, b0y = 2 * qy / 16, b1y = 2 * (qy + gs) / 16;
              for (int by = b0y; by < b1y && by < sub_nby; ++by)
                for (int bx = b0x; bx < b1x && bx < sub_nbx; ++bx) {
                  const double ux = bx * 16 + 8, uy = by * 16 + 8;
                  const double dx = (ax * ux + bx_ * uy + tx) - (pt[0] * ux + pt[1] * uy + pt[2]);
                  const double dy = (ay * ux + by_ * uy + ty) - (pt[3] * ux + pt[4] * uy + pt[5]);
                  const size_t o = (size_t(k) * sub_nby + by) * sub_nbx + bx;
                  sub_field[2 * o] = float(std::max(-24.0, std::min(24.0, dx)));
                  sub_field[2 * o + 1] = float(std::max(-24.0, std::min(24.0, dy)));
                }
            }
        }
      };
      {
        const int nt = (prm.tile_lk && !prm.paper_parity) ? std::min(3, K) : 1;
        std::vector<std::thread> helpers;
        for (int h = 1; h < nt; ++h)
          helpers.emplace_back([&, h] {
            std::vector<float> gm;
            for (int k = h; k < K; k += nt) align_frame(k, gm);
          });
        for (int k = 0; k < K; k += nt) align_frame(k, gmov);
        for (auto& th : helpers) th.join();
      }
      SolveStats ss;
      SolveParams spt = sp;
      spt.debug_maps = dbg_path != nullptr;
      if (const char* trace = std::getenv("LKR_TRACE_PREFIX"); trace &&
          ji == size_t(std::getenv("LKR_TRACE_TILE") ? std::atoi(std::getenv("LKR_TRACE_TILE")) : 0))
        spt.debug_prefix = std::string(trace) + "_tile_" + std::to_string(gx) + "_" + std::to_string(gy);

      if (use_sub) spt.local_init = sub_field.data();   // parallax start (block search below overrides)
      // Local motion start: MFSR block search (align.comp) on affine-prewarped greys.
      if (spt.local_block == 16 && prm.block_search && !prm.paper_parity && bsearch.ok() &&
          bsearch.run(tf.data(), K, n, p.data(), 3, 4, S.sigma, &field))
      {
        spt.local_init = field.data();
        if (std::getenv("LKR_DEBUG_BS")) {
          size_t nz = 0; float mx = 0;
          for (size_t q = 0; q + 1 < field.size(); q += 2) {
            const float m = std::max(std::fabs(field[q]), std::fabs(field[q + 1]));
            nz += m > 0; mx = std::max(mx, m);
          }
          std::fprintf(stderr, "block search: %zu/%zu blocks moved, max %.1f LR px\n", nz, field.size() / 2, mx);
        }
      }
      const double ts = now_ms();
      const bool ok = solver.solve(tf.data(), K, n, n, p.data(), spt, prior, &x, &ss);
      const double solve_ms = now_ms() - ts;
      Done done;
      done.x.swap(x);
      if (holdout) done.yk.assign(tf.data() + size_t(K - 1) * n * n, tf.data() + size_t(K) * n * n);
      const double t_wait = now_ms();
      std::unique_lock<std::mutex> l(mu);
      // Back-pressure: every waiting result holds a tile image.
      cv.wait(l, [&] { return failed || ticket == ji || pending.size() < 2 * gpus.size(); });
      S.ms_wait += now_ms() - t_wait;
      if (failed) return;
      if (!ok) { failed = true; fail_msg = "tile solve failed"; cv.notify_all(); return; }
      S.ms_gpu += ss.gpu_ms;
      S.ms_solve += solve_ms;
      S.ms_prep += ts - tprep;
      S.ms_load += tl1 - tl0;
      S.ms_prior += ss.prior_ms;
      S.ms_init += ss.init_ms;
      S.ms_finish += ss.finish_ms;
      S.prior_calls += ss.prior_calls;
      ++S.tiles;
      done.ss = std::move(ss);
      pending.emplace(ji, std::move(done));
      const double t_blend = now_ms();
      while (!pending.empty() && pending.begin()->first == ticket) {
        blend(ticket, pending.begin()->second);
        pending.erase(pending.begin());
        ++ticket;
      }
      S.ms_blend += now_ms() - t_blend;
      cv.notify_all();
    }
  };
  {
    std::vector<std::thread> th;
    for (size_t w = 1; w < gpus.size(); ++w) th.emplace_back(worker, gpus[w]);
    worker(gpus[0]);
    for (auto& t : th) t.join();

  if (dbg_path && !dmaps.empty()) {
    for (int m = 0; m < kDebugMaps; ++m)
      for (int Y = 0; Y < GH; ++Y)
        for (int X = 0; X < GW; ++X) {
          const float ws = wrow[size_t(Y)] * wcol[size_t(X)];
          if (ws > 0) dmaps[size_t(m) * GW * GH + size_t(Y) * GW + X] /= ws;
        }
    if (FILE* f = std::fopen(dbg_path, "wb")) {
      std::fwrite(dmaps.data(), 4, dmaps.size(), f);
      std::fclose(f);
      std::fprintf(stderr, "debug maps: %d x %d x %d -> %s\n", kDebugMaps, GH, GW, dbg_path);
    }
  }
  }
  if (failed) {
    if (err) *err = fail_msg;
    return false;
  }
  // Normalise; the <= s px border outside the RGGB grid replicates its edge.
  for (int Y = 0; Y < *out_h; ++Y)
    for (int X = 0; X < *out_w; ++X) {
      const int gxp = std::clamp(X - offx, 0, GW - 1), gyp = std::clamp(Y - offy, 0, GH - 1);
      const int sx = gxp + offx, sy = gyp + offy;
      float* o = rgb_data + (size_t(Y) * *out_w + X) * 3;
      const float ws = wrow[size_t(gyp)] * wcol[size_t(gxp)];
      if (sx == X && sy == Y) {
        const float inv = ws > 0 ? 1.0f / ws : 0.0f;
        o[0] *= inv; o[1] *= inv; o[2] *= inv;
      }
    }
  for (int Y = 0; Y < *out_h; ++Y)   // second pass: copy normalised edge samples outward
    for (int X = 0; X < *out_w; ++X) {
      const int sx = std::clamp(X - offx, 0, GW - 1) + offx, sy = std::clamp(Y - offy, 0, GH - 1) + offy;
      if (sx == X && sy == Y) continue;
      float* o = rgb_data + (size_t(Y) * *out_w + X) * 3;
      const float* src = rgb_data + (size_t(sy) * *out_w + sx) * 3;
      o[0] = src[0]; o[1] = src[1]; o[2] = src[2];
    }
  if (holdout) {
    const size_t plane = size_t(g.w) * g.h;
    for (int i = 0; i < g.h; ++i)
      for (int j = 0; j < g.w; ++j) {
        const size_t o = size_t(i) * g.w + j;
        const float wv = S.holdout[plane + o], ws = wrow[size_t(s * i)] * wcol[size_t(s * j)];
        if (wv > 0) { S.holdout[o] /= wv; S.holdout[2 * plane + o] /= wv; }
        S.holdout[plane + o] = ws > 0 ? std::min(wv / ws, 1.0f) : 0.0f;
      }
  }
  S.ms_tiles = now_ms() - t;
  S.gpu_profile = gpu->profileReport();
  if (!S.gpu_profile.empty()) {   // pointer -> shader name, sorted by time
    std::vector<std::pair<double, std::string>> rows;
    size_t pos = 0;
    while (pos < S.gpu_profile.size()) {
      const size_t e = S.gpu_profile.find(' ', pos), c = S.gpu_profile.find(':', pos);
      const std::string ptr = S.gpu_profile.substr(pos, c - pos);
      const double ms = std::atof(S.gpu_profile.c_str() + c + 1);
      const size_t at = prof_names.find("=" + ptr);
      std::string name = ptr;
      if (at != std::string::npos) {
        const size_t b = prof_names.rfind(' ', at);
        name = prof_names.substr(b == std::string::npos ? 0 : b + 1, at - (b == std::string::npos ? 0 : b + 1));
      }
      rows.emplace_back(ms, name);
      if (e == std::string::npos) break;
      pos = e + 1;
    }
    std::sort(rows.rbegin(), rows.rend());
    S.gpu_profile.clear();
    char b[96];
    for (const auto& r : rows) { std::snprintf(b, sizeof(b), "%s %.0f, ", r.second.c_str(), r.first); S.gpu_profile += b; }
  }
  S.ms_total = now_ms() - t0;
  if (st) *st = S;
  return true;
}

}  // namespace lkr
