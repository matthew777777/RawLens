# Motorola encoded-video monitor investigation — 2026-11-13

Inputs: `logcat-20261113_142857_870 (1).txt` (1,062 lines) and
`logcat-20261113_142857_870 (2).txt` (6,275 lines), Motorola signature,
Android 17, Adreno 829, app 2.2.86. The second export extends the same session.
Mirrored logger entries repeat many events; counts below exclude duplicates.

## Findings and changes

- Eight takes saved 1,134 frames total (183, 173, 185, 102, 128, 138, 103,
  122). Every take reports zero frame drops, encoder errors, metadata misses,
  and monitor skips. Monitor presentation totals are cumulative and end at
  1,134, with zero fence-adoption or GL-presentation failures. Sampled P010
  values match the codec input exactly (`mismatches=0`).
- Successful swaps do not prove screen visibility. The new default-layer ISP
  SurfaceView can cover the default-layer encoded monitor with black while
  the RAW viewfinder already uses media-overlay ordering. Preserve the
  existing workspace fix setting the encoded monitor's media-overlay order.
  The monitor remains below application controls.
- The monitor draws a 960x540 image into a 1264x1686 surface. Bound its
  surface to a 1080-pixel long edge, preserving the layout aspect ratio:
  810x1080 on this device, about 59% fewer presentation pixels. Compositor
  scaling leaves saved video dimensions and grading untouched. This is a
  rendering-work reduction, not a measured temperature improvement.
- Fix the offer gate being published after worker enqueue: a fast draw could
  clear it before the producer set it, wedging future offers. Serialize
  pending-frame enqueue/removal during detach; retain the busy gate until
  active GL sampling and slot release finish. Reject offers immediately on
  surface loss and consume rejected post attempts.
- Recreate EGL on surface size changes, following the RAW viewfinder's
  existing driver workaround. Reset the initialization backoff for a newly
  created surface so rapid reattachment does not inherit an old retry delay.

## Other log messages

- Heat is confirmed at a mild level: thermal status 0 -> 1 at 14:48:00,
  then 1 -> 0 at 14:50:01. No severe status, GPU starvation, record backlog,
  or thermal degradation appears. No temperature or earlier-release baseline
  is supplied, so the logs cannot establish a thermal regression's cause.
- Recording starts block main for 226–556 ms. `doStartEncodedVideo` performs
  camera handoff, destination setup, and recorder initialization synchronously.
  Moving this work requires a separate lifecycle-safe startup transition;
  this patch does not change recording ownership or startup sequencing.
- The possible-leak warning at 14:46:38 is a heuristic across different
  lifecycle states. PSS after stops remains around 526–535 MB, then returns
  to 55–66 MB; native allocation returns to about 23 MB. This does not show
  an accumulating recording-resource leak. Codec input allocation grows to
  530,841,600 bytes during startup and is later reclaimed.
- One stale-session callback occurs during initial camera selection. Current
  source already serializes session creation onto the camera handler and
  guards the asynchronous configuration gap with
  `configuringSessionGeneration`, in addition to lifecycle/device checks.
- The single HAL stream-configuration warning at 14:39:53 occurs while a take
  stops; all its frames finalize and the following preview opens normally.
  Consumer abandonment, duplicate Surface disconnect, and discarded codec
  buffers accompany teardown. No failed take follows these messages.
- The image-import alignment warning falls back successfully to zero-copy
  buffer import for 4000-wide RAW. Initial missing-WB metadata uses the
  existing fallback and later settles with HAL AWB. Codec capability-query
  warnings and initial unspecified color aspects precede the successfully
  configured Main10 encoder with the requested color metadata.

## Validation

All 13 focused JVM tests pass, covering monitor dimensions, aspect-fit, color
round trips, and bounded presentation sizing. Device-test Kotlin compilation passes and includes a
new regression that places a buffered blue ISP SurfaceView underneath the
monitor, offers a red HardwareBuffer, and checks the composed screenshot
before and after resize and reattachment. This distinguishes visibility from
successful EGL swaps. No Android device is connected, so the compositor test
and thermal comparison require a device run.

`:app:assembleGithubRelease` succeeds, producing version 2.3.0 from the
current workspace (including pre-existing changes). Validation log:
`/tmp/rawlens-encoded-preview-fix-build.log`.
