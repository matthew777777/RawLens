# Dark-wall geometry follow-up

The earlier identical reruns established repeatability, not preservation of
geometry or detail against the reference. The user correctly identified
remaining soft/wavy wall texture in `IMG_20261112_125318_179` at 1x.

## Root cause and correction

`jacblk.comp` accumulates unnormalised camera-RGB gradient products. The
local translation solve added `lambda * (trace + .02)` to its Hessian,
regardless of signal/noise level. In dark texture, that fixed floor dominates
the data Hessian and suppresses subpixel alignment correction.

The floor is now `min(configured_floor, 256 * max(a * .18 + b, 1e-12))`,
in the same signal-squared units as the block Hessian. The existing floor
remains an upper bound. Neighbour regularisation, step clamping, rejection,
and the reference frame's fixed geometry remain.

Default schedules change from GGJJGJ / GGJJJJGJJJJ to GGGJJJ / GGGJJJJJJJJ.
The same three motion steps now all use the reference-only initial image,
before averaging/reconstruction. Previously the third step fitted an
intermediate merge, which could already be blurred by alignment errors.

## Controls and evidence

Full-resolution controls disabled sub-tile initialization, skipped G steps,
disabled the hybrid combine, reduced regularisation, increased leading G
steps, or tightened temporal consistency. Disabling local alignment alone
did not resolve the wall softness. Stricter rejection helped the wall but
discarded too much 2x foliage; rejection defaults are unchanged.

`lkrl_motion_check` samples an analytic textured scene with known smooth
local displacement at dark and 16x brighter exposure. Six reference-only
motion steps isolate the alignment mechanics from merging and denoising.
The previous implementation fails the dark case (motion RMSE ~.363 px).
The correction passes both brightness levels (.0520 / .0522 px, limit .12).

Real-wall diagnostic: raw sensor-output rectangle x=2100..2700,
y=260..800, WB luma, three [1 2 1] smoothing passes in each direction.
For each 40x40 window, fit residual against reference x/y gradients plus
an intensity offset. This is an approximate displacement diagnostic against
a noisy reference, not ground-truth optical flow or an absolute sharpness
measurement. Median / p95 fitted displacement magnitude:

| Variant | Median | p95 |
| --- | --- | --- |
| Previous merge | .134 px | .455 px |
| Noise-calibrated floor | .091 px | .387 px |
| Floor + three leading G steps (selected) | .086 px | .333 px |
| Floor + six leading G steps (not selected) | .082 px | .298 px |

Three steps retain the existing motion-pass count. Six add cost for a small
additional improvement. The wall is still smoother than the noisy single
frame; these results do not establish that all remaining softness is solved.

Car-burst 2x regression: upper-bush median support 7.67 frames, 5.86% below
four; lower bush/rock median 7.46, 1.70% below four. Previous corrected output:
7.63 / 5.90% and 7.44 / 1.84%. Inspected car crop shows no new duplication.
These support figures alone are not image-quality scores.

Host build, Android assembleDebug, known-motion regression, shared-adapter
DCT/colour-edge tests, and all six KernelNet regressions pass. Final full
resolution outputs: `/tmp/lkrl-wall-geometry/wall-fixed-1x.dng` and
`car-fixed-2x.dng`. Review crop: `wall-final-comparison.png` (reference,
previous merge, corrected alignment). Phone runtime remains untested.
