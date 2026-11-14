# MFSR C9: reference parity chain (CUDA -> CPU port -> RawLens), 2026-11-14

Checklist §M C9 ("dim-burst divergence vs the Python reference, 70.5 dB").
Question asked by the owner: is the CPU port of the reference
(matthew777777/Handheld-Multi-Frame-Super-Resolution, commit 0b4371c, Numba
njit + PyTorch CPU) faithful to the original Numba-CUDA code (Jamy-L
upstream, its parent 27c4655), and is RawLens' sr-pipeline faithful to it?

References used, in order of authority for "what the code does":
1. Jamy-L upstream at 27c4655 (the IPOL companion code of Lafenetre et al.,
   ipol.im/pub/pre/460; its README notes the repo now diverges from the
   IPOL demo).
2. Numba-CUDA lowering rules (numba 0.68.1 `numba/cuda/cudaimpl.py`), for
   rounding and type unification.
3. Wronski et al. 2019 for intent. Paper-vs-IPOL settings were already
   settled in mfsr-paper-gaps.md (keep IPOL s1/s2 and GAT); nothing here
   reopens them.

## Method (no NVIDIA GPU needed)

The original kernels run unmodified under the Numba CUDA simulator
(`NUMBA_ENABLE_CUDASIM=1`) with three shims: torch tensors become simulator
device arrays sharing memory (what `__cuda_array_interface__` does on a real
GPU), `cuda.as_cuda_array` likewise, and `cuda.shfl_down_sync` (absent from
the simulator) is emulated with two block barriers. Harness committed to the
fork: `tools/cuda_sim_parity/` (`setup.sh`, `kernel_parity.py`,
`tile64_known_shift.py`). Caveat: the simulator executes Python/NumPy-2
arithmetic, so float32-vs-float64 promotion differs from compiled
Numba-CUDA (NEP 50: `float32 * python float` stays float32 there). It is a
logic oracle; differences around 1e-5 are typing noise, not findings.

## Leg 1: CPU port vs original CUDA

Every kernel was compared on random and structured inputs (clipped patch,
odd-integer flows, all tile sizes, scale 1/1.5/2, both kernel laws, noise
correction on/off). Orchestration (super_resolution.main, alignment loop,
L2 FFT block matching, merge, kernels, GAT, grey, guide, S, threshold,
local min) is a faithful transcription; the port fixed two harmless
truthiness bugs on device arrays (`assert ref_local_means`,
`and accumulated_r`) with `is not None`.

Deviations found:

| # | Where | Original CUDA | CPU port (0b4371c) | Impact | Fork fix |
|---|---|---|---|---|---|
| 1 | `round()` in L1 start flow, warp centre, noise-LUT index | `__nv_llrint`: half to **even** | `round_half_away` ("matches CUDA round()", false) | warp at image border with exact .5 positions: 0.016 in warped means -> R up to 0.31 on those pixels (border only: the clamp duplicates edge pixels, so the window is not symmetric); L1/LUT only at exact ties | `round_half_even` (np.rint) |
| 2 | accumulators `mean_/var_` (local stats), `w_acc` (warp), `d_sq_/sigma_sq_/brightness` (d/sigma) | initialised with int `0`: Numba unifies to **float64** | forced `np.float32(0)` | precision only (125 dB on a real crop) | float `0.0` |
| 3 | `cuda_L1_local_search64` (SNR <= 14 dB) | **bug**: shared patch indexed without `shift_x/shift_y`; every candidate equal, always returns (-R, -R) | correct exhaustive search | night bursts: CUDA adds (-1,-1) px at the finest level (R = 1) before ICA | kept (documented deviation) |
| 4 | `ica_kernel_64` (SNR <= 14 dB) | **bug**: 4-row sliding window advances `floor_y` before loading `floor_y+1`, interpolating rows y0 and y0+2 | correct per-pixel bilinear | known 0.4 px shift: CUDA y = -1.063, port -0.147 (x identical) | kept (documented deviation) |

After fixes 1-2 every kernel agrees to <= 4e-5 except the two intentional
64-px deviations (kernel_parity.py, 33 checks). ICA 8/16/32 and L1 16/32 are
bit-identical or within 1e-6. Fork tests (tests/test_cpu_pipeline.py) pass.

Consequence: for SNR <= 14 dB (64 px tiles) the CPU port, and therefore
RawLens, do NOT reproduce the original CUDA output, by design: the CUDA
output is wrong there. Upstream bug report candidates (not filed).

