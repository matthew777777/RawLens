# Xiaomi 14 Ultra DCG16 comparison

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Source: user-provided `Xiaomi 14 Ultra Full Settings (1).zip`, MCPro lens profile exports. These are test configurations, not hardware-verified RawLens fixes.

Both f/1.6 profiles select route `0/2`, RAW16, 4096×2304, operating mode 0, and vendor priority mode in MCPro. RawLens has no corresponding vendor-priority switch; copying tags does not establish equivalent backend behavior.

- `24fps-vendor-keys.json`: profile named “24mm DCG16🌚 24fps f1.6”; no sensor current_mode override.
- `60fps-vendor-keys.json`: profile named “24mm DCG16🌚 60fps f1.6”; request tag `org.codeaurora.qcamera3.sensor_meta_data.current_mode`, Int32, 23.

Both send request-scoped clientName bytes decoding to `com.android.camera` plus NUL, xiaomi.app.module Int32[] 167, com.xiaomi.lens.aperture Float[] 1.63, and com.xiaomi.lens.apertureMode Int32[] 1. Scope and types are preserved exactly, including clientName being request scoped despite its name. Profile names are not proof of actual sensor frame rate or dynamic range.

To reproduce: select route 0/2, choose RAW_SENSOR (RAW16) and RAW size 4096x2304, paste one JSON into the vendor-key Import keys dialog, then reopen the camera. Import merges and preserves existing keys: clear conflicting keys first. When switching from the 60fps profile to 24fps, remove current_mode explicitly; the 24fps import does not delete it. The updated 60fps file also imports `rawFps: 60`, supported by the current RawLens source. Check “RAW preview frame rate” reads 60 FPS after import: merge preserves an existing nonzero FPS preference. The older file omitted this field and left RAW preview at its default 30 FPS. The source profile named 24fps actually stores startup frameRate 25; its filename alone must not be treated as proof of cadence. The 24fps JSON continues to import vendor keys only.

RawLens previously omitted intermediate RAW sizes from its session ladder when they were neither the largest/smallest nor at most half the largest area. In that case an advertised 4096x2304 preference was silently ineffective. The ladder now includes intermediate RAW-only configurations with full and reduced reader depths. This addresses size selection, not proof of the reported camera failure's cause. It cannot expose sizes absent from the live stream map.

The archive contains no RawLens camera failure log. Remaining verification needs the affected phone: record the selected/open/physical route, stream dimensions, applied/skipped vendor keys, session callback and CameraDevice error, then check RAW frame delivery and saved photo/video. The attached ADB phone is model 25080RABDG (lapis), not a Xiaomi 14 Ultra. No Rawr source was inspected or changed.


## Field logs, 2026-10-31

Source: `logcat-20261031_104233_732 (1).txt` and `(2).txt`, supplied by the user. The first file is an exact prefix of the second, so these are overlapping exports of the same run, not independent trials. Device header: Xiaomi 24030PN60G, Android 14/API 34.

- Every RAW session in these exports uses 4096x3072 and a requested [30,30] FPS range. No session or first RAW image confirms 4096x2304. EncodedVideo's size listing includes 4096x2304, proving advertisement, not selection.
- At 10:50:17.620 the applied request-key list starts including current_mode. Subsequent camera-0 opens deliver no first RAW image and report device error 4. The user's supplied profile identifies the value as 23; the old log records key names, not values.
- At 10:51:37.594 current_mode disappears from the applied list; the first RAW image follows at 10:51:38.244. Its size remains 4096x3072. This supports a failing sensor-mode/request configuration, not a Vulkan-only display failure. It does not establish whether mode 23 works at its intended size and 60 FPS on the newer build.
- RAW viewfinder metadata reports black 1024 and white 16383 on the working streams. RAW_SENSOR uses a 16-bit storage container; container width and a profile name cannot confirm dual conversion gain operation. Do not force white to 65535 just to label the stream DCG16.

Two additional code fixes follow this evidence: normalize the discovery picker's multiplication sign (×) to ASCII x before storing/reading sizes, and retain failed-route recovery budgets when a different fallback route delivers frames. Previously the picker discarded every displayed size as invalid and frames from camera 2 re-enabled retries of broken camera 0.

Next test: install a build containing these fixes, select RAW_SENSOR and 4096×2304, close/reopen the picker and verify the selected radio item persists. First test without current_mode; log the actual configured size and delivered frames. Then test the 60fps profile with current_mode 23 and RAW preview frame rate explicitly 60, reopening after changes. Export a separate log for each test and provide an original DNG from a working run. If the advertised 60 FPS range is absent, record that rather than treating a lower rate as equivalent. These steps test the intended configuration; they do not promise DCG16 activation.


## Follow-up shutter and video report, 2026-10-31 19:29

Source: `logcat-20261031_192923_757 (1).txt`, Xiaomi 24030PN60G/API 34.

4096x2304 now appears both in session configuration and delivered RAW images. At 19:29:41.853 the selected crop is `RAW ONLY MIN`, maxImages=8. The ordinary save admission reserved ten preview/pairing slots, so even an idle single-photo request could never pass. This explains the disabled photo shutter without a submitted still capture. The preferred-size ordering now prepends a full-reader RAW-only attempt when necessary, preserving the minimal-reader fallback. Minimal readers with at least four slots admit one forward still/save at a time, retaining all other slots for preview; multi-input bursts and ZSL ring admission remain bounded.

A RAW-video attempt did start at 19:31:34 (audio started and RawVideoRecorder reported capture results). All workers then logged `frame encode failed: Attempt to invoke virtual method 'java.lang.Class java.lang.Object.getClass()' on a null object reference`. No stack trace or failing stage was included, so this report cannot identify the exact call or establish a fix for video encoding. RAW recording also switched to a 4096x3072 stream, separate from the selected 4096x2304 photo-preview session. The recorder now records the first full exception stack with stage/sequence/timestamp and reports a recording error after ten consecutive failures rather than silently dropping every frame. This is diagnostics and failure handling, not a verified repair of the Xiaomi exception.

The 60 FPS selection logs `range=null`. Steady preview telemetry still reports approximately 30 rendered FPS and a 33ms camera interval. Neither the selected setting nor the 16.64ms advertised minimum frame duration proves 60 FPS delivery or DCG16. Working crop frames still report black=1024 and white=16383.

Validation: 27 SessionStreamCombo tests and four CaptureQueueCapacity tests passed. No ADB devices were connected for a physical shutter/video test. Retest an ordinary single photo first; if RAW video still fails, export the new log containing `frame failed at ...` and its full stack trace. Do not treat the video failure-handling change as evidence that video encoding now works.
