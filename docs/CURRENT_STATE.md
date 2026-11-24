# Current repository state

This overview describes the source on `refactor/2.0` reviewed on 2026-11-18.
The generated app version belongs in `app/version.properties`, release metadata
and tags. It is intentionally not repeated in the README.

## App behavior

- Camera2 RAW capture, PROGRAM / ETTR / manual exposure, DNG, JPEG, JPEG XL and
  Ultra HDR development remain supported, subject to camera capabilities.
- **MFSR** defaults to the Jamy-L-derived Wronski kernel merge with RawLens
  improvements at native resolution or full-frame 2×. Independent widths use
  0.85× detail and 1.30× flat-region denoise, with grain retention at 0.50
  and capture sharpening off. Green detail is interpolated directly from
  measured Bayer samples, with directional interpolation near strong axial
  edges. Experimental two-band reconstruction is opt-in.
  **LK-R** uses `LkrMerge` and `tools/lkr-lite` (LK-R Lite; it replaced `tools/lkr-pipeline` on 2026-11-14). HDR+ remains a separate option.
  Internal MFSR/LKR identifiers remain unchanged. Earlier releases used the display names KB-SR / LK-SR.
- B&W capture offers Off, Detail and Clean, with Clean as the default; legacy
  Auto preferences migrate to Clean. ACES 2.0 joins Basic, AgX and Sigmoid.
- LK-R uses a colour-only learned network by default when an NPU is detected;
  saved choices remain authoritative. Without an NPU it defaults off.
- The default ZSL buffer is 16 frames (`MainActivity.DEFAULT_RAW_ZSL_FRAME_COUNT`).
  Saved preferences, available reader capacity and mode-specific limits still apply.
- Settings has General, Photo, Video, Exposure, Burst, Merge, Denoise, Lenses,
  Debug and About pages (`SettingsDialog`). Camera capture pauses behind Settings.
- Offline Merges starts with JPEG output and offers JPEG / JPEG XL quality
  controls, burst sharing and MFSR / LK-R / HDR+ (`MergesActivity`). Staged burst
  loading preserves device orientation (`OfflineBurstFrame`). LK-R output
  resolution and viewfinder zoom are independent controls.
- RAW development includes revised histogram-posterior white-balance fallback,
  two-radius chroma cleanup and lens-shading transport for root RAW / merged DNGs.
- **Always use ISP viewfinder** is available under General → Viewfinder, including
  for full-resolution capture on compatible cameras. The separate merge-only
  ISP option was removed. ISP appearance can differ from the saved RAW develop.
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
| [sr-pipeline](../tools/sr-pipeline/README.md) | JVM MFSR CLI, CPU and Vulkan; `run.sh` builds the required components. DNGs without NoiseProfile use a scene-noise estimate. |
| [lkr-lite](../tools/lkr-lite/README.md) | LK-R engine (LK-R Lite), host CLI and app native integration (liblkrpipeline.so). |
| [Tools index](../tools/README.md) | Shader builders, format checks, numerical oracles and device validation utilities. |

Mobile MFSR optimizations, guide-based LK-R rejection and foliage experiments
are recorded under [performance](performance/). Timings and image-quality numbers
apply to the stated hardware, datasets and flags. The unused experimental `rawdn`
denoiser and GALOSH comparison prototypes were removed; the
[archive guide](refactor/README.md) identifies their Git revision.

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
entry points. Dated audits, research plans, benchmark summaries and release notes
preserve evidence from their original revision. Raw logs, media and obsolete session
handoffs are available through the [archive guide](refactor/README.md).
Their old defaults, pending tasks and version numbers are historical; consult
this overview and the relevant implementation before treating them as current.
Upstream README files, licenses, notices and provenance records retain their
original attribution and scope. Standalone export templates describe the package
they generate rather than this Android checkout.
