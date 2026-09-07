# Viewfinder startup + RAW ZSL preview fix

## Fixed

- Cold-start preview no longer depends on the phone's physical orientation. The TextureView mapping is derived from the camera sensor mount and fixed display rotation, preventing a landscape camera buffer from being stretched into a portrait 4:3 view when the app starts flat or upside-down.
- App-operated RAW ZSL now keeps `TEMPLATE_PREVIEW` as the repeating request template while adding the RAW `ImageReader` as a second target. Some Camera2 HALs stall/freeze the SurfaceTexture preview when `TEMPLATE_ZERO_SHUTTER_LAG` is used for a continuous full-resolution RAW stream.
- The requested AE FPS range is no longer forced when the full-resolution RAW stream cannot satisfy the lower bound of that advertised range. This avoids a second vendor-HAL stall path.

## Important architecture detail

The on-screen TextureView is still the camera's normal processed preview target. RAW ZSL adds a simultaneous full-resolution RAW_SENSOR stream for the ring buffer and RAW histogram. It does not demosaic every RAW frame into the TextureView. A true RAW-rendered live view would require a dedicated realtime RAW renderer/surface and is a separate feature from ZSL capture.

## Verification

- `git diff --check` passes.
- Gradle compilation cannot run in this isolated environment because Gradle 8.9 is not cached and the wrapper cannot reach services.gradle.org.
