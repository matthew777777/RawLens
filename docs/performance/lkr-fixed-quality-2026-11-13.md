# LK-R fixed-quality Mac performance, 2026-11-13

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Full-frame baselines and two implementation optimizations were tested on Apple
M1 through MoltenVK. The two workloads produce 12.48 MP from 16 frames and
49.94 MP from 24 frames. No vivo performance measurement is implied.

## Inputs and controls

[Input manifest and SHA-256 hashes](lkr-fixed-quality-2026-11-13/inputs.json)
freeze 16 distinct foliage RAWs from `IMG_20261112_153213_172` and 24 distinct
shoreline RAWs from `IMG_20261111_134639`. Duplicate exports were excluded.
These are locally available Xiaomi captures, not the vivo captures behind the
logcat files. Both input sets are 4080×3060 CFA frames, decoded as RAW16. The
second workload reconstructs 8160×6120 at scale 2, matching the kind of 50 MP
output in the logs; it is not a native 50 MP RAW-input benchmark.

All variants use eight solver iterations, three z-steps, the same motion-update
schedule, rejection/temporal settings, full frame set, 512-pixel tiles,
32-pixel overlap, guide off and no DCT prior. Both have 63 tiles. Workers are
fixed at three for 1× and two for 2×, matching the app's normal limits.
No early stopping, frame dropping, lower precision or resolution change was used.

The original executable/source were frozen before instrumentation. Adding
instrumentation preserved the full float reconstruction byte for byte. A
profile run per workload ranks costs; GPU timestamps are disabled for the
three alternating baseline/candidate timing pairs. Pair order alternates to
reduce order/temperature bias. No other benchmark or build was launched during
those paired runs. This is an ordinary desktop, not a controlled thermal lab.
Each replay creates new Vulkan contexts. Solver pipeline setup and additional
worker-context creation are included in merge time. Initial root-context
creation, context teardown, input DNG reading and output DNG/float writing are
outside TOTAL(merge), and included in separately recorded process wall time. Android's packed staging,
learned noise prior, preview load and OEM scheduling are not reproduced here.

## Throughput

| Workload | Baseline runs, ms | Candidate runs, ms | Baseline median | Candidate median | Reduction |
| --- | --- | --- | ---: | ---: | ---: |
| 12mp16 | 16739, 16432, 18561 | 16213, 15862, 16188 | 16.739s | 16.188s | 3.3% |
| 50mp24 | 62025, 68345, 83677 | 55127, 60545, 67650 | 68.345s | 60.545s | 11.4% |

The 1× gain is small. The 2× runs show a repeatable relative benefit in each pair, but large desktop timing drift; these are three-pair measurements, not a precise cross-device prediction. The last runs completed before work was stopped at the user’s request.


## Ranked costs

The profiler now enables timestamps for every tile worker and resolves shader
names before each worker's solver is destroyed. The previous report covered
only worker 1. The following table uses the separate profiled baseline runs,
not the no-timestamp throughput runs.

| Component | 12.48 MP / 16 | 49.94 MP / 24 |
| --- | ---: | ---: |
| Residual + weighted adjoint shader | 4,053 ms | 37,640 ms |
| Residual shader | 3,718 ms | 15,973 ms |
| Scatter shader | 1,010 ms | 10,471 ms |
| Global/local Jacobian shaders combined | 1,449 ms | 12,296 ms |
| Tile preparation, including local alignment | 9,998 ms | 18,658 ms |
| Of preparation: RAW tile load/normalization | 498 ms | 872 ms |
| Solver CPU initialization | 625 ms | 1,263 ms |
| CPU finishing | 759 ms | 3,074 ms |
| Other solver CPU work, including coverage/noise-scale readback | 1,734 ms | 4,240 ms |
| Output blending | 162 ms | 585 ms |
| Vulkan buffer allocation/API time | 4.2 ms | 1.3 ms |
| Pipeline creation | 120.6 ms | 28.4 ms |
| Descriptor set allocation/update | 1.8 ms | 1.9 ms |
| Queue submission API time | 263.3 ms | 369.5 ms |
| Fence wait wall time | 37,797 ms | 112,963 ms |
| Ordered blending/backpressure wait | 2 ms | 18 ms |

