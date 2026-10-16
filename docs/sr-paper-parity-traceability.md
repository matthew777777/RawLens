# SR Paper-to-Code Traceability (Wronski 2019 + Supplement)

Reference implementation in this repo is the Jamy-L IPOL 2023 port
(`merge.py` Algs. 4/6–9/11, `linalg.py`, `params.update_snr_config`);
Wronski section/equation refs below are the algorithmic authority, Jamy-L
refs are the code-level port contract. Status as of 2026-10-15; nothing
below claims parity except where a passing test is named.

## 1. Main paper (1905.03277v2) — pipeline in execution order

| # | Paper requirement (section/eq/fig) | Code location | Deviation / fix / test |
|---|---|---|---|
| P1 | Burst of Bayer CFA frames, base frame = alignment target (§3.1, Fig.2a) | `RawSrBurstPlanner`, `RawSrPackedFrame` | OK (same-exposure gate 1.10, docs/raw-sr-burst-planner.md). No change. |
| P2 | Local registration to base: coarse-to-fine pyramid block-match + 3× Lucas-Kanade warp refine; quadratic-fit declared insufficient (§3.2) | `RawSrAlignment` (+ `RawSrCfaOrientation` RGGB-space align) | Consistent with paper. Fixed blinds (tile-quilt guard in `remapFieldToSensor`, white-blinds pits handling) are NON-NEGOTIABLE, preserved untouched. |
| P3 | Merge Eq.1: per-channel `C = Σw·r·c / Σw·r`, 3×3 gather, online sequential, base R̂=1 (§3.3, §5.1, Fig.2g–h) | `RawSrBayerMerge`, `MosaicSrReconstructor`, `RawSrCoreKernel`, `merge_accumulate.glsl` | OK base path (nearest-tile flow, nearest-quad r, raw-unit Gaussian, `EPS=0.0` divide). Doc staleness only: merge.md §6 `eps=1e-8` contradicts code `EPS=0.0` — fix doc, not code. |
| P4 | Kernel Eq.2: `w = exp(−½dᵀΩ⁻¹d)` anisotropic Gaussian RBF at output grid (§5.1.1) | `RawSrCoreKernel.tapWeight/tapZ`, shader twin | OK. No additive floor. |
| P5 | Covariance Eq.3–4: half-res quad-luma structure tensor, eig, Ω from k1/k2; anisotropy from (λ1−λ2)/(λ1+λ2); bilinear upsample (§5.1.2) | `RawSrKernelCovariance` via `RawSrCoreKernels`, `RawSrCovarianceGuide` | OK (Jamy-L `linalg.py` verbatim incl. quirks). A-formula: code uses divide form; supp OCR multiply form is rejected (see §3). |
| P6 | Robustness Eq.5–9: `R = s·exp(−d²/σ²)−t`, noise-corrected σ/d (Eq.6 Wiener), motion-span s1/s2 (Eq.7–8), 5×5 min (Eq.9) (§5.2) | `RawSrRobustness` (Algs.6–9), `robustness.glsl` | PARTIAL: photo term + s1/s2 + 5×5 min present. Wiener σ_md/d_md Monte-Carlo tables + S.3 variance-loss HF reject have NO confirmed counterpart — open item, needs Jamy-L `robustness.py` diff before adding anything. |
| P7 | Post-merge: spatial denoise, CCM, tonemap, sharpen (§3.3, §6.3) | Downstream develop path (not SR) | Out of scope for kernel parity. |
| P8 | Sampling: aliased input required, N=15 (8 enough high-SNR), 1.5–2× real SR gain (§4, §6.4) | √2 shared lattice (merge.md §2, deliberate, evaluated) | Engineering decision, documented. No change. |

## 2. Supplement (028-wronski-supp) — heuristics and tuning

