# RawLens

RawLens is a photography-first, open-source Android camera built around a scene-referred RAW pipeline. It captures full-resolution Camera2 sensor data as DNG files and develops calibrated RAW JPEG output while providing direct exposure controls and a minimal portrait-oriented viewfinder. The app has no gallery, cloud sync, or account system; its only network use is the optional in-app update check (disabled in F-Droid builds).

> [!IMPORTANT]
> Hardware support is device-dependent. RawLens requires a rear camera that advertises the Camera2 `RAW` capability. Some manufacturers expose RAW only on selected lenses or restrict simultaneous stream configurations.

## Features

- Full-resolution `RAW_SENSOR` capture saved as DNG
- Optional RAW zero-shutter-lag capture from a bounded pre-shutter frame buffer
- Single-frame and six-frame burst release modes
- AUTO AE, PROGRAM custom RAW-driven AE, ZSL RAW motion selection, and MANUAL exposure modes
- Manual ISO, shutter speed, white balance, and exposure compensation
- Auto, Center Weighted, Frame Average, and Spot AE metering
- PROGRAM custom AE: shutter-vs-ISO priority slider (ISO priority by default), center / average / spot RAW metering, highlight guard, damped closed loop gliding toward target at ~1 stop/s, per-lens ISO/shutter min/max, ISO-lock / shutter-lock auto, neutral brightness bias, automatic handheld shutter limit; converged ETTR freezes PROGRAM and owns the exposure
- Adaptive RAW development exposure for AUTO/ZSL and adjustable PROGRAM strength, shared across bursts
- Tap/drag focus and exposure metering targets
- Optional face-priority AF/AE from HAL face detection (Settings → Exposure), with face boxes on the viewfinder; tap targets override faces
- RAW-capable lens discovery and lens switching
- Live histogram, rule-of-thirds grid, rotation-vector horizon/level guide with gravity fallback, 2/5-second timer, torch, OIS toggle, and Camera2 diagnostics
- Per-camera DNG calibration overrides for black/white levels, noise profile, and color/calibration/forward matrices
- RAW JPEG development with AgX Base, Golden, and Punchy looks; contrast, saturation, purity, hue preservation,
  highlight/shadow range, and gamut-compression controls
- Optional JPEG XL output (Settings → JPEG → JPEG XL output): the same AgX develop saved as `.jxl` up to
  16-bit integer (plus 16/32-bit float) with darktable-style bit depth, quality, effort, and decoding-speed
  options and embedded EXIF/XMP; requires a libjxl-enabled build (bundled automatically when the build
  configuration enables bundled libjxl or supplies a libjxl checkout)
- Optional lossless JPEG XL DNG (Settings → General → DNG compression, off by
  default): TinyDNG encodes single-frame RAW16 and 16-bit HDR+ mosaics directly,
  with DNG 1.7 version tags and retained EXIF/GPS. Requires a libjxl-enabled build
  and a compatible DNG reader. Packed input and float-CFA exports retain their
  uncompressed paths. See [DNG integration](docs/tinydng-integration.md).
- Optional AI RAW denoise (RawNIND-tiny): writes a denoised DNG and develops the JPEG from it, with an option to keep the original sensor DNG
- Portrait viewfinder layout

RAW ZSL is opt-in under **Settings → General → RAW zero shutter lag**, or by selecting the `ZSL` capture mode. The ZSL buffer can be set from 1 to 59 saved frames (2 by default), targeting a 30 FPS stream where the device advertises a compatible Camera2 range. At 30 FPS, 30 frames represent approximately one second of pre-shutter history. The effective ring is clamped to what the HAL buffer queue fits (pairing plus transition headroom is reserved first), so deep requests degrade gracefully instead of failing. JPEG/JPEG+DNG selections are bounded to six frames for development memory; DNG-only can save the whole effective ring. The RAW badge changes to `RAW • ZSL` after the first paired frame is buffered. `ZSL …` means the buffer is warming, `ZSL OFF` means the setting is disabled, and `ZSL N/A` means RawLens has automatically fallen back to ordinary RAW capture.

