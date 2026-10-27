# JPEG XL stills and DNG JXL compression — study (2026-10-20)

Original 2026-10-20 scope: `native_jxl_encoder.cpp` (developed stills), `tinydng_jni.cpp`
`encodeCfaJxl` (DNG 1.7 JXL tiles), the Kotlin wrappers (`NativeJxlEncoder`,
`JxlOutputSettings`, `JxlPixelPack`, `JxlMetadataBoxes`, `JxlSaver`,
`DngCompression`) and the libjxl pin in `app/src/main/cpp/CMakeLists.txt`.

## Current integration (2026-10-27)

The DNG `encodeCfaJxl` helper described in this historical study has been
removed. JNI now passes RAW16 pixels to TinyDNG, whose optional libjxl encoder
owns lossless compression, DNG 1.7 version defaults and chunked compressed
output. The developed-still encoder remains in `native_jxl_encoder.cpp`.
All 13 shared TinyDNG files match upstream `e1d6442` byte-for-byte.

Android links libjxl on `tinydng_v3`; host builds discover installed libjxl
through pkg-config. Effort remains 3 by default with automatic threads.
Single-frame and 16-bit HDR+ JXL saves preserve EXIF/GPS. Packed and float-CFA
paths remain uncompressed. Native encode tests independently decode exact
samples, including both TIFF byte orders and partial/multiple strips.
The 2026-10-27 run passed 58 selected JVM tests without skips, TinyDNG's 10-test
suite with ASan/UBSan, and an Android arm64 library build. Historical device
ratios/timings below have not been remeasured after moving the encoder.
See [current integration and build instructions](../tinydng-integration.md).

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
| J4 | **Pin bumps silently did not apply.** (a) `LIBJXL_VERSION` was a `CACHE STRING`, so an existing build tree kept `v0.11.1` (all 10 caches did); (b) the fetch reused a version-less `libjxl-src` dir; (c) the resolved path was written back into `LIBJXL_SOURCE_DIR`, which an old cache then supplied. A "bumped" build rebuilt 0.11.1 | Pin is a normal variable (explicit `RAWLENS_LIBJXL_VERSION_OVERRIDE` remains); version-specific clone dir; private `_rawlens_jxl_src`; configure logs the source dir. Verified: fresh clones report `git describe` = v0.12.0 for both ABIs |

## libjxl 0.11.1 → 0.12.0 (2026-10-20)

Relevant 0.12.0 changelog items: better lossless effort density/speed, fixed
"Density regression with Predictor Zero since v0.11", fixed "Images could be
corrupted when encoding effort 1 lossless" (RawLens allows effort 1), "Float16
values roundtrip more accurately"; no removed API that RawLens calls (jpegli
removal is irrelevant: already disabled). `jxlinfo` output format changed (C++
rewrite); `verify.py` accepts both.

Device proof (Xiaomi 25080RABDG), verified with libjxl djxl/jxlinfo:

| Check | 0.11.1 files, 0.11.1 tools | 0.11.1 files, 0.12.0 tools | 0.12.0 files, 0.12.0 tools | 0.12.0 files, 0.11.1 tools |
|---|---|---|---|---|
| 16 checks (lossless pixels, orientation 6/1, sRGB/PQ transfer, intensity target, 52546, DNG 1.7, bit-identical mosaic) | all pass | all pass | all pass | all pass |

Sizes / encode time:

| Output | 0.11.1 | 0.12.0 |
|---|---|---|
| Still SDR lossless 257×193 | 242,399 B, 143 ms | 238,487 B (−1.6 %), 131 ms |
| DNG JXL 1024×768 | 852,352 B | 853,758 B (+0.16 %) |
| DNG JXL 12 MP (ratio) | 13,550,484 B (1.843×) | 13,549,774 B (1.843×) |
| DNG JXL 12 MP encode, 7 reps × 3 alternating runs, min/median ms | 523/530, 510/536, 460/469 | 546/559, 476/494, 473/485 |

12 MP DNG encode time is indistinguishable within run-to-run (thermal)
variation; DNG size identical; stills ~1.6 % smaller.
