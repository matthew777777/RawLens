# Default preview efficiency audit — 2026-10-30

The initial changes remove repeated work without changing capture cadence,
preview resolution, color math, or image ownership and GPU fence rules. The
later rendering change below intentionally reduces preview presentation
resolution, with the user's authorization to trade preview quality for cost.
They do not establish a battery-current improvement. Initial validation was on
the host; the follow-up below also uses the connected MT6878 phone.

## Implemented

- `VfLevels`: both legacy and BGU preview previously regenerated four
  normalization rows every rendered frame. A synchronized four-entry cache now
  reuses tables for identical Q6 black/white levels. Keys are copied; table bytes
  are read-only to consumers. Every changed key still produces the exact table.
  Maximum cached table payload is 1 MiB. Normal 10/12/14-bit tables are smaller.
- Native viewfinder LUT upload: reuse CPU scratch vectors, compare against the
  last successfully uploaded table in cached CPU memory, and skip mapped GPU
  writes and noncoherent flushes for identical data. Never compare by Java array
  identity or read mapped GPU memory for the comparison. Flush failures invalidate
  the fast path; retries, shrink tails, growth and recreated allocations remain
  correct. JNI still transfers the input bytes for a content comparison.
- BGU low sampler: hoist phase tables out of the row loop. Phase-array allocation
  is now constant per frame instead of scaling with image height. Odd/even step,
  crop parity and all CFA permutations retain the same normalization operations.
- BGU grid upload: pack directly from the planar coefficients into one reusable
  direct buffer, using flat indices. Remove three short arrays and three direct
  allocations per uploaded grid, plus repeated coordinate division and checked
  `grid.at` lookups. Each GL upload copies its client data before returning, so
  the same client buffer can hold the next matrix row. GPU texture formats and
  coefficients remain identical.
- Legacy Vulkan validation: when the input signal already guarantees an
  inconclusive dark-scene verdict, skip `glFinish` and CPU output sampling. Keep
  input probation active so validation resumes when light returns. Both sampled
  image and buffer/bridge/upload tiers use this early exit.

## Validation

- 265 tests in 34 preview-related suites passed: `Vf*Test`, `Bgu*Test`, and
  `RawPreview*Test` (GithubDebug).
- Added independent scalar comparisons for sampling at odd/even strides and crop
  origins, and multidimensional grid packing. Added LUT key-ownership/change tests.
- `python3 tools/preview-efficiency/test_native_lut.py`: passed. Compiles the
  production JNI upload function against fake JNI/Vulkan, checking identical
  contents, changed contents in the same input array, growth, shrink tails,
  coherent/noncoherent memory, failed-flush retries and context reset. It does not
  exercise a GPU driver.
- Android NDK arm64 syntax checks passed for `vf_vulkan_vf.cpp` and
  `dual_raw_vulkan.cpp`, both of which consume the changed native code.
- `git diff --check`: passed.

Host benchmark versus `cb1f496b41cca3cf1ecb3474a9884510dbab1911`:

| CPU operation | Before, µs | After, µs | Ratio |
|---|---:|---:|---:|
| Stable 10-bit normalization table | 3.166 | 0.112 | 28.25× |
| Stable 12-bit normalization table | 12.408 | 0.215 | 57.80× |
| Stable 14-bit normalization table | 46.589 | 0.193 | 241.39× |
| 128×96 low sampler | 146.374 | 140.636 | 1.04× |
| 17×13×9 grid packing/upload preparation | 109.969 | 59.476 | 1.85× |

These are median warmed same-VM timings on an aarch64 OpenJDK host. The harness
compiles the actual baseline Kotlin sources and compares sampler and packed-grid
outputs before timing. It includes allocation in the old upload preparation but
no GL driver calls. They are microbenchmarks, not Android ART results, frame-time
improvements, or watts saved. In particular, LUT caching alone does not explain
or resolve a reported 1100 mA drain.

Reproduce after compiling GithubDebug:

