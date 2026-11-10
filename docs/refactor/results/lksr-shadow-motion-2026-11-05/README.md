# Final shadow, noise and sunset fixes

The follow-up investigation fixed three additional implementation defects and
validated a cleaner, shorter default reconstruction. The earlier report below
records the initial motion/chroma pass, not the final settings.

- **Fractional black levels were lost.** TinyDNG exposed integer BlackLevel
  values, and the desktop LK-SR runner averaged them. In `0009`, the actual CFA
  levels are `[63.75, 63, 63, 63.5]` DN. Retaining those fractions and applying
  each to its own CFA cell removes the magenta shadow offset. This was already
  present in the data fit with FFDNet disabled; it was not created by FFDNet.
  The Android merge accepts floating-point normalization from its caller; the
  demonstrated failure was in the desktop DNG reader/runner.
- **Quantized shadow noise was underestimated.** The median of squared
  Laplacian differences locks to discrete code values. Its lower-tail selection
  drove the fitted read-noise intercept to zero in the heater crop. Block mean
  energy with a calibrated lower-tail correction avoids this failure. Synthetic
  shadow variance recovery is 1.015× truth without quantization and 0.980× with
  a 1/960 code step. This remains an image-based estimate, not a claim that a
  camera's complete noise distribution is known.
- **GAT clipped the signal at zero before transforming it.** Read-noise samples
  below black are legitimate. The transform now clips its square-root radicand,
  preserving negative signal samples within the transform's domain. The network
  packing/noise-channel order matches the official FFDNet implementation.
- **Highlight cleanup destroyed valid sunset colour.** Its mask was based on
  the brightest raw channel and spread two pixels into neighbours. A single
  clipped channel therefore neutralised orange even though green and blue
  retained information. The mask now requires all channels near clipping and
  does not spread. The [stage comparison](sunset-root-cause.jpg) directly shows
  orange before the old finishing step and white after it.

The default app/desktop schedule is now **8 iterations, 4 FFDNet calls, prior
strength 0.35, no residual grain reinjection**. The prior weights themselves
were not retrained. Explicit Lab overrides still apply. The old finishing step
returned 40–80% of the removed luminance residual, including noise. Merely
increasing prior strength to 0.5 visibly smeared texture and was rejected.
The eight-iteration candidate was checked on grille, grass, mountain, hair,
trees and car crops, then all eight HDR+ bursts at full resolution. This is
empirical validation on these scenes, not a universal convergence guarantee.

## Final comparisons and checks

All panels share exposure and white balance; they are camera-RGB diagnostic
renders, not independently auto-toned previews.

- [Heater shadows: colour and noise](grill-fixed.jpg)
- [Sunset: orange retained](sunset-fixed.jpg)
- [Grass](grass-new-fixed.jpg), [mountains](mountain-new-fixed.jpg),
  [hair](hair-new-fixed.jpg)
- Full-resolution motion checks: [trees](trees-final-compare.jpg),
  [car](car-final-compare.jpg)
- [Verified final DNG paths, dimensions and timings](final-renders.json)

`lkr_noise_check`, `lkr_robust_check`, `lkr_denoise_check` and `lkr_photo_check`
passed. Coverage includes quantized noise estimation, fractional DNG levels,
signed GAT input, synthetic denoising MSE, chroma/luma invariants, motion
rejection, single-channel-clipped colour preservation and absence of highlight
mask spill. Desktop builds and Android `externalNativeBuildGithubDebug` passed.
Android GPU/NPU image parity was not measured on a device in this pass.

Final DNGs are saved in each supplied burst's **`out/LKSR.dng`**. Previous DNGs
are backed up under `/tmp/rawlens-lksr-subset-final/*-previous.dng`. Float outputs,
run logs and per-burst dimensions/timings are in that same temporary directory.
The original sensor frames and Google/MFSR reference DNGs are untouched.

