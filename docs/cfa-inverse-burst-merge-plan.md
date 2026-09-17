# CFA-aware inverse burst reconstruction for RawLens

## Implementation specification

**Status:** desktop implementation exists; correctness repairs and qualification are incomplete (2026-09-15). This is the target specification, not a claim of implemented behavior.  
**Primary output:** one native-resolution, merged Bayer DNG from a 2-30 frame RAW ZSL burst  
**Optional output:** experimental 1.5x/2x derived Bayer DNG when phase-diversity gates pass  
**Compute target:** OpenGL ES 3.1 compute on Mali-G78/G710/G715/G720-class GPUs  
**Non-goals:** a neural inference runtime, perceptual texture synthesis, or demosaicing before fusion

## Execution architecture: two targets, one algorithm

Start with the repair prompts in `docs/cfa-inverse-burst-agent-prompts.md`. The version-3 audit in `docs/burst-reconstruction-desktop-audit.md` remains historical evidence. Subsequent source edits are work in progress: do not assume a finding is closed until its regression test passes. The last completed test run during repairs reported 40 tests and two failures (`BundleIoTest.versionConflictRejected`, `ReconstructionTest.integerTranslationRecovered`); later alignment edits were not covered by that run. Rebuild before continuing. Do not recreate the existing desktop module or port its unverified behavior to GLES.

The project must ship with an algorithmically identical desktop CPU implementation in a separate Gradle application, parallel to `tools/mosaic-desktop`:

```text
tools/burst-reconstruction-desktop/
```

This is the canonical, inspectable implementation of the complete pipeline, not a reduced oracle. It performs reference selection, pyramid construction, global and local alignment, uncertainty, both robust merge passes, optional PSF estimation/backprojection, repacking, diagnostics, and DNG output on the desktop CPU. It may take minutes or hours on large bursts; that is acceptable on a workstation and unacceptable on the phone.

The Android implementation runs the same stages and formulas in GLES compute. Shared conformance data locks down coordinates, constants, iteration counts, border modes, reductions, confidence terms, early-stop rules, and output metadata. Bit identity is required for integer/decision outputs. Floating-point images use documented absolute/relative/ULP tolerances because CPU FP64/FP32 and Mali FP16/FP32 evaluation order cannot be bit-identical.

There are therefore three test lanes:

| Lane | Where it runs | Purpose |
|---|---|---|
| Desktop CPU unit/integration | Development machine | Full algorithm correctness, exhaustive synthetic cases, real supplied bursts, long reconstruction and parameter sweeps |
| Android local JVM | Development machine | Android-independent contracts, serialization, metadata math, job ownership seams; never full-resolution reconstruction |
| Headless GLES instrumentation | Connected phone | Shader-vs-CPU parity fixtures, real-burst GPU output, DNG artifacts, Mali timings/memory/driver behavior |

Do not implement or invoke a full-resolution CPU fallback on the MTK phone. If GLES capability or execution fails, return the reference/ordinary capture path with a truthful reason. Phone CPU work is limited to orchestration, metadata, small reductions when proven cheap, and output serialization.

## 1. Decision

Replace the current one-pass `HdrRawMerge` model for same-exposure ZSL denoising with a deterministic, CFA-aware inverse-reconstruction engine. Keep the existing HDR merge available for exposure-bracketed HDR until the new engine explicitly models brackets.

The new path is:

```text
RAW_SENSOR images + immutable metadata + gyro history
  -> validate ownership/geometry
  -> normalize and split Bayer quads (R, G1, G2, B)
  -> score/select a reference
  -> green-proxy pyramid and gyro-seeded global alignment
  -> pyramidal cell flow with uncertainty
  -> first robust gather accumulation
  -> residual scale estimation
  -> second robust gather accumulation
  -> optional PSF-aware iterative backprojection
  -> repack Bayer
  -> derived DNG + optional JPEG developed from that exact merge
```

Native resolution is the first product milestone. It provides real denoising, deghosting, and recovery of aliased detail without inventing a larger sensor geometry. Super-resolution is enabled only after reconstruction coverage and phase diversity can be measured reliably.

## 2. What the supplied papers contribute

The papers are design evidence, not code to port verbatim.

