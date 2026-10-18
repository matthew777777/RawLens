# hlmeasure: on-device measurement for the hlrecon plan

Executes the exact `HlVulkanRecon` dispatch plan through the shipped
`spirv/hlrecon.spv` on a local Vulkan compute device and diffs the outputs
against the JVM twin within a ULP envelope.

## Flow

1. `./gradlew :app:testDebugUnitTest --tests "com.matthew.rawlens.HlVulkanReconTest"`
   (`writeMeasureBundle` writes `plan.json` + little-endian float32 `.bin`
   files to `/tmp/hlmeasure/bundle`: staged device inputs plus twin outputs).
2. Build: `clang++ -std=c++17 -O1 -I/opt/homebrew/include -L/opt/homebrew/lib
   -lvulkan -o harness harness.cpp` (macOS; Linux: point at your SDK).
3. Run: `VK_ICD_FILENAMES=<icd> ./harness /tmp/hlmeasure/bundle
   ../../app/src/main/assets/spirv/hlrecon.spv /tmp/hlmeasure/out`
   (68 dispatches, one submit each with cross-submit barriers).
4. Compare: `python3 compare.py` (device-vs-twin) and
   `python3 compare_golden.py` (device-vs-oracle-goldens, parsed from
   `RtHlEndToEndTest.kt`).

## Baseline (Apple M1, MoltenVK, 2026-10-16, 32x32 oracle scene)

| output | device-vs-twin | device-vs-oracle | durable gate |
|---|---|---|---|
| merged R | 16 | 7 | 64 |
| merged G | 14 | 10 | 64 |
| merged B | 15 | 10 | 64 |
| mask | 71 | 234 | 512 |
| guide | 0 | 4 | 64 |

All inside the `HlVulkanReconTest` gates. Note the device lands closer to
the C++ oracle on pixels than HotSpot does (`precise` forbids the FMA
fusion the JVM applies).

## Lossless optimization (2026-10-16, same device)

Banked (exact, zero value change):

- Guide statistics (`meanI/corrI/varI`) depend only on the guide image and
  the radius, so they run once per radius instead of once per guided pass
  (12 dispatches instead of 24).
- Radius-0 box means are the IEEE-exact identity (single tap over 1.0),
  so their H+V copy dispatches are skipped with buffer aliasing in
  `HlVulkanRecon.buildPlan` (hlbl=1 shape: 40 dispatches instead of 68).
  Guarded by the `radiusZeroPlanSkipsIdentityBlurs` and
  `fullPlanDefUseValid` tests.
- `ADDMUL` combine op closes `a*guide+b` without an extra negate pass.

Tried and REVERTED: shared-memory blur tiling (cooperative staging with
provably identical summation order) measured 0 ULP different (bit-exact)
but ~40% SLOWER at r=32 and neutral at r=4 on this stack — neighboring
threads' overlapping windows already hit the L2/texture cache, so explicit
staging only added barrier and occupancy cost. The direct windowed form
is the fast path; the revert restored baseline bits exactly (0 ULP).

Blur micro-benchmark (2048x2048 plane, H+V passes, medians of 7
alternating runs): r=4 untiled ~5.0ms; r=32 untiled ~9.6ms vs tiled
~13.5ms. Mali numbers will differ in absolute terms, but the L2-locality
argument transfers: keep the direct form unless on-target profiling says
otherwise.

## Mali-G615 on-device results (Xiaomi 25080RABDG via `run_android.sh`, 2026-10-16)

| output | device-vs-twin | device-vs-oracle | durable gate |
|---|---|---|---|
| merged R | 15 | 8 | 64 |
| merged G | 13 | 11 | 64 |
| merged B | 14 | 11 | 64 |
| mask | 66 | 229 | 512 |
| guide | 0 | 4 | 64 |

Full 68-dispatch plan: gpu_ms=36-42 (32x32 scene, submit-dominated).
Blur micro-benchmark (2048x2048, H+V r=4): direct ~37ms median;
the shared-memory tiled variant is bit-exact (0 ULP) but ~35% slower
on Mali too (5/5 rounds) — the revert stands on-target as well.

## Harness portability lessons (Mali faults where MoltenVK is lenient)

- Never `vkUpdateDescriptorSets` with NULL image views (missing table
  images, e.g. no `gamma`): Mali reads view+0xa8 and SIGSEGVs. The
  harness now fails loudly on NULL views instead.
- `VkQueryPoolCreateInfo` needs exactly five members
  (`queryCount=2`); a six-initializer typo silently made a 0-query pool.
- Command pools that re-record need `RESET_COMMAND_BUFFER_BIT`.
- Android needs no portability enumeration (`__ANDROID__` guard);
  macOS needs `VK_ICD_FILENAMES` + the enumerate-portability bit.
- Cross-submit visibility uses an explicit memory barrier at the top of
  each op buffer (queue-wide backward scope); one submit per op keeps
  UBO contents exact.
- Micro bundles must include every image the shader's binding table can
  touch (`gamma` included) — the harness validates views, not names.
