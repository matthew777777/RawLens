# Full-source optimization investigation

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

2026-11-01. HEAD 28977494032f743742eafbbf77ff437317544554, refactor/2.0.
Unrelated working-tree edits preserved; Xiaomi 25080RABDG connected. The user
rejected reducing reconstruction resolution: all new measurements here use the
full 3840×2160 crop of 4080×3060 RAW input, preserving detail and field of view.
Approximate reduced-resolution reports remain historical failures, not candidates
for acceptance. No excluded algorithm source or reference data was consulted.

## Measured work

1. Remove reflective modulo from executed, domain-bounded addresses. The public
   stage margins bound all stencil reads; the retained VH traversal selects a
   valid earlier domain. Host oracle: 67 cases, max error zero. Phone oracle:
   35 cases, max 4.172325134277344e-7, including three licensed real CFA crops.
   Reports: requirements-direct-address-validation.json and
   android-requirements-direct-address/run.json.
2. Decode/normalize RAW16 once into an immutable FP32 GPU cache. This explicitly
   trades another ~16.6 MB of input representation at 4K for eliminating repeated
   bit extraction, sensor-phase lookup and normalization division at every stencil
   tap. No CPU normalization or intermediate readback. Canonical interior VH
   bypasses retained-domain traversal; its exact fallback remains. Persistent
   native engine: 71 comparisons, final-half output exactly matches the validated
   normalized-input product engine on Apple M1. Phone five-test suite passed;
   original FP32 numerical thresholds remain unchanged.
3. Compute four directional responses once per global site (diagonals follow
   the pinned odd-column convention). They occupy final-RGB storage before
   guide calculation; that storage is dead before RGB reconstruction writes it.
   Explicit compute barriers order normalization -> responses -> guides ->
   reconstruction -> product. No extra full-frame allocation. The eight-pass
   timestamp range is bounded by the existing sixteen queries. Distinct native
   allocations justify `restrict` on these blocks. Host production engine:
   71 exact final-half comparisons. Phone validation/timing follows below.

The normalization-only full-source diagnostic recorded real RCD (68 frames,
zero MHC), zero P010 CPU copy and zero frame-processing errors, but only
2.30055 delivered sensor fps. Median RCD wall time 416.8549 ms, p95 476.0286 ms.
Full Main10/audio decode passed. Stage medians: normalization 11.79 ms,
guides 116.34, green 29.29, opposite 24.60, final 105.33, product 24.47,
RGB half upload 22.22. Costs overlap/contain submission and completion effects;
do not sum them as independently elapsed frame intervals. Source/report/APK
identities are in recordings-normalized-full/. This is diagnostic improvement
from the earlier full-source integer-cell route (~1.45 s), not controlled causal
proof or performance acceptance.

## Public guidance and limits

- [Khronos specialization constants](https://github.com/KhronosGroup/Vulkan-Samples/blob/main/samples/performance/specialization_constants/README.adoc): specialize uniform control paths. The engine already specializes stage selection;
  response caching does not assume that merely adding specialization meets FPS.
- [Arm Mali Offline Compiler guide](https://documentation-service.arm.com/static/674866c827eda361ad4dc590): simplify shader work and inspect arithmetic/load-store/register costs. Shared memory uses cached system RAM on Arm, so adding it without measurement is not automatically faster. No offline-compiler spill diagnosis is claimed here; the tool is not installed.

FP16 arithmetic, reduced detail, hidden timestamp changes and obsolete shader
benchmarks are not used to claim success. Full-source 1080p30/1440p30/4K24
recording targets remain unmet. Native RCD still synchronously waits and reads
half RGB to CPU before a HardwareBuffer upload; independent encoder-owned P010
delivery has removed the later pixel copy, not this RGB bridge. A supported
GPU RGB handoff and per-slot async resources remain required integration work.

## Cooperative final interpolation experiment

`app_normalized_shared_final_source()` stages CFA, green and missing-color
values for an 8×8 output group plus radius-three halo: three 14×14 float arrays
(2352 bytes). All invocations participate in loading and the workgroup barrier
before any individual tile/crop bounds return. Valid loads are bounded by tile
extent; exactly-18-pixel tails remain skipped. Stage 3 alone uses the shared
accessors; the other public stages retain their own immutable input reads.
The final interpolation equations, summation order, CFA phases and output tile
ownership are unchanged. Persistent host comparison passed 71 cases exactly
at the final-half boundary. See normalized-shared-engine-validation.json.

This variant remains a separate selectable experiment. Arm's shared-memory
caveat applies: fewer repeated global reads is an implementation rationale,
not proof of lower device time. Device correctness and recording measurements
must decide whether it is useful; do not promote it from host tests alone.

## Stopped — full-source phone tests, 2026-11-01

Stopped at the user request. Six headless correctness tests passed, including
32 nonuniform chroma cases with zero code error. Three full-source shared/cached
30-second diagnostic takes completed; none establishes 1080p30/1440p30/4K24.
Native render remains roughly 0.44 seconds. See full-source-phone-stop-status.json
for exact per-mode results and analysis coverage. Remaining analysis/build jobs
were stopped; fresh final release/package validation remains incomplete.

The user correctly identified an order-of-magnitude gap: 444ms is about 10.7x
the 24fps frame budget, or 13.3x the 30fps budget. Further small cache changes
are insufficient as a strategy. Next work should redesign the GPU-resident
video RCD execution around tile-local intermediates, supported GPU RGB handoff
and encoder-owned output, with full RAW detail/FOV preserved. Simpler directional
math is an experimental option requiring quality evidence against the pinned
photo reference; it cannot inherit exact numerical acceptance. No implementation
or new tests of that architectural direction were started after the stop.

A GPU-only scratch allocation experiment passed host checks (267 baseline exact
frames; 71 normalized/shared half-boundary exact frames), but is not present
in the measured phone APK and has not passed phone validation. Preserve that
distinction when resuming.
