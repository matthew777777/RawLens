# HDR+ scene-auto validation — 2026-11-05

> Repository context: [current source state](../../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

14 real bursts, 110 source DNGs, 56 full-resolution native merges on Apple M1/MoltenVK. All outputs finite. {'tests': 69, 'failures': 0, 'errors': 0, 'skipped': 0} for the final HDR+ test run (including the eight-burst fixture test).

Auto strength and quality are enabled for new and existing settings. The migration marker is persisted on the next settings save; subsequent manual choices round-trip unchanged. HDR+ itself is not forced on.

## Automatic decisions

| Burst | Strength | Path | Frames kept | Map mean range |
|---|---:|---|---|---|
| IMG_20260917_141353_404 | 3.39 | HQ | 8 | 1.60–2.13 |
| IMG_20260919_184641_979 | 1.00 | Fast | 8 | 1.00–2.30 |
| IMG_20260920_133434_814 | 2.61 | HQ | 8 | 1.31–1.77 |
| IMG_20260921_194618_520 | 8.03 | HQ | 8 | 3.20–3.82 |
| IMG_20260929_170823_957 | 2.60 | HQ | 8 | 1.69–2.59 |
| IMG_20261104_111404_842 | 1.00 | Fast | 8 | 1.00–1.00 |
| 0009_20160702_154921_383 | 5.76 | HQ | 7 | 3.07–3.87 |
| 0030_20151214_101221_477 | 1.00 | Fast | 8 | 1.00–4.11 |
| 0039_20141007_135645_851 | 3.71 | HQ | 10 | 1.43–2.12 |
| 0043_20160917_113940_016 | 6.41 | HQ | 7 | 2.83–3.95 |
| 0272_20160521_145117_858 | 2.99 | HQ | 2 | 1.78–1.78 |
| 0543_20150918_185854_904 | 5.12 | HQ | 8 | 2.78–3.86 |
| 4KK2_20150906_172222_214 | 5.90 | HQ | 10 | 3.78–4.30 |
| 5a9e_20150328_160520_398 | 4.16 | HQ | 10 | 1.96–2.34 |

Strength is the burst-level conservative value. Local/frame strengths can differ, as recorded in each decision file. HQ rounds strengths internally; map means are descriptive, not equivalent to a uniform slider setting.

## Google RAW comparison

Active-area and black/white normalization, integer CFA-cell translation, shared unclipped mask; no exposure/color fitting. Google output is a processed reference, not noiseless truth. Different reference frames, subpixel displacement, local motion and algorithm differences limit PSNR interpretation. Higher agreement does not prove fewer ghosts.

| Burst | Source PSNR | Auto PSNR | HQ 3 PSNR | HQ 6 PSNR |
|---|---:|---:|---:|---:|
| 0009_20160702_154921_383 | 42.44 | 42.63 | 42.66 | 42.74 |
| 0030_20151214_101221_477 | 33.85 | 33.85 | 33.89 | 33.92 |
| 0039_20141007_135645_851 | 35.46 | 35.60 | 35.70 | 35.90 |
| 0043_20160917_113940_016 | 36.84 | 36.89 | 36.90 | 36.93 |
| 0272_20160521_145117_858 | 50.71 | 51.67 | 52.12 | 53.04 |
| 0543_20150918_185854_904 | 46.52 | 47.56 | 47.52 | 48.18 |
| 4KK2_20150906_172222_214 | 42.99 | 43.40 | 43.37 | 43.68 |
| 5a9e_20150328_160520_398 | 47.59 | 49.18 | 49.88 | 52.48 |

HQ 6 has higher reference agreement on all eight subset scenes. Auto improves on the source in all eight but does not establish optimal denoising. The local risk maps often suppress substantial averaging; sparse unaligned mismatch and noise-contaminated dense MAD remain limitations. Reviewed two 480×480 crops per burst plus overview sheets: this is a spot check, not an exhaustive pixel-level artifact guarantee.

## Work still required

1. Separate noise from motion in local confidence maps and measure residuals relative to the chosen aligned reference. Validate against moving subjects, foliage, water, thin wires and low-light texture; do not select a policy solely by whole-image PSNR.
2. Sweep strengths on representative training scenes, then validate the noise budget and motion policy on separate held-out bursts. Add deep night, high ISO, long exposures, varying sensor bit depth and burst counts.
3. Run old/new builds on Android devices covering supported Adreno/Mali GPUs and native-meter fallback. Compare manual outputs, Auto artifacts, capture success, peak memory, sustained latency and thermal behavior. No Android device was attached for this run.

## Reproduction and artifacts

See `tools/hdrplus-auto/README.md`, `results.json`, `google-metrics.json`, individual decisions/native logs, and `build/reports/hdrplus-scene-auto/index.html` for local comparison sheets. Full generated DNGs remain under `/tmp/rawlens-scene-bursts` and `/tmp/rawlens-scene-subset`; source folders were read-only.

Reference dataset: https://hdrplusdata.org/dataset.html (Hasinoff et al.; CC-BY-SA). Research and implementation policy details: `docs/refactor/hdrplus-scene-auto.md`.
