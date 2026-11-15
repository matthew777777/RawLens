# KernelNet reconstruction tile seams

Reported image: `IMG_20210228_133623_KernelNet_2x/merged.dng`,
8064 x 6048. Reproduced from all 20 DNGs in
`Downloads/hdrplus_subset/IMG_20210228_133623` with the desktop launcher
settings (Vulkan, KernelNet, 2x, automatic noise LUT, reference 0).

The merge sampled the alignment grid with nearest-tile lookup. A motion
vector changed abruptly every 16 input pixels, hence every 32 output pixels
at 2x. Even with continuous reference-grid kernel covariances this moves the
Gaussian gather abruptly, imprinting rectangular boundaries into the image.
This is reconstruction discontinuity, not DNG storage tiling.

`merge_splat.comp` now interpolates the KernelNet motion field between tile
centres, extending the nearest centre constantly at the image boundaries.
The existing reference-kernel flag scopes the change: the Jamy-L/Wronski
branch retains nearest-tile sampling. Alignment, robustness/S.3, kernel
inference, covariance interpolation, Bayer sampling and normalization are
unchanged. Robustness remains a conservative estimate from the original
alignment tiles; this change does not promise to remove all motion ghosts
or Bayer aliasing.

## Validation

- Full module suite: 258 cases, 177 executed, 81 skipped, zero failures.
  Native merge-splat and divide shader properties enabled; unrelated GPU
  stage proofs without their shader properties remain skipped.
- Existing native merge parity against Kotlin CPU and stored GPU fixtures
  passes, as does staged/resident accumulation equivalence.
- New `KernelNetFlowTest`: actual Vulkan resident-frame reconstruction
  against an independent Gaussian-gather oracle for zero and affine
  displacement. Covers tile boundaries, both image axes and a non-square
  frame with partial final tiles. Explicit GPU setup fails if no GPU is
  available rather than silently skipping this proof.
- Full-resolution regeneration succeeded (16.2 s on this Mac). Original
  user DNG retained; corrected DNG written separately.
- Smooth pavement ROI: output x=5040..5599, y=3904..4479, green channel.
  Mean absolute adjacent-pixel difference on 32-pixel boundaries:

| Direction | Original | Corrected | Interior even-phase mean, original/corrected |
|---|---:|---:|---:|
| y | 30.66 | 20.99 | 25.55 / 24.28 |
| x | 33.10 | 23.08 | 30.37 / 28.96 |

Values are 16-bit DNG code units. Comparing even phases avoids confusing
Bayer-phase variation with the tile effect. Boundary excess disappears;
interior differences change much less than boundary differences. An
independent 512-input-pixel crop rerun shows the same result. Visual review
of the pedestrian crop confirms reduced rectangular imprinting. Android
hardware and unrelated burst scenes were not exercised in this change.

## Follow-up: residual boxy contours

The owner still saw boxy contours after the flow interpolation fix. A
controlled crop experiment interpolating the robustness warp and S-map
produced almost no change (pavement x/y differences 27.66/23.72 versus
27.66/23.72 code units). That experiment was discarded; no robustness or
JNI changes were retained.

A second discontinuity remained in the finite reconstruction support:
`int(mov)` switches the 3x3 gather abruptly at integer sensor coordinates,
discarding samples whose Gaussian weights are still nonzero. This is
particularly significant with KernelNet's wider covariances and widened
R/B colour-difference kernels. The earlier flow-only fix could not remove
this sample-support discontinuity.

KernelNet now uses a 7x7 gather, preserving the Gaussian within 2.5 sensor
pixels on each axis and fading the outer ring to zero with a cubic
smoothstep at 3.5 pixels. Entering/leaving sample weights therefore go to
zero continuously. Wronski/Jamy-L still uses the original 3x3 Gaussian
gather and passes its existing native parity checks. This supersedes the
initial statement above that Bayer sampling is entirely unchanged: its
colour phases are unchanged, but KernelNet's spatial gather is larger.

The GPU regression now also covers translations on either side of an
integer gather centre and asserts continuity of normalized output, in
addition to matching the independent Gaussian-plus-taper oracle over the
entire test image. Full SR suite again passed: 177 executed, 81 skipped.

On the same 512-input-pixel pedestrian/pavement crop, mean pavement
adjacent differences fell from 23.72/27.66 to 22.64/26.50 code units (y/x).
Gradient magnitude on the strongest 1% of edges retained 99.73% of the
flow-only result; this crop measurement supports reduced reconstruction
roughness without a material loss of strong-edge detail. Visual comparison
shows reduced boxy contours. This is not a claim of perfect reconstruction
of moving subjects or validation on all scenes/devices.

The second full-frame result is written separately to
`/tmp/IMG_20210228_133623_KernelNet_2x_fixed_v2/merged.dng`.

## Second owner rejection: investigate the actual rectangles

The owner confirmed opening `fixed_v2` and supplied another screenshot with
large rectangular patches still present. The preceding gradient scores and
small-window improvements did **not** establish that the reported problem
was fixed. Treat those as intermediate observations, not acceptance evidence.

Full-frame flow exports show unrelated neighbouring pavement estimates
varying by about a raw pixel, including tiles with weak two-dimensional
reference-gradient support. A smooth interpolation of unstable matches still
produces a distorted surface. Separate experiments with global affine and
5x5-median flow confirmed that changing alignment, rather than widening the
reconstruction kernel again, changes the reported region substantially.
Those two experiment-only overrides and reference-Hessian export code were
removed from the implementation.

The current candidate combines:

