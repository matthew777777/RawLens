# MFSR "Wronski 2.0" (owner goal, 2026-10-22)

Owner: dig deep and improve MFSR beyond the paper ("fix Wronski itself"),
not only match it. Starting point: the softness investigation below.

Status 2026-10-23: this is checklist section M, item C13 in
`docs/refactor/release-2.0-checklist.md` (moved last: after everything
else). Z1 and C9-C14 were merged into C13 — the zipper, the dim-burst
divergence, auto exposure and the core reconstruction are one
programme, diagnosed together before any fix. The checklist is the
living tracker (checkboxes/tags); this doc is the deep record.
Default reconstruction kernel is now Wronski S.1; KernelNet stays
selectable in Settings -> Burst but is not the default until fixed
(owner 2026-10-23).

## Report (owner): MFSR sometimes soft, hazy, motion-blurred

## Findings (desktop, 8 bursts in ~/Downloads/Photos/Photos, vs LibRaw AHD of the reference frame)
- Not S.3, not noise correction, not KernelNet width (x0.25..0.05) nor the
  Wronski kernel: <= 1 % edge-sharpness difference, visually identical; a
  narrower kernel only raises noise.
- Inputs are fine: exposure drift <= 2.5 %, frames equally sharp, real
  multi-pixel motion between frames (median 0.2..15 px).
- Robustness merges almost everything in static scenes (mean R 0.95-0.99;
  sea burst rejects up to 28 % on moving water).
- The softness is in the reconstruction: one frame through MFSR is
  0.91-0.96x AHD edge sharpness, the 8-frame merge only 0.93-0.98x, while
  noise falls to 0.12-0.46x AHD. Fine low-contrast texture (distant
  hills, foliage) is smoothed away: "soft / hazy". IMG_20260928_185731_891
  cypress crop: AHD 1.00, single 0.93, merge 0.97, 2x merge -> 1x 0.97,
  merge + Richardson-Lucy (sigma 0.75, 20 it.) 1.07.
- Merging at 2x and resampling to 1x gives no gain.
- MFSR JPEG path has no adaptive exposure (code: "no RGB leg yet"): flatter
  than normal shots. Now tracked as C14 (see Programme below). (Lens
  shading IS applied per frame in decodeSharp; the vignetting seen on
  desktop is the CLI ignoring the DNG GainMap.)
- Motion blur: not reproduced on desktop (CLI merges F00..F07, ref F00; app
  takes newest frames, sharpest-of-3 ref, keeps AE-searching frames). Needs
  one app shot that shows it + its log.
Images: ~/Desktop/mfsr_soft. Debug: SRP_R_DUMP=<dir> dumps per-frame
robustness maps and prints mean / rejected share.

## Direction (owner, 2026-10-22)
Fix the MFSR core itself. Post-processing is NOT the answer: no capture
sharpening, no exposure or tone changes to hide the softness. (The RL
deconvolution test above only measured how much detail the core leaves
on the table.) The merged result must be sharp on its own.

## Core work (to plan)
1. Why the multi-frame merge adds so little resolution despite real
   sub-pixel motion and ~98 % acceptance: measure alignment precision
   (ICA residual vs true shift), sample density per output pixel, kernel
   shape vs that density, accumulation / normalization, robustness
   smoothing of the weights. (= C13a diagnosis, below.)
2. Why a single frame through the reconstruction is softer than a plain
   demosaic (0.91-0.96x AHD): kernel law, gather footprint, guide/grey.
3. Go beyond the paper where it is weak ("fix Wronski itself"): e.g.
   density-adaptive kernels, joint demosaic/SR (Lecouat et al. 2021),
   better sub-pixel alignment; each change measured against AHD and the
   burst set, no AI-only shortcuts without a reference. (= C13b/C13c.)
4. Motion-blur cases from the app (frame selection, AE-searching frames):
   needs one app shot showing it + its log.
5. Plus mfsr-paper-gaps.md items (dim-burst divergence = C9, scale > 1
   for zoom + S.3 thresholds + failed-tile alignment = C10).

## Programme (checklist §M / C13, merged 2026-10-23)

Merged-in items (IDs and tags kept from the checklist):

- **Z1 [2.0] — linear DNG zipper.** The MFSR linear (demosaiced RGB)
  DNG shows dots and small squares alternating one dark, the next
  brighter, along edges and fine detail. Root cause to find in the
  merge / linear DNG path (CFA phase, per-channel accumulation or
  normalisation, green split, DNG writer layout), no masking filter.
  Suspect list overlaps the core hypothesis below; diagnose with
  C13a, do not fix blind. Cross-ref: checklist G1 (green
  equalisation) checks Gr/Gb mismatch first as a candidate cause.
- **C9 [2.0] — dim-burst divergence** vs the Python reference (70.5 dB).

## Research scan 2026-10-23 (hypotheses, not measurements)

Online papers + open-source repos, 6 angles; softness findings are in
the Report above.