The ZSL implementation continuously pairs full-resolution RAW images with Camera2 metadata by sensor timestamp. It requests the fastest advertised compatible AE range up to 30 FPS and retains only the configured number of recent frames, bounded by the configured ring and `ImageReader` capacity. On shutter press, it considers only fresh frames whose exposure and rolling-shutter readout completed by that instant, then scores them using recency, autofocus state, auto-exposure state, lens movement, ISO, exposure duration, and gyroscope motion. Request epochs prevent frames from an earlier control state entering a new buffer. The separate release control saves either one frame or six frames during ordinary forward capture.

RawLens uses the Camera2 application-operated ZSL request template where the camera advertises a compatible reprocessing capability, and a direct preview-plus-RAW repeating request otherwise. If the full-resolution stream combination is rejected, does not produce paired frames, or exceeds the memory budget, capture automatically falls back to an ordinary forward RAW request.

ZSL and PROGRAM custom AE are mutually exclusive; MANUAL disables exposure compensation and uses the current metered pair when entered. Converged ETTR freezes the PROGRAM loop and owns preview + still exposure from the actual sensor values; otherwise PROGRAM falls back to its own RAW-measured pair. Automatic digital capture exposure is suspended under PROGRAM (sensor ISO + shutter are driven directly); JPEG development keeps its adjustable adaptive-exposure trim. Cameras without manual sensor control keep using Android AE, with PROGRAM locks and limits disabled.

## Current UI

The viewfinder includes the mode switcher, RAW status, lens switcher, manual control chips, focus/exposure metering targets, histogram, guide overlays, quick controls, and shutter. The AE METER quick tile is dual-function: in PROGRAM it cycles center / average / spot RAW metering, otherwise it cycles the hardware AE metering. In PROGRAM, tap an ISO/S chip for that axis's slider (Auto releases both locks); hold a chip to lock that axis and let the other adjust; hold the second locked axis to enter MANUAL. The full per-lens editor (priority slider, metering, locks, min/max bounds, brightness bias) opens from the axis sliders and Settings; ISO/shutter bounds are also linked from each lens's RAW DNG calibration editor. Settings contains General, JPEG, Exposure, Burst, Denoise, Lenses, Debug, and About tabs. General keeps RAW capture, DNG backend, location, and viewfinder; JPEG holds JPEG output, AgX, adaptive exposure, and highlight handling; Exposure holds HDR brackets, per-lens PROGRAM bounds, and ETTR; Burst holds RAW ZSL, super-resolution, and sidecar controls; Debug holds HDR debug frames, viewfinder overlays, and app logs. Swiping left or right on empty space flips between tabs like book pages. Lens discovery filters Camera2 IDs to rear-facing cameras that advertise RAW support and lets the user save a subset of those IDs.

The DNG sensor calibration editor is guided by the active camera's declared metadata. Overrides are stored per Camera2 ID and are injected into native DNG metadata without changing the RAW pixel payload. Resetting a camera returns it to device-declared defaults; undeclared tags are not offered as safe overrides.

## Requirements

- Android 11 or newer (API 30+; GPU video record needs Android 13+ for half-float buffers)
- A Camera2 camera advertising `REQUEST_AVAILABLE_CAPABILITIES_RAW`
- Android Studio with JDK 17, or a compatible command-line Android build environment
- Android SDK 35 for compilation
- CMake 3.22.1 for the native DNG writer

The application requests only camera permission plus one photo-folder grant (asked automatically on first install, re-grantable from burst settings). DNG/JPEG bursts are written through `MediaStore` to `DCIM/RawLens/<stem>`, and gyro sidecars (`burst.json` + `gyro/` CSVs) land next to the DNGs — falling back to `Download/RawLens/<stem>` only until the folder is granted. Copy any split sidecars next to the DNGs before desktop `import`.
See [PRIVACY.md](PRIVACY.md) for the project's privacy statement.

## Build from source

From the repository root:

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

