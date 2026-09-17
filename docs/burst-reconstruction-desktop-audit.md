# Desktop burst reconstruction audit

Date: 2026-09-15 (Europe/Athens). Algorithm version audited: 3.

## Verdict

The module builds and executes, but it is **not yet a correct implementation of the complete planned reconstruction engine or a trustworthy GPU conformance reference**. Native merging has correctness defects; SR has incorrect forward/adjoint mathematics; the comparison and resume tools can report misleading results.

This is a source and execution audit. No reconstruction source or existing tests were changed. Diagnostic programs and disposable outputs are under `/private/tmp/burst-audit-s7pjmU`.

## Coverage and verification

Read all 21 production Kotlin files, all 10 test/helper Kotlin files, and `build.gradle.kts`: 6,254 Kotlin lines plus the build configuration. Generated build outputs were inspected as execution evidence, not treated as handwritten source. Compared implementation to `cfa-inverse-burst-merge-plan.md` and its prompts.

Executed:

- Build/install distribution: successful.
- `./gradlew :tools:burst-reconstruction-desktop:test --rerun-tasks --console=plain`: **40 tests, zero failures/errors/skips**, actual recompilation and test execution.
- Additional Java diagnostic probes calling the existing compiled Kotlin code, including reflection to inspect private sampling/reprojection helpers.
- Native pipeline on the existing eight-frame `/private/tmp/lr-burst2` bundle, crop `400,400,260,258`, with one and four workers. Both runs selected reference 4 and admitted three merge inputs. DNG, merged planes, and diagnostic maps were byte-identical between worker counts.
- ExifTool validation of that output: two warnings, ExposureTime and ISO in the wrong IFD.

The stored real bundle is a previously prepared local corpus. This audit does not establish its original capture provenance or validate full-resolution quality. No phone GPU run, GUI RAW-developer comparison, exhaustive full-resolution burst sweep, or thermal/memory stress test was performed. Passing a crop test does not override the failures below.

Severity: **P1** = blocks correctness/conformance; **P2** = material feature/metadata/edge-case defect. “Reproduced” means a diagnostic execution demonstrated the behavior; “source-confirmed” means traced in the code, without a separate end-to-end image-quality experiment.

## P1 findings

### 1. Noise profile units and exposure scaling are wrong

Locations: `Radiometry.kt:76`, `Radiometry.kt:103`, `Reconstruction.kt:116`, `Reconstruction.kt:136`.

`transformNoisePair` treats Camera2/DNG noise coefficients as raw DN coefficients and normalizes them again. Camera2 defines its input signal in normalized `[0,1]` units; see [SENSOR_NOISE_PROFILE](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_NOISE_PROFILE).

Independently, for `y = z/e` and `Var(z) = S*z + O`, the corresponding coefficients are `a = S/e`, `b = O/e²`. The implementation multiplies by `e` and `e²`. Its ISO fallback repeats that direction error.

**Reproduced:** changing e from 1 to 2 multiplies slope by 2 and intercept by 4; they should divide by 2 and 4 in the normalized model. This changes alignment confidence, robust rejection, and relative merge weights. Correct the coefficient domain first, then recalibrate thresholds that were tuned around the wrong variance. The earlier plan's noise-domain wording also needs correction.

### 2. Odd active-area origins apply CFA phase twice

Locations: `DngAdmission.kt:300`, `QuadSplit.kt:63`, `QuadSplit.kt:132`.

The reader stores an already shifted Bayer pattern. Split/repack then pass that pattern together with the sensor origin into `planeMap`, shifting it again. Repacking with the same wrong mapping can hide the defect in round-trip tests while green alignment and color-dependent noise operate on the wrong colors.

**Reproduced:** a stored GRBG image at sensor x=1 with R=.9, G=.4, B=.2 splits to R=.4, G1=.9, B=.4. Choose one convention: sensor pattern plus absolute origin, or stored pattern plus zero origin. Test plane identity, not just split/repack round trips.

### 3. Flow-cell lookup uses the wrong cell width on partial grids

Locations: `Merge.kt:306`, `PsfSr.kt:346`; grid creation at `CellFlow.kt:100`.

The producer uses `ceil(width / tuning.flowCellQuads)` cells. Consumers reconstruct cell size as `width / cellCount`, which is smaller than the configured size whenever the final cell is partial. Both axes then sample displacement/confidence/sharpness from the wrong cells.

