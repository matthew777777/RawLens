# SkyKing Photon-Camera Bayer-direct merge reference

Added 2026-09-10 for Prompt 4 and future 4B–4D substeps.

## Checkout and provenance

- Repository: https://github.com/SkyKing0007/Photon-Camera
- Branch: `backup-26514-before-26515-short-bento-fix-20260820`
- Pinned commit: `e9855a3af7a79801a762ec3f99b441474926f009`
- Local checkout: `references/Photon-Camera-SkyKing/`
- Status: user-reported working implementation; accumulator/finalizer source
  inspected locally. Not independently built or device-qualified for RawLens.

Reproduce the reference:

```sh
git clone --depth 1 --single-branch --branch backup-26514-before-26515-short-bento-fix-20260820 https://github.com/SkyKing0007/Photon-Camera.git references/Photon-Camera-SkyKing
git -C references/Photon-Camera-SkyKing rev-parse HEAD
```

Verify the hash above before citing it: a branch can move. This is an ignored,
research-only checkout, not a RawLens build dependency. Preserve applicable
upstream licensing and record any copied/adapted code in RawLens's notices.

## Why this helps

This is a useful reference for Prompt 4's Bayer-direct merge, considerably closer
to our target than the current RGB-averaging prototype. The
[source accumulator](https://github.com/SkyKing0007/Photon-Camera/blob/e9855a3af7a79801a762ec3f99b441474926f009/app/src/main/assets/shaders/motionv2/direct_rgb_accumulate.glsl)
provides:

- Direct integer Bayer RAW sampling with per-phase black-level normalization.
- Subpixel flow converted from Bayer-quad coordinates into RAW pixels.
- A 3×3 sampling neighborhood weighted by anisotropic covariance/precision and
  robustness, with additional green-guided chroma weighting.
- Separate semantic-channel denominators and frame-support tracking.
- Preservation of previous accumulation when projected samples fall outside the
  image; out-of-bounds support diagnostics are updated separately.

## Crucial accumulator/finalizer contract

These accumulators are **not ordinary RGB**. They store **G, R−G, B−G** in a
white-balanced calculation space. The
[companion finalizer](https://github.com/SkyKing0007/Photon-Camera/blob/e9855a3af7a79801a762ec3f99b441474926f009/app/src/main/assets/shaders/motionv2/mfsr_finalize.glsl)
normalizes these independently, reconstructs RGB, optionally applies lens shading,
and removes white balance. Copying the accumulation shader alone would produce
incorrect colors. Initialization, guide production, white-balance conventions,
denominator meaning and finalization must be reviewed together.

## Adaptation decisions for RawLens

Adapt the RAW sampling, covariance weighting, separate denominators and support
tracking as appropriate. Do not copy the whole color contract implicitly:

1. Highlight reconstruction modifies clipped observations before fusion. Validate
   this independently for a minimally processed prime DNG.
2. Negative normalized samples are clamped, unlike RawLens's existing unclamped
   normalization; the finalizer also clamps negative sensor RGB.
3. Lens shading is applied after reconstruction. RawLens corrects each frame before
   accumulation, using its frozen metadata. Do not apply shading a second time.
4. Covariance, robustness and the green guide are supplied by other stages. The
   accumulator does not implement their producers.
5. This accumulator's output grid is native-size. A 24 MP Mosaic SR DNG still
   requires separate target-grid, same-CFA-color reconstruction—not re-mosaicing
   completed RGB.
6. Exposure scaling in upstream code does not change RawLens v1's same-exposure
   burst-planner contract.

Read the whole producer/initializer/accumulator/finalizer pipeline before adapting.
Relevant neighboring shaders under `app/src/main/assets/shaders/motionv2/` include
`direct_rgb_init.glsl`, `mfsr_chroma_guide.glsl`, `mfsr_bjzhou_guide.glsl`,
the `mfsr_bjzhou_rejection_*` stages and the alternate `direct_rgb_finalize_*`
shaders. These are source-reading leads, not interchangeable passes or a verified
dispatch graph. A literal-name search in the checkout's app host sources did not
locate dispatch sites for the cited pair; trace actual resource binding/dispatch
before integration.

Recommendation: use this checkout and its complete producer/finalizer pipeline as
an implementation reference for Prompts 4B–4D. It can inform RawLens substantially,
but does not replace RawLens's missing robustness integration, reference fallback,
memory management, target-grid reconstruction or DNG writer work. Keep the current
truthful capture fallback until the plan's validation gates pass.

## Original branch links supplied by the user

- [Accumulator](https://github.com/SkyKing0007/Photon-Camera/blob/backup-26514-before-26515-short-bento-fix-20260820/app/src/main/assets/shaders/motionv2/direct_rgb_accumulate.glsl)
- [Finalizer](https://github.com/SkyKing0007/Photon-Camera/blob/backup-26514-before-26515-short-bento-fix-20260820/app/src/main/assets/shaders/motionv2/mfsr_finalize.glsl)
