# RAW ZSL ring — study before refactor (2026-10-20)

Scope: `RawZslBuffer.kt`, `RawImageOwnership.kt`, `ZslOverflowTracker.kt`,
`CaptureQueueCapacity.kt`, `ForwardRawSelection.kt`, and the ZSL parts of
`RawCameraController.kt` (RAW image listener, pairing, admission, overflow
relief, watchdog, cooldown, selection retry, save dispatch). No code moved yet.

## References used (pinned)

| Ref | Revision |
|---|---|
| AOSP `camera/docs/metadata_definitions.xml` (source of the Camera2 reference docs) | `platform/system/media` main, fetched 2026-10-20 |
| AOSP `media/java/android/media/ImageReader.java` | `platform/frameworks/base` main, fetched 2026-10-20 |
| PhotonCamera `capture/CaptureController.java` | eszdman/PhotonCamera `b2eff821` (2026-10-07) |
| MotionCam `RawBufferManager.cpp`, `RawImageConsumer.cpp`, `CameraSession.cpp` | tadit7/motioncam `ea628e5b` (2022-03-22, last open-source state) |
| Google Research, [HDR+ with Bracketing on Pixel Phones](https://research.google/blog/hdr-with-bracketing-on-pixel-phones/) (2021) | — |

## Verified (code matches the reference)

1. **App-held RAW ring is the only RAW ZSL route.** Camera2 reprocessing
   inputs are PRIVATE (`PRIVATE_REPROCESSING`) or YUV_420_888
   (`YUV_REPROCESSING`) only; `CONTROL_ENABLE_ZSL` lets the HAL reuse past
   frames for STILL_CAPTURE requests, with no RAW guarantee. PhotonCamera
   (`mZslRingBuffer`, ArrayDeque<Image>) and MotionCam (`RawBufferManager`)
   both keep their own ring.
2. **Frame completion time.** `SENSOR_TIMESTAMP` = "start of exposure of first
   row"; `ROLLING_SHUTTER_SKEW` = first-row to (last+1)-row exposure start.
   `completedAt = ts + exposure + skew` is end of exposure of the last row.
   Timestamps share `elapsedRealtimeNanos` only when `timestampSource ==
   REALTIME`; RawLens adds the offset and compares with the press clock only
   then (`rawZslRealtimeTimestamps`), else compares sensor timestamps.
3. **Overflow relief.** ImageReader: once `maxImages` are acquired,
   `acquireNextImage` throws `IllegalStateException` "until more images are
   released with Image#close". So draining the queue frees nothing; a *held*
   image must be closed first — exactly `relieveZslReaderPressure` then
   `drainQueuedRawImages`.
4. **acquireNextImage, not acquireLatestImage.** ImageReader docs: Latest
   "will automatically release older images"; Next is "recommended for
   batch/background processing". Dropping intermediate frames would lose the
   image whose result is pending — the comment at the listener is correct.
5. **Drain on request transitions** — PhotonCamera does the same
   (`CaptureController.java` ~3586–3592). Comment on `drainQueuedRawImages`
   verified.
6. **Oldest-first eviction** — PhotonCamera `pollFirst()`+`close()`;
   MotionCam `dequeueUnusedBuffer()` takes `mReadyBuffers.front()`.
7. **Partial take** — PhotonCamera `take = min(ring.size, frameCount)`.
8. **ZSL before + forward frames after the press** (hybrid top-up) — the
   ZSL/PSL split is described in the Google Research post above.

## Findings (to fix during the move)

| # | Where | Finding | Fix |
|---|---|---|---|
| F1 | `RawZslBuffer.takeUpTo` KDoc; controller ×11 | "GCam available-capacity rung", "GCam bottom rung", "GCam non-ZSL style degradation", "PhotonCamera/GCam style" | GCam is closed source: keep only claims with a public source (ZSL+PSL → Google Research post); reword the rest as RawLens design |
| F2 | `BufferedRawFrame.metadataApproximate` / `admitStaleZslFrame` KDoc | "PhotonCamera-style approximation" | PhotonCamera uses the preview result for **every** ZSL frame; RawLens only on a missed pairing. Say so |
| F3 | `RawZslBuffer.qualityScore` | Frame ranking (AE/lens state, 0.15/ISO-stop, ×120 motion, 0.1 s age, 1.5 approx) has no reference; PhotonCamera takes the newest N, MotionCam the nearest-in-time to the reference | Behaviour kept (parity). Document as a RawLens heuristic with unsourced weights; flag for a future evidence-based decision |
| F4 | `calculateRawZslCapacity` | Computes frame bytes only to test `> 0`; name and maths imply a memory budget that does not exist | Replace with the plain condition; drop `RAW_BYTES_PER_PIXEL` if unused |
| F5 | same KDoc | "a full 30-frame ring" — `MAX_ZSL_FRAMES = 59` | Correct the text |
| F6 | `chooseRawZslFpsRange` log | `target=$X requested=$X` logs one constant twice | Single field |
| F7 | `selectAndSaveRawZsl` | MFSR and HDR+ branches are the same 18-line block | One helper; HDR+/MFSR merges themselves untouched (excluded) |
| F8 | `saveRawSuperResolutionMerge` | "Future SR hook" stub, zero callers, only releases images | MFSR-adjacent: list under excluded; leave |

## Architectural note (not for this refactor)

MotionCam copies each RAW into a memory-budgeted pool and returns the
HAL `AImage` immediately (`MAX_BUFFERED_RAW_IMAGES = 8`). RawLens and
PhotonCamera hold the HAL `Image` itself, so ring size is bounded by
ImageReader `maxImages` (RawLens hard cap 61) and gralloc — the root of
the overflow/pressure/watchdog machinery. A copy-out pool would decouple
ring depth from the reader at the cost of a full-frame memcpy per frame
(~24 MB at 12 MP RAW16, ~720 MB/s at 30 fps) and the zero-copy GPU
viewfinder borrow (`RawImageOwnership.borrow`). Changing it alters
memory, bandwidth and device behaviour, so it needs device measurements.
Out of scope for a parity-preserving refactor.

## Proposed move (after approval)

1. `ZslRing` (camera-thread owner of pairing maps, admission, overflow
   relief, watchdog, cooldown, epoch/latch flags) behind a `Host` interface,
   like `TouchFocus`. `RawZslBuffer` stays the storage.
2. Save dispatch (`selectAndSaveRawZsl`, retry loop) stays in the controller
   until the capture/save region is split, since it calls HDR+/MFSR/top-up.
3. F1–F7 fixed in the same series, one commit each; JVM tests for the ring
   policy through a fake host.
