# RAW-SR Bayer-direct merge contract (Prompt 4D)

Written **before** any merge implementation. The code must match this document;
any behavior difference is a bug unless recorded here as a deliberate deviation.

**Revision 2026-09-27 (reference-parity port):** the merge is a 1:1 port of
Jamy-L `merge.py` Algs. 4 (accumulation) and 11 (reference handling), CPU
oracle and Vulkan shader alike. The Stacker/SkyKing-derived extras that used
to live here — fallback cascade, accumulated-robustness overwrite, tap
censoring, highlight rolloff, burst-nearest ordering, flow smoothing,
motion-edge stop, Rc clamping — are REMOVED from the base path (the reference
defines none of them) and survive only as dormant, tested A/B helpers where
noted. Sections below describe the ported behavior; the September history
(§9 history note, §13) is kept for provenance.

References: Wronski et al. SIGGRAPH 2019 §5 / paper Alg. 1 (kernels),
Jamy-L IPOL 2023 `merge.py` Algs. 4 (accumulation) and 11 (reference handling),
`utils.divide` (normalization),
RawLens `docs/raw-sr-alignment-contract.md` (flow units/lookup),
`docs/raw-sr-robustness.md` (robustness maps, Rc), `docs/raw-sr-packed-executor.md`
(frame layout, CFA phase), `RawSrCovarianceGuide` (coordinate contract, units),
`RawSrKernelCovariance` (covariance packing), `RawSrPackedFrame.pattern`
(phase-shifted CFA), `docs/wronski-raw-zsl-super-resolution-plan.md` §7.6.

