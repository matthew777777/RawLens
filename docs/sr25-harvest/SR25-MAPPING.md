# SR25 harvest mapping — paper → IPOL → old RawLens → current → scaled path

Source pins (2026-09-18):
- Jamy-L `references/Handheld-Multi-Frame-Super-Resolution-Jamy-L` @ `07bc3f2` (2026-09-03)
- ImageStackAlignator `references/ImageStackAlignator` @ `b12e86e` (2020-05-02)
- JVision `references/Handheld-Multi-Frame-Super-Resolution-JVision` @ `b94b9ee` (2019-08-12)
- Old RawLens: `b60a271` (Wronski merge stack), `6821bcd` (mosaic √2 + linear DNG)
  harvested verbatim into `docs/sr25-harvest/old-*` (merge contract, wronski plan,
  alignment, robustness, `RawSrBayerMerge/KernelCovariance/Robustness/Tuning`,
  `MosaicSrReconstructor`, `LinearRgbDngWriter`, `MosaicSrDngWriter`).

## 1. Pipeline order (all sources agree; keep)

Jamy-L `super_resolution.py::main` (Alg.1): grey → align (Alg.2) → robustness
(Alg.6) → kernels (Alg.5) → merge (Alg.4, ref first with `dummy_r=ones`) → `num/den`.
Old RawLens: same order, but **reference-last** (addition commutes; fixes float
determinism vs CPU oracle). Current desktop `Pipeline.kt`: validate→admit→
reference-metrics→select→pyramid→global-align→flow→merge→psf→detail→repack→sr→plan.
SR25 scaled path keeps current order; reference-last stays.

## 2. Registration

| Item | Jamy-L / paper | Old RawLens | Current desktop | SR25 decision |
|---|---|---|---|---|
| Coarse | `block_matching.py` pyramid, L2 coarse + L1 fine | `RawSrAlignment` BM + LK refine | `GlobalAlign` (gyro-seeded `H=K·R·K⁻¹`, translation/affine) + `CellFlow` LK translation-first, conditional local affine | Keep current; add FFT low-pass gray trial |
| Fine | `ICA.py` 3 iterations, subpixel | LK refine | 3-iter LK equivalent, quad-px `unc` (CRLB) | Keep; gate SR on calibrated `unc`, never `1-conf` |
| Gray for align | `utils_image.compute_grey_images` decimating quads; IPOL text notes FFT low-pass alternative beats quad-average (~0.1 px) | Bayer-quad gray | Quad gray + per-level `(a,b)` noise | Trial FFT low-pass option; pick by synthetic <0.1 px test |
| ISA extra | — | — | — | Adopt **only**: global pre-align + rotation search as fallback when gyro absent; high-pass before tracking for long exposures; flat-area zero-shift fallback. Do NOT adopt full-res tensors (see §3) |
| Flow units | Tile field, px | Quad px, `src=p+0.5+2·flow` | Quad px, bilinear `sampleFlow`, stored spacing | Keep; scaled path: `src=(x,y)/s + flow`, per-plane 0.5-quad offsets in operator |

## 3. Kernels (anisotropic RBF)

Paper §5.1 / Jamy-L `kernels.py::estimate_kernels` + `cuda_estimate_kernel`:
GAT(α,β) → decimating grey → separable grad filters → 2×2 structure tensor over
2×2 window → eig → `A=1+sqrt((l1-l2)/(l1+l2))`, `D=clamp(1-sqrt(l1)/Dtr+Dth,0,1)` →
linear law `k1=(2-A)+(A-1)/kshrink`, `k2=(2-A)+(A-1)*kstretch` →
`k=kdetail·((1-D)·k+ D·kdenoise)` → `Ω=B·diag(k1²,k2²)·Bᵀ`, sampled per quad,
bilinear-interp at `g=src·(guide/raw)-0.5`, invert per pixel, `w=exp(-0.5·dᵀP d)`, 3×3.

- Old `RawSrKernelCovariance`: exact linear law, stores **precision** directly (skips
  per-pixel invert, same geometry), all 4 CFA phases via `BayerPattern.colorAt`
  (never Jamy-L `i%2+j%2` hardcode), flat/non-finite → isotropic denoise fallback.
- JVision `getKernelPara`: **DEVIATION — do not copy**: `A=1+sqrt(l1/l2)` (wrong vs
  paper) and stretch/shrink applied swapped (`k1_hat=kdetail·kstretch·A`,
  `k2_hat=kdetail/kshrink/A`). Paper-correct form is Jamy-L linear law above.
- ISA: **DEVIATION — do not copy blindly**: ref-frame-only kernels, full-res
  debayered+gray tensors with gaussian smoothing, 5×5 support. Rationale noted
  (more control) but breaks quad-anchored precision contract + memory model.
  Possibly revisit support radius only via measured MTF, never full-res tensors.