| Paper | Transferable idea | Deterministic mobile interpretation | Explicitly not copied |
|---|---|---|---|
| Lian and Peng, *Kernel-Aware Burst Blind Super-Resolution* (`2112.07315v2`) | Image formation includes frame-dependent blur kernels and motion; use a pyramid for alignment | Estimate a bounded anisotropic Gaussian PSF per frame/region; include it in forward projection and confidence | Learned kernel estimator and deformable feature alignment |
| Kim et al., *Burst Image Super-Resolution with Base Frame Selection* (`2406.17869v1`) | The first frame is not always the best base, especially with non-uniform degradation | Score clipping, noise, sharpness, gyro blur, AF/AE state, and pose centrality; select from the captured burst | Frame Selection Network |
| *Feature Alignment with Equivariant Convolutions for Burst Image Super-Resolution* (`2503.08300v1`) | Alignment should have an explicit, geometrically meaningful transform and image-domain supervision | Keep global rotation/translation/affine transforms explicit; refine local residual flow and validate it against image residuals | Equivariant CNN, Restormer/INR reconstruction |
| *Keyframe-Centric State-Space Modeling for Burst Image Super-Resolution* / BurstMamba (`2503.19634v2`) | Spend most reconstruction effort on a strong keyframe and use other frames for complementary sub-pixel evidence; gather/aggregate/scatter preserves native-view evidence | Reference-first initialization, per-frame gather, residual-only corrections, and phase-coverage gating | Mamba/state-space model, learned wavelet conditioning |

### QMambaBSR: newly supplied evidence and limits

The supplied *QMambaBSR: Burst Image Super-Resolution with Query State Space Model* (Xin Di et al., CVPR 2025), sections 3.1–3.4, adds three relevant ideas:

- QSSM combines information across the burst, including preliminary fusion of the noisy base with current-frame features, before base-conditioned intra/inter-frame querying. Our deterministic interpretation is a burst-derived preliminary estimate followed by noise-aware consistency checks—not treating a noisy reference as ground truth.
- MSFM combines local CNN, channel Transformer, and directional SSM branches. Our pyramid and local support checks are engineering analogues of multi-scale reasoning, not implementations or equivalents of MSFM.
- AdaUp adapts learned transposed-convolution kernels using pooled input/output feature distributions. Our measured sampling geometry, local support/conditioning, and PSF-aware operator serve a related goal; they are not the paper's AdaUp and inherit none of its benchmark guarantees.

The paper uses the first frame as base and reconstructs HR **RGB**. It does not establish automatic reference selection, calibrated physical PSFs, RAW-negative fidelity, CPU/GLES parity, or our uncertainty/phase thresholds. Keep reference selection justified by the base-frame-selection paper. Do not add a Mamba runtime, learned kernel network, or generative texture prior. `2503.08300v1` remains the equivariant-alignment paper, not QMambaBSR.

Whole-burst agreement alone is insufficient: fixed-pattern noise can repeat, motion can be coherent, and valid aliased detail can vary with phase. Validate consistency through geometry, expected noise and, in SR, forward-projected observations. Add tests with a noisy base, independent noise, repeated sensor defects, periodic detail, and independent motion. Two robust passes are our chosen approximation, not a reproduction of QSSM.

## 3. Existing RawLens integration map

| Concern | Existing code | Plan |
|---|---|---|
| ZSL ownership and initial candidate ranking | `RawZslBuffer.kt` | Preserve exact-once `Image.close()` behavior. Split candidate-set selection from reconstruction-reference selection. |
| Motion sensing | `CameraMotionTracker.kt` | Extend storage from scalar angular speed to timestamped xyz gyro samples; keep scalar API for existing ZSL scoring. |
| RAW metadata | `RawFrameMetadata.kt` | Reuse per-frame black/white, CFA, crop, exposure, ISO, noise profile, lens shading, timestamp, and rolling-shutter skew snapshots. |
| RAW unpack/pre-correction | `RawSensorUnpacker.kt`, `RawPreDemosaicPipeline.kt` | Share normalization semantics. Use direct GPU unpack for reconstruction; retain desktop CPU tests, not a phone reconstruction fallback. |
| Current merge | `HdrRawMerge.kt`, `HdrFlowNetAligner.kt` | Do not silently change HDR behavior. Introduce a separate `RawBurstReconstructionProcessor`; later choose it explicitly for same-exposure ZSL. |
| GLES lifecycle | `Gles31AmazeProcessor.kt`, `RawDevelopmentCoordinator.kt` | Reuse the dedicated worker/context conventions, program ownership checks, capability probes, memory accounting, and shader loading patterns. |
| DNG | `FloatCfaDngWriter.kt`, `NativeDngWriter.kt`, TinyDNG JNI | Native-size float CFA can use the existing float writer initially. Production 16-bit derived CFA and scaled dimensions should use the custom TinyDNG path with explicit overrides. |
| JPEG | `RawDevelopmentCoordinator.kt`, `Gles31JpegOutputProcessor.kt` | Demosaic/develop the merged CFA once. JPEG and DNG must originate from the same merged buffer. |

The deleted `RawSr*` files and `docs/wronski-raw-zsl-super-resolution-plan.md` in the current worktree are not restored by this plan.

## 4. Contracts and data model

Introduce names that do not collide with the deleted experimental path:

