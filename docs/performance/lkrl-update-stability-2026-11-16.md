# Reconstruction update instability — source correction, real-burst validation pending

This establishes a defect in `shaders/zupd.comp`. It does **not** establish
that the owner's remaining car-burst maze is eliminated. No accepted maze-free
full car output, Android runtime result, or release has been produced for this change.

## Trace from samples to the unstable update

1. `lite_common.glsl` defines a nonnegative sensor operator U: each valid
   RAW sample averages the bilinear taps of its Bayer plane. Its row sums are 1.
2. `adj.comp` accumulates gradient `g = U^T W (U z - y)` and coverage
   `c = U^T W 1`. Coverage is the normal matrix's row sum, not its diagonal.
3. The 2x default in `lkrl_solver.cpp` passes a coverage box radius of 1,
   eight J updates, and three emulated data steps per update.
4. `zupd.comp` averages coverage over 3x3, derives a scalar gain from that
   mean, and divides the **unsmoothed** gradient by the resulting divisor.
   That smoothed divisor need not bound the normal operator. The gain formula
   does not restore this missing bound.
5. For eight identically aligned frames at 2x, a measured red/blue sensor
   sample covers a disjoint 2x2 HR footprint, each tap having coverage 2.
   Interior 3x3 coverage averaging gives 8/9. With N=3 and L=1.8, the old
   effective footprint step is approximately 2.24691. A constant error on
   that footprint changes sign and grows by about 1.24691 each update.
   This occurs with exact motion, no noise, no clipping, and no rejection.
   It is a numerical instability rather than evidence of misalignment.

## Correction

Keep the existing divisor unless `omega * ownCoverage / divisor > 1.8`;
otherwise raise it to `omega * ownCoverage / 1.8` before caching it.

For any vector v, Jensen's inequality applied to every row gives
`v^T U^T W U v <= sum_i c_i v_i^2` for nonnegative W. Thus the diagonal
bound limits the largest eigenvalue of the effective preconditioned data
operator to at most 1.8, below the divergence threshold 2. This argument
assumes fixed geometry/weights and exact arithmetic; fixed-point accumulation
and float rounding still require a GPU check. The bound applies to the
default unsmoothed gradient. The opt-in `LKRL_UPDSMOOTH` experiment uses a
different operator and is excluded.

The change does not filter image samples, alter motion, or change rejection.
It only limits oversized data steps. This is weaker than the previously
rejected rule replacing smoothed coverage by the maximum of smoothed and own
coverage: the present rule only intervenes when the final effective step
exceeds the operator bound, after the scalar gain and over-relaxation.

## Reproducible CPU evidence

`research/update_stability.cpp` constructs an achromatic textured image with
a diagonal edge, generates noiseless measurements with known motion, and
uses production MHC/bilinear initialization. It independently evaluates all
sensor residuals after every update. This is a CPU mechanism audit, not an
execution of the shader or the full image pipeline.

```sh
cmake -S tools/lkr-lite/src/main/cpp -B tools/lkr-lite/build/host
cmake --build tools/lkr-lite/build/host --target lkrl_update_stability_check
tools/lkr-lite/build/host/lkrl_update_stability_check
tools/lkr-lite/build/host/lkrl_update_stability_check --baseline
```

The corrected audit exits 0; `--baseline` intentionally exits 1. Data loss
must not increase at any of 64 updates. The first eight updates use the
default 2x per-update N=3; the longer run holds that same divisor fixed to
expose divergence rather than changing the schedule's gain.

| Known geometry | Old data RMSE after 8 updates | Bounded data RMSE after 8 updates |
| --- | ---: | ---: |
| Identical frames | 0.00560238 | 0.000166071 |
| Diverse fractional shifts | 0.0000354217 | 0.0000354217 |
| Half-pixel shift grid | 0.0000168338 | 0.0000168338 |
| Near-identical shifts | 0.00447166 | 0.000137423 |

