# LK-R rejection validation — 2026-11-13

The reference-guide confidence experiment provides partial detail recovery, not a complete solution.

**Update (same day):** the guide is now the default (`SolveParams::guide_confidence = 2`; `--guide 0` restores residual confidence, paper mode disables it). `weight_guide` runs per Bayer quad and variances are stored for the reference frame only; outputs are byte-identical to `--guide 2` on 8 cases (074919/080720/184641/33TJ at 1x and 2x, oracle, odd crop), and 074919 1x matches this report's `after` f32 SHA-256 `a56b2485…`. See [research and implementation rationale](lkr-rejection-research-2026-11-13.md).

## Coverage and artifacts

Root: `/tmp/lkr-rejection-validation-20261113`.

- Inventoried 21 source bursts (205 CFA frames): 12 in `hdrplus_subset`, 9 in `My/Bursts`. Source paths and hashes are in `inventory.json`; inputs were not modified.
- Existing processed outputs were excluded. The HDR+ directory named `IMG_20260919_184641_979` contains only processed linear DNGs; its eight-frame source burst was evaluated from `My/Bursts`.
- Completed all 21 bursts at both 1× and 2× with preserved baseline and experimental executables: 84 full-sensor reconstructions. `metrics-guide2-experiment.json` contains 42 paired comparisons. Difference and gradient measures describe changes, not ground-truth quality.
- Final requested native 1× deliverables: `final-1x/index.html`, with 21 before/after pairs, 42 DNGs, 42 full PNGs, and native-resolution crop pairs. All renders use the same neutral RawTherapee profile, camera white balance, sRGB output, and no resizing, denoising, or sharpening.
- Visually reviewed full-frame overview pairs and selected native largest-change / gradient-loss crops for every scene, plus named bicycle and moving-car regions. This is sampled visual inspection, not inspection of every output pixel.

## Results and limits

Fixed-alignment static pilots show that rejection removes useful support: in the 074919 crop, accepted support increased from 0.7602 to 0.9138 at 1×, and 0.8279 to 0.9083 at 2×. A manually trusted static region reached 0.9973. These crop-specific results isolate rejection; they do not establish that alignment is perfect everywhere.

Three labeled 16-frame synthetic configurations were scored at both scales for baseline, guide, reduced threshold, and rejection-disabled bound (24 trials). At 2×, static-texture scores rose from approximately 1.73–1.74 dB to 1.83–1.84 dB with the guide; the rejection-disabled bound reached 3.09–3.36 dB but produced severe motion contamination. Gains at 1× were small. Guide trail/color scores improved in these trials. Full scores and parameters are in `synthetic/results.json`.

The guide is therefore a useful experiment, not sufficient evidence to change production defaults. More accepted frames alone do not demonstrate restored detail. The next research step is to close the static-oracle gap while preserving independently verified motion rejection, using these frozen inputs and comparisons.

The handoff's alleged person behind railings is a bicycle/static balcony detail. That interpretation and the blue-strength “solidity” metric were invalid. Genuine motion was verified in source frames: pedestrian/taxis in `33TJ_20150612_201525_012` and the car in `IMG_20260919_184641_979`. Invalid early renders from a stale residual experiment are isolated and excluded; see `CORRECTIONS.md` in the artifact root.

## DNG compatibility correction

The Python archival rewrapper initially wrote one BlackLevel value for three RGB samples. RapidRAW correctly rejected it. The helper now writes BlackLevel[3] and WhiteLevel[3], preserving the archived uint16 RGB pixels exactly. The production native writer already writes three RGB BlackLevel values and was not changed for this issue.

All 42 corrected final DNGs decoded successfully with RapidRAW's Rawler dependency at revision `9dbcc5654ad4b15e2649e6636e96f1b49fc9cf0f`; per-file results are saved beside the DNGs as `*-rapidraw.json`. This tests its decoder, not the GUI. RawTherapee also rendered all 42 corrected files. A regression verifies that the decoder accepts the corrected three-value tag and rejects the legacy scalar tag.

## Verification

- Native robust checks passed, including temporal/shadow/chroma/highlight checks, static-vs-changed guide confidence, bounded static oracle, and multi-step all-static oracle equivalence (maximum error zero).
- With guide disabled, fixed-alignment default outputs were byte-identical to the preserved baseline.
- Full runs checked dimensions and finite reconstruction values; archival DNGs checked pixel identity and native dimensions.
- Final inventory audit: 21 manifest entries, 42 DNGs, 42 full PNGs, 42 successful Rawler checks, 42 scale/burst comparison records.
- No production default promotion, source-data edits, staging, or commits.
