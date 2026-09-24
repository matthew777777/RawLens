# Stability, memory, and UI efficiency review — 2026-09-24

Continuation entry points: [main prompt](../CONTINUATION_PROMPT.md),
[full handoff](performance-handoff-2026-09-24/HANDOFF.md), and
[saved validation/profiling evidence](performance-handoff-2026-09-24/evidence/).

This pass reviewed the current working tree, which already contained extensive changes.
The changes below preserve processing algorithms, shader arithmetic, precision,
resolution, capture counts, and encoder settings. This is a targeted audit, not an
exhaustive certification of Android/Linux graphics behavior on every driver.

## Changes

| Area | Change | Expected effect |
| --- | --- | --- |
| RAW preview | Lazily allocate the three CPU fallback pixel buffers | GPU-only sessions avoid 13,996,800 bytes (13.35 MiB) of direct-buffer capacity. CPU fallback retains its original resolution and capacity. |
| Full RGBA and robustness readback | Allocate one direct scratch buffer per readback operation and reuse it for every band | Removes repeated band allocations and dependence on GC reclaiming earlier direct buffers. Row order and reduction order are unchanged. |
| RGB strip readback | Repack through one RGBA row instead of a whole RGBA band | At width 4080 and 256 rows, removes 16,646,400 bytes (15.88 MiB) of heap scratch per call. Bulk row copies preserve the original float bits. |
| Vulkan AHB imports | Choose a memory type from the intersection of AHB and VkBuffer requirements; cleanly reject an empty intersection | Avoids an invalid memory binding on drivers whose allowed type masks differ. Applies to both preview and the dual-RAW importer that includes this implementation. |
| Exposure UI | Set ISO, shutter, and WB text only when the displayed string changes | Avoids redundant TextView layout/invalidation work while retaining the existing publication rate. |
| Quick controls | Reuse the current drawable/color state until a tile changes state | Avoids repeated drawable creation and background replacement. |
| Preview histogram | Reuse its 96×54 bitmap and pixel array; release the bitmap on pause; skip readback while paused | Removes about 40.5 KiB of bitmap/pixel payload allocation per update. Histogram sampling and arithmetic remain unchanged. Existing callers can still transfer bitmap ownership for recycling. |
| HDR worker failures | Wait for every submitted strip before propagating a worker failure or interruption | Prevents callers releasing/reusing image storage while sibling workers still write into it; restores the caller's interrupt flag after draining. Successful strip boundaries and arithmetic remain unchanged. |
| Preview quad coordinates | Reuse the UV buffer until rotation or mirroring changes | Removes the per-frame list and Pair allocations; uses the same coordinate calculations when orientation changes. |

These are allocation calculations, not measured process PSS reductions or FPS gains.
The CPU fallback allocates its pixels on first use, so the first fallback frame still
pays allocation cost. Preview histogram getBitmap remains a synchronous readback.
RGB strip readback still allocates a direct buffer per strip; this pass removes its
large heap duplicate rather than introducing a long-lived full-size cache.

## Primary sources checked

- [Google/Android memory guidance](https://developer.android.com/topic/performance/memory): resource lifetimes, allocation analysis, and memory profiling.
- [Android slow rendering guidance](https://developer.android.com/topic/performance/issues/render): main-thread work, layout cost, and measuring jank on representative builds.
- [Khronos Vulkan memory allocation guide](https://docs.vulkan.org/guide/latest/memory_allocation.html): allocation management and reuse.
- [Vulkan vkBindBufferMemory requirements](https://docs.vulkan.org/refpages/latest/refpages/source/vkBindBufferMemory.html): the chosen allocation type must be allowed by the buffer's memoryTypeBits.
- [Khronos OpenGL ES glReadPixels reference source](https://github.com/KhronosGroup/OpenGL-Refpages/blob/main/es3/glReadPixels.xml): row ordering and float readback semantics.
- [Linux DMA-BUF synchronization documentation](https://www.kernel.org/doc/html/latest/driver-api/dma-buf.html): buffer sharing and synchronization responsibilities. Existing GPU completion and ownership barriers were retained; removing them would need separate cross-driver evidence.

## Validation

- `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest` passed.
- Latest unit XML results after the HDR worker fix: 749 tests, 0 failures,
  0 errors, 2 skipped (747 passed). Debug APK build also passed.
- Added concurrency regressions for a failed strip with a still-running sibling
  and an interrupted waiter that must drain its worker and restore interruption.
- Added a raw-bit parity test for the row repack, including signed zero, NaN payload,
  infinity, subnormal values, and untouched destination boundaries.
- Extended the existing device GPU/DNG parity test to 270 rows, exercising both
  scratch reuse across the 256-row boundary and a short final band.
- Added a device test for reusable histogram bitmap ownership and refreshed bins.
- `git diff --check` passed.
- Device model reported `25080RABDG`. After USB installation was approved, both
  the matching debug app and test APK installed successfully.
- Before the subsequent HDR worker fix, all 7 targeted device tests passed,
  with no skips, in 6.157 seconds:
  Vulkan malformed-SPIR-V rejection and device recreation/output-buffer reimport;
  CPU mosaic-to-DNG and GPU merge-to-DNG integration; exact striped/full GPU
  readback and serialized DNG-byte parity across the 256-row boundary; reusable
  histogram bitmap ownership and bin refresh; RAW/YUV source switching during
  capture gaps. Log: `/private/tmp/rawlens-stability-device-tests.log`.
- After the HDR fix and preview UV cache, the final debug build was installed
  and all 7 device regressions passed again, without skips, in 5.184 seconds.
  Log: `/private/tmp/rawlens-stability-followup-device-tests.log`.

Pending device checks: live camera-input AHB import across different drivers,
camera fallback behavior, and before/after memory and UI frame-time profiling.
No measured on-device speedup or universal image-quality parity is claimed;
the passing parity tests cover their synthetic fixtures on this device.

## Follow-up device observation

The HDR fix was installed. A 20.27-second automated observation before the UV-cache
change confirmed `visibility=0` at both ends (same process, one Activity).
Android HWUI reported 152 rendered UI frames, 48 janky (31.58%), median 17 ms,
p90 21 ms, p95 23 ms, p99 25 ms; GPU p95 was 11 ms. These counters describe
the Android UI, not the separate RAW SurfaceView frame rate. This is a debug
build observation, not a controlled before/after comparison or release benchmark.

PSS moved from 220,378 to 227,871 KiB; RSS from 330,424 to 344,644 KiB;
swap PSS from 39,092 to 38,728 KiB. Graphics accounting was approximately
constant (111,380 to 111,352 KiB). A single short interval cannot establish a
leak. An earlier longer sample ended with `visibility=8` and was excluded from
steady-preview comparisons because the app had become hidden.

Raw observation files: `/private/tmp/rawlens-profile-window-{start,end}-{gfx,mem}.txt`.
Further work needs a controlled release-build trace of main/render-thread scheduling
and repeated identical preview/capture cycles. The observed jank remains unresolved;
no FPS improvement is inferred from the allocation changes.