**Re-evaluate before starting any candidate below:** the scan's
flow-error / density numbers are hypotheses, not measurements; the
Lecouat repo is 404 (paper-only), no Vulkan prototype was benchmarked,
and exact Lecouat registration-error table values are unconfirmed.
Re-check premises, licences and costs first.

**Root-cause hypothesis:** Wronski Eq.1 is order-0 kernel regression
(Nadaraya-Watson) — positive-lobe averaging, no local slope fit, no
PSF/pixel-integration forward model, kernel ignores realised sample
density. Predicts all measured symptoms (single-frame 0.91-0.96x AHD,
merge 0.93-0.98x, noise 0.12-0.46x, 2x->1x no gain, narrower kernel =
only noise).

**Ranked candidates** (gain x evidence / Vulkan port cost):

- **C13a [2.0] Diagnosis FIRST (zero new code):** synthetic
  known-shift bursts (0.05 px steps) through Align/Ica; histogram
  frac(flow), ICA residual RMS/bias per tile, phase-coverage per
  output pixel. Tests whether acceptance (R 0.95-0.99) != accuracy
  and whether fractional phases cluster. Gate for C13b-C13e.
- **C13b [2.0] Density-adaptive + order-1 kernel:** log sample
  density/support vs sharpness; scale Omega by realised density
  (Drizzle pixfrac, BSD-3); then local-linear steering kernel
  regression (Takeda, equations only — toolbox licence unstated,
  related patent ~2029).
- **C13c [2.0] Iterative MAP reconstruction (structural fix):**
  stripped Lecouat et al. 2021 "Lucas-Kanade Reloaded" / Farsiu
  (forward DBW model + BTV, 10-20 iterations, ICA flow as init,
  per-tile affine refine against the reconstructed HR image, not
  the LR reference). Paper-only (repo 404). Only class that inverts
  blur inside the merge.
- **C13d [2.0] Phase-correlation alignment cross-check:**
  Guizar-Sicairos single-step DFT (BSD-3, scikit-image), OpenCV
  phaseCorrelate (Apache-2.0) as offline oracle; ship only if C13a
  shows ICA RMS > ~0.1 px.
- **C13e [2.0] Diff vs ImageStackAlignator** (GPL-3.0, only
  GPL-compatible + portable repo found): documents Wronski paper
  errors inherited via Jamy-L (subsampled tile structure tensor,
  kernel size, alignment outliers).
- **C13f [2.0.x] Extend KernelNet (do not replace):** per-frame
  kernel prediction, density/SNR-conditioned width, learned
  deconv-kernel params; train on own bursts (AHD/RL oracles), export
  via existing pnnx->ncnn path. Note: KernelNet is currently not the
  default (see Status) — this item is also where its fix lands.

**Calibration:** pure-reconstruction gain is realistically bounded at
parity to ~+7 % edge sharpness (RL probe 1.07x); larger gains imply a
learned prior + hallucination risk (teeth metric is the guardrail).

**Do NOT pursue:**
- Learned burst nets (DBSR CC BY-NC-SA = GPL-incompatible; BIPNet/
  EBSR no licence; Burstormer/BSRT PyTorch transformer, no Vulkan 1.2
  path, no Android repo found).
- RAFT as shipped flow (EPE ~2.9 px >> 0.1 px need; offline
  falsification oracle only).
- Capture sharpening as the fix (diagnostic/headroom probe only, per
  Direction above).
- PhotonCamera SABRE (checked 2026-10-23: full reverse-eng. MGC
  SABRE, but 1x denoise merge — no density/forward-model/2x help;
  usable only as same-burst A/B oracle and for
  rejection/merged-noise tuning reference).

## C13a results (2026-11-03): alignment precision, measured

Harness (test-only, opt-in with `-PsrpDiag=1`):
`tools/sr-pipeline/src/test/kotlin/com/rawlens/srpipeline/diag/C13aAlignmentDiagTest.kt`
(synthetic 1/f scene, analytic shifts, 4x4 pixel-aperture integration) and
`C13aRealBurstDiagTest.kt` (real 8-frame burst IMG_20260929_170823_957,
REDMI Note 15 Pro, ISO 50, 512 px crops at centre (1785,1274), (801,600),
(3001,2200); 2048 crop only for the large-shift check). The CPU Kotlin
Align+ICA is used with the app/CLI config: factors [1,2,4,4], tiles
[16,16,16,8], radii [1,4,4,4], 3 ICA iterations, FFT grey.
Oracle: exhaustive sub-pixel SSD (Keys bicubic, 32x32 grey window, 0.01 px
grid), validated on known shifts (median error 0.000, max 0.14 px).

Facts:
1. Units: FFT grey is full raw resolution, so the flow is in raw pixels
   (not half-raw).
2. Known integer shifts on real content (<= 8 px): interior median error
   0.001-0.015 px. Correct.
