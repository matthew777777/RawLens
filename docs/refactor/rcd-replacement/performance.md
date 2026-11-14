## Recording integration and prior-work review — 2026-11-01

The requirements-derived RAW16 GPU path is now connected to the hidden recorder
route. Headless Android correctness passed five tests, including 36 full-source
and 28 independently area-reduced adapter cases; unchanged native baseline passed
267 exact host comparisons. Two fresh release APKs passed package inspection.
Six real 30-second recordings fully decoded with actual RCD and no MHC frames,
but all failed cadence: full-source 0.55–0.69 fps; approximate 960×540 reconstruction
6.51–7.96 fps. Neither mobile performance nor approximate quality is accepted.

The user authorized reduced geometry/equation experiments, then directed review
of our earlier 16-to-30 fps recording work. That review found the current recorder
forces encoder GPU-writer delivery off, and the new RCD route also performs a
synchronous RGB readback/copy. Recovering the application scheduling/encoder
benefits is the next integration priority. No excluded shader implementation is
needed for that work. See [integration results](requirements-recording-integration.md),
[measured summary](requirements-recording-summary.json) and
[prior session review](prior-recording-session-review.md).


## Latest three-mode diagnostic result — 2026-11-01

The FP32 shared-CFA, cell-packed route completed 30-second recordings at each
requested mode on Xiaomi 25080RABDG. All files fully decoded; Main10/audio and
actual RCD use are recorded in per-run metadata. These are explicitly diagnostic,
with competing phone workload uncontrolled at the user's request.

| Requested mode | Distinct queued fps | Median reconstruction | Cadence gate |
| --- | ---: | ---: | --- |
| 1920x1080-30 | 2.29 | 440.4 ms | Failed |
| 2560x1440-30 | 2.24 | 442.2 ms | Failed |
| 3840x2160-24 | 2.21 | 454.4 ms | Failed |

Evidence: `recordings-photo-candidate-cell-packed-modes/analysis-summary.json`,
per-mode `analysis.json`, and `recording-optimization-summary.json`. The source
crop/detail was held constant. Counts use actual unique sensor timestamps and
queued identities, not fixed PTS. Drops/cadence deficits remain failed evidence.
All 1080p30, 1440p30 and 4K24 targets remain unmet; no ten-minute acceptance soak
or production promotion is claimed. GPU RAW normalization/import, output-copy
removal, further kernel throughput tuning and controlled multi-device acceptance
remain open. No FP16 arithmetic or fused P010 workaround was introduced.

# Performance acceptance — not measured

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Earlier phases performed no Android tests. Subsequently authorized headless
Vulkan correctness tests passed on the connected Xiaomi; no recording runs
were performed. Host Apple M1/MoltenVK numerical checks are not mobile performance
evidence. No delivered fps, thermal stability, drop/duplicate rate, packing
overhead on mobile or release-device GPU timing is claimed.

Required release gates remain unchanged:

| Mode | Status |
| --- | --- |
| 1920×1080 at 30 fps | Unmeasured; mandatory |
| 2560×1440 at 30 fps | Unmeasured; mandatory |
| 3840×2160 at 24 fps | Unmeasured; mandatory |

The complete acceptance contract in research-2026-11-01.md applies: named Mali
and Adreno devices, frozen source/crop/FOV/detail, real sensor identities and
timestamps, no drops/duplicates/starvation/fallback, rolling cadence and jitter
limits, Main10 with audio and full decode, profiles/LUT/live monitoring, ten-minute
runs per required mode/device plus warm-condition repeats. No PTS manipulation.

Host M3 experiments are recorded in packing-benchmark-isolated.json,
direct-packing-benchmark.json and specialization-benchmark.json. Shared-memory
reconstruction uses an 8×8 output core, green halo 5 and opposite-color halo 3,
with immutable guide inputs and barriers between reconstruction stages. Packing
uses four FP32 CFA sites per 2×2 cell; odd-cell unused lanes are zero. Timings
include the extra packing pass. None of the packed variants beat scalar input
on this Apple M1. No production packed layout has been fixed. Optional FP16
arithmetic and P010 fusion remain behind later numerical gates. Host numerical
validation alone cannot authorize restoring the advertised video RCD option.

Performance recording tests remain explicitly deferred. Headless correctness
was subsequently authorized and passed on the listed Mali device. Named acceptance
hardware beyond the listed Xiaomi, complete device/driver evidence and real
recordings will be required later; no inference of an Adreno result from Mali or
Apple results is permitted.

Host-only final isolated comparison (five warmups, ten measured repeats):

| CFA size | Scalar baseline ms | Direct + specialized ms | Packed + specialized ms | Packing pass ms |
| --- | ---: | ---: | ---: | ---: |
| 1920×1080 | 89.332 | 35.028 | 40.451 | 0.462 |
| 2560×1440 | 157.981 | 64.496 | 72.428 | 0.745 |
| 3840×2160 | 361.725 | 150.415 | 169.731 | 1.450 |

