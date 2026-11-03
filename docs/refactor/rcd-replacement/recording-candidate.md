
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

# Main recording RCD candidate: preserved 200 ms photo implementation

Decision, 2026-11-01: prioritize the preserved photo implementation as the
candidate for the main recording RCD, following the user's explicit instruction.
The newer atlas implementation remains a reference and fallback experiment;
its measured approximately 470 ms frame cost is not an improvement over the
historical four-stage result. Promotion to the application default is pending
correctness, ownership and real recording validation, not merely compilation.

## Starting evidence

Branch `refactor/2.0`, HEAD `28977494032f743742eafbbf77ff437317544554`.
The existing dirty working tree, including unrelated camera/HDR/wavelet changes,
is preserved. No AGENTS.md was found under the workspace's parent directory.
An elevated `adb devices -l` returned no devices during this preparation.
Consequently there is no new phone timing, recording or Android correctness pass.

The preserved source is
`tools/rcd-baseline/fast-20261026/vf_rcd_measured_200ms.comp`, SHA-256
`79c7896d38d2d91d3034b7c94731475a27692d4f03d7ddc10b72153c20b015d8`.
Its README reports 200.7 ms at 4080×2294 on Xiaomi 25080RABDG/Mali-G615 MC2,
four stages 55.1/41.9/53.2/50.4 ms. That is historical demosaic evidence,
not a newly reproduced result or a complete recording time. The current recording
tests use a 3840×2160 crop of 4080×3060 RAW; these geometries must not be silently
equated when comparing timings. Required source detail/FOV remains unchanged.

`git log --follow` traces the candidate through `2e7e787`, `bf16f46` and the
pre-dispute photo introduction `a244814`. The existing audit records the hashes
of those photo revisions. This is evidence of a photo lineage, not complete proof
of the origin of every later edit. The candidate's header attributes RawTherapee
RCD 2.3, but does not specify its upstream revision. Pinned RawTherapee remains
the mathematical oracle; the candidate is a compatibility implementation, not
an alternative source of expected pixels. No archive was opened and no saved
binary was disassembled or used as an oracle.

## Prepared adaptation

`tools/photo-rcd/prepare_recording_candidate.py` checks the preserved source hash,
then emits a separately named Vulkan candidate and compiler/hash manifest.
It retains reconstruction expressions and epsilon constants. Explicit changes:

- FP32 arithmetic and RGBA32F intermediates; the historical RGBA16F storage is
  a later, separately measured numerical experiment.
- Immutable previous-pass RGB image at binding 3, separate from write-only
  output at binding 1. No invocation reads the RGB image being written during
  that dispatch. Whole-texel stores of unchanged components do not establish
  race freedom in the historical in-place version.
- Full-grid ownership for all four stages. Stage 1 initializes green pixels
  directly, including an unpaired final green pixel in odd-width crops;
  later stages explicitly carry untouched pixels forward.
- Periodic phase-preserving mirror for tiny dimensions. Canonical upstream
  borders remain distinct from this photo application's mirror extension.

These changes do **not** inherit the 200.7 ms measurement. Full-grid dispatch
and FP32 storage may initially cost more. The purpose is an uncomplicated,
race-free starting point before paired dispatch and storage optimizations.
The original source and saved binary are unchanged; generated outputs are
outside application assets and cannot silently ship as the default backend.

Reproduce compilation and SPIR-V validation:

```sh
python3 tools/photo-rcd/prepare_recording_candidate.py \
  --out /tmp/rawlens-200ms-recording-candidate
```

## Native integration contract

Four bindings: RAW SSBO 0, write-only output RGBA32F image 1, direction/low-pass
RGBA32F image 2, read-only previous RGBA32F image 3. Preserve the original 72-byte
push-constant layout: dimensions at 0, origin at 8, channel map at 16,
black levels at 32, inverse ranges at 48, pixel pitch at 64, mode at 68.
Stage specialization constant 0 selects 0/1/2/3. Every stage dispatches
`ceil(width/8) × ceil(height/8)` workgroups. Validate dimensions, crop bounds,
strides, byte ranges and numeric metadata on the host before dispatch.

