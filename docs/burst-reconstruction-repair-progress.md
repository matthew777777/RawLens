# Burst reconstruction repair progress

Cumulative handoff log for prompts F1–F6. Each audit finding from
`docs/burst-reconstruction-desktop-audit.md` (algorithm version 3) is open,
partially addressed, or verified with test evidence. The audit stays
historical: entries below describe what changed and what proved it, and
never rewrite past findings as fixed without a passing regression.
Worktree edits after the audit report algorithm version 4.

## F1 — verification and resume trust (session 2026-09-15)

Baseline before edits (fresh, `--rerun-tasks --console=plain`):
`./gradlew :tools:burst-reconstruction-desktop:test` → **40 tests, 2 failed**:
`BundleIoTest.versionConflictRejected` (IllegalArgumentException at test line 29),
`ReconstructionTest.integerTranslationRecovered` (AssertionError, coverage 0.0).
This matches the docs' last reported repair run.

After F1 edits: **72 tests, 1 failed** (full `--rerun-tasks` run).
New suites: CacheResumeTest (14), CompareTest (7), TuningValidationTest (6),
CliOptionsTest (3); BundleIoTest +2 (9 total).
`git diff --check`: clean. No commit. No phone access.

Changed files (production):
`Main.kt` (allowed-flag sets per command; strict int/long/tolerance parsers;
`--tolerance-abs/--tolerance-rel`; ref-precedence help text),
`BundleIo.kt` (MiniJson rejects non-finite/overflowing numbers; parseTuning
reads 12 new fields with legacy defaults; manifest writes via ArtifactIo),
`StageCache.kt` (unchanged — key helper already correct; callers fixed),
`ResultComparison.kt` (rewritten: full inventory incl. status/integrity/
metrics/tuning/result; backend-timing exclusion policy; notes numeric-aware
compare; DNG first/worst loci + finite checks; stale `.tmp` rejected),
`Tuning.kt` (12 new fields + range validation),
`BurstContracts.kt` (PLAN_DEPARTURES record),
`Pipeline.kt` (upstream key chaining; stage-dir wipe on recompute; stale
scaled-DNG cleanup; atomic metrics/tuning/result; effective block in
result.json; workers/memBudget in metrics notes),
`Merge.kt` (gate constants now read from tuning),
`RobustWeights.kt` (baseWeight overload with explicit bounds),
`CellFlow.kt` (LocalSigma floor, joint gate and Huber delta now read from
tuning; acceptance formula unchanged in shape),
`BurstWorkers.kt` (effectiveCount telemetry).
Changed files (tests): `BundleIoTest.kt` (version-conflict mechanism repaired
without weakening; malformed-JSON cases), plus the four new suites above.

Manual CLI verification (installed dist, real 8-frame burst, crop):
import ok; two forced runs → `compare` exit 0; mutated merged.bin sample →
exit 1 with `first=8` locus; `run-stage --stage validate` executes only
`[validate]`, no DNG; `--threads banana`, unknown flag, `--tolerance NaN`,
`--ref 99`, malformed manifest → clear errors, exit 1.

### Finding status after F1

- 4 (`compare` compared no image data): **verified**. ResultComparison reads
  every inventoried DNG/bin/JSON artifact; exact integer/decision checks;
  configurable abs/rel tolerances; first/worst loci; backend exclusion policy
  documented in KDoc. Evidence: CompareTest (7 tests: identical pass, map/count/
  DNG-pixel mutations with loci, gyro-hash change, inventory add/remove,
  tolerance split) + manual CLI exit-code runs.
- 5 (resume ignores reference/upstream deps): **verified**. Keys now chain
  each stage to its predecessor; CLI `--ref` wins over manifest override
  (help text states it). Evidence: CacheResumeTest ref-change (downstream
  re-executes, stale `flow-1.bin` gone), cliRefBeatsManifest.
- 6 (cache omits gyro/output integrity): **verified**. Gyro already in
  inputHash (prior work); F1 added atomic metrics/tuning/result/manifest
  writes, stage-dir wipe on recompute, stale scaled-DNG cleanup, final-DNG
  removal recovery. Evidence: removedDng, interruptedStage (integrity +
  corrupt-JSON), staleScaledDng tests; versionConflictRejected repaired
  (conflicting schema built as raw JSON so the reader, not the strict
  constructor, is under test; plus schema-0 case).
- 20 (`run-stage` runs everything): **verified** (mechanism predates F1;
  F1 adds the missing tests). Evidence: runStageStopsEarly,
  runStageFlowStopsBeforeMerge (incl. resume reuses staged flow),
  unknownStageRejected.
- 25 (tuning unchecked/bypassed): **partially addressed**. Schema validation
  (versions, ranges, gate relations), 12 new snapshot fields with legacy
  defaults, effective-settings + departures record in result.json, truthful
  worker counts. Evidence: TuningValidationTest (6), effectiveBlock test.
  Left open: confidence-before-regularization recompute (F3 behavior),
  smoothstep reversed-edge GLSL port note (G1), requestedFrames/outputScale
  unconsumed (recorded as departure; G3 may consume).
- 1, 2, 3, 7, 8, 9, 10, 11, 12, 16, 17, 18, 19, 21, 22, 23, 24: **open**
  (out of F1 scope, untouched). Notes: v4 already contains structural work
  toward 13/14/15 (IRLS merge, per-plane counts, propagated variance,
  source-domain clipping) and toward 5/6/20 (integrity records, atomic
  ArtifactIo, stopAfter) — **partially addressed by prior work, not
  verified by F1** except where the suites above pin behavior.
