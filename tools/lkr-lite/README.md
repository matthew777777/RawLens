# lkr-lite

A cheaper LK-Reloaded for mobile GPUs (Mali, Adreno; Vulkan compute). Same
forward model as `tools/lkr-pipeline` (affine + 16-px local motion, box blur
over the LR pixel, Bayer decimation), far fewer passes over the frames.
Target: within 0.5 dB of LK-R at 2x, visually indistinguishable at 1x.

**Standalone**: no LK-R code is linked. The host stages it needs (burst
loading, our own noise fit, coarse LK, MHC, the Vulkan layer) are copies in
namespace `lkrl` (`lkrl_host`, `lkrl_mhc`, `lkrl_gpu`, `lkrl_image`), so LK-R
tuning can never change Lite. Only `libsrpipeline` (Vulkan device, DNG I/O)
is shared, as LK-R and MFSR do.

## Algorithm

Per tile, schedule `GGGJGGJ` (G = motion step, J = merge step):

1. **Start image** z0: reference frame demosaic upsampled to the output grid.
   1x: 50/50 coherent MHC + bilinear (MHC alone leaves a Bayer maze on fine
   foliage that a few merge steps cannot fit away); 2x: MHC.
2. **Motion steps (G)**, first three against z0 itself (the clean reference,
   not a merge blurred by still-misaligned frames), then against the merge:
   - `jac.comp`: affine Gauss-Newton normal equations, one LR sample per lane,
     24 moments about the tile centre, subgroup sums; 6x6 solve on the host.
   - `jacblk.comp` + `solveblk.comp`: 16-px block translations, robust-free
     (validity) weights, LK-R's neighbour-smoothness regulariser
     lambda (trace + floor), lambda noise-scaled, step clamp +-1 px.
3. **Weights**, refreshed lazily only before a merge step (motion steps do not
   need them): residual test against z0 (`resid`, `werr`, `wavg`: LK-R's
   weight_e variance, 3x3 mean, Cauchy) combined by min with the
   reference-guide confidence (`gstats` once per tile, `wguide`), then the
   min-filter spread (`wminh`, `wminv`).
