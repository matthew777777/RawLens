# RAW-SR motion robustness (Prompt 4C): equations and contracts

This document is written **before** the implementation. The code must match it;
any behavior difference is a bug unless recorded here as a deliberate deviation.

**Revision 2026-09-27 (reference-parity port):** robustness is a 1:1 port of
Jamy-L IPOL 2023 `robustness.py` Algs. 6–9 (`compute_robustness`,
`compute_guide_image`, `compute_local_stats`, `warp_stats`, `compute_d_sigma`,
`compute_s`, `robustness_threshold`, `local_min`), including the sqrt guide,
the Dogson warp, the measured-variance photo term, and the reliability-blind
flow gate. The former extras — linear guide, bilinear warp, analytic
expected-variance model, rail/saturation/residual/reliability/model/hot-pixel
gates, static-hypothesis bypass — are REMOVED from the base path (the
reference defines none) and survive only as dormant diagnostics where noted.

References: Wronski et al. SIGGRAPH 2019 §5 (esp. §5.2 robustness model),
Jamy-L IPOL 2023 (see above), the Jamy-L Monte Carlo clipped-noise LUT
(`monte_carlo.py`, `noise_lut.py`, transform
`clip_raw_then_sqrt_bayer_quad_rgb_v1`), and the SkyKing working reference
(`motionv2/mfsr_robustness.glsl` historical only).

## 1. Photometric domain

Statistics run on the **square root of unshaded black/white-normalized Bayer
observations**: `g = sqrt(max((code − b)/(W − b), 0))` per quad channel (§2),
reference Alg. 7 verbatim. This is deliberately separate from the GAT
covariance guide and from the shaded fusion path.

Lens shading: no gains are applied in this domain, so none are booked. This is
exact — not an approximation — for same-exposure, same-lens bursts: shading
multiplies both frames' signals and noises equally, so the standardized
disagreement `d/σ` is gain-invariant to first order. Exposure mismatch breaks
the invariance; it is caught by the planner's global `EXPOSURE` gate
(`RawSrBurstPlanner`, `MAX_EXPOSURE_RATIO = 1.10`, untouched) and by the
exposure-mismatch robustness test. A lens consistency test proves identical
decisions with and without a lens model attached.

## 2. Sqrt guide (per frame, IPOL Alg. 7)

Per Bayer quad `q`, three channels from the quad's four samples by sensor CFA
color (exactly 1 R + 2 G + 1 B per quad for every Bayer pattern and origin):

- `G_R(q)` = sqrt of the normalized R sample; `G_B(q)` = sqrt of the
  normalized B sample; `G_G(q)` = sqrt of the MEAN of the two normalized
  green samples (root of the mean, not mean of roots).

Per-channel normalized noise coefficients (`α_R`, `β_R`, …) are carried for
diagnostics (all four phases / RGB triple reduction like the covariance
guide); the base photo term does not consume them — it runs over measured
guide variance only (§5), like the reference.

