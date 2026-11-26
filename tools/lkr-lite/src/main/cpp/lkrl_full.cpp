// SPDX-License-Identifier: Apache-2.0
#include "lkrl_full.h"
#include "lkrl_lfc.h"
#include "lkrl_prior.h"

#include <algorithm>
#include <limits>
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

#include "lkrl_blocksearch.h"
#include "lkrl_pack.h"
#include "lkrl_host.h"
#include "lkrl_gpulk.h"
#include "lkrl_patchseed.h"

namespace lkrl { std::atomic<long long> g_prep_us[3];   // prep profile (LKRL_PREP_LOG): tile LK, quadrant LK, patch seed
}
namespace lkrl {

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
  const int x = g.x0 + gx;
  const float range[4] = {white - black[0], white - black[1], white - black[2], white - black[3]};
  for (int i = 0; i < th; ++i) {
    const int y = g.y0 + gy + i;
    const uint16_t* row = RowSamples(f, y, x, tw, unpacked.data());
    float* dst = out + size_t(i) * tw;
    const int first = ((y & 1) << 1) | (x & 1);
    const int second = first ^ 1;
    int j = 0;
    for (; j + 1 < tw; j += 2) {
      dst[j] = (float(row[j]) - black[first]) / range[first];
      dst[j + 1] = (float(row[j + 1]) - black[second]) / range[second];
    }
    if (j < tw) dst[j] = (float(row[j]) - black[first]) / range[first];
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

// Average the PC bilinear RGB guide directly onto Lite's existing alignment
// grid. Three normalized sensor rows suffice, even for packed input.
std::vector<float> BinnedBilinearGrey(const RawFrame& frame, const Grid& g, int bin,
                                      const float black[4], float white, int* bw, int* bh) {
  *bw = g.w / bin;
  *bh = g.h / bin;
  std::vector<float> out(size_t(*bw) * *bh, 0.0f);
  std::vector<float> rows(size_t(3) * g.w);
  std::vector<uint16_t> unpacked(frame.packed_bits > 0 ? size_t(g.w) : 0);
  float range[4];
  for (int c = 0; c < 4; ++c) range[c] = white - black[c];
  auto load = [&](int i) {
    const int y = g.y0 + i;
    const uint16_t* raw = RowSamples(frame, y, g.x0, g.w, unpacked.data());
    float* row = rows.data() + size_t(i % 3) * g.w;
    const int phase = ((y & 1) << 1) | (g.x0 & 1);
    for (int x = 0; x < g.w; ++x) {
      const int c = phase ^ (x & 1);
      row[x] = (float(raw[x]) - black[c]) / range[c];
    }
  };
  if (*bw == 0 || *bh == 0) return out;
  load(0);
  load(1);
  const float inv = 1.0f / float(3 * bin * bin);
  for (int y = 0; y < *bh * bin; ++y) {
    const float* above = rows.data() + size_t((y == 0 ? 1 : y - 1) % 3) * g.w;
    const float* center = rows.data() + size_t(y % 3) * g.w;
    const float* below = rows.data() + size_t((y + 1 == g.h ? y - 1 : y + 1) % 3) * g.w;
    for (int bx = 0; bx < *bw; ++bx) {
      float sum = 0.0f;
      for (int x = bx * bin; x < (bx + 1) * bin; ++x) {
        const int l = x == 0 ? 1 : x - 1, r = x + 1 == g.w ? x - 1 : x + 1;
        const float axial = above[x] + below[x] + center[l] + center[r];
        const float diagonal = above[l] + above[r] + below[l] + below[r];
        // Sum R+G+B from the same normalized 3x3 bilinear kernel as PC.
        sum += ((y ^ x) & 1) ? 0.5f * (center[x] + axial) + 0.125f * diagonal
                             : center[x] + 0.25f * (axial + diagonal);
      }
      out[size_t(y / bin) * *bw + bx] += sum * inv;
    }
    if (y + 2 < g.h) load(y + 2);
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

int AutoTileSize(int w, int h, int s, int overlap, int align) {
  // The grid fitted to this frame (2026-11-20): target 416 at 1x, 320 at 2x (Mali-G615 cache), up to 5 %
  // above it when that saves a row or column, then the smallest tile giving that grid (the last tile no
  // longer overlaps its neighbour by most of its width). 4080 x 3060 at 1x: 11 x 8 = 88 tiles of 416 ->
  // 10 x 8 = 80 of 424, phone ~-0.5 s; 2x zoom keeps 7 x 5 of 320.
  const int target = s == 1 ? 416 : 320;
  const int ov0 = std::min(overlap, target / 4) & ~1;
  auto fit = [&](int len) {
    const int n = std::max(1, int(std::ceil(double(len - ov0) / (1.05 * target - ov0))));
    return (len - ov0 + n - 1) / n + ov0;
  };
  const int t = std::max(fit(w), fit(h));
  return (t + align - 1) / align * align;
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
  // HDR bracket: exposure of each frame relative to the reference (frame 0).
  std::vector<float> erel(size_t(K), 1.0f);
  bool hdr = false;
  if (!prm.exposure.empty()) {
    if (int(prm.exposure.size()) != K || holdout) {
      if (err) *err = "exposure needs one value per frame and no holdout";
      return false;
    }
    for (int k = 0; k < K; ++k) {
      if (!(prm.exposure[size_t(k)] > 0.0f)) { if (err) *err = "exposure must be > 0"; return false; }
      erel[size_t(k)] = prm.exposure[size_t(k)] / prm.exposure[0];
      if (std::fabs(erel[size_t(k)] - 1.0f) > 0.02f) hdr = true;
    }
    if (!hdr) std::fill(erel.begin(), erel.end(), 1.0f);
  }
  // Clip level in reference units: the shortest frame's (samples above it are clipped somewhere);
  // clipped samples are written just above it so the solver's ">= clip" test excludes them.
  float emin = 1.0f;
  for (float e : erel) emin = std::min(emin, e);
  const float hdr_clip = prm.solve.clip / emin, hdr_mark = 1.02f * hdr_clip;
  S.hdr_white = hdr ? hdr_clip : 1.0f;
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
  if (!prm.model_error.empty() && (prm.model_error.size() != size_t(rw)*rh ||
      (!(prm.solve.dct_prior && prm.solve.dct_aperture > 0) && !prm.solve.band_rejection))) {
    if (err) *err = "model-error map requires region dimensions and a compatible experiment";
    return false;
  }
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
  if (prm.sharpest_ref && !prm.paper_parity && K > 1 && !holdout && !hdr) {
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
  float hl_ratio[3] = {1.0f, 1.0f, 1.0f};
  size_t hl_cells = 0;
  std::thread noise_fit([&] {
    const double tn = now_ms();
    std::vector<float> c(size_t(g.w) * g.h);
    {   // row bands in parallel (same values); the coarse LK threads are short, the noise fit is the critical path
      const int nb = 4, band = ((g.h / nb) + 1) & ~1;
      std::vector<std::thread> ld;
      for (int b = 0; b < nb; ++b) {
        const int y0 = b * band, y1 = std::min(g.h, y0 + band);
        if (y0 >= y1) break;
        ld.emplace_back([&, y0, y1] { LoadTile(frames[0], g, 0, y0, g.w, y1 - y0, black, white, c.data() + size_t(y0) * g.w); });
      }
      for (auto& th : ld) th.join();
    }
    std::thread sig([&] { S.sigma = NoiseSigma(c.data(), g.h, g.w); });   // independent of the block fit
    int bins = 0;
    NoiseAB(c.data(), g.h, g.w, &S.a, &S.b, &bins);
    sig.join();
    if (const char* v = std::getenv("LKRL_SIGMA")) S.sigma = float(std::atof(v));   // diagnostics
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
    // Highlight reconstruction colour (SolveParams::hl_ratio): white-balanced rim colour (G = 1), summed over the reference's 2x2 cells in the soft-clip band (brightest channel in
    // [0.85 clip, clip), every channel unclipped) -- the unclipped rim of clipped areas, the same band
    // the reconstruction blends over. Sums = brightness-weighted, so the bright rim dominates.
    {
      const float clip = prm.solve.clip, lo = 0.85f * clip;
      double sc[3] = {0, 0, 0}, so[3] = {0, 0, 0};
      size_t nb = 0;
      for (int y = 0; y + 1 < g.h; y += 2)
        for (int x = 0; x + 1 < g.w; x += 2) {
          const float* r0 = c.data() + size_t(y) * g.w + x;
          const float v[3] = {r0[0], 0.5f * (r0[1] + r0[g.w]), r0[g.w + 1]};
          const float mx = std::max(v[0], std::max(r0[1], std::max(r0[g.w], v[2])));
          if (mx < lo || mx >= clip) continue;
          const float vw[3] = {v[0] * wb[0], v[1] * wb[1], v[2] * wb[2]};
          for (int k = 0; k < 3; ++k) { sc[k] += vw[k]; so[k] += 0.5 * (vw[(k + 1) % 3] + vw[(k + 2) % 3]); }
          ++nb;
        }
      // Rim colour normalised to green (wb space); a clipped channel is rebuilt from the unclipped ones.
      for (int k = 0; k < 3; ++k) hl_ratio[k] = (nb >= 64 && sc[1] > 0) ? float(std::clamp(sc[k] / sc[1], 0.25, 4.0)) : 1.0f;
      (void)so;
      hl_cells = nb;
    }
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
    if (hdr) {   // bracket: near-clipped bins (raw units) carry no exposure-consistent signal -> NaN, skipped by LK
      std::vector<float> v = BinnedGrey(frame, g, bin, black, white, bw, bh);
      const float lim = 0.8f * prm.solve.clip;
      for (float& x : v) if (x >= lim) x = std::numeric_limits<float>::quiet_NaN();
      return v;
    }
    if (!prm.paper_parity) {
      const char* bilinear = std::getenv("LKRL_COARSE_BILINEAR");
      if (bilinear && std::atoi(bilinear) != 0)
        return BinnedBilinearGrey(frame, g, bin, black, white, bw, bh);
      return BinnedGrey(frame, g, bin, black, white, bw, bh);
    }
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
    // One thread per frame up to the cores minus the noise fit's (2026-11-20; was half the cores: 7 frames
    // ran in 2 rounds on the 8-core phone, ~0.75 s serial before any GPU work). LKRL_COARSE_THR overrides.
    static const int coarse_thr = std::getenv("LKRL_COARSE_THR") ? std::atoi(std::getenv("LKRL_COARSE_THR")) : 0;
    const int ncpu = std::max(1, int(std::thread::hardware_concurrency()));
    const int nthr = std::max(1, std::min(K - 1, coarse_thr > 0 ? coarse_thr : std::max(1, ncpu - 1)));
    std::vector<std::thread> pool;
    for (int tt = 0; tt < nthr; ++tt)
      pool.emplace_back([&, tt] {
        for (int k = 1 + tt; k < K; k += nthr) {
          int w2, h2;
          std::vector<float> mov = alignmentGrey(frames[k], &w2, &h2);
          if (hdr) {   // bracket: known exposure ratio (clipped areas would bias a median ratio)
            gain[k] = 1.0f / erel[size_t(k)];
            for (float& v : mov) v *= gain[k];
          } else if (!prm.paper_parity) {
            const float mm = median_of(mov);
            if (mref > 0 && mm > 0) {
              const float gk = mref / mm;
              if (gk > 0.8f && gk < 1.25f && std::fabs(gk - 1.0f) > 0.002f) {
                gain[k] = gk;
                for (float& v : mov) v *= gk;
              }
            }
          }
          // Coarse LK iteration cap (2026-11-18): at full binned resolution noisy frames never reached the
          // 1e-3 px stop and ran all 40 iterations (3 of 7 on IMG_20260919_184641_979), most of the coarse
          // time; tile LK and the solver refine the motion afterwards. 16 (coarse levels converge in <= 11; IMG_20260917_141353_404 frames need 13-16):
          // Mac coarse 0.60 -> 0.34 s (8 frames), 1.87 -> 0.86 s (16); final image 74.5 dB vs 40 (noise grain
          // only), other bursts bit-identical. LKRL_COARSE_IT / debug.lkr.coarseit = the old 40 for A/B.
          static const int coarse_it = std::getenv("LKRL_COARSE_IT") ? std::atoi(std::getenv("LKRL_COARSE_IT")) : 16;
          std::array<double, 6> q = LkAffine(lref, mov.data(), coarse_it);
          const int alignment_scale = prm.paper_parity ? 1 : bin;
          q[2] *= alignment_scale;
          q[5] *= alignment_scale;
          P[k] = q;
        }
      });
    for (auto& th : pool) th.join();
    if (const char* fn = std::getenv("LKRL_AFFINE_FILE")) {   // diagnostics: K lines of 6 numbers
      if (FILE* f = std::fopen(fn, "r")) {
        for (int k = 0; k < K; ++k) for (int m = 0; m < 6; ++m) if (std::fscanf(f, "%lf", &P[k][m]) != 1) break;
        std::fclose(f);
      }
    }
    if (std::getenv("LKRL_NOGAIN")) std::fill(gain.begin(), gain.end(), 1.0f);   // diagnostics
    if (const char* v = std::getenv("LKRL_COARSE_SHIFT")) {   // diagnostics: add (dx, dy) to frames 1..K-1
      float dx = 0, dy = 0; std::sscanf(v, "%f %f", &dx, &dy);
      for (int k = 1; k < K; ++k) { P[k][2] += dx; P[k][5] += dy; }
    }
    if (std::getenv("LKRL_PRINT_AFFINE"))   // diagnostics
      for (int k = 0; k < K; ++k)
        std::fprintf(stderr, "affine %d: %.6f %.6f %.3f %.6f %.6f %.3f gain %.4f\n", k, P[k][0], P[k][1], P[k][2], P[k][3], P[k][4], P[k][5], gain[k]);
  }
  noise_fit.join();
  S.ms_coarse = now_ms() - t;   // wall, noise fit included

  // 3) Overlapping tiles, feathered accumulation into the interleaved output.
  t = now_ms();
  const PriorFn prior = !prm.solve.no_prior && make_prior ? make_prior(S.a, S.b, S.sigma, wb) : PriorFn();
  // The DCT x-step needs the solved tile (s * tile) to be a multiple of 4.
  const int tile_target = prm.tile > 0 ? prm.tile : (prm.s == 1 ? 416 : 320);
  const int tile_align = ((prm.solve.dct_prior || prm.solve.dct_fallback) && prm.s == 1) ? 4 : 2;
  const int tile_req = prm.tile > 0 ? tile_target : AutoTileSize(g.w, g.h, prm.s, prm.overlap, tile_align);
  const int tile = std::min({tile_req, g.w, g.h}) & ~(tile_align - 1);
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
  if (const char* v = std::getenv("LKRL_ALIGN2")) sp.align2 = std::atoi(v) != 0;
  if (sp.align2 && sp.support_step <= 0.0f) sp.support_step = 0.5f;
  sp.s = s;
  sp.sigma_noise = S.sigma;
  sp.sigma_max = prm.smax_x * S.sigma;
  sp.sigma_end = prm.end_x * S.sigma;
  sp.wb = wb;
  for (int k = 0; k < 3; ++k) sp.hl_ratio[k] = hl_ratio[k];
  if (const char* v = std::getenv("LKRL_HL_LOG"))
    if (v[0] == '1') std::fprintf(stderr, "lkr-lite: highlight ratios R %.3f G %.3f B %.3f from %zu rim cells\n",
                                  hl_ratio[0], hl_ratio[1], hl_ratio[2], hl_cells);
  if (prm.paper_parity) {
    sp.prior_gain = 1.0f;
    sp.grain = 0.0f;
    sp.notch = false;
    sp.clip = 1e9f;
    sp.robust = false;
    sp.guide_confidence = 0.0f;
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
  if (hdr) { sp.clip = hdr_clip; sp.exposure = erel; }
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
  // At 2x each R/B observation covers one of 16 HR Bayer phases. Weight the
  // frame count by the diversity of the measured global shifts. Repeated
  // frames add no new phases. Make one decision for all overlapping tiles.
  constexpr int phase_count = 4 * 4;  // Bayer period at 2x.
  if (prm.auto_look && look_t == 0 && s == 2 && !hdr && !holdout && !prm.paper_parity && K >= phase_count) {
    double mass[4][4]{};
    const double cx = g.w * 0.5, cy = g.h * 0.5;
    for (const auto& a : P) {
      const double x = s * ((a[0] - 1) * cx + a[1] * cy + a[2]);
      const double y = s * (a[3] * cx + (a[4] - 1) * cy + a[5]);
      if (!std::isfinite(x) || !std::isfinite(y)) continue;
      const int ix = int(std::floor(x)), iy = int(std::floor(y));
      const double fx = x - ix, fy = y - iy;
      for (int j = 0; j < 2; ++j) for (int i = 0; i < 2; ++i)
        mass[(iy + j) & 3][(ix + i) & 3] += (i ? fx : 1 - fx) * (j ? fy : 1 - fy);
    }
    double squares = 0.0, least = double(K);
    for (const auto& row : mass) for (double value : row) {
      squares += value * value;
      least = std::min(least, value);
    }
    // Inverse concentration / 16 is one for uniform sampling.
    const double phase_fraction = squares > 0 ? double(K) * K / (phase_count * squares) : 0.0;
    sp.direct_rgb = least > 0 && K * phase_fraction >= phase_count;
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
  const char* dense_dir = std::getenv("LKRL_DENSE_FIELD_DIR");
  const char* raw_trace_dir = std::getenv("LKRL_RAW_TRACE_DIR");
  if ((std::getenv("LKRL_DENSE_FIELD") && jobs.size() != 1) ||
      (hdr && (dense_dir || std::getenv("LKRL_DENSE_FIELD")))) {
    if (err) *err = "dense replay requires equal exposures and one field per tile";
    return false;
  }
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
  std::string fail_msg;
  std::map<std::string, double> shader_ms;
  std::map<std::string, int> shader_n;   // dispatches (LKRL_PROF_N prints ms/count)
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
    // Lite: tile / sub-tile LK batched on this worker's GPU (LkAffine's control flow, float sums).
    // Default CPU (LK-R's path): measured on Mali the batched GPU LK was slower (prep 16 -> 37-44 s
    // summed): a host round trip per iteration, and its small passes queue behind the other workers'
    // solver work, while the CPU LK overlaps it on idle cores. LKRL_GPU_LK=1 for experiments.
    static const bool cpu_lk = std::getenv("LKRL_GPU_LK") == nullptr;
    LkBatchGpu lkgpu(wg);
    const bool gpu_lk = !cpu_lk && lkgpu.ok();
    { std::lock_guard<std::mutex> l(mu); S.ms_setup += now_ms() - t_solver; }
    if (!solver.ok()) {
      std::lock_guard<std::mutex> l(mu);
      failed = true; fail_msg = "solver pipelines unavailable";
      cv.notify_all();
      return;
    }
    wg->profile(gpu->profiling());
    std::vector<float> x, field, model_tile;
    const bool use_block_search = sp.local_block == 16 && prm.block_search && !prm.paper_parity;
    BlockSearch bsearch(use_block_search ? wg : nullptr);
    // CPU tile preparation (load, tile LK, sub-tile LK) runs one tile ahead on its own thread while
    // the GPU solves the current tile (2026-11-17). Before, each worker prepared and then solved, so
    // the GPU idled during the LK: Mali-G615 1x full merge 18.2-18.6 s, 14.1 s with tile LK off.
    // Same computation per tile: output identical. LKRL_NO_PREFETCH = the old sequential order.
    struct Prepared {
      size_t ji = SIZE_MAX;
      std::vector<float> tf, p, sub_field, fine_field;
      bool use_sub = false, fail = false;
      double prep_ms = 0.0, load_ms = 0.0;
    };
    auto prepare = [&](size_t ji, Prepared& o) {
      o.ji = ji; o.fail = false; o.use_sub = false;
      std::vector<float>& tf = o.tf;
      tf.resize(size_t(K) * n * n);
      const TileJob& jb = jobs[ji];
      const double tprep = now_ms();
      const int gx = jb.gx, gy = jb.gy;
      const double tl0 = now_ms();
      for (int k = 0; k < K; ++k) {
        float* fk = tf.data() + size_t(k) * n * n;
        LoadTile(frames[k], g, gx, gy, n, n, black, white, fk);
        if (hdr) {   // reference units; clipped samples (any frame, the reference too) marked above hdr_clip
          const float gk = 1.0f / erel[size_t(k)];
          for (size_t i = 0; i < size_t(n) * n; ++i) fk[i] = fk[i] >= prm.solve.clip ? hdr_mark : fk[i] * gk;
        } else if (gain[k] != 1.0f)   // clipped samples stay clipped (>= clip -> 1)
          for (size_t i = 0; i < size_t(n) * n; ++i) fk[i] = fk[i] >= prm.solve.clip ? 1.0f : fk[i] * gain[k];
      }
      const double tl1 = now_ms();
      std::vector<float>& p = o.p;
      p.assign(size_t(K) * 6, 0.0f);
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
            if (hdr && std::max(std::max(r0[0], r0[1]), std::max(r0[n], r0[n + 1])) >= hdr_clip)   // clipped: masked in LK
              o[size_t(ii) * g2 + jj] = std::numeric_limits<float>::quiet_NaN();
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
      const int sub_size = prm.sub_lk == 0 ? 0 : (look_t > 0.5f ? prm.sub_lk_night : prm.sub_lk);
      const int sub_blur = int(std::lround(prm.sub_lk_blur_night * look_t));
      // Any tile size (2026-11-20): quadrants of sub_size spread evenly over the tile (overlapping when n is
      // not a multiple), each 16 px field block from the nearest quadrant. The old n % sub_size == 0 rule
      // silently turned this off for the faster 416 / 424 px tiles: wind-moved bushes lost ~20 % fine detail
      // (IMG_20260919_184641_979). With n a multiple the quadrants and blocks are exactly the old ones.
      const bool use_sub = sub_size >= 64 && prm.tile_lk && !prm.paper_parity && sp.local_block == 16 &&
                           n >= sub_size && look_t <= prm.sub_lk_max_noise;
      // Quadrant starts along one axis (grey px) and the field blocks [b0, b1) each one owns.
      const int sub_gs = sub_size / 2;
      std::vector<int> qs;
      std::vector<std::pair<int, int>> qb;
      if (use_sub) {
        const int nq = std::max(1, (g2 + sub_gs / 2) / sub_gs);
        for (int i = 0; i < nq; ++i) qs.push_back(nq == 1 ? (g2 - sub_gs) / 2 : i * (g2 - sub_gs) / (nq - 1));
        auto firstBlock = [](int lo2) { return lo2 <= 8 ? 0 : (lo2 - 8 + 15) / 16; };   // first block centre >= lo2 (LR px)
        for (int i = 0; i < nq; ++i) {
          const int lo2 = i == 0 ? 0 : qs[i - 1] + qs[i] + sub_gs;              // 2 x midpoint of the centres
          const int hi2 = i == nq - 1 ? 2 * g2 + 16 : qs[i] + qs[i + 1] + sub_gs;
          qb.push_back({firstBlock(lo2), firstBlock(hi2)});
        }
      }
      const int sub_nbx = (n + 15) / 16, sub_nby = (n + 15) / 16;
      std::vector<float>& sub_field = o.sub_field;
      sub_field.clear();
      if (use_sub) sub_field.assign(size_t(K) * sub_nbx * sub_nby * 2, 0.0f);
      o.use_sub = use_sub;
      const int nfx = (n + 7) / 8, nfy = (n + 7) / 8;
      static const int fine_env = std::getenv("LKRL_FINE") ? std::atoi(std::getenv("LKRL_FINE")) : -1;
      const bool use_fine = use_sub && K > 1 && (fine_env >= 0 ? fine_env != 0 : sp.align2);
      o.fine_field.assign(use_fine ? size_t(K) * nfx * nfy * 2 : 0, 1e9f);   // no own offset
      std::vector<LkRef> qref;   // quadrant references, row-major in the order align_frame visits them
      if (use_sub) {
        const int gs = sub_gs;
        std::vector<float> sr(size_t(gs) * gs);
        for (int qy : qs)
          for (int qx : qs) {
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
        const double pt0 = now_ms();
        const auto& q = P[k];
        std::array<double, 6> pt{q[0], q[1], q[0] * gx + q[1] * gy + q[2] - gx,
                                 q[3], q[4], q[3] * gx + q[4] * gy + q[5] - gy};
        if (k > 0 && prm.tile_lk && !prm.paper_parity) {
          cellGrey(tf.data() + size_t(k) * n * n, gm);
          std::array<double, 6> pg{pt[0], pt[1], pt[2] * 0.5, pt[3], pt[4], pt[5] * 0.5};   // LR -> grey px
          const std::array<double, 6> pr = LkAffine(tref, gm.data(), 30, &pg);
          if (LkCompareFits(tref, gm.data(), pg, pr).improves())
            pt = {pr[0], pr[1], pr[2] * 2.0, pr[3], pr[4], pr[5] * 2.0};
        }
        for (int c = 0; c < 6; ++c) p[k * 6 + c] = float(pt[c]);
        const double pt1 = now_ms();
        g_prep_us[0] += (long long)(1000.0 * (pt1 - pt0));
        struct SubT { double t; ~SubT() { g_prep_us[1] += (long long)(1000.0 * (now_ms() - t)); } } subt{pt1};
        // Sub-tile alignment (parallax): quadrant affine relative to the tile's,
        // written as the local motion start d(u) = (A' u + t') - (A u + t), LR px.
        if (k > 0 && use_sub) {
          const int gs = sub_gs;   // quadrant edge in grey px
          std::vector<float> sm(size_t(gs) * gs);
          size_t qi = 0;
          for (size_t iy = 0; iy < qs.size(); ++iy)
            for (size_t ix = 0; ix < qs.size(); ++ix) {
              const int qx = qs[ix], qy = qs[iy];
              const LkRef& sr = qref[qi++];
              for (int i = 0; i < gs; ++i)
                for (int j = 0; j < gs; ++j) sm[size_t(i) * gs + j] = gm[size_t(qy + i) * g2 + qx + j];
              if (sub_blur > 0) BoxBlurGrey(&sm, gs, sub_blur);
              const double tgx = pt[2] * 0.5, tgy = pt[5] * 0.5;   // tile affine in grey px
              std::array<double, 6> ps{pt[0], pt[1], pt[0] * qx + pt[1] * qy + tgx - qx,
                                       pt[3], pt[4], pt[3] * qx + pt[4] * qy + tgy - qy};
              const std::array<double, 6> pq = LkAffine(sr, sm.data(), 30, &ps);
              const auto fit = LkCompareFits(sr, sm.data(), ps, pq);
              const bool keep = fit.improves(prm.sub_lk_gain);
              if (std::getenv("LKR_DEBUG_SUB"))
                std::fprintf(stderr, "sub k%d q(%d,%d) size %d blur %d: residual %.5f -> %.5f, support %zu/%zu (%s)\n",
                             k, qx, qy, sub_size, sub_blur, fit.seed_error, fit.candidate_error,
                             fit.common_samples, fit.seed_samples, keep ? "kept" : "rejected");
              if (!keep)
                continue;   // no clear improvement: keep the tile affine here
              // Quadrant affine in tile LR coords: t_LR = 2 (t' + o - A' o).
              const double ax = pq[0], bx_ = pq[1], ay = pq[3], by_ = pq[4];
              const double tx = 2.0 * (pq[2] + qx - (ax * qx + bx_ * qy));
              const double ty = 2.0 * (pq[5] + qy - (ay * qx + by_ * qy));
              const size_t ixq = size_t(std::find(qs.begin(), qs.end(), qx) - qs.begin()), iyq = size_t(std::find(qs.begin(), qs.end(), qy) - qs.begin());
              const int b0x = qb[ixq].first, b1x = qb[ixq].second, b0y = qb[iyq].first, b1y = qb[iyq].second;
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
      // align2 (LKRL_PATCH_SEED=0 off): noise-gated block offsets on the cell grey, frame by frame.
      auto patch_seed = [&](int k, const std::vector<float>& gm) {
        static const int ps_env = std::getenv("LKRL_PATCH_SEED") ? std::atoi(std::getenv("LKRL_PATCH_SEED")) : -1;
        const bool on = ps_env >= 0 ? ps_env != 0 : sp.align2;
        if (!on || k == 0 || !use_sub) return;
        struct PsT { double t; ~PsT() { g_prep_us[2] += (long long)(1000.0 * (now_ms() - t)); } } pst{now_ms()};
        auto envf = [](const char* n, float d) { const char* v = std::getenv(n); return v ? float(std::atof(v)) : d; };
        static const float trig = envf("LKRL_PS_TRIG", 1.3f), gn = envf("LKRL_PS_GAIN", 0.85f), srch = envf("LKRL_PS_SEARCH", 5.0f), slk = envf("LKRL_PS_SLACK", 0.5f);
        PatchSeedParams pp;
        pp.noise_a = S.a; pp.noise_b = S.b; pp.trigger = trig; pp.gain = gn; pp.search = int(srch); pp.slack = slk;
        static const float uniq = envf("LKRL_PS_UNIQUE", 0.0f);
        pp.unique = uniq;
        const float* q = p.data() + size_t(k) * 6;
        const double pg[6] = {q[0], q[1], q[2] * 0.5, q[3], q[4], q[5] * 0.5};
        const PatchSeedStats ps = PatchSeed(gref.data(), gm.data(), g2, pg, pp,
                                            sub_field.data() + size_t(k) * sub_nbx * sub_nby * 2, sub_nbx, sub_nby);
        static const float pfu = envf("LKRL_PF_UNIQUE", 3.0f), pfa = envf("LKRL_PF_AGREE", 2.0f);
        pp.fine_unique = pfu; pp.fine_agree = int(pfa);
        if (use_fine)
          PatchSeedFine(gref.data(), gm.data(), g2, pg, pp, sub_field.data() + size_t(k) * sub_nbx * sub_nby * 2, sub_nbx, sub_nby,
                        o.fine_field.data() + size_t(k) * nfx * nfy * 2, nfx, nfy);
        if (use_fine) {   // changed blocks: the measured offset itself (the motion steps do not move it)
          const float* f = sub_field.data() + size_t(k) * sub_nbx * sub_nby * 2;
          float* ex = o.fine_field.data() + size_t(k) * nfx * nfy * 2;
          for (int by = 0; by < nfy; ++by) for (int bx = 0; bx < nfx; ++bx, ex += 2) {
            if (ex[0] == 0 && ex[1] == 0) { ex[0] = ex[1] = 1e9f; continue; }
            const float cx = (bx * 8 + 4) / 16.0f - 0.5f, cy = (by * 8 + 4) / 16.0f - 0.5f;
            const int ix = int(std::floor(cx)), iy = int(std::floor(cy));
            const float fx = cx - ix, fy = cy - iy;
            auto F = [&](int x, int y, int d) { return f[2 * (size_t(std::clamp(y, 0, sub_nby - 1)) * sub_nbx + std::clamp(x, 0, sub_nbx - 1)) + d]; };
            for (int d = 0; d < 2; ++d)
              ex[d] += (F(ix, iy, d) * (1 - fx) + F(ix + 1, iy, d) * fx) * (1 - fy) + (F(ix, iy + 1, d) * (1 - fx) + F(ix + 1, iy + 1, d) * fx) * fy;
          }
        }
        if (std::getenv("LKRL_PS_LOG"))
          std::fprintf(stderr, "patch seed k%d: %d blocks, %d checked, %d changed\n", k, ps.blocks, ps.checked, ps.changed);
      };
      if (gpu_lk && prm.tile_lk && !prm.paper_parity && K > 1) {
        // Same steps as align_frame, phase by phase so every frame's (then every sub-tile's) LK
        // iterates in one GPU batch. CPU parts (grey, blur, residual tests) run as before.
        std::vector<std::vector<float>> gms(static_cast<size_t>(K));
        std::vector<std::array<double, 6>> pts(static_cast<size_t>(K)), pgs(static_cast<size_t>(K));
        for (int k = 0; k < K; ++k) {
          const auto& q = P[k];
          pts[size_t(k)] = {q[0], q[1], q[0] * gx + q[1] * gy + q[2] - gx, q[3], q[4], q[3] * gx + q[4] * gy + q[5] - gy};
          if (k > 0) {
            cellGrey(tf.data() + size_t(k) * n * n, gms[size_t(k)]);
            const auto& pt = pts[size_t(k)];
            pgs[size_t(k)] = {pt[0], pt[1], pt[2] * 0.5, pt[3], pt[4], pt[5] * 0.5};   // LR -> grey px
          }
        }
        std::vector<LkJob> jobs;
        for (int k = 1; k < K; ++k) jobs.push_back({&tref, gms[size_t(k)].data(), pgs[size_t(k)]});
        if (!lkgpu.run(jobs, 30)) { o.fail = true; return; }
        for (int k = 1; k < K; ++k) {
          const auto& pr = jobs[size_t(k - 1)].p;
          const auto& pg = pgs[size_t(k)];
          if (LkCompareFits(tref, gms[size_t(k)].data(), pg, pr).improves())
            pts[size_t(k)] = {pr[0], pr[1], pr[2] * 2.0, pr[3], pr[4], pr[5] * 2.0};
        }
        for (int k = 0; k < K; ++k)
          for (int c = 0; c < 6; ++c) p[k * 6 + c] = float(pts[size_t(k)][size_t(c)]);
        if (use_sub) {
          const int gs = sub_gs;
          struct Sub { int k, qx, qy; size_t qi; std::array<double, 6> ps; };
          std::vector<Sub> subs;
          std::vector<std::vector<float>> sms;
          for (int k = 1; k < K; ++k) {
            const auto& pt = pts[size_t(k)];
            size_t qi = 0;
            for (int qy : qs)
              for (int qx : qs) {
                std::vector<float> sm(size_t(gs) * gs);
                for (int i = 0; i < gs; ++i)
                  for (int j = 0; j < gs; ++j) sm[size_t(i) * gs + j] = gms[size_t(k)][size_t(qy + i) * g2 + qx + j];
                if (sub_blur > 0) BoxBlurGrey(&sm, gs, sub_blur);
                const double tgx = pt[2] * 0.5, tgy = pt[5] * 0.5;   // tile affine in grey px
                subs.push_back({k, qx, qy, qi++, {pt[0], pt[1], pt[0] * qx + pt[1] * qy + tgx - qx,
                                                  pt[3], pt[4], pt[3] * qx + pt[4] * qy + tgy - qy}});
                sms.push_back(std::move(sm));
              }
          }
          std::vector<LkJob> sj;
          for (size_t i = 0; i < subs.size(); ++i) sj.push_back({&qref[subs[i].qi], sms[i].data(), subs[i].ps});
          if (!lkgpu.run(sj, 30)) { o.fail = true; return; }
          for (size_t i = 0; i < subs.size(); ++i) {
            const Sub& sb = subs[i];
            const LkRef& sr = qref[sb.qi];
            const auto& pq = sj[i].p;
            const auto& pt = pts[size_t(sb.k)];
            if (!LkCompareFits(sr, sms[i].data(), sb.ps, pq).improves(prm.sub_lk_gain)) continue;   // no clear improvement: keep the tile affine
            const int qx = sb.qx, qy = sb.qy, k = sb.k;
            const double ax = pq[0], bx_ = pq[1], ay = pq[3], by_ = pq[4];
            const double tx = 2.0 * (pq[2] + qx - (ax * qx + bx_ * qy));
            const double ty = 2.0 * (pq[5] + qy - (ay * qx + by_ * qy));
            const size_t ixq = size_t(std::find(qs.begin(), qs.end(), qx) - qs.begin()), iyq = size_t(std::find(qs.begin(), qs.end(), qy) - qs.begin());
              const int b0x = qb[ixq].first, b1x = qb[ixq].second, b0y = qb[iyq].first, b1y = qb[iyq].second;
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
      } else
      {
        const int nt = (prm.tile_lk && !prm.paper_parity) ? std::min(3, K) : 1;
        std::vector<std::thread> helpers;
        for (int h = 1; h < nt; ++h)
          helpers.emplace_back([&, h] {
            std::vector<float> gm;
            for (int k = h; k < K; k += nt) { align_frame(k, gm); patch_seed(k, gm); }
          });
        for (int k = 0; k < K; k += nt) { align_frame(k, gmov); patch_seed(k, gmov); }
        for (auto& th : helpers) th.join();
      }
      // No block of any frame moves on its own: the merge keeps the plain 16 px field.
      static const float pf_min = std::getenv("LKRL_PF_MIN") ? float(std::atof(std::getenv("LKRL_PF_MIN"))) : 0.01f;
      if (use_fine) {
        size_t moved = 0;
        for (size_t q = 0; q < o.fine_field.size(); q += 2) moved += o.fine_field[q] < 1e8f;
        if (moved <= size_t(pf_min * float(o.fine_field.size() / 2))) o.fine_field.clear();
        if (std::getenv("LKRL_PS_LOG")) std::fprintf(stderr, "fine: %zu of %zu blocks\n", moved, o.fine_field.size() / 2);
      }
      o.prep_ms = now_ms() - tprep;
      o.load_ms = tl1 - tl0;
    };
    static const bool prefetch_env = std::getenv("LKRL_NO_PREFETCH") == nullptr;
    const bool prefetch = prefetch_env && !gpu_lk;   // the GPU LK would share this worker's queue
    // Lag-1 tile pipeline (NPU prior): see solveBegin / solveEnd.
    struct InFlight { size_t ji = 0; bool ok = false, live = false; PendingPtr pend; double solve_ms = 0, prep_ms = 0, load_ms = 0; std::vector<float> yk; };
    const bool lag = !sp.no_prior && (PriorEnabled() || sp.npu_denoise || NpuDenoiseEnabled()) && !std::getenv("LKRL_PRIOR_NOLAG");
    InFlight inflight;
    auto commit = [&](InFlight& f) -> bool {
      f.live = false;
      std::vector<float> x;
      SolveStats ss;
      const double te = now_ms();
      const bool ok = f.ok && solver.solveEnd(&f.pend, &x, &ss);
      const double solve_ms = f.solve_ms + (now_ms() - te);
      Done done;
      done.x.swap(x);
      if (holdout) done.yk.swap(f.yk);
      const double t_wait = now_ms();
      std::unique_lock<std::mutex> l(mu);
      // Back-pressure: every waiting result holds a tile image.
      cv.wait(l, [&] { return failed || ticket == f.ji || pending.size() < 2 * gpus.size(); });
      S.ms_wait += now_ms() - t_wait;
      if (failed) return false;
      if (!ok) { failed = true; fail_msg = "tile solve failed"; cv.notify_all(); return false; }
      S.ms_gpu += ss.gpu_ms;
      S.ms_solve += solve_ms;
      S.ms_prep += f.prep_ms;
      S.ms_load += f.load_ms;
      S.ms_prior += ss.prior_ms;
      S.ms_init += ss.init_ms;
      S.ms_finish += ss.finish_ms;
      S.prior_calls += ss.prior_calls;
      ++S.tiles;
      done.ss = std::move(ss);
      pending.emplace(f.ji, std::move(done));
      const double t_blend = now_ms();
      while (!pending.empty() && pending.begin()->first == ticket) {
        blend(ticket, pending.begin()->second);
        pending.erase(pending.begin());
        ++ticket;
      }
      S.ms_blend += now_ms() - t_blend;
      cv.notify_all();
      l.unlock();
      return true;
    };
    Prepared bufs[2];
    int cb = 0;
    { const size_t j0 = next.fetch_add(1); if (j0 < jobs.size()) prepare(j0, bufs[0]); }
    std::thread pre;
    struct Joiner { std::thread& t; ~Joiner() { if (t.joinable()) t.join(); } } joiner{pre};
    for (;;) {
      Prepared& cur = bufs[cb];
      if (cur.ji >= jobs.size()) break;
      if (cur.fail) { std::lock_guard<std::mutex> l(mu); failed = true; fail_msg = "gpu lk"; cv.notify_all(); return; }
      const size_t jn = next.fetch_add(1);
      const bool have_next = jn < jobs.size();
      bufs[cb ^ 1].ji = SIZE_MAX;
      if (have_next && prefetch) pre = std::thread([&prepare, &bufs, jn, slot = cb ^ 1] { prepare(jn, bufs[slot]); });
      const size_t ji = cur.ji;
      const TileJob& jb = jobs[ji];
      const int gx = jb.gx, gy = jb.gy;
      std::vector<float>& tf = cur.tf;
      std::vector<float>& p = cur.p;
      const bool use_sub = cur.use_sub;
      std::vector<float>& sub_field = cur.sub_field;
      SolveStats ss;
      SolveParams spt = sp;
      if (!prm.model_error.empty()) {
        model_tile.resize(size_t(s*n)*(s*n));
        for (int y=0;y<s*n;++y) for (int x=0;x<s*n;++x) {
          const float fx=std::clamp((x+.5f)/s-.5f+g.x0+gx-rx,0.f,float(rw-1));
          const float fy=std::clamp((y+.5f)/s-.5f+g.y0+gy-ry,0.f,float(rh-1));
          const int ix=int(fx),iy=int(fy),jx=std::min(ix+1,rw-1),jy=std::min(iy+1,rh-1);
          const float ax=fx-ix,ay=fy-iy;
          auto at=[&](int yy,int xx){return prm.model_error[size_t(yy)*rw+xx];};
          model_tile[size_t(y)*(s*n)+x]=(1-ay)*((1-ax)*at(iy,ix)+ax*at(iy,jx))+
                                        ay*((1-ax)*at(jy,ix)+ax*at(jy,jx));
        }
        spt.model_error=model_tile.data();
      }
      if (prm.trusted_static[2] > 0 && prm.trusted_static[3] > 0) {
        spt.trusted_static = prm.trusted_static;
        spt.trusted_static[0] -= float(g.x0 + gx);
        spt.trusted_static[1] -= float(g.y0 + gy);
      }
      spt.debug_maps = dbg_path != nullptr;
      if (const char* trace = std::getenv("LKRL_TRACE_PREFIX"); trace &&
          ji == size_t(std::getenv("LKRL_TRACE_TILE") ? std::atoi(std::getenv("LKRL_TRACE_TILE")) : 0))
        spt.debug_prefix = std::string(trace) + "_tile_" + std::to_string(gx) + "_" + std::to_string(gy);

      if (dense_dir || raw_trace_dir) {
        const std::string tile = "/tile_" + std::to_string(gx) + "_" + std::to_string(gy);
        if (dense_dir) spt.dense_field_path = std::string(dense_dir) + tile + ".bin";
        if (raw_trace_dir) {
          const std::string path = std::string(raw_trace_dir) + tile + "-raw.f32";
          FILE* f = std::fopen(path.c_str(), "wb");
          bool ok = f != nullptr;
          if (f) {
            ok = std::fwrite(tf.data(), sizeof(float), tf.size(), f) == tf.size();
            ok = std::fclose(f) == 0 && ok;
          }
          if (!ok) {
            std::lock_guard<std::mutex> lock(mu);
            failed = true;
            fail_msg = "write raw tile trace";
            cv.notify_all();
            return;
          }
        }
      }

      if (use_sub && !cur.fine_field.empty()) spt.local_fine = cur.fine_field.data();
      if (use_sub) spt.local_init = sub_field.data();   // parallax start
      // Local motion start: MFSR block search (align.comp) on affine-prewarped greys.
      if (use_block_search && bsearch.ok() &&
          bsearch.run(tf.data(), K, n, p.data(), 3, 4, S.sigma, &field))
      {
        if (std::getenv("LKR_DEBUG_BS")) {
          size_t nz = 0; float mx = 0;
          for (size_t q = 0; q + 1 < field.size(); q += 2) {
            const float m = std::max(std::fabs(field[q]), std::fabs(field[q + 1]));
            nz += m > 0; mx = std::max(mx, m);
          }
          std::fprintf(stderr, "block search: %zu/%zu blocks moved, max %.1f LR px\n", nz, field.size() / 2, mx);
        }
        // A zero search result has no accepted large displacement. Keep
        // the sub-tile estimate for those blocks.
        if (use_sub)
          for (size_t q = 0; q < field.size(); q += 2)
            if (field[q] == 0.0f && field[q + 1] == 0.0f) {
              field[q] = sub_field[q];
              field[q + 1] = sub_field[q + 1];
            }
        spt.local_init = field.data();
      }
      // Two-phase solve: the GPU fit now; with the NPU prior on, this tile's prior runs while the worker
      // fits its NEXT tile, and the tile is finished / blended one iteration later (lag 1).
      const double ts = now_ms();
      InFlight now_t;
      now_t.ji = ji;
      now_t.ok = solver.solveBegin(tf.data(), K, n, n, p.data(), spt, prior, &now_t.pend);
      now_t.solve_ms = now_ms() - ts;
      now_t.prep_ms = cur.prep_ms; now_t.load_ms = cur.load_ms;
      if (holdout) now_t.yk.assign(tf.data() + size_t(K - 1) * n * n, tf.data() + size_t(K) * n * n);
      now_t.live = true;
      if (lag) {
        if (inflight.live && !commit(inflight)) return;
        inflight = std::move(now_t);
      } else if (!commit(now_t)) {
        return;
      }
      if (have_next) {
        if (prefetch) pre.join();
        else prepare(jn, bufs[cb ^ 1]);
      }
      cb ^= 1;
    }
    if (inflight.live && !commit(inflight)) return;
    // Resolve names while this worker's Solver pipelines still exist. Profile
    // every worker; reporting just worker 1 misranks a full-frame tiled merge.
    const std::string report = wg->profileReport(), names = solver.profileNames();
    std::lock_guard<std::mutex> lock(mu);
    size_t pos = 0;
    while (pos < report.size()) {
      const size_t end = report.find(' ', pos), colon = report.find(':', pos);
      const std::string ptr = report.substr(pos, colon - pos);
      const size_t at = names.find("=" + ptr);
      std::string name = ptr;
      if (at != std::string::npos) {
        const size_t begin = names.rfind(' ', at);
        const size_t start = begin == std::string::npos ? 0 : begin + 1;
        name = names.substr(start, at - start);
      }
      shader_ms[name] += std::atof(report.c_str() + colon + 1);
      const size_t hash = report.find('#', colon);
      if (hash != std::string::npos && (end == std::string::npos || hash < end)) shader_n[name] += std::atoi(report.c_str() + hash + 1);
      if (end == std::string::npos) break;
      pos = end + 1;
    }
  };
  {
    std::vector<std::thread> th;
    for (size_t w = 1; w < gpus.size(); ++w) th.emplace_back(worker, gpus[w]);
    worker(gpus[0]);
    for (auto& t : th) t.join();
    if (std::getenv("LKRL_PREP_LOG"))
      std::fprintf(stderr, "prep cpu ms (summed over threads): tile LK %.0f quadrant LK %.0f patch seed %.0f\n",
                   g_prep_us[0] / 1000.0, g_prep_us[1] / 1000.0, g_prep_us[2] / 1000.0);

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
  std::vector<std::pair<double, std::string>> ranked;
  for (const auto& e : shader_ms) ranked.emplace_back(e.second, e.first);
  std::sort(ranked.rbegin(), ranked.rend());
  for (const auto& e : ranked) {
    char line[128];
    static const bool prof_n = std::getenv("LKRL_PROF_N") != nullptr;
    if (prof_n) std::snprintf(line, sizeof(line), "%s %.1f/%d, ", e.second.c_str(), e.first, shader_n[e.second]);
    else std::snprintf(line, sizeof(line), "%s %.1f, ", e.second.c_str(), e.first);
    S.gpu_profile += line;
  }
  for (const Gpu* wg : gpus) {
    const auto& v = wg->stats();
    auto& total = S.gpu_stats;
    total.allocations += v.allocations; total.allocated_bytes += v.allocated_bytes;
    total.live_bytes += v.live_bytes; total.peak_bytes += v.peak_bytes;
    total.pipelines += v.pipelines; total.sets += v.sets;
    total.submissions += v.submissions; total.dispatches += v.dispatches; total.copies += v.copies;
    total.alloc_ms += v.alloc_ms; total.pipeline_ms += v.pipeline_ms; total.sets_ms += v.sets_ms;
    total.record_ms += v.record_ms; total.queue_ms += v.queue_ms; total.wait_ms += v.wait_ms;
    total.coherency_ms += v.coherency_ms; total.query_ms += v.query_ms;
  }
  {
    static const float lfc_env = std::getenv("LKRL_LFC") ? float(std::atof(std::getenv("LKRL_LFC"))) : -1.0f;
    const float lk = lfc_env >= 0.0f ? lfc_env : (sp.direct_rgb ? 0.0f : prm.lfc_k);
    // Last NaN guard, always, before the image-wide pass that would spread it (tiles replace theirs).
    for (size_t i = 0, n3 = size_t(*out_w) * *out_h * 3; i < n3; ++i)
      if (!std::isfinite(rgb_data[i])) rgb_data[i] = 0.0f;
    if (!sp.no_prior && lk > 0.0f) {
      const double tl = now_ms();
      const float wbf[3] = {wb[0], wb[1], wb[2]};
      LfcStats ls;
      LowFreqChroma(rgb_data, *out_w, *out_h, wbf, lk, S.a, S.b, prm.s, &ls);
      S.lfc_ms = float(now_ms() - tl); S.lfc_wu = ls.wu; S.lfc_wv = ls.wv;
    }
  }
  // Highlight reconstruction (FinishLook) can lift clipped channels above the output white (clouds whose
  // green clipped: rebuilt from red / blue). Report the reached level as the white, as an HDR merge does,
  // so writers scale instead of clamping one channel (a clamped green turns the cloud pink). 99.99th
  // percentile of the values above the white: a lone ringing overshoot does not darken the whole file.
  static const bool hl_rec_env_off = std::getenv("LKRL_HL_REC") && std::getenv("LKRL_HL_REC")[0] == '0';
  if (prm.solve.highlight_desat && !hl_rec_env_off && !std::getenv("LKRL_HL_OLD")) {
    std::vector<float> over;
    for (size_t i = 0, n3 = size_t(*out_w) * *out_h * 3; i < n3; ++i)
      if (rgb_data[i] > S.hdr_white) over.push_back(rgb_data[i]);
    if (over.size() >= 16) {
      const size_t q = size_t(double(over.size() - 1) * 0.9999);
      std::nth_element(over.begin(), over.begin() + q, over.end());
      S.hdr_white = std::max(S.hdr_white, over[q]);
    }
  }
  S.ms_total = now_ms() - t0;
  if (st) *st = S;
  return true;
}

}  // namespace lkrl
