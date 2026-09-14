# RawLens RAW ZSL Super-Resolution

## Technical implementation plan and agent work prompts

**Status:** proposed implementation plan  
**Scope:** merge a selected 2-30 frame Camera2 `RAW_SENSOR` ZSL burst into one high-quality DNG, selectable as a linear-RGB prime DNG or an experimental super-resolved CFA DNG, plus its JPEG derivative.  
**Primary method:** Wronski et al. handheld multi-frame super-resolution, completed with the IPOL implementation details by Lafenetre, Facciolo, and Eboli.  
**Non-goal for v1:** a learned neural reconstruction model, exposure-bracket HDR, or a user-visible 2x output size.

---

## 1. Product decision

RawLens already continuously buffers full-resolution `RAW_SENSOR` images in ZSL mode. A shutter press currently selects the requested frames and saves/develops each one independently.

Add a quick-menu switch named **RAW SR**:

| Setting | Input | Artifacts saved |
|---|---|---|
| `RAW SR OFF` | selected ZSL buffer frames | existing behavior: every selected source Bayer DNG, and each requested JPEG |
| `RAW SR ON - Linear` | the same 2-30 selected ZSL frames | one merged 16-bit linear RGB prime DNG and, if selected, one JPEG developed from that exact merge |
| `RAW SR ON - Mosaic` | the same 2-30 selected ZSL frames | one 16-bit super-resolved Bayer/CFA DNG for later RAW development, and one JPEG from the direct merged RGB result |

Turning RAW SR on must enable and warm RAW ZSL. Turning it off must not discard ZSL: ZSL remains available for the existing individual-frame workflow.

The default linear output grid in v1 is the native sensor image width and height. It is still a super-resolution merge: it jointly reconstructs RGB, suppresses Bayer artifacts, and denoises using the sub-pixel samples. The Mosaic SR output is an advanced experimental mode whose target grid can be 24 MP when the source is approximately 12 MP. An internal `outputScale` setting will allow an experimental 2x grid later. Do not expose 2x in the first release.

---

## 2. DNG output modes

A Bayer DNG has one measured sample per pixel and a CFA pattern that tells a RAW developer whether that sample is red, green, or blue. The merged Wronski result has reconstructed red, green, and blue at every output pixel from many shifted Bayer observations. It cannot truthfully remain an un-demosaiced Bayer image.

### 2.1 Default: linear RGB prime DNG

The default prime DNG is a standards-compliant **16-bit scene-linear RGB DNG**:

- `PhotometricInterpretation = LinearRaw`.
- `SamplesPerPixel = 3` for RGB.
- no CFA tags for the main merged image.
- scene-linear camera RGB values, 16-bit quantized using a documented output scale.
- orientation, capture-time, lens, exposure, color-transform, white-point, and merge provenance metadata retained.

This remains substantially editable in RAW developers: exposure, white balance, color transforms, curves, highlights, shadows, and grading remain available. What is intentionally no longer selectable is the demosaic algorithm, because the merge has already performed a better multi-frame demosaic.

### 2.2 Advanced: Mosaic SR DNG for later RawTherapee demosaic

It is also possible to produce a valid 24 MP Bayer DNG for a later RawTherapee demosaic. This is a **derived/synthetic CFA negative**, not untouched sensor data:

1. Define an even-width/even-height target grid at the requested megapixel count and preserve the source aspect ratio. A 12 MP to 24 MP result uses approximately `sqrt(2) = 1.414x` width and height scaling; a true 2x-by-2x result would be 48 MP.
2. Assign the target grid the selected Bayer pattern, for example RGGB.
3. At each target site, reconstruct only that site's designated R, G, or B sample from same-color aligned observations across the burst, using the same flow, kernel, and robustness model.
4. Write one 16-bit sample per target pixel as a CFA DNG. RawTherapee can then demosaic it using AMaZE, RCD, or another user-selected method.

Mosaic SR DNG requirements:

