# RawLens

RawLens is an open-source Android camera built around a scene-referred RAW pipeline, with direct exposure controls, RAW photo development, burst merging and video recording.

[Download releases](https://github.com/matthew777777/RawLens/releases) · [User guide](docs/user-guide/user-guide.md) · [Changelog](CHANGELOG.md)

## Features

- **RAW photos:** DNG, JPEG, JPEG+DNG, JPEG XL and Ultra HDR, with AMaZE / RCD demosaic, AgX looks, highlight reconstruction and denoise.
- **Exposure controls:** AUTO, PROGRAM, ZSL and MANUAL modes, ETTR, RAW metering, zebras, false colour and focus peaking.
- **Burst merging:** KB-SR, LK-SR and HDR+, with zero-shutter-lag capture and offline merges of saved bursts.
- **RAW Video:** lossless `.craw` recording with audio and motion metadata.
- **Encoded Video:** Rec.709, S-Log3 / S-Gamut3.Cine, HLG and ACES profiles, custom LUTs and gyro stabilization.
- **Viewfinder:** histogram / waveform scopes, lens selection, manual controls and optional ISP preview.

See the [feature and controls reference](docs/FEATURES.md) for details.

## Requirements

Android 11 or newer and a camera exposing Camera2 RAW support. GPU video recording requires Android 13 or newer. Lens availability, capture modes and recording performance depend on the device.

RawLens processes media locally, with no accounts, advertising or analytics. GitHub builds offer an optional update check; F-Droid builds disable it. See [Privacy](PRIVACY.md).

## Build from source

Install JDK 27 for the Gradle daemon, Android SDK 37, NDK 27.0.12077973 and CMake 3.22.1. The compilation target is Java 21.

```bash
git clone --recurse-submodules https://github.com/matthew777777/RawLens.git
cd RawLens
./gradlew assembleGithubDebug -PfreezeVersion
```

The APK is written to `app/build/outputs/apk/github/debug/`. For an existing checkout, initialize dependencies with `git submodule update --init --recursive`.

See the [development guide](docs/DEVELOPMENT.md) for build options, architecture and release steps, or [F-Droid instructions](docs/fdroid.md) for that distribution.

## Documentation

- [Current repository state](docs/CURRENT_STATE.md)
- [Desktop super-resolution CLI](tools/sr-pipeline/README.md) and [host tools](tools/README.md)
- [Contributing](CONTRIBUTING.md)

## License

GNU GPL version 3 or later. See [LICENSE](LICENSE), [third-party notices](NOTICE.md) and [research acknowledgements](references/README.md).
