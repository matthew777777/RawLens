# A5: BGU viewfinder source study (2026-10-26)

Status: **CLOSED 2026-10-26** after source/refactor checks, the encoded-guide
haze regression, 17 device tests, 78 host tests and a guarded smoke run of the
R8-minified 1.0.468 release. The owner requested closure after finishing these
checks. This audit covers A5 on the connected Xiaomi 25080RABDG; it does not
claim that every supported GPU or the whole application has been verified.

## Reference versions and algorithm choice

All upstream sources were read, not inferred from repository descriptions.
The checkouts used for this audit are temporary; reproducible permalinks:

- [Chen, Adams, Wadhwa and Hasinoff, 2016, Bilateral Guided Upsampling](https://people.csail.mit.edu/hasinoff/pubs/ChenEtAl16-bgu.pdf),
  particularly the fast approximation: fit local affine colour maps from
  blurred moment matrices, then interpolate and apply them.
- [google/bgu f2d6f2d1950b64790a2f712dc80b6c4bada410d5](https://github.com/google/bgu/tree/f2d6f2d1950b64790a2f712dc80b6c4bada410d5):
  [original fit_and_slice_3x4.cpp](https://github.com/google/bgu/blob/f2d6f2d1950b64790a2f712dc80b6c4bada410d5/src/halide/fit_and_slice_3x4.cpp),
  [later Generator](https://github.com/google/bgu/blob/f2d6f2d1950b64790a2f712dc80b6c4bada410d5/src/halide/fit_and_slice_affine_grid_halide.cpp),
  [GL slice](https://github.com/google/bgu/blob/f2d6f2d1950b64790a2f712dc80b6c4bada410d5/src/glsl/apply_local_curves.fs.glsl),
  and box_downsample_generator.cpp in the same Halide directory.
- [Halide 05e488b31085acea61fa4e3c65facc2b4a2019ee apps/bgu](https://github.com/halide/Halide/blob/05e488b31085acea61fa4e3c65facc2b4a2019ee/apps/bgu/bgu_generator.cpp)
  and [apps/bilateral_grid](https://github.com/halide/Halide/blob/05e488b31085acea61fa4e3c65facc2b4a2019ee/apps/bilateral_grid/bilateral_grid_generator.cpp).
  The former uses a different regularizer; the latter is a scalar bilateral
  filter, not an affine-model fit. Neither is a drop-in numerical oracle.
- [HDRNet e9a5bf59a232ee7562d02a6cf4dc5cef6191b6c6 slicing](https://github.com/google/hdrnet/blob/e9a5bf59a232ee7562d02a6cf4dc5cef6191b6c6/hdrnet/ops/bilateral_slice.cc):
  related learned-grid interpolation, with half-cell coordinates and a
  smoothed intensity weight. Its coordinates cannot replace BGU's fitted
  cell centres without changing the fit too.
- [OpenGL ES 3.0.6](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf),
  texture filtering and clamp-to-edge;
  [GLSL ES 3.00](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf),
  section 4.5.2 (precision and range).
- [Java memory model, JLS 17.4.5](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.5),
  [JNI array functions](https://docs.oracle.com/en/java/javase/21/docs/specs/jni/functions.html#array-operations),
  [Android LensShadingMap](https://developer.android.com/reference/android/hardware/camera2/params/LensShadingMap),
  [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics),
  [CaptureResult](https://developer.android.com/reference/android/hardware/camera2/CaptureResult),
  and [EGL_KHR_fence_sync](https://registry.khronos.org/EGL/extensions/KHR/EGL_KHR_fence_sync.txt)
  and [EGL_ANDROID_native_fence_sync](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_native_fence_sync.txt).

Important correction to an initial hypothesis: epsilon `1e-6` is **not**
a hallucinated constant. Google's original 2016 fit uses `lambda=1e-6` and
`epsilon=1e-6`; its later Generator uses `epsilon=0.1`. RawLens combines
original numerical parameters with the modern Generator structure. Keep
both original values for image parity. Provenance comments and NOTICE now
name both files.

## Coverage and decisions

This table covers the executable sections of every file named by A5,
including newly extracted owners. Platform glue and performance policy are
identified as RawLens adaptations; no claim that a research paper supplies
threading, UI policy, telemetry or camera-device thresholds.

| File / section | Authority and decision |
|---|---|
| `BguFit.kt`: grid indexing, dimensions, default bins and regularization | Original Google fit: 3x4 affine matrix, x-fastest planar coefficients; s=16, r=1/8, lambda=1e-6. Retain ceil spatial extents and inclusive upper intensity bin. |
| `BguFit.kt`: JNI facade, look buffers | RawLens allocation/availability adaptation; frame arrays stay fit-thread-local. Cross-thread grid buffers moved to `BguGridExchange`. |
| `bgu_fit.cpp`: Matrix, transpose, elimination, channel packing | Google fit; retain float Gauss-Jordan and ordering. Independent double/pivoted oracle checks observable predictions rather than asserting identical coefficients in degenerate cells. |
| `bgu_fit.cpp`: repeat-edge input, centred splat, luma, blur | Google fit: low pixel origin x*s-s/2; luma .25/.5/.25; nearest z bin; seven weights 1/(abs(d)+1)^3 along each axis. Do not normalize blur moments independently. |
| `bgu_fit.cpp`: gain prior and solve | Original 2016 Google equations: both epsilon and lambda scale by N+1. Blank bins tend toward identity; under-constrained populated bins toward a mean gain. Retained. |
| `bgu_fit.cpp`: scheduling | RawLens Halide 21 adaptation; GuardWithIf handles grids narrower than SIMD width. No numerical or schedule rewrite. |
| `BguSlice.kt`: shader | Google affine slicing plus explicit conversion from fitted cell coordinates to texture centres. Quad green merge, sRGB guide conditioning and output IGN are RawLens adaptations. Grid samplers and arithmetic now highp for signed/large coefficients. |
| `BguSlice.kt`: packGrid / floatToHalf | RawLens RGBA16F transfer layout; three textures, one per affine row. Existing saturating/NaN/flush policy retained, including half-up rounding (not ties-to-even). Tests pin it. |
| `BguSliceCpu.kt`, `bgu_slice_cpu.cpp` | Scalar/native twins of the GL contract: trilinear clamp-to-edge, affine application, optional final IGN; subsampling is a RawLens fallback policy. JNI size and finite-scale checks strengthened. |
| `BguLook.kt`, `halide_bgu_look_jni.cpp` | Planar quad/lens buffers, matrix transpose and fixed scalar parameter ABI; JNI checked before pinning. |
| `bgu_look.cpp`: guide, lens, RAW, JPEG, scheduling | Operator approximated by BGU, not part of the BGU paper. Authority is existing `VfGpuImport` look and `look_check.cpp` double oracle. Regression guide is now sRGB-conditioned; matrix/AgX/HL target arithmetic retained. Lens interpolation follows Camera2 map order. |
| `BguSample.kt`: sampleLowFrame / guideSharpness | RawLens point sampler, per-CFA-phase normalization and diagnostic gradient. Tests cover stride, sites and bounds. Point sampling deliberately retained; replacing it with box filtering changes the operator input and performance. |
| `BguGeometry.kt`: guide/crop/low geometry | RawLens Bayer-aligned sampling budget. Even steps, extents and origins retained; not a paper constant. Low factor eight follows the reference's low-resolution setup but sampling differs as noted. |
| `BguGeometry.kt`: UVs / CFA mapping | Android sensor orientation/CFA metadata and GL texture convention; existing rotation/mirror/site tests retained. |
| `BguSpike.kt`, `halide_bgu_jni.cpp`, `bgu_spike_downsample.cpp` | Google box-downsample adaptation with rounded u16 averages and repeat-edge remainder. This is a toolchain test; the live low-frame sampler is point-sampled. Corrected misleading documentation. JNI now validates the input length and products. |
| `BguViewfinder`: public setters, defaults and guide boost | RawLens performance/UI policy. Existing resolution, boost hysteresis, rate floors, timeout thresholds, metering and exposure smoothing retained. These are not reference-derived algorithm constants. |
| `BguViewfinder`: offer / queues / fitLoop | Camera2 no-retention boundary; sampled arrays now leased until fit/drop completes. Fits carry session generations. Grid publication uses explicit ownership rather than assumed upload time. |
| `BguViewfinder`: GPU guide ladder / probation | Existing sealed `VfVulkan` IMAGE -> BRIDGE -> PLANE -> COPY stages and black-output verdict; native Vulkan maths excluded from this A5 implementation change. Keep fallbacks and tier decisions. |
| `BguViewfinder`: CPU guide / export slots / fences | Existing `VfCpuNeon` contract; its copy facade already throws on a native error. New slot leases prevent producer reuse during render submission; unsignalled fences retain ownership and drop the frame instead of proceeding. |
| `BguViewfinder`: black/white levels and colour hold | Camera2 dynamic -> held -> static precedence retained for recording continuity. Calibrated colour cache and save-domain adaptive meter retained. |
| `BguViewfinder`: snapshotParams / snapshotLens / calibratedColor | Existing calibrated look authority, lens map layout and cache. Refresh params immediately on geometry/session changes. Compare every lens-map value; three sentinels were insufficient. |
| `BguViewfinder`: renderLoop / renderOne / teardown / surface lifecycle | RawLens SurfaceView/EGL lifecycle adaptation. Rendering uses the geometry belonging to the uploaded fit. Old-session publication/presentation cannot advance a new session's sequence. |
| `BguViewfinder`: stats, holds, watchdog and fence bookkeeping helpers | RawLens diagnostics and failure policy; existing tests retained. Dead CPU-slice/identity-grid scaffolding removed: the actual fallback is CPU guide plus GL slice. |
| `BguEglCore.kt` | Extracted ES3 display/context/window owner. Same EGL calls and configuration; no new device capability requirement. |
| `BguGlSlice.kt` | Extracted shader/textures/upload/present owner. Uniform grid dimensions and low-frame scale now come from uploaded fit; guide resizing cannot reinterpret an old grid as a new size. |
| `BguGuideEncoding.kt` | Standard sRGB OETF applied only to the regression input; independent anchors and monotonicity tests, frozen-RAW comparison and native/GL checks. |
| `BguGridExchange.kt` | RawLens ownership adaptation under JLS monitor rules. Three grids: published, consumer-held, writable; ownership swaps occur under a monitor, fit/upload outside. No steady-state grid allocation or copy. |
| `BguQuadPool.kt` | Bounded sampled-frame leases, released on fit completion, failure, dropped queue entries and session clearing. Prevents ring wrap overwriting a stalled fit. |
| `BguGuideSlots.kt` | CPU read/write leases for the three guide slots; GPU lifetime additionally requires the existing completion fd. Clearing invalidates sequences without revoking active leases. |
| `halide_bgu_fit_jni.cpp`, `bgu_jni_shape.h` | JNI/32-bit Halide buffer extent contract; reject short inputs, non-finite fit parameters and overflowing dimensions before creating descriptors. |

## Defects fixed

The encoded-guide image fix below was added after structural tests passed;
those earlier tests alone did **not** establish image parity.

1. Two fit arrays ping-ponged on the assumption that the renderer uploaded
   within one fit period. A stalled renderer violated that assumption.
2. Six low-frame arrays rotated by index, regardless of whether a fit still
   read them. Queue length alone did not bound an array's lifetime.
3. Guide slot sequence checks did not reserve a slot during read/submission.
   The producer could lap a stalled consumer. CPU and GPU guides now share
   explicit CPU ownership, with GPU completion still enforced by fences.
4. A timed-out fence was closed and the producer proceeded to overwrite the
   export. It now retains the fence and skips the frame.
5. A stale uploaded grid was addressed using dimensions from the current
   guide. Fit geometry now travels with the grid.
6. Cached low-frame lens coordinates survived geometry/session changes.
   Refresh those parameters when geometry changes and reset their cache at
   session invalidation.
7. Lens-map identity used three floats, ignoring changes elsewhere in the
   map. Full content comparison and a revision identity replace sentinels.
8. Low-precision grid samples had an insufficient guaranteed range for
   signed affine coefficients. Grid sampling and application now use highp.
9. Fit JNI checked output size but omitted both input lengths. Spike JNI
   omitted its input length. Shape products could overflow; all relevant
   BGU JNI bridges now validate bounded products.
10. An in-progress old-session fit/presentation could publish sequence
    state after reset. Jobs carry generations; publication and presentation
    commits check the generation against synchronized invalidation.

## Retained adaptations and limits

- `VfVulkan` and `VfCpuNeon` both load `rawLensVfEgl`. A missing library
  cannot be repaired by switching between them. The original missing-library
  failure policy is retained; runtime GPU initialization/import failures
  still use the existing CPU fallback.

- No global MATLAB optimization or HDRNet-trained guide was added. RawLens
  uses the original fast approximation; changing the method conflicts with
  this parity pass.
- The live guide is 8-bit normalized quads; low input is sparse point
  sampling, not Google's box-downsampled RGB. Both greens are averaged for
  the affine guide. Full demosaic/texture reconstruction is outside this
  model's abilities. Retained for image and performance compatibility.
- Spatial grids end at ceil(low/s)-1 and clamp at their edge; the infinite
  boundary-extended Google fit could evaluate extra cells. Our scalar and
  GL tests use the shipped finite-grid convention, not a claim of exact
  equality to Google's monolithic full-resolution evaluator.
- Display uses normalized guide texel centres. Native/scalar oracle and
  GL follow this convention. The original Halide full-image evaluator uses
  integer pixel origins; replacing one with the other creates a phase shift.
- The fit is an approximation to the low-resolution operator; an affine
  reconstruction test does not establish JPEG/save parity in every scene.
- Lens crossfade, AE feed-forward, exposure chase, boost thresholds and
  watchdogs are application policies, retained rather than assigned fake
  scientific provenance. Existing lens-glide behavior is nonlinear because
  each step starts from the previous emitted map; this was not retuned.
- GPU queue-family/import/fence implementation inside shared `VfVulkan` and
  `VfEglImport` remains the separately audited native VF scope. A CPU slot
  lease is not a substitute for a GPU fence.

## Reproducible validation

Host commands:

```sh
bash tools/halide/check_bgu.sh
./gradlew :app:testDebugUnitTest --tests 'com.matthew.rawlens.Bgu*' --console=plain
```

`check_bgu.sh` builds host filters into a temporary directory; it never
rewrites the checked-in Android archives. The fit generator's production specialization (`curve_alpha=0`) and
schedule retain the original math; its Android archives are unchanged.
`bgu_look` now encodes only the regression guide and has been regenerated
for all four ABIs. Its developed operator expressions remain unchanged.

- Original fit baseline: affine mean `5.35e-5`, max `0.00823`; curved target
  mean `0.00723`, max `0.0423`.
- New independent double-precision oracle directly sums weighted samples
  and uses pivoted elimination. Eighteen cases: 1x1, 7x5, 17x13, each with
  black, white, constant colour, a two-level edge, random RGB and exact half-bin
  boundaries. Maximum
  sliced prediction difference `4.44e-6`, below 0.25/255. Coefficient
  differences in unobserved/degenerate bins are logged, not mislabelled as
  visible errors.
- Downsample and look host gates pass. Existing look tolerance is guide
  1e-6 / developed 5e-4 against the independent double operator.
- Ownership regressions hold a grid or sampled frame across 100 producer
  iterations; resizing, bounded reuse, session invalidation and guide-slot
  exclusivity are exercised independently of camera timing.

Device: Xiaomi 25080RABDG (`lapis`), arm64. Baseline installed release APK
was preserved at `/tmp/rawlens-a5-installed-before.apk` before replacing it.
Baseline was explicitly switched to BGU using the existing control.
Baseline and final timing summaries, device suites and minified smoke
results follow in the final validation section below.

## Exclusions

HDR+, bracket merge, MFSR, AMaZE and highlight reconstruction/desaturation
were not rewritten. Existing RCD/MHC/native guide math, calibrated look,
AgX, sensor exposure and RAW/DNG writers were not retuned by A5. This study
does not promote an excluded algorithm to “proven parity”; AMaZE's existing
unproven status remains in D16.

## Owner image report and same-RAW failure

Owner 2026-10-26: both BGU GPU and BGU CPU look milky/hazy compared with
the direct GPU/CPU engines. A new opt-in
`BguImageParityInstrumentedTest` evaluates one actual Camera2 RAW through
the same high-resolution look operator and through its low-resolution BGU
fit. Guide values, metadata, look parameters and lens map are held identical.
This isolates approximation error from scene/exposure differences.

Initial sample (4080x3060 RAW, 1020x764 guide, s=16):

| Mode | Intensity bins | Mean absolute error | Signed dark-region lift (output < .25) |
|---|---:|---:|---:|
| RAW | 8 | .01721 | +.07692 (19.6 codes) |
| RAW | 16 | .01555 | +.05676 |
| RAW | 32 | .01462 | +.03612 |
| JPEG | 8 | .01641 | +.04393 (11.2 codes) |
| JPEG | 16 | .01471 | +.03033 |
| JPEG | 32 | .01367 | +.01614 |

Increasing grid depth helps but does not remove the shadow lift. Testing
the reference Generator's intensity-coordinate curve and fit-domain choices
on frozen operator pairs precedes any production numerical change.

## Encoded-guide image correction

Frozen pairs from a second real RAW frame reproduced the failure on the
host (original scalar replay agrees with the device to output quantization).
The input to the affine regression was linear sensor RGB, while its target
was display-encoded RGB. The rapidly changing dark-end slope consumes much
of an eight-bin fit. This is a conditioning/approximation failure, even though
the solver implements the original equations.

Alternatives were measured on identical operator pairs, not different captures:

| Eight-bin, s=16 variant | RAW mean error | RAW neutral-shadow signed bias | JPEG mean error | JPEG neutral-shadow signed bias |
|---|---:|---:|---:|---:|
| Original linear guide | .02129 | +.03351 | .02616 | +.07635 |
| Reference intensity curve alpha=.8 | .01556 | +.02023 | .01833 | +.05062 |
| sRGB-conditioned RGB guide | .01328 | +.00478 | .01528 | +.02763 |
| Encoded guide + intensity curve | .01313 | +.00535 | .01480 | +.02262 |

Neutral-shadow population: all developed channels <.25, channel spread <.05.
The original/conditioned RAW biases are 8.55/1.22 codes; JPEG 19.47/7.05 codes.
Fitting a linearized developed target and applying its transfer after slicing
was also tried; it retained substantial shadow lift and was not selected.
Increasing bins reduced lift but increased grid work and left residual errors.

Selected: standard sRGB OETF on the **regression guide**, after averaging the
two greens, in both the low-resolution Halide guide and the high-resolution
GL/native/scalar slice inputs. Developed targets, capture exposure, white/black
normalization, colour transforms and display encoding are unchanged. It is a
change of regression coordinates, not a new display gamma or contrast slider.
Eight intensity bins and spatial bin 16 remain unchanged.

This is a measured RawLens adaptation, not a claim that Google requires raw
sensor RGB to be sRGB-encoded. Google's examples fit image pairs; our RAW
guide has a materially different distribution. The standard OETF is already
used by the existing display operator. The original epsilon/lambda and the
linear intensity coordinate of the production fit remain unchanged.

`BguGuideEncoding.kt` holds the scalar definition, with black/toe/18%-grey/white
anchors and monotonicity tests. `bgu_look`, the GL slice, the native slicer and
its scalar oracle use the same transform. `BguFrozenImageInstrumentedTest`
requires a preserved linear-guide baseline and checks that both mean error
and neutral-shadow lift improve on the same frame; it does not accept a
different exposure as proof.

Replay (the directory must contain the original **linear-guide** pairs):

```sh
bash tools/halide/replay_bgu.sh /path/to/frozen/pairs /tmp/bgu-replay
```

`curve_alpha` is a host-probe generator specialization; its default zero keeps
the shipped fit's original coordinate. Do not combine a curved fit archive
with a linear-coordinate slice. The test images and RAW-derived binary pairs
stay in temporary/device storage, not in the repository.

**The encoded-guide correction passed device verification; A5 is closed.**
The correction substantially reduces lift; it does not make a coarse affine
model mathematically identical to direct nonlinear rendering in every pixel.

## Historical checkpoint before phone reconnection

- All 78 BGU JVM tests pass.
- Encoded-guide `bgu_look` passes its independent double host operator check
  and was rebuilt for arm64-v8a, armeabi-v7a, x86_64 and x86.
- Device-test APKs and the R8-minified release build successfully (1.0.466).
- Prior structural/linear-guide build passed 15 device checks, including the
  signed-coefficient GL oracle and GPU/CPU resize/resume. This is **not** a
  device pass for the subsequent encoded-guide correction.
- ADB disconnected before the encoded-guide APK install. Frozen-RAW regression,
  encoded GL/native slice parity, live frame-cost comparison and minified
  smoke are pending reconnection. No claim of final device image acceptance.
- The final candidate APK is
  `app/build/outputs/apk/release/RawLens-1.0.466-release-arm64-v8a.apk`.

Evidence is in `docs/refactor/results/a5-bgu/`; RAW-derived imagery stays
in temporary/device storage. HDR+, MFSR, AMaZE and HL remain excluded.

## Final validation and A5 closure (2026-10-26)

All gates below passed on Xiaomi 25080RABDG (`lapis`, arm64). The first UI
smoke attempt was interrupted when another app became foreground and was
not counted. The final attempt checks RawLens foreground before every input.

| Gate | Result |
|---|---|
| BGU host tests | 78 tests, zero failures; `jvm-final.txt` |
| Independent fit oracle | 18 cases, maximum sliced difference 4.44e-6 |
| Encoded look host oracle | Eight operator/lens cases pass; four Android ABI archives regenerated |
| Native/GL/image/live suite | 17 tests pass, including frozen RAW and new Camera2 capture |
| Production GL vs scalar | Maximum one 8-bit code in all four grid/signed-coefficient cases |
| GPU/CPU live switching | GPU 29.64 fps, CPU 30.09 fps; small/large CPU guides 30.05/30.18 fps |
| Power-save / resume | 11.75 fps at the existing saver cadence; GPU resume 29.08 fps |
| Minified release | 1.0.468 installed without clearing app data; guarded mode/lens/resume smoke passes |

Device frozen-RAW results (exactly the same input and look parameters):

| Metric | Original | Encoded guide | Reduction |
|---|---:|---:|---:|
| RAW neutral-shadow signed lift | .0335104 (8.55 codes) | .0047834 (1.22 codes) | 85.7% |
| JPEG neutral-shadow signed lift | .0763549 (19.47 codes) | .0276298 (7.05 codes) | 63.8% |
| RAW mean absolute error | .0213143 | .0133106 | 37.6% |
| JPEG mean absolute error | .0261819 | .0153079 | 41.5% |

The frozen test also checks populations (44,700 RAW / 19,224 JPEG channel
samples), mean-error improvement and at least halving shadow lift. It does
not use a new capture as the before/after proof. Images were inspected and
remain in temporary/device storage rather than the repository.

Release smoke: direct GPU was verified at startup; guarded switching then
covered CPU -> BGU -> BGU_CPU -> AUTO, 0.7x -> 1x lens changes (3264x2448 and
4080x3060 RAW), and background/resume while BGU_CPU was selected. The process
survived; no BGU fatal or AndroidRuntime exception occurred in the smoke
window. The BGU screenshot showed 30.0 fps; CPU telemetry showed 30.0 fps
before and after resume, with the resumed screenshot at 29.0 fps. AUTO was
restored. The minified release and its mapping archive remain in
`app/build/outputs/apk/release/` as `RawLens-1.0.468-release-arm64-v8a.apk`
and `RawLens-1.0.468-release-mapping.txt.gz`.

Performance scope: the original baseline's settled GPU preview was
29.0–30.2 fps; final settled preview remains in that range. Lens changes and
session startup had transient lower windows (for example 20.3 fps immediately
after returning to the main lens); these are not hidden or treated as steady
state. This is evidence of preserved live cadence on this phone, not proof
of identical power, latency or performance on every device. Release-wide
multi-device acceptance remains E22.

The coarse affine model still has residual error relative to direct nonlinear
rendering, including the quantified JPEG shadow lift above. Closure records
the completed reference audit and verified correction, not exact pixel
equality to the direct engine in all scenes. HDR+, MFSR, AMaZE and HL remain
excluded; no new parity claim is made for them.

Reproducible evidence: `results/a5-bgu/device-encoded-final.txt`,
`frozen-device-final.txt`, `device-encoded-telemetry.txt`, `jvm-final.txt`,
`release-minified-summary.json` and `release-minified-log.txt`.
