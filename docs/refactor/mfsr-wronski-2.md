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