4. **Merge steps (J)**: damped Jacobi
   `z <- z - U^T W (U z - y) / (U^T W 1 + delta)`, fused residual + adjoint
   with int32 fixed-point atomics (`adj`), update (`zupd`).
   delta = 0.1 K / s^2 at 2x (removes the HR-grid checker that per-pixel
   normalisation leaves; LK-R's own image behaves like a damped estimate),
   0 at 1x.

## Results (2026-11-13, Apple M1, 512 x 512 LR crops)

Inputs are LK-R traces (`LKR_TRACE_PREFIX`, defaults, `--prior 0 --guide 2`),
so Lite starts from LK-R's own initial geometry and noise fit. Synthetic
ground truth from `lkr-pipeline/host/lkr_synth_burst.py` (12 frames, template
building F00, source foliage F00, moving person 1.5 px / frame).

PSNR vs truth (dB):

| | LK-R | Lite |
| --- | --- | --- |
| moving person 2x | 24.50 | 24.94 |
| static 2x | 25.49 | 25.32 |
| moving person 1x | 28.03 | 28.44 |
| static 1x | 29.06 | 31.14 |

Real frozen bursts (`/tmp/lkr-conditioning-20261113/inputs`), Lite vs LK-R
image: 1x 41-59 dB, 2x 39-65 dB. Bayer-period checker energy (mean / p99 of
the per-16 px window Nyquist amplitude; lower is better):

| | LK-R | Lite |
| --- | --- | --- |
| foliage 1x | .00407 / .0196 | .00395 / .0161 |
| building 1x | .00233 / .0153 | .00153 / .0094 |
| car 1x | .00231 / .0110 | .00165 / .0113 |
| foliage 2x | .00056 / .0031 | .00048 / .0026 |
| building 2x | .00042 / .0035 | .00040 / .0032 |
| car 2x | .00032 / .0016 | .00036 / .0018 |

Tile solve time, building crop, same sequence: 1x LK-R 288-337 ms vs Lite
61-63 ms; 2x LK-R 608-676 ms vs Lite 146-158 ms (4.3-5x). Mac only; the
phone decides.

malioc (Mali-G615): no stack spills in any pass. `adj` and `jac` at 64
registers (50 % occupancy), the others at full occupancy.

What did not work (measured): more merge steps (noise amplification, worse vs
truth); robust weights in the motion steps (they reject exactly the samples
that would fix the motion); undamped block translations (drift to 3-6 px);
a Bayer-period notch on the start image (too far from LK-R); a bilinear-only
start (2x 0.8 dB softer).

## Build and run (desktop)

```sh
cmake -S tools/lkr-lite/src/main/cpp -B tools/lkr-lite/build/host -DCMAKE_BUILD_TYPE=Release
cmake --build tools/lkr-lite/build/host -j 4
LKR_TRACE_PREFIX=/tmp/x/case-x2 lkr_full_run BURST /tmp/x/case-x2.dng --prior 0 --guide 2 --s 2 --crop 512 512 --at X Y
DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib tools/lkr-lite/build/host/lkrl_run --trace /tmp/x/case-x2_tile_0_0 out.f32
```

`research/`: exact sparse-matrix copy of the operator (`lite_model.py`),
experiments, GPU scoring (`score_gpu.py`), checker metric (`checker.py`),
side-by-side renders (`render_ab.py`). Paths point at the session scratchpad.

## Next

1. Own front end: DNG burst -> tiles, noise fit, coarse + tile + sub-tile LK
   (copies exist in `lkrl_host`), blend, DNG out; then a matched full-frame
   comparison with LK-R through the same finish.
2. Prior / finish (step 5) and the look.
3. Phone: JNI, Mali + Adreno timings, PSS, OOB check (Mali DEVICE_LOST).

## Phone performance (2026-11-13, Mali-G615 MC2, car burst 8 frames, full frame, `--guide 2 --msmooth 1 --msr 1`)

| | LK-R | Lite |
| --- | --- | --- |
| 1x, 3 workers | 52.9 s | 19.7 s |
| 2x, 2 workers | 120.7 s | 48.0 s |

Measured with the cross-built CLIs over adb (`/data/local/tmp/lkrl`, `LD_LIBRARY_PATH=.`).
What paid (each verified on the phone; bit-exact unless noted):
- Per-worker buffer cache (alloc 3.4 s -> 0.3 s summed).
- LK-R weight chain once per tile, later refreshes only validity (GPU 38.9 -> 27.2 s at 1x;
  2x parity 52.318 -> 52.306 dB, not bit-exact).
- 3 motion steps instead of 5 (better parity: car 1x 60.2 dB, 2x 52.46 dB).
- Merge divisor cached per weight refresh (`zupd`, bit-exact).
- 2x merge: 4 sub-samples' taps summed in a 3x3 block, <= 9 atomics instead of 16
  (`adj` 37.2 -> 27.9 s; rounding-level difference, parity 52.51 dB).
- Motion look demosaics only the bounding box it reads (bit-exact; small on Mali).
No gain (kept out): interleaved LK reference (bit-exact, LkAffine is compute-bound),
float LK taps (no gain, flips sub-tile decisions), jac moment-by-moment reduction (malioc LS up),
more workers (within noise).

Remaining time (1x, summed over workers): GPU 23 s, CPU tile LK 17 s (LkAffine = 61 % of
CPU, same code as LK-R), CPU motion look + finish 11.6 s. At 2x: `adj` 28 s of 57 s GPU.

### Round 2 (2026-11-13 evening), tried and measured on the phone
- Batched GPU LK (`lkrl_gpulk`, `shaders/lkfit.comp`; tile + sub-tile fits iterate together,
  LkAffine's control flow on the host). Parity held (car 1x 60.17 vs 60.20 dB with CPU LK, same
  worst block) and Mac prep fell 7.3 -> 5.4 s, but on Mali prep ROSE 16 -> 37-44 s summed: a host
  round trip per iteration, and the small passes queue behind the other workers' solves while the
  CPU LK overlaps them on idle cores. Off by default; `LKRL_GPU_LK=1` to try.
- LK iteration census (car 1x): half of the 7056 sub-tile fits run to the 30-iteration cap
  (flat / ambiguous quadrants); tile fits ~19 iterations. The cost is LK-R's algorithm itself.
- Motion look parallelised over frames / pixels: bit-identical for any thread count
  (`LKRL_THREADS`), but slower on Mali with 3 workers (oversubscription): kept at 1 thread.

### Checker / colour maze on moving subjects, grass, water (fixed 2026-11-13)
Seen in the parity gallery at 2x (cat + pedestrian in IMG_20261112_074919_397, grass in
IMG_20261010_115802_466, water in 5a9e). Cause: the merge step emulates LK-R's N = 24 global
gradient steps per pixel (gain 1 - (1 - 1.8 c / L)^N) but applied all 24 at EVERY merge step, so
sparsely covered pixels (frames rejected) converged ~10x further than in LK-R and fitted the few
same-colour samples alone: a Bayer-period colour maze (1.9x LK-R's amplitude). Fix: N spread over
the schedule (24 / merge steps), and L = LK-R's max(U^T 1) (measured 1.45x / 1.8x the old estimate).
Cat crop: Bayer chroma 0.91/1.02 % -> 0.52/0.68 % (LK-R 0.49/0.70 %), parity 63.7 -> 65.9 dB;
grass 2x 52.8 -> 55.7 dB, 1x 55.1 -> 57.8 dB; water 2x 48.6 -> 50.4 dB (worst block 31 -> 36.8).
Tried and rejected on the way: divisor floor at own coverage, binomial divisor, binomial-filtered
update, a (pi,pi) notch (luminance (pi,pi) is not the visible pattern; the pattern is chroma).

### Parity pass result (2026-11-14, all 21 bursts x 1x/2x, `--guide 2 --msmooth 1 --msr 1`)
Gallery: https://claude.ai/artifact/BWzeJZaJ7ixpj6k9gDyHG5 (DNG pairs in ~/Downloads/LKRL_parity).
Lite vs LK-R: 1x 50.0-67.8 dB (worst 1 % of 64 px blocks 40.8-58.6), 2x 47.1-66.7 dB (38.7-57.3).
Inspected the lowest: 0039 2x and the night burst = different noise realisation in dark flat
areas (same grain level), plus a thin outline on one moving bright object; IMG_20261107 =
specular glints and hard edges only; 33TJ headlight (the "ghost") = clipped specular specks
inside the lamp, colour differs slightly, both pipelines show them. No structural difference left.

## SNR programme (2026-11-14): cleaner merge without learning
Measured with a noise / detail split (`research/snr_bench.py split`: the same synthetic burst with and
without noise, fixed noise model; `lkr_synth_burst.py --no-rail`): LK-R and Lite merges were NOISIER
than one frame at 2x (chroma -5..-7 dB): the per-plane fit amplifies noise.
Lite defaults now:
- Hybrid merge (`chroma_from_avg = 5`): a shift-and-add average (schedule step `A`: normalised
  convolution of U^T W y and U^T W 1, binomial window r 0 at 1x / 1 at 2x, clipped samples weight 1)
  plus the fit's extra detail per channel (Y, R-Y, B-Y), Wiener-shrunk against the model noise of the
  average: w = max(E - t nv, 0) / E. Strength t follows the measured noise: relative noise at mid-grey
  3 % -> off (bit-identical to the fit, no extra cost), 6 % -> t = 1 (user choice: night clean,
  daylight foliage as before). Synthetic high noise at t 16: 2x luma 21.9 -> 33.2 dB, chroma 14 -> 24.
- Lost-frames cleanup (`dct_fallback`, LK-R's mode ported): DCT shrink of only the excess noise
  sqrt(ns^2 - 1), chroma + luma k 2. Trail (moving person) chroma +1.7..3 dB, static untouched.
- `--prior 1`: LK-R's DCT x-step, one pass on the final image (strength sweep: cleaner flats, no
  gain vs truth: kept light / opt-in).
Tried, kept off: HDR+-style frequency-domain robustness per sample (`LKRL_FREQ`: trail +1..2 dB but
ghosts worse -- occlusion is real difference at every frequency); temporal weight smoothing
(`LKRL_TSMOOTH`: +0.2 dB); stronger single-pass prior; fewer fit steps alone.
- Edge zipper at 2x (motion look off exposes it; IMG_20260919_184641_979 car / poles): it is the fit's
  CHROMA (zipper-line power chroma 0.0152 vs luma 0.0021). Hybrid default now takes chroma from the
  all-frame average in every scene (luma: the fit, noise-gated shrink): chroma zipper 0.0152 -> 0.0055,
  luma detail unchanged, no reference fallback. Clipped samples weigh 1e-3 in the average (weight 1 put
  RGB blobs next to glints). Motion look is off in Lite (LKRL_MLOOK=1 to compare). Start image (fit from
  the average) tested: no effect on the zipper.
