# Requirements-derived recording integration

2026-11-01. Work started at HEAD `28977494032f743742eafbbf77ff437317544554`,
branch `refactor/2.0`, with unrelated tracked and untracked edits preserved.
ADB found Xiaomi 25080RABDG `fe79feha9lmb6hhi`. Phone workload remains
uncontrolled; authorized headless runs are diagnostics.

## Implementation

The internal `requirementsInput` route connects the independently designed RAW16
cell representation to the persistent native engine and existing recorder.
Generated `requirements_pack` reads padded RAW byte staging (including odd byte
and pixel strides), writes two uint32 words per geometric Bayer cell, and never
writes a normalized FP32 frame. `requirements_rcd` decodes and normalizes those
cells on the GPU using immutable per-frame sensor black/white/crop metadata.
Reconstruction equations, canonical tile traversal compatibility and border
behavior come from the approved pinned RawTherapee baseline.

Intermediate scalar storage is 14 bytes per atlas site: VH 4, LP 2, PQ 2,
green 4 and missing opposite color 2. LP/PQ/missing values exist only at R/B
sites; known samples remain in the immutable packed input. Packing, five public
reconstruction passes and the existing separate product/half-output pass use
explicit compute barriers. A completion fence is awaited before host reads or
reuse. Allocations and recorded commands are reused for matching crop size,
CFA phase, RAW capacity and output mode. Black/white/origin/stride metadata is
uploaded per frame, not baked into a reused command buffer.

The adapter shares RAW metadata validation with the previous CPU normalization
path. This route omits CPU float normalization but still stages RAW bytes and
copies the final half output into the recorder's HardwareBuffer. The existing
separate grading/LUT/scaling/P010 and duplicated grade fence ownership continue
to protect that buffer. Unsupported Vulkan descriptor/range limits fail route
creation or rendering and retain the recorder's explicit MHC fallback.

This route is hidden behind validation flags. No still-photo algorithm or public
recording default was changed. It does not establish zero-copy camera import,
asynchronous overlap, performance acceptance, clean-room status or legal clearance.

## Validation

Results are recorded below after executing the fresh build and phone checks.
The earlier 3.73 ms figure remains an isolated packing measurement; it is not
this pipeline's render time or delivered recording cadence.

## Authorized geometry experiment

The user subsequently authorized reduced source geometry and possible equation
changes. A separate, explicit `reconstructionScale` diagnostic was implemented;
scale 1 remains the reference-compatible path. Scales greater than one average
same-color CFA samples over source-cell blocks before reconstruction. The crop
and field of view remain covered; sampling density and fine detail decrease.
Per-frame metadata records the scale, and the grading pass receives the actual
reconstruction dimensions rather than the original RAW crop dimensions. LSC
coordinates use the source-block center. Current interpolation equations remain
unchanged. This is approximate RCD, not full-source reference equivalence.

GPU integer averaging is checked against an independent CPU implementation at
scales 2, 3 and 4, four crop phases, and two odd sizes (24 cases). Final-half
comparisons allow 0.001 absolute error at the explicit binary16 boundary; the
observed maximum is 0.00048828125 for both these and 36 reference-mode adapter
cases. This budget does not replace the FP32 reconstruction oracle tolerance.
The phone test suite passed all five tests. See
`android-requirements-scaled-adapter/run.json` and `requirements-adapter.json`.

`approximate_quality.py` additionally compares oracle reconstruction of three
CC0 crops at scales 2/3/4 with full-source oracle output, using an explicitly
specified bilinear enlargement. The 9-case report is
`approximate-quality-diagnostic.json`: scale-4 linear-RGB PSNR ranges from
30.58 to 34.42 dB. These small reencoded crops and this diagnostic scaler are
not application-output or perceptual quality acceptance. No approximate-mode
quality threshold has been approved. Recording analysis separately records
cadence and marks reduced-geometry quality unaccepted.

## Measured recording results

| Output | Reconstruction | Delivered sensor fps | Median RCD wall ms |
| --- | --- | ---: | ---: |
| 1920×1080@30 | 3840×2160 | 0.606 | 1448.95 |
| 2560×1440@30 | 3840×2160 | 0.692 | 1637.73 |
| 3840×2160@24 | 3840×2160 | 0.554 | 1806.45 |
| 1920×1080@30 | 960×540 | 7.738 | 126.29 |
| 2560×1440@30 | 960×540 | 7.955 | 123.56 |
| 3840×2160@24 | 960×540 | 6.507 | 127.43 |

All six 30-second files fully decoded with Main10/audio, actual RCD frames and
zero MHC frames. Every mode failed cadence acceptance. Workload was uncontrolled;
none was a long thermal/cadence soak. Scaled quality is separately unaccepted.

The latest correctness retry passed all five tests, including 36 reference-mode
and 28 reduced-geometry adapter cases (scales 2/3/4/8). A transient ADB failure
is retained in `android-requirements-scale8-adapter/initial-adb-interruption.txt`.
Scale-8 recording was not started; the user redirected work to reviewing our
previous scheduling/encoder improvements. See `prior-recording-session-review.md`.

Current shared native baseline regression: 267 exact host comparisons. Two fresh
release APKs passed source/SPIR-V/license/JNI/retired-identifier inspection in
`requirements-scaled-package-validation.json`. The full JVM suite was not rerun
in this phase; these native, Android and package checks target the changed path.

Remaining: asynchronous native recording submission, supported GPU RGB handoff,
encoder-owned P010 delivery/content probes, reconstruction optimization, and
mandatory delivered-cadence/quality/thermal acceptance. Phone hardware is available;
these are unfinished engineering gates, not an external hardware blocker.
