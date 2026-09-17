# RawLens

RawLens is a photography-first, open-source Android camera built around a scene-referred RAW pipeline. It captures full-resolution Camera2 sensor data as DNG files and develops calibrated RAW JPEG output while providing direct exposure controls and a minimal portrait-oriented viewfinder. The app has no gallery, cloud sync, account system, or runtime network communication.

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
- RAW-capable lens discovery and lens switching
- Live histogram, rule-of-thirds grid, rotation-vector horizon/level guide with gravity fallback, 2/5-second timer, torch, OIS toggle, and Camera2 diagnostics
- Per-camera DNG calibration overrides for black/white levels, noise profile, and color/calibration/forward matrices
- RAW JPEG development with AgX Base, Golden, and Punchy looks; contrast, saturation, purity, hue preservation,
  highlight/shadow range, and gamut-compression controls
- Optional RAW prefilter, chroma cleanup, luma cleanup, grain retention, and edge-protection denoise controls
- Portrait viewfinder layout

RAW ZSL is opt-in under **Settings → General → RAW zero shutter lag**, or by selecting the `ZSL` capture mode. The ZSL buffer can be set from 1 to 30 saved frames (2 by default), targeting a 30 FPS stream where the device advertises a compatible Camera2 range. At 30 FPS, 30 frames represent approximately one second of pre-shutter history. JPEG/JPEG+DNG selections are bounded to six frames for development memory; DNG-only can save all 30. The RAW badge changes to `RAW • ZSL` after the first paired frame is buffered. `ZSL …` means the buffer is warming, `ZSL OFF` means the setting is disabled, and `ZSL N/A` means RawLens has automatically fallen back to ordinary RAW capture.

The ZSL implementation continuously pairs full-resolution RAW images with Camera2 metadata by sensor timestamp. It requests the fastest advertised compatible AE range up to 30 FPS and retains only the configured number of recent frames, bounded by the configured ring and `ImageReader` capacity. On shutter press, it considers only fresh frames whose exposure and rolling-shutter readout completed by that instant, then scores them using recency, autofocus state, auto-exposure state, lens movement, ISO, exposure duration, and gyroscope motion. Request epochs prevent frames from an earlier control state entering a new buffer. The separate release control saves either one frame or six frames during ordinary forward capture.

RawLens uses the Camera2 application-operated ZSL request template where the camera advertises a compatible reprocessing capability, and a direct preview-plus-RAW repeating request otherwise. If the full-resolution stream combination is rejected, does not produce paired frames, or exceeds the memory budget, capture automatically falls back to an ordinary forward RAW request.

ZSL and PROGRAM custom AE are mutually exclusive; MANUAL disables exposure compensation and uses the current metered pair when entered. Converged ETTR freezes the PROGRAM loop and owns preview + still exposure from the actual sensor values; otherwise PROGRAM falls back to its own RAW-measured pair. Automatic digital capture exposure is suspended under PROGRAM (sensor ISO + shutter are driven directly); JPEG development keeps its adjustable adaptive-exposure trim. Cameras without manual sensor control keep using Android AE, with PROGRAM locks and limits disabled.

## Current UI

The viewfinder includes the mode switcher, RAW status, lens switcher, manual control chips, focus/exposure metering targets, histogram, guide overlays, quick controls, and shutter. The AE METER quick tile is dual-function: in PROGRAM it cycles center / average / spot RAW metering, otherwise it cycles the hardware AE metering. In PROGRAM, tap an ISO/S chip for that axis's slider (Auto releases both locks); hold a chip to lock that axis and let the other adjust; hold the second locked axis to enter MANUAL. The full per-lens editor (priority slider, metering, locks, min/max bounds, brightness bias) opens from the axis sliders and Settings; ISO/shutter bounds are also linked from each lens's RAW DNG calibration editor. Settings contains General, Denoise, Lens discovery, and About tabs. General includes JPEG output, AgX, adaptive exposure, per-lens PROGRAM bounds, and RAW ZSL controls. Lens discovery filters Camera2 IDs to rear-facing cameras that advertise RAW support and lets the user save a subset of those IDs.

