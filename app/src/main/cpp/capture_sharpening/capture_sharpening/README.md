# Capture sharpening

GPU port of RawTherapee's Capture Sharpening (`rtengine/capturesharpening.cc`,
`rt_algo.cc`, `boxblur.cc`, `gauss.cc` at RT `b2ee504`): Richardson-Lucy
deconvolution of luminance with a Gaussian PSF, gated by a contrast/clip blend
mask and applied to RGB as a luminance ratio. Auto radius (diagonal green
ratios) and auto contrast (flat-tile search) run on the GPU; nothing is read
back.

API: `include/capture_sharpening/CaptureSharpening.h`. Create from a
`rawr::vk::GpuContext`, then `record()` into the caller's command buffer. The
image is the demosaiced pre-white-balance camera RGB (RGBA16F, white = 1),
sharpened in place. Run it right after demosaic, before denoise and white
balance (RT's order). Native CFA samples must be intact (AMaZE, RCD, VNG and the
merge keep them): the radius and the fallback clip mask read them back.

## Passes

| Pass | Shader |
|---|---|
| Auto radius (max accepted green ratio, one atomic per workgroup) | `cs_radius` |
| Radius finalize, deconvolution dispatch sizes, contrast-search selects | `cs_select` |
| Auto contrast: 10x10 block stats, 80/40 px tile scans, refine, threshold | `cs_luma_blocks`, `cs_tile_stats`, `cs_contrast` |
| Blend mask: clip x contrast sigmoid, then RT's Young-van Vliet blur (rows on a transposed copy, columns) | `cs_blend_raw`, `cs_iir`, `cs_transpose` |
| Original luminance copy | `cs_yold` |
| Richardson-Lucy on 32x32 tiles in shared memory, applied in place | `cs_deconv` |
| Strip mode: saves originals an earlier strip has sharpened | `cs_apron` |

`cs_deconv` is specialized per kernel size (3/5/7/9/13); `cs_select` writes
indirect dispatch sizes so only the size matching the GPU-side radius runs.
Tiles follow RT's row-major order: the last tile of a row/column overlaps its
predecessors and blends over their result, so the deconvolution runs as four
phases (interior, row ends, bottom row, corner).

Viewfinder friendliness: `RecordInfo::deconvBandRows` with
`setTileBoundary()` lets the caller submit between bands of tile rows. Memory:
images above `maxStripPixels` (default 24 Mpx, ~150 MB of scratch) run in
horizontal strips; radius and contrast are still chosen over the whole frame
and the output matches a whole-frame run (50 MP: byte-identical in 3 strips).

## Deviations from RT

- Scalar float on the GPU instead of RT's float/double mix: ~1 ulp per value.
- The tile scratch ring is reset per tile; RT reuses it per OpenMP thread, which
  only changes results with a corner offset (and is schedule-dependent in RT).
- Lab L uses the exact formula instead of RT's 65536-entry LUT.
- The 0.95 clip threshold comes from the sensor clip state when bound.

## Validation

Tools in `tools/` (generated files go to `tmp/capture_sharpening/`):

1. `rt_harness/build.sh` compiles RT's functions verbatim (scalar, single
   thread, no FP contraction) into `cs_ref`; `parity.py` checks
   `capsharp_reference.py` (NumPy port) against it: bit-identical on 9
   synthetic cases covering every kernel size, auto radius/contrast, the
   iteration check, the corner offset and clipped highlights.
2. Host validator (`-DCAPTURE_SHARPENING_BUILD_TESTS=ON`, MoltenVK) plus
   `compare_gpu.py`: GPU vs NumPy on a DNG (decoded with the gain maps and RT's
   AMaZE from `rt_amaze/`) or a synthetic scene. 50 MP and 12.5 MP: radius and
   contrast exact, ~82 dB RGB, sharpening-delta correlation 0.99995+.
3. `run_dng.py` renders iteration-count comparison crops.

Device (V2562, Adreno 840, standalone validator, thermal status 0):

| Level | 12.5 MP | 50 MP |
|---|---|---|
| 3 iterations | 80 ms | 338 ms |
| 5 iterations | 105 ms | 451 ms |
| 10 iterations | 165 ms | 725 ms |

Tried and slower on Adreno 840: 8-row register blocks, skipping the zero
disc-corner taps through a specialization-constant branch, and reading the
original luminance from global memory to halve shared memory.