These medians exclude app RAW upload/correction/grade/codec work and cannot pass
any real-time recording gate. They show that this host implementation still has
substantial optimization work. Benchmark outputs matched full-resolution scalar
RGBA exactly at all three sizes. First exploratory timing overlapped numerical
work; use the later isolated reports for decisions.

## 200 ms recording candidate preparation — 2026-11-01

User selected the preserved 200 ms photo RCD as the priority main recording candidate. Added a hash-pinned, race-free FP32 adaptation generator and an explicit native integration contract. Compilation and Vulkan 1.1 SPIR-V validation passed. This is preparation, not a default-backend switch or a numerical/performance pass. Elevated ADB listed no devices. Android tests and real recordings are authorized, but unavailable in this turn. Historical 200.7 ms covers demosaic only. All mandatory 1080p30/1440p30/4K24 gates remain unmet; previous failed recordings remain evidence. See [candidate preparation](recording-candidate.md) and [validation manifest](recording-candidate-preparation.json).

## Photo recording candidate — measured continuation

Android recordings are now explicitly authorized and ran headlessly on Xiaomi
25080RABDG/Mali-G615 MC2. The earlier deferral statements above are historical.
The first canonical FP32 full-grid adaptation ran 30 seconds per required mode,
with unchanged 3840×2160 full-source crop. Files decoded, but all cadence gates
failed: 1080p30 delivered 0.872 distinct queued fps at 1097 ms median render;
1440p30 delivered 0.838 fps at 1118 ms; 4K24 delivered 0.810 fps at 1136 ms.
These results are not the preserved shader's historical 200.7 ms benchmark.
They expose the cost of the explicit canonical FP32 validation adaptation.

After shared guide loads, paired R/B-vs-green ownership and separate FP32
storage images, an eight-second 1080p diagnostic still measured 1125 ms median
render, with too few post-startup frames for meaningful cadence. No speedup or
acceptance is claimed. Guide/green/opposite/final median GPU stages were roughly
177/226/147/246 ms. CPU normalization remained about 55 ms and final half-buffer
upload about 28 ms. Both also exceed the eventual frame budget unless overlapped
or removed; they are explicit optimization work, not hidden in shader time.

Evidence: `recordings-photo-candidate/`, `recordings-photo-candidate-images/`,
`android-recording-candidate/`, `android-recording-candidate-images/`.
Shared/paired/image optimizations were exact against the new canonical candidate
on 267 host cases, and the FP32 image phone correctness/product tests passed.
Direct intermediate addressing removes redundant reflection and skips unowned
final pixels; its 267-case host test also passed. Subsequent phone measurements
are recorded separately. Required 1080p30, 1440p30 and 4K24 acceptance remains
unmet. No ten-minute acceptance soak is justified by these failing short runs.

## Current candidate result

The direct-addressing candidate is integrated through native Vulkan, JNI,
Kotlin, the headless recorder and the separate grading/LUT/scaling/P010 route.
Shared guide loading and paired R/B-vs-green dispatch preserve immutable pass
inputs. All 267 host outputs match the new canonical candidate baseline exactly;
maximum pinned-oracle interior error is 2.3841858e-7 and border error zero.
The unchanged canonical backend also passed 267 exact shared-engine regression
cases, including persistent reuse, resize/phase changes and invalid recovery.

Four final Xiaomi headless tests passed (`android-recording-candidate-direct/`),
including 123 candidate fixtures at maximum error 2.9802322e-7, profiles/LUT,
RAW adapter and grade ownership. The final direct buffer candidate is validated;
the image variant's phone validation predates the direct-addressing change and
must be repeated if selected for production.

The latest eight-second 1080p diagnostic measured 648.4 ms median render,
1253.9 ms p95, 11 RCD frames and two drops. Median GPU guide/green/opposite/final
costs were 160.5/111.7/92.2/103.4 ms; normalization 39.9 ms and half upload 16.0 ms.
This improves the first validation adaptation's roughly 1097 ms, but remains
slower than the historical 200.7 ms demosaic and the earlier roughly 470 ms
replacement route. It does not meet any requested recording cadence. The short
probe has insufficient post-startup sensor coverage; the analyzer's zero cadence
value is not a claim that no frames were encoded. Full-grid 30-second results
for all required modes remain failed evidence in `recordings-photo-candidate/`.

Fresh GithubRelease and FdroidRelease builds passed. Two current release APKs
passed source/shader/license/hash/JNI/retired-identifier package checks; see
`recording-candidate-package-validation.json`. These verification packages were
not published and do not establish production signing approval or fps.
Python syntax compilation and `git diff --check` passed. No unrelated edits were
reset, no private archive opened, and no remote actions performed.

