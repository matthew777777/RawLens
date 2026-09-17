# Burst reconstruction paper conformance

Date: 2026-09-15. Algorithm version audited: 7 (post-F4). Baseline: 163 tests, 0 failures.
Worktree is dirty (see `git status`); unrelated changes preserved; deleted RawSr experiments not restored.

This report traces the supplied research corpus to the plan
(`docs/cfa-inverse-burst-merge-plan.md`) to the desktop CPU twin
(`tools/burst-reconstruction-desktop`), per the pre-F5 audit prompt.
It distinguishes author claims from our engineering inferences and never
transfers neural benchmark gains to the deterministic implementation.

## 1. Corpus inventory (verified filenames, duplicates, accessibility)

Markdown under `/Users/monikamalinowska/md` (4 files, 2007 lines total):

- `2112.07315v2.md` (465 lines) — Kernel-Aware Burst Blind Super-Resolution (Lian/Peng).
- `2406.17869v1.md` (405 lines) — Burst Image Super-Resolution with Base Frame Selection (Kim et al., NEBI + FSN).
- `2503.08300v1.md` (501 lines) — Feature Alignment with Equivariant Convolutions for Burst Image Super-Resolution (Liu et al.).
- `2503.19634v2.md` (636 lines) — Keyframe-Centric State-Space Modeling / BurstMamba (Unal/Marty/Dai).

PDFs under `/Users/monikamalinowska/Pdf` (5 files):

- `2112.07315v2.pdf` (2,672,376 B) — matches `2112.07315v2.md` header/abstract.
- `2406.17869v1.pdf` (3,260,752 B) — NEBI content (50 hits for NEBI in decompressed streams); matches md.
- `2408.08665v2.pdf` (4,444,616 B) — **QMambaBSR** (9 hits for QMamba, QSSM/AdaUp context
  `Query State Space Model (QSSM)`, `Adaptive Up-sampling (AdaUp)` in stream text).
  There is **no corresponding `.md`** under `/Users/monikamalinowska/md`.
- `2503.08300v1.pdf` (1,094,452 B) — equivariant paper (1 hit for equivariant alignment context;
  5 QMamba hits are citations, e.g. `introduced in QMambaBSR [9]`, not identity).
- `2503.19634v2.pdf` (50,031,367 B) — BurstMamba (PDF Title
  `Keyframe-Centric State-Space Modeling for Burst Image Super-Resolution`, 47 BurstMamba hits,
  26 QMamba hits as related work).

Duplicates (not independent evidence):

- `/Users/monikamalinowska/Downloads/Pdf.zip` contains `2112.07315v2.pdf` and
  `2112.07315v2 (1).pdf`, both 2,672,376 B — byte-duplicate of the same paper.
  The zip omits `2408.08665v2.pdf`. Do not count the `(1)` copy separately.

Inaccessible / degraded material (reported, not guessed):

- QMambaBSR Markdown is absent from `/Users/monikamalinowska/md`. The plan (§16) cites
  `/Users/monikamalinowska/Downloads/Di_QMambaBSR_...md` with the same-basename PDF;
  that file is absent from `/Users/monikamalinowska/Downloads` (listing shows Burst test,
  DNGs.zip, Pdf.zip, Sea/Street zips, no `Di_` file). QMambaBSR review below uses
  `2408.08665v2.pdf` decompressed stream text plus the plan's §§2/11 summaries.
- No PDF skill is installed in this session (only `customize-opencode`); `pdfinfo`/`pdftotext`
  are absent. PDF inspection used `zlib` stream decompression + metadata (`Title`/`Author`)
  and keyword contexts. Displayed equations, figures and tables that the Markdown
  extraction omits or corrupts (e.g. `2112` Eq.1 degradation `y = (x ⊗ k)↓ + η`,
  `2406` Fig.4 FSN/CMA diagram, `2503.08300` Eq.4 alignment loss, `2503.19634` Alg.1/GAS Fig.,
  QMambaBSR §§3.1–3.4 QSSM/MSFM/AdaUp equations) are **not reconstructed from guesswork**.
  Where a PDF page/equation number is needed below and only Markdown § references survive,
  the entry says so explicitly.
- All four `.md` files were read completely (section headers inventoried; method §§3–4
  and experiment §§4–5 reviewed). PDF stream text was spot-checked for identity
  (titles, QSSM/AdaUp, NEBI counts, equivariant context, BurstMamba counts), not as a
  full re-read of every equation/figure. No complete-corpus equation-level verification
  is claimed.

## 2. Paper-by-paper traceability

Conventions: `Status` is one of verified adaptation, partial, missing, contradictory,
deliberately excluded, deferred to F5/GPU. `Code` gives exact files/functions.
`Tests` gives independent evidence (not round-trips through the same helper).

