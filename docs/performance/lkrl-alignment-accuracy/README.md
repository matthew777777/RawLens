# LK-R Lite alignment accuracy and motion robustness

The native affine and block solver remains the production estimator. Joint pose and depth reduced mean correspondence error on controlled parallax scenes, with lower reverse-flow coverage and weaker final reconstruction than native blocks with a reference anchor in the shared research reconstruction. Native blocks were also more accurate on planar scenes. Motion and HDR results do not justify a geometry port into the default Vulkan path.

The native changes avoid creating the optional block-search pipeline when the search is disabled, make `--sublk 0` apply at night, and restore the real RAW holdout diagnostic. The latter projects the finished tile into the held-out frame with the same Bayer phase, half-pixel convention, bilinear block interpolation and subpixel tap footprint as `resid.comp`. Native merge settings and shader math remain as before. The original host executable was preserved at `/tmp/rawlens-alignment-original` before rebuilding.

## Implementation audit

| Stage | Active implementation and interaction |
|---|---|
| Sensor input | `lkrl_full.cpp` phase-aligns the crop to RGGB and keeps the caller’s uint16 or packed RAW until tile loading. The default reference is frame zero. HDR ratios come from the bracket metadata. Alignment greys and normalized CFA observations have separate roles. |
| Global alignment | `lkrl_full.cpp` uses Bayer means with bin 2 or 4, a three-level forward-additive affine fit from `lkrl_host.cpp`, and a default 16-iteration cap per level. Non-HDR median ratios are applied only in a bounded range. HDR uses known ratios and excludes near-clipped bins. The affine maps moving-frame centres to reference coordinates. |
| Tile and sub-tile initialization | The global map is translated into each overlapping tile’s coordinates. Two-level affine fitting uses half-resolution Bayer-cell greys and accepts tile fits by residual. `lkrl_gpulk.cpp` batches normal-equation accumulation in Vulkan when available and solves the 6×6 reductions on the host. Sub-tiles default to 128 sensor pixels and switch to 256 with extra smoothing in the night branch. An accepted sub-tile needs a residual below 90% of the seed residual. Its affine difference is evaluated at each 16-pixel block centre and bounded to ±24 pixels per component. |
| Optional block search | `lkrl_blocksearch.cpp` prewarps guides by the tile affine and calls the MFSR integer-search shader at successively finer scales. If enabled and successful, this replaces the sub-tile seed. It defaults to off. The Mali run confirms dispatch activity. The MoltenVK A/B runs showed no additional dispatches when requested, so their block-search rows cover fallback behavior. |
| Native subpixel refinement | `jac.comp` accumulates centred affine normal equations. The host solves the 6×6 system. `jacblk.comp` then accumulates local weighted 2×2 translation equations. `solveblk.comp` regularizes toward the four adjacent blocks with a noise-scaled Hessian floor and clamps each update component to ±1 sensor pixel. The reference stays fixed. `lite_common.glsl` bilinearly interpolates the block field when sampling, avoiding piecewise-constant steps. The default schedules are `GJJJ` at 1× and `GGGJJJJJJJJ` at 2×, with the optional average inserted before the first reconstruction step. |
| Validity and rejection | `resid.comp` applies warp and CFA footprint validity. The default LK-R weight chain compares against a reference-derived guide and applies temporal and spatial rejection plus optional residual confidence. Its motion detector can use field curvature. The alternative Lite chain uses Cauchy residual weights, guide statistics and a min filter. Both recompute validity after geometry changes. Neither explicitly identifies a moving object or guarantees scene visibility. |
| Reconstruction | `adj.comp` and `adj_wg.comp` scatter reconstruction gradients and weighted coverage through the same affine-plus-block map used for the forward model. CFA channel selection and sample integration stay in the sensor domain. Coverage drives the normalized reconstruction updates and downstream lost-frame cleanup. HDR marks clipped data, scales variance in normalized exposure units and attempts bracket-based highlight recovery. |

