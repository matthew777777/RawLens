# RAW-SR Bayer-direct merge contract (Prompt 4D)

Written **before** any merge implementation. The code must match this document;
any behavior difference is a bug unless recorded here as a deliberate deviation.
No code changes are made in this step.

References: Wronski et al. SIGGRAPH 2019 §5 / paper Alg. 1 (kernels),
Jamy-L IPOL 2023 `merge.py` Algs. 4 (accumulation geometry) and 11 (reference handling),
SkyKing `motionv2/direct_rgb_accumulate.glsl`
+ `mfsr_finalize.glsl` (Bayer-direct sampling, support tracking),
Stacker v0.1.5-beta `wronski_cpu_merge.cpp` (burst-nearest fallback,
accumulated-robustness overwrite inspiration),
RawLens `docs/raw-sr-alignment-contract.md` (flow units/lookup),
`docs/raw-sr-robustness.md` (robustness maps, Rc), `docs/raw-sr-packed-executor.md`
(frame layout, CFA phase), `RawSrCovarianceGuide` (coordinate contract, units),
`RawSrKernelCovariance` (precision packing), `RawSrPackedFrame.pattern`
(phase-shifted CFA), `docs/wronski-raw-zsl-super-resolution-plan.md` §7.6.

Attribution scope: Jamy-L covers the accumulation geometry (§§3–5: flow,
precision lookup, 3×3 splats). Jamy-L leaves zero-support pixels undefined
(NaN, later blacked) and censors nothing — the fallback cascade (§9),
the censor guard, and the burst-nearest ordering are Stacker/SkyKing-derived
and judged on their own contract below, not on Alg. 4/11 fidelity.

## 1. Color space: ordinary linear camera RGB

Numerators are ordinary linear camera-RGB sums (`vec3`: R, G, B independently
accumulated); denominators are independent per-channel weights (`vec3`: R, G, B).
This is a deliberate deviation from the SkyKing accumulator, which stores
white-balanced `G, R−G, B−G` opponent channels restored by its finalizer:
copying that contract without its white-balance/finalizer pipeline would produce
incorrect colors (see `docs/raw-sr-skyking-reference.md`). No white balance,
no opponent transform, and no highlight reconstruction run inside the merge in v1.

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
The experimental 2× Mosaic SR target grid is deferred; nothing here assumes it.

## 3. Flow direction and units

Flow is reference-anchored and stored in **Bayer-quad pixels** (alignment
contract §"Pyramid, coordinates and matching"). For output pixel `p`
(reference RAW coordinates), the projected source position in moving frame `n` is

`source_n(p) = p + 0.5 + 2 · flow_n(p)`,

where `flow_n(p)` is the **bilinear** flow sample at quad `q` containing `p`
(`RawSrAlignmentField.flowAtSmooth`, mirrored by `flowSmooth` in
robustness.glsl/merge_accumulate.glsl and by the mosaic accumulator, whose
nearest-tile lookup imprinted the same quilt until 2026-09-12): tile centers sit at integer lattice
points of `u = (q + 0.5) / tileSize − 0.5`, the four surrounding tiles blend
dx/dy (residual stays nearest so the residual gate keeps its exact boundary),
and blended reliability gates at 0.5. Nearest-tile lookup imprinted the tile
grid on shadows as a 16px quilt (measured ±23 codes phase-spread on a real
linear DNG), so tile borders stay inside alignment and out of the merge; any
non-finite corner falls back to the containing tile. The `+0.5` terms are the
pixel-center convention: output pixel `p` integrates `[p, p+1)`, and the source
is addressed in the same continuous RAW-pixel coordinate system. Multiplying by
2 is the Bayer-quad-to-RAW conversion (`d_raw = 2 · d_quad`).

## 4. Covariance lookup coordinates, units, kernel exponent

Each frame's kernel field is its own `RawSrKernelCovariance.precision` field
(stored per quad pixel, entries in **quad-pixel⁻²**), estimated from that
frame's own variance-stabilized covariance guide. The lookup is source-anchored:
at projected position `source`, guide coordinates are

`g = source · (guideSize / rawSize) − 0.5`,

