# Pooled polynomial reconstruction: initial study

The owner requests an organic DSLR appearance: suppress chroma noise and
bleeding, remove most luminance noise but preserve fine grain and actual HF
texture, and reduce the painted appearance of Wronski-style averaging.
This work is experimental. It does not establish a production 2.0 upgrade or
superiority to the upstream Halide-alignment revision.

## New reconstruction

`PolynomialReconstruction.kt` accumulates local normal equations across the
accepted burst samples instead of merely normalizing weighted intensities.
Green uses the basis [1,x,y,x²,xy,y²]; white-balanced colour differences use
[1,x,y]. Slopes and curvature have ridge penalties; the intercept is evaluated
at the requested output position. The kernels are isotropic (green sigma 0.9
sensor pixels, colour sigma 1.35), rather than noisy per-frame steering maps.
Colour uses both a green range gate and a same-colour CFA-lattice anchor to
avoid borrowing a different hue across an equal-luminance edge.

The solver checks effective sample support, numerical rank, finite values and
sample extrema. Fewer than three accepted frame-equivalents keep the ordinary
merge; three to six fade into the fitted result. Existing six-frame luminance
weights preserve sensor grain; colour receives all accepted frames. No noise
is synthesized and no display sharpening is applied. The sensor noise model
sets the colour range tolerance. This is still a regularized point-sample
model: it does not invert a calibrated lens/pixel PSF, and does not cure bad
registration or prove recovery of detail above the sensor Nyquist limit.

The CPU oracle is exposed by `--reconstruction organic`; Vulkan explicitly
rejects this option pending a tested implementation. Existing Wronski and
KernelNet GPU algorithms are preserved. CPU alignment is unchanged, so this
study isolates reconstruction rather than importing the new upstream aligner.

## Upstream comparison

Inspected exact upstream commit
[b71c534](https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution/commit/b71c5344e40a4a738ac6494f3b6ec5897922e29b),
"Implement Halide alignment", authored 2026-11-17. It replaces the alignment
stack, changes binomial pyramid filtering, gradients and flow transfer, removes
FFT grey, and ends with overlapping 16-pixel tiles (stride 8) and median flow
filtering. Its accumulation remains a kernel-regression average. Alignment
and reconstruction improvements are complementary; a same-burst comparison
against that exact revision is still required.

## Verification and limits

The first controlled noisy synthetic texture test at 2x gave a green MSE ratio
of 0.105 against ordinary Gaussian Wronski accumulation at the tested width.
This is one analytic scene with known translations, not a general image-quality
score. Tests also cover constant colour differences, rejected/one-frame support,
equal-green colour edges and repeated CLI tuning arguments. The final focused
run passes all 12 reconstruction/CLI tests.

Real input: all 20 frames of IMG_20210228_133623. Examined 512-pixel crops at
(1536,1536), roof/branches; (1536,0), sky; and (1536,2496), roadway, at 1x.
Baselines use CPU Wronski, app tuning and the same WB colour-difference gains.
The prototype has modestly clearer branches/roof details and much less fine
colour variation in sky, with more fine luminance variation. These are crop
observations; there is no clean reference establishing real detail accuracy.
Preview images share fixed WB/tone mapping in camera RGB, not a complete
colour-managed photographic render. DNGs retain the pipeline colour metadata.
Final sky HP RMS (axial neighbour-average residual, interior excluding 32 px):
chroma 0.00035387 -> 0.00011405 (68% lower); green 0.00022437 -> 0.00052893
(2.36x). This measures high-frequency variation in a flat region, not ground-truth
noise or texture accuracy. Roof green HP increases 1.14x and chroma HP falls 18%,
which can mix real detail with noise and is not scored as resolution gain.
Review assets and four crop DNGs are in
`~/Downloads/IMG_20210228_133623_organic_study/`; comparisons show baseline left,
polynomial right.

Direct cached Kotlin compilation and JNI DNG I/O work in the execution session.
Gradle initialization fails on its local lock socket, and MoltenVK initialization
reports Metal unavailable from this session. The owner confirms the normal
terminal Vulkan launcher works; no GPU hardware failure is inferred.

The broader direct-JUnit run discovers 279 tests with four failures: two kernel
expectations and two end-to-end parity checks. All four resolve in an isolated
rerun with the HEAD `KernelParams` default (`kStretch=4`), while retaining the
new EndToEnd implementation. The working tree already had `kStretch=3` on entry.
No owner tuning change was reverted and no parity thresholds were relaxed.
GPU tests without native properties are skipped, not GPU-validated passes.

## CLI correction

Previously each `--tune` silently replaced the preceding argument. Repeated
flags now compose, duplicate keys resolve to the last value, and `--tune app`
resets preceding overrides. The reported command's stretch 3 / shrink 2 values
are already the current app defaults; the output folder name does not select
stretch 2.5.

## Work stopped at owner request

The full-frame render was cancelled on 2026-11-18; no full polynomial DNG was
delivered. Removed the experiment temporary directory and its 1.16 GiB of
render/cache data. Source drafts and saved offline compiler reports remain.
The bounded-memory polynomial cache passed synthetic tiled/untiled parity
at 1x, 1.5x and 2x. The mixed-radix CPU grey FFT change passed its focused tests.
The mobile separable equivalent-kernel shader and integration are drafts: they
still require CPU/GPU image parity, both-vendor before/after analysis and device
timings before any quality or performance claim. The saved initial offline
baseline has 240 successful jobs across A750, G615 and G720. No A740 was tested.

## Step 4 on Vulkan: per-frame order-1 fit (2026-11-20, rejected)

Tried in `merge_splat.comp` behind merge mode bits 64/128: a per-frame
local-linear [1,x,y] ridge fit (ridge 0.25 px², clamped to the sample range)
over each frame's own sites of a channel in the 7x7 gather, with the frame's
Wronski kernel weights. It stores no normal equations: frames still average
through num/den (cross-frame moments would cost about 7 extra floats per output
pixel for green alone, roughly +340 MB at 12 MP 1x, which the phone merge budget cannot take).

Known-truth bench (WronskiVsKernelNetBenchTest, v2 alignment, all merge
features), PSNR:

| variant | clean 1x / 2x | iso100 1x / 2x | dim 1x / 2x |
|---|---|---|---|
| current (detail green cubic, order-0 chroma) | 47.86 / 48.20 | 44.27 / 44.45 | 37.50 / 37.58 |
| order-1 chroma | 47.90 / 48.20 | 44.31 / 44.45 | 37.52 / 37.58 |
| order-1 chroma + steered order-1 green | 44.17 / 44.52 | 43.40 / 43.76 | 40.16 / 40.74 |

- Chroma: neutral (+0.04 dB at 1x, 0 at 2x).
- Green: equal to the old steered order-0 gather (IPOL clean 44.08). Order-1
  adds no sharpness over it, so the Wronski kernel width is the limit, not the fit order. The dim gain
  (+2.7 dB, flat error halved) comes from per-frame denoising and from
  bypassing the grain-keeping green frame weights. This is the open grain-versus-noise
  (grainRetention) decision, not a reconstruction win.
- Cost even when switched off: the extra code took merge_splat from 32 to 64
  work registers on Mali-G615/G720 (100 % -> 50 % occupancy), slowing every
  merge. Reverted; no code kept.
