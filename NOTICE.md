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

## TinyDNG and historical PhotonCamera DNG integration

The active TinyDNG DNG backend compiles the checked-in C11 v3 sources under
`app/src/main/cpp/deps/tinydng`, synchronized with
https://github.com/matthew777777/tinydng at `4df281b`.
TinyDNG is Copyright (c) 2016-present Syoyo Fujita and contributors and is
licensed under MIT; its license is retained in the vendor directory.
`UPSTREAM.json` records the original `1f18169` source hashes, and `rawlens.patch`
reproduces the current writer/header from that base. The Android DNG build
compiles out ZIP, baseline JPEG, PSD and multi-threaded decode.

RawLens supplies Camera2 metadata while TinyDNG handles checked TIFF fields,
EXIF/GPS directories, opcode payload transport and optional lossless JPEG XL.
The DNG encoder links libjxl through TinyDNG, rather than a separate JNI codec.
PhotonCamera's earlier DNG integration is no longer the active writer; its
historical attribution and retained source licenses remain applicable.

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

The active Vulkan Bayer opposed port and its Kotlin fallback reference darktable
commit `891e46cd57b64d67dbde183d5d3c0e430edd11d6`, particularly
`data/kernels/basic.cl::{_calc_refavg,highlights_initmask,highlights_dilatemask,highlights_chroma,highlights_opposed}`
and `src/iop/hlreconstruct/opposed.c::process_opposed_cl`. Pinned source excerpts
are in `docs/refactor/upstream/darktable-opposed.{c,cl}`. The Vulkan translation
uses storage images in place of planar masks, preserves row reduction order,
and implements binary32 cube-root rounding with integer midpoint comparisons.
Lens-shading correction is undone for reconstruction math and restored on changed
photosites. This port and the source-derived oracle remain GPL-3.0-or-later.

RawLens's exposure-bracket radiance merge is additionally adapted from darktable commit
`52435b9a0c6bcf470f683e0c4455ecd321b9aec5`, primarily
`src/control/jobs/control_jobs.c::_control_merge_hdr_process()` and `_envelope()`. It retains the
exposure/aperture/ISO calibration, photon-count weighting, highlight envelope, clipped-pixel
fallback, and final white-level normalization, with FlowNet registration replacing darktable's
desktop OpenCV alignment. `FloatCfaDngWriter` adapts `src/imageio/imageio_dng.c`'s
`dt_imageio_dng_write_float()` layout: uncompressed little-endian TIFF/DNG, one 32-bit IEEE-float
CFA sample per pixel, `SampleFormat=3`, normalized black level zero, and white level one.

## JPEG XL encoder (libjxl, optional) and darktable JXL mapping

JPEG XL output is encoded by the JPEG XL reference implementation:

- Project: libjxl
- Repository: https://github.com/libjxl/libjxl
- License: BSD 3-Clause (GPL-3.0 compatible)
- Integration: `app/src/main/cpp/native_jxl_encoder.cpp` via the `jxlEncoder`
  JNI bridge for developed stills; `deps/tinydng/tinydng_write.c` through
  `rawLensDng` for unsigned 16-bit single-channel DNG pixels.
- Build: `app/src/main/cpp/CMakeLists.txt` (`LIBJXL_SOURCE_DIR`), with
  `TINYDNG_HAVE_JXL` defined on the TinyDNG target; host discovery uses pkg-config.

libjxl auto-fetches at configure time (pinned `LIBJXL_VERSION`, currently
v0.12.0 (`a7a9c78`) with encoder-only
flags plus `JPEGXL_ENABLE_SJPEG/JPEGLI=OFF` for NDK r27) unless
`LIBJXL_SOURCE_DIR` points at an existing checkout or `-DRAWLENS_ENABLE_JXL=OFF`
(source-only/F-Droid builds) is set; `./gradlew` accepts `-PjxlSourceDir=` and
`-PdisableJxl`. Without libjxl the bridge still builds but every encode call
reports "not bundled" instead of writing. The encoder option mapping
(bit depth 8/10/12/16/32, 16-bit uint vs half float, quality with lossless
at 100 except 16-bit float, effort 1..9, decoding-speed tier 0..4, native
color encoding with ICC fallback, BMFF container with Exif/`xml ` boxes)
follows darktable's `src/imageio/format/jxl.c` (GPL-3.0-or-later, darktable
developers), which remains a mapping reference; no darktable code is copied.
Integer depths below 16-bit are fed as float (like darktable): libjxl reads a
`UINT16` input as full-range 16-bit and would rescale depth-native integers
(verified: 1023 arrived as 16 for 10-bit output).

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
  `bgu_fit` adapts `src/halide/fit_and_slice_3x4.cpp` (2016 gain prior)
  and `src/halide/fit_and_slice_affine_grid_halide.cpp` (Generator structure;
  f32 I/O, fit-only, BGU luma weights), and the GPU slice shader ports
  `apply_local_curves.fs.glsl` to hand-written OpenGL ES 3.00. The paper's
  fit/slice split (§4–§5) shapes the whole viewfinder engine; the engine,
  JNI bridges, and schedules are RawLens's own work.

