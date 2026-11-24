# Full LK-R, Lite and learned 2021 pilot

## Matched solver comparison

The Mac full LK-R and Lite CLI runs used identical eight-frame RAW inputs, output scale, and crop. The two ground-truth fixtures use `tools/lkr-lite/research/lkr_synth_burst.py` with a 512×512 RAW crop at (1536, 1024). The static burst uses seed 11 with no subject or rails. The motion burst uses seed 14 with rails and subject speed 1.6 pixels/frame. Both used the car DNG template, and the motion burst drew its texture from the night DNG. Evaluation excluded a 32 RAW-pixel perimeter and neighborhoods around saturated values, and restored Lite's linear exposure scale using its reported HDR white even with bracket mode off.

Detail error is RMS of the sigma-1 Gaussian high-pass luma error over stationary, textured pixels. Moving-region error is luma RMSE over the dilated union of subject masks. Lower error is better. RGB PSNR has a unit peak.

| Fixture | Full RGB PSNR | Lite RGB PSNR | Full detail error | Lite detail error |
|---|---:|---:|---:|---:|
| Static 1× | 33.351 | 33.562 | 0.01870 | 0.01782 |
| Static 2× | 29.692 | 29.899 | 0.02534 | 0.02502 |
| Motion 1× | 29.994 | 29.160 | 0.02905 | 0.03288 |
| Motion 2× | 27.163 | 26.922 | 0.03586 | 0.03722 |

Default Lite's moving-region errors were 0.05210 and 0.06916 at 1× and 2×, versus 0.05564 and 0.07091 for full LK-R. Thus full LK-R fit stationary texture better in the moving scene, while Lite had lower error in the moving region. On the static fixture, Lite had slightly lower texture error.

Adding two Jacobi passes at unchanged total response improved static 1× detail error from 0.01782 to 0.01753 and worsened motion 1× from 0.03288 to 0.03326. Setting `LKRL_STEPS=36` produced the same output bytes as the default in all four cases. The alternate Lite weighting path (`LKRL_LKRW=0`) reduced motion 1× detail error to 0.03182, but increased moving-region error to 0.05266. At 2× it increased stationary detail error from 0.03722 to 0.03740.

The real car and night comparisons used matched 768×640 1× crops, seven merged frames and one withheld frame. Lite emitted no usable held-out residuals, so there is no comparable independent holdout score. Relative to the full LK-R output, the high-pass luma amplitude slope in textured regions moved from 0.945 to 0.815 on the car crop, and 0.990 to 0.740 on the night crop with alternate weighting. These slopes measure agreement with the PC solver, not ground-truth accuracy. The alternate weighting is unsuitable as a default detail improvement on this evidence.

The full-frame Lapis timing used eight car-burst frames at 4080×3060 and 1×, with three workers and HDR and luma DCT disabled. ABBA merge times were 13.295 s (default), 11.813 s (alternate), 11.806 s (alternate), and 13.337 s (default). The alternate path saved 1.51 s on average. Its real-crop detail tradeoff outweighs that speed gain for the requested objective. No app defaults changed.

## Learned 2021 pilot

`python/lkr/train_2021.py` runs a same-exposure, differentiable Bayer reconstruction with learned HQS parameters, affine Gauss–Newton updates and a shared, noise-conditioned ResUNet. The pilot uses width 8, four outer iterations and three data updates per outer iteration. It trained for 400 steps on synthetic 32×32 crops from Zurich training RGB images. Evaluation used 48 crops from the Zurich test set with independent noise and motion. Initial affine transforms were perturbed synthetic truth. The pilot uses a PyTorch reference and has not been integrated into the native PC or Android solver.

| Held-out synthetic validation | RGB PSNR | Horizontal gradient MAE | Dark-flat RMSE |
|---|---:|---:|---:|
| Solver only, four stages | 43.501 dB | 0.005854 | 0.003440 |
| Learned prior, four stages | 43.598 dB | 0.005787 | 0.003415 |
| Solver only, three stages | 43.740 dB | 0.005474 | 0.003297 |
| Learned prior, three stages | 43.821 dB | 0.005421 | 0.003277 |

The learned prior gives a small positive signal at each matched stage count. The early-stop benefit also appears when bypassing the prior, so this pilot does not show that learning enables a shorter schedule. The checkpoint is `artifacts/lkr-2021-pilot/pilot-2021.pt`. The pilot does not establish full-image detail, real-burst denoising, motion robustness, or paper parity.