### P1 — 2112.07315v2, Kernel-Aware Burst Blind Super-Resolution (KBNet)

- Source: md §§1–3 (esp. §3.1 problem formulation, §3.2 kernel estimator, §3.4 pyramid
  kernel-aware deformable alignment, §3.5 fusion/reconstruction); PDF identity via
  size + md header (displayed Eq.1 `y_i = (x ⊗ k_i)↓_s + η` is corrupted in md line 27,
  reported as inaccessible, not quoted).
- What authors do: two-step blind SR — per-frame blur-kernel estimator CNN (with PCA
  embeddings) + restorer with Adaptive Kernel-Aware Blocks and pyramid kernel-aware
  deformable alignment; raw-burst input, RGB SR output; learned kernels/deformables.
- Assumptions/limits: learned estimator needs training data; kernels are Gaussian-like
  embeddings, not calibrated physical PSFs; alignment is feature-domain deformable,
  not an explicit geometric transform with image-domain supervision; evaluation is
  neural SR benchmarks (PSNR/SSIM on synthetic + real), not RAW-negative fidelity,
  CPU/GLES parity, or uncertainty/phase gating.
- Our adaptation (deliberate, non-neural): estimate a bounded anisotropic Gaussian PSF
  per frame/region from edge spread + gyro path; include it in forward projection and
  confidence; use an explicit Gaussian pyramid for alignment. Explicitly NOT copied:
  learned kernel estimator, deformable feature alignment, AKAB/RCAB reconstruction.
- Code: `PsfSr.kt:28-112` (`PsfEstimation.estimate`, `kernel`), `Pyramid.kt:36-133`
  (`GreenPyramid.build`, binomial reduce, `REDUCE_VARIANCE`), `Pipeline.kt:407-432`
  (PSF stage), `Reconstruction.kt:400-407` (SR memory gate).
- Tests: `ReconstructionTest` PSF/SR branches (monotonic history, not adjoint-correct);
  `DngAdmissionF4Test` writer coherence; **no calibrated edge-spread/blur-kernel test**.
- Status: **partial**. Pyramid exists and is tested (`PyramidTest`); PSF is a
  `|Laplacian|/|grad|` heuristic without calibrated conversion, trust is edge-count
  based, kernels truncate support, gyro/focal never supplied (`PsfSr.kt:63-94`,
  `Pipeline.kt:414-417`), and `trusted` never gates SR. Full calibration/trust
  enforcement is **deferred to F5**; current SR must therefore fail safe to native
  (see §4 D3).

### P2 — 2406.17869v1, Base Frame Selection (NEBI + FSN)

- Source: md §§1,3–5 (esp. §3 NEBI benchmark, §4 FSN: §§4.1–4.4 extractor/CMA/FCM/base
  estimation, §5 Tables 1–2, Fig.5); PDF NEBI hits confirm identity.
- What authors do: non-uniformly exposed bursts (14 frames, 0.01–0.14 s) + Frame
  Selection Network that predicts the best base-frame index (cross-entropy against
  highest-PSNR frame) as a plug-in to existing BISR nets; shows first-frame-is-best
  is suboptimal under varying degradation.
- Assumptions/limits: RGB SR networks (BIPNet/DeepSR/DeepRep), learned FSN, 14-frame
  non-uniform exposures with blur/noise variation; ground-truth base = highest metric;
  no RAW-domain fusion, no calibrated PSF, no CPU/GLES parity, no uncertainty gates.
- Our adaptation: deterministic reference scoring (clipping, sharpness, noise, gyro
  blur, pose centrality, exposure utility, focus/AE penalty) with weighted total and
  temporal-median tie-break; selection from the captured burst, never privileged
  first frame. Explicitly NOT copied: Frame Selection Network.
- Code: `ReferenceScoring.kt:34-88` (weights, `TIE_BAND`, `selectReference`),
  `Reconstruction.kt:199-306` (`referenceFeaturesImage`, `gyroBlur`, `focusAePenalty`),
  `Pipeline.kt:153-188` (metrics/select stages, CLI `--ref` wins).
- Tests: `ReferenceScoringTest` (scalar totals/ties), `ReferenceFeaturesTest` (7:
  all-plane clipping incl. red-only/single-green, own-model sharpness, gyro pose with
  timestamp fallback + provenance, unknown AF/AE = 0.5), `PipelineGyroTest`,
  `CacheResumeTest.cliRefBeatsManifest`.
- Status: **verified adaptation** for same-exposure ZSL reference selection. Limits:
  non-uniform exposure bursts are rejected by `Radiometry.exposureScale` [0.5,2.0]
  (by design; bracketed HDR stays in `HdrRawMerge`), so NEBI-style exposure variation
  is a **deliberately excluded** operating point, not a supported claim.

### P3 — 2503.08300v1, Equivariant Alignment

