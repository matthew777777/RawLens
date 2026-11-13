// SPDX-License-Identifier: Apache-2.0
#pragma once

// Full-frame LK-Reloaded: overlapping tiles over the whole sensor, each its
// own affine motion (the paper's "globally piecewise-affine" model, Sec. 3.1),
// initialised from ONE global coarse LK on a binned frame and refined per tile
// by the solver's Gauss-Newton step. Inputs stay as the caller's uint16 RAW
// planes; each tile is normalised on the fly (no full-frame float copies of
// the burst). Output: linear camera RGB, interleaved, sensor coordinates,
// (s W) x (s H).

#include <array>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "lkr_solver.h"

namespace lkr {

struct RawFrame {
  const uint16_t* data = nullptr;
  int row_stride_px = 0;   // pixels per row (>= width)
  // > 0: `data` is a staged frame (lkr_pack.h), rows of row_stride_px samples packed at this
  // many bits; 0: plain uint16 samples.
  int packed_bits = 0;
};

struct FullParams {
  int s = 1;               // output scale (1 or 2)
  int tile = 512;          // LR tile edge incl. overlap (even)
  int overlap = 32;        // LR overlap between tiles (even)
  // Optional zero-initialised caller allocation for (w, h, 3) floats; null or a null result uses rgb_out.
  std::function<float*(int, int)> out_alloc;
  // Optional: refines the burst's own noise fit (a, b in normalised raw units,
  // bins = its confidence), e.g. with the app's learned per-device model.
  // Returns false to keep the fit. Empty = burst fit only (desktop).
  std::function<bool(float* a, float* b, int bins)> noise_refine;
  int workers = 3;
  // Cap on the summed per-worker solver buffers (MB, estimated from K, tile and s); 0 = no cap.
  // Each worker holds its own buffers, so large bursts drop to fewer workers instead of running out of memory.
  int solver_budget_mb = 0;
  int min_workers = 1;   // the budget never drops below this (x2 keeps 2: 1 worker is ~15-40 % slower)         // concurrent tile solvers: GPU + CPU work of some overlaps the prior of another (3 measured best on MT6878)
  int crop_x = -1, crop_y = -1, crop_w = 0, crop_h = 0;   // optional sub-rectangle (sensor px)
  SolveParams solve;       // T, z_steps, prior_at, step_scale (sigma_* filled here)
  // Host diagnostic: known-static sensor rectangle x,y,width,height.
  std::array<float, 4> trusted_static{0, 0, 0, 0};
  float smax_x = 10.0f, end_x = 1.0f;   // schedule in multiples of our noise sigma
  // Noise-aware look controls. The old daylight prior 0.15 plus 0.8 residual
  // reinjection retained substantial shadow noise. Keep the denoised estimate
  // by default; explicit Lab overrides can still request residual texture.
  // false = use solve.prior_gain / solve.grain as given.
  bool auto_look = true;
  float look_day_prior = 0.35f, look_night_prior = 0.35f;   // auto look endpoints (LK-SR Lab)
  float look_day_grain = 0.0f, look_night_grain = 0.0f;
  float equalize_day = 0.25f, equalize_night = 1.0f;
  // Motion rejection follows the noise level too (2026-11-04, wind-blown pine in
  // IMG_20261104_111404): daylight uses a steep curve at k 2, so frames moved by
  // the wind drop out instead of blending at half weight (streaky smear, -40 %);
  // night keeps Cauchy at k 3 (steep at night over-smoothed the building 20 %).
  float robust_k_day = 2.0f, robust_k_night = 3.0f;
  float robust_pow_day = 2.0f, robust_pow_night = 1.0f;   // noise-equalisation strength, blended like the look
  // LK baseline (not a reproduction of the trained paper model):
  // turns OFF every RawLens addition -- DSLR look (prior gain / grain / auto),
  // Bayer notch, clip masking, robust weights + noise equalisation, local
  // motion + block search. Noise estimation, schedule and prior stay.
  bool paper_parity = false;
  bool tile_lk = true;
  // Reference = sharpest frame (Laplacian of the Bayer-cell grey, per unit
  // brightness), not simply the first: a wind- or shake-blurred first frame
  // otherwise becomes the image LK-SR builds on (pine in IMG_20261104_111404).
  // The LK baseline keeps the first frame.
  // Parallax (handheld camera moved: near objects shift up to 35 px relative
  // to far ones, IMG_20261104_111404 bunker vs road below): each solve tile is
  // also aligned in sub_lk x sub_lk LR quadrants (affine from the tile's), and
  // the difference starts the 16-px local motion field. 0 = off.
  int sub_lk = 128;
  float sub_lk_gain = 0.90f;      // keep a quadrant only if it cuts the alignment error by >= 10 % (3 % fitted noise: blurred night treelines)
  float sub_lk_max_noise = 1.0f;  // off above this look level (0 day .. 1 night); 1 = on at all levels
  int sub_lk_night = 256;         // quadrant size at night (look level > 0.5): more structure per fit
  float sub_lk_blur_night = 1.5f; // pre-blur radius (grey px) at night, scaled by the look level: align on structure, not noise
  bool sharpest_ref = false;   // measured: did not help the wind-blurred pine; kept off        // per-tile coarse LK refinement of the global affine (corners)
  bool block_search = false;  // align.comp start for LARGE motion: off by default (false matches smeared wind-blown foliage)
  // Held-out validation (desktop diagnostics): input frame index (>= 1) that is left out of the
  // merge, aligned to the finished image and compared with it through the forward model.
  // FullStats::holdout receives the maps. -1 = off. Needs >= 3 frames.
  int holdout = -1;
  bool auto_local_lambda = true;   // scale solve.local_lambda by (noise / 0.0025)^2, cap 5   // init local motion with sr-pipeline align.comp (coarse-to-fine)
};

struct FullStats {
  int tiles = 0;
  float sigma = 0, a = 0, b = 0;
  float fit_a = 0, fit_b = 0;   // the burst's own fit, before noise_refine
  bool noise_learned = false;   // a, b came from noise_refine (learned model)
  double ms_noise = 0, ms_coarse = 0, ms_tiles = 0, ms_gpu = 0, ms_prior = 0, ms_total = 0;
  int prior_calls = 0;
  double ms_setup = 0, ms_wait = 0;   // extra Vulkan devices + all solver pipelines; workers waiting for their blend turn
  double ms_init = 0, ms_finish = 0, ms_blend = 0;   // sums over tiles: solver CPU start, FinishLook, output blend
  double ms_solve = 0, ms_prep = 0, ms_load = 0;   // ms_load: LoadTile part of ms_prep
  int workers = 0;
  int ref_index = 0;   // input frame used as the reference (sharpest_ref)   // tile workers actually running (own Vulkan context each)   // sums over tiles: solver wall, tile load + per-tile LK
  float prior_gain = 0, grain = 0;   // look actually used
  float sigma_look = 0;
  float local_lambda = 0;              // noise level the look was chosen from
  std::string gpu_profile;   // per-shader GPU ms (when Gpu::profile is on)
  // FullParams::holdout: 3 planes of holdout_h x holdout_w, on the held-out frame's own RGGB grid
  // (shifted against the output by that frame's motion): squared residual / expected noise
  // variance, usable share of the sample (0..1), signed residual / expected noise sigma.
  std::vector<float> holdout;
  int holdout_w = 0, holdout_h = 0;
};

// cfa: LibRaw codes of the 2x2 cell, row-major (0=R, 1=G, 2=B).
// black: per cell (same order), white: white level.
// PriorFactory is called once with the fitted noise (a, b, sigma) and wb, and
// returns the PriorFn used for every tile (empty = 'none').
using PriorFactory = std::function<PriorFn(float a, float b, float sigma, const std::array<float, 3>& wb)>;

bool MergeFull(Gpu* gpu, const std::vector<RawFrame>& frames, int W, int H, const int cfa[4],
               const float black[4], float white, const std::array<float, 3>& wb, const FullParams& prm,
               const PriorFactory& make_prior, std::vector<float>* rgb_out, int* out_w, int* out_h,
               FullStats* st, std::string* err);

}  // namespace lkr
