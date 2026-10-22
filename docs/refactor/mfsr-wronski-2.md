# MFSR "Wronski 2.0" (owner goal, 2026-10-22, parked)

Owner: dig deep and improve MFSR beyond the paper ("fix Wronski itself"),
not only match it. Starting point: the softness investigation below.

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
  than normal shots. (Lens shading IS applied per frame in decodeSharp;
  the vignetting seen on desktop is the CLI ignoring the DNG GainMap.)
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
   smoothing of the weights.
2. Why a single frame through the reconstruction is softer than a plain
   demosaic (0.91-0.96x AHD): kernel law, gather footprint, guide/grey.
3. Go beyond the paper where it is weak ("fix Wronski itself"): e.g.
   density-adaptive kernels, joint demosaic/SR (Lecouat et al. 2021),
   better sub-pixel alignment; each change measured against AHD and the
   burst set, no AI-only shortcuts without a reference.
4. Motion-blur cases from the app (frame selection, AE-searching frames):
   needs one app shot showing it + its log.
5. Plus mfsr-paper-gaps.md items (dim-burst divergence, scale > 1 for zoom,
   S.3 thresholds, failed-tile alignment).