- Source: md §§1–3 (esp. §3.1 motivation/equivariance, §3.2.2 alignment module with
  rotation-translation `f_j`, image-domain loss Eq.4, §3.3 Thm.1/Cor.1 sketch, §3.2.3
  MDTA/INR reconstruction, Fig.1–2); PDF equivariant context confirms identity.
- What authors do: Eq-CNN (`ENet`) with learnable rotation-translation matrices;
  alignment transform learned with explicit image-domain supervision then applied in
  feature domain (theoretically grounded vs. DCN/local-only or flow-only);
  reconstruction via MDTA + INR, sRGB output.
- Assumptions/limits: learned Eq-CNN with discretized angles (error bounds in Thm.1);
  burst misalignments modeled as rotation+translation from handheld shifts; RGB/sRGB
  benchmarks; no RAW CFA handling, no calibrated noise/uncertainty, no GLES parity.
- Our adaptation: keep global rotation/translation/affine transforms explicit;
  gyro-seeded rotational homography `H=K·R·K⁻¹` with fallback to identity; coarse
  global translation/affine via robust green-proxy gradients with held-out improvement
  gate; pyramidal cell LK translation-first with conditional local affine behind
  Hessian-condition + held-out gates; never smooth across strong reference gradients
  or large FB inconsistency. Explicitly NOT copied: Eq-CNN, Restormer/INR.
- Code: `GlobalAlign.kt:103-215` (`seedFromRotation`, `refineCoarse` with cross-term
  Jacobian, held-out affine gate), `CellFlow.kt:129-258` (`estimate`, coarse-to-fine
  with `cellL*span` parent mapping, `refineAffineLevel` with even-fit/odd-gate),
  `Pyramid.kt`, `Pipeline.kt:202-323`.
- Tests: `AlignmentTest` (15: fractional/off-center rotation/affine-shear/exposure/
  periodic/motion-boundary/odd-sizes/uncertainty/threads/strict-mode/global-improves),
  `GyroSyncTest` (10), `PyramidTest` (5). Synthetic renderers are independent
  (`AnalyticScene.kt`).
- Status: **verified adaptation** for explicit geometry + conditional affine.
  Brightness bias/gain per cell (plan "may") is **deferred**; FP16 is storage
  emulation only (`Fp16.kt`), not a hardware oracle.

### P4 — 2503.19634v2, BurstMamba (keyframe-centric SSM, GAS, wavelet conditioning)

- Source: md §§1–4 + Apps (esp. §3.1 keyframe SISR stream, §3.2 burst prior stream,
  §3.3 GAS, §3.4 ψ-S6 wavelet conditioning, §4 scaling Fig.1 L=1→14 +6 dB at +1.3 GFLOPs/frame,
  §2 distinction vs. QMambaBSR); PDF Title/47 BurstMamba hits confirm identity.
- What authors do: decouple keyframe SR (high-capacity Mamba SISR) from burst priors
  (lightweight stream, one-way residual injection); GAS (gather corresponding features,
  aggregate, scatter residuals to native views, avoid early warping); DTCWT-conditioned
  selectivity toward HF; transformer-free SSM routing; RGB/RAW benchmarks.
- Assumptions/limits: learned Mamba routing, 14-frame bursts, benchmark PSNR/FLOPs;
  all frames are LR with subpixel jitter; no physical PSF calibration, no RAW-negative
  pipeline, no GLES parity, no per-pixel uncertainty/phase gates as we define them.
- Our adaptation: reference-first initialization, per-frame gather, residual-only
  corrections, phase-coverage gating; native-view preservation (no early warping;
  bilinear only within one CFA plane); burst-wide two-pass consistency (first-pass
  consensus → second-pass Tukey), not a reproduction of SSM/GAS/wavelets.
  Explicitly NOT copied: Mamba/state-space model, learned wavelet conditioning.
- Code: `Merge.kt:41-143` (`GatherMerge.merge` two IRLS passes, `BurstObservation.sample`
  same-plane bilinear with tap-level clipping + `w²·var` propagation),
  `QuadSplit.kt:57-155`, `Reconstruction.kt:349-372` (`sharpNormQuad`),
  `PhaseHistogram.kt`, `Pipeline.kt:324-405`.
- Tests: `MergeRegressionsTest` (12, incl. noisy-ref-uses-clean-evidence,
  calibrated-variance, moving-interior counts, tap-level clipping, variance persistence),
  `CfaIdentityTest` (6), `ReconstructionTest` (14).
- Status: **partial (native) / deferred (SR)**. Native gather + two-pass residual
  structure is implemented and tested. BUT per-pixel merge weighting currently
  multiplies by tracking confidence (`C_hessian·C_res·C_fb·C_texture·C_bounds`),
  which suppresses flat/shadow denoising and makes the merge reference-dominated —
  the exact patchy-noise failure in §4 D1. GAS/wavelet routing are **deliberately
  excluded** (no neural runtime). Local phase/conditioning diagnostics are pooled,
  not per-region/per-plane — **missing**, F5 prerequisite.

