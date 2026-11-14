# Stabilization replacement plan (2026-11-13)

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Scope: replace `StabTrajectory.kt` and the post-record warp with a stronger engine.
This plan starts from VidUltra (`Stabilizer.java`, `Gyro.java`, `VisionOis.java`, GPL-3.0)
and adds methods from the literature and Gyroflow where they beat it.

## 1. Where we are vs VidUltra

| | RawLens today | VidUltra (defaults) |
|---|---|---|
| Orientation | quaternion integration, then **Euler** yaw/pitch/roll | quaternion ring buffer, slerp lookup |
| Smoothing | 0.5 s box moving average on Euler | Gaussian σ=0.35 s on rotation vectors relative to the current pose |
| Warp model | one affine per frame (shift + roll), no lens | full 3-D rotation homography + Brown distortion |
| Rolling shutter | ignored | 32 row bands, row time = ts + exp/2 + skew·y/H |
| Margin handling | hard clamp at 90 % of a 5 % margin (snaps) | slew-limited `alpha` blend toward the raw path, planned 0.45 s ahead; bisection "fits" test on 16 border points; dynamic zoom up to 1.35× |
| OIS / walking bob | none | parametric hidden-OIS model, or 3-strip phase correlation residual |
| Gyro↔camera time offset | none | fixed constant, **0 by default** |
| When | post-record decode → warp → re-encode | during recording, 0.6 s look-ahead in RAW buffers |

Verdict: VidUltra is better on every axis. Its two weak points are its **causal heuristics**
(alpha/zoom slew rates and min/max windows are hand-tuned, and `planPath`, its real planner,
is off by default) and its **uncalibrated timing** (gyro offset and gyro bias are assumed).
Because we stabilize post-record we have the whole take, so we can solve both properly.

## 2. Better than VidUltra, from research

1. **Global constrained path optimization (Grundmann et al. 2011, "L1-optimal camera paths";
   Bell, Troccoli & Pulli, ECCV 2014).** With the full take we can solve for the virtual
   orientation path in one pass, instead of iterating clamp→smooth or slew-limiting alpha.
   Variables: a rotation vector per frame, expressed relative to a reference (piecewise
   re-anchored every ~2 s so small-angle holds). Objective: L1 (or Huber) on the 1st, 2nd
   and 3rd derivatives, so the result is tripod-still segments, constant-velocity pans and
   smooth ease-in/out. Constraints: the crop window stays inside the valid sensor area
   for every row band, linearized from VidUltra's `fits()` corner projection. A few hundred
   frames × 3 axes is a small LP/QP: use ADMM or a dense banded solver on the CPU, which
   takes milliseconds per minute of video. Outcome: no lag, no snap-back, and corrections
   start exactly as early as needed. VidUltra's planner only approximates this.
2. **Self-calibration from the video (Karpenko et al. 2011; Ovrén & Forssén 2015;
   Gyroflow sync).** Estimate the **gyro↔frame time offset**, the **gyro bias** and the
   **effective readout time** for each take. Track sparse features (or reuse the
   LK flow from LK-R) on a low-res proxy, then minimize the reprojection error of the
   gyro-predicted motion over offset/bias/skew. A few ms of offset error produces visible
   wobble that cannot be fixed downstream. This is the biggest single win over VidUltra.
3. **Measured OIS instead of modeled OIS.** Camera2 `STATISTICS_OIS_DATA_MODE` /
   `STATISTICS_OIS_SAMPLES` (API 31+) report the lens shift per timestamp on devices that
   support them. Fold the measured shift into the per-row source mapping as a translation in
   normalized coordinates. Fallback order: OIS samples, then vision residual (VidUltra
   strips), then the parametric model, then none.
4. **Exact per-row rotation.** VidUltra uses piecewise-constant bands, which can tear on
   fast pans. Upload the band endpoints and **slerp/lerp the matrix per row in the shader**,
   or upload one quaternion per row (only H×16 bytes). Either way the result is continuous.
5. **Horizon lock / roll leveling (Gyroflow).** We already record the accelerometer. Fuse it
   with a complementary/Mahony filter to get a gravity vector, and add an optional soft roll
   term in (1). This is an optional mode.
