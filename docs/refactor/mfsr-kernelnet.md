# MFSR: PhotonCamera KernelNet as the Wronski reconstruction kernel

Wronski et al. 2019 reconstruct each output pixel as a kernel-weighted sum
(Eq. 1-2) with anisotropic Gaussian kernels from each frame's structure
tensor (supplement S.1). RawLens can instead use PhotonCamera's KernelNet
(eszdman/PhotonCamera @ b2eff82, `kernelnet_aniso_v2_2_params`, GPL-3.0),
a 6-layer CNN predicting (s1, s2, rho) per pixel from sqrt quad luma and
the noise sigma. `MfsrSettings.kernel` / CLI `--kernel` select it
(default KERNELNET in the app; Wronski stays for IPOL parity and fallback).

## What is 1:1 with PhotonCamera
- Model files verbatim (tools/sr-pipeline/src/main/resources/kernelnet).
- Inputs: in0 = sqrt(clamp(2x2 quad mean, 0, 1)) (singleluma.glsl), in1 =
  sigma = sqrt(S*0.5 + O), S/O = mean R,G,B noise model (ESD4D, noiseMpy 1),
  zero-padded like any ncnn input channel. Sensor orientation (the merge's
  RGGB flips are undone for the net and mapped back, rho negated on a
  one-axis flip).
- Output stored through fp16 (their RGBA16F map).
- Kernel shape: anisoupscale.glsl's exp(-q) with s2 = x width, s1 = y width
  (measured on the model: across vertical edges s2 shrinks to 0.40 while s1
  stays 0.92), rho clamp +-0.8, det >= 1e-2, elongation <= 7, floor 0.25 px.
  In Wronski form: Omega = 0.5 [[s2^2, rho s1 s2], [rho s1 s2, s1^2]].

## Where it necessarily differs
- PhotonCamera never uses KernelNet as a merge kernel (ESD4D: "Kernels gate
  the merge statistics ONLY"); the reconstruction use is its single-image
  upscale. The Wronski integration is ours: one map from the reference
  frame, read by the merge on the reference grid (not each frame's aligned
  position).
- Their upscale's sigma floor is zoom-dependent and at 1:1 equals the 1.2 px
  cap (adaptivity off); dropped. Their 1.2 px cap starved flats of
  denoising in the merge (dim burst: +8 % noise vs Wronski); cap 2.0 px.
- Their denoise shader (dynamicgaussian_kernelnet.glsl) reads s1 as the x
  width: swapped vs the model and vs their own upscale (PhotonCamera bug).
- Units: their upscale scales map texels by raw/map (x4); their denoise
  implies the luma grid (x2). sigmaScale 0.25 (below) sits on the x4 base.

## Tuning (two real bursts, 512/1024 crops, same pipeline otherwise)
| kernel | bright SNR 29.5: detail / p99 edge / noise | dim SNR 21.8: detail / p99 / noise mid, dark |
|---|---|---|
| Wronski S.1 | 0.0438 / 0.1853 / 0.0270 | 0.0109 / 0.1434 / 0.00307, 0.00347 |
| KN x0.25 cap 2.0 (default) | 0.0445 / 0.1827 / 0.0253 | 0.0109 / 0.1431 / 0.00288, 0.00316 |
| KN x0.5 cap 1.2 | (512 crop) 0.0201 vs 0.0216, p99 0.0787 vs 0.0921 | 0.0107 / 0.1297 / 0.00331 |
| KN x0.9 cap 1.2 (PhotonCamera trim) | 0.0197 / 0.0741 | 0.0106 / 0.1239 / 0.00331 |

## Verification
- Engine map vs an exact float64 evaluation of the .bin: max 5e-4 (fp16
  output rounding), tools/sr-pipeline/parity/kernelnet_parity.py. The python
  ncnn wheel was not trustworthy as a reference (see the script).
- Engine vs python harness (fork CPU pipeline + same KernelNet kernels),
  bright burst crop: 101.5 dB.
- Phone (Mali, app build) vs desktop (MoltenVK), 1024 crop with noise LUT:
  KernelNet 118.2 dB (max 1.5e-5 = DNG 16-bit), Wronski bit-identical.
- Speed: the per-frame kernel chain is skipped (512 crop 2.8 s -> 2.1 s desktop).

## Open
- Dim 30-frame burst: engine vs fork differ at ~71 dB (max 0.19) for the
  Wronski kernel too: pre-existing, not KernelNet; to investigate.
- The app APK also carries a pre-existing copy of the model under
  assets/models (20 KB duplicate).
