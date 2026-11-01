# Vivo RCD clip / log / RAWR comparison

## Matched field evidence

`IMG_20261031_215213_624_LOG709.mp4` matches the recording named in
`logcat-20261031_215201_009 (1).txt`, app 2.0.56. FFmpeg fully decoded all
139 frames without reported errors: 3840x1920, HEVC Main 10, approximately
29.91 fps. Decoded frames retain the coloured fine grid and stair-stepped
edges; playback through VLC is not required to reproduce them.

The log reports RAW 4080x3072, CPU and AHB pitch 4080, crop 3840x1920 at
(120,576), packed RCD, Qualcomm HEVC, and DIRECT_P010 plane-copy delivery.
For both diagnostic frames, all 96 checked output words match the staging
buffer after copying. All 139 frames were graded with zero reported drops
or frame errors. Those sparse comparisons validate the sampled copy, not
every pixel or subsequent encoder behavior. Direct comparisons with the
first lossy-decoded picture differ and cannot serve as a bit-exact oracle;
the sparse first-frame comparison is archived without treating it as proof
of a particular processing-stage failure.

The user reported a clean idle viewfinder and corruption in the recording
preview. MHC has not yet been tested on this phone with matching settings.
RCD processing is therefore a strong lead, not an established root cause.

## RAWR comparison

Read-only upstream checkout: `adityawarmanfw/rawr` at
`4c64cb1f5eb553bf61ca9a459c5a2c94964732e4`.

- `app/src/main/cpp/vulkan/RawAhbImporter.cpp` uses plain storage-buffer
  import on Adreno and the AHB descriptor stride. RawLens already follows
  the same basic word-addressing/import approach.
- `native/video_pipeline/README.md` explicitly describes tests on Vivo V2562.
- `native/video_pipeline/shaders/video_demosaic_quad.comp` uses literal
  16x16 workgroups. `video_quad_core.glsl` uses f32 RAW cache storage and
  u32 packed-half green/chroma storage. Its video demosaic is guided chroma
  with Hamilton-Adams green, not RawLens's packed RCD algorithm.

RawLens's existing RCD modules have specialization-sized 16-bit shared
arrays. Their SPIR-V uses WorkgroupSize specialization constants to derive
array dimensions. A driver/layout/compiler issue there is a concrete
remaining hypothesis consistent with tiled artifacts. The RAWR comparison
does not establish that the Vivo driver mishandles those modules.

## Compatibility adaptation

Qualcomm Vulkan devices (vendor 0x5143) now select RCD variants with fixed
16x16 workgroups and u32 shared words holding packed half values. Reads
unpack into the original half type; writes preserve explicit half packing.
Arithmetic, colour transforms, grading, and output format remain the same.
Explicit packing matters: simply widening storage to float changed rounding
in the initial parity experiment. The final packed-word implementation
passes strict comparisons; tolerances were not relaxed.

The other GPU vendors keep the existing specialized f16-shared shaders.
No RAWR processing source was copied. Variants are generated from RawLens's
existing RCD shader sources by `tools/build_packed_rcd_portable.py`, and all
six binaries pass spirv-val. The file logger records
`RCD shaders=portable-fixed16-packed32-shared workgroup=16x16 tuned=false`
on the compatibility path.

## Validation and next field check

On Xiaomi 25080RABDG, forced portable vs existing shaders matched every
checked output bit across 8 cropped RGB cases (252,992 float values) and
48 fused P010 cases (1,043,040 words), all four Bayer patterns, three output
sizes, and BT.709/S-Log3/HLG/ACES. The comparisons passed without a LUT,
then passed again with a 16-point identity LUT. The final combined run
passed all 12 tests, including the existing packed-RCD suite. Release
assembly passed. The isolated test build uses the previously documented
exclusion of its duplicate lint tasks; the regular release ran its checks.

This validates math preservation on the Xiaomi, not a fix or a performance
result on Adreno. Retest on the Vivo using matching RCD and MHC takes.

Separately installable APK: `/tmp/RawLens-2.0.57-Vivo-RCD-compat-arm64.apk`,
package `com.matthew.rawlens.vivocheck`. Its six shader binaries were checked
against the generated files and its APK signature verified.

Evidence: [no-LUT parity](results/vivo-rcd-portable-2026-10-31/portable-parity-no-lut.txt),
[final 12 tests](results/vivo-rcd-portable-2026-10-31/portable-and-default-lut-tests.txt),
[release build](results/vivo-rcd-portable-2026-10-31/release-build.txt).

Upstream references:
[Vivo-tested video path](https://github.com/adityawarmanfw/rawr/blob/4c64cb1f5eb553bf61ca9a459c5a2c94964732e4/native/video_pipeline/README.md),
[quad shader](https://github.com/adityawarmanfw/rawr/blob/4c64cb1f5eb553bf61ca9a459c5a2c94964732e4/native/video_pipeline/shaders/video_quad_core.glsl),
[RAW importer](https://github.com/adityawarmanfw/rawr/blob/4c64cb1f5eb553bf61ca9a459c5a2c94964732e4/app/src/main/cpp/vulkan/RawAhbImporter.cpp).