the packed precision entries are bilinearly interpolated at `g` (clamped to the
guide grid), exactly like the SkyKing `interpolatePrecision` convention. This
differs from Jamy-L, which bilinearly interpolates covariance then inverts per
pixel: storing precision directly removes the per-pixel inversion while keeping
the same lookup geometry. Offsets are converted to quad pixels
(`d_quad = d_raw / 2`) and the kernel exponent is

`z = d_quadᵀ · P_quad · d_quad ≥ 0` (clamped at 0),

which is invariant under the raw/quad conversion (`P_raw = P_quad / 4`;
pinned by the `RawSrCovarianceGuide` exponent-invariance test). The kernel weight is

`w = exp(−0.5 · z)`,

natural exponential per Jamy-L (deliberate deviation from SkyKing's
`exp2(−0.5·d) + 0.00005`: no additive floor; near-zero weights are handled by
the denominator epsilon + fallback in §9, not by a weight pedestal).

## 5. Sampling support and CFA phase

For each output pixel, the 3×3 RAW support around `center = floor(source)` is
visited (`center + {−1, 0, 1}²`), matching Jamy-L Alg. 4. Tap offsets use pixel
centers: `dist = tap + 0.5 − source`. Per-tap out-of-bounds taps are skipped
individually (never clamped into the image).

Each tap contributes **only to its own color channel**: the tap color comes
from moving-frame sensor coordinates
(`sensor = tap + (sensorCropLeft, sensorCropTop)`) via the frame's shifted
`pattern` (`RawSrPackedFrame.pattern`), which already folds sensor origin +
crop origin. The hardcoded `rggb` mapping in Jamy-L `merge.py` (`channel =
i%2 + j%2`) MUST NOT be used; all four CFA phases are supported. Shared local
CFA phase across frames is a packed-executor admission requirement, so routing
by moving-frame sensor color is consistent with reference-frame color at the
same local offset.

Per-tap accumulated contribution (channel `c` = tap color):

`num_c(p) += w · r_n(q) · sample_c(tap)`,
`den_c(p) += w · r_n(q)`,

where `r_n(q)` is the frame's robustness at the reference-anchored quad `q`
containing `p`, evaluated with the same bilinear flow sample (the robustness
field itself stays per-quad; only its flow input is smoothed), and `sample_c`
is the normalized shaded merge sample (§1).

Opt-in green-guided chroma deweight (L1, Sabre `direct_rgb_accumulate`
`chromaWeight` analogue, adapted to plain linear RGB): when the moving frame
carries `ChromaParams`, R/B taps additionally scale by `exp(−0.5·d²)` with
`d = (localGreen − targetGreen)/σ`, `σ = max(2.5·√max(S·signal+O, 0), 1/160)`.
`targetGreen` is the kernel-weighted green mean at the source (same spatial
weights × r, censored taps excluded); `localGreen` is the mean of the finite
uncensored green samples in the 3×3 window around the tap (no dense Sabre
chromaGuide exists on this path). Green taps, the reference frame (`r = 1`
path), and burst-nearest accumulation never deweight. Null `ChromaParams`
(the default) runs the legacy path bitwise-identically. Coefficients are
normalized-domain single-sample green noise (mean of the two green phases),
not the ×0.25 green-mean pair used by the robustness photo term.

## 6. Normalization

`merged_c(p) = num_c(p) / max(den_c(p), eps)` with `eps = 1e-8`, applied
per channel independently (a missing blue denominator never fades green).
`den_c(p) ≤ eps` triggers the local fallback (§9), so the epsilon branch is
reached only if the fallback itself is unavailable. Merged output is linear
camera RGB in the reference frame's color/shading/exposure state.

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

- `r_n(q) == 0` (photo conflict, saturation, residual, flow-unreliable,
  out-of-bounds per `docs/raw-sr-robustness.md` §7): the frame contributes zero
  weight at every pixel of `q`. No separate merge-side validity test exists;
  alignment/robustness gates are authoritative.
- Motion-edge stop (water / occlusion boundaries): neighbouring tiles whose
  flow disagrees by more than `MOTION_EDGE_QUAD` (1.0 quad px) mean the
  bilinear flow blends two motions, so every splat would misregister. The
  pixel skips like `r == 0` (accumulators keep the reference-only value,
  no OOB bump). Same 3x3 tile-spread test as the robustness irregularity
  gate, at discontinuity scale; CPU oracle and GLSL mirror exactly. Only
  demonstrated disagreement vetoes (two finite in-bounds tiles apart):
  non-finite neighbours are missing data, not motion — those pixels merge,
  with the robustness gates (residual, photo term, s1 scaling) as backstop,
  so noisy night flow cannot carve single-frame patches into static scenes.