```sh
python3 tools/preview-efficiency/benchmark.py
python3 tools/preview-efficiency/test_native_lut.py
./gradlew :app:testGithubDebugUnitTest \
  --tests 'com.matthew.rawlens.Bgu*Test' \
  --tests 'com.matthew.rawlens.RawPreview*Test' \
  --tests 'com.matthew.rawlens.Vf*Test'
```

## Research and remaining measurement

Arm's [GPU best-practices guide](https://documentation-service.arm.com/static/67a62b17091bfc3e0a947695)
identifies excess memory bandwidth as a power cost and warns against overly broad
Vulkan dependencies. Qualcomm's [Adreno memory guidance](https://www.qualcomm.com/developer/blog/2026/05/high-performance-memory-extension-optimize-memory)
similarly emphasizes avoiding external-memory traffic. Its tile-memory extension
requires runtime support and is not a portable fix for these external buffers.
Khronos's [synchronization guide](https://docs.vulkan.org/guide/latest/synchronization.html)
explains the cost of unnecessary serialization. These support reducing data
movement and redundant synchronization; they do not prove which subsystem is
responsible for RawLens's reported power draw.

The default AUTO route prefers legacy GPU preview. BGU improvements benefit its
fallback and forced routes; the normalization cache/upload and dark-scene probe
changes affect legacy preview directly.

The larger unresolved candidates require Adreno and Mali traces:

1. Which import tier actually runs? Plane/copy fallback can copy a full RAW16
   allocation every rendered frame. At 4080×3060×30 fps its payload is ~749 MB/s;
   a full memcpy adds ~1.5 GB/s of logical read/write traffic before GPU reads.
   Hardware caches determine how much reaches external DRAM.
2. How much time/energy belongs to continuous camera RAW production versus GPU
   conversion, final tonemapping, CPU metering/scopes and composition? Keep the
   same camera session while isolating each consumer to distinguish these costs.
3. Does the two-stage Vulkan-to-GL path dominate on either driver? A fused direct
   RAW-rendering path needs image-import capability checks and GPU parity tests.
   Removing ownership barriers or waits without those checks risks corruption.
4. Compare sustained default preview against the standard camera on the same
   device, scene, brightness, refresh rate, battery voltage and thermal state,
   unplugged and settled. Record camera/output fps with average current and
   CPU/GPU/memory activity. The QCM6490 anecdote is not a cross-device power target.

