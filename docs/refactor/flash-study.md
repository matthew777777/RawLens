# Flash and torch: study (2026-10-22)

Scope: `RawCameraController` toggleTorch/cycleFlashStill/setTorchEnabled,
applyStillFlash, captureSingleWithFlash, runFlashPrecaptureThenCapture.
Decisions already live in `FlashStillPolicy` (A10, with JVM tests).

## Reference
Open Camera `CameraController2.java` @ `0dd4cbe` (2026-05-27): precapture
states STATE_WAITING_PRECAPTURE_START / _DONE (lines ~7250, ~8211).

## Findings

| # | Finding | Proposed |
|---|---|---|
| F1 | RawLens fires the still once `lastAeState` reads CONVERGED, FLASH_REQUIRED or LOCKED, polled every 100 ms with one 2 s timeout. That state can come from a result produced before the trigger took effect, so the still can fire before metering ran. Open Camera: send an IDLE-trigger request first, wait for AE to **enter** PRECAPTURE (2 s timeout), then for it to **leave** (3 s timeout); it deliberately does not accept FLASH_REQUIRED as "started" (bad exposures on Nexus 6 / OnePlus 3T). | Rewrite batch: pure `FlashPrecapture` two-phase state machine fed by AE states from results after the trigger, Open Camera timeouts; JVM tests. |
| F2 | The trigger request is sent once with `capture()`; Open Camera also re-arms the repeating request with the precapture builder (trigger IDLE) so AE state is reported on every frame. | Same rewrite. |

## Move
No verbatim extraction now: the remaining code is capture/session
orchestration; the precapture wait is extracted in its corrected form in
the rewrite batch (owner: rewrites after the remaining moves).

## Done (A3, 2026-10-22)
`FlashPrecapture` is Open Camera's two-phase wait (enter PRECAPTURE <= 2 s,
leave it <= 3 s; null AE state passes both), fed with every preview
result's AE state plus a 100 ms timeout tick; an IDLE trigger precedes
START (F2). A pre-trigger CONVERGED / FLASH_REQUIRED can no longer fire
the still (F1). 4 JVM tests. Device verification pending.


## Device follow-up and owner policy (2026-10-26)

- PROGRAM (including its custom-AE setting) and enabled ETTR offer **Off / On only**.
  Entering either with Auto already selected clears it to Off; the cycle skips
  Auto. Exposure settings and flash accessibility descriptions explain:
  "Auto flash is unavailable in PROGRAM and ETTR. RawLens needs its own auto-flash metering."
- Their ISO/shutter resolution is unchanged. Forced On retains AE_OFF and
  FLASH_SINGLE; no HAL AE takeover of the PROGRAM/ETTR pair was shipped.
- Ordinary hardware-AE flash now uses the existing singleShotTarget fallback
  for RAW-only sessions, feeds one-shot 3A results into the precapture waiter,
  discards those RAW preview frames from still pairing, and keeps its metered
  flash AE mode until capture completion. Completion/error/stop clears the waiter
  and override. Explicit manual ISO/shutter keeps the original SINGLE path.
- Reference: [Android CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest)
  and [AOSP 3A modes](https://source.android.com/docs/core/camera/camera3_3Amodes).
  Hardware AUTO flash is HAL-controlled and conditional on scene brightness;
  PROGRAM/ETTR do not substitute a guessed software flash threshold.

On Redmi 25080RABDG / Android 16, the final real-controller probe passed:
OFF did not fire; ordinary forced ON and AUTO when FLASH_REQUIRED reported
FIRED; explicit manual, PROGRAM and ETTR forced ON reported FIRED with AE_OFF.
The owner also confirmed seeing the LED pulse. Tests check Auto suppression,
selection reset, and cleanup. **91 focused unit tests passed** in isolated
reports. The tested minified release **1.0.434** was installed on the phone.
[Device traces, test result and release hash](results/flash-2026-10-26/).
The probe creates real still captures; existing media was not deleted.

TODO: implement RawLens-owned auto-flash metering for PROGRAM/ETTR before
re-enabling Auto there. This remains deliberately unimplemented.
