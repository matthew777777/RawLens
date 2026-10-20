# PROGRAM AE and ETTR — study before refactor (2026-10-20)

Scope: the PROGRAM custom-AE loop and RAW ETTR in `RawCameraController`
(26 state fields, 31 functions) and the pure helpers `RawProgramMeter`,
`AutoExposureBalance`, `RawEttr`.

## References (pinned)

| Ref | Revision |
|---|---|
| PhotonCamera `processing/parameters/IsoExpoSelector.java` | `b2eff821` |
| Magic Lantern `modules/ettr/ettr.c` (reticulatedpines/magiclantern_simplified) | `142472e9` (2026-10-04) |

## Verified

- `AutoExposureBalance` "PhotonCamera Photo mode begins its low-light
  extension at 1/30 s": `PHOTO_HANDHELD_CAP_START = ExposureIndex.sec / 30`.
- Auto safe-shutter endpoint 1/15 s: `PHOTO_HANDHELD_CAP_END = ExposureIndex.sec / 15`
  (= `PROGRAM_SHUTTER_END_NANOS`). The focal-length scaling around it is RawLens's own.
- ETTR highlight percentile 99.9 %: Magic Lantern uses `1000 - auto_ettr_ignore`
  per mille with default `ignore = 1`.
- ETTR measures the hottest channel: Magic Lantern default clip projection is
  `GRAY_PROJECTION_MAX_RGB`.

## Findings (behaviour; owner decisions)

| # | Finding |
|---|---|
| E1 | Default ETTR headroom is 0.3 EV below clipping (range 0–2 EV). Magic Lantern defaults to 1 EV and never targets closer than 0.5 EV (`target = MIN(level, -0.5)`). RawLens is more aggressive than the reference allows. |
| E2 | On an already-clipped frame the 99.9 % level reads 1.0, so the correction is only `-headroom` (−0.3 EV) per update however overexposed the scene is. Magic Lantern keeps 12 lower percentiles and extrapolates the true overshoot from the previous frame. |

`RawEttr.kt` cited no reference; its percentile and channel choices now have one (above).

## Refactor done

`ProgramAeSolver`: profile limits, locked-axis values and the glide step moved
verbatim out of the controller (state, timer and request pushes stay).