```kotlin
internal data class BurstRawFrame(
    val image: Image,
    val metadata: RawFrameMetadata,
    val timestampNanos: Long,
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val rollingShutterSkewNanos: Long,
    val gyroSamples: List<GyroSample>
)

internal data class BurstReconstructionSettings(
    val enabled: Boolean = false,
    val requestedFrames: Int = 8,
    val outputScale: Float = 1f,
    val robustPasses: Int = 2,
    val backProjectionIterations: Int = 0,
    val saveDebugBundle: Boolean = false
)

internal data class BurstReferenceScore(
    val index: Int,
    val total: Float,
    val clipping: Float,
    val sharpness: Float,
    val noise: Float,
    val gyroBlur: Float,
    val poseCentrality: Float
)
```

Required invariants:

1. A merge job owns 2-30 `Image` instances and closes each exactly once on success, fallback, cancellation, or exception.
2. Every accepted frame has compatible camera identity, processing crop, dimensions, stored CFA phase and calibration. Decode each frame's own strides and black/white levels; these need not be identical. Reject incompatible origins/calibration, not legitimate normalization differences.
3. Metadata is captured with each image. Never use a later preview `CaptureResult` during reconstruction.
4. Flow is defined from output/reference coordinates to source coordinates in **Bayer-quad units**. Conversion to full RAW coordinates is centralized and tested.
5. All radiometric comparisons occur after black subtraction, white normalization, lens-shading policy, and exposure normalization.
6. The native-resolution result remains a one-sample-per-pixel CFA mosaic with the reference crop and phase.
7. A failed quality gate returns the selected reference frame truthfully; it never writes a nominally merged DNG containing invalid or zero-weight regions.
8. Desktop CPU and phone GPU consume the same versioned burst manifest and tuning snapshot and emit the same versioned metrics schema.
9. No production code path performs the complete reconstruction on the phone CPU.

### 4.1 Portable burst/debug bundle

Define a directory format usable by both executors:

```text
burst-name/
  manifest.json
  frames/000.dng ... NNN.dng
  gyro/000.csv ... NNN.csv       # optional timestamp,x,y,z samples
  expected/                      # optional CPU oracle products
  tuning.json                    # optional explicit override, copied to results
```

`manifest.json` contains schema/algorithm versions, frame order, reference override or auto-selection, timestamps, exposure, ISO, rolling-shutter skew, camera/intrinsic data, noise profiles, crops, CFA/sensor origins, expected file hashes, and requested native/SR mode. DNG tags remain authoritative for image geometry and radiometry; sidecar values exist for data not represented reliably in ordinary DNGs and must be checked for conflicts.

Each execution emits a result directory with merged DNG/raw planes, reference scores, transforms, flow/confidence maps, accepted/fallback maps, phase coverage, PSFs, per-stage residuals, metrics, tuning snapshot, timings, and input/output hashes. Large maps use a simple documented binary header plus little-endian payload so Kotlin/JVM and Android can share readers without third-party dependencies.

## 5. Coordinates and CFA representation

Let full RAW coordinates be `(x, y)`. A Bayer quad is `(qx, qy) = (floor(x/2), floor(y/2))`; the intra-quad plane is `(x mod 2, y mod 2)`, interpreted through the shifted `BayerPattern` already produced by `RawSensorUnpacker`.

Use one `rgba16f` texture at half width/height:

```text
R = red sample after pattern mapping (not necessarily the top-left sample)
G = the first green sample
B = the blue sample
A = the second green sample
```

Do not assume RGGB channel positions in a shader. Pass a four-entry CFA-plane mapping derived from `BayerPattern`. Alignment luminance is initially:

```text
L = 0.5 * (G1 + G2)
```

Canonical storage order is **R, G1, B, G2** throughout Kotlin arrays, RGBA textures and binary maps. A stored pattern is already shifted for the crop; do not apply sensor-origin parity again during split/repack. Preserve the origin separately as provenance. Test expected color identity for all four patterns and four origin parities, not merely a split/repack round trip. Store flow cell spacing explicitly; never reconstruct it as image width divided by cell count. For scaled CFA, specify each plane's offset in the full-RAW lattice before deriving latent coordinates.

Optionally add a small, clamped red/blue contribution only after tests prove it improves low-green scenes without chromatic misregistration.

For a reference full-RAW location, gather only the corresponding plane from a source quad. Bilinear interpolation is permitted **within one half-resolution color plane**. It is forbidden across unrelated CFA planes.

## 6. Radiometry and noise

For plane `c`, normalize each input code:

```text
z_i,c = (raw_i,c - black_i,c) / (white_i - black_i,c)
e_i   = exposureTime_i * sensitivity_i / (exposureTime_ref * sensitivity_ref)
y_i,c = z_i,c / e_i
```

Preserve below-black fluctuations during reconstruction to avoid censoring noise; clamp only where the declared output encoding requires it. Use aperture transmission only when it can vary within a burst. Reject unsupported exposure scales rather than silently clamping them. Resolve required exposure metadata from valid DNG EXIF or checked sidecars; never invent exposure or ISO. Explicitly declare the supported exposure range separately from bracketed HDR.

