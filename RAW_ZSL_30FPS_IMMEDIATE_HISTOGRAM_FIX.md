# RAW ZSL 30 fps / immediate RAW histogram fix

Changes in this package:

- Continuous RAW ZSL now drains `ImageReader` with `acquireNextImage()` in timestamp order instead of `acquireLatestImage()`. This prevents Camera2 from silently discarding intermediate RAW buffers that may already have matching `TotalCaptureResult` metadata, improves 30-frame ring fill, and makes the first RAW histogram pair arrive reliably.
- ZSL repeating requests keep `TEMPLATE_PREVIEW` for a smooth TextureView, but set `CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG` and apply the selected advertised AE target FPS range when compatible with the configured stream ceiling.
- Entering ZSL immediately stops YUV histogram readback and labels the histogram `RAW SENSOR • WARMING`. The first timestamp-paired RAW frame replaces the bins immediately; no shutter/burst is required.
- Leaving ZSL or a ZSL fallback immediately restores the processed YUV preview histogram.

30 fps remains hardware-dependent: Camera2 cannot deliver 30 full-resolution RAW frames per second if the sensor/HAL reports a RAW minimum frame duration slower than 33.3 ms or if exposure time itself exceeds the frame period.

Validation note: source structure and delimiter balance were checked. The Gradle compile could not run in the packaging environment because Gradle 8.9 was not cached and outbound network access is disabled.
