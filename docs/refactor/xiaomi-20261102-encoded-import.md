# Xiaomi encoded recording black preview

Reviewed the supplied 21,702-line `logcat-20261102_160031_236 (1).txt`,
including the complete timeline and repeated-message patterns. Device:
Xiaomi 24129PN74G, Android 16, app 2.0.69, Adreno driver 0800.71.

Both takes (16:00:40 and 16:00:44) receive RAW_SENSOR 4096x3072,
rowStride=8192, pixelStride=2. HEVC starts successfully with direct P010
plane-copy delivery. All fused submissions return -5 (RAW input import
failure). Adreno reports invalid allocation metadata and rejects both
plain and external imports. Each take grades zero frames and is aborted
by the 2.5-second watchdog; preview presents zero frames, MP4 receives
zero video frames. Idle preview succeeds through `vulkan-plane` upload.

Encoded recording now retries RAW import failure using an app-owned
CPU-writable GPU_DATA_BUFFER BLOB per recording slot. The original plane
pitch, full sensor coordinates and active final-row bytes are preserved.
Subsequent frames avoid the broken camera AHB import for that take.
The existing slot completion handoff gates upload reuse; teardown releases
uploads and the next take resets the fallback. Both MHC and superpixel
paths are covered. Other submission errors do not trigger retry. Positive
compute status 5 and negative fused status -5 are handled separately, so
valid fused completion fd 5 is not mistaken for an error.

Validation: Github debug Kotlin compilation and 19 targeted JVM tests
(16 recorder, 3 import fallback) passed, zero failures/errors.
No ADB device was connected. Actual GPU upload/import, resulting preview,
MP4 decoding and sustained cadence still require an affected-phone retest.
The fallback adds up to three full RAW staging allocations and one RAW
plane copy per frame only on devices where direct import fails.
