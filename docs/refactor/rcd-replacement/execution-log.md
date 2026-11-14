# Replacement execution log

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

## 2026-11-01 — initial snapshot and M1

Initial HEAD `28977494032f743742eafbbf77ff437317544554`, branch `refactor/2.0`.
Read the independent packed photo plan (including its research refinement),
research-2026-11-01.md, public-reference replacement plan and issue1-removal.md
before implementation. No applicable AGENTS.md found in the workspace or its
ancestor directories. Work is local; no commits, remote writes, history cleanup,
release changes, issue comments or device tests are authorized by this execution.

Initial modified files (preserved): `RefactorDeviceChecksTest.kt`,
`tinydng_jni.cpp`, `RawCameraController.kt`, `RawStreamPlan.kt`,
`RawVideoRecorder.kt`, `SessionStreamSelection.kt`, `VendorKeyEditors.kt`,
`RawStreamPlanTest.kt`, `SessionStreamComboTest.kt`,
`docs/refactor/xiaomi-20261101-memory-investigation.md`, `tools/tinydng_roundtrip.c`.
Initial untracked files (preserved): `raw_dng_stream.h`,
`CameraResultDiagnostics.kt`, `LazyResourcePool.kt`, `LazyResourcePoolTest.kt`,
`SensorCadenceTest.kt`, `docs/refactor/camera-result-diagnostics.md`,
`docs/refactor/xiaomi-20261101-123748-followup.md`. During audit, an additional
unrelated `docs/refactor/motorola-20261101-shutter-recovery.md` appeared; preserved.
Only `tools/photo-rcd/` and new evidence files here belong to this implementation.

`adb devices -l` at entry listed `fe79feha9lmb6hhi`, Xiaomi `25080RABDG`,
product `lapis_eea`, device `lapis`, connected. Listing only; device execution is
deferred. Host tools: Apple clang++, Python 3 stdlib, glslangValidator; Vulkan
headers and MoltenVK/loader libraries installed under `/opt/homebrew`. No standalone
`kotlinc` or `vulkaninfo` found on PATH.

Exposure: task context contains prior exposure to disputed material. No disputed
shader bodies, archives, disassembly, median/ratio-grid design, old expected pixels
or derived sources were opened for this work. Existing public-reference checkout
was used only after confirming its upstream remote and pin. Photo candidate history
and diffs were inspected to assess eligibility; uncertain scheduling changes are
excluded from new implementation guidance. This is an attributed public-source
port, with independently designed integration, not a strict clean-room process.

Decisions:

- Removal/history work is already complete technical work; do not repeat it.
- RawTherapee is the primary oracle. Preserve shipped photo behavior. Current
  photo source is an audited compatibility candidate, not an approved foundation.
- Fetch pinned sources directly through the read-only GitHub connector. Preserve
  full upstream files and GPL notices; verify exact SHA-256 and Git blob identities.
- Compile the upstream scalar algorithm via a new host shim, extracting only
  border interpolation and SQR/LIM01/intp dependencies. Emit a reproducible
  adaptation patch and stage snapshots; no Kotlin/GLSL reference translation used.
- Compile with FP32, `-ffp-contract=off -fno-fast-math`, no OpenMP.
- First compile found a host alignment macro placement error; corrected the shim
  to the upstream-compatible GCC/Clang aligned attribute. No equation changed.

Reproduction: `python3 tools/photo-rcd/validate.py --work /tmp/rawlens-photo-rcd-m1/validation --report docs/refactor/rcd-replacement/host-validation.json`.
Build sources/patches and binaries go under `/tmp`, not app assets. Reports below
must distinguish oracle self-checks from implementation equivalence.

## Host implementation and investigation

- Vendored exact pinned public sources, hash manifest and license texts. Added
  scalar oracle extractor/shim, stage capture and compiler/sanitizer checks.
- 1,152 synthetic fixtures passed at O0/O2/ASan+UBSan with identical output and
  stage bytes; nine invalid inputs were rejected with no sanitizer diagnostics.
- Obtained raw.pixls.us CC0 entry 3115; full DNG SHA matched the published value.
  Parsed DNG BlackLevel and WhiteLevel directly, decoded CFA using LibRaw 0.22.2,
  retained three bounded normalized crops. Initial exploratory normalization used
  the storage maximum; corrected to tagged WhiteLevel=16314 before final real
  fixture tests. Three real crops passed O0/O2/ASan+UBSan exactly.