Camera2/DNG noise pairs describe variance for normalized signal, not raw DN. For `Var(z)=S*z+O` and `y=z/e`, use `a=S/e`, `b=O/e²`; do not divide by the sensor range again. Only a separately declared DN-domain calibration uses the DN-to-normalized variance transform. Centralize this in a CPU-tested `BurstNoiseModel` and upload four `(a, b)` pairs per frame such that:

```text
variance_i,c(y) = max(a_i,c * max(y, 0) + b_i,c, varianceFloor)
```

If the HAL noise profile is missing or invalid, use a conservative ISO-calibrated fallback and mark the provenance in metrics/DNG metadata.

If the fallback is only a heuristic and not measured calibration, label it as such. Normalize alignment images as well as merge observations. Alignment residual variance includes both frames, green averaging, interpolation and pyramid filtering; document covariance approximations. Lens shading multiplies variance by gain squared. Detect source clipping before exposure scaling, per nonzero interpolation tap. Propagate interpolated variance with squared tap weights and merged variance as `sum(w²*var)/sum(w)²`, conditional on fixed weights; robust data-dependent selection makes this an approximation that must be validated empirically.

## 7. Reference selection

Run selection on small GPU reductions or bounded CPU thumbnails. Normalize every component to `[0,1]`:

```text
score_i =
    1.8 * unclippedFraction_i
  + 1.5 * sharpnessPercentile_i
  + 1.2 * inverseNoise_i
  + 0.8 * poseCentrality_i
  + 0.4 * exposureUtility_i
  - 1.8 * gyroBlur_i
  - 1.0 * focusOrAePenalty_i
```

Definitions:

- `unclippedFraction`: fraction below a plane-aware highlight threshold and above the noise floor.
- `sharpnessPercentile`: robust high percentile of green-proxy gradient energy, normalized for noise.
- `inverseNoise`: predicted SNR in midtones, capped to avoid always choosing the longest exposure.
- `poseCentrality`: inverse median global displacement to the other candidates; approximate first from integrated gyro, then refine from coarse image alignment.
- `exposureUtility`: rewards useful highlight/shadow coverage without rewarding clipping.
- `gyroBlur`: angular travel during exposure plus rolling-shutter readout, mapped through focal length/pixel pitch when available.

Select the highest score only if it is separated meaningfully. Within a small tie band, prefer the temporal median to reduce displacement. Log all component scores in debug builds.

## 8. Alignment

### 8.1 Gyro/global initialization

Integrate xyz gyro samples over each frame interval. With calibrated intrinsics `K`, form an initial rotational homography:

```text
H_i = K * R_i * inverse(K)
```

When intrinsics or synchronized samples are unavailable, use identity initialization. Estimate a residual global translation or affine transform at the coarsest image level using robust green-proxy gradients. Homography is allowed only when it improves held-out residuals; handheld parallax still requires local flow.

### 8.2 Pyramid and cell flow

- Build 4 levels for ordinary phone RAW sizes; permit a fifth when the predicted displacement exceeds the level-4 search range.
- Use 16x16 half-resolution cells (32x32 full RAW support) with an overlapping 24x24 or 32x32 matching window.
- Coarse pass: bounded gradient/photometric block search around the gyro/global prediction.
- Refinement: 3-5 Gauss-Newton Lucas-Kanade iterations per level with Charbonnier or Huber influence.
- Solve a translation model first. Enable local affine parameters only when the Hessian condition and residual improvement justify the extra degrees of freedom.
- Regularize neighboring cells edge-aware; never smooth across a strong reference gradient or a large forward/backward inconsistency.

The local LK residual uses exposure-normalized green luminance and is divided by expected sensor noise. Brightness bias/gain may be estimated per cell within tight bounds for non-uniform bursts.

Use one photometric Jacobian per observation; affine normal equations must include cross-axis terms. Apply the entire global transform at cell positions, not only its translation. Stop a pyramid at its actual smallest level and derive coordinate scale from real reductions. Integrate gyro over clipped, covered time intervals with calibrated timestamp/axis conventions; apply rotation about the principal point. Missing synchronization/intrinsics means an explicitly unseeded image solve. Recompute confidence after any flow change. Local affine is a separately tested extension; translation-only behavior must not be labeled piecewise affine.

### 8.3 Alignment uncertainty

Store one `rgba16f` texel per flow cell:

```text
RG = displacement in Bayer-quad pixels
B  = log2 confidence denominator or residual scale
A  = confidence [0,1]
```

Confidence combines:

```text
C_align = C_hessian * C_residual * C_forwardBackward * C_texture * C_bounds
```

Confidence is dimensionless. Separately export displacement uncertainty in quad-pixel units from a conditioned, noise-scaled Hessian and consistency diagnostics, with its calibration assumptions. `1-confidence` is not pixel uncertainty and must not be used as an SR uncertainty gate.

