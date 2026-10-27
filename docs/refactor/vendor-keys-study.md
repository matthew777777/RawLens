# Vendor keys: source audit and gap fixes (2026-10-27)

The initial audit identified V1–V12. The owner subsequently authorized filling
those gaps. This document describes the resulting implementation. Source
agreement is distinct from evidence of sensor effects on a particular device.

Scope: vendor model, JSON, storage, Android key resolution/discovery,
application/probe, editor, and controller session/request integration.

## Pinned references

- **P**: [PhotonCamera VendorTagUtils](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/api/VendorTagUtils.java):
  `isSupported`, `TunableKey`, `applyTunableKeys`, `builderSessionApply`.
- **PS**: [PhotonCamera CaptureController](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java):
  `buildSessionParams`, `setSessionKeys`, session creation, preview/still application.
- **V**: [PhotonVidCam VendorTagUtils](https://github.com/AJ732101/PhotonVidCam/blob/458be5bf8c9942963ffde5a54408d16650d934be/app/src/main/java/com/particlesdevs/photoncamera/api/VendorTagUtils.java):
  `isSupported`, `builderSessionApply`.
- **VC**: [PhotonVidCam CaptureController](https://github.com/AJ732101/PhotonVidCam/blob/458be5bf8c9942963ffde5a54408d16650d934be/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java),
  session creation around lines 1446–1453.
- **VR**: [PhotonVidCam CameraReflectionApi](https://github.com/AJ732101/PhotonVidCam/blob/458be5bf8c9942963ffde5a54408d16650d934be/app/src/main/java/com/particlesdevs/photoncamera/api/CameraReflectionApi.java):
  `createCustomCaptureSession`.
- **A**: [AOSP Android 16 CameraDeviceImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java#L845):
  `createCustomCaptureSession` passes `sessionParams = null`;
  `createCaptureSession(SessionConfiguration)` forwards `getSessionParameters()`.
- **S**: [Android SessionConfiguration](https://developer.android.com/reference/android/hardware/camera2/params/SessionConfiguration),
  `setSessionParameters`.
- **B**: [Android CaptureRequest.Builder](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.Builder),
  `get`, `set`, `setPhysicalCameraKey`.

Both repository default branches were `dev` when inspected. PhotonCamera's
`master` is older; conclusions here use the pinned default-branch revisions.
Reference code is evidence of implementation, not proof that its comments or
OEM recipes are correct.

Additional framework sources for the gap fixes:

- [AOSP API 30 SessionConfiguration constructor](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-11.0.0_r1/core/java/android/hardware/camera2/params/SessionConfiguration.java),
  [API 33](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/core/java/android/hardware/camera2/params/SessionConfiguration.java),
  [API 36](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/hardware/camera2/params/SessionConfiguration.java):
  constructor stores the integer session type unchanged.
- [AOSP CameraMetadataNative.Key](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/hardware/camera2/impl/CameraMetadataNative.java):
  `getTag`, `getType`, `mVendorId`; tag lookup depends on vendor identity.
- [AOSP CaptureRequest.Key](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/hardware/camera2/CaptureRequest.java):
  `getNativeKey`; public name/type construction versus framework vendor keys.
- [AOSP CameraMetadata.getKeys](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/hardware/camera2/CameraMetadata.java):
  advertised vendor keys carry their provider IDs; numeric IDs must not be
  guessed from list position.

## Findings resolved

| ID | Finding | Implementation |
|---|---|---|
| V1 | A successful logical `get` was called HAL support. | Probe and application use the same actual `set` destination. Feedback explicitly says builder acceptance, never verified DCG/remosaic. |
| V2 | String vendor values missing. | String type retained; empty strings are allowed, whitespace preserved, total length bounded and NUL rejected. Existing type labels/order remain compatible. |
| V3 | Editing key lists discarded operating mode; mode overflow could wrap. | Edits preserve mode in atomic store updates. Mode must be 0 or >=32768, preventing accidental high-speed/reserved session modes. Strict import rejects invalid modes; tolerant persisted load uses 0. |
| V4 | Custom operating-mode API silently discarded session keys. | `VendorSessionConfiguration` constructs a modern configuration with the requested integer session type, same output configurations/executor/input, and early parameters. `CameraDevice.createCaptureSession(config)` forwards both type and parameters in AOSP. Hidden custom-session reflection removed. |
| V5 | Advertised session lists incorrectly prevented attempts. | Every enabled explicit session entry is individually set; no advertisement gate or preliminary get. This follows PS's actual `setSessionKeys` implementation. Rejected values skip individually; session-level rejection has bounded fallback. |
| V6 | Physical-only controls failed a preliminary logical read; probe and session destination differed. | Request controls advertised for physical use are set there first; existing logical fallback on IllegalArgumentException remains. Session probes and application both set logically. The controller uses the open logical device's characteristics for its physical-request eligibility list, while image calibration still uses the selected sensor's characteristics. No unconditional duplicate write. |
| V7 | Numeric tags were merely serialized hex names. | Per-characteristics catalog maps native IDs to advertised request/session/physical key objects. Numeric and named controls reuse the original key, retaining native type/provider ID. Unknown/unresolvable numeric tags are refused explicitly; no fabricated hex-named key or raw metadata write. |
| V8 | Static metadata was offered as writable controls; physical-only controls omitted. | Discovery lists request, physical request and session keys only, with correct source/scope precedence. Read-only characteristics are excluded. |
| V9 | Unsupported universal Vivo/Xiaomi recipe claims. | Removed proven/universal claims and Xiaomi mode example from UI. Existing disabled Vivo template remains an opt-in template. Neither upstream source proves its effect on every phone. |
| V10 | Failed session template omitted some skipped entries. | All active session entries are reported skipped when template/build fails. |
| V11 | Reports persisted across routes/disabled keys and claimed application after fallback. | Stop/open clear reports; empty request profiles publish empty reports. Session reports distinguish pending builder output from configured session, show actual mode/fallback, and move dropped keys to skipped. Reopen badge compares the active mode/parameter fingerprint with saved settings. |
| V12 | Exported mode-only profiles could not restore a mode. | Mode-only export/preset save/import work. Additive merge keeps a destination nonzero mode, otherwise adopts incoming mode. Editor explains this policy. Explicit mode editor can replace an existing mode. |

## Session recovery

`VendorSessionAttempt.sequence` produces unique attempts:

1. Requested vendor mode with accepted session parameters, if present.
2. Standard mode with the same parameters (only when a vendor mode was requested).
3. Standard mode without parameters (only when parameters were present).

Each occurs at most once. Synchronous IllegalArgumentException or
UnsupportedOperationException and asynchronous `onConfigureFailed` advance
the same sequence. Unsupported modern APIs can then use the existing legacy
surface API with mode 0/no early keys. An asynchronously rejected standard
output combination goes to the existing stream/route ladder, without a
redundant legacy submission. Camera access/closed-device failures go to
normal controller recovery. Failed sessions close; stale callbacks cannot
install a session or start another retry. Saved settings are never silently
rewritten by recovery.

The default profile has one modern attempt and preserves the legacy API
compatibility fallback. No vendor key is added or enabled automatically.
No extra capture is issued by discovery/probing.

## Persistence and request cost

- Store reads tolerate malformed JSON or wrong preference types without
  deleting the original. Imports are strict: damaged rows, duplicates,
  excess entries, unknown format versions and invalid mode fields fail.
- Tolerant persisted loads keep the first valid identity and cap at 32 rows.
  Equivalent hex spellings share one identity. Profiles are bounded to
  262144 JSON characters; values to 2048 characters and arrays to 64 elements.
- JSON rejects duplicate object members, invalid numbers and raw controls;
  escaping includes form feed. Nonfinite numeric values remain rejected.
- Key edits, renames, deletes, presets and mode changes transform the latest
  profile under one store lock, preserving fields from other edits.
- Session fingerprints use escaped canonical JSON rather than ambiguous
  delimiter concatenation.
- Profile parsing is cached by stored JSON in a bounded 64-entry store cache.
  External preference changes invalidate naturally. Advertised key catalogs
  use weak characteristic keys; tag indices are lazy, and native type lookup
  is cached per key. Named application does not scan/reflect the full catalog
  on each builder. No image processing or per-frame capture callback work added.

Byte arrays accept signed -128..127 and unsigned 0..255 bit-pattern spelling.
Short is not advertised as an editor type: upstream's Java Short mapping
alone does not establish a Camera2 native marshaler. Existing supported types
cover Byte/Int/Long/Float/Double/Boolean/String and byte/int/long/float arrays.
Unsupported value classes are rejected rather than coerced. Standard level
characteristics remain calibration settings, never request writes.

## Validation

- Full JVM suite: **1700 tests, 0 failures, 0 errors, 5 skips**; 42 vendor tests.
- Full release `assembleRelease` passed with normal checks, R8 and resource
  shrinking. Release 1.0.558 arm64 APK built/installed and ART `speed` compiled.
  Installed APK SHA-256 exactly matches the build:
  `c29c91f39caeb137371218ca39cd6c1055eaae9572ef2dc849720301b98f5704`.
  Package flags exclude DEBUGGABLE. Phone: Xiaomi 25080RABDG / Android 16.
- All phone checks used that full release APK through its UI. The unminified
  deviceTest APK was built but **never installed or used as device evidence**.
  The initially prepared direct-app framework test was removed in favor of
  `tools/vendor_release_smoke.py`, which verifies the installed release checksum.
- Shipping discovery resolved `android.control.aeMode` to `0x10003`. A profile
  with that numeric request and the named early session control round-tripped.
- At 19:33:21 local time the release logged parameter attachment, submitted
  `VendorSessionAttempt(operatingMode=37379, parameters=true) legacy=false`,
  applied request alias 0x10003, and reached `Session ready`. No fallback was
  needed on this phone. This confirms transport/configuration, not DCG.
- A real capture saved `IMG_20261027_193344_162.dng` at 19:33:44. Downloaded
  and validated TIFF/DNG structure, 4080×3060 CFA, 16-bit uncompressed payload,
  all strip bounds and varying pixel bytes (24,977,962-byte file).
- Add/delete of an inactive test key preserved mode 37379. The release probe
  rejected unknown numeric 0xFFFFFFFF explicitly. Mode-only import/export
  passed. Importing reserved mode 1 was refused without changing the profile.
- Original empty profile was restored and the standard camera reopened.
- `git diff --check` passed. Main logs: `/tmp/rawlens-vendor-ready-final-unit.log`,
  `/tmp/rawlens-vendor-ready-release-build.log`,
  `/tmp/rawlens-vendor-release-ui-results.txt`,
  `/tmp/rawlens-vendor-release-extra-results.txt`.
  UI driver corrections involved text entry, canonical export sorting and
  reopening the editor after Cancel; no app source change was needed.
  The initial combined build hit a lint FIR crash; a second was terminated.
  The isolated final unit and full-release builds both passed.

The [durable result record](results/vendor-keys-release-2026-10-27.txt)
contains artifact identity, runtime events and test limits.

Regression coverage includes synchronous/asynchronous vendor rejection,
parameter-preserving fallback, stale callbacks, legacy API fallback,
physical-only keys without logical reads, physical refusal, per-key errors,
probe/application destination agreement, numeric refusal, strict versus
tolerant loading, mode import, JSON limits, fingerprint collisions, and
concurrent store edits. Release UI checks exercise real numeric resolution, early parameters plus
a vendor session type, persistence/editor paths and real capture delivery.
Physical routing and asynchronous fallback were fault-tested in JVM tests;
they were not induced on this phone. No multi-device performance A/B was run.

## Production limits

Numeric IDs are device/provider-specific. Tag reflection uses non-SDK
framework access which an OEM or future Android version may restrict. On
refusal the key is explicitly skipped and its advertised name remains the
supported route; no hidden-API exemption or bypass is installed. Unadvertised
numeric IDs cannot safely be inferred and are refused. Named unadvertised
keys are attempted with the user-specified type.

A configured session and accepted builder do not prove a proprietary sensor
feature engaged. Actual DCG/remosaic recipes, physical-camera behavior and
equal performance across the device matrix require tests on those devices.
No implementation can honestly declare universal OEM recipe support from
these sources or one phone. The validation below states exactly what ran.

## Exclusions

HDR+, MFSR/SR, AMaZE, HL, Galosh, wavelet, FlowNet and RawNIND processing were
not changed by this task. [AUDIT.md](AUDIT.md#excluded-components-untouched-parity-already-demonstrated)
lists existing evidence and limits; exclusion does not imply AMaZE/HL parity.
Unrelated working-tree modifications present before this task remain in place.
