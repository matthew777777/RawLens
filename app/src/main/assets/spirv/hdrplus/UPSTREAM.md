# Upstream provenance: RAWR merge_hdrplus

Vendored: 2026-10-06, full `merge_hdrplus/shaders/` directory (28 `.comp`
files) plus `merge_hdrplus/include/rawr/raw_merge_hdrplus_gpu/RawMergeHdrPlusGpu.h`
(the geometry/robustness header, vendored byte-identical at
`app/src/main/cpp/hdrplus/RawMergeHdrPlusGpu.h`).

- Source: https://github.com/adityawarmanfw/rawr
- Revision: f41e6c2c493cb2ebfe37ca7dcb98a6e38a5d40a5 (2026-10-06)
- License: GPL-3.0-only (compatible with RawLens GPL-3.0-or-later)

RAWR's own upstream note (from its `merge_hdrplus/UPSTREAM.md`):

> Source: https://github.com/martin-marek/hdr-plus-swift
> Revision: 69cb0572bb6712e160c448260125cb6099bdfd87 (2024-08-24).
> GPL-3.0; compatible with Rawr's GPL-3.0-only (see repository LICENSE).
>
> Ported to GLSL compute: the "Fast" (spatial-domain) merge path,
> `burstphoto/align/align.{swift,metal}`, `burstphoto/merge/spatial.{swift,metal}`
> and the texture helpers it uses (`blur_mosaic_texture`, `avg_pool`,
> `upsample_*`, `texture_mean`, `add_texture_weighted`). Matches the upstream
> exposure-control-off path (plain pyramid, no black/WB normalization), which
> is how uniform-exposure bursts are merged. Parity against the Metal original
> on RZSL bursts is ~80 dB PSNR (differences at integer-rounding level).

What RawLens ports 1:1 (shader-visible behavior bit-identical):

- 20 of the 28 `.comp` shaders, unmodified (this directory).
- `RawMergeHdrPlusGpu.h` geometry/robustness math, unmodified.
- The dispatch sequence, bindings, push constants, and barriers of RAWR's
  `pipeline/src/HdrPlusRecorder.cpp` (`recordReference`, per-companion
  prepare/align/merge, `recordFinalize`) and the 4-pass frequency loop of
  `AndroidBurstCoordinator::runHdrPlusFrequency`, re-expressed in
  `app/src/main/cpp/hdrplus/hdrplus_run.cpp` against RawLens's own compact
  Vulkan host (context/arena/executor in `hdrplus_host.*`).

Upstream-parity patches (RawLens, 2026-10-06): 8 shaders carry small
marked patches toward Burst Photo (see `app/src/main/cpp/hdrplus/PARITY.md`
for the proof). Pre-patch md5s — byte-identical to RAWR `f41e6c2` at
vendoring time; pristine copies re-fetchable from that commit:

- hdrp_upsample_align.comp: 6973896be26c7ecf4bc4851d4815bbca (OOB reads zero)
- hdrp_accumulate.comp: 6bddbde14bce0265f414d6f63e8190ad (OOB weight zero, divide by N, add-mode ref)
- hdrq_mismatch_norm.comp: 1fb28b4075fccc1ae963ba0eeb523609 (divide by N)
- hdrp_column_sum.comp: 340212c95275bdaf40a8f21b1fa1f791 (upstream sum order)
- hdrp_mean.comp: 6b5531359617ea69ba2743b46038cb83 (upstream sum order)
- hdrq_region_mean.comp: 2f59f9c494f66c2e59f70210128e43c6 (upstream sum order)
- hdrq_forward_dft.comp: 2f3e308c416a7de3da64ba08be353997 (twiddle association)
- hdrq_backward_dft.comp: 45b6fd8d2e154ee571514159cc9dba4f (twiddle association)

RawLens extension (step-4 strength maps, 2026-10-07 — intentional behavior
beyond upstream, not a parity fix; see `PARITY.md` "RawLens host extensions"):
2 shaders carry marked extension patches. Pre-patch md5s — byte-identical to
RAWR `f41e6c2` at vendoring time; pristine copies re-fetchable from that commit:

- hdrp_merge_weight.comp: a1dabc5564734437c8bbc697958e091e (per-block
  robustness-map binding + mapOffset push; negative offset keeps the
  upstream computation, verified <=1 DN @16-bit rescheduling dust vs the
  pre-patch shader on a 30-frame burst crop)
- hdrq_merge.comp: 5e69b7cfa0f9d704a87ef5a621e1f10e (per-block Wiener-norm
  triple map binding + mapOffset/origin/dims push; negative offset keeps
  the upstream computation)

Host deviations from RAWR toward Burst Photo (same proof doc): the HQ
tile-border pass is skipped (upstream-off returns early for unknown black),
and the spatial reference accumulates in frame order over a cleared
accumulator. `hdrq_border.comp` stays vendored + loaded for provenance but
is undispatched.

What is RawLens's own code (repository license, SPDX headers):

- `app/src/main/cpp/hdrplus/hdrplus_host.{h,cpp}` (instance/device/queue,
  SPIR-V module load, image/buffer allocation, descriptor/pipeline cache,
  one-shot submit helper),
- `app/src/main/cpp/hdrplus/hdrplus_run.cpp` (the 1:1 dispatch translation),
- `app/src/main/cpp/hdrplus/hdrplus_jni.cpp`,
- `HdrPlusVulkan.kt`, `HdrPlusMerge.kt`, `HdrPlusSettings.kt`,
  the ZSL shutter-path branch, and the quick-panel tile.
