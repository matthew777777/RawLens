# RAWR Capture Sharpening port

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Pinned reference: https://github.com/adityawarmanfw/rawr at
`537852ec0e109f78fb4ed54bc6a692d9c7cfca92`.

The complete `native/capture_sharpening` module is vendored under
`app/src/main/cpp/capture_sharpening/capture_sharpening`: C++ implementation,
public API, eleven compute shaders and shared GLSL, native parameter defaults,
automatic radius and contrast, luminance/clip masks, Young–van Vliet blur,
Richardson–Lucy iterations, iteration limiting, corner offset, overlapping end
tiles, strip support, original reference tools and notices. Processing code
and shaders are byte-identical to the pinned RAWR checkout. The sole shared
Vulkan-helper adaptation replaces an untyped null in `std::exchange` with
`VkDevice{}` so the same code compiles on armeabi-v7a. It changes no arithmetic.

## Integration re-audit (2026-11-03)

Reference refreshed against current RAWR HEAD
`e7c844192008e1006d48ef85d931bf298e928192` (unchanged from the 2026-11-02
fetch). All 35 checked module/helper files still match, except the documented
non-arithmetic typed-null Vulkan adaptation. All eleven compiled shader
binaries match independently compiled RAWR binaries.

The enabled sharpening stage now uses Camera2 COLOR_CORRECTION_GAINS directly,
collapsing RGGB to RGB by averaging both greens. It no longer uses the final
color transform's calibrated WB. Missing/short gains default to unity, matching
RAWR FrameMetadataSnapshot; present invalid gains are rejected. The core
normalizes gain ratios internally as upstream does.

Original sensor clipping is now supplied where available. RAW_SENSOR inputs
are classified before LSC/WB/reconstruction at RAWR's 0.995 threshold, with
R/G1/G2/B bit labels and per-2x2 R16_UINT layout. The classifier honors buffer
position, row/pixel stride, crop origin, Bayer phase and per-site black levels;
it retains the GPU demosaic route without allocating a float CFA. CPU CFA
preprocessing also retains clip evidence before lens correction; CFA copies
preserve it through AI and highlight reconstruction. Bracket merge provenance
can supply the mask independently of HDR radiance. JNI validates mask capacity,
even dimensions and the Kotlin facade validates matching dimensions/phase.
The host uploads, transitions and binds the mask to unchanged RAWR shaders.

Sharpening defaults **off**, as explicitly requested by the user on 2026-11-03.
The enabled Low/Medium/Sharp/Sharpest presets remain RAWR's 2/3/4/6 iterations.
Existing saved preferences are respected. This off default intentionally
supersedes RAWR's default-on setting and the historical table below.

Fresh evidence: `results/capture-sharpening-rawr-parity-2026-11-03/`:

- 12 independently built core GPU cases: complete RGB bytes identical,
  including 4208×3120, all presets/CFA patterns and strip/band cases.
- 8 shipping C++ host bridge versus independent RAWR cases: complete RGB
  bytes identical (maximum error zero). Covers absent, clear, channel-specific
  and all-clipped sensor masks; non-unit green WB; all four presets/CFA patterns;
  repeated calls and image-size changes within one host context.
- Android application build and instrumented-test Kotlin compilation pass.
  Focused unit tests cover presets/default off, WB defaults/green averaging,
  sensor threshold/channel bits, crop/stride/phase, retained LSC evidence and
  HDR provenance, plus wavelet and unpack/preprocessing regressions.
- No phone was connected; no fresh Android GPU execution is claimed. Host
  bridge verification stubs only Android asset IO; processing C++ is unchanged.

Additional reproduction after running the core proof:

```sh
python tools/capture-sharpening-parity/prove_bridge.py \
  --reference /path/to/rawr --proof /tmp/rawlens-capsharp-proof
```

### Remaining surrounding-pipeline limits

These changes establish stage equality for identical input pixels, WB, phase,
clip evidence and parameters. They do not make all RawLens photo development
identical to RAWR. RawLens can reconstruct highlights in Bayer space **before**
sharpening; the checked RAWR live capture path sharpens before its downstream
highlight reconstruction. AI/demosaic/merge inputs can also differ. The auto
radius reads native image samples, so original clip evidence alone cannot
cancel differences in already-reconstructed input pixels.

Imported CFAs without retained sensor evidence, merged RGB/SR and merged GPU
textures still use RAWR's supported null-clip branch (0.95 native-sample
classification). This branch is not equivalent to RAWR capture paths that
supply an original sensor mask. No post-correction CFA is relabeled as original
sensor evidence. Quad-Bayer sharpening remains bypassed. Complete capture
parity therefore remains unproven for these surrounding paths.

## Settings

Settings → Denoise → Capture sharpening exposes the same settings as RAWR:

| Control | Values |
|---|---|
| Enabled | Off by default (user override, 2026-11-03); RAWR defaults on |
| Strength | Low: 2; Medium: 3; Sharp: 4; Sharpest: 6 iterations |

