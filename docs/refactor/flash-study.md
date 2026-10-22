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
