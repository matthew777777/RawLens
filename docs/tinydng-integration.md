# TinyDNG integration

## Sources and ownership

RawLens vendors the C11 v3 writer from
https://github.com/matthew777777/tinydng. All 13 shared files in
`app/src/main/cpp/deps/tinydng/UPSTREAM.json` match TinyDNG commit `4df281b`
byte-for-byte. That manifest intentionally retains the original `1f18169`
base hashes; `rawlens.patch` reproduces the updated header/writer from that
base. Vendor-only provenance files and Android/JNI code are separate.
The checked-in TinyDNG source build needs no fetch or research checkout.

The shared writer now supports checked serialized TIFF/DNG fields, sorted
EXIF/GPS directories, all three opaque opcode lists, sequential uncompressed
strips and native lossless JPEG XL. It enforces 64 total main-IFD entries,
256 KiB extras and 128 fields per EXIF/GPS directory. Malformed lengths,
duplicate tags and unsupported combinations fail explicitly. EXIF/GPS directories
and header-first output currently require classic TIFF. Generic fields preserve
DNG 1.7 tags; this does not imply interpretation of every DNG 1.7 feature.

RawLens owns Camera2 metadata conversion, coordinate/CFA transforms and pixel
quantization; TinyDNG owns TIFF layout, directories, offsets and compression.
The former PhotonCamera DNG bridge and C++ header are no longer the active writer.

## Pixel and output paths

`NativeDngWriter` packs padded RAW16 rows without changing sample values, shifts
black levels/CFA to the stored buffer origin and calls `rawLensDng` JNI. JNI
validates descriptors/capacity and passes pixels directly to TinyDNG. Single-frame
uncompressed and JXL saves still materialize the final TIFF in memory before
chunked OutputStream delivery; errors propagate to failed-media cleanup.

`TinyDngImageWriter` uses header-first streaming for uncompressed merged,
linear RGB and float-CFA output. Header, metadata and precomputed strip offsets
are emitted at open; strips append in order to a non-seekable stream. EXIF/GPS
subdirectories are deep-copied by the native writer. The 16-bit HDR+ JXL path
uses the one-shot native encoder with those same directories. Float-CFA and
packed-input paths remain uncompressed. Single-frame GPS enrichment still uses
`DngCaptureMetadata`; selecting JXL does not discard location.

## JPEG XL / DNG 1.7

Callers pass ordinary host-order unsigned RAW16 pixels with compression 52546;
no app-side compressed buffer or `precompressed` flag is required. TinyDNG
encodes losslessly, supplies missing DNGVersion/DNGBackwardVersion 1.7.0.0 tags,
and rejects explicit older versions. Defaults match the former JNI encoder:
effort 3, automatic threads, linear grayscale and original-profile lossless
samples. Compressed bytes drain directly to the TIFF sink through reusable
64 KiB staging; libjxl still allocates its own frame/runner memory outside
TinyDNG's tracked context cap. JXL tiles and header-first output are unsupported.
TinyDNG does not decode JPEG XL. Caller-encoded strip copy-through remains
available without libjxl for other library clients.

Android defines `TINYDNG_HAVE_JXL` on `tinydng_v3` and links it to the existing
`jxl`/`jxl_threads` targets. The libjxl pin is v0.12.0, vendored as the
`third_party/libjxl` git submodule; use `-PjxlSourceDir=<checkout with
submodules>` to override it or `-PdisableJxl` to disable it. Host `tools/build_dng_native.sh` discovers
libjxl/libjxl_threads >=0.8 through pkg-config and adds their runtime paths;
custom installs use `PKG_CONFIG_PATH`. Without libjxl, ordinary saves still
work and JXL pixel encoding reports unsupported. Standalone TinyDNG uses
`-DTINYDNG_ENABLE_JXL=ON` and exposes `tinydng_has_jxl_encoder()`.

## Capture metadata

`TinyDngMetadata` preserves fractional black levels, white level, orientation,
ActiveArea/default crop, row-major Camera2 color/calibration/forward matrices,
AsShotNeutral, illuminants, exposure, ISO, aperture and focal length when provided.
Captured matrix snapshots are transposed into TIFF row-major order because the
snapshot helper calls Camera2 getElement(column, row) in column-major order;
explicit editor overrides remain in DNG row-major order.
It does not invent a calibrated noise model when the HAL supplies none.

NoiseProfile is TIFF DOUBLE, six values in RGB order. Eight Camera2 values are
mapped using the original sensor CFA; each color retains the complete S/O pair
with the greatest positive slope. This follows Android's `generateNoiseProfile`:
https://android.googlesource.com/platform/frameworks/base/+/3542f7d/core/jni/android_hardware_camera2_DngCreator.cpp
Six-value user overrides are already RGB. Nonfinite or nonphysical profiles are
omitted. Noise coefficients describe normalized signal, so no RAW16 scaling is applied.

Lens shading remains four big-endian GainMap opcodes in TIFF UNDEFINED OpcodeList2.
Rectangles use ActiveArea-relative CFA phases and exclusive bottom/right bounds;
pitch is 2, map spacing is 1/(N-1), and singleton dimensions use spacing 1.
Camera2 R/Ge/Go/B samples are selected using CFA color and original sensor-row
parity, including odd stored-buffer and ActiveArea offsets. Maps are omitted when
absent or already applied by the HAL, avoiding double correction.

Android DngCreator remains the default for ordinary captures; AUTO can fall
back to TinyDNG, and JXL selects TinyDNG explicitly. Float-CFA exports use the
shared TinyDNG transport with their own metadata and quantization rules.

## Verification

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.matthew.rawlens.NativeJxlDngWriterTest' \
  --tests 'com.matthew.rawlens.LinearRgbDngWriterTest' \
  --tests 'com.matthew.rawlens.FloatCfaDngWriterTest' \
  --tests 'com.matthew.rawlens.HdrPlusDngWriterTest' \
  --tests 'com.matthew.rawlens.GpsDngWriterTest' \
  --tests 'com.matthew.rawlens.DngCompressionTest' \
  --tests 'com.matthew.rawlens.DngExifDirectoryTest' \
  --tests 'com.matthew.rawlens.TinyDngMetadataTest'
python3 tools/verify_tinydng.py
```

Provide host libjxl through pkg-config to execute `NativeJxlDngWriterTest`;
it skips when the host library lacks the encoder. On 2026-10-27 all 58 selected
JVM tests passed without skips with libjxl 0.12.0. The ASan/UBSan host GainMap
verifier passed, and `rawLensDng` built for Android arm64. TinyDNG's separate
10-test suite passed in default/encoder-enabled builds and with ASan/UBSan
while independently decoding JXL strips to exact RAW16 samples. Its metadata
tests cover both byte orders, all opcode lists, EXIF/GPS, sequential sinks,
allocation failures and cleanup. The recorded vendor patch was reproduced
against its base and all 13 shared files compared byte-for-byte.

These checks validate the library/JNI integration. Fresh camera captures,
MediaStore behavior and external RAW-developer rendering still need device
verification for this encoder move; older device benchmarks below describe
the earlier implementation, not a new performance measurement.