**Reproduced:** width=34 quads, configured cell=16, grid width=3; x=12 belongs to cell 0 but returns cell 1. Store cell spacing in `FlowField` and use it consistently. Typical camera dimensions and arbitrary crops need this test.

### 4. `compare` reports success without comparing image data

Location: `Main.kt:167`.

The command checks limited metrics and stage statuses/cache keys. It never reads binary maps or DNG samples; it also omits accepted-frame counts and most score components. Equal cache keys describe configuration, not equal output.

**Reproduced:** replaced one result's `merged.bin` with three garbage bytes and its DNG with one byte. `compare` still printed `compare ok (no differences)` and exited successfully. This must be fixed before any CPU/GPU parity claim. Require a complete artifact inventory, shape/type validation, exact categorical comparisons, finite-value checks, and per-stage numerical tolerances.

### 5. Resume does not invalidate reference-dependent stages

Locations: `Pipeline.kt:144`, `Pipeline.kt:173`, `Pipeline.kt:222`, `Pipeline.kt:497`.

Only the selection stage includes the reference override. Global alignment, flow, merge, and repack keys omit the selected reference and upstream artifact keys. Changing `--ref` can therefore reuse results in the previous reference coordinate system.

**Reproduced:** run with ref 0, resume with ref 1: flow cache reused, then `FileNotFoundException` for `flow-0.bin`, because the former reference had no flow artifact. Other existing file combinations can mask the crash while retaining stale outputs. Bind downstream keys to selected reference and dependency hashes. Also correct CLI precedence: manifest override currently wins over the documented CLI override.

### 6. Cache validation omits gyro input and output integrity

Locations: `BundleIo.kt:430`, `Pipeline.kt:607`, `Pipeline.kt:641`.

Input hashes cover the manifest and DNG bytes, but no gyro CSV contents. Cached binary outputs are checked mostly by existence; flow accepts any `flow-*` file, repack always returns true, SR does not check its binary/DNG, and there are no output checksums. SR keys also omit the memory budget used for eligibility.

**Reproduced:** adding gyro data leaves `inputHash` unchanged. Moving the final DNG away then resuming reports repack cached and leaves the DNG absent. Validate all declared outputs, their hashes and dimensions; include all consumed input sidecars/options; publish completed artifacts atomically.

### 7. Alignment ignores exposure normalization and source noise

Locations: `Pipeline.kt:191`, `Pipeline.kt:229`, `Reconstruction.kt:81`.

Pyramids are built from black/white-normalized packs without applying per-frame exposure scale. Those scales only reach merge/noise setup. Cell alignment sees reference noise only; coarse fitting uses raw residuals against Huber delta 1.5, so ordinary normalized residuals effectively receive L2 weighting.

**Source-confirmed:** mixed exposure/ISO bursts are neither rejected by a same-exposure gate nor correctly normalized for alignment. Same geometry with changed exposure can be interpreted as motion or rejected. Specify supported exposure variation and normalize both image residuals and their variances consistently.

### 8. Affine/global geometry is not propagated correctly

Locations: `GlobalAlign.kt:355`, `CellFlow.kt:103`, `GlobalAlign.kt:159`.

The affine normal matrix sums separate x and y Jacobian outer products. A scalar photometric residual has one six-component Jacobian; its outer product includes cross-axis terms that are missing here. Cell flow then initializes only from global tx/ty, discarding its affine matrix. No conditional local affine solve exists despite the file's description.

**Source-confirmed:** the pipeline cannot implement the planned piecewise affine model faithfully. Fix the scalar-residual Jacobian and initialize each cell using its transformed spatial position. Compare descended translation against the actual affine model, not an affine model reduced to translation.

### 9. SR forward projection and its adjoint are inconsistent

Locations: `PsfSr.kt:279`, `PsfSr.kt:291`, `PsfSr.kt:326`.

- Reference-to-source flow is indexed at source coordinates and negated to approximate the inverse. This is only valid for constant translation, not spatially varying flow.
- Forward projection uses bilinear latent samples; the claimed adjoint truncates each coordinate to one integer destination, omitting bilinear transpose weights and forward boundary normalization.
- PSF covariance is in native quad units, but kernel offsets are applied directly in SR pixels without scaling the footprint.
- The update loops over source observations and scatters into shared latent pixels. A serial CPU execution works, but this contradicts the planned output-owned gather GPU implementation.
- Backprojection uses confidence/Huber weighting but omits the native clipping/motion gates and explicit inverse-variance weighting in its data update.