For identical frames, image RMSE also falls from 0.00353337 (old) to
0.00120390 (bounded) after eight updates. Initial image RMSE is 0.00133545.
Remaining error demonstrates that preventing divergence is not sufficient
to solve every reconstruction ambiguity or demosaic artifact.

Full outputs are in `results/lkrl-update-stability-{baseline,bounded}.txt`.
Both varied-motion controls are numerically unchanged in this CPU audit.

## Shader verification and outstanding work

### Runtime access restored; defect verified but broad maze remains

After the session changed to unrestricted access, a direct Metal probe sees
Apple M1 and the actual shader regression passes. An isolated baseline archive
and test object, compiled with the old `zupd` shader without modifying the
worktree, fails: identical-frame loss 0.204799609 -> 2.68095112. The corrected
shader gives 0.204799609 -> 0.00432930841. GPU result logs are in `results/`.

Matched 512x512 car-tile captures at sensor (1792,512), 2x, same eight frames,
same fixed noise model, demonstrate the correction's limited scope: the bound
changes 1,381 of 3,145,728 divisor entries. MHC seed and aligned average are
identical; final hybrid camera-RGB RMS difference is 9.57e-7. This does not
account for the broad ridge/foliage maze. The owner reports the `_2x_3.dng`
output still has the artifact, with two screenshots showing foliage and rocks.

A fresh ridge capture at sensor (1280,1536), 512x512, traces seed/J1/J4/J8/
average/hybrid. The pattern strengthens during the fitting updates before
finishing. An isolated AMaZE reconstruction-seed control, retaining the MHC
rejection reference and replaying identical final affine/local geometry with
no G steps, also retains the pattern. No AMaZE production change was made.

The runtime initialization failure described below is historical and resolved.

The shader compiles and the host `lkrl_denoise_check` / `lkrl_full_run`
targets build. `repeatedFrameStability` in the existing GPU regression
constructs exact identical-frame residuals and executes the actual update
shader for eight steps, testing both fresh and cached divisors.

Before unrestricted session access, execution exited 2 at device initialization:
installed MoltenVK reported `Vulkan is not supported on this device. MoltenVK
requires Metal, which is not available on this device.` At that point the
regression had not run on a GPU. Both the corrected shader and negative
control have since been executed, as recorded above.

A follow-up probe calling Metal directly, without Vulkan, reports zero
devices from `MTLCopyAllDevices()` and no default device from
`MTLCreateSystemDefaultDevice()` in the tool's command environment. This
does not imply that the owner's GPU is broken or unavailable to normal
applications. The eight DNG source files at the supplied burst path are
present. A direct `lkrl_full_run` crop attempt also exits before reconstruction.
ADB starts successfully but currently lists no attached Android devices.

Next required evidence: matched full before/after car-burst renders with
current rejection and motion, retained fine detail in
foliage/road/car/lights, and Android runtime verification. If the real maze
remains, continue the source investigation; this numerical correction alone
must not be presented as fulfillment of the full goal.

## Additional reconstruction controls — rejected as fixes

An isolated CPU least-squares experiment represented full-resolution shared
luminance plus two bilinearly interpolated coarse colour-difference fields.
It used the captured ridge geometry, captured rejection weights, and the same
pixel-area operator on a 128x128 RAW region with surrounding output margin.
Spacing 2 and 4 HR pixels and 8/24/64 LSQR iterations were inspected. Lower
sensor residual was accompanied by additional periodic texture, particularly
at more iterations. This is further evidence that minimizing sensor residual
alone is not a quality criterion. No such representation was added to the
production solver. The earlier shared-luminance control remains rejected too.

A phase-balance diagnostic found a roughly 9e-4 Gr/Gb offset in the bright
sky on this tile, but the dark vegetation has near-zero mean phase offset.
Thus a blanket green-offset correction is not supported as the cause or fix
for the general foliage/rock artifact. No RAW normalization was changed.

## Motion and pixel-aperture isolation

