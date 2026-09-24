# RawLens performance and stability handoff

Date: 2026-09-24. Ready-to-paste task prompt: [CONTINUATION_PROMPT.md](../../CONTINUATION_PROMPT.md).
Technical audit and primary sources: [audit](../stability-performance-audit-2026-09-24.md).

## User intent and constraints

Original request: consult Android/Google/Linux/Vulkan/OpenGL ES sources and optimize
the app for stability, performance, and lower memory use without changing image
quality. The user then added overall efficiency and fast UI/UX, explicitly saying
not to stop or replace the original work. They repeatedly requested continuation,
approved device-test retries, and finally requested this written handoff.

Preserve image algorithms, shader arithmetic, precision, resolution, frame counts,
color processing, and encoder settings. Favor resource lifetime fixes, bounded
reuse, less allocation, and elimination of redundant work. Do not reduce quality
or silently disable functionality to improve benchmarks.

## Workspace state

- Repository: `/Users/monikamalinowska/AndroidStudioProjects/RawLens`.
- Shell: zsh on macOS; project uses Gradle/Kotlin/Java and C++ Android native code.
- Current tree had 303 status entries when this handoff was prepared, including
  extensive pre-existing edits and additions. Treat the current source as the
  working baseline. A HEAD diff is not a diff of this session alone.
- No `AGENTS.md` was found by the searches performed in this session. Check again
  if your environment supplies additional instructions.
- No commits, resets, cleans, or broad formatting operations were performed.
- No subagents were used. Session instructions prohibit unsolicited delegation.
- Last installed artifact: `app/build/outputs/apk/debug/app-debug.apk`, including
  the HDR worker fix and UV cache. APKs and build output are regenerable and may
  be replaced by subsequent work.

## Exact areas changed in this session

Paths below are relative to the repository root. Some files already contained
other contributors' changes; preserve those.

| File | This session's change |
| --- | --- |
| `app/src/main/java/com/matthew/rawlens/RawViewfinder.kt` | `Frame.pixels` now uses lazy direct-buffer allocation. `bindQuadAttribs` caches rotation/mirroring and reuses the coordinates buffer until they change. |
| `app/src/main/java/com/matthew/rawlens/RawSrMergeJob.kt` | `readRgbaFloat` and `readRcMeanSupport` reuse one direct scratch buffer per operation. `readMergedRgbStrip` calls `copyRgbaRowsToRgb`, using one heap row rather than one full heap band. |
| `app/src/main/cpp/vf_vulkan_vf.cpp` | `importInputBuffer` intersects AHB and VkBuffer memory-type masks after querying buffer requirements; rejects/cleans up when no compatible type exists. |
| `app/src/main/java/com/matthew/rawlens/MainActivity.kt` | Avoid identical exposure TextView updates; cache quick-tile drawable state; reuse histogram bitmap, recycle it on pause, and skip preview histogram readback while paused. |
| `app/src/main/java/com/matthew/rawlens/HistogramView.kt` | Reuse preview pixel array. Optional `recycleBitmap` argument defaults to true to preserve existing ownership behavior; activity passes false for its reusable bitmap. |
| `app/src/main/java/com/matthew/rawlens/HdrPools.kt` | Both parallel helpers use `awaitWorkers` to drain siblings after failure/interruption before returning shared storage to callers. Restores interrupt status after draining. |
| `app/src/test/java/com/matthew/rawlens/RgbaReadbackCopyTest.kt` | New raw-float-bit repack regression, including NaN payload, signed zero, infinities, subnormal values, offsets, and destination boundaries. |
| `app/src/test/java/com/matthew/rawlens/HdrPoolsTest.kt` | New concurrency regressions for failed siblings and interrupted waiters. |
| `app/src/androidTest/java/com/matthew/rawlens/RawSrMergeJobInstrumentedTest.kt` | Existing striped/full GPU readback and DNG parity fixture extended to 270 rows to cross the 256-row scratch boundary. |
| `app/src/androidTest/java/com/matthew/rawlens/HistogramSourceInstrumentedTest.kt` | Added reusable bitmap ownership and bin-refresh regression. |

The dual-RAW native importer includes `vf_vulkan_vf.cpp`, so its memory-type selection
also benefits from the importer fix. The output-buffer Vulkan test does not exercise
every live camera-input AHB type on every driver.

## Quantified savings and limits

- GPU-only preview avoids allocating 3 × 1080 × 1080 × 4 bytes = **13.35 MiB**
  of CPU fallback buffer capacity. First fallback use still allocates those buffers.
- At width 4080 and 256 rows, replacing the heap RGBA band with one row avoids
  **15.88 MiB** of heap scratch per strip call.
- Reusing the histogram bitmap/pixel array avoids approximately **40.5 KiB** of
  payload allocation per update at the same 96×54 sampling resolution.
- These are code-derived allocation calculations, not measured PSS or FPS gains.
- Strip RGB readback still allocates a direct buffer per strip; full-frame readback
  methods reuse their band scratch within a single operation.
- Histogram `TextureView.getBitmap` remains synchronous. Metadata publication was
  already throttled to eight updates/second before this work; do not rediscover
  that as a new optimization.
- Existing Vulkan import cache and GPU synchronization were retained. Blindly
  lowering cache capacity can cause repeated imports; removing waits/barriers can
  introduce corruption or use-after-release.

## Validation and saved evidence

Latest local build: `:app:testDebugUnitTest :app:assembleDebug` passed after HDR and
UV changes. Earlier full build also included `:app:assembleDebugAndroidTest` and
passed. Latest unit results: **749 total, 747 passed, 2 skipped, 0 errors/failures**.
`git diff --check` passed. Skipped test names are in
[`evidence/unit-test-summary.json`](evidence/unit-test-summary.json).