## Magic Lantern RAW exposure assist

F1/F2 port RAW zebra colour classification, RAW EV conversion and spot-region
averaging from `reticulatedpines/magiclantern_simplified` commit
`142472e95054d73640f50789ac0fd66325499d82`, `src/zebra.c` and `src/raw.c`.
Copyright (C) 2009 Trammell Hudson; Copyright (C) 2013 Magic Lantern Team.
These source files are GPL-2.0-or-later; the combined application uses GPL-3.0-or-later.
The Kotlin port retains upstream attribution in `MagicLanternRawExposure.kt`.
Platform adaptations and exact source ranges are documented in
`docs/refactor/raw-exposure-assist-audit.md`; original excerpts are retained
under `docs/refactor/upstream/`. No Canon firmware or profiles are bundled.

RawLens's ETTR (`MagicLanternEttr.kt`) ports `auto_ettr_get_correction` from
`modules/ettr/ettr.c` at the same commit (single-ISO path: percentile
targets, overexposure extrapolation, SNR limits). GPL-2.0-or-later,
Copyright (C) Magic Lantern Team.

## libcamera (Raspberry Pi AGC)

PROGRAM auto exposure (`ProgramAgc.kt`, `ProgramAeLoop.kt`) ports the
Raspberry Pi AGC channel and histogram helpers from libcamera commit
`0bf3fc5` (`src/ipa/rpi/controller/rpi/agc_channel.cpp`,
`src/ipa/libipa/histogram.cpp`) with the imx477 default tuning values.
Copyright (C) 2019, 2023 Raspberry Pi Ltd, BSD-2-Clause; license text bundled
as `app/src/main/assets/licenses/BSD-2-Clause-libcamera.txt`.
https://gitlab.freedesktop.org/camera/libcamera

## Open Camera (flash precapture)

The flash precapture wait (`FlashPrecapture.kt`: AE must enter PRECAPTURE
within 2 s, then leave it within 3 s) follows Open Camera's
`CameraController2` at commit `0dd4cbe`. Open Camera, Copyright Mark Harman,
GPL-3.0-or-later. https://sourceforge.net/projects/opencamera/

## BakingLab ACES fit (Direct Log ACES profile)

The Direct Log ACES look (`VfLogGrade.kt` and the direct-video grade
shaders) uses Stephen Hill's fitted ACES RRT/ODT from TheRealMJP/BakingLab
`ACES.hlsl`. Copyright (c) 2016 MJP, MIT License; license text bundled as
`app/src/main/assets/licenses/bakinglab-aces.txt`.

## Google Turbo colormap

`TurboColormap.kt` ports the GLSL polynomial approximation by Anton Mikhailov
and Ruofei Du, Copyright 2019 Google LLC., Apache-2.0, from
https://gist.github.com/mikhailov-work/0d177465a8151eb6ede1768d51d476c7
revision `f0b77bf03aa5fce5a3ebe25988fef36ec43f5096`.
RawLens precomputes ARGB8 entries and supplies RAW EV rather than processed luma.
Bundled notices and license texts: `app/src/main/assets/licenses/`.

## Cinematic RAW Studio (.CRAW) encoder and decoder

`third_party/craw/{encoder,decoder}` vendor the Cinematic RAW Studio encoder
and decoder (Apache License 2.0; LICENSE and NOTICE files alongside, commit
pins in `third_party/craw/SOURCE.md`). They are linked into the separate
`rawLensCraw` and `rawLensCrawReader` native libraries through RawLens'
GPL-3.0-or-later JNI bridges (`craw_writer_jni.cpp`, `craw_reader_jni.cpp`).

## Packed-ratio RCD variant

The shaders under `app/src/main/assets/shaders/rcd_packed` are adapted from the user-provided packed-ratio RCD source archive (commit e0e284d58f85ae9c4bc1b2cffe0c9722969a7bb8), licensed under GNU GPL v3. Original sources, license, and provenance are preserved in `third_party/rcd-packed-upstream`.