DNG output uses Android DngCreator or the checked-in C11 TinyDNG v3 writer.
The TinyDNG backend owns TIFF layout, checked metadata fields, EXIF/GPS directories,
lens-shading opcode transport and optional native lossless JPEG XL encoding.
RawLens supplies frozen Camera2 metadata and RAW pixels through a small JNI bridge;
it no longer uses PhotonCamera's DNG writer or downloads `tiny_dng_writer.h`.
The shared TinyDNG sources match upstream commit `e1d6442` byte-for-byte.

Android links TinyDNG to the existing libjxl target when available (pinned v0.12.0).
For source builds, supply `-PjxlSourceDir=<checkout with submodules>`; builds with
JXL disabled still support uncompressed DNG. Host tests detect installed libjxl
and libjxl_threads >=0.8 through pkg-config; custom installations use
`PKG_CONFIG_PATH`. TinyDNG's standalone CMake option is `TINYDNG_ENABLE_JXL=ON`.
See [integration and verification](docs/tinydng-integration.md) for details.
Aside from the optional update check (disabled in F-Droid builds), the installed
app makes no network requests at runtime.

To create a distributable release, configure your own Android signing key and build a signed release APK or App Bundle using Android Studio. Signing credentials and keystores must never be committed.

## Desktop super-resolution (no device)

`tools/sr-pipeline` hosts the desktop JVM CLI harness for the multi-frame
super-resolution pipeline (burst frame directory in, super-resolved output
out) for Linux and macOS (M1 included). Start at
[tools/sr-pipeline/README.md](tools/sr-pipeline/README.md):

```bash
./gradlew :tools:sr-pipeline:installDist
./gradlew :tools:sr-pipeline:test
```

## GitHub release checklist

1. Note the auto-bumped version in `app/version.properties` (every `assemble`/`bundle` build advances the patch number and `versionCode` by one; edit the file by hand only for major/minor releases).
2. Update `CHANGELOG.md` and verify that the release notes describe the actual build.
3. Run `./gradlew clean test assembleDebug` and perform capture tests on at least one RAW-capable device in AUTO, PROGRAM, MANUAL, burst, and (when supported) ZSL modes.
4. Build and sign the release artifact with your private signing configuration.
5. Create a version tag, for example `v1.0.0`, from the exact source used for the binary.
6. Publish the signed artifact, checksums, release notes, and corresponding source from that tag together.

## Current architecture

`MainActivity` owns the portrait viewfinder UI, settings, gesture overlays, rotation-aware control placement, and persisted preferences. `RawCameraController` owns Camera2 capture, timestamp pairing, ZSL lifecycle, and fallback behavior. `RawDevelopmentCoordinator` performs normalized RAW validation, corrected pre-demosaic processing, adaptive exposure analysis, AMaZE demosaicing, and JPEG development. `Gles31JpegOutputProcessor` applies AgX and optional Ultra HDR gainmaps. `DngSaver` combines each selected `RAW_SENSOR` image with its exact frozen capture metadata and provides a pending MediaStore stream to Android DngCreator or TinyDNG.
`TinyDngImageWriter` also serializes merged, linear RGB and float-CFA output;
TinyDNG owns lossless JXL encoding for supported RAW16 paths. PROGRAM and ZSL use this identical path. UI overlays and lens discovery are kept in small, separate classes under `app/src/main/java/com/matthew/rawlens`.

## Contributing

Bug reports and focused pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) before submitting code. Device reports should include the Android version, device model, affected Camera2 ID/lens, and the diagnostic overlay when possible.

## License and acknowledgements

RawLens is licensed under the GNU General Public License, version 3 or later. See [LICENSE](LICENSE).

RAW ZSL lifecycle research also used PhotonCamera's pinned `dev` source as a reference, especially its continuous RAW ownership and request-transition draining patterns. RawLens's PROGRAM shutter-first exposure policy acknowledges the related work in commit [`5bb9cf4`](https://github.com/eszdman/PhotonCamera/commit/5bb9cf47fa9313abb00f1eb594b647e0553fd866), authored by `matthew777777`. PhotonCamera is distributed under the GNU GPL. See [NOTICE.md](NOTICE.md) and [references/README.md](references/README.md) for attribution and the exact reference revision.

RawLens is an independent project and is not endorsed by camera manufacturers, Adobe, or the PhotonCamera maintainers.
