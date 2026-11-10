# Scene-driven HDR+ automatic strength

## Scope and evidence

This changes automatic strength selection, not the Vulkan merge algorithm,
manual slider, capture sequence, supported formats, or device requirements.
The existing frame selection and Fast/HQ thresholds remain compatibility
policies. This is not a completed whole-application audit or a claim of
cross-device performance parity.

Primary references consulted 2026-11-05:

- [Hasinoff et al., HDR+ (2016)](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/): aligned RAW bursts and robust merging.
- [Liba et al., very low-light photography (2019)](https://research.google/pubs/handheld-mobile-photography-in-very-low-light/): motion/noise tradeoffs; capture-time motion metering is distinct from this post-capture selector.
- [Google Night Sight explanation](https://research.google/blog/night-sight-seeing-in-the-dark-on-pixel-phones/): subject motion as well as camera shake matters. Gyro alone cannot describe subject motion.
- [MathWorks wavelet noise estimator](https://www.mathworks.com/help/wavelet/ref/wnoisest.html): Gaussian noise scale from median absolute deviation divided by 0.6745.
- [Burst Photo frequency merge](https://github.com/martin-marek/hdr-plus-swift/blob/main/burstphoto/merge/frequency.swift): slider controls exponential robustness/noise/motion terms. RawLens' pinned port is documented in `app/src/main/cpp/hdrplus/PARITY.md`; `RawMergeHdrPlusGpu.h::frequencyNorms` rounds HQ strengths to integers.

None of these sources specifies RawLens' automatic slider values or proves
that a particular scene must use 3 or 6. The estimator is based on published
statistics; the mapping from its output to this app's slider is a separate,
explicit engineering policy requiring image-quality validation.

## Implementation

`HdrPlusSceneNoise` samples at most 64×64 disjoint 4×4 RAW patches per
frame, across all four Bayer phases. Each phase supplies a diagonal Haar
coefficient `(a-b-c+d)/2` from same-color samples two pixels apart. Its
coefficient energy is one, so independent equal-variance noise retains its
standard deviation. Constant/linear scene components cancel. Coefficients
are divided by their local black-subtracted signal; centered MAD divided
by the Gaussian factor gives a robust relative noise estimate. Clipped
samples and near-black samples without a stable denominator are excluded.
Too few valid samples means unknown, not noiseless.

The burst median relative noise `r` maps continuously to
`1 + log2(1 + (r / 0.02)^2)`, bounded by the valid automatic range 1–13.
The 2% noise budget is an explicit RawLens policy parameter, not a value
from Google's paper and not a universal perceptual threshold. The log
response avoids a linear climb toward excessive strengths. ISO is retained
in the selector API for compatibility but does not affect this calculation.
Missing noise measurements fall back to the existing conservative default.

Motion reduces the extra strength above the native minimum. Per-frame
strengths retain the burst gyro constraint. Maps may only reduce the
per-frame budget, never replace it with the old maximum of 13. A 3×3 minimum
filter extends conservative rejection around risky cells; unlike the old
box average it cannot increase strength at a rejected cell. Manual strength
continues to disable both frame strengths and maps. Manual mode does not
run the new noise estimator.

## Limitations and validation boundaries

- Single-frame Haar coefficients can include fine texture, and clipping,
  quantization, correlated noise or near-black signal bias the estimate.
  Relative noise is not lux and the method does not classify day/night.
- Existing motion grids compare adjacent unaligned frames. They are risk
  indicators, not calibrated reference-frame alignment uncertainty. Dense
  MAD still contains sensor noise; this can reject too much in noisy scenes.
  The native merge retains its own post-alignment mismatch/robustness logic.
- The old empirically fitted motion/gyro thresholds and frame rejection
  policy remain; this change does not pretend they were derived from papers.
- HQ rounds strengths internally. Continuous selector output therefore does
  not imply continuously varying HQ parameters. Native formulas are preserved.
- Neither unit tests nor desktop MoltenVK establish Android GPU-driver
  compatibility, unchanged phone latency, or optimal image quality.

`HdrPlusSceneNoiseTest` checks known Gaussian noise, black offsets, code-depth
scaling, missing/clipped inputs and monotonic bounded response.
`HdrPlusAutoSelectTest` checks manual overrides, frame/reference mapping,
noise dependence, ISO independence, motion/gyro constraints and map safety.
`HdrPlusLocalBurstTest` is an opt-in real-burst integration test that executes
the actual Kotlin classes and exports decisions/maps to the native runner.
Reproduction instructions are in `tools/hdrplus-auto/README.md`.

## Default activation

Auto strength and quality default to true. Existing preferences without
`hdr_plus_scene_auto_migrated=true` also select Auto, including previously
stored false flags, as explicitly requested. Saving settings writes the
marker; later manual choices are respected. HDR+ enablement is unchanged.

Local results and outstanding calibration work are recorded in
`docs/refactor/results/hdrplus-scene-auto-2026-11-05/report.md`.
