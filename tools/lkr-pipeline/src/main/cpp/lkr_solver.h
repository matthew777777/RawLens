// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

// LK-Reloaded solver for one tile (Lecouat, Ponce, Mairal, ICCV 2021):
// HQS with one Gauss-Newton (Lucas-Kanade) motion step per iteration and a
// plug-and-play prior. Same math and conventions as python/lkr/lkr_torch.py
// (the parity reference); see shaders/lkr_common.glsl.

#include <cstdint>
#include <array>
#include <functional>
#include <string>
#include <deque>
#include <vector>

#include "lkr_gpu.h"

namespace lkr {

// Plug-and-play prior x = f(z, sigma). z and x are 3 planes of H*W floats
// (camera-linear, RGGB order R,G,B); they point straight into the mapped
// GPU buffers (no copies on unified memory). Returns false on failure.
// noise_scale: per-HR-pixel factor (H*W, >= 1) on the noise level, from the
// fraction of frames that contributed there (robust weights); null = 1.
using PriorFn = std::function<bool(const float* z, float* x, int H, int W, float sigma, const float* noise_scale)>;

struct SolveParams {
  int s = 2;
  int T = 16;
  int z_steps = 3;
  float sigma_noise = 0.01f;   // data-term scale (our own estimate)
  float sigma_max = 0.1f;      // schedule start
  float sigma_end = 0.01f;     // schedule end
  float lam = 1.0f;
  float step_scale = 1.8f;     // eta = c/(L+mu); c < 2 is stable with the L bound
  bool refine_motion = true;
  std::vector<int> prior_at;   // empty = every iteration
  float L = 0.0f;              // >0 given; 0 = Schur bound max(U^T 1); <0 = power iteration
  // Look controls (not in the paper; PSNR-tuned values give a waxy look):
  float prior_gain = 0.4f;     // sigma handed to the prior = gain * sigma_t (0.4: DSLR look, user-picked)
  // Robust data term (moving objects / occlusion): Cauchy IRLS weights against
  // our noise model sigma^2 = noise_a y + noise_b, from iteration robust_start.
  bool robust = true;
  bool equalize_noise = true;
  // How much the denoiser map / grain follow the contributing-frame factor
  // (0..1; the luma Bayer notch always uses the full factor). MergeFull sets it
  // from the noise level: in clean daylight one reference frame is barely
  // noisy, and full strength smeared static texture where a moving shadow
  // rejected frames (rock bunker under a swaying tree, IMG_20261104_111404).
  float equalize_strength = 1.0f;
  // Temporal weighting: also reject frame k where it disagrees with the
  // REFERENCE-only start image (not the running merge, which already holds
  // the average of e.g. moving water). 0 = off.
  float temporal_k = 3.0f;
  float temporal_tau = 0.6f;
  int robust_spread = 3;        // min-filter radius (LR px) on the robust weights (Wronski robustness); 0 = off
  bool mhc_init = true;         // start from the encoded-video coherent MHC (lkr_mhc, = MhcReference); false = bilinear (paper)   // prior noise map / grain follow the contributing-frame count
  float robust_k = 3.0f;
  float robust_pow = 1.0f;     // rejection curve steepness (1 = Cauchy, 2 = steep: no half-weight blending of moving foliage)
  float robust_tau = 0.1f;      // edge tolerance (LR px misregistration): 0.3 let moving-car edges ghost, 0 rejects static edges
  int robust_start = 0;        // from the start: late rejection leaves ghost colour at unsampled sites
  float noise_a = 0.0f, noise_b = 0.0f;
  // Local motion (wind, moving objects that can be tracked): per-frame
  // translation per local_block x local_block LR block on top of the tile
  // affine, refined by Gauss-Newton each iteration, regularised towards the
  // neighbouring blocks with weight local_lambda * (trace + local_floor).
  int local_block = 16;         // 0 = off (paper model); 16 = jacblk workgroup size
  float local_lambda = 0.1f;
  float local_floor = 0.02f;
  const float* local_init = nullptr;   // optional K x nb vec2 initial field (block search)
  float* local_out = nullptr;          // optional: final field (diagnostics)
  // Prior must not move the local mean: x += lowpass(z - x) after each
  // prior call (GAT-domain denoising lifts night shadows: R/G 1.10 vs raw
  // truth 0.98). Box radius in HR px, 0 = off.
  int mean_preserve = 8;
  // Shadow cleanup (RawLens look): where the local relative noise
  // sqrt(a L + b) / L is well above its mid-grey value, raise the prior's
  // noise map by up to (1 + shadow_boost) -- the network denoises shadows
  // harder, edge-aware -- and return grain divided by the same factor;
  // shadow_chroma blends shadow chroma toward its local mean. 0 = off.
  // Values are the DAY strengths; MergeFull's auto look fades them to 0 at night.
  float shadow_boost = 1.5f;
  float shadow_chroma = 0.7f;
  // Blown highlights: near clipping one colour channel saturates (and is left
  // out of the data term) while the others do not, which leaves single red /
  // blue / magenta pixels on lamp edges. Pull colour toward neutral (luma kept)
  // where any channel nears clip, over a 2-px widened mask. Off in paper parity.
  bool highlight_desat = true;
  bool notch = true;           // remove Bayer-period (zipper/maze) structure from colour + grain
  float clip = 0.98f;          // normalised level at/above which a raw sample is treated as clipped
  float grain = 0.4f;          // add back grain * (removed detail), luminance only (neutral)
  std::array<float, 3> wb{1.0f, 1.0f, 1.0f};   // white balance for the luminance grain
  bool gather = false;
  // Robust data step as a gather (no atomics) instead of the int32-atomic
  // scatter. Exact transpose, same image (61-70 dB), but measured 1.8x SLOWER
  // on Mali-G615 (1024^2: 34.0 s vs 18.6 s): kept as an A/B switch only.
  // One GPU invocation per 2x2 Bayer quad (vec2 row loads, shared affine +
  // local block per quad) for the per-sample robust passes; per-sample maths
  // unchanged (rounding-level diff). Measured NO gain on Mali-G615 (1024^2:
  // 19.5-19.9 s vs 20.2-20.9 s; these passes are bound by the scattered HR
  // tap reads and int atomics, not by the y/wt loads): A/B switch only.
  bool quad = false;
  // Packed bilinear taps (malioc: every pass is load/store bound): zpack.comp
  // stores each HR pixel's 2x2 taps as one vec4 so the robust residual /
  // adjoint passes read 1 vector instead of 4 scalars per sample. Same maths
  // (bit-identical). malioc LS cycles drop 20-30%, but measured SLOWER on the
  // phone (adjoint 3.0 -> 4.5 s, residual 1.2 -> 2.5 s per 1024^2): the packed
  // copy is 4x the bytes and misses cache; the 4 scalar taps share lines.
  // These passes are cache/bandwidth bound, not instruction bound. A/B only.
  bool pack_taps = false;
  // Motion refinement (Eq. 7, global + local) on every iteration for the first
  // motion_warmup iterations, then every motion_every-th (1 = paper: always).
  int motion_every = 2;
  int motion_warmup = 4;
  bool robust_gather = false;         // adjoint as gather fused with the z-step (no atomics); false = scatter (faster on Mali + M1)
  int power_iters = 60;
  float power_tol = 0.0f;      // stop when |dL| <= tol*L (0 = run all)
};

struct SolveStats {
  float L = 0.0f;
  double gpu_ms = 0.0, prior_ms = 0.0, total_ms = 0.0;
  int prior_calls = 0, submits = 0, power_iters_used = 0;
  uint64_t dispatches = 0;
};

class Solver {
 public:
  explicit Solver(Gpu* gpu);
  ~Solver();
  bool ok() const { return ok_; }
  std::string profileNames() const;   // pointer -> shader name map for Gpu::profileReport