| # | Paper requirement | Code location | Deviation / fix / test |
|---|---|---|---|
| S1 | Kernel law S.1: `A`, `D`, `k̂1/k̂2`, `k1/k2`; kstretch=4, kshrink=2; D overrides anisotropy in flats | `RawSrKernelCovariance`, `RawSrTuning` | A-formula conflict: main-pipeline evidence gives `A=1+√((λ1−λ2)/(λ1+λ2))` while S.1 OCR reads `A=1+√(λ1λ2)` — code follows the tensor-ratio form; documented as paper-ambiguity, code kept. D law is divide form `D=clamp(1−√λ1/Dtr+Dth)` per `RawSrCoreKernels.denoiseWeight`; multiply OCR variant rejected. |
| S2 | Tuning S.2: Ts∈{16,32,64}, kdetail 0.25–0.33, kdenoise 3–5, Dth/Dtr ranges, t=0.12, s1=12, s2=2, Mth=0.8px, piecewise-linear in SNR 6–30 | `RawSrTuning.forSnr` | OK: single linear segment over clipped SNR dB [6,30] + tile step 64/32/16 at 14/22 dB; pinned by `RawSrTuningTest`. Scale note: paper Dth/Dtr units are [0,1]-image gradient magnitude; code endpoints (0.81→0.71 / 1.24→1.0) apply in the variance-stabilized guide domain. |
| S3 | HF-artifact removal S.3: variance loss after lowpass + alignment-field variation → full reject | `RawSrUnblocker` (Sabre-`unblocker.cc` analogue) | NAMING ONLY: "Sabre" here is a code comment, not a Sabre library. Functionality (variance-loss mask) is paper-required; keep. "Remove SABRE code" (Phase 6) must NOT delete this — see §5. |
| S4 | Robustness degradation S.5–S.6: graceful fall to single-frame demosaic PSNR | Frame rejection + robustness min | Behavior asserted by `RawSrFrameRejectionTest`, `RawSrRobustnessTest`. |

## 3. Code-only additions (no paper equation — judged on own tests)

| Addition | Location | Standing |
|---|---|---|
| `CHROMA_SIGMA_MPY=2.0` R/B 2×-wider taps | `RawSrBayerMerge`, `MosaicSrReconstructor`, `RawSrCoreKernel.scaleChromaZ` | KEEP (deliberate Nyquist deviation, green bitwise-identical, `1.0` restores reference path; pinned by parity tests). |
| Chroma-from-luma finishing pass | `RawSrCoreFinish.stabilize`, `chroma_from_luma.glsl` | FIX IN PROGRESS (§4): ratio form `G·smooth(R/G)` is unstable as G→0 (shadow magenta donuts). Restoring difference form `G+smooth(R−G)` per commit 5106699. G lane still untouched; smoothing of the difference still denoises chroma. |
| Dead-lane inpaint | `RawSrDeadLaneInpaint`, `RawSrCoreFinish.inpaint` | KEEP (zero-support handling; paper leaves behavior at 0/0 to pipeline). |
| Hot-pixel pre-mask | `RawSrHotPixel` (Sabre analogue) | KEEP (sensor reality; comment naming only). |
| Merged noise model | `RawSrMergedNoise` (Sabre analogue) | KEEP (paper §5.2.2 noise-model slot; comment naming only). |
| KernelNet anisotropic predictor (default ON) | `RawSrKernelNetAniso`, `KernelNetNcnnProcessor` | KEEP + VALIDATE (Phase 5): replaces analytic A/D→Ω on quad-res sqrt-luma input; zipper gates OFF by default = verbatim convert. Must be re-tested on foliage/HF/noise cases after §4. |
| Flow bilateral regularization (opt-in) | `RawSrTuning.flowRegularizeSigma` | KEEP OFF by default (documented improvement, not reference). |
| Containing-tile `flowAtNearestInto` warp | `RawSrAlignment`, merge/robustness/mosaic call sites, `merge_accumulate.glsl`, `robustness.glsl` | FIXED 2026-10-16: retired the bilinear blend — it invented warps at flow discontinuities (0.375 smear measured, now <1e-5). Code matches the Jamy-L oracle goldens full-field (1e-6). `flowAtSmoothInto` kept as dormant A/B helper only. |

## 4. Phase 3D fix — chroma-from-luma difference domain (this change)

- Root cause: ratio taps `R/G`, `B/G` divide by NEIGHBOR guides; any tap
  with G≈0 yields ±Inf/large ratios that the center-only `GUIDE_EPS` guard
  cannot stop (only the center pixel is guarded). In shadows this sprays
  magenta donuts; the old mitigation (keep-original on non-finite sums)
  fires after the poison spreads through the 7×7 window.
- Fix: difference domain `R′ = G + smooth(R−G)`, `B′ = G + smooth(B−G)`.
  Convex blend plus G: cannot amplify, needs no division, always finite
  for finite inputs. G untouched, so luma detail is bit-identical;
  chroma smoothing (hence denoising) is preserved.
- Mirrored op-for-op in: `RawSrCoreFinish.stabilize` (app + tools twin),
  `chroma_from_luma.glsl` (app ESSL + tools resource), regenerated
  staged/vk + SPIR-V via `tools/srvk_shader_transform.py`.