- `MotionField`: reference-conditioned, spatially regularized finest-grid
  displacements before **both** robustness and reconstruction. Confidence uses
  the smaller structure-tensor eigenvalue, scaled relative to the reference
  distribution. This is an empirical texture-support scale, not calibrated
  sensor-noise uncertainty. A weak positive data anchor prevents a textured
  moving object from dragging an unconstrained background. A robust start
  and Charbonnier neighbour weights suppress unsupported jitter while
  retaining strongly supported separate motion. Iteration is bounded and
  stops early when the field stabilizes. State is proportional to tile count,
  not full-resolution image size.
- The same constant-extended cubic B-spline motion field in reconstruction
  and robustness warping. The earlier reconstruction-only bilinear field
  was merely C0, while robustness continued to use nearest-tile warps.
  The new field is C2 and shared in coordinate convention by both stages.
- Continuous sampling of robustness strengths and guide-grid rejection
  weights, using the actual guide centres. KernelNet previously inherited
  the reference port's floor-then-truncate lookup, which also displaced the
  weights. S.3's conservative hard rejection and 5x5 minimum remain active.
- The legacy 0/1 native robustness flags retain the original path. Bit 1
  explicitly enables the continuous KernelNet field; bit 0 independently
  controls noise correction. Jamy-L/Wronski CPU and GPU parity paths remain
  unchanged. Public native method signatures and push-constant size are
  unchanged.

Validation of this candidate:

- Full module suite with merge, divide and robustness shader properties:
  **263 tests, 187 executed, 76 skipped, zero failures**.
- Actual Vulkan reconstruction checked against an analytic affine-field
  Gaussian oracle, including non-square frames and partial edge tiles.
- Actual Vulkan rejection checked against an analytic moving guide ramp and
  spatial strength ramp across tile boundaries. A known residual checks
  `exp(-d/sigma)` and ensures enabling the field does not accidentally enable
  the noise LUT. The final 5x5 minimum is included in the expectation.
- Spatial-regularizer tests cover constant translation, singular tiles and
  narrow grids, affine camera motion, weak noisy regions, and independently
  moving foreground that must not drag a flat background.
- Full 20-frame 8064x6048 output succeeded in 36.4 s on this Mac, compared
  with 20.7 s for the earlier gather-only full run. These are individual
  wall times, not controlled benchmarks; the quality correction adds work.
- Independent eight-frame 512-input-pixel crops succeeded on
  `IMG_20260929_170823_957` and `IMG_20261112_125318_179` at 2x.
  The former also succeeded at 1x (512x512 output).
- Static architecture ROI x=2600..4199, y=2000..3199 retains 99.51% of v2's
  mean gradient magnitude on v2's strongest 1% of edges. Pavement adjacent
  differences changed little (y/x 22.74/26.65 to 22.78/26.48 code units),
  emphasizing why that metric alone cannot determine acceptance of the
  local rectangular artifact.

Candidate: `/tmp/IMG_20210228_133623_continuous_motion/merged.dng`.
Comparison against v2: `/tmp/srp-continuous-comparison.png`.
Owner review of the exact patch is requested; do not claim that all visible
boxes are eliminated solely from passing these tests. Android hardware was
not tested in this follow-up. Unrelated app work in the shared checkout was
not modified by this investigation.

## Owner: close, residual zipper and a small shoe tile

The owner reported that the continuous-motion result was close, with
remaining zipper and a small tile on the grey shoe, and supplied crops of
both pedestrians. This narrows the remaining acceptance target.

Two retained corrections:

1. Desktop colour-difference reconstruction was off by default, and its
   explicit CLI option had a parser bug: the value was read but the argument
   index was not advanced afterwards. The value was then treated as an
   unknown argument. The real production parser is now covered by successful
   parsing tests. Vulkan KernelNet defaults to the existing app
   colour-difference reconstruction with gains from valid AsShotNeutral,
   and the existing scale-dependent chroma width (1.5 at 2x). On this DNG
   the gains are R=2.211663, B=1.6. Explicit gains, auto and off work;
   Wronski/CPU defaults remain off. Invalid/missing neutral does not invent
   gains or enable the correction.
2. S.3 still had a nearest-tile hard motion gate, despite the continuous
   warp and strength field. Its KernelNet path now uses the same spline
   membership for that gate. Full motion membership still fully rejects;
   the 5x5 rejection minimum remains. The legacy branch keeps its exact
   hard gate. A diagnostic with S.3 disabled changed the shoe but introduced
   ghosting, so disabling S.3 was not retained.

Matched 512-input-pixel controls with the same continuous S.3 shader show:

- Green output difference with colour difference enabled versus off:
  **zero DNG code units** (bit-identical green detail channel).
- On the strongest 5% of green-gradient edges, white-balanced chroma
  high-pass RMS falls from 288.74/283.54 to 44.10/39.59 (R-G / B-G), about
  85%/86%. This measures false-colour/zipper energy, not recovered detail.
- A separate experimental dense-reference green interpolation was tested
  for residual luminance checker. It lowered high-frequency energy but did
  not consistently improve the Bayer-phase ratio (one shoe ROI worsened
  from 2.40 to 2.64). It was **not** retained; production green reconstruction
  was not broadened by that experiment.

Validation: 266 tests, 190 executed, 76 skipped, zero failures. The analytic
GPU rejection oracle now covers continuous S.3 membership and the final
minimum, plus an all-motion case that must remain fully rejected. Three
additional tests cover the actual CLI parser, automatic/manual/off white
balance selection, reference-backend defaults and invalid neutral metadata.

Full 20-frame output regenerated at 8064x6048 in 48.7 s on this Mac:
`/tmp/IMG_20210228_133623_zipper_corrected/merged.dng`.
Both owner regions were rendered from the full output for comparison:
`/tmp/srp-zipper-both-regions.png`. The file is a new visual-review result;
do not interpret the chroma score as proof of zero remaining luminance
aliasing at 600% zoom. No Android hardware validation was performed here.