[Android power profiling](https://developer.android.com/studio/profile/power-profiler)
can separate supported device rails; otherwise battery gauge current is a
whole-device measurement. No mA reduction is claimed until that A/B exists.

## Follow-up: deeper audit and wrap-up

Further implemented changes:

- Plane and locked-AHB upload fallbacks now copy only the exact four RAW16
  samples consumed by each preview texel. The shader keeps its normalization,
  channel order, and output size; only staging geometry changes to tightly packed
  quads. Source bounds are validated before copying. The unchanged direct-import
  tiers do not use this optimization.
- All three viewfinder dispatch variants batch input/output/shading descriptor
  writes into one driver call instead of two.
- LUT upload checks the previous compute fence before changing mapped memory or
  descriptors. BUSY remains backpressure through the legacy Kotlin caller instead
  of becoming a submit failure. Native tests cover busy, lost-device, and retry
  behavior as well as contents/tails/flush failures.
- Histogram luminance accumulates three scalars instead of allocating two arrays
  for every sampled block. Channel and luminance bins remain identical, including
  partial quads. At 8,000 sampled blocks this removes approximately 16,000 array
  allocations per histogram update.
- BGU uses the existing native bounded fence wait instead of 1 ms sleep polling.
  Unsignaled fences retain their ownership and retry behavior.
- RGBA preview exports no longer request `GPU_DATA_BUFFER`, which the
  [NDK contract](https://developer.android.com/ndk/reference/group/a-hardware-buffer)
  reserves for BLOB buffers. Sampled-image and color-output usage remain.

Confirmed checks before the final GPU test run:

- 328 tests in 45 preview/scope suites passed, with zero failures or errors.
- Compact RAW host test passed 2,000 randomized layouts under address/undefined
  behavior sanitizers, including unaligned addresses, odd/even steps and crops,
  padded rows, exact buffer ends, all four sites and rejected malformed geometry.
- The same layout checks passed in an arm64 executable on the MT6878 phone.
- Both native consumers passed Android NDK arm64 syntax checks. The isolated probe
  additionally builds `rawLensVfEgl` for arm64 and ARMv7.

Native CPU heap benchmark on the connected MT6878 phone, 4080×3060 input with
4096-pixel padded row pitch:

| Output / sample step | Staging bytes before → after | Upload CPU time before → after |
|---|---:|---:|
| 2040×1530 / 2 | 25,067,520 → 24,969,600 | 5.4092 → 4.7462 ms |
| 1020×765 / 4 | 25,067,520 → 6,242,400 | 5.2713 → 2.1468 ms |
| 510×382 / 8 | 25,067,520 → 1,558,560 | 4.3682 → 0.7298 ms |
| 255×191 / 16 | 25,067,520 → 389,640 | 5.3153 → 0.3111 ms |

These timings exclude AHB lock, mapped Vulkan memory, flush, GPU, camera and
display costs. The data reduction is exact; timings are illustrative medians on
this phone, not a power measurement or a cross-device speed guarantee.

The standard test build encountered an internal Kotlin FIR lint crash while
analyzing `RawCameraController.kt`, followed by a shared-output D8 cache collision.
The isolated probe uses a separate package, build directory and native staging
directory, with build caching and the crashed lint-vital task disabled. It does
not replace the daily installed app. Lint is not reported as passed.

Further primary references used in this follow-up:

- [Arm shader branching guidance](https://documentation-service.arm.com/static/67a62b17091bfc3e0a947695):
  check branching against computation, and avoid evaluating expensive paths only
  to discard them with a mix; validate rather than assuming branches win.
- [Qualcomm memory-access guidance](https://docs.qualcomm.com/bundle/publicresource/80-NB295-11_REV_C_Qualcomm_Snapdragon_Mobile_Platform_Opencl_General_Programming_and_Optimization.pdf):
  vectorized/coalesced access and data movement matter. Its OpenCL-specific details
  are not treated as Vulkan guarantees.
- [Vulkan host-memory synchronization](https://docs.vulkan.org/spec/latest/chapters/memory.html):
  previous device reads must finish before host writes to the same mapped range.
- [Android explicit fences](https://source.android.com/docs/core/graphics/sync):
  buffer reuse follows completion fences; removing waits without ownership
  synchronization is not a valid power optimization.

Deferred: full default-preview energy A/B, Adreno device measurements, camera/DRAM
rail attribution, and additional shader specialization/fusion. No battery-current
improvement or comprehensive optimization of every application line is claimed.

The proposed uniform-branch shortcut for disabled hue/gamut controls was rejected:
the real Mali-G615 MC2 comparison found a maximum eight-code difference across
60 color-control/exposure/display/look combinations. Its runtime changes were
removed. The pre-change fragment is retained only in Android-test assets as a
parity reference; it is not included in the production APK.

The first isolated device run passed compact plane/locked-AHB upload parity at
every output byte (128 combinations of sample step, crop parity, CFA mapping and
input path), the lens precision check, and the BGU grid-rendering check. Final
verification after rollback passed all four device tests. The tonemap matched the
pinned reference exactly (maximum code error zero across all 60 combinations) on
Mali-G615 MC2. The final isolated test build also completed successfully. Reports
are archived under `docs/refactor/results/preview-efficiency-2026-10-30/`.

Wrap-up scope: the implemented upload, allocation, descriptor, synchronization,
and buffer-usage changes are retained. The failed shader shortcut is absent from
runtime code. The daily installed app was not replaced by the isolated probes.

## Accepted preview-quality tradeoff: bound the present surface

After authorization to reduce preview quality, the production legacy viewfinder
now bounds its `SurfaceView` buffer by the selected preview long edge. Previously
RAW was sampled at preview resolution, then the full tonemap was evaluated again
for every physical display pixel. The buffer now keeps the view's aspect ratio
at a smaller size; Android scales it to the same layout rectangle. This applies
by default and does not require battery-saver mode.

For a 1080×1613 view at the existing 1080 preview setting, presentation becomes
723×1080: 780,840 shaded pixels instead of 1,742,040, a 55.2% reduction. RAW sample
geometry, camera request cadence and the saved-file pipeline remain unchanged.
UI controls/overlays retain their layout resolution. The preview can look softer
when enlarged and its dither pattern scales with the buffer; those are the
accepted spatial-quality tradeoffs. Smaller views are never upscaled internally.

Implementation is in `VfPresentSize.kt` and `RawViewfinder.kt`. Surface sizing is
coalesced onto the UI thread, follows layout and resolution changes, and uses the
existing GL recreation path when the surface changes. MainActivity's overlay
mapping already refits from layout dimensions and frame geometry. See Android's
[SurfaceHolder contract](https://developer.android.com/reference/android/view/SurfaceHolder#setFixedSize(int,%20int))
for fixed-size surfaces and the required window-thread affinity.

The hue/gamut branch experiment was reconsidered with the relaxed quality
criterion. Its measured maximum error was 8/255, mean absolute RGB error 0.00565
codes and p99 zero across the test cases. It showed no speed benefit in the
single-frame comparison (5.52 ms reference versus 5.87 ms candidate), so its
runtime changes were again removed on performance grounds. Quality tolerance is
not the reason to exclude it from this final implementation.

Single-frame wall timings were noisy (CPU wakeups/clock scaling), so the final
throughput comparison keeps eight frames queued, places a fence and flush after
each frame, waits once per batch, alternates case order, warms up and reports
medians. These fences separate the frame work on tiled drivers. The phone did
not expose GL disjoint timer queries, so results include CPU/driver submission,
clear, GPU rendering and final completion wait; they are not pure GPU timestamps.

Final Mali-G615 MC2 / MT6878 AgX preview result:

| Test case | Render size | Median per frame |
|---|---:|---:|
| Pinned pre-change shader, full surface | 1080×1613 | 2.2899 ms |
| Current shader, full surface control | 1080×1613 | 2.2753 ms |
| Current shader, capped present surface | 723×1080 | 1.0999 ms |

The full-size controls are within 0.7%; capped presentation uses about 52% less
time in this synthetic saturated render-throughput test. This does not establish
the same improvement in whole-app frame time, battery current or energy use;
camera, UI composition and display costs are outside the offscreen benchmark.

Validation of the retained implementation:

- 186 tests in 19 selected preview suites passed, including 10,000 randomized
  layout sizes for presentation bounds/aspect and the existing preview policies.
- All five isolated device checks passed. The production SurfaceView was created,
  resized between preview resolutions, resized by layout, switched into/out of
  record mode, and presented through recreated production EGL/GL state.
- Compact-upload, lens-precision and BGU-grid checks remain passing.
- Current color math matched the pinned reference at identical raster size:
  maximum/mean/p99 RGB code errors all zero across 60 test cases.
- Build succeeded with the previously documented lint-vital tooling exclusion;
  `git diff --check` passed. The isolated test activity is in `src/deviceTest` and
  is excluded from release builds.

Reproduce with `tools/preview-efficiency/isolated.init.gradle` and
`run_device_checks.py`; this runner now executes five checks. Final reports and
the rejected candidate's measurements are archived in
`docs/refactor/results/preview-quality-tradeoff-2026-10-30/`. The daily installed
app was not replaced. Adreno hardware and whole-device power A/B remain unmeasured.
