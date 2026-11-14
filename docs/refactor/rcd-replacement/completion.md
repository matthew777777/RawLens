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

# Milestone status — replacement not complete

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

2026-11-01. Starting HEAD/branch and preserved unrelated changes are in
execution-log.md. This work implements the recommended M1 starting milestone
and advances M2–M4 through host optimization experiments and internal recording integration. It does not complete the
product replacement or its required release acceptance.

Actual additions:

- `tools/photo-rcd/upstream/`: pinned RawTherapee/original-RCD public evidence,
  source hashes/Git blobs, original notices and license texts.
- `tools/photo-rcd/`: reproducible scalar extraction, FP32 host Vulkan baseline,
  synthetic/invalid-input checks, real RAW import/checks and Kotlin photo
  compatibility adapter. Still-photo behavior and default recording choices remain unchanged; an internal validation switch selects the new recording route.
- `tools/photo-rcd/fixtures/`: three verified CC0 CFA crops, provenance/normalization
  manifest and official CC0 text. Full DNG/PGM remain outside the repository.
- Reports and contracts here, including preserved failed exploratory comparisons
  and a reviewable oracle adaptation patch.

## Gate status

| Gate | Outcome |
| --- | --- |
| Public algorithm provenance/license records | Pinned source route established; existing photo origin remains partly uncertain and excluded as a foundation. No legal-clearance claim. |
| M1 independent host oracle / numerical contract | Implemented and host-validated; source-step traceability, fixtures and reproducible flags recorded. |
| M2 race-free FP32 baseline | Host Vulkan numerical gate passes in the recorded Apple M1/MoltenVK environment. No Android driver validation. |
| M3 tiled reconstruction and packing-overhead experiment | Experiments implemented and measured on host: shared-memory reconstruction, CFA cache, packed CFA, direct addressing and pass specialization. Packing/tiled variants were slower; retain scalar FP32 baseline and optional direct/specialized path. Mobile tuning remains open. |
| M4 precision / grade / LUT / scaling / P010 / recording integration | Internal route integrated with separate grading/LUT/scaling/P010 and fence-protected RGB ownership; RAW16/corrections/final FP16 rounding host-tested. Android integration validation incomplete. No new UI choice; FP16 arithmetic and fusion gated. |
| M5 Android correctness, ownership/failure-path and real cadence/thermal acceptance | Headless synthetic/CC0 correctness passed on Xiaomi Mali-G615; Adreno and real-camera ownership validation open. Performance recordings remain deferred; mandatory targets not waived. |
| Fresh release APK/source/symbol inspection | Six fresh GitHub/F-Droid release verification APKs passed source/shader/license/JNI and retired-identifier checks. Debug-signed local artifacts only; no publication approval. |
| External cleanup/release/issue actions | No actions taken. Previous technical history cleanup is not repeated. New replacement publication/response proposals require actual integrated artifacts first. |

No mobile fps or thermal outcome is available. Stable 1080p30, 1440p30 and 4K24
remain mandatory open acceptance gates under the research contract. Headless correctness was subsequently authorized and tested on the listed Mali;
performance recordings remain deferred. Both named target GPU families and the exact
sensor/crop/output/profile conditions must be established for acceptance.

Remaining implementation work is not blocked on a missing public algorithm: the
pinned source is available. Further optimization and Android integration validation must
continue from this validated host baseline. External hardware evidence and device
test authorization are specifically required for the later Android gates; neither
can be replaced by these host results. Existing-distribution questions and qualified
legal review remain separate from technical correctness.

## Continued implementation evidence

- 801 packed/tiled comparisons, 801 cache comparisons, 534 direct-address
  comparisons and 534 pass-specialization comparisons matched saved baseline
  RGBA bytes exactly. Fifty-five targeted intermediate-stage comparisons also
  matched the pinned scalar oracle exactly.
- Both actual APK shader binaries (baseline and optimized), compiled with the
  pinned NDK tool, passed 267 shared-engine host frames each, including persistent
  allocation reuse, resize/phase changes and invalid-phase recovery.
- JVM suite: 1,838 tests, zero failures/errors, six skips. ASan/UBSan input adapter
  checks include 63,488 finite-half round trips and adjacent-half tie rounding.
- `package-validation.json` contains six APK paths, hashes, license hashes, shader
  manifests and signer identities. They use Android Debug signing, so they are
  verification artifacts, not upgrade-compatible public releases.
- `specialization-benchmark.json` records host-only GPU medians: optimized scalar
  35.028 / 64.496 / 150.415 ms at 1080p / 1440p / 4K; corresponding packed paths
  40.451 / 72.428 / 169.731 ms including packing. These are not mobile fps.

The synchronous reconstruction/upload integration is intentionally uncomplicated;
its CPU copies, final FP16 boundary and separate grading need device correctness
and performance evidence before any production selection. Asynchronous RAW import,
portable device feature gates in practice, all-profile/LUT recording validation,
full cadence/thermal acceptance and a qualifying packed implementation remain open.

## Further ownership review

Added `PhotoRcdCompletion` and three host tests for grade-fence duplication
failure, timeout, wait exception and successful completion/reuse. If duplication
fails after submission, the renderer retains the original fd and the recorder
stops accepting frames; no unprotected P010 destination is reused. The complete
JVM suite now reports 1,841 tests, zero failures/errors and six skips. See
ownership-validation.json and jvm-validation.json.

External release notes, publication steps and an issue response are prepared in
external-proposals.md without executing external actions. Device gates remain
deferred and no performance acceptance status changed.

Ownership-fix release rebuild passed (GitHub/F-Droid, frozen version). All six
new APKs passed inspection; shader bytes match both previously executed packaged
artifacts exactly and native engine source is unchanged. See
ownership-package-validation.json. No Android tests or external actions ran.

## Headless Android correctness — authorized continuation

Three headless instrumentation tests passed on Xiaomi 25080RABDG, Android 16,
Mali-G615 MC2 (Vulkan 1.3.247, driver 184553472). The isolated
com.matthew.rawlens.rcdcheck package preserves the normal camera application.
No activity, camera stream, codec recording or performance run was launched.

123 pinned-scalar-oracle cases passed: max normalized RGB error 2.9802322e-7,
largest per-case mean 1.1616454e-8, border max 5.9604645e-8. Baseline and optimized
results matched exactly on this GPU. Checks also passed for invalid input
recovery, padded RAW16/odd crop, post-RCD shading and pre-WB white protection,
separate all-profile scaling/grade/P010, actual completion-fd duplication/reuse
and a nontrivial 16-point recorded LUT. P010 samples stayed within two codes of
the declared CPU anchors. No skipped required path.

See android-correctness-summary.json and android-mali-headless-final/.
An earlier expanded run rejected an unsupported two-point LUT test fixture;
that evidence is retained. The test fixture was corrected, not the tolerance.

Still open: Adreno device correctness; actual sensor/RAW AHB ownership and
cancellation/start-stop behavior; complete profile/LUT scene coverage, full
decoded output and all mandatory recording cadence/thermal gates. Headless
correctness does not establish delivered fps. Existing release-package evidence
predates the added instrumentation/readback bridge; the tested isolated APK
hashes are recorded separately and are not publication artifacts.

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
