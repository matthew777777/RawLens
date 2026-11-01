# Xiaomi memory-warning investigation — 2026-11-01

## Finding

The six supplied exports do not establish a persistent memory leak. The largest
observed growth is graphics memory while the camera is active. It plateaus and
falls back to zero graphics allocation after camera shutdown/backgrounding.
The warnings are worth retaining as growth signals, but are not leak verdicts.
No runtime memory-management change is justified by this evidence alone.

## Inputs

All six exports identify Xiaomi 24030PN60G / Android SDK 34. Both
`logcat-20261101_101803_951` files are byte-identical. The `104004_513 (1)` file
adds post-crash teardown samples; `104144_806` extends beyond its `(1)` copy.
All variants were checked, including those extra samples. Duplicate mirror
entries were not counted as independent observations.

## Observed cleanup (MB as printed by the existing logger)

| Process | Before cleanup | After cleanup | Interpretation |
| --- | --- | --- | --- |
| 26265 | 10:37:51.928: PSS 300, graphics 214 | 10:37:56.531: PSS 83, graphics 0 | Graphics allocations released after backgrounding |
| 26265 | 10:38:26.559: PSS 360, graphics 294 | 10:39:26.630: PSS 391, graphics 299 | Active-camera graphics allocation reaches a plateau |
| 26265 | 10:39:26.630: PSS 391, graphics 299 | 10:39:56.698: PSS 85, graphics 0; 10:39:58.594: PSS 73, graphics 0 | Warning-associated growth does not persist across camera shutdown |
| 29838 | 10:40:35.214: PSS 415, graphics 281 | 10:41:14.702: PSS 72, graphics 0 | Similar cleanup after RAW-video use |
| 29838 | 10:41:42.260: PSS 188, graphics 86 | 10:41:43.050: PSS 98, graphics 0 | Extra `(1)` log confirms activity teardown cleanup |
| 11185 | 10:42:45.533: Java used 161, native allocated 84, graphics 215 | 10:43:14.782: Java used 13, native allocated 57 | Post-recording allocation spike is reclaimed in the same process |

The last process still reports graphics 152 MB at the final sample. There is
no later settled sample to establish cleanup for that final pause. This is an
explicit evidence gap, not proof that the graphics allocation leaked.

## Why the warnings fire

`MemoryLeakDiagnostics.trendWarning` compares the last six samples for a label.
It warns on >=32 MB PSS growth with at least four non-decreasing transitions,
or corresponding native/resource growth. Equal consecutive values count as
non-decreasing. It does not require all samples to describe the same camera
route, stream dimensions, foreground state, or warmed pipeline.

The periodic windows here mix low-memory background samples with active camera
samples. Graphics moves from 0 to roughly 299 MB. The same warning reports zero
increase in tracked textures, native buffers, and live Images. The native-heap
changes in the periodic warnings are -3, +7, and -14 MB; they do not explain the
roughly 300 MB PSS growth. The preview warning's native increase (+46 MB) spans
multiple lifecycle/stream changes and does not remain after shutdown.

## Current-source lifetime audit

- `RawViewfinder.invalidateSession` releases pending frame leases and schedules
  native import reset. `releaseGl` resets native imports, destroys the cached
  EGL image, closes the export buffer, and tears down EGL state.
- `VfVulkan.resetNative` releases imported Vulkan resources and retained
  AHardwareBuffer references for input and output caches. The current input
  cache has a 24-entry cap; the output cache has a 6-entry cap. These imports
  map existing camera pool buffers; the cap is not evidence of 24 fresh copies.
- `RawVideoRecorder` allocates a bounded workers+2 direct-buffer payload pool.
  Stop drains workers/committer and closes session, device, and reader.
  `MainActivity.finishVideoStop` drops its recorder reference. The logs show
  the corresponding high Java/native allocation later decreases.
- Lifetime counters remain bounded in the supplied samples. In particular,
  live Images return to zero. However, GL/Vulkan counters show zero even while
  the Vulkan image preview is active, so those counters alone cannot prove
  absence of native/driver resource retention.
- No OutOfMemoryError, negative-resource accounting warning, explicit retained
  GL/native-buffer warning, or skipped/deferred native-reset warning appears
  in these exports. Absence of those messages is supporting evidence only.

## Limits and next useful device check

This is log correlation plus a current-source audit, not a heap/native allocation
profile of the affected device. The installed obfuscated build need not match
all current implementation details. A small leak could coexist with the large,
reversible graphics allocations.

For stronger evidence, repeat at least six identical cycles on one route and
RAW size: warm preview, take the same photo or short recording, background,
then collect a settled sample after 10 and 30 seconds. Compare the settled
background baselines across cycles, not active preview against idle. If those
baselines keep rising, capture a heap/native allocation profile and graphics
buffer/import counts on that Xiaomi before changing buffer ownership or cache
limits. Keep current warning thresholds; do not hide the signals merely because
this observed growth was reversible.
