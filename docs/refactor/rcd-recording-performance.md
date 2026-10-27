# RCD recording performance follow-up

Priority agreed with the user on 2026-10-27: achieve a sustained 33.3 ms frame interval in Direct Log RCD recording (30 fps), including the complete recording pipeline.

The user subsequently specified stable 24 fps as the minimum acceptable result, with 30 fps preferred. Evaluate cadence as well as the overall average; do not manufacture throughput by duplicating frames or changing sensor-relative RCD timestamps.

The user reports approximately 16–17 fps regardless of selected resolution. Investigate a shared scheduling, synchronization, camera delivery, buffer-copy, or encoder bottleneck before assuming demosaic cost alone explains the limit. Confirm the actual sensor crop and dispatch dimensions for every output resolution; a smaller encoded output may still process a full-size RAW crop.

Measured so far:

- Packed RCD 3840×2160 ACES-to-P010 isolated device benchmark: warmed median approximately 37.3 ms, range 37.0–37.7 ms. This is not end-to-end recording throughput.
- Device-test recording: 120 muxed video frames over 7.3758 seconds, approximately 16.3 fps; CPU chain average 54.7 ms, copy average 23.1 ms. Recording, audio, decode, and MediaStore publication passed.
- Optimized release 1.0.512 installed; R8 enabled, native Release `-O3`, Android ART `speed` compilation verified. A release UI recording test was started but interrupted; no completed release measurement has been verified. Device subsequently disconnected, so recording state could not be checked remotely.

Next trace should compare resolution settings with actual input/crop/output dimensions and per-frame timestamps for camera arrival, queue wait, Vulkan mutex wait, GPU submission/completion, P010 copy, codec input/output, and muxed PTS. Determine which work overlaps and which serializes; do not add overlapping averages as though they were independent elapsed times. Check preview contention, stale frame handling, queue depth, and thermal state. Report both steady-state frame interval and actual muxed fps.

Preserve image quality. Direct Log's RCD option uses Packed RCD; still photos retain the existing RCD. Keep the original approximately 200 ms baseline in `tools/rcd-baseline/fast-20261026/` intact. The 33.3 ms goal has not yet been achieved.

## Optimizations and measurements on 2026-10-27

- RCD now submits asynchronously with three independent sets of stage descriptors and grade parameters. Internal images stay queue-owned, with barriers between batches. A completion fd gates RAW lease release and encoder delivery. Slot reuse also waits for the actual Vulkan fence, accounting for an exported fd signaling slightly earlier.
- Shared arrays use specialization-sized dimensions. The tuned shape uses 32×16 for guide, 32×32 for green, 32×8 for median, and 32×32 for row-pair output. The output stage owns two pixels in one row instead of four pixels in a Bayer quad. Each output profile has its own specialized pipeline; its arithmetic is unchanged.
- The weighted median sorts eight neighbors with 19 compare-exchanges, then inserts the duplicated center into ranks four and five using clamps. The final half-precision sum and multiply are retained. Host verification covers all 512 Boolean inputs and 100000 finite-half cases. GPU tests require zero pixel differences from the original shader source and zero P010 differences from separate grading across four profiles.
- Isolated 4K ACES/P010 warmed samples with these changes: 33.42, 33.17, 35.95, 33.83, 33.27, 35.93 ms (median approximately 33.62 ms). Individual samples below 33.3 ms do not establish sustained 30 fps.
- Asynchronous staging/copy recording improved from 16.3 fps to roughly 22.7–23.0 fps. The CPU copy still cost roughly 25 ms and overlapped/competed with GPU work.
- A GPU ImageWriter path writes the same P010 output directly into encoder-owned buffers. It probes format, usage, storage import, and tight allocation layout before selecting this path. Unsupported devices retain direct-P010 staging/copy. Writer buffers remain owned until GPU completion; when preview reads a writer buffer, an additional completion-fd reference gates encoder delivery through the preview dispatch.
- First GPU-writer 4K ACES / 30 fps request: 25.4 muxed fps, CPU copy 0 ms, saved Main10 with audio and decoded frame. This first run had one BUSY drop, subsequently addressed by waiting on the actual Vulkan submission fence at slot reuse.
- GPU-writer 4K ACES / 24 fps request after that fix: 344 muxed frames, 23.9 fps over approximately 14.4 seconds, zero drops/starves/degraded frames, copy 0 ms; Main10, audio, decode, and MediaStore save passed. The initial test harness capped the requested 30-second run at 15 seconds. The cap was raised and per-second cadence assertions added for a longer soak.

