# Landscape DNG/JPEG orientation fix

## Root cause

Capture orientation was derived from the live `deviceOrientationDegrees` when a RAW image reached
`saveRawFrame()`. With RAW ZSL, burst capture, camera-thread dispatch, and queued JPEG development,
that save boundary can occur after the shutter press. A landscape RAW frame could therefore inherit
a newer handset orientation and receive the wrong DNG/JPEG EXIF orientation (commonly a 180-degree
error). Portrait hid the problem because the default portrait orientation is stable most often.

## Fix

- Snapshot `deviceOrientationDegrees` at the shutter press for single and burst captures.
- Resolve and freeze one `activeOutputOrientation` when the logical capture starts.
- Use that exact frozen EXIF orientation for the `RawFrameMetadata` consumed by both DNG and JPEG.
- Centralize Camera2 orientation conversion in `CaptureOrientation`.
- Handle Camera2's front/back camera sign convention explicitly.
- Keep RAW and developed JPEG pixels in native sensor coordinates; orientation remains metadata-only.
- Add unit vectors covering portrait, both landscape directions, upside-down, front-facing cameras,
  normalization, and equivalent negative rotations.

## Validation

`CaptureOrientation.kt` compiles independently with the local Kotlin compiler and all orientation
vectors pass a direct sanity check. `git diff --check` passes. The full Gradle unit suite could not
run in the offline sandbox because Gradle 8.9 was not present in the wrapper cache and the wrapper
attempted to download it from services.gradle.org.