  // frames: K x h x w RGGB raw in [0,1] (frame 0 = reference);
  // p: K x 6 affine motions in/out; x_out: 3 x (s h) x (s w).
  bool solve(const float* frames, int K, int h, int w, float* p, const SolveParams& prm,
             const PriorFn& prior, std::vector<float>* x_out, SolveStats* st);

 private:
  struct Entry { Buffer buf; size_t bytes = 0; bool host_read = false; };
  Buffer& Cached(int slot, size_t bytes, bool host_read);
  std::deque<Entry> cache_;   // deque: references stay valid while it grows
  Gpu* gpu_;
  bool ok_ = false;
  Pipeline resid_, zstep_, grad_, jac_, solve_, residg_, zgather_, weight_, scatter_, jacblk_, solveblk_, wmin_, wtemp_, wminh_, radjw_, wexp_, wth_, zgw_, residq_, radjwq_, scatq_, zpack_, residzq_, radjwzq_;
};

// CPU helpers (exact mirrors of the python reference).
void FinishLook(const float* z, float* x, int H, int W, const SolveParams& prm, const float* noise_scale = nullptr,
                const float* shadow = nullptr);
void DemosaicBilinear(const float* raw, int h, int w, float* rgb);           // scipy 'mirror'
void UpsampleBilinear(const float* src, int C, int h, int w, int s, float* dst);  // torch align_corners=False

}  // namespace lkr
