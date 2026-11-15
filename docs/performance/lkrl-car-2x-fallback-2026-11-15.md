# Car burst 2x: excessive rejection, not excessive DCT smoothing

Burst: `IMG_20260919_184641_979`, eight 4080x3060 frames, output 8160x6120.
Noise fit a=8.21e-6, b=1.61e-6. KernelNet bypasses at this noise level.
Full-frame CLI merges use `--s 2 --prior 0`, with and without `--fallback 0`.
Coverage is diagnostic plane zero (effective frame weights, not an integer
count or proof of usable super-resolution sampling). Diagnostic grid height
is 6116 before the final sensor-size border padding to 6120.

Fallback on/off mean absolute WB luma difference is 1.23e-8, consistent with
rounding, as expected for colour-only fallback. Camera RGB MAE is 3.44e-5.
About 33.6% of pixels differ by more than 1e-5 in any camera channel. Inspected
pole, car, ridge and foreground bush crops show very little visual difference.
Disabling the fallback does not remove the uneven soft texture in the bushes.

The new noise-only cap on the temporal curvature allowance causes unnecessary
rejection. An isolated source copy removed only the cap in `weight_t.comp` and
`weight_td.comp`, retaining refreshed weights and all denoise changes. Results:

| Region | Median effective frames, capped / uncapped | Share below 4, capped / uncapped |
| --- | --- | --- |
| Whole diagnostic grid | 7.19 / 7.55 | 15.8% / 8.6% |
| Upper bushes | 6.53 / 7.63 | 24.5% / 5.9% |
| Lower bushes and rocks | 5.26 / 7.44 | 37.5% / 1.8% |

Upper region: sensor-output coordinates x=2480..3680, y=4500..6000.
Lower region: x=3300..4700, y=3600..5600. Both rendered clockwise to match
the upright screenshots, with identical white balance, exposure and gamma.

The cap mistook legitimate CFA/interpolation error on clean fine texture for
motion. It was removed from both windows. The original curvature allowance
remains; weight refresh, edge-guided chroma smoothing, lower colour DCT
threshold, and zero fallback luma remain. No further loosening is justified
from the remaining rejection percentage alone: moving car, occlusions and
parallax still need rejection. This dataset has no ground-truth sharp image.

Review artifacts under `/tmp/lkrl-car-2x/`: `tree1-compare.png` and
`tree2-compare.png` are capped fallback-on / capped fallback-off / uncapped.
`IMG_20260919_184641_979_2x.dng` is the final main-build output.

Final validation: host build, Android `:app:assembleDebug`, shared-adapter
1x/2x denoise/colour-edge checks, full-resolution final 2x merge, and a fresh
full-resolution original leaves/wall 1x merge all pass. Phone runtime not tested.