Rejected experiments: shared high-pass cache (pixel-exact but slower); direct RAW fetching without shared tiles (slower and failed source pixel parity). Neither is retained in runtime assets.

Android's `Image.getHardwareBuffer()` does not support MediaCodec input Images; the writer path obtains supported buffers from ImageWriter instead. See [Android Image API](https://developer.android.com/reference/android/media/Image#getHardwareBuffer()).

## Longer cadence verification

One early 30-second 24 fps run failed cadence checks (20.5 fps average, 18–24 frames in complete seconds), despite thermal status zero. This variability motivated foreground testing with the real monitor attached; do not treat the short initial test as a stability guarantee.

The next 30-second 24 fps repeat passed: 705 frames, 24.0 muxed fps, exactly 24 frames in each of 27 complete steady-state seconds; p95 gap 41667 µs, maximum steady gap 41778 µs. No drops, starves, degraded frames, or CPU pixel copies. Main10, audio, decode, and publication passed.

The 30-second foreground/live-monitor run at 24 fps also passed: 706 frames, 24.0 fps, exactly 24 frames in every one of 27 measured seconds, p95 41667 µs and maximum steady gap 41778 µs. No drops/starves/degraded frames or over-budget submissions. This is the recommended stable setting on the tested phone.

The 30-second foreground run requesting 30 fps averaged 26.4 fps (775 frames), with 26–28 frames per complete second and 167 live preview submissions. No drops/starves/degraded frames, copy 0 ms; video/audio/decode/save passed. The p95 frame gap was 66655 µs, so this is throughput progress, **not stable 30 fps**. RCD preserves the actual sensor timestamps rather than duplicating frames to inflate the count.

Fence ownership cleanup: the delivery worker clears the matching slot fd before closing it and releasing the slot latch. Stop-time cleanup consequently cannot close a stale descriptor number that the codec or camera has since reused.

Writer input ownership: await the dequeued Image's acquire fence before GPU writes. `getHardwareBuffer()` does not consume this fence as `getPlanes()` does. GPU completion (including any monitor read) is separately proved before queueing the Image to the encoder. See [AOSP ImageWriter native implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/jni/android_media_ImageWriter.cpp) and [Image.getFence](https://developer.android.com/reference/android/media/Image#getFence()).

## Resolution routing

Smaller encoded resolutions previously retained the full RCD crop and switched to a full-size RGB intermediate plus a separate scale/grade pass. This explained why output resolution alone did not proportionally reduce work. The new fused stage reconstructs only the source sites selected by the separate grade's exact f32 nearest-coordinate formula, preserving the original full-source RCD calculation and RGBA16F rounding. Scaled output uses direct ratio-image reads; full-size output retains the shared ratio tile. Scaling and output profile are pipeline specialization constants, removing unused paths from each compiled variant.

GPU parity tests cover 128×96 source output at 64×48, 96×54, 128×96, and 256×192 across BT.709, S-Log3, HLG, and ACES. Every sampled P010 unit must match the separate RGB+grade path exactly. No lower-detail demosaic or sensor binning is introduced.

Final scaled-output foreground tests (30-second ACES takes, real live monitor, Main10/AAC/decode/publication):

| Output | Requested | Muxed result | Complete steady seconds | Preview submissions | Drops/starves/degraded |
| --- | --- | --- | --- | --- | --- |
| 1920×1080 | 30 fps | 884 frames, 30.0 fps | 27 windows, all exactly 30 | 451 | 0 / 0 / 0 |
| 2560×1440 | 30 fps | 883 frames, 30.0 fps | 27 windows, all exactly 30 | 381 | 0 / 0 / 0 |
| 3840×2160 | 24 fps | 706 frames, 24.0 fps | 27 windows, all exactly 24 | 380 | 0 / 0 / 0 |

Both tests had p95 frame intervals of 33411 µs and maximum steady intervals of 33434 µs. CPU chain averages were 0.9 / 1.2 ms, with no over-budget submissions and no CPU pixel copies. MediaStore publications were IDs 55988 and 55990 on the test phone. Decoded PNG export supports visual checks in addition to stream/container checks.

The final 4K 24 fps repeat after both fence fixes had p95 41667 µs, maximum steady gap 41778 µs, CPU chain average 1.0 ms, no over-budget submissions, and no CPU pixel copy. Saved Main10/AAC/decode/publication passed (MediaStore ID 55992). This verifies the minimum stable mode independently of the lower-resolution 30 fps results.