- `integerTranslationRecovered` (flow coverage 0.0): **open, F3 owner**.
  The test drives `GlobalAlign.refineCoarse` + `CellFlow.estimate`
  directly (no Pipeline/cache/compare involvement); its (4,2) shift is
  phase-fair. Points at findings 7/8/21, not F1 code. F1 suites pass
  around it via reference-fallback paths.

### Next prerequisite (F2)

F2 needs the F1 baseline above plus a real-burst fixture with mixed
exposure/ISO for the radiometry identities; no F1 follow-up is pending
except keeping `integerTranslationRecovered` red until F3 fixes alignment.

## F2 — CFA radiometry and native merge (session 2026-09-15)

Baseline: F1 exit state (72 tests, 1 failed: `integerTranslationRecovered`).

After F2 edits: **95 tests, 0 failed** (full `--rerun-tasks` run).
New suites: CfaIdentityTest (6), MergeRegressionsTest (12),
DngAdmissionF2Test (5); RadiometryTest rewritten (5).
`git diff --check`: clean. No commit. No phone access.
Algorithm version bumped 4 → 5 (merge weights, noise domain, sharpness,
floor validity all changed; old caches invalidate via status check).

Changed files (production):
`Radiometry.kt` (normalized-domain `transformNoisePair(s, o, e)` → a=S/e,
b=O/e², black/white params removed; fallback rewritten as explicit
DN-declared calibration with the black-pedestal photon term;
`normalize` preserves below-black; exposure scales outside [0.5, 2.0]
rejected, not clamped),
`Reconstruction.kt` (noiseModels drops black lookup; `sharpCells`
replaced by `sharpNormQuad`: per-quad noise-normalized green energy;
`exposureScales` already rejected missing metadata — pinned by test),
`DngAdmission.kt` (dead `blackForPlane` removed with the signature change;
unpack/crop/admit untouched — already stored-pattern + unclamped),
`Merge.kt` (reference floor skipped for non-finite/clipped taps;
sharpness from flow-displaced, noise-normalized support with null→1
fallback),
`Pipeline.kt` (sharpNorm maps for all frames incl. reference; variance.bin
persisted; truthful `reference-unchanged`/`omitted-varying-support` noise
provenance labels),
`BurstDngWriter.kt` (`lensShading=none-applied;hotPixelMask=none`
provenance tokens; writer already omitted unjustified global profiles),
`QuadSplit.kt` (validity/correction policy KDoc only),
`BurstContracts.kt` (version 5 note).
Changed files (tests): `RadiometryTest.kt` (identity/inequality expectations:
halve/quarter under e=2, black-term presence, range rejection),
`SyntheticBursts.kt` (`gauss()` helper; default profile corrected to
normalized-realistic S=4e-4 — the old DN-magnitude default encoded the
wrong domain), `ReconstructionTest.kt` (sharp helper ported; 4 assertions
corrected where the test — not the code — was wrong: partial-grid indices,
exact-integer-edge acceptance, exposureFixture arithmetic, merge-error
bound now above the 0.0058 inverse-variance prediction).

Manual CLI verification (installed dist, real 8-frame burst, full frame):
import ok; full run 58 s, reference #4, 8/8 accepted; resume 12/12 cached;
self-compare ok; DNG tags coherent (4080x3060, GBRG, 0/65535, orientation);
`noiseProvenance=omitted-varying-support` on the merged output.
Note: per-frame coverages read ~0.16 (vs ~0.45 pre-F2) — expected direction:
the corrected noise domain predicts ~30x larger daylight variance than the
old DN-divided values, so confidence is stricter; acceptance is unchanged
and tuning/qualification of the new operating point belongs to F5/F6.

### Finding status after F2

- 1 (noise units/exposure direction): **verified**. a=S/e, b=O/e² with
  divide-by-range removed; DN fallback explicitly separate with the
  black term; below-black preserved; exposure range [0.5, 2.0] enforced.
  Evidence: RadiometryTest identities (halve/quarter, black-term,
  symmetry, rejections).
- 2 (double CFA shift): **verified** (convention already stored-pattern in
  v4; F2 pins it). Evidence: CfaIdentityTest all-patterns × all-origin
  parities with per-color constants, stored-GRBG-at-x=1 audit repro,
  cross-origin G1 identity.
- 3 (cell width inference): **verified** (stored spacing + `index()` already
  in v4; F2 pins it). Evidence: partial-grid lookup table incl. clamping.
- 13 (counts/fallback honesty): **verified**. Counts already came from
  actual positive weights in v4; F2 pins the audit repro shape with TRUE
  interior coords (accepted==1, fallback==1, merged==ref) plus
  zero-confidence and all-white saturation cases.
- 14 (clipping after normalization): **verified** (tap-level pre-scale
  rejection already in v4 `BurstObservation`; F2 pins it). Evidence:
  saturated-at-long-exposure rejected despite y-agreement (accepted==2),
  valid short-exposure highlight admitted, exact-edge tap semantics.
- 15 (latent/output noise honesty): **verified**. Propagated per-plane
  variance persisted (`variance.bin`) and restored on resume; global DNG
  profile omitted except true reference-only fallback, with truthful
  provenance labels. Evidence: calibrated-variance test (measured and
  propagated map both within 4x of 1/Σ(1/O_i), beating uniform averaging),
  variance-persistence + resume-compare test, derived-profile unit branches,
  uniform-run DNG tag absence.
- Noisy-reference bias (F2 prompt QMamba item): **verified**. With correct
  per-frame variances the gate admits clean evidence against a noisy base.
  Evidence: noisy-ref (σ.05) + clean sources (σ.005) merges to <50% of the
  base MSE with accepted≥2 (would be impossible under the old
  DN-normalized variances).
