# JPEG XL stills and DNG JXL compression — study (2026-10-20)

Scope: `native_jxl_encoder.cpp` (developed stills), `tinydng_jni.cpp`
`encodeCfaJxl` (DNG 1.7 JXL tiles), the Kotlin wrappers (`NativeJxlEncoder`,
`JxlOutputSettings`, `JxlPixelPack`, `JxlMetadataBoxes`, `JxlSaver`,
`DngCompression`) and the libjxl pin in `app/src/main/cpp/CMakeLists.txt`.

## References (pinned)

| Ref | Revision |
|---|---|
| libjxl | v0.11.1 `794a5dc` (pin before), v0.12.0 `a7a9c78` |
| darktable `src/imageio/format/jxl.c` | master `e335c52` |
| Adobe DNG SDK 1.7 (`dng_jxl.cpp`, `dng_tag_codes.h`, `dng_tag_values.h`) via github.com/CitrusPeel/dng_sdk | main, fetched 2026-10-20 |

## Verified

- Still encoder basic-info / lossless / distance / effort / decoding-speed
  mapping is darktable's `write_image` line for line, including "Do not enable
  libjxl lossless mode for float16" (float32 still uses SetFrameLossless).
- DNG Compression 52546 = `ccJXL` (DNG SDK `dng_tag_values.h`).
- Single-plane raw encoding matches the DNG SDK: GRAY, D65, LINEAR, lossless
  with `uses_original_profile`; the SDK notes primaries are unused for gray.

## Findings

| # | Finding | Action |
|---|---|---|
| J0 | Comment cited "libjxl #17487"; libjxl has no such issue (404, max ~5052). It is **darktable** issue #17487 "jxl - check option combinations & bit depth", cited by darktable's jxl.c | Citation corrected with links |
| J1 | `JxlColorEncoding` filled field by field, never zeroed (both encoders); the DNG SDK `memset`s it first | Zeroed. Proven byte-identical on device (all four proof files) |
| J2 | No JXL decode/round-trip verification existed (host test lib builds without libjxl) | `JxlProofDeviceTest` + `tools/jxl_proof/verify.py` (libjxl djxl/jxlinfo): lossless pixels, orientation, transfer, intensity target, DNG 52546 / 1.7 / bit-identical mosaic; negative control catches a single flipped bit |
| J3 | DNG writer does not emit the optional `JXLDistance` (52553), `JXLEffort` (52554), `JXLDecodeSpeed` (52555) tags (DNG SDK `dng_tag_codes.h`) | Recorded; optional metadata, not needed by readers |
