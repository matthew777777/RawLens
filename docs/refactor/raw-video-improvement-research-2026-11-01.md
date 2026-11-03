# RAW video improvement research — 2026-11-01

Scope: current RawLens working tree, including uncommitted changes. Source review and external research only; no application changes or new device benchmarks. Recommendations below are engineering judgments, not measured speedups. RAW video here means the CRAW sensor recording path; processing recommendations target its preview/development/export unless explicitly stated otherwise.

## What already exists

`RawVideoRecorder.kt` already has bounded queues, pooled compressed payloads, parallel encoding, timestamp-matched capture results, capture-order commitment, slow-worker parking, PCM audio, gyro/accelerometer recording, RAW preview fan-out, storage guards, and lossless full-resolution encoding. Direct destination recording is documented in `tools/direct-recording/README.md`. CRAW already has chunk checksums, an index and a reader that scans unfinished files. Replacing these is not a priority.

The October 31 RawRec/VidUltra study remains useful, but its temporal processing suggestions largely concern Encoded Video. For CRAW, preserve full-resolution sensor samples and make development choices reversible.

## 1. Preserve more accurate calibration — first quality task

**Observed:** `CrawMetadata.container()` serializes static black and white levels. `CrawMetadata.frame()` records exposure, ISO, WB and rolling-shutter skew, but not dynamic black/white levels or the sensor noise profile. The photo path in `RawFrameMetadata.kt` already resolves dynamic levels, static fallbacks and explicit overrides. RAW video lens shading currently uses a snapshot supplied before recording, resampled for each crop, rather than a map from each matched capture result.

**Proposal:** share validated sensor-level resolution between photo and video; serialize per-frame effective black/white levels, their provenance and noise coefficients when available. Preserve camera/lens geometry and active physical camera identity. Use matched-result lens shading when available, with the existing snapshot as an explicitly identified fallback. Retain the existing guard against double correction when the HAL already applies shading.

Update CRAW consumers and DNG exporters together: an extra JSON field alone does not improve rendered output. Define channel ordering, units, crop coordinates and missing-value behavior. Do not infer a physical saturation level from the brightest subject pixel. Keep codec storage precision separate from black/white normalization; existing full-resolution encoding retries at 16 bits for samples exceeding the declared bit depth.

