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

RawLens's HDR bracket alignment vendors PhotonCamera's FlowNet-v2 NCNN implementation from
commit `9efb24a44119b04223b4a2eef50c7837ad643970`. Relevant sources are
`processing/ml/FlowNetNcnnProcessor.java`, `cpp/ncnnMl.cpp`, `cpp/flownet/`, the ABI-specific NCNN
static libraries, and `assets/models/flownet_flat.ncnn.{param,bin}`. The vendored implementation
is recorded in `app/src/main/cpp/flownet/UPSTREAM.md`. RawLens supplies its own
normalized-CFA input renderer and CFA-parity-preserving warp.

RawLens's SR anisotropic weights vendor PhotonCamera's KernelNet parameter model
from the same commit `9efb24a44119b04223b4a2eef50c7837ad643970`: the JNI tiling
runtime in `app/src/main/cpp/ncnnMl.cpp` (`KernelNetCtx`), the Java wrapper
`app/src/main/java/com/particlesdevs/photoncamera/processing/ml/KernelNetNcnnProcessor.java`,
and `assets/models/kernelnet_aniso_v2_2_params.ncnn.{param,bin}`. Deliberate
divergences from upstream: the native side emits channel-major float32
`[s1][s2][rho]` planes instead of upstream's RGBA-interleaved fp16 halves, and
the Kotlin bridge (`RawSrKernelNetAniso`) converts the raw model output straight
to the SR precision field as `P = 2*M(s)` per quad (the `mergeCombineWeight`
convention, `s1`=y / `s2`=x, no transpose, no extra rescale, no width cap, no
area floor, no narrow-axis clamp — the direct upstream law). Unusable triples
(non-positive or non-finite axes) fall back to the analytic kernel.

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

RawLens's exposure-bracket radiance merge is additionally adapted from darktable commit
`52435b9a0c6bcf470f683e0c4455ecd321b9aec5`, primarily
`src/control/jobs/control_jobs.c::_control_merge_hdr_process()` and `_envelope()`. It retains the
exposure/aperture/ISO calibration, photon-count weighting, highlight envelope, clipped-pixel
fallback, and final white-level normalization, with FlowNet registration replacing darktable's
desktop OpenCV alignment. `FloatCfaDngWriter` adapts `src/imageio/imageio_dng.c`'s
`dt_imageio_dng_write_float()` layout: uncompressed little-endian TIFF/DNG, one 32-bit IEEE-float
CFA sample per pixel, `SampleFormat=3`, normalized black level zero, and white level one.

## Sea real-RAW test fixture

`app/src/androidTest/assets/rawsr/sea/` contains lossless Bayer-region extracts and
metadata from the user-contributed Sea photographs. Data license: CC BY 4.0,
separate from the application source license. Attribution: Sea burst contributor
(RawLens user). See that directory's `LICENSE.md` and `manifest.json` for permission,
original-file digests and the exact extraction changes. These are instrumentation
assets only, not production APK assets.

## Handheld burst super-resolution references

RawLens's RAW-SR kernel covariance (`RawSrKernelCovariance`, `RawSrCovarianceGuide`,
`assets/shaders/rawsr/kernel_covariance.glsl`) is a clean-room reimplementation
of the published Wronski et al. SIGGRAPH 2019 method and its IPOL 2023
transcription. The implementation was checked against Jamy Lafenetre's
MIT-licensed `Handheld-Multi-Frame-Super-Resolution` reference
(`handheld_super_resolution/kernels.py`, `linalg.py`, `utils_image.py`); no
upstream code is copied into RawLens. Jamy Lafenetre and contributors provide
that reference under the MIT License without endorsement of RawLens.

## Full-resolution Quad-Bayer development

The green-gradient and green-guided color reconstruction in
`app/src/main/assets/shaders/quad/` is adapted from PhotonCamera at commit
`4ee108e169496f429c0afa0cc33e57bb6b2ec724`, specifically
`demosaicp0quad.glsl`, `demosaicp12quad.glsl`, and `demosaicp2quad.glsl`.
PhotonCamera contributors' work is licensed under GPL-3.0; the adapted shaders
remain GPL-3.0-or-later. RawLens adds compute dispatch, all Bayer orders, sensor
crop phase, defined CFA-preserving border sampling, and its ACEScg output path.

## Burst-reconstruction rewrite references (research-only, not shipped)

The following checkouts live in git-ignored `references/upstream/` and were
study references for the retired desktop rewrite.
No upstream code is copied into RawLens; algorithms are reimplemented
clean-room in stdlib-only Kotlin/JVM. Pinned HEADs as cloned 2026-09-23
(`--depth 1`); cite hashes, not branches.

