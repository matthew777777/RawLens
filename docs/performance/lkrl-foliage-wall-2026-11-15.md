# LK-R Lite foliage, wall and DCT switch

Input: 16 frames in `IMG_20261112_125318_179`, 4080 × 3064.
Compared the supplied `IMG_20261112_125318_179_1x.dng`, the pre-change
full-frame CLI merge, and the corrected full-frame merge. Inspection used
identical white balance/exposure/gamma, including sensor rectangle
`x=1800, y=0, w=1024, h=1024`, rotated clockwise for the wall view.
These are camera-RGB diagnostic previews, not a colour-managed export.

## Causes and changes

- Geometry changed after the first merge but rejection weights remained frozen.
  Recompute weights after motion updates; `LKRL_WONCE=1` retains the old research
  option. This adds a weight-chain refresh and may increase phone merge time.
- A noise-only cap on temporal texture tolerance was initially tried, then
  removed after the car burst `IMG_20260919_184641_979` at 2x exposed false
  rejection of foliage/rocks. Curvature also represents CFA/interpolation
  model error, which remains significant in clean static texture. Retain the
  original curvature allowance and existing gradient/noise tests.
- Hybrid chroma was always spatially blurred (radii 1/2), even in daylight.
  Guide those taps with colour differences in the aligned average so the
  filter cannot freely mix a green leaf and grey wall. Keep fit chroma as
  the source, preserving the earlier repeating-pattern/zipper mitigation.
- DCT off silently enabled lost-frame DCT with luma threshold 2, although
  DCT on used luma threshold 1. Fallback now uses zero luma shrinkage and no
  texture-energy shadow boost. Both CLI and JNI use this shared adapter.
- Colour DCT threshold 16 (squared to 256 in the shader) erased leaf colour.
  Default is now 2, squared to 4. `LKRL_FB=0` also correctly disables fallback;
  previously merely defining that variable enabled it.

## Experiments

Controls included fallback off, chroma radius zero, refreshed weights,
strict temporal rejection (`--tdet 0 --ttau .15 --tk 2`), strong motion
regularisation (`--llam 5`), repeated-reference burst, and DCT explicitly on.
The first 1024 crop at (1500,900) was useful for general foliage; the wall
crop above corresponds to the reported issue. Crop-local noise fits differ
from the full-frame fit, so the final review uses a full-frame run.

Full-frame fit: a=2.14e-5, b=1.04e-6, sigma=0.00277. KernelNet is bypassed
at this noise level; disabling it did not explain this burst's blur.
KernelNet and hybrid noise-gated luma refinement remain independent of the
DCT switch. The switch controls DCT luma smoothing, not every denoise stage.

The corrected crop retains more wall cracks, branch separation and leaf
colour. Some leaves remain soft in the captured reference; no ground-truth
sharp image exists for a quantitative sharpness claim.

## Validation

- Host CMake build, including all changed shaders.
- Android `:app:assembleDebug` passes, including the final incremental build
  after the last shader/settings changes (2.3.45). APK built, not installed.
- `lkrl_denoise_check`: shared adapter at 1×/2×, fallback luma error below
  5.3e-8, while colour cleanup is active. Explicit prior changes luma (MAE
  0.00219 / 0.00157 on the noisy moving-bar fixture). Green/grey edge test
  at radii 1/2: maximum error 7.5e-9; the old unconditional filter fails.
- Existing `lkrl_kernelnet_check`: all six cases pass, including byte-identical
  low-noise bypass and reduced flat-field variance at 6%/10% noise.
- Real full-frame 1× DCT-off output and 512-sensor-pixel 2× DCT-on wall crop
  complete successfully.

Review artifacts in `/tmp/lkrl-leaves/`: `review-1x.dng`,
`review-2x-crop.dng`, and `review-comparison.png` (supplied/corrected).
Timing runs overlapped native Android compilation and are not a performance
benchmark. Device runtime/performance validation is still needed.
