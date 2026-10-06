# SR alignment bilinear 1:1 port plan (GPU front-end)

Goal: transcribe JAMY-L `alignment.py` / `block_matching.py` / `ICA.py` /
`utils_image.py` into the `rawsr/*` Vulkan passes in float32, BILINEAR
inter-level default everywhere. CPU donor: `RawSrAlignment` /
`RawSrCoreAlign` (verified 1:1 incl. per-size ICA quirks).

## Open Q3 decision: FFT grey = (a) CPU upload

`compute_grey_images(FFT)` is a full-frame FFT + spectrum mask + iFFT:
not tile-local, no clean compute-shader mapping, and the CPU
`RawSrAlignment.fftGrey` is already exact. No blocker found for upload:
host computes grey on CPU, uploads R32F (`uploadR32f`, like the guide
path), pads ref on GPU (below). No grey shader in the alignment path.

## Pass inventory (`tools/sr-vulkan/.../shaders/rawsr`, mirrored 1:1 under `app/...`)

Kept as-is: `bayer_quad_gray.glsl` (decimating-grey utility; alignment no
longer dispatches it — FFT grey replaces it).

Rewritten (same filenames, new uniforms/semantics):

- `pyramid_downsample.glsl` — valid-conv gaussian + strided take.
  Uniforms unchanged (`u_source,u_size,u_factor,u_axis,u_weights[17]`);
  reflection padding REMOVED. Output along pass axis: `floor((in-2r)/f)`,
  `r=int(2f+0.5)`. Host chains axis 0 then axis 1 (commutes exactly).
  `u_factor` 1 never dispatches (host-side identity).
- `block_match.glsl` — L1 SAD zero-fill (seed half-away, integer out) /
  L2 direct-SSD edge-clamped (seed half-even, winner adds onto the
  fractional seed). First-minimum scan, no validity gating.
  Uniforms: `u_reference,u_moving,u_seed_flow; u_mov_size,u_tile_grid,`
  `u_tile_size,u_search_radius,u_l1`. `u_scale`/`u_has_initial_flow`/
  `u_min_fraction`/`u_previous_grid` are GONE (see below). Out: z =
  mean cost/area, w = 1.
- `lk_refine.glsl` — ICA: unhalved zero-padded gradients, full-tile
  Hessian, `|det|<1e-10` keeps seed; tile-8 clamped sampler +
  tree-clipped partials (unclipped step); 16/32/64 zero-fill + step clip
  +-radius (64 with the clip disabled at 32767). The old CUDA 64-kernel
  sliding window (bottom row read floor+2) is RETIRED on CPU and GPU:
  the current reference `cpu_ica` has no 64 path (parent-verified
  2026-10-04; the `ica_ts64_*` goldens were regenerated from it).
  Uniforms: `u_reference,u_moving,u_flow; u_ref_size,u_mov_size,`
  `u_tile_grid,u_tile_size,u_search_radius,u_iterations`. Out: z =
  mean-abs residual, w = det verdict. Downstream consumes xy only.

New:

- `circular_pad.glsl` — `out[p]=src[p mod srcSize]`; ref grey only.
  Uniforms: `u_source,u_size` (padded size).
- `flow_upscale.glsl` — torch interpolate align_corners=False +
  xfactor + zero-pad/crop. Uniforms: `u_prior,u_src_grid,u_dst_grid,`
  `u_repeat,u_factor,u_mode` (0 nearest, 1 bilinear default, 2 bicubic
  Keys a=-0.75). Out: z = 0, w = 1.

Retired: `flow_consistency.glsl` DELETED from both shader trees and
from `app/.../spirv` (spv/staged/vk + manifest). The reference has no
consistency pass; the host comment already anticipated removal.

## Intended host call shape (host child owns `VkRawSrProcessor.kt`)

```
greyRef = fftGrey(mosaicRef); upload each moving grey R32F
refPadded = circular_pad(upload(greyRef))          # to tile multiple
refPyr = [refPadded, down(refPadded,2), down(.,4), down(.,4)]  # valid-conv
movPyr = same from the UNPADDED moving grey
flow = zeros(coarsestGrid)                          # upload zeros; no OOB factorAt
for level in 3..0:                                  # coarse -> fine
    ts = tileSizeAt(level); grid = floor(refW/ts) x floor(refH/ts)  # floor, not ceilDiv
    if level < 3:
        f = FACTORS[level+1]; rep = f / (ts / tileSizeAt(level+1))
        flow = flow_upscale(flow, srcGrid, grid, rep, f, mode=BILINEAR)
    seed = block_match(ref, mov, flow, ts, radiusAt(level), l1=(level==0))
    flow = lk_refine(ref, mov, seed, ts, radiusAt(level), iterations=3)
```

`u_scale` semantics: the uniform no longer exists. The seed always
arrives pre-upscaled at the current grid; the coarsest level uploads
zeros, so `factorAt(level+1)` is never read out of range by construction.

Sizing: axis-0 pass `W,H -> floor((W-2r)/f),H`; axis-1 `-> floor((W-2r)/f),
floor((H-2r)/f)`. Weights: `gaussianKernel1d(f)` as today (17 taps max).

## Fallout for the host/tests children (not this stage)

- `RawSrAlignmentConfig` default NEAREST -> BILINEAR; drop the
  `require(NEAREST)` + `tileSize<=32` cap (shaders support 64 now).
- Grid `ceilDiv` -> `floor`; moving/ref levels differ in size (padded
  ref vs unpadded moving) — pass both sizes.
- `ShaderStagingTest` count 22 -> 23 (rawsr 21->22: -consistency
  +pad +upscale; raw 1). Sliced-preprocessing pin still holds.
- `RawCameraController` SR warmup list: add `circular_pad`,
  `flow_upscale`; drop `flow_consistency`.
- SPIR-V: regenerated with `tools/srvk_shader_transform.py`
  (glslang 16.6.0, `--target-env vulkan1.2`); shaders stay `#version
  450`-staged ESSL, no extensions/subgroups/float16.

## Tolerances

float32 GPU vs double CPU: same formulas, ~1e-6 agreement expected;
flat-area argmin ties may flip sides (either minimum is valid — test
on textured fixtures). Bitwise vs torch/CUDA is out of scope.