## Leg 2: RawLens sr-pipeline vs the reference

Engine-level, identical float inputs (`C9ReferenceE2eTest`, opt-in diag;
Python side `tools/sr-pipeline/parity/c9/c9_export.py` exports the burst crop, the reference's
effective SNR-tuned config and its output). RawLens config: Python-exact
(edgeExtendFlow off, kMin 0, no colour-diff, S.3 off, noise correction off;
hot pixels / shading are outside EndToEnd). Burst IMG_20260921_194618_520
(the C9 burst), 8 frames.

| Crop | SNR | Tiles | RawLens vs reference | With reference flows injected |
|---|---|---|---|---|
| 1024 @ (1024,1024) | 24.0 dB | 16 | 81.9 dB, max 0.039, 0.009 % px > 1/255 | 89.5 dB, max 0.039 |
| 2048 @ (0,0) | 23.2 dB | 16 | 82.4 dB, max 0.039, 0.009 % px > 1/255 | 95.7 dB, max 0.039 |

(The old 70.5 dB predates C10/C18/C26 and had post-paper changes mixed in;
these numbers isolate the engine.) Two causes, bisected stage by stage:

1. **Kernel NaN guard on clipped highlights (all the max 0.039 pixels).**
   Clipped flat windows give l1 = l2 = 0, so the reference computes
   A = 1 + sqrt(0/0) = NaN -> NaN covariance -> in the merge
   `z = max(0, NaN)` = 0 (Numba lowers float max to maxnum) -> every 3x3
   weight is 1: a flat box. RawLens (Kernels.computeK and kernels.comp)
   guards the division, giving an isotropic Gaussian of variance
   (kDetail*kDenoise)^2 (0.893 here). Proof: the guard alone added to the
   reference makes RawLens agree at 113.0 dB, max 2.4e-3, with the same
   flows. The guard is intentional (added to stop NaN black holes on the
   RawLens CPU path); it only changes clipped-lamp edges (0.95 vs 0.91 at
   clip). Keep it; Python-exact parity tests must exclude clipped windows
   or add the guard on the Python side.
2. **L2 block-matching near-ties (all the flow divergence).** 4 of 28 672
   tiles (1024 crop) / 25 of 114 688 (2048 crop) differ, starting at one
   coarse-level tile. There the reference's float32 FFT correlation and
   RawLens' FFT pick different integer shifts. Exact float64 SSD of the
   candidates: frame 1 tile (30,17): -3 -> 0.013927, -4 -> 0.013932;
   frame 6 tile (29,17): (-2,+2) -> 0.014015, (-2,+1) -> 0.014021.
   RawLens picked the true minimum both times; the reference picked the
   runner-up (0.04 % worse). Ill-posed tie, not a logic error; ICA then
   converges to different local minima and the error propagates x2 down
   the pyramid. Not fixable by "matching" (any FFT implementation
   difference flips such ties).

Everything else (robustness, kernels, merge on non-clipped pixels) is float
noise: robustness differs by <= 3.7e-3 between RawLens and the port, less
than the port differs from its own CUDA-typed variant (1.1e-2), i.e. the
robustness exponent amplifies last-bit differences; outputs agree at
>= 113 dB once both effects are removed. C9 is explained; no RawLens logic
defect found.

RawLens inherits deviation #1 from the port: Kotlin `roundHalfAway`
(Align.kt, Robustness.kt) and the shaders `align.comp` / `robustness.comp`
use half-away and their comments claim that is CUDA `round()` semantics,
which is false (`corr.comp` correctly uses half-even for torch `.round()`).
Impact on real crops is precision-level except border pixels with exact .5
warp positions (flow exactly an odd integer, e.g. ICA-skipped flat tiles on
the frame edge). Changing it changes app output, so it is left for the
owner (see checklist C9).

## Leg 3: code vs Wronski et al. 2019 (paper + supplement S.1-S.3)

Source: arXiv 1905.03277v1 (24 pages, includes the supplement), read line by
line against upstream 27c4655 (= the port after the fixes above) and
sr-pipeline. "=" means the code does exactly what the text says.

