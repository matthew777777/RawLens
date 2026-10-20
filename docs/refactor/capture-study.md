# Capture / pairing / save — study before refactor (2026-10-20)

Scope: `RawCameraController` frame pairing (`pendingImages`, `pendingResults`,
`pendingZslResults`, `pendingTimeouts`, `previewRawTimestamps`;
`pairAvailableFrame`, `pairRawZslFrame`, `pairAndSave`, `schedulePairTimeout`,
`closeAllPendingPairs`, `closeUnmatchedRawImages`, `relieveZslReaderPressure*`,
`drainQueuedRawImages`), the forward-capture callbacks (`captureCallback`,
the RAW branch of `previewCaptureCallback`), `finishCapture`, and the save
dispatch that `pairAndSave` routes into.

## References (pinned)

| Ref | Revision |
|---|---|
| AOSP `camera/docs/metadata_definitions.xml` | system/media main, fetched 2026-10-20 |
| AOSP `CameraCaptureSession.java`, `CaptureFailure.java` | frameworks/base main, fetched 2026-10-20 |
| MotionCam `RawImageConsumer.cpp`, `CameraSession.cpp` | tadit7/motioncam `ea628e5b` |
| PhotonCamera `capture/CaptureController.java` | eszdman/PhotonCamera `b2eff821` |

## Verified

1. **Pairing by `SENSOR_TIMESTAMP`.** "The timestamps are also included in all
   image buffers produced for the same capture, and will be identical on all
   the outputs" (metadata_definitions.xml, `sensor.timestamp`). MotionCam pairs
   the same way (`mPendingBuffers` keyed by timestamp, `doMatchMetadata`).
2. **Displaced duplicates are closed** (`pendingImages.put(...)?.let(release)`),
   matching MotionCam's "Pending timestamp already exists" discard.
3. **A repeating result never consumes a still slot**: the preview callback
   pairs a RAW result into a forward capture only through `ForwardRawSelection`
   (strictly increasing timestamps after the cutoff).
4. **Teardown closes Images before the ImageReader**, which `stop()` documents
   (gralloc lock errors otherwise); consistent with ImageReader's ownership
   model (Images must be closed to return buffers).

## Findings

| # | Where | Finding | Proposed |
|---|---|---|---|
| C1 | `finishCapture` | With an HDR bracket active, `captureSequence` is incremented twice and `activeFramesRemaining` zeroed twice; it also calls `resumeRawZslIfIdle()` and most callers call it again | Record only: harmless, but simplifying changes call order |
| C2 | `refreshCaptureAvailability` comment | "a 30-frame burst" — the ring maximum is 59 | Fix text |
| C3 | `onCaptureFailed` / `onCaptureSequenceAborted` / pair timeout | One failed frame ends the whole forward capture and discards frames already paired. Spec: "Other requests are unaffected, and some or all image buffers from the capture may have been pushed" | **Owner decision** (behaviour). Neither reference does better: MotionCam's handlers are empty, PhotonCamera has none |
| C4 | callbacks | `onCaptureBufferLost` not implemented: a lost RAW buffer waits out `PAIR_TIMEOUT_MS` instead of failing fast. Spec: buffer-lost + `onCaptureFailed(wasImageCaptured=true)` when result metadata is also lost | **Owner decision** (behaviour) |
| C5 | `stop()` | Pairing maps cleared three times (`finishCapture` → `closeAllPendingPairs`, inside the lock, after the lock) | Keep the in-lock one (must precede reader close) and the post-lock safety net; route both through the pairing component |
| C6 | `pairAndSave` / `finishCapture` | HDR-bracket reset (`activeHdrStops = 2`, `activeHdrFrameEvs = [-2,0,2]`, flags) written twice | One helper |

## Proposed move

1. C2 text, C6 helper.
2. `FramePairing`: owns the five pairing collections, the pair-timeout
   scheduling, duplicate/unmatched/teardown release and the reader-pressure
   relief priority (unmatched → oldest ring frame → oldest pairing). Routing
   of a completed pair stays in the controller behind a `Host`
   (`onForwardPair(image, result)`, `onZslPair(...)`, `onPairExpired(...)`),
   so HDR+/MFSR/bracket/dual-RAW save paths are untouched. JVM tests for
   pairing order, duplicates, timeouts and relief priority.
3. C3/C4 only after an owner decision.
