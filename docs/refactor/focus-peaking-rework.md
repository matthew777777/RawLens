# RAW focus peaking rework — 2026-10-31

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

The photo and RAW-recording overlays now use the same RAW detail detector. This is a sharp-detail assist, independent of the HAL's autofocus success flag. A featureless surface cannot establish optical focus from image gradients; its visible sharp boundaries can be marked, while its flat interior remains unmarked.

## Primary-source review

- [Magic Lantern, `src/zebra.c`, `calc_peak`](https://github.com/reticulatedpines/magiclantern_simplified/blob/dev/src/zebra.c): display-luma Laplacian with optional subtraction of a strong first derivative. Useful distinction between fine detail and high-contrast boundaries; its display thresholds are not normalized RAW/noise thresholds. Retrieved source SHA-256: `5605ef086387b8cae6bed4813447c19036cf4a3b1795cb674cfe7654258c6b8a`.
- [darktable, `src/common/focus_peaking.h`](https://github.com/darktable-org/darktable/blob/master/src/common/focus_peaking.h): prefilters noise and compares nearby/distant gradient magnitudes before thresholding and postfiltering. This informed the multiscale approach. RawLens does not claim a literal port of darktable's surface blur or its display-luma normalization.
- [Android `SENSOR_NOISE_PROFILE`](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_NOISE_PROFILE): the per-channel signal-dependent variance model is used only with timestamp-matched metadata, or an explicit profile override. Local noise estimation remains available without a usable profile.

This is a new RAW adaptation. Its constants and clipping policy are RawLens engineering choices, not Magic Lantern constants or guarantees of absolute optical focus.

## Changes and rationale

The old live path sampled one 8×8 Bayer patch per cell, used fixed squared-energy/ratio gates, and dropped cells without a neighboring grid mark. Sparse sampling and the absolute threshold missed weaker sharp detail. A clipped edge or noise could pass the energy tests. Marks appeared at grid-cell centers rather than on the detected edge.

The replacement:

1. Reads 32×32 Bayer patches into 16×16 averaged-green samples; normalizes each of the four Bayer phases separately. Explicit level overrides win over matched dynamic levels and static characteristics. Neighboring frames cannot provide dynamic levels/noise for these pixels.
2. Flags a sample if **any** underlying RAW channel reaches 98% of its black-subtracted range. A summed-area mask excludes the entire support of both gradient scales around such samples. This is sensor clipping rejection; a display-white tone-mapped pixel is not necessarily RAW-clipped.
3. Estimates noise from median absolute diagonal Haar residuals and takes the conservative maximum with a usable metadata noise model. Removes isolated impulses and applies a 3×3 binomial prefilter.
4. Compares gradient energy at one- and two-sample distances. Requires a noise-dependent gradient floor and a fine/coarse ratio, with a correction for the square kernel's diagonal-edge bias. The fixed minimum gradient is 0.006 in normalized RAW; the noise term is six times the filtered gradient standard deviation. The ratio threshold ranges from 0.68 on axis-aligned edges to 0.62 on diagonal edges.
5. Requires support within the patch, rather than requiring another distant grid cell to pass. Returns actual normalized edge coordinates so the renderer places the dot on that edge. It does not fill smooth regions or force a percentage of the frame to show marks.
6. Runs the same calculation in a source-built native library for RAW16/10/12, independently of optional Halide archives. The Kotlin implementation is the reference/fallback. Native arithmetic disables fast-math and contraction; device tests check exact masks and edge coordinates.
7. Samples photo peaking on a bounded background worker: one borrowed RAW frame, no backlog. Retaps/manual focus changes invalidate pending output and clear old marks. Stop invalidates publication; the frame lease keeps the source alive until processing finishes. The camera handler does not run this detector. The recording RAW path shares the worker and detector.

`LegacyScopeFocusSampler` is retained only as the independent reference for the existing fused Halide scope ABI and historical A/B diagnostics. `ScopeMeterAB` explicitly checks that historical implementation. Neither the live photo nor RAW-recording overlay uses it. The separate native monitor library's display-luma shader is also not the RAW overlay path exercised here.

## Device verification

[Headless results and raw measurements](results/peaking-2026-10-31/README.md).

After the user's headless-only instruction, testing used no Activity, TextureView, screenshots, or input injection. Camera2 captured RAW-only buffers directly, with exact timestamp-matched metadata. The suite covered:

- Smooth planes/ramps, weak sharp axis/diagonal edges, broad blurred edges, clipped edges, 400 noise patches and an isolated hot sample.
- Native/Kotlin equivalence across four CFA patterns and RAW16/10/12, including padding, nonzero buffer origin and truncated-buffer rejection.
- Real 4080×3060 buffers at 0, 2, 4 and 10 D plus high-exposure stages. Every marked point's complete Bayer support was independently checked for clipping. Native masks and coordinates matched the reference exactly.
- Production photo worker delivery on `photo-peaking`, immediate release of the camera's image ownership, correct final lease release, and stop hiding publication.

The initial Kotlin-only device run was too slow (about 175–720 ms). The native runs measured about 81–165 ms, below the existing 250 ms sampling interval, without moving processing onto the camera thread. Those timings are measurements for this device/build, not guarantees for every sensor or recording resolution.

## RAW-only / ISP preview

RAW-only remains the normal initial photo session. An ISP stream is a compatibility fallback when RAW-only configuration is rejected or the existing throughput probe selects the preview remedy; a saved per-camera verdict can select that path on later opens. Where used, preview selection requests a normal advertised HD size rather than a tiny placeholder.

This peaking detector never needs ISP pixels. All new hardware tests used `PREVIEW=none`. The existing frame-rate probe is a compatibility heuristic, not independent proof that an ISP stream improves every slow scene. Its classification policy was not broadened as part of the peaking rework.