3. Known sub-pixel shifts on real grey content (0.25/0.5/0.75/1.5 px, with
   and without measured noise): median error 0.03-0.07 px, with a slight
   pull toward the integer (0.5 reads ~0.45). The ICA itself is sound.
4. **Large motion fails in a border band.** Shifts >= 16 px (2048 crop):
   10-13 % of tiles wrong (errors up to 95 px), all within ~16 tiles
   (~256 raw px) of the image border: 52-83 % bad within 8 tiles, 7-9 % at
   8-16, ~0 % beyond. The coarse-level flow is wrong near the borders and
   the fine levels (radius 1/4) cannot recover. On a 4080x3060 frame,
   a 256 px band is ~27 % of the area. The app's real handheld motion is
   0.2-15 px, and this burst's motion is 1-6 px, so the band is in play.
   Robustness then rejects those tiles (lost merge gain at the frame edges,
   not artefacts). Candidate fix, C10 "failed-tile alignment": clamp/
   replicate instead of circular padding at coarse levels, or carry
   the global median flow into border tiles. Needs its own A/B.
5. Real motion F01-F07 vs F00, textured tiles (top-quartile structure
   tensor min eigenvalue): ours vs oracle median 0.08 px, RMS 0.18 px,
   > 0.25 px on 9 % of tiles (centre and upper-left crops). That is above
   the ~0.1 px gate C13d set. Caveat: the oracle has its own half-pixel
   bias in flat noisy areas, see 6.
6. Low-texture areas (crop 3001,2200): the residual is identical at our
   flow, the oracle flow and the integer-rounded oracle flow (0.00051 vs
   0.00049 vs 0.00051, noise floor 0.00021). The sub-pixel phase is not
   determined there. Our flow locks to integer phases (frac histogram
   0.20/0.15 at 0/0.1 vs 0.01 at 0.5); the SSD oracle clusters at 0.5
   (interpolation noise averaging). Neither estimate means anything there.
   With no detail to super-resolve, this costs no resolution, only noise
   placement.
7. Phase coverage over the 8 frames: mean nearest-neighbour phase distance
   0.13-0.16 vs 0.189 for i.i.d. uniform. Phases cluster somewhat; they
   do not collapse.

Verdict for the gate:
- C13d (phase-correlation cross-check) is warranted. Textured-tile RMS
  0.18 px > 0.1 px. Next step: confirm with a second, unbiased oracle
  (Guizar-Sicairos upsampled DFT) before changing the shipped ICA.
- Border-band coarse failure (item 4) is a new, concrete defect for
  C10's failed-tile item.
- Alignment is NOT the main cause of the 0.91-0.96x single-frame softness:
  one frame has zero flow by construction, so C13b (kernel/density) stays
  the lead for that symptom.

## C13a merge leg + Z1 (2026-11-03): sample density and the zipper

Harness: `tools/sr-pipeline/src/test/kotlin/com/rawlens/srpipeline/diag/C13aMergeDiagTest.kt`
(opt-in, `-PsrpDiag=1`). Same burst, 512 crops (1785,1274) and (801,600).
CPU Kotlin merge with the CLI config: SNR 27.45 dB, tile 16, kDetail 0.258,
kDenoise 3.21, Wronski S.1 kernels with GAT, scale 1, robustness without
noise LUT. Per output pixel and channel: W = sum w, and n_eff = W^2 / sum w^2.
sum w^2 is exact: the same merge with cov/2 and r^2. Zipper fingerprint:
3x3 high-pass RMS split by output Bayer site, plus Nyquist checker amplitudes.
PNGs (4x): `tools/sr-pipeline/build/c13a/`.

Facts:
1. One frame: the R/B high-pass differs 2-3x between sites (R-site n_eff 1,
   B-site 3.6), the same structure as a bilinear demosaic. This is the
   single-frame alternating-sharpness pattern; colour fringes are visible
   on thin wires in `c801_600_merge1f.png`.
2. Eight frames: on average the site dependence is gone (ratio 1.03-1.15),
   Nyquist checker amplitude <= 1 % of high-pass, mean n_eff R/B 10-14, G 20-23.
3. **But 1.1-1.6 % of pixels keep R/B n_eff < 1.5 (one effective sample out of
   8 frames), and 3-4.5 % < 3.** They are edge pixels: green gradient 6-13x
   the median. There the site ratio returns to 1.4-1.65, which is the
   single-frame zipper, localised to edges and fine detail, exactly as Z1
   describes.
4. Robustness is NOT the cause: with robustness off, the same buckets hold
   1.1-1.6 % / 3-3.9 %. Robustness means were 0.86-0.89 per frame.
5. Cause: kernel width. Wronski kernel minor-axis std, frame 1:
   p5 0.13 px, p25 0.29-0.72 px, median 0.83 px. R/B sample spacing is 2 px.
   At std 0.13 px the weight 1 px away is exp(-30), so the merge becomes
   a nearest-sample pick across all frames. Which site wins alternates,
   giving dark/bright dots along edges.