**Why:** Android defines dynamic black/white levels per frame and a per-CFA-channel noise model. Correct metadata can prevent normalization errors; the visible gain depends on what each device actually reports. [Camera2 CaptureResult reference](https://developer.android.com/reference/android/hardware/camera2/CaptureResult).

**Acceptance:** dark frames, gray ramps, changing ISO, hot sensor and supported DCG modes; compare photo/video normalization from equivalent captures and verify CRAW-to-DNG metadata. Confirm unsupported fields fall back without dropping otherwise valid frames.

## 2. Improve sustained cadence — first reliability task

**Observed:** the recorder exposes average/max encode, result wait, close and write times plus queue/reorder depths. Camera images remain retained through encode and capture-result pairing; GPU/analysis leases may extend their lifetime. There is no continuous general thermal/pressure governor in the inspected recorder.

**Proposal:** collect p50/p95/p99 stage latency, payload-pool occupancy, actual image lease lifetime, sensor timestamp gaps and drops by cause. Add sustained thermal observation, then a controller with hysteresis that reduces optional preview/scopes/peaking cadence before capture suffers. Offer a dim/low-cadence monitor. Trial ADPF hints for the actual worker threads, with measured work durations.

Do not assume a quick `release()` call proves the camera buffer was returned: another lease may remain. Prototype early extraction into pooled buffers only if measurements show camera-buffer starvation. This adds memory traffic, so compare against the current shared-buffer architecture. More workers and larger queues can increase memory and heat.

Android documents ImageReader acquisition limits and the need to close acquired images; its thermal and performance-hint APIs provide mechanisms, not a guarantee of sustained throughput. Handle unsupported thermal readings and platform sampling limits. [ImageReader](https://developer.android.com/reference/android/media/ImageReader), [Thermal API](https://developer.android.com/games/optimize/adpf/thermal), [Performance Hint API](https://source.android.com/docs/core/perf/performance-hint-api).

**Acceptance:** proposed 20–30 minute takes with texture, high-ISO noise, audio and the normal monitor; repeat internal-storage and USB destinations after cooling. Record actual frame gaps, memory, thermal status and stage distributions. Compare all monitor/worker policies on the same scenes and device.

## 3. Add validated 24/25 fps capture modes

**Observed:** `RawVideoRecorder.FPS` is fixed at 30; CRAW container metadata also uses that constant.

**Proposal:** make frame rate a take-level setting and validate RAW stream combinations by actual sensor timestamps. Begin with 24, 25 and 30 fps where sustainable. Change request policy, shutter budget, metadata and tests together. Add shutter-angle controls and flicker-aware shutter suggestions while retaining explicit manual overrides. Do not label a variable-rate HAL stream as constant-rate merely because its average is close.

At equal compressed bytes/frame, 24 fps requires 20% fewer payload bytes/second than 30; 25 fps requires about 16.7% fewer. Actual energy and thermal savings require measurement. A 25 fps / 1/50 s option is useful to test under 50 Hz lighting, but LEDs/PWM can still flicker.

**Acceptance:** timestamp distributions, exposure results and audio synchronization at each supported mode; test 50/60 Hz lighting. Unsupported devices should not advertise an unverified mode.

## 4. Add temporal development — largest potential low-light gain

**Proposal:** optional motion-compensated temporal denoising in CRAW playback/export, starting with conservative history blending and noise-aware rejection. Reject disocclusions and moving detail; reset history on scene cuts, geometry changes, timestamp gaps and exposure jumps. Treat CFA phases correctly if alignment operates before demosaic. Follow with temporally stable demosaic, chroma filtering and restrained sharpening.

EMVD is a relevant research direction: recurrent fusion, denoising and detail refinement with complexity designed for mobile systems. Its reported results do not establish 4K performance or quality on RawLens devices. Joint video denoising/demosaicing is another reference. [EMVD](https://arxiv.org/abs/2103.05407), [Video Joint Denoising and Demosaicing With Recurrent CNNs](https://openaccess.thecvf.com/content/WACV2023/papers/Dewil_Video_Joint_Denoising_and_Demosaicing_With_Recurrent_CNNs_WACV_2023_paper.pdf).

Start offline so quality work cannot cause master recording drops. Two full 4K RGBA16F history images alone consume about 126.6 MiB, before masks and intermediates. Evaluate classical methods before committing to a model/training/runtime dependency.

**Acceptance:** moving faces/hands, foliage, fabric, pans, cuts, lighting changes and dark gradients. Assess trails, texture retention and flicker in motion, alongside noise metrics; a clean static wall is insufficient.

## 5. Turn embedded gyro into calibrated stabilization

**Observed:** motion is recorded in Android sensor axes; per-frame exposure and rolling-shutter skew already exist in CRAW metadata. Motion is disabled when the camera lacks a compatible realtime timestamp domain. Keep that safeguard.

**Proposal:** export a documented Gyroflow-compatible motion/timeline package and calibration profile for supported camera/mode combinations. Preserve intrinsics, distortion, image orientation, crop offset, readout direction, exposure and OIS state/data where available. Validate remaining gyro/image timing offset, even when clocks share a domain. Use row-dependent transforms in development to handle rolling shutter; apply crop/warp once in final export.

Gyroflow identifies accurate synchronization as central to good stabilization and supports estimating offset from image motion. Gyro rotation alone does not solve walking translation, parallax, OIS residual motion or motion blur. [Gyroflow synchronization](https://docs.gyroflow.xyz/app/getting-started/basic-usage/synchronization), [Gyroflow source](https://github.com/gyroflow/gyroflow).

**Acceptance:** controlled rotations, fast pans, walking, nearby objects and OIS on/off. Check borders, wobble, alignment and calibration separately for each crop/mode.

## 6. Make interrupted recordings recoverable in the product

**Observed:** the CRAW reader scans footerless files, but staged-take discovery deliberately accepts finalized files only. New recording goes directly to a provider URI, so a recovery feature limited to the old staging directory would miss current recordings.

**Proposal:** track unfinished destination URIs and offer recovery to a new finalized copy, preserving the original. Verify complete chunks/checksums, rebuild the index and report recovered duration and losses. Include MediaStore pending-file lifecycle and user-selected folder permissions. Recovery is possible only for bytes the provider/drive still exposes.

**Acceptance:** process kill, truncation, disk full and USB disconnect; recovered files seek and keep audio/motion aligned. Do not claim a clean stop or automatically delete the damaged source.

## 7. Benchmark compression and clarify reduced-resolution modes

Compare current Live v2 and Fast on identical real sensor frames across ISO, detail and bit depths. Measure sample-exact round trip, compressed bytes, sustained phone encode/decode latency, memory and energy. Include low-effort lossless JPEG XL as a comparison only where the container/decoder experiment is explicitly versioned; libjxl documents effort/speed/size tradeoffs, not universal superiority. Old Live v1 host results cannot rank Live v2. [libjxl effort documentation](https://github.com/libjxl/libjxl/blob/main/doc/encode_effort.md).

For scale, 4096×3072×30 at an assumed 6 compressed bits/pixel is about 283 MB/s, or 17 GB/minute, before overhead. This is illustrative, not a RawLens measurement. Test USB sustained throughput through the actual destination provider, cable and filesystem.

The optional RGB binning path is a processed derivative: `binRgb()` demosaics/downsamples, and for input above 12 bits it first shifts to 12-bit precision, then scales the result back. The Bayer option remosaics that RGB result. Lossless compression of those results does not restore the original sensor samples. Make this distinction visible; investigate wider intermediate arithmetic if retaining more precision in reduced-resolution output matters. Test aliasing and shadow gradients before claiming a quality gain.

Keep full-resolution sensor recording as the archival option. Consider optional low-resolution proxies after cadence is proven, derived from the same timestamps with a deterministic mapping to master frames. Proxy work must fit the remaining GPU/encoder budget.

## Suggested implementation order

1. Per-frame calibration metadata and decoder/export support, alongside detailed cadence telemetry.
2. Sustained recording acceptance, reduced monitor load and measured thermal control.
3. Validated 24/25 fps modes and interrupted-take recovery.
4. Offline temporal denoising and stable development.
5. Calibrated gyro/rolling-shutter export and proxies.
6. Codec or early-extraction experiments only when the measured bottleneck justifies them.

No new tests were run for this research-only report. Existing source comments and previous benchmark reports are context, not fresh evidence of sustained recording performance.
