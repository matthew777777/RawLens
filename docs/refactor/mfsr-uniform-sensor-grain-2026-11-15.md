# Uniform original sensor-grain retention

The continuous green reconstruction fixed the strong Bayer-phase edge zipper,
but averaging every green frame suppressed the original sensor texture too much.
The owner requested original grain throughout buildings, trees, plants and flats,
with no generated grain and no flat-area-only mask.

KernelNet colour-difference merges now use six source frames for luminance,
evenly spaced across the burst's processing order, including the reference and
last frame. Fewer than six frames retain all luminance contributors. Weights
sum to the input frame count. The same temporal weights apply at every image
position; all frames still contribute colour differences and undergo alignment
and robustness checks. No additive noise, texture classifier or spatial grain
mask is used. Both 1x and 2x use this policy.

The green sampler stays continuous. A reweighted reference uses that same
reconstruction rather than its broader Gaussian fallback, preserving fine
source texture and sharpness. Existing rejection still protects moving areas.
Wronski and colour-difference-disabled paths retain their previous behavior.

`PipelineConfig.grainRetention` and CLI `--grain-retention` accept 0..1:
1 (default) uses the six-frame luminance selection, 0 restores all-frame green
averaging, intermediate values blend the temporal weights. This changes actual
sensor-noise averaging, not noise synthesis. Native green-frame weights default
to 1 for existing low-level callers. No new resident buffers are needed.

Validation:

- 271 pipeline tests: 195 executed, 76 skipped, zero failures/errors.
- GPU grain test measures retained noise separately from clean signal in flat
  and textured regions. Fine-grain RMS increases 1.63/1.74x at 1x and
  1.65/1.76x at 2x. R/B colour differences remain bitwise identical; clean flat
  input acquires no invented noise.
- GPU straight-edge regression still passes its original 0.05/0.04 sensor-pixel
  contour thresholds at 1x/2x. The continuous-flow reconstruction checks pass.
- Weight checks cover 1..59 frames, total mass, positive reference coverage and
  the all-frame opt-out. CLI option consumption is tested.
- Original burst sky crop at 2x: detrended green RMS 73.2 -> 120.7, compared
  with 128.0 for LK-R Lite. Fine-band RMS 19.7 -> 37.3; the output retains the
  correlation of interpolated sensor grain rather than adding white noise.
  Four-by-four phase RMS ratio is 1.039, with no strong noise checker pattern.
- App GitHub debug Kotlin compilation succeeds with the shared JNI changes.

Full review outputs are generated under
`/tmp/IMG_20210228_133623_grain_1x/merged.dng` and
`/tmp/IMG_20210228_133623_grain_2x/merged.dng`.
