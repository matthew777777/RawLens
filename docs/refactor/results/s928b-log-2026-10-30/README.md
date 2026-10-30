# Samsung SM-S928B log review

Source: `logcat-20261030_011334_545.txt`, 13,108 lines, Android 17 / SDK 37.
SHA-256: `cd9c9b9842d17d6b55c2bf6cc8dd006b73e73b5318c44747cdf61d8121fa3dc2`.
`line-coverage.txt` assigns every source line to one category, including repeated
messages and ordinary lifecycle events. No crash, fatal exception or ANR appears.

## Findings and changes

| Source lines | Finding | Resolution |
| --- | --- | --- |
| 853–886, 10059–10073, 10162–10168, 12017–12018, 12207–12208 | IDs 6/7 fail synchronously with CAMERA_DISCONNECTED, followed by the asynchronous disconnect callback. No successful open or RAW frame from these IDs is recorded. | Added camera-thread, generation-guarded device recovery shared by both failure paths. Teardown invalidates duplicate callbacks. Retry once after a two-second cooldown; after repeated failure, fall back only to an enabled, verified RAW route. Existing direct-physical fallback remains available. No permanent DEAD verdict is recorded for transient disconnects. |
| 897–904, 10084–10135, 12030–12436 | Renderer fallback and RAW request recovery run after the camera has disappeared, using stream counters from the previous session. Request retries cannot reopen a device. | Both starvation handlers and RAW preview recovery require a live device and session. Device recovery tears down the old session/watchdogs and invalidates queued work. Exhausted recovery leaves a selectable CAMERA UNAVAILABLE state; explicit lens selection can retry. Budgets reset on delivered frames or explicit selection, not merely onOpened. |
| 88–93, 174–196, 223–227 | Layout listeners change view/overlay parameters during layout; initial surface geometry changes repeatedly. | Defer viewport/scope placement and viewfinder overlay synchronization until after layout. Avoid assigning equal viewport/overlay parameters, and skip unchanged aspect ratios. Overlay synchronization also follows position-only changes. |
| 50, 331 and repeated throughout (11,029 lines) | Surface alpha setters run every displayed frame and every starvation tick, even with identical values. | Guard reveal/hide assignments against the current alpha, preserving epoch/freshness checks. This removes redundant setter work and vendor log spam; it does not claim to eliminate every main-thread frame callback. |
| 323–326, 477–479 and later lens switches | Vulkan image import rejects unsupported alignment, then buffer import succeeds and zero-copy is verified. | Working capability fallback. Preserve the buffer tier rather than bypassing the alignment safety check. |
| 449, 685, 852, 8540, 8771, 9873, 10057, 10441, 11397, 12015 | ConsumerBase reports abandoned ImageReaders during lens teardown. | No failed capture or use-after-close exception follows these messages. Existing ownership-aware reader retirement is retained. Samsung validation is needed to establish whether any producer/reader ordering change is necessary. |
| 261, 465, 701, 854, 8787, 9889, 10208, 10457 | Framework monitor contention during close/configure, 176–326 ms. | Camera configuration/teardown is blocking inside Camera2. The log alone does not establish a safe app-side fix. Avoid speculative replacement of session lifecycle handling. |
| 1373 onward; scope samples up to 16 ms, preview stats roughly 25–30 fps | Preview CPU/GPU timing spikes; occasional long offer/draw. | Redundant alpha/layout work reduced. Existing histogram bulk-row sampling and preview optimizations remain intact; histogram parity/throttle and geometry tests are included in validation. Remaining timing requires measurement on the Samsung; no performance guarantee is inferred from host tests. |
| 25, 73–81, 136–138, 260, 268, 3608–3611, 5544, 11980, 12485 and repetitions | Missing QSPM/vendor libraries, denied vendor properties, gralloc metadata defaults, HWUI shader/pipeline diagnostic, ART inline-cache/monitor messages, Samsung Settings warning. | These originate in platform/driver/framework code. The app's Vulkan pipeline subsequently initializes and verifies output. Do not bundle private vendor libraries, request privileged permissions, or alter app shaders based solely on these messages. |
| 12761–13108 | Background teardown and memory reports. | Camera is closed; background CPU is 0.2%; graphics falls to 10 MB. The cumulative `vf=10355` frame counter is not a live resource count. No unbounded memory leak is demonstrated. |

## Validation

Recovery unit tests cover retry, verified fallback, exhaustion without cycling,
absence of verified alternatives, and resetting the budget after recovery.
Existing open-rejection and session-ladder tests protect the adjacent fallback logic.
Layout geometry and histogram tests cover the unchanged framing/sampling contracts.
Final validation: `:app:testGithubDebugUnitTest` passed all 36 selected tests
(0 failures/errors/skips), including compilation of the modified Kotlin sources.
`git diff --check` passed. Build log: `/tmp/rawlens-s928-log-final-tests.log`.

ADB found only Xiaomi model `25080RABDG`, not the source Samsung. The Samsung's
aux-camera availability, HAL restart duration, teardown diagnostics, actual layout
warnings and preview timing remain device-validation items. This review fixes the
identified application recovery/layout/alpha defects; it cannot repair vendor camera
availability or establish device performance from an old log.