- `PhotometricInterpretation = CFA` and `SamplesPerPixel = 1`.
- original 2x2 CFA repeat/pattern metadata, including correct target-grid phase.
- a new target active area/crop matching the larger derived grid.
- black level `0` and white level `65535` after normalized 16-bit quantization, unless the writer uses another documented lossless encoding.
- camera color matrices and a private/XMP `DerivedFromRawBurst` record.
- explicit user-facing label and metadata: `RawLens Mosaic SR DNG - derived Bayer reconstruction`, plus target scale and selected/accepted frames.

The Mosaic SR path keeps later demosaic choice but necessarily retains only one color value per target pixel. The linear RGB path keeps all reconstructed color values at every target pixel and is therefore the default quality path.

Optional advanced setting for a later phase: **Keep source burst**. When enabled, write the normal source Bayer DNGs alongside either merged result so an editor can try an independent offline merge. Do not embed 30 originals in one DNG in v1.

Expected storage, before compression: a 24 MP 16-bit RGB output is about 144 MB; a 24 MP 16-bit Bayer source is about 48 MB. Use a valid lossless DNG-compatible compression mode only after uncompressed output passes interoperability validation.

---

## 3. Existing RawLens integration points

| Concern | Existing component | Required change |
|---|---|---|
| ZSL ring and ownership | `RawZslBuffer.kt` | retain selected `BufferedRawFrame` objects as one merge job; close every image exactly once after completion/failure |
| Shutter and ZSL selection | `RawCameraController.kt` | replace SR-on per-frame saves with one logical burst work item |
| Settings and UI | `MainActivity.kt`, `activity_main.xml` | quick-menu RAW SR tile, preference, availability/count display |
| RAW normalization | `RawFrameMetadata.kt`, `RawSensorUnpacker.kt`, `RawPreDemosaicPipeline.kt` | expose per-frame black/white, CFA phase, crop, lens shading, white balance, and noise profile to the merger |
| GLES infrastructure | `Gles31AmazeProcessor.kt`, shaders | create a persistent GLES 3.1 SR processor and shader passes |
| JPEG development | `RawDevelopmentCoordinator.kt`, `Gles31JpegOutputProcessor.kt` | accept a merged scene-linear texture, without re-running AMaZE on a Bayer plane |
| Mosaic DNG saving | `DngSaver.kt`, `NativeDngWriter.kt` | leave unchanged for sources; add distinct `LinearDngSaver` and `MosaicSrDngSaver`/native writers |

`NativeDngWriter` is intentionally not the target for merged pixels. It serializes exactly one Camera2 Bayer plane through PhotonCamera's mosaic DNG writer.

---

## 4. User interface and capture contract

### 4.1 Quick menu

Add a third quick-settings row in `activity_main.xml` with:

```text
RAW SR
OFF
```

When active and warm, show:

```text
RAW SR
ON x15
```

Required states:

| State | Tile text | Meaning |
|---|---|---|
| disabled | `RAW SR / OFF` | normal ZSL source-frame saving |
| requested/warming | `RAW SR / WARMING` | ring has not yet reached required count |
| ready | `RAW SR / ON xN` | next shutter merges N selected frames |
| unavailable | `RAW SR / UNAVAILABLE` | full RAW ring or required GPU capability is unavailable |
| capture | `RAW SR / MERGING` | one background job owns selected frames |

The existing Settings frame-count control becomes **ZSL frames / SR merge count**. Keep its 1-30 support for legacy ZSL; choosing RAW SR clamps the active count to 2-30. Add an advanced DNG mode control: **Linear RGB (recommended)** or **Mosaic SR - RawTherapee demosaic**.

### 4.2 Preferences and snapshot

Add:

```kotlin
data class RawSuperResolutionSettings(
    val enabled: Boolean = false,
    val dngMode: RawSrDngMode = RawSrDngMode.LINEAR_RGB,
    val outputScale: Float = 1f, // Internal only in v1.
    val keepSourceBurst: Boolean = false // Deferred UI.
)

enum class RawSrDngMode { LINEAR_RGB, MOSAIC_SR }
```

Persist `enabled` in `SharedPreferences`. Snapshot it in `RawCameraController.beginCapture` with `CaptureFormat`, JPEG output settings, denoise settings, and exposure mode. Settings changes must never alter a queued merge.

### 4.3 Capture behavior

