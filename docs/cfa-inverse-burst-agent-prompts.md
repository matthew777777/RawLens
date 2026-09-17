# Remaining-work prompts: burst RAW reconstruction

Updated 2026-09-15. Use one prompt per session to control usage. These replace the obsolete create-from-scratch Prompts 1/1A and consolidate the old phone prompts; they do not declare their unfinished deliverables complete.

The desktop module already exists at `tools/burst-reconstruction-desktop`. Repairs are partially applied and **unverified**. The historical audit covers algorithm version 3; current working-tree edits include version 4. Do not restart, overwrite, restore deleted experiments, or assume a patched finding is fixed.

## Copy this preamble before every prompt

> Work in /Users/monikamalinowska/AndroidStudioProjects/RawLens. Read docs/cfa-inverse-burst-merge-plan.md completely and the relevant findings in docs/burst-reconstruction-desktop-audit.md. Inspect git status and current source/tests before editing; this is a dirty worktree with partially applied repairs. Preserve unrelated changes and deleted RawSr/rawsr code. Do not recreate the module, change exposure-bracketed HDR, add phone CPU reconstruction, commit, or use subagents. Use apply_patch. Implement only this prompt's bounded scope, with a reproducer/regression for every defect fixed. Tests must assert independent expected results, not merely round-trip through the same faulty helpers. Run targeted tests, then the desktop suite when practical, and git diff --check. Record exact commands/results, changed files, remaining failures and the next prerequisite in docs/burst-reconstruction-repair-progress.md. Keep that log cumulative; do not rewrite the historical audit as if its findings never occurred. If the session limit prevents completion, leave a precise checkpoint and do not claim success. No phone access is needed for F1–F6.

The progress file is a handoff log, not a replacement for tests. Create it on the first repair task if absent. Record each audit finding as open, partially addressed, or verified with test evidence.

## Execution order

F1 → F2 → F3 → F4 → F5 → F6 → G1 → G2 → G3.

F1–F6 finish and qualify the full desktop CPU implementation. G1 starts with native GLES; G2 ports qualified SR. G3 integrates capture and qualifies production behavior. If a task exceeds one session, resume that task instead of jumping ahead.

| Prompt | Primary audit findings |
|---|---|
| F1: cache, CLI, comparison | 4, 5, 6, 20, 25 |
| F2: radiometry, CFA, native fusion | 1, 2, 3, 13, 14, 15 |
| F3: alignment, reference, precision | 7, 8, 12, 16, 21, 23 |
| F4: DNG and admission | 17, 18, 19, 24; output aspects of 15 |
| F5: matched SR and eligibility | 9, 10, 11, 22; SR aspects of 3 and 12 |
| F6: whole-module qualification | All 25 plus issues not enumerated by the audit |

### F1 — make verification and resume trustworthy

> Repair the existing CLI, result comparison, cache, bundle and tuning code. Start with a fresh build/test baseline; the last completed repair run had 40 tests and failures in BundleIoTest.versionConflictRejected and ReconstructionTest.integerTranslationRecovered, and did not cover later alignment edits. Diagnose rather than weakening assertions to make them pass. Audit Main.kt, BundleIo.kt, StageCache.kt, ResultComparison.kt, ArtifactIo.kt, Tuning.kt, BurstContracts.kt and Pipeline.kt's stage execution.
>
> compare must validate and read all declared DNG/binary outputs, shapes, finite samples, metadata and categorical maps. Require exact integer/decision comparisons and configurable per-stage absolute/relative tolerances; report first/worst mismatch and nonzero exit. Separate timing/backend metadata from algorithm parity. Reject missing, truncated, altered and unexpected/stale output artifacts. Cache keys must bind all consumed input sidecars, effective options, reference, tuning and upstream dependencies. Verify checksums and inventory before reuse, including final DNGs. Publish completed artifacts atomically. Reference override precedence must match CLI help. run-stage must stop at the requested stage after only required dependencies, not claim the whole pipeline completed. Validate versions/ranges and reject duplicate/trailing malformed JSON, nonfinite numbers and unsafe paths. Make effective worker/memory settings truthful.
>
> Regressions: mutate one map value, count, DNG pixel, gyro CSV and output inventory; change reference/mode/crop/tuning/memory budget on resume; remove final DNG; interrupt a stage; use malformed manifest/tuning; run validation-only. Identical valid results must pass, each corruption must fail, and cache reuse must match a fresh execution.