- Sharpness support (F2 prompt item): **verified**. Aligned,
  noise-normalized ratios; cross-exposure ratio ≈1 test; blur/sharp
  ordering test.
- `integerTranslationRecovered`: now PASSES as a side effect of the
  corrected noise domain (synthetic confidence calibration sane again).
  This does NOT close findings 7/8/21/23 — rotation, affine, exposure-
  normalized alignment, pyramid termination and reference-feature
  contracts remain untested fixed and stay **open for F3**.
- 7, 8, 9, 10, 11, 12, 16, 17, 18, 19, 21, 22, 23, 24: **open** (out of F2
  scope). Notes: admission already checks origins/camera identity (24
  largely satisfied in v4; per-frame calibration differences explicitly
  admitted + tested this session); reference-feature contracts (23),
  gyro/intrinsics (16), DNG subset/policy details (17–19) untouched.

### Next prerequisite (F3)

F3 owns alignment/uncertainty/precision (7, 8, 12, 16, 21, 23) against the
corrected F2 radiometry. Suggested first probe: re-run the F2 real-burst
result through any new alignment and compare coverage/residual deltas;
the F2 full-frame outputs under /tmp are scratch (not committed fixtures).

## F3 — align correctly, measure uncertainty, honor FP32 (session 2026-09-15)

Baseline: F2 exit state (95 tests, 0 failed; v5).

After F3 edits: **138 tests, 0 failed** (full `--rerun-tasks` run).
New files: `Fp16.kt`, `AnalyticScene.kt` (test renderer), `AlignmentTest`
(15), `GyroSyncTest` (10), `ReferenceFeaturesTest` (7), `PyramidTest` (5),
`Fp16Test` (3), `PipelineGyroTest` (2); `TuningValidationTest` +1.
`git diff --check`: clean. No commit. No phone access.
Algorithm version bumped 5 → 6 (reference scores, alignment numerics,
uncertainty additions all change behavior).

Changed files (production):
`Pyramid.kt` (required per-level propagated noise `(a,b)`, binomial
variance factor documented; termination guard kept),
`Reconstruction.kt` (`buildOne` normalizes by own exposure with
green-averaged models; reference features measure all-plane clipping,
own-model sharpness, gyro pose with timestamp fallback + provenance,
AF/AE unknown = 0.5, path-length gyro blur),
`GlobalAlign.kt` (alignFloor in cost/steps; clipped-interval integration
with sorted dedup of invalid samples; `coveredFraction`; `pathLength`;
principal-point seed kept; FP32 throughout, `strictFp32` removed as
meaningless there — emulation happens at pyramid build),
`CellFlow.kt` (combined ref+src per-level sigma; FP32 NormalSystem solves
in both modes; conditional local affine with held-out + effect-size gates
and honest model labels; confidence/uncertainty recomputed after vector
changes; affine-aware reverse consistency; calibrated quad-pixel
uncertainty export; FP16 output rounding under strict),
`BundleIo.kt` (`BurstIntrinsics` + per-frame AF/AE/lens fields with
round-trip and invalid-rejection),
`Pipeline.kt` (exposure/noise-aware pyramid builds; real intrinsics with
coverage-gated seeding from both frames' windows; pose provenance note;
flow-affine/flow-unc persistence),
`Tuning.kt` (`minGyroCoverage`), `BurstContracts.kt` (v6 note),
`ReconstructionTest.kt` (helpers mirror production normalization).

Manual CLI verification (installed dist, real 8-frame burst, full frame):
62 s run, reference #4, 8/8 accepted; resume 12/12 cached; self-compare
ok; DNG tags coherent; ~9% affine cells with 0.024-quad median
uncertainty on the street scene; `pose=timestamps` (no gyro files —
honest fallback).

### Finding status after F3

- 7 (alignment ignores exposure/source noise): **verified**. Pyramids are
  exposure-normalized per frame with propagated (a,b) at every level;
  LK residuals divide by combined ref+src predicted noise. Evidence:
  exposureVariationStillAligns (e=0.5 vs e=1 recovers (2,1)), PyramidTest
  noise-propagation math, per-level SigPair plumbing.
- 8 (affine geometry/propagation): **verified**. Global Jacobian already
  carried cross terms (v4); F3 adds conditional local affine (conditioned
  FP32 solve, even-fit/odd-gate, effect-size floor) with explicit model
  labels, full global-transform seeding per cell (pre-existing), affine-
  aware reverse consistency, and recomputed confidence/uncertainty.
  Evidence: affineShearLabeledHonestly (mixed fixture), rotationCellsUse-
  Affine (cross-term exercise), translation-identity labels asserted.
- 12 (strict FP32 not strict): **verified**. All image arithmetic,
  accumulation and solves are FP32 (NormalSystem) in both modes; the old
  Double accumulation paths are deleted, not branched. strictFp32 now
  means FP16 storage emulation at pack/pyramid/flow-map boundaries
  (Fp16 unit tests incl. halfway-even, overflow, subnormals) plus shared
  FP32 math. Evidence: Fp16Test, strictModeAgreesWithinOracleTolerance,
  flowDeterministicAcrossThreads (bit-exact per mode).
- 16 (gyro disabled/integration): **verified**. Intrinsics in the bundle
  (validated, rejected when invalid); coverage-gated seeding from both
  frames' windows; deduped sync (duplicate spike no longer leaks);
  principal-point rotation; path-length blur. Evidence: GyroSyncTest (10:
  clipped/reverse/degenerate/coverage/oscillation/principal-point/
  image-only/round-trip/legacy/AF-AE), PipelineGyroTest (seeded flags
  end to end, image-only fallback).
