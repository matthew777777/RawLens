# Live scope exposure correction

References:

- Android Camera2 CaptureResult documentation: https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_DYNAMIC_WHITE_LEVEL
- Android dynamic black level documentation: https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_DYNAMIC_BLACK_LEVEL
- Google Filament tone mapping implementation (the pinned AgX reference used by AgxDisplayTransform): https://github.com/google/filament/blob/2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad/filament/src/ToneMapper.cpp

Android documents dynamic white as the maximum raw value for the frame and explains that black levels can change with capture settings. Live scopes now prefer dynamic levels, including physical camera results when present, and fall back to static characteristics.

Code finding: the live JPEG scope previously multiplied a normalized sensor value by exposure gain and then clamped it to the 0..1 LUT domain. Values above 1 therefore all mapped to curve(1), even though the tone curve accepts higher scene-linear values. The live LUT now incorporates exposure during construction, before lookup. Its cache includes exposure EV as well as JPEG settings.

Regression tests cover distinct bright values above unity, changes in exposure EV, histogram and waveform movement, cached LUT reuse, and the unchanged linear RAW exposure policy. These are synthetic tests; the supplied phone scenes still require on-device verification. JPEG scopes remain per-channel tone-curve approximations, without the full development color transform.

## Source audit

The pinned Filament source was downloaded and its SHA-256 verified as
`1e3212b67f2954721a4336c68fef1904204873835896e25c9ba77f9030aa42cd`, matching the project reference.

- [ToneMapper.cpp lines 714–715](https://github.com/google/filament/blob/2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad/filament/src/ToneMapper.cpp#L714-L715) define the AgX log exposure bounds. The upper bound is about 4.026, corresponding to a scene-linear value around 16.3, above 1.
- [ToneMapper.cpp lines 763–774](https://github.com/google/filament/blob/2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad/filament/src/ToneMapper.cpp#L763-L774) apply the inset matrix, log2 conversion, exposure-range normalization, then clamp. There is no input clamp at scene-linear 1.
- [Filament ColorGrading.h](https://github.com/google/filament/blob/main/filament/include/filament/ColorGrading.h) documents exposure before tone mapping and a factor of two per stop.
- [AOSP CaptureResult.java](https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/camera2/CaptureResult.java), the comments immediately preceding SENSOR_DYNAMIC_BLACK_LEVEL and SENSOR_DYNAMIC_WHITE_LEVEL, specify per-frame sensor levels, CFA ordering, and optional availability.

Audit correction: the old scopeEncode comment claimed LUT-domain saturation mirrored saved JPEG highlight clipping. That was unsupported and has been removed. The full Filament transform includes color matrices; the per-channel scope LUT does not reproduce that full transform. Live unpaired RAW frames currently use the latest preview result, so their sensor metadata is approximate until an exact pair is available. No phone reproduction has established that the two corrected defects explain every supplied scene.
