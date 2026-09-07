# AMaZE RawTherapee alignment fix

Reference: RawTherapee `dev/rtengine/amaze_demosaic_RT.cc`.

## Fixes retained/verified in the active shader tree

- `rbpm.glsl`: Bayer-phase-dependent `Dgrbsq1p/Dgrbsq1m` centers match the two RawTherapee branches. The second branch is centered at `indx`, not `indx+1`.
- `gcorr.glsl`: diagonal G correction is applied only at R/B sites. Measured G sites retain the CFA green instead of consuming R/B half-grid state, preventing alternating complementary-color zippering.
- `chroma.glsl`: RawTherapee diagonal pointer offsets are translated consistently: `p1=(+1,-1)`, `m1=(+1,+1)`, `p3=(+3,-3)`, `m3=(+3,+3)` in image `(x,y)` coordinates.

## Additional alignment in this fix

RawTherapee keeps CFA and all AMaZE working arrays as 32-bit `float`. The GPU port previously used `RGBA16F` for ten vector scratch textures, including the CFA copy carried in `grad.a`. That introduced a precision/range mismatch between passes. All AMaZE vector scratch images are now `RGBA32F`; scalar weights remain `R32F`, and the final scene/output texture remains `RGBA16F` for the existing downstream contract.

The texture-pool byte accounting and retained scratch budget were updated for the larger AMaZE working set.

## Validation performed

- Checked the active AMaZE pass graph and all image-format bindings for consistency.
- Compared the key Bayer-phase, diagonal-interpolation, G-correction and chroma-smoothing formulas against the current RawTherapee `dev` source.
- Gradle unit tests could not be re-run in the sandbox because the Gradle 9.3.0 distribution is not cached and outbound access to `services.gradle.org` is unavailable. Existing generated test results in the supplied repo were left untouched.


## 2026-09-03 periodic stripe / noise follow-up

Two remaining GPU-port defects were found from the supplied same-capture DNG/JPEG:

- `rbpm.glsl` under-filled the workgroup-local `delp/delm/Dgrbsq1*` half-grid on the right side. A right-most output can anchor an R/B site at `b+8`, while RawTherapee subsequently reads `(indx+m2)>>1` / `(indx+p2)>>1`, i.e. the half-grid slot at `s+2 = b+10`. Those slots were left as zero every 8-pixel workgroup. The staging range now includes `b+10`, matching the RawTherapee pointer arithmetic and removing the periodic diagonal-weight discontinuity that can show as magenta/yellow zipper bands.
- The seven-scale denoise pyramid was recursively stored in `RGBA16F`. Darktable performs its wavelet math in float; repeated fp16 roundoff in dark shadows turns tiny chroma coefficients into structured/blotchy noise. All denoise scratch images are now `RGBA32F`; only the AMaZE input and final scene-linear frame remain `RGBA16F`. The invalid `readonly writeonly` image qualifier on the accumulation image was also replaced by a legal read/write image declaration.

Reference checked against the current RawTherapee `dev/rtengine/amaze_demosaic_RT.cc`, especially the diagonal gradient / variance block (`delp`, `delm`, `Dgrbsq1p/m`) and the subsequent `m1/m2/p1/p2` half-grid accesses.
