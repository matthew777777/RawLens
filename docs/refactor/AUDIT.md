# RawLens 2.0 refactor — audit trail

Branch `refactor/2.0`. One logical change per commit; each row lists its
evidence. "Device-unverified" means no on-device run yet.

## Backup and baseline (2026-10-20, before any change)

- `../RawLens-backups/RawLens-20261020-160039.tar.gz` — full tree incl. WIP;
  test-restore of `MainActivity.kt` byte-identical.
- `../RawLens-backups/RawLens-20261020-160039.bundle` — all refs;
  `git bundle verify` OK.
- Baseline 1.0.393: `assembleDebug` OK; `testDebugUnitTest` 1489 pass /
  0 fail / 2 skip. APK + reports in `../RawLens-backups/baseline-1.0.393/`.

## Ground rules

- Out of scope (parity already demonstrated): see "Excluded components".
- Classes with `external fun` keep their package and name: JNI resolves
  `Java_<package>_<Class>_<method>` by name
  (https://docs.oracle.com/en/java/javase/21/docs/specs/jni/design.html#resolving-native-method-names).
- UI/logic moves are verbatim; each is checked by a normalized diff of the
  moved code against `HEAD` plus the full unit suite.

## Change log

| Commit | Change | Reference | Evidence | Compat |
|---|---|---|---|---|
| 8150aed | Remove agent session leftovers | — | n/a | none |
| 56edf98 | Gradle 9.8.2, AGP 9.4.0, compileSdk 37, JVM 21, KGP 2.4.21 | [AGP 9.0 notes](https://developer.android.com/build/releases/agp-9-0-0-release-notes), [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [AGP compat](https://developer.android.com/build/releases/gradle-plugin) | 1489/0/2 = baseline; sr-pipeline 196 pass | targetSdk 35, NDK 27.0.12077973, core 1.9.0 unchanged. KGP 2.4.21 officially tested to Gradle 9.7.0/AGP 9.3.1 ([table](https://kotlinlang.org/docs/gradle-configure-project.html)); newer allowed with possible warnings |
| cce76b2 | Commit owner's pre-refactor WIP | — | in baseline | none |
| d922b3b | `UpdateFlow` extracted from `MainActivity` | — | normalized diff identical; 1489/0 | device-unverified (UI dialog) |
| 922988c | `SettingsTransfer` extracted from `MainActivity` | [SAF](https://developer.android.com/training/data-storage/shared/documents-files) | 1489/0 | device-unverified (pickers) |
| 0562fce | `Camera2Names` from `RawCameraController`; gap filled: AE modes 5 `ON_EXTERNAL_FLASH`, 6 `ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY` printed UNKNOWN | API 37 `android.jar` via `javap -constants`; [CameraMetadata](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata) | 1492/0 (+3 new) | debug text only |
| 8e2bdb4 | Touch focus: remove dead `setAfPointOnly` (0 callers; KDoc claimed AE-only, code did AF-only), fix orphan KDoc, dedupe error reset into `abortTouchFocusOnError` | Open Camera `CameraController2.setFocusAndMeteringArea` (verified: no-AF-region path installs AE only) | 1492/0 | no behavior change |
| abe8702 | Touch AF state: remove dead `cancelOpenCameraAutoFocusForNewTouch` (0 callers); merge duplicated sharp-lock publish into `publishSharpLock` (side-effect order unchanged) | — | 1492/0 | device-unverified (no JVM coverage of controller AF) |
| ab0ac60 | Dead-code sweep: 28 unreferenced functions removed across 14 files (list in commit). `scheduleDynamicExposureProbe` was never called since the initial import (9fe6376), so its Runnable was always null and its 3 cancels were no-ops; wiring it would add captures (parity), so removed. Stale `VendorKeyAndroid` KDoc claimed a hidden-key fallback the app never ran; corrected. Skipped: excluded components, `VulkanHub.deviceInfo` (JNI wrapper) | grep sweep + manual check of each | fresh 1492/0; debug, release and androidTest compile | no behavior change |
| c966668 | `TouchFocus` (+ `Host` interface) extracted from `RawCameraController`: 17 functions, 19 fields, 6 constants; controller 9159 → 8706 lines | Open Camera `CameraController2`; [CONTROL_AF_TRIGGER](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_TRIGGER) | token diff of every moved body vs pre-move file: only Host substitutions (each Host method = the replaced code); `startSent=true` now set just before request build (error path resets it, end state identical). 13 new JVM tests (first coverage of this state machine). Fresh 1505/0; release + androidTest compile | device-unverified |
| 84349e1 | ZSL study (no code) | see `docs/refactor/zsl-study.md` | — | — |
| 5711982 | ZSL F4–F6: capacity condition, stale doc, duplicate log field | — | 1505/0 | none |
| 84761c2 | ZSL F1–F3: unverifiable GCam citations replaced; PhotonCamera differences stated; `qualityScore` marked unsourced | Google Research HDR+ with Bracketing; PhotonCamera `b2eff82` | compile | comment-only |
| 35e3826 | ZSL F7: HDR+/MFSR full-ring takes share `takeFullRingForMerge` (merges untouched) | — | fresh 1505/0; `activeFrameCount()` pure, so the single call is equivalent | device-unverified |
| ddf1f4d | `ZslStreamHealth` extracted: watchdog windows, transient cooldown, burst-mode latch, overflow-storm escalation, fruitless-cooldown rebuild (decisions only; controller keeps actions). Inline copies of the blocked test in `updateRepeatingRequest`/`admitStaleZslFrame` now call `isRawZslBlocked()`. Controller 8706 → 8630 | AOSP ImageReader.java (maxImages / IllegalStateException) | 12 new JVM tests; fresh 1517/0; release + androidTest compile. `open()` reset order: cooldown reset moved below VF invalidation/`notifyVfRoute`, neither reads ZSL state | device-unverified |
| 6fa0aee | F9 (owner-approved): capacity-0 status text no longer claims a memory budget; one constant for both sites | `clampZslCapacityForReader` | fresh unit run | user-visible text only |
| a623574 | Session study (no code); S1 bug recorded | AOSP CameraDevice/CameraDeviceImpl, metadata_definitions.xml | — | — |
| 35fed6e | S2/S4/S5/S6: stale packed log, orphan KDoc, stream-use-case comments vs spec, `open()` re-indent | metadata_definitions.xml | `git diff -w` text only | none |
| f54ae94 | S7: RAW image listener → `onRawImageAvailable` | — | body identical ignoring whitespace; 1517/0 | none |
| 71e8a2f | S3/S8: `discardSessionOutputs`, `switchToAltRoute` | — | 1517/0 | none |
| 9ece6d1 | `SessionLadder`: combo index, alt route, failed/unopenable/opened-once IDs, retry budgets; `recoverRawPreview` alt check reuses it. Controller 8630 → 8594 | — | 6 new JVM tests; fresh 1523/0; release + androidTest compile | device-unverified |
| 404efcb | **S1 fix (owner-approved):** vendor operating-mode session now passes `List<OutputConfiguration>` | AOSP `CameraDevice.createCustomCaptureSession`, `CameraDeviceImpl.java:824-830` | fresh unit run; no JVM path exercises it | **behaviour change** for users with a non-zero operating mode; hidden-API access may still be refused; device-unverified |
| 2fe26be | Capture/pairing/save study (no code) | AOSP CameraCaptureSession/CaptureFailure, metadata_definitions.xml; MotionCam; PhotonCamera | — | — |
| 98ff888 | C2/C6: shutter comment; shared HDR-bracket reset | — | 1523/0 | none |
| 2d263d3 | `FramePairing`: image↔result pairing (forward + ZSL), pair timeouts, repeating-RAW memory, reader-pressure relief order, unmatched/ZSL/teardown release; completed-pair routing stays in the controller (save paths untouched). `stop()`'s two teardown clears keep their exact release-failure behaviour via a flag. Controller 8594 → 8543 | Camera2 `sensor.timestamp` "identical on all the outputs"; MotionCam `RawImageConsumer` | moved bodies token-diffed vs pre-move file (relief differs only by moving the ring-eviction side effects into the host); 16 new JVM tests; fresh 1539/0; release + androidTest compile | device-unverified |
| 66a51e3 | Device soak test: headless lens seed, `-e burst true`, FramePairing reflection, preview check only for preview sessions | — | phone baseline (below) | test-only |
| ce864e5 | **C3/C4 (owner-approved):** a failed frame (`onCaptureFailed`) or lost RAW buffer (`onCaptureBufferLost`, new) resolves only that frame; the capture continues and saves what arrives. All-lost keeps the old "CAPTURE FAILED" + dead-chain outcome. Aborted sequences unchanged | AOSP `CameraCaptureSession.CaptureCallback.onCaptureFailed` ("Other requests are unaffected"), `onCaptureBufferLost`, `CaptureFailure.wasImageCaptured` | JVM 1540/0 (+1); phone (below) | **behaviour change** for partial failures; failure paths not inducible on the test phone |
| 553b90a | Still-chain study with on-device timing (R1 recorded) | AOSP `captureBurst`; PhotonCamera; MotionCam | device log | — |
| 932d7ce | R3/R4: one forward-capture error tail | — | 1540/0; phone (below) | none |

## Device runs (Xiaomi 25080RABDG, MT6878 / Mali-G615, API 36; release build)

| Build | ZSL ×2, 5 cycles | AUTO single, 5 | AUTO burst ×6, 3 | Notes |
|---|---|---|---|---|
| 2d263d3 (all refactors to FramePairing) | 5/5 | 5/5 | 3/3 | pairs=0, saves=0 after each cycle; PSS 193–208 MB; RAW VF 24–30 fps |
| C3/C4 | 5/5 | 5/5 | 3/3 | same ranges; ZSL pairs≤1 (live ring frame in flight, limit 12) |
| 932d7ce (R3/R4) | 5/5 | 5/5 | 3/3 | PSS 193–210 MB; RAW VF 25–30 fps; pairs=0 |

No frame failed or lost a buffer in these runs, so the new C3/C4 branches did not execute on device.

## Full on-device suite (refactored 1.0.397 vs original 1.0.374, same phone)

- Refactored, full suite: 118 tests, 74 pass, 33 skipped (opt-in flags), 11 fail.
- 2 failures are environmental: `DcgVulkanBurstInstrumentedTest` (optional
  `libdcg_probe.so` not built), `DevelopDngEndToEndInstrumentedTest` (fixture
  DNG not pushed to /data/local/tmp).
- The other 9 classes were re-run on the **original pre-refactor app**
  (`ec68f222` + owner WIP, AGP 8.13.2 / Gradle 8.13, built in a clean
  worktree) and on the refactored app in the same order: **all 20 test cases
  give identical results** (8 fail on both, 12 pass on both). The
  `VfVulkanInstrumentedTest.gradeInitAllocatesRecorderTwins` failure seen in
  the full suite passes on both builds in isolation: order-dependent (shared
  Vulkan device state), not a regression.
- APK comparison: assets identical (plus generated `sr_spv/`, present only
  where the desktop SPV build exists); all 25 native libraries have
  byte-identical `.text` sections (differences are build paths/IDs only).
- Pre-existing failures (owner's backlog, not refactor scope): Galosh shader
  count 43 vs 44; FlowNet identity fallback; histogram AgX bins 48 vs 64;
  Linear RGB DNG white level 65535 vs 912; MediaStore rejects
  `video/x-crs`; scene-linear negative clipping; Ultra HDR Exif rewrite
  drops Display P3; VfVulkan synthetic record P010 centre.
- Targeted refactor checks (`RefactorDeviceChecksTest`): 4/4 (touch focus,
  settings round trip, S1 opmode 37379 applied, update check).

## Open decisions for the owner

- **R1** (still chain): serialized forward bursts space frames 166.6 ms apart on MT6878 (RAW minFrameNs 50 ms); both references pipeline with `captureBurst`; the "log-proven on MediaTek" reason has no log in the repo. Proposal: flagged pipelined experiment soaked on this phone. See `still-chain-study.md`.
- ZSL copy-out pool (MotionCam design) — see `zsl-study.md`, needs device measurements.

## Process note

- Read only fresh test results: delete `app/build/test-results/testDebugUnitTest`
  before a run. A run that fails at task selection leaves old XML behind
  (this happened once; caught before commit).
- `testBuildType = "release"`: on-device tests compile via
  `:app:compileReleaseAndroidTestKotlin`.

## Open follow-ups

- `android.newDsl=false`: migrate APK rename to `listenToArtifacts`
  ([recipe](https://github.com/android/gradle-recipes/tree/agp-9.0/listenToArtifacts))
  together with the F-Droid output path (`docs/fdroid.md`). Removed in AGP 10.
- androidx.core declared 1.19.1 but force-resolved to 1.9.0; lifting the pin
  is a runtime change needing device tests.

## Excluded components (untouched, parity already demonstrated)

| Group | Files | Parity evidence in repo |
|---|---|---|
| HDR+ | `HdrPlus*`, `HdrPools`, `cpp/hdrplus/`, `spirv/hdrplus/` | `cpp/hdrplus/PARITY.md`: ~80 dB vs Metal, bit-exact vs intermediate port |
| MFSR / SR | `Mfsr*`, `BurstMerge*`, `BurstReferenceSelection`, `tools/sr-pipeline` | `docs/sr-paper-parity-traceability.md` (only rows with a named passing test) |
| AMaZE | `*Amaze*`, `RawTherapeeAmaze`, `Rt*`, `shaders/amaze/` | **Excluded at owner's request, but parity is NOT established**: `docs/amaze-rawtherapee-audit.md` says "RawTherapee-identical output is not established" |
| HL | `Hl*`, `RtHighlightMap` | excluded at owner's request |
| Galosh | `Galosh*`, `cpp/galosh/`, `spirv/galosh/` | `docs/galosh-phase2-parity.md`: PASS ≥69 dB on Mali-G615 |
| Wavelet | `Wavelet*`, `cpp/wavelet/`, `spirv/wavelet/` | `spirv/wavelet/UPSTREAM.md`: vendored bit-identical (rawr / darktable) |
| ML (FlowNet, RawNIND) | `HdrFlowNetAligner`, `RawNind*`, `cpp/flownet/` | vendored PhotonCamera sources (`cpp/flownet/UPSTREAM.md`) |