### F2 — correct CFA radiometry and native merge

> Audit Radiometry.kt, BayerCoords.kt, QuadSplit.kt, Merge.kt and relevant Reconstruction/Pipeline code line by line. Finish existing repairs rather than layering duplicate paths. Lock canonical plane order R,G1,B,G2 and the stored-pattern convention. Test all Bayer patterns, odd x/y crop origins, non-square/partial flow grids, single-channel impulses and exact-edge samples. Persist cell spacing; never infer it from width/cell count.
>
> For normalized noise Var(z)=S*z+O and y=z/e, require a=S/e and b=O/e², including fallback scaling. Keep DN-domain calibration explicitly separate. Preserve below-black noise until the output encoding boundary. Apply exposure consistently and reject unsupported metadata/scales. Define hot-pixel/validity and lens-shading policies, including gain-squared variance; do not silently claim corrections not implemented. Check clipping per source tap before exposure normalization. Propagate interpolation and weighted-merge variance, persist it in the cache, and make fresh/resumed outputs equivalent.
>
> Implement two robust passes with actual positive-weight support counts, validity-respecting reference floors and truthful local fallback. Fix sharpness weighting to use corresponding aligned support or disable it explicitly. Test noisy-reference bias: clean burst evidence must not be permanently discarded merely because it differs from one noisy base. QMambaBSR motivates burst-wide consistency, but this deterministic estimator is not QSSM. Keep geometry/phase-aware detail and moving regions distinct; do not use raw agreement alone.
>
> Regressions: independent noisy reference and sources, multiple exposure/ISO levels, calibrated expected variance, an extreme outlier, moving foreground, clipped source at different exposure, zero-confidence input, color impulses, negative noise fluctuations and all-black/white fixtures. Verify denoising actually uses multiple samples; reference-only fallback must not pass as a noise-reduction result.

### F3 — align correctly, measure uncertainty, honor FP32

> Audit GlobalAlign.kt, NormalSystem.kt, CellFlow.kt, Pyramid.kt, ReferenceScoring.kt and their Reconstruction/Pipeline integration. The global solver was recently rewritten and must be independently verified. Normalize source/reference pyramids by exposure and propagate both frames' noise through green averaging/filtering/interpolation. Use standardized robust residuals in coarse search, fitting and confidence. Verify ONE affine photometric Jacobian with cross-axis terms, conditioned solves, held-out improvement and full global-transform application at cell positions.
>
> Correct pyramid termination/scales, partial cells, support/border overlap and coarse-to-fine lookup. Refine local translation first; implement conditional local affine with explicit parameter storage, coordinate evaluation and residual/conditioning tests to fulfill the full plan. Do not label translation-only cells piecewise affine. Recompute uncertainty/confidence after vector or regularization changes. Export calibrated displacement uncertainty in quad-pixel units separately from dimensionless confidence.
>
> Wire gyro metadata/intrinsics through the bundle with timestamp and axis conventions. Integrate clipped covered intervals, handle reverse intervals, rotate about the principal point, and reject invalid synchronization instead of extrapolating. Missing optional gyro/intrinsics uses an explicit image-only path. Reference features must measure all-plane clipping, exposure/noise-aware sharpness and actual pose centrality; distinguish timestamp tie-breaking from pose. Missing AF/AE information is unknown, not a fabricated good score.
>
> Trace strict-fp32 through all image calculations, normal accumulation, solves, confidence and coordinate updates. Float-to-Double input rounding is not FP32 arithmetic. Keep any FP64 path separate; metadata timestamps can retain their proper integer/Double representation. Add synthetic independent renderers for fractional motion, rotation about off-center intrinsics, affine shear, exposure variation, flat/periodic regions, motion boundaries, odd sizes and gyro windows. Verify non-degrading residuals, uncertainty calibration and thread determinism; avoid tuning thresholds around broken radiometry.

