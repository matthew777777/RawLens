# Video study (DirectLogRecorder, RawVideoRecorder)

References: Android MediaCodec / MediaMuxer API docs (lifecycle: a configured
or started codec must be release()d; MediaMuxer must be release()d), MotionCam
(tadit7/motioncam) RAW video recorder for the .crs path.

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
  RawBufferStreamer.cpp / RawContainerImpl.cpp. Old v1/v2 container
  (INDEX_MAGIC 0x43525349), so it is the reference for the pipeline shape
  only, not for the file RawLens writes.
- MotionCam official v3 reader, reference decoder [b931ec0]: the
  reference for the `.crs` v3 format RawLens writes ("CRSRAW " 3, footer
  0x43525346). Built on the host; see "Decoder proof" below.
- PhotonCamera, eszdman/PhotonCamera [b2eff82]:
  processing/processor/RawVideoProcessor.java + util/CrsWriter.java.
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
| Gyro in container | yes | n/a | disabled ("Uncrsesome aborts demux"; claim not verifiable: no public repo found) | sidecar | gcsv sidecar |
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

## Decoder proof (MotionCam's own reader)

RawVideoRecordTest `-e keepTakes true` keeps each take; decoded on the host
with reference decoder (every frame loaded, size checked, timestamps
strictly increasing, audio, gyro, accel):

| Crop | Frames (all decode) | Size | fps | Audio | Gyro/accel |
|---|---|---|---|---|---|
| Open Gate | 72 | 4080x3060 | 29.6 | 48 kHz x2 | 1032/1032 |
| 2.39:1 | 72 | 4080x1704 | 30.0 | 48 kHz x2 | 1025/1025 |
| 2.00:1 | 72 | 4080x2040 | 30.0 | 48 kHz x2 | 1018/1018 |
| 16:9 | 69 | 4080x2292 | 28.3 | 48 kHz x2 | 1027/1028 |

V2+V3 build, same phone: DirectLogStartFailureDeviceTest OK,
DirectLogRecordDeviceTest OK (88/87/0 and 94/93/0 acq/graded/drop),
RawVideoRecordTest OK; the four takes decode with reference decoder at
72 frames, 30.0 fps each. No "Missing capture result", head-skip or
pool-exhausted line in the baseline, V2 or V3 runs, so the earlier 69-frame
16:9 take was the capture window, not V7.
