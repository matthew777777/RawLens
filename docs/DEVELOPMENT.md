# Development and release guide

See the [README](../README.md) for a quick start and [current repository state](CURRENT_STATE.md) for recent changes.

## Requirements

- Android 11 or newer (API 30+; GPU video record needs Android 13+ for half-float buffers)
- A Camera2 camera advertising `REQUEST_AVAILABLE_CAPABILITIES_RAW`
- JDK 27 for the pinned Gradle daemon (the project compiles to Java 21), Android SDK 37 (compileSdk 37, targetSdk 35), NDK 27.0.12077973, CMake 3.22.1
- Gradle 9.8.2 (wrapper), AGP 9.4.1, Kotlin 2.4.21

The application requests camera permission, microphone permission on the first video record (mic-less devices record silent video), location only when geotagging is enabled, and one photo-folder grant (asked on first install, re-grantable from Burst settings). Stills and bursts are written through `MediaStore` to `DCIM/RawLens/<stem>`, and gyro sidecars (`burst.json` + `gyro/` CSVs) land next to the DNGs — falling back to `Download/RawLens/<stem>` only until the folder is granted. Copy any split sidecars next to the DNGs before desktop `import`.
See [PRIVACY.md](../PRIVACY.md) for the project's privacy statement.

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
| `fdroid` | F-Droid: source-only native code, no updater and no `INTERNET` permission, version frozen, one universal APK. See [docs/fdroid.md](../docs/fdroid.md) |

Build properties:

| Property | Effect |
|---|---|
| `-PfreezeVersion` | Build at exactly the committed `app/version.properties` (tagged releases). Without it, every `assemble`/`bundle` advances the patch number and `versionCode` |
| `-PjxlSourceDir=<libjxl checkout>` | Use a specific libjxl checkout; otherwise the submodule is used, or GitHub builds fetch the same pin |
| `-PdisableJxl` | Build without JPEG XL (JPEG / DNG still work; JXL reports "not bundled") |

DNG output uses Android DngCreator or the checked-in C11 TinyDNG v3 writer. The TinyDNG backend owns TIFF layout, checked metadata fields, EXIF/GPS directories, lens-shading opcode transport and optional native lossless JPEG XL encoding. RawLens supplies frozen Camera2 metadata and RAW pixels through a small JNI bridge. Host tests detect installed libjxl and libjxl_threads >=0.8 through pkg-config; custom installations use `PKG_CONFIG_PATH`. TinyDNG's standalone CMake option is `TINYDNG_ENABLE_JXL=ON`. See [integration and verification](../docs/tinydng-integration.md) for details.

The `.craw` encoder and decoder are vendored from the Apache-2.0 Cinematic RAW Studio repositories under `third_party/craw`; `tools/sync_craw.sh` refreshes them.

Release builds are shrunk with R8; the mapping for each version is archived under `release-mappings/`. To create a distributable release, configure your own Android signing key and build a signed release APK or App Bundle. Signing credentials and keystores must never be committed.

## Desktop super-resolution (no device)

`tools/sr-pipeline` is the desktop JVM CLI for the MFSR engine (DNG burst directory in, `merged.dng` out) on Linux and macOS (Apple Silicon included), with CPU and Vulkan backends. Start at [tools/sr-pipeline/README.md](../tools/sr-pipeline/README.md):

```bash
./gradlew :tools:sr-pipeline:installDist
./gradlew :tools:sr-pipeline:test
```

Other host tools (shader builders, parity oracles, DNG and LUT checks) are listed in [tools/README.md](../tools/README.md).

## GitHub release checklist

1. Set the release version in `app/version.properties` (`VERSION_NAME`, `VERSION_CODE`) and build with `-PfreezeVersion` so it is not auto-bumped.
2. Update `CHANGELOG.md`, the release notes, and `fastlane/metadata/android/en-US/changelogs/<VERSION_CODE>.txt`, and verify they describe the actual build.
3. Run `./gradlew clean test assembleGithubDebug assembleFdroidRelease` and perform capture tests on at least one RAW-capable device in AUTO, PROGRAM, MANUAL, burst, ZSL (when supported), RAW Video and Encoded Video. The full 2.0 checklist is in [docs/refactor/release-2.0-checklist.md](../docs/refactor/release-2.0-checklist.md).
4. Build and sign the release artifact (`assembleGithubRelease -PfreezeVersion`) with your private signing configuration; keep the R8 mapping.
5. Create a plain version tag, for example `2.0.0`, from the exact source used for the binary.
6. Publish the signed artifact, R8 mapping, checksums, release notes, and corresponding source from that tag together.

## Current architecture

`MainActivity` owns the viewfinder UI and persisted preferences; the settings pages (`Settings*Tab`, `PhotoSettingsTab`), quick/manual controls, overlays and lens discovery are split into separate files. `RawCameraController` owns Camera2 capture and delegates to focused units: `SessionLadder` (session/stream fallback), `FramePairing`, `ZslStreamHealth` / `RawZslBuffer`, `StillChain`, save jobs (`RawFrameSaveJob`, `HdrBracketSaveJob`, `ZslMergeSaveJobs`), `TouchFocus`, and the `ProgramAeLoop` / `EttrLoop` exposure loops.

`RawDevelopmentCoordinator` performs normalized RAW validation, highlight reconstruction, corrected pre-demosaic processing, adaptive exposure analysis, demosaicing, and development; `Gles31JpegOutputProcessor` / `Gles31JxlOutputProcessor` apply AgX and optional Ultra HDR gain maps. Merges live in `HdrPlusMerge`, `MfsrMerge`, `LkrMerge`, `HdrSeqMerge` and `BurstMergeRunner`. `DngSaver` combines each selected `RAW_SENSOR` image with its frozen capture metadata and streams to Android DngCreator or TinyDNG; `TinyDngImageWriter` also serializes merged, linear RGB and float-CFA output. Video is recorded by `RawVideoRecorder` (`.craw` via `CrawWriter`) and `EncodedVideoRecorder` (Vulkan record shaders, optional 3D LUT). All sources are under `app/src/main/java/com/matthew/rawlens`; native code is under `app/src/main/cpp`.

## License and acknowledgements

RawLens is licensed under the GNU General Public License, version 3 or later. See [LICENSE](../LICENSE).

RawLens ports or follows published work from libcamera (PROGRAM AGC), Magic Lantern (ETTR, RAW zebras), Open Camera (flash precapture), darktable (inpaint-opposed highlights, JPEG XL export options), RawTherapee (AMaZE), the Wronski et al. / IPOL MFSR reference, Stephen Hill's BakingLab ACES fit, Sony's S-Log3 / S-Gamut3.Cine specification, and Cinematic RAW Studio (`.craw`).

RAW ZSL lifecycle research also used PhotonCamera's pinned `dev` source as a reference, especially its continuous RAW ownership and request-transition draining patterns. RawLens's PROGRAM shutter-first exposure policy acknowledges the related work in commit [`5bb9cf4`](https://github.com/eszdman/PhotonCamera/commit/5bb9cf47fa9313abb00f1eb594b647e0553fd866), authored by `matthew777777`. PhotonCamera is distributed under the GNU GPL. See [NOTICE.md](../NOTICE.md) and [references/README.md](../references/README.md) for attribution and the exact reference revisions.

RawLens is an independent project and is not endorsed by camera manufacturers, Sony, Adobe, or the PhotonCamera maintainers.