- timothybrooks/hdr-plus — https://github.com/timothybrooks/hdr-plus —
  HEAD `ef4dd2ca53a51e105ed923557c726b253f05c13b` (2026-01-12) — MIT
  (Copyright (c) 2017 Tim Brooks). Reference for FFT-based tile alignment
  and pairwise Wiener temporal merge with calibrated noise thresholds.
- martin-marek/hdr-plus-pytorch — https://github.com/martin-marek/hdr-plus-pytorch —
  HEAD `e7091c33b0e3417f84e72d70ad2081b66daee56d` (2024-09-08) — MIT
  (Copyright (c) 2021 Martin Marek). Reference for vectorized HDR+
  align-and-merge structure.
- amonod/hdrplus-python — https://github.com/amonod/hdrplus-python —
  HEAD `98ebf1724196bc070c1248e0f8efa2df6c18b8ef` (2022-06-27) —
  GNU AGPL-3.0. Ideas only; do not copy code into RawLens (AGPL copyleft).
  Reference for HDR+ stage decomposition and test bursts.
- GuoShi28/GCP-Net — https://github.com/GuoShi28/GCP-Net —
  HEAD `cef7513fa242343055af64e612429e4384d3c1d7` (2021-08-09) — Apache-2.0.
  Reference for green-channel-prior guided joint denoising/demosaicking
  (GCP-Net, TIP 2021); classical reimplementation only, no model weights.
- GuoShi28/2StageAlign — https://github.com/GuoShi28/2StageAlign —
  HEAD `f39218a0be26f1de9e75021acef6c4ab3bdf8b06` (2022-12-08) — MIT
  (Copyright (c) 2022 Shi Guo). Reference for coarse patch-level plus
  refined pixel-level alignment scheme (CVPR 2022); classical port, no ML.
- goutamgmb/deep-rep — https://github.com/goutamgmb/deep-rep —
  HEAD `154c51ed4075880eb814df84d863614aa3922ae1` (2021-10-22) —
  CC BY-NC-SA 4.0 (Huawei). Ideas and BurstSR eval/synthetic-data protocol
  only; do not copy code (NonCommercial, incompatible with redistribution).
- Pre-existing: Jamy Lafenetre Handheld-Multi-Frame-Super-Resolution
  (`references/Handheld-Multi-Frame-Super-Resolution-Jamy-L`, HEAD `07bc3f2`,
  MIT) — core Wronski SIGGRAPH 2019 steerable-kernel merge + robustness
  model reference (see existing section above); ImageStackAlignator
  (`b12e86e`) — global NCC pre-align reference.

Upstream authors provide their work without endorsement of RawLens.

## MediaCinemaRAW-Encoder (vendored, RAW video P0 spike onward)

- Project: MediaCinemaRAW-Encoder
- Repository: https://github.com/matthew777777/MediaCinemaRAW-Encoder
- Pinned commit: `55cceb2ef74c23f761be72af5bce04c16267e0bc` (2026-10-04:
  multithreaded encode, SSE2, edge-clamp, container I/O buffering)
- License: GNU General Public License version 3 only
- Vendored files (SPDX headers intact):
  `app/src/main/cpp/cinemaraw/include/MediaCinemaRAW/Encoder.h` and
  `app/src/main/cpp/cinemaraw/src/Encoder.cpp` are verbatim upstream,
  including `encode_parallel()` and the x86 SSE2 paths.
  `app/src/main/cpp/cinemaraw/include/MediaCinemaRAW/ContainerWriter.h` and
  `app/src/main/cpp/cinemaraw/src/ContainerWriter.cpp` are a RawLens fork of
  the upstream writer: raw-fd I/O with a staging buffer (large-block writes
  for Android FUSE), an `int fd` constructor, a zero-copy `writeFrame`
  pointer overload, `fsync` on close, and the audio-index origin stored as
  the full-nanosecond first-chunk timestamp (verified against a genuine
  recording; upstream stores milliseconds). Upstream's motion coalescing,
  audio-before-motion tail order, and chunked payload writes are merged in.
- Purpose: lossless type-7 RAW-frame encoding (RAW16 / packed RAW10 input)
  plus version-3 `.mcraw` container writing (PCM16 audio, gyro/accel motion)
  for the planned RAW Video mode. Interop oracle during development:
  https://github.com/mirsadm/motioncam-decoder (external, not vendored).
- RawLens JNI bridges (`app/src/main/cpp/cinemaraw_spike_jni.cpp`,
  `app/src/main/cpp/cinemaraw_writer_jni.cpp`, `CinemaRawSpike.kt`,
  `CinemaRawWriter.kt`) are RawLens's own code under the repository license.
  The recorder encodes serially on N frame workers; `encode_parallel()` is
  exposed only through the spike bench (per-frame threading would
  oversubscribe the frame workers).

## RAWR merge_hdrplus (vendored shaders + header, HDR+ port)

