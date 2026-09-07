# Darktable profiled wavelet chroma denoise port

The previous RawLens denoise implementation was removed and replaced with a fresh chroma-only wavelet path inspired directly by darktable `src/iop/denoiseprofile.c` (`wavelets: chroma only`).

## Pipeline

1. AMaZE demosaics to scene-linear RGB exactly as before.
2. RGB is transformed to darktable's Y0/U0/V0 basis:
   - Y0 = (R + G + B) / 3
   - U0 = (R - B) / 2
   - V0 = (R - 2G + B) / 4
3. Seven undecimated 5-tap a-trous wavelet scales use the `[1 4 6 4 1] / 16` kernel.
4. Y0 detail coefficients are passed through unchanged (the darktable chroma-only preset sets the Y0 curve to zero).
5. U0/V0 coefficients use noise-model-aware BayesShrink. The preset's 0.5 chroma curve maps to darktable's 8x threshold adjustment.
6. The final coarse residue is added back and the exact inverse Y0/U0/V0 transform reconstructs scene-linear RGB.

The UI now exposes only a master enable and `strength`, defaulting to **0.200** as requested. The old pre-demosaic stabilizer, chroma regression, WHT luma denoise, detail/edge controls, and denoise-linked film grain controls are no longer part of the active denoise implementation.

The implementation is tiled with a 128-pixel halo, matching the support radius of the seventh a-trous band, so neighboring tiles have the data required by the coarsest wavelet level.

## Validation

The Y0/U0/V0 forward and inverse matrices were numerically checked for round-trip identity. `git diff --check` passes. A Gradle build was attempted, but this sandbox did not already contain the required Gradle distribution and external network access is disabled, so the wrapper could not download Gradle 8.9.
