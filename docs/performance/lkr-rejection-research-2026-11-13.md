# LK-R static-detail rejection: research and experiment plan

Research date: 2026-11-13. Local HEAD: `99ce5172ec06f3dd26a7487c59b59fed7ebc4965`.

**Correction from direct frame inspection during implementation:** the handoff's
`080720` x1420–1720 / y540–840 region shows a bicycle behind balcony railings,
not a person. The eight source-frame crops confirm this. The handoff's
railing/person test and blue-strength/solidity metric
must not be used as motion rejection evidence. A decrease
in blue there may be removal of static false color. Re-evaluate the previously
rejected approaches on verified moving subjects. Evidence:
`/tmp/lkr-rejection-validation-20261113/080720-individual-frames.png`.

This is a focused literature and source-code review, not an exhaustive claim to have read every burst-SR paper. No reconstruction experiments or phone tests were run for this review. Results quoted from the handoff are earlier measurements, not new verification. Alignment remains fixed in the proposed first experiments because the handoff identified rejection as the detail-loss mechanism.

Existing uncommitted changes in `lkr_solver.cpp`, `lkr_solver.h`, and `lkr_full_run.cpp` implement the optional fallback-chroma experiment. They were read and left untouched.

## Recommendation

First prove the recoverable detail with known motion masks and fixed geometry. Then test a separate reference-anchored confidence estimator that can recognize phase-dependent aliasing and motion using spatial/color context. Keep the RAW reconstruction operator. Do not spend another round globally loosening tau, delaying rejection, or fitting camera motion.

A learned confidence module is a well-supported architectural direction in the reviewed sources, but it is not yet a demonstrated solution to our bursts. A cheaper statistical classifier should be tested with verified labels before committing to training and deployment.

## What our code actually does

| Stage | Code | Relevant behavior |
|---|---|---|
| Image formation | `shaders/resid.comp`, `lkr_common.glsl::subPos` | Projects RGB estimate z into each RAW frame, selecting the CFA channel, integrating s-by-s subpositions with bilinear taps. Residual is U_k z minus y_k. |
| Residual normalization | `shaders/weight_e.comp` | e = r² / [a max(y,0) + b + tau² max(same-color gradient energy, range²/16)]. The added tolerance describes local sensitivity, not an explicit aliasing model. |
| Initial robust weight | `shaders/weight.comp` | Averages normalized squared residual over a 3-by-3 RAW neighborhood, then w = 1/[1+(mean(e)/k²)^p]. Frame zero always has weight one. |
| Temporal evidence | `weight_th.comp`, `weight_tdet.comp`, `weight_t.comp` | Tests against the fixed reference-only initial image z0; includes common-detail tolerance and a wider dark-region test. The handoff's supposed railing-person failure actually concerns static bicycle/railing detail. |
| Rejection spread | `weight_minh.comp`, `weight_min.comp` | Takes a neighborhood minimum of raw robust and temporal weights. Default solver radius is three LR pixels. Alias-induced low weights can spread to neighboring samples. |
| Iteration | `lkr_solver.cpp::data_step` | Recomputes weights on the first data step of an outer iteration; later data steps reuse them when spread is enabled. Rejection starts at iteration zero by default. |
| Daylight curve | `lkr_full.h`, `lkr_full.cpp` | Auto look uses daylight k=2, p=2, rather than the standalone SolveParams defaults k=3, p=1. Record effective parameters in every comparison. |
| Prior/finishing | `lkr_solver.cpp` | Reduced weighted coverage can increase prior noise scale; Bayer-period cleanup and finishing can further affect apparent detail. Trace final_z, final_x, final_look separately. |

The causal concern is a feedback loop: incomplete HR texture produces residuals; residuals suppress the observations needed to reconstruct it; the reference remains fully weighted. Spatial spreading and coverage-dependent processing can amplify that loss. The handoff supports this explanation, but it does not distinguish all contributions from model mismatch, phase coverage, prior suppression, and reference bias.

Important comparison correction: `lkr_full.cpp`'s paper-parity block also changes prior gain, initialization, local motion, clipping, mean preservation, grain, notching, shadow processing and scheduling. `--paper 1` is useful as a visual baseline, but cannot by itself isolate rejection. Toggle rejection in an otherwise identical pipeline and freeze recorded geometry.

