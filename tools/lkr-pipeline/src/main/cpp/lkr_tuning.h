// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// LK-SR expert tuning ("LK-SR Lab" in Settings -> Merge): a fixed-index float
// table shared with the app (LkrTuning.kt mirrors the indices). The native
// FullParams defaults are the single source of truth: TuningDefaults() reports
// them to the app, ApplyTuning() overrides only finite entries (NaN = keep the
// code default). Paper parity in MergeFull still wins over every entry.

#include <algorithm>
#include <cmath>
#include <vector>

#include "lkr_full.h"

namespace lkr {

enum Tuning : int {
  kIterations = 0,     // HQS iterations T
  kZSteps,             // z gradient steps per iteration
  kPriorEvery,         // prior on every n-th iteration
  kLookDayPrior,       // auto look: prior gain at clean daylight noise
  kLookNightPrior,     // ... at night noise
  kLookDayGrain,       // auto look: grain at clean daylight noise
  kLookNightGrain,     // ... at night noise
  kRobust,             // robust (motion-rejecting) data term, 0/1
  kRobustK,            // Cauchy threshold in noise sigmas
  kRobustTau,          // edge tolerance (LR px misregistration)
  kRobustSpread,       // rejection spread radius (LR px), 0 = off
  kTemporalK,          // temporal test vs reference threshold, 0 = off
  kTemporalTau,        // temporal test edge tolerance
  kEqualizeNoise,      // stronger prior where frames were rejected, 0/1
  kLocalMotion,        // 16-px local block motion, 0/1
  kLocalLambda,        // local motion regularisation (base, auto-scaled by noise)
  kMotionEvery,        // motion refinement every n-th iteration (after warm-up)
  kTileLk,             // per-tile coarse alignment, 0/1
  kMeanPreserve,       // prior mean-preservation radius (HR px), 0 = off
  kShadowBoost,        // daylight shadow cleanup: prior boost
  kShadowChroma,       // daylight shadow cleanup: chroma blend
  kNotch,              // Bayer-period notch, 0/1
  kClip,               // clipped-sample threshold (normalised)
  kMhcInit,            // start image: video MHC (1) or bilinear (0)
  kStepScale,          // z step size c in eta = c / (L + mu)
  kOverlap,            // tile overlap (LR px, even)
  kWorkers,            // concurrent tile workers
  kSigmaMax,           // schedule start, in multiples of our noise sigma
  kTuningCount
};

// The app's default parameter set (what Merge() used before tuning existed).
inline FullParams AppDefaults() {
  FullParams prm;
  prm.solve.T = 16;
  prm.solve.z_steps = 3;
  for (int t = 1; t < 16; t += 2) prm.solve.prior_at.push_back(t);   // every 2nd (tuned)
  return prm;
}

inline std::vector<float> TuningDefaults() {
  const FullParams p = AppDefaults();
  const SolveParams& s = p.solve;
  std::vector<float> v(kTuningCount);
  v[kIterations] = float(s.T);
  v[kZSteps] = float(s.z_steps);
  v[kPriorEvery] = 2.0f;
  v[kLookDayPrior] = p.look_day_prior;
  v[kLookNightPrior] = p.look_night_prior;
  v[kLookDayGrain] = p.look_day_grain;
  v[kLookNightGrain] = p.look_night_grain;
  v[kRobust] = s.robust ? 1.0f : 0.0f;
  v[kRobustK] = p.robust_k_day;   // daylight value; the auto look blends to robust_k_night
  v[kRobustTau] = s.robust_tau;
  v[kRobustSpread] = float(s.robust_spread);
  v[kTemporalK] = s.temporal_k;
  v[kTemporalTau] = s.temporal_tau;
  v[kEqualizeNoise] = s.equalize_noise ? 1.0f : 0.0f;
  v[kLocalMotion] = s.local_block > 0 ? 1.0f : 0.0f;
  v[kLocalLambda] = s.local_lambda;
  v[kMotionEvery] = float(s.motion_every);
  v[kTileLk] = p.tile_lk ? 1.0f : 0.0f;
  v[kMeanPreserve] = float(s.mean_preserve);
  v[kShadowBoost] = s.shadow_boost;
  v[kShadowChroma] = s.shadow_chroma;
  v[kNotch] = s.notch ? 1.0f : 0.0f;
  v[kClip] = s.clip;
  v[kMhcInit] = s.mhc_init ? 1.0f : 0.0f;
  v[kStepScale] = s.step_scale;
  v[kOverlap] = float(p.overlap);
  v[kWorkers] = float(p.workers);
  v[kSigmaMax] = p.smax_x;
  return v;
}

// Overrides the finite entries of `t` (size n) on top of `prm`.
inline void ApplyTuning(const float* t, int n, FullParams* prm) {
  SolveParams& s = prm->solve;
  auto has = [&](int i) { return i < n && std::isfinite(t[i]); };
  auto on = [&](int i) { return t[i] >= 0.5f; };
  auto ival = [&](int i) { return int(std::lround(t[i])); };
  if (has(kIterations)) s.T = std::max(1, ival(kIterations));
  if (has(kZSteps)) s.z_steps = std::max(1, ival(kZSteps));
  if (has(kIterations) || has(kPriorEvery)) {   // prior on every n-th iteration, ending on an odd one as before
    const int every = has(kPriorEvery) ? std::max(1, ival(kPriorEvery)) : 2;
    s.prior_at.clear();
    for (int it = every - 1; it < s.T; it += every) s.prior_at.push_back(it);
  }
  if (has(kLookDayPrior)) prm->look_day_prior = t[kLookDayPrior];
  if (has(kLookNightPrior)) prm->look_night_prior = t[kLookNightPrior];
  if (has(kLookDayGrain)) prm->look_day_grain = t[kLookDayGrain];
  if (has(kLookNightGrain)) prm->look_night_grain = t[kLookNightGrain];
  if (has(kRobust)) s.robust = on(kRobust);
  if (has(kRobustK)) s.robust_k = prm->robust_k_day = prm->robust_k_night = t[kRobustK];   // fixed at all noise levels
  if (has(kRobustTau)) s.robust_tau = t[kRobustTau];
  if (has(kRobustSpread)) s.robust_spread = std::max(0, ival(kRobustSpread));
  if (has(kTemporalK)) s.temporal_k = t[kTemporalK];
  if (has(kTemporalTau)) s.temporal_tau = t[kTemporalTau];
  if (has(kEqualizeNoise)) s.equalize_noise = on(kEqualizeNoise);
  if (has(kLocalMotion)) s.local_block = on(kLocalMotion) ? 16 : 0;   // jacblk workgroup: 16 or off
  if (has(kLocalLambda)) s.local_lambda = t[kLocalLambda];
  if (has(kMotionEvery)) s.motion_every = std::max(1, ival(kMotionEvery));
  if (has(kTileLk)) prm->tile_lk = on(kTileLk);
  if (has(kMeanPreserve)) s.mean_preserve = std::max(0, ival(kMeanPreserve));
  if (has(kShadowBoost)) s.shadow_boost = t[kShadowBoost];
  if (has(kShadowChroma)) s.shadow_chroma = t[kShadowChroma];
  if (has(kNotch)) s.notch = on(kNotch);
  if (has(kClip)) s.clip = t[kClip];
  if (has(kMhcInit)) s.mhc_init = on(kMhcInit);
  if (has(kStepScale)) s.step_scale = t[kStepScale];
  if (has(kOverlap)) prm->overlap = std::max(0, ival(kOverlap)) & ~1;
  if (has(kWorkers)) prm->workers = std::max(1, ival(kWorkers));
  if (has(kSigmaMax)) prm->smax_x = t[kSigmaMax];
}

}  // namespace lkr