- Current desktop: per-frame precision fields + `SrOperator::kernelForScale`
  (`C·s²`, radius honors 3σ ≤8, normalized, no silent truncation). PSF estimator
  inverted calibration (`σ=EDGE_K/ratio`), stride-1, noise-gated, trust =
  edges>64 && raw≤MAX.
- SR25: keep current; scaled covariance `C·s²` with `s=√2`; source-anchored
  bilinear precision lookup; `z=d_quadᵀ·P·d_quad ≥ 0`, `w=exp(-0.5z)`, no floor
  pedestal (SkyKing `exp2+5e-5` explicitly rejected — fallback handles zeros).

## 4. Robustness

Jamy-L `robustness.py` Alg.6–9: guide RGB (quad average) → local stats 3×3 →
warp (`warp_stats`, Dodgson) → `d,σ` → Monte-Carlo LUT noise correction
(`σ=max(σp,σt)`, Wiener shrink on `d`) → flow-discontinuity penalty `S` →
`R=max(min(s·exp(-d²/σ²)-t,1),0)` → local min.
- Old `RawSrRobustness`: same shape, `Rc+=r` per accepted non-ref frame, ref excluded.
- ISA: replaces LUT with Foi `σ²=αx+β` closed form + √2 cancellation; omits `Mt`
  (outlier rejection in tracking covers it). Acceptable simplification; current
  desktop already uses analytic `(a,b)` + propagated variance — keep ours.
- Current: `mergeConf=C_res·C_fb·C_bounds` (NO Hess/texture in merge weight —
  D1 fix), tracking `conf` only for coverage/SR; joint quad weights (worst-plane
  motion/Tukey veto); tap-level clipping pre-scale with `w²·var`; two-pass
  consensus; `Rc<0.5` overwrite → burst-nearest fallback + `SATURATED_REF_GUARD`.
- SR25: unchanged rules at scale; `mergeConf`-at-reference for SR data term;
  native-fallback source gating; `agree` mass drives detail stage.

## 5. Merge core equations (frozen)

```
w = exp(-0.5 · d_quadᵀ · P_quad · d_quad),  3×3 taps, per-tap CFA routing
num_c += w · r · sample_c,  den_c += w · r,  merged_c = num_c / max(den_c, 1e-8)
```
- Jamy-L Alg.4 (`merge.py::accumulate`): output-owned gather, 1 thread/px,
  nearest robustness, per-pixel covariance invert, RGGB hardcode (fix in ours).
- Old `RawSrBayerMerge`: reference-last, `r_ref=1`, `MIN_SUPPORT=0.5`,
  burst-nearest fallback, saturation guard 0.99, Double accumulation.
- Current `Merge.kt` + `HfWiener.kt`: two-band (binomial-5 LOW through full joint
  machinery + green-joint HF Wiener with static `motionBoost`), `LOW_VAR_Q`,
  linearized Wiener variance, `PixelPrecision` foliage gates, sharp linear ratio,
  bilinear flow/weight resampling (seam fix), tiled==resident bit-exact.
- SR25 scaled gather: identical math with `src=(x,y)/√2 + flow`, precision
  `C·2`, per-plane offsets; mosaic path adds same-colour-only gate.

## 6. Output grids / writers

- Old `MosaicSrReconstructor`: `LINEAR_SCALE=√2`, floor-to-even
  (`2·floor(dim·s/2)`), phase = source phase at shared (crop) origin, even
  aspect-preserving; streaming + eager bitwise agreement; `weight/taps/Rc/oob/
  fallback` diagnostics, DNG carries CFA only.
- Old `LinearRgbDngWriter`/`MosaicSrDngWriter`: unsigned rationals (10 s safe),
  `ForwardMatrix1` carry (never invent), EXIF-IFD exposure/ISO, no invented
  `NoiseProfile`, provenance `algorithm=` + opcode lists.
- Current `BurstDngWriter` + mosaic `PixelShiftDngWriter`: same contract, native
  grid only.
- SR25 `ScaledGrid.kt` (new): one √2 even grid serving both outputs
  (≈5769×4327 from 4080×3060, ~25 MP); `ActiveArea/DefaultCrop/CFA` provenance;
  `algorithm=v14`; tiled writes; ExifTool `Validate:OK` required.

## 7. Anti-oil-painting (strict, locked)

No texture synthesis/grain/sharpening; no ref-HF injection; no `kDetail`
shrinking beyond PSF truth; `TuningFeedback` ghost-veto on widening; unqualified
tiles (phase/MTF/PSF/unc) → honest native-upsampled reference. Ship gates:
`detailKept↑` with `ghost/flatN` flat, `tiled==resident` exact, DNG valid.
