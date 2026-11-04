# lkr-pipeline

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
| Learned ResUNet prior | FFDNet (KAIR weights) in the GAT domain of **our own** noise fit (`lkr_prior.cpp`) |
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
  `lkr_prior.*` FFDNet-GAT prior (ncnn; LiteRT NPU on Android behind the same
  `PriorFn`).
- `host/` desktop executables: `lkr_parity` (vs python), `lkr_run`
  (DNG burst -> DNG), `lkr_prior_check` (ncnn vs torch).

## Desktop build (macOS, MoltenVK)

```sh
# ncnn macOS-vulkan frameworks (not committed), release 20260526:
mkdir -p tools/lkr-pipeline/build/deps/ncnn && cd tools/lkr-pipeline/build/deps
curl -LO https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-macos-vulkan.zip
unzip -o ncnn-20260526-macos-vulkan.zip -d ncnn && cd -
# model: exact fp32 copy of KAIR ffdnet_color.pth (SHA-256 286f04f7...)
python3 -I python/lkr/export_ncnn.py ffdnet_color.pth tools/lkr-pipeline/build/models
cmake -S tools/lkr-pipeline/src/main/cpp -B tools/lkr-pipeline/build/host -DCMAKE_BUILD_TYPE=Release
cmake --build tools/lkr-pipeline/build/host -j 4
DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib tools/lkr-pipeline/build/host/lkr_run BURST_DIR OUT_DIR --cx 1681 --cy 1480 --crop 512
```

`glslc` comes from the Android NDK (`~/Library/Android/sdk/ndk/*/shader-tools`).

## Parity (2026-11-04, Apple M1)

`python -I python/lkr/parity.py dump OUT BURST --cx X --cy Y --crop 512 [--ffdnet ffdnet_color.pth]`,
then `lkr_parity OUT` (with `LKR_MODEL=build/models` for the prior), then
`parity.py check OUT`. Burst crops 8 x 512 x 512, x2, T 16, 3 z-steps, prior every 2nd.

| Check | Day roof (ISO 50) | Night building (ISO 4653) |
| --- | --- | --- |
| Solver, no prior: image vs torch | 128.1 dB | 122.3 dB |
| Solver + FFDNet-GAT prior (ncnn fp32): image vs torch | 129.3 dB | 131.2 dB |
| Motion vs torch (max translation diff) | 1.3e-5 px | 2.6e-5 px |
| Coarse LK (C++ vs python) | 1.2e-7 px | 1.5e-8 px |
| Noise fit sigma / a / b (C++ vs python) | identical to 4 digits | identical to 4 digits |
| ncnn FFDNet core vs torch | fp32 122.4 dB, fp16 53.0 dB | |

## Desktop timing (Apple M1, 512 crop, 8 frames, x2 -> 1024^2)

| Stage | fp32 prior | fp16 prior |
| --- | ---: | ---: |
| DNG crop load (8 frames) | 97 ms | 92 ms |
| Noise fit | 3 ms | 3 ms |
| Coarse LK (CPU, 1 thread) | 174 ms | 174 ms |
| Solver GPU (16 it x (3 z-steps + GN)) | 532 ms | 568 ms |
| Prior, 8 calls (ncnn Vulkan) | 5749 ms | 2472 ms |
| **Total** | **6794 ms** | **3510 ms** |

fp16 vs fp32 final image: 57.1 dB, max |d| 0.029 (highlights) -- quality
against ground truth still to be checked before fp16 becomes the default.
