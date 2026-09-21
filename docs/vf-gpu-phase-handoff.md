# RawLens: SurfaceView + zero-copy GPU viewfinder phase

Paste the prompt below into a new session to continue this work.

---

# RawLens: SurfaceView + zero-copy GPU viewfinder phase

## Project

Android camera app at `/Users/monikamalinowska/AndroidStudioProjects/RawLens`
(package `com.matthew.rawlens`, minSdk 29, compileSdk 35). App module: `:app`.
Build: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`.
ADB at `~/Library/Android/sdk/platform-tools/adb`. Test device (usually attached):
Xiaomi 25080RABDG, Android 16, MediaTek MT6878, Mali-G615 MC2
(supports `GL_OES_EGL_image`).

## Current state (all verified on-device, everything builds green)

The RAW viewfinder works but is CPU-bound: sampling a 4080x3060 sensor to a
680x510 preview costs **149.5 ms/frame → 3.0 FPS** (VF debug overlay).
Goal: true 30 Hz.

Already landed and must not regress:

- `RawPreviewSampler.kt` — bulk-`ShortBuffer` fast path + reciprocal LUT,
  16 ms throttle floor.
- `RawViewfinder.kt` — cached GL uniform/attrib locations,
  `glTexSubImage2D` reuse, selectable resolution (480/640/960 via
  `setTargetLongEdge`), per-frame WYSIWYG snapshot (jpeg flag + 7 AgX params).
- WYSIWYG tonemap contract (`VfPreviewMode.kt`): `DNG_ONLY` → raw-clean
  Reinhard; `JPEG`/`JPEG_DNG` → cheap scene-referred AgX-lite fragment shader
  tracking `JpegOutputSettings` (contrast, saturation, purity/outset, hue,
  shadow/highlight EV, gamut) + sRGB OETF. **Never ISP YUV.**
- `RawCameraController` keeps the RAW repeating target attached in BOTH modes
  (`useRawViewfinder` no longer gated on preview mode);
  `pushVfRenderState()` pushes tonemap on format/mode change;
  `setJpegOutputSettings` live-pushes AgX to the VF. Mode switches are
  uniform flips, no session rebuild.
- System preview (`AutoFitTextureView`, `activity_main.xml`) is a stream
  target only: **VISIBLE + alpha=0**. CRITICAL LESSON: `INVISIBLE`
  suppresses `SurfaceTexture` creation on this HAL → camera never opens
  (zero camera clients, UI frozen at layout defaults). Never use
  INVISIBLE/GONE on that view; `positionViewfinderOverlays` re-enforces
  alpha 0.
- Startup path is fully instrumented: every state
  (`WAITING FOR VIEWFINDER/LAYOUT`, `NO SENSOR DATA`, `SESSION ERROR`,
  `Session ready (zsl=… vf=…)`) goes to both status UI and logcat
  (`RawLensCamera`/`RawViewfinder` tags).
- `RAW|JPG` toggle chip (`vfPreviewButton`) + resolution setting in
  Settings; `VfPreviewModeTest` locks the FOLLOW mapping.

## Your task — GPU phase (GLES31-first, no Vulkan yet)

Replace the CPU copy (`RawPreviewSampler.copy` becomes fallback only) with:

1. **Zero-copy upload**: `Image.getHardwareBuffer()` →
   `EGL_ANDROID_hardware_buffer` / `glEGLImageTargetTexture2DOES` → `R16UI`
   texture. Keep the ZSL ring CPU path intact; VF must not retain `Image`s.
2. **Compute superpixel**: new `vf_unpack` + superpixel compute shader
   (`2x2 Bayer → R,(G1+G2)/2,B`), specialized (no hot-loop branches) for
   bit-depth/white-level, CFA pattern, crop-origin shift. Follow the
   existing pattern of `Gles31AmazeProcessor` +
   `assets/shaders/raw/preprocess.glsl`.
3. **Lens shading on GPU**: change the save-path gains upload from `RGBA32F`
   to `RGBA16F` + `LINEAR` filtering, replace manual bilinear in
   `preprocess.glsl:34-56` with one `texture()` fetch; share the builder
   with the VF so VF==still color.
4. **2 frames in flight**: `RawViewfinder` currently blocks on
   `eglSwapBuffers` with 3 CPU buffers + 1 pending. Add fence-based
   pipelining (2–3 GPU slots, lazy alloc, reuse) so the CPU never waits;
   keep `invalidateSession`/watchdog/`snapshot()` semantics.
   `eglSwapInterval(0)`-style low-latency present if trivially available.
5. **SurfaceView migration**: move `RawViewfinder` from `TextureView` to
   `SurfaceView` for true present control. Constraints: overlay sync
   (`syncGuideOverlayToViewfinder`, metering overlay) currently reads the
   view rect — preserve geometry; keep lifecycle handling (surface
   create/destroy → `releaseGl`, session invalidation);
   rotation/mirroring via existing `RawPreviewGeometry.sensorPoint`.
6. Keep `mediump` for CCM/gamma/AgX-lite (Mali-friendly), `highp` only for
   unpack/normalize.

## Constraints

- WYSIWYG contract is inviolable: same WB/CCM, JPEG VF tracks all 7 AgX
  sliders live, never ISP YUV, per-frame snapshots (no tearing on settings
  change).
- System preview stays VISIBLE + alpha 0 (see lesson above).
- Every new failure mode needs both a status string and a logcat line
  (startup silence cost us a full debugging session).
- Fallbacks: CPU sampler stays for `pixelStride != 2` and devices where
  HW-buffer import fails; MediaTek gralloc quirks are real (see
  `relieveZslReaderPressureIfNeeded`, overflow-storm handling).

## Verification (device attached via ADB)

1. `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`
   must be green.
2. `adb install -r` both APKs, grant CAMERA,
   `am start -n com.matthew.rawlens/.MainActivity`, screenshot
   (`exec-out screencap -p`) + `logcat -d | grep -E "RawLensCamera|RawViewfinder"`:
   expect `Session ready (zsl=… vf=true)`, no `RAW preview recovery`
   warnings, VF overlay showing rising FPS and falling ms vs the
   3.0 FPS / 149.5 ms baseline.
3. `dumpsys media.camera` → active client present while app is foreground.
4. Soak: `adb shell am instrument -w -e class com.matthew.rawlens.ZslCaptureSoakTest ...`
   per `docs/zsl-raw-viewfinder-verification.md` (lens setup must already be
   complete in-app); repeat for `hybrid false`, formats `JPEG`/`JPEG_DNG`/
   `DNG_ONLY`.
5. Watch `logcat` for `VF uniforms missing` / shader compile failures
   (failure mode = black VF).

## Step 2 outcome (2026-09-20, verified on Xiaomi 25080RABDG / MT6878)

Zero-copy EGL import is **infeasible on this HAL** (hard evidence):

- `ImageReader` (4-arg, CPU usage) buffers: `eglCreateImageKHR`
  (`EGL_NATIVE_BUFFER_ANDROID`) fails with `EGL_BAD_ALLOC (0x3003)`.
  `eglGetNativeClientBufferANDROID` succeeds; strides are packed
  (`pixelStride=2 rowStride=8160 w=4080 h=3060`); ESSL 3.00 Bayer program
  compiles/links (`VF GPU program ready`).
- Adding `USAGE_GPU_SAMPLED_IMAGE` to the RAW reader (5-arg overload)
  **starves the reader completely**: session configures, zero RAW images
  delivered, ZSL + VF + RAW histogram all dead. Reverted; see comment at the
  `ImageReader.newInstance` call site in `RawCameraController`.

Landed instead (all green, stream healthy):

- `VfEglImport` + `app/src/main/cpp/vf_egl_jni.cpp` (lib `rawLensVfEgl`):
  minimal NDK bridge for the hidden EGL import entry points. Fails soft.
- `VfGpuImport` (pure, unit-tested by `VfGpuImportTest`): eligibility rules,
  quad-offset mapping, CPU/GPU fragment shaders composed from shared pieces
  (WYSIWYG parity by construction).
- `RawViewfinder`: `offer()` tries GPU first, falls back to CPU per frame
  (`VF CPU fallback: <reason>`), sticky after 3 consecutive import failures
  (`sticking to CPU copy for session`), re-probed each session. Debug overlay
  shows `GPU`/`CPU` path. No `Image` is ever retained (only a short-lived
  `HardwareBuffer` ref); ImageReader slot accounting untouched.
- Fallback behavior verified on-device: 3× `egl-import` probe → sticky CPU →
  live VF at CPU-bound FPS with `CPU` badge. The GPU path auto-enables on any
  HAL where the import succeeds — no code change needed.

Open decision for 30 Hz: with direct EGL import blocked, the remaining options are
(a) full-frame PBO/`glTexImage2D` upload + GPU downsample (upload-bound, not
zero-copy), (b) NEON-vectorized CPU sampler via JNI, or (c) accept ~3 FPS CPU.
Needs user call before Step 3.

## Vulkan probe outcome (2026-09-20, same device) — ZERO-COPY VIABLE

`vf_vulkan_probe.cpp` (one-shot, first GPU frame per session) proves Vulkan
imports the exact HAL buffers EGL rejects:

- `buffer 4080x3060 layers=1 format=32 usage=131123 stride=4080`
  (format 32 = `HAL_PIXEL_FORMAT_RAW16`; usage = CPU_READ + MTK vendor bit,
  **no** `GPU_SAMPLED_IMAGE` — Vulkan does not need it, EGL does)
- `device Mali-G615 MC2 api=1.3.247`, `ext external-memory=1 ahb=1`
- `allocationSize=25067520 memoryTypeBits=0x2 vkFormat=74 ...`
  (`vkFormat=74` = `VK_FORMAT_R16_UINT`: driver maps RAW16 AHB directly,
  no `externalFormat` path needed)
- `IMPORT+BIND OK tiling=OPTIMAL` → `VF vulkan probe: OK:vkformat:OPTIMAL`

Reference flow (ncnn AHB doc, verified on Mali + the Vulkan spec): query
`VkAndroidHardwareBufferFormatPropertiesANDROID` via
`vkGetAndroidHardwareBufferPropertiesANDROID`, create the image with the
reported `VkFormat` (or `VK_FORMAT_UNDEFINED` + `VkExternalFormatANDROID`),
dedicated-allocate with `VkImportAndroidHardwareBufferInfoANDROID`, bind.
Cache allocator+pipeline by AHB pointer (reader cycles a small pool; per-frame
pipeline create costs ~24 ms). NDK note: the entry point is
`vkGetAndroidHardwareBufferPropertiesANDROID` (no `Memory` infix).

MotionCam (`EkinStrop/motioncam`, single `main` branch) contains NO AHB
import: its `VulkanRawPreview` does full-frame `memcpy` into a coherent mapped
`VkBuffer` + compute + swapchain present. Still a useful shape reference for
the compute/present side, but our import works where a memcpy fallback would be.

Implied architecture: Vulkan owns import + superpixel/tonemap compute;
present via Vulkan swapchain on the `SurfaceView`, or export via
`vkGetMemoryAndroidHardwareBufferANDROID` into an app-allocated AHB that GL
imports (proven: own-buffer EGL import works).

## Vulkan VF landed (2026-09-20) — TRUE 30 Hz ZERO-COPY

Result: **29.8–30.5 FPS / 5–6 ms** (from 3.0 FPS / 149.5 ms), `GPU` badge,
correct scene on 1× (4080×3060) and 0.7× (3264×2448), JPG toggle + lens
switch live, no recovery/uniform warnings, ZSL ring untouched.

Architecture (as built): Vulkan compute (import HAL RAW as **storage buffer**
→ `vf_superpixel.comp` → RGBA8 export AHB) + existing GL present (EGL-import
the export buffer → unchanged tonemap shader → SurfaceView). `vkQueueWaitIdle`
per frame for cross-API correctness (timeline-semaphore export = follow-up).

Critical lessons (each cost a debugging round, all verified on-device):

1. Image imports of HAL buffers sample SKEWED even when bind succeeds:
   driver reports `rowPitch=8192 (4096 px)` for a packed 4080-px buffer
   (allocationSize exactly fits 4096×3060×2; CPU plane says 8160). Both
   OPTIMAL and LINEAR tilings stripe. Fix: import input as
   `VK_BUFFER_USAGE_STORAGE_BUFFER` with explicit `(y*pitch+x)` addressing,
   pitch from the Image plane (the CPU path's ground truth).
2. Output chain proven by a gradient-grid shader swap before touching input.
3. Export AHB usage: `SAMPLED_IMAGE | DATA_BUFFER | COLOR_OUTPUT` imports as
   Vulkan STORAGE and re-imports to EGL cleanly.
4. Push-constant layout is triple-locked: `.comp` source comment,
   `static_assert(sizeof==72)` in C++, `VfVulkanTest` packing test.
5. SPIR-V is precompiled (`glslangValidator -V --target-env vulkan1.1`,
   Vulkan 1.1 for minSdk 29) and shipped in `assets/shaders/vf/` with the
   `.comp` source + regen command in its header. glslang 16.6.0 via Homebrew.

Files: `vf_vulkan_vf.cpp` (device/import/compute), `VfVulkan.kt` (bridge +
pure packing), `vf_superpixel.comp/.spv`, `RawViewfinder` tiers
(Vulkan → EGL-direct → CPU), `VfVulkanTest`. Fallback chain per frame with
per-tier session stickiness; overlay `GPU`/`CPU` badge is the status string.