The [LK paper](https://arxiv.org/html/2104.06191v2) learns both its ResUNet prior
and update weights jointly; our pretrained FFDNet and hand-set schedule are a
substitution, not equivalent learned optimization. The GAT review also used
[the authors' inverse-transform discussion](https://webpages.tuni.fi/foi/invansc/)
and [official FFDNet packing code](https://github.com/cszn/KAIR/blob/master/models/network_ffdnet.py).

---

# LK-SR shadow / motion diagnosis — 2026-11-05

Two confirmed defects were fixed in the shared native solver. No HDR+ merge code
was changed by this investigation.

1. **Shadow chroma cleanup was unreachable at the default luma boost of zero.**
   Its mask was constructed only inside `shadow_boost > 0`. The independent
   `shadow_chroma = 0.7` setting therefore did nothing. The mask now exists when
   either operation needs it, but the prior noise map is boosted only when
   luma boost is requested. This restores colour cleanup without increasing
   the FFDNet strength that smears grass.
2. **Temporal rejection could miss displaced fine structures.** Its signed
   5×5 mean cancels positive and negative residuals. The actual GPU residual
   and acceptance maps expose this on hair. A second, noise-normalised energy
   test now rejects disagreement even when its signed mean is small. Its
   finite-support falloff prevents many weakly accepted frames accumulating
   into a ghost. Existing gradient tolerance and reference-frame weight remain.

The energy tolerance uses the Gaussian second-moment scale `sqrt(2/n)`.
Demosaic correlations and alignment errors mean it is not a calibrated
statistical confidence interval. Real-burst checks are therefore essential.

## Evidence

Each scene image uses the same exposure and white balance for its panels.
Before/after crop panels use identical inputs and crop settings. The full-frame
panel shows the corresponding region from a separate full-resolution run;
noise estimation and tile boundaries differ, so that panel is a consistency
check rather than an exact numerical A/B. All outputs are scale 1.

| Scene | Effective frame weight before → after | Temporal acceptance before → after |
|---|---:|---:|
| Grass, 5a9e | 8.936 → 8.913 | 0.9999 → 0.9994 |
| Mountain, 0543 | 7.227 → 7.227 | 1.0000 → 1.0000 |
| Hair, 0039 | 7.637 → 7.296 | 0.9721 → 0.9198 |
| Trees, IMG_20261104_111404_842 | 2.702 → 1.975 | 0.7223 → 0.4065 |
| Car, IMG_20260919_184641_979 | 6.925 → 6.587 | 0.9877 → 0.9435 |

These are crop averages excluding a 32-pixel border. They describe acceptance,
not PSNR or image quality. The maps are source-grid summaries; different source
frames are not exactly the same spatial coordinates. Exact numbers and crop
locations are in [metrics.json](metrics.json).

- [Grass comparison](grass.jpg) / [maps](grass-maps.jpg)
- [Mountain comparison](mountain.jpg) / [maps](mountain-maps.jpg)
- [Hair comparison](hair.jpg) / [maps](hair-maps.jpg)
- [Trees comparison](trees.jpg) / [maps](trees-maps.jpg)
- [Car comparison](car.jpg) / [maps](car-maps.jpg)
- [Hair: actual residual, signed mean, RMS and acceptance](hair-residual-maps.jpg)

The static scenes keep essentially all their prior frame support. The moving
scenes reject more inconsistent samples; fine hair and foliage improve in the
inspected crops. This does not establish perfect recovery of every moving
strand or leaf. The Google merged DNGs are useful visual references, but use a
different reference frame / geometry and are not pixel-aligned ground truth.

## Validation and limits

Desktop Vulkan (Apple M1, ncnn Vulkan fp16) native build succeeded. The dedicated
`lkr_robust_check` passed:

- Static Gaussian residual: mean temporal weight 0.99806.
- Alternating-sign and coherent motion residual: weight 0; reference weight 1.
- Shadow mask at luma boost 0: 0 with chroma off, 1 with chroma on.
- Prior noise factor remains unboosted.
- Synthetic chroma variance ratio 0.1103, maximum luminance change 0.

Five supplied bursts were also run at full resolution. DNGs, float outputs,
logs and detailed traces are under `/tmp/rawlens-lksr-detail/`, with full outputs
named `grass-full.dng`, `mountain-full.dng`, `hair-full.dng`, `trees-full.dng`,
and `car-full.dng`. Original input files were not modified.

The fixes do **not** redesign luminance denoising. Stage maps show that a
stronger FFDNet prior can still remove fine texture; weaker settings retain
luminance grain. Experiments changing the prior schedule, coupling, or adding
an alternate luma filter did not justify production changes and were removed.
Residual luminance grain remains a quality limitation. Android device/NPU
execution was not verified in this pass.

The [original LK paper](https://arxiv.org/html/2104.06191v2) supplies the
reconstruction framework. Our FFDNet substitution and robust motion weights
are application extensions, not a reproduction of its learned prior.
