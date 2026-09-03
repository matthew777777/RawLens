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

The DNG target compiles PhotonCamera's checked-in `app/src/main/cpp/dngCreator.cpp` directly and
generates its Java `processing/DngCreator.java` verbatim from the local reference at commit
`54d9febc596b34376b8be242a388f386d97e8f5d`.
That implementation uses the unmodified ParticlesDevs TinyDNG fork requested by PhotonCamera.
TinyDNG is Copyright (c) 2016-present Syoyo Fujita and contributors and is distributed under
the MIT License. PhotonCamera remains licensed under GPL-3.0-or-later. No stb code is used.

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
