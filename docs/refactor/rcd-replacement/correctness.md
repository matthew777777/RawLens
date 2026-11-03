# Host correctness evidence — 2026-11-01

## Results

| Check | Result | Evidence |
| --- | --- | --- |
| Pinned scalar oracle, synthetic corpus | 1,152 cases passed; exact output/stage bytes at O0, O2 and ASan+UBSan | host-validation.json |
| Invalid input contract | Nine cases rejected with exit 2, no sanitizer diagnostics | host-validation.json |
| Licensed real CFA | Three Ricoh GR III crops passed, exact O0/O2/ASan+UBSan output/stage bytes | real-host-validation.json; tools/photo-rcd/fixtures/manifest.json |
| FP32 GPU versus scalar oracle | 267 cases passed unchanged diagnostic budgets; final RGB, border, native-sample and all captured-stage max/mean absolute errors **zero** | gpu-host-validation.json |
| GPU determinism | 24 noise cases repeated with identical GPU RGBA bytes | gpu-host-validation.json |
| Current Kotlin photo compatibility | Twelve comparisons recorded; meaningful interior differences, no equivalence claim | photo-comparison.json |
| SPIR-V | glslang Vulkan 1.1 compilation and spirv-val passed | GPU report commands/tool versions |
| Source/fixture integrity, Python syntax, whitespace | Pinned hashes and fixture hashes verified; Python AST parsed; git diff --check passed | execution-log.md; artifact-manifest.json |

GPU environment: Apple M1, MoltenVK 1.4.2, driver version value 10402,
`MVK_CONFIG_FAST_MATH_ENABLED=0`. See report for compiler/tool versions and exact
source/SPIR-V hashes. Zero measured error on this corpus/environment does not
establish universal CPU/GPU bit identity or Android correctness. The predeclared
2e-5 max / 2e-6 mean RGB investigation budgets were not raised to obtain a pass.
Stage comparisons cover upstream-owned interior, not unused atlas halo storage.

Synthetic host coverage: all four Bayer patterns, four odd/even crop origins,
padded sensor strides, odd sizes, canonical minimum 19×19, multiple tile/tail
geometries, zeros, near-black, flat fields, color fields, asymmetric ramps,
horizontal/diagonal edges, checkerboards, chroma, impulses, saturation and seeded
noise. Full-image output checks include canonical edges/corners. Tiny canonical
inputs are explicitly rejected. GPU tests receive tight normalized crops with
the resulting Bayer phase; they do not validate an Android RAW16 stride adapter.

Real fixtures: three 97×95 normalized CFA crops, sensor origins (68,40),
(1769,1081), (3500,2501), tagged black 64 and white 16314. Individual CC0 record,
original/crop hashes, decoder and normalization are retained. One camera is a
starting fixture set, not coverage of all sensors or HDR scenes.

## Investigations retained

`initial-gpu-mismatch.json` records the initial PQ lookup failure.
`full-frame-scheduling-mismatch.json` records why globally evaluated guides did
not match the actual scalar tile conventions. `gpu-default-math-validation.json`
retains the expanded run before the exactly-18-pixel tail-skip correction; its
two failures were still present with strict math and were fixed by the skip rule.
No failed exploratory run is silently treated as passing evidence. The final
report is gpu-host-validation.json.

The Kotlin photo reference differs substantially even away from the application
border: maximum error in the 10-pixel-excluded noise interior was about 0.464,
and chroma about 0.301. Flat interior matched. Those findings support selecting
the pinned public-source oracle; they do not authorize changing shipped photo
behavior. Kotlin uses direct full-resolution diagonal responses and mirrored
borders rather than all the pinned implementation conventions. More photo GPU
comparison remains deferred with device tests.

## Reproduction and limits

Run commands in [the tool README](../../../tools/photo-rcd/README.md). Host GPU
access may need an unsandboxed run on macOS; no script invokes adb. The saved
[adaptation patch](oracle-adaptation.patch) is reproduced by build_oracle.py.

Not covered: Android drivers, stage-buffer feature/limit gates on Mali/Adreno,
RAW16/black-level adapter invalid metadata, tiny-image application extension,
signed/above-white reconstruction, corrected photo shading/WB/highlight order,
grade/LUT/scaling/P010 fidelity, asynchronous recording fence ownership and
failure recovery, actual camera cadence/encoding or thermal performance.
These remain explicit later gates. No production path was replaced.

## Continuation results

optimization-validation.json: 801 exact packed/tiled comparisons.
cache-optimization-validation.json: 801 exact cache comparisons.
direct-optimization-validation.json and specialization-validation.json: 534
exact comparisons each. optimization-stage-validation.json: 55 targeted
comparisons with all captured stage bytes exact against the scalar oracle.

packaged-engine-validation.json: baseline and optimized shader bytes extracted
from a hash-verified APK each pass 267 actual shared-native-engine frames.
input-contract-validation.txt records sanitizer-backed RAW16/correction/half
rounding checks. jvm-validation.json records 1,838 tests, zero failures/errors,
six skips. package-validation.json records six verified release APK inventories.
These supplement, not replace, required Android correctness tests.

Further ownership tests: ownership-validation.json records three passing host
tests of the actual PhotoRcdCompletion owner. A failed delivery-fd duplicate
retains the original fence; timeout/wait exceptions cannot authorize slot reuse,
and completed original fds are closed once. Recorder stops after a post-submit
duplication error. Updated full JVM suite: 1,841 tests, zero failures/errors,
six skips. Kernel sync-fd behavior still requires deferred Android validation.

## Android headless Vulkan run

Authorized correctness-only execution passed all three methods on Mali-G615
(Xiaomi 25080RABDG, Android 16). See android-correctness-summary.json for exact
limits, artifacts and Vulkan identity. 123 scalar-oracle cases cover four CFA
phases, canonical borders, odd sizes/tile tails, noise/chroma/near-black/ramps
and three licensed RAW crops. Max/mean/border errors remained below the frozen
diagnostic budgets; optimization equality was exact on device.

Production RAW-plane adapter checks cover padded row/pixel stride and odd crop,
invalid stride rejection, FP16 readback, LSC/white protection order, and buffer
reuse after actual grade completion fds. Separate Vulkan grade/scaling/P010 passed
all four profiles, with and without the recorded-LUT shader. LUT uses a generated
16^3 affine map; SLOG3 output is checked against CPU trilinear application.
Required paths fail rather than skip. This is finite synthetic/CC0 testing; it
does not cover real camera imports, a camera session, codec output or sustained
recording. No GLES rendering, UI or recordings were used.

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