### F4 — make DNG admission and output physically coherent

> Audit DngAdmission.kt, BurstDngWriter.kt, manifest metadata resolution and repack integration. Support the declared uncompressed RAW subset including ordinary EXIF IFD metadata and RAW SubIFD layouts, or reject unsupported layouts precisely. Validate strip/sample format, predictor, linearization/correction requirements, endian handling, bounds, lengths and overflow before allocation. Honor calibration inherited from the appropriate parent IFD. Do not silently ignore mandatory image corrections.
>
> Resolve exposure/ISO from DNG EXIF or explicitly checked manifest fallback; detect conflicts instead of inventing defaults. Admit valid per-frame black/white differences after individual normalization, while rejecting incompatible camera identity, origin, geometry or calibration. Preserve color calibration rather than inventing identity matrices.
>
> Write exposure/ISO in EXIF, use valid unsigned rationals for long exposure, and correctly declare dimensions, CFA, black/white, active area/default crop, orientation and calibration for native output. Keep scaled geometry disabled until F5's lattice contract is verified. Omit misleading global NoiseProfile when spatial merge variance cannot be represented, export variance diagnostics, and record provenance. Test externally authored fixtures and inspect generated tags with ExifTool: our writer/readback alone is insufficient.
>
> Cover 10-second and fractional exposures, odd active-area origins, parent-IFD calibration, changed per-frame black/white, truncated strips, invalid tags, overflow, missing required calibration and metadata conflicts. Record any unavailable external decoder verification as pending, not passed.

### F5 — replace inconsistent SR with a matched reconstruction operator

> Audit PsfSr.kt, PhaseHistogram.kt and all SR orchestration/cache/output code. Define the discrete source-observation operator A explicitly: CFA plane offsets in full-RAW coordinates, latent scale/origin, reference-to-source flow and its inversion, PSF units/support, sensor sampling, interpolation weights, borders and normalization. Derive A-transpose from exactly the same weights. Do not subtract flow evaluated at an unrelated source cell or truncate bilinear adjoint positions to integers.
>
> Add dot-product adjoint and finite-difference gradient tests FIRST, including fractional translations, spatially varying flow, anisotropic blur, cropped borders and both 1.5x/2x. Production GPU equivalence requires output-owned gather; if a small sparse matrix is used as a test oracle, it must independently verify the same operator. Apply source validity/clipping, heteroscedastic noise, motion/flow confidence and consistent robust objective weights. Evaluate each candidate iterate before accepting it, associate history with the evaluated image, and restore the actual best iterate. Test objective/returned-image agreement rather than filtering residual history.
>
> Replace fake optical-MTF and uncertainty proxies. Estimate PSFs from calibrated edge/blur evidence; validate known kernels, honor covariance scaling/support and enforce trust. Build local per-plane phase/conditioning diagnostics across distinct frames; never pool unrelated regions as proof of support or require 2x to pass a separate 1.5x histogram. Unknown optical support means native fallback. QMambaBSR AdaUp is a learned feature-conditioned kernel, not a formula for our PSF; do not attribute this deterministic operator to the paper.
>
> Qualify 1.5x before 2x against independently rendered latent truth and native-plus-static-upsampling baselines. Unsupported regions may only retain labeled native-derived support; globally unqualified bursts output native dimensions. Forced SR is experimental and never bypasses memory/safety limits. Verify scaled CFA offsets, active/default crop and calibration provenance before scaled DNG output. Include negative tests: integer-only bursts, local phase degeneracy, noisy base, moving occluders, untrusted PSF and brightness changes without genuine added detail. Keep strict FP32 arithmetic and deterministic updates.

### F6 — exhaustive closure and desktop handoff

