# Native viewfinder (vf_vulkan_vf.cpp) split

`vf_vulkan_vf.cpp` (7888 lines) is one translation unit by design: its
first 3240 lines are an anonymous namespace holding the param layouts and
the global Vulkan `Context` every stage shares, and `dual_raw_vulkan.cpp`
compiles the whole module again via `#include "vf_vulkan_vf.cpp"`. Splitting
it into separately compiled .cpp files would give each its own copy of that
state (and of every `static`), so it is split into ordered `.inc` parts that
`vf_vulkan_vf.cpp` includes; the preprocessed source is unchanged.

| Part | Contents |
|---|---|
| vf_vulkan_vf_types.inc | includes, logging, push-constant layouts (static_assert-pinned), Context |
| vf_vulkan_vf_resources.inc | LUT, staging, bridge, AHardwareBuffer buffer/image imports |
| vf_vulkan_vf_pipelines.inc | compute pipeline creation for every stage |
| vf_vulkan_vf_shade_lut.inc | lens-shading images, stage teardown, grade LUT; closes the namespace |
| vf_vulkan_vf_viewfinder.inc | context lifecycle, RAW viewfinder superpixel dispatch |
| vf_vulkan_vf_grade_twins.inc | Phase-B grade stage, Direct-Log wait-free submit twins |
| vf_vulkan_vf_p010_preview.inc | true-10-bit P010 stage, record-preview monitor |
| vf_vulkan_vf_rcd.inc | RCD demosaic, fused RCD finalize + grade |
| vf_vulkan_vf_mhc_stab.inc | MHC demosaic, fused MHC + grade, stabilization warp |

Cuts sit between top-level items (each part starts at its item's comment
block); the file has no preprocessor conditionals and no __LINE__/__FILE__,
so concatenating the parts reproduces the original byte for byte (checked).

Proof: release arm64-v8a librawLensVfEgl.so and librawLensDualRaw.so built
before and after; `.text`, `.rodata`, `.data` and `.data.rel.ro` are
byte-identical in both libraries (298856 / 291400 bytes of .text). No
machine code changed, so no device run is needed for this step.

Not done here (behaviour changes, each needs its own study and device
parity run): de-duplicating the per-stage pipeline creators (createGrade /
Yuv / Preview / Rgb8 / Rcd / Mhc / Fused pipeline share most of their
descriptor-set boilerplate) and importP010Out-style long JNI bodies. The
V9 GPU wedge (docs/refactor/video-study.md) lives in this module's submit
path and is the first behaviour item to study.

## Algorithm check against references (2026-10-21)

Benchmark: Kodak 24 (r0k.us), sRGB-decoded to linear, RGGB mosaic; "hot"
= x4 exposure clipped at sensor white (glint stand-in). CPSNR on sRGB,
10 px border excluded. References: colour-demosaicing 0.2.5 (bilinear,
Malvar 2004, Menon 2007) and RawTherapee rcd_demosaic.cc (dev, RCD 2.3)
ported to a single-tile standalone (dbench/rcd_ref.cpp). RawLens CPU twins
via DemosaicBenchHarnessTest ($RAWLENS_DEMOSAIC_BENCH).

| Algorithm | CPSNR | CPSNR hot | HL chroma err | cyan % white HL |
|---|---|---|---|---|
| bilinear | 29.86 | 27.22 | 0.0252 | 2.83 |
| Malvar 2004 | 31.59 | 28.87 | 0.0185 | 0.91 |
| RawLens MHC (MhcReference) | 32.78 | 29.41 | 0.0186 | 0.81 |
| Menon 2007 | 33.63 | 30.60 | 0.0176 | 0.41 |
| RawTherapee RCD | 34.95 | 31.51 | 0.0166 | 0.47 |
| RawLens RCD (RcdReference) | 34.96 | 31.53 | 0.0165 | 0.41 |

- MHC fallback kernels equal Malvar-He-Cutler 2004 coefficients exactly;
  the edge-directed/bilateral additions are RawLens-own (no published
  source) but measure +1.2 dB over plain MHC and no worse in clipped
  highlights.
- RcdReference equals RawTherapee RCD (+0.01 dB).
- Device parity (GPU vs CPU twin): MHC mean 8.6e-5 max 9.8e-4; RCD mean
  1.1e-4 max 5.7e-3. Both shaders compute their references.

## Findings

- D1 RCD Direct-Log is unusable: 235 ms per 4K frame (dirs 55, green 51,
  opp 59, atG 69 ms) against a 33 ms budget; a 4 s take acquired 94 and
  graded 4 frames. Correct output, wrong performance; offered in the UI as
  a normal choice although VideoDemosaic's KDoc calls it A/B only.
- D2 (hypothesis, unconfirmed) MHC Direct-Log cyan on sun glints:
  vf_mhcyuv desaturates near-clip pixels to the WB neutral BEFORE the
  per-channel lens-shading gains, then the WB-folded CCM runs with no
  clip. A clipped glint therefore takes the colour of the shading map's
  channel imbalance at that position (cyan where the R gain is lowest).
  darktable/RawTherapee clip to the common white after per-channel gains.
  Needs a RAW sample of the scene (or the phone's shading map) to confirm.
- D3 RcdParityDeviceTest.rcdStillsRealData inits RCD without the grade
  stage (initRcdNative requires it) and needs /data/local/tmp/dng_win.bin:
  test bug, not app bug. The photo path (ensureRcdVulkan) inits in order.
- Photo RCD (JpegDemosaic.RCD) not yet exercised on device.