Allocate two distinct RGB images A/B and direction image D. Stage 0 writes D;
stage 1 writes A; stage 2 reads A and writes B; stage 3 reads B and writes A.
Bind an initialized valid descriptor at 3 even for stages that do not read it.
Insert compute-write to compute-read barriers after each dependency. Neither
descriptor aliasing nor queue ordering alone replaces the memory dependency.
Retain RAW and per-frame parameters until completion; retain final A until
grading's completion fence signals. Reuse the existing separate grading/LUT,
scaling and P010 validation route and its failure handling. No implicit MHC
substitution may be counted as a successful RCD recording.

## Promotion and optimization order

1. Finish per-edit source attribution and record differences from pinned upstream
   and audited photo equations. Existing history/header evidence alone is
   insufficient to mark every later change approved.
2. Execute the prepared FP32 candidate against the independent host oracle and
   same-device baseline, all phases, borders, odd crops/sizes and licensed RAW
   fixtures. Record interior and mirror-border differences separately; do not
   reuse historical candidate expected pixels or silently widen tolerances.
3. Implement the four-binding native route above; run existing product, fence,
   invalid-input and recording tests on it. Only then select it as main RCD.
4. Reproduce a comparable four-stage phone benchmark, then measure the complete
   recording path. Optimize the largest measured stage first: specialized
   pipelines, shared CFA loads, race-free paired dispatch, register pressure,
   image traffic, direct RAW import, asynchronous ownership and output copies.
   Each change must pass its numerical and lifetime gate.
5. Optional FP16 storage/arithmetic or fusion requires an explicit numerical
   gate. Preserve full-source detail/FOV. Separate GPU timings from delivered
   frame cadence; PTS manipulation cannot pass.

Required recording gates remain 1920×1080/30 and 2560×1440/30 (33.3 ms nominal
frame period), and 3840×2160/24 (41.7 ms). Prefer the research report's headroom;
meeting a demosaic-only time is insufficient. All three sustained performance
gates remain failed/unestablished by previous runs. No claim of 33.3 ms, legal
clearance, release readiness or completion is made by this preparation.

## Continued implementation and phone validation

The phone reconnected for this continuation: Xiaomi 25080RABDG,
serial `fe79feha9lmb6hhi`. The initial absence above is historical.
The generated candidate now has a shared native-engine adapter, Kotlin/JNI
selection and an explicit headless recording argument `--recording-candidate`.
It remains an internal candidate rather than silently replacing public MHC.

The native adapter currently uses separate FP32 SSBO stages, with shared CFA
loads and canonical 194/176 tiles. This replaces the proposed four-image native
contract above for the current runnable baseline; images and paired dispatch
remain measured optimization candidates. Bindings are input CFA 0, packed
VH/LP/PQ vec4 guides 1, green RGB 4, opposite RGB 5, final RGB 6, product metadata
7 and final half output 8. The canonical engine's 16-byte push constants and
specialization constant 9 apply. Scratch buffers are tile atlases; final output
is full-resolution. Stages 0/1/2/3 dispatch 25×25 groups per tile, stage 4 applies
canonical border interpolation, stage 6 applies the existing validated separate
product corrections and half adapter. Compute barriers, synchronous completion,
frame reuse and grade-fence protection are retained from the validated engine.

The original direct candidate failed canonical comparison (maximum interior
0.79269). Correcting odd-column diagonal lookup reduced it to 0.00470; explicit
upstream scratch-domain and border adapters reduced it to 2.3841858e-7, with
zero border difference over 267 host fixtures. No tolerance was widened.
The initial and intermediate failure reports remain available. Four headless
phone tests passed, including the candidate's 123 canonical fixtures, product
corrections, profiles/LUT and grade ownership. Candidate phone maximum error
was 2.9802322e-7. See `android-recording-candidate/`.
Real 30-second recordings of all three requested modes are a separate gate;
passing these numerical tests makes no performance claim.

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