| Paper | What it says | IPOL code / RawLens |
|---|---|---|
| 3.2 alignment | Hasinoff coarse-to-fine tile block matching, then 3 Lucas-Kanade iterations | = (L1 finest, L2 coarser; 3 inverse-compositional LK iterations per level; ICA is a valid LK variant) |
| 3.2, S.2 Ts | tile size [16, 32, 64] px by SNR | = (<=14 dB 64, <=22 dB 32, else 16) |
| Eq. 1 | C = sum c*w*R / sum w*R over frames and a 3x3 neighbourhood; base frame R = 1 | = (merge gathers 3x3, reference merged with r = 1) |
| Eq. 2 | w = exp(-1/2 d^T Omega^-1 d) | = |
| 5.1.2 decimation | grey from each 2x2 Bayer quad, half resolution | = quad mean; IPOL adds GAT first (paper has none; owner kept IPOL, mfsr-paper-gaps.md) |
| Eq. 3 gradients | forward differences in the 3x3 window, four gradient values, structure tensor | = (2x2 forward-difference kernels, tensor summed over the 4 gradients) |
| Eq. 4 + S.1 | Omega = [e1 e2] diag(k1, k2) [e1 e2]^T with k already squared in S.1 | = (covs use k^2) |
| 5.1.2 | Omega computed at half res, bilinearly upsampled | = (bilinear covariance lerp in merge) |
| S.1 A | A = 1 + sqrt(lambda1/lambda2) | **differs**: IPOL A = 1 + sqrt((l1-l2)/(l1+l2)), bounded [1, 2]; the paper's form is unbounded and >= 2 everywhere |
| S.1 k-hat | k1 = kdetail*kstretch*A, k2 = kdetail/(kshrink*A) | **differs**: IPOL linear law k1 = (2-A)+(A-1)/kshrink, k2 = (2-A)+(A-1)*kstretch (interpolates isotropic -> 4:2 anisotropy over A in [1,2]) |
| S.1 D, final k | D = clamp(1 - sqrt(l1)/Dtr + Dth, 0, 1); k = (1-D)*k-hat + D*kdetail*kdenoise, squared | = |
| S.2 kdetail, kdenoise | [0.25..0.33] px, [3..5], piecewise linear in SNR 6..30 | = (lerp 0.33->0.25, 5->3) |
| S.2 Dth, Dtr | [0.001..0.010], [0.006..0.020] in normalised gradient units | **differs**: IPOL [0.81..0.71], [1.24..1.0], because its gradients are taken after the GAT (noise-std units), not on [0,1] intensities |
| S.2 kstretch, kshrink, t, Mth | 4, 2, 0.12, 0.8 px | = |
| S.2 s1, s2 | s1 = 12 (M > Mth), s2 = 2 | **swapped** in IPOL (2 / 12); measured and kept by owner 2026-10-22 (mfsr-paper-gaps.md) |
| 5.2.2 guide | R, B direct, greens averaged, half res | = plus IPOL sqrt on the guide (upstream README: "diverges from the IPOL paper"), so d and sigma are in sqrt units |
| 5.2.2 stats | 3x3 colour mean and spatial std | = (variance summed over channels, d^2 = summed squared mean difference; the paper does not say how channels combine) |
| Eq. 5 | R = s*exp(-d^2/sigma^2) - t | = clamp to [0,1] |
| Eq. 6 | sigma = max(sigma_ms, sigma_md); d = d_ms * d_ms^2/(d_ms^2 + d_md^2) | = algebraically (code works on squares: d^2 * shrink^2) |
| 5.2.2 noise | sigma_md, d_md from Monte Carlo per brightness | = (monte_carlo.py; RawLens NoiseLutGen) |
| Eq. 7-8 | M = span of tile flows over 3x3 tiles; s = s1 if M > Mth else s2 | = (compute_s) |
| Eq. 9 | R-hat = min over 5x5 | = |
| S.3 | reject high-frequency regions that lose variance under low-pass when M is large | not in IPOL; RawLens implements it (mfsr-s3.md), off for parity |
| 6.4 | resample to the desired magnification | = scale parameter (app merges at scale 1, C10) |

Not specified by the paper, filled in by IPOL: warping the moving guide
statistics by half the tile flow with a Dogson biquadratic kernel; NaN
handling of flat windows (see Leg 2, cause 1); GAT before kernels; sqrt
guide. RawLens post-paper additions (all switchable, off in parity tests):
KernelNet default kernel (C25), kMin 0.64 (C26), colour-difference merge
(C15/Z1), edgeExtendFlow (C10), hot pixels (C18), shading after merge (C19),
S.3.

Verdict: the merge, kernel and robustness structure (Eq. 1-9) and every
supplement constant except four are 1:1 with the paper. The four differences
(A formula, k-hat law, Dth/Dtr units, s1/s2 order) are IPOL design choices
already in the reference; RawLens follows IPOL, as decided by the owner.