- 21 (pyramid termination/scales): **verified** (early-stop guard predates
  F3; F3 pins it). Evidence: PyramidTest termination/no-repeat/size
  agreement/anti-alias interior/odd sizes via oddSizesAlign; span derived
  from built depth (refineCoarse require + test helper).
- 23 (reference features vs contracts): **verified**. All-plane clipping
  (red-only and single-green discriminators), own-model sharpness
  ordering, gyro pose with timestamp fallback + provenance, unknown
  AF/AE = 0.5, tie-break stays temporal. Evidence: ReferenceFeaturesTest
  (7). Brightness bias/gain per cell (plan "may") explicitly deferred.
- `integerTranslationRecovered`: passes (F2 side effect, re-verified in
  the 138).
- 9, 10, 11, 17, 18, 19, 22: **open** (F4/F5 scope, untouched). 24/25:
  prior F1/F2 states stand; F3 added `minGyroCoverage` validation.

### Next prerequisite (F4)

F4 owns DNG admission/output (17, 18, 19, 24). It can start immediately:
no F3 follow-up is pending. Suggested: use the hand-built TIFF helper
pattern from DngAdmissionF2Test for externally-authored fixtures, and
the `/tmp/f3-full` street result for ExifTool audits (scratch only).

## F4 — DNG admission and output coherence (session 2026-09-15)

Scope: `DngAdmission.kt` (reader validation, parent-IFD calibration,
manifest resolution), `BurstDngWriter.kt` (forward-matrix carry,
provenance tokens), manifest sidecar calibration, repack integration.
Algorithm version 7. Suite: 138 tests/0 failures -> 163/0
(`DngAdmissionF4Test`, 25 new). `git diff --check` clean.

Reader validation (all pinned by independently authored hand TIFFs, never
our writer): opcode lists (51008/51009/51022) detected and recorded, not
silently assumed applied (rejecting them would refuse the phone corpus);
separate planar, non-unit predictor/sample format, LinearizationTable,
non-16-bit depth, bad/missing CFA tags, non-Bayer CFAPattern values
(explicit IAE permutation check before `fromDngCfa`, which threw a bare
`UnsupportedOperationException`), BlackLevel counts other than 1/4,
missing ColorMatrix1/AsShotNeutral, truncated strips and out-of-range
strip offsets (both checked before bulk copies), and 100000x100000
dimensions (Int `multiplyExact` throws before allocation) all reject.
Parent-IFD calibration inheritance verified via a thumbnail-IFD0 +
RAW-SubIFD fixture: ColorMatrix1/illuminant inherit from the root, the
CFA IFD's own AsShotNeutral takes precedence. EXIF-IFD-only
exposure/ISO honored (1/250s, ISO 400).

Manifest resolution (`resolveExposure`): EXIF wins when present; checked
sidecar fills gaps with per-frame EXIF/MANIFEST provenance recorded on
the admit stage (`exposureSource`) and `DecodedBurst`. Conflicts
(exposure, ISO, geometry, CFA) throw. New in F4: zero/placeholder
sidecar exposure/ISO on the fallback path throw instead of filling 0;
`BurstManifestFrame` carries `colorMatrix1`/`asShotNeutral` lists
(round-tripped through `toMap`/`fromMap`, empty = undeclared for older
manifests), cross-checked within 1e-6 (covers the writer's own rational
quantization on re-admitted derived DNGs); `import` populates them from
the admitted frame. `admit()` now also requires matching ColorMatrix1
and CalibrationIlluminant1 across frames (per-frame black/white and
neutral still admitted after individual normalization). `import`
rejects EXIF-less DNGs (verified on CLI against an exiftool-stripped
copy: `DNG EXIF lacks ExposureTime; radiometry cannot be reconstructed`)
instead of inventing 1/60s + ISO 100.

Writer coherence: 10s exposure survives the unsigned RATIONAL path
(4294967290/429496729, no signed wrap); 1/659s round-trips within 2.6e-6
relative; ForwardMatrix1 carried (absent -> absent, never invented);
scaled-geometry write stays a self-consistent DNG with
targetDims/sensorOrigin/targetPattern provenance (F5 owns scaled-mode
semantics). `derivedNoiseProfile` already omits unjustified global fits
(returns non-null only for single-frame); no code change needed.

Manual CLI verification (installed dist, fresh real 8-frame Xiaomi
burst `/tmp/f4-burst` -> `/tmp/f4-out`, full frame): import records the
real sidecar calibration (non-identity matrix, neutral [0.537, 1,
0.619]); run reference #4, 8/8 accepted; resume 12/12 cached;
self-compare ok; admit record `exposureSource=[exif x8]`. ExifTool on
`merged-native.dng`: `Validate: OK`, zero warnings; ExposureTime 1/659
+ ISO 50 in the ExifIFD; ColorMatrix1/2, ForwardMatrix1, AsShotNeutral,
D65/Standard-A illuminants carried; GBRG CFA dims/pattern, full
ActiveArea/DefaultCrop, Rotate-90-CW orientation; NoiseProfile absent
as designed; provenance records `algorithm=7`, `opcodeLists=51009`
with `lensShading=none-applied`.

### Finding status after F4

- 17 (input validation/coverage): **verified**. Unsupported TIFF/DNG
  layouts reject precisely (list above); strip bounds and dimension
  overflow are validated before allocation/copies; manifest zero-
  placeholders rejected. Evidence: DngAdmissionF4Test negative suite
  (16 reader tests on externally authored fixtures).