**Source-confirmed:** this is not the adjoint of the implemented sampling operator. Add independent `A`/`Aᵀ` implementations and a dot-product adjoint test with fractional shifts, varying flow, PSFs, and borders before trusting SR convergence or porting it to GPU.

### 10. SR early stopping associates a residual with the wrong image

Location: `PsfSr.kt:200`.

`adjointStep` measures the current latent; the code updates that latent before testing the residual and saves the updated image as `best`. The residual belongs to the preceding image. On reversal, restoring `best` can restore the very iterate whose worse residual was just detected. The last returned update is never evaluated.

**Reproduced:** one iteration recorded residual 1.780817 while the returned image evaluated to 0.612915; two iterations recorded 0.612915 for an image evaluating to 0.313305. This demonstrates the one-iterate offset even on an improving example. Existing monotonicity tests inspect a history that intentionally omits worsening values, so they do not validate the returned image.

### 11. SR gates do not measure the quantities they claim

Locations: `Reconstruction.kt:253`, `Reconstruction.kt:264`, `PsfSr.kt:140`, `Pipeline.kt:375`.

`mtfRatio` is a ratio of summed squared luminance, with no gradients, frequency analysis, or MTF estimate. `1-confidence` is dimensionless but compared to a displacement threshold in quad pixels. Phase coverage pools different spatial cells, so unrelated regions can collectively pass without local burst diversity.

**Reproduced:** changing a constant image from .2 to .3 yields “MTF ratio” 2.25 despite zero detail. A fully populated 2x phase lattice `{0,.5}²` is rejected because the code requires the unrelated 3x3 1.5x histogram to pass first (coverage 4/9).

Use scale-specific, local sampling-support and positional-uncertainty tests. Validate resolution gain independently of brightness/noise. The present gates cannot substantiate true SR.

### 12. `--strict-fp32` is not a strict FP32 implementation

Locations: `GlobalAlign.kt:296`, `GlobalAlign.kt:336`, `CellFlow.kt:301`, `Merge.kt:229`, `QuadSplit.kt:6`.

Normal equations and solves remain Double. Global fitting casts values originating as Float back through Float to Double, which does not change the subsequent arithmetic. Merge accumulates Double numerator/denominator and rounds only after summing. There is no FP16 storage emulation.

**Source-confirmed:** small differences between the current modes do not validate Mali arithmetic. Define operation/storage boundaries and implement actual FP32 accumulations/solves plus optional FP16 round-trip storage before using this mode as a hardware oracle.

## P2 findings

### 13. Accepted-count and fallback maps count rejected observations

Location: `Merge.kt:362`.

`finalize` recomputes a geometric/confidence proxy rather than counting the actual nonzero robust weights per channel. It counts samples removed by clipping, motion, and Tukey rejection. Confidence also includes out-of-footprint frames.

**Reproduced:** reference=.2, moving frame=.9, tiny noise: output=.2, but acceptedCount=2 and fallback=0. The second observation contributed no valid weight. Preserve per-plane statistics during accumulation and derive diagnostics/fallback from those exact values.

### 14. Clipping is checked after exposure normalization

Location: `Merge.kt:274`.

Sensor saturation must be checked on source z/taps. Checking `obs=z/e` can admit a saturated long exposure after scaling down, or reject valid unsaturated short-exposure highlights after scaling up. Bilinear interpolation can also hide a clipped source tap. Carry source-domain tap validity through sampling. Backprojection currently has no corresponding clipping check.

### 15. Latent and output noise estimates do not describe the merge

Locations: `Merge.kt:206`, `BurstDngWriter.kt:201`.

Pass-two latent variance simply evaluates the reference noise model at x0. Output DNG noise is always reference noise divided by globally accepted frame count. Neither accounts for actual weights, resampling covariance, local rejection, or fallback. In a reference-only region of an eight-frame accepted burst, the tag can claim eightfold variance reduction where none occurred. Propagate weighted variance and choose a documented conservative global approximation if a spatial model cannot be stored.

### 16. Gyro alignment is disabled, and integration is incorrect at boundaries

Locations: `Pipeline.kt:191`, `GlobalAlign.kt:36`, `GlobalAlign.kt:71`.

Pipeline always passes null intrinsics; the manifest has no intrinsics field, so gyro transforms always become identity. Integration can include time before the requested start, omits the final interval, and cannot handle a source timestamp preceding the reference with its current forward-only interval. The rotation seed ignores principal-point compensation and sensor-to-camera axis calibration. Blur scoring measures net rotation, which can cancel during oscillation.

