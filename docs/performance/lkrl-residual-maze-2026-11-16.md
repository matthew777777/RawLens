# Car 2x residual maze investigation — unresolved

The owner still sees a repeated maze / zigzag in foliage, rocks, the car,
lamps and road after the colour-guide correction. Do not describe that
correction or the investigation below as a complete zipper fix.

Subsequent source audit: [update instability](lkrl-update-stability-2026-11-16.md)
reproduces divergent reconstruction on noiseless low-motion inputs and adds
an operator-bound correction. Its CPU mechanism tests pass, but the actual
shader and affected real burst still require runtime validation. The overall
maze issue remains unresolved.

Input burst: `IMG_20260919_184641_979`, eight 4080x3060 frames. Full-frame
host outputs use `--s 2 --prior 0`. The supplied original RGB DNG matches
`/tmp/lkrl-wall-geometry/car-fixed-2x.dng.f32` to RGB16 quantisation. The
owner's later `IMG_20260919_184641_979_2x_2.dng` matches the chromaticity-guide
candidate to RGB16 quantisation (sampled camera-RGB MAE 3.81e-6).

## Confirmed, limited correction

The hybrid's colour range guide used RGB distance, so brightness differences
could disable colour interpolation. Subtracting luma still gates brightness
edges on coloured surfaces. Compare chromaticity instead using
`ag*(Ya/level) - av*(Yg/level)`, with `level=max(Ya,Yg,1e-4)` and the existing
noise allowance. This leaves the luminance reconstruction, motion geometry,
reference image and rejection calculations unchanged. It retains the original
noiseless green/grey colour-boundary test.

The directional Bayer-pattern regression test uses alternating **columns** of
fit chroma across a brightness edge on a fixed-colour surface. Alternating
both axes was an insufficient test: the other axis cancelled the pattern even
with the old guide. The corrected test fails the original committed shader:
maximum colour residual 0.00333336 / 0.00272728 for radii 1 / 2. The corrected
guide gives 7.45e-9 / 2.98e-8. Existing adapter checks pass at both scales.

All five 6116x8160 full-frame diagnostic planes were compared between the
pre-existing luma-subtracted-guide run and the chromaticity run: zero differing
float samples. This demonstrates unchanged diagnostics for that isolated
colour-guide change, not a proof of correct rejection or sufficient SR samples.

## Controls that did not satisfy the requested result

- Unconditional chroma interpolation leaves a luminance component of the maze.
- Replacing fit luma with the aligned average visibly loses fine detail. Rejected.
- A flat-passband filter of fit-minus-average detail was investigated but not
  adopted as a root correction.
- Explicit global data updates and fitting from the aligned average did not
  remove the visible problem. The old GPU path ignored `LKRL_FROMAVG`; an
  isolated control honoured it. No default initialization change was retained.
- Removing the demosaic seed's cross-colour curvature compensation did not
  provide a complete fix.
- The archived LK-R solver documents a sensor-pixel-mean mismatch in bilinear
  initialization and has opt-in initialization back-projection. Lite ignores
  the corresponding API option. Correcting the reconstruction seed alone while
  retaining the rejection reference did not remove the general surface maze;
  it is not established as the complete cause here.
- Shared-luminance reconstruction from fixed colour fields passed an exact
  synthetic textured-scene check (RMS 5.82e-5, texture gain 0.999946), but raised
  high-frequency power / introduced patterns on the real burst. Rejected.
- Applying the colour constraint between data updates did not eliminate the
  remaining pattern. Rejected.

Experimental solver/shader changes were removed from the working code. The
retained changes are the limited chromaticity-guide correction and its GPU
regression test. There is no new accepted maze-free output, no release/version
bump, and no Android runtime validation for a complete fix.

## Git baselines

An isolated extraction of the old LK-R engine at `baea2432^` and Lite at
`1dd3daae` was built and run on the same full burst. Current DNG I/O was linked
for these host comparisons; they are engine baselines, not complete historical
APK reproductions. Both differ in sharpness, colour and artifacts from the
current output, so rollback is not a demonstrated solution preserving the
owner's requested detail and current rejection behaviour.

Useful retained files under `/tmp/lkrl-zipper/` include `old-lkr-2x.dng`,
`old-lite-2x.dng`, `*-git.png`, logs, the isolated engine sources/builds, and
`spectrum.json`. The spectral summaries are descriptive measurements, not
quality scores: real fine texture contributes to the measured bands too.

## Disk cleanup

At the owner's request, generated temporary buffers / rendered outputs,
Android build output, disposable Gradle caches and selected user application
caches were removed. The report recorded 45.36 GiB removed without errors.
Source projects, input photos, signing material, Android SDKs, downloaded
Gradle dependencies and the comparison baselines were preserved. Some
rejected large experiment files were discarded; small review crops, scripts
and logs remain. `/tmp/rawlens-cleanup-report.json` lists deletions.
