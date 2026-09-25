# RAW-SR motion robustness (Prompt 4C): equations and contracts

This document is written **before** the implementation. The code must match it;
any behavior difference is a bug unless recorded here as a deliberate deviation.

References: Wronski et al. SIGGRAPH 2019 §5 (esp. §5.2 robustness model),
Jamy-L IPOL 2023 `robustness.py` Alg. 6–9 (`compute_robustness`,
`compute_guide_image`, `compute_local_stats`, `warp_stats`, `compute_d_sigma`,
`compute_s`, `robustness_threshold`, `local_min`), the Jamy-L Monte Carlo
clipped-noise LUT (`monte_carlo.py`, `noise_lut.py`, transform
`clip_raw_then_sqrt_bayer_quad_rgb_v1`), and the SkyKing working reference
(`motionv2/mfsr_robustness.glsl`, `mfsr_bjzhou_rejection_*.glsl`,
`direct_rgb_accumulate.glsl` robustness consumption).

## 1. Photometric domain

Statistics are computed on **unshaded black/white-normalized Bayer observations**,
`v = (code − b)/(W − b)`, unclamped. This is the domain where the captured
`var(code) = S·code + O` model holds exactly. It is deliberately separate from
the GAT covariance guide (variance-stabilized units would distort photometric
comparison) and from the shaded fusion path.

Lens shading: no gains are applied in this domain, so none are booked. This is
exact — not an approximation — for same-exposure, same-lens bursts: shading
multiplies both frames' signals and noises equally, so the standardized
disagreement `d/σ` is gain-invariant to first order. Exposure mismatch breaks
the invariance; it is caught by the planner's global `EXPOSURE` gate
(`RawSrBurstPlanner`, `MAX_EXPOSURE_RATIO = 1.10`, untouched) and by the
exposure-mismatch robustness test. A lens consistency test proves identical
decisions with and without a lens model attached.

## 2. Linear guide (per frame)

Per Bayer quad `q`, three channels from the quad's four samples by sensor CFA
color (exactly 1 R + 2 G + 1 B per quad for every Bayer pattern and origin):

- `L_R(q)` = normalized R sample; `L_B(q)` = normalized B sample;
  `L_G(q)` = mean of the two normalized green samples.

Per-channel normalized noise coefficients: the channel's sample phases map to
`(S, O)` exactly like the 4B.1 guide (8-coefficient by sensor raster phase,
6-coefficient by sensor color); R/B use their single sample's pair, G uses a
QUARTER of its two samples' pairs:

- `α_R = S/(W−b)`, `β_R = (S·b+O)/(W−b)²` (same for B); `α_G, β_G` are the
  two green phases' pairs summed then quartered. The guide green channel is
  the MEAN of two samples, whose variance is `(v1+v2)/4` — halving (mean of
  pair variances) overstates green noise 2x, discounts green differences
  against R/B in the photo term, and mottles chroma.