### P5 — 2408.08665v2, QMambaBSR (QSSM + MSFM + AdaUp)

- Source: `2408.08665v2.pdf` stream text (QSSM/AdaUp contexts above) + plan §2/QMamba
  summary (§§3.1–3.4 per plan); **no `.md` in corpus** (see §1). Equation/figure/page
  numbers are not quoted from guesswork.
- What authors do (per plan + stream snippets): QSSM fuses noisy base + current-frame
  features before base-conditioned intra/inter-frame querying; MSFM mixes local CNN +
  channel Transformer + directional SSM; AdaUp adapts transposed-conv kernels from
  pooled feature distributions; first-frame base, HR RGB output.
- Assumptions/limits: learned QSSM/MSFM/AdaUp; first frame as base; RGB benchmarks;
  no reference selection, no physical PSF, no RAW fidelity/parity/uncertainty gates.
- Our adaptation (engineering inference, not equivalence): burst-derived preliminary
  estimate (pass-one consensus) followed by noise-aware consistency checks
  (standardized residuals with `varS+varX0+alignVar`, Tukey/Hüber); noisy reference
  is never ground truth (validity-respecting floor, tap-level clipping, second-pass
  reweight around consensus). Pyramid/support checks are engineering analogues of
  multi-scale reasoning, NOT implementations of MSFM; measured sampling/PSF geometry
  serves AdaUp's goal but IS NOT AdaUp and inherits none of its guarantees.
- Code: `Merge.kt:88-109` (pass-one `refValue` gate → pass-two `first[]` consensus +
  `standardizedResidual` + `tukey`), `RobustWeights.kt:76-113`, `CellFlow.kt:278-327`
  (`SigPair` combined ref+src noise, `scoreVectors`), `Pipeline.kt:324-364`.
- Tests: `MergeRegressionsTest.noisyReferenceUsesCleanEvidence`,
  `AlignedSharpnessRatioIsExposureUnbiased`, `AlignmentTest.exposureVariationStillAligns`.
- Status: **partial**. Two-pass consensus exists, but pass one is still
  reference-anchored (`nr` vs `refValue`, `Merge.kt:89`) and per-pixel weights still
  carry tracking confidence, so a noisy base + low texture reintroduces bias via the
  back door (see §4 D1). Robust averaging is NOT QSSM; pyramid is NOT MSFM; Gaussian
  PSF is NOT AdaUp. Do not transfer QMambaBSR benchmark gains.

## 3. PhotonCamera reference (non-paper, user-supplied baseline)

Location: `/Users/monikamalinowska/AndroidStudioProjects/RawLens/references/PhotonCamera`
(`app/src/main/assets/shaders/merge/`: `merge00`, `mergeAlign`, `mergeCombineWeight0/1`,
`noiseblend`, `merge2o`; `processing/opengl/scripts/`: `AverageRaw`, `FlowNetAlignment`,
`PyramidAlignment`).

What PhotonCamera does differently (read, not ported):

- Merge weight is Wiener-like `comb = N²/(excess²+N²)` with `excess = localDiff −
  noiseFloor` over an 11×11 kernel-weighted window plus a signed-statistic
  anti-ghost term (`localSigned − signedFloor`), floors `0.7979·σ` (E|X| for Gaussian).
  Flat areas: `localDiff ≈ floor → excess ≈ 0 → comb ≈ 1` (full averaging).
  One weight per Bayer quad (`robustWeight = min(r,g,b,a)`) avoids chroma shifts.
- Alignment falls back to the coarse warp in flat/occluded areas (optical-flow
  refinement returns `ivec2(0)` unless the selected block beats zero-offset by 5%
  AND by 2σ of window SAD); it never zero-weights flat pixels from the merge.
- Our code does the opposite in shadows: `w_align = flowConfidence` with
  `C = C_hess·C_res·C_fb·C_texture·C_bounds` (`CellFlow.kt:300-312`) → flat
  (`C_tex≈0`, `C_hess≈0`) gives `conf≈0.12` (measured sea-street mean 0.12, >0.25
  frac 0.16) → per-pixel weight `(1/var)·0.12·motion·sharp` vs reference `1/var` →
  reference holds ~54% of total weight with 7 sources (street measurement §5) →
  only ~1.7× variance reduction instead of 8×, i.e. patchy noisy/clean regions.
  Sea (true 8-frame subset, §5) is worse: coverage 0.03 + `minUsefulCoverage 0.15`
  globally rejects all 7 sources → reference-only (`accepted=1`, `fallback=1`).

