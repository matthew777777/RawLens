# Step-3 spike findings: Wronski kernel-merge (/tmp/wronski-spike/spike.py, throwaway)

Oracle: `burst8` (Redmi 25080RABDG, GBRG, ISO 50), ref frame #3, rawpy load,
normalize `(z-64)/(1023-64)`, quad split, quad-average gray proxy, FFT phase
correlation global align. Evidence JSONs: `golden/bursts/spike-*-metrics.json`.

## Numbers

Sky crop `0,0,512,512` (flat, gradmean 0.0013):

| variant | hf_ratio | p90_ratio | flat_ratio |
|---|---|---|---|
| naive-global (uniform avg) | 0.397 | 0.351 | 1.819 |
| kernel-global (steerable + robust) | 0.521 | 0.247 | 1.683 |
| naive-local (ungated block-match) | 0.349 | 0.190 | 1.998 |
| Kotlin oracle (same crop) | — | **0.40** | **1.12** |

Texture crop `1784,1274,512,512` (gradmean 0.0168, 12x sky):

| variant | hf_ratio | p90_ratio | flat_ratio |
|---|---|---|---|
| naive-global | 0.567 | 0.751 | 1.340 |
| kernel-global | 0.538 | 0.709 | 1.347 |
| kernel-local (double-resampled) | 0.384 | 0.601 | 1.569 |

## Locked decisions for the Kotlin rewrite (step 4)

1. **Naive resample-then-average reproduces the disease** (sky p90 0.35 vs
   Kotlin 0.40). Bilinear-gather + uniform weights is confirmed mush source.
2. **Warp-free gathering is mandatory.** Even perfectly aligned,
   averaging subpixel-shifted bilinear samples caps detail (~0.75 p90 on
   texture). Port Wronski's true structure: for each output pixel, project
   into each frame's *native* grid via flow, take native 3x3 taps with NO
   pre-warp interpolation, weight by anisotropic Gaussian of the *true*
   subpixel offset + noise-calibrated robustness. Kotlin's
   `BurstObservation.sample` (warp first, gather later) must go.
3. **One resample max, ever.** `kernel-local` lost HF purely from two
   chained bilinear warps (same flaw as `SrOperator` double-MTF-sag in the
   audit). Compose global + local flow into a single sampling field before
   touching pixels. GLES port inherits this rule.
4. **Kernel shape helps mean-HF, not strong edges alone** (0.52 vs 0.40 on
   sky). Keep steerable kernels (`K_BASE=0.7` quad-px start, stretch 4,
   shrink 2, structure-tensor guide) but they are not a substitute for (2).
5. **Flow must be gated.** Ungated per-block SSD fits noise on flat crops
   (corrections pinned at lattice bounds, p90 0.35 -> 0.19). Keep the
   uncertainty/confidence concept (`CellFlow`'s `unc`), fix it: coarse-to-fine
   on denoised proxy, forward-backward consistency, calibrated CRLB, green NOT
   green-only (use quad-average gray + R/B check).
6. **Robustness knee works, needs SNR tuning.** Wiener-style
   `1/(1+(|d|/(D_TH*sigma))^2)` with `D_TH=2.5`, sigma from G1-G2 residual
   (`0.00146` here) gave 1.3-2.0x flat denoise of ideal 2.83x. Port as
   `BurstTuning` params with SNR-based auto scaling (IPOL `D_th/D_tr`).
7. **Global translation suffices for static scenes** (local corrections ~0
   on texture crop). Local flow budget goes to parallax/moving objects +
   deghost segmentation, which the spike did not attempt.

## Spike tunables to carry into BurstTuning

`K_BASE 0.7, K_STRETCH 4, K_SHRINK 2, D_TH 2.5, TENSOR_BLUR 1.0` (quad-px units).

---

# Step-4a record: warp-free KernelMerge (v45, all 345 tests green)

Shipped: `tools/burst-reconstruction-desktop/src/main/kotlin/com/matthew/burstrecon/KernelMerge.kt`
(`KernelGather`: `steerAt` + `sample`), wired into `GatherMerge.merge` and
`mergeTiled` (bit-exact between them — steering reads the same band values).
`BurstObservation.sample` untouched (legacy path + PsfSr + pinned tests).
`BurstTuning` gains `kernelBase/kernelStretch/kernelShrink/kernelBilateralC`;
`ALGORITHM_VERSION` 44 → 45. Tests: `KernelMergeTest` (8).

## Grid lock (truth-anchored jitter fixtures, 8-frame, ±0.15q true flows)

Dark rock (need energy ≥ 0.70×truth) / daylight foliage (need mse ≤ 1.03×single):

| kb | C | dark ratio | dark mse | day mse (single 7.32e-5) |
|---|---|---|---|---|
| 0 (legacy) | — | 0.722 | 1.88e-6 | 7.54e-5 (passes by 0.03%!) |
| 0.3 | 1.5 | 0.816 | 1.69e-6 | 7.47e-5 |
| **0.4** | **1.5** | **0.733** | **1.86e-6** | **7.51e-5** |
| 0.5 | 1.5 | 0.631 ✗ | 2.18e-6 | 7.58e-5 ✗ |
| 0.7 | 1.5 | 0.51 ✗ | 2.96e-6 | 7.47e-5 |

Locked: `kernelBase=0.4, kernelStretch=4, kernelShrink=2, kernelBilateralC=1.5`.
No global width serves both (dark ≤0.3, daylight ≥0.5) → support adapts per
pixel from outer-tap agreement (`AGREE_C=1.2`, dead-zone).

## Mechanisms found (each caught by a failing test, fixed, re-greened)

1. **Variance calibration**: 9-tap kernel reports 2-3× smaller variance than
   bilinear → all sigma gates over-reject (acceptance 7.95→7.71). Fixed with
   footprint spread term minus Bessel-corrected noise floor (flats: excess 0,
   frames stay symmetric with the exact-tap reference).
2. **Bilateral agreement** (spike D_TH, dead-zone form): radius-1 taps on
   near-Nyquist texture are anti-correlated and cancel signal (MSE +68%
   without it). Taps within Cσ keep full weight (flats pay no tax).
3. **Steering gates** (energy × coherence): high energy + random orientation
   (loud noise) must not steer; mis-steered wide kernels smear worse than
   isotropic. `drive` = energySig × smoothstep(0.5, 0.8, aniso).
4. **Width < bilinear under-averages on flats** (flatStack N=30 0.057 vs 0.05
   band at kb=0.3): base 0.4 reproduces bilinear's footprint on correlated
   taps for full √N fusion.

## Real-data A/B (burst8: 8× Redmi daylight, true legacy = kb=0 re-import)

- Sky crop: 96.6% pixels differ, MAD 2.7e-5 (0.05% of level) — denoise
  redistribution only; verdicts identical (P90 0.41, flat 1.12, seam 1.079).
- Texture-center crop: 20% differ, max 1.16e-2 (29% of level, localized);
  aggregates equal (P90 1.00, support 2.39 both).
- Determinism: same-tuning reruns are bit-identical (merged.bin cmp clean).

## NOT done (next steps)

- Center crop is 80% fallback / support 2.39 — gate-bound, not
  observation-bound. Acceptance recalibration for sharper kernel samples is
  step 4b (gates tuned for bilinear-blurred samples; FoliageMush-style
  contracts must keep holding).
- SR path (`PsfSr`/`SrOperator` bilinear-double-sag) untouched — step 4c.
- Sea30 real-data A/B + golden baselines refresh follow the gate work.