The candidate remains internal: public MHC/default and still-photo behavior are
preserved. Default promotion, direct GPU RAW normalization/import, eliminating
CPU output copies, further kernel/register/traffic tuning, final precision gates,
all-mode sustained cadence/thermal acceptance and Adreno validation remain open.
33.3 ms at 1080p/1440p and 41.7 ms at 4K remain mandatory frame periods, not results.

## Compiler and memory experiments

The compact-response probe measured 659.7 ms median render, with roughly
104 ms guides plus 112 ms response preprocessing: no overall gain versus the
previous 648.4 ms route. Pairing green reconstruction, reusing odd-column
responses and hoisting tile metadata with a safe in-bounds CFA fast path reduced
the next short probe to 494.0 ms median. Main guide/green/opposite/final medians
were 49.8/40.0/76.4/55.1 ms, plus 66.7 ms responses and 28.3 ms product output;
CPU normalize was 41.3 ms and half upload 22.3 ms. The eight-second diagnostic
still failed cadence (1.30 distinct queued fps over its analyzed sensor span).
Literal-stage binaries passed all numerical gates but measured 514.5 ms median
in the next short probe: no demonstrated speedup. Measurements are distinct
runs, not controlled thermal equivalence or sustained acceptance. Reports live in
recordings-photo-candidate-cached/, recordings-photo-candidate-invariants/ and
recordings-photo-candidate-baked/.

Primary-source guidance consulted: [Khronos specialization sample](https://docs.vulkan.org/samples/latest/samples/performance/specialization_constants/README.html)
and [Arm Mali compiler guide](https://documentation-service.arm.com/static/65d790ed837c4d065f654434).
They motivate compiler/static-stage and memory/occupancy experiments, not claims
of a register spill diagnosis on this phone. Mali Offline Compiler is unavailable
in this environment. Only newly generated replacement SPIR-V was inspected for
instruction inventory; no preserved/disputed shader binary was disassembled.
Shared-memory CFA staging versus direct read-only CFA is measured independently;
shared memory is not assumed to provide a dedicated on-chip scratchpad on Mali.

## FP32 packed guide comparison

The independently derived cell layout passed exact comparisons to the canonical
candidate and the phone product/fence gate. It uses 8 FP32 guide values per 2×2
cell (4 VH, 2 LP, 2 PQ), plus scalar green and two-component opposite storage.
This is storage/dispatch packing, not a lower-resolution reconstruction.
The shared-CFA short probe measured 483.256 ms median render, with guide/green/
opposite/final GPU medians 120.6/44.1/62.5/55.9 ms. Direct-CFA measured 506.641 ms,
with corresponding 168.6/39.3/53.6/46.5 ms. Bypassing shared staging did not improve
overall time. Both decoded but failed the recording cadence gate. These short
runs had insufficient post-startup sensor coverage; analyzer fps=0 is not a claim
that no frames were encoded. See recordings-photo-candidate-cell-packed/ and
recordings-photo-candidate-direct-raw/.

The user's explicit answer was “Keep testing as diagnostics for now”; workload
was not controlled for acceptance. No foreground app was stopped or phone setting
changed. Three 30-second packed-route mode diagnostics follow below. A requested
mode, successful recording call or GPU timing alone never establishes delivered
fps. Stable 1080p30/1440p30/4K24 and warm ten-minute acceptance remain open.

## GPU requirements prototype — implemented

See [gpu-input-implementation.md](gpu-input-implementation.md). Added GPU RAW16 cell packing, on-GPU normalization from cells and compact LP/PQ/missing-color planes around the pinned public baseline. The GPU producer feeds RCD through an explicit dependency without CPU intermediate normalization/readback. Host input tests passed 84 cases; host complete-chain tests passed 67; standalone headless Xiaomi tests passed 35 (max RGB error 4.172325134277344e-7). A pack-only 4K-source diagnostic measured 3.725845 ms warm median, excluding staging, reconstruction and recording. The source crop was preserved. Native recorder/camera-buffer and video-output integration remain unfinished; no new FPS, APK or acceptance result is claimed.

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

## Claude handoff, stopped — 2026-11-02

The user requested noting all work and continuing in Claude tomorrow. No active
own build/test/recording/benchmark job was found at the stop. Current source,
validated historical artifacts, failures and exact next steps are recorded in
[claude-handoff-2026-11-02.md](claude-handoff-2026-11-02.md), with current hashes
in handoff-state-2026-11-02.json. Full-source tile-local recording regressed; a
large-frame bounded-VH shape test has an unresolved non-finite product failure.
The queried-usage GPU image probe passed only its small native pixel test; the
new allocation fix has not passed the complete rebuilt app gate. All mandatory
FPS/quality/thermal acceptance remains unmet. Do not resume without user direction.
