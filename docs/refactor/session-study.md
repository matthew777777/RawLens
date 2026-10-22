# Camera open / session setup — study before refactor (2026-10-20)

Scope: `RawCameraController` `open`, `deviceCallback`, `createSession`
(incl. the inline RAW image listener), `createPerformanceSession`,
`chooseSessionPerformancePlan`, `attachVendorSessionParams`,
`createCustomCaptureSession`, `sessionCallback`, `handleSessionSetupFailed`,
the session ladder (`advanceSessionLadder`, `advancePastFailedOpen`,
`downgradePackedFormatToAuto`, `sessionComboCount`, `resetSessionLadder`) and
the open retries (`scheduleOpenRejectionRetry`, `retryOpenForTransientRoute`).

## References (pinned)

| Ref | Revision |
|---|---|
| AOSP `core/java/android/hardware/camera2/CameraDevice.java` | frameworks/base main, fetched 2026-10-20 |
| AOSP `core/java/android/hardware/camera2/impl/CameraDeviceImpl.java` | same |
| AOSP `camera/docs/metadata_definitions.xml` | system/media main, fetched 2026-10-20 |
| API 37 `android.jar` (`javap -constants`) | SDK platform android-37.0 |

## Verified

1. `StateCallback.onDisconnected` / `onError`: "You should clean up the camera
   with CameraDevice#close" — both callbacks close the device.
2. Surface-list `createCaptureSession(List<Surface>, …)` is `@Deprecated`; the
   code uses it only as the fallback after `SessionConfiguration` is refused.
3. `isSessionConfigurationSupported` probe before committing a hinted plan;
   unsupported-query HALs fall back to DEFAULT.
4. Stream use cases are HAL 3.8 / API 33 (`hal_version="3.8"`), gated on
   `TIRAMISU` and `REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE`.
5. Vendor operating modes start at `SESSION_OPERATION_MODE_VENDOR_START =
   0x8000` (`CameraDevice.java`, @SystemApi); 37379 = 0x9203 is in range.
6. `ImageFormat.RAW14 = 44` exists in API 37 (`IMAGE_FORMAT_RAW14`).

## Findings

| # | Where | Finding | Proposed |
|---|---|---|---|
| **S1** | `createCustomCaptureSession` | **Bug.** AOSP signature is `(InputConfiguration, List<OutputConfiguration>, int, StateCallback, Handler)`; RawLens passes `outputConfigurations.map { it.surface }` (a `List<Surface>`). Reflection does not check generics, and `CameraDeviceImpl` iterates `for (OutputConfiguration output : outputs)` → `ClassCastException`, caught by `catch (Exception)`, standard session used. A user-set operating mode has therefore never been applied. No field log in the repo shows it working. | **Owner decision**: passing `config.outputConfigurations` makes an opt-in feature work for the first time (device behaviour change). Hidden-API access to `CameraDeviceImpl` may also be refused by the platform; unverified. |
| S2 | `createSession` log | "sessions stay RAW_SENSOR (no unpack path)" — packed sessions exist (`effectiveRawFormat`) and `RawPackedUnpacker` is used by 5 classes | Correct the log text |
| S3 | `createSession` catches | Same 6-line reader/surface cleanup in 4 catch blocks | One helper |
| S4 | above `logRawStreamCombo` | Orphaned KDoc ("Camera2's modern session API…") belongs to `createPerformanceSession` | Move it |
| S5 | `chooseSessionPerformancePlan` comments | Spec: DEFAULT and STILL_CAPTURE both have "the same behavior as the default JPEG and RAW related formats", so for the RAW output they are equivalent *per spec*; the comments present DEFAULT as safer. The Samsung stall is field behaviour, not spec | Reword: spec-equivalent, field-observed difference kept |
| S6 | `open()` | Outer `try {` body not indented (~120 lines read as outside the try) | Re-indent (whitespace only) |
| S7 | `createSession` | ~200-line RAW image listener lambda inline | Move to a named method (verbatim) |
| S8 | ladder | `advanceSessionLadder` and `advancePastFailedOpen` repeat the alt-route branch; `scheduleOpenRejectionRetry` / `retryOpenForTransientRoute` are the same shape | Shared helpers |
| S9 | class layout | Ladder/probe fields declared between functions (~line 1580) | Group with the extracted ladder |
| G1 | gap | `CameraDeviceSetup.isSessionConfigurationSupported` (API 35) is AOSP's recommended query; it can run before `openCamera` | Record only; not needed for parity |

## Proposed move

1. S2, S4, S5, S6 (text/whitespace) — one commit.
2. S7 listener → `onRawImageAvailable(reader, generation)` — verbatim move.
3. S3 cleanup helper, S8 helpers.
4. `SessionLadder` (combo index, alt-route flag, failed / unopenable /
   opened-once IDs, retry budgets) as a pure policy with JVM tests, like
   `ZslStreamHealth`; the controller keeps every restart and status action.
5. S1 only after the owner decides.

## Done

- S9: ladder, stream-health, ISP-probe, VF-route and vendor-report fields
  moved, unchanged, to the end of the controller's field block (every
  initializer reads only `context`, constructor arguments or constants, so
  moving them earlier cannot change values). Line-multiset diff: only three
  section comments added.
