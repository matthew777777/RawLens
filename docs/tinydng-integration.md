# TinyDNG integration

RawLens' `TINY_DNG` backend uses the C11 v3 writer from
https://github.com/matthew777777/tinydng at commit
`1f181699511e08e51baabd9fdab2311ea1253d4b`.
The requested repository is cloned into `references/tinydng` (ignored research checkout).
The build uses pinned, checked-in sources under `app/src/main/cpp/deps/tinydng`, so it
needs neither a network fetch nor that checkout. `UPSTREAM.json` records original
file hashes; `rawlens.patch` records every modification to upstream code.

Upstream v3 can read NoiseProfile and GainMap but its writer omits them, ActiveArea,
and several calibration tags. The local patch adds validated extra TIFF fields:
known field sizes, payload length checks, duplicate rejection, and a 64-tag limit.
The existing 256 KiB metadata capacity remains enforced with a reported error.
RawLens supplies Camera2 metadata as these fields while TinyDNG owns TIFF layout,
strip offsets and pixel serialization. The old PhotonCamera DNG bridge and C++
header are no longer compiled. The `dngCreator` library name remains for JPEG JNI.

`NativeDngWriter` packs padded RAW16 rows without modifying samples, shifts black
levels and CFA to the stored buffer origin, then calls `rawLensDng` JNI. JNI validates
buffer capacity and descriptors, writes uncompressed 16-bit CFA, and sends the
result to the OutputStream in 64 KiB chunks. Native output is currently materialized
in memory; this does not claim constant-memory streaming. Native failures and Java
OutputStream exceptions propagate to DngSaver's existing failed-media cleanup.

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

Android DngCreator remains the default; AUTO still falls back to TinyDNG. HDR
float-CFA export remains its separate writer.

## Verification

- `./gradlew :app:testDebugUnitTest --tests com.matthew.rawlens.DngNoiseProfileTest --tests com.matthew.rawlens.TinyDngMetadataTest :app:assembleDebug`
- `python3 tools/verify_tinydng.py`

The native test runs with ASan/UBSan and checks lossless sample round-trip,
DOUBLE NoiseProfile, parsed GainMap/ActiveArea, rejected malformed and duplicate
tags, and allocation cleanup. It also independently parses OpcodeList2 using the
existing Python checker. With the reference checkout present, it verifies the
vendored source can be reproduced exactly from upstream plus the recorded patch.
Kotlin tests cover all four CFA layouts, both buffer-origin parities, cropped
ActiveArea geometry, distinct gain channels, fractional black levels, calibration,
and already-applied lens shading.

No Android device was connected during this integration; a fresh camera capture
and external RAW developer rendering still require device verification.
