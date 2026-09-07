# DNG native-memory growth fix

## Root cause

When a RAW_SENSOR plane had padded row stride, `NativeDngWriter.contiguousRawBuffer()` allocated a new
direct `ByteBuffer` of `width * height * 2` bytes for every DNG. At 4080x3060 this is about 23.8 MiB
per capture. Direct buffers live in native memory and are not deterministically reclaimed at the end
of the save, so repeated captures produced the near-linear `nativeAlloc` growth seen in Logcat while
GL texture accounting remained stable.

## Fix

- Reuse one direct DNG row-unpadding scratch buffer in `NativeDngWriter`.
- Grow it only if a later sensor requires a larger RAW frame.
- Synchronize `write()` so the shared buffer is never reused concurrently.
- Keep the zero-copy path unchanged when the camera already provides tightly packed RAW rows.
- Do not change RAW pixels, DNG metadata, AMaZE, JPEG output, or orientation behavior.

For a 4080x3060 RAW stream, repeated padded-row DNG saves now retain one ~23.8 MiB staging buffer
instead of allocating another ~23.8 MiB direct buffer on every capture.
