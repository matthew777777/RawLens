# RAW viewfinder and ISP mobile GPU audit

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Research of the current working tree, 2026-11-14. No runtime code changed.
Existing uncommitted changes were included in the inspection and left intact.
The connected lapis phone was detected; the selected existing logcat tags had
no output. No fresh frame benchmark was run. All timings below are historical
repository evidence, not measurements of a new optimization.

## Findings and execution paths

### Legacy RAW viewfinder

`RawViewfinder.kt` uses Vulkan superpixel extraction, an RGBA export AHB,
then a GLES look and presentation. Input import has image/buffer/bridge/plane/
copy fallbacks. `vf_superpixel_linear.comp` writes normalized RGBA16F
R/Gr/Gb/B at sampled Bayer quads; lens shading and the look happen downstream.
The compute shader is already output sized, rather than a full sensor demosaic.
Its sampling uses one quad per output location with `u_step`; this is sparse
sampling, not an antialiasing reduction filter.

`VfPreviewMode.kt` already offers 480/640/960/1080 long edges. Saver caps at
640 and approximately 15 fps; recording caps this VF at 480 and approximately
10 fps. `RawViewfinder.updatePresentBufferSize` also limits the Surface buffer.
GL linear sampling is already configured on the ordinary preview textures.
Thus cheap upscale and reduced resolution are substantially present already.

Native VF compute calls wait on a fence before returning: see
`vf_vulkan_vf_viewfinder.inc`. Kotlin `computeMs` includes JNI, locking,
submission, queued GPU work and completion wait. GLES `toneMs` measures CPU
issuing commands, not isolated fragment GPU execution. Do not add these fields
as if they were independent GPU timestamps.

### BGU viewfinder

`BguViewfinder.kt` is a second architecture: sparse CPU low-resolution samples,
small look/fit, Vulkan guide, then GLES grid slicing. It already avoids doing
the whole look at every guide pixel. Its policy can raise the guide to the
surface edge capped at 1080 and its headroom boost can target 2160. A lower
user target alone therefore does not necessarily lower BGU GPU cost.
An economy policy should explicitly cap the guide and disable that boost.

### Encoded RAW video

`EncodedVideoRecorder.kt` selects fused MHC or public photo RCD, with
superpixel + grade-YUV as a fallback. Its comments describing MHC as one
dispatch are stale: `vf_vulkan_vf_mhc_stab.inc` dispatches
`vf_mhc_green.comp` over `cDims`, inserts a storage barrier, then dispatches
`vf_mhcyuv.comp` over `oDims`. Both are in one queue submission.

The green pass covers the full crop even when output is smaller. Green is
packed as two halves per uint. The second pass reconstructs colour differences,
grades and writes P010, with each invocation owning a 2x2 output cell. This
already removes the full RGB intermediate between demosaic and grading.
Reducing output dims alone does not reduce the green pass. Removing that pass
without replacing its calculation changes the coherent-green algorithm.

`photo_rcd_engine.cpp` is a separate, multi-stage engine with existing stage
timestamps and frame-global/lite/tiled routes. Old RCD timings must not be
attributed to this newer engine. It already batches stages with barriers in
a command buffer, so a generic recommendation to batch RCD would be redundant.

### Whole still / burst pipeline

There is no single all-Vulkan ISP. `RawDevelopmentCoordinator.kt` also uses
GLES AMaZE, GLES merged development, Vulkan highlight/denoise/merge components,
CPU preparations and output consumers. Backend transitions and copies need
to be profiled as well as shader arithmetic.

`tools/sr-pipeline/.../VulkanPipeline.kt` keeps substantial data resident and
uploads frames once, but the native common dispatch helper in
`srp_pipeline.cpp` submits and waits for a fence after each invocation. Keeping
buffers resident does not remove these CPU/GPU synchronization gaps. Batch
dependent dispatches into a command buffer with correct storage barriers;
wait only at actual host data dependencies. Preserve buffer ownership and
timeout protections. Savings need measurement; large compute-heavy stages
may have little relative submission overhead.

`Gles31MergedDevelopProcessor.kt` already combines cleanup and colour conversion
in one dispatch, reading RGBA32F and writing RGBA16F. Its logged total includes
the output consumer. Moving all still work to lower resolution would change
saved detail; use a separate preview/economy output policy rather than silently
changing full quality capture.

## Timing evidence

