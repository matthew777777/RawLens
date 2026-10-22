# Forward still chain — study before refactor (2026-10-20)

Scope: `RawCameraController.captureFrames` (request building, timeout
budget), `submitNextStill`, `pendingStillRequests` / `activeStillCallback`,
`noteChainDeadAndMaybeRecover`, and how pair completion advances the chain.

## References (pinned)

| Ref | Revision |
|---|---|
| AOSP `CameraCaptureSession.java` (`capture`, `captureBurst`) | frameworks/base main, fetched 2026-10-20 |
| PhotonCamera `capture/CaptureController.java` | `b2eff821` |
| MotionCam `CameraSession.cpp` | `ea628e5b` |
| Device log, Xiaomi 25080RABDG (MT6878), AUTO burst ×6, build `ce864e5` | 2026-10-20 17:50 |

## How RawLens submits a forward burst

All N requests are built up front (TEMPLATE_STILL_CAPTURE, RAW + optional
preview target, `CaptureTag(captureId, i, N)`, `applyCameraControls`, HDR
exposure per bracket, still flash, lens-shading map). Only the first is
submitted with `session.capture`; each completed pair (or, since C3/C4, each
lost frame) submits the next. The code says this is because "HALs whose
gralloc pool cannot survive a pipelined N-frame burst (log-proven on
MediaTek)". No such log is in the repository; the chain arrived in `2b94709`.

## References differ

- AOSP `captureBurst`: "captured in the minimum amount of time possible, and
  will not be interleaved with requests submitted by other capture or repeat
  calls … the main difference … is that this method guarantees that no other
  requests will be interspersed with the burst."
- PhotonCamera submits the whole burst with `captureBurst(captures, …)`
  (`CaptureController.java` ~3806).
- MotionCam submits N requests in one `ACameraCaptureSession_capture`
  (`CameraSession.cpp` ~850–897).

## Measured on device (R1)

Sensor-timestamp spacing inside each 6-frame burst: 200 ms for frame 1→2,
then **166.6 ms** for every later frame (e.g. 169252965158260 →
169253131806260), i.e. exactly 5 sensor periods at 30 fps. The session's RAW
`minFrameNs` is 50 ms (20 fps), so a pipelined burst could plausibly run at
~50 ms/frame: ~3× shorter bursts and less motion between frames. Whether
MT6878 survives it is the open question the chain was written for.

## Findings

| # | Finding | Proposed |
|---|---|---|
| R1 | Serialized chain costs ~167 ms/frame on MT6878 vs ~50 ms possible; the "log-proven" reason has no log in the repo; both references pipeline | **Owner decision**: device experiment (pipelined `captureBurst` behind a flag, soak on this phone), keep serialized as fallback |
| R2 | Chain state (`pendingStillRequests`, `activeStillCallback`, `submitNextStill`) is spread through the controller and reset in `finishCapture` | Extract `StillChain` (queue + submit + error outcome), verbatim |
| R3 | `submitNextStill`'s three failure branches repeat the same top-up/finish/resume/dead-chain tail | One helper |
| R4 | `captureFrames` catch blocks repeat `cameraCaptureSequenceActive = false; finishCapture(..); resume; dead-chain` | One helper |

## Proposed move

R3/R4 helpers, then R2 `StillChain` with JVM tests (queue order, submit on
pair/loss, error outcome). R1 only with owner approval, as a flagged experiment.

## Done

- R3/R4: `932d7ce`. R1: pipelined bursts by default with a persisted
  fallback (`90e7973`).
- R2: `StillChain` (queue, active callback, take-next, clear) extracted with
  4 JVM tests; the controller keeps every submit and error action. Phone:
  4 forward 6-frame bursts, pipelined path, 24/24 saved.
