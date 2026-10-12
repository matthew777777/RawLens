# Upstream provenance: RAWR raw_denoise

Vendored: 2026-10-11, from `native/raw_denoise` (sources + shaders,
byte-identical) and its validation harness inputs.

- Source: https://github.com/adityawarmanfw/rawr
- Revision: 87c2209 (release 0.3.1, 2026-10-11)
- License: GPL-3.0-only (compatible with RawLens GPL-3.0-or-later)

Rawr's own upstream note (`native/raw_denoise/NOTICE.md`, copied to
`app/src/main/cpp/wavelet/upstream/NOTICE.md`):

> GPU port of **darktable's** `denoiseprofile` wavelet path
> (<https://github.com/darktable-org/darktable>), GPL-3.0-or-later.

What RawLens vendors 1:1 (behavior bit-identical):

- `app/src/main/cpp/wavelet/upstream/`: `DenoisePipeline.hpp` +
  `DenoisePipeline.cpp`, unmodified.
- This directory: the 4 `denoise_img_*.comp` shaders, unmodified, plus
  `.spv` compiled with `glslangValidator -V --target-env vulkan1.1`.
- `tools/wavelet-parity/`: `denoiseprofile_oracle.py` (float32 darktable
  port), `verify_denoise_parity.py` (adapted paths only), one frozen
  fixture set (`RAWR_20260926_000415_161` input + s1 golden) with its
  manifest row, and the `denoise_bench` host harness (+ the 61-line
  `vk_test_common.hpp` it needs). The other two fixture sets were not
  vendored (30 MB total); point the script at a rawr checkout to run them.

RawLens-authored (not upstream):

- `app/src/main/cpp/wavelet/wavelet_host.*`, `wavelet_jni.cpp`: private
  Vulkan device, staging upload/download of RGBA16F, JNI.
- `WaveletDenoiser.kt`, `DenoiseSettings` wavelet fields, `resolve()`,
  camera-linear tap + post-CCM pass in the AMaZE/JPEG path.
