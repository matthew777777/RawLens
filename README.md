# RawLens

RawLens is a photography-first, open-source Android camera built around a scene-referred RAW pipeline. It captures full-resolution Camera2 sensor data as DNG files, develops calibrated RAW JPEG / JPEG XL / Ultra HDR output, and records lossless RAW video and real-time Direct Log video, with direct exposure controls and a minimal portrait-oriented viewfinder. The app has no cloud sync or account system; its only network use is the optional in-app update check (disabled in F-Droid builds).

Current release: **2.0.0** (versionCode 2000). See [CHANGELOG.md](CHANGELOG.md) and [RELEASE_NOTES_2.0.md](RELEASE_NOTES_2.0.md).

> [!IMPORTANT]
> Hardware support is device-dependent. RawLens requires a rear camera that advertises the Camera2 `RAW` capability. Some manufacturers expose RAW only on selected lenses or restrict simultaneous stream configurations.

## Features

### Stills

- Full-resolution `RAW_SENSOR` capture saved as DNG, JPEG, JPEG+DNG, or JPEG XL
- AUTO, PROGRAM, ZSL, and MANUAL exposure modes; single-frame and six-frame (pipelined) burst release
- Optional RAW zero-shutter-lag capture from a bounded pre-shutter frame buffer
- **MFSR** handheld multi-frame super-resolution (Wronski et al. 2019 / IPOL reference) as a ZSL shutter merge or an offline merge, with Wronski or KernelNet reconstruction kernels
- **HDR+** burst merge, HDR brackets (±2 / ±4 EV, merged or separate DNGs), sequential-DCG trio merge (0 / −2 / 0 EV), and experimental two-exposure RAW HDR
- Offline merges of staged `IMG_<ts>_BURST.craw` takes from the Merges screen (MFSR or HDR+)
- darktable **inpaint-opposed** highlight reconstruction on normalized Bayer data before demosaic
- RAW development with AMaZE (or RCD) demosaic and AgX Base, Golden, and Punchy looks; contrast, saturation, purity, hue preservation, highlight/shadow range, and gamut-compression controls
- **Ultra HDR** JPEG (SDR base + fitted gain map, ISO 21496-1) and Display P3 output
- **JPEG XL** output (Settings → Photo): the same AgX develop up to 16-bit integer (plus 16/32-bit float) with darktable-style bit depth, quality, effort and decoding-speed options and embedded EXIF/XMP
- Optional lossless **JPEG XL DNG** (Settings → General → DNG compression, off by default): TinyDNG encodes single-frame RAW16 and 16-bit HDR+ mosaics directly with DNG 1.7 version tags and retained EXIF/GPS. Packed input and float-CFA exports keep their uncompressed paths. See [DNG integration](docs/tinydng-integration.md)
- Denoise: profiled wavelet (Vulkan) and optional AI RAW denoise (RawNIND Bayer), which writes a denoised DNG and develops from it
- Complete capture metadata (aperture, focal length, focus distance, flash, AE/AWB mode, exposure compensation, zoom, lens spec, composite frame counts) frozen at the shutter press, so delayed and offline writers emit the same EXIF as live saves
- Per-camera DNG calibration overrides for black/white levels, noise profile, and color/calibration/forward matrices
- Per-lens vendor keys (OEM request/session keys such as Xiaomi's DCG operating mode)

### Exposure

- **PROGRAM** auto exposure ported from libcamera's Raspberry Pi AGC: Y target with modelled saturation, NORMAL / HIGHLIGHT constraint modes, per-frame exposure filter, flicker rounding; centre, average, spot and highlight-weighted RAW metering; shutter-vs-ISO priority, per-lens ISO/shutter bounds, ISO-lock / shutter-lock, brightness bias, automatic handheld shutter limit
- **ETTR** after Magic Lantern's auto ETTR: 99.9 % percentile of per-pixel max(RGB), headroom 0.5–2 EV, midtone/shadow SNR limits from the Camera2 noise profile; converged ETTR freezes PROGRAM and owns the exposure
- **Active D-Lighting** (Off … Extra high), shared by PROGRAM metering, ETTR headroom and development
- Manual ISO, shutter, white balance and exposure compensation; Auto, Center Weighted, Frame Average and Spot hardware AE metering
- RAW exposure assist (Magic Lantern `zebra.c`): RAW zebras, false colour and a RAW spot meter in EV relative to clipping
- Tap/drag focus and metering targets, optional face-priority AF/AE, focus peaking
- Flash with Open Camera's two-phase precapture wait (Off / On in PROGRAM and ETTR)

### Video

- **RAW video** in the `.craw` (Cinematic RAW Studio) container: lossless predictive codecs (CRAW Live default: codec 3; CRAW Live v1 — Legacy: codec 2; CRAW Balanced/Rice: codec 1), mandatory chunk CRC32C with optional RAW-frame CRC, crash-recoverable streaming, PCM16 audio, gyro/accelerometer in Android axes, rolling-shutter skew, lens-shading maps (LSHD chunk) and a seek index. Float audio is supported by the format, not current capture. See the [CRAW evidence and reproducible JPEG XL comparison](https://github.com/matthew777777/CinematicRAWStudio-Spec/blob/main/BENCHMARKS.md); isolated codec throughput is separate from sustained camera recording.
- Optional half-resolution binning recorded as demosaiced RGB (best) or CFA (cheap)
- **Direct Log** video with Rec.709, Sony-exact **S-Gamut3.Cine / S-Log3**, HLG and ACES (BakingLab fit) profiles; RCD (default), MHC or super-pixel demosaic; real-time Packed RCD at 3840×2160 24 fps and 2560×1440 / 1920×1080 30 fps on the test phone
- User `.cube` LUT library (up to 16 imports, `LUT_3D_SIZE` 16–65) for S-Log3 monitoring during a take, optionally baked into the recording as SDR Rec.709 (`_LUT709`)
- Locks on record (AE / AF / AWB), stabilization output, stereo dBFS meters

### Viewfinder and UI

- Live histogram / waveform scopes (log scale by default), rule-of-thirds grid, rotation-vector level, 2/5-second timer, torch, OIS toggle, Camera2 diagnostics
- Shared translucent overlay style for focus, level, framing guides, zebras and spot meter
- Feature tags (gold = active/manual), lens discs in a recessed track, video quick panel with torch, gallery seat with the last capture's thumbnail
- RAW-capable lens discovery with verdicts from real use, and per-lens RAW size / packed RAW format selection
- Portrait viewfinder layout with rotation-aware controls

## RAW ZSL

RAW ZSL is opt-in under **Settings → Burst → Zero shutter lag**, or by selecting the `ZSL` capture mode. The ZSL buffer can be set from 1 to 59 saved frames (8 by default), targeting a 30 FPS stream where the device advertises a compatible Camera2 range. At 30 FPS, 30 frames represent approximately one second of pre-shutter history. The effective ring is clamped to what the HAL buffer queue fits (pairing plus transition headroom is reserved first), so deep requests degrade gracefully instead of failing. JPEG/JPEG+DNG selections are bounded to six frames for development memory; DNG-only can save the whole effective ring. Optionally the shutter dumps the whole ring to one `IMG_<ts>_BURST.craw` for a later offline merge. The RAW badge changes to `RAW • ZSL` after the first paired frame is buffered. `ZSL …` means the buffer is warming, `ZSL OFF` means the setting is disabled, and `ZSL N/A` means RawLens has automatically fallen back to ordinary RAW capture.

The ZSL implementation continuously pairs full-resolution RAW images with Camera2 metadata by sensor timestamp. It requests the fastest advertised compatible AE range up to 30 FPS and retains only the configured number of recent frames, bounded by the configured ring and `ImageReader` capacity. On shutter press, it considers only fresh frames whose exposure and rolling-shutter readout completed by that instant, then scores them using recency, autofocus state, auto-exposure state, lens movement, ISO, exposure duration, and gyroscope motion. Request epochs prevent frames from an earlier control state entering a new buffer.

RawLens uses the Camera2 application-operated ZSL request template where the camera advertises a compatible reprocessing capability, and a direct preview-plus-RAW repeating request otherwise. If the full-resolution stream combination is rejected, does not produce paired frames, or exceeds the memory budget, capture automatically falls back to an ordinary forward RAW request.

ZSL and PROGRAM custom AE are mutually exclusive (enabling HDR+ pulls the mode into ZSL and restores the previous mode when turned off); MANUAL disables exposure compensation and uses the current metered pair when entered. Cameras without manual sensor control keep using Android AE, with PROGRAM locks and limits disabled.

## Current UI

The viewfinder includes the mode switcher, RAW status, lens switcher, manual control chips, focus/exposure metering targets, scopes, guide overlays, quick controls, and shutter. In PROGRAM, tap an ISO/S chip for that axis's slider (Auto releases both locks); hold a chip to lock that axis and let the other adjust; hold the second locked axis to enter MANUAL.

Settings has General, Photo, Video, Exposure, Burst, Denoise, Lenses, Debug, and About tabs; swiping left or right on empty space flips between them.

| Tab | Contents |
|---|---|
| General | RAW capture, DNG backend and compression, location, viewfinder, settings backup |
| Photo | Photo format, JPEG / JPEG XL quality, Ultra HDR, AgX look, Active D-Lighting, adaptive exposure, highlight handling, demosaic (AMaZE / RCD) |
| Video | Direct Log (profile, resolution, frame rate, demosaic, crop, gain, LUT library), RAW video (codec, crop, binning, sound), locks on record, stabilization |
| Exposure | PROGRAM custom AE per lens, ETTR, HDR brackets, two-exposure RAW HDR, face priority |
| Burst | Offline burst merges, zero shutter lag, MFSR, HDR+, burst gyro sidecars |
| Denoise | Profiled wavelet (Vulkan), AI RAW denoise (RawNIND Bayer) |
| Lenses | Lens discovery and per-lens selection |
| Debug | HDR debug frames, camera diagnostics, app log |
| About | Version, licence and open-source notices, update check (GitHub builds) |

The DNG sensor calibration editor is guided by the active camera's declared metadata. Overrides are stored per Camera2 ID and are injected into native DNG metadata without changing the RAW pixel payload. Resetting a camera returns it to device-declared defaults; undeclared tags are not offered as safe overrides.

A user guide lives in [docs/user-guide/user-guide.md](docs/user-guide/user-guide.md).

## Requirements

- Android 11 or newer (API 30+; GPU video record needs Android 13+ for half-float buffers)
- A Camera2 camera advertising `REQUEST_AVAILABLE_CAPABILITIES_RAW`
- JDK 21 or newer for Gradle (the project compiles to Java 21), Android SDK 37 (compileSdk 37, targetSdk 35), NDK 27.0.12077973, CMake 3.22.1
- Gradle 9.8.2 (wrapper), AGP 9.4.1, Kotlin 2.4.21

The application requests camera permission, microphone permission on the first video record (mic-less devices record silent video), location only when geotagging is enabled, and one photo-folder grant (asked on first install, re-grantable from Burst settings). Stills and bursts are written through `MediaStore` to `DCIM/RawLens/<stem>`, and gyro sidecars (`burst.json` + `gyro/` CSVs) land next to the DNGs — falling back to `Download/RawLens/<stem>` only until the folder is granted. Copy any split sidecars next to the DNGs before desktop `import`.
See [PRIVACY.md](PRIVACY.md) for the project's privacy statement.

## Build from source

Clone with submodules (libjxl v0.12.0 lives at `third_party/libjxl`):

```bash
git clone --recurse-submodules https://github.com/matthew777777/RawLens.git
# or, in an existing checkout:
git submodule update --init --recursive
./gradlew assembleGithubDebug
```

The debug APK is generated at `app/build/outputs/apk/github/debug/`.

Build flavors (dimension `distribution`):

| Flavor | Channel |
|---|---|
| `github` (default) | GitHub releases: in-app updater, vendored ncnn (GPU) and Halide filters |
| `fdroid` | F-Droid: source-only native code, no updater and no `INTERNET` permission, version frozen, one universal APK. See [docs/fdroid.md](docs/fdroid.md) |

Build properties:

| Property | Effect |
|---|---|
| `-PfreezeVersion` | Build at exactly the committed `app/version.properties` (tagged releases). Without it, every `assemble`/`bundle` advances the patch number and `versionCode` |
| `-PjxlSourceDir=<libjxl checkout>` | Use a specific libjxl checkout; otherwise the submodule is used, or GitHub builds fetch the same pin |
| `-PdisableJxl` | Build without JPEG XL (JPEG / DNG still work; JXL reports "not bundled") |

DNG output uses Android DngCreator or the checked-in C11 TinyDNG v3 writer. The TinyDNG backend owns TIFF layout, checked metadata fields, EXIF/GPS directories, lens-shading opcode transport and optional native lossless JPEG XL encoding. RawLens supplies frozen Camera2 metadata and RAW pixels through a small JNI bridge. Host tests detect installed libjxl and libjxl_threads >=0.8 through pkg-config; custom installations use `PKG_CONFIG_PATH`. TinyDNG's standalone CMake option is `TINYDNG_ENABLE_JXL=ON`. See [integration and verification](docs/tinydng-integration.md) for details.

The `.craw` encoder and decoder are vendored from the Apache-2.0 Cinematic RAW Studio repositories under `third_party/craw`; `tools/sync_craw.sh` refreshes them.

Release builds are shrunk with R8; the mapping for each version is archived under `release-mappings/`. To create a distributable release, configure your own Android signing key and build a signed release APK or App Bundle. Signing credentials and keystores must never be committed.

## Desktop super-resolution (no device)

`tools/sr-pipeline` is the desktop JVM CLI for the MFSR engine (DNG burst directory in, `merged.dng` out) on Linux and macOS (Apple Silicon included), with CPU and Vulkan backends. Start at [tools/sr-pipeline/README.md](tools/sr-pipeline/README.md):

```bash
./gradlew :tools:sr-pipeline:installDist
./gradlew :tools:sr-pipeline:test
```

Other host tools (shader builders, parity oracles, DNG and LUT checks) are listed in [tools/README.md](tools/README.md).

## GitHub release checklist

1. Set the release version in `app/version.properties` (`VERSION_NAME`, `VERSION_CODE`) and build with `-PfreezeVersion` so it is not auto-bumped.
2. Update `CHANGELOG.md`, the release notes, and `fastlane/metadata/android/en-US/changelogs/<VERSION_CODE>.txt`, and verify they describe the actual build.
3. Run `./gradlew clean test assembleGithubDebug assembleFdroidRelease` and perform capture tests on at least one RAW-capable device in AUTO, PROGRAM, MANUAL, burst, ZSL (when supported), RAW video and Direct Log. The full 2.0 checklist is in [docs/refactor/release-2.0-checklist.md](docs/refactor/release-2.0-checklist.md).
4. Build and sign the release artifact (`assembleGithubRelease -PfreezeVersion`) with your private signing configuration; keep the R8 mapping.
5. Create a plain version tag, for example `2.0.0`, from the exact source used for the binary.
6. Publish the signed artifact, R8 mapping, checksums, release notes, and corresponding source from that tag together.

## Current architecture

`MainActivity` owns the viewfinder UI and persisted preferences; the settings pages (`Settings*Tab`, `PhotoSettingsTab`), quick/manual controls, overlays and lens discovery are split into separate files. `RawCameraController` owns Camera2 capture and delegates to focused units: `SessionLadder` (session/stream fallback), `FramePairing`, `ZslStreamHealth` / `RawZslBuffer`, `StillChain`, save jobs (`RawFrameSaveJob`, `HdrBracketSaveJob`, `ZslMergeSaveJobs`), `TouchFocus`, and the `ProgramAeLoop` / `EttrLoop` exposure loops.

`RawDevelopmentCoordinator` performs normalized RAW validation, highlight reconstruction, corrected pre-demosaic processing, adaptive exposure analysis, demosaicing, and development; `Gles31JpegOutputProcessor` / `Gles31JxlOutputProcessor` apply AgX and optional Ultra HDR gain maps. Merges live in `HdrPlusMerge`, `MfsrMerge`, `HdrSeqMerge` and `BurstMergeRunner`. `DngSaver` combines each selected `RAW_SENSOR` image with its frozen capture metadata and streams to Android DngCreator or TinyDNG; `TinyDngImageWriter` also serializes merged, linear RGB and float-CFA output. Video is recorded by `RawVideoRecorder` (`.craw` via `CrawWriter`) and `DirectLogRecorder` (Vulkan record shaders, optional 3D LUT). All sources are under `app/src/main/java/com/matthew/rawlens`; native code is under `app/src/main/cpp`.

## Contributing

Bug reports and focused pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) before submitting code. Device reports should include the Android version, device model, affected Camera2 ID/lens, and the diagnostic overlay when possible.

## License and acknowledgements

RawLens is licensed under the GNU General Public License, version 3 or later. See [LICENSE](LICENSE).

2.0 ports or follows published work from libcamera (PROGRAM AGC), Magic Lantern (ETTR, RAW zebras), Open Camera (flash precapture), darktable (inpaint-opposed highlights, JPEG XL export options), RawTherapee (AMaZE), the Wronski et al. / IPOL MFSR reference, Stephen Hill's BakingLab ACES fit, Sony's S-Log3 / S-Gamut3.Cine specification, and Cinematic RAW Studio (`.craw`).

RAW ZSL lifecycle research also used PhotonCamera's pinned `dev` source as a reference, especially its continuous RAW ownership and request-transition draining patterns. RawLens's PROGRAM shutter-first exposure policy acknowledges the related work in commit [`5bb9cf4`](https://github.com/eszdman/PhotonCamera/commit/5bb9cf47fa9313abb00f1eb594b647e0553fd866), authored by `matthew777777`. PhotonCamera is distributed under the GNU GPL. See [NOTICE.md](NOTICE.md) and [references/README.md](references/README.md) for attribution and the exact reference revisions.

RawLens is an independent project and is not endorsed by camera manufacturers, Sony, Adobe, or the PhotonCamera maintainers.


## Codec display names

| ID | Display name | Historical names / aliases |
|---:|---|---|
| 1 | **CRAW Balanced** | CRAW-Fast, Fast |
| 2 | **CRAW Live v1 — Legacy** | CRAW-Live, CRAW-Live v1, Live v1 |
| 3 | **CRAW Live** | CRAW-Live v2, Live v2 |
| 4 | **CRAW Compact — Offline** | CRAW-Compact, Desktop Compact |

These are display names. Codec IDs, encoded bytes, saved numeric preferences
and legacy metadata strings retain their existing meanings. Existing recordings
are not rewritten. Historical benchmark reports keep their original labels;
use the IDs to match them to this table. Balanced describes the intended
size/CPU tradeoff, not a guarantee of the smallest files. Live is the current
recording choice; Live v1 is retained for compatibility and Compact is offline.
