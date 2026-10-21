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