Conformance reading: none of P1–P5 support zero-weighting flat regions from the
merge. PhotonCamera, QMamba-consistency, GAS native-view preservation and the
equivariant global-transform prior all point the same way: flat/static pixels should
average (denoise) via the global/coarse geometry with consistency gating; Hessian/
texture belong to SR/detail gating (uncertainty, phase), not to the native merge weight.

## 4. Prioritized discrepancy list (code evidence → fix → lane)

Pre-F5 batch = D1 (+ its sea half D1b) + D2 + D3. Nothing here implements F5 SR
reconstruction or GLES; unsafe SR is made to fail safe.

### D1 (P0, native): flat/shadow merge is reference-dominated via tracking confidence

- Evidence: `CellFlow.kt:300-312` (`cTex`, `cHess` multiply into `conf`);
  `Merge.kt:83-104` (`w_align = conf`, `weight = (1/var)·conf·motion·sharp`);
  street run (§5): mean conf 0.12, `acceptedCount` mean 7.86 yet effective
  var reduction ~1.7× (reference 54% weight); `merge-unc` honest large-unc for flat
  (`AlignmentTest.flatCellsReportLargeUncertainty`) proves uncertainty already
  separates SR gating from merge need.
- Violates: plan §9 (weights must denoise; `1-confidence` is not pixel uncertainty,
  §8.3) + P4/P5 rationale (consistency, not trackability, gates fusion) +
  PhotonCamera Wiener reference.
- Failure: shadows stay at base-frame noise; adjacent textured cells clean → patches.
- Reproducer: street 8-frame full run (existing `/tmp/sea-burst` treated as STREET) +
  new `FlatConsistentBurstMergesEvenly` synthetic (flat 0.18 + σ0.01×4, identity flow
  with conf 0.12): merged MSE must beat base by >2× with balanced weights, not
  reference-dominated.
- Fix (this batch): split consistency from trackability. `CellFlow.scoreVectors`
  also exports `mergeConf = C_res·C_fb·C_bounds` (no Hessian/texture); `FlowField`
  carries it; pipeline persists `flow-merge-*.bin`; `Merge` weights by `mergeConf`.
  Tracking `confidence` stays for coverage/quality/SR. Bump algorithm 7→8.
- Lane: **pre-F5** (native correctness + F5 prerequisite).

### D1b (P0, native/sea): good-residual low-coverage frames globally rejected → sea reference-only

- Evidence: `CellFlow.kt:241-243` joint gate
  `(cov≥0.60 || (cov≥0.15 && med<4)) && med<8`; sea run (§5): med 0.36–0.49 (excellent)
  but cov 0.03 → all 7 rejected → `accepted=1`, `fallback=1`, zero denoising.
  Street med 0.16–0.30/cov 0.16 accepted only via the `0.15` floor.
- Violates: same rationale as D1 (coverage = trackability for SR/detail, residual =
  consistency for denoising) + user requirement to properly merge sea bursts;
  reference-only fallback is not reconstruction proof.
- Failure: entire sea burst keeps base-frame shadow noise; no amount of per-pixel
  fixing helps because sources never reach the merge (`Pipeline.kt:343-345`).
- Reproducer: sea 8-frame subset run (`/tmp/sea-burst-true` → `/tmp/sea-out-true`);
  assert post-fix `accepted≥4` with water rejected locally (motion/Tukey), rocks
  denoised, no global ghost.
- Fix (this batch): accept for merge on consistency (`med < maxUsefulResidual`)
  regardless of coverage; keep coverage for SR eligibility/quality. Update
  `flatRegionRejectsHonestly` expectation (constant flat with zero motion accepts
  with near-zero displacement; uncertainty stays large for SR). Version bump covers it.
- Lane: **pre-F5**.

### D2 (P1, measurement): SR uncertainty gate uses dimensionless `1−confidence` as quad pixels

- Evidence: `Pipeline.kt:457-469` (`unc += 1−c`, `meanUnc` vs 1/3 quad) vs
  `CellFlow.kt:315-324` calibrated `unc` (CRLB, [1e-3,8] quad px) + finding 11 +
  plan §8.3 (`1−confidence` is NOT pixel uncertainty).
- Failure: SR eligibility compares wrong units; flat (large unc, small `1−c`?) and
  textured gating both untrustworthy — blocks a valid SR operator.
- Fix (this batch): mean `uncX/uncY` over contributing (`conf>0.25`) cells for the
  gate; `SrEligibility.decide` documents quad-pixel units. No SR reconstruction change.
- Lane: **pre-F5** (prerequisite for valid SR operator).

### D3 (P1, safety): untrusted PSF + pooled phase + brightness-MTF can still admit SR

