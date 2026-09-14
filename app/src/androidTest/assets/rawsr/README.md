# RAW-SR real-device fixtures (schema 1)

Inputs are measured, **unnormalized Bayer sensor codes**, never rendered PNG/JPEG
or re-mosaiced RGB. This supersedes the former instruction to store normalized
planes: normalization belongs to the tested CPU/GLES pipelines.

## Sea

`sea/` holds lossless extracts from all 30 user-contributed Sea DNGs, captured on
Redmi 25080RABDG using PhotonCamera. Redistribution was confirmed on 2026-09-10;
see [license and attribution](sea/LICENSE.md).

- Original dimensions: 4080×3060, 16-bit GBRG, ISO 50, same exposure.
- Stored region: 514×386 at (800,1200), in DNG active-array coordinates.
- Processing crop: (1,1,512,384), testing odd-origin CFA shift to GRBG.
- Reference: original index 15, a real captured middle frame.
- Each `frame-NN.raw16le.gzip` is gzip-compressed little-endian uint16, row-major.
  The suffix avoids Android's automatic expansion/renaming of `.gz` assets.
  No scaling, clipping, normalization, demosaic, resampling, alignment or fusion.
- Four pending DNG GainMap opcodes become a 17×17 R/G-even/G-odd/B lens map.
  Those maps have not been applied to the stored Bayer samples.

The originals were copied byte-for-byte into Git-ignored
`references/rawsr-private/Sea/`. Only the compact extracts are test APK assets;
neither dataset is included in the production APK.

## Manifest

`manifest.json` contains `schemaVersion=1`, `dataKind=real-camera-bayer`,
license/source provenance, `referenceIndex`, `referenceReason` and ordered frames.
Every frame records:

- payload path, byte count, SHA-256 of compressed and decoded bytes;
- original filename and DNG SHA-256;
- device make/model/software and camera/physical-camera IDs when known;
- width/height, byte row/pixel strides, crop [left,top,width,height],
  sensor origin [x,y], coordinate system, original dimensions/active array;
- original HAL geometry when known;
- sensor-origin CFA pattern, four row-major black levels, white level;
- exposure seconds, ISO, timestamps and provenance, six noise coefficients;
- lens-shading status/applied flag, rows/columns, active rectangle
  [left,top,right,bottom], channel order and point-major gains.

The loader bounds decoded sizes, validates required metadata and both checksums,
and freezes packed-plane descriptors. Corruption never falls back to rendered data.

## Known metadata limits

These DNGs do not preserve Camera2 SENSOR_TIMESTAMP, original HAL Image strides,
camera/physical IDs or physical sensor-buffer origin. Those fields are explicitly
null. Filename milliseconds are **save-time evidence**, not sensor timestamps.
The filename save times span roughly 27 seconds; this does not establish capture cadence.
DNG active-array origin is not claimed to be physical HAL origin. This fixture
validates real Bayer alignment, normalization and import, but does not complete
the original frozen-Camera2-metadata or ZSL-cadence qualification.

## Reproduction and tests

```sh
python3 tools/import_rawsr_sea.py references/rawsr-private/Sea --output app/src/androidTest/assets/rawsr/sea --roi 800 1200 514 386
```

Requires numpy, tifffile and exiftool. Optional rawpy/Pillow preview is solely for
human ROI inspection, never algorithm input. The importer verifies exact equality
of decoded fixture pixels and the original Bayer slice and emits deterministic gzip.

Run `RawSrGpuInstrumentedTest` headlessly for the synthetic + real matrix.
CSV, flow/residual/confidence PNGs and `sea_metrics.json` are exported to
app-private `cache/rawsr-debug/`. Synthetic accuracy and 80% coverage gates are
unchanged. Real-scene coverage/rejection is reported separately: it has no known
ground-truth flow. Adreno is **UNTESTED** until an actual Adreno run.
The truthful reference-frame capture fallback remains active.
