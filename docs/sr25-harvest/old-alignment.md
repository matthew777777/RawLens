# Deterministic RAW-SR alignment contract

Implemented and validated on 2026-09-10. CPU `RawSrAlignment` is the oracle for
`Gles31RawSrProcessor`; this does not enable merged DNG/JPEG saving.

## Reference decisions

The supplied IPOL article (`article_lr.md`, section 2.1, Algorithm 2) specifies
Gaussian multiscale block matching, L2 at coarse scales, L1 at the finest scale,
selection among three coarse-flow candidates, and three finest-level IC iterations.
The supplied Wronski paper (`1905.03277v2.md`) supplies the RAW/quad alignment context.

The inspected Jamy-L checkout is commit
`07bc3f26c875975612cd169d6bba2a1b1ddc4436`:
`handheld_super_resolution/config.py`, `alignment.py`, `block_matching.py`,
and `utils_image.py::cuda_downsample`.

That newer checkout differs from IPOL: it defaults to bilinear flow upscaling,
refines every scale, and uses valid convolution that shrinks image support. RawLens
deliberately follows IPOL's three-candidate, non-interpolating propagation and
finest-only IC. Border extension is explicit below rather than copying the
checkout's support shrinkage. These are documented choices, not claims of a
bit-identical port of the CUDA/PyTorch implementation.

## Pyramid, coordinates and matching

- The base grayscale image averages each complete 2×2 Bayer quad. This CFA-removal
  operation remains a quad average; subsequent pyramid reductions are Gaussian.
- Finest-first incremental scale factors: `[1, 2, 4, 4]`. Optional fifth/sixth
  levels continue by four. Stop before a reduced dimension would be less than four.
- At each reduction, sigma is `factor / 2`, radius is `2 * factor` (four sigma).
  CPU generates normalized coefficients once per reduction and uploads the same
  float coefficients to GLES. Horizontal then vertical separable passes use
  half-sample reflect borders. Output `p` samples filtered input `factor * p`;
  output dimensions are floor-divided. No hidden half-pixel origin shift.
- Tiles retain a fixed size in each level's pixels, so parents split into an integer
  number of finer tiles. Partial edge tiles are clipped to actual image dimensions.
- Parent lookup is `fineTile / factor`, **not** a ratio of ceil-rounded grid sizes.
  Evaluate the containing parent and the closer horizontal/vertical neighbors,
  in that order; pick the valid candidate minimizing current-patch L1 cost.
  Neighbor direction uses the fine tile's position within its parent.
  Scale the selected displacement by the level factor; never blend flow vectors.
- With default `searchRadius=4`, finest-first radii are `[1, 4, 4, 4]`.
  Configuration changes the coarse radius only; the finest radius remains one.
  Shader/configuration bounds are radius 1–6 and tile size 4–32.
- Local search minimizes mean squared difference at coarse levels, mean absolute
  difference at the finest. A candidate needs at least four samples and 75% of
  the clipped tile area. Out-of-image samples are not fabricated by clamping.
- Exact cost ties prefer the smallest squared innovation, then the fixed
  Y-major/X-minor enumeration order. Invalid parent candidates are ignored;
  absence of a valid seed resets the local seed to zero.
- Each intermediate displacement is in that pyramid level's pixels, with explicit
  scale conversion. Final flow is in base **Bayer-quad pixels**. Only the existing
  full-resolution RGB sampling boundary multiplies by two.

## IC refinement and rejection

Exactly three IC update slots run at the finest level, not three at every level.
Reference central-difference gradients and the Hessian are fixed for the tile.
For residual `moving(p + flow) - reference(p)`, the translation update subtracts
`inverse(H) * gradientResidual`. Each axis's update is bounded to ±1 level pixel.
Failed tiles perform no further updates; they cannot become valid again.

Default checks:

