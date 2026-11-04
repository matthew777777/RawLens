# Android FFDNet benchmarks

Standalone Android arm64 probe; does not change or install the app. Run from the
repository with `python3 tools/ffdnet-probe/run.py`. Requires Android NDK, ADB,
and the repository's vendored ncnn archives. `ANDROID_HOME` overrides the SDK
location. Options: `--runs 7 --tiles 128 256 512 --output /tmp/rawlens-ffdnet-benchmark`.

## Workload

Deterministic seed 20261104, dense random weights, 15 convolutions with 3x3
kernels, 5 input channels, 64 hidden channels, 4 output channels; ReLU after
all but the final convolution. This models the inference convolution core of
grayscale FFDNet: four packed image channels plus one noise channel. Batch
normalization is assumed folded into the convolution constants at inference.
There are 485,316 parameters, 1,941,264 bytes of FP32 constants (~1.85 MiB), or
~0.93 MiB at FP16. Original-resolution tile sizes 128/256/512 correspond to
64/128/256 convolution grids. The log's `core` field means that packed grid
side, not the usable interior after overlap.

All backends receive identical weights and image data (explicit OIHW/ OHWI and
CHW/ NHWC conversion). NNAPI constant buffers remain alive through execution.
Two warmup executions are excluded, then seven measured runs. Timings are
synchronous, include backend input/output submission and completed inference,
and exclude graph setup/compilation. There is no Android reference CPU fallback
for NNAPI: compilation explicitly targets one device and requires support for
all 15 operations. This establishes execution through that accelerator driver;
it does not expose the vendor driver's internal scheduling.

CPU: ncnn, four threads, FP32 or FP16 storage/arithmetic. GPU: ncnn Vulkan,
FP16 storage/arithmetic, subgroup operations disabled. NPU: native NNAPI,
FP32 tensors with FP16 relaxation, sustained-speed preference. This probes the
existing working driver route, **not LiteRT**. CPU/GPU differences include
runtime/compiler implementation differences, not just processor hardware.

This is a compute/load simulation, not a trained denoiser. It excludes GAT,
pixel packing/unpacking, tile assembly, sensor calibration, and plug-and-play
reconstruction steps. The model accepts prepacked inputs and produces packed
outputs. Random-weight numerical agreement does not establish real denoising
quality. It simulates one denoiser pass; iterative PnP repeatedly incurs that
cost. Results do not represent color FFDNet, DRUNet, or KernelNet performance.

## Device results (2026-11-04)

ADB: Xiaomi 25080RABDG / lapis, MediaTek MT6878, Mali-G615 MC2, Android 16,
7,588,424 KiB total memory. NNAPI drivers version 7.2.4. Thermal status was 0
before each measured backend, with no manual clock/performance overrides.
Measurements were sequential on a normally running phone, not a controlled
sustained-thermal study. Seven warm-run medians in milliseconds:

| Backend | 128 tile | 256 tile | 512 tile |
| --- | ---: | ---: | ---: |
| CPU FP32, 4 threads | 51.829 | 216.945 | 840.098 |
| CPU FP16, 4 threads | 25.634 | 106.510 | 402.746 |
| GPU Vulkan FP16 | 22.121 | 93.909 | 333.107 |
| NPU mtk-mdla_shim | 5.066 | 16.249 | 58.424 |
| NPU mtk-neuron_shim | 7.571 | 20.289 | 68.296 |

MDLA supported all 15 operations. For 512 tiles its seven measured runs ranged
58.074–65.511 ms; compilation took 712 ms, separately. Maximum absolute error
against ncnn CPU FP32 was 0.003538 and RMSE 0.000767. GPU RMSE was 0.004081;
CPU FP16 RMSE was 0.018244. These are random-network output errors, not quality
scores or errors on a trained FFDNet image.

At 512 tiles MDLA is 5.70x faster than this GPU path, 6.89x faster than CPU
FP16, and 14.38x faster than CPU FP32.

