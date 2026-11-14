> Follow-up: [whole-path review and corrections, 2026-11-05](ffdnet-denoise-review.md). The colour guard now filters prediction differences rather than absolute chroma. Earlier results below describe the preceding revisions.

# Standalone FFDNet photo denoising

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Date: 2026-11-04

## Current revision: constrained colour transfer

The default implementation now constrains the stride-4 chroma prediction using
`lkr_colour_guard.h`. It first applies a radius-1 edge-aware chroma filter guided
by the first pass RGB and the existing fitted noise variance. It then subtracts
three separable radius-4 box passes of the opponent-colour difference from the
first pass. Thus the phase predictor supplies fine chroma cleanup, while the
first pass supplies broad colour. The guard does not estimate a new white balance,
neutralize the image, or globally reduce saturation. Experimental extra luma
transfer remains zero by default.

The filtering addresses fine patterns left by recombining the independent phases;
mean colour anchoring alone left those patterns. Radius-1/2 filtering and radius-4/8
colour anchoring were compared locally; radius-1 and radius-4 retain a conservative
small-support filter. Test results remain limited to these samples and synthetic
checks; this is not evidence of general optimality.

The measured added mean R−(G+B)/2 relative to the first pass becomes:

| Scene | Prior phase pass | Guarded pass |
| --- | ---: | ---: |
| Indoor | +0.00002220 | +0.000000045 |
| Sunset | +0.00004363 | +0.000000066 |
| Flower | +0.00016419 | −0.000000084 |
| Architecture | +0.00006580 | −0.000000543 |

These are unexposed linear-display differences from the first pass, not errors
against clean ground truth. Local coarse colour differences are reduced but not
zero: 32×32-block red-difference RMS is reduced approximately 71%, 78%, 73%, and
47%, respectively. Fine red-opponent Laplacian MAD is lower than both the prior
phase pass and first pass in all four crops, but includes real texture and is not
a noise or accuracy score. The 4×4 phase mean variation drops in the three
indoor/sunset/flower samples; architecture remains close to the first-pass value.

Artifacts: `artifacts/ffdnet-study/colour-guard-comparison.png` (first, previous,
guarded), `SCENE/colour-guard-comparison.png` (previous versus guarded), and
`colour-guard-metrics.json`. `SCENE/colour-guard.png` contains each full 896×896
result. The inputs and fixed rendering are unchanged. Native output was used for
these files; the Python guard experiments are separate references.

Known tradeoff: broad chroma noise already in the first pass is preserved along
with genuine broad colour. Fine colour transitions can still be affected. The
added red bias is substantially reduced in these tests, but real colour accuracy
has not been established; a clean target/colour chart and more scenes are needed.
The original sunset image already has a magenta appearance. This guard does not
claim to correct that original tint. Android GPU/NPU runtime remains unmeasured.
The entire standalone FFDNet option remains disabled by default.

Peak additional frame storage is now three RGBA16F copies (24 B/pixel), plus network
scratch and bounded width-by-band filter scratch (about 14 MiB at width 4080).
Noise-fit storage is freed before inference. Allocation or processing failure
leaves the source intact. The band size is 128 rows, with the complete 12-row box
filter halo; output is staged until all bands succeed.

`lkr_colour_guard_check` passes on known saturated colour regions with a luminance
ramp, an injected colour offset and a periodic chroma error: opponent MSE falls
from 0.00049990707 to 0.0000061520261; mean opponent residual is about −4.8e-6;
luminance error stays below 0.000107. A neutral-patch check, alpha preservation,
non-finite-output failure, and band-size 37 versus full-image agreement also pass.
This synthetic test does not measure real-DNG accuracy. The earlier native checks
and 16 focused Android denoise unit tests still pass; both Android ABIs compile.

Use `compare_ffdnet.py --no-colour-guard` to reproduce the unconstrained phase
prediction. The desktop runner's final optional parameter is `COLOUR_GUARD` (0/1).
The remaining sections document investigation history and superseded variants.