Per-sample rail gate (code domain, using the sample's own `(S, O, b, W)`),
highlight side only:

- `σ_code = sqrt(S·code + O)`; sample is near-rail iff
  `code > W − 3·σ_code`. There is deliberately NO shadow rail: signal
  within 3σ of black is normal read-noise-limited data, and zeroing it
  denies shadows every moving frame (lifted noise floor, patchy Rc);
  below-black evidence is judged by the photo term, never by a gate.
- Quad `q` is saturated iff any of its four samples is near-rail. The `3σ`
  multiplier is a 4C-chosen, test-validated constant (see §8). The rail gate
  is retained as a pre-gate alongside the measured noise LUT (§5): rails are
  censored before any statistical comparison, LUT or analytic.

## 3. Local statistics (IPOL Alg. 8)

Per channel, 3×3 window with clamp-to-edge taps (mirrors the reference):

- `μ(q) = Σx/9`, `V(q) = max(Σx²/9 − μ², 0)` (variance floored at 0).

## 4. Warp (IPOL `warp_stats`, bilinear deviation)

Moving means are sampled at `q + flow(q)` in **quad units** (our flow convention;
the reference scales raw-unit flow by 0.5 — same geometry). Flow lookup is
**nearest tile, never blended**: interpolating across rejected-flow
discontinuities is forbidden. The moving sample itself uses bilinear weights
`w_i` (deviation from the reference Dogson biquadratic; matches the alignment
`bilinearOrNull` convention, CPU/GPU bit-consistent by test):

- `μ_m(q) = Σ w_i·m_i`, `Σw = 1`; taps clamp to edges.
- Warp center outside `[0,W)×[0,H)` → `OUT_OF_BOUNDS`, `R = 0` (the reference
  writes `+inf`, which likewise forces `R = 0`).

## 5. Expected disagreement (IPOL `compute_d_sigma`, LUT replaced)

- `d²(q) = Σ_c (μ_r,c − μ_m,c)²`.
- `σ²(q) = Σ_c [V_r,c + V_m,c]`, with `V_r,c` the measured reference local
  variance (scene + noise) and `V_m,c = (Σw_i²)·(ᾱ_c·μ_m,c + β̄_c)/9` the
  model-expected moving variance. The `/9` accounts the 3×3 averaging weights;
  `Σw_i²` accounts warp interpolation (1 for integer shifts); `(ᾱ, β̄)` are the
  channel coefficients from §2.
- Edge rule: `σ² == 0` with `d² == 0` accepts (`r_photo = 1`); `σ² == 0` with
  `d² > 0` rejects (infinite standardized distance).
- Missing, invalid, inconsistent, or exactly-zero noise model: the photo gate
  stands open (`r_photo = 1`) with an explicit `MODEL_*` flag. No noise floor
  is invented; alignment, validity, and saturation gates still bind.

This replaces the reference `σ² = max(V_r, LUT(brightness))` floor plus the
`d²·(d²/(d²+d²_LUT))²` shrink. Documented approximation, validated by the
saturation and exposure tests; no IPOL equivalence is claimed for clipped rails.

### Measured noise LUT (added 2026-09-22)

When a `RawSrNoiseLut.Lut` is provided — and only when both models are valid —
the analytic `d²/σ²` above is corrected exactly like the reference
`compute_d_sigma`, before the `σ² == 0` edge rule:

- `brightness` = mean of the three reference channel means, clamped to [0, 1]
  (the LUT's binning key).
- `(σ²_LUT, d²_LUT)` = nearest-bin lookup
  (`floor(b·(bins−1) + 0.5)`, identical on CPU, in the generator, and in GLSL).
- `σ² = max(σ², σ²_LUT)`; when `d² > 0`, `d² *= (d²/(d²+d²_LUT))²`.

The LUT is generated for the LINEAR transform
(`clip_raw_then_linear_bayer_quad_rgb_v1`): the reference Monte Carlo
procedure (stratified latent prior, Welford 3×3 patch statistics, R/B
single-sample channels, G mean-of-two-greens, binning by measured reference
brightness) without the square root, since our guide is linear. Reference
sqrt-transform `.npz` files are not loadable and not interchangeable; the
transform name is part of the cache identity. LUTs are reference-derived and
cached per sensor profile under `rawsr-noiselut-<sha1>.bin` (versioned
little-endian: magic `RLNLUT01`, version, bins/trials/seed, RGBG alpha/beta,
σ²/d² curves, SEMs, bin counts). A null LUT keeps the analytic path exactly
(a zero LUT is a bit-exact no-op, pinned by test).

## 6. Flow irregularity and threshold (IPOL `compute_s`, `robustness_threshold`)

Over 3×3 tiles (in-bounds tiles only, mirroring the reference):

- `spread² = (max dx − min dx)² + (max dy − min dy)²`; `spread > Mth → s1 else s2`.
- `Mth = 0.4` quad pixels: the published `Mt = 0.8` is in raw pixels (§“State
  and convert Mth spatial units explicitly”). Nonfinite flow in the window
  forces `s1`. Unreliable tiles are EXCLUDED from the spread (their flow is
  garbage, not motion): a reliable quad surrounded by unreliable neighbours
  is judged on the reliable subset, and falls back to `s2` when that agrees.
  Unreliable quads themselves still take `s1` via their own per-quad gate —
  only the halo they cast on neighbours is removed.
- `R(q) = clamp(S·exp(−d²/σ²) − t, 0, 1)` with centralized `t = 0.12`,
  `s1 = 2`, `s2 = 12` (Jamy-L `RobustnessConfig` values, unchanged).
- Unreliable-flow tiles do **not** use their flow: the zero-shift hypothesis is
  tested instead (§7). That path uses `S = s1` (untrusted neighborhood).

## 7. Gate combination and flags

`R(q)` above is the photometric term. Hard gates force `R = 0` with flags:

| Flag | Meaning |
|---|---|
| `FLOW_UNRELIABLE` | Tile unreliable and zero-shift hypothesis falsified |
| `STATIC_HYPOTHESIS` | Tile unreliable but zero-shift agreement accepts (informational; `r > 0`) |
| `RESIDUAL` | Reliable tile with LK residual above `maxMeanAbsoluteResidual` |
| `OUT_OF_BOUNDS` | Warp target outside the moving frame |
| `INVALID_FLOW` | Nonfinite flow components |
| `SATURATED` | Near-rail samples in either frame's quad (highlight side only; shadows never rail) |
| `PHOTO_CONFLICT` | All hard gates pass but the photo term reaches 0 |
| `MODEL_MISSING` / `MODEL_ZERO` | Photo gate open for lack of a usable model |
| `HOTPIXEL` | Quad holds a masked stuck-bright tap (see `raw-sr-hotpixels.md`) |
| `UNBLOCKED` | Quad attenuated by variance-loss mask (see `raw-sr-unblocker.md`) |
| `MOTION_IRREGULAR` | Reliable-but-irregular tile merged under the s1 motion scale (informational; support attribution, never a veto by itself) |

A weak Hessian alone never auto-rejects a static noisy flat patch: such tiles
take the zero-shift path, and agreement accepts them with `STATIC_HYPOTHESIS`
(a tested hypothesis, never a silent zero substitution). Flatness is not
evidence; demonstrated conflict is.

Final map: `r = 5×5 clamp-window minimum of R` (IPOL Alg. 9); flags stay
per-quad own-evaluation. All outputs finite; hard-invalid quads get explicit
zero weights.

## 8. Rc accumulation

`Rc` is a per-quad float field, initialized to zero. Each accepted
non-reference frame contributes its robustness exactly once per quad:
`Rc += r_n`. The reference is excluded; reference-only mode leaves `Rc`
identically zero. `1 + Rc` is described as a robustness-based frame-support
estimate for diagnostics — not a statistically exact effective sample count.

## 9. Deliberate deviations (summary)

1. Linear photometric domain instead of sqrt/white-balanced guide (§1).
2. Bilinear warp instead of Dogson biquadratic (§4).
3. Nearest-tile flow; tested zero-shift hypothesis for unreliable tiles (§6–7).
4. Analytic expected variance + rail rejection, optionally corrected by a
   measured Monte Carlo LUT generated for OUR linear transform (§5), never the
   reference sqrt-transform curves; `3σ` rail multiplier is the single
   4C-chosen constant, validated by saturation tests.
5. `Mth` converted 0.8 raw px → 0.4 quad px (§6).
6. Per-channel noise reduction, exact for Bayer quads (§2).
7. Lens gains N/A by unshaded-domain construction (§1).
8. SkyKing morphology not used; IPOL 5×5 local min retained (§7).

## 10. Quantitative acceptance criteria (declared before evaluation)

- CPU/GPU agreement: `|r_gpu − r_cpu| ≤ 2e−3 + 2e−3·|r_cpu|` per quad (strict
  family); flags bit-exact; `|Rc_gpu − Rc_cpu| ≤ 2e−3 + 2e−3·|Rc_cpu|`.
- Static noisy retention: ≥ 95% of interior quads accepted (`r > 0) across
  dark/mid/bright scenes with matched profiles.
- Conflict rejection: ≥ 90% of independently-translated foreground quads
  rejected (`r == 0`); saturated quads 100% `SATURATED`-flagged with `r == 0`.
- Finiteness: every output finite on every test, including poisoned flow.
- Rc exactness: hand-built accumulation matches to 1e−6; reference-only Rc is
  exactly zero; repeated execution is bit-exact.
- Exports: CSV + PNG diagnostics exist and are non-empty for the multi-scene run.

Addendum (2026-09-22, measured noise LUT):

- LUT-disabled CPU/GPU agreement inherits the §10 tolerance unchanged: a null
  LUT is a no-op on both sides (CPU formula untouched, shader correction
  gated by `u_lut_enabled`).
- LUT-enabled CPU/GPU agreement inherits the same tolerance family (device
  re-run pending — agreed formula, mirrored bin selection).
- Zero LUT is a bit-exact CPU no-op; d-shrink and σ-floor each recover a
  pinned mean-level conflict; the LUT is ignored when the model is invalid.

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
