# LK-SR state, 2026-11-10 (stopped by the owner)

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Last commit: `93a5fc3` (temporal tolerance 1.0, noise-fit fix, clip-halo fix).
Everything below is uncommitted in the working tree.

## Decisions
- LK-SR default stays no prior. This is the owner's baseline.
- FFDNet + GAT is removed from LK-SR. It was judged too smooth.
- Non-learned replacement: sliding 8x8 DCT, stride 4, empirical Wiener shrinkage, luma 1, chroma 8. Optional, off by default. No extra night smoothing.
- The standalone photo denoise keeps FFDNet and its model files. Removing it needs a separate decision.
- The DCT shader should stay reusable for future raw video.

## Uncommitted changes
- Shaders: `dct_x.glsl`, `dct_x_y.comp`, `dct_x_u.comp`, `dct_x_v.comp`, `dct_yuv.comp`.
- Solver: `SolveParams::dct_prior`, `dct_luma`, `dct_chroma`; x-step in `lkr_solver.cpp`. H and W must be multiples of 4, so `lkr_full.cpp` rounds the tile when s = 1.
- JNI and Kotlin: `nativeMerge` takes `dctPrior`; FFDNet, NPU and fp16 arguments are gone. `MfsrSettings.lkrDctPrior` (pref `lkr_dct_prior`) replaces `lkrUsePrior` and `lkrPaperParity`. Settings -> Merge has one DCT switch.
- CLI `lkr_full_run`: `--prior 0|1` is none or DCT; `--dluma`, `--dchroma`. `--model` is accepted but unused. `--paper` remains for research.
- `temporal_detail` default changed from 1 to 4 in `lkr_solver.h`. See below.

## Measurements
- GPU DCT vs CPU prototype: relative RMS at most 0.002 % on 5 scenes at 1x and 2x.
- No-prior output was bit-identical before the `temporal_detail` change. It will differ now, in areas the temporal test used to reject.
- malioc G615: load/store cycles per block 2760 -> 1295 (three channel passes). Still 64 registers with some spill.
- Mac, one tile, x-step: FFDNet 3.1 s, DCT 0.28 s.
- App built and `MfsrSettingsTest` passed with DCT at `temporal_detail` 1. Not rebuilt since the change to 4.

## Yellow/cyan bands on Friant perforated panels
Cause: the temporal test still rejects this static panel. Its fine pattern exceeds the model-error tolerance, so the area falls to few frames and colour aliasing remains.

Panel near the HOTEL sign, 2x, crop at 2640,1340:

| `--tdet` | Band energy | Effective frames (of 20) |
|---|---|---|
| 1 | 0.0295 | 10.2 |
| 2 | 0.0273 | 14.3 |
| 4 | 0.0266 | 16.0 |
| 8 | 0.0265 | 16.2 |
| temporal off | 0.0265 | 19.0 |

Effect of going from 1 to 4 on moving content:
- Cyclist: still rejected, no ghost (temporal off does ghost).
- Lake (0006): fine texture 0.00615 -> 0.00566, about -8 %.
- Sea (5a9e): 0.01533 -> 0.01485, about -3 %.

Not validated at 4: full-frame Friant 1x and 2x (the run was stopped), the top balcony panel, and the 17-burst sweep. The DNGs in `~/Downloads/LKSR-validation-2026-11-10/` were rendered at `temporal_detail` 1 and still show the bands.

## Other known issues
- Night burst 194618 keeps strong colour noise with no prior.
- No-prior 2x can look grainier in flat sky now that all frames are used.
- Some spots: faint moire on perforated panels remains in both no-prior and DCT.

## Zoom report (open)
Owner saw a wrong crop location and motion-like blur in 1.5x / 2x zoom.
- Mac, app-equivalent 2x centre crop on 3 bursts: lands within 1-2 px of the sensor centre and looks sharp.
- Preview zoom scales around the viewfinder centre.
- Not reproduced. Phone zoom output has never been compared with the Mac. `LkrPipelineDeviceTest` does not dump zoom results; add that, or measure a real bad photo.

## Phone
- Test package `com.matthew.rawlens.lkrcheck` is installed with the DCT build at `temporal_detail` 1.
- Install: `./gradlew -PlkrCheck :app:installGithubDeviceTest :app:installGithubDeviceTestAndroidTest`. The Xiaomi USB-install guard can block it; the owner has to allow it. Do not touch the phone screen.
- Bursts: `/data/local/tmp/lkr/night` and `/data/local/tmp/lkr/day`.
- Only one timing exists: day burst, 8 frames, 1.5x zoom, no prior: 100 s, peak PSS 1.95 GB, 40.8 C.
- No DCT timing on the phone yet. Device-test arguments: `-e lkrDir`, `-e lkrCases full,zoom2,zoom15`, `-e lkrDct 0|1`. Capture logcat while the test runs; the buffer overwrites earlier result lines.

## Next
1. Finish validating `temporal_detail` 4 (Friant at 2x viewed at 1:1, then the sweep).
2. Commit, staging explicit paths only.
3. Phone timings for none vs DCT, then the zoom comparison.