- Tests: `RawSrChromaFromLumaTest` updated (dead-tap neighbors now heal
  instead of passthrough) + new shadow-donut regression
  (`deepShadowMagentaStaysBounded`). CPU/GPU agreement stays ~1ulp (no
  division ⇒ FMA divergence shrinks).

## 5. Phase 6 pre-analysis — SABRE/GCAM removal

- `grep` over `app/src/main` finds NO Sabre/Gcam library or module: 13
  "Sabre" hits are all comments (`unblocker.cc` analogue, dense-gather
  style, `GetMergedNoiseModel`/`suppress_hot_pixels_bayer` analogues).
  The single "Gcam" hit is an unrelated UI comment (`LevelOverlayView`
  horizon). There is nothing to delete yet without deleting
  paper-required functionality (S.3 mask, §5.2.2 noise model, hot-pixel
  mask) or unrelated UI.
- Verdict: Phase 6 reduces to (a) rewording stale "Sabre-style" comments
  where the referenced behavior is now Jamy-L-verbatim, and (b) confirming
  no build/runtime dependency on removed code. No implementation deletion
  performed in this change; full removal after Phase 5 validation.

## 6. Backend parity matrix with tolerances (verified 2026-10-16)

| Pipeline | Reference | Tolerance | Evidence |
|---|---|---|---|
| CPU linear vs Jamy-L oracle goldens (real 128×96 burst) | `tools/jamy_parity_oracle.py` fixtures | rgb < 2e-5, den < 1e-4 | `JamyCoreParityTest.linearMergeMatchesOracle`: rgbFull=1.49e-6, denFull=4.16e-5 |
| CPU robustness vs oracle, uniform + step flow | `A_r_f*.f32`, `B_r.f32` | full-field < 1e-6 | `robustnessConstantFlowMatchesOracle` interior bitwise 0.0; `robustnessStepFlowMatchesOracleEverywhere` full=4.47e-7 (step band included) |
| CPU mosaic vs CPU linear (native scale) | twinship, same weights/routing | < 2e-6 (float vs double accumulators) | `mosaicNativeMatchesLinear`: 2.38e-7, 0/12288 fallbacks |
| CPU guide stats vs oracle | fixtures | bitwise | mean/var/movstats 0.0 |
| CPU kernels vs oracle | fixtures | < 2e-4 (f64-vs-f32 eig noise; oracle self-diff 8.6e-5) | cov 1.2e-5–5.5e-5 |
| App CPU vs desktop CPU twins | byte-identical sources | bitwise (RawSrCoreParityTest donor agreement) | `RawSrCoreParityTest`, `ShaderStagingTest` green |
| Vulkan GPU vs CPU oracle | 256px DCG crop, float32 vs float64 | meanAbs < 1e-5, maxAbs < 1e-3 | `VkMergeParityTest` — DATA-GATED (needs `captures/dcg/vulkan`, absent); last measured 2026-09-25: meanAbs=1.0e-8, maxAbs=8.9e-8 |
| Desktop/Android CPU mosaic | unified `MosaicSrReconstructor` (shared module, no fork) | bitwise by construction | single source file mirrored app↔tools |
| CFL CPU vs GPU | same formula, FMA divergence | ~1ulp (division-free now; strictly smaller than before) | `chroma_from_luma` twins + `RawSrChromaFromLumaTest` |
| KernelNet vs analytic | per-pixel/wholesale analytic fallback | model path default ON (app); verbatim convert with zipper gates OFF | `RawSrKernelNetAnisoTest` green; `RawSrMergeJob` freezes per-stream decision, logs analytic fallback |

Moiré/zipper validation: `chromaLatchGuardKillsEdgeTeethAndGuideBitIdentical`
(verbatim latches teeth worst>0.1, guard strictly improves, green bitwise),
`flowDiscontinuityDoesNotSmearAcrossTiles` (period-4 stripes, smear <1e-5),
`referenceOnlyPreservesConstantColourAllPatternsAndOrigins` (all CFA
patterns/origins), `mosaicNativeMatchesLinear` (CFA-gated twinship).
Chroma validation independent of luma: `deepShadowMagentaStaysBounded`
(bounded <0.02 in pits) + `rowAlternatingChromaIsDampedAndGuideBitIdentical`
(G bit-identical, step <0.02).