> Re-read every handwritten production/test/build/CLI file in tools/burst-reconstruction-desktop against the updated plan, including issues beyond the 25 recorded findings. Produce an explicit coverage inventory. Inspect resource lifetimes, concurrency, worker override/reset, memory estimates/allocation bounds, repeated reads/copies, output atomicity, stale artifacts, parser errors, cancellation, numerical edge cases, comments and documented unsupported behavior. Fix in-scope defects with independent regressions; do not substitute cosmetic rewrites for correctness.
>
> Run the complete desktop suite and CLI workflows on small synthetic bundles and the user's available real bursts without modifying originals. Compare fresh/resumed and threads 1/N; exercise each stage, native and qualified/forced SR. Validate output DNG externally where tools are available. Report noise reduction, accepted support, ghosting/alias behavior, reprojection objective, runtime and peak memory, distinguishing measured quantities from estimates. A small crop does not establish full-resolution/device qualification.
>
> Close each audit finding only with source and test evidence in the progress log; list any still open. Update README commands, supported DNG/correction policies, precision/format contracts and exported GPU fixtures. Record algorithm/schema/tuning versions and hashes. Freeze a CPU conformance baseline only once required gates pass. No GPU correctness/performance claims without phone runs.

## Future prompts — do not run before desktop qualification

### G1 — native GLES and headless parity

> After F6 passes, port the verified native CPU stages into a separate GLES 3.1 processor and add the headless Android instrumentation/host push-run-pull-compare workflow before camera UI integration. Inspect and reuse existing lifecycle/xyz-gyro work instead of duplicating it. Port unpack/validity, reference metrics, pyramids, global/local flow and uncertainty, both gather passes, propagated variance, repack and result schema. Use the exact CPU coordinate/weight/decision contracts and exported fixtures. Support crop, reference, stage, tuning, repeats and structured failures; no full reconstruction on phone CPU. Query limits, bound allocations, retain FP32 sums, use explicit barriers and output-owned gather. Validate all CFA phases and partial grids, corruption detection, fresh/resume semantics and repeated resource cleanup. Produce native DNG/maps from supplied phone bursts and compare actual pixels with cached desktop results. Do not enable production capture or SR yet.

### G2 — qualified SR GPU port

> After G1 native parity and F5/F6 SR qualification, port the exact tested forward/adjoint, PSF, local support/eligibility and iteration acceptance rules to GLES gather passes. Preserve distinct masks/variance and scaled CFA geometry. Run GPU adjoint/parity tests and supplied qualified/unqualified bursts headlessly. Validate 1.5x first, then 2x. Measure FP16 alternatives only against strict-FP32 baselines; do not change the algorithm to hide parity failures. Enforce memory/watchdog gates even in forced mode. Produce labeled scaled DNGs only when supported; preserve native fallback. Do not run desktop reconstruction on the phone.

### G3 — capture integration and Mali release gates

> After headless parity, integrate immutable burst-job metadata, exact-once Image ownership, xyz gyro windows, cancellation and failure handling behind a feature flag. Keep normal/bracketed HDR behavior unchanged. Write one merged DNG and optional JPEG from the same merged CFA, with truthful fallback/provenance and atomic final files. Test construction failure, cancellation, exceptions, context loss and success for ownership/resource leaks. Qualify on the actual connected Mali device: record renderer/driver, per-pass and end-to-end times, upload/readback, peak memory, repeated 2/8/15/30-frame behavior, thermal/watchdog limits and precision. Run ExifTool plus available independent RAW developers. Keep SR experimental until quality, interoperability and performance gates pass. Do not claim untested GPU families are qualified.

## Removed and retained scope

- Removed obsolete module/scaffold creation prompts 1 and 1A; F1–F6 repair the existing implementation instead.
- Consolidated old prompts 2–9/8A into G1 and G3; native reference/alignment/merge requirements remain in F2/F3 and G1.
- Consolidated old prompts 10–12 into F5 and G2; scaled-DNG requirements remain.
- Folded generic audit/benchmark prompts into F6 and G3 to avoid repeated unbounded review sessions.
- No source files, tests, datasets or historical audit evidence are removed by this document.
