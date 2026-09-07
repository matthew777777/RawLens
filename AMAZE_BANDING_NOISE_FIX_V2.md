# AMaZE banding / false-colour / no-denoise noise fix v2

This revision fixes two separate causes found after re-checking the supplied DNG and the current
RawTherapee `amaze_demosaic_RT.cc`.

## 1. Revert the incorrect rbpm shared range extension

The previous patch extended the half-grid staging through `b + 10`. That was incorrect. An 8-wide
workgroup only executes R/B-site math from even output columns `b..b+6`; `site_x()` can move the
anchor to `b+7`, and the farthest half-grid access at `site+2` lands in the even storage slot `b+8`.

Worse, evaluating `delpm_val()` at `b+10` reads CFA at `b+12` while the `HALO=4` shared CFA tile only
contains columns through `b+11`. `SIDX()` therefore aliases the following shared-memory row. This is
workgroup-periodic and can produce the exact kind of repeating magenta/yellow false-colour striping
seen in the screenshots.

The valid staging range is restored to `b-2 .. b+8`.

## 2. Feed AMaZE a white-balanced CFA, as RawTherapee does

RawTherapee explicitly documents the CFA values entering AMaZE as being after white-balance
multipliers are applied. RawLens was feeding black/white-normalized but otherwise unbalanced sensor
channels to the AMaZE equations, and applying white balance only later as part of the camera-to-ACEScg
matrix.

That changes AMaZE itself: its adaptive colour ratios, directional variances, Nyquist decisions and
saturation bounds all see the camera's normal R/G/B sensitivity imbalance as image chroma. The result
can be false-colour zippering and coloured, structured-looking noise even when the denoise module is
disabled.

RawLens now applies a clipping-safe equivalent balance in `pad.glsl` before AMaZE. The gains are
normalized so their maximum is 1. For the supplied DNG's AsShotNeutral, approximately
`(0.49854, 1.0, 0.67016)`, the AMaZE balance is approximately `(1.0, 0.49854, 0.74391)`, which makes a
neutral CFA achromatic without amplifying any channel beyond RAW white.

The final camera-to-ACEScg matrix is compensated by the inverse of that balance, so overall
scene-linear colorimetry is unchanged. The highlight-neutral target is transformed into the same
balanced camera basis and becomes neutral `(1,1,1)` as expected.

## Noise note

The supplied report says the bad noise appearance occurs with denoise disabled. Therefore it cannot
be caused by the wavelet denoise shaders. The earlier RGBA32F wavelet fixes are retained for when
that module is enabled, but this revision addresses the no-denoise noise at the AMaZE input stage.
AgX Base/Punchy may make chroma artifacts more visible, but it is downstream of demosaic and is not
the source of Bayer/workgroup-periodic false colour.
