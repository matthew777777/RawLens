# Capture metadata audit

Reference: [comparison workbook, Summary / OfInterest / Full](https://docs.google.com/spreadsheets/d/159e77LWv6eWM6HZgvuhk74pXCYthOlcaV8J7gK7rf0k/edit#gid=937712955), reviewed 2026-10-25. The Full tab contains 3,062 tag rows; many are proprietary maker notes or derived ExifTool values, rather than portable capture tags. The OfInterest tab narrows the comparison to 63 items. Presence in another camera's output does not establish a valid source for RawLens.

## Implemented

| Metadata | Behavior |
| --- | --- |
| Exposure, aperture, focal length, focus distance, flash | Shared frozen-frame EXIF values for JPEG and DNG; DNG EXIF copies accompany existing legacy IFD0 values. Missing capture facts are omitted. |
| APEX ShutterSpeedValue / ApertureValue | Signed shutter rational supports exposures longer than one second. |
| ExposureMode / ExposureProgram / WhiteBalance | Captured Camera2 AE/AWB modes, independent of convergence states. CONTROL_MODE_OFF disables both. These describe HAL controls; application AE implemented using manual HAL requests still reports manual. |
| ExposureBiasValue | Capture compensation steps multiplied by the characteristics' EV step; signed rational. |
| ISO | EXIF sensitivity plus full LONG ISOSpeed; values above 65535 use the SHORT saturation convention without losing the full speed. |
| Timestamps | Original/digitized dates, all three subsecond and offset fields, and modification date from the saver's supplied capture ID. No conversion of boot-time sensor timestamps to Unix dates. Millisecond precision only. |
| LensModel | Application-generated identifier from device model, physical/camera ID, reported focal length and aperture. Does not claim an OEM marketing lens name. |
| LensSpecification | Fixed focal length and widest available aperture when characteristics declare exactly one focal length. Variable lens endpoint apertures are not guessed. |
| DigitalZoomRatio | Reported zoom for non-logical cameras only. Logical camera zoom can include optical switching and is omitted. |
| GPS | Altitude/ref, fix UTC date/time, processing method, horizontal positioning error when available, plus coordinates. Same GPS directory is appended to Android and single-frame TinyDNG output, including lossless JXL DNG. |
| GPS encodings | JPEG version is four BYTE values rather than ASCII digits; JPEG GPS timestamp uses AndroidX's accepted HH:MM:SS input. Processing method is UNDEFINED with the ASCII character-code prefix; accuracy is explicitly rational. |
| HDR+ composite metadata | DNG CompositeImage=2 and SourceImageNumberOfCompositeImage=[selected,used]. Both live reference snapshots and provenance-driven/offline HDR+ writers carry counts. |
| ProfileName | Names the existing embedded matrices "RawLens Camera2 calibration"; preserves a pre-existing platform profile name. No new rendering look or external profile is implied. |
| Offline CRS | New recordings retain aperture, focal length, focus distance, flash state, AE/AWB modes, compensation steps and step size, and non-logical digital zoom. Older containers omit unavailable facts. |
| TinyDNG rational encoding | Uses the shared reduced, overflow-safe rational serializer. |

## Serialization

Standalone `.jxl` output now uses the same `DngExifDirectory` and `GpsTiffDirectory` field builders in its embedded EXIF box. It includes exposure controls/APEX values, full ISO, lens identifiers/specification, timezone/subseconds, GPS accuracy/processing method, and HDR+ composite counts when present in the snapshot. Unlike AndroidX's JPEG writer, this custom TIFF builder can serialize the composite count tags. ColorSpace and output pixel dimensions are in the EXIF subdirectory; dimensions match the developed raster (including downscaled output). Directories are sorted and payloads aligned to two bytes. XMP dimensions and white balance match EXIF, and XMP dates use ISO 8601 with the timezone offset. An absent GPS fix adds no GPS directory. The previous incorrect GPSImgDirectionRef="GPS" entry is removed; GPSProcessingMethod uses its actual UNDEFINED tag and character-code prefix.

Android DngCreator remains responsible for its raw calibration, crops and correction opcodes. Before MediaStore publication, `DngCaptureMetadata` appends updated EXIF/GPS and IFD0 directories, retaining existing entries and offsets. Unknown tags, MakerNote payloads, next-IFD links, strips, compression and image bytes remain in place. The TIFF header changes last. The same operation enriches single-frame TinyDNG output, eliminating the previous GPS loss and GPS-related JXL fallback. Added bytes scale with metadata, not image size. Calibration overrides still run after enrichment.

Merged/float/linear writers serialize EXIF/GPS directly through TinyDNG. Float CFA saves now receive their capture ID for dates. No AndroidX `saveAttributes()` call is made on DNGs; AndroidX supports writing JPEG/PNG/WebP, not DNG.

## Remaining gaps and data requirements

- **GPSImgDirection:** needs a shutter-synchronized, orientation-corrected compass measurement and magnetic/true-north reference. Location bearing is travel direction and is not a substitute.
- **MeteringMode:** Camera2 AE regions do not reveal the vendor's metering pattern. Omitted rather than claiming spot/average metering.
- **LensMake / LensSerialNumber:** no dependable public Camera2 source. Device manufacturer is not necessarily lens manufacturer.
- **35mm equivalent focal length:** needs validated physical-sensor/crop geometry for each capture mode and physical lens; omitted.
- **Composite exposure times:** not yet emitted; the EXIF tag uses a structured UNDEFINED payload, not a plain exposure array. Need total capture interval and source exposure accounting, including rejected frames. Existing HDR+ ImageDescription preserves used-frame exposures.
- **JPEG composite count tags:** AndroidX 1.4.2 does not recognize these tags; they are deliberately excluded from `CaptureExifAttributes`. A separate tested APP1 writer would be required. DNG counts are supported.
- **Other merge algorithms:** HDR+ counts are wired; HDR brackets, fusion and MFSR need their own selected/used-frame provenance before they can emit trustworthy composite tags.
- **Distortion opcodes:** no new placeholder OpcodeList3 is emitted. Android output retains platform opcodes; TinyDNG still needs a validated conversion of Camera2 intrinsics/distortion into DNG warp opcodes and crop coordinates.
- **Proprietary metadata / vendor JPEG copying:** no cross-frame copying of MakerNotes, GPS, exposure or lens tags from unrelated stock JPEGs. Most proprietary rows in Full have no public API equivalent.
- **Delayed offline output:** original CRS capture wall time/timezone and OEM identity are not reconstructed from the recording-relative sensor clock. Existing saver timestamp semantics remain.

## References

- [Camera2 CaptureResult](https://developer.android.com/reference/android/hardware/camera2/CaptureResult): mode definitions, compensation steps and zoom semantics.
- [DngCreator](https://developer.android.com/reference/android/hardware/camera2/DngCreator): platform writer API and setLocation contract.
- [AndroidX ExifInterface](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface) and [implementation](https://github.com/androidx/androidx/blob/androidx-main/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java): supported tags, write formats and byte/rational/timestamp handling.
- [CIPA EXIF composite-image extension](https://www.cipa.jp/std/documents/e/DC-X008-Translation-2019-E.pdf): general composite and source-count meanings.
- [Adobe DNG specification and SDK](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html): DNG profile/calibration and TIFF structure.

## Validation

Targeted host tests cover the real AndroidX JPEG EXIF save/reopen path, including raw GPS version bytes, signed exposure values, UTC GPS time and altitude/accuracy; the host-only Android Pair stub is supplied with constructor values. DNG append tests run in both byte orders, check opaque payload and original-byte preservation, and verify appended signed rationals and GPS fields. Existing native TinyDNG writer tests cover float, linear RGB, HDR+, GPS and metadata output. Physical-device MediaStore and camera-HAL validation remain necessary, especially for Ultra HDR, JXL and logical multi-camera devices.

## TinyDNG encoder sync (2026-10-27)

TinyDNG now owns RAW16-to-JPEG-XL encoding for single-frame and 16-bit HDR+
DNG output; JNI supplies pixels and metadata directly. EXIF/GPS handling above
continues to apply. The generic writer transports OpcodeList1/2/3 verbatim,
including future opcodes, while RawLens remains responsible for valid Camera2
lens-shading and distortion conversions. The sync does not add a new distortion
model. Missing JXL version tags become DNG 1.7.0.0; explicit older versions fail.
`NativeJxlDngWriterTest` verifies the JNI JXL path with automatic versions and a
GPS directory when host libjxl is available. See
[TinyDNG integration](tinydng-integration.md) for build, limits and verification.
