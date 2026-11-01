# RawRec and VidUltra source study

Reviewed 2026-10-31 against the current, modified RawLens working tree. This is a source inspection, not a build, device benchmark, or proof of image quality. No application code was changed.

## Reproducible sources

- RawRec: https://github.com/Not-to-Shabby/RawRec/tree/45ac16a3a878810368dd10f75c2b4eb12a3acee0
  - Local clone: `/tmp/rawlens-review-RawRec`.
  - Root LICENSE: Apache-2.0.
- VidUltra: https://github.com/Pixelgamer4k/VidUltra/tree/25674ef4f647d783c8db712e2adc0606659141e9
  - Local clone: `/tmp/rawlens-review-VidUltra`.
  - Root LICENSE: GPL-3.0.
- RawLens source files carry GPL-3.0-or-later identifiers. Any actual reuse should retain the applicable source notices and attribution; separately check bundled dependencies. No upstream code is copied in this report.

RawLens's existing `docs/refactor/video-study.md` already studied this exact RawRec revision. Several proposals there have since landed; the current implementation takes precedence over that document's old open-item list.

## 1. RawRec, step by step

Paths below are relative to RawRec's `app/src/main/java/dev/rawrec/` unless specified otherwise.

1. **Discover and configure:** `app/probe/CameraCatalog.kt`, `ModeProbe.kt`, `app/profiles/SocOptimizer.kt`, and `app/capture/RawCaptureEngine.kt` choose a RAW stream and build capture requests. Device-family tuning supplies queue sizes and worker counts. These are heuristics, not evidence that a mode sustains its requested rate.
2. **Acquire and pair:** `RawCaptureEngine.kt` drains images with `acquireNextImage` and pairs result metadata by timestamp. `RecordingController.kt` accepts image ownership into a bounded queue. Keeping this callback short avoids starving Camera2.
3. **Extract early:** a dedicated extractor packs expanded sensor values into MIPI RAW10/12/14 bytes, optionally crops, then closes the camera image. Native packers in `app/src/main/cpp/rawrec_jni.cpp` include NEON and split-row work. Recycled packed buffers decouple camera-buffer lifetime from compression latency.
4. **Compress:** workers use persistent Zstd contexts. `EntropyGovernor.kt` observes queue occupancy and remaining buffers, switches to faster compression under pressure, and requires sustained relief before easing back. The recording controller separately gates active workers on thermal escalation.
5. **Write:** workers put completed payloads into a shared output queue; one writer interleaves video and PCM audio. `app/container/RvspWriter.kt` writes record headers, payload CRC32, and a final index trailer. Its streaming reader can return complete records before a truncated tail.
6. **Manage the take:** a free-space check refuses starts below 1 GB or at 98% usage; a rolling check stops below 500 MB or at 98%. `CaptureForegroundService.kt` provides recording service/wake-lock machinery. `PerfSession.kt` uses Android performance hints, plus Xiaomi-specific broadcasts. `ThermalGovernor.kt` combines platform status with predicted thermal headroom.
7. **Develop later:** `tool/Rvtool.kt`, `DngOpcodes.kt`, `ColorScience.kt`, and the GPU backends support conversion, inspection, and desktop playback. `app/proxy/ProxyEncoder.kt` provides software MJPEG proxy recording rather than assuming a second hardware encoder works alongside RAW capture.

### Useful ideas for RawLens

| Idea | Current RawLens | Recommendation |
|---|---|---|
| Bounded queues, pooled payloads, parallel encoding | Already in `RawVideoRecorder.kt` | Keep; expose queue/pool pressure clearly in diagnostics. |
| Early release of Camera2 images | Images remain leased through encode and possibly preview GPU use | Measure image lifetime; prototype extraction only if retained images are the bottleneck. A new copy/packing stage adds memory traffic. |
| Pressure controller with hysteresis | Drop counters, bounded queues, slow-worker parking; no equivalent general pressure governor found | Add bottleneck-aware control, beginning with optional preview/scopes work. Never change sample precision to preserve cadence. |
| Thermal prediction and ADPF | Encoded Video checks heat at take setup; no equivalent continuous RAW controller or hint session found | Trial platform thermal listeners and ADPF with measured stage duration and thread ownership. Keep geometry constant during a take. |
| CRC, index, truncated-tail reading | Already implemented by CRAW writer/reader | Do not replace CRAW with RVSP for these features. |
| Low-storage stop | Already implemented in current `RawVideoRecorder.kt` | Retain; old video-study V4 is stale. |
| Crash recovery UI | CRAW reader scans unfinalized files; `RawVideoSaver.stagedTakes` deliberately excludes footerless files | Add explicit recovery into a new finalized copy, followed by normal publication. Preserve the damaged original. |
| Low-power recording monitor | RAW preview fan-out exists | Offer monitor sleep/reduced cadence while preserving capture and encoding; tap wakes the viewfinder. |
| Proxy/review workflow | Separate CRAW and Encoded Video recorders exist | Explore low-resolution proxies derived from the same frame timeline, after resource testing. Do not run two independent camera sessions. |
| Background recording | Activity currently stops cleanly on pause | A foreground recording service is a separate product feature, requiring lifecycle ownership changes; useful if background/screen-off recording is wanted. |

