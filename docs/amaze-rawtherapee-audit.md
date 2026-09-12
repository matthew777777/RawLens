# AMaZE comparison — 2026-09-08

Status: confirmed port defects corrected; **RawTherapee-identical output is not established**.
This is a source audit with targeted executable and device GPU checks, not a pixelwise comparison
against a running RawTherapee demosaicer.

Reference: [RawTherapee AMaZE at 498f623784e33fd9a7077fcd8937fe0734033366](https://github.com/RawTherapee/RawTherapee/blob/498f623784e33fd9a7077fcd8937fe0734033366/rtengine/amaze_demosaic_RT.cc).
The downloaded `dev` file was byte-identical to this pinned revision.
File SHA-256: `daaa23881cc08f5e9997103618002d134a4b557c6bca296c7b0194209c1b1600`.

## Corrected defects

| Location | Finding and correction |
| --- | --- |
| `AmazePipelineContract.kt` | `hvwt`, `nyq2`, `area`, and `pmrbint` call `site_x`, which reads `u_fc`, but their contracts omitted CFA_PHASE. Newly linked uniforms default to zero, so these passes incorrectly treated every even column as an R/B site. All four now receive the actual Bayer pattern. The previous unit test incorrectly required the omission for `hvwt`; replaced with the correct assertion and a check of Bayer-helper users. |
| `gradcd.glsl` | The clipping branch changed color differences but retained adaptive-ratio estimates in `dgintv/dginth`. Both upstream scalar and SIMD branches replace all four estimates with Hamilton–Adams values before calculating those errors. Restored those assignments. |
| `pad.glsl` | Upstream top strips use `32 - rr + top`, where the first tile has `top = -16`. The port used `32 - rr`. Corrected this to `16 - rr` outside corners and reproduced the distinct upstream corner addresses. |
| `pad.glsl` | For small crops, clamping unavailable reference-border taps could change CFA parity. Added reflection that retains parity. This is a defined RawLens extension: upstream's fixed-border addressing assumes larger input dimensions. |
| `nyq2.glsl` | Neighborhood reads ran at the outermost window texels, including invalid shared-memory indices. Restricted flags to the upstream eight-pixel interior. This also keeps downstream flagged-area reads within their valid domain. |
| `area.glsl` | The area sum traversed columns before rows. Restored upstream's row-first accumulation order to avoid this unnecessary floating-point difference. |

## Remaining differences that prevent an identity claim

1. **Update ordering is algorithmically different.** Upstream overwrites `hcd/vcd`
   during variance selection/bounding (scalar lines 599–686), and overwrites
   `hvwt` and `pmwt` during neighbor-based weight refinement (lines 974–979 and
   1241–1248). Later pixels can read already-updated predecessors. `bound.glsl`,
   `green.glsl`, and `pmrbint.glsl` read separate immutable input textures, so
   all pixels see the previous pass instead. This is more than float rounding.
   Matching it requires preserving the upstream dependencies; simply allowing
   concurrent reads/writes to one texture would create data races. Scalar and
   SIMD upstream builds also need to be distinguished when defining a target.

2. **Tile and Nyquist domains differ.** Upstream defaults to 160-pixel tiles
   including a 16-pixel skirt (128-pixel output steps); RawLens uses 1024-pixel
   output tiles with 48 pixels of context on each side. Upstream restricts
   Nyquist processing by each tile's detected bounding box and `doNyquist`;
   RawLens uses flags throughout the window. A large skirt does not establish
   equivalence when the algorithm includes ordered updates and tile-level gates.
   Upstream also conditionally calls `border_interpolate` when `border < 4`;
   RawLens has no corresponding final border stage.

3. **The delivered texture includes additional processing.** Both `final.glsl`
   and `final_display.glsl` whiten highlights using a 0.70–0.99 smoothstep, then
   apply the camera matrix. The whitening is not in the reference demosaicer
   and changes measured samples, including values above 1. It remains an
   existing RawLens rendering policy, not an AMaZE equivalence feature.
   A meaningful oracle comparison must observe RGB before this policy and
   before display mapping, with matching input balance and clipping thresholds.

4. **Precision and input conventions must match.** Scratch is float32, but
   the normal output is RGBA16F and the fused display output is RGBA8.
   Upstream produces float RGB in its 65535 scale. RawLens also pre-balances
   CFA and compensates the camera matrix. Exact comparisons require identical
   preprocessed CFA values, scale, `initialGain`/`clipPoint`, CFA origin, and
   border settings; matching the name AMaZE is insufficient.

The cardinal/diagonal formulas, Gaussian constants, half-grid offsets, existing
chroma stencil corrections, and measured-green preservation were inspected
against the pinned source. No additional mismatch was identified in those
specific formulas beyond the clipping branch above. This is not proof that
all GPU executions are correct.

## Verification

- `python3 tools/verify_amaze_scalar.py` — passed. Extracts and executes actual
  production GLSL scalar snippets as C++: edge/corner coordinates, parity-safe
  small-image reflection, and each clipping trigger plus the unclipped branch.
  It does not compile a GLES shader or execute the full algorithm.
- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` — passed.
- `git diff --check` — passed.
- Added `amazeBayerPhaseFollowsTranslatedCrops` to the GPU instrumented tests.
  It compares the same textured scene across all four shifted Bayer phases,
  away from borders, with 0.001 normalized maximum-error tolerance. This checks
  translation consistency of the port, not equivalence to upstream.
- Runtime GPU validation **passed: 8 tests, zero failures**, on `25080RABDG`
  (Android 16, serial `fe79feha9lmb6hhi`), in 7.78 seconds. Both APKs were
  installed directly and `am instrument` ran `SceneLinearColorGpuInstrumentedTest`.
  RawLens remains installed. The earlier USB installation restriction was resolved.
- The initial device run passed six of seven tests. The remaining test wrongly
  required negative output from negative RAW input despite AMaZE's intentional
  camera-RGB clamp; its CPU/GPU comparison already passed. Retained that comparison
  and HDR assertion, and replaced the invalid negative-range expectation with a
  separate controlled test: a 0.25 flat field and diagonal matrix (-1, 1, 8)
  must produce (-0.25, 0.25, 2) after the clamp. All eight tests then passed.
- Device coverage includes all four Bayer phases, red/blue channel identity,
  packed RAW versus CPU preprocessing, fused versus separate display output,
  Ultra HDR gainmap attachment, finite denoised output, CPU/GPU color conversion,
  and preservation of negative/HDR matrix output. These are synthetic regression
  checks, not comparisons against an independent RawTherapee demosaic oracle.

To complete a numerical parity assessment, use the pinned upstream implementation
as an independent oracle and compare pre-render float RGB on a supported device.
Include all four Bayer phases, saturated edges, dark/noisy patches, diagonals,
Nyquist textures, image borders, and tile seams. Resolve the ordered-update and
tile-domain differences before setting acceptance tolerances; do not disguise
algorithm differences by allowing a large tolerance.

## Ordered GLES reference parity — 2026-09-12

`tools/generate_amaze_gles_reference.py` generates
`app/src/main/assets/shaders/amaze/ordered_reference.glsl`: the pinned scalar
body, serial tile order, per-tile buffers, `precise` everywhere. The host oracle
(`tools/build_amaze_reference.py` + `tools/amaze_reference_host.cpp`, same
pinned source, checksum-pinned) produces the 24 fixtures in
`app/src/androidTest/assets/amaze_reference.bin` (4 Bayer phases x 64x66,
130x134, 258x262 scenes). `/tmp/host_verify_ordered.py` (throwaway probe, not
committed) translates the generated shader back to C++ and runs the fixtures:
**TOTAL=0 bit-exact mismatches on x86-64** (was 55630 before the fixes below).

Reference divergences from upstream desktop behavior (both applied to the
oracle that generates fixtures AND to the shader; documented UB, not behavior):
- Tile-init pad loops write a fixed 16 rows/cols past `rrmax`/`ccmax`,
  overrunning the 160-row tile arrays for edge tiles (`rrmax` in 144..160).
  Clamped to the rows/cols the mirror needs (`OOB_PATCHES`).
- `nyquist2` shares memory with the `cddiffsq` float array. Past the rows the
  Nyquist test clears and recomputes, stale rows keep float patterns that the
  area-interpolation window (up to 6 rows past the Nyquist bounds) reads as
  flag bytes, gating extra cells into the area sums (`NYQUIST2_CLEAR_PATCH`:
  full clear per tile when the Nyquist path runs).

Mobile-GPU accommodations (exact on x86, required on Mali-class dividers):
- Every float division uses a layered fixup: one FMA refinement step
  (`v1 = q + fma(-q,d,n)/d`, split with C++ precedence) followed by a
  verify-and-adjust pass (at most one ulp step, decided by the exact FMA
  residual against the exact `|d|*ulp` threshold with ties-to-even; the
  second round keeps the stepped value only if verified, else reverts to
  `v1`). Verified bit-identical to raw division at all 50 sites on x86
  (`CHECK_DIV`, 46 trail-less sites instrumented).
- `RawTherapeeAmazeInstrumentedTest#orderedGlesAgainstPinnedHostUpstream`
  dispatches tiles serially (one workgroup per dispatch, shared region 0) with
  zero-filled scratch/flag buffers, matching the oracle's `calloc` + serial
  order. Batched per-region dispatch cannot replicate serial staleness for
  edge tiles, which read previous-tile leftovers past their fresh range.

2026-09-12, measured on 25080RABDG (Mali-G615 MC2, GLES 3.2 r44p1):
- An FP sweep (65536 operand triples; `tools/fp_sweep.py` reproduces the
  host references) showed Mali `+,-,*,fma` bit-exact vs host, but `/` wrong
  on ~20% of inputs (usually 1 ulp; reciprocal-path flush on
  subnormal/overflow scales) and `sqrt`/`inversesqrt` wrong on ~19%.
  `precise` does not change divider behavior. One-step refinement alone left
  337/24-fixture samples 1-2 ulp off (rounding-boundary cases); the layered
  fixup converges to the correctly rounded quotient everywhere the residual
  and threshold stay exactly computable, and provably never underperforms
  one-step (every give-up keeps `v1`).
- `orderedGlesAgainstPinnedHostUpstream` + native bit-identical test:
  **BUILD SUCCESSFUL, mismatches=0/maxError=0.0 on all 24 fixtures.**
- Production 13-pass pipeline (`Gles31AmazeProcessor`), 4032x3024 synthetic
  CFA, denoise off: **~2.1-2.4 s/frame** end-to-end (prepareCpu ~40 ms,
  submitCpu ~50 ms, GPU ~2.0-2.3 s). Photo-develop rate, not viewfinder
  rate; the oracle shader's serial 1-wide dispatch is parity-only tooling,
  not a performance path. Temp probe files removed; `tools/fp_sweep.py`
  kept as the divider-characterization harness.
