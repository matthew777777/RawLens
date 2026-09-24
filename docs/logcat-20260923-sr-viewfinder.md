# RAW viewfinder / SR contention audit — 2026-09-23

Read all 20 supplied log exports (190,196 lines). The `(1)` exports overlap the
similarly named files; table counts are per export, not independent incidents.

| Log | Lines | GL preview failures | RAW reader overflows |
| --- | ---: | ---: | ---: |
| logcat-20260922_231606_231.txt | 5302 | 2 | 1 |
| logcat-20260923_074018_637.txt | 2302 | 0 | 0 |
| logcat-20260923_082123_195.txt | 2302 | 0 | 0 |
| logcat-20260923_094400_170.txt | 10352 | 3 | 0 |
| logcat-20260923_102059_026.txt | 1302 | 0 | 0 |
| logcat-20260923_113206_650.txt | 5502 | 0 | 1 |
| logcat-20260923_131926_450.txt | 2202 | 2 | 0 |
| logcat-20260923_132557_476.txt | 702 | 0 | 0 |
| logcat-20260923_133023_847 (1).txt | 18294 | 2 | 9 |
| logcat-20260923_133023_847.txt | 19494 | 2 | 9 |
| logcat-20260923_133943_989.txt | 2702 | 3 | 1 |
| logcat-20260923_134430_342 (1).txt | 6277 | 5 | 17 |
| logcat-20260923_134430_342.txt | 12377 | 5 | 17 |
| logcat-20260923_145133_664.txt | 1202 | 0 | 0 |
| logcat-20260923_150438_304.txt | 20452 | 2 | 1 |
| logcat-20260923_153434_756.txt | 3002 | 5 | 0 |
| logcat-20260923_180120_114.txt | 1752 | 0 | 0 |
| logcat-20260923_182310_502.txt | 252 | 0 | 0 |
| logcat-20260923_194123_145 (1).txt | 36938 | 10 | 1 |
| logcat-20260923_194123_145.txt | 37488 | 10 | 1 |

## Findings

- Repeated preview GL `0x505`, EGL import `0x3003`, Vulkan device loss, slow
  presentation, and RAW ImageReader overflow. They establish resource/driver
  failures; they do not prove a single underlying vendor-driver cause.
- The last export shows a 16-frame SR selection at 19:45:49, progressively slow
  preview draws (850 ms at 19:46:08), then GL failure at 19:46:21.
- Preview starvation previously restarted Camera2 even while RAW buffers were
  arriving. Those restarts discard the ZSL ring and interrupt preview again.
- SR allocated seven full-resolution RGBA32F planes plus two R32F planes up
  front. Its two-frame queue actually submitted a third frame before waiting;
  a fence timeout was ignored. Deleting a texture handle did not retire queued
  GPU work or reclaim its backing storage immediately.
- CPU mosaic used eight workers; one logged eight-frame mosaic took 153,627 ms.
  Eight workers remain enabled, as requested. There is no permanent CPU cap or
  priority reduction in this change.
- Missing optional tiny RawNIND model messages occur in older builds; the
  existing independent Bayer initialization/fallback remains in place. Vendor
  metadata and SELinux diagnostics alone do not justify application changes.

## Changes

- Camera recovery requires missing RAW arrivals, rather than missing display
  frames alone. Renderer failure no longer restarts a healthy input stream.
- SR-only shader programs support invocation offsets. Compute is submitted in
  bounded rectangles, with smaller budgets for expensive alignment kernels.
  Each rectangle must complete successfully before the next is submitted;
  failed or timed-out fences abort instead of allowing an unbounded queue.
  A short yield lets the independent viewfinder context submit work. This
  trades some GPU merge throughput for responsiveness; it is not a guaranteed
  frame-rate reservation by the driver.
- Reference accumulators, fallback, and output textures are allocated when
  needed, deferring 649,209,600 bytes at 4080×3060 during moving-frame work.
  Nearest-support textures are freed before JPEG/readback consumption. All
  compute precision and per-pixel accumulation order remain unchanged.
- With KernelNet available, the unused analytic CPU guide is no longer built
  and uploaded for every GPU input frame.
- Mosaic caches the motion-disagreement decision per alignment tile, replacing
  a repeated nine-neighbour scan per output pixel with a lookup. It preserves
  the exact original gate, including edge clamping and non-finite flows.
- Corrected the existing device-test packed oracle to pass crop-relative CFA
  phase, as required by MergeFrame. Its old full-sensor phase caused odd-crop
  reference mismatches. The sea reference also omitted the existing hot-pixel
  detection/inpainting stage; its reference and alignment inputs now include it.

## Validation

- Latest unit suite: 743 tests, zero failures/errors, two existing skips.
- Debug app and instrumentation APK builds passed.
- All 20 sliced compute shaders passed glslangValidator.
- Connected Xiaomi 25080RABDG, live RAW VF plus eight synthetic 4080×3060
  packed SR frames: passed. Merge took 112,368 ms, peak tracked SR textures
  1,510,709,952 bytes, maximum displayed-frame age 868 ms, zero inactive
  preview samples, final rolling FPS 28.01. This demonstrates continuity in
  that run, not zero dropped frames or a guaranteed minimum FPS.
- GPU and mosaic correctness: all 24 checks passed across the full run and
  the three affected packed-reference reruns after fixing the CPU oracle.
  No tolerance was relaxed. The three reruns completed in 44.407 seconds.

The optional preview contention test uses an eight-frame synthetic 4080×3060
burst alongside the real camera viewfinder, without saving synthetic images.
It measures maximum age of the last displayed RAW frame and inactive samples.
Earlier attempts were invalidated by user interaction/backgrounding and a
package installation; neither is a valid uninterrupted-preview measurement.

## RAW histogram after capture

The Activity's two-second RAW freshness timeout substituted a YUV bitmap even
while RAW remained the selected source. HistogramView independently accepted
YUV after the same hold interval. Also, VF-only RAW arrivals could be released
without histogram sampling while a capture slot was reserved.

- HistogramView now enforces the selected source, rejecting delayed callbacks
  from the other source. Only an explicit source toggle clears the bins and
  changes source. RAW gaps retain RAW bins and expose a waiting status.
- Activity polling never reads the YUV TextureView while RAW is selected, and
  RAW callbacks recheck the selection on the UI thread before updating.
- RAW acquisition publishes throttled histogram samples before ZSL/capture/
  VF-only routing, using the same frame and no additional camera targets.
- A rejected RAW histogram stream reports RAW waiting, rather than claiming
  the histogram switched to YUV.
- Device regression covers a RAW gap exceeding the previous two-second hold,
  delayed YUV/RAW callbacks, and explicit source toggles. Passed on the phone
  (2.123 seconds). Final debug APK installed successfully.