Z1 status: a mechanism matching the report (edge-localised, period-2,
R/B) is reproduced and explained on the desktop CPU path. Not yet
reproduced on the app path (Vulkan; the linear DNG writer is not on this
path). Confirmation needs one app linear DNG of this burst; the C13a
harness can measure it.
Fix direction (do not ship blind): this is C13b. The kernel must not
shrink below the realised sample density: floor the minor-axis std by
local n_eff/channel spacing (Drizzle pixfrac idea), or make the shrink
law channel-aware. A/B gate: edge n_eff < 1.5 share -> ~0, site ratio
-> 1.0-1.1, sharpness vs AHD not lower, parity elsewhere unchanged.

## C10 border band: fix + A/B (2026-11-03)

Root cause (per-level breakdown, `C13aRealBurstDiagTest.borderBandPerLevel`):
the coarse levels are right. The failure starts at the x2 level and only
at the bottom/right: within 128 raw px, 98-100 % of final-level tiles
are wrong there, vs 10-30 % at top/left. The top/left share is
content that genuinely leaves the frame. `upscale_lvl` (Python/IPOL, mirrored in
`Resample.upscaleLvl`) zero-pads the "shortfall" tiles: the finer level
has more tiles than repeat x coarse tiles. Zero flow + radius 1-4 cannot
reach motion >= ~8 px. The app frame has shortfall at every level:
4080x3060 -> lvl1 23x31 from 22x30, lvl2 95x127 from 92x124, lvl3
192x255 from 190x254.

Fix: `Resample.upscaleLvl(edgeExtend)` copies the nearest upscaled tile
into shortfall tiles (clamp-to-edge, the rule the L2 search already uses
for pixels). Switch: `AlignLoopConfig.edgeExtendFlow` /
`PipelineConfig.edgeExtendFlow`, default true. False = Python-exact. One
code point serves the CPU and GPU paths: `AlignGpu` calls the same
host-side `upscaleLvl`.

A/B (`C10BorderAbTest`, opt-in):
- Known shifts, 2048 crop. Bottom/right 128 px band, wrong tiles, off -> on:
  s=(16,-10) 98.6 -> 0.0 %, (24,20) 100 -> 0.0 %, (40,-30) 100 -> 3.2 %.
  Interior: 2.5 -> 1.6 %, 2.4 -> 0.6 %, 2.2 -> 0.3 %. Small regression:
  s=(4,-2) band 0.4 -> 2.8 %.
- Real 8-frame merge, 512 crops (this burst moves 1-6 px): band residual
  |ref - warped| -2 to -6 %. Output max diff 0.011-0.023 in the band,
  <= 0.009 elsewhere (fixed edge tiles feed the bilinear upscale of their
  neighbours).
- Tests: sr-pipeline 215 pass. The reference-parity tests (ParityHarness
  align/alignCrop/endtoend, ParityDualTest, AlignLoopHostParityTest, the
  zero-pad unit test) now pin `edgeExtendFlow = false` and still pass, so
  the port stays proven 1:1 to Python in that mode. New unit test
  `upscale lvl edge extends shortfall`. App `*Mfsr*` unit tests pass.
- Open: device run (SrpPipelineCropDeviceTest desktop-vs-device `cmp`,
  timing). Expected unchanged: both sides run the same Kotlin code.

## C13d second oracle (2026-11-03): verdict reversed, do not ship

`C13dDftOracleTest`: Guizar-Sicairos et al. 2008 upsampled-DFT registration
(skimage `phase_cross_correlation` method, BSD-3), 64x64 Hann, x100.
- Known sub-pixel shifts on real grey content with measured noise: median
  error 0.045-0.067 px, p95 0.13-0.52. That is the same accuracy as our ICA
  (0.03-0.07 median).
- Ours vs DFT on real motion, textured tiles (n=1183 per crop): median
  0.069-0.075 px, bias <= 0.012 px, p95 0.34-0.39. The fractional-phase
  histograms of ours and DFT match closely: no locking on texture.
- So the 0.18 px "RMS vs SSD oracle" in the C13a section was mostly oracle
  error plus tails where every estimator struggles. Measured against two
  references, the ICA is as good as phase correlation. **C13d: no-go.** Keep
  ICA. Alignment is not where the MFSR softness comes from; the lead
  is the kernel (C13b, see the Z1 section).

## C13b / Z1 kernel experiments (2026-11-03)

Harness `C13bKernelSweepTest` (opt-in). KernelNet ported 1:1 from
`kernelnet.comp` into the test (CPU), so both kernels run on the CPU.
Burst 170823_957, 8 frames, crops (1785,1274) and (801,600).

Reference check: our Wronski covariance Omega = B^T diag(k1^2, k2^2) B
matches IPOL Lafenetre/Facciolo/Eboli 2023 Eq. 16, so k is a std. Not
a port bug. IPOL Fig. 4 notes narrow isotropic kernels cause zipper at
edges; stretching along edges only reduces it.

