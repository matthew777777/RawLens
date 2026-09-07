# Android DngCreator primary backend

RawLens now separates RAW development from DNG serialization.

## Default

`ANDROID` uses `android.hardware.camera2.DngCreator(CameraCharacteristics, CaptureResult)` and
`writeImage(OutputStream, Image)`. This keeps Camera2 -> DNG translation in the Android framework,
including the framework's NoiseProfile, ActiveArea/CFA/color metadata, bad-pixel metadata, and
OpcodeList2 lens-shading GainMaps when the HAL returns `STATISTICS_LENS_SHADING_CORRECTION_MAP`.
RawLens already requests `STATISTICS_LENS_SHADING_MAP_MODE_ON` whenever the camera advertises it.

RawLens' optional calibration overrides are preserved: the Android writer creates the standards-
compliant DNG first, then `DngMetadataPatcher` replaces only tags that already exist in that DNG.
RAW pixels are not modified by the patcher.

## Diagnostic backends

Settings -> General -> DNG writer backend:

* `ANDROID (official DngCreator)` — default, no custom serializer involved.
* `AUTO (Android -> TinyDNG fallback)` — tries Android first; if framework DNG creation fails, uses
  the patched TinyDNG path. AUTO writes the Android attempt to a temporary file so a partial failed
  DNG can never contaminate the TinyDNG fallback output.
* `TINYDNG (patched fallback)` — forces the existing patched PhotonCamera/TinyDNG writer for A/B
  testing.

The JPEG/development path is unchanged: CAF, AMaZE, noise-aware adaptive development exposure,
lens shading for JPEG, AgX and JPEG encoding remain RawLens code.

## A/B test

Capture the same static scene once with `ANDROID`, then once with `TINYDNG`, without changing camera
settings. For darktable interoperability, first test `ANDROID`; it is now the intended production
path. Keep the TinyDNG result only as a diagnostic comparison/fallback.

Logcat records both requested and actual backends. In AUTO this makes fallback visible:

`RawLensDng: DNG requestedBackend=AUTO actualBackend=ANDROID ...`

or

`RawLensDng: DNG requestedBackend=AUTO actualBackend=TINY_DNG ...`