1. User presses shutter while RAW SR is on.
2. Controller waits up to the existing ZSL selection timeout for the configured count.
3. `RawZslBuffer.takeBest` returns ordered frames; it must retain its current focus/AE/lens/motion quality scoring.
4. Controller stops ZSL repetition only after ownership transfers to one `RawSuperResolutionCapture` work item.
5. The serialized writer executes the merge, writes the prime DNG, optionally writes JPEG, then closes all images.
6. Status reports `RAW SR x15 -> DNG + JPEG`, or a truthful fallback/error.

No current source frame may be saved or JPEG-developed independently when SR is on unless an explicit fallback requires a reference-frame output.

---

## 5. Data model and invariants

Create an immutable capture snapshot:

```kotlin
internal data class RawSuperResolutionCapture(
    val frames: List<RawSuperResolutionFrame>,
    val settings: RawSuperResolutionSettings,
    val captureFormat: CaptureFormat,
    val jpegSettings: JpegOutputSettings,
    val denoiseSettings: DenoiseSettings,
    val selectedCameraId: String,
    val outputOrientation: Int
)

internal data class RawSuperResolutionFrame(
    val image: Image,
    val result: TotalCaptureResult,
    val metadata: RawFrameMetadata,
    val timestampNanos: Long,
    val motionRadiansPerSecond: Float
)
```

Invariants:

1. A job has 2-30 frames at construction; otherwise normal single-frame fallback is used.
2. All eligible frames have identical active crop dimensions, RAW buffer geometry, CFA layout, and compatible lens/camera ID.
3. Each frame uses its own captured black levels, white level, lens shading, exposure time, sensitivity, neutral point, and noise profile. Never read a later preview result while merging.
4. The reference is one of the captured frames, never a synthetic average.
5. A selected `Image` has precisely one owner at any time and is closed in all outcomes.
6. GPU processing is sequential per non-reference frame. Do not retain a full CPU float array for every input.

---

## 6. Reference-frame selection and eligibility

Use the existing ZSL ranking to select the candidate set, then pick one base frame from that set.

Reference score, in order:

1. valid RAW metadata/geometry and no saturated or missing plane;
2. AF stable, AE converged, lens stationary;
3. lowest rolling-shutter angular travel;
4. high sharpness score from a downsampled Bayer-quad luminance image;
5. median timestamp among valid candidates when scores are similar, to minimize typical displacement.

Reject a frame before GPU allocation when:

- camera ID, active crop, dimensions, pixel stride, CFA phase, or bit-depth normalization is incompatible;
- exposure differs by more than a conservative same-exposure threshold in v1;
- it is too saturated, has invalid black/white levels, or lacks required color metadata;
- a cheap global registration/sharpness check predicts displacement beyond the local-flow search range.

V1 is **same-exposure SR**. A future bracketed HDR-SR mode needs exposure-normalized registration, saturation masks, a different image-formation model, and separate validation.

---

## 7. GPU Wronski/IPOL pipeline

Create `RawSuperResolutionProcessor`, running on the same dedicated writer/EGL lifecycle as the existing GLES processors but with its own shader programs and texture pool.

### 7.1 Input preparation

For every frame:

1. Upload the original 16-bit Bayer plane with stride/crop information.
2. Normalize samples: `(raw - black(channel)) / (white - black(channel))`.
3. Apply lens-shading correction only if the HAL did not already apply it.
4. Keep Bayer phase exact after sensor-origin/crop offsets.
5. Build an alias-reduced grayscale image from 2x2 Bayer quads for alignment. Preserve the full Bayer source for accumulation.
6. Estimate base-frame SNR from normalized mean brightness and the captured noise profile.

### 7.2 Parameters from SNR

Use IPOL initial parameters, made explicit and centralized in `RawSrTuning`:

| Base-frame SNR | tile size `T` |
|---|---:|
| under 14 | 64 px |
| 14-22 | 32 px |
| above 22 | 16 px |