1. KernelNet minor-axis std: p5 0.28-0.29 px, p25 0.49-0.66 (Wronski p5
   0.13). Its one-sample share is 0.6 % (Wronski 1.4-1.6 %), yet its R/B
   edge site ratio is as high or higher (1.48-1.50 vs 1.40-1.45). This
   matches the owner report that KernelNet zippers too.
2. Kernel floor (minor std >= s), all channels or R/B only, s 0.5-1.0:
   one-sample pixels -> 0-0.26 %, but the edge site ratio barely moves
   (1.22-1.58), at a cost of 2-9 % sharpness. **The floor alone does not
   fix Z1.**
3. **Colour-difference merge** (standard demosaicking practice: Hamilton-
   Adams/AHD interpolate C-G; Farsiu, Elad, Milanfar 2006: cross-channel
   relation needed to avoid colour artifacts in multi-frame demosaicing).
   Each frame's R/B samples enter the merge as C - G_hat (G_hat = per-frame
   Hamilton-Adams directional green); output R = G + (R-G), same for B.
   Kernels and robustness are unchanged.
   - (801,600): R/B edge ratio 1.396 -> 1.086 (Wronski), 1.499 -> 1.169
     (KernelNet), vs the G yardstick 1.087/1.126. Zipper at G level, i.e. gone.
   - Centre crop: 1.45 -> 1.34, 1.48 -> 1.30 (G yardstick 1.38-1.41,
     content-limited there).
   - Sharpness (|grad Y| at edges) +0.6 to +2.2 %. Flat-area Y noise
     +8 to +16 %: G_hat noise enters R/B. Needs tuning before shipping
     (e.g. smoother G_hat for the difference, or merge G first and use the
     merged G at each sample position: two-pass).
   PNGs: `tools/sr-pipeline/build/c13a/c801_600_*_{base,colourdiff}.png`.

## Goal restated (owner, 2026-11-03)
MFSR 2.0 targets **2x output with real added detail** (proper super-resolution),
Z1-clean. Scale 1 is the current app mode and stays as a fallback. All C13/C15+
work is measured against the 2x goal, on a synthetic known-truth burst (the
only place "added detail" is provable) and on real bursts (visual + zipper metrics).

## 2x goal: known-truth measurement and the structural fix (2026-11-03)

Harness `Sr2xTruthTest` (opt-in). 512 px analytic scene (1/f texture to 0.45
cycles/raw px, above the R/B Bayer Nyquist; sharp edges; 0.4 c/px line pairs),
8 frames at random +-3 px shifts, 4x4 pixel-aperture integration, RGGB gains,
noise clean / a=1e-4,b=1e-6 / a=8e-4,b=2e-5. Truth = the aperture-integrated
scene at the 2x output centres. Tiles fixed at [16,16,16,8] (synthetic size).
detailSNR = SNR of the high-pass band (x - gauss 1 px) against the truth's
high-pass, which is where resolution shows.

**1. The current merge does not super-resolve.** detailSNR in dB:
| method | clean | iso100 | dim |
|---|---|---|---|
| A 1 frame bilinear + bicubic 2x | 1.48 | 0.14 | -4.52 |
| B 8-frame merge 1x + bicubic 2x | 1.63 | 1.36 | 0.14 |
| C 8-frame merge at 2x (app engine) | 1.35 | 0.67 | -0.38 |
| D C + colour-difference (C15) | 0.87 | -0.06 | -0.78 |
C is no better than B (and no better than A when clean): the Wronski
accumulation (order-0 kernel regression, Eq. 1) denoises but adds no
resolution at 2x. This confirms the C13 root-cause hypothesis with ground truth.
(D trades 2x detail for zipper-freedom at 1x. C15 must be re-judged
inside the new reconstruction, not on its own.)

**2. Forward-model reconstruction does.** Irani & Peleg 1991 iterative
back-projection / Farsiu, Robinson, Elad, Milanfar 2004 robust MAP SR / Farsiu,
Elad, Milanfar 2006 multi-frame demosaic-SR:
- X = 2x RGB, initialised from C.
- A_k = shift (our per-tile flow) + 1-raw-px box (4 bilinear taps at
  +-0.25 px) + Bayer mask. Verified: A_k(truth) reproduces noise-free
  frames to RMS 0.0015.
- Update X -= mu * splat(r*e) / splat(r).
- Priors: chroma (R-G, B-G) gaussian sigma 1 HR px each iteration; luma
  Laplacian lambda. Without the luma prior it diverges with noise (2x is
  under-determined for R/B).
