# Third-party notices

## PhotonCamera

RawLens's PROGRAM shutter-first exposure policy and ZSL lifecycle acknowledge work from PhotonCamera:

- Project: PhotonCamera
- Repository: https://github.com/eszdman/PhotonCamera
- Referenced change: https://github.com/eszdman/PhotonCamera/commit/5bb9cf47fa9313abb00f1eb594b647e0553fd866
- Change author: matthew777777
- Change date: 2026-07-31
- License: GNU General Public License version 3 or later

RAW ZSL lifecycle research additionally referenced PhotonCamera's `dev` branch at commit
`54d9febc596b34376b8be242a388f386d97e8f5d`, primarily:

- `capture/CaptureController.java` for continuous RAW ImageReader ownership, bounded buffering,
  capture-time freezing, and draining queued RAW images across request transitions
- `control/Gyro.java` for the general idea of associating continuous gyroscope history with
  buffered frames

RawLens's GLES 3.1 AMaZE demosaic is derived from the same pinned PhotonCamera commit,
specifically `postpipeline/Amaze.java`, `assets/shaders/amaze/*.glsl`, and
`assets/shaders/utils/import_amaze.glsl`. PhotonCamera introduced that dependency closure in
commits `eba655ac94a0c7b5bd25398aa3256f24dd14813f` and
`a1a86e550758d6058217851a2272e8e676298ca8`. RawLens retains the 13-pass shader math and tiled
dependency skirt, but uploads an unbounded `R32F` CFA, passes the actual Bayer phase, and removes
PhotonCamera's final negative-RGB clamp so clipping remains deferred to the output transform.

The local reference checkout currently points to that commit on `dev`; cite the commit hash rather
than the moving branch when describing research. RawLens independently pairs each RAW image with its own `TotalCaptureResult`, follows Android's
documented start-of-first-row sensor timestamp semantics, includes rolling-shutter readout in its
pre-shutter cutoff, and uses its own bounded single-frame selection policy.

RawLens adaptations are maintained separately and may differ substantially from the original implementation. Existing upstream copyright and license notices must remain attached to any copied or modified source. RawLens modifications are identified by repository history and release tags.

PhotonCamera and its contributors provide their work without endorsement of RawLens.

RawLens's HDR bracket alignment also compiles PhotonCamera's FlowNet-v2 NCNN implementation from
the pinned `54d9febc596b34376b8be242a388f386d97e8f5d` checkout. Relevant sources are
`processing/ml/FlowNetNcnnProcessor.java`, `cpp/ncnnMl.cpp`, `cpp/flownet/`, the ABI-specific NCNN
static libraries, and `assets/models/flownet_flat.ncnn.{param,bin}`. RawLens supplies its own
normalized-CFA input renderer and CFA-parity-preserving warp.

## Google Filament AgX

RawLens's SDR display transform is a Kotlin/GLSL adaptation of the AgX Base implementation in
Google Filament:

- Project: Google Filament
- Repository: https://github.com/google/filament
- Referenced commit: `2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad`
- Primary source: `filament/src/ToneMapper.cpp`
- Source SHA-256: `1e3212b67f2954721a4336c68fef1904204873835896e25c9ba77f9030aa42cd`
- License: Apache License 2.0

RawLens uses the pinned AgX inset, log2 exposure range, polynomial contrast curve, Base view
outset, and display-linear 2.2 conversion. Because Filament's implementation operates in linear
Rec.2020, RawLens explicitly converts scene-linear ACEScg/AP1 D60 to Rec.2020 D65 before AgX and
converts the result to output-linear sRGB or Display P3 afterward. The JPEG output tab keeps the
official Base result at its defaults and adds optional contrast, saturation,
purity, hue preservation, adjustable tone-range limits, and bounded gamut compression. RawLens then
performs the sRGB OETF and encoded-space dithering.

RawLens also provides an optional adaptive development-exposure stage before AgX. It uses a trimmed
log-luminance analysis of the corrected RAW CFA, protects the upper percentile, clamps correction to
plus or minus 1.5 EV, and shares one correction across each logical burst/ZSL selection. This is a
RawLens development feature and does not alter DNG pixels or frozen Camera2 capture metadata.

## PhotonCamera DNG creator and TinyDNG