Per-sample rail diagnostic (code domain, using the sample's own `(S, O, b, W)`),
highlight side only: `σ_code = sqrt(S·code + O)`; sample is near-rail iff
`code > W − 3·σ_code`. There is deliberately NO shadow rail. The rail map is
diagnostic-only in the base path: the reference defines no saturation gate,
and clipped blocks reject through the photo term alone (pinned by test).

## 3. Local statistics (IPOL Alg. 8)

Per channel, 3×3 window with clamp-to-edge taps (mirrors the reference):

- `μ(q) = Σx/9`, `V(q) = max(Σx²/9 − μ², 0)` (variance floored at 0).

## 4. Warp (IPOL `warp_stats`, Dogson biquadratic)

Moving means are sampled at `q + flow(q)` in **quad units** (our flow
convention; the reference scales raw-unit flow by 0.5 — same geometry). Flow
lookup is **nearest tile, never blended**, exactly like the reference
`int(lr//tile_size)`. The moving sample uses Dogson biquadratic weights
(`a = 1`, reference `dogson` verbatim):

- `μ_m(q) = Σ w_i·m_i / Σ w`; taps clamp to edges; weights renormalize.
- Warp center outside `[0,W)×[0,H)` → `OUT_OF_BOUNDS`, `R = 0` (the reference
  writes `+inf`, which likewise forces `R = 0`).

## 5. Expected disagreement (IPOL `compute_d_sigma`, measured variance)

- `d²(q) = Σ_c (μ_r,c − μ_m,c)²`.
- `σ²(q) = Σ_c V_r,c`, the measured reference local variance (scene + noise),
  with the measured-LUT noise correction below when a LUT is attached.
- Edge rule: `d²/σ²` non-finite (the `0/0` exactly-flat edge, reference-undefined)
  rejects (`r = 0`, reject on doubt). `σ² == 0` with `d² > 0` likewise rejects
  (infinite standardized distance).
- The noise profile feeds diagnostics only: a missing, invalid, or exactly-zero
  model changes no verdict (bitwise) and sets no flag. No noise floor is
  invented; the analytic path needs none.

### Measured noise LUT (reference `compute_d_sigma` correction)

When a `RawSrNoiseLut.Lut` is provided, the analytic `d²/σ²` above is
corrected exactly like the reference, before the edge rule:

- `brightness` = mean of the three reference channel means, clamped to [0, 1]
  (the LUT's binning key).
- `(σ²_LUT, d²_LUT)` = nearest-bin lookup
  (`floor(b·(bins−1) + 0.5)`, identical on CPU, in the generator, and in GLSL).
- `σ² = max(σ², σ²_LUT)`; when `d² > 0`, `d² *= (d²/(d²+d²_LUT))²`.

The LUT is generated for the sqrt transform
(`clip_raw_then_sqrt_bayer_quad_rgb_v1`): the reference Monte Carlo procedure
(stratified latent prior, Welford 3×3 patch statistics, R/B single-sample
channels, root of the two-green mean, binning by measured reference
brightness). The transform name is part of the cache identity: linear-transform
LUTs are stale and unloadable. LUTs are cached per sensor profile under
`rawsr-noiselut-<sha1>.bin` (versioned little-endian: magic `RLNLUT01`,
version, bins/trials/seed, RGBG alpha/beta, σ²/d² curves, SEMs, bin counts).
A null LUT keeps the analytic path exactly (a zero LUT is a bit-exact no-op,
pinned by test).

## 6. Flow irregularity and threshold (IPOL `compute_s`, `robustness_threshold`)

Over 3×3 tiles (in-bounds tiles only, mirroring the reference):

- `spread² = (max dx − min dx)² + (max dy − min dy)²`; `spread > Mth → s1 else s2`.
- `Mth = 0.4` quad pixels: the published `Mt = 0.8` is in raw pixels.
  Non-finite tiles are SKIPPED as missing data (reference block matching
  yields finite flows by construction, so this edge is reference-undefined;
  the skip matches the merge shader); with no finite tile the verdict is
  irregular. Reliability is IGNORED throughout (the reference has no
  reliability concept): wild or non-finite flows on unreliable tiles enter
  the spread exactly like reliable ones.
- `R(q) = clamp(S·exp(−d²/σ²) − t, 0, 1)` with centralized `t = 0.12`,
  `s1 = 2`, `s2 = 12` (Jamy-L `RobustnessConfig` values, unchanged).

## 7. Flags and final map

The base path reports exactly two flags (both hard-zero `R = 0`):

| Flag | Meaning |
|---|---|
| `OUT_OF_BOUNDS` | Warp target outside the moving frame |
| `INVALID_FLOW` | Non-finite flow components |

All other `FLAG_*` bits (`FLOW_UNRELIABLE`, `STATIC_HYPOTHESIS`, `RESIDUAL`,
`SATURATED`, `PHOTO_CONFLICT`, `MODEL_MISSING`, `MODEL_ZERO`, `HOTPIXEL`,
`UNBLOCKED`, `MOTION_IRREGULAR`) are dormant: no base-path evaluation sets
them. Misalignment protection is the photo term plus the `s1` irregular scale
— there is no residual gate, no saturation gate, no reliability gate, no
static-hypothesis bypass, and no hot-pixel gate. Hot pixels are handled at the
sample layer (`RawSrHotPixel` detect + `MergeJob` inpaint before the merge);
a single stuck tap cannot move the 3×3-mean photo term (pinned by test).

Final map: `r = 5×5 clamp-window minimum of R` (IPOL Alg. 9); flags stay
per-quad own-evaluation. All outputs finite; hard-invalid quads get explicit
zero weights.

## 8. Rc accumulation

`Rc` is a per-quad float field, initialized to zero. Each accepted
non-reference frame contributes its robustness exactly once per quad:
`Rc += r_n` (finite-sanitized). The reference is excluded; reference-only mode
leaves `Rc` identically zero. `1 + Rc` is described as a robustness-based
frame-support estimate for diagnostics — not a statistically exact effective
sample count.

## 9. Deliberate deviations (summary)

1. `d²/σ²` non-finite edge (exactly-flat fields) rejects; the reference edge
   is implementation-defined (CUDA NaN semantics). Real captures always carry
   noise, so the edge only bites synthetic constant fields.
2. Non-finite flow tiles skipped as missing data in the spread (§6);
   reference-undefined (its flows are finite by construction).
3. Pattern-aware CFA routing instead of the hardcoded RGGB map (identical on
   even-origin RGGB).
4. Rail / noise-model / hot-pixel diagnostics computed but unconsumed by the
   base verdict (§§2, 5, 7).
5. Lens gains N/A by unshaded-domain construction (§1).
6. `Mth` converted 0.8 raw px → 0.4 quad px (§6).

## 10. Quantitative acceptance criteria (declared before evaluation)

- CPU/GPU agreement: `|r_gpu − r_cpu| ≤ 2e−3 + 2e−3·|r_cpu|` per quad (strict
  family); flags bit-exact; `|Rc_gpu − Rc_cpu| ≤ 2e−3 + 2e−3·|Rc_cpu|`.
- Static noisy retention: ≥ 95% of interior quads accepted (`r > 0`) across
  dark/mid/bright scenes with matched profiles. (Perfectly flat synthetic
  fields read `r = 0` per the §5 edge rule; retention needs signal variance.)
- Conflict rejection: ≥ 90% of independently-translated foreground quads
  rejected (`r == 0`); saturated blocks reject through the photo term with NO
  saturation flag.
- Flow-scale discrimination: fixed disagreement inside the (2.8, 4.6) `d²/σ²`
  window accepts under smooth flow (s2) and rejects under irregular flow (s1).
- Finiteness: every output finite on every test, including poisoned flow.
- Rc exactness: hand-built accumulation matches to 1e−6; reference-only Rc is
  exactly zero; repeated execution is bit-exact.
- Missing model changes no verdict (bitwise) and sets no flag; the LUT
  applies whenever attached.
- Exports: CSV + PNG diagnostics exist and are non-empty for the multi-scene run.

Addendum (measured noise LUT):

- LUT-disabled CPU/GPU agreement inherits the §10 tolerance unchanged: a null
  LUT is a no-op on both sides (CPU formula untouched, shader correction
  gated by `u_lut_enabled`).
- LUT-enabled CPU/GPU agreement inherits the same tolerance family (device
  re-run pending — agreed formula, mirrored bin selection).
- Zero LUT is a bit-exact CPU no-op; d-shrink and σ-floor each recover a
  pinned mean-level conflict.

## 11. Verification record

- 2026-09-10, Xiaomi 25080RABDG (Mali GPU), headless `connectedDebugAndroidTest`:
  `RawSrRobustnessInstrumentedTest` 7/7 pass, `RawSrRobustnessTest`
  (JVM) 13/13 pass.
- One real bug found by the agreement gate: the GLES pass warped raw
  moving-guide texels while the oracle (§4) warps the 3×3 `movMean`
  table. Symptom was systematic CPU-high/GPU-low weights with the
  exposure scene still agreeing (both sides clamp to 0 there). Fixed in
  `robustness.glsl` (`movMeanAt` helper); all five agreement probes then
  report `rDiffs=0 flagDiffs=0`.
- Adreno remains UNTESTED.
- 2026-09-22, JVM `testDebugUnitTest` (no device in this environment):
  `RawSrNoiseLutTest` 11/11, `RawSrRobustnessTest` 17/17, `RawSrMergeJobTest`
  20/20 (incl. the chain-level zero-LUT no-op). LUT-enabled and hard-law
  CPU/GPU agreement re-runs on Mali/Adreno are pending.
- 2026-09-27, JVM `testDebugUnitTest` after the reference-parity port (no
  device in this environment): full app suite 782 green (2 pre-existing
  skips), sr-vulkan desktop suite 11 green, `parity_sr_vulkan.py` clean.
  On-device CPU/GPU agreement re-runs (Mali/Adreno) are pending.