Interpolate `kDetail`, `kDenoise`, `Dth`, and `Dtr` over clipped SNR 6-30. Start from IPOL's reported endpoints: `kDetail` 0.33 to 0.25 px, `kDenoise` 5.0 to 3.0, `Dth` 0.81 to 0.71, and `Dtr` 1.24 to 1.0. Initial noise-independent values: `kStretch=4`, `kShrink=2`, `t=0.12`, `s1=2`, `s2=12`, `Mth=0.8 px`.

All constants must be logged in debug builds and testable without GLES.

### 7.3 Alignment: each non-reference frame

1. Build a four-level Gaussian pyramid of Bayer-quad grayscale images.
2. Partition the reference into non-overlapping tiles of `T x T`.
3. At coarsest level, run bounded block matching in a local search window.
4. Propagate tile displacement through finer levels; use L2 matching at coarse levels and L1 on the finest level.
5. Run exactly three inverse-compositional Lucas-Kanade refinements on each final tile for sub-pixel flow.
6. Reject unreliable/flat tiles using Hessian determinant and matching-residual thresholds.
7. Store a tile-wise flow texture in reference coordinates; do not blur flow across known discontinuities.

The implementation must expose an optional debug flow visualization and per-tile residual texture.

### 7.4 Kernel covariance

For each source frame, calculate local gradients and the structure tensor on the half-resolution Bayer-quad luminance representation. From tensor eigenvalues/eigenvectors derive the anisotropic Gaussian covariance:

- narrow support in detailed corners;
- elongated support along reliable edges;
- wider/more radial support in flat or low-SNR regions.

Store either covariance coefficients `(a, b, c, d)` or an equivalent packed inverse covariance texture. The merge shader must use these directly; do not allocate per-pixel Kotlin objects.

### 7.5 Motion robustness

For each non-reference frame and output region:

1. Warp local source samples by its tile flow.
2. Compare warped/reference local mean and standard deviation.
3. Evaluate expected noise using the per-frame camera noise profile.
4. Apply the noise-aware/Wiener robustness relation to produce `r_n(x,y)` in `[0, 1]`.
5. Penalize local flow inconsistency, which identifies occlusion, aperture-problem failures, and independent object motion.
6. Accumulate `Rc(x,y) += r_n(x,y)` at half resolution.

Robustness is mandatory. A merge without it can create visible ghosting even when average alignment looks correct.

### 7.6 Sequential accumulation

Allocate once:

- `num`: RGB float numerator, target grid size;
- `den`: RGB/compatible weight denominator, target grid size;
- `Rc`: half-resolution accumulated robustness;
- working source, pyramid, flow, covariance, and robustness textures for one frame.

For each non-reference frame:

1. compute flow, covariance, and robustness;
2. map each target sample back into the source Bayer grid;
3. visit the local 3x3 Bayer support;
4. place the observed sample only in its R, G, or B accumulator;
5. multiply kernel weight by robustness and add to `num`/`den`;
6. release/reuse per-frame working textures.

After all non-reference frames, add the reference frame last. At locations with low `Rc`, increase the reference support / local denoising according to the IPOL accumulated-robustness strategy. At severely insufficient confidence, discard previous fusion locally and reconstruct only from the reference. This prioritizes absence of artifacts over aggressive detail reconstruction.

Normalize `mergedRgb = num / max(den, epsilon)`. Produce `effectiveFrameCount = 1 + Rc` for diagnostics and local output denoising.

For `MOSAIC_SR`, run a companion CFA-target accumulation on its derived target grid. Each target location accumulates only observations matching that location's Bayer color. The RGB merge remains required for the JPEG, debug comparisons, and validation; do not create Mosaic SR simply by re-mosaicing the completed RGB output.

### 7.7 Output color and JPEG

The merged texture is linear camera RGB. Resolve its camera-to-ACEScg/color transform using the selected reference metadata and feed it directly into the existing JPEG output processor. Do not send it through `RawDevelopmentCoordinator.developJpeg`, which expects a Bayer plane and performs AMaZE.

Add a sibling API such as:

```kotlin
fun developMergedJpeg(
    mergedCameraRgb: GlesTexture,
    referenceMetadata: RawFrameMetadata,
    settings: RawDevelopmentSettings,
    outputSettings: JpegOutputSettings
): DevelopedJpeg
```

