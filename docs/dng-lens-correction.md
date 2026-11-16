# DNG lens correction and RapidRAW compatibility

Research and implementation, 2026-11-16.

## Findings

RawLens's default is Android DngCreator, not TinyDNG. Current AOSP DngCreator
already converts paired Camera2 LENS_DISTORTION and LENS_INTRINSIC_CALIBRATION
into OpcodeList3/WarpRectilinear when the HAL supplies them. The initial
diagnosis that all our DNGs lack distortion metadata was too broad.

A recent 4080x3060 capture from the connected Xiaomi 25080RABDG contains four
OpcodeList2 GainMaps and no OpcodeList3. Its static Camera2 dump contains no
standard lens distortion/intrinsic calibration fields. This is evidence about
this device/firmware, not a promise that every frame or other phone lacks them.
No source photograph or complete device metadata dump is committed here.

RapidRAW upstream at `b09789369ebebb4e761b230580fa4eb8a2788977` does not consume
these GainMaps. Its pinned rawler at `b1d102af3e5543c9582e99718c7ae57d564ec176`
copies opcode lists for DNG rewriting but only applies MapPolynomial to its
special proxy path. RapidRAW's optional Apple RAW path explicitly disables
Apple lens correction, so that is not a portable solution either.

Linear DNGs usually look correct because RawLens applies shading during RAW
development or to merged RGB before export. The reader does not need to apply
an opcode. This says nothing about geometric distortion being corrected.

## Implementation and rollout

1. **Preserve standard geometric calibration.** Snapshot both modern Camera2
   calibration arrays from the same physical capture result; never combine a
   physical coefficient array with logical-camera intrinsics. TinyDNG and
   supported float-CFA and HDR+ writers emit optional WarpRectilinear
   in OpcodeList3. Original mosaic samples remain untouched in these modes.
   Linear RGB merges need an explicit output-to-sensor mapping before a warp
   can safely be copied: equal dimensions do not prove the same field of view.
2. **Provide an immediate shading workaround.** General settings → DNG writer
   backend → Compatibility (lens shading baked) exports single-frame,
   uncompressed 32-bit float Bayer DNGs. Black subtraction and the camera's
   shading map are applied once, in bounded 32-row strips. No demosaic, geometric
   resampling, clipping to nominal white, or mutation of the input buffer occurs.
   Already-applied shading is not multiplied again. No GainMap or original
   spatially invariant NoiseProfile is written after shading is baked. Color
   matrix overrides, EXIF and GPS remain supported. DNGBackwardVersion is 1.4.
   File payload is about twice RAW16, and the correction is permanent. Missing
   maps without HAL-applied shading, unproven geometry and grouped/Quad Bayer
   readouts are rejected rather than labeled corrected. Merged/AI saves keep
   their existing processing paths. JXL selection does not override this mode.
3. **Fix the reader for original captures.** A focused RapidRAW PR adds standard
   GainMap application before demosaicing, retaining float headroom and honoring
   area, pitch, planes, origin, spacing and active-area coordinates. This also
   helps existing files. A separate geometric-correction change should consume
   OpcodeList3 before orientation/default crop and prevent double application
   with Lensfun; it must not pretend that missing camera calibration exists.

## Geometry conversion limits

Camera2 uses focal-normalized coordinates; DNG uses the distance from the optical
center to the farthest image corner. For isotropic focal length f and radius R,
the radial multipliers are (R/f)^2, (R/f)^4 and (R/f)^6, and the two tangential
multipliers are R/f. The principal point is adjusted from pre-correction active
coordinates to the retained processing crop. Unit tests independently evaluate
both equations across the image, including off-center optics and crop offsets.

The current serializer requires fx≈fy and zero skew (rounding tolerance 1e-6),
finite calibration, an optical center inside the retained image, and positive
sampled radial/Jacobian derivatives. Vendor readout overrides, maximum-resolution
pixel mode, explicitly binned readouts, unknown/resized geometry, deprecated
LENS_RADIAL_DISTORTION and all-zero models are omitted. It does not assume a
sensor model identifies a lens, fabricate TCA, or add an implicit zoom/crop.
Editors may need their normal automatic crop for warped edges.

Supporting every phone means graceful capability detection. Accurate geometric
correction on a phone exposing no calibration still needs a verified device/lens
profile or a separately validated calibration method. libcamera algorithms and
tuning files cannot manufacture another module's missing optical calibration.

## Primary sources

- [Camera2 capture calibration](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LENS_DISTORTION)
- [AOSP DngCreator conversion](https://android.googlesource.com/platform/frameworks/base/+/master/core/jni/android_hardware_camera2_DngCreator.cpp)
- [DNG SDK warp implementation](https://android.googlesource.com/platform/external/dng_sdk/+/refs/heads/android14-prebuilt-test/source/dng_lens_correction.cpp)
- [DNG SDK gain-map interpolation](https://android.googlesource.com/platform/external/dng_sdk/+/refs/heads/android14-prebuilt-test/source/dng_gain_map.cpp)
- [RapidRAW Lensfun behavior](https://www.getrapidraw.com/docs/technical/geometry-merging-export)
- [Embedded lens correction request](https://github.com/CyberTimon/RapidRAW/issues/1289)
- [libcamera tuning requirements](https://lists.libcamera.org/pipermail/libcamera-devel/2024-August/044115.html)

## Validation

Host tests exercise Camera2↔DNG coordinate equivalence; malformed/missing/folding
calibration; cropped/full writer tags; real native serialization; strip boundaries;
odd sensor origins and CFA channel parity; negative noise and above-white samples;
missing/already-applied shading; and read-only input buffers. Full-resolution
device capture, interoperability and memory/latency measurements remain separate
from host correctness tests; do not claim them from a successful unit-test run.
