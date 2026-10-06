# HDR+ parity proof: RawLens Vulkan port vs Burst Photo (hdr-plus-swift)

Upstream: https://github.com/martin-marek/hdr-plus-swift at
`69cb0572bb6712e160c448260125cb6099bdfd87` (2024-08-24), GPL-3.0.
Intermediate: RAWR `merge_hdrplus` at `f41e6c2c493cb2ebfe37ca7dcb98a6e38a5d40a5`
(GPL-3.0-only), which ported Burst Photo's merge to GLSL compute.

## Claim

The RawLens HDR+ merge (`app/src/main/cpp/hdrplus/`, shaders in
`app/src/main/assets/spirv/hdrplus/`) reproduces Burst Photo's merge on the
path our app can execute — uniform-exposure Bayer bursts, "Medium" tile 32 /
search 64, strength 1..22 — with the exposure-control-off behavior upstream
selects for that path (plain pyramid, no black/WB normalization, no tone
mapping, tile-border repair skipped). Every kernel and every dispatch step
was compared line-by-line against the Swift/Metal original; gratuitous
deviations were fixed (see "Fixes applied"), and the remaining differences
are performance-motivated reformulations with identical real-number math
(see "Remaining deviations"). RAWR measured ~80 dB PSNR vs the Metal
original on RZSL bursts for the Fast path; our port is bit-exact vs RAWR
except for the documented upstream-parity patches below.

Two deliberate scope boundaries (not bugs):

- The app only produces uniform-exposure Bayer ZSL bursts, so X-Trans
  kernels, bracketed-exposure branches, exposure-control tone mapping, the
  strength-23 temporal-average mode and the DNG/CLI tooling are not ported.
- HQ defaults to RAWR's align-once mode (align pass 0 only, continuous warp
  weights). The upstream-exact schedule is `frequency_align_once = 0`
  (per-pass alignment, upstream warp weights); the parity runner covers both
  modes and both clear the same bars.

Assumption used throughout: a Metal `texture.read` outside the texture
returns zero. Burst Photo relies on safe out-of-bounds reads (its
`correct_upsampling_error` multiplies them by zero), and RAWR's stable ~80 dB
measurement implies deterministic zeros rather than undefined values.

## Method

1. Read all 6161 lines of the Swift/Metal merge (`burstphoto/align`,
   `burstphoto/merge`, `burstphoto/texture`, `burstphoto/exposure`,
   `denoise.swift` driver).
2. Verified the 28 vendored `.comp` shaders + `RawMergeHdrPlusGpu.h` were
   byte-identical to RAWR (pre-patch md5s in UPSTREAM.md), then compared each
   shader's math, loop bounds, index expressions and accumulation order
   against its Metal kernel, and each push constant / binding / group count /
   barrier in `hdrplus_run.cpp` against RAWR's `HdrPlusRecorder.cpp` and
   `AndroidBurstCoordinator.cpp` drive loops (which were themselves checked
   against the Swift dispatch sequence).
3. Fixed every deviation that costs nothing (wrong values, wrong order, wrong
   edge rule), rebuilt the SPIR-V with the documented recipe, and re-ran the
   parity runner plus the repo gates.

## Kernel verdicts (Fast / spatial path)

