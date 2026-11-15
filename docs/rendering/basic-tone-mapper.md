# Basic / AgX photo tone mapping

Photo output offers a two-button **Basic | AgX** selector. The per-lens preference
`jpeg_tone_mapper` defaults to Basic when unset or unknown. Explicit saved Basic/AgX
choices are preserved.
The selected mode is part of the immutable shutter/development settings and is
also loaded for offline merges. Switching preserves the stored AgX tuning.

Basic follows the RAW display response in RapidRAW revision
`89ed2db5ee452f522f6952d3b1cc4821c5c082c3`,
[`shader.wgsl`, `display_transform`](https://github.com/CyberTimon/RapidRAW/blob/89ed2db5ee452f522f6952d3b1cc4821c5c082c3/src-tauri/src/shaders/shader.wgsl):

1. Convert calibrated ACEScg to the selected sRGB or Display P3 output primaries.
2. Clamp to [0, 1] and apply the sRGB transfer function.
3. Apply brightness gamma 1.1: `x = encoded^(1/1.1)`.
4. Blend 25% `x` with 75% `x*x*(3-2*x)`.

This implements the Basic display transform, not RapidRAW's separate exposure,
highlight/shadow, curves, or other editing controls. RawLens retains its own
calibration, metering, highlight reconstruction and sensor-white protection.
AgX look parameters and gamut compression do not alter Basic. Basic now has its
own optional ratio-preserving rational highlight shoulder, controlled by the shared
Highlight shoulder slider. A zero strength reproduces the original RapidRAW Basic
response. The default strength is one. Its meter keeps the sensor-domain sky guard
fixed and does not pre-compress meter samples, so the display shoulder cannot buy
additional exposure and cancel its highlight protection.

The curve runs inside the existing fused AMaZE/output pass and the existing RAW
viewfinder fragment tail. CPU/Vulkan/AHB preview routes share the same GLSL tail;
BGU uses the equivalent low-resolution Halide operator (mode 3), regenerated for
all four Android ABIs. No new full-resolution image, LUT texture, readback or GPU
pass is required. Ordinary SDR writes the Basic code value directly. Only gainmap
or PQ output decodes it to display-linear; Ultra HDR retains the existing scene
HDR tap. PQ adds scene energy above SDR white to the Basic base.

Scopes use the CPU Basic curve through the existing cached scope LUT. Native
scope code receives that LUT through its existing interface. Preview matching
covers the display response, exposure and color contract; viewfinder superpixels
and BGU approximation still differ from full-resolution AMaZE detail.

Validation sources: `BasicToneMapperTest`, `SceneLinearColorGpuInstrumentedTest`,
`PreviewTonemapEfficiencyDeviceTest.basicPreviewMatchesOutputCurve`, and
`tools/halide/look_check.cpp` (16 cases, including Basic/P3 with lens shading).

Validation on 2026-11-15:

- 53 focused JVM tests passed (Basic, JPEG settings, adaptive exposure and scope cache).
- The Halide scalar/AOT parity run passed all 16 cases; four Android ABI archives
  were regenerated.
- GitHub device-test APK and instrumentation APK built successfully.
- On the connected phone, 14 of 15 selected device tests passed, including Basic
  RAW preview/output parity, Basic fused/separate P3 output, CPU/GPU output parity
  for both mappers, shader compilation, and the BGU checks.
- That initial broader run found two failures outside the display transform.
  Their causes and fixes are described below.

The phone checks used `-PphotoRcdHeadless` (`com.matthew.rawlens.rcdcheck`), leaving
the normal camera package intact.

## Follow-up: PROGRAM AE and blue-channel identity

The PROGRAM AE startup expectation still used the legacy fixed 16% brightness
target. The profile now defaults to Natural tuning, which selects a 13% target
for the test scene and scales its histogram constraints accordingly. The loop
was applying that tuning correctly. The startup regression now covers Fixed,
Natural and Protect highlights, with neutral and +0.5 EV compensation, and checks
both the target and the applied ISO/shutter exposure.

The blue identity failure was a shared defringe bug, rather than a Bayer-phase
swap. Its edge detector computed `lo / max(hi, 1e-6)` from green extrema. A flat
zero-green blue or purple area therefore produced 0, which was interpreted as
maximum edge contrast and stripped its chroma. The detector now uses relative
range `(hi - lo) / max(hi, 1e-6)`, so flat regions produce zero contrast. The
existing 3x/7x edge thresholds are preserved above the numerical floor. This
changes the common cleanup used by AMaZE, fused output, RCD and merged RGB tails;
it adds no pass or texture.

New GPU regressions cover flat blue/purple patches with zero, near-zero and
positive green, and verify that genuine green-contrast edges still lose purple
fringes without losing green detail. The original all-Bayer-pattern identity
check remains enabled.

Follow-up validation (2026-11-15):

- Full GitHub debug JVM suite: 2,006 tests, zero failures, nine skipped
  (1,997 passed), across 287 suites. All five PROGRAM AE loop tests pass.
- The isolated GitHub device-test app and instrumentation APK build successfully.
- All 21 selected phone tests pass: scene-linear/CFA identity, shared chroma
  cleanup (including both new regressions), AMaZE highlight protection, Basic RAW
  preview/output parity, viewfinder shader precision and BGU look checks.
- Follow-up phone tests used `com.matthew.rawlens.tonecheck`, isolated via a
  temporary Gradle init script; the normal camera package was not replaced.

Both failures from the initial run are resolved. PROGRAM AE required correcting
its stale test expectation; blue-channel identity required the shared shader fix.

## Cloud headroom and accurate JPEG descriptions

The supplied `IMG_20261115_085914_296.dng` / `.jpg` pair exposed the hard clipping
boundary of Basic. In the sampled cloud ROI (oriented x=0..0.34, y=0.17..0.37),
none of the normalized sensor quads reaches sensor white; green peaks around
0.533. In the supplied JPEG, 36.45% of the ROI has every channel above 0.98.
Its legacy EXIF description incorrectly claims `AgX Base`, so the file alone
cannot establish which mapper was actually selected at capture.

Basic now rolls off the maximum output-primary channel above scene-linear 0.6:
`compressed = 0.6 + (peak - 0.6) / (1 + (peak - 0.6) / 0.4)`.
All RGB channels share `mix(1, compressed/peak, strength)`, preserving their ratios.
The knee is continuous with slope one; the full-strength curve approaches white
without hard-clipping ordinary scene highlights. Values whose maximum channel is
at or below 0.6 are unchanged. Shoulder zero bypasses the compression. This is a
RawLens highlight-handling extension before the original Basic display response,
not a claim that upstream RapidRAW uses this shoulder.

The same arithmetic runs in the CPU scope/reference transform, shared RAW
viewfinder tail, standalone/fused JPEG and JXL output, and low-resolution BGU
operator. No new texture or GPU pass is added. The sky guard remains in sensor
linear units and does not brighten the develop to undo the shoulder.

JPEG ImageDescription now has JPEG DEVELOPMENT, PARAMETERS, DEVELOPMENT SETTINGS,
optional TIMINGS, and Source sections. It records the selected mapper and active
curve controls, working/output space, output dimensions, encoding/quality, cleanup,
configured metering/highlight controls, and frozen source exposure/ISO/camera facts.
Existing HDR+/LK-SR/KB-SR merge records remain intact before that development block.
Unknown actual demosaic backends are not guessed: the preference is labeled as a
preference, including its fallback possibility. JPEG/R is reported only after a
successful HDR encode; otherwise the record explicitly states SDR fallback.
Existing saved files are not rewritten.

`BasicCloudDngDeviceTest` is an optional full-resolution device regression using an
external decoded sensor plane, not a bundled personal photograph. It compares
shoulder off/on with identical exposure settings and writes both renders and cloud
metrics. `JpegImageDescriptionTest` and the existing EXIF round-trip tests cover
mapper-specific fields, locale stability, merge provenance and HDR fallbacks.

Cloud/metadata validation (2026-11-15):

- Full JVM suite: 2,011 tests, zero failures, nine skipped (2,002 passed).
- Halide scalar/AOT checks: 16 cases passed, maximum absolute error 1.37e-5;
  all four Android ABI look archives regenerated.
- Isolated `.cloudcheck` app and instrumentation APK build successfully.
- All 16 selected phone tests passed, including the optional full-resolution DNG
  fixture, CPU/GPU output parity, fused/separate output, Basic preview at shoulder
  strengths zero and one, shader precision and BGU checks.
- Both DNG re-develops used +1.5 EV. Of 212,160 sampled cloud pixels, 35,234
  (16.61%) had every channel >=250 without the shoulder; zero did with it.
  141,111 cloud samples became darker by more than six summed RGB code values.
  Maximum sampled midtone difference was zero codes. This paired re-develop tests
  the new shoulder at identical exposure; the old JPEG lacks enough development
  metadata to reproduce all original capture settings exactly.
- The test JPEGs and metrics were pulled for visual/numeric verification. Personal
  source pixels and rendered photos are not added to the repository.
