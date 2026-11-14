# Motorola Signature shutter/recovery report

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Read all 3,134 lines of `logcat-20261101_170058_392 (1).txt`.
Device motorola signature, SDK 37. This export predates the new SENSOR_MODE /
SENSOR_FPS / AF_TRIGGER diagnostics. Capture format in this export is JPEG,
not the DNG/HDR+ state pictured in the accompanying screenshot.

Observed sequence:

- ID 2 advertises and selects [60,60]. Initial sessions deliver RAW and a
  five-frame ZSL ring. Unlike the Xiaomi report, 60 is not rejected as unadvertised.
- Changing the current_mode key to Request on a running preview yields a
  stable frame stream. Repeating request keys include the named vendor key.
- Device errors already occur at 17:02:10, before the sole logged shutter.
- At 17:03:15.197 the shutter consumes five buffered RAW frames. There is no
  logged forward still request that explains a change of frame rate here.
- JPEGs F00..F04 all save successfully, ending at 17:03:20.527. This is not
  the previous Xiaomi DngCreator serialization failure.
- At 17:03:18 the activity backgrounds, camera closes and surface is lost.
  The export does not identify what caused this lifecycle transition.
- At 17:03:22 the resumed session immediately carries the saved REQUEST key.
  It configures then reports camera-device error 4 at 17:03:23.173. Reopens
  repeat the error; some attempts temporarily report camera IDs unavailable.
  This is a device/HAL-level failure, not a fatal Java app exception.

Likely compatibility issue: a fresh session immediately applying current_mode
is not equivalent to the user's successful staged startup (start preview with
session/FPS configuration, then switch the vendor mode into repeating requests).
A manual live scope transition is not currently replayed by camera reopen.
This correlation does not prove which session tag/template/timing the HAL
requires. Do not claim dropping still-capture FPS as the root cause, or silently
remove the requested mode on recovery.

Memory: the JPEG development burst peaks near 1.2 GB PSS; graphics near 875 MB.
After frame processing a 220 MB GL texture pool remains cached. This adds load
and merits a separate pool-lifetime audit, but this export does not establish
memory pressure as the cause of the recurring device errors (errors precede
capture and also happen immediately after clean session creation).

Next reproduction should use the diagnostic APK on this Motorola: reproduce
one successful live scope transition, one shutter, and one reopen, preserving
requested/result mode, actual sensor cadence, and lifecycle events. Test a
single DNG first to separate mode/session recovery from JPEG burst-development
load. Need the matching DNG/HDR+ failure log if that path differs from this JPEG
capture. A staged sensor-mode bootstrap is a candidate compatibility feature,
not an established fix from this export alone.