The statement that 1x necessarily requires fallback is too strong. A 1x latent grid cannot represent every above-Nyquist component present before sampling; nevertheless multi-frame demosaicing and anti-aliasing can improve a 1x result. Test a 2x latent solve followed by proper low-pass downsampling to 1x to separate internal representation from delivery resolution. This is a hypothesis, not a guarantee of more detail.

## Literature and upstream source comparison

### Lucas–Kanade Reloaded, Lecouat et al., ICCV 2021

[Paper](https://arxiv.org/html/2104.06191), [author results](https://bruno-31.github.io/lkburst2/).

The published quadratic reconstruction objective has no equivalent of our added Cauchy/power, temporal mask, or min-spread chain. Thus high static detail and poor moving-object behavior in paper mode are consistent. The paper's forward model integrates warped HR content before CFA sampling; our projection follows that structure. Keep it.

The paper names `bruno-31/lkburst` as its code repository, but the public clone failed with a GitHub authentication/not-found-style response during this review. An upstream source-level comparison with that repository was therefore unavailable; the equation comparison is with the paper and our local Python/C++ implementations. Do not call our mode a verified reproduction of inaccessible pretrained upstream weights.

### Handheld Multi-Frame Super-Resolution, Wronski et al., SIGGRAPH 2019

[Paper](https://arxiv.org/abs/1905.03277), [author supplement](https://www.ignaciogarciadorado.com/p/2019_TOG/2019_TOG_supplemental.pdf).

The method separates reconstruction from robustness, comparing local guide statistics with a texture-dependent tolerance and motion prior. Its supplement explicitly describes over-rejection of highly aliased detail and a variance-loss heuristic that can reject correctly aligned high frequencies. It confirms the tradeoff; it does not supply a universal motion/aliasing discriminator.

Our min filter resembles one component of that design, but its input is normalized RAW prediction error rather than the same guide-statistics confidence. Porting the minimum filter does not confer the behavior of the complete method. Earlier d/sigma and flow-irregularity experiments are implementation history; their motion conclusions need reevaluation because the main motion criterion misidentified a static crop.

### Implementing Handheld Burst Super-Resolution, Lafenetre et al., IPOL 2023; current repository

[Publication and archived code](https://www.ipol.im/pub/art/2023/460/), [current repository](https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution).

Source inspected at commit `27c4655ae4752898e0ae6e9737697c4d89c95226`, especially `handheld_super_resolution/robustness.py`.

The current code forms half-resolution RGB guides using sqrt(max(raw,0)), computes local means/variances before warping statistics, applies noise-LUT correction, uses RGB mean distance and summed variance, penalizes irregular flow, then applies a local minimum. The README explicitly says the current implementation diverges from the IPOL demo. Its September 2026 robustness changes matter when comparing to older experiments.

A controlled current-code guide baseline is worthwhile: the handoff's scalar d/sigma approximation is not automatically an exact reproduction of this RGB/transformed/noise-corrected path. However, this is a bounded baseline, not a reason to repeat unconstrained parameter searches. Color aliases may still fool it; verified moving pedestrians and cars remain mandatory.

### High Dynamic Range and Super-Resolution from Raw Image Bursts, Lecouat et al., SIGGRAPH 2022

[Paper, especially Sections 4.1 and 4.3](https://arxiv.org/html/2207.14671), [author project](https://teboli.github.io/hdr_sr_raw_burst/).

The follow-up introduces a separate learned confidence function g comparing a frame with the warped reference, using concatenated bilinearly interpolated RGB images and a tiny U-Net. Confidence is distinct from the evolving reconstruction residual. The authors report improvement for moderate motion and also state non-rigid-motion limitations. This supports a separate confidence module, not a guarantee that its original network solves mixed railings and people. Public executable source/checkpoints for this confidence component were not verified here.

### Deep Burst SR and Deep Reparametrization, Bhat et al., 2021

[Deep Burst SR paper](https://arxiv.org/abs/2101.10997), [fusion source](https://github.com/goutamgmb/deep-burst-sr/blob/master/models/dbsr/merging.py).

Source inspected at `16f158e0553b6702cc1693c43f733c2830973278`. `WeightedSum` predicts feature-channel weights from projected features, differences from base features, and optional subpixel-offset embeddings. The base may be the burst mean or reference according to configuration. This is richer evidence than scalar RAW residual energy.

[Deep Reparametrization paper](https://openaccess.thecvf.com/content/ICCV2021/html/Bhat_Deep_Reparametrization_of_Multi-Frame_Super-Resolution_and_Denoising_ICCV_2021_paper.html), [weight predictor](https://github.com/goutamgmb/deep-rep/blob/master/models/deeprep/weight_predictors.py).

Source inspected at `154c51ed4075880eb814df84d863614aa3922ae1`. It compares frame features with warped reference features by default, optionally uses mean features, and can include offsets and noise features. The optimizer predicts certainty before its update loop. These learned feature-space weights cannot be copied numerically into a RAW IRLS solver. In particular, residual multipliers in a squared objective enter its gradient squared; our current scatter multiplies residual by w directly. Define coefficient conventions explicitly.

### Additional methods screened

[Self-Supervised Burst Super-Resolution, ICCV 2023](https://openaccess.thecvf.com/content/ICCV2023/html/Bhat_Self-Supervised_Burst_Super-Resolution_ICCV_2023_paper.html): forward-model supervision from noisy bursts and robustness to dynamic training data motivate independent-frame validation. The abstract was verified; detailed full-PDF access failed, so this review does not attribute a specific masking implementation to it.

[HDR+, Hasinoff et al., 2016](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/): pairwise frequency-domain robust merging is a useful reference for preserving the reference instant, but is not a direct solution to RAW aliased-detail recovery.

[Farsiu et al., robust SR](https://elad.cs.technion.ac.il/wp-content/uploads/2018/02/15_SuperRes_ICIP_2003.pdf): robust data losses are established tools; replacing Cauchy with another scalar loss does not add the missing visibility evidence.

[Burstormer, CVPR 2023](https://openaccess.thecvf.com/content/CVPR2023/html/Dudhane_Burstormer_Burst_Image_Restoration_and_Enhancement_Transformer_CVPR_2023_paper.html): learned contextual fusion is relevant, but adopting a full transformer reconstruction system is a larger change than replacing our confidence calculation. Newer architecture/benchmark papers surfaced in the search do not establish success on these particular bursts. No such success is assumed.

## Ordered experiment plan

### 1. Establish causality and the achievable target

Use existing aligned bursts and save geometry once. Preserve the same reference, initialization, latent scale, prior, iteration count and finishing for all rejection ablations. For an initial diagnostic, freeze geometry after the trusted alignment stage; test normal refinement separately only after comparing masks.

Run: current robust weights; weights one everywhere except invalid/clipped samples; and diagnostic known visibility masks. For the last case, accept static observations and exclude non-reference observations that disagree with reference-time visibility. Mark both the reference person's occupied area and its swept/disoccluded region; a mask of only the current person silhouette is insufficient. Keep the reference frame as the time anchor. At antialiased occlusion boundaries, use a conservative exclusion margin covering the projection footprint.

Known masks come from the synthetic labels, with hand annotations for the real evaluation crops. They are an upper-bound diagnostic, not a deployable algorithm. If they recover static texture while preserving the person, this validates confidence redesign. If they do not, quantify forward-model/prior/conditioning limitations before training anything.

Compare 1x, 2x, and 2x followed by anti-aliased reduction to 1x. Log per-color coverage and sampling-phase diversity, not just mean accepted weight. Sixteen similar phases need not provide sixteen independent constraints.

### 2. Diagnose how the decisions fail

Dump RAW residual, normalized energy, pre-spread weight, temporal weight, post-spread weight, weighted coverage and final_z/x/look for the same crops. Store sample validity explicitly in the diagnostic scorer; residual zero is not logically equivalent to invalid, although several current paths use it as a sentinel. Audit that separately without claiming it is the root cause.

Separate error introduced by the seed, the first robust iteration, neighborhood spreading, the prior and finishing. Plot rejection versus CFA channel and fractional sample position. A strong phase relationship on known static patches is evidence for sampling/model error, not independent motion.

Use the existing holdout machinery as a starting point, but score camera-linear reconstruction before artistic finishing and keep held-out geometry fixed for confidence comparisons. The current holdout code re-aligns against the finished output, which can hide differences. Cross-validation groups must preserve useful phase coverage. Low held-out error alone cannot certify the reference person's visibility: a consistent background can win a majority vote.

### 3. Test two confidence candidates on fixed geometry

Candidate A, bounded non-learning baseline: compare an IPOL-inspired RGB guide statistic with explicit transform and noise assumptions. Evaluate static detail and verified motion separately. Stop if no threshold preserves the real moving subjects while recovering static support. An analytic approximation to the noise correction must be identified as such.

Candidate B, preferred architectural experiment: a small reference-anchored confidence predictor. Inputs should include packed CFA/RGB context from reference and comparison frames, their warped differences, fractional sampling phase, and noise level. Include both color and larger spatial context so the region behind railings is visible to the classifier. Predict conservative per-frame visibility confidence, shared across local CFA colors. Retain full-resolution RAW samples for reconstruction.

Train first on known static bursts and physically sampled dynamic composites: foreground railings over independently moving subjects, matching luma but different chroma, non-rigid clothing, fractional-pixel coverage and exposure-time integration. Split by scene, holding out verified moving pedestrians/cars for evaluation. Use 080720 for static-detail/false-color evaluation. Use reference-time image targets and an explicit penalty for accepting changed visibility. Existing weights are not ground-truth labels.

Use confidence to authorize static sample admission; keep a calibrated robust residual as an additional safeguard only if its model-error allowance no longer vetoes the same confirmed-static aliases. Multiplying a new permissive confidence by our unchanged strict w cannot restore rejected detail. Conversely, taking the maximum of two confidences can bypass motion protection. Define separate static/moving/uncertain decisions: certified static can use a less restrictive data term, moving keeps conservative exclusion, uncertain retains current behavior.

A classifier does not resolve every ambiguity. Mixed layers within one sensor footprint can be unidentifiable; conservative reference reconstruction is appropriate there. Wider context supplies evidence that scalar pixel residuals lack, rather than mathematically guaranteeing separation.

### 4. Change spatial spreading only after changing evidence

Apply spreading to evidence of changed visibility, rather than to every large reconstruction residual. Compare mask dilation with projection-footprint-aware exclusion and guided spatial propagation. Preserve common decisions across CFA colors to avoid the known magenta/green moving-edge failure. Do not simply reduce spread radius globally: the handoff already records ghosts from that tradeoff.

If phase-dependent errors persist in known static regions at 2x, test forward-model calibration with independent data: pixel integration, PSF, exposure normalization and prediction uncertainty. Add an uncertainty term to the denominator only if measured on independent known-static samples; a free texture-dependent tolerance would repeat the failed tau experiments.

### 5. Acceptance and deployment

Mandatory real cases: 074919 far building/pole (sensor x1640–1960, y1280–1680), balconies (approximately x900–2400, y0–600), 080720 static bicycle/railings (x1420–1720, y540–840), the moving pedestrian/taxis in 33TJ, and the moving car in IMG_20260919_184641_979. Sand 134639 is useful if available, but it is not present in the user's two requested collections. Retain moving foliage, dark-motion and clipped-highlight regressions.

Report synthetic detail error/PSNR and ghost/trail error separately, with multiple seeds and motion/phase settings. Earlier handoff figures (8.9 dB rejection-off versus 5.6 dB committed on one synthetic configuration) are a reference point, not a universal target. Report real support, false-color energy and matched-tone visual crops; support increase alone is not success. Verified moving-subject solidity must match the committed result within measured evaluation repeatability, with no new trails or chromatic fringes. The earlier 080720 blue-strength score is invalid as a motion criterion.

Only port a winning host prototype to Vulkan after the real motion test passes. Measure full-resolution time, peak memory and device output; a 2x internal grid has four times as many latent pixels as 1x and is not automatically cheap. Stage explicit paths only. Phone validation uses the lkrcheck package.

Fallback chroma cleanup remains a separate artifact-reduction option. It may improve false color in genuinely unsupported regions, but it cannot restore the observations or SR detail that rejection removed.
