// SPDX-License-Identifier: GPL-3.0-or-later
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
#include <string>
#include <vector>

#include "lkr_solver.h"

namespace lkr {

struct RawFrame {
  const uint16_t* data = nullptr;
  int row_stride_px = 0;   // pixels per row (>= width)
};

struct FullParams {
  int s = 1;               // output scale (1 or 2)
  int tile = 512;          // LR tile edge incl. overlap (even)
  int overlap = 32;        // LR overlap between tiles (even)
  int workers = 3;         // concurrent tile solvers: GPU + CPU work of some overlaps the NPU prior of another (3 measured best on MT6878)
  int crop_x = -1, crop_y = -1, crop_w = 0, crop_h = 0;   // optional sub-rectangle (sensor px)
  SolveParams solve;       // T, z_steps, prior_at, step_scale (sigma_* filled here)
  float smax_x = 10.0f, end_x = 1.0f;   // schedule in multiples of our noise sigma
  // DSLR look follows OUR noise model at mid-grey (user-picked 2026-11-04):
  // clean (<= 0.0025, ISO-50 daylight) prior 0.15 / grain 0.8, noisy (>= 0.012,
  // ISO-4653 night) prior 0.4 / grain 0.4, log-linear in between.
  // false = use solve.prior_gain / solve.grain as given.
  bool auto_look = true;
  float look_day_prior = 0.15f, look_night_prior = 0.40f;   // auto look endpoints (LK-SR Lab)
  float look_day_grain = 0.80f, look_night_grain = 0.40f;
  float equalize_day = 0.25f, equalize_night = 1.0f;
  // Motion rejection follows the noise level too (2026-11-04, wind-blown pine in
  // IMG_20261104_111404): daylight uses a steep curve at k 2, so frames moved by
  // the wind drop out instead of blending at half weight (streaky smear, -40 %);
  // night keeps Cauchy at k 3 (steep at night over-smoothed the building 20 %).
  float robust_k_day = 2.0f, robust_k_night = 3.0f;
  float robust_pow_day = 2.0f, robust_pow_night = 1.0f;   // noise-equalisation strength, blended like the look
  // Paper parity (Lecouat et al. 2021 as published, learned parts substituted):
  // turns OFF every RawLens addition -- DSLR look (prior gain / grain / auto),
  // Bayer notch, clip masking, robust weights + noise equalisation, local
  // motion + block search. Noise estimation, schedule and prior stay.
  bool paper_parity = false;
  bool tile_lk = true;
  // Reference = sharpest frame (Laplacian of the Bayer-cell grey, per unit
  // brightness), not simply the first: a wind- or shake-blurred first frame
  // otherwise becomes the image LK-SR builds on (pine in IMG_20261104_111404).
  // Paper parity keeps the first frame.
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
  bool auto_local_lambda = true;   // scale solve.local_lambda by (noise / 0.0025)^2, cap 5   // init local motion with sr-pipeline align.comp (coarse-to-fine)
};

struct FullStats {
  int tiles = 0;
  float sigma = 0, a = 0, b = 0;
  double ms_noise = 0, ms_coarse = 0, ms_tiles = 0, ms_gpu = 0, ms_prior = 0, ms_total = 0;
  int prior_calls = 0;
  double ms_solve = 0, ms_prep = 0, ms_load = 0;   // ms_load: LoadTile part of ms_prep
  int workers = 0;
  int ref_index = 0;   // input frame used as the reference (sharpest_ref)   // tile workers actually running (own Vulkan context each)   // sums over tiles: solver wall, tile load + per-tile LK
  float prior_gain = 0, grain = 0;   // look actually used
  float sigma_look = 0;
  float local_lambda = 0;              // noise level the look was chosen from
  std::string gpu_profile;   // per-shader GPU ms (when Gpu::profile is on)
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