The opt-in stage trace now also records `affine-initial.f32` and
`local-initial.f32` before the G steps. These are the actual tile/sub-tile or
block-search initial estimates, not an invented zero-motion control.

On the same ridge tile, comparing block-centre motion before/after G gives
median displacement changes 0.21–0.30 LR pixels across non-reference frames;
p95 varies from 0.85 to 3.04 pixels. These values alone are not quality scores.
Replaying the pre-G geometry with J-only reconstruction still shows the
pattern and more coloured artifacts in the inspected foliage crop. Rejection
is recomputed for each geometry, so this is a pipeline geometry control,
not a fixed-rejection attribution. Disabling G was not retained as a fix.

An isolated diagnostic shader build substitutes the sensor centre for every
subsample location, consistently changing the residual and adjoint. Geometry
is held at the captured post-G values, with no G steps. The normal J-only
control uses the original pixel-area operator. A second point-sample run
replays the captured `weights.f32` after rejection calculation, holding
rejection fixed as well. Both point-sample versions retain patterned foliage
and coloured artifacts. No point-sample shader was added to production.
This does not establish that the physical sensor aperture is exactly correct;
it rejects this particular replacement as a demonstrated maze fix.

Review artifacts and isolated build scripts for these controls are under
`/tmp/lkrl-source-audit/`: `geometry-control.png`, `aperture-control.png`,
`aperture-fixed.png`, `aperture-control.py`, and `aperture-fixed.py`.
The scripts compile separate archives; normal worktree shaders are preserved.

## Manufactured-noise response with captured ridge geometry

`research/noise_amplification.py` builds the documented forward operator on
a 128x128 RAW subregion of the ridge trace, preserving captured affine/local
geometry and rejection weights. The known image is the captured demosaic
seed. Observations are manufactured as `U * knownImage`, optionally adding
independent Gaussian samples from the recorded sensor noise model. Starting
at the known image isolates noise response from initialization and motion
errors. Eight data updates use the default divisor plus the stability bound.

The noiseless result is exactly unchanged. Adding one noise-model standard
deviation produces camera-RGB error RMS 0.001854 and WB-luma RMS 0.001464;
doubling the added noise doubles those errors. About 68% of luma-error
spectral power falls between radial frequencies 0.2 and 0.4 cycles/HR pixel.
The error-only image shows structured, maze-like noise. This proves such
texture can be introduced by the reconstruction's noise response even with
exact geometry and fixed rejection. It does not prove that every artifact
in the real burst has this cause or that the captured seed is true detail.

Reproduction (numpy/scipy/Pillow required):

```sh
python tools/lkr-lite/research/noise_amplification.py TRACE_PREFIX OUTPUT_DIR
```

The trace must be an eight-frame 512x512 2x tile. Retained outputs are in
`results/lkrl-noise-amplification/`. A float CPU operator is used; fixed-point
GPU parity remains a separate check. No production reconstruction change
was made on the basis of this experiment.

An additional inverse-problem control adds full-resolution colour-gradient
and luma-curvature penalties inside the objective. Colour penalties alone
still permit patterned luminance; increasing luma regularization reduces
the pattern but can soften detail. Six parameter pairs were inspected;
none is accepted as the requested detail-preserving fix. The next useful
test must measure known-detail transfer as well as noise amplification.

## Known-detail transfer gate rejects uniform derivative regularization

`research/detail_transfer.py` measures the linear data response to known
achromatic sinusoidal probes with captured ridge or car geometry and
rejection. Probe/noise reconstruction starts at zero, separating the data
response from MHC initialization. This is not full-pipeline scene quality.
The regularized controls solve a joint Y/U/V objective with luma-curvature
and colour-gradient penalties; they do not filter the finished image.

On the ridge geometry, penalties (0.015, 0.1) lower manufactured luma-noise
RMS from 0.001445 to 0.001243 (about 14%). At radial frequency 0.125,
horizontal/diagonal transfer rises from about 0.886/0.885 to 0.990/0.989.
At 0.25 it rises from 0.743/0.738 to 0.788/0.748. However, extending the
probe to 0.35 reveals diagonal transfer falling from 0.413 to 0.280.
The lower-noise control therefore sacrifices some finest real detail and
is rejected as a general fix. A favourable low-frequency subset would have
missed this failure. Car-geometry probe results are also retained.