Apply any final spatial denoise with strength inversely proportional to `effectiveFrameCount`, and only after confidence-aware fallback. Keep this transform independent of DNG writing so the prime DNG remains minimally rendered.

---

## 8. Merged DNG writers

Add `LinearDngSaver` and `MosaicSrDngSaver` with isolated native DNG encoder APIs. Do not modify the source-DNG contract.

### 8.1 Writer input

```kotlin
data class LinearDngInput(
    val width: Int,
    val height: Int,
    val cameraRgb16: ShortBuffer,
    val reference: RawFrameMetadata,
    val referenceResult: CaptureResult,
    val frameCountSelected: Int,
    val frameCountAccepted: Int,
    val outputScale: Float,
    val algorithmId: String = "RawLens Wronski RAW SR v1"
)
```

```kotlin
data class MosaicSrDngInput(
    val width: Int,
    val height: Int,
    val cfa16: ShortBuffer,
    val cfaPattern: RawCfaPattern,
    val reference: RawFrameMetadata,
    val referenceResult: CaptureResult,
    val frameCountSelected: Int,
    val frameCountAccepted: Int,
    val targetScale: Float,
    val algorithmId: String = "RawLens Wronski Mosaic SR v1"
)
```

Quantize only at this boundary. Keep GPU/internal merge data in float or half float. The output scale and clipping policy must be explicit metadata, not an undocumented multiplication.

### 8.2 Required output behavior

1. Insert a pending MediaStore item under `DCIM/RawLens`.
2. For `LINEAR_RGB`, write one main-image IFD as 16-bit three-sample linear raw RGB.
3. For `MOSAIC_SR`, write one main-image IFD as 16-bit one-sample CFA data with the target 2x2 Bayer pattern, target active area, and no claims that it is an original sensor readout.
4. Copy truthful reference EXIF: capture time, lens, focal length, aperture, ISO, exposure, orientation, and camera identity.
5. Write valid RGB color transforms / calibration metadata suitable for the selected output representation.
6. Add XMP/private metadata: algorithm version, selected/accepted/rejected frame counts, reference timestamp, output scale, source camera ID, and `DerivedFromRawBurst=true` for Mosaic SR.
7. Optionally write a small preview/thumbnail; this is not a substitute for the main image.
8. Publish the MediaStore item only after a successful close; delete its pending entry on all failures.

### 8.3 DNG interoperability gate

Before switching the feature on by default, validate both modes in Lightroom/Adobe Camera Raw, RawTherapee, darktable, and at least one independent DNG parser. For Mosaic SR, explicitly test multiple RawTherapee demosaic algorithms, correct CFA phase, target dimensions, border handling, white balance, and color transforms. Verify absence of clipped/magenta output in both modes.

---

## 9. Threading, memory, and lifecycle

1. Keep one serialized writer/merge worker. A merge must never overlap another full-resolution development or merge job.
2. Reserve queue capacity by **logical artifact**, not by selected-frame count, when SR is on. The job itself owns up to 30 camera images.
3. Extend memory estimation to include target-grid `num`, `den`, merged RGB, `Rc`, reference pyramid/gradients, and one non-reference frame workspace.
4. Perform device-specific admission control before selection. If a 30-frame ring plus expected GPU peak exceeds the safe budget, report a clear fallback and preserve normal capture.
5. On activity shutdown, let an active foreground-backed merge complete when possible; otherwise close all unprocessed images and remove pending MediaStore items.
6. Ensure every EGL object is created/destroyed on its owning writer thread.

---

## 10. Failure and fallback policy

| Condition | Result |
|---|---|
| fewer than two valid selected frames | write/develop the reference source normally, status `RAW SR FALLBACK - 1 FRAME` |
| one or more incompatible frames | reject those frames; continue if at least two remain |
| global alignment fails for a frame | reject that frame; preserve reference and accepted frames |
| local motion/occlusion | per-pixel base-frame fallback, never global ghost blend |
| shader/EGL failure before output | close images, delete pending output, report error; do not save fake prime DNG |
| DNG write fails after merge | retain JPEG result only if its save succeeds; report `JPEG SAVED - DNG FAILED` |
| JPEG development fails after valid DNG | publish DNG, report `DNG SAVED - JPEG FAILED` |

