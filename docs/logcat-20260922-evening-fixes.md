# Evening capture log and SR highlight fixes — 2026-09-23

Inputs inspected:

- `logcat-20260922_231606_231.txt`
- `IMG_20260922_232413_142_LINEAR.dng`

## Highlight artifacts

The supplied DNG is an uncompressed 4080×3060, 16-bit LinearRaw RGB file,
with eight selected/accepted frames and an effective count of 2.1727.
AsShotNeutral is `[0.749634, 1, 0.368611]`. Pixel inspection found 12,044
bright equal-RGB pixels (all three codes identical and above 64000), clustered
on the lampshade. For example, source pixel `(1325,1319)` is
`[65535,65535,65535]`; adjacent pixels retain strongly unequal channels.
A WB-only diagnostic crop shows the colored dots in the stored pixel data,
not just an embedded thumbnail.

The old merge omitted clipped taps from channel kernels, then forced only
the reference's clipped **site** to equal RGB. That left unsupported channels
and CFA-phase discontinuities around those sites. Equal camera RGB also is
not neutral before the DNG's white balance is applied. Mosaic used a related
site-color gate, leaving holes when its scaled target site did not match the
reference site's color.

`RawSrHighlights` now supplies one 3×3 reference-neighborhood mask for all
colors, with a smooth 0.95–0.99 normalized rolloff. Linear CPU/GPU and mosaic
eager/streaming outputs resolve censored chroma toward normalized
AsShotNeutral. The neutral vector stays within the writer's range so
quantization does not clip its channels independently. The mapped mosaic
plane is reused for highlight peaks; no additional full-resolution heap
plane is allocated. The saturation footprint grows by at most one source
pixel. This deliberately neutralizes clipped color/detail; it is not recovery
of information that the sensor did not record.

The LinearRaw DNG writer also incorrectly declared a 2×2 black-level repeat
with only three black values. It now declares a 1×1 RGB tile, matching those
three values. Source files were not modified. New provenance identifies
`RawLens-RawSr/4G-scale1-neutral-highlights` and
`RawLens-MosaicSr/5F-neutral-highlights`.

## RawNIND loading and capture integration

The log reports the absent optional tiny model. Its initialization returned
before attempting the bundled Bayer model, leaving the Bayer readiness latch
pending. Additionally, capture denoising only called tiny inference; the
Bayer RGB entry point had no capture caller.

- Initialization paths are independent. Every attempted model completes its
  latch; readiness requires successful warmup. Initialization and teardown
  share per-model locks, preventing publication of handles after close.
- Capture denoising waits for initialization on the save worker, then uses
  tiny if available or the bundled Bayer model. Bayer predictions are projected
  back onto the source CFA sites for the existing denoise-once DNG/JPEG path.
  Both models preserve the original local Bayer pattern. Strength blending
  occurs on CFA values; this path still uses AMaZE for JPEG development.
- Packing no longer allocates four intermediate full-plane channel arrays.
  Bayer remosaicing reads native RGB output directly, avoiding a second
  full-resolution RGB heap copy.
- Status text distinguishes loading, ready, and unavailable; it no longer
  claims GPU readiness or instructs users to paste asset files.

The upstream [model contract](https://github.com/darktable-org/darktable-ai/blob/master/models/rawdenoise-nind/README.md)
requires scalar gain matching. The existing code omitted this and enabled
FP16 despite the Bayer model's arbitrary learned output scale. A host ONNX
forward of the local Bayer export on a seeded 0.2±0.015 input produced:

- input mean `0.1999949813`;
- output mean `198340.9844`, range `65387.4102`–`262235.0313`;
- required scalar gain `1.0083391544e-6`;
- finite output of shape `[1,3,1024,1024]`.

Bayer NCNN now uses FP32 storage/arithmetic, lightmode, and a 512-pixel tile
(384 core + two 64 borders). Native inference rejects non-finite results.
The entire output gets one gain matched to the normalized input mean before
strength blending, also in the separately exposed RGB API. No model weights
were changed. The host ONNX run validates this numerical issue, not Android
NCNN execution or photographic denoising quality.

## Preview recovery

At 23:24:41.853 and 23:24:56.807 the preview's combined GL/swap assertion
fails. Between them Vulkan logs `submit failed -4`, fence timeouts, and camera
preview recovery. Vulkan documents `-4` as
[VK_ERROR_DEVICE_LOST](https://docs.vulkan.org/spec/latest/chapters/fundamentals.html).

Native code now distinguishes device loss from a generic submission failure.
A lost device immediately disables that tier and schedules recreation,
instead of resubmitting three times. Recovery backoff increases even when
recreation succeeds but presentation fails again; only a presented frame
resets it. GL draw and EGL swap failures now report their separate error codes.
This improves recovery and diagnosis; it does not establish the original
cause of the driver/device loss. Vendor gralloc and missing Xiaomi private
metadata diagnostics remain outside what this log alone can safely resolve.

## Validation

- `:app:testDebugUnitTest`: 695 tests, 693 passed, two skipped (the absent
  optional tiny-model asset contract checks), no failures.
- `:app:assembleDebug`: passed, including native builds for all configured ABIs.
- `:app:assembleDebugAndroidTest`: passed. Updated the existing merged-RGB
  tests to use the current AI settings and assert no added spatial blur.
- Updated merge finalization shader passed `glslangValidator` ES 3.1 validation.
- Regressions cover all Bayer patterns/crop phases, clipped green with
  non-unity neutral, bounded highlight footprint, mosaic streaming parity,
  DNG black-level dimensions, tiny-missing/Bayer-ready initialization,
  failed warmup, capture routing, remosaic phase, gain matching, and device-loss
  policy.
- New device tests exercise the real packed GPU highlight path and bundled
  RawNIND inference. No Android device was connected, so they were compiled
  but not executed. Real lamp captures, SR mosaic demosaicing in external
  converters, preview recovery, and NCNN performance still need phone testing.