6. **Optimized dynamic zoom.** Make the crop scale a variable in (1), with its own smoothness
   penalty and an upper bound. It replaces VidUltra's ±0.004/0.03 slew heuristic.
7. **Motion-blur-aware strength (Pixel "Fused Video Stabilization" 2018).** At long exposures,
   perfect stabilization exposes smeared frames on a still background. Relax the derivative
   weights when exposure × angular velocity exceeds about 1–2 px of blur. The threshold comes
   from measured blur, not a constant
   ([[auto-no-hardcoded-thresholds]]).
8. **Later: parallax-aware residual.** MeshFlow (Liu 2016) and bundled paths can handle
   close-subject walking, which the strip phase correlation cannot. This is research-grade,
   so it comes only after 1–5 are validated.

Considered and not chosen: fully learned stabilizers (Deep Online Fused VS, Shi et al. 2022).
They depend on training data and the device, and gyro + optimization + calibration already
covers handheld shake.

## 3. Stages

**S1: Metadata (sidecar v2).** Record per frame: `SENSOR_TIMESTAMP`, `SENSOR_EXPOSURE_TIME`,
`SENSOR_ROLLING_SHUTTER_SKEW`, `LENS_INTRINSIC_CALIBRATION`, `LENS_DISTORTION`, active
array/crop rect, and OIS samples when present. Raw gyro and accel go in at full rate (not
pre-integrated). Version the format and keep the v1 parser. Test: `StabSidecarTest`
round-trip.

**S2: Planner core (pure JVM, host-testable).** Write `StabPlanner.kt`: quaternion
integration with bias, row-time model, Brown projection, a `fits()` port, and the global
optimizer from (1). Output: per-frame row-band quaternions plus the crop scale. Test with
synthetic shake + pans + a known offset, and replay the motion from a real take through a
host tool (like the `tools/lkr-pipeline/host` pattern).

**S3: Calibration.** Write `StabCalib`: estimate offset/bias/skew from a 1/8-res proxy decode
plus LK features. Refuse if confidence is low and fall back to 0 offset/bias. Report the
values in the Report/log.

**S4: Vulkan warp.** Replace `vf_stabwarp.comp` with a per-pixel virtual ray → per-row
rotation → OIS shift → distortion → bilinear (or bicubic luma) sample. Run malioc
([[malioc-workflow]]) because Mali-G615 is load-bound ([[mali-g615-rcd-perf]]). Keep the
row table in constant/uniform memory, not an SSBO, if it fits.

**S5: OIS and vision residual.** Use OIS samples where available. Otherwise use VidUltra-style
strip phase correlation on the proxy as the fallback, with confidence gating.

**S6: In-record stabilization (optional, after S1–S5 ship).** Warp developed frames before
HEVC encoding, using a causal-window version of the S2 optimizer (receding horizon, about
0.5 s). Buffer **developed YUV frames at encode resolution**, not RAW, so VidUltra's
hundreds-of-MB RAW queue becomes about 0.5 s × 30 × 12 MB ≈ 180 MB at 4K 10-bit, or less
with a smaller look-ahead. Sustained thermal testing is required. Post-record stays as the
high-quality path.

## 4. Validation

- Unit: synthetic paths (static, pan, walk, whip) for no border exposure, zero lag on pans,
  and jerk reduction vs v1.
- Real takes: tripod, handheld static, walking, fast pan, low-light long exposure. Metrics:
  residual inter-frame motion from feature tracks (lower is better), crop actually used, and
  wobble (row-wise residual variance).
- A/B vs v1 and vs a VidUltra-port configuration (same metadata, its heuristics), so we can
  prove the optimizer beats it rather than assume it does.

## 5. Licensing

VidUltra and Gyroflow are GPL-3.0, which is compatible with our GPL-3.0-or-later. Keep the
attribution in ported files and NOTICE. The papers' methods are not copyrighted code.

## Sources

