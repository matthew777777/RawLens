# Scene-driven HDR+ automatic strength

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

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

None of these sources specifies RawLens' automatic slider values. The
estimator is based on published statistics; the mapping to this app's slider
follows from the merge's own noise term (below).

## Implementation (revised 2026-11-11)

The first version mapped one relative-noise number through a tuned budget
(`1 + log2(1 + (r / 0.02)^2)`) and then attenuated it with absolute map
thresholds. On nine real bursts that under-merged every low-light scene:
the curve rose one strength step per doubling of noise variance where the
merge needs two, and the map thresholds (fitted at ISO 50) read plain sensor
noise as motion, cutting ISO 1708/4653 still bursts from 6.5/8.0 to an
effective 3.3. Strength now has no tuned scene thresholds.

**Noise model.** `HdrPlusSceneNoise` pools same-CFA diagonal Haar samples
(at most 64x64 disjoint 4x4 patches per frame, all four phases) over the
whole burst, splits them into equal-population brightness bins (cube-root
rule) and fits `variance = a*level + b` in normalized raw units to the
per-bin MAD variances. Camera2/DNG noise profiles are never read. When the
learned per-device model (`rawlens_noise`, the store shared with the wavelet
stage and MFSR) covers the ISO, it is the prior and the burst estimate is the
evidence, mixed by the existing `rawlens_noise::combine` rule
(`WaveletVulkan.combineNoise`). HDR+ reads the store; it does not train it.

**Strength from the merge's own equations.** `HdrPlusMergeModel` restates
how the native frequency merge turns the slider into its Wiener noise norm
and divides that by the power a noise-only frame difference has under the
measured model. The result, the noise gain, is 1 where the merge assumes
exactly the sensor's noise. Auto picks the strength whose gain equals the
target:

- still content: gain = frame count. The Wiener leak is then about
  1/(N+1) per companion, less than one frame's worth of averaging, so more
  gain buys nothing visible and only admits larger ghosts;
- moving content: gain falls geometrically to 1 with the motion share.

**Motion share** is the part of a measured frame difference that noise does
not explain, in power terms: `1 - (expected noise difference / observed)^2`.
The expected difference comes from the noise model evaluated on the same
samples the meter uses. It is computed per pair (burst and per-frame
strengths) and per 32 px cell from the native dense difference (strength
maps, 3x3 minimum). A flat cell in a shaken frame therefore keeps full
strength while textured or moving cells drop to matched noise.

**Fast path.** The spatial merge weights a companion by
`1 - diff / maxDiff`, where `maxDiff` is the reference's own blur residual
over the slider's robustness. `HdrPlusSceneNoise.spatialNoiseReference`
measures that residual and `HdrPlusMergeModel.spatialGains` converts the
rule into the same noise gain (equal leak), so both paths are driven by one
target. The selector uses the table of the path it chose.

**Frames, reference and path** use the same measurement instead of fitted
thresholds. A frame's usable share is its mean still share over the map
cells. A frame whose usable share against either neighbor is below the
merge's leak, 1/(N+1), is dropped (corrupt or re-exposed frames); the
reference maximizes sharpness x usable share; the path is HQ when the
companions hold at least one whole frame of still content and Fast
otherwise. Hot-block fraction and gyro are still logged but decide nothing.

Without the native dense meter there are no maps (the strided ratio grid has
four samples per cell and cannot separate noise from motion) and no frame
dropping; per-frame strengths apply. Unknown noise or motion falls back to
the default strength, Fast, all frames. Manual strength or path overrides
only that choice.

## Limitations and validation boundaries

- Motion is measured between unaligned adjacent frames, so camera shake over
  texture counts as motion. That is conservative, not a ghost detector.
- The Fast-path reference is measured on the preferred reference frame, not
  the one finally chosen, and its noise term is global, as in the merge.
- Haar samples can include fine texture; the per-bin MAD tolerates it while
  most of a bin is flat.
- No test burst here has a corrupt frame or enough motion to select Fast;
  both rules are covered by unit tests only.
- Validated on desktop Vulkan (MoltenVK) only. No phone run, timing or
  memory measurement was done for this revision.

## Results on nine real bursts (2026-11-11)

Mean applied strength, old -> new: ISO 1708 indoor still (20 frames)
3.4 -> 11.9; ISO 4653 night still 3.4 -> 11.2; ISO 50-119 daylight
handheld 1.5-2.5 -> 3.9-6.5. All nine now take HQ (the shaken daylight
burst was Fast before) and keep every frame.
Flat-area noise in developed 1:1 crops (8-bit SD; single frame / old /
new): indoor wall 4.3 / 3.6 / 1.0, indoor floor 4.2 / 2.9 / 1.1, night
road 16.4 / 12.9 / 6.0. Strength 22 gives 0.9 / 1.0 / 5.1-5.5.
Forced Fast with its noise-matched strengths reaches the same levels
(indoor wall 0.93, night road 5.9); before, Fast strengths were not
noise-matched at all. No ghosting seen on wires, foliage or the moving car.

## Default activation

Auto strength and quality default to true. Existing preferences without
`hdr_plus_scene_auto_migrated=true` also select Auto, including previously
stored false flags, as explicitly requested. Saving settings writes the
marker; later manual choices are respected. HDR+ enablement is unchanged.

Local results and outstanding calibration work are recorded in
`docs/refactor/results/hdrplus-scene-auto-2026-11-05/report.md`.
