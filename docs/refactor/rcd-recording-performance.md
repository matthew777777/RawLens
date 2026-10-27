# RCD recording performance follow-up

Priority agreed with the user on 2026-10-27: achieve a sustained 33.3 ms frame interval in Direct Log RCD recording (30 fps), including the complete recording pipeline.

The user reports approximately 16–17 fps regardless of selected resolution. Investigate a shared scheduling, synchronization, camera delivery, buffer-copy, or encoder bottleneck before assuming demosaic cost alone explains the limit. Confirm the actual sensor crop and dispatch dimensions for every output resolution; a smaller encoded output may still process a full-size RAW crop.

Measured so far:

- Packed RCD 3840×2160 ACES-to-P010 isolated device benchmark: warmed median approximately 37.3 ms, range 37.0–37.7 ms. This is not end-to-end recording throughput.
- Device-test recording: 120 muxed video frames over 7.3758 seconds, approximately 16.3 fps; CPU chain average 54.7 ms, copy average 23.1 ms. Recording, audio, decode, and MediaStore publication passed.
- Optimized release 1.0.512 installed; R8 enabled, native Release `-O3`, Android ART `speed` compilation verified. A release UI recording test was started but interrupted; no completed release measurement has been verified. Device subsequently disconnected, so recording state could not be checked remotely.

Next trace should compare resolution settings with actual input/crop/output dimensions and per-frame timestamps for camera arrival, queue wait, Vulkan mutex wait, GPU submission/completion, P010 copy, codec input/output, and muxed PTS. Determine which work overlaps and which serializes; do not add overlapping averages as though they were independent elapsed times. Check preview contention, stale frame handling, queue depth, and thermal state. Report both steady-state frame interval and actual muxed fps.

Preserve image quality. Direct Log's RCD option uses Packed RCD; still photos retain the existing RCD. Keep the original approximately 200 ms baseline in `tools/rcd-baseline/fast-20261026/` intact. The 33.3 ms goal has not yet been achieved.