The native defaults remain auto radius on, radius 0.75, corner offset 0,
auto contrast on, contrast 10, automatic iteration limiting on. RAWR's UI does
not expose separate controls for those native parameters. Strength survives
disable/re-enable. Controls are frozen with capture settings, read by both
shutter and offline-merge preference readers, and included in complete settings
backup through the existing generic preferences serializer.

## Pipeline placement

Full-resolution demosaic → **Capture Sharpening** → optional profiled wavelet →
white balance/camera-to-ACEScg matrix → output rendering/encoding.

The stage receives pre-WB camera-linear RGBA16F, white = 1, and camera WB gains.
It converts to RawTherapee units internally using the unchanged RAWR code.
Output fusion is disabled when the camera-linear tap is needed. Bayer phase
includes the sensor/crop origin. The native host stages the complete buffer
and copies output to the JVM only after GPU completion. An enabled sharpening
failure propagates instead of silently saving an unsharpened photo.

JPEG and JPEG XL use this placement for ordinary captures, AI/CFA development,
merged CFAs and merged RGB/SR. The live merged-texture bridge reads in the
owning EGL context, develops the sharpened buffer on the photo context, and
restores the caller's context. DNG sensor/archive data is unaffected.

## Proof and reproduction

Evidence: `results/capture-sharpening-rawr-parity-2026-11-01/`.

- Source identity records SHA-256 hashes and the sole typed-null adaptation.
- Independent RAWR and RawLens builds emit identical SPIR-V and byte-identical
  complete RGB output in all 12 host GPU cases, including 4208×3120 at Sharpest.
- Cases cover every preset, every Bayer pattern, manual radii selecting all
  five kernel sizes, auto radius/contrast, iteration limiting on/off, corner
  boost, clipped highlights, partial tiles, forced strips and band submissions.
- A separate NumPy implementation checks the default RGGB case: mean absolute
  error 0.000122, p99 0.000479, maximum 0.0009761 in normalized RGB.
- On-device independent RAWR versus shipping RawLens JNI: 4/4 complete RGB
  outputs byte-identical on Xiaomi 25080RABDG, Mali-G615 MC2, across all
  presets and Bayer patterns. All maximum errors are zero.
- Two instrumented tests pass: native bridge cross-GPU golden comparison and
  exact disabled bypass; settings persistence; JPEG/JXL CFA, merged-CFA and
  merged-RGB/SR development on/off; live merged-texture context restoration.
- Existing wavelet regression suite: 10 tests pass.
- Full Android builds pass, including arm64-v8a and armeabi-v7a native code.
  Phone validation installs a separate test package, preserving the daily app.

Host proof (macOS, MoltenVK, NumPy, NDK glslc):

```sh
python tools/capture-sharpening-parity/prove_parity.py \
  --reference /path/to/rawr --out /tmp/rawlens-capsharp-proof \
  --glslc /path/to/ndk/shader-tools/darwin-x86_64/glslc
```

Build the separate phone test package, preserving the daily app:

```sh
./gradlew -PfreezeVersion -I tools/capture-sharpening-parity/isolated.init.gradle \
  :app:assembleGithubDeviceTest :app:assembleGithubDeviceTestAndroidTest
python tools/capture-sharpening-parity/prove_device.py \
  --reference /path/to/rawr --out /tmp/rawlens-capsharp-proof \
  --ndk /path/to/ndk --adb /path/to/adb \
  --apk /path/to/arm64-deviceTest.apk --test-apk /path/to/androidTest.apk
```

The device tool compiles the pinned RAWR implementation separately, installs
only `com.matthew.rawlens.capsharpprobe`, exercises shipping JNI and compares
all RGB samples with independent RAWR execution on the same phone GPU.

## Scope of parity

A subsequent [direct RawTherapee comparison](capture-sharpening-rawtherapee-comparison.md)
measures 14 cases and documents numerical/feature differences. Exact RAWR
parity must not be interpreted as exact RawTherapee parity.

Exact parity applies to this complete stage given identical RGBA16F input,
WB, Bayer phase and native parameters. Cross-vendor GPU equality and equality
of complete developed RAWR/RawLens photos are not claimed; their demosaicers,
highlight reconstruction and output rendering differ.

The initial port used RAWR's supported null-clip-state branch, which detects
clipped sites from native demosaiced samples at 0.95 of white. It does not bind
RAWR's optional separately retained pre-LSC sensor-clip image. With lens shading
or reconstructed highlights, that branch can choose a different clipping mask
from RAWR capture paths that bind the original sensor clip state.

Quad-Bayer development retains its existing separate path: RAWR's Bayer
native-sample auto-radius contract cannot be applied faithfully to a 4×4 CFA.
The settings and complete algorithm are ported; exact Quad-Bayer capture parity
is not established and sharpening is bypassed on that path.
