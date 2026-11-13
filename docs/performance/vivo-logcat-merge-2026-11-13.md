# vivo V2562 log review, 2026-11-13

Reviewed all four supplied files (340, 1,925, 1,519 and 5,752 lines). The
07:03:12 `(1)` export is a shorter snapshot of the same process/session as the
1,925-line export; its merges must not be counted twice. No fatal exception or
native fatal signal appears in these files.

Completed LK-R merges, grouped by process/session:

| Session | RAW size | Frames | Selection ms | Merge ms |
| --- | --- | --- | --- | --- |
| 07:03:12 | 4080×3064 | 16 | 72 | 23,547 |
| 07:03:12 | 4080×3064 | 16 | 51 | 61,127 |
| 07:03:12 | 8160×6128 | 24 | 85 | 112,087 |
| 07:03:12 | 8160×6144 | 24 | 67 | 148,723 |
| 08:46:34 | 8160×6128 | 24 | 230 | 142,928 |
| 08:46:34 | 8160×6128 | 24 | 210 | 800,879 |
| 08:46:34 | 8160×6128 | 24 | 215 | 297,158 |
| 08:46:34 | 4080×3064 | 16 | 146 | 38,221 |
| 08:46:34 | 4080×3072 | 16 | 150 | 23,490 |
| 08:46:34 | 4080×3072 | 16 | 77 | 43,513 |

The last session also starts a 24-frame merge at 09:42:32 with selection=214ms;
there is no completion before the export ends. Selection is not always below
0.1 seconds. JPEG encoding/saving is much shorter than the merge, but that
excludes the separate RGB development stage.

## Evidence and limits

* Every file has zero RawLensLkr lines. LkrMerge explicitly imported
  android.util.Log; native logs also bypass the in-process Log tee. Current
  LogcatFileWriter writes wrapper calls directly and treats logcat as an
  additional source. Framework/startup output therefore does not prove that
  native profiling will reach exported files later.
* The 61.1s vs 23.5s pair is a 2.60 ratio associated with backgrounding, not a
  controlled benchmark of vivo throttling. The 800.9s merge starts around
  08:50:10, spans repeated pauses/resumes and Activity destruction, and finishes
  at 09:03:30. The process remains alive and service diagnostics still report
  `services=jpeg`; it is stopped only after completion at 09:03:34. This rules
  out simply losing the processing service in that example. It does not
  identify CPU scheduling, GPU clocking, thermal limits or process suspension.
* Memory during that merge is substantial: around 2.8GB PSS and 2GB reported
  graphics memory at 08:50:34. Ring retention and concurrent preview/merge
  allocations contribute. Leak heuristics compare different workloads and
  report both increases and decreases, so they do not establish a leak.
* Repeated duplicate RAW stream configuration is visible on main and RawCamera
  threads. A second submission closes the first before autofocus setup. This
  explains the recoverable closed-session stack traces and redundant reader
  allocations, independently of the merge performance problem.
* Initial vendor JSON/DMA heap errors and image-tier fallback warnings are
  followed by successful RAW delivery; they are not evidence of a fatal merge
  failure.

## Changes and validation

Session creation is serialized on RawCamera and guarded throughout asynchronous
configuration. Kotlin LK-R diagnostics use the file tee under RawLensCamera.
JNI retains bounded, per-thread native messages (including per-shader GPU
profile, worker counts, setup, load, solve, wait and blend timings) and Kotlin
consumes them immediately after each merge, including failure returns.

A merge-scoped monitor records start/end and ten-second samples of process
importance, CPU time, worker priority/scheduling counters, cgroup, thermal and
power state, and elapsed versus awake time. This adds evidence without changing
image processing or assuming an OEM scheduling fix. The existing foreground
service already owns a six-hour partial wake lock; increasing it is not a
supported explanation or remedy for these shorter cases.

GitHub debug APK builds successfully, including native JNI. Relevant session,
logging and processing-service tests pass. An initial full unit suite failed in
ProgramAeLoopTest.startAppliesTheTargetAtOnce (outside the changed code).
No ADB device was connected, so on-device export and background-speed validation
remain outstanding. Changes are uncommitted; no push was performed.