Worker times overlap: this is a ranking, not an additive wall-time budget.
Fence waits include GPU execution and queue scheduling; they must not be added
to shader time or described as pure synchronization overhead. Shader timestamp
spans can also include contention between worker queues. CPU preparation includes
its ordinary vector allocations; CPU heap allocations were not independently
intercepted. The explicitly timed allocation category is Vulkan buffer creation,
allocation, binding and mapping, not every allocation in the process.

The baseline makes 693 submissions, 9,576 shader dispatches and 882 buffer copies.
Requested GPU buffer allocation peaks sum to 792.8 MiB across the three 1× workers
and 928.6 MiB across the two 2× workers. These are requested buffer sizes, excluding
RAW inputs, output image, driver allocations and other app components.

Full all-worker tables and raw profile logs are in
[the evidence folder](lkr-fixed-quality-2026-11-13/).

## Implementation changes

1. `resid_adj_w.comp` retains the exact bilinear offsets/fractions calculated
   while evaluating each sample's forward residual. Its transpose reuses those
   taps instead of recomputing the affine/local warp. Integer atomic accumulation,
   rounding, sample validity and weights remain unchanged. There are at most
   four sub-samples at the supported 1×/2× scales.
2. With no CPU or DCT prior, `lkr_solver.cpp` records up to four iterations in
   one command buffer, preserving every dispatch, copy and memory barrier. It
   submits before coverage readback and at the final iteration. CPU/DCT-prior
   and trace paths keep their existing per-iteration submission behavior.

Submissions fall from 693 to 315 (54.5% fewer), while all 9,576 dispatches and
882 copies remain. No GPU buffers are added. In the separate 50 MP profile,
residual/adjoint shader time falls from 37,639.9 to 19,398.2 worker-summed ms.
The other stage times also vary with GPU contention, so that kernel comparison
is supporting evidence rather than the throughput claim.

## Output comparison

Every completed paired baseline and candidate replay produced the same full-image
float hash for its workload. The finite scan and pixel comparison report zero
changed samples, maximum absolute error 0, RMSE 0 and infinite PSNR. Consequently
detail, motion artifacts and noise are unchanged from baseline for both frozen
bursts. [Quality metrics](lkr-fixed-quality-2026-11-13/quality.json) retain the
full-image hashes and per-region measurements. Native-pixel comparison images
remain in `/tmp/rawlens-lkr-perf-20261113/quality-preliminary/`.


Comparison tooling scans every float sample for finiteness and error, computes
full-output hashes, and produces nine native-pixel before/after crops plus
per-region gradient/Laplacian statistics. These texture statistics mix detail
and noise; they are not an independent noise estimate without ground truth.
Byte identity is stronger evidence that this optimization preserves baseline
noise, detail and motion artifacts. It does not establish that the baseline is
artifact-free. Display crops share one baseline-derived exposure and a simple
camera-RGB transfer; no sharpening, denoising or resizing is applied to crops.

## Reproduction and validation

Benchmark tools:

* [Paired replay runner](../../tools/lkr-pipeline/host/lkr_perf_benchmark.py)
* [Float comparison and crop generator](../../tools/lkr-pipeline/host/lkr_perf_compare.py)

Local frozen artifacts, binaries, source snapshot, all logs and images:
`/tmp/rawlens-lkr-perf-20261113/`.

```sh
python3 tools/lkr-pipeline/host/lkr_perf_benchmark.py \
  --manifest docs/performance/lkr-fixed-quality-2026-11-13/inputs.json \
  --baseline /tmp/rawlens-lkr-perf-20261113/instrumented-baseline-run \
  --candidate /tmp/rawlens-lkr-perf-20261113/candidate-run \
  --output /tmp/rawlens-lkr-perf-replay --repeats 3
```

Native `lkr_robust_check` passes, including temporal rejection, explicit guide
research paths, zero model-error identity, DCT mode switching, shadow/chroma
and highlight checks. The GitHub Android debug
APK builds successfully with the shared changes. No device is connected;
Android GPU performance and thermal behavior remain unmeasured. The Mac result
still falls short of a few-second full-frame merge.