KernelNet standing: default path on app (`enabled=true`, model
`kernelnet_aniso_v2_2` in assets, quad-res sqrt-luma input domain,
`P_quad = 2·M(s)` bridge, analytic fallback on any failure). Desktop CLI
is opt-in (`--kernelnet`) for offline determinism — documented platform
difference; parity goldens pin the analytic path. Re-validation on
foliage/HF/low-light device captures is still open (needs
`captures/` data + ncnn runtime; `NcnnKernelNetHostTest` passes where
the native lib is present).

Phase 6 verdict: no SABRE/GCAM module exists anywhere (no such files;
no build-file references; full-repo grep finds only provenance comments
on paper-required code — unblocker=S.3, merged-noise=§5.2.2,
hot-pixel=sensor reality — plus unrelated UI/log-format/GCam-payload
mentions and the separate HDR+ bracket pipeline, all preserved). The
only stale Sabre references (dense-warp) were corrected with the
warp fix. Nothing to delete; fixed-blinds code (`RawSrCfaOrientation`,
alignment quilt guards) untouched — verified via `git diff --name-only`.

## 7. Real-burst validation (2026-10-16, IMG_20260927_134544_522, 8×DNG 4080×3060)

All three backends ran end-to-end with KernelNet covariances (ncnn
inference ~75–160 ms/frame, 0 caps/floors/rejects): mosaic CPU
(8 selected, 7 accepted, eff 3.68 frames, 5768×4326 SR mosaic DNG,
45.6 s), linear CPU (0 dead lanes, CFL steadied 12.48 Mpx, 42.8 s),
linear Vulkan (accepted 8, 18.1 s, ~2.4× faster than CPU).
CPU-vs-Vulkan on the real burst: meanAbs 4.2e-6, maxAbs 0.011
normalized, 0.08% pixels >0.001, none >0.05 — inside the float
tolerances modulo uint16 quantization. Shadow chroma bounded
(max R/G 2.36, p99.9 2.05, identical both backends): no magenta
donuts. Mosaic output finite, 1.4 ppm zero cells. Visual crops
(facade, car edge, dense foliage) show resolved leaves, no dots,
no zipper teeth. s1/s2 note: code prints s1=2.0/s2=12.0
(motion→strict), matching the Jamy-L oracle (`t_s1_s2_mt =
[0.12, 2.0, 12.0, 0.8]`, oracle parity bitwise) — confirm against
the paper PDF's s1=12/s2=2 assignment before calling it a
deviation; semantics (distrust motion areas) are defensible.

Foliage-grain verdict (same burst), revised after the fold fix:
the unconditional unblocker fold WAS an architectural defect — it
capped agreement wherever keep-weight ran low even under smooth flow,
against paper S.3 (reject needs variance loss AND alignment
variation). Identical frames kept 0.45 mean support; output noise
flatlined with frame count. Fix (tags 4N/5L): cap only where tile
flow is irregular; smooth flow keeps r. Support is now 1.0 on
identical AND real pairs, foliage chroma falls monotonically with N,
CPU/Vulkan agree to 4e-6. Orientation note: output DNGs carry EXIF
Orientation RIGHTTOP — analysis must rot90-CW to match RT coordinates.
Teeth verdict at the reported RT sites: two independent 4-frame merges
of disjoint halves share 75% of teeth (2% by chance, HP corr 0.92) —
the micro-texture is real resolved detail, not invention; the rest is
noise/flutter. Zipper gates default ON (259→53 invented teeth, −2%
acutance; `--no-zipper-gates` opts out). Minimum-support calibration:
analytic `--detail-floor` 0.3/0.4/0.5 → 50/28/12 invented teeth at
−8/−10/−13% acutance; KernelNet `--kernelnet-area-floor` 0.4 → 3–14
teeth at −13% — same dial, kept opt-in (default would trade verified
sharpness for smoothness and break oracle goldens). Structure tensor
is paper-verbatim (2x2/4-gradient, Eq.3 — no smoothing specified), so
no tensor change. Remaining 600% floor: median temporal noise 0.0007
vs 0.0033 total — fixed sensor pattern + outliers identical every
frame; remedy is flat-field calibration or post-merge spatial denoise
(S3.3), not kernel changes.

## 8. Open verification items (not parity claims)

1. Wiener σ_md/d_md + S.3 variance-loss threshold: diff Jamy-L
   `robustness.py` against `RawSrRobustness`; add only what the port
   contract confirms. 2. Warp snap: DONE (tile-snap restored, oracle
   goldens match full-field). 3. `merge.md` §6 eps + §3 lookup docstrings:
   update to `EPS=0.0` + actual call sites. 4. KernelNet re-validation
   suite (Phase 5). 5. Mosaic/desktop/Vulkan parity tolerances (Phase 4).
