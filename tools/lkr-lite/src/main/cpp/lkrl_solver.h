// SPDX-License-Identifier: Apache-2.0
#pragma once

// LK-R Lite tile solver: a few Jacobi-preconditioned merge steps (J) alternated with
// Gauss-Newton motion steps (G) against the merge, robust weights computed against the
// reference-only start image once per geometry. See ../../../README.md.

#include <deque>
#include <string>
#include <vector>

#include "lkrl_gpu.h"

namespace lkrl {

struct LiteParams {
  int s = 2;
  // Empty = by scale: 1x GGJJGJJJ; 2x GGJJJGJJJJJJJ (10 merge steps: grain / detail of LK-R's 24 data
  // steps at 2x). 3 motion steps match LK-R better than 5 (car 1x 59.93 -> 60.20 dB, worst block
  // 36.7 -> 38.4; IMG_20210228_133623 2x 52.31 -> 52.46) and save 2 jac / jacblk passes + round trips.
  std::string schedule;   // G = motion step + weight refresh, J = merge step; leading G align to the reference-only image
  float noise_a = 0, noise_b = 0;     // our own fit: var = a y + b (normalised raw units)
  float clip = 0.98f;
  float robust_k = 3.0f, robust_pow = 1.0f, robust_tau = 0.1f;
  int robust_spread = 3;
  // Reference-guide confidence (LK-R --guide): > 0 = strength (LK-R default 2). Needs even h, w.
  float guide = 2.0f;
  int guide_mode = 1;
  // LK-R's exact weight chain (shaders/lkr, verbatim): temporal gate vs the reference-only image
  // (detail term, dark-wide window) x reference guide, min spread; recomputed per geometry.
  // Needs guide > 0 (LK-R --guide); false = Lite's own residual test (guide_mode).
  bool lkr_weights = true;
  // LK-R's chain once per tile (after the first motion steps); later refreshes only update the
  // validity of the moved geometry (13.4 s of 39 s GPU on Mali car 1x was the chain, run twice).
  bool lkr_weights_once = true;
  // DCT x-step after the merge (LK-R's dct_x shaders, one pass on the final z): x = denoise(z).
  int chroma_from_avg = 5;   // 1: chroma of the 'A' average, fit continues from it; 2: fit restarts from the start image   // schedule with 'A': output chroma from the average, luma from the fit
  bool dct = false;
  bool dct_fallback = false;   // shrink only the excess noise of lost frames (noise map sqrt(ns^2 - 1))
  float dct_sigma = 0.0f;                      // noise level handed to the shrinkage (LK-R: sigma_t * prior_gain)
  float dct_luma = 1.0f, dct_chroma = 16.0f;   // thresholds, multiples of the noise level
  float dct_dark_level = 0.02f, dct_dark_cap = 8.0f, dct_dark_luma = 0.25f;
  float wb[3] = {1.0f, 1.0f, 1.0f};
  bool robust = true;
  float temporal_k = 3.0f, temporal_tau = 0.6f, temporal_detail = 4.0f, temporal_dark_level = 0.02f;
  bool temporal_dark_wide = true;   // 0: guide replaces the residual weight (LK-R); 1: min(guide, residual)
  int local_block = 16;               // must be 16 (jacblk workgroup)
  float local_lambda = 0.1f;   // before the noise scaling (auto_local_lambda)
  float local_floor = 0.02f;
  bool auto_local_lambda = true;   // lambda * max(1, (sigma(0.18) / 0.0025)^2), cap 5 (LK-R)
  // Motion-step sample weights: 0 = robust weights (computed with the CURRENT, possibly wrong,
  // geometry: rejects exactly the samples that would fix it), 1 = validity only.
  int gn_weights = 1;
  // Jacobi damping delta = jacobi_damp * K / s^2 (a fully covered pixel has ~K / s^2 per channel
  // share): 0 = plain Jacobi.
  float jacobi_damp = -1.0f;   // < 0: by scale (0 at 1x and 2x: 0.1 at 2x lowered the HR checker but cost
                               // fine foliage detail vs LK-R, detail ratio 0.87 -> 0.96 at 0)
  // Start image: share of the coherent MHC demosaic (1), rest bilinear (0). MHC is sharper but
  // leaves a Bayer maze on fine foliage that a few merge steps cannot fit away (1x).
  // Coverage floor of the merge step, fraction of the channel's expected coverage: holds HR pixels
  // where most frames were rejected near the current image (false colour / maze from sparse samples).
  float cov_floor = 0.0f;   // unused (kept for A/B notes): replaced by lkr_steps
  // LK-R step response: N data steps of LK-R's global step 1.8 / L, L = lkr_l * K / (2 s^2)
  // (the green plane's expected coverage). 0 = plain Jacobi.
  int cov_smooth = -1;   // coverage box radius in the merge step; < 0: by scale (1x 0, 2x 1)
  float lkr_steps = 24.0f, lkr_l = -1.0f;   // lkr_steps: LK-R data steps over the whole schedule (T 8 x 3);   // lkr_l < 0: by scale, LK-R's L = max(U^T 1) measured 1.4-1.5x (1x) / 1.6-1.9x (2x) of K / (2 s^2)   // AppDefaults: T 8 x 3 z-steps
  float mhc_mix = -1.0f;   // < 0: by scale (1x 0.75: foliage checker and detail at LK-R level, 2x 1)
  // Bayer-period notch on the start image (LR grid, before upsampling): the checker and row /
  // column alternations of the luminance, estimated over a (2r+1)^2 box and removed from all
  // three planes. MHC on fine foliage leaves a maze the few merge steps cannot fit away.
  int start_notch = 0;   // box radius in LR px; 0 = off               // start image: coherent MHC of the reference (else bilinear)
  bool profile = false;
};

struct LiteStats {
  double ms_total = 0, ms_gpu = 0, ms_host = 0;
  int submits = 0;
  std::string profile;
};

class LiteSolver {
 public:
  explicit LiteSolver(Gpu* gpu);
  ~LiteSolver();
  bool ok() const { return ok_; }
  std::string profileNames() const;   // pipeline address -> shader name (Gpu::profileReport)
  // frames: K x h x w RGGB-normalised, frame 0 = reference. p: K x 6 affine start (in/out).
  // dloc: K x nb x 2 local offsets start (in/out, may be empty = zero). z_out: 3 x sH x sW.
  // noise_scale (optional): LK-R's per-HR-pixel noise factor min(sqrt(max(0.8 / rho, 1)), sqrt(K)),
  // rho = weighted / plain coverage over the three planes (FinishLook notch / fallback input).
  bool solve(const float* frames, int K, int h, int w, std::vector<float>& p, std::vector<float>& dloc,
             const LiteParams& prm, std::vector<float>* z_out, LiteStats* st, std::string* err,
             std::vector<float>* noise_scale = nullptr, std::vector<float>* resid_out = nullptr,
             std::vector<float>* weights_out = nullptr, std::vector<float>* x_out = nullptr);
  // FinishLook default path on the GPU (fin.comp): notch, luma notch where frames were lost, clip
  // halo, grain, highlight desaturation. x in/out (3 H W planar camera RGB).
  struct FinishArgs { float wb[3]; float clip, grain, eq_strength; bool notch, desat; };
  bool finish(const float* z, std::vector<float>* x, int H, int W, const FinishArgs& fa,
              const std::vector<float>* noise_scale, std::string* err);   // final residual vs z (K x h x w, 0 = invalid), final weights

 private:
  struct Entry { Buffer buf; size_t bytes = 0; bool host_read = false; };
  std::deque<Entry> cache_;
  std::deque<Entry> fcache_;   // finish() buffers   // per-solver buffers, reused across tiles (deque: stable references)
  Gpu* gpu_;
  bool ok_ = false;
  Pipeline resid_, werr_, wavg_, wminh_, wminv_, adj_, zupd_, jac_, jacblk_, solveblk_, gstats_, wguide_, wvalid_,
      kresid_, ktdet_, kth_, kt_, ktv_, ktd_, kguide_, kminh_, kmin_, kdy_, kdu_, kdv_, kdyuv_, knconv_, kndn_, hyb_, adjwg_, fin_;
};

}  // namespace lkrl