- Evidence: `PsfEstimation.trusted` (edge-count, `PsfSr.kt:73`) never gates
  (`SrEligibility.decide` ignores it; `Pipeline.kt:481-514` doesn't pass it);
  `Reconstruction.mtfRatio` is luminance-energy ratio, not MTF (finding 11; constant
  0.2→0.3 gives 2.25); `phaseOffsets` pools unrelated cells globally
  (`Reconstruction.kt:386-398`); `Backprojection` adjoint inconsistency + early-stop
  offset (findings 9–10) untouched.
- Failure: unsafe SR activation on unqualified bursts (wrong detail, non-adjoint
  updates, brightness-gated "MTF").
- Fix (this batch, fail-safe only): require reference + used-frame PSFs `trusted`
  for `Eligible`; label `mtfRatio`/pooled-phase diagnostic-only in KDoc; keep native
  fallback (`NativeOnly`) as the only production path until F5 rebuilds the operator,
  adjoint tests, local per-plane conditioning and calibrated MTF. Forced SR stays
  `forced-experimental`, never bypassing memory/safety.
- Lane: **pre-F5 safety**; full operator rebuild stays **F5**.

### Deferred to F5 (not regressions, prerequisites noted)

- F5-1: matched forward/adjoint `A`/`Aᵀ` with dot-product + finite-difference tests
  (fractional/nonuniform/borders/anisotropic), output-owned gather (findings 9–10).
- F5-2: local per-plane phase/conditioning, scale-specific bins, calibrated optical MTF,
  trusted regional PSF with support/covariance enforcement (findings 11, 22).
- F5-3: 1.5× before 2× against latent truth + native+Lanczos baselines; labeled
  native-derived support only; scaled CFA offsets/crop/calibration provenance.
- GLES (G1–G3) starts only after F6 desktop qualification; no phone CPU reconstruction.

## 5. Measurements used (commands/results, both bursts)

- Baseline suite: `./gradlew :tools:burst-reconstruction-desktop:test --rerun-tasks
  --console=plain` → **163 tests, 0 failures** (23 suites).
- STREET (existing 8-frame `IMG_20260914_*`, `/tmp/sea-burst` → `/tmp/sea-out`, full
  frame 4080×3060, `--force`): `reference #4 accepted=8`, coverage ~0.16/frame,
  global residuals 0.25–0.76, `acceptedCount` mean 7.86, `fallback` 0.002, quality
  0.236, flow conf mean 0.12 (`>0.25` frac 0.16). Effective denoising ~1.7× var
  (reference-dominated), not 8×. DNG tags coherent; `noiseProvenance=omitted-varying-support`.
- SEA (true 8-frame subset `RAW_1788587993*–7999*` from `Sea (1).zip`,
  `/tmp/sea-burst-true` → `/tmp/sea-out-true`, full frame): `reference #4 accepted=1`,
  coverage ~0.03/frame, global residuals 1.33–2.17, cell med 0.36–0.49, merge
  `count` 1.0, `fallback` 1.0 — pure reference fallback, zero denoising.
- Artifacts are scratch under `/tmp` (not fixtures); originals unmodified.
- DNG interoperability beyond ExifTool `Validate: OK` on the earlier F4 street result
  remains pending for these two outputs (recorded, not passed).

## 6. Handoff: is F5 ready?

No — D1/D1b/D2/D3 above are the remaining pre-F5 prerequisites. After this batch
(plus green 163+ suite and refreshed street+sea runs showing multi-frame accepted
support with local water/motion fallback and balanced flat weights), F5 can start
on a trustworthy native operator. Do not port current SR/Adjoint/PSF/phase to GLES.

## 7. F5 addendum (2026-09-15, v8 → v9): matched SR operator, calibrated gates

Scope: findings 9, 10, 11, 22 + prompt F5 items. No neural runtime added; no
claim transferred from paper benchmarks. QMambaBSR AdaUp remains explicitly NOT
our PSF formula (deterministic Gaussian + edge calibration below).

- Matched A/Aᵀ (`SrOperator.kt`): per-plane CFA offsets, scaled PSF kernels
  (C·s², honored radius), fixed-point flow inversion with affine-exact branch and
  stored-spacing lookup, tap-level clipping/validity via `BurstObservation`,
  mergeConf-at-reference + native-fallback motion gating, bilinearTranspose with
  boundary normalization in BOTH paths. Dot-product (<Ax,y>=<x,Aᵀy>) and
  finite-difference gradient tests FIRST (`SrOperatorTest`, 5 tests: fractional
  1.5x/2x, varying flow, anisotropic blur + borders, CFA offsets, gradients).
- Backprojection (`PsfSr.kt`): uses the matched operator; iterate evaluation =
  measured pre-update residual associated with the returned image; best-restore
  returns the best EVALUATED iterate (never an unevaluated last update); history
  non-increasing by construction (`SrQualificationTest.objectiveMatchesReturnedImage`).
- PSF: inverted edge-ratio calibration (σ=EDGE_K/ratio, EDGE_K=0.55 validated on
  synthetic blurred edges within 0.55px), stride-1 sampling, noise-gated edges,
  trust = edges>64 && raw≤MAX (MIN-clamp stays trusted/conservative);
  support honored (no silent truncation). Street remeasured σ≈0.35 (was 1.5).
- Gates (`SrEligibility.decide`, new signature): trusted-PSF fail-closed, physical
  PSF-MTF at Nyquist (mean>0.02) REPLACING the brightness ratio, local per-tile
  phase (4×4 cells, ≥2 frames/tile, 0.6 tile fraction, independent per scale),
  calibrated quad-pixel uncertainty. Brightness-MTF and pooled-phase removed.
- Qualification (`SrQualificationTest`, 7): known-blur recovery, noisy-flat
  untrusted, MTF ordering, objective agreement, sr15BeatsNativeUpsample (synthetic
  truth), moving-occluder native hold, huge-blur NativeOnly. Integer-only →
  NativeOnly via local phase (`ReconstructionTest.srGatesBehave` rewritten).
- Per-plane note: planes share flow geometry with explicit 0.5-quad lattice
  offsets in the operator (phase diversity across planes preserved); affine
  per-site evaluation remains translation-based in the merge path (documented,
  honest model labels retained) — full per-site affine SR is G2/G3 work.
- Status of F5 items: verified adaptation (operator/adjoint/iterate/PSF/phase/MTF/
  1.5x-first). 2x path shares the operator (adjoint tests cover 2x) but production
  2x on real bursts is NOT qualified here (no real-burst 2x gate pass claimed).

## 8. v10 HF-detail tuning addendum (2026-09-15)

Complaint: everything too smooth/mushy vs PhotonCamera. Method: one-at-a-time
sweeps on the street-center 512² crop (8 static frames) + synthetic PSF probe,
ghost/noise guards held.

- Native gates (Tukey 4.685→6/8, sharpMax 4→8, motion (2,4)→(2.5,5)) do NOT move
  detail on static content (dP90 0.0798→0.0796, ghost flat): the native merge is
  at its resampling ceiling, not over-rejecting. Wider motion gates only add
  noise (flatN 0.0106→0.0115). Unchanged.
- PSF probe (independent numpy renderer, step edge σ∈{0.5,0.8,1.0,1.5}):
  EDGE_K=0.55 underestimated σ ~35% (0.37/0.47/0.64/1.31) → systematic
  under-deblurring. EDGE_K=0.8 centers it (0.54/0.69/0.94/1.91). Changed.
- SR iterations 3→5: eligible-crop history still improves ~30%/iter at 3
  (9.5→6.2→5.0→4.3→3.9), early-stop guards reversal. Changed (default).
- Structural note: PhotonCamera merges with INTEGER-texel-block shifts only
  ("fractional resampling is illegal here" — mergeCombineWeight0.glsl), i.e. no
  resampling blur but no subpixel alignment either; ours resamples subpixel
  (better alignment/denoising, mild resampling softness) and recovers the delta
  honestly via deconvolution where tracked. Dark/untracked regions stay native
  by design — no invented texture.
- Results @384 quads street-center: SR15 dP90 0.0892 (+14% vs v9 SR,
  +62% vs photon-upsampled, +68% vs native-upsampled), flatN 0.0107 (≈native),
  ghost 0.039 (better than native 0.055 / photon-up 0.046). Native @512 still
  ~4% softer than photon-center with lower noise (documented tradeoff).
- Sea TL/BR native unchanged (SR-ineligible dark rock/water: honest fallback).

## 9. v11 memory addendum (2026-09-15)

30-frame full-res merge OOM fixed without touching a single merged pixel:
file-backed spill (deterministic rule), halo-sized band reads, tiled gather
and streamed SR, all pinned bit-exact against resident execution (13 tests).
PhotonCamera PR #179 contributed engineering patterns only (band streaming,
free-after-use, no heap duplicates — its GPU/EXIF/JPEG specifics do not apply
here); no paper claims involved. Sea 30-frame full-res now merges 30/30 under
-Xmx2g in ~6.5 min with photon-matching radiometry. Full-res 30-frame SR remains
memory-gated unless eligible; crops exercise the SR path.

