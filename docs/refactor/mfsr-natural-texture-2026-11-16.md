# Remove inverse-filter oversharpening from green reconstruction

The foliage and building examples reported on IMG_20261112_125318_179 exposed
excessive fine-frequency amplification in the continuous green sampler.
The previous second-difference compensation used
K(t)=(1+2a)C(t)-a[C(t-1)+C(t+1)], a=0.125*scale.
Its discrete prefilter response is 1+4a*sin(omega/2)^2 on each diagonal
lattice axis. At diagonal-lattice Nyquist the separable gain reaches 2.25
at 1x and 4 at 2x. Constant/linear reproduction and a straight-edge contour
check do not constrain this frequency gain. Dense foliage, fence texture
and railings therefore looked etched and developed repeated hard contours.
Clamping to the support range contained extrema but not this amplification.

Remove the second-difference boost and retain the interpolating cubic.
Continuous flow, colour-difference reconstruction, motion rejection and uniform
six-frame luminance grain policy remain in place. This intentionally accepts
less edge acutance in exchange for a more natural reconstruction at 1x/2x;
it does not restore a wide Gaussian post-filter or generated grain.

Validation:

- New GPU KernelNetTextureGainTest measures the RMS of known fine diagonal
  waves. The previous shader fails (1x RMS 0.05644, limit 0.055); the new
  sampler passes and also exceeds a minimum texture-energy bound.
- 272 pipeline tests: 196 executed, 76 skipped, no failures/errors with the
  final shader. Grain, flow and straight-edge contour regressions pass.
- The straight-edge width bound is deliberately changed from 2 to 2.5 sensor
  pixels to permit the unboosted interpolation transition. Its position-error
  limits remain unchanged; arbitrary broad blur still fails the width bound.
- Before/after 512x512 sensor crop at 2304,768 from all 16 new-burst frames
  (2x): visibly reduced harsh periodic texture on the fence and foliage.
  Review image: /tmp/srp-organic-crop-compare.png. This verifies a contributor
  to the reported appearance, not elimination of all Bayer/rejection artifacts.
- Earlier IMG_20210228_133623 moving-subject crop reprocessed at 2x and inspected;
  the strong diagonal teeth do not return. Synthetic coverage tests both scales.
- Full-frame before/after runs were stopped after taking excessively long;
  validation used crops instead. No complete new-burst full DNG was generated.

Also removed 4.25 GiB of disposable earlier experiment outputs from /tmp at
owner request, retaining the latest grain DNGs and current validation crops.
