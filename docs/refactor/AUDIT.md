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
| (next) | Touch AF state: remove dead `cancelOpenCameraAutoFocusForNewTouch` (0 callers); merge duplicated sharp-lock publish into `publishSharpLock` (side-effect order unchanged) | — | 1492/0 | device-unverified (no JVM coverage of controller AF) |

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
