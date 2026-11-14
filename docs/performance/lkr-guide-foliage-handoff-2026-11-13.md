# Handoff: LK-R guide confidence makes checkerboard in wind-moved foliage (2026-11-13)

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

## State

- `982c6cc9` made the reference-guide confidence the default (`SolveParams::guide_confidence = 2`,
  `shaders/guide_stats.comp`, `shaders/weight_guide.comp`). `--guide 0` / `debug.lkr.guide 0` = old residual
  confidence. `--paper 1` disables it. Validation: `lkr-rejection-validation-2026-11-13.md`.
- GitHub release **2.2.86 is a DRAFT** (not published) because of the problem below.
- **Uncommitted in the tree:** `guide_residual` (`--gres a`, default 0 = byte-identical to the committed
  default; `lkr_robust_check` passes). With a > 0, weight = min(guide, residual_weight^a): `weight.comp`
  residual weights are written to `wrawb` first, `weight_guide.comp` combines (binding 3 now read-write,
  `pc.f1` = a). Files: `lkr_solver.cpp/.h`, `weight_guide.comp`, `host/lkr_full_run.cpp`.

## Problem

User burst `~/Downloads/IMG_20261112_153213_172` (16 frames, windy field with bushes, 2x via
`lkr_full_run BURST out.dng --prior 0 --s 2`): checkerboard / single-pixel dot speckle (often coloured) in
dark foliage, at 1x and 2x. Old default (guide off) is clean but soft.

Test crop: sensor `--crop 512 512 --at 1000 1250` (bushes in front of a white house).

## Measured (desktop, Apple GPU)

Checker = mean |2x2 [[1,-1],[-1,1]] luma| / mean luma (finest HR period). Detail = mean |luma gradient| / mean.
Support = debug map 0 (effective frames) / 16. Static = 074919 far building `--crop 512 512 --at 1560 1220`.

| config | foliage checker 1x / 2x | foliage support 1x / 2x | static support 1x / 2x | static detail 1x / 2x | static checker 1x / 2x |
|---|---|---|---|---|---|
| guide off | 0.0183 / 0.0059 | 0.48 / 0.55 | 0.83 / 0.89 | 0.1336 / 0.0734 | 0.0108 / 0.0034 |
| guide (default) | 0.0253 / 0.0101 | 0.85 / 0.84 | 0.95 / 0.94 | 0.1464 / 0.0781 | 0.0128 / 0.0043 |
| --gres 0.25 | 0.0232 / 0.0092 | 0.74 / 0.80 | 0.91 / 0.94 | 0.1443 / 0.0775 | |
| --gres 0.5 | 0.0209 / 0.0079 | 0.65 / 0.73 | 0.88 / 0.93 | 0.1404 / 0.0766 | |
| --gres 1 | 0.0182 / 0.0058 | 0.46 / 0.53 | 0.81 / 0.88 | 0.1333 / 0.0732 | 0.0107 / 0.0034 |
| guide + --prior 1 (DCT) | 0.0219 / 0.0068 | 0.85 / 0.84 | 0.95 / 0.94 | 0.1407 / 0.0719 | 0.0114 / 0.0032 |
| --paper 1 | 0.0253 / 0.0122 | 1.00 / 1.00 | 1.00 / 1.00 | 0.1417 / 0.0783 | 0.0120 / 0.0052 |

Findings:
- Alignment is fine: `host/lkr_align_overlay.py` on a traced 448 px tile is grey; red/cyan only on scattered
  thin stems/leaves (wind).
- The guide compares 3x3-quad (6x6 px) sqrt-RGB means; slightly moved leaves keep the mean, so it accepts them.
- `--gres` is only a slider between guide and guide-off: checker and static detail move together. Not a fix.
- Checker rises on the static scene too, and it is mostly finest-scale speckle, not structured ghosts:
  with no prior, more (slightly inconsistent) frames leave an undamped HR-Nyquist mode.
- DCT prior removes most of it while keeping support, but softens static detail (0.0719 < off 0.0734).

## Proposed next step (not started)

Targeted cleanup instead of rejection: keep the guide's frames, suppress only the finest-scale (2x2 checker /
HR-Nyquist) component where the residual test says frames disagree (low residual weight = wind), leave
agreeing static texture untouched. The residual weights are already computed by the `--gres` path; they
need an HR map (like the debug-map effective-frame map) for `FinishLook`. FinishLook already has a
Bayer-period luma notch gated by `noise_scale` (see `NotchBayerPeriod`) as a pattern.

Acceptance: foliage checker near guide-off (<= ~0.0065 at 2x) with foliage support >= 0.8, AND static
detail >= guide (0.0781 at 2x), visual check of both crops, ghost check on verified motion
(33TJ pedestrian/taxis `~/Downloads/hdrplus_subset/33TJ_20150612_201525_012`, 184641 car), and
byte-identical output when the feature is off. Measure phone (lkrcheck package, test set `street` = 074919)
only after the desktop result holds.

If no fix lands: consider reverting the default to `guide_confidence = 0` before publishing 2.2.86.

## Notes

- Mac python with numpy/PIL: `/usr/bin/python3` (not `-I`).
- Determinism: same binary + args gives byte-identical `.f32`; use `cmp` for "unchanged" claims.
- Rules: stage explicit paths only (user edits the tree concurrently); no hard-coded thresholds — derive
  from measured noise/motion.