Never silently call a merged Bayer file a prime DNG. Never advertise an SR output if only the untouched source frame was saved.

---

## 11. Phased implementation sequence

### Phase A - contracts and UI

1. Add `RawSuperResolutionSettings`, preference storage, snapshotting, and quick tile.
2. Add `RawSuperResolutionCapture` and replace SR-on individual save scheduling with one job.
3. Add strict image-ownership tests and source-frame legacy regression tests.
4. Gate SR by ZSL readiness and GPU capability, but temporarily route accepted jobs to a reference-only diagnostic implementation.

**Exit criterion:** toggling changes no legacy output behavior when off; SR on owns exactly one burst job and produces truthful status/fallback.

### Phase B - deterministic alignment and merge prototype

1. Implement Bayer-quad normalization, pyramid, tile matching, and debug flow output.
2. Implement sub-pixel inverse-compositional LK and rejection masks.
3. Implement RGB numerator/denominator accumulation at 1x, with reference-only output as an A/B option.
4. Add synthetic shifted-Bayer tests and desktop/device debug capture fixtures.

**Exit criterion:** static synthetic scenes show correct R/G/B reconstruction, sub-pixel flow, reduced noise, and no channel/CFA phase swap.

#### Phase B implementation checkpoint (2026-09-01)

Implemented:

1. `RawSrAlignment` is the deterministic CPU oracle for Bayer-quad grayscale construction,
   image pyramids, coarse-to-fine tile search, inverse-compositional translation refinement,
   residual scoring, and per-tile rejection masks. Flow is always expressed in Bayer-quad pixels.
2. `RawSrAlignmentTuning` selects 64/32/16-RAW-pixel-equivalent tiles from estimated SNR.
3. `RawSrMergePrototype` provides the required phase-safe 1x RGB numerator/denominator merge and
   reference-only A/B mode. Unreliable tiles do not contribute non-reference samples.
4. GLES 3.1 shader contracts live under `shaders/rawsr`: Bayer-quad reduction, pyramid reduction,
   block matching, LK refinement, and ping-pong RGB accumulation. The accumulation boundary alone
   converts quad flow back to full-resolution pixels.
5. `RawSrAlignmentTest` covers all Bayer phases, synthetic sub-pixel motion, SNR tuning, channel
   ordering, and noise reduction.

Remaining before Phase B can be called device-complete:

1. `Gles31RawSrProcessor` now allocates pyramid, flow, and ping-pong accumulator textures and
   dispatches the five RAW-SR compute passes from one thread-confined GLES session. Offline GLSL
   validation passes for every shader.
2. `RawSrGpuInstrumentedTest` compiles/executes the graph, records GLES vendor/renderer, and compares
   corresponding interior flow tiles and normalized RGB with the CPU oracle and known synthetic
   motion. On 2026-09-01 the full headless matrix passed on ARM Mali-G615 MC2. Static and ±integer
   shifts were exact; mixed sub-pixel truth MAE was x=0.01697/y=0.01831 Bayer-quad pixels, GPU/CPU
   MAE was below 0.00000025, and reliable interior coverage was 100%. Flat-field rejection,
   reference-only accumulation across all four Bayer phases, and two-frame RGB accumulation also
   passed. Adreno hardware validation remains required when a device becomes available.
3. `RawSrDebugExporter` now emits flow/confidence PNGs and the androidTest fixture directory defines
   the lossless normalized-Bayer manifest contract. A redistributable real RAW burst still needs to
   be captured and checked in; synthetic Bayer fixtures must not be mislabeled as real captures.
4. Replace Phase A's truthful reference fallback only after those device and real-fixture comparisons pass. The
   current capture path intentionally continues to save/develop the reference frame.

### Phase C - robustness and image-quality gate

1. Implement noise-profile weighting, per-frame robustness, `Rc`, and reference-last fallback.
2. Add effective-frame-count-aware post-merge denoise for JPEG only.
3. Tune SNR parameters on test bursts from the actual target phones.
4. Test people, leaves, water, vehicles, and low light explicitly.