An intermediate acquire-fence change incorrectly treated an invalid (empty) SyncFence as a timeout on this phone, dropping every frame and leaving the monitor dark. The corrected check accepts an invalid fence as already complete and waits only on valid fences. Subsequent 1080p/1440p tests above passed. The diagnostic activity now displays an explicit recording/saving badge, because its test-owned recorder is independent of MainActivity's normal record-button state.

The diagnostic decoded frame and monitor both show the same dark indoor exposure against a bright window. This is actual capture brightness rather than a monitor-only mismatch. These neutral diagnostic takes use zero LOG gain; normal release-app capture settings must be checked separately before changing exposure behavior.

## Lens-shading correction

The previous Packed RCD recorder fetched the HAL lens-shading map but did not pass it to the RCD shaders. Its `shadedFrames` count therefore represented map availability rather than actual application. The correction is now wired through synchronous RGB output, synchronous P010, and asynchronous P010 submission.

The final output stage samples the existing linear-filtered Camera2 RGBA16F gain map in full sensor coordinates (`crop origin + selected source site`). It applies R and B gains independently and the mean of Gr/Gb to demosaiced green, after the original RCD half-float rounding and before WB/CCM/exposure/transfer encoding. Scaled output follows the same source-coordinate formula as separate scaling. No full-frame correction pass or CPU pixel copy is added. A malformed/unuploadable requested map fails the submission rather than silently claiming correction. Sensors reporting HAL-applied shading retain the existing skip rule against double correction.

The extended parameter block is 192 bytes within the existing 256-byte per-slot allocation; three independent map images and parameter/descriptor sets protect in-flight frames. Map uploads reuse the existing tiny texture uploader. Preview samples the corrected encoded P010, so recording and monitor receive the same correction.

Device validation: unity gains preserve source RGB exactly; a nonuniform per-channel 3×3 map is checked against `LensShadingModel` with nonzero crop and active-array offsets. Corrected P010 is independently checked before WB/CCM across BT.709, S-Log3, HLG, and ACES at full and scaled output. Maximum observed difference was one 10-bit code value (CPU math/sampler rounding); the test permits at most two. Distinct changing maps across three asynchronous slots also match synchronous results exactly. All eight Packed RCD device tests passed.

During cadence testing, closing the legacy photo preview reset the shared Vulkan recording pipeline. A recording now pins shared GPU resources until its own jobs drain. Viewfinder resets defer during the take; recorder cleanup unpins and resets normally. The integration test can explicitly request a reset mid-take to verify that recording survives it. This guards a resource-lifetime failure rather than masking frame-rate measurements.

Shading-enabled cadence status: after the resource-ownership fix, a 30-second 1080p test survived the deliberate mid-take reset and applied correction to all 877 graded frames. No recorder drops, frame errors, starves, degraded frames, or CPU pixel copies occurred. Muxed average was 29.8 fps; complete seconds were `[30,30,29,28,30,29,30,29,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30,30]`, so the strict cadence assertion failed. This is not recorded as a stable-30 pass. The app was then ART speed-compiled to investigate early startup stalls, but the repeat was interrupted by USB disconnection before results could be collected. Shading-enabled 1440p/30 and 4K/24 verification and final release installation remain pending reconnection. Earlier stable results in this document were measured before RCD lens shading was wired and do not establish the corrected path's cadence.

After reconnection, a quick 4K/24 take graded 223 frames, all shaded, with zero recorder drops/starves/degraded frames and zero CPU pixel copies. Its monitor assertion failed because the test harness copied the dimensions of an inactive viewfinder. The harness now sizes the monitor from the visible legacy/BGU view or the parent viewport. Quick mode skips only debug PNG writing; decoding, audio, publication, correction coverage, and cadence checks remain required. USB disconnected again during the corrected-layout repeat; restarting the ADB server did not recover a device.

Reviewed release APK `RawLens-1.0.544-release-arm64-v8a.apk` is built with normal release optimization and checks; its five Packed RCD shader binaries match current source outputs and both new recording JNI entry points are present. It has not yet been installed. The last installed app is device-test 1.0.543, with recording resource protection, and the latest test APK supports the corrected preview layout and `-e fast true`. Resume quick tests in user-requested order: 4K/24, 1440p/30, 1080p/30, using `-e requireShading true -e stableFps <rate> -e seconds 10 -e foreground true -e fast true`; set width/height for the smaller outputs. Install and speed-compile the release only after results are reviewed.