- 18 (color pipeline correctness): **verified** for admission/output.
  Parent-IFD inheritance, EXIF-IFD resolution, per-frame black/white
  admission, cross-frame + manifest calibration cross-checks, forward-
  matrix carry, unsigned exposure rationals. Evidence: SubIFD/ExifIFD/
  round-trip tests + ExifTool `Validate: OK` on real output. Full
  colorimetric end-to-end (rendered pixels vs reference developer)
  remains out of scope for the desktop twin.
- 19 (metadata/provenance integrity): **verified**. Exposure/ISO from
  EXIF or checked manifest fallback with conflict detection and
  per-frame provenance; sidecar calibration declared at import and
  re-checked at admission; no invented identity matrices, exposure,
  ISO, or NoiseProfile. Evidence: resolveExposure unit tests,
  admitBurst manifest-provenance integration, real-burst admit record.
- 24 (reader/writer soundness): **verified**. Reader/writer round-trip
  exposures (10s, fractional), forward matrices, scaled geometry;
  writer output re-admits under the same validation. Evidence:
  writer-coherence tests + ExifTool audit.
- 9, 10, 11, 22: **open** (F5/F6 scope, untouched).

### Next prerequisite (F5)

F5 owns scaled-output geometry/CFA-phase semantics and any remaining
SR-mode work. It can start immediately: F4's scaled-geometry test pins
current provenance-carrying behavior only. Suggested: decide the
scaled ActiveArea/DefaultCrop convention before changing the writer.

## Pre-F5 shadow/sea repair batch (session 2026-09-15, v7 → v8)

Prompt: user-reported patchy shadow noise (noisy base retained in shadows,
clean elsewhere) + requirement to properly merge sea bursts; audit against the
complete corpus before F5. Used BOTH bursts (per correction): STREET existing
8-frame `IMG_20260914_*` (Downloads/Burst test) and SEA true 8-frame subset
`RAW_1788587993*–7999*` from `Sea (1).zip` (30-frame zip, first 8 for speed).

Baseline (v7, 163 tests/0 failures):
- STREET `/tmp/sea-burst` → `/tmp/sea-out`: ref #4, 8/8 accepted, coverage ~0.16,
  global residuals 0.25–0.76, count mean 7.86, fallback 0.002, quality 0.236,
  flow conf mean 0.12, propagated var mean 6.08e-06. Effective denoising ~1.7×
  var (reference 54% weight with 7 sources) instead of 8×.
- SEA `/tmp/sea-burst-true` → `/tmp/sea-out-true`: ref #4, **1/8 accepted**,
  coverage ~0.03, cell med 0.36–0.49, global residuals 1.33–2.17, count 1.0,
  fallback 1.0 — pure reference fallback, zero denoising.

Root causes (see `docs/burst-reconstruction-paper-conformance.md` D1/D1b/D2/D3):
tracking confidence (`C_hess·C_res·C_fb·C_tex·C_bounds`) gated native merge weights
and frame acceptance, so flat/shadow/sea-rock pixels (low Hessian/texture, low
residual) were downweighted or globally rejected. None of P1–P5 supports this;
PhotonCamera Wiener (`N²/(excess²+N²)`), QMamba consistency, GAS native-view and
equivariant global prior all require flat/static averaging with consistency gating.

Changes (production):
`CellFlow.kt` (FlowField.mergeConf + `mergeWeightAt`, `scoreVectors` exports
`C_res·C_fb·C_bounds`, residual-based frame acceptance `med<maxUsefulResidual`
irrespective of coverage, FP16 quantize of merge map),
`Merge.kt` (weights by `mergeWeightAt`, quality now reflects denoising contribution),
`Pipeline.kt` (persists/reads `flow-merge-*.bin`, SR meanUnc from calibrated
uncX/uncY quad-pixels, untrusted-PSF → NativeOnly fail-safe),
`PsfSr.kt`/`Reconstruction.kt` (diagnostic-only MTF/pooled-phase KDoc, quad-pixel
uncertainty docs), `BurstContracts.kt` (v8), plan §9.1 (w_align = mergeConsistency).
Tests: `AlignmentTest.flatRegionRejectsHonestly` → `flatRegionMergesHonestly`
(zero-motion accept, low coverage, large unc, high mergeConf); new
`ShadowMergeTest` (5: mergeWeight split incl. legacy fallback, flat 4-frame >2×
MSE win, low-coverage good-residual acceptance, quad-pixel uncertainty gate pin,
flat PSF untrusted).

After: **168 tests, 0 failures** (`--rerun-tasks`).
- STREET v8 (`/tmp/street-burst-v8` → `/tmp/street-out`): 8/8, quality 0.756
  (was 0.236), var mean 1.42e-06 (was 6.08e-06, ~4.3× lower, near 1/Σ(1/O)
  prediction), mergeConf 0.71–0.75 vs track 0.12–0.13.
- SEA v8 (`/tmp/sea-burst-v8` → `/tmp/sea-out-v8`): **8/8** (was 1/8), count 7.12,
  fallback 0.007 (water/motion rejected locally), quality 0.587, var 2.58e-06,
  mergeConf 0.49–0.54 vs track 0.03–0.04.
`git diff --check`: clean (see handoff). No commit. No phone access.

Finding deltas: D1/D1b/D2/D3 (new, beyond original 25) fixed+verified as above;
F1–F4 states stand except `flatRegionRejectsHonestly` superseded with justification.
F5 prerequisites remaining: matched A/Aᵀ + adjoint/gradient tests, local per-plane
phase/conditioning, calibrated MTF/regional PSF with support enforcement, 1.5×→2×
qualification vs latent truth. Unsafe SR stays NativeOnly; forced SR remains
experimental. F5 NOT started.

