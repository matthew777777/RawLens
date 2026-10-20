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
| 59e579e | Remove obsolete DCG probe tests/artifacts (owner-requested); live opmode feature kept | — | release + androidTest build | test-only |
| d4103eb | Gradle runs on **JDK 27** via `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=27`, found through macOS java_home); compile target stays Java 21 | [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html): 9.8 runs on JVM 17–27 | JDK 27: app 1540/0, sr-pipeline 196/0/38; phone ZSL 5/5, AUTO 5/5, burst 3/3, refactor checks 4/4 (PSS 202–217 MB vs 193–210 before, watch) | build machine only |
| 6bed719 | Save-writer study; W1/W2 comment corrections | GaloshDenoiser `@Synchronized` | compile | comment-only |
| 90e7973 | **R1 (owner-approved, default on after phone soak):** multi-frame forward captures submitted with `captureBurst`; any lost frame / abort / timeout / submit failure in a pipelined burst falls back to the serialized chain and persists the verdict (`still_burst/pipelined_refused`). **W4 (owner-approved):** seven save settings frozen at shutter time | AOSP `CameraCaptureSession.captureBurst`; PhotonCamera `captureBurst`; MotionCam multi-request capture | phone: 20/20 pipelined bursts, frame gap 33.3 ms (was 166.6), 6-frame span 167 ms (was ~833), no fallback; 6 burst DNGs distinct (MD5) and valid; ZSL 5/5, hybrid top-up ×8 5/5, AUTO 5/5, refactor checks 4/4; JVM 1540/0 | **behaviour change**: bursts ~5× faster on this device |
| 938f762 | W3: single-frame save job moved from `saveRawFrame` into `RawFrameSaveJob` + `RawFrameSaveInput` (shutter-time snapshot) behind `RawFrameSaveJob.Host`; `formatSaveOutcome` now uses the shutter-time developed format (W4) | — | body identical to the old lambda after mapping substitutions (only addition: format argument); JVM 1540/0; phone DNG, JPEG, JPEG+DNG, ZSL, burst, refactor checks all pass | device JPEG ~10.5 s/frame, **not baseline-compared** (development code unchanged) |
| 09cc8ad | Device settings snapshot test (9 tabs, accessibility tree) | — | baseline 314 controls; two runs identical except live logcat line | test-only |
| bfb2c69 | MainActivity: `SettingsKit` (shared settings helpers, verbatim) + Settings → Photo tab moved to `PhotoSettingsTab` (522 lines) behind a Host. MainActivity paused here at owner request | AndroidX Preference is Google's settings guidance (PhotonCamera uses it); not adopted: UI rewrite, not parity | JVM 1540/0; phone snapshot identical to baseline (314 lines). Snapshot proves rendering, not control interaction | none expected |
| f26bd3e | `ProgramAeSolver`: PROGRAM limits, locked values and glide step out of the controller (verbatim; Range args → numbers). Exposure study: PhotonCamera 1/30 s & 1/15 s claims verified; ETTR checked against Magic Lantern (E1/E2 owner decisions) | PhotonCamera `IsoExpoSelector`; Magic Lantern `modules/ettr/ettr.c` | limits/locked bodies identical; JVM 1545/0, no baseline class lost tests; phone PROGRAM 5/5 with `PROGRAM fast-start ISO 2166 EXP 1/15` | none |
| a23f9c2 | Scopes: AgX scope LUT cached per JPEG look (`ScopeLutCache`; LUT is a pure function of a data class, samplers read-only, camera thread only). Soak test: pins developed format JPEG; `-e agxScope true` | — | JVM 1547/0, no baseline class lost tests. Phone A/B, AgX scopes, same test ×2 per build: LUT 4.16/4.54 → 0.17/0.16 ms, scan 8.64/7.99 → 9.84/9.49 ms, **total 12.80/12.53 → 10.01/9.66 ms per sample (~−22 %)**; linear-RAW scopes unaffected | camera-thread time ↓ |
| b49a8fe | Viewfinder control: RAW-stream selection (ZSL / histogram / ETTR / PROGRAM / RAW VF) moved verbatim out of `updateRepeatingRequest` into `RawStreamPlan` (6 JVM tests); duplicated FPS-range set merged (same key, same value). Verified: MotionCam repeat request = TEMPLATE_PREVIEW + preview + RAW targets; `CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG` semantics ("stream full-resolution images … final capture"); recovery decisions already pure + tested, left in place | MotionCam `CameraSession.cpp`; AOSP `control.captureIntent` | JVM 1553/0, no baseline class lost tests. Phone: AUTO 5/5, PROGRAM 5/5, burst 3/3, refactor checks 4/4. **ZSL soak: 15/16 on this build** (1 failure "shutter remained disabled after save", first run after install, log lost, not reproduced in 15 reruns) vs 6/6 on the previous build. Logic equivalent (three pure reads now evaluated eagerly) | watch: rare ZSL rearm failure |
| (next) | JXL study: darktable citation fixed (J0); `JxlColorEncoding` zeroed like the DNG SDK (J1); JXL proof harness (J2) | darktable jxl.c `e335c52`; Adobe DNG SDK 1.7 | libjxl 0.11.1 baseline 16/16 checks pass; negative control fails on one flipped bit; J1 outputs byte-identical (4/4 files) | none |

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

- **E1/E2** (ETTR): 0.3 EV default headroom vs Magic Lantern's ≥0.5 EV; slow recovery from clipped frames. See `exposure-study.md`.

- ZSL copy-out pool (MotionCam design) — see `zsl-study.md`, needs device measurements.

## Process note

- Read only fresh test results: delete `app/build/test-results/testDebugUnitTest`
  before a run. A run that fails at task selection leaves old XML behind
  (this happened once; caught before commit).
- **Never create files with shell redirection without checking the path
  first.** `cat >` overwrote the existing `ProgramAeSolverTest.kt` (19 tests)
  once; caught because the total fell from 1540 to 1526, restored from git
  before any commit. Every test run now also compares per-class counts
  against the archived baseline results.
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
