# LK-R foliage dots: code audit, literature review, and implementation plan

## Decision

Restore residual rejection as the default while investigating a detail-preserving reconstruction. `SolveParams::guide_confidence` is now 0; explicit `--guide 2` and `debug.lkr.guide 2` retain the experiment. Existing uncommitted `--gres` edits are preserved. No release was published or draft release modified.

The research priority is **conditioning-aware regularization inside the solve**, with frequency-aware motion confidence as a second experiment. A residual mask plus a final notch is not yet a justified fix. This is a proposal supported by the evidence below, not a claim that the new reconstruction has been implemented or validated.

## What was actually reviewed

This is a focused review of relevant reconstruction/motion sections and selected source implementations, not every paper or repository online. Core sources:

| Source | Useful distinction for this problem |
|---|---|
| [Wronski et al., 2019](https://arxiv.org/abs/1905.03277) | Robust confidence accompanies structure-adaptive anisotropic reconstruction kernels. Kernel width trades detail against artifacts; it is not merely a frame-admission rule. |
| [Wronski supplement, S.3–S.5](https://www.ignaciogarciadorado.com/p/2019_TOG/2019_TOG_supplemental.pdf) | Mean/statistics-based robustness can miss high-frequency failures. The described variance-loss plus flow-variation safeguard can reject these cases, but also rejects valid aliased detail. Its existence does not prove our leaves are globally misaligned. |
| [IPOL implementation, 2023](https://www.ipol.im/pub/art/2023/460/) | Reconstructs with adaptive kernels and describes fallback when evidence is inadequate. Reports that Gaussian/median filtering of the final reconstruction can add blur while retaining fusion artifacts. |
| [LK-Reloaded, 2021](https://arxiv.org/abs/2104.06191) | The image prior is an essential part of its inverse-problem formulation. Our no-prior branch is not the complete learned method. |
| [Lecouat et al., HDR/SR, 2022](https://arxiv.org/html/2207.14671) | Separates reference-based confidence from the reconstruction prior. Its own ablation finds a substantial gap between no prior and learned priors. This supports separating validity estimation from regularization, not transplanting its benchmark scores to RawLens. |
| [Farsiu et al., 2004](https://people.duke.edu/~sf59/srfinal.pdf) | Couples a robust data term with edge-preserving regularization. Robust admission alone does not define a well-constrained color reconstruction. |
| [HDR+, 2016](https://people.csail.mit.edu/hasinoff/pubs/HasinoffEtAl16-hdrplus-preprint.pdf) and [IPOL analysis, 2021](https://www.ipol.im/pub/art/2021/336/) | Frequency-specific temporal shrinkage permits partial acceptance of a frame. Spatial shrinkage is a separate operation. This motivates band-dependent evidence rather than one weight for every frequency, but raw phase/aliasing must be modeled before comparing SR bands. |
| [Deep Reparametrization, 2021](https://arxiv.org/abs/2108.08286) | Uses learned features, confidence and a latent reconstruction representation; not a drop-in scalar residual threshold. |
| [Kernel Prediction Networks, 2018](https://openaccess.thecvf.com/content_cvpr_2018/html/Mildenhall_Burst_Denoising_With_CVPR_2018_paper.html) | Joint spatial/temporal filtering is learned with realistic noise and supervised training; helpful as an architecture comparison, not evidence that its weights solve this burst. |
| [BSRT, 2022](https://arxiv.org/abs/2204.08332) and [Burstormer, 2023](https://arxiv.org/abs/2304.01194) | Learned multiscale features and deformable alignment/fusion are much broader changes than our guide switch. Useful future comparators, not the first mobile patch. |

Recent-work screening: [BurstMamba, revised August 2026](https://arxiv.org/abs/2503.19634), separates keyframe reconstruction from burst evidence and includes frequency-conditioned routing; [BurstGP, April 2026](https://arxiv.org/abs/2604.23508), uses generative priors. These were screened at abstract/architecture level, not reproduced. Generative synthesis is not the initial route for faithful camera detail recovery.

Source checkouts inspected (selected modules, no pretrained model evaluations):

- `Jamy-L/Handheld-Multi-Frame-Super-Resolution`, `27c4655ae4752898e0ae6e9737697c4d89c95226`: `robustness.py`, `merge.py`, `config.py`, noise LUT/Monte Carlo code. Current source differs from the 2023 article. It includes flow-dependent robustness scaling, calibrated nonlinear noise correction, and steerable reconstruction kernels; our guide is not a full port.
- `goutamgmb/deep-rep`, `154c51ed4075880eb814df84d863614aa3922ae1`: confidence predictor consumes feature differences, offsets and optionally noise, rather than only local RGB means.
- `akshaydudhane16/Burstormer`, `d5620a5edadb29dfc20001c3a900b46ac9474570`: multilevel alignment and feature/difference fusion in `Burst Super-resolution/Network.py`.
- IPOL HDR+ `code.tgz` from the article: `package/algorithm/merging.py` implements pairwise Fourier shrinkage and separate spatial shrinkage, with noise scaling for the transform/window.
- `timothybrooks/hdr-plus`, `ef4dd2ca53a51e105ed923557c726b253f05c13b`: inspected `src/merge.cpp`; this version uses tile L1 weights and cosine blending, **not** the paper's Fourier Wiener merge. Do not mistake it for the paper implementation.

Checkouts and downloaded archive are under `/tmp/lkr-foliage-fix-20261113`. Original LK-Reloaded `bruno-31/lkburst` was inaccessible via unauthenticated Git during this review; the paper/project page were read instead. No external code was copied into the solver.

## Our code versus those methods

1. `guide_stats.comp` averages sqrt RGB over nine Bayer quads, a 6×6 sensor footprint. `weight_guide.comp` compares these means, with reference variance and analytic noise shrinkage. Fine moving leaves can change while preserving these means. Analytic delta propagation near zero and bilinear interpolation of statistics also differ from the calibrated IPOL implementation.
2. `weight_minh.comp` combines guide and temporal confidence with a minimum; the two spread passes propagate rejection. The guide does not replace temporal protection.
3. `--gres` restores the same running-image residual information that rejected aliased static detail. It is an informative ablation, not an independent motion classifier.
4. `lkr_common.glsl` / `resid.comp` integrate `s*s` bilinear sub-samples per sensor pixel. `scatter.comp` is the corresponding adjoint. A frame count says little about whether these samples independently constrain each fine spatial/color mode.
5. Both host `lkr_full_run.cpp` and Android `lkr_jni.cpp` currently construct an empty `PriorFactory`. With DCT disabled there is no learned image prior. In `lkr_solver.cpp`, the no-prior x-step copies z to x; HQS coupling does not itself supply a spatial image prior.
6. Current `noise_scale` uses a total weighted/unweighted coverage ratio, not a local covariance or phase-rank estimate. Guide support can be high even when the reconstruction is uncertain. The debug effective-frame map additionally uses nearest sensor indexing rather than geometric HR backprojection; use it as a proxy, not exact HR evidence.
7. `FinishLook` conditions its luma notch on rejected coverage. When the guide retains frames that trigger dots, this cleanup may remain weak. Broadening the notch damages static texture and does not remove all measured speckle.

## New controlled evidence

`host/lkr_sampling_modes.py` replays the scalar pixel-integration/bilinear equations on synthetic periodic modes shared across RGB planes. It is a mathematical forward-model probe, not a GPU parity test or real-burst alignment measurement.

At 2× with pure translations, unit-amplitude horizontal, vertical and diagonal period-two modes have output RMS approximately zero (<= 1.2e-15); DC remains 1. With a one-degree rotation, these modes still have response only approximately 0.00015–0.00030. A period-eight test remains strongly observed (RMS approximately 0.38).

Thus, for this geometry, an output can acquire alternating energy without an appreciable sensor residual. **Residual confidence alone cannot detect a mode that the sensor operator cannot see.** More accepted frames do not remove this null space. This supports a conditioning investigation; it does not establish that every visible real-burst dot is that exact mode. At 1×, CFA phase and geometry need their own analysis.

Results: `/tmp/lkr-foliage-fix-20261113/sampling-modes.json`. The preceding full-strength notch probe is documented in `lkr-guide-notch-feasibility-2026-11-13.md`; it failed foliage and static-detail acceptance.

## Ordered implementation plan

### 1. Establish diagnostics with geometry frozen

- Save current affine and local motion fields, final pre-finish reconstruction, actual final forward-model residual, and confidence components separately. Recompute residuals at the saved final image/geometry; do not use a stale gradient dump.
- Evaluate known static building/wires/bicycle, windy foliage, and verified car/pedestrian regions separately at 1× and 2×. Do not infer motion from low confidence or blue strength.
- Backproject evidence with the exact forward/adjoint taps. Report per-color coverage, effective weighted sample count, and a local Gram matrix over a small spatial/phase basis. A diagonal coverage map alone cannot expose a poorly observed checker mode.
- Inspect eigenvectors/eigenvalues and residual spectra. Inject low-amplitude checker/stripe modes into the saved reconstruction and measure the change in the forward data term. Include smooth synthetic patches, known near-Nyquist static texture, known wind-like deformations, and different CFA/subpixel phases.

### 2. First candidate: stabilize weakly measured modes during reconstruction

Prototype an opt-in local prior that penalizes only poorly observed fine-scale modes, using the measured Gram matrix and sensor noise. Preserve the DC and well-supported edge/texture subspaces. Test coupled luma/chroma behavior: a luma-only correction can leave colored dots.

Apply the correction in the HQS/x-step or a well-defined regularized z-step, not solely after FinishLook. Start with a small periodic/transform basis and a noise-aware covariance approximation; validate its agreement with direct operator probes. Choose strengths from propagated measurement uncertainty and held-out residual calibration, rather than constants chosen to make this foliage crop pass.

This is our proposed adaptation of inverse-problem principles, not an existing paper algorithm reproduced verbatim. A low eigenvalue alone does not specify the correct image value; define and evaluate the anchor/prior explicitly. Exact null-space information cannot be recovered from these measurements without a prior.

### 3. Second candidate: band-dependent motion evidence

Keep low-frequency contributions where means agree, but assess high-frequency consistency using the forward-model prediction, noise and sampling phase. HDR+-style frequency-selective weighting is a useful template, not something to paste directly onto aliased raw bands. Add flow-discontinuity/variance-loss evidence as an ablation, since it can also reject static repetitive patterns.

Compare this with candidate 2 independently and then together. Do not blend the restored residual rejection into the guide and label that a new motion detector.

### 4. Acceptance and escalation

- First require the visible chessboard/dot pattern to disappear in native 1:1 foliage crops, including colored dots and multiple crop origins. The scalar mixed-difference score includes real texture, so a score of zero is not a meaningful universal goal.
- Retain the supplied quantitative goals: 2× foliage checker <= approximately 0.0065, guide support >= 0.8, static detail >= the guide baseline (approximately 0.0781), plus matched visual edge/texture review. Support and gradient magnitude alone are insufficient; injected artifacts can increase either apparent detail or fit quality.
- Verify known motion without new ghosts, finite outputs, unchanged off-path bytes, DC/color preservation, no tile seams or clipped-highlight ringing. Use the same saved geometry for causal ablations, then test normal refinement end to end.
- Only after a desktop candidate passes, run full sensor before/after reconstruction on every source burst in both requested collections, adding this windy burst. Render every final 1× DNG with the matched profile and native crops; validate decoder compatibility. Then measure the phone, including cool-device timing and memory. The rollback is not evidence that the requested high-support/static-detail goal has been met.

## Rollback verification completed this turn

Built host tools and passed `lkr_robust_check`. For foliage, building, car and pedestrian crops at both scales (eight cases), the restored default is byte-identical to explicit `--guide 0`; explicit `--guide 2` remains byte-identical to the preserved pre-change executable. Completed fresh full-sensor windy-burst before/after runs at 1× and 2× and rendered all ten crop/full-frame pairs (20 DNGs) at their native dimensions with matched RawTherapee processing.

Foliage crop checker returns from 0.010106 to 0.005893 at 2× and from 0.025252 to 0.018282 at 1×. Static-building 2× detail falls from 0.078057 to 0.073368: this is the acknowledged rollback tradeoff, not a successful detail-preserving fix.

Gallery: `/tmp/lkr-foliage-fix-20261113/index.html`. Commands, hashes, parity records and scores are saved alongside the renders. Original source DNGs were not modified. No staging or commits were performed.