- Project: Rawr (Android RAW camera)
- Repository: https://github.com/adityawarmanfw/rawr
- Pinned commit: `f41e6c2c493cb2ebfe37ca7dcb98a6e38a5d40a5` (2026-10-06)
- License: GNU General Public License version 3 only (compatible with
  RawLens GPL-3.0-or-later)
- Upstream-of-upstream: Burst Photo (https://github.com/martin-marek/hdr-plus-swift,
  GPL-3.0) at `69cb0572bb6712e160c448260125cb6099bdfd87` (2024-08-24), which
  RAWR ported to GLSL/Vulkan (see RAWR's `merge_hdrplus/UPSTREAM.md`).
- Vendored files (unmodified): the 28 merge kernels `hdrp_*.comp` (spatial
  "Fast" merge) and `hdrq_*.comp` (frequency "Higher quality" merge) under
  `app/src/main/assets/spirv/hdrplus/` plus the geometry/robustness header
  `RawMergeHdrPlusGpu.h` at `app/src/main/cpp/hdrplus/`, with SPIR-V rebuilt
  via `glslangValidator -V --target-env=vulkan1.2` + `spirv-opt -O` +
  `spirv-val` (see the assets README.md for the exact command and
  UPSTREAM.md for the full provenance chain).
- RawLens host (`app/src/main/cpp/hdrplus/`, `HdrPlusVulkan.kt`,
  `HdrPlusMerge.kt`) is RawLens's own code under the repository license;
  its dispatch sequence, bindings, push constants, barriers, and pass
  order are a 1:1 translation of RAWR's `HdrPlusRecorder.cpp` and the
  `runHdrPlus`/`runHdrPlusFrequency` drive loops, so shader-visible
  behavior (and output bits) match RAWR. Host-only differences: run-scoped
  allocation instead of RAWR's aliasing arena, one submission instead of
  interleaved chunks, and whole-run GPU timestamps.

## GALOSH (vendored shaders, raw-denoise port)

- Project: GALOSH — blind, training-free denoising of raw Bayer images
- Repository: https://github.com/luxgrain/GALOSH
- Pinned commit: `11de0593cc8091933ad76d1d6d873f7dee537f15` (HEAD at vendoring, 2026-09-26)
- License: Apache License, Version 2.0
- Vendored files (unmodified): the 43 raw-path kernels
  `standalone/vk/shaders/o32_*.comp` plus `galosh_f16_rne.glsl`, with
  SPIR-V rebuilt via NDK r27 `glslc -O --target-env=vulkan1.2`, all under
  `app/src/main/assets/spirv/galosh/` (see its README.md for the rebuild
  command). The `yuv_*` engine is not vendored (raw-only port).
- RawLens host (`app/src/main/cpp/galosh/`, `GaloshVulkan.kt`) is RawLens's
  own code under the repository license; it loads the vendored SPIR-V and
  follows the upstream device contract (Vulkan 1.2, float16 arithmetic,
  16-bit storage) documented in `docs/galosh-phase0-spec.md`.

## Halide (AOT filters for the BGU viewfinder)

- Project: Halide
- Repository: https://github.com/halide/Halide
- Host toolchain pinned: 21.0.0 (Homebrew 21.0.0_2) on arm64 macOS, C++17,
  per `tools/halide/build_aot.sh`
- License: MIT License
- The AOT archives under `app/src/main/cpp/halide/filters/` (one per Android
  ABI for `bgu_spike_downsample`, `bgu_look`, `bgu_fit`) embed the Halide
  runtime and ship in the APK. The generator sources under
  `tools/halide/generators/` are RawLens's own code under the repository
  license (two adapt google/bgu generator structure, see below); the script
  plus sources are the audit/regen path for the checked-in archives.

## google/bgu (bilateral-grid research reference + adapted stages)

- Project: google/bgu — Bilateral Guided Upsampling (J. Chen, A. Adams,
  N. Wadhwa, S. Hasinoff, SIGGRAPH Asia 2016)
- Repository: https://github.com/google/bgu
- Pinned commit: `f2d6f2d` (depth-1 clone at research time, 2026-10-04)
- License: Apache License, Version 2.0
- Adapted (not vendored) into RawLens's own Halide generators and shaders:
  `bgu_spike_downsample` adapts `src/halide/box_downsample_generator.cpp`,
  `bgu_fit` adapts `src/halide/fit_and_slice_affine_grid_halide.cpp`
  (f32 I/O, fit-only, BGU luma weights), and the GPU slice shader ports
  `apply_local_curves.fs.glsl` to hand-written OpenGL ES 3.00. The paper's
  fit/slice split (§4–§5) shapes the whole viewfinder engine; the engine,
  JNI bridges, and schedules are RawLens's own work.
