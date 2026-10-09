# Unblocker / variance-loss mask (normative)

Sabre analogue: `unblocker.cc` (`CreateUnblockerTexture`, `UnblockerBlur`,
`UnblockerWeightCalculation`, consumed as `unblocker_texture` beside the
rejection texture). Block-based fusion trusts variance it cannot see: a
Nyquist checker averages to flat under 2x2 boxing, so kernel means would
fuse its high-frequency energy at full weight. This stage measures, per
quad, how much signal variance survives the box lowpass and attenuates the
frame's robustness where variance was lost.

## 1. Formula (noise-gated ratio)

Per quad `q` on the inpainted quad-gray field (the same plane the alignment
pyramid consumes):

- `Vfull[q]`: 3x3 clamp-to-edge sample variance (same stencil as
  `evaluate`'s per-channel `refVar`, single channel).
- `H`: half-quad box mean (2x2 quad average, ceil-sized); `Vlow[q]`: 3x3
  variance of `H` at `q/2` (integer halving, clamped).
- `Vn[q] = A·gray[q] + B`: expected noise variance from the green
  normalized coefficients. Green is a documented approximation — quad gray
  is luma-ish, Sabre keeps a green-only fast path, and this is a regime
  gate, not metrology. Known limitation: under strong chroma casts the
  regime call at isolated quads may misjudge; the gate is conservative
  (attenuates only on clear signal excess), so revisit with per-channel
  `Vn` only if fixtures show it.
- Regimes: non-finite measurements, negative noise estimates, and
  noise-dominated quads (`Vfull ≤ 2·Vn`, `UNBLOCKER_NOISE_GATE = 2.0`) all
  keep weight 1. Else `u = clamp((Vlow − Vn/4) / (Vfull − Vn), 0, 1)`
  (the denominator is strictly positive on this branch: `Vfull > 2·Vn`
  with `Vn ≥ 0`).

A pure `Vlow/Vfull` ratio is deliberately NOT used: photon noise loses ~4x
variance under boxing, so a pure ratio would slash every noisy flat and
kill the denoising fusion that 1x merging exists for. Noise carries no
detail to protect; only lost *signal* variance attenuates.

Domain lock (regression 2026-10-08): `gray` MUST be the plain normalized
quad mean (linear; `RawSrCovarianceGuide.plainMean`), NEVER the
GAT-stabilized guide the kernels consume. `Vn = A·gray + B` is a linear
noise estimate, so feeding it stabilized gray compares stabilized variance
(noise floor ~0.25, vs a linear estimate an order of magnitude smaller) and
slashes every noisy flat exactly like the pure ratio this gate exists to
avoid (REDMI5 robustness mean 0.93 → 0.04; the "same plane the alignment
pyramid consumes" phrasing above predates guide stabilization and does NOT
mean the stabilized guide). Both call sites — CPU `buildMovingFrame` and
the GPU `ubGuideProvider` upload — use `plainMean`; the
`RawSrUnblockerDomainTest` pins the producer linear and the fold a no-op on
model noise.

## 2. Contract

- Flat → exactly 1; Nyquist checker → exactly 0 (its box mean is
  constant); model-consistent noise → 1; grid-aligned step edges keep 1
  (no variance is actually lost when the lowpass preserves the edge —
  attenuation needs sub-half-cell structure); misaligned steps land
  strictly inside (0, 1). The exact degenerates agree bitwise CPU/GPU.
- No usable model (null/invalid/zero coefficients) → weight 1 everywhere:
  no model, no gate, never a fabricated attenuation.
- Non-finite taps never attenuate: any NaN touching the measurement keeps
  weight 1 rather than deciding on an untrusted window.
- The reference never attenuates (`u = 1` by construction, no passes run):
  it defines detail; attenuating it would dim everything equally. The
  adapter path (no codes, no model) elides the passes: pixel-identical,
  not a shortcut.

## 3. Consumption

- CPU: `RawSrMergeJob.buildMovingFrame` bakes the field into the frame's
  robustness after the support gate (`applyToFrameAndSpread`:
  `r' = min(r, clamp(u, 0, 1))` — the keep-weight caps agreement
  instead of compounding it, Sabre
  `weight = min(1 - unblocker, frame_weight)` — then the
  contested-warp veto, then a second 5x5 local minimum over the folded
  field; non-finite inputs to zero, `FLAG_UNBLOCKED = 1024` where
  `u < 0.999`). One site serves the Linear oracle and the Mosaic chain
  alike. `Rc` folds the effective weights, so the support overwrite and
  the future merged-noise scale account for unblocking automatically.
  The keep verdict judges the pre-fold field (frame selection is
  independent of blocking); without a usable noise model the field
  rides through (no model, no gate).
- Contested-warp veto (RawLens, not Sabre — Sabre's dense flow never
  ghosts): quads whose 3x3 tile flow spread exceeds the motion
  threshold while u < 0.5 (`VETO_UNBLOCKER_THRESHOLD`: majority of
  the signal variance lost) contribute exactly 0 — the blend bends
  across disagreeing tiles there, and the bend would render at any
  partial weight. Photometric agreement is blind to it (boxed means
  match at whole-period shifts) and the s1 path cannot reject an
  exact match, so the conjunction (contested warp AND lost variance)
  is the only signal that sees period ghosts. Measured surgical
  (4-6.5% of quads on the validation burst; pit/bush keep full
  weight); the veto rides the same spread, so pinholes fill from
  vetoed neighbors.
- GPU: the host uploads the CPU guide gray verbatim per moving frame
  (4E precedent — the pyramid's linear grey lives in a different
  domain and would gate differently), then
  `unblocker_downsample` → `unblocker_weight` → `unblocker_modulate`
  (min-cap + veto) run per moving frame inside the one-workspace
  discipline (transients released per frame —
  burst-length-independent peak is preserved), between
  `robustness_min` and a SECOND `robustness_min`, ahead of
  `robustness_accumulate` — the exact CPU order (min → fold → min).
  `merge_accumulate` and `robustness.glsl` are untouched.

## 4. Fixture rule

Unblocker/robustness fixtures must be model-plausible: white noise against
a clean model is indistinguishable from dense stuck taps and fires every
correct single-frame gate. The approved chain fixture is the block texture
in `RawSrMergeJobTest` (aperiodic, mean-stable, spike-free); unblocker unit
fixtures use exact degenerates plus margin-covered noise.

## 5. Guarantees

- One computation per moving frame; green coefficients shared with the
  robustness upload (no new metadata plumbing).
- Pixel-affecting change: Linear `RawLens-RawSr/4F-scale1`, Mosaic
  `RawLens-MosaicSr/5E`; fold + veto enabled 2026-10-06 (always on):
  Linear `RawLens-RawSr/4F-veto1`, Mosaic `RawLens-MosaicSr/5E-veto1`.
  Blinds kinks eliminated, (1298,1149) ghost resolved to the reference
  value, quilt zmax 1.39 (REF 2.27), sharpness best-ever; VK twins CPU
  (mean|d| 0.3).
- Coverage/merge-factor telemetry rides with the merged-noise item, which
  reads the same `Rc`/denominator accumulators.