## F5 — matched SR operator + calibrated gates (session 2026-09-15, v8 → v9)

Baseline: v8 exit (168 tests/0 failures) + street/sea v8 runs.

Targets supplied: `/Users/monikamalinowska/Downloads/photon_street.dng` +
`/Users/monikamalinowska/Downloads/sea_photon.dng` (both 4080×3060 GBRG, bl 0,
wh 65535 — same stored geometry as our outputs, direct window comparison).
Bursts: STREET 8-frame `IMG_20260914_*`, SEA full 30-frame `RAW_17885879*`
from `Sea (1).zip` (30 frames; full-res 30-frame merge OOMs the desktop heap —
known all-packs-resident limit, F6/ops tiling work; crops used per request).

Changes (production): new `SrOperator.kt` (offsets, kernelForScale, invertRefPos,
bilinearTaps, forwardTaps, dot-checks); `PsfSr.kt` Backprojection rewired
(matched weights, BurstObservation clipping/validity, mergeConf-at-reference,
native-fallback source gating, evaluated-iterate best-restore), PSF estimator
inverted calibration (EDGE_K=0.55, stride 1, noise gate, MIN-clamp trusted),
kernel support honored; `SrEligibility.decide` new signature (trusted PSF,
PsfMtf>0.02, LocalPhase tiles, quad-pixel unc; brightness/pooled gates deleted);
`Pipeline.kt` PSF noiseSigma plumbing + new gate call + pattern/fallback into
runBackprojection; `BurstContracts.kt` v9. Tests: `SrOperatorTest` (5, FIRST),
`SrQualificationTest` (7), `srGatesBehave` + uncertainty fixtures rewritten.

After: **180 tests, 0 failures** (`--rerun-tasks`). `git diff --check` clean.
No commit. No phone access.

Evidence:
- Synthetic: adjoint rel err <1e-4 (fractional/varying/aniso/borders, 1.5x+2x);
  gradient vs finite-diff <2%; sr15 MSE beats native+bilinear on phase-diverse
  gratings; occluder holds native (<0.15); integer-only/huge-blur → NativeOnly;
  known-blur σ0.8/1.5 recovered <0.55px, noisy flat untrusted.
- Street full-res native (v9): ref #4 8/8; PSF σ≈0.35 trusted (was 1.5).
  Center crop (1784,1274,512,512): coverage ~0.7, med ~2-3, SR15 Eligible
  (local phase 0.94/16 tiles, MTF 0.64), history 8.52→4.10→2.74.
  Real detail @384 quads: SR15 dP90 0.0783 vs photon-up 0.0550 (+42%) vs
  native-up 0.0530 (+48%), flatN flat (0.0101 vs 0.0093) — genuine detail, no blowup.
  Native center vs photon @512: dP50 0.01322 vs 0.01372 (4% softer), flatN
  0.01056 vs 0.01087, gMean identical 0.0549.
- Sea crops (30-frame burst, 512² windows): TL med 0.09-0.26 → 29/29 merge
  (ours cleaner than photon: flatN 0.0138 vs 0.0225, 11-30% softer detail —
  denoising/detail tradeoff, no invention); center med 4.3-6.6 → 0/29,
  reference-only (dynamic water, truthful, sharp-not-mushy); BR med 1.4-1.9 →
  29/29 with local fallback (rocks merge, water gated). Sea BR vs photon:
  dP90 0.0113 vs 0.0127, flatN 0.0107 vs 0.0131.
- Outputs: `merged-native.dng` + `merged-sr15.dng` (eligible crops) ExifTool
  Validate OK; resume 12/12 cached; self-compare ok.

Mushy-moving/rocks disposition: moving water keeps reference-native (sharp,
labeled fallback) instead of averaged mush/ghost; static rocks merge 29-30
frames with motion gating; SR adds measured detail only where tracking + phase
+ MTF support it (street center). Dark untrackable rocks stay native — honest,
not invented. Remaining softness vs photon (~4-30% gradient) is the documented
denoise/detail tradeoff, not aliasing or fallback failure.

### Handoff: F5 READY (desktop CPU)

F5 items above are implemented + tested + measured on street/sea crops.
Production SR activates only on qualified bursts/crops (street-center 1.5x yes;
sea dark/dynamic native fallback). 2x operator covered by adjoint tests but NOT
production-qualified on real bursts here. G1/G2 prerequisites: 30-frame full-res
tiling (OOM), output-owned latent-gather GLES port of SrOperator weights,
FP16 validation, Mali qualification. F6: full-matrix + thermal/memory + decoder sweep.

## v10 HF-detail tuning (session 2026-09-15, v9 → v10)

Prompt: preserve/recover HF luma detail, everything too smooth/mushy.

Sweeps (street-center 512² crop, 8 frames; ghost = |G1−G2| textured, flatN =
flat-mask std): Tukey 6/8, sharpMax 8 → no detail/no-ghost change (native at
resampling ceiling); motion (2.5,5) → softer + noisier (rejected). PSF probe
(step edges, independent renderer): K=0.55 under-reads σ ~35% → K=0.8.
Locked: EDGE_K 0.8, backprojectionIterations15x default 5 (history +30%/iter at
3), BundleIo legacy default synced to 5, ALGORITHM_VERSION 10. Native gates
unchanged. `TuningValidationTest.legacySnapshot` failure from the default bump
fixed at the stale parse default (not by weakening the test).