**Exit criterion:** moving regions remain artifact-free even if they locally fall back to lower detail/noisier single-frame reconstruction.

### Phase D - output artifacts

1. Add direct merged-texture JPEG development.
2. Add uncompressed Linear RGB DNG writer and cross-tool validation.
3. Add the experimental Mosaic SR DNG writer, direct CFA-target accumulation, and RawTherapee demosaic validation.
4. Add valid lossless compression only after bit-exact decode and compatibility tests.
5. Add merge provenance metadata and MediaStore lifecycle tests.

**Exit criterion:** either selected DNG mode produces one valid merged DNG and one matching JPEG; Linear RGB has editable color/exposure behavior and Mosaic SR can be correctly demosaiced in RawTherapee.

### Phase E - performance and release readiness

1. Profile 2, 8, 15, and 30 frames on supported devices.
2. Tune texture lifetimes, precision, tile sizes, and queue admission limits.
3. Add per-device adaptive frame cap as a future enhancement, while honoring the user-selected upper bound.
4. Release behind the quick-menu switch; keep debug statistics accessible.

**Exit criterion:** no OOM, no leaked images, no stalled camera session, and clear capture feedback under every supported configuration.

---

## 12. Test matrix

### Unit tests

- 2-30 count validation and preference persistence.
- capture snapshot immutability while UI settings change.
- `Image` closure exactly once on normal, rejection, cancellation, and writer-rejection paths.
- frame metadata compatibility and reference selection.
- SNR tuning boundaries and interpolation.
- linear-RGB and Mosaic SR DNG quantization/scaling, RGB byte order, CFA phase, and metadata mapping.

### GPU/instrumented tests

- synthetic Bayer checkerboard shifted by known fractional translations;
- RGGB, GRBG, GBRG, and BGGR phase/crop-origin coverage;
- uniform patch denoising improves with frame count;
- edge remains sharp under correct motion;
- moving-region robustness rejects conflicting samples;
- result is deterministic for the same burst and settings.

### Device validation

| Scene | Counts | Expected result |
|---|---|---|
| daylight static fine detail | 2, 8, 15, 30 | reduced Bayer artifacts; diminishing gains after adequate coverage |
| dim static scene | 8, 15, 30 | lower noise while retaining detail |
| people / vehicles | 8, 15 | no ghosts; moving parts may use reference fallback |
| foliage / water | 8, 15 | no unstable texture flicker or colored artifacts |
| low-light highlights | 8, 15 | no highlight color shift or clipped merge |
| extreme motion | 2, 8 | clear fallback rather than a corrupted result |

### Artifact acceptance

- one merged DNG / one JPEG exactly when RAW SR is on;
- source DNG count equals configured selection when RAW SR is off;
- merged DNG and JPEG have matching orientation/crop;
- Linear RGB DNG opens in independent RAW tools; Mosaic SR DNG opens and demosaics correctly in RawTherapee;
- no memory growth proportional to all frame workspaces;
- camera returns to a warmed ZSL state after each job.

---

## 13. Research decisions captured by this plan

1. **Bayer-direct fusion precedes demosaic.** Merging developed JPEGs or individually demosaiced RGB frames loses the sampling advantage and is not the chosen path.
2. **Reference-frame priority beats equal averaging.** The base frame is both visual anchor and safe fallback.
3. **Robustness is a core algorithm stage.** Alignment alone is insufficient for occlusions and local motion.
4. **Same exposure comes first.** It simplifies registration and keeps the merge physically interpretable. HDR-bracketed SR is a distinct feature.
5. **1x comes first.** It delivers real quality gains with manageable output/memory costs. Sensor/lens sampling determines whether 2x has recoverable detail.
6. **Linear RGB DNG is the default quality merged-negative format.** Mosaic SR DNG is a valid, explicitly derived CFA reconstruction for later RawTherapee demosaic; source mosaic DNG remains the original/archive format.
7. **Learned models are research candidates, not v1 dependencies.** They need a RawLens-specific dataset, device evaluation, model packaging, and a separate policy for hallucination risk.

---

## 14. Implementation prompts

Use these as bounded work requests. Run the test/build checks stated in each prompt before handing off. Do not modify unrelated files.