With TRUE flows (mu 0.2, 20 it.), PSNR / detailSNR:
- clean: 43.9/1.35 -> 52.6/11.3 (lambda 0.05).
- iso100: 42.5/0.67 -> 46.3/5.2 (lambda 0.10).
- dim: 39.1/-0.38 -> 40.4/0.27 (lambda 0.10, it. 5; needs larger lambda).
With OUR ESTIMATED flows (15 it.):
- clean: detailSNR 1.35 -> 6.8 dB (PSNR 43.9 -> 48.9).
- iso100: 0.67 -> 2.9 dB (42.5 -> 44.2).
- dim: -0.38 -> 0.1 dB.
Pipeline robustness weights: no change on this static scene. They are still
needed for real motion.

**3. What now limits 2x detail: alignment precision under noise.**
Flow error vs truth, median (p90), on this synthetic texture: clean 0.07
(0.28) px, iso100 0.48 (0.98), dim 1.09 (1.94). True-flow headroom: +4.5 dB
clean, +2.3 dB iso100. Caveat: the synthetic "iso100" grey noise is ~10x the
real burst's measured grey noise. On the real burst the ICA measured ~0.05 px
(C13a), so real-burst headroom must be measured on real data next.

Plan this sets (checklist C13c, promoted to the 2.0 core):
1. Noise-adaptive lambda (MAP: lambda ~ sigma^2 from the noise profile), a
   stopping rule, a robust data term (Farsiu L1 / robustness r per sample)
   for motion.
2. Re-measure alignment on real data. Then refine flow against the
   reconstructed HR image (Lecouat et al. 2021 "Lucas-Kanade Reloaded"
   idea) if the real-burst error warrants it.
3. Real-burst 2x crops with the reconstruction: visual + zipper metric.
4. Engine port: forward (gather) + adjoint (splat) + prior passes as
   compute shaders. Merge output at 2x = 98 MP floats (1.2 GB RGB fp32)
   does not fit the 512 MB heap / phone, so this is tiled with overlap,
   native memory, fp16 storage. Budget measured before committing.

### Real burst at 2x + priors (2026-11-03, Sr2xTruthTest.realBurst2x / perChannelColourCheck)
Crop (801,600) of 170823_957, 8 frames, our flows + pipeline robustness,
15 iterations. Values relative to A = single-frame bilinear + bicubic 2x.
Edge = mean |grad Y| on the top-5 % edge pixels; noise = flat-area HP RMS.
| method | edge | flat noise |
|---|---|---|
| C merge at 2x (app engine) | 1.11 | 0.43 |
| E BP + chroma gauss + luma Laplacian 0.05 | 1.31 | 2.05 |
| luma-only SR (chroma locked to C) + Laplacian 0.10 / 0.20 | 1.03 / 0.90 | 0.95 / 0.61 |
| BP + BTV 5e-4 / 1.5e-3 (chroma locked) | 1.04 / 1.05 | 10.6 / 33.9 (BTV step above noise) |
| **F BP + chroma gauss + BTV 3e-5** (Farsiu 2004 Eq. 22-24, P=2, a=0.7) | **1.53** | **0.72** |
| G same, BTV 1e-4 | 1.44 | 2.26 |
F is visibly sharper (concrete texture, truss edges) and cleaner than a
single frame. Today's merge (C) stays cleaner (0.43): noise vs detail is the
lambda trade, to be made noise-adaptive.
**Open defect: green cast on dark thin girders in E/F/G** (absent in A and C).
Not a forward-model phase bug: with true flows on synthetic truth the R/B
(C-G) edge error is unbiased (<= 3e-4). Without the chroma prior, R/B PSNR is
47.4/49.3 dB vs merge 43.7/45.8, so the gaussian chroma prior costs colour
accuracy. Suspects (real-data only): estimated-flow error hitting the sparse
R/B samples, the robustness-weight path (not exercised in synthetic),
uncorrected lens shading/black. Next: inject estimated flows + robustness into
the synthetic truth run to reproduce, then fix.
PNGs: tools/sr-pipeline/build/c13a/real2x_c801_600_*.png

### Green cast: root-caused and fixed (2026-11-03, Sr2xTruthTest.greenCastDiag)
Greenness = mean white-balanced G - (R+B)/2 on the darkest 10 % (girders):
- Single frame A: +0.00002. Merge C: +0.00009.
- F (BP + chroma prior + BTV): +0.00033. F without robustness weights:
  +0.00033, so the weights are not the cause.
- F without the chroma prior: +0.00006.
Cause: the chroma prior smoothed raw R-G / B-G. Un-white-balanced, a neutral
surface has R-G = (0.53-1)*Y, strongly brightness-dependent, so smoothing
across a dark girder on a bright background pulls the girder's R/B down,
which reads as green. Fix: smooth the *white-balanced* difference
wb*C - G (neutral = 0; AsShotNeutral gains) and map back.
- Real: greenness +0.00009 (= merge). Edge 1.35x A (merge 1.11x), flat
  noise 0.585x A (merge 0.427x). Girders neutral
  (real2x_c801_600_F_bp_btv3e-5_wbchroma.png).
- Synthetic truth (true flows): R/B PSNR 46.0/43.9 -> **53.9/56.0 dB**
  (no prior 47.4/49.3; merge 43.7/45.8). G 51.0 unchanged.