### RawRec code to avoid copying unchanged

- **Critical-pressure raw fallback:** `RecordingController.kt` around lines 714–721 assigns `rawPayload` as the output, then immediately returns that same array to `payloadPool`. A later extraction can overwrite it before disk writing. The file header also continues to declare ZSTD while the record receives uncompressed bytes; the inspected codec interface has no per-record override. This is an apparent corruption path under exactly the pressure the feature is meant to handle.
- **Completion order:** compression workers enqueue results as they finish, and the writer writes directly from that queue. There is no capture-sequence reorder stage in the inspected path. File record sequence numbers are assigned at write time. RawLens already has explicit capture-order commitment and late-result handling; preserve it.
- **Zero-drop claims:** each bounded stage can evict work, so an adaptive compression policy cannot guarantee zero dropped frames. Lower compression can also worsen a storage bottleneck by increasing output bandwidth.
- **Thermal headroom:** the poller runs every three seconds and treats negative values as unsupported; unsupported NaN is not explicitly handled. Validate platform sampling requirements and unsupported readings before adapting this design. Do not inherit its repeated polling unchanged.
- **Page-cache advice:** `POSIX_FADV_DONTNEED` is advisory, not a durability guarantee or a reliable way to discard dirty pages. The call also follows writes through a buffered stream. Measure writeback and memory pressure before adding it; avoid flushing every frame simply to imitate this code.
- **Float audio marketing:** accepting PCM_FLOAT does not establish that the microphone/ADC can recover overload. Choose float for processing convenience where useful, not on the README's promise of effectively unlimited unclipped capture.

## 2. VidUltra, step by step

Paths below are relative to VidUltra's `app/src/main/java/dev/vidultra/proto/`.

1. **Negotiate the device:** `DeviceSetup.java` chooses RAW format/size, output crop, frame rate, a small ISP YUV side stream, and encoder. The side stream helps obtain AWB/lens-shading metadata and feeds visual motion estimation.
2. **Capture and upload:** `Engine.java` acquires RAW, associates results, detects packed layouts, and uploads a sensor rectangle via `glTexSubImage2D`. This is CPU-addressed texture upload, not the same architecture as RawLens's imported hardware-buffer Vulkan path.
3. **Meter and control exposure:** a small RAW sample grid drives `AeController.java`. The controller combines midtone and highlight targets, gyro-dependent dead bands, a critically damped exposure spring, asymmetric brightening/darkening speeds, flicker-aware shutter allocation, and a frame-timestamped shader trim to compensate sensor request lag.
4. **Develop the RAW:** `Shaders.java` performs unpacking, normalization, WB, MHC demosaic, highlight handling, calibration/gamut transforms, and output transfer functions. Lens shading is updated from results. The normal Rec.709 look includes creative tone/color decisions; these should remain separate from a neutral log transform.
5. **Plan stabilization:** `Gyro.java`, `Stabilizer.java`, and `VisionOis.java` combine quaternion orientation, rolling-shutter timing, lens geometry, crop-margin planning, and image-derived residual motion. Video frames wait about 0.6 seconds for future motion while the preview remains responsive.
6. **Reduce noise:** `NoiseReducer.java` warps the current frame into output space, aligns history, tests motion against predicted noise, maintains a recent-motion mask, accumulates temporal history, and filters chroma at reduced resolution. HAL noise estimates are adjusted by measured scene noise. Full-resolution history uses floating-point buffers.
7. **Encode and mux:** an EGL recordable surface feeds HEVC Main10 or an 8-bit fallback. Presentation times derive from sensor timestamps. `AudioEncoder.java` and `Muxer.java` combine audio/video; `DiagLog.java` prepares support diagnostics.

### Best improvements for RawLens

**A. Motion-aware temporal denoise — largest potential image-quality addition.**

RawLens has photo denoisers, but the inspected Encoded Video path has no equivalent video history/motion-mask stage. Implement an optional Vulkan video stage before final transfer/YUV conversion: normalize into a consistent linear working space, reproject history, reject moving/disoccluded pixels, clamp history, and filter chroma conservatively. Scale noise thresholds with sensor noise, exposure, WB, and shading. Reset history on camera/crop changes, large exposure discontinuities, and scene cuts. Keep an off mode.

Begin with a two-buffer history and conservative motion rejection. VidUltra's block-search motion compensation is explicitly experimental and **off by default** because inaccurate vectors smeared detail; do not present it as a proven feature to copy. Its default history length adapts between four and eight frames and retains some current-frame grain. Those values are tuning references, not universal settings.

At 3840×2160, two RGBA16F history textures alone occupy about 126.6 MiB; current-frame, variance, masks, and other intermediates add more. Prototype at 1080p, then measure 4K GPU time, bandwidth, temperature, and detail retention. Faces, moving hands, foliage, fine fabric, pans, and abrupt cuts are more meaningful checks than a static dark wall alone.

**B. Rolling-shutter-aware stabilization — clearest gap in our current implementation.**