### Prompt 1 - settings and quick menu

> Implement RAW SR settings and quick-menu UI in RawLens. Add a persisted `RawSuperResolutionSettings.enabled` preference, a `RAW SR OFF/ON` quick tile, and state text for warming/unavailable/ready. Enabling SR must enable RAW ZSL; disabling SR must keep legacy ZSL available. Clamp active merge count to 2-30 without breaking legacy 1-frame ZSL. Snapshot the setting at shutter press. Add unit tests for persistence and count behavior. Do not change output saving yet.

### Prompt 2 - burst work ownership

> Refactor `RawCameraController` so a RAW SR ZSL shutter press creates one immutable `RawSuperResolutionCapture` work item rather than calling `saveRawFrame` per selected frame. Preserve current behavior when RAW SR is off. Ensure each `Image` is closed exactly once on success, queue rejection, cancellation, and exception. Add tests around `RawZslBuffer` selection and worker ownership. Do not implement image merging yet; use a reference-frame diagnostic fallback.

### Prompt 3 - alignment foundation

> Add a GLES 3.1 `RawSuperResolutionProcessor` foundation. Implement normalized Bayer-quad grayscale creation, a four-level pyramid, tile-wise coarse-to-fine block matching, and three inverse-compositional Lucas-Kanade refinements. Output debug flow/residual textures behind a debug flag. Use only captured per-frame metadata, honor CFA phase and crop origin, and add synthetic fractional-shift instrumented tests. Do not write DNG output yet.

### Prompt 4 - robust Bayer merge

> Implement the Wronski/IPOL Bayer-direct merge in `RawSuperResolutionProcessor`: structure-tensor anisotropic kernels, noise-profile-aware per-pixel robustness, sequential RGB numerator/denominator accumulation, accumulated robustness, and reference-last local fallback. Start at output scale 1. Expose merged camera-RGB and effective-frame-count textures. Add synthetic static/moving-scene tests proving channel correctness, noise reduction, and no ghost blending.

### Prompt 5 - JPEG and prime DNG outputs

> Connect merged camera-RGB output directly to the existing JPEG color/output pipeline without running Bayer AMaZE again. Implement two separate DNG outputs without altering source Bayer DNG output: a default 16-bit Linear RGB prime DNG writer and an experimental 16-bit Mosaic SR DNG writer. Mosaic SR must accumulate only same-CFA-color samples directly on an even, larger target Bayer grid, not re-mosaic completed RGB; identify it in metadata as a derived Bayer reconstruction. Copy truthful reference metadata, write merge provenance, add unit tests for RGB order/CFA phase/output scale, and validate later RawTherapee demosaic. RAW SR must save one selected merged DNG mode and one JPEG according to `CaptureFormat`.

### Prompt 6 - performance and safety

> Profile and harden RAW SR on supported devices at 2, 8, 15, and 30 frames. Add logical-job queue admission and memory estimation for merge textures plus retained camera images. Ensure ZSL resumes after success/failure, GLES resources stay writer-thread-confined, and fallbacks are truthful. Add debug metrics for selected/accepted frames, local effective-frame count, alignment rejection, peak memory estimate, and elapsed times.

---

## 15. Source reading list

- Wronski et al., *Handheld Multi-Frame Super-Resolution* (SIGGRAPH 2019): original mobile Bayer-direct method.
- Lafenetre, Facciolo, Eboli, *Implementing Handheld Burst Super-Resolution* (IPOL 2023): practical algorithm details and parameterization.
- Hasinoff et al., *Burst Photography for High Dynamic Range and Low-Light Imaging on Mobile Cameras* (2016): Camera2 RAW-burst and robust mobile merge precedent.
- Lecouat et al., *Lucas-Kanade Reloaded* (ICCV 2021): hybrid learned/physical future direction.
- Lecouat et al., *High Dynamic Range and Super-Resolution from Raw Image Bursts* (2022): future bracketed HDR-SR direction.
- Bhat et al., *Deep Burst Super-Resolution* (CVPR 2021), Burstormer (CVPR 2023), and MFSR-GAN (CVPRW 2025): datasets and learned-fusion research, not v1 dependencies.
