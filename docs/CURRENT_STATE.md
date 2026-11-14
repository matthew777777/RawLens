# Current repository state

This overview describes the source on `refactor/2.0` reviewed on 2026-11-14.
The generated app version belongs in `app/version.properties`, release metadata
and tags. It is intentionally not repeated in the README.

## App behavior

- Camera2 RAW capture, PROGRAM / ETTR / manual exposure, DNG, JPEG, JPEG XL and
  Ultra HDR development remain supported, subject to camera capabilities.
- **KB-SR** is the user-facing name of the MFSR engine (`MfsrMerge`);
  **LK-SR** uses `LkrMerge` and `tools/lkr-pipeline`. HDR+ remains a separate option.
  Internal MFSR/LKR identifiers and historical LK-R labels still occur in source.
- The default ZSL buffer is 16 frames (`MainActivity.DEFAULT_RAW_ZSL_FRAME_COUNT`).
  Saved preferences, available reader capacity and mode-specific limits still apply.
- Settings has General, Photo, Video, Exposure, Burst, Merge, Denoise, Lenses,
  Debug and About pages (`SettingsDialog`). Camera capture pauses behind Settings.
- Offline Merges starts with JPEG output and offers JPEG / JPEG XL quality
  controls, burst sharing and KB-SR / LK-SR / HDR+ (`MergesActivity`). Staged burst
  loading preserves device orientation (`OfflineBurstFrame`). LK-SR output
  resolution and viewfinder zoom are independent controls.
- RAW development includes revised histogram-posterior white-balance fallback,
  two-radius chroma cleanup and lens-shading transport for root RAW / merged DNGs.
- The RAW viewfinder can use an optional ISP SurfaceView route, including during
  LK-SR merges. This is an explicit preview tradeoff, not proof that every
  manufacturer's ISP stream matches the RAW develop.
- RAW Video uses `.craw`, with optional temporal DCT denoise. Encoded Video uses
  MHC or Super pixel demosaic, offers saturation/vibrance controls and retains
  the selected demosaic through session fallbacks. Video RCD was removed;
  still-photo RCD remains available.
- Gyro stabilization v2 implements L1 camera-path smoothing and self-calibration.
  Device-specific quality and calibration remain dependent on the available motion
  and image data; research plans also contain proposals beyond implemented work.

## Desktop and research tools

| Component | Current role |
|---|---|
| [sr-pipeline](../tools/sr-pipeline/README.md) | JVM MFSR / KB-SR CLI, CPU and Vulkan; `run.sh` builds the required components. DNGs without NoiseProfile use a scene-noise estimate. |
| [lkr-pipeline](../tools/lkr-pipeline/README.md) | Full LK-SR engine, host CLI and app native integration. |
| [lkr-lite](../tools/lkr-lite/README.md) | Separate standalone Vulkan CLI, including a DNG-burst front end, hybrid merge and fp16 frames. Optional KernelNet denoise and other experiments are not app defaults. |
| [lkr-standalone](../tools/lkr-standalone/README.md) | Export/package workflow and templates; generated-package docs describe the exported tree. |
| [Tools index](../tools/README.md) | Shader builders, format checks, numerical oracles and device validation utilities. |

Mobile MFSR optimizations, guide-based LK-SR rejection and foliage experiments
are recorded under [performance](performance/). Timings and image-quality numbers
apply to the stated hardware, datasets and flags. Experimental RAW denoise and
GALOSH comparison code does not imply a shipped GALOSH option.

## Build and validation

The pinned Gradle daemon uses JDK 27 (`gradle/gradle-daemon-jvm.properties`);
compilation targets Java 21. The repository pins Gradle 9.8.2, AGP 9.4.1,
Kotlin 2.4.21, compile SDK 37, target SDK 35, NDK 27.0.12077973 and CMake 3.22.1.
Initialize submodules before building. GitHub and F-Droid are separate distribution
flavors; the latter disables the updater. See [build instructions](DEVELOPMENT.md#build-from-source)
and [F-Droid documentation](fdroid.md).

Use `-PfreezeVersion` when a verification build must not advance the generated
version. Relevant checks include `:app:testGithubDebugUnitTest` and
`:tools:sr-pipeline:test`; camera, GPU and stabilization changes also need the
corresponding physical-device checks. This documentation refresh does not claim
new APK, device, numerical-parity or performance test results.

## Reading the documentation

The README, contribution guide, tools index and this overview describe current
entry points. Dated audits, research plans, handoffs, benchmark results, session
transcripts and release notes preserve evidence from their original revision.
Their old defaults, pending tasks and version numbers are historical; consult
this overview and the relevant implementation before treating them as current.
Upstream README files, licenses, notices and provenance records retain their
original attribution and scope. Standalone export templates describe the package
they generate rather than this Android checkout.