- Added host-only Vulkan SSBO FP32 baseline, not app assets. Initial full-frame
  translation failed PQ stage matching. Corrected odd-column HPF/half-index lookup.
- Remaining mismatches traced to upstream per-tile guide domains and retained VH
  scratch. Added independent per-tile buffers and recomputation from immutable CFA,
  avoiding mutable inter-tile dependencies. Preserved failed exploratory reports.
- Expanded tests found exactly 18-pixel tail tiles must be skipped before scratch
  updates, as in the source. Corrected that rule, rather than raising tolerances.
- Sandbox blocked host GPU discovery. Approved escalated host-only commands reached
  Apple M1 via MoltenVK. Metal pipeline initially failed because `interpolation`
  collides with a Metal namespace; renamed the new helper. No equation change.
- MoltenVK fast math is explicitly disabled to match the baseline contract.
  The two tail-skip failures also occurred without fast math: disabling it alone
  was not the fix. All reported thresholds remain unchanged.
- Current photo Kotlin comparison compiled using cached Kotlin 2.2.21 dependencies.
  Twelve compatibility comparisons show meaningful interior differences from the
  pinned oracle; preserve it, do not use it as the replacement foundation. A rerun
  initially selected a javadoc jar; fixed exact binary-jar selection and reran.

Other concurrent workspace changes appeared in `CaptureSharpeningSettings.kt`,
`app/version.properties`, `VendorSensorModePolicy.kt`,
`VendorSensorModePolicyTest.kt`, and Motorola capture documentation. Untouched by
this task. No Android app source was edited for this replacement milestone.

Remaining work is explicit in completion.md. No mobile correctness/performance or
fresh APK cleanliness is inferred from these host tests.

Final host baseline run: 267/267 cases pass, all captured-stage and final RGB
absolute errors zero on Apple M1/MoltenVK with fast math disabled; 24 repeated
noise runs have identical RGBA bytes. No threshold was raised. The full expanded
pre-correction failure report is retained as gpu-default-math-validation.json.
Final source HEAD remained `28977494032f743742eafbbf77ff437317544554`.

## Continuation — packing, integration and package verification

User requested continuation, briefly requested note/stop, then resumed. HEAD
remains 28977494032f743742eafbbf77ff437317544554 on refactor/2.0. All unrelated
camera/wavelet/sharpening changes preserved. New edits: app/build.gradle.kts,
CMakeLists.txt, EncodedVideoRecorder.kt, PhotoRcdRenderer.kt, native photo_rcd/
and tools/photo-rcd/ plus these reports. No device tests, installs, remote writes,
publication or history changes. Read-only inventory still lists Xiaomi
fe79feha9lmb6hhi / 25080RABDG. Sandbox adb listing failed to start its local
daemon; authorized read-only listing outside sandbox succeeded.

Implemented packed 2×2 CFA, shared reconstruction, source caching, direct
addressing and pass specialization from baseline equations. Numerical reports
record exact matches; host timings reject packing/shared reconstruction as
defaults on Apple M1. Direct addressing proves executed stencil bounds rather
than changing edge reconstruction. Retain plain FP32 baseline.

Implemented reusable native engine with immutable stages and completion-gated
resources, independent RAW16 adapter, correction order and explicit ties-even
FP16 output. Internal recorder validation switch uses separate existing grade,
LUT/scaling/P010 and diagnostic MHC fallback. No public RCD preference added.
Grade fence duplicates retain RGB until consumption; incomplete grade stops
recording and retains resources rather than reusing a live destination.

Build commands: ./gradlew :app:assembleRelease :app:testGithubDebugUnitTest
-PfreezeVersion --console=plain. Final log /tmp/rawlens-photo-rcd-complete-release.log
reports BUILD SUCCESSFUL. Version preserved at workspace's 2.0.72. Both release
flavors/ARM variants built. All six APKs inspected; debug signer explicitly
recorded. Actual packaged NDK shader bytes both passed 267 host engine cases.
No mobile fps claim. See completion.md for remaining work.

## Ownership audit continuation