The DNG sensor calibration editor is guided by the active camera's declared metadata. Overrides are stored per Camera2 ID and are injected into native DNG metadata without changing the RAW pixel payload. Resetting a camera returns it to device-declared defaults; undeclared tags are not offered as safe overrides.

## Requirements

- Android 10 or newer (API 29+)
- A Camera2 camera advertising `REQUEST_AVAILABLE_CAPABILITIES_RAW`
- Android Studio with JDK 17, or a compatible command-line Android build environment
- Android SDK 35 for compilation
- CMake 3.22.1 for the native DNG writer

The application requests only camera permission. DNG/JPEG bursts are written through `MediaStore` to `DCIM/RawLens/<stem>`, gyro sidecars (`burst.json` + `gyro/` CSVs) to `Download/RawLens/<stem>` — unless the photo folder is granted under Settings → General → sidecars, in which case they land next to the DNGs. Otherwise copy them next to the DNGs before desktop `import`.
See [PRIVACY.md](PRIVACY.md) for the project's privacy statement.

## Build from source

From the repository root:

```bash
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

The DNG target compiles the checked-in PhotonCamera `dngCreator.cpp` directly, generates an exact build copy of PhotonCamera's `DngCreator.java`, and downloads the same unmodified ParticlesDevs `tiny_dng_writer.h` requested by PhotonCamera's CMake project. Still captures use PhotonCamera's `setParameters` → `setCompression(false)` → `writeBuffer(OutputStream, …)` sequence. RawLens only bridges its frozen Camera2 metadata into the parameter fields, repacks a padded RAW plane when necessary, and supplies the pending MediaStore output stream. It does not patch TinyDNG or add a preview image. The installed app does not make network requests at runtime.

To create a distributable release, configure your own Android signing key and build a signed release APK or App Bundle using Android Studio. Signing credentials and keystores must never be committed.

## GitHub release checklist

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update `CHANGELOG.md` and verify that the release notes describe the actual build.
3. Run `./gradlew clean test assembleDebug` and perform capture tests on at least one RAW-capable device in AUTO, PROGRAM, MANUAL, burst, and (when supported) ZSL modes.
4. Build and sign the release artifact with your private signing configuration.
5. Create a version tag, for example `v1.0.0`, from the exact source used for the binary.
6. Publish the signed artifact, checksums, release notes, and corresponding source from that tag together.

## Current architecture

`MainActivity` owns the portrait viewfinder UI, settings, gesture overlays, rotation-aware control placement, and persisted preferences. `RawCameraController` owns Camera2 capture, timestamp pairing, ZSL lifecycle, and fallback behavior. `RawDevelopmentCoordinator` performs normalized RAW validation, corrected pre-demosaic processing, adaptive exposure analysis, AMaZE demosaicing, and JPEG development. `Gles31JpegOutputProcessor` applies AgX and optional Ultra HDR gainmaps. `DngSaver` combines each selected `RAW_SENSOR` image with its exact frozen capture metadata and provides a pending MediaStore stream to PhotonCamera's Java/native DNG saver. PROGRAM and ZSL use this identical path. UI overlays and lens discovery are kept in small, separate classes under `app/src/main/java/com/matthew/rawlens`.

## Contributing

Bug reports and focused pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) before submitting code. Device reports should include the Android version, device model, affected Camera2 ID/lens, and the diagnostic overlay when possible.

## License and acknowledgements

RawLens is licensed under the GNU General Public License, version 3 or later. See [LICENSE](LICENSE).

RAW ZSL lifecycle research also used PhotonCamera's pinned `dev` source as a reference, especially its continuous RAW ownership and request-transition draining patterns. RawLens's PROGRAM shutter-first exposure policy acknowledges the related work in commit [`5bb9cf4`](https://github.com/eszdman/PhotonCamera/commit/5bb9cf47fa9313abb00f1eb594b647e0553fd866), authored by `matthew777777`. PhotonCamera is distributed under the GNU GPL. See [NOTICE.md](NOTICE.md) and [references/README.md](references/README.md) for attribution and the exact reference revision.

RawLens is an independent project and is not endorsed by camera manufacturers, Adobe, or the PhotonCamera maintainers.