Rule for the port: every colour prior / colour-difference step (C15 too)
operates on white-balanced channels.

### Step 2: noise-adaptive prior + robust data term (2026-11-03)
`Sr2xTruthTest.noiseAdaptiveSweep` / `motionRobustness`, estimated flows, 15 it.
- BTV with per-pixel weight kappa*sigma(Y), sigma^2 = aY + b (sensor profile):
  - kappa 0.002-0.02 is too small everywhere.
  - Clean needs a model/alignment floor: sigma^2 += sigma_m^2,
    sigma_m = 0.002.
  - Plain sign() BTV injects noise as kappa grows (dim gets worse): the
    fixed-size subgradient step oscillates.
- **Charbonnier-smoothed BTV** d/sqrt(d^2 + (c*sigma)^2), c = 1: quadratic
  below noise, L1 on edges. **One setting, kappa 0.1, beats today's merge at
  every noise level.** PSNR/detailSNR (merge -> recon):
  - clean 43.9/1.35 -> 48.7/5.47
  - iso100 42.5/0.67 -> 44.2/2.59 (kappa 0.05: 45.1/3.10)
  - dim 39.1/-0.38 -> 40.2/0.30
  Window kappa 0.07-0.1. Dim is alignment-limited (step 4).
- Motion (disc moving 1.5 px/frame, truth = reference), PSNR moving disc /
  static background:
  | method | disc | background |
  |---|---|---|
  | merge | 47.0 | 42.4 |
  | recon, no rejection | 46.2 (ghosting) | 44.0 |
  | **recon + pipeline robustness r per sample** | **48.1** | **44.1** |
  | + Huber 3 sigma | 48.1 | 42.7 |
  Huber is dropped. Stage 2 must consume stage 1's robustness (two-stage design
  confirmed). Mean r inside the disc is 0.81: local per-tile flow follows the
  disc, so it is mostly aligned rather than rejected.
Stage-2 settings so far: X0 = merge @2x; 15 it., mu 0.2; WB chroma gauss 1 HR px;
Charbonnier BTV (P 2, a 0.7, kappa 0.1, c 1, sigma from the noise profile +
floor 0.002); data weight = stage-1 robustness.

### Step 3: KernelNet as the stage-2 prior (2026-11-03, Sr2xTruthTest.steeringPriorSweep)
Steering-kernel prior (equations after Takeda, Farsiu, Milanfar 2007
"Kernel regression for image processing and reconstruction"; steering idea
only): BTV shift weights a^{|l|+|m|} become a^{|l|+|m|} * 2 exp(-0.5 d^T
(s^2 Omega)^-1 d), Omega = the kernel covariance at that pixel, so the prior
smooths along edges, not across. The same hook takes Wronski (S.1) or
KernelNet covariances. KernelNet runs on the synthetic Bayer frames via the
1:1 CPU port. Known truth, estimated flows, robustness on, 15 it.
PSNR/detailSNR:
| prior | clean | iso100 | dim |
|---|---|---|---|
| merge (stage 1 only) | 43.88/1.35 | 42.50/0.67 | 39.08/-0.38 |
| isotropic, kappa 0.1 | 48.74/5.45 | 44.17/2.63 | 40.12/0.28 |
| Wronski-steered, kappa 0.1, s2 | 48.89/4.99 | 44.87/3.21 | 40.25/0.33 |
| **KernelNet-steered, kappa 0.1, s1** | 48.55/4.32 | **45.35/3.29** | 40.22/0.26 |
| KernelNet-steered, kappa 0.1, s2 | **49.04/5.43** | 44.40/3.03 | 40.05/0.20 |
| any steered, kappa 0.2 | unstable with noise (dim -12 to -14 dB) | | |
- KernelNet steering is the best prior at the realistic noise level:
  +1.2 dB PSNR and +0.66 dB detail over isotropic. Clean and dim are on par.
- kappa must stay at 0.1 with steering: the steered weights concentrate the
  step along edges, and 0.2 diverges.
- Next: real bursts (5 bursts x 3 crops, MultiBurst2xTest). Then
  per-pixel s from KernelNet's own noise conditioning instead of one global s.

### Multi-burst real evaluation (2026-11-03, MultiBurst2xTest): stage 2 NOT ready
5 bursts x 3 fixed crops (tl/c/br). Owner review: "all of them look bad". Visual
evaluation agrees where it matters:
- **My real metric was invalid on half the crops.** Fixed positions landed on
  plain sky (184641 tl, 133434 tl) and near-black shadow (170823 br). There
  "edge |grad Y|" measures noise, not detail (e.g. edge 0.36 = the merge
  correctly denoising flat sky). Needs content-aware crop picking (top
  structure-tensor tiles) and a metric that cannot reward noise.