## 10. P6 — Dudhane et al., BIPNet: Burst Image Restoration and Enhancement (CVPR 2022)

- Source: `/Users/monikamalinowska/Downloads/Dudhane_Burst_Image_Restoration_and_Enhancement_CVPR_2022_paper.md`
  (50KB Markdown, no PDF pair in `/Users/monikamalinowska/Pdf`; full text read:
  abstract, §§1–5, Tables 1–6, Figs. 1–8, references). Section/equation/figure
  numbers below refer to that file.
- What authors do: RAW-burst network (6.67M params, L1 loss) with three stages —
  (1) edge-boosting feature alignment EBFA (§3.1: FPM residual-global-context
  denoiser + modulated deformable alignment + refined-aligned-features RAF with
  an explicit high-frequency residue added back, `e = ȳ + W3(ȳ − y_br)`);
  (2) pseudo-burst fusion PBFF (§3.2, Eq.4: channel-wise cross-frame exchange so
  each pseudo-feature carries complementary properties of ALL frames, + shared
  U-Net multi-scale MSF); (3) adaptive group upsampling AGU (§3.3, Eq.5: dense
  attention per 4-feature group, progressive ×2 levels; uniform weights for
  textureless regions to denoise, low weights for misaligned frames to
  anti-ghost). Results: ×4 SR 41.93 PSNR SyntheticBurst / 48.49 BurstSR,
  ablations Table 2 (DAM +1.85dB, RAF +0.71, PBFF +1.25, AGU +1.0, EBFA +0.3).
