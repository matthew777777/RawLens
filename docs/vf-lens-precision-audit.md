# RAW viewfinder lens-shading precision fix

The CPU sampler and Vulkan sampler both present through `VfGpuImport.cpuFragmentShader()`.
Their ESSL 1.00 lens function formerly computed sensor geometry and green-row parity
with the shader's default mediump precision. On FP16 implementations, odd sensor rows
above 2048 cannot be represented: 2049 becomes 2048. For a Gr site on the odd row of
a quad, this selects G-even instead of G-odd. Unequal green samples then produce an
incorrect merged green, which a color matrix can turn into colored shading. Lens UV
and quad selection also need enough precision to address the sensor consistently.

`vfLensGain` now uses highp geometry and a highp row for the ESSL 1.00 parity check.
The ESSL 3.00 function uses the same precision for geometry and retains integer parity.
The CPU fragment varying now carries the vertex shader's highp UV precision through
to the lens function. Lens gains, RGB tonemapping and the small RGBA16F map retain
their existing precision. There are still four Bayer reads (or one RGBA read), one
lens-map lookup, and one rendering pass; no neighborhood filter or full-frame copy.
Actual GPU timing has not been measured, so this is an operation-count assessment.

## Independent sources

These sources predate modern generative code tools:

- Android Open Source Project, copyright 2014,
  [LensShadingMap.java](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/android-30/+/refs/heads/androidx-activity-release/android/hardware/camera2/params/LensShadingMap.java),
  blob `38c1dd4404500532b4f7d23c2209eac54a0d6a5f`.
  `getGainFactor` documents R/G-even/G-odd/B order and indexes the interleaved
  array; `getGainFactorVector` reads those four channels separately. This supports
  retaining distinct even/odd green corrections, rather than disabling color shading.
- Khronos, [GLSL ES 1.00, revision 17 (2009)](https://registry.khronos.org/OpenGL/specs/es/2.0/GLSL_ES_Specification_1.00.pdf),
  sections 4.5.1–4.5.3 and 5.12: mediump is insufficient for pixel-exact sensor geometry.
  FP16's loss of odd integers above 2048 is reproduced numerically in the JVM test.

## Validation and limits

All 32 focused JVM tests passed, covering VF shader generation, the 2049 rounding
reproduction, green channel phase and calibrated color. The GPU regression passed on
the connected 25080RABDG / Android 16 device (2026-10-25). An instrumented test compiles and links both
complete production shader programs on a real ES3 context, then renders the production
ESSL 1.00 lens function with unequal green gains across sensor rows through 8190 and
both green phases. This isolates channel selection from tonemap quantization.

This fixes a demonstrated precision defect. Without a before/after scene comparison,
it does not establish that every reported fringe was caused by lens shading. The
existing four-site superpixel also samples R/G/B at different sensor positions; optical
chromatic aberration and high-contrast CFA aliasing are separate possible causes.
