# Video study (DirectLogRecorder, RawVideoRecorder)

References: Android MediaCodec / MediaMuxer API docs (lifecycle: a configured
or started codec must be release()d; MediaMuxer must be release()d), and
open-source RAW video recorders (below).

## V1: DirectLogRecorder.start leaked on failure (fixed)

`start()` launches the two copy workers (DirectLogCopyA/B) near the top, then
reads camera characteristics, inits Vulkan stages, negotiates the encoder,
allocates P010 staging, creates the muxer, drain thread and audio. Only the
final camera-open block called `teardown()` on failure, and `teardown()` never
stopped the copy workers, drain thread or muxer. Any earlier throw (no 10-bit
encoder, P010 allocation, Vulkan init, bad camera id) left both workers
polling every 100 ms for the life of the process, plus codec/muxer/audio.

Fix: `start()` wraps the body (`startTake`) and on any exception runs
`abortStart()`: stop audio, stop copy workers, close held fences, stop and join
the drain thread, release the muxer, `teardown()`, delete an empty output
file, clear the crash breadcrumb (a refused start is not a crash).

Proof (Xiaomi 25080RABDG, DirectLogStartFailureDeviceTest, 3 failed starts):
- before: FAIL, leaked [DirectLogCopyA x3, DirectLogCopyB x3]
- after: PASS, no threads, no output file

Parity (same phone, same tests, before -> after):
- DirectLogRecordDeviceTest OK both: open-gate acq 92/graded 91/drop 0 ->
  92/91/0; step4 95/94/0 -> 97/96/0 (frame count tracks take timing)
- RawVideoRecordTest OK both: 4 takes, audio 70-71 chunks each

## RawVideoRecorder vs open-source RAW video recorders

References studied (local clones, commit in brackets):
- MotionCam open source, tadit7/motioncam [ea628e5]: libMotionCam
  RawBufferStreamer.cpp: reference for the pipeline shape only.
- PhotonCamera, eszdman/PhotonCamera [b2eff82]:
  processing/processor/RawVideoProcessor.java.
- RawRec, Not-to-Shabby/RawRec [45ac16a, 2026-10-16]:
  capture/RecordingController.kt (own .rvsp container, CinemaDNG export).
- OpenMotionFX/OpenCamera [1792adf, 2026-07-05]: Camera2Engine.kt (RAW ->
  GPU debayer -> HEVC, plus a CinemaDNG sequence mode).
- Android: CaptureRequest.SENSOR_FRAME_DURATION / CONTROL_AE_TARGET_FPS_RANGE
  reference docs; ImageReader.acquireNextImage contract.

| Topic | RawLens | MotionCam | PhotonCamera | RawRec | OpenMotionFX |
|---|---|---|---|---|---|
| Pipeline | queue cap 8 drop-oldest -> 3 encode workers -> 1 ordered committer | queue -> N process threads -> per-fd IO threads | parallel encoders -> 1 ordered writer, slot pool | queue -> zstd pool -> writer | GPU debayer -> codec |
| Image released | after encode, before disk IO | after container add | after encode, before disk IO | after pack | after GL upload |
| Ordering | seq reorder buffer, wedged head skipped after 250 ms | none needed (one IO thread per file) | single ordered writer | single writer | n/a |
| Timestamps on disk | recording-relative | absolute | recording-relative (credited by RawLens) | own clock | n/a |
| Per-frame result metadata | yes; frame DROPPED if result misses 100 ms | from buffer metadata | yes; falls back to request params | first result only | WB per result |
| AE fps range | fixed (30,30) unchecked -> V3 | n/a | n/a | n/a | hard CFR via SENSOR_FRAME_DURATION |
| Manual frame duration | not set (template default) -> V5 | n/a | n/a | n/a | set |
| Gyro in container | yes | n/a | disabled | sidecar | gcsv sidecar |
| Low-storage stop | none -> V4 | none | none found | auto-stop < 500 MB / 98 % | none found |
| Page cache drop | none -> V6 | none | none | posix_fadvise DONTNEED | none |
| Start-failure cleanup | partial -> V2 | stop() in dtor | state machine FAILED | catch Throwable cleanup | n/a |
| RAW-only session | yes | no (preview + RAW) | no | yes | adds a dummy PRIVATE stream: vivo HAL drops RAW-only to ~20 fps |

## Findings

- V2 (fixed): RawVideoRecorder.start camera-step failure after the encode
  and commit threads started: teardown() forgot them (workers blocked in
  take() forever, committer polling a writer teardown closes). Now
  interrupted and joined first; queued frames released.
- V3 (fixed): RAW video set CONTROL_AE_TARGET_FPS_RANGE (30,30) without
  checking it is advertised. Now the same LogVideoProbe.fixedFpsRange pick
  as Direct-Log: exact (30,30) whenever advertised (identical request on
  every device that worked; this phone advertises it), else the narrowest
  range containing 30.