A rough 4080x3060 single-plane projection with conservative 30-original-pixel
halos on each side leaves 452x452 interiors: 10x7=70 tiles. Multiplying the
measured 512-tile median gives ~4.09 s MDLA versus ~23.32 s GPU, excluding
packing, GAT, transfers outside the benchmark, tile assembly and setup. This is
an extrapolation, not a measured full-resolution result. Separate processing
of four Bayer phase planes and iterative PnP need their own workload counts;
this number must not be presented as an end-to-end RAW pipeline benchmark.

Raw samples and numerical comparisons are in `results/raw.log`, machine-readable
medians in `results/results.json`, and thermal states in
`results/thermal-status.json`. The runner stores per-backend full thermal dumps
and its binary under the selected output directory. The binary is large because
it statically includes the existing ncnn/Vulkan shader compiler; this executable
size is not the neural-network weight size.


## Pretrained color FFDNet / PnP denoiser (2026-11-04)

The second run uses the **actual `ffdnet_color.pth` checkpoint used by the
existing `python/lkr` plug-and-play prior**, not random weights. Found locally
in the experiment's scratchpad; the network layout is independently documented
by [KAIR](https://github.com/cszn/KAIR/blob/master/models/network_ffdnet.py).
The author publishes the checkpoint at
[KAIR releases](https://github.com/cszn/KAIR/releases/download/v1.0/ffdnet_color.pth).
Checkpoint SHA-256:
`286f04f7e3a20254c067ba95efb5d3339f4fc8a09a365a93cd469184d4cdb279`.

Architecture: 12 convolutions, 96 hidden channels, 13 packed input channels
(12 RGB subimage channels plus sigma), 12 output channels. 852,108 parameters,
3,408,432 bytes of FP32 constants; original checkpoint 3,411,551 bytes.
No training, weight pruning, or integer quantization was performed.

The input is a real linear RGB reconstruction crop from the existing roof
experiment. `prepare_pretrained.py` applies the same GAT preparation formula as
`Prior._gat_net` with **illustrative** a=0.002, b=0.00002, WB=[1.9,1,1.5],
sigma/sig_ref=1. These are not a calibrated noise profile for that photograph.
The resulting normalized image and noise map are packed exactly as the local
FFDNet implementation expects. PyTorch inference generates independent reference
outputs; Android results are pulled back and compared against them before and
after pixel shuffle / the local inverse-GAT formula. This tests model conversion
and numerical agreement, not improvement against ground truth.

Same seven-run methodology and explicit-device NNAPI selection as the synthetic
run. Every NPU compilation accepted all **12/12** operations.

| Backend | 128 tile | 256 tile | 512 tile |
| --- | ---: | ---: | ---: |
| CPU FP32, 4 threads | 89.004 ms | 322.585 ms | 1284.892 ms |
| CPU FP16, 4 threads | 41.890 ms | 161.544 ms | 702.445 ms |
| GPU Vulkan FP16 | 35.874 ms | 129.731 ms | 493.110 ms |
| NPU mtk-mdla_shim | 8.031 ms | 27.484 ms | 104.150 ms |
| NPU mtk-neuron_shim | 7.838 ms | 30.037 ms | 106.738 ms |

512-tile MDLA is 4.73x faster than this GPU path and 6.74x faster than CPU FP16.
MDLA's seven 512-tile runs were 104.061–108.679 ms; compilation was 755 ms,
separately. Thermal status was 0 before all backend runs.

Independent PyTorch validation for the 512 tile:

| Backend | Normalized max error | Normalized RMSE | Linear RMSE after inverse GAT |
| --- | ---: | ---: | ---: |
| CPU FP32 | 0.00000161 | 0.000000259 | 0.000000144 |
| CPU FP16 | 0.009098 | 0.001356 | 0.000761 |
| GPU FP16 | 0.007669 | 0.001668 | 0.000947 |
| MDLA / Neuron relaxed FP16 | 0.000831 | 0.000283 | 0.000150 |

The MDLA normalized agreement is ~70.96 dB relative to PyTorch, not a denoising
PSNR score. Real-weight numerics differ substantially from the random-weight
simulation, so do not use the earlier errors as a quality prediction.

These timings measure **one real PnP denoiser call**, excluding GAT, packing,
unpacking, inverse GAT and reconstruction/motion/data-fidelity steps. They do
not time an entire iterative PnP solve. Sixteen such calls on one 512 tile would
alone total ~1.67 s on MDLA (arithmetic projection); a full-resolution image
requires many tiles. No final RawLens pipeline or LiteRT integration was made.

Reproduce with a PyTorch + NumPy Python environment (exporter imports the existing
local `python/lkr/drunet.py`; no edits to that implementation are needed):

```sh
python3 tools/ffdnet-probe/prepare_pretrained.py --weights /path/ffdnet_color.pth --image /path/linear-rgb-chw.npy --output /tmp/ffdnet-prepared
python3 tools/ffdnet-probe/run.py --prepared /tmp/ffdnet-prepared --output /tmp/ffdnet-trained-results
python3 tools/ffdnet-probe/validate_pretrained.py --prepared /tmp/ffdnet-prepared --results /tmp/ffdnet-trained-results
```

`--image` accepts finite RGB CHW `.npy` arrays. Preparation uses upper-left crops
(or resizes when smaller than the tile); each crop gets its own GAT range.
This is not a full-image overlap/seam test. For exact input identity, see the
image checksum and preparation parameters in `results-pretrained/manifest.json`.
Source images, original checkpoints and binary tensors are kept outside the
repository. Exported references/inputs are under
`/tmp/rawlens-ffdnet-trained-inputs`, and full results/binary outputs under
`/tmp/rawlens-ffdnet-trained-benchmark`. Compact logs, medians and validation
metrics are preserved in `results-pretrained/`.

## LiteRT MediaTek backend and 1024 tiles (2026-11-04)

Tested the same checkpoint and GAT inputs through **LiteRT 2.3.2 CompiledModel**
with `litert-npu-runtime-mediatek:2.3.2`. C SDK headers are from the compatible
2.3.0 release. This is a standalone arm64 CLI probe, not integration into the
RawLens app or verification of Android app classloader/native-library access.

`export_litert.py` constructs an ordinary `.tflite` graph containing 12 built-in
CONV_2D operations with fused ReLU, using the original trained FP32 constants
(reordered OIHW to OHWI). Signature inputs/outputs are packed NHWC FP32.
No training, approximate weights, custom kernels, or Select TF ops are used.
The flatbuffer is 3,412,128 bytes for each static tile shape. Its Android LiteRT
CPU execution was checked against PyTorch at 128: maximum error 1.07e-6,
RMSE 1.75e-7 (`cpu-export-validation.log`). The installed Mac LiteRT 2.0.3 Python
wheel lacked a required dylib, so host interpreter verification was skipped in
favor of this Android CPU check and all-size Android NPU/PyTorch comparisons.

LiteRT explicitly requests NPU only. The probe **rejects the result** if
`LiteRtCompiledModelIsNonCpuFullyAccelerated` is false. Logs additionally prove
that the MediaTek compiler selected **12/12 ops**, produced one partition,
applied its compiler plugin and dispatch delegate, and loaded the system
`libneuronusdk_adapter.mtk.so` (Neuron SDK 8.2.26). Library maps are saved in
the log. A plugin successfully loading alone is not considered acceleration.
The optional missing `NeuronExecution_setConfig` symbol was logged as a warning;
compilation, delegation and executions all succeeded.

Paired results: same phone, weights, PyTorch reference, two warmups, seven timed
runs; backend order alternates between sizes. Native NNAPI is rebuilt from
`probe.cpp` and explicitly targets `mtk-mdla_shim`. For this paired comparison,
its reference tensor is the independent PyTorch output, including at 1024.
The phone remained at thermal status 0 before every backend. LiteRT timings
include synchronous input buffer lock/write, completed inference, and output
lock/read. The NNAPI timings include synchronous compute into bound host
buffers, with input/output binding and execution creation outside the timer.
Both exclude graph creation/compilation. This compares practical runtime paths,
not an isolated accelerator-kernel timer or a controlled long-duration study.

| Original RGB tile | NNAPI MDLA median | LiteRT MediaTek NPU median | Latency reduction |
| --- | ---: | ---: | ---: |
| 256x256 | 28.058 ms | 26.038 ms | 7.2% |
| 512x512 | 104.751 ms | 98.607 ms | 5.9% |
| 1024x1024 | 413.144 ms | 386.605 ms | 6.4% |

For 1024, LiteRT's seven timed samples ranged 386.051–390.486 ms and native
NNAPI's 412.000–415.651 ms. Graph compilation/setup took ~2.31 s for LiteRT and
~2.30 s for native NNAPI, separately from inference.

**All paired output arrays were byte-identical across LiteRT and native NNAPI.**
At 1024, maximum error against PyTorch was 0.00091624, normalized RMSE 0.00024426,
and linear RMSE after inverse GAT 0.00012695. Output checksums, all samples and
validation metrics are preserved in `results-litert/`. This measures agreement
with the trained PyTorch implementation, not ground-truth denoising quality.

1024 works, but gives only ~1.4% more raw pixels/second than 512 through native
MDLA (~2% through LiteRT), before accounting for overlap. Larger tiles reduce
halo overhead, while image dimensions and padding determine the actual number
of tiles. Therefore this is not evidence of a large full-frame speedup from
1024 tiles. Iterative PnP reconstruction and sustained thermal behavior remain
outside the measured workload.

Runtime dependency downloads (do not commit their binaries):

- [LiteRT 2.3.2 AAR](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/2.3.2/litert-2.3.2.aar)
- [MediaTek NPU 2.3.2 AAR](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-npu-runtime-mediatek/2.3.2/litert-npu-runtime-mediatek-2.3.2.aar)
- [LiteRT 2.3.0 C SDK](https://github.com/google-ai-edge/LiteRT/releases/download/v2.3.0/litert_cc_sdk.zip)

Extract the three arm64 `.so` files from the AARs into a library directory and
extract the C SDK. The runner generates its feature-toggle build header if
missing. Official [MediaTek setup](https://developers.google.com/edge/litert/next/mediatek)
and [NPU guide](https://developers.google.com/edge/litert/next/npu) describe the
CompiledModel backend. Dependencies' SHA-256 hashes are saved with the results.

Reproduce after preparation (include `--tiles 128 256 512 1024` when running
`prepare_pretrained.py`):

```sh
python3 tools/ffdnet-probe/export_litert.py --prepared /tmp/ffdnet-prepared --output /tmp/ffdnet-models --skip-host-validation
python3 tools/ffdnet-probe/run_litert.py --sdk /path/litert_cc_sdk --libraries /path/arm64-libs --models /tmp/ffdnet-models --prepared /tmp/ffdnet-prepared --output /tmp/ffdnet-litert-results --compare-mdla
python3 tools/ffdnet-probe/validate_pretrained.py --prepared /tmp/ffdnet-prepared --results /tmp/ffdnet-litert-results --backends litert-npu nnapi-mdla --tiles 256 512 1024
```

Actual scratch locations: SDK/model/library files in `/tmp/rawlens-ffdnet-litert`,
prepared data in `/tmp/rawlens-ffdnet-trained-inputs`, paired outputs and full
thermal dumps in `/tmp/rawlens-ffdnet-litert-benchmark`.
