# Merged noise model (normative)

Sabre analogue: `GetMergedNoiseModel(&merged.frame_metadata.dng_noise_model_bayer)`.
Averaging N independent samples scales both the shot-noise slope and the
read-noise offset by 1/N. A merged burst DNG carrying the reference
single-frame profile overstates its noise, and downstream converters
over-blur it. This stage derives the merged profile from the measured
support instead.

## 1. Derivation

- Per-quad support is `1 + Rc`, folding alignment robustness, hot-pixel
  rails, and unblocker attenuation (all three attenuate `r` before `Rc`
  accumulates, so the support already accounts for them).
- The DNG NoiseProfile tag is global per plane, so support collapses to
  its mean: `N_eff = mean(1 + Rc)` — a uniform-gain approximation,
  documented here and recorded per file in the provenance
  `effectiveFrames` key (4-decimal, locale-free).
- `scaleProfile`: `S/N`, `O/N` per RGB plane via the same 8/6-coefficient
  forms as `DngNoiseProfile` (whose max-slope-per-plane collapse is
  reused, conservatism included). Effective counts below 1 or
  non-measurable resolve to the unscaled reference profile; null, invalid,
  or zero-noise inputs omit the tag — never fabricated.

## 2. Sources per path

- Linear (GPU): banded `GL_RED`/`FLOAT` readback of the quad `Rc` texture
  (`readRcMeanSupport`, ~3 MB transient, no retained array). Readback
  failure keeps the reference profile — today's behaviour, not an error.
- Mosaic (CPU): scalar pass over the mapped `rcAcc` accumulator before the
  temp files are deleted (`StreamingMosaic.meanSupport`); the eager path's
  `rc` agrees bitwise (same order, same float32 rounding — pinned by
  `streamingMeanSupportMatchesEagerRc`).

## 3. Guarantees

- Metadata-only change: no pixel, no algorithm version bump (stays
  `4F`/`5E`); the file's own `effectiveFrames` key plus tag 51041 presence
  fully describe what was written (scaled / passthrough / omitted).
- Malformed overrides fall back to the reference model at the writer
  boundary, never into the file.
- Coverage/merge-factor telemetry rides free: the mosaic save logs
  `effectiveFrames` per burst (the Sabre `Average merge factor` analogue).