Reject rather than extrapolate flow whose source footprint is outside the valid crop. A frame is globally rejected if accepted flow coverage is too low or its robust normalized residual is much worse than the reference/noise prediction.

## 9. Robust merge objective

The conceptual objective is:

```text
argmin_x sum(i,p) rho((A_i x - y_i,p) / sigma_i,p) + lambda * R(x)
```

For native output, approximate it with two deterministic gather passes.

### 9.1 Base weights

For source sample `s` predicting reference value `x0`:

```text
w_noise  = 1 / max(variance(s), varianceFloor)
w_align  = mergeConsistency (C_res * C_fb * C_bounds, no Hessian/texture)
w_motion = 1 - smoothstep(motionAccept, motionReject, normalizedResidual)
w_clip   = (1 - smoothstep(clipLow, clipHigh, sourceSignal)) * sampleValidity
w_sharp  = clamp(sourceSharpness / referenceSharpness, sharpMin, sharpMax)
w_phase  = 1 at native resolution; phase-coverage term in SR mode
w_base   = w_noise * w_align * w_motion * w_clip * w_sharp * w_phase
```

Tracking confidence (`C_hessian * C_res * C_fb * C_texture * C_bounds`) gates
coverage, quality diagnostics and SR/detail decisions — never the native merge
weight. Flat/static cells (low Hessian/texture, low residual, consistent FB)
must average for denoising; Hessian/texture belong to uncertainty/phase gating.
Corrected 2026-09-15 (v8) after street/sea patchy-noise evidence; see
`docs/burst-reconstruction-paper-conformance.md` D1.

Clamp each factor independently and cap total inverse-variance weight so an optimistic noise profile cannot dominate.

Require increasing smoothstep edges. The reference floor never overrides invalidity or saturation. Sharpness is measured in aligned support with exposure/noise accounted for, or disabled with provenance—not from unmatched image cells. Avoid permanently rejecting clean observations solely because they disagree with a noisy base: test first-pass consensus and second-pass reweighting explicitly.

### 9.2 Pass one

Gather source observations per output location/plane and accumulate FP32 numerator, denominator, weighted squared residual proxy, accepted count, and coverage. Include the reference with a nonzero floor weight. Resolve `x0 = numerator / denominator`.

### 9.3 Pass two

Re-gather and calculate standardized residual:

```text
r = (s - x0) / sqrt(variance(s) + variance(x0) + alignmentVariance)
```

Use Tukey biweight for hard outlier rejection, with Huber as an optional less aggressive mode:

```text
u = abs(r) / c
w_robust = (1 - u*u)^2 when u < 1, else 0
w_final = w_base * w_robust
```

Recompute numerator/denominator in FP32. Fall back to the reference sample wherever denominator, accepted count, or confidence is insufficient. Preserve values above nominal white only when the DNG encoding and white-level policy explicitly support headroom.

Accepted counts include only observations with actual positive final weights. Export per-plane support or a documented conservative aggregate, actual fallback decisions, and propagated variance. Reference-only output is a truthful fallback, not evidence of successful denoising.

## 10. GLES 3.1 pass graph

This is an 11-stage design; stages 5-8 repeat for each non-reference frame, and stages 10-11 are conditional.

| # | Shader/pass | Inputs -> outputs | Formats | Workgroup |
|---:|---|---|---|---:|
| 1 | `unpack_cfa` | `R16UI` RAW + metadata -> normalized Bayer quads, validity | `RGBA16F`, `R8UI` | 16x8 |
| 2 | `reference_metrics` | quads -> clipping/noise/sharpness reductions | `RGBA16F` scratch + SSBO counters | 16x8 |
| 3 | `green_pyramid` | selected reference/source quads -> Gaussian/gradient levels | `R16F` luminance, `RG16F` gradient | 16x8 |
| 4 | `global_align` | coarsest pyramids + gyro seed -> global transform/residual | small SSBO/texture | 8x8 |
| 5 | `flow_search` | pyramid level + predicted cells -> coarse cell displacement | `RGBA16F` cell texture | 8x8 |
| 6 | `flow_refine` | source/reference gradients + cells -> refined flow/Hessian stats | ping-pong `RGBA16F` | 8x8 |
| 7 | `flow_confidence` | refined flow + reverse/residual data -> confidence/motion mask | `RGBA16F`, `R8UI` | 8x8 |
| 8 | `merge_accumulate` | source quads + flow/confidence/noise -> pass-one or pass-two sums | `RGBA32F` numerator, `RGBA32F` denominator/statistics | 16x8 |
| 9 | `merge_resolve` | sums -> merged quad planes + quality map | `RGBA16F` or `RGBA32F`, `R16F` | 16x8 |
| 10 | `forward_project_backproject` | latent + per-frame flow/PSF -> residual/update | `RGBA16F/32F` ping-pong | 8x8 |
| 11 | `repack_bayer` | four planes -> full-size CFA readback/DNG buffer | `R16UI` or `R32F` | 16x8 |