`StabTrajectory.kt` explicitly ignores rolling shutter and outputs one affine-like matrix per frame. Upgrade the sidecar to preserve per-frame exposure time and rolling-shutter skew, calibration/intrinsics, and distortion when available. Plan quaternion-based transforms at multiple row times, then interpolate in the warp shader. Use the exposure midpoint for each row and respect sensor/crop coordinate mapping.

Start in the existing post-record stabilization path: full-take future motion is already available, and this avoids retaining a large RAW look-ahead queue. That improves geometry but still incurs the existing decode/re-encode pass. Later, a single-pass recording implementation could avoid that extra generation if memory and sustained GPU performance permit.

**C. Visual residual motion / OIS compensation — valuable after timing is correct.**

Gyros measure rotation, not walking translation or an independently moving OIS lens. VidUltra phase-correlates horizontal strips and subtracts gyro-predicted motion to estimate the residual. Apply a confidence-gated version to a small image stream or a downsampled GPU image. Reject low texture, blur, parallax disagreement, and moving-subject dominance. Calibrate gyro timing and lens behavior before enabling it by default. Do not transfer its OIS gains or delay constants across phones.

**D. Smooth video auto exposure — strong usability improvement.**

The current shared video policy supports HAL auto exposure, manual settings, and locks. Add an optional smooth-auto controller using existing RAW metering infrastructure: EV-space smoothing, highlight guard, asymmetric response, dead band/hold, and anti-flicker shutter choices. Preserve requested manual shutter/ISO and locks. Timestamp any residual digital gain against the actual capture result, not the latest request. Keep adaptive shadow lift/local tone mapping in an explicit display/creative look; avoid silently baking it into neutral log.

**E. Supersampled downscaling and output validation.**

VidUltra averages multiple spatial samples in linear light for smaller outputs. RawLens already has MHC/RCD and superpixel paths; compare thin diagonal lines and foliage before assuming its demosaic is better. Add a quality downscale option where our selected path undersamples. Keep CFA, black levels, white balance, crop, gamut, and transfer identical in comparisons.

**F. Encoder surface path — benchmark candidate, not a replacement plan.**

An RGBA1010102 EGL surface may reduce explicit P010 CPU copying on suitable devices. RawLens already supports direct P010, ImageWriter, and a GPU-written writer-buffer route in selected cases. Compare against each actual selected path. Validate decoded bit depth, range, grayscale ramp, chroma, transfer tags, timestamps, and sustained cadence before calling any new surface route correct or faster. Keep the existing vendor layout validation.

### VidUltra limitations relevant to adoption

- A 0.6 s look-ahead holds roughly 18 RAW frames at 30 fps. At 4096×3072 expanded RAW16, those images alone are about 432 MiB, before extra camera buffers or denoise textures. Device queue limits matter as much as RAM.
- Its Snapdragon-only README is a support policy for its implementation, not evidence that RAW-developed encoded video is impossible on other vendors. Keep RawLens's capability and device-probe approach.
- `looksPacked10` and the packed-as-RAW16 upload branch assume a particular mislabelled layout. RawLens's current stride/layout work should remain authoritative; do not replace it with a global heuristic.
- `Muxer.java` starts only after both tracks arrive and accumulates an unbounded pending-sample list beforehand. Reuse the idea of track coordination, not this failure behavior; audio-unavailable paths and bounds need explicit handling.
- Matching a named log curve does not make a phone sensor equivalent to the named cinema camera. Audit curve constants, input normalization, gamut, and metadata separately. More profile names are a lower priority than verified output.

## 3. Recommended implementation order and acceptance evidence

| Order | Work | Acceptance evidence |
|---|---|---|
| 1 | Recording pressure telemetry, continuous thermal observation, optional low-power monitor | Long takes with bright detail/high-ISO noise; frame gaps, image lifetime, queue/pool depth, encode/write/GPU percentiles, memory and thermal history. No changed bit depth or geometry. |
| 2 | Conservative optional temporal video NR | Moving subjects and texture retain detail without trails; stable exposure/cut resets; sustained target cadence within memory budget. |
| 3 | Versioned stabilization metadata and row-aware post warp | Fast pans and walking reduce skew/wobble; correct orientation/crop; no exposed borders; unsupported metadata falls back predictably. |
| 4 | Smooth video AE and flicker-aware shutter policy | Dark-to-bright/bright-to-dark transitions, backlit subject, AE lock/manual overrides, 50/60 Hz lighting. Verify actual sensor values and rendered brightness over time. |
| 5 | Visual residual/OIS correction | Static distant scene, walking, nearby parallax, moving subject, low texture; confidence rejection prevents false corrections. |
| 6 | CRAW recovery UI and optional proxy workflow | Process-kill/truncated-file fixtures; recovered copy opens and seeks, audio/motion remain aligned, original preserved. Proxy must not reduce master cadence. |

ADPF, an EGL encoder route, and early extraction should be isolated measured experiments. Neither repository proves a speedup on RawLens's supported devices. The main architectural recommendation is to retain CRAW and the Vulkan capture/develop path, then add selected processing and recording-control improvements around them.