User requested further work; Android testing remains deferred. HEAD/branch and
unrelated modifications preserved. Found a post-grade fd-duplication failure
could leave P010 without a delivery job while accepting later frames. Added
PhotoRcdCompletion with explicit original-fence ownership and stopped recording
on a duplicate failure after submit. Three host lifecycle tests pass, covering
duplicate failure, timeout, wait exception and repeated completion/reuse. This
does not substitute for real sync-fd/Android execution. Prepared external release
and issue-response proposals in external-proposals.md; no remote actions taken.

Ownership-fix release rebuild passed (GitHub/F-Droid, frozen version). All six
new APKs passed inspection; shader bytes match both previously executed packaged
artifacts exactly and native engine source is unchanged. See
ownership-package-validation.json. No Android tests or external actions ran.

## Authorized headless Android correctness work

User explicitly authorized Android correctness tests, headless, while deferring
performance recordings. Initial HEAD remains 28977494032f743742eafbbf77ff437317544554.
Connected device inventory: Xiaomi 25080RABDG / fe79feha9lmb6hhi, Android 16,
Mali-G615 MC2, GLES driver r44p1. No Adreno device connected. Unrelated new HDR
metadata/save changes in the workspace are preserved. Tests use an opt-in isolated
com.matthew.rawlens.rcdcheck package; no normal app replacement, activity launch,
camera stream, recording, sustained test or mobile timing measurement.

Added a canonical FP32 readback on the actual shared native engine and a RAW-plane
entry to the existing production adapter for synthetic headless validation.
Generated 123 instrumented-test fixtures from the pinned scalar oracle; not from
Kotlin photo comparison or disputed backend. Added required-path tests for
canonical output, exact same-device optimization, bad-phase recovery, padded
RAW16/odd crops, final FP16 readback and separate all-profile P010 grading/fences.
Output buffer declares CPU_READ as well as CPU_WRITE for correctness readback.
Results are recorded separately; this authorization does not lift the deferred
performance-recording gate or authorize public video RCD exposure.

Headless run results: first two methods passed. Expanded run then rejected the
new test's unsupported 2-point LUT before GPU submission; corrected fixture to
16^3 and reran all three methods successfully. Saved all runs. No thresholds
changed. Android GPU service vkjson confirms Vulkan identity directly; GLES
SurfaceFlinger line was inventory only, not a rendering API used by the tests.
Final test APK hashes and command in android-mali-headless-final/run.json;
summary in android-correctness-summary.json. Initial runner assumed AGP filter
key identifier; actual metadata uses value, corrected before installation.
Current workspace version was already 2.0.73 during concurrent work; all test
builds used -PfreezeVersion and -PphotoRcdHeadless. No unrelated edits overwritten.

## 200 ms recording candidate preparation — 2026-11-01

User selected the preserved 200 ms photo RCD as the priority main recording candidate. Added a hash-pinned, race-free FP32 adaptation generator and an explicit native integration contract. Compilation and Vulkan 1.1 SPIR-V validation passed. This is preparation, not a default-backend switch or a numerical/performance pass. Elevated ADB listed no devices. Android tests and real recordings are authorized, but unavailable in this turn. Historical 200.7 ms covers demosaic only. All mandatory 1080p30/1440p30/4K24 gates remain unmet; previous failed recordings remain evidence. See [candidate preparation](recording-candidate.md) and [validation manifest](recording-candidate-preparation.json).

## Recording candidate continuation

Implemented the preserved-photo candidate through the shared native engine,
Kotlin/JNI and headless recorder selector. Corrected the omitted pinned-upstream
odd-column diagonal lookup and preserved canonical tile/border rules. Original
and intermediate numerical failures are retained, not relabeled passes.
The canonical adaptation passed 267 host cases at max interior 2.3841858e-7 and
border zero. Xiaomi headless instrumentation passed four tests, with candidate
123-case max 2.9802322e-7 plus grading/LUT/ownership checks. Reports:
`recording-candidate-canonical-validation.json`, `android-recording-candidate/`.
The phone is connected again. Real-mode cadence/performance remains a separate
acceptance gate. Public MHC and still-photo selection are preserved; no remote
history, release or issue actions were performed.

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

## Continued FP32 optimization — 2026-11-01

