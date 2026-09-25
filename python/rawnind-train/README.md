# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# rawnind-train: Mac training scaffold for RawNIND-tiny Bayer denoising

Single-frame, pre-demosaic denoiser trained on Mac (PyTorch/MPS), deployed
on Android via NCNN. Mirrors the RawNIND paper's Bayer-direct idea
(Brummer et al., `arXiv:2501.08924`) shrunk to a mobile U-Net.

## Layout

| File | Depends | Purpose |
|---|---|---|
| `config.py` | stdlib | patch/noise/model/optim defaults |
| `dng_loader.py` | numpy | DNG parse, normalize, RGGB pack, patches, aug |
| `rawdsets.py` | numpy | streaming dataset + numpy collate (worker import-light) |
| `synthetic.py` | numpy | Poisson-Gaussian noise synthesis |
| `model.py` | torch | RawNIND-tiny U-Net (NCNN-safe ops only) |
| `losses.py` | torch | Bayer L1 + demosaic-consistency L1, numpy PSNR |
| `profile_train.py` | torch optional | CPU-vs-GPU profiler + speedup verdict |
| `model_mlx.py` | mlx | RawNIND-tiny mirror in MLX (NHWC, OHWI convs) |
| `train_mlx.py` | mlx + torch | MLX trainer (same data/seeds/loss as train.py) |
| `transfer_mlx_to_torch.py` | mlx + torch | weight bridge + framework parity gate |

## 4. Faster path: MLX (Apple Silicon)

PyTorch-MPS is launch-bound on small nets (~76 patches/s on an M1 Air).
The MLX trainer runs the identical recipe (same patches for the same
`--seed`, same loss math verified to 1e-7, same param count) and transfers
back for the unchanged export:

```bash
pip install -r requirements-mlx.txt
python3 train_mlx.py --data data --out checkpoints --epochs 200 --batch 32 --workers 0
python3 transfer_mlx_to_torch.py --mlx checkpoints/mlx_best.npz --torch checkpoints/best.pt
python3 export.py --checkpoint checkpoints/best.pt --out model.onnx
```

8GB-Air rules: batch 32, workers 0 (spawn workers each copy all frames
and re-import the frameworks -- that alone wedged one 8GB machine), close
other apps first. `train_mlx.py` caps Metal cache/memory/wired itself, but
if epoch 0's first batch stalls >10 min, kill it and retry batch 16: a
stuck GPU wait (not slow progress) means memory pressure won.

`transfer_mlx_to_torch.py` refuses shape/channel mismatches loudly and
re-runs a same-input parity check (must agree to 1e-5) on every transfer;
`export.py` then re-verifies the ONNX. The only real difference between
frameworks is layout (MLX NHWC/OHWI vs torch NCHW/OIHW), handled inside
the transfer -- never hand-transpose.
| `train.py` | torch | training loop (MPS/CUDA/CPU) |
| `export.py` | torch | ONNX export + verify + pnnx checklist |

`dng_loader.py` / `synthetic.py` / `config.py` are numpy-only and
self-test without torch: `python3 dng_loader.py` etc.

## 0. Install (Mac, Apple Silicon)

```bash
pip install -r requirements.txt
python3 dng_loader.py && python3 synthetic.py   # numpy self-tests, no torch
python3 -c "import torch; print(torch.backends.mps.is_available())"  # True
```

## 1. Prepare data

```
data/
  bursts/<id>/frame_00.dng ... frame_05.dng + merged.dng   # real pairs
  singles/*.dng                                            # clean, synthetic
```

* `frame_*.dng` are noisy HDR+ payload frames; `merged.dng` is the merged
  clean target (same geometry). Mismatched geometries are center-cropped to
  overlap in `train.py`.
* No DNG stack handy? Pre-extract `.npy` dicts
  `{codes, black4, white, pattern[, shot, read]}` — same layout, works
  headless (see `dng_loader` self-test).
* Recommended start: ~50/50 real/synthetic (`synthetic_frac`, shot/read
  drawn log-uniform 0.5x–3x nominal for multi-ISO coverage).

## 2. Profile (10 minutes, decides every speedup)

```bash
python3 profile_train.py --data data --batch 32 --workers 4
```

This times the per-epoch NumPy rebuild vs MPS forward/backward and prints a
verdict: **CPU-bound** (fix data: `--workers`, patch caching — MLX won't
help) or **GPU-bound** (bigger batches, autocast fp16, MLX port worth it).
Epoch lines in `train.py` also always print `build=`/`loop=` seconds so you
can correlate. See `profile_train.py --help` for subset runs. (Named
`profile_train.py` — not `profile.py` — so it can never shadow the stdlib
`profile` module that torch's optimizer imports.)

## 3. Smoke test, then train

```bash
python3 train.py --data data --out checkpoints --epochs 5 --batch 8
python3 train.py --data data --out checkpoints --epochs 200 --batch 32 --workers 4
```

Fastest known-good flags for an 8GB M1 Air: `--batch 32 --workers 2`
(batch 64 also fits but leaves little headroom next to macOS + MPS, and
macOS spawn workers each carry a frame copy -- 0 disables them entirely;
`--amp` fp16 is on by default for mps). Frames stream as uint16 codes
(~24MB per 12MP frame), so 30+ frames stay far clear of swap.
Checkpoints: `checkpoints/best.pt` (best val L1) + `last.pt`. Val reports
Bayer L1 + PSNR every 5 epochs (fixed frame, no aug).

## 3. Export to ONNX, convert to NCNN

```bash
python3 export.py --checkpoint checkpoints/best.pt --out model.onnx
# needs the external pnnx binary: https://github.com/pnnx/pnnx
pnnx model.onnx inputshape=[1,5,528,528]
cp model_ncnn.param app/src/main/assets/models/rawnind_tiny.ncnn.param
cp model_ncnn.bin   app/src/main/assets/models/rawnind_tiny.ncnn.bin
```

`export.py` prints the exact RF radius, input spec, and Android checklist.
`onnxruntime` numeric verify runs automatically if installed.

## 4. Android integration (Phase 2, not scaffolded here)

1. `app/src/main/cpp/ncnnMl.cpp`: add `RawNindCtx` copying the
   `KernelNetCtx` tiled path (core 1024 Bayer, border ≥ RF+2 packed px).
2. Pack `[R,G1,G2,B]`+sigma **after** lens-shading/defect, **before**
   `amaze.process()`; non-RGGB sensors `packed_to_rggb()` first.
3. Extend `DenoiseSettings`, thread through `RawDevelopmentCoordinator`,
   add Settings toggle, keep wavelet fallback, update `estimateMemory()`.

## Conventions (do not break)

* Normalized domain `[0,1]` via per-parity black levels — same math as
  `RawSensorUnpacker.unpackNormalized`.
* Packed order is `[R,G1,G2,B]`, **not** burst-ref's `[R,G1,B,G2]`.
* Flat patches are kept (chroma noise lives there); saturated patches are
  dropped (`max_saturated_frac`).
* Aug is full-frame flip/rot180 + RGGB-parity re-sampling (no border wrap).
* Determinism: every stochastic call takes an explicit seed/generator.
