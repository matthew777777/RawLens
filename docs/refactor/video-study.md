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