Start: branch refactor/2.0, HEAD 28977494032f743742eafbbf77ff437317544554.
Dirty-tree snapshot preserved at /tmp/rawlens-rcd-continuation-status.txt;
unrelated camera/HDR/wavelet edits retained. Xiaomi 25080RABDG remained connected.
Added compact green/opposite storage, response caching, paired green ownership
with explicit odd-width initialization, odd-column diagonal response reuse,
per-invocation tile metadata, in-bounds raw fast path, literal-stage binaries,
FP32 Bayer-cell guide storage and a direct immutable-CFA access experiment.
Each host variant passed 267 exact output comparisons to the new canonical
candidate and the original pinned-oracle budgets; no tolerance was widened.
The initial compact-store omission of known mosaic channels was caught by the
host gate, corrected, and retained in recording-candidate-compact-initial-mismatch.json.
Phone correctness/product tests passed for compact cache, invariant/paired,
literal-stage and cell-packed variants; direct-CFA test and measurements follow.
No optional FP16 arithmetic/storage change or P010 fusion was introduced.

## Continued FP32 optimization results

Added compact green/opposite storage, response caching, paired green ownership,
per-invocation tile metadata, in-bounds raw addressing, literal-stage programs,
and a derived FP32 Bayer-cell guide layout. All six final host variants matched
all 267 canonical candidate outputs exactly and passed the original independent
RawTherapee budgets (maximum interior 2.3841858e-7, border zero). The unchanged
baseline passed another 267 exact shared-engine regression cases. Five device
configurations (cached, invariant/paired, literal-stage, cell-packed, direct-CFA)
passed all four headless correctness/product/LUT/fence tests each. These are
correctness checks, not performance passes.

Eight-second diagnostic medians: compact cache 659.7 ms; invariant/paired 494.0 ms;
literal stages 514.5 ms; packed shared-CFA 483.3 ms; packed direct-CFA 506.6 ms.
There is no demonstrated gain from literal stages or direct-CFA on this phone.
Short probes are separate, uncontrolled runs; do not present differences as
controlled speedup estimates. The packed variant halves guide storage and reduces
combined guide/green/opposite storage from 1806528 to 752720 bytes per canonical
tile, retaining full-resolution FP32 reconstruction. It still does not reproduce
the historical 200.7 ms demosaic timing or meet any mandatory recording target.

The user explicitly chose to keep testing as diagnostics with the phone workload
uncontrolled. GPU frequency/governor reads were denied by Android; no clocks or
phone settings were modified. The observed power snapshot was Awake/interactive,
USB-powered, battery 80%; it is not a frequency or thermal-performance measurement.
Controlled-idle acceptance, warmed ten-minute takes, Adreno evidence and final
production promotion remain incomplete. All three target modes stay mandatory.

Fresh GithubRelease/FdroidRelease builds and two current APK package inspections
passed; see recording-optimization-package-validation.json. Shader/source/license
hashes, all literal-stage binaries and required native exports were checked.
No public release or history/issue action was performed. The recording harness
now compares installed base.apk SHA-256 with the build and rejects a mismatched
--no-install run. See recording-optimization-summary.json and each per-run report.

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

## Requirements-derived design revision

The next layout and execution contract is [requirements-derived-design.md](requirements-derived-design.md). It starts with packed RAW16 input (the current guide-packed experiment still uploads normalized FP32 CFA), compact public-algorithm intermediates, dependency-derived halos and explicit video-buffer lifetimes. The integer layout/ownership model passed 84 cases across four CFA phases and seven invalid inputs; see requirements-layout-validation.json. No Android tests, runtime promotion, changed interpolation tolerance or performance pass is claimed by this revision. HEAD remains 28977494032f743742eafbbf77ff437317544554 on refactor/2.0; unrelated working-tree edits were preserved. Xiaomi 25080RABDG is available; no device tests were needed for this specification step.

## GPU requirements prototype — implemented

See [gpu-input-implementation.md](gpu-input-implementation.md). Added GPU RAW16 cell packing, on-GPU normalization from cells and compact LP/PQ/missing-color planes around the pinned public baseline. The GPU producer feeds RCD through an explicit dependency without CPU intermediate normalization/readback. Host input tests passed 84 cases; host complete-chain tests passed 67; standalone headless Xiaomi tests passed 35 (max RGB error 4.172325134277344e-7). A pack-only 4K-source diagnostic measured 3.725845 ms warm median, excluding staging, reconstruction and recording. The source crop was preserved. Native recorder/camera-buffer and video-output integration remain unfinished; no new FPS, APK or acceptance result is claimed.

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