| Evidence | Recorded result | Interpretation |
|---|---|---|
| `docs/refactor/results/preview-quality-tradeoff-2026-10-30/branch-candidate-timing.txt` | CPU-inclusive draw 5.524 ms baseline; 5.869 ms shortcut; 4.052 ms capped at 723x1080 | Buffer cap won about 26.6%; shortcut alone lost. One historical test, not GPU-only timings. |
| `EncodedVideoRecorder.kt`, fused init comment | FP32 fused 33.1–34.1 ms; FP16 34.9–35.0 ms; MHC alone 26.4 vs 31.5 ms | Do not enable FP16 arithmetic globally on Mali. Historical kernel versions. |
| `vf_mhcyuv.comp`, shading comment | Manual shading loads about 7 ms versus about 0.5 ms hardware filtering | This optimization is already implemented. |
| `docs/refactor/vf-native-study.md` | Old four-stage 4K RCD about 235 ms | Historical retired-path warning, not current public RCD performance. |
| `output/cleanup-benchmark-20261114/phone/develop-timings.json` | Across 40 records per mode, median develop: off 63.07 ms, old 61.84 ms, wide 63.60 ms | No reliable end-to-end win from these medians; noise and consumer work obscure attribution. |

## Recommended experiments, in order

1. Add per-slot timestamp queries to VF and MHC: extraction, green,
   reconstruction+grade+pack, monitor and warp. Read completed slots without
   adding waits. Record CPU submit duration, completion latency, frame age,
   busy/dropped counts and present cadence separately. Honor timestamp support,
   valid-bit wrap and timestampPeriod. Reuse the photo RCD timing pattern.
2. Benchmark VF at 640/800/960/1080 internally with matching presentation caps.
   800 is an experiment, not currently a supported setting. Relative pixel
   work versus 1080 is approximately 35%/55%/79%/100%, before step rounding
   and fixed overhead. These are pixel ratios, not predicted time speedups.
   Start with hardware bilinear upscale; test a mild optional sharpening only
   if needed. Include BGU guide caps/boost in the policy.
3. Prototype a one-pass superpixel-to-P010 economy video kernel: preserve
   normalization, shading, WB/CCM, selected transfer function and 2x2 chroma
   averaging; remove the RGBA intermediate and extra submission of the current
   superpixel fallback. This deliberately uses binning rather than MHC and
   needs an explicit quality mode.
4. Prototype Bayer-aware reduction before coherent MHC: filter matching CFA
   phases separately into a smaller Bayer domain, then run BOTH green and
   reconstruction there. Half width/height gives a quarter of domain pixels,
   plus reduction/upscale cost. Never bilinear resize the interleaved RAW
   mosaic. Update CFA phase, shading coordinates, crop mapping and borders.
   At 4K output a smaller working domain loses detail: label it accordingly.
5. A/B exact MHC changes: interior fast path avoiding repeated mirror/clamp
   work; explicit reuse of packed RAW loads; workgroups 8x8, 16x8 and 16x16;
   shorter live temporaries. The green pass currently runs generic phase and
   boundary logic per tap. Consider specialization for CFA/output mode only
   after checking compiler output. These are hypotheses, not guaranteed wins.
6. Batch burst compute dispatches around host dependencies. Measure submission
   count and fence wait time before any arithmetic rewrite.

Do not assume fewer shaders is automatically faster. Recomputing green in a
single MHC pass may save a buffer but multiply neighborhood work and register
pressure. Shared-memory tiling also needs an A/B: Arm shared memory is backed
by cached system RAM, and barriers can constrain scheduling.

## Benchmark acceptance

Use a fixed RAW fixture for parity and repeatable GPU work, then a live moving
scene for cadence. Warm pipelines first; rotate candidate order; collect p50,
p95 and sustained 3–5 minute results with thermal state. Compare total GPU
frame cost, not only the rewritten stage. Test preview alone and during
recording, where the shared queue changes behavior. Energy improvement must
be measured separately; shorter elapsed time alone does not establish it.

Check fine text/foliage, moire, coloured edges, clipped glints, dark noise,
shading and WB continuity, crop/orientation and recording colour modes.
Exact rewrites should retain existing parity tolerances; reduced resolution
variants require visual comparisons and an explicit quality tradeoff.

## Primary references

- Vulkan timestamp queries and supported queues:
  https://docs.vulkan.org/spec/latest/chapters/queries.html
- Vulkan timestamp sample:
  https://docs.vulkan.org/samples/latest/samples/api/timestamp_queries/README.html
- Arm shader analysis, register pressure and dispatch sizing:
  https://support.arm.com/documentation/102693/2026-5/Analyze-your-frames/Analyze-shader-programs
- Arm GPU best practices (compute/shared memory):
  https://documentation-service.arm.com/static/67a62b17091bfc3e0a947695%3Ftoken%3D