1. Block matching must produce a finite valid candidate.
2. At least four template-gradient samples must exist.
3. Hessian determinant must be finite and strictly above `1e-5`.
4. Determinant must also exceed `1e-4 * trace(H)^2` to reject poorly conditioned
   rank-one/aperture-problem tiles.
5. Every fixed template sample must remain bilinearly sampleable during each
   update. Otherwise reject instead of silently changing Hessian support.
6. Final residual needs at least four samples and 75% of clipped tile area;
   mean absolute residual must be at most `0.12`.
7. Compute reverse alignment independently. At the forward-warped tile center,
   select the containing reverse tile without interpolation. Reject out-of-image
   centers, invalid reverse tiles, or forward-plus-reverse max-axis error above
   `1.5` quad pixels. This is a validity test, never flow smoothing.

Flow layout is `(dx, dy, meanAbsoluteResidual, confidence)`. Confidence is exactly
zero or one. Invalid flow/residual values remain finite; unavailable/nonfinite
residuals use the documented sentinel `1e6`. Rejection never fabricates confidence.
Coarse block-matching texture scores are L2 or L1 costs, not final residuals.

## Verification and diagnostics

Final command:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb -s fe79feha9lmb6hhi shell am instrument -w -r -e class com.matthew.rawlens.RawSrGpuInstrumentedTest com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
```

Results: **110 unit tests**, debug and test APK builds, and **8 headless GPU tests
passed** on **ARM / Mali-G615 MC2**. No Activity launch is used by the test class.
All four levels are exercised in the 384×288 RAW large-motion test.

| Motion in RAW pixels | Reliable interior coverage | CPU/GPU X/Y MAE (quad pixels) | Known-shift X/Y MAE |
|---|---:|---:|---:|
| Static | 24/24, 100% | 0 / 0 | 0 / 0 |
| +4, +2 | 24/24, 100% | 0 / 0 | 0 / 0 |
| −4, −2 | 24/24, 100% | 0 / 0 | 0 / 0 |
| +1.2, −0.6 | 24/24, 100% | 2.38e-7 / 1.64e-7 | 0.016852 / 0.018316 |
| +20, −12 | 336/352, 95.45% | 0 / 0 | 0 / 0 |

Mixed-subpixel CPU/GPU bias: X=1.14e-7, Y=−2.48e-9; maximum error=3.28e-6.
Two-frame normalized RGB maximum difference is zero. Confidence masks agree
exactly with the CPU for all compared tiles, including edges/corners. Repeated
GPU flow execution is bit-identical on this device; CPU repeated fields are equal.

Additional tests cover all CFA phases with packed, padded, odd-origin crops; flat,
rank-one and NaN-contaminated inputs; partial edge/corner support; Gaussian DC
preservation/alias attenuation; reverse consistency; high-residual rejection;
noninterpolated discontinuity lookup; and fixed refinement/search schedules.

Acceptance limits are unchanged: static ≤0.05 quad pixels; synthetic subpixel MAE
<0.45 per axis; reliable interior coverage ≥80%; normalized RGB difference ≤0.015;
no nonfinite flow/residual or CFA/RB swap. Boundary/rejection tests do not demand
coverage from intentionally invalid support.

Exports include CSV and flow/residual/confidence PNGs for every diagnostic case,
written before comparison assertions. Residual images use fixed [0,0.12] scaling;
confidence images are exact black/white masks. The final run produced 52 files:

- Device: app-private `cache/rawsr-debug/`
- Local copy: `app/build/reports/rawsr-alignment-mali-2026-09-10/rawsr-debug/`

Sequential packed-RAW processing remains bounded: 64×48 bursts of 2/8/15/30 frames
all peak at **188,368 tracked texture bytes** with the new pyramid (driver overhead
and retained source Images excluded). Reverse alignment uses the same already
allocated pair of pyramids and releases its temporary flows per moving frame.
Adreno, real-burst validation and full-resolution performance remain separate
qualification work; the truthful capture reference fallback remains active.