- VidUltra: https://github.com/Pixelgamer4k/VidUltra
- Karpenko et al., Digital Video Stabilization and Rolling Shutter Correction using Gyroscopes: https://graphics.stanford.edu/papers/stabilization/
- Bell, Troccoli, Pulli, A Non-Linear Filter for Gyroscope-Based Video Stabilization (ECCV 2014): https://research.nvidia.com/publication/non-linear-filter-gyroscope-based-video-stabilization
- Ovrén & Forssén, gyro/RS camera calibration (ICRA 2015): https://people.isy.liu.se/cvl/perfo/papers/ovren_icra15.pdf
- Gyroflow: https://github.com/gyroflow/gyroflow , docs https://docs.gyroflow.xyz/app/getting-started/basic-usage/stabilization
- Grundmann, Kwatra, Essa, Auto-Directed Video Stabilization with Robust L1 Optimal Camera Paths (CVPR 2011)
- Google AI blog, Fused Video Stabilization on the Pixel 2 (2018)
- Liu et al., MeshFlow (ECCV 2016)

## Status (2026-11-13)

- **S1 done (not phone-tested).** Sidecar v2 (`StabSidecar.Extras`): per-frame exposure, rolling-shutter
  skew, per-frame intrinsics; static intrinsics/distortion/pre-correction + active arrays; OIS samples
  (`STATISTICS_OIS_DATA_MODE_ON` requested when advertised, API 31+); accelerometer in raw device axes.
  v1 files still parse. `EncodedVideoRecorder` fills it; `EncodedVideoStabilizer` still uses `StabTrajectory`.
- **S2 done (host only).** `StabPlanner` + `StabPathSolver`. The weights deviate from Grundmann's:
  (10, 1, 100) made pans lazy, with ~30 % speed wobble on a steady pan. We now use w = (0.1, 1, 100)
  and λ = 1 in normalised box units. Tests: `StabPlannerTest`, `StabPathSolverTest`, `StabSidecarTest`.
- **S3 done (host only).** `StabCalib`: gyro↔frame offset (±60 ms grid + parabola, accepted only when
  its standard error is under 0.1 frame) and gyro pitch/yaw bias (accepted when > 3 robust SE). It
  works from `StabCalibPass`, which runs LK global shift on 320-wide luma over the first 300 decoded frames.
  Readout-time and roll-bias calibration are not done.
- **Optimizations done.** Exact per-frame crop box: bisection on the real border test, every 4th frame,
  plus a joint corner scale. Per-frame band-rotation cache. ADMM warm start + over-relaxation (1.6) +
  residual-balanced ρ, with tolerance 1e-4. 10-minute, 30 fps synthetic walk: 8.7 s → 1.4 s on the desktop JVM;
  converged; 0 refined frames (was 34/150 on the pan fixture).
- **Wired.** `EncodedVideoStabilizer.planTake`: calibration pass → `StabPlanner` (crop margin kept at
  v1's 5 %) → fallback `StabTrajectory`. `debug.rawlens.stab_engine` = `legacy` | `nocalib`.
  `vf_stabwarp.comp` now takes two homographies (row 0 / last row), interpolates by row and does a
  projective divide (push block 104 B). malioc: still LS-bound at 14 cycles, 32 regs, no stack.
  Lens distortion is in the plan but NOT in the shader yet (pinhole warp).
- **Not phone-tested.** The installed app on the test phone has a different signing key.

### Update (2026-11-13, after first phone takes)

- **Re-enabled on device:** post-record stab had been disconnected by the direct-recording change
  (2d12b706). It now warps from the saved URI into `<stem>_STAB.mp4` in the same folder. Fixed on device:
  the PCM key lost on read-back, the Main10 force pick (it had fallen to 8-bit), and false gyro bias
  from hidden OIS (gain + bias fit, kept only if it lowers the residual).
- **OIS policy:** with no OIS samples, a take that wants a stabilized copy runs with OIS off
  (`oisPolicy=off`). The first-frame `oisMode` log shows whether the HAL honoured it.
- **Calibration pairs:** accepted by the shift's standard error (< 0.25 luma px) instead of texture/residual.
- **Horizon lock:** `debug.rawlens.stab_horizon=1`. It picks the accel axis signs from the data, levels to
  the nearest 90°, and refuses when the axes are ambiguous. It is limited by roll room: the default 5 %
  margin levels only ~1–2°.
- **Blur easing:** on by default; λ·(1 + (blur/2 px)²) per frame from the exposure-time rotation.
- **NOTICE:** VidUltra attribution added.
- **Deferred:** lens distortion in the shader, readout/roll-bias calibration and OIS-sample use (this
  phone reports none of the inputs), auto-zoom, picture-based OIS, MeshFlow, in-record stabilization.