Practical notes:

- GLES has no portable FP32 float atomics; this design is gather-only and gives each invocation exclusive output ownership.
- Query actual limits with `glGetIntegeri_v`; only assume the GLES 3.1 minimum 16 KB shared memory.
- FP16 is appropriate for images, pyramids, gradients, flow, and confidence after device validation. Numerators, denominators, reductions, and iterative updates remain FP32.
- Stream one moving frame through reusable textures. Retain only the reference pyramid, accumulators, resolved latent, and one frame's working set.
- Use explicit `glMemoryBarrier` bits between image/SSBO producer-consumer passes. Do not use `glFinish` between ordinary passes.
- Compile shader variants by CFA mapping, robust loss, and SR scale only when it materially removes branches.

## 11. Optional PSF-aware super-resolution

> F5 status (2026-09-15, v9): the desktop twin implements the matched operator
> below (`SrOperator`: per-plane CFA offsets, scaled PSF kernels, fixed-point
> flow inversion with affine-exact branch, tap-level clipping/validity,
> mergeConf-at-reference + native motion gating, exact-transpose bilinear with
> boundary normalization; dot-product + gradient tested), evaluated-iterate
> best-restore, calibrated inverted PSF estimation with noise gating, local
> per-tile phase and physical PSF-MTF gates (brightness ratio removed). 1.5x
> qualified vs truth + native-upsample baseline + negatives; street-center 1.5x
> shows measured detail gain over PhotonCamera-upsampled (+42% dP90, flat noise).
> 2x operator tested but not production-qualified on real bursts. Sea
> dark/dynamic falls back to native honestly. G2 ports these exact weights to
> output-owned gather.


SR is a separate quality mode, not a larger allocation of the native merge.

Estimate a simple PSF per frame or coarse region from edge spread and gyro exposure path. Represent it as a positive-definite 2x2 covariance plus a small support radius. Reject estimates outside calibrated bounds.

Enable scale `s > 1` only if all gates pass:

1. enough accepted frames and alignment coverage;
2. fractional offsets cover target phase bins rather than clustering near integers;
3. the flow uncertainty is smaller than the desired sub-pixel increment;
4. predicted optical/sensor MTF leaves useful energy above native Nyquist;
5. memory and watchdog estimates fit the device tier.

Initialize the latent CFA planes from the native robust merge. For 3-5 iterations:

1. forward warp and convolve the latent plane with the frame/region PSF;
2. sample it at the source sensor lattice;
3. calculate a confidence/robustness-weighted residual;
4. gather the adjoint residual into the latent grid;
5. normalize by adjoint weight and apply a bounded update;
6. apply weak edge-aware regularization only to unsupported modes.

Stop early when normalized residual improvement is small or begins to reverse. If any gate fails, write the native merged DNG and report `SR not supported by this burst`; do not upsample and sharpen.

### Mandatory SR correctness gates

- Define a single discrete operator `A` with explicit reference/source direction, CFA offsets, PSF units, interpolation, borders and normalization. Derive `Aᵀ` from the exact same weights; do not use integer-rounded backprojection of a bilinear forward sample. Spatially varying flow cannot be inverted by subtracting its value at an unrelated source cell.
- Verify `<Ax,r> ≈ <x,Aᵀr>` and finite-difference gradients on fractional motion, nonuniform flow, borders and anisotropic blur. GPU transpose evaluation must be output-owned gather; any CPU sparse reference must produce the same operator, not a different reconstruction.
- Weight observations using validity, source-domain clipping, noise, flow and motion confidence. Evaluate the objective for each candidate iterate before accepting it; recorded history and returned best image must correspond to the same evaluated states. Never filter a worsening history to make it look monotonic.
- Measure phase coverage per output region and CFA plane across distinct frames. A pooled histogram over unrelated cells is not local evidence. Specify scale-specific sampling/conditioning tests: 2x must not depend on passing a separate 1.5x histogram. Histogram coverage is only a diagnostic, not proof of recoverability.
- Validate physical PSFs on known synthetic edge-spread/blur fixtures. A Laplacian-to-gradient heuristic is not calibrated blur width. Honor support radius and covariance scaling; unknown/untrusted PSFs fail production SR eligibility. Brightness-squared or sharpening ratios are not optical MTF.
- Use a local support/conditioning map to restrict detail updates. A scaled output, if globally qualified, uses explicitly labeled native-derived interpolation in unsupported regions; if global gates fail, output native dimensions. Experimental force mode may bypass quality gates with provenance, never memory/safety checks.

## 12. DNG policy

### Native merged CFA