After: **180 tests, 0 failures**. Street-center SR15: dP90 0.0892 (v9 SR 0.0783,
photon-up 0.0550, native-up 0.0530), flatN 0.0107, ghost 0.039; history
9.5→6.2→5.0→4.3→3.9 over 5 iters. Sea TL/BR native re-verified (honest
SR-ineligible fallback). `git diff --check` clean. No commit.

## v11 30-frame memory (session 2026-09-15, v10 → v11)

Prompt: full-res 30-frame merge OOMs; fix leaks/pressure/usage — 30 frames must merge.
Reference: PhotonCamera PR #179 (RealJohnGalt, peak-memory; engineering patterns
only — band-by-band streaming, free-right-after-use, no heap duplicates; none of
its GPU code applies to this CPU twin).

Diagnosis (12MP quads 2040×1530 ≈ 12.5MB/plane-array): 30 resident packs ≈1.5GB
+ full sharp maps ≈372MB + full merge first/out arrays ≈200MB + pyramids/DNG
buffers → OOM on default heap (reproduced: OutOfMemoryError at Merge.kt:69).
No handle/stream leaks found (DNG via readBytes, ArtifactIo temp+finally,
daemon worker pool, run-scoped decode memo); pressure was pure residency.

Changes (production):
- New `PackStore.kt`: admit-stage spill (pack-NN.bin LE planes, header dims),
  halo-sized band reads with clamped origins, deterministic spill rule
  (>12 frames or >512MB packs), TILE_QUADS=128. Spill files live in
  stages/admit/ (F1 integrity covers them; recompute wipes them; crash orphans
  wiped by next recompute — documented, not leaked).
- `QuadPack.yOrigin` (default 0; existing paths bit-identical) + band-aware
  `BurstObservation.sample` + `QuadSplit.sampleBanded`.
- `GatherMerge.mergeTiled` + resident/store `TileSource` factories: per-tile
  two passes, tile-local first-arrays, on-demand sharp bands (exact interiors
  via +1 extended green). Legacy `merge()` untouched (≤12-frame fast path).
- `DecodedBurst.qw/qh` (always valid) + `packStoreDir` + `packFull`/`packBand`;
  all `d.packs[i]` consumers migrated (align/flow/psf sequential transients,
  referenceFeaturesImage per-frame).
- `Backprojection.SrMeta` + `reconstructStreamed` (shared per-frame core +
  iteration driver): one pack transient per frame per iteration. SR sharp maps
  dropped in both paths (dead weight — operator never consumed them; ~370MB).