## Chroma correction after visual review

The original single-pass result left substantial visible red/green speckle and
blotches. Its high-frequency statistic was not evidence of adequate chroma cleanup;
the earlier summary overstated that improvement. Original results remain as a baseline.

The revised app default adds a second FFDNet stage on 16 stride-4 pixel phases,
then transfers **only WB-camera opponent chroma** onto the first result. Each phase
contains pixels four original pixels apart; this is a decorrelation adapter, not
averaging/downscaling or a global saturation adjustment. The same fixed GAT range
and original noise fit are used for every phase. Only the final combined result
is blended against the original with the strength slider.

This follows the adaptation rationale of [When AWGN-based Denoiser Meets Real
Noises](https://arxiv.org/abs/1904.03485). We do not implement that paper's complete
noise estimator/refinement pipeline, and do not claim its benchmark results.
A stride-2 variant was also tested and left more visible colour speckle in the
indoor and sunset examples. Stride 4 gives a larger improvement in these crops.

`artifacts/ffdnet-study/chroma-revision.png` shows original, first version, and
revised chroma on the same three scenes at 1:1 scale, with unchanged rendering.
`indoor/chroma-revision.png` is a smaller first-versus-revised comparison.
Individual revised results are `SCENE/chroma-final.png`; intermediate stride-2/4
experiments are `SCENE/chroma-experiments.png`. `first/chroma-final.rgba16f`
also checks the architectural/foliage input with the revised default.

WB-camera luminance uses weights (0.2126, 0.7152, 0.0722) after WB; it is not
exact display luminance after the camera colour matrix. The maximum change from
the first result over the indoor/sunset/flower images is respectively 0.0001043,
0.0000151 and 0.0001363 in normalized linear units (half-float rounding). Fine
monochrome noise remains deliberately; this is not a claim of complete denoising.
The earlier real-DNG comparisons have no clean targets, so no PSNR claim is possible.

Native checks additionally cover stride-2/4 phase coverage, partial tiles and phase
tile boundaries, WB-camera luminance preservation, retained nonzero opponent colour
differences, alpha, invalid predictions, and failure after earlier successful work.
The source buffer is now committed only after both stages and the final blend succeed,
including allocation failure. Peak additional image storage is two RGBA16F copies
(16 B/pixel), plus bounded tile/network scratch. The second pass costs extra inference;
small crops in particular pay padding overhead. Android GPU/NPU runtime remains unmeasured.

Use `compare_ffdnet.py --chroma-stride 0` to reproduce the first version,
`--chroma-stride 2` for the intermediate experiment, and the default `4` for the revision.

The sections below record the original design and first-pass investigation.


## Decision and research

Reuse the bundled KAIR colour FFDNet through the existing generalized Anscombe
transform (GAT) adapter. Run on pre-WB camera-linear RGB after demosaic,
before capture sharpening, colour conversion, tone mapping and encoding.
This is a practical first implementation using existing weights and inference
backends, not a demonstrated optimum across all camera models.

- [FFDNet original paper](https://arxiv.org/abs/1710.04026): Gaussian denoiser
  conditioned by a noise map, designed for adjustable noise and efficient inference.
- [KAIR reference inference](https://github.com/cszn/KAIR/blob/master/main_test_ffdnet.py):
  distinguishes ordinary and clipped-noise weights. Reuse our ordinary colour
  weights in the existing stabilized float domain, rather than passing encoded JPEG pixels.
- [Mäkitalo–Foi inverse-transform research](https://webpages.tuni.fi/foi/invansc/index.html):
  stabilizing Poisson-Gaussian noise, Gaussian denoising, then a suitable inverse
  transform avoids the bias of a naive squared inverse. We retain the existing
  closed-form approximate inverse, not an implementation of the exact inverse.
- [Noise2VST, ICCV 2025](https://openaccess.thecvf.com/content/ICCV2025/html/Herbreteau_Self-Calibrated_Variance-Stabilizing_Transformations_for_Real-World_Image_Denoising_ICCV_2025_paper.html):
  supports adapting fixed Gaussian denoisers to real noise with a VST. Its learned
  per-image VST is a separate algorithm and is not implemented here.

Packed Bayer is not the input of the bundled three-channel colour weights.
A post-render JPEG also has tone-dependent, spatially correlated processing noise.
The existing linear RGB tap is therefore the most direct integration point for
these weights. Demosaic still correlates noise, so real-image testing remains necessary.

## Implementation

Settings → Denoise → FFDNet photo denoise, disabled by default, 0–100% source/output
blend. Active FFDNet replaces post-demosaic wavelet; existing pre-demosaic RawNIND
can still stack if the user enables it. To evaluate FFDNet alone, turn RawNIND off.
Single-frame Bayer JPEG/JXL entry points share the stage. Quad Bayer and merged
HDR+/MFSR/LK-SR entry points bypass it. Sensor DNGs remain unchanged.

The native implementation is shared verbatim between Android JNI and the desktop
validation CLI (`lkr_photo_denoise.cpp`). It re-samples CFA lattice sites from
camera RGB for the existing robust scalar Poisson-Gaussian fit. This fit uses
both green planes and is an approximation for other channels and demosaiced noise;
its existing insufficient-data fallback is retained. The positive GAT slope floor
is 1e-5. WB is normalized to green=1.

512×512 tiles, 32-pixel halo, 448-pixel retained core, even tile origins, clamped
image boundaries. The 32-pixel halo exceeds the shipped model's 24-pixel full-size
receptive radius. One image-wide GAT normalization range prevents tile-dependent
normalization. Odd dimensions and partial edge tiles are supported. The model
uses ncnn FP32 with an optional existing 512 LiteRT model on Android; NPU-open
failure uses ncnn. Inference failure keeps the original image; it does not switch
backend and retry mid-image.

Output is committed only after every tile succeeds. One RGBA16F output copy costs
8 B/pixel, plus bounded tile/network scratch. Noise estimation temporarily uses
one full-resolution float CFA copy (released before inference). Alpha is copied
unchanged. No replacement model weights or new training are involved.

## Local DNG iterations

`tools/lkr-pipeline/compare_ffdnet.py` renders 35%, 65%, and 100% blend comparisons.
It uses LibRaw AHD, unity-WB camera RGB input, the camera matrix, fixed WB and fixed
exposure, and an sRGB transfer function. Both sides have identical rendering.
This is the same native denoiser but **not** the app's AMaZE/ACES/AgX renderer.
Demosaic is full-frame before selecting the test region; denoising and its noise
fit run on the selected 896×896 region. Full-frame app noise estimates can differ.
The later blend variants use the half-float full-strength prediction without
re-inference; intermediate-blend half rounding may differ from the app by an LSB.

Inputs, preserved read-only:

| Scene | Local source | Fixed display exposure |
| --- | --- | --- |
| Architecture/foliage | Downloads/IMG_20261009_104928_875.dng | ×2 |
| Indoor equipment | Downloads/hdrplus_subset/0009_20160702_154921_383/frames/payload_N000.dng | ×4 |
| Sunset/mountain shadows | Downloads/hdrplus_subset/0543_20150918_185854_904/frames/payload_N000.dng | ×8 |
| Flower detail | Downloads/hdrplus_subset/0030_20151214_101221_477/frames/payload_N000.dng | ×2 |

Results are in `artifacts/ffdnet-study`, including 1:1 before/after crops,
strength contact sheets, individual PNGs, parameters and native logs. Full strength
reduces fine colour speckle and preserves major edges in these samples. Fine petal
texture softens; 65% is a useful alternative when texture matters more than maximum
noise reduction. Lifted sunset shadows still show low-frequency colour noise.
There is no clean reference: these comparisons do not establish reconstruction
accuracy, PSNR, or absence of detail loss. No new inference noise multiplier was
selected solely from these four crops.

A descriptive high-frequency R−G MAD drops by approximately 35% indoors, 59% in
sunset shadows, and 50% on the flower crop at full strength. This statistic includes
texture and must not be interpreted as a pure noise or quality score.

## Validation

- Android Kotlin and native builds pass (arm64-v8a and armeabi-v7a).
- 16 focused denoise unit tests pass.
- Native checks pass: identity, zero strength, failure after an earlier successful
  tile leaves input intact, non-finite prediction rejection, alpha preservation,
  and partial edge tiles.
- Real-model tiled versus padded whole-image inference: maximum absolute output
  difference 0.00048828 (half-float quantization scale).
- Synthetic smooth signal with known Poisson-Gaussian noise: MSE
  0.00032815427 → 0.0000019339354. This is a synthetic sanity check, not real-DNG PSNR.
- Full unit suite encountered one unrelated `ProgramAeLoopTest` expected-exposure
  failure; no exposure code was changed.
- Local DNG runs used ncnn CPU. Android GPU/NPU runtime and full-resolution memory/
  timing have not been measured in this task. This remains opt-in pending that work.

Reproduce native checks:

```sh
cmake -S tools/lkr-pipeline/src/main/cpp -B tools/lkr-pipeline/build/host
cmake --build tools/lkr-pipeline/build/host --target lkr_denoise_run lkr_denoise_check -j 4
tools/lkr-pipeline/build/host/lkr_denoise_check \
  app/src/main/assets/models/lkr_ffdnet.ncnn.param \
  app/src/main/assets/models/lkr_ffdnet.ncnn.bin
python tools/lkr-pipeline/compare_ffdnet.py INPUT.dng --out OUTPUT --crop 896 --exposure 2
```


## Luma transfer experiment

The same stride-4 prediction was tested with 0%, 50%, and 100% of its luminance
transferred, keeping its chroma identical across these variants. See
`artifacts/ffdnet-study/SCENE/luma-comparison.png` for indoor, sunset, flower,
and architecture (`first`). These are 1:1 crops with the same display settings.
The effect is subtler than the chroma correction. Fine grain and some fine
structure change; full transfer is not established as better on these samples.
The Android default remains zero additional phase-luma transfer; the original
full-resolution FFDNet pass still denoises luma.

The desktop runner accepts optional `CHROMA_STRIDE LUMA`, and
`compare_ffdnet.py --phase-luma 0.5` runs the native 50% experiment. Values outside
[0,1] and non-finite values are rejected. The native adapter now supports this
parameter with zero as its default; no app control/default was changed. Tests
check target luminance interpolation and invariant opponent colour differences
at 50% and 100%, along with invalid values and unchanged alpha.
The comparison PNGs at intermediate luma were generated by interpolating the
half-float endpoints, which can differ from native intermediate output by an LSB.


## Colour-bias audit after red/magenta feedback

The revised chroma pass must not be treated as colour-validated. Comparing
32×32 block averages in linear display RGB shows a positive mean shift in
R − (G+B)/2 relative to the first pass in all four samples. The means are
+0.0000222 (indoor), +0.0000436 (sunset), +0.0001642 (flower), +0.0000658
(architecture), in normalized linear display units before display exposure.
These are shifts from the prior output, not error against clean ground truth.
The original sunset DNG already has a magenta component; the revision is not
the sole source of that appearance.

See `artifacts/ffdnet-study/colour-audit.json` and each scene's `colour-audit.png`.
The PNGs average 32×32 pixels before the fixed render to expose coarse colour
instead of fine grain. The chroma projection preserves a chosen WB-camera
luminance, but does not constrain hue or low-frequency channel bias. The exact
cause of the model's shifts has not been isolated; phase-specific inference,
noise-model mismatch and the transform/inverse remain candidates. Additional
luma transfer does not enforce colour fidelity and is not a fix for this issue.
No corrective white balance or global desaturation has been applied to disguise
it. The standalone stage remains opt-in and the red-bias issue is unresolved.