Attribution scope: accumulation geometry, kernel handling, normalization,
flow/robustness sampling, and the no-fallback divide are Jamy-L verbatim
(§§3–6, 8). The output lattice is a
deliberate grid deviation (shared √2 SR lattice, §2 — not Jamy-L's 2x): the
reconstruction is information-limited by the source kernels, so the denser
2x sampling added no detail at 4x memory (evaluated, then retired).
The dormant helpers (`accumulateNearest`, `ChromaParams`, `MIN_SUPPORT`,
`MOTION_EDGE_QUAD`, `SATURATED_REF_GUARD`) are Stacker/SkyKing-derived and
judged on their own tests, never on Alg. 4/11 fidelity.

## 1. Color space: ordinary linear camera RGB

Numerators are ordinary linear camera-RGB sums (`vec3`: R, G, B independently
accumulated); denominators are independent per-channel weights (`vec3`: R, G, B).
This is a deliberate deviation from the SkyKing accumulator, which stores
white-balanced `G, R−G, B−G` opponent channels restored by its finalizer:
copying that contract without its white-balance/finalizer pipeline would produce
incorrect colors (see `docs/raw-sr-skyking-reference.md`). No white balance,
no opponent transform, and no highlight reconstruction run inside the merge.

Per-frame merge samples are black-subtracted, white-normalized, lens-shaded
linear values from the existing normalize path (`raw/preprocess.glsl`
semantics), i.e. `v = (code − b) / (W − b)` with per-phase black levels and the
frame's frozen shading state. Unclamped (negatives preserved), matching the
robustness photometric domain convention except that shading IS applied here.
Exposure scaling is exactly 1: the burst planner's same-exposure gate
(`MAX_EXPOSURE_RATIO = 1.10`) is the v1 contract; no per-frame exposure
compensation inside accumulation.

## 2. Output grid and pixel centers

v1 output grid is native 1× (`scale = 1`): output pixel `(i, j)` (row, column)
is a RAW-sensor pixel at the reference crop's local coordinates, with center
`(j + 0.5, i + 0.5)` in RAW pixels. Output dimensions equal the reference crop
dimensions (even-sized; shared by all frames per the packed-executor contract).
The Mosaic SR target grid (√2 area scale, CFA-target reconstruction) is a
separate path (`MosaicSrReconstructor`) sharing this accumulation math.

The SR output grid (`RawSrLinearScale.SR`) is the shared √2 lattice both
paths plan via `planSrOutputDims` (floor-to-even each side — a 12MP source
lands at 5768×4326 ≈ 25MP, pixel-identical to the mosaic SR grid): output
pixel `(i, j)` centers on source `((j + 0.5) / √2, (i + 0.5) / √2)` in RAW
pixels. Kernels, flow, and robustness stay source-anchored (quad grid,
raw-unit distances, §§3–5 sample the source position); only the output
lattice changes, so every formula below reads with `p = (pixel + 0.5) /
factor`. Rc/support stay quad-shaped; the fallback/oob/support planes and
the DNG follow the output grid. The `outputScale` provenance tag records the
grid factor (1.0 or √2 ≈ 1.4142135623730951).

## 3. Flow direction and units

Flow is reference-anchored and stored in **RAW pixels** on a RAW-pixel tile
lattice (`RawSrTileFlow.dx/dy`, `RawSrAlignmentField.tileSize` in raw px —
the Jamy-L convention: its default FFT grey keeps full resolution, so its
flows are raw pixels). For output pixel `p`, the projected source position
in moving frame `n` is

`source_n(p) = (p + 0.5) / factor + flow_n(p)`,

where `flow_n(p)` is the containing tile's raw-unit vector with no blending
(`RawSrAlignmentField.flowAtNearestInto` via `RawSrCoreSampling.flowTileIndex`):
`px = int(lr_x//tile_size)`, reference-verbatim. Non-finite tiles skip the
pixel. There is no motion-edge stop in the base path. The `+0.5` terms are the
pixel-center convention: output pixel `p` integrates `[p, p+1)`, and the source
is addressed in the same continuous RAW-pixel coordinate system.

GPU twin: the flow texture stores **RAW-unit** vectors on the RAW lattice
(the 1:1 aligner tiles the raw grid, like the CPU chain), so the shader
applies `source = (p + 0.5) / u_upscale + shift` verbatim — the same
geometry with no unit conversion (retired: the pre-rewrite quad lattice
applied `+ 2·shift`). The CPU/GPU agreement gate (§12) covers the twin.

## 4. Covariance lookup coordinates, units, kernel exponent

Each frame's kernel field is its own `RawSrKernelCovariance.covariance` field
(stored per quad pixel), estimated from that frame's own variance-stabilized
covariance guide. The lookup is source-anchored: at projected position `source`,
guide coordinates are

`g = source · (guideSize / rawSize) − 0.5`,

the packed covariance entries are bilinearly interpolated at `g` (clamped to
the guide grid, sign-preserving `modf` fractions, row-then-column lerp), and
the interpolated covariance is inverted per pixel to the precision the
exponent consumes — reference `merge.py::accumulate` verbatim. Distances are
raw-unit (`dist = tap + 0.5 − source`, no quad conversion) and the kernel
exponent is

`z = d_rawᵀ · P · d_raw ≥ 0` (clamped at 0),

with kernel weight `w = exp(−0.5 · z)` (natural exponential per Jamy-L; no
additive floor). Non-invertible interpolated covariances (non-positive or
non-finite determinant) skip the pixel.

## 5. Sampling support and CFA phase

For each output pixel, the 3×3 RAW support around `center = floor(source)` is
visited (`center + {−1, 0, 1}²`), matching Jamy-L Alg. 4. Tap offsets use pixel
centers: `dist = tap + 0.5 − source`. Per-tap out-of-bounds taps are skipped
individually (never clamped into the image).

Each tap contributes **only to its own color channel**: the tap color comes
from crop-relative coordinates via the frame's shifted `pattern`
(`RawSrPackedFrame.pattern`), which already folds sensor origin + crop origin
(shifting again double-folds the phase and swaps R/B on odd origins). The
hardcoded `rggb` mapping in Jamy-L `merge.py` (`channel = i%2 + j%2`) MUST NOT
be used; all four CFA phases are supported (identical to the reference on
even-origin RGGB). Shared local CFA phase across frames is a packed-executor
admission requirement, so routing by moving-frame sensor color is consistent
with reference-frame color at the same local offset.

Per-tap accumulated contribution (channel `c` = tap color):

`num_c(p) += w · r_n · sample_c(tap)`,
`den_c(p) += w · r_n`,

where `r_n` is the frame's robustness from the nearest quad with the
reference one-quad shift (`min(int(lr//2−0.5))`, sampled at the pixel
center `s = (p + 0.5) / 2 − 1`), and `sample_c` is the normalized shaded
merge sample (§1) — reference-verbatim. Every finite sample merges (no
censor skip): the reference defines none. The reference frame merges with
`r_ref = 1`.

Opt-in green-guided chroma deweight (dormant A/B; Sabre `direct_rgb_accumulate`
`chromaWeight` analogue, adapted to plain linear RGB): when the moving frame
carries `ChromaParams`, R/B taps additionally scale by `exp(−0.5·d²)` with
`d = (localGreen − targetGreen)/σ`, `σ = max(2.5·√max(S·signal+O, 0), 1/160)`.
`targetGreen` is the kernel-weighted green mean at the source (same spatial
weights × r); `localGreen` is the mean of the finite green samples in the 3×3
window around the tap (no dense Sabre chromaGuide exists on this path). Green
taps, the reference frame, and burst-nearest accumulation never deweight. Null
`ChromaParams` (the default) runs the reference path. Coefficients are
normalized-domain single-sample green noise (mean of the two green phases),
not the ×0.25 green-mean pair used by the robustness photo term.

## 6. Normalization

`merged_c(p) = num_c(p) / den_c(p)` per channel independently (a missing blue
denominator never fades green), with `den_c(p) ≤ eps` (`eps = 1e-8`) reading
exactly 0 — the reference NaN-at-zero-support blacked downstream (an empty
denominator implies an empty numerator, so the only undefined quotient is
0/0). The per-pixel fallback flag marks exactly the `den ≤ eps` set
(diagnostic only). There is no fallback cascade: no reference-only branch, no
nearest blend, no overwrite. Merged output is linear camera RGB in the
reference frame's color/shading/exposure state.

## 7. Border behavior

- Projected `source` outside `[0, W) × [0, H)` (continuous RAW coordinates):
  the frame contributes nothing at `p`; previous accumulators are preserved and
  a per-pixel out-of-bounds diagnostic counter is incremented (SkyKing
  `support.a` analog, separate from color denominators).
- Partial support at image edges: in-bounds taps accumulate normally; missing
  taps are skipped (no clamp fabrication, consistent with the alignment
  contract's "out-of-image samples are not fabricated").
- Flow-tile lookup outside the tile grid cannot happen (clamped); robustness
  lookup clamps to the quad grid.

## 8. Rejection rules

- `r_n == 0`: the frame contributes zero weight at the pixel. The skip runs
  before the flow lookup, so rejected frames leave accumulators (and the OOB
  counter) untouched. No separate merge-side validity test exists.
- Non-finite flow: the pixel skips with an OOB bump (diagnostic counter).
- Out-of-bounds source: the pixel skips with an OOB bump.
- Non-finite sample, weight, or robustness values: tap skipped; every
  accumulator stays finite on every input (tested with poisoned inputs).
- Frames rejected by the burst planner never reach the merge.
- There is deliberately NO tap censor, NO motion-edge stop, NO residual gate,
  and NO reliability gate in the base path: the reference defines none, and
  robustness (`docs/raw-sr-robustness.md`) is the sole misalignment backstop.

## 9. Merge order, Rc, and the removed fallback cascade

Merge order is reference-last (plan §7.6; Jamy-L merges the reference first,
but addition commutes so this only fixes float determinism alongside the CPU
oracle). The reference frame merges with zero shift and `r_ref = 1` everywhere
(Jamy-L `dummy_r = ones` analog), using its own kernels looked up unshifted.
With `referenceOnly` the moving frames are skipped on the same path.

`Rc` is the per-quad finite-sanitized sum of moving-frame robustness weights
(`RawSrRobustness.accumulate`, reference excluded; identically zero in
reference-only mode). It is diagnostic-only: it feeds `1 + Rc` support (§11)
and QA, never a merge branch.

History note (removed September 2026 by the reference-parity port): the
Stacker-inspired accumulated-robustness overwrite (`MIN_SUPPORT = 0.5`), the
burst-nearest/reference-kernel fallback cascade, the tap censor
(`SATURATED_REF_GUARD`), the motion-edge stop (`MOTION_EDGE_QUAD`), the
highlight peak-mask rolloff, and the reference-quotient lane no longer back
any base-path behavior. `accumulateNearest` (Stacker delta-kernel rule) stays
as a tested dormant helper with its censor skip; `MIN_SUPPORT`,
`MOTION_EDGE_QUAD`, and `SATURATED_REF_GUARD` stay as dormant A/B constants.
Zero support divides to 0 (§6).

## 10. Reference-only A/B mode on the same path

A reference-only mode runs the identical accumulation path with a single frame
(the reference), forced zero flow, `r = 1`, same kernels, same 3×3 support,
same normalization. Moving frames are skipped; `Rc` stays identically zero
(§11). It is the color/noise/sharpness baseline for every acceptance criterion
below. A fully-rejected moving frame (`r = 0`) is bit-identical to skipping it.

## 11. 1 + Rc support estimate

`Rc` is the per-quad float field from `RawSrRobustness.accumulate`
(`Rc += r_n` per moving frame, finite-sanitized; reference excluded;
reference-only mode leaves `Rc` identically zero). `1 + Rc` is reported per
quad as the robustness-based frame-support estimate for diagnostics and local
output-denoising control. It is a support indicator, not a statistically exact
effective sample count (robustness doc §8): a plain sum with no per-frame
clamp (in-contract robustness is already [0, 1]). Per-channel moving-frame
support rides alongside as a per-pixel bitmask (R/G/B denominators above
eps), the Sabre `support.g/b` analogue for denoising control; the
normalization quotients themselves are unclamped.

## 12. Quantitative acceptance criteria (declared before evaluation)

All criteria run on synthetic bursts with ground truth, comparing the merged
output against the reference-only A/B output and the known synthetic inputs.
CPU oracle vs GPU shader agreement uses the same tolerance family as the
robustness stage.

- Colour: on a static synthetic gray-ramp burst, per-channel merged means are
  within 1% of the reference-only means; no channel swap (R/B response follows
  the frame CFA, checked across all four phases); static-scene per-pixel
  `|merged − refOnly| ≤ 0.01` (normalized linear) for ≥ 99% of interior pixels.
- CPU/GPU agreement: per-pixel `|x_gpu − x_cpu| ≤ 2e−3 + 2e−3·|x_cpu|` for
  numerators, denominators, and final RGB; `Rc` within the same family;
  OOB/fallback pixel sets bit-exact. Two counted provisions: (1) tap-window
  straddle — where a frame's projected source sits within 1e−4 of an integer,
  float64-CPU and float32-GPU sources may floor the 3×3 tap center to
  adjacent pixels (whole-tap-weight accumulator shifts with an unaffected or
  equally-valid RGB ratio); num/den samples, and rgb samples whose
  accumulators straddled, are excused and counted, never silently dropped.
  (2) The image-level `RGB_TOL` gate applies to the rgb max error only;
  unnormalized accumulators legitimately exceed it at large burst counts.
- Noise reduction: 8-frame static burst with independent per-frame Gaussian
  noise (σ = 0.02 normalized) and subpixel shifts at the fixed K = 1
  reference-unit kernel: mean `Rc ≥ 6.0` over interior quads, and interior
  output variance ≤ 0.3× single-frame variance (ideal 1/8; margin allows
  kernel correlation; the 0.25 bound belonged to the retired 2×-wider
  quad-unit kernel distances).
- Edge preservation: slanted-edge MTF50 of the static-merge output ≥ 90% of
  the reference-only MTF50 on the same scene; no directional MTF50 asymmetry
  beyond 5% (catches covariance-axis bugs).
- Ghost blending (no merge-level rejection exists): a conflicting moving
  frame blends through the plain kernel quotient — low-support sites resolve
  to the `w·r`-weighted mean with a clean mask, never to a reference-only
  overwrite. Misalignment protection lives in robustness, not here.
  Zero-support pixels divide to 0 with the flag set; reference-only and
  fully-rejected runs agree bit-exactly.
- Finiteness/determinism: no non-finite numerator, denominator, `Rc`, or
  output on any test including poisoned flow/noise inputs; repeated GPU runs
  bit-identical; CPU oracle run-to-run equal.

## 13. Bilinear-everywhere + unblocker + contested veto (2026-10-05/06, RawLens improvement)

Sabre-style dense warp, always on, no gates: merge gather AND
robustness warp both bilinear-sample the flow (`flowAtSmoothInto`,
C0-continuous, tears impossible by construction), and per-pixel
rejection (not flow vetoes) handles mistakes. The robustness warp
uses the SAME bilinear lookup as the gather, so r scores the warp
that actually renders. Non-finite corners fall back to the containing
tile; non-finite results skip with oob++ (invalid-flow propagation
preserved). The pre-Sabre gated smoother (`flowAtGatedSmoothInto`,
`FlowPhotoGate`, gradK) and all its tuning/CLI/shader surface are
DELETED, not default-off — the gates were scaffolding around the
self-inflicted tile snap. CPU and GPU transcribe the same formulas
in float32 (`merge_accumulate.glsl`, `robustness.glsl`).

The unblocker fold (`RawSrUnblocker`, always on) caps robustness by
the variance-loss keep-weight (`r' = min(r, u)`, Sabre
`weight = min(1 - unblocker, frame_weight)`), then the contested-warp
veto zeroes quads where the 3x3 tile flow spread exceeds the motion
threshold while u < 0.5 (period-ghost bends would render at any
partial weight), and a second 5x5 local minimum spreads the fold
(Sabre dilate). The keep verdict judges the PRE-fold field, so frame
selection is unchanged; without a usable noise model the field rides
through (no model, no gate). GPU mirrors the order exactly
(robustness → min → modulate → min → accumulate) with the CPU guide
gray uploaded verbatim (4E precedent: the pyramid's linear grey
lives in a different domain and would gate differently).

Validated on the Xiaomi burst (Xiaomi REDMI Note 15 Pro 5G,
4080x3060 x8): quilt zmax 1.39 (REF Jamy-L 2.27, gated 2.23,
bilinear-no-fold 1.96; single-frame floor 1.18); blinds slats
straight (user spots track single-frame at mean|d| 47/16 DN vs
989/655 gated); the (1298,1149) "pit" reads 3713 ≈ single-frame
3690 — the 9836 value that gated/REF render there is a ghost smear
(seven period-shifted moving samples averaging against the
reference), so matching REF was a trap; sharpness best-ever
(hp-std 786.6 vs 770.0 gated — rejecting ghosts sharpens); VK twins
CPU at mean|d| 0.3, pit-exact, spots-exact. Veto measured surgical
(4-6.5% of quads; pit/bush keep full weight).

History compressed: gated smoothing (spread 2.0 + photo floor 0.035)
was user-confirmed blinds-good with pit 9836, but left foliage
tears (gates can't engage where tiles disagree by design) and the
blinds ghost above. Spread INF is the blinds killer (user-confirmed
bad). GradK failed the pit gate (k=1: 9836 → 6163 — the pit's own
±2 gradient exceeds tear gradients, no k separates). Blend
verification was tried and reverted (useless at INF). Pinned by
`RawSrFlowIntoParityTest` smooth/nearest cases,
`flowTransitionBlendsAcrossBorderButMatchesUniformFarAway`,
`robustnessStepFlowMatchesOracleOffBand` (off-band REF parity +
band divergence), unblocker cap/spread/veto tests, and GPU
`gpuBilinearSmoothMatchesCpuOracle`. GPU carries no bitwise
guarantee across source edits (twin-tolerance parity only).

## 14. Sign-off (2026-09-10, Mali-G615 MC2, Redmi 25080RABDG)

Prompt 4D declared DONE. JVM unit suite 211/211 green. Headless GPU
`connectedDebugAndroidTest`: 50/50 ran, 49 pass; every merge, flow-oracle,
covariance, and robustness test green, including the previously signal-9-killed
`flowMatrixMatchesCpuOracle`. Sea-burst real-Bayer coverage recorded in
`rawsr-sea-mali-results-2026-09-10.json`. The single red test,
`RawTherapeeAmazeInstrumentedTest.orderedGlesAgainstPinnedHostUpstream`, belongs
to the Amaze demosaic port (out of 4D scope): its GLES shader compile errors
were fixed drive-by, but its output diverges from the pinned host reference on
some scenes while the CPU port bit-matches — tracked as separate follow-up
work. Adreno UNTESTED.