**Reproduced:** constant 1 rad/s requested over [1s,2.5s] with samples at 0s and 2s integrates to 2 radians instead of 1.5. Define sample coverage/interpolation policy, integrate clipped intervals in the correct direction, and test axis/principal-point transforms before enabling it.

### 17. DNG reader misses standard EXIF metadata and silently invents exposure

Locations: `DngAdmission.kt:314`, `Reconstruction.kt:90`, `Main.kt:126`.

Exposure and ISO are read only from the selected raw IFD; ExifIFD (tag 34665) is not traversed. Missing values become 1/60 s and ISO 100. Manifest exposure/ISO do not feed `exposureScales`, and declared geometry/camera/CFA values are not cross-checked against decoded DNGs. This allows plausible-looking output using invented calibration.

Read standard metadata locations, validate sidecar conflicts, record missing metadata explicitly, and either use a documented supplied value or reject incompatible reconstruction.

### 18. Reader's supported DNG subset is narrower than admission claims

Locations: `DngAdmission.kt:179`, `DngAdmission.kt:342`.

Only the next-IFD chain is searched, not raw SubIFDs. TIFF field types outside a small list cause failure even for irrelevant metadata. Compression and unsupported bit depths are explicitly rejected (an acceptable documented limitation); however, SamplesPerPixel, SampleFormat, Predictor, LinearizationTable, and relevant opcode/calibration requirements are not validated before treating codes as linear CFA. IFD cycles and multiplication overflow are not guarded.

Publish an exact supported subset and reject unsupported image semantics deliberately. Add external-writer DNG fixtures; self-written DNG round trips cannot expose shared reader/writer errors.

### 19. Writer produces incorrect EXIF placement and long exposures

Locations: `BurstDngWriter.kt:110`, `BurstDngWriter.kt:215`.

ExposureTime and ISO are written in IFD0 instead of ExifIFD. **ExifTool reproduced both warnings.** The fixed 1e9 rational denominator with signed Int numerator saturates above approximately 2.147 seconds. **Reproduced:** 10 seconds reads back as 2.147483647 seconds.

Write an ExifIFD and select a rational representation within its unsigned range. Preserve or explicitly resolve default crop/calibration metadata; the current writer omits DefaultCropOrigin/Size and substitutes identity/neutral calibration when source calibration is absent.

### 20. `run-stage` executes the entire pipeline

Location: `Pipeline.kt:487`.

After validating the stage name, it calls `runAll` without using that name. **Reproduced:** requesting only `validate` executed all twelve stages and wrote a DNG. This defeats quick stage diagnostics and can trigger expensive work unexpectedly. Execute the requested stage and its prerequisites only, with explicit cached input requirements.

### 21. Small pyramids repeat levels with incorrect scale semantics

Location: `Pyramid.kt:60`.

Once dimensions become smaller than 4, reduction stops but the outer repeat keeps appending levels. Consumers still assume a factor-of-two displacement change per level. **Reproduced:** a requested four-level 4x4 pyramid is `[4x4,2x2,2x2,2x2]`. Cap actual depth and derive span from actual levels; test minimum admitted crops.

### 22. PSF estimation is a heuristic without calibration or trust enforcement

Locations: `PsfSr.kt:63`, `PsfSr.kt:70`, `PsfSr.kt:95`, `Pipeline.kt:341`.

The ratio `|Laplacian|/|gradient|` is directly called Gaussian sigma, without a validated conversion or edge-profile fit. As a derivative ratio it is not generally a blur width. `trusted` depends on edge count, not whether sigma was clamped; kernels truncate declared support to radius 3. Pipeline never supplies gyro/focal parameters and reconstruction does not reject untrusted estimates. This is a placeholder quality model, not the planned regional, degradation-aware estimator. Validate against known PSFs and propagate trust/support explicitly.

### 23. Reference features differ materially from their contracts

Locations: `Reconstruction.kt:154`, `Reconstruction.kt:174`, `Reconstruction.kt:195`, `ReferenceScoring.kt:81`.

Clipping uses averaged greens only, missing saturated red/blue or a single clipped green. Pose centrality is timestamp distance, not pose distance. AF/AE penalty is always zero. Sharpness compares unnormalized frames with noise models normalized to another frame. Tie selection uses index order, not timestamps. Label actual proxies honestly and implement/test the intended features before interpreting these scores as quality evidence.