- Post-merge slimming (PR #179 free-after-use): quality/counts/residual/variance
  dropped from memory after persistence (files stay); repack/SR keep planes +
  fallback only (~62MB saved full-res).
- `ResultComparison`: pack-*.bin compared byte-exact (raw layout, not BRBM).
- `BurstContracts.kt` v11. DNG writer already row-streams (no heap duplicate —
  matches PR #179 writeBuffer fix; verified, no change).

Tests: `PackStoreTest` (5: round-trip, bands incl. edge clamps, truncation/
header rejection, spill boundaries, halo), `MergeTiledTest` (6: bit-exact vs
resident on static/motion/clipping/noisy-ref/large-displacement-halo/tiny+odd
tiles, plus 30-frame denoise+support), `SrStreamTest` (2: bit-exact streamed SR
incl. fallback), `CompareTest.spilledPackBytesComparedExactly`.

After: **194 tests, 0 failures** (`--rerun-tasks`). `git diff --check` clean.
No commit. No phone access.

Evidence (all under `-Xmx2g`):
- Sea 30-frame full-res (`/tmp/sea-burst-v11` → `/tmp/sea-out-v11`): completed
  6:28 (was OOM), ref #14, **30/30 accepted**, count mean 25.84, fallback 0.12%,
  quality 0.52, spill 1498MB recorded, DNG ExifTool Validate OK, means match
  photon sea (0.0846 vs 0.0845).
- Partial-resume: deleted merge maps → resume re-spilled + recomputed merge
  only (12/12 cached otherwise); full resume 12/12 cached.
- A/B street-crop parity: compare ok. Self-compare clean apart from macOS
  .DS_Store/RawTherapee .pp3 droppings (pre-existing F1 strictness, environment
  noise — out-dirs must be kept clean; not loosened).
- Known remaining: admit transient peaks ~1.5GB during initial decode+spill
  (fits 2GB, proven); DNG re-read per pixel-needing run (pre-existing waste,
  not a leak); 30-frame full-res SR stays memory-gated unless eligible
  (honest NativeOnly); per-run 1.5GB spill I/O (~seconds on SSD).

## v12 squares + detail + learn loop (session 2026-09-15, v11 → v12)

Prompts: plastic/oil-painting mush, water squares, soft horizon trees; new paper
BIPNet (see conformance §10); run 8 frames on sea + Street Left/Right with
single-frame comparison every time; learn-with-each-run loop (all prior todos kept).

Findings & fixes (production):
- Squares (PROVEN): per-cell-constant displacement + mergeConf stepped at
  16-quad borders — count-map boundary/interior change ratio 1.19 and quality
  steps 10.4x on sea output. Fix: bilinear flow/weight resampling across cell
  centers (`FlowField.sampleFlow`, affine-neighbourhood nearest fallback) in
  both merge paths (hoisted per-pixel) + `sampleTranslation` in SR inversion.
  Post-fix sea-8 count ratio 1.11; jittered-flow seam test bounds
  boundary/interior error < 1.5 end to end.
- Sharpness linearization: sqrt ratio → linear (same [0.25,4] clamp), both
  merge paths. Sharp/blur-mix fixture pins detail tracking (≥0.85x all-sharp
  energy); static sweeps had shown sqrt at its ceiling.
- Test-helper calibration bug (found while baselining): `bundleDir` dropped
  NoiseProfile (wrote null) → fallback heuristic variances → pipeline rejected
  everything (support 1.0) on synthetic bundles. Now persists declared
  profiles (honest sensor calibration round-trip).
- `single-compare` CLI (`SingleCompare.kt`): merged vs reference-single detail
  P50/P90, flat noise, |G1-G2| ghost, support + advisory verdicts + JSON
  (--out). Handles cropped results (same-window reference crop).
- Learn loop (`TuningFeedback.kt`): `--feedback <result|file>` replays stored
  bounded steps onto manifest tuning + one measurement-driven step; `--learn`
  writes feedback.json (SingleCompare summary + SR convergence + provenance).
  Ghost evidence vetoes widening (BIPNet-AGU rule); safety gates never tuned;
  cache keys hash effective tuning. Rules/clamps/determinism unit-tested;
  pipeline round-trip tested (learn → feedback → adjusted deterministic rerun).

Tests: sampleFlow blend/center/affine-fallback units, jittered seam bound,
sharp/blur preference, TuningFeedbackTest (7), SingleCompareTest (3: baseline,
crop, learn round trip + forced-hunger knob move), CompareTest pack-bytes case.
After: **207 tests, 0 failures**. `git diff --check` clean. No commit.

Run matrix (8 frames, -Xmx2g, full-res, v12 binary):
- sea-8 r1 --learn: ref #4 8/8, support 7.07, detailKept 0.89, ghost x0.90.
- sea-8 r2 --feedback r1 --learn: adj tukey+1/sharp+1 (4.685→5.185, 4→5),
  provenance logged; detail 0.89, ghost 0.90 (scene-limited, stable).
- streetL r1 --learn: 8/8, support 7.92, detail 0.87, ghost 0.81.
- streetL r2 --feedback: same quality (0.87/0.81), adj applied.
- streetR r1 --learn: 8/8, support 7.94, detail 0.88, ghost 0.82, flat ratio
  0.96 (reference is the cleanest frame; averaging near-best single ≈ parity).
- Flat-noise ratios ~1.06 on busy scenes: bottom-quintile mask is
  texture-dominated, understating sensor-noise reduction (propagated variance
  maps remain the principled noise claim); verdict renamed MARGINAL accordingly.
- Horizon-trees sea crop (30-frame burst): 30/30 accepted, coverage ~0.21,
  med ~0.8 — merges with local fallback; foliage softness is incoherent
  wind-motion texture (averages out honestly) + dark untracked SR fallback.

Plastic disposition: static-fusion knobs were already at ceiling (sweeps);
resampling/PSF blur is recovered by SR where eligible (street-center +62%
dP90 over photon-upsampled, v10). No synthetic grain or sharpening added
(deliberately excluded per no-texture-synthesis rule).

## v13 HDR+ two-band Wiener detail (session 2026-09-16, v12 → v13)

Prompt: plastic mush, water squares, soft horizon trees; study martin-marek
hdr-plus-swift/pytorch + supplied samples; fix ours (papers paused).

Learned (frequency.swift/.metal): 8×8-tile reference-anchored Wiener merge —
agreeing frequencies average, disagreeing keep reference; single cross-channel
weight (mid-mean); mismatch-scaled motion boost; Fourier subpixel refinement;
mismatch-weighted deconvolution; raised-cosine overlap + 4 shifted runs.

Findings & fixes (production):
- Maze root: per-plane independent weights let G1/G2 keep different frame sets
  (confirmed green-disparity class; app HdrRawMerge is mosaic-domain single
  weight — unaffected; unpacker CFA parity confirmed). Fix: joint quad weights
  (worst-plane motion/Tukey veto à la their channel rule) in both merge paths;
  joint clipping (any clipped plane vetoes the quad).
- Mush root: single-band robust average blends misaligned texture. Fix:
  two-band layer — binomial LOWs through the full joint machinery plus a
  green-joint reference-anchored HF correction (static boost averages HF,
  motion keeps reference HF). Weights keep unscaled shape (heteroscedastic
  ratios + caps behave as proven); propagation uses calibrated LOW_VAR_Q and
  linearized Wiener variance (map/pred = 1.23, honest).
- Squares: bilinear flow/weight resampling (v12) held; count-boundary ratio
  1.11 on sea-8 (was 1.19 pre-fix; quality-map steps are diagnostic-only).
- Tuning: hfBaseC=1.0 [0.5,8], hfMaxMotion=8.0 [1,25] (HDR+ 1..25 range);
  `BurstContracts.kt` v13. Learn loop untouched (thresholds still valid).

Tests: HfWienerTest (5: blur DC/impulse, wiener/motionBoost limits, static
detail ≥0.6 HF energy + denoise, occluder holds native exactly, flat DC
exact); existing calibratedVariance re-passes; tiled==resident re-pinned.
After: **213 tests, 0 failures**. `git diff --check` clean. No commit.

Run matrix (8 frames, v13 binary, single-compare every run):
- sea-8 r1 --learn: 8/8, support 7.00, detailKept 0.98 (was 0.93), ghost 0.94.
- sea-8 r2 --feedback: holds (all-zero steps — healthy, no hunger), same numbers.
- streetL r1: 8/8, 7.92, 0.90/0.87-ghost. streetR r1: 8/8, 7.94, 0.90/0.86.
- street-center SR15: eligible, history 9.29→3.68, dP90 0.0891, ghost 0.040.
- Horizon-trees sea crop: merges with local fallback (wind-incoherent foliage
  averages honestly; no invented texture — SR-ineligible dark, documented).
