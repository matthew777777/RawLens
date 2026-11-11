# lkr-pipeline

This module is licensed under [Apache 2.0](LICENSE); see [NOTICE](NOTICE).
The separately packaged desktop repository is generated with
`tools/lkr-standalone` in the RawLens checkout, preserving shared source
paths and bytes. Its detailed guides describe current CLI defaults; measurements
below are historical runs with the explicitly stated settings.

LK-Reloaded burst super-resolution (Lecouat, Ponce, Mairal, *Lucas-Kanade
Reloaded: End-to-End Super-Resolution from Raw Image Bursts*, ICCV 2021,
arXiv:2104.06191): the third burst method next to MFSR (`tools/sr-pipeline`)
and HDR+. Vulkan compute everywhere: MoltenVK on desktop, Android Vulkan in
the app, same SPIR-V.

The Python reference and every tuning/quality result live in `python/lkr`
(see its sources); this module is the production port.

## What is the paper, what is ours

| Paper | Here |
| --- | --- |
| Forward model y = D B W_p x: 6-param affine, box blur over the LR pixel, Bayer decimation (Eq. 1-2) | exact (`shaders/lkr_common.glsl`, `resid_adj.comp`) |
| HQS with increasing mu (Eq. 4); z gradient step (Eq. 5) | exact, 3 steps/iteration (tuned) |
| One Gauss-Newton / Lucas-Kanade step per frame vs the HR estimate (Eq. 7) | exact (`grad.comp`, `jac.comp`, `solve.comp`, all on GPU) |
| Learned ResUNet prior | Optional Vulkan sliding-DCT prior (`shaders/dct_*.comp`) |
| Learned mu_t, eta_t | sigma_t geometric 10 sigma -> 1 sigma; eta = 1.8 / (L + mu) with L = max(U^T 1) (Schur bound, stable for c < 2) |
| Coarse init: forward-additive LK on bilinear grey (Sec. 3.4) | exact + 3-level pyramid (`lkr_host.cpp`) |
| T not stated | T = 16, prior on every 2nd iteration (8 calls; tuned, <= 0.27 dB below 16 calls) |

Noise parameters are always estimated from the burst itself
(`NoiseSigma`, `NoiseAB`), never taken from the Camera2/DNG NoiseProfile.

## Layout

- `src/main/cpp/lkr_gpu.*` recorded Vulkan compute (persistent pipelines and
  descriptor sets, one submit per iteration, persistently mapped buffers).
  Device/queue come from sr-pipeline's `srp_context` by **linking
  libsrpipeline**; DNG I/O is `srp_dng`. No sr-pipeline sources are copied.
- `src/main/cpp/shaders/*.comp` embedded at build time (`glslc -mfmt=num`).
  `U^T r` scatters with int32 atomics on fixed point: associative, so results
  are bit-identical across GPUs and dispatch orders, no float atomics needed.
- `src/main/cpp/lkr_solver.*` one-tile solve; `lkr_host.*` CPU stages;
  Vulkan sliding-DCT regularisation.
- `host/` desktop executables: `lkr_parity` (vs python), `lkr_run`
  (DNG burst -> DNG), and `lkr_full_run` (tiled production pipeline).

## Desktop build (macOS, MoltenVK)

```sh
cmake -S tools/lkr-pipeline/src/main/cpp -B tools/lkr-pipeline/build/host -DCMAKE_BUILD_TYPE=Release
cmake --build tools/lkr-pipeline/build/host -j 4
DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib tools/lkr-pipeline/build/host/lkr_run BURST_DIR OUT_DIR --cx 1681 --cy 1480 --crop 512
```

`glslc` comes from the Android NDK (`~/Library/Android/sdk/ndk/*/shader-tools`).

## Parity (2026-11-04, Apple M1)

`python -I python/lkr/parity.py dump OUT BURST --cx X --cy Y --crop 512`,
then `lkr_parity OUT`, then
`parity.py check OUT`. Burst crops 8 x 512 x 512, x2, T 16, 3 z-steps, prior every 2nd.

| Check | Day roof (ISO 50) | Night building (ISO 4653) |
| --- | --- | --- |
| Solver, no prior: image vs torch | 128.1 dB | 122.3 dB |
| Motion vs torch (max translation diff) | 1.3e-5 px | 2.6e-5 px |
| Coarse LK (C++ vs python) | 1.2e-7 px | 1.5e-8 px |
| Noise fit sigma / a / b (C++ vs python) | identical to 4 digits | identical to 4 digits |

## Shadow and motion diagnostics

`LKR_DEBUG_MAPS=/tmp/result.maps` writes five planar float32 maps: effective
frame weight, temporal acceptance, prior noise factor, shadow mask, maximum
local displacement. These are diagnostic source-grid summaries, not a warped
per-pixel ground truth.

For the actual residuals and the data/prior split at each denoiser call:

```sh
LKR_TRACE_PREFIX=/tmp/hair LKR_TRACE_TILE=4 lkr_full_run BURST OUT.dng
python tools/lkr-pipeline/inspect_trace.py /tmp/hair_tile_480_480_meta.txt --frame 9
```

The tile suffix uses its crop-relative origin; choose the emitted metadata file.
Tracing is off by default. Dumps include input frames, initial/final motion,
prior input/output, temporal residuals and row moments from the same GPU pass,
and final output before/after finishing. RGB dumps are planar camera-linear;
weights and residuals are `K × h × w` in source-frame coordinates. `temporal_rows`
is `K × h × w × 4` (sum residual, sum variance, count, sum squared residual).
The renderer requires NumPy and Pillow and applies one exposure to all stages.

### Held-out frame check

`lkr_full_run BURST OUT.dng --holdout 19` leaves input frame 19 out of the merge,
aligns it to the finished image with the solver's own motion step and compares it
with that image through the forward model, in the raw domain. It prints the
residual energy divided by the expected noise variance, overall and per 16 px
block, and writes `OUT.dng.holdout.f32`: three planar float32 maps on the held-out
frame's grid (squared residual / variance, usable share, signed residual / sigma).

Reading it: about 1 to 1 + 1/(merged frames) where every frame was used and the
image is right; towards 2 where frames were rejected; above that where the image
and the frame really differ (motion or a changed reflection in the held-out frame,
ghosting, lost detail). Compare settings on the same burst and crop rather than
reading absolute values: the scale follows the noise fit, and a fit over a small
textured crop reads high (Friant 512 px crop: `a` twice the 1024 px value, block
median 0.57). Real motion in the held-out frame is red whatever the setting.

`lkr_robust_check` runs Vulkan regression checks for signed-residual cancellation,
static-noise acceptance, reference preservation, independent shadow chroma and
luminance preservation. Build it with the desktop CMake project, then run with
the same Vulkan library path as `lkr_full_run`.