- Dimensions, active area, default crop, CFA repeat/pattern, orientation, and color calibration come from the reference geometry.
- Pixel values are derived. Record burst frame count, accepted count, reference index, algorithm version, exposure normalization, noise-model provenance, and merge confidence in XMP/private metadata.
- If output is normalized float CFA, `FloatCfaDngWriter` can be the first validated path.
- For 16-bit integer CFA, quantize once with explicit black `0`, white `65535` (or a deliberately reserved headroom white) and use custom TinyDNG metadata overrides.
- Export propagated variance maps. Do not write a global reference-profile divided by frame count when support/weights vary spatially. Omit an unrepresentable output NoiseProfile and document why; retain a reference profile only for a true unchanged reference fallback with matching radiometry.

Read exposure/ISO through standard EXIF IFD pointers and write them in the proper EXIF IFD. Encode rational exposures without signed-32-bit saturation. Handle declared RAW SubIFDs or reject unsupported layouts with a precise message. Never silently substitute identity color calibration or ignore unsupported predictors, linearization, or required correction opcodes. Check sidecar conflicts, offsets, multiplication overflow, finite samples and crop bounds. Publish final files atomically.

### Scaled derived CFA

Use the custom writer. Scale active area/default crop coherently, retain sensor-origin/CFA phase provenance, and clearly identify the image as a reconstructed Bayer mosaic. `DngCreator` is not the target because its geometry is tied to the physical Camera2 stream metadata.

Validate output in at least Android decoders, RawTherapee, darktable, and ExifTool. Check dimensions, CFA phase, black/white, color matrices, orientation, crop, noise profile, and absence of NaN/Inf or row-stride corruption.

## 13. Delivery phases

The numbered execution prompts supersede the old create-from-scratch sequence: **F1–F6 repair/verify the existing CPU module, then G1–G3 implement and qualify GLES/integration**. The phases below describe deliverables, not completed work. No GPU port is approved merely because the existing desktop application runs.

### Phase 0 - portable corpus and full desktop CPU twin

Create `tools/burst-reconstruction-desktop` as an offline Kotlin/JVM application following the dependency-light structure of `tools/mosaic-desktop`. Define the portable burst/result schemas and implement the complete CPU pipeline stage by stage. Begin with cropped fixtures, then run native-resolution real bursts. Parallelize by output tile with deterministic per-tile reduction; never change numerical results based on thread count. Provide stage-selective commands so long alignment/SR runs can be cached and resumed without silently mixing algorithm/tuning versions.

Exit: deterministic unit tests cover all Bayer patterns, crop parity, exposure/noise transforms, alignment, confidence, robust rejection, zero-weight fallback, PSF/backprojection, schema round trips, and thread-count determinism. The CLI produces a merged DNG plus the complete result bundle from supplied DNG bursts.

### Phase 1 - native same-exposure merge, translation flow

Implement GLES passes 1-9 and 11 with translation-only pyramidal flow, two robust passes, and the reference selector. Port each stage from the desktop CPU twin and add small exported parity fixtures before proceeding to the next. Keep feature-gated and preserve existing capture behavior when disabled.

Exit: static bursts reduce measured variance close to inverse-variance prediction; synthetic translations reconstruct without CFA color leakage; moving-object fixtures fall back locally to reference.

### Phase 2 - gyro and local affine refinement

Store xyz gyro history, seed global alignment, add conditional local affine refinement and rolling-shutter-aware initialization.

Exit: alignment coverage/residual improves over identity and translation-only baselines on handheld fixtures without increasing ghosting.

### Phase 3 - production DNG/JPEG integration

Wire job ownership, cancellation/fallback, native merged DNG, and JPEG-from-merge. Add memory telemetry, thermal/capability gates, and user-visible status.

Exit: instrumented capture writes exactly one valid merged DNG and requested JPEG, closes every source, and survives cancellation/low-memory/error injection.

### Phase 3.1 - headless phone burst runner

Add an Android instrumentation entry point that accepts an `adb push`ed portable burst directory and output directory, runs GLES without camera/UI interaction, and writes the complete result bundle. Support stage filters, crops, iteration overrides, repeat/warmup counts, and fail-fast parity thresholds. The host-side command pulls outputs for desktop comparison. It must never select the CPU reconstruction implementation on-device.

Exit: one command runs a supplied burst headlessly on the connected phone, pulls the merged DNG/maps/metrics, and compares them with a cached desktop CPU result. Failures preserve logs and structured stage status.

### Phase 4 - PSF and experimental SR

Add PSF estimation, phase-diversity metrics, pass 10, scaled CFA writer metadata, and 1.5x before 2x.

Exit: SR activates only on qualified bursts and beats native-merge-plus-Lanczos on slanted-edge MTF and aliased-detail fixtures without worse ghosting.

### Phase 5 - tuning and device qualification

Tune per GPU tier, validate FP16 error, add watchdog-safe tiling, and build a regression corpus covering daylight, low light, foliage, text, faces, water, moving subjects, saturated lights, and rolling shutter.