```sh
python tools/lkr-lite/research/detail_transfer.py TRACE_PREFIX OUTPUT_DIR
```

Results: `results/lkrl-detail-transfer/{ridge,car}.json`. These are CPU
operator audits on one subregion of each captured tile, not a proof of a
maze-free real output or a replacement for Android validation. No uniform
derivative penalty has been added to production.

## Adaptive derivative control — weak-detail failure

`research/adaptive_transfer.py` builds a guide from the same eight data
updates used in the baseline, then estimates guide curvature energy above
expected propagated sensor noise. Four independent noise probes estimate
that energy with the captured geometry/rejection. The local luma penalty
decreases with estimated structure; no manufactured ground-truth image is
used as the guide. The colour-gradient penalty stays at 0.1.

The probes now use physically small amplitudes 0.005 and 0.02 instead of
unit-amplitude linear probes. This matters because the adaptive operator is
nonlinear. At base luma strength 0.06, noise RMS falls to 0.000829 and the
0.02-amplitude diagonal 0.35 probe has gain 0.6585. But the same frequency
at amplitude 0.005 has gain only 0.1665 (baseline linear gain 0.4128).
Strong texture retention would therefore conceal destruction of weak fine
detail. Lower strengths were tested too; the control is not accepted as a
general detail-preserving fix and is not integrated into production.

The manufactured-probe solves converged, while the real-scene adaptive
solves hit their 160-iteration limit. Scene previews from this control are
therefore exploratory, not certified converged comparisons. Full per-probe
status and results are in `results/lkrl-adaptive-transfer/ridge.json`.
The captured seed/geometry/rejection artifacts under `/tmp/lkrl-source-audit/`
are required to rerun the script. This advances the regression requirements
to include weak fine texture, not only clean high-contrast edges.

## Fixed chromaticity and matched SR-pipeline controls

A fixed-colour-ratio control estimates chromaticity by smoothing the aligned
burst average (Gaussian sigma 1, 2, or 4 HR pixels), then fits one shared
full-resolution brightness field through the captured sensor operator.
Luma-curvature strengths 0.001, 0.003, and 0.01 were tested. All nine CG
solves converged. Patterned texture remains in the inspected ridge and the
stronger penalties soften detail. No production fixed-ratio change was made.
Residuals/convergence are in `results/lkrl-engine-ridge/ratio-control.txt`.

The current SR-pipeline launcher was built and run with the same eight
source frames, reference 0, noise model (8.21e-6, 1.61e-6), scale 2, default
KernelNet and automatic noise LUT. The CFA is [1,2,0,1], normalized by a
vertical flip in SR-pipeline before cropping. Its CLI crop (1280,1014,512,512)
therefore maps to sensor y=1534..2045. LK-R's requested crop is
(1280,1536,512,512); its solver grid starts y=1535 but output blending
accounts for that offset and returns requested sensor coordinates.
The displayed SR crop is shifted four HR pixels after clockwise rotation
to match the LK-R interior region. WB gains, display exposure and gamma are
identical in the two panels. Both raw orientations are 6.

`results/lkrl-engine-ridge/comparison.png` shows less fine patterned grain
but substantially softer foliage and rock detail in SR-pipeline. This is a
cross-engine control with each engine's own alignment and rejection, not a
controlled proof of which single reconstruction stage causes the difference.
Replacing the engine is not accepted as the requested detail-preserving fix.
The launcher/build/run and LK-R run logs are saved beside the comparison.

## Conditional held-out fitting curves and consistent rejection reference

`research/holdout_curve.py` excludes one non-reference frame from fitting,
recomputes its training coverage/divisor, and scores sensor prediction on
that frame for 0–24 updates. Motion and rejection were captured using the
whole burst; this is a conditional fitting audit, **not** independent
end-to-end validation. A 16-LR-pixel border is excluded from the score.