- Invalid-flow tiles (confidence 0) contribute nothing for moving frames
  (their `r` is 0 via the zero-shift-hypothesis path when falsified).
- Non-finite sample, weight, or robustness values: tap skipped; every
  accumulator stays finite on every input (tested with poisoned inputs).
- Censored taps (>= guard) are skipped in kernel means and nearest picks
  alike: clipped white must never become a fallback value (deliberate
  deviation from Stacker's finite-only nearest rule).
- Frames rejected by the burst planner never reach the merge.

## 9. Accumulated-robustness-dependent reference support and local fallback

Merge order is reference-last (plan §7.6; Jamy-L merges the reference first,
but addition commutes so this only fixes float determinism alongside the CPU
oracle). The reference frame merges with zero shift and `r_ref = 1` everywhere
(Jamy-L `dummy_r = ones` analog), using its own kernels looked up unshifted.

Reference support is Rc-dependent by construction, with no extra boost
constants in v1: at output quad `q` the relative reference weight is
`w_ref / (w_ref + Σ_n w_n·r_n)`, which rises toward 1 as moving-frame
robustness (and hence `Rc`) falls. Where all moving frames are rejected
(`Rc ≈ 0`), the output is reference-dominated automatically.

Accumulated-robustness overwrite (Stacker-inspired, not Stacker-identical):
any quad with `Rc < MIN_SUPPORT` (half a frame-equivalent of moving-frame
support, `RawSrBayerMerge.MIN_SUPPORT = 0.5`) joins the fallback set even
where its denominators exceed eps, so ghost-prone sites keep no kernel
blend. The inspiration is Stacker's `apply_cpu_accumulated_robustness_overwrite`
(pinned v0.1.5-beta, commit 715d949e,
app/src/main/cpp/wronski_cpu_merge.cpp lines 692-714), but the quantities
are incommensurate: Stacker compares a per-pixel `accumulated_robustness`
scalar against a caller-passed `max_frame_count`, while ours compares the
per-quad `Rc` pure sum of moving-frame finite-sanitized robustness weights
(`RawSrRobustness.accumulate`, reference excluded). Moreover, in the pinned
Stacker the only `1.0` threshold is a self-test-harness literal
(`nativeWronskiCpuAccumulatedOverwriteSample`); no production merge path
calls the overwrite, and no threshold of `2` exists anywhere (the 2.0s in
that sample are fixture data). So 0.5 is not matched to Stacker's 1.0 — it
stands on our own threshold A/B below. The packed-path (Linear) fallback set
resolves through the reference kernel mean wherever the reference kernel
has support (saturation guard → zero-support ref-only → reference kernel
mean → nearest blend → nested ref-only): per-channel burst-nearest picks
straddle high-contrast edges into chroma speckles (lamp-clip contours), so
nearest survives solely where the reference kernel itself has no support.
(The mosaic path matches: same order, same reason — see
`references/mosaic-target-figure.md`.) A
single frame at `r = 0.3` is caught; a full ghost (`Rc ≈ 0`) is caught;
quads the reference already dominates keep blending.

Censored taps and the zero-support rule (2026-09-12, lamp/pole fix): taps at
or above `SATURATED_REF_GUARD` (0.99) never enter any kernel mean — clipped
values carry no trustworthy signal (SkyKing `CENSORED_UNKNOWN_CHROMA`), and
the reference's own self-bleed at `r = 1` painted the lamp halo.

The original per-site white override was replaced on 2026-09-23: equal
camera RGB is not neutral under AsShotNeutral, and censored taps left
unsupported colour channels on adjacent sites. Linear CPU/GPU and mosaic
(eager/streaming) now share a reference 3x3 peak mask, rolled in with
smoothstep from 0.99 to 1.0 — only neighbourhoods containing a truly
censored tap whiten; fused near-white detail (lamp halos, glints, bright
texture) keeps its colour instead of blooming into 3x3 white squares.
Output blends toward AsShotNeutral normalized
by its largest component, scaled by the peak capped at 1. This stays on the
neutral ray through 16-bit quantization and fills all CFA phases coherently.
The footprint expands by at most one source pixel; values below the rolloff
are unchanged. Clipped colour/detail is deliberately neutralized, not
recovered. See `logcat-20260922-evening-fixes.md` for the supplied lamp image.
Quads with no moving support (`Rc ≤ eps`) retain the reference quotient
before this final highlight resolution; the nearest fallback is unchanged.
Residual, structural: accepted-kernel pixels still show a one-pixel phase
fringe (≤ 0.23 on a 0.6 step, two-frame worst case) — Bayer-domain scatter
kernels mix phases; frame-count symmetrisation shrinks it on device. The 0.5 point sits clear of the `Rc = 1` saturation fixed point,
where accepted GPU/CPU arithmetic differences would straddle a literal 1.0
threshold routinely even though both sides agree within tolerance. The rule
is inert with no moving frames (reference-only output already equals the
reference, mask stays clean) and on paths where `Rc` is untracked (GPU
adapter path, `u_min_support = 0`). Both mosaic finalizers enforce it at the
shared `MIN_SUPPORT`: eager reads support from each target site's source
quad (the same quad whose robustness fed accumulation) and sets the fallback
mask; streaming tracks Rc in a seventh, quad-sized mapped accumulator and
routes values identically (StreamingMosaic carries no mask by design — the
DNG saver consumes CFA only). Threshold A/B (2026-09-12, runlog): 0.5
leaves the discriminating band [0.5, 1.0) kernel-merged — kernel mush up to
0.053 there on worst-case content — but quads in that band are only 1–6%
of sea/forest frames while up to 55% sit within 1e-7 of Rc = 1.0, so a 1.0
boundary would sit on the single most common real-world value, inviting
eager-float/streaming-double flips with no excuse gate on the DNG path and
trading genuine kernel SR detail for nearest snaps; 0.5 stands on this
measurement, not on parity with any Stacker threshold.

Local fallback semantics: where `den_c(p) ≤ eps` for channel `c` (no usable
support from any frame — only possible at borders or under total rejection),
the output takes the reference-only A/B path value at `(p, c)` (§10). The
fallback is reference data through the identical kernel/weight machinery, never
an interpolated fabrication from other channels.

## 10. Reference-only A/B mode on the same path

A reference-only mode runs the identical accumulation path with a single frame
(the reference), forced zero flow, `r = 1`, same kernels, same 3×3 support,
same normalization and fallback. Moving frames are skipped; `Rc` stays
identically zero (§11). It is the color/noise/sharpness baseline for every
acceptance criterion below and the fallback source in §9.

## 11. 1 + Rc support estimate

`Rc` is the per-quad float field from `RawSrRobustness.accumulate`
(`Rc += r_n` per accepted non-reference frame; reference excluded;
reference-only mode leaves `Rc` identically zero). `1 + Rc` is reported per
quad as the robustness-based frame-support estimate for diagnostics and local
output-denoising control. It is a support indicator, not a statistically exact
effective sample count (robustness doc §8).

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
  noise (σ = 0.02 normalized) and subpixel shifts: mean `Rc ≥ 6.0` over
  interior quads, and interior output variance ≤ 0.25× single-frame variance
  (ideal 1/8; margin allows kernel correlation).
- Edge preservation: slanted-edge MTF50 of the static-merge output ≥ 90% of
  the reference-only MTF50 on the same scene; no directional MTF50 asymmetry
  beyond 5% (catches covariance-axis bugs).
- Ghost rejection: synthetic burst with an independently translating
  foreground patch (≥ 2 quad pixels displacement vs background): ≥ 95% of
  foreground-interior pixels satisfy `|merged − refOnly| ≤ 0.02`; saturated
  synthetic highlights merge to the reference value exactly (`r == 0` path).
- Finiteness/determinism: no non-finite numerator, denominator, `Rc`, or
  output on any test including poisoned flow/noise inputs; repeated GPU runs
  bit-identical; CPU oracle run-to-run equal.

## 13. Sign-off (2026-09-10, Mali-G615 MC2, Redmi 25080RABDG)

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