- V4 (open, owner decision): no free-space guard. At ~220 MB/s (Open Gate,
  654 MB per 3 s), a full disk makes every writeFrame throw (logged, frame
  lost) while the take keeps "recording"; the footer/index write at stop
  then fails and the whole take is unreadable to readers without recovery.
  Proposal: check StatFs at start and periodically on the committer, stop
  the take cleanly (footer written) below a reserve (RawRec: 500 MB).
- V5 (open, verify): AE OFF takes leave SENSOR_FRAME_DURATION at the
  TEMPLATE_RECORD default. Correct while that default is 33.3 ms; set it
  explicitly to 1e9/FPS (OpenMotionFX does) after confirming the default on
  device so the request stays identical here.
- V6 (open, measure first): no page-cache drop on long takes (RawRec uses
  posix_fadvise DONTNEED after each write to keep dirty pages from building
  up). Measure memory over a long take before changing anything.
- V7 (open, owner decision): a frame whose capture result misses its
  100 ms window is dropped (never stale calibration). PhotonCamera writes it
  with request values instead. Count these misses in the soak before
  deciding.
- V8 (refactor): videoControlRange / applyVideoManualToRequest are
  duplicated in DirectLogRecorder and RawVideoRecorder (same logic except V3
  and Direct-Log's lens-shading-map request). Merge into one helper during
  the split.
- Note: RAW-only session. OpenMotionFX reports the vivo HAL drops RAW-only
  sessions to ~20 fps; this phone holds 30 fps, so no change, but it is the
  first thing to check on a vivo report.

V2+V3 build, same phone: DirectLogStartFailureDeviceTest OK,
DirectLogRecordDeviceTest OK (88/87/0 and 94/93/0 acq/graded/drop),
RawVideoRecordTest OK at 72 frames, 30.0 fps each. No "Missing capture result", head-skip or
pool-exhausted line in the baseline, V2 or V3 runs, so the earlier 69-frame
16:9 take was the capture window, not V7.

## DirectLogRecorder.start split

startTake() now calls six stages in order: resetTakeState, resolveGeometry
(returns TakeGeometry: characteristics, sensor size, crop, export size,
effective preferMhc), initGpuStages, startEncoder, allocateBuffers,
startStreams. Moved verbatim: the stage bodies concatenated equal the old
body token for token (5002 tokens), the rest of the file is unchanged.
Device (same phone): DirectLogStartFailureDeviceTest OK,
DirectLogMatrixDeviceTest OK, DirectLogRecordDeviceTest OK with the same
paths as baseline (fused-mhcyuv / superpixel-fallback, DIRECT_P010) and
acq/graded/drop 93/92/0 and 98/97/0.

## V8, processFrame/teardown split, V4

- V8 (done): VideoManualControls.applyToRequest / controlRange hold the one
  copy; each recorder keeps its AE fps-range pick (and Direct-Log its
  lens-shading request). The removed RawVideoRecorder copy was
  token-identical after renames (752 + 449 tokens). Proof on device:
  VideoRequestSnapshotDeviceTest dumps every key/value of both recorders'
  TEMPLATE_RECORD requests plus all slider ranges for 10 manual/lock cases;
  old build vs new build: 1340 lines, identical, no throws.
- processFrame: the three demosaic branches are submitFused / submitRcd /
  submitSuperpixel (FrameSubmit carries the per-frame inputs; null = the
  branch's existing counted drop). Branch bodies token-identical (162 / 721
  / 260 tokens), the surrounding processFrame text identical.
- teardown: stopCameraAndAudio, detachMonitor, releaseGpuBuffers,
  releaseEncoder, releaseSdrEgl; token-identical (816 tokens). (Earlier
  "271 lines" counted into the companion object; teardown was ~95 lines.)
- V4 (done): RawVideoRecorder.storageReserveBytes (500 MB, RawRec's figure).
  start() throws StorageFullException below it (status "STORAGE FULL");
  the committer checks usableSpace every 500 ms, stops admitting frames
  and calls onStorageLow once; MainActivity stops the take through
  stopVideoRecording ("STORAGE FULL • STOPPED"). RawVideoStorageGuardDeviceTest:
  refusal leaves no thread or file; a mid-take trip admits no further
  frames and the stopped take is complete (28 frames, index and
  footer present).

Device (new build 401/403): V8 requests identical; DirectLogStartFailure,
DirectLogMatrix, RawVideoStorageGuard (2), RawVideoRecordTest OK; four
RAW takes at 73-74 frames, ~30 fps; DCIM untouched.

### V9 (open, watch): Direct-Log "copy job: GPU wedge"

On 2026-10-21 morning DirectLogRecordDeviceTest failed intermittently with
"frame exceptions" from awaitGpuFence (GPU > 100 ms behind a frame), on
the ORIGINAL build as much as the new one, back to back, thermal status 0:

| Build | Runs | Failed | Wedged frames per run |
|---|---|---|---|
| original 4f7eeb6 (402) | 5 | 3 | 10, 9, 0, 5, 0 |
| refactor (403) | 5 | 4 | 2, 0, 11, 1, 2 |

Every run the night before (both builds) was clean, so this is device
state (GPU clocks or contention), not the refactor. Not a parity change;
investigate separately (GPU frequency/contention during the take, and
whether 100 ms is the right valve).