On the ridge subregion the minima for held-out frames 1/3/5/7 occur at
10/9/7/8 updates. The current eight-update default is therefore close to
these minima; indiscriminately reducing it is not supported as a ridge fix.
On the car subregion the minima occur at 2/3/4/2, with much higher normalized
prediction errors. Motion/model mismatch and moving objects can affect
those errors; they cannot be interpreted as pure noise or quality scores.
Curves are in `results/lkrl-holdout/{ridge,car}.json`.

The prior pixel-mean-consistent initialization experiment corrected only
the reconstruction seed, not the separate rejection reference. A new
isolated control corrects **only** the latter, at 2x. It exactly inverts the
separable LR-to-HR-bilinear-to-LR-area-mean operator. Its 1D kernel is
[1/8, 3/4, 1/8], with diagonal 7/8 at the clamped endpoints; a tridiagonal
solve per row/column precedes bilinear upsampling. This preserves coherent
interpolation rather than adding independent constant block offsets.

Against unclipped positive reference measurements, pixel-mean RMS mismatch
falls from 0.001684 to 1.25e-8 (maximum 8.94e-8). Captured affine and local
motion are identical; rejection weights change slightly (mean changes less
than 0.002 per non-reference frame), and the broad ridge pattern remains.
Thus the rejection-reference mismatch alone is not the missing fix.
No such reference correction was integrated into production. Numerical
results are in `results/lkrl-holdout/reference-mean-error.json`; the isolated
build and review image remain under `/tmp/lkrl-source-audit/` as
`build-reference-control.py` and `reference-control.png`.

## Directional derivative audit and cross-region failure

The uniform Laplacian-squared penalty includes a mixed derivative term;
its discrete frequency response differs between axis-aligned and diagonal
structure. An alternative control penalizes `Dxx^T Dxx + Dyy^T Dyy`, leaving
out the mixed term. This is still an inverse-problem regularizer, not a
change to production reconstruction.

`research/detail_transfer.py` now supports `--luma-operator axes`, repeated
`--penalty luma,colour`, `--angles`, and `--max-iterations`. The angular
audit tests 0–90 degrees in 15-degree increments at radial frequencies
0.125, 0.25, and 0.35 (21 probes).

On the ridge, strengths (0.014,0.1) give noise RMS ratio 0.9801 while
retaining at least baseline transfer on all 21 probes (minimum gain
improvement 0.00078). Every solve converges. This is a modest numerical
improvement, not evidence that the visible maze is gone.

On the car geometry, the same settings initially hit the 120-iteration
limit, so all probes were rerun with a 480-iteration limit. The converged
results still increase noise and reduce some finest-detail transfer. The
ridge result therefore does not generalize, and no global axis-penalty
change was made. Results and all convergence statuses are retained in
`results/lkrl-axis-transfer/{ridge,car}.json`.

```sh
python tools/lkr-lite/research/detail_transfer.py TRACE_PREFIX OUTPUT_DIR \
  --luma-operator axes --penalty .014,.1 --angles --max-iterations 480
```

## RAW correlation: a sensor-coordinate component in the burst

`research/raw_noise_covariance.py` audits a marked open-sky ROI in the
ridge RAW trace: x=20..119, y=32..479. A degree-three illumination surface
is fitted separately to each CFA phase and frame. Same-phase spatial
neighbour residual correlations have medians about 0.01–0.03, so there is
no strong within-frame maze-like spatial correlation in this sky sample.
However, residuals at the **same sensor coordinates across frames** correlate
substantially. The distinction matters: spatially uncorrelated fixed
pixel errors can still be correlated between frames.

