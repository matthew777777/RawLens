# Full LK-R synthetic NPU benchmark on Lapis

The target is eight 4080×3060 Bayer frames and a 4080×3060 RGB output at 1×. The test uses MediaTek MT6878 (Lapis, model 25080RABDG) and compares the freshly built LK-R Lite native merge against the learned-stage workload of a possible Full LK-R implementation.

## Workload

- **2021 reconstruction.** Alternate the raw forward-model data update with a learned image prior using half-quadratic splitting. The 2022 extension retains that structure and supplies the three-iteration schedule used here. [2021 paper](https://arxiv.org/abs/2104.06191)
- **2022 HDR extension.** Exposure-dependent image formation, saturation handling, learned registration features and fusion confidence, and a prior conditioned on a scalar γ. The authors describe the large prior widths (32, 64, 128, 128), two residual blocks per scale, and pixel-shuffle upsampling. The runner instantiates three standalone CNN graphs with deterministic random weights. The feature and confidence proxies use 16 channels at every scale and share the prior's block layout. Decoder convolutions preceding pixel shuffle are 1×1, and skip sums enter the decoder residual stacks. Run one feature map per input, one confidence map per non-reference frame, and three prior passes. Feature and confidence computations use the full 12 MP grid for this test. [2022 paper](https://arxiv.org/abs/2207.14671) · [Architecture and schedule, thesis §6.4.3 and §6.a.2](https://bruno-31.github.io/doc/thesisplain.pdf)
- **2023 motion.** The method jointly estimates camera poses and per-reference-pixel depth and surface orientation. It uses coarse-to-fine optimization, with pose updates on SO(3), depth/normal updates and target-view warps. The paper's SR demo solves the inverse problem on demosaicked linear RGB. Feeding those warps to the bracketed Bayer reconstruction would be a new integration choice. The benchmark times CNNs only. The dense optimizer, raw data update, and CPU/GPU/NPU transfers between pipeline stages remain unmeasured. [2023 paper and supplement §K](https://ar5iv.labs.arxiv.org/html/2312.05190)

## Reproduce the CNN benchmark

Build from the repository root with an Android NDK arm64 compiler. The program queries support for every operator and compiles for one named accelerator. Android CPU and GPU fallback are disabled. The graphs use FLOAT32 host tensors with FP16 relaxation enabled. The first two executions warm up the graph. Execution timings include NNAPI execution creation, buffer registration, and synchronous compute. Compilation is reported separately.

```sh
aarch64-linux-android29-clang++ -std=c++17 -O3 -static-libstdc++ \
  tools/lkr-lite/research/prior/resunet_nnapi_bench.cpp \
  -lneuralnetworks -o /tmp/resunet-nnapi
adb push /tmp/resunet-nnapi /data/local/tmp/resunet-nnapi
adb shell chmod 755 /data/local/tmp/resunet-nnapi
adb shell /data/local/tmp/resunet-nnapi prior 1248 36 mtk-mdla_shim
adb shell /data/local/tmp/resunet-nnapi feature 1248 96 mtk-mdla_shim
adb shell /data/local/tmp/resunet-nnapi confidence 1248 84 mtk-mdla_shim
```

The modeled tile grid leaves 112 pixels on each side as halo. The graph's maximum input dependency radius is 97 pixels and its downsampling phase repeats every eight pixels. A square 1248-pixel graph tile therefore covers a 1024-pixel core. The grid is 4×3, and the sustained test repeats the three graphs for the resulting invocation counts. It reuses a fixed synthetic input tensor per graph. Real tile extraction, edge padding, stitching, stage dependencies and output equivalence were outside this timing test.

For Lite, build the checkout's `lkrl_core` and `srpipeline` arm64 Release CMake targets and link `tools/lkr-lite/host/lkrl_full_run.cpp` against them, Vulkan, NNAPI, Android and log. Run with `--s 1 --workers 3 --frames 8 --prior 0` for the color-only DCT case, and `--prior 1` for the luma DCT check. Use the same existing eight-frame car burst at `/data/local/tmp/lkrl/car`. Lite's `TOTAL(merge)` excludes DNG input and output. The checkout was based on `b8de711d` and included pre-existing local edits to the Lite solver.

## Results

Lite color-only DCT merge times were 13.657, 13.672 and 14.606 s (median 13.672 s). With luma DCT enabled, the times were 17.494, 14.488 and 14.403 s (median 14.488 s). The first luma run started with a new Vulkan cache.

All three 1248×1248 CNN graphs reported 56/56 supported operators on `mtk-mdla_shim`, compiled, and returned finite, nonconstant output.

| CNN | Calls | Median per call | Median on hardware | Summed execution |
|---|---:|---:|---:|---:|
| Large ResUNet prior | 36 | 769.12 ms | 748.04 ms | 28.953 s |
| Registration features | 96 | 146.85 ms | 139.66 ms | 14.398 s |
| Fusion confidence | 84 | 180.03 ms | 155.26 ms | 15.284 s |
| Total | 216 | | | 58.635 s |

Compilation took 6.29, 6.61 and 6.29 s respectively, outside the execution sums. At 736×736 (512-pixel core), the nine-call medians were 271.21, 53.54 and 68.47 ms. Multiplying by the corresponding invocation counts gives 82.62 s. At 480×480 (256-pixel core), the same calculation gives 144.24 s.

The tested serial NPU CNN schedule took 4.29× Lite's default merge time before the reconstruction and dense-motion work. The large prior alone took 2.12× Lite's median. Independent CNN runs demonstrate execution cost for the chosen graph and tile schedule. A production port with trained weights, validated tiled output, and the integrated 2023 optimizer would need its own end-to-end timing and accuracy comparison.

## Narrow-network follow-up

The optional final argument to the NNAPI runner selects a constant channel width across the four scales. Widths 4 and 8 retain the same 56-operator layout, 1248×1248 graph shape and full-resolution 12-tile invocation counts. All ops ran on the same MDLA device with NNAPI fallback disabled.

| Width | Three prior passes | Eight feature passes | Seven confidence passes | CNN time |
|---|---:|---:|---:|---:|
| 4 | 3.430 s | 8.862 s | 10.731 s | 23.024 s |
| 8 | 4.284 s | 10.009 s | 11.559 s | 25.852 s |

These measurements assume one feature pass per frame and one confidence pass per non-reference frame. Confidence refreshes after changing warps would add calls. Timing includes synchronous execution with host tensors and excludes graph compilation, reconstruction and motion. Narrowing alone did not meet the 13.672 s Lite baseline for this serial deployment.

The PyTorch prototype in `python/lkr/hdr_hqs.py` implements three HDR HQS image updates on caller-supplied warps, including the exposure-scaled RGGB operator, saturation weighting, learned confidence, and the shared ResUNet prior. The smoke runner uses eight-frame noisy synthetic brackets from crop pairs with known subpixel motion. On validation crops from Kodak image 02, after 128 optimization steps using image 01, linear MAE went from 0.226886 at random initialization to 0.069192. Gradients reached both CNNs and the learned HQS parameters. On a separate CPU check of the prior graph, a 112-pixel halo around 128-pixel cores reproduced the untiled output to a maximum absolute difference of 2.98e-7. This used clipped, phase-aligned tiles and did not test fixed-shape NNAPI edge handling.

The smoke test uses known translation warps, reference-image initialization and one configurable data step per HQS stage. The registration network, 2023 pose/plane optimizer, aligned HDR initializer and live RAW input still need integration. The optimization check establishes trainability on a narrow fixture and does not establish quality against Lite.

The host geometry prototype in `python/lkr/dense_warp.py` implements the 2023 Appendix B fixed-point reverse warp for supplied poses and plane maps. It returns the grid and validity format accepted by the HQS prototype. On a calibrated slanted-plane scene, valid pixels agreed with the closed-form inverse homography to 6.40e-14 pixels in float64. This checks the warp primitive and leaves estimation of the poses and plane map unverified.

### TFLite / LiteRT comparison

The follow-up used the same Lapis phone, 1248×1248 tiles, width four and invocation counts (36 / 96 / 84). The TFLite models used independently initialized dense weights and were checked against their corresponding PyTorch outputs. The LiteRT 2.3.2 MediaTek runner required full NPU delegation, reused managed I/O buffers, and timed host input copy, synchronous inference and host output copy. Setup and two warm-ups per graph were excluded.

| Backend and export | Prior | Features | Confidence | Total |
|---|---:|---:|---:|---:|
| Native NNAPI, pixel shuffle | 3.430 s | 8.862 s | 10.731 s | 23.024 s |
| LiteRT, converter layout with redundant mirror pad removed | 13.135 s | 36.372 s | 32.589 s | 82.097 s |
| LiteRT, NHWC with stride-2 transpose convolution | 2.529 s | 8.132 s | 7.637 s | 18.298 s |

The direct TFLite depth-to-space export selected 53 of 56 ops and failed full delegation. A direct compact reshape–transpose–reshape export also failed full delegation. The converter export selected 92 of 93 ops before the removal of an all-zero mirror pad. The transpose-convolution export selected 53 of 53 ops in one partition for each CNN. A 2×2 stride-2 transpose convolution is equivalent to a 1×1 convolution followed by 2× pixel shuffle after reindexing the kernel, with the upsampling biases set to zero in the synthetic models. The LiteRT CPU comparison against the matching PyTorch models had maximum absolute error under 4.5e-7. The NPU maximum absolute error was at most 0.00139.

The fastest LiteRT export reduced the synthetic CNN time by 20.5% versus the earlier NNAPI configuration. The results compare practical graph and runtime configurations, with different synthetic weights and timing sessions. The 18.30 s total still exceeds the 13.67 s Lite native merge before adding the hybrid's data consistency and geometry.

### INT8 alignment and confidence

Both feature and confidence graphs were quantized from the corresponding width-4 NHWC transpose-convolution export. The calibration used one synthetic 1248×1248 uniform-random tensor per graph, 8% activation headroom, per-output-channel symmetric INT8 weights, INT32 biases, and signed INT8 activations and I/O. All 53 operators delegated in one partition per graph on the same LiteRT 2.3.2 MediaTek backend.

| Workload | FP32 model wall time | INT8 model wall time | INT8 inference only |
|---|---:|---:|---:|
| Alignment features, 96 calls | 8.132 s | 4.136 s | 3.185 s |
| Fusion confidence, 84 calls | 7.637 s | 5.354 s | 4.379 s |
| Both | 15.770 s | 9.490 s | 7.564 s |

INT8 wall time includes float-to-INT8 tensor packing and output dequantization. The preceding FP32 wall comparison includes float buffer copies. Including the unchanged float prior yields 12.019 s for the serial learned-stage schedule, leaving 1.653 s against current Lite for all remaining work. An idealized handoff using the measured INT8 invocation subtotal plus the float prior would be 10.092 s before the rest of the solver.

CPU evaluation on a held-out Kodak crop converted to linear RGGB, bracketing at −2, 0, and +1.5 EV with synthetic sensor noise, gave feature-output MAE of 0.00412–0.00419 and sigmoid confidence MAE of 0.00104 across the two non-reference exposures. The 99th-percentile absolute confidence error was 0.00340. The CNN weights remain synthetic. These tensor differences do not establish alignment or reconstruction quality for a trained model.

### Dense motion host reference

`python/lkr/dense_alignment.py` alternates full-resolution damped SE(3) Gauss–Newton pose steps and scale-local Adam tangent-plane updates, starting from supplied coarse depth. The feature maps are supplied by the caller. The timing used two alternations and two plane steps per level, a 3×3 patch, and a supplied 8×10 coarse depth seed. This is a PyTorch CPU implementation on the Mac, not an Android or Vulkan measurement.

| Synthetic slanted-plane burst | Solve time | Valid interior EPE, identity → fitted |
|---|---:|---:|
| 3 × 80×64 | 2.20 s | 1.032 → 0.065 px |
| 8 × 320×256 | 5.60 s | 0.922 → 0.044 px |
| 8 × 640×512 | 27.85 s | 0.688 → 0.035 px |

These times exclude the reverse-warp phase and include host optimizer setup. Endpoints were evaluated by comparing the fitted and analytic reverse warps on jointly valid interior pixels. This verifies a planar fixture. Nonplanar scenes, depth boundaries, bracketed alignment quality, pose/plane convergence parity, and full-resolution Android performance remain open. A single full-resolution 3×3 pose update for seven non-reference frames traverses about 787 million pixel-patch residuals. The host times should not be linearly extrapolated to a GPU implementation.
