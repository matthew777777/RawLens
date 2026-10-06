# SR Vulkan rewrite plan: 1:1 port of the CPU linear chain + JAMY-L

Status: plan only. No code changed. CPU behavior must stay IDENTICAL;
the rewrite ports the CPU chain to Vulkan compute as GPU float32 with
tight tolerances — NOT literal bitwise.

Repo root used for every path below:
`/Users/monikamalinowska/AndroidStudioProjects/RawLens`
(All evidence paths are absolute under that root. Prior inventory refs
"prior result 1..4" arrived as bare completion markers with no inspected
evidence, so every claim below was re-grounded by direct file reads.)

## 0. Source of truth (read these first, in this order)

CPU linear chain (the oracle; DO NOT change behavior):

1. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrMergeJob.kt`
   - `mosaicChain` (eager) and `mosaicStream` (streaming): the canonical
     stage order. `buildMovingFrame` = unpack → align-or-reject →
     robustness-or-reject → support-gate → precision. `mergeFrame` =
     GAT guide → analytic covariance. `kernelNetSwap` = analytic→learned
     swap shared by CLI + streaming.
2. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrBayerMerge.kt`
   - `merge` + `accumulateFrame`: Jamy-L Alg. 4 verbatim + `utils.divide`
     parity (`EPS = 0.0`, exact-zero gate), `CHROMA_SIGMA_MPY = 2.0`
     latch guard (`1.0` = reference verbatim).
3. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrAlignment.kt`
   - `fftGrey`, `circularPad`, `pyramid`, `alignPair`/`alignBurst`
     (coarse-to-fine: upscale → block match L1-finest/L2-coarse → ICA
     refine), `flowAtNearestInto` (nearest-tile, raw-unit vectors).
4. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrRobustness.kt`
   - `referenceStatsFromPacked`, `movingStatsFromPacked`,
     `evaluateWithStats` (Dogson warp, s1/s2, threshold, 5x5 local min),
     `accumulate` (plain per-quad Rc sums).
5. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrCovarianceGuide.kt`
   - `guide` (GAT variance-stabilized guide), `noiseTables`.
6. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrKernelCovariance.kt`
   - `covariance` (merge consumes covariance, interpolates + inverts per
     pixel). Reference law: STEERABLE + LINEAR.
7. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrKernelNetAniso.kt`
   - `sigmaFor` (auto-sigma clamp [1, 2]), `precisionFor` /
     `precisionForPackedKernelOnly` (learned swap, per-pixel analytic
     fallback).
8. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrDeadLaneInpaint.kt`
   (`MAX_RING = 3`, fixed perimeter order, pre-inpaint reads only) and
   `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrChromaFromLuma.kt`
   (sigma-1.0 7-tap separable, `GUIDE_EPS = 1e-4`, G untouched).
9. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSuperResolutionSettings.kt`
   - `planSrOutputDims` (floor-to-even shared grid), `RawSrLinearScale`,
     `RawSrMosaicScale`. `MosaicSrReconstructor.LINEAR_SCALE` (√2) in
     `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/MosaicSrReconstructor.kt`.
10. `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/RawSrTuning.kt`
    - `fromReference` (SNR scan), `alignmentConfig()` (tile ∈ {16,32,64}
      raw px), `forSnr`.

Current GPU path (to be rewritten; alignment stages are KNOWN-STALE —
`VkRawSrProcessor.kt` carries a "Checkout port" comment saying GPU
alignment predates the FFT-grey/valid-pyramid/block/ICA front end):

- `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/VkRawSrProcessor.kt`
  (orchestrator: `processPacked`, `process`, `execute`)
- `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/VkCompute.kt`
  (`VkImage`/`VkArena`/`VkSession`/`VkBound`/`VkProgramCache`)
- `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/java/com/matthew/rawlens/SrVulkan.kt`
  (unified native `srvulkan` facade), `RawSrGpuOutput.kt`,
  `RawSrGpuScheduling.kt`, `UploadBuffers.kt`, `GpuRawAmazeInput.kt`
- Canonical shader sources:
  `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/assets/shaders/rawsr/*.glsl`
  (21 files); staged Vulkan variants + SPIR-V + `manifest.json` under
  `/Users/monikamalinowska/AndroidStudioProjects/RawLens/app/src/main/assets/spirv/`;
  transform `tools/srvk_shader_transform.py`, check
  `tools/srvk_spirv_check.py`; module `tools/sr-vulkan/` (`PARITY.md`,
  `README.md`, `build.gradle.kts`); parity script
  `tools/parity_sr_vulkan.py`.

Desktop mirrors:

- `/Users/monikamalinowska/AndroidStudioProjects/RawLens/tools/linear-sr-desktop/src/main/kotlin/com/matthew/srdesktop/LinearMain.kt`
  (CPU oracle chain + `--backend vulkan` mirror; `--cpu-gat-cov`,
  `--no-inpaint`, `--no-cfl` A/B flags; `--dump-fields` f32 dumps)
- `/Users/monikamalinowska/AndroidStudioProjects/RawLens/tools/mosaic-desktop/`
  (CPU streaming mirror of `runSrMosaicDng`)

## 1. Unified shared-core modules (new files + CPU refactor, zero behavior change)

Goal: one formula, two instantiations. New `RawSrCore*` pure modules hold
the exact scalar math; CPU call sites delegate to them (verified by
existing tests @ 0-tolerance where they currently assert exactness);
shaders transcribe the same formulas in float32.

### 1a. New files (all under `app/src/main/java/com/matthew/rawlens/`)

| New file | Contents (pure, no Android/GL) | CPU donor (delegate, don't duplicate) |
|---|---|---|
| `RawSrCoreSampling.kt` | `flowTileIndex(lr, tileSize)` (= `int(lr//tile)`), `robustnessQuad(lr)` (= `min(int(lr//2-0.5))` + the one-quad shift used by `sampleRobustness`), `covarianceGuideCoord(source, guideW/H, w/h)` (= `source*(guide/raw)-0.5`), `sourceCenter(out, factor)` (= `(p+0.5)/factor`), `flowLookupPos` (= `(qx+.5)/factor`), `robustnessSamplePos` (= `(p+.5)/factor/2-1`) | `RawSrBayerMerge.accumulateFrame` sampling block; `RawSrAlignmentField.flowAtNearestInto` index math |
| `RawSrCoreKernel.kt` | `accumulateTap(z or P, d, r, sample, chromaZScale, channel)` → `(w*r*c, w*r)` with `z = dᵀPd`, `w = exp(-0.5*max(z,0))`; `chromaZScale(s) = 1/(s*s)`; `divide(num, den)` with `EPS = 0.0` exact-zero gate + non-finite→0 + fallback flag | `RawSrBayerMerge` tap loop + finalizer; `MosaicSrReconstructor` twin |
| `RawSrCoreRobustness.kt` | Dogson warp residual, color distance, s1/s2 scaling, threshold `t`, 5x5 local-min reduction, `accumulateRc` (finite-sanitized plain sums), `support = 1+Rc` | `RawSrRobustness.evaluateWithStats` + `accumulate` |
| `RawSrCoreKernels.kt` | Structure-tensor → eigendecomposition → `selectionAxes` (linear law) → covariance assembly; `kDetail/kDenoise/flatSigma/detailFloor` blend law | `RawSrKernelCovariance.solve` (keep `solveIso`, HARD law, quirks intact) |
| `RawSrCoreGuide.kt` | Per-phase GAT stabilize + quad average; noise-table resolve (`alpha`/`beta`, ModelClass) | `RawSrCovarianceGuide.guide` + `noiseTables` |
| `RawSrCoreAlign.kt` | L1/L2 block-match cost, ICA refine step (incl. transcribed tile-8 sampler-clamp / tile-64 no-clip + misaligned-window quirks), `auxiliaryReliable`, `upsampleFlow` NEAREST/BILINEAR/BICUBIC (Keys a=-0.75, align_corners=False, edge-clamped) | `RawSrAlignment.blockMatchL1/L2`, `refineIca`, `upsampleFlow` |
| `RawSrCoreFinish.kt` | Inpaint ring enumeration order (top row, bottom row, open sides top-to-bottom, rings 1..3) + `sum/count`; CFL 7-tap weights `KERNEL`, `GUIDE_EPS` guard, two-pass order | `RawSrDeadLaneInpaint`, `RawSrChromaFromLuma` |

Keep `RawSrTuning`, `planSrOutputDims`, `BayerPattern.colorAt` /
`cellOrdinals` as-is (already shared); shaders take them as uniforms /
constants, never re-derive.

### 1b. CPU refactor steps (each step: delegate → run tests → commit; behavior IDENTICAL)

1. Extract `RawSrCoreSampling.kt`; rewrite the `RawSrBayerMerge` and
   `MosaicSrReconstructor` sampling call sites to call it. Proof:
   `RawSrBayerMergeTest`, `MosaicSharpnessTest`,
   `MosaicSrInstrumentedTest` green with unchanged assertions.
2. Extract `RawSrCoreKernel.kt`; delegate both merge finalizers.
   Proof: same tests + `StackerNearestParityTest`.
3. Extract `RawSrCoreRobustness.kt`; delegate `evaluateWithStats` /
   `accumulate` bodies (keep sharding in the caller).
   Proof: `RawSrRobustnessTest`, `RawSrFrameRejectionTest`.
4. Extract `RawSrCoreKernels.kt` + `RawSrCoreGuide.kt`; delegate
   `RawSrKernelCovariance` / `RawSrCovarianceGuide`.
   Proof: `RawSrCovarianceGuideTest`, `RawSrTuningTest`,
   `RawSrKernelNetAnisoTest`.
5. Extract `RawSrCoreAlign.kt`; delegate `RawSrAlignment` cost/refine/
   upsample helpers (keep `alignPair` orchestration + quirks comments).
   Proof: `RawSrFlowUnitsTest`, `RawSrFftTest`, `HdrBracketAlignerTest`.
6. Extract `RawSrCoreFinish.kt`; delegate inpaint + CFL.
   Proof: `RawSrChromaFromLumaTest`, dead-lane tests.
7. Forbid re-divergence: add a unit test asserting the CPU merge calls
   the Core entry points (e.g. via a counting test seam or by
   codegen-diff of the delegated blocks), and extend
   `tools/parity_sr_vulkan.py` to include the 7 new files in the
   byte-identical set (§5 `tools/sr-vulkan/PARITY.md` update).

Non-goals for the CPU side: no algorithmic change, no float↔double
change, no loop reorder, no new fallback. `chromaSigmaMpy = 1.0` must
still restore the reference-verbatim path; `EPS` stays `0.0`.

## 2. Shader rewrite list (stage → file, with sampling rules)

Canonical sources live in `app/src/main/assets/shaders/rawsr/`; the
`tools/sr-vulkan` build stages `.vk.glsl`/`.staged.glsl` + `.spv` into
`app/src/main/assets/spirv/` via `tools/srvk_shader_transform.py`
(checked by `tools/srvk_spirv_check.py`, manifest `spirv/manifest.json`).
Rewrite = replace each `.glsl` body with a 1:1 transcription of the
named `RawSrCore*` formula; keep entry/uniform conventions and the
transform pipeline unchanged.

### Canonical CPU op order (from `mosaicChain`/`buildMovingFrame` + `LinearMain.run`)

Per moving frame: unpack → (LSC unless applied) → `fftGrey` on the
UNSHADED plane → `alignPair` (circular-padded ref pyramid vs unpadded
mov pyramid) → reject gates → `evaluateWithStats` → `judge` support
gate → GAT `guide` → `covariance` → optional KernelNet swap → merge
accumulate. Then: reference accumulates LAST (r=1, zero shift) →
normalize → inpaint → CFL. Rc = plain per-quad sums; support = 1+Rc.

### Stage → shader map

| # | Stage | Shader file (`shaders/rawsr/`) | Transcribes | Sampling / contract rules |
|---|---|---|---|---|
| 0 | normalize | `preprocess` (exists; keep, re-verify) | ` RawSensorUnpacker.unpackNormalized` + LSC state | `v=(code-b)/(W-b)` per-phase black, frozen shading; unclamped; bit-exact vs CPU normalize (integer-derived floats) |
| 1 | FFT grey | `fft_stage.glsl` + `fft_remap.glsl` (NEW, landed) | `RawSrAlignment.fftGrey` | MUST match CPU grey: same window/FFT/dc handling; tolerance §6 (achieved worst ~5e-7, VkFftGreyParityTest). Current `bayer_quad_gray.glsl` (plain quad average) is NOT the alignment input — retire it from the align path (keep only if covariance still needs plain gray; it does not — covariance consumes the GAT guide) |
| 2 | circular pad | `circular_pad.glsl` on the GPU path (host pad on CPU fallback) | `RawSrAlignment.circularPad` | Ref padded to tile multiple; moving NOT padded |
| 3 | pyramid | `pyramid_downsample.glsl` (rewrite) | `RawSrAlignment.pyramid` + `gaussianKernel1d` | Jamy-L [1,2,4,4] schedule, separable Gaussian, exact kernel taps as uniforms; level count exactly 4 |
| 4 | block match | `block_match.glsl` (rewrite) | `RawSrCoreAlign` L1 (level 0) / L2 (coarse) | `radiusAt`: 1 at finest, `searchRadius` else; seeded from upsampled flow; tile sizes `[ts,ts,ts,ts/2]` |
| 5 | ICA refine | `lk_refine.glsl` (rewrite) | `RawSrCoreAlign` refine, `lkIterations` (=3), incl. tile-8/tile-64 quirks | Must reproduce quirks (sampler clamp vs zero-fill; tile-64 no-clip + misaligned window) — port quirk-for-quirk, comment each |
| 6 | inter-level upscale | `flow_upscale.comp` (NEW) | `upsampleFlow` | BILINEAR default (checkout default); support NEAREST + BICUBIC via uniform; Keys a=-0.75, align_corners=False, edge-clamped |
| 7 | flow consistency | `flow_consistency.glsl` (rewrite or RETIRE) | `alignPair` has NO fwd/bwd consistency pass | CPU `alignPair` runs one directional pass; the current GPU fwd+bwd+consistency is a deviation. Decision: retire the reverse pass + this shader unless a documented A/B keeps it (see Open Q1) |
| 8 | GAT guide | `linear_guide.glsl` (rewrite) | `RawSrCoreGuide` | Per-phase GAT stabilize + quad average from RAW codes; same `alpha`/`beta` resolve incl. ModelClass fallbacks |
| 9 | kernel covariance | `kernel_covariance.glsl` (rewrite) | `RawSrCoreKernels` | STEERABLE + LINEAR default; uniforms for `kDetail/kDenoise/flatSigma/detailFloor/dTh/dTr/kStretch/kShrink`; exact-flat + non-finite → isotropic fallback |
| 10 | KernelNet swap | host-side upload (no shader; keep current `kernelNetProvider` design) | `precisionForPackedKernelOnly` + `sigmaFor` | Upload learned field verbatim; per-frame analytic fallback; frozen per-burst readiness |
| 11 | robustness | `robustness.glsl` + `robustness_min.glsl` (rewrite) | `RawSrCoreRobustness` | Warp lookup = `flowAtNearestInto` at quad center `(2q+1)` raw coords, NEAREST tile, no blending; `u_mth_quad = mTh/2`; noise-LUT keyed by reference brightness; then 5x5 local min |
| 12 | Rc accumulate | `robustness_accumulate.glsl` (keep, re-verify) | `accumulate` | Plain sums, non-finite sanitized to 0 |
| 13 | merge accumulate | `merge_accumulate.glsl` (rewrite — the heart) | `RawSrCoreSampling` + `RawSrCoreKernel` tap loop | Per output px: `source=(p+.5)/factor`; flow = containing tile vector at raw source (`int(lr//tile)`, NEAREST, no blend; non-finite → skip+oob++); `r` = nearest quad with one-quad shift at `(p+.5)/factor/2-1` (`r==0` → skip); covariance bilinear at `source*(guide/raw)-0.5`, invert per px; 3x3 raw taps, CFA-routed, `w=exp(-0.5*max(z,0))`, R/B `z*=chromaZScale`; `num+=w*r*c`, `den+=w*r`; reference pass: zero shift, r=1 |
| 14 | clear | `clear_accumulators.glsl`, `clear_reference.glsl` (keep) | — | Zero-init; unchanged |
| 15 | finalize | `merge_finalize.glsl` (rewrite) | `RawSrCoreKernel.divide` | Reference-last add, per-channel divide, `den<=0` → 0, non-finite → 0 + fallback flag; `channelEvidence` bitpack (bit c ⟺ moving-only den > 0) |
| 16 | inpaint | `inpaint_dead_lanes.glsl` (rewrite) | `RawSrCoreFinish` inpaint | Rings 1..3, exact perimeter order, reads pre-inpaint planes only |
| 17 | CFL | `chroma_from_luma.glsl` (rewrite) | `RawSrCoreFinish` CFL | Same KERNEL decimals, `GUIDE_EPS=1e-4`, clamped borders, G untouched |
| — | hot pixels | `hot_mask.glsl`, `hot_inpaint.glsl` (RETIRED, already dormant) | Jamy-L defines no hot-pixel stage | Delete from manifest + assets (both CPU `mosaicChain` and GPU bypass them); keep `RawSrHotPixel.kt` + `RawSrHotPixelTest` for the non-SR path |
| — | unblocker | `unblocker_*.glsl` (A/B ONLY) | `RawSrUnblocker` (A/B, not base) | Keep out of the base pass sequence; base path has no unblocker fold |

GLSL precision rules (all rewritten shaders): `highp float` /
`float32`; no `mediump` in accumulation paths; FMA contraction allowed
(≈1ulp); `exp()` is the libm-correct single-precision exp (tolerance
absorbs libm differences, §6). No workgroup-shared accumulation that
would reorder the per-pixel op sequence.

## 3. Host processor + staging/scheduling structure

Keep the file skeleton (`VkRawSrProcessor.kt` orchestrator,
`VkCompute.kt` host types, `SrVulkan.kt` facade, `RawSrGpuOutput.kt`
descriptor) and rewrite the pass sequence inside `execute()` to the §2
order. Concretely:

- `VkRawSrProcessor.execute` new sequence per burst:
  1. Upload reference once (codes R16UI + normalized CFA R32F via
     `preprocess`), keep resident. Build ref FFT-grey → circular pad →
     pyramid (levels shared by all moving frames, like `refPyramid`).
  2. Reference GAT guide → analytic covariance (or KernelNet upload);
     reference linear guide + noise-LUT texture resident for the burst.
  3. Per moving frame (exactly one workspace live, released before the
     next — keep the current arena discipline): upload → FFT-grey →
     pyramid (UNPADDED) → directional `alignPair` equivalent (levels
     3→0: upscale → block match → ICA refine) → GAT guide → covariance
     (or KernelNet) → robustness + min → Rc accumulate → merge
     accumulate. Run the CPU `judge` support gate on readback
     robustness/flow stats and SKIP the merge accumulate for rejected
     frames (rejection stays a host decision; see Open Q2 for the
     stats-readback design).
  4. Reference-last accumulate (r=1) → finalize → inpaint → CFL.
  5. `consume(RawSrGpuOutput)` contract unchanged (same texture-ID
     fields; `releaseAccumulators` trim points move to the new
     last-reader passes: numerators die after finalize, denominators
     after inpaint).
- Staging: keep `VkArena` texture-per-pass discipline; add FFT scratch
  + pyramid level + flow-level ping-pong textures to
  `estimateTransientBytes`/`upscaleFits` (recompute the formula; the
  current `out*(5*16+2*4)+src*4` undercounts the new alignment
  workspace). Uploads stay banded (`READBACK_STRIP_ROWS = 256` style)
  via `UploadBuffers`.
- Scheduling: keep `RawSrGpuScheduling.slices` + `u_dispatch_offset`
  injection (`shaderSource` requires no `gl_WorkGroupID` use — new
  shaders must obey it). Slice budgets: keep phone defaults (64 for
  block_match/lk_refine, else 131072) until measured; `-Dsrvk.sliceBudget`
  override stays for desktop.
- `processPacked` keeps: reference-first requirement, tuning resolve
  (`tuningOverride ?: fromReference`), KernelNet provider with frozen
  per-burst readiness + auto-sigma ratio, `onFlow`/`onCovariance`/
  `onRobustness` diagnostic callbacks (extend with `onGrey` if cheap).
  `process` (CPU-oracle adapter, robustness bypass) stays for tests.
- Threading: writer-thread-confined, no re-entrancy, session owned per
  thread — unchanged.

## 4. Desktop + Android CPU-mosaic mirror wiring

- `tools/linear-sr-desktop` (`LinearMain.kt`): already the dual-backend
  harness — `--backend cpu|vulkan`, `--dump-fields` f32 dumps
  (`cpu_cov_*`, `cpu_flow_*`, `cpu_r_*`, `cpu_merge_*` vs `vk_*`),
  `--cpu-gat-cov`, `--no-inpaint`, `--no-cfl`, `--kernel-preset`,
  `--linear-scale`, `--reference-only`. Rewrite work:
  1. Add `--dump-grey` + `--dump-pyramid` (CPU `fftGrey`/levels vs GPU
     readback) and `--dump-align <level>` (per-level flow) to bisect
     alignment divergence.
  2. Add a `--compare` mode that runs BOTH backends in one invocation
     and prints per-stage max-abs-diff + tolerance verdict (exit
     non-zero on breach) — the primary local gate (§7).
  3. Keep `--backend vulkan` compiling against the rewritten processor
     throughout (no flag-day: stage-by-stage port, §7 step order).
- `tools/mosaic-desktop`: CPU-only streaming mirror; NO Vulkan backend
  (mosaic is CPU on the phone too). Wiring change: none, except it must
  keep passing unchanged (guards the §1 CPU refactor: if mosaic output
  changes by even 1 bit, the refactor regressed).
- Android (`RawCameraController` SR save path + `MosaicSrDngSaver`):
  no call-site change. Linear DNG continues through
  `VkRawSrProcessor.processPacked` (GPU) with CPU oracle fallback;
  Mosaic DNG continues through `mosaicStream` +
  `MosaicSrReconstructor.reconstructStreaming`. The only Android-side
  edits are the shared-file rewrites (§1–§3) plus test updates (§6).
- Parity: `tools/parity_sr_vulkan.py` + `tools/sr-vulkan/PARITY.md`
  gain the 7 new `RawSrCore*` files and any new shaders in the
  byte-identical set; retired shaders (`hot_*`, maybe
  `flow_consistency`, `bayer_quad_gray` from align) are removed from
  the resource list.

## 5. Exact doc fixes

| Doc | Fix |
|---|---|
| `docs/raw-sr-merge.md` | §2 grid: state the SR lattice is `planSrOutputDims` floor-to-even shared with mosaic (already §-revised; verify numbers). §§3–6: add explicit per-rule citations to `RawSrCoreSampling`/`RawSrCoreKernel` after extraction. Add a "GPU float32 tolerance" section pointing at the rewrite plan (this file) instead of any bitwise claim. |
| `docs/raw-sr-alignment-contract.md` | Add `fftGrey` → `circularPad` (ref only) → `pyramid` [1,2,4,4] → `alignPair` (L1 finest/L2 coarse, ICA, tile schedule `[ts,ts,ts,ts/2]`) as THE contract; document `flowUpscale` default BILINEAR (unified CPU/GPU default matching the reference `AlignmentConfig.flow_upscale_mode`; the interim NEAREST default was retired with the GPU 1:1 port, which transcribes the same dense bilinear upscale) + Keys a=-0.75; record the GPU rewrite as the implementation. Fix any paragraph still describing the old bayer-quad-gray/GLES aligner. |
| `docs/raw-sr-robustness.md` | Pin warp lookup (`flowAtNearestInto` at `(2q+1)`, nearest, no blend), `u_mth_quad = mTh/2`, 5x5 min, Rc plain-sum + support `1+Rc`; cite `RawSrCoreRobustness`. |
| `docs/raw-sr-packed-executor.md` | Verify phase-shift rule (`pattern.shifted(...)`, shifted form is the only valid input) matches the rewritten `merge_accumulate` `u_fc` contract; add CFA-routing worked example if missing. |
| `docs/raw-sr-unblocker.md` | Add header: A/B ONLY, not in the base path (CPU or GPU). |
| `docs/raw-sr-hotpixels.md` | Add header: retired from SR (CPU + GPU bypass); retained for the non-SR path. |
| `docs/raw-sr-merged-noise.md` | Verify `effectiveFrames(meanSupport, accepted)` + `scaleProfile` inputs (mean of `1+Rc`) against the rewritten Rc path; no formula change expected. |
| `docs/raw-sr-burst-planner.md` | No change (planner untouched); verify same-exposure gate `MAX_EXPOSURE_RATIO = 1.10` still stated. |
| `docs/raw-sr-skyking-reference.md` | No change (provenance only); verify the "deliberate deviation" list still matches the ported base. |
| `docs/raw-sr-4e-runlog.md`, `docs/sr-alignment-parity-followup.md` | Append a short "superseded by the Vulkan rewrite" note + link to this plan; do not rewrite history. |
| `tools/sr-vulkan/PARITY.md` | Add the 7 `RawSrCore*` files to the byte-identical list; update shader resource list (add `fft_grey`, `flow_upscale`, `pad_circular` if separate; remove `hot_*`; resolve `flow_consistency`/`bayer_quad_gray` per §2). |
| `tools/sr-vulkan/README.md` | Update `--backend vulkan` status + the new `--compare` gate + FFT/Vulkan-SDK prerequisites if the FFT needs them. |
| `tools/linear-sr-desktop/README.md` | Document `--compare`, `--dump-grey`, `--dump-pyramid`, `--dump-align`. |

## 6. Test updates with bounds

Principle: CPU tests stay EXACT (0-tolerance where they are today —
the §1 refactor must not move them); GPU-vs-CPU tests use tight
per-stage float32 tolerances, NEVER bitwise. All tolerances are
max-abs-diff over the full field unless noted.

| Test (existing → update) | Bounds / change |
|---|---|
| `app/src/test/.../RawSrBayerMergeTest.kt` | UNCHANGED assertions (1e-5f/1e-6f/0f as today). Guards §1b step 1–2. |
| `app/src/test/.../RawSrMergeJobTest.kt` | UNCHANGED. Guards chain order. |
| `app/src/test/.../RawSrRobustnessTest.kt`, `RawSrCovarianceGuideTest.kt`, `RawSrTuningTest.kt`, `RawSrFlowUnitsTest.kt`, `RawSrFftTest.kt`, `RawSrChromaFromLumaTest.kt`, `RawSrKernelNetAnisoTest.kt`, `StackerNearestParityTest.kt` | UNCHANGED. Guard §1b steps 3–6. |
| NEW `RawSrCoreParityTest.kt` (sr-vulkan unit tests) | Core-vs-donor exactness: Core entry outputs vs legacy CPU blocks, 0-tolerance on CPU (same doubles/floats, same order). |
| GPU stage tests (extend `tools/sr-vulkan/src/test/...` + `VkMergeParityTest`-style harness) | Per-stage CPU-vs-GPU max-abs-diff on a 512px forest/sea crop (fixtures under `app/src/androidTest/assets/rawsr/`): grey ≤ 2e-6; pyramid ≤ 5e-6; per-level flow ≤ 5e-3 px (ICA is iterative; residual ≤ 1e-4); GAT guide ≤ 2e-6; covariance ≤ 1e-5 (relative ≤ 1e-4 near razor axes); robustness r ≤ 2e-5; merge num/den relative ≤ 5e-5; final RGB ≤ 2e-4 (≈0.5 LSB at 12-bit white); CFL/inpaint ≤ 1e-6 vs CPU finish on the SAME GPU-merge input (isolate finish from merge error). Rc/support: exact-sum in float64 on readback, ≤ 1e-4 relative. |
| `MosaicSrInstrumentedTest.kt` | UNCHANGED (CPU mosaic is the refactor tripwire: 1-bit move = regression). |
| `RawSrMergeJobInstrumentedTest.kt`, `RawSrPreviewInstrumentedTest.kt` | Update only if the `RawSrGpuOutput` descriptor changes (it should not); add tolerance-based GPU assertions per the stage table above. |
| Burst522-class regression (truck-roof spikes) | Keep/extend the strong-edge agreement test: no 1px full-range spikes; max local deviation vs CPU ≤ 5e-4 on the documented crop. |
| KernelNet A/B | CPU `precisionFor` vs GPU-uploaded field: bitwise (same bytes uploaded); learned-vs-analytic selection unchanged. |

Tolerance calibration procedure: run `--compare` on forest + sea
fixtures at 512px and full-res; set each bound at 4× the observed
p99 stage diff, rounded up to 1 significant digit; record observed
numbers in the test file comment. Re-calibrate once after the final
stage lands; bounds only tighten afterwards.

## 7. Ordered build steps + verification commands + acceptance numbers

Build order (no flag-day; every step keeps all existing tests green):

1. §1a: add the 7 `RawSrCore*` files (pure additions; no callers yet).
   Verify: `./gradlew :app:compileDebugKotlin` (or the repo's unit-test
   task) + full unit suite green.
2. §1b steps 1–6, one step per commit: delegate → unit tests →
   `mosaic-desktop` output diff (must be EMPTY).
3. §2 shaders 0–3 (normalize verify, FFT grey, pad, pyramid): land +
   stage tests (grey/pyramid bounds). `LinearMain --compare` for grey
   only (add `--compare-stage grey,pyramid` filter).
4. §2 shaders 4–7 (block match, ICA refine, upscale, consistency
   decision): land + per-level flow bounds.
5. §2 shaders 8–10 (guide, covariance, KernelNet upload verify): land
   + guide/covariance bounds.
6. §2 shaders 11–12 (robustness, Rc): land + r/Rc bounds.
7. §2 shader 13 (merge accumulate): land + num/den bounds; reference
   pass + oob/evidence counters verified against CPU dumps.
8. §2 shaders 15–17 (finalize, inpaint, CFL): land + finish bounds.
9. §3 host rewrite: new `execute()` sequence + rejection-gate
   readback + `estimateTransientBytes` recompute + trim-point moves.
10. §4 desktop wiring: `--compare`, `--dump-grey/pyramid/align`.
11. §5 doc fixes. §6 new/updated tests + tolerance calibration.
12. Retire `hot_*` (+ `flow_consistency`/`bayer_quad_gray` per
    decisions); update `PARITY.md`, manifest, `spirv/` artifacts.

Verification commands (repo root):

```bash
./gradlew :tools:sr-vulkan:check            # parity (empty-diff) + unit tests
python3 tools/parity_sr_vulkan.py            # standalone parity
python3 tools/srvk_spirv_check.py            # SPIR-V/manifest check
./gradlew :tools:mosaic-desktop:installDist :tools:linear-sr-desktop:installDist
LINEAR=tools/linear-sr-desktop/build/install/linear-sr-desktop/bin/linear-sr-desktop
$LINEAR --in <dng-dir> --out /tmp/lin --crop 0,0,512,512 --compare
$LINEAR --in <dng-dir> --out /tmp/lin --crop 0,0,512,512 --backend vulkan --dump-fields /tmp/vk
```

Acceptance numbers (512px crop, forest + sea fixtures, `--compare`):

- All stage bounds in §6 met; exit 0.
- Final RGB max-abs-diff ≤ 2e-4; mean ≤ 2e-5; zero NaN/Inf; fallback
  mask agreement ≥ 99.9% of pixels; oob-count agreement exact per
  pixel (integer counters).
- Full-burst (8×12MP) Vulkan run completes; peak transient ≤ the
  recomputed `estimateTransientBytes`; no OOM on the reference device;
  CPU outputs bit-identical before/after (mosaic-desktop diff empty).
- All pre-existing CPU unit + instrumented tests green with
  UNCHANGED assertions.

## Open questions

1. **Flow consistency / reverse pass** — RETIRED: the reference
   `alignment.py` has no fwd/bwd consistency pass (verified: no
   reverse/consistency symbol; only coarse-to-fine `reversed(pyramid)`
   iteration), CPU `alignPair` is directional, and the GPU already runs
   the same single directional pass (`VkRawSrProcessor.align` →
   `alignDirectional`). `flow_consistency.glsl` is removed with the
   shader stage (it stays staged only until then).
2. **Rejection-gate placement**: CPU rejects frames inside the chain
   (`judge` on flow+robustness). GPU proposal: read back per-frame
   robustness/flow stats and skip merge-accumulate on the host.
   Acceptable readback cost, or should the gate move fully onto GPU
   with a scalar readback? Needs a measured stall number on-device.
3. **FFT implementation on GPU** — RESOLVED (2026-10-05): option
   (b) landed. CPU `fftGrey` cost ~6 s/frame on the host (49 s of a
   62 s VK run), so the mixed-radix DIT is ported 1:1 to compute
   shaders (`fft_stage`/`fft_remap`, same factor order and per-output
   summation order, float32 with CPU-double tables rounded to float —
   bitwise-vs-double is unachievable: neither target GPU family
   exposes shaderFloat64). The GPU FFTs the uploaded mosaic with the
   RGGB flip fused, the reference pads via `circular_pad`, and any
   per-frame GPU failure falls back to the host grey + upload
   (`VkRawSrProcessor.greyTexture`). 12 MP grey in ~0.4 s on desktop;
   `VkFftGreyParityTest` pins GPU-vs-CPU grey (worst ~5e-7).
4. **BILINEAR vs NEAREST inter-level upscale on GPU** — RESOLVED
   (2026-10-04): BILINEAR is the unified CPU/GPU default (pinned by
   `flowUpscaleDefaultsToBilinearLikeReference`), matching the reference
   `AlignmentConfig.flow_upscale_mode = "bilinear"`. The interim NEAREST
   default (kept during verification because the legacy GPU path was
   nearest-only and `VkRawSrProcessor` required NEAREST) is retired:
   the GPU 1:1 port transcribes the same dense bilinear upscale, and
   `JamyAlignmentParityTest.endToEndRecoversKnownShift` now measures
   port fidelity in the shipped default mode on both sides.
5. **Unblocker / chroma-gate / `accumulateNearest`**: stay dormant A/B
   (recommended, matches CPU base). Confirm no product path needs them
   in the Vulkan base.
6. **`bayer_quad_gray.glsl` after the rewrite**: unused if FFT grey
   replaces it everywhere. Delete, or keep for a non-SR consumer?
   (Verify no other caller: viewfinder `vf_*` shaders are separate.)
7. **KernelNet input on GPU**: current provider uses the PLAIN unpack
   (no LSC — documented approximation). CPU `kernelNetSwap` uses
   corrected samples. Keep the approximation (recommended; shape
   estimation is gain-invariant) or plumb corrected samples to the
   provider? If kept, record the bound it adds to covariance
   tolerance.

## Verification record (2026-10-03, parent session)

The workflow's verify/synthesis tail failed at runtime, so the parent
verified directly. Findings fixed during verification:

- `flowUpscale` default reverted BILINEAR → NEAREST (unified default;
  Open Q4 above), both trees + pin test.
- `VkRawSrProcessor.alignDirectional` coarsest-level `factorAt(level+1)`
  out-of-bounds guarded (u_scale unread without a prior).
- macOS any-Mac portability: `stageMoltenVK` Gradle task bundles
  `libMoltenVK.dylib` beside staged `libsrvulkan.dylib` with an
  `@loader_path` link; `SrVulkan.load()` extracts the sibling.
  Proven by `vkcheck` under `sandbox-exec` denying `/opt/homebrew` and
  `/usr/local`. Linux unchanged (system `libvulkan.so.1`).
- Burst IMG_20260927_134544_522 full-res native (paranoid flags):
  VK-vs-CPU mean 8.08e-4 / max 0.130 / p99.9 0.0209;
  CPU-vs-JAMYL mean 2.78e-4 (pre-rewrite 2.58e-4);
  CPU-vs-oldCPU mean 1.23e-4 (alignment-driver delta only; merge math
  identical per parity suites). VK blinds clean (maze below CPU/ref).
- Remaining gap: GPU alignment front-end (FFT grey, pad, pyramid,
  block/ICA, upscale) is still the legacy path — the known deferred
  stage and the dominant VK-vs-CPU diff driver. App suite 1066/0/2,
  sr-vulkan 52/57 (5 missing-captures environmental).