The 2023 method jointly fits frame poses and reference tangent planes with a robust patch loss. The prototype uses scaled plane normals, exponential SE(3) updates, a coarse plane pyramid with Adam and full-resolution pose updates. Direct projection follows the plane-induced homography. Reverse projection iterates disparity as in Appendix B. The convergence mask is partial visibility evidence. Independently moving surfaces can have perfectly consistent forward and inverse projections under a wrong rigid model. The prototype uses Cauchy residuals, Sobel derivative approximations, fixed damping and explicit iteration budgets. It is an independent reference implementation of the core formulation, with different numerical choices from a full paper reproduction. No official implementation was located during the source review. The paper’s shallow monocular initializer sets depth at roughly 16×16. The benchmark compares flat and low-resolution oracle seeds. The oracle measures the opportunity from better depth initialization. It supplies geometry unavailable on real captures. [2023 paper and appendices](https://arxiv.org/html/2312.05190)

The 2021 work motivates reference-domain fitting through the CFA measurement model. The 2022 work uses learned exposure-robust guide features and an exposure-aware inverse problem. This experiment uses normalized Bayer guides and tests saturation masks instead of using the learned alignment model. Exposure-normalized measurements are projected through the same per-channel CFA operator as equal-exposure observations. Neither the guide representation nor the exposure model was trained on the benchmark. [2021 RAW super-resolution](https://arxiv.org/abs/2104.06191) · [2022 HDR RAW bursts](https://arxiv.org/abs/2207.14671)

## Reproduction

Build the native targets. Preserve an executable from before a change to compare the full pipeline independently.

```sh
cmake -S tools/lkr-lite/src/main/cpp -B tools/lkr-lite/build/host -DCMAKE_BUILD_TYPE=Release
cmake --build tools/lkr-lite/build/host --target lkrl_full_run lkrl_run lkrl_motion_check
```

The reference benchmark needs Python with PyTorch, Pillow and NumPy. Use your platform’s Vulkan loader configuration. On the test Mac, it used MoltenVK from Homebrew.

```sh
python python/lkr/benchmark_geometry.py \
  --native-runner tools/lkr-lite/build/host/lkrl_run \
  --seeds 907 1201 2027 --output /tmp/rawlens-alignment-holdout
```

The tests first check direct and inverse tilted-plane warps against analytic homographies, the SE(3) projection derivative by finite differences, and the masked CFA adjoint identity in double precision at 1× and 2×. The benchmark writes per-seed JSON, camera-linear RGB tensors and previews, flow tensors, endpoint-error maps, and confidence maps. Native-affine and native-block methods use the same traced Bayer frames as the geometry estimator. The single-tile harness initializes with native affine LK on Bayer-cell means, then exports the affine and 16-pixel field after the native Vulkan solve. It does not exercise full-frame seeding, tile overlap or the full finish stack. The `native_solver_rgb` row shows the native solver’s own reconstruction as a separate control. Native trace export currently uses equal exposure. The geometry HDR rows therefore have no matched native motion estimate.

Reconstructive controls use the same 1× CFA forward and transpose operators, input photos, reference frame, and fixed iteration budget. Suffixed anchor methods deliberately vary fusion. The anchor assesses noise-scaled guide disagreement, splats rejected support into reference coordinates, and adds a local reference term compatible with the normal-equation update. The noise coefficients are known synthetic sensor settings. Timing uses one Apple M1 CPU thread for the Python estimator. Native trace wall time includes subprocess startup, Vulkan initialization, reconstruction and file I/O, so the CSV reports it separately from estimator timing. Holdout seeds vary texture and noise. They share scene layouts and motion families with the initial seeds.

For the full native RAW A/B, run the following on the output of `lkr_synth_burst.py` or a real burst. A real held-out frame is excluded from fusion. Its registration uses the front-end affine and available local seed. The residual therefore scores reconstruction and that registration together.

```sh
bash tools/lkr-lite/research/alignment_ab.sh \
  tools/lkr-lite/build/host/lkrl_full_run BURST_DIR OUTPUT_DIR \
  --frames 8 --crop 512 512 --at 1536 1024 --s 1 --workers 3 --prior 0 --hdr 0
python tools/lkr-lite/research/score_alignment_ab.py BURST_DIR/labels.npz OUTPUT_DIR
```

The full native benchmark reads and writes DNG plus linear RGB sidecars. The native scoring script needs NumPy and SciPy. Add `--holdout 7` on real equal-exposure comparisons. The existing HDR interface does not combine bracket mode with holdout.

## Matched synthetic findings

Held-out results are means over three seeds. Each endpoint-error statistic scores visible ground-truth correspondences. The native solver RGB column uses native fusion and is a separate comparison from the shared-reconstruction columns. See the [complete table](synthetic-results.csv) for all methods, initial and held-out splits, mean and tail errors, coverage, failure rate, region scores, PSNR, SSIM and timing.

| Scene | Native block mean EPE, px | Geometry mean EPE, px | Native solver PSNR, dB | Geometry with anchor PSNR, dB |
|---|---:|---:|---:|---:|
| Planar | 0.023 | 0.072 | 32.09 | 30.78 |
| Parallax | 0.220 | 0.137 | 31.98 | 30.20 |
| Independent motion | 1.902 | 1.944 | 31.91 | 29.15 |
| Thin periodic foreground in motion | 0.687 | 0.768 | 29.96 | 25.97 |
| Weak texture | 0.799 | 0.833 | 36.74 | 28.63 |
| Large shake | 1.193 | 0.646 | 32.03 | 30.08 |
| HDR, static | — | 0.519 | — | 22.32 |
| HDR, motion | — | 2.521 | — | 22.56 |

Geometry cut mean parallax error by 38% and large-shake error by 46% relative to the tested native single-tile block path. Parallax P99 was 0.753 px for geometry and 0.679 px for native blocks. Geometry reverse coverage on visible parallax samples was 77%, compared with 100% native geometric support. Native support measures in-bounds sampling, so it can include incorrect depth or motion correspondences. The approximate hybrid seed changed mean parallax error from 0.137 to 0.136 px. It did not resolve the occlusion and fusion trade-off.

The reference anchor rescued the otherwise lightly weighted shared reconstruction in the moving-object case. With native block motion held fixed, it increased PSNR from 17.26 to 29.81 dB. The native solver already reached 31.91 dB on those inputs. The same anchor lowered the shared native-block score from 33.16 to 28.55 dB in weak texture. For large shake, geometry plus the anchor scored 30.08 dB against 29.77 dB for native blocks plus the same anchor. The native solver’s own output reached 32.03 dB. This does not support a general quality improvement over native fusion.

Guarded clipping-aware geometry improved mean EPE in the moving HDR case from 2.521 to 2.140 px. Its reconstruction scored 17.07 dB against 27.16 dB for the single-reference control. With static HDR and the coarse oracle seed, EPE improved to 0.335 px, but that reconstruction still reached only 20.88 dB. True HDR recovery and treatment of sparse saturated support remain open.

[Held-out reconstruction comparison](reconstruction.png) · [Endpoint-error and confidence maps](motion-diagnostics.png)

## Native verification and device cost

The rebuilt host known-motion Vulkan check measured **0.023229 px RMSE** on dark texture and **0.023230 px** after a 16× brightness scale change. On two real eight-frame 768×640 crops with automatic bracket detection, three paired runs per burst produced bit-identical camera-linear RGB before and after the changes. Pipeline creation fell from **141 to 138** in each pair, with **16 submissions and 368 dispatches** in both builds. Three pairs per scene with cold initialization do not isolate a wall-time gain.

The restored 1× holdout produced populated maps for both real crops with HDR disabled. Block search did not dispatch on this host. Median and P90 are across the accepted 16-pixel blocks for the ratio of squared error to predicted sensor variance.

| Real burst / method | Blocks | Median | P90 |
|---|---:|---:|---:|
| Car, baseline | 1776 | 0.782 | 2.640 |
| Car, sub-tiles disabled | 1776 | 0.970 | 3.218 |
| Night, baseline | 1920 | 1.766 | 6.708 |
| Night, sub-tiles disabled | 1920 | 1.762 | 6.061 |

The staged Android executable on model 25080RABDG, Mali-G615 MC2, ran the same real car crop with eight frames and three workers. Each row is a single profiled invocation of the staged build, before the host changes above. Reported memory sums the per-worker Vulkan allocation peaks. The device RGB sidecars were bit-identical between baseline and disabled sub-tiles. Block search changed the output. There is no reference reconstruction for this real crop.

| Native Mali configuration | Merge s | Submissions | Dispatches | GPU allocation peaks summed, MiB | Fence wait summed, ms |
|---|---:|---:|---:|---:|---:|
| Baseline | 3.558 | 20 | 608 | 435.6 | 5831.1 |
| Block search | 3.787 | 104 | 692 | 436.1 | 6190.5 |
| Sub-tiles disabled | 3.470 | 20 | 608 | 435.6 | 5818.2 |

The full native synthetic DNG A/B gave 33.573 dB on the static burst and 29.160 dB on the moving burst at 1×, with identical outputs for the three host variants. At 2×, the baseline scored 29.905 and 26.922 dB, respectively. Disabling sub-tiles gave 29.797 and 26.924 dB. The baseline and disabled-subtile 2× moving-region luma RMSE values were 0.069158 and 0.069113 in linear units. Block-search requests gave identical results to baseline on MoltenVK, and dispatch counters showed no additional search work there.

## Limits

Geometry is a research path. Its reverse mask and quality regressions are reasons to withhold a production Vulkan implementation. The anchor results use a compact shared reconstruction and need a matched real RAW comparison against the full native rejection and finish stack before promotion. The hybrid test seeds geometry from the native coarse affine translation. No separate dense residual-refinement Vulkan path or runtime adaptive flow selector was deployed.

The tests exercise parametric parallax, clipped bracket exposures, sensor noise, large shake, weak texture, a translating foreground and periodic thin foreground strips. They do not establish results for rolling shutter, lens distortion, moving vehicles with complex occlusion, multiple independently moving surfaces, water, or wind in real bursts. Current geometry uses a common calibrated pinhole matrix. The test did not evaluate a pretrained depth initializer. Device coverage is one Mali model. Adreno, bandwidth, energy, thermal throttling and end-to-end 2× geometry reconstruction remain unverified.

## 2× alignment follow-up

These tests kept the 2× output size, reconstruction schedule after motion, and rejection settings fixed. The synthetic DNG runner used the same eight RAW frames and 512×512 crop for every row. The interpolation-aware Jacobian and motion-discontinuity regularizer were evaluated and removed after measuring regressions. The [2× correspondence table](2x-motion-epe.csv) reports pooled errors across three seeds. Example trade-offs include 0.174 to 0.155 px lower mean parallax error with motion-discontinuity weighting, with P99 rising from 0.600 to 0.627 px. The interpolation-aware Jacobian raised mean weak-texture error from 0.652 to 0.965 px. The native smooth-motion smoke test also favored the current Jacobian at 0.023 versus 0.038 px RMSE. Increasing the default three Gauss–Newton passes to five or seven slightly helped moving-region error while degrading static quality. The default remains three.

The optional Vulkan large-displacement search needs tile dimensions divisible by 64 for its three-level pyramid. Testing explicitly with 320-pixel tiles activated it on MoltenVK. The previous implementation replaced the whole sub-tile seed even when the search accepted zero blocks. The implementation now uses accepted large displacements where available and leaves the sub-tile estimate in other blocks. This changes only motion initialization on the opt-in search path.

| 2× synthetic DNG crop | Default seed PSNR | Previous search PSNR | Combined seed PSNR |
|---|---:|---:|---:|
| Static | 29.921 | 29.761 | 29.921 |
| Moving foreground | 26.897 | 26.997 | 26.997 |

On the moving DNG crop, the search improved high-pass detail RMSE from 0.044019 to 0.043387 and moving-region luma RMSE from 0.068481 to 0.068263. The combined seed retained those gains and was bit-identical to the default seed on the static crop. The search increased submissions from 24 to 108 across the four synthetic tiles. Real RAW holdout results were mixed. Car P90 was 2.641 in all three modes, while night P90 was 5.216 with the default seed, 4.927 with the previous search, and 5.434 with the combined seed. This is a narrow correction to the opt-in path. These results do not justify enabling search by default or claiming a broad real-world quality gain.

```sh
tools/lkr-lite/build/host/lkrl_full_run BURST_DIR OUT.dng \
  --frames 8 --crop 512 512 --at 1536 1024 --tile 320 --s 2 \
  --workers 3 --prior 0 --hdr 0 --bsearch 1
```