| Shader | Metal original | Verdict |
|---|---|---|
| `hdrp_prepare` | `prepare_texture_bayer` | MATCH (uniform black; rebase onto ref black is a no-op for equal blacks; empty hot-pixel list is a no-op; sensor-list concealment replaces the burst detector by RAWR design) |
| `hdrp_hot_pixel` | `find_hotpixels_*` + conceal | DOCUMENTED SUBSET (sensor list + median-of-4; see deviations) |
| `hdrp_avg_pool` | `avg_pool` scale 2, black 0 | MATCH (incl. fp16-quantize emulation and `/4` vs `*0.25`, both exact) |
| `hdrp_blur` | `blur_mosaic_texture` k2/k16 | MATCH (binomial tables, truncation, border renormalization, fp16 rule) |
| `hdrp_upsample_align` | `upsample_nearest_int` | FIXED then MATCH (was clamp, now zero past the edge like Metal) |
| `hdrp_correct_upsampling` | `correct_upsampling_error` uniform | MATCH except parallel summation order; out-of-frame cost +inf replicated from upstream's half overflow |
| `hdrp_tile_diff` | `compute_tile_differences25` | MATCH except half-vs-float staging and summation order (see deviations); displacement layout, OOB loss `-131008`, L1/L2 rule exact |
| `hdrp_best_tile` | `find_best_tile_alignment` | MATCH (first-minimum scan, `downscale*prev + disp`) |
| `hdrp_warp` | `warp_texture_bayer` | MATCH (grid math, `+0.1` index rule, weights, accumulation order; OOB reads zero) |
| `hdrp_color_diff` | `color_difference` | MATCH (loop order, offsets) |
| `hdrp_column_sum` + `hdrp_mean` | `texture_mean` (single value) | FIXED then MATCH (phase-grouped order, exact) |
| `hdrp_merge_weight` | `compute_merge_weight` | MATCH (incl. robustness-0 bypass) |
| `hdrp_accumulate` | upsample bilinear + `add_texture_weighted` + `add_texture` | FIXED then MATCH (zero past the edge, divide by N, frame-order accumulation over a cleared accumulator) |
| `hdrp_finalize` | `convert_float_to_uint16` role | DOCUMENTED: normalized-float CFA output + app DNG writer instead of upstream's raw-units DNG projection (affine map, tested separately) |

## Kernel verdicts (HQ / frequency path)

| Shader | Metal original | Verdict |
|---|---|---|
| `hdrq_to_rgba` | `convert_to_rgba` | MATCH in per-pass mode (align-once adds a pass-0 offset by RAWR design) |
| `hdrq_warp_rgba` | warp + `convert_to_rgba` fused | MATCH in per-pass mode (`continuous=0`); `continuous=1` is the align-once variant |
| `hdrq_rms` | `calculate_rms_rgba` | MATCH (loop order, `0.25*sqrt/8`) |
| `hdrq_mismatch` | `calculate_abs_diff_rgba` + `calculate_mismatch_rgba` | MATCH except tree-reduction order; the `width-1` end quirk replicated |
| `hdrq_region_mean` | `texture_mean` on the shift-cropped mismatch | FIXED then MATCH (exact association) |
| `hdrq_mismatch_norm` | `normalize_mismatch` + `add_texture` | FIXED then MATCH (divide by N) |
| `hdrq_shift_table` | shift-coefficient preamble of `merge_frequency_domain` | MATCH (same expression, precomputed) |
| `hdrq_merge` | `merge_frequency_domain` uniform | MATCH given the same best shift; shift search is a cross-correlation argmax (same argmin in real arithmetic, see deviations); Wiener weight, magnitude/motion norms, merge update exact |
| `hdrq_deconvolute` | `deconvolute_frequency_domain` tile 8 | MATCH (gain table, weight, update) |
| `hdrq_forward_dft` | `forward_dft` | MATCH (RAWR ports the DFT reference where upstream dispatches its hand FFT — same transform up to rounding; twiddle association fixed) |
| `hdrq_backward_dft` | `backward_dft` | MATCH (same FFT-vs-DFT note; `/N/64` normalization exact) |
| `hdrq_border` | `reduce_artifacts_tile_border` | FIXED by removal: upstream-off skips it (black `-1`); it is vendored but undispatched |
| `hdrq_accumulate` | `convert_to_bayer` + crop + `add_texture` | MATCH (phase unpack, offsets, pass order, `/1` exact) |

## Host sequence verdicts

- Pyramid/padding geometry (`RawMergeHdrPlusGpu.h`): MATCH — level counts,
  tile sizes, search radii, symmetric even padding, frequency pads/crops/
  tile grid, 4-pass shift pattern, mismatch-crop region.
- `robustness` / `frequencyNorms`: MATCH — same formulas (uniform-exposure
  reduction of the exposure corrections: corr1 = corr2 = 1).
- Dispatch values, bindings, group counts, barriers, pass/companion/level
  order, align-slot copy, mismatch clear: MATCH vs RAWR's recorder, which
  matches the Swift sequence step-for-step (verified: prepare → pyramid →
  align levels coarse-to-fine → warp → merge → accumulate → finalize;
  frequency: rgba → rms → forward → per-companion warp/mismatch/mean/norm/
  merge → deconvolve → backward → accumulate ×4 → finalize).
- Driver mapping: reference = middle frame, strength 13, tile 32, search 64:
  MATCH. Strength 23 (plain average) is intentionally not exposed.