### 24. Admission rejects legitimate per-frame normalization changes

Location: `DngAdmission.kt:112`.

Already normalized frames must have identical black and white levels, although the plan requires using each frame's own calibration. Sensor origins and camera identity are not checked in the same admission path. Permit valid per-frame calibration changes, and check the geometry/camera properties that actually determine compatibility.

### 25. Several tuning settings are unchecked, bypassed, or outside the snapshot

Locations: `Tuning.kt:42`, `CellFlow.kt:80`, `CellFlow.kt:203`, `Merge.kt:81`, `BurstContracts.kt:40`.

**Reproduced:** tuning version 99, negative support size, negative Huber/Tukey values, and negative backprojection counts are accepted by the constructor. Many algorithm thresholds live in source constants instead of the tuning snapshot. The configured 0.60 frame-coverage gate can be bypassed by a hard-coded 0.15 gate plus residual threshold; this is a documented code adjustment but is absent from the plan/tuning contract. Confidence is computed before regularization changes the displacement and is not recomputed afterward. Settings permit one robust pass although execution always uses two, and a requested frame count that execution does not consume.

Validate the whole schema and serialize the effective algorithm settings, including intentional departures from the plan. The custom CPU `smoothstep` supports reversed edges; a future GLSL port must use an explicit equivalent, because native GLSL `smoothstep` is undefined for reversed edges ([GLES 3.1 specification](https://registry.khronos.org/OpenGL/specs/es/3.1/GLSL_ES_Specification_3.10.pdf)).

## Additional completeness/performance observations

- The desktop stores all frame quad packs. This is acceptable as an explicit desktop tradeoff, but it is not the phone's one-frame streaming memory model. `--mem-budget-mb` only gates SR after native decoding/merging; it does not bound native allocations.
- `runAll` reads merged maps again even immediately after computing them, creating avoidable full-size copies. BinaryMap and hashing read/write whole files/buffers. Large-frame peak memory needs measurement.
- SR's heavy loops are serial and do not use `BurstWorkers`; increasing `--threads` accelerates only other stages. Worker overrides above the default pool size do not increase actual pool concurrency; the global override also persists between runs unless explicitly reset.
- No hot-pixel correction, lens-shading application/provenance policy, camera intrinsics serialization, or regional PSF model is implemented in this module.
- Intermediate Gaussian/gradient arrays, first-pass accumulators, and exact per-plane weight decisions are not exported. The stage schema is insufficient for the intended detailed GPU parity debugging.
- `MiniJson` accepts trailing data and duplicate keys, lacks `\b`/`\f` escape support, and silently defaults many missing/wrong-typed fields. BinaryMap arithmetic is unchecked and accepts non-finite payloads. CLI unknown options/invalid numeric values can silently fall back to defaults. Tighten schema parsing before accepting these artifacts as verified caches.
- Final DNG writes and cache publication are not atomic. A failure can leave partial or old artifacts under apparently final names. Reusing an output directory can retain stale SR files when a later run falls back to native.
- Merger samples one cell displacement per quad without evaluating per-color site offsets for affine/SR geometry. A future correct affine/scaled CFA implementation must derive each plane's physical lattice origin explicitly.

## Why the current tests miss these defects

- `noisePairTransformIsFinite` checks finiteness, not units or exposure-transform identities.
- CFA split/repack tests use zero origins and cancel matching mapping mistakes.
- The noise reduction test supplies a noiseless reference; outputting only that reference passes its quality assertion. It also bypasses production frame rejection by calling merge directly.
- The moving-object test's queried quad coordinates are mostly outside the painted full-resolution square; it does not reliably measure the intended occlusion interior.
- Synthetic odd full-pixel shifts move mosaic samples between CFA colors rather than rendering a shifted continuous color scene and sampling the CFA anew. Achromatic fixtures conceal that error.
- Backprojection tests inspect filtered residual history, not an independently evaluated returned image or a true adjoint relationship.
- Pipeline tests cover same-option resume only. The test named `runResumeAndCompare` does not invoke the CLI comparison.
- No independent DNG reader/writer corpus, spatially varying flow cell-boundary test, threshold-adjacent hardware precision corpus, calibrated PSF/MTF test, or actual GPU result comparison exists here.

## File-by-file review inventory

All files are under `tools/burst-reconstruction-desktop`.

| File | Review outcome |
|---|---|
| `build.gradle.kts` | Builds on the local JVM; no runtime dependencies beyond stdlib; JUnit test dependency exists despite broad “no third-party dependencies” comment. |
| `BayerCoords.kt` | Mapping helpers reviewed; stored-vs-sensor convention is broken by callers (finding 2). |
| `BundleIo.kt` | Serialization/parser/maps reviewed; findings 6, 17 and schema observations. |
| `BurstContracts.kt` | Contracts reviewed; accepted settings exceed execution support (25). |
| `BurstDngWriter.kt` | Writer/quantization reviewed and executed; findings 15, 19. |
| `BurstWorkers.kt` | Disjoint sharding works in tested paths; global override/pool limits noted. |
| `CellFlow.kt` | All levels, solves, reverse checks, regularization reviewed; findings 3, 7, 8, 12, 25. |
| `DngAdmission.kt` | TIFF parsing, unpacking, crop, metadata, admission reviewed; findings 2, 17, 18, 24. |
| `GlobalAlign.kt` | Gyro, translation, affine, solver reviewed; findings 7, 8, 12, 16. |
| `Main.kt` | Every command/option reviewed; findings 4, 17, 20. |
| `Merge.kt` | Both passes, sampling, variance, resolve, diagnostics reviewed; findings 3, 12-15. |
| `PhaseHistogram.kt` | Histogram implementation reviewed; scale/locality gate problems in callers (11). |
| `Pipeline.kt` | Full orchestration/cache/SR paths reviewed and exercised; findings 5-7, 16, 20. |
| `PsfSr.kt` | Full PSF/gating/reconstruction reviewed; findings 9-11, 22. |
| `Pyramid.kt` | Filtering/borders/depth reviewed; finding 21. |
| `QuadSplit.kt` | All pack/sample/repack code reviewed; finding 2 and precision boundary limitation. |
| `Radiometry.kt` | All equations/validation reviewed; finding 1. |
| `Reconstruction.kt` | Admission/scoring/noise/phase/memory helpers reviewed; findings 1, 11, 16, 23. |
| `ReferenceScoring.kt` | Score weights align with plan; timestamp-order assumption noted (23). |
| `RobustWeights.kt` | Finite ordinary inputs implement stated scalar weights; portability/non-finite contracts need care (25). |
| `StageCache.kt` | Key helper correctly hashes supplied fields; caller dependencies are incomplete (5, 6). |
| `Tuning.kt` | All fields/validation/serialization reviewed; finding 25. |
| `BayerCoordsTest.kt` | Read/run; missing nonzero-origin integration and plane-identity checks. |
| `BundleIoTest.kt` | Read/run; missing gyro hashes, corruption and strict schema cases. |
| `DngAdmissionTest.kt` | Read/run; only packed-10 vectors covered here. |
| `PhaseHistogramTest.kt` | Read/run; missing complete 2x-lattice eligibility case. |
| `PipelineTest.kt` | Read/run; missing changed-reference resume, missing-output recovery, genuine CLI compare. |
| `RadiometryTest.kt` | Read/run; missing mathematically correct noise assertions. |
| `ReconstructionTest.kt` | Read/run; misleading denoising/SR coverage detailed above. |
| `ReferenceScoringTest.kt` | Read/run; scalar scores tested, actual image-feature quality largely untested. |
| `RobustWeightsTest.kt` | Read/run; scalar robust-loss checks pass. |
| `SyntheticBursts.kt` | Read; fixture CFA shifting and self-writer limitations detailed above. |

## Recommended repair order

1. Fix comparison and cache dependency/integrity checks so validation can be trusted.
2. Correct CFA origins, flow-grid spacing, normalized noise/exposure, and source clipping.
3. Add independent native fixtures with noisy references, real per-plane colors, mixed exposure, odd ActiveArea origins, and partial flow cells. Propagate actual weights/counts/variance.
4. Correct global/local geometry and gyro metadata/integration; define supported radiometric corrections.
5. Rebuild SR around a tested sampling operator and exact adjoint, then correct early stopping, PSF scale/trust, and local scale-specific eligibility.
6. Implement real arithmetic-compatibility modes and stage exports before the headless Mali port is used for parity testing.
7. Fix DNG metadata/interoperability, stage-only execution, and large-image memory bounds; expand the real-burst regression corpus.

The code is a working prototype with useful structure and reproducible execution. It should not yet be described as a validated complete reconstruction engine.
