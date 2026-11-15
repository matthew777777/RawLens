# KernelNet softness at 1x and 2x

The owner compared sr-pipeline with LK-R Lite on roof edges, railings and
foliage in IMG_20210228_133623 and reported an excessive filtered appearance.
The owner explicitly required the correction at both 1x and 2x.

The retained correction changes the sampling bandwidth during reconstruction;
it does not apply an unsharp mask or a post-processing contrast boost.
Comparison-frame green samples use half the base model bandwidth in output
pixels. The original reference width remains a stable fallback when temporal
samples are rejected. R/B colour differences retain their established wider
kernels, and continuous motion/rejection and S.3 remain active.

For a comparison's base Gaussian quadratic distance q and output scale s:

    area = 4 * s * s
    w_green = area * exp(-0.5 * area * q)

The existing support taper and robustness weight still multiply this weight.
The area factor compensates the two-dimensional Gaussian integral: narrowing
must not silently downweight a comparison against the wider reference. All
weights remain nonnegative; this is still a normalized sample average.

The reference is selected explicitly by a mode flag, not by testing for zero
flow. A stationary comparison is still a comparison. Native signatures and
push-constant size are unchanged; the resident-frame entry accepts additional
mode bits. Existing 0..3 native callers retain their original interpretation.
The detail path requires colour-difference input so independent raw R/B
channels do not acquire a different luma bandwidth. Explicit colour-diff off,
Wronski and CPU reference paths retain their legacy reconstruction widths.

## Isolation experiments

The reference KernelNet map is the same at both output sizes. At the original
0.25 width scale, about 49% of its axis widths reach the 1.2 px cap; about 33%
are raised to the 2x 0.7 px floor. Reducing the cap alone from 1.2 to 0.85 barely
changes strong roof-edge gradients. Lowering the floor from 0.7 to 0.55 and
0.35 improves edge response, confirming an excessive minimum resampling
bandwidth, but would also narrow the reference and colour reconstruction.
Those global setting changes were not made the default.

A comparison-only output-width correction retains the wider reference and
colour support while reducing the additional low-pass effect at both sizes.
A new optional --kn-floor override supports controlled width diagnostics;
it must be positive and no greater than --kn-cap. The existing base width
settings remain numerically unchanged.

## Measurements

Matched 20-frame crops used the original reference 0, automatic noise LUT,
KernelNet, base sigmaScale 0.25, cap 1.2, floor 0.9 at 1x / 0.7 at 2x, and
reference AsShotNeutral colour-difference gains R=2.211663, B=1.6.

The strongest 10% of green-gradient pixels in the corresponding LK-R Lite
roof ROI define a common mask. This measures edge contrast, not equivalence
to ground truth; noise and sampling phase can also affect the statistic.

| Output | Previous gradient / LK-R Lite | Updated matched crop / LK-R Lite | Gain over previous |
|---|---:|---:|---:|
| 1x | 0.710 | 0.841 | 18.5% |
| 2x | 0.641 | 0.843 | 31.4% |

Full-frame regeneration gives updated ratios of 0.844 at 1x and 0.850 at 2x.
ROIs: 1x x=1300..1749, y=1025..1199; 2x x=2600..3499, y=2050..2399.
The controlled crop origin is raw (1100,600), size 1024x1024. The baseline
1x comparison panel comes from that controlled crop, not a separately
regenerated baseline full image. Full-frame and crop confidence distributions
can differ slightly.

More detail exposes more grain. On the matched 2x sky crop, after planar
trend removal, green RMS is 40.6 code units before, 116.4 with the retained
bandwidth, and 172.9 in the supplied LK-R Lite result. The less aggressive
comparison footprint reached only 0.795 of LK-R Lite's roof gradient with
RMS 64.0; the chosen setting better addresses the reported softness.

The prior pedestrian ROIs were also checked. Their four-pixel green phase
RMS max/min ratios improve from 1.49 to 1.20 and from 2.40 to 1.81, although
high-frequency energy increases with the recovered contrast and grain. This
is not a claim of eliminating every visible alias at 600% magnification.

An independent eight-frame dim-scene crop (IMG_20260929_170823_957) succeeded
at both scales. On low-gradient pixels, checker coherence remains below 1%
of high-pass RMS: 0.0082 at 1x, 0.0021 at 2x. Phase RMS max/min is 1.13 / 1.28.
These are smoke/quality observations on this crop, not validation of all
lighting, motion or devices.

## Validation and artifacts

- Module suite: 267 tests, 191 executed, 76 skipped, zero failures.
- Actual GPU oracle exercises legacy, detail-comparison and explicit-reference
  modes at both 1x and 2x, with constant/affine flow, integer-centre crossings,
  non-square images and partial edge tiles.
- Reference-frame and chroma numerator/denominator arrays remain bit-identical
  to the legacy kernel at each tested geometry. Narrow comparison green is
  checked against an independent analytic Gaussian oracle. Its numerical
  tolerance scales with Gaussian peak density and fp32 coordinate sensitivity.
- Existing Jamy-L/Wronski native parity and robustness tests still pass.
- The actual CLI parser is tested for the new width-floor override.
- No Android hardware test or unrelated app change is included in this work.

Full 20-frame outputs:

- /tmp/IMG_20210228_133623_detail_1x/merged.dng (4032x3024, 19.1 s on this run)
- /tmp/IMG_20210228_133623_detail_2x/merged.dng (8064x6048, 25.1 s on this run)

Comparison: /tmp/srp-detail-1x-2x-comparison.png. Columns are LK-R Lite,
previous sr-pipeline, updated sr-pipeline. The 1x row is enlarged with nearest
neighbour display only; the 2x row is native-sized. DNGs contain linear camera
RGB and no display sharpening. Timings are individual runs under varying
system load, not controlled performance comparisons.