- Assumptions/limits: learned deformable alignment/attention/upsampling, 14-frame
  48×48 RAW bursts, RGB outputs, benchmark PSNR/SSIM; no physical PSF, no
  RAW-negative pipeline, no CPU/GLES parity, no uncertainty/phase gates.
- Our deterministic adaptation (no neural runtime; benchmark gains never
  transferred): (a) RAF analogue under study as bounded HF-rescue with
  denoised gating (NOT reference-HF injection — that would launder base noise
  as detail); (b) PBFF analogue = our two-pass burst-consensus preliminary
  estimate (v8) rather than late fusion; (c) AGU analogue = uniform mergeConf
  for textureless consistency + motion/Tukey anti-ghost gates (already), with
  per-cell→bilinear weight smoothing to remove square seams (v12 fix below).
  Deformable alignment, U-Net MSF and learned dense attention explicitly NOT copied.
- Code: `Merge.kt` (mergeTiled bilinear flow/weight resampling), `PsfSr.kt`
  (streamed SR inversion sampling), `CellFlow.kt` (kept explicit geometry).
- Tests: MergeTiledTest block-seam cases, SrStreamTest, block-periodicity
  probes on sea outputs (quality-map 10.4x steps pre-fix).
- Status: **partial** (fusion-consensus + uniform-for-textureless + anti-ghost
  verified adaptations; learned modules deliberately excluded; HF-rescue rule
  pending measurement in the de-plasticize batch).

## 11. v12 addendum (2026-09-15)

- Squares: BIPNet-AGU lesson (uniform textureless fusion + low misaligned
  weights) was already our mergeConf/motion design; the SEAM defect was
  nearest-cell sampling of both. Fixed with bilinear flow/weight resampling
  (merge + SR inversion), pinned by blend-exact units and a seam-bound test.
- Detail: linear sharp ratios (bounded) implement "sharper frames earn
  proportionally higher weights" deterministically; pinned by a sharp/blur fixture.
- Learn loop: deterministic per-burst tuning adaptation from SingleCompare
  summaries with ghost vetoes — the operational form of "uniform weights for
  textureless, low weights for misaligned" tuned by measurement, not by hand.

## 12. HDR+ frequency merge (martin-marek hdr-plus-swift/pytorch, studied 2026-09-16)

- Sources: `burstphoto/merge/frequency.swift` (orchestration: 8×8 tiles, RMS
  noise, normalized mismatch, highlights norm, robustness/read/max-motion
  norms, 4 shifted runs, deconvolution, raised-cosine overlap, tile-border
  blending) and `burstphoto/merge/frequency.metal` (`merge_frequency_domain`
  Wiener kernel + `deconvolute_frequency_domain` + `reduce_artifacts_tile_border`).
- What they do: per-frequency Wiener shrinkage anchored at the reference —
  `w = |D|²/(|D|² + magnitude·motion·noise·highlights)`, single weight across
  channels (mid-mean, anti-color-artifact), pairwise accumulate
  `ref·w + aligned·(1−w)`, mismatch-normalized motion boost (static averages
  hard), Fourier subpixel refinement, mismatch-weighted deconvolution.
- Our deterministic adaptation (no FFT): spatial two-band analogue —
  binomial-LOW robust-averaged with full-agreement joint weights plus a
  green-joint reference-anchored HF correction with static-boosted Wiener
  (HfWiener.kt). Learned/FFT/GPU parts explicitly NOT copied; benchmark gains
  never transferred.
- Code: `HfWiener.kt`, `Merge.kt` (both gather paths), `Tuning.kt`
  (hfBaseC/hfMaxMotion). Tests: HfWienerTest, MergeTiledTest equivalence +
  sharp/blur + maze + seam suites, calibrated-variance re-pass (map/pred 1.23).
- Status: **verified adaptation** (detailKept 0.98 sea-8, ghost steady,
  occluder holds native, DC exact).
