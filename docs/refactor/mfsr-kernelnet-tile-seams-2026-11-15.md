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