Exit: no GPU errors, ANRs, leaks, or corrupted DNGs across the supported device matrix; fallback remains truthful.

## 14. Verification matrix

| Layer | Required tests |
|---|---|
| Desktop CPU | Complete end-to-end pipeline on synthetic and supplied DNG bursts; CFA mapping; crop parity; noise transforms; reference ordering; alignment/confidence; robust merge; PSF/SR; deterministic multithreading; resumable stage artifacts |
| Android local JVM | Portable-schema round trips, metadata math, settings/versioning, ownership/fallback seams, memory arithmetic; no full-resolution reconstruction |
| Shader conformance | Odd strides/crops; borders; half/float tolerance; zero/NaN defense; gather reads never cross CFA planes; barrier correctness |
| Synthetic reconstruction | Integer and fractional translation, rotation, affine shear, blur kernels, exposure variation, clipping, Poisson-Gaussian noise, occlusion, independent motion |
| Headless instrumented GPU | CPU twin comparison on cropped and full supplied bursts; shader compile/probe; Mali precision; repeated job lifetime; cancellation/context recreation; artifact pull and stage diagnostics |
| Image quality | temporal noise, edge MTF, zipper/false-color rate, ghosting masks, normalized reprojection residual, accepted coverage |
| DNG interoperability | ExifTool tag audit plus open/develop in RawTherapee and darktable; orientation/crop/color/CFA checks |
| Performance | per-pass GPU time, upload/readback time, peak Java/native/GL memory, thermal behavior, burst-length scaling |

Never approve a change from PSNR alone. The physically meaningful RAW path prioritizes alignment truth, CFA integrity, deghosting, and predictable noise over perceptual sharpness.

## 15. Initial tuning defaults

These are starting values, not paper-derived constants:

```text
native frames:             8 default, 2 minimum, 15 normal maximum
pyramid levels:            4, adaptive fifth level
flow cell:                 16x16 quad pixels
support window:            24x24 quad pixels
LK iterations:             4 per level
Huber delta (alignment):   1.5 normalized sigma
Tukey c (merge):           4.685 normalized sigma
clip rejection:            >= 0.995 normalized sensor white
minimum accepted samples:  2 including reference
minimum flow coverage:     0.60 per frame
SR phase diagnostics:      scale-specific local bins plus operator conditioning
phase coverage threshold:  initial 70% diagnostic threshold, not proof of SR support
backprojection:            5 iterations at 1.5x; maximum 5 (v10 tuning: history still
  improves ~30%/iter at 3; desktop budget tolerates 5; early-stop still guards reversal)
```

Every threshold must live in one tuning structure, be logged with algorithm version, and be overrideable by tests/debug builds.

Reject unsupported versions, scales, iterations, NaN/Inf and out-of-range settings. Strict FP32 means arithmetic, accumulation and solver operations—not converting already-Float inputs to Double. If optional FP64 solvers remain, isolate them from strict mode. Lock thread-independent accumulation order and document CPU/GPU decision tolerances near thresholds.

Cache keys include every consumed input (including gyro/calibration sidecars), tuning, selected reference, geometry, effective options and upstream artifact hashes. Spilled packs (stages/admit/pack-NN.bin) are covered by stage integrity;
merge tiling/streaming are bit-identical paths, so cache keys need no tile parameter. Verify an explicit artifact inventory, checksums, dimensions and finite values before cache reuse. `run-stage` executes only the requested stage and required predecessors. `compare` reads pixels and maps, compares categorical outputs exactly, applies declared per-stage floating tolerances, and fails on missing/corrupt/unexpected artifacts; matching cache keys are not parity evidence. Timing/backend fields are compared under a separate policy, not required to be numerically identical.

## 16. Source corpus

The provided Markdown and PDF pairs were treated as the authoritative local corpus:

- `2112.07315v2`: *Kernel-Aware Burst Blind Super-Resolution* (duplicate copies are byte/content variants of the same paper).
- `2406.17869v1`: *Burst Image Super-Resolution with Base Frame Selection* / NEBI and FSN.
- `2503.08300v1`: *Feature Alignment with Equivariant Convolutions for Burst Image Super-Resolution*.
- `2503.19634v2`: *Keyframe-Centric State-Space Modeling for Burst Image Super-Resolution* / BurstMamba.
- *QMambaBSR: Burst Image Super-Resolution with Query State Space Model*, CVPR 2025: supplied Markdown at `/Users/monikamalinowska/Downloads/Di_QMambaBSR_Burst_Image_Super-Resolution_with_Query_State_Space_Model_CVPR_2025_paper.md`, with the same-basename PDF. Sections 3.1–3.4 support the distinctions above. The Markdown omits some displayed equations; do not reconstruct or attribute missing equations from guesswork.

The implementation deliberately translates their high-level findings into explicit image formation, confidence, and gather reconstruction suitable for mobile GLES rather than reproducing their neural architectures.