The final debug APK installed successfully. The existing matching test APK was
rerun against it: **7 tests passed, no skips, 5.184 seconds**:

- `VfVulkanInstrumentedTest`: malformed SPIR-V rejection; device recreation and
  output-buffer reimport.
- `RawSrMergeJobInstrumentedTest`: CPU mosaic/DNG integration; GPU merge/DNG
  integration; exact full/striped float readback and serialized DNG-byte parity.
- `HistogramSourceInstrumentedTest`: reusable preview bitmap and refreshed bins;
  source selection surviving capture gaps and rejecting stale updates.

Logs, the two new unit-test XML reports, and profiling snapshots have been copied
from temporary/build locations into [evidence/](evidence/) so the next session does
not depend on `/private/tmp` surviving. No universal image parity is claimed.
There is no dedicated device test of the new UV-cache lifecycle yet; existing
geometry tests pass, but rotation/mirroring and context recreation remain useful
live checks.

## Device and profiling findings

- ADB serial: `fe79feha9lmb6hhi`; `ro.product.model`: `25080RABDG`.
- ADB executable: `/Users/monikamalinowska/Library/Android/sdk/platform-tools/adb`.
- USB occasionally disconnected. Install attempts sometimes returned
  `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. The user approved
  retries; explicit `--no-streaming -r -t` for the test APK eventually succeeded.
- A prior install command reported success but instrumentation was not registered.
  Verify registration/results rather than trusting an install line alone.
- Camera permission was granted to the debug package for profiling. Launches
  sometimes opened the permission controller, so check actual foreground state.
- The last action on-device was the successful instrumentation suite. Do not
  assume the camera activity remains visible afterward.

Clean observation: 20.27 seconds, same process and one Activity, `visibility=0`
at both endpoints; debug build with HDR fix, **before** the last UV-cache change.

| Metric | Result |
| --- | --- |
| HWUI frames | 152 |
| Janky frames | 48 / 31.58% |
| UI median / p90 / p95 / p99 | 17 / 21 / 23 / 25 ms |
| GPU p95 | 11 ms |
| PSS start → end | 220,378 → 227,871 KiB |
| RSS start → end | 330,424 → 344,644 KiB |
| Swap PSS start → end | 39,092 → 38,728 KiB |
| Graphics accounting start → end | 111,380 → 111,352 KiB |

This measures Android HWUI, **not RAW SurfaceView FPS**. There is no controlled
before/after benchmark. One short memory rise does not establish a leak. A prior
longer observation ended with `visibility=8`; its memory decrease was excluded
from steady-preview claims. Tool/approval delays stretched that observation,
so use one automated short window rather than widely separated interactive calls.
Capture the current engine/settings and refresh/thermal state next time; these
were not fully recorded, which limits comparison.

## Next work, in priority order

1. Establish a repeatable release/non-debuggable profiling workload, preserving
   the current workspace and settings. Collect a Perfetto/system trace covering
   main thread, RenderThread, RAW GL worker, GPU scheduling, camera callbacks,
   and memory/GC. Investigate the measured UI jank rather than assuming shader
   quality reductions are necessary.
2. Record camera/backend, mode, resolution, frame rate, refresh rate, visibility,
   thermal state and workload with every sample. Compare the same workload before
   and after each fix; do not treat UI update count as camera FPS.
3. Repeat identical preview/capture/background/resume cycles to assess memory
   growth and resource lifetime. Capture total PSS, RSS, swap, native/Java heap,
   graphics accounting, and live image/GL ownership where available.
4. Verify UV reuse across rotation, mirror changes, GPU/CPU fallback and context
   recreation; exercise live camera-input Vulkan imports and device-loss recovery.
5. Potential areas to investigate, not established causes: synchronous histogram
   readback; lifecycle `MemoryLeakDiagnostics.sample` calls doing PSS/proc reads on
   the main thread; repeated frame visibility posts; repeated UI/layout work;
   large per-strip direct readback allocations. Measure before redesigning.
6. Run relevant regressions and preserve exact output parity for every quality-
   sensitive path touched. Update the audit with measured outcomes and limits.

The broad optimization objective is not finished: **UI jank remains unresolved**,
cross-driver live-input coverage is incomplete, and no measured FPS improvement
or controlled before/after memory reduction has been established.

## Reproduction commands

Run from the repository root. Follow the current environment's approval rules;
Gradle needed access to the user's cache and ADB needed unsandboxed device access.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
git diff --check

ADB=/Users/monikamalinowska/Library/Android/sdk/platform-tools/adb
DEVICE=fe79feha9lmb6hhi
"$ADB" devices
"$ADB" -s "$DEVICE" install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$DEVICE" install --no-streaming -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ADB" -s "$DEVICE" shell pm list instrumentation
"$ADB" -s "$DEVICE" shell am instrument -w -r \
  -e class com.matthew.rawlens.VfVulkanInstrumentedTest,com.matthew.rawlens.RawSrMergeJobInstrumentedTest,com.matthew.rawlens.HistogramSourceInstrumentedTest \
  com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
```

For a short profiling window, launch preview, allow startup to settle, reset
`dumpsys gfxinfo`, save `dumpsys meminfo`, wait 20 seconds, then save both again.
Check `visibility=0` and process identity at both endpoints. Keep delays below
60 seconds per tool call and continue giving progress updates. The evidence
filenames show exactly which snapshots produced the reported observation.