Single-frame DNG output uses Android's official `DngCreator` by default, with
AUTO falling back to the vendored TinyDNG v3 writer on platform failure.
The vendored core at `app/src/main/cpp/deps/tinydng` is pinned to
https://github.com/matthew777777/tinydng at commit
`1f181699511e08e51baabd9fdab2311ea1253d4b` (file hashes in `UPSTREAM.json`,
local changes in `rawlens.patch`). Upstream v3 parses NoiseProfile/GainMap but
does not emit them; RawLens supplies NoiseProfile, GainMap/OpcodeList2,
ActiveArea, and full Camera2 calibration as validated extra TIFF fields.
TinyDNG is Copyright (c) 2016-present Syoyo Fujita and contributors and is
distributed under the MIT License (see `app/src/main/cpp/deps/tinydng/LICENSE`
and `miniz.LICENSE`). The old PhotonCamera DNG Java/native bridge is no longer
compiled; the `dngCreator` native library name now holds only the JPEG encoder.
PhotonCamera remains licensed under GPL-3.0-or-later. No stb code is used.
See `docs/tinydng-integration.md` for backend selection and verification limits.

## RawTherapee AMaZE reference

RawLens's 13-pass tiled AMaZE GLES 3.1 executor was checked against a pinned
RawTherapee reference (`references/RawTherapee/amaze_demosaic_RT.cc`):

- Project: RawTherapee
- Repository: https://github.com/RawTherapee/RawTherapee
- Referenced commit: `498f6237`
- License: GNU General Public License version 3 or later

The audit, host oracle, and Mali-G615 parity runs are recorded in
`docs/amaze-rawtherapee-audit.md`. RawTherapee-identical output is not claimed;
see that audit for validation limits. RawTherapee and its contributors do not
endorse RawLens.

## darktable

RawLens's CPU reference implementation of pre-demosaic inpaint-opposed highlight
reconstruction is a Kotlin port and adaptation of darktable:

- Project: darktable
- Repository: https://github.com/darktable-org/darktable
- Referenced commit: `0156c6e156f40c54a98f67c0be9c96db61487386`
- License: GNU General Public License version 3 or later
- Primary source: `src/iop/hlreconstruct/opposed.c`
- Shared reference helper: `src/iop/hlreconstruct/segbased.c::_calc_refavg`
- Clip constant: `src/iop/highlights.c::highlights_clip_magics[DT_IOP_HIGHLIGHTS_OPPOSED]`

The port retains the upstream algorithm's opposing-channel cube-root reference,
block mask, dilation footprint, near-clip chrominance sampling, and clip multiplier.
RawLens adapts the scalar clipping threshold to a spatial saturation map because
lens-shading correction occurs before highlight reconstruction. The adapted source
remains licensed under GPL-3.0-or-later; darktable and its contributors do not endorse
RawLens.

RawLens's exposure-bracket radiance merge is additionally adapted from darktable commit
`52435b9a0c6bcf470f683e0c4455ecd321b9aec5`, primarily
`src/control/jobs/control_jobs.c::_control_merge_hdr_process()` and `_envelope()`. It retains the
exposure/aperture/ISO calibration, photon-count weighting, highlight envelope, clipped-pixel
fallback, and final white-level normalization, with FlowNet registration replacing darktable's
desktop OpenCV alignment. `FloatCfaDngWriter` adapts `src/imageio/imageio_dng.c`'s
`dt_imageio_dng_write_float()` layout: uncompressed little-endian TIFF/DNG, one 32-bit IEEE-float
CFA sample per pixel, `SampleFormat=3`, normalized black level zero, and white level one.

## Sea and Forest real-RAW test fixtures

`app/src/androidTest/assets/rawsr/sea/` contains lossless Bayer-region extracts and
metadata from the user-contributed Sea photographs. Data license: CC BY 4.0,
separate from the application source license. Attribution: Sea burst contributor
(RawLens user). See that directory's `LICENSE.md` and `manifest.json` for permission
(2026-09-10), original-file digests and the exact extraction changes. These are instrumentation
assets only, not production APK assets.

`app/src/androidTest/assets/rawsr/forest/` contains lossless Bayer-region extracts
and metadata from the user-contributed Forest GCam ZSL burst (32 payloads, oldest
30 extracted). Data license: CC BY 4.0, separate from the application source
license. Attribution: RawLens user (forest GCam ZSL burst contributor). See that
directory's `LICENSE.md` and `manifest.json` for permission (2026-09-11),
original-file digests and the exact extraction changes. These are instrumentation
assets only, not production APK assets.

## Handheld burst super-resolution references

RawLens's RAW-SR kernel covariance (`RawSrKernelCovariance`, `RawSrCovarianceGuide`,
`assets/shaders/rawsr/kernel_covariance.glsl`, `assets/shaders/rawsr/guide_gray.glsl`)
is a clean-room reimplementation of the published Wronski et al. SIGGRAPH 2019
method and its IPOL 2023 transcription. The implementation was checked against
Jamy Lafenetre's MIT-licensed `Handheld-Multi-Frame-Super-Resolution` reference
(`handheld_super_resolution/kernels.py`, `linalg.py`, `utils_image.py`); no
upstream code is copied into RawLens. Jamy Lafenetre and contributors provide
that reference under the MIT License without endorsement of RawLens.
