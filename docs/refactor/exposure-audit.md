# PROGRAM AE and ETTR: reference audit (2026-10-22)

Every number and heuristic in the PROGRAM loop (RawProgramMeter,
ProgramAeSolver, AutoExposureBalance, controller loop) and ETTR (RawEttr,
EttrLoop) checked against non-AI references.

## References (pinned)
| Ref | Revision |
|---|---|
| libcamera Raspberry Pi AGC: `src/ipa/rpi/controller/rpi/agc_channel.cpp` + tuning `vc4/data/imx477.json` (BSD-2-Clause / LGPL) | `0bf3fc5` (2026-10-15) |
| Magic Lantern `modules/ettr/ettr.c` | `142472e` (2026-09-16) |
| PhotonCamera `IsoExpoSelector.java` | `b2eff821` (earlier study) |
| ISO 12232:2019 (saturation-based speed: H_sat = 78/S; standard output: mid-grey H = 10/S) | standard |

## PROGRAM

| Item | RawLens | Reference | Verdict |
|---|---|---|---|
| Brightness signal | pooled-green mean of RAW mosaic (centre/avg/spot) | libcamera: Rec.601 Y from region RGB sums, AWB-corrected, **iterated** with saturation modelled (`computeInitialY`, 8 passes) | partial: green-only mean ignores saturation non-linearity |
| Target | 0.18 linear x 2^bias, default bias **+0.5 EV** (0.255) | libcamera y_target 0.16-0.17 (lux-dependent); ISO 12232 saturation-based mid-grey = 10/78 = 0.128 of clip | **unreferenced**: +0.5 EV default rests on "stock runs dark" |
| Highlight protection | `HIGHLIGHT_GUARD` 0.9 on hottest channel, brighten-only cap | libcamera constraint modes: inter-quantile mean of Y over q 0.98-1.0 vs y_target (LOWER 0.3 normal; UPPER 0.8 in "highlight" mode) | **unreferenced number**; reference has a defined percentile-band constraint |
| Step caps | MAX_STEP 2 EV (fast start), 1 EV (tracking), converged tol 0.12 EV | libcamera: one-shot gain to target (capped x10 per pass), startup_frames=10 at speed 1; stable_region | step caps unreferenced |
| Smoothing | EMA alpha 0.5 on brightness **plus** 10 Hz glide (40 % per tick, max 1/8 EV, snap 1/24 EV) **plus** step cap | libcamera: one filter on total exposure, speed 0.2, sqrt(speed) within +-20 % of target, fast desaturate (x0.4) when clipped | **three stacked unreferenced dampers**; reference uses one filter with a defined fast-reduce path |
| Fast start | first sample jumps with full 2 EV authority | libcamera: speed 1.0 for startup_frames (10) | same idea, different numbers |
| Cadence | 250 ms tracking / 1000 ms converged, doubled when sampling is slow | libcamera: every frame | RawLens-specific (CPU sampling cost); acceptable, measured |
| Shutter/ISO split | PhotonCamera handheld curve (1/30 -> 1/15 s) + balance slider + focal scaling | libcamera exposure modes (shutter/gain stage tables, flicker periods) | 1/30 & 1/15 referenced; balance multiplier, focal scaling, PHOTON_CAP_RAMP_STOPS 4.0 unreferenced |
| Flicker avoidance | none | libcamera rounds shutter to flicker periods | **gap** |
| Clipped frame recovery | darkening limited to the step cap | libcamera desaturate: exposure x0.4 at once when target unreachable | **gap** |

## ETTR

| Item | RawLens | Magic Lantern | Verdict |
|---|---|---|---|
| Percentile / channel | 99.9 %, max RGB | `1000 - ignore`, ignore=1; GRAY_PROJECTION_MAX_RGB | matches |
| Target | 0.3 EV below clip (0-2) | `MIN(level, -0.5)`, default level -1 EV | **E1 (open)**: more aggressive than ML allows |
| Clipped frames | -headroom per update (-0.3 EV) | 12 lower percentiles + previous-frame differences extrapolate the true overshoot, up to -10 EV | **E2 (open)**: slow recovery |
| Step clamp | 2 EV per update; converged 0.15 EV | ML applies the full correction (single shot) | unreferenced clamp |
| Shutter/ISO priority | longest safe shutter at min ISO, then ISO | ML: shutter up to `auto.ettr.max.tv`, then ISO (same order) | matches in order |
| Handheld limit | gyro: 0.006 rad x 24/f_eq, x2 with OIS, 0.01 rad/s deadband | ML: user shutter limit (default ~1/30 s-class), no gyro | RawLens extension; blur-angle constants unreferenced |
| Noise floor | none | midtone SNR >= 6 EV, shadow SNR >= 2 EV limits (allow some clipping rather than noisy mids) | **gap** |

## Proposed (owner decisions, behaviour changes)
1. PROGRAM: replace the EMA + glide + step-cap stack with libcamera's
   single total-exposure filter (speed 0.2, sqrt near target, startup
   frames at 1.0, desaturate x0.4), and its Y-target + q0.98-1.0 highlight
   constraint instead of HIGHLIGHT_GUARD 0.9. Default target from the
   reference (0.16-0.17 Y), i.e. drop the +0.5 EV default bias unless the
   owner wants it as a look.
2. PROGRAM: add flicker-period rounding (libcamera divideUpExposure).
3. ETTR: Magic Lantern target clamp (<= -0.5 EV), the multi-percentile
   overexposure extrapolation (E2), single-shot correction, and the
   midtone/shadow SNR limits.
4. Keep, documented as RawLens extensions: gyro handheld ceiling, sampling
   cadence back-off.

## Done: PROGRAM on libcamera RPi AGC (A1, owner 2026-10-22)
Owner: no +0.5 EV default (the profile default was already 0.0; only a
stale doc said +0.5). `ProgramAgc` ports computeGain (Y target 0.16,
8 iterations, x10 cap, saturation modelled on the frame green histogram),
the constraint modes (NORMAL default: lower bound q0.98-1.0 >= 0.3;
HIGHLIGHT adds the 0.8 upper bound), filterExposure (speed 0.2 per frame,
sqrt near target, 2 % stable region, 10 startup frames at speed 1), the
x0.4 desaturate step, and flicker rounding from Camera2 scene flicker.
Removed with no reference: brightness EMA, 2/1 EV step caps, glide
(0.4 / 1/8 EV / 1/24 EV), HIGHLIGHT_GUARD 0.9. Kept: PhotonCamera ISO /
shutter curve, profile balance/locks, metering zones. SPOT/CENTER samples
now carry a 16k-sample full-frame histogram for the constraints.
Device verification pending (phone disconnected at commit time).