- **Stage 2 fails on moving water** (141353 c, mean r 0.34): checkerboard dots,
  coloured speckles, smeared waves. The merge is fine (single frame with less
  noise). With most frames rejected, 2x reconstruction from ~1 Bayer frame
  is under-determined, and the prior does not hold it.
- **Stage 2 fails at night** (194618 c, ISO 4653): blotchy, noise 8-10x the
  single frame in flats, isolated coloured dots (hot pixels, C18: no defect
  correction on MFSR inputs). kappa was tuned on synthetic noise.
  (Merge C = NaN there in the metric: den = 0 pixels, which the app blacks
  out; to check.)
- Stage 2 helps only on static, textured, bright content (170823 wires).
Fixes, in order (before any further tuning):
1. Confidence gating: per output pixel, stage 1's effective sample count
   n_eff (exact, as in C13a) decides how far stage 2 may move away from the merge:
   X = C + g(n_eff)(E - C), g = 0 when there is too little reliable data (motion,
   rejection). The merge is the safe fallback, never worse than today.
2. Prior strength scaled by 1/n_eff and by the burst's measured noise (not
   synthetic kappa). Check sigma from the profile against measured flat noise per burst.
3. Hot/dead pixel correction on inputs (C18) before both stages.
4. Evaluation on content-aware crops, with a visual A/C/E sheet per crop.

## App merge on the Mac's Vulkan: all bursts (2026-11-03, VulkanBurstCheckTest)
Desktop JNI library built from tools/sr-pipeline/src/main/cpp (CMake now has a
non-Android branch: JDK JNI headers, MoltenVK loader, no Android log library), with the
app's compiled .spv. Configured like MfsrMerge.runEngine: frames flipped to RGGB like the app,
DNG noise profile, full-frame SNR tiles, NoiseLutGen LUT + S.3, HotPixels, KernelNet
on the full flipped reference, ColourDiff with AsShotNeutral. 5 bursts x 4 crops
(tl, c, br, most-textured; night burst 1024 crops for its 32 px tiles).
Detail sheets: tools/sr-pipeline/build/vkcheck/*.png (single | Wronski off | on |
KernelNet off | on, most detailed 128 px window, 4x).
- Zipper (R/B edge site ratio; green's own ratio in brackets = no-zipper level),
  fix off -> on: Wronski e.g. sea br 2.77 -> 1.55 (1.24), 184641 tl 2.72 -> 2.05
  (1.74), 133434 br 2.07 -> 1.38 (1.29), textured crops 1.47-1.87 -> 1.25-1.42.
  KernelNet: 2.84 -> 1.59, 2.89 -> 1.19 (1.10), 1.87 -> 1.41. Daylight: mostly
  within ~0.1 of green. Night (ISO 4653): only 1.93 -> 1.77 (1.51).
- No black holes, no NaN; night lamps clean; HotPixels 0 defects in daylight crops,
  3-19 at night.
- Flat-area checker: fix on is lower or equal (184641: 0.029 -> 0.003).
- Coloured speckles: visibly fewer with the fix on (sea rocks, sea crest). Remaining
  crest dots are also in the single frame (sub-pixel sparkle aliasing in the capture).
- **Open: KernelNet leaves a fine checkerboard in textured areas** (sea rocks,
  133434 rocks). Texture-checker score: KernelNet 0.044-0.086 vs Wronski
  0.012-0.081 and single 0.031-0.053; typically 1.5-3x Wronski's. Not caused by
  its minimum kernel width: kernelnet.comp clamp 0.25 -> 0.40 -> 0.60 (recompiled,
  Vulkan) barely moves it (e.g. 0.063 -> 0.063 -> 0.058). Next suspects: KernelNet's
  elongation (<= 7:1) + rho orientation selecting one diagonal green lattice. Wronski stays
  the default kernel.

### C24 KernelNet checkerboard: fixed (2026-11-03, Vulkan, all 20 crops)
Texture-checker score relative to Wronski (mean / worst / crops >1.1x), sharpness vs Wronski:
| KernelNet min / cap | checker | sharpness |
|---|---|---|
| 0.25 / 2.0 (old) | 1.64 / 3.72 / 15 | 0.998 |
| elongation cap 2:1, 1:1, rho 0 | 1.63-2.06, no fix | - |
| 0.25 / 1.4 | 1.19 / - / 12 (flats fixed, texture not) | - |
| 0.70 / 1.2 | 0.92 / 1.41 / 2 | 0.975 |
| 0.80 / 1.2 | 0.79 / 1.10 / 1 | 0.961 |
| **0.90 / 1.2 (shipped)** | **0.67 / 1.00 / 0** | **0.949** |
Mechanism: merge_splat gathers 3x3. Kernels wider than that make every output an
equal-weight average of 1, 2 or 4 same-colour samples depending on the Bayer site
(site-periodic noise = checker in flats); kernels narrower than ~0.9 px pick one
lattice (checker in texture). Wronski's own widths sit inside this window. Sheets:
tools/sr-pipeline/build/vkcheck/knfloor_*.png.
