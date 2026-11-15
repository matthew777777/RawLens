# Green reconstruction zipper: root cause and correction

The detail bandwidth change in `2b9d36d7` introduced a separate reconstruction alias. It affected static buildings as well as moving subjects. Motion rejection alone cannot fix it.

## Mechanism

Comparison green kernels multiplied the inverse covariance by `4*scale*scale`, then multiplied Gaussian weights by the same area factor. At 2x the minimum raw-space standard deviation was roughly 0.12 sensor pixels, much smaller than the green checkerboard sampling interval. The discrete Gaussian's mass and first moment therefore changed sharply with subpixel Bayer phase. Dividing by accumulated mass corrects constant values but does not correct the displaced sample centroid. Edges followed that centroid, producing repeated diagonal teeth. Increasing output scale made the problem worse.

A synthetic burst reproduces the problem with exact, spatially constant translations and a known straight edge, ruling out optical-flow errors as a necessary cause. A local slope correction using a separate slope per frame was insufficient: it improved 2x but degraded 1x edges. Simple cubic interpolation removed the strong corrugation but sacrificed too much static detail.

## Implemented reconstruction

Only the existing KernelNet colour-difference comparison-green path changes. Green samples lie on an integer diagonal lattice:

- Sensor indices: `j=u+v+1`, `i=u-v`.
- Query in sensor centre coordinates: `u=(x+y-2)/2`, `v=(x-y-1)/2`.

Reconstruct green with a separable interpolating cubic on this lattice. A symmetric lattice second difference compensates interpolation attenuation: `K(t)=(1+2a)C(t)-a[C(t-1)+C(t+1)]`, with `a=0.125*clamp(scale,1,2)`. Both the interpolator and compensation reproduce constants and linear ramps, so detail recovery does not introduce the old displaced-centroid bias. Bound the estimate to the observed six-by-six support range to contain ringing. Border extension preserves CFA parity.

The comparison-frame green weight is now its integrated Gaussian green mass, `pi*sqrt(det(cov))*robustness`, independent of Bayer phase. The model covariance therefore controls comparison-frame mass rather than the green interpolation footprint. This is a deliberate change from direct narrow green splatting to combining continuously reconstructed green frames. The reference Gaussian fallback, R/B colour-difference accumulation, robustness, flow and legacy Wronski path retain their existing math.

The shader needs no additional resident images, JNI arguments or host stages. It reads 36 green taps per comparison/output pixel and precomputes the six axis weights. The change applies at both 1x and 2x.

## Verification

- `KernelNetFlowTest`: GPU interpolation oracle, exact affine-intensity reproduction in the interior, both scales, affine/constant flow, borders and gather continuity. Non-detail reference/chroma outputs remain bitwise identical to the legacy KernelNet path.
- `KernelNetGreenEdgeTest`: an analytically defined, sensor-pixel-integrated diagonal edge in a 12-frame translated Bayer burst. Ground-truth contour thresholds are 0.05 sensor pixels at 1x and 0.04 at 2x; the previous narrow reconstruction fails. An edge-width limit also rejects a broad-blur workaround.
- Independent numerical reproduction: final contour RMS approximately 0.0424 at 1x and 0.0326 at 2x, versus 0.0580 and 0.1352 for narrow splatting. These are contour measurements on sampled images; even the sampled ground truth has nonzero interpolated-contour error.
- Native-backed pipeline suite: 268 tests, 76 skipped, 192 executed, zero failures/errors. Forced rerun after shader optimization; a normal Gradle invocation alone can reuse results because an external SPIR-V file changing at the same property path is not a declared test input.
- Original 20-frame `IMG_20210228_133623` burst: inspected the two moving people and static roof/rail/window crops, plus generated full 4032x3024 and 8064x6048 RGB DNGs. The moving-subject diagonal teeth are visibly reduced. Full outputs retain strong roof edges relative to the LK-R Lite comparison; that local measurement is not a general image-quality score.
- Full host Vulkan runs: 20.3 seconds at 1x, 27.2 seconds at 2x on this Mac. Android performance and visual acceptance have not been checked in this task.

Review files:

- `/tmp/IMG_20210228_133623_lattice_1x/merged.dng`
- `/tmp/IMG_20210228_133623_lattice_2x/merged.dng`
- `/tmp/srp-zipper-fixed-comparison.png`

These are review candidates; owner acceptance should use the full DNGs, including static detail, moving edges and smooth areas. No claim is made that every pre-existing colour/rejection artifact is eliminated.