Subtracting a Gaussian sigma=2 phase-pixel lowpass to remove smooth remaining
scene variation gives median cross-frame correlations 0.0693 (R), 0.2486
(Gr), 0.2533 (Gb), and 0.2983 (B). Shifting one phase pixel (two RAW pixels)
in either axis drops the green/blue correlations to roughly -0.01..-0.02.
The zero-lag peak persists across all frame pairs. This supports a shared
sensor-coordinate residual component, not simply smooth sky/cloud content.
It does not identify whether the component is sensor PRNU, readout, or ISP
processing, nor prove that it explains the whole visible maze.

Measured sky residual RMS / scalar noise-model RMS is approximately
0.969/1.259/1.260/1.246 for R/Gr/Gb/B. The scalar independent-noise model
therefore misses both phase-specific variance and some cross-frame
covariance on this sample. A model that fits a shared sensor-coordinate
offset together with the warped scene is a distinct source-level hypothesis
to test; no RAW subtraction or production model change is yet justified.

```sh
python tools/lkr-lite/research/raw_noise_covariance.py TRACE_PREFIX OUTPUT.json \
  --sky-roi 20,32,100,448
```

All per-frame/per-phase, cross-frame, highpass, and shifted controls are in
`results/lkrl-raw-noise/ridge*.json`. The ROI is explicitly required by the
script; arbitrary scene texture must not be passed off as a noise sample.

## Shared sensor-coordinate nuisance model: preliminary source control

`research/shared_sensor_control.py` changes the data model to
`y_k = U_k z + f`, where f is a sensor-coordinate field shared by the
frames, with a quadratic prior. For fixed z, the optimal common residual
is `sum_k w_k (U_k z - y_k) / (sum_k w_k + prior)`. Subtracting this common
component from each residual before the adjoint marginalizes f. Geometry,
rejection and image-update divisor remain fixed. There is no image filter.

A manufactured noise test uses sigma=0.0015 with 75% independent variance
and 25% common sensor-coordinate variance. This is a simplified diagnostic,
not a per-phase calibrated model for the full burst. Starting at the exact
manufactured image, eight updates give luma error RMS 0.0014445 (original),
0.0012409 (prior=2), 0.0012787 (4), 0.0013256 (8), and 0.0013692 (16).
The real ridge control still contains patterned texture, so this is not
an accepted fix. Known-detail transfer, identifiability of f versus slowly
moving scene structure, gain-dependent noise and cross-region behaviour
must be checked before any production change. Lower noise alone could
reflect slower data fitting rather than a better reconstruction.

Results: `results/lkrl-shared-sensor/ridge.json`. The shared field is fitted
jointly in a CPU operator control; no raw-pixel subtraction or GPU change
was made. The independent-noise maze mechanism demonstrated earlier remains
valid too; shared sensor noise is an additional measured input component.

### Matched-detail audit rejects the simple shared-offset estimator

`research/shared_sensor_transfer.py` evaluates that marginal-offset model
with priors 2, 4, and 8 at 8/12/16/24 updates. The per-update divisor/gain
is held fixed; the longer runs measure the estimator's evolving response,
not the runtime's rescaled longer-schedule setting. The same manufactured
75%-independent/25%-shared noise is used for every model. Six achromatic
horizontal/diagonal probes at radial frequencies 0.125/0.25/0.35 measure
detail response starting from zero. The baseline is eight original updates.

At prior=2 and eight updates, noise RMS is 0.8591 times baseline but the
worst probe loses 0.0579 absolute gain. At 16 updates, all six probes have
at least baseline gain, but noise rises to 1.1350 times baseline. Prior=4
at 12 updates also recovers all probe gains, with noise ratio 1.0453.
None of the tested settings both reduces noise and matches baseline detail
on all six probes. This rejects a lower-noise same-update preview as proof
of correction. Results: `results/lkrl-shared-sensor/ridge-transfer.json`.

The observed sensor-coordinate correlation remains relevant, but a simple
joint additive offset model alone is not accepted as the full maze fix.
It can absorb real scene components that are similar across sensor positions;
separating those from sensor error requires additional evidence or a better
model. No such nuisance field was added to the production solver.