## Fixes applied (this audit)

Deviations from Burst Photo that cost nothing to fix were fixed; each
breaks RAWR byte-identity for that file on purpose (pre-patch md5s, equal
to RAWR `f41e6c2`, are listed in `spirv/hdrplus/UPSTREAM.md`):

1. HQ tile-border pass skipped (`hdrplus_run.cpp` `recordPassFinish`):
   upstream `reduce_artifacts_tile_border` returns early for unknown black
   (`-1`), i.e. always on the exposure-off path. Running it with a
   synthetic `-2` floor blended every tile border away from upstream.
   The effect is mild in practice (both blend terms carry the raised-cosine
   window, so it is ~identity where the merge already matches the ref),
   but skipping is the upstream-exact behavior.
2. `hdrp_upsample_align`: out-of-range source indices yield zero instead of
   the clamped edge tile (Metal OOB reads are zero; upstream hits this at
   the last row/column of levels below the tile-8 floor).
3. `hdrp_accumulate`: weight samples past the cell grid read zero instead of
   clamping (Metal OOB rule at the last output row/column).
4. Frame-count division: `hdrp_accumulate` and `hdrq_mismatch_norm` divide
   by N like `add_texture` instead of multiplying by a reciprocal.
5. Reference accumulated in frame order over a cleared `hdrp_accum`
   (`run_spatial`), matching upstream's zero-filled final texture and
   frame loop (same association).
6. Noise/mismatch means use upstream's exact phase-grouped association
   (`hdrp_column_sum` now emits even/odd column sums, `hdrp_mean` and
   `hdrq_region_mean` group by 2x2 phase and add phases in order).
7. DFT twiddle arguments associate left-to-right (`angle*dm*dx`) as in the
   Metal kernels.

## Remaining deviations (accepted, quantified)

- Half-vs-float alignment costs: upstream stages comparison samples in
  fp16 (`0.5×` in half); we compute in float from fp16-quantized inputs.
  ~2^-11 relative cost noise; can flip integer vectors on near-ties.
- Parallel summation orders in tile costs, upsampling correction and the
  mismatch window (serial upstream). Rounding-level; can flip near-ties.
- FFT-vs-DFT: upstream dispatches its hand-rolled FFT for 8x8 tiles; RAWR
  ports the DFT reference both approximate. Rounding-level (~1e-7).
- Shift-search reformulation: cross-correlation argmax instead of
  least-squares argmin — the same argmin in real arithmetic (rotation
  preserves magnitude), possibly different float rounding on near-ties.
- Transcendentals (`cos`/`sin`/`pow`) and host `pow` may differ ~1ulp
  between Metal and Vulkan/libm drivers. Inherent to any port.
- `sqrt` is correctly rounded (identical); fp16 quantization matches
  (round-to-nearest-even both sides); int/float conversions exact.
- Hot pixels: sensor list + median-of-4 concealment instead of the
  burst-average detector (RAWR design; empty list = no-op; Camera2 wiring
  pending). Black rebase onto the reference is a no-op for equal blacks.
- Align-once HQ (default) vs per-pass HQ (upstream-exact): documented mode,
  both covered by the parity runner.
- Output convention: normalized float CFA + app 16-bit DNG writer vs
  upstream raw-units projection (affine map; writer covered by unit tests).

## Evidence

- `tools/hdrplus-parity` (shipped host + recorder on desktop Vulkan,
  Apple M1, 2026-10-06): ALL CHECKS PASSED — Fast identity 159.7 dB,
  denoise +5.8 dB, shift 159.8 dB; HQ identity 56.0 dB, denoise +5.3 dB,
  shift 55.6 dB; HQ-perpass identity 56.0 dB, shift 55.6 dB;
  bit-identical determinism on both paths.
- `:app:buildCMakeDebug` green: `libhdrplus.so` for all 4 ABIs with the
  patched host.
- Unit gate `:app:testDebugUnitTest`: NOT re-run — blocked by a pre-existing
  compile error in a peer session's untracked file
  (`DirectLogRecorder.kt:1293`, `riparms` typo, not part of this change).
  Last green before this audit: HdrPlusSettings 6/6, HdrPlusDngWriter 2/2,
  DngExif 8/8, full suite 1129/1130 (1 pre-existing HdrTileDeghost failure).
  No Kotlin file was touched by this audit, so those results still stand.
