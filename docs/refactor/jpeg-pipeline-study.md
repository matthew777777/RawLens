# JPEG pipeline study (AgX, auto exposure, AMaZE, quad Bayer, HL, speed)

## Capture-to-file timing baseline (build 418, Xiaomi 25080RABDG, 12 MP)

AUTO, JPEG only, AMaZE, highlight reconstruction ON (user setting):

| Stage | Time |
|---|---|
| RAW prep + adaptive exposure | 15-60 ms |
| GPU develop ("GPU+output") | 11.5-19.2 s |
| of which HL reconstruction | 3.3-9.7 s (scales with clipped ROI) |
| JPEG encode + save | 0.37-0.50 s |

Isolated AMaZE (PhotoDemosaicKodakDeviceTest, 12 MP mosaic, AmazeProfiler):
2.9 s; heaviest passes hvwt 443 ms, gcorr 393, nyqref 281, rbpm 249,
gradcd 223 (12 tiles of 1024 px, RGBA32F scratch). Heavy Java GC churn
during the develop (70-220 MB freed repeatedly, heap ~500 MB): full-frame
CPU round trips around HL (readback, convert, per-tile upload/download,
pack).

## AgX: PASS

RawLens pins Google Filament ToneMapper.cpp @2a8018f; the fetched file's
SHA-256 matches the pinned 1e3212b6...aa42cd. Filament's mat3f lists
columns; RawLens rows equal Filament columns: inset diff 0, outset (=
inverse of Filament's OutsetInv) 4e-16. Official-base path vs Filament +
colour-science matrices over 20 000 random scene colours (-12..+10 EV):
max 1.9e-4, mean 5.4e-5 (one 8-bit step = 3.9e-3). ACEScg->Rec.2020
(Bradford) and Rec.2020->sRGB differ from colour-science by ~2e-4
(primaries rounding); Display P3 by 8e-11. GLSL agx_srgb8.glsl: every
matrix is the exact transpose of the Kotlin reference (<=3e-16), all
constants present; HDR tail slope 0.1702 = d(contrast)/dx at 1 (checked).
darktable's agx.c is a different parameterisation (not the pinned base).

- AgX-1 (open, minor): "Reset official AgX Base" keeps the current
  highlightShoulder (default 1), so the reset is not pure Filament AgX.

## Auto exposure (scene-referred): PASS

Trimmed log-average (p5..p95) to 0.18 (Reinhard et al. 2002 key value; the
HDR+ metering basis), p99 hard white guard (1 % clip budget) and p95 soft
guard for broad highlights (HDR+ protects highlights first), low-key
damping and a night tier. Meters before AgX in scene-linear, with the AgX
shoulder applied to samples so the meter sees what AgX sees.

- AE-1 (fixed) log2 of a zero sample made the log-average -inf (+2.5 EV:
  max lift plus the HDR-scene ceiling); a negative made it NaN. Latent:
  all three callers filter > SHADOW_FLOOR, but analyzeSamples did not
  enforce it. Samples are now compacted to finite positives first
  (AdaptiveExposureMeterableSamplesTest); unit suite unchanged (1556 pass).
- AE-2 (observation) the meter averages un-white-balanced per-CFA samples,
  not luminance: ~0.4 EV constant bias for a neutral under daylight gains
  (absorbed by tuning), ~0.07 EV daylight vs tungsten. Kept.
