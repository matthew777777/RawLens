# Touch AF/AE overlay release fix

UI-only fix: when the Open Camera-style touch-focus cycle automatically releases back to continuous-picture AF, `RawCameraController` now invokes the existing `onMeteringReleased()` callback. `MainActivity` already maps that callback to `meteringOverlay.clearTargets()` on the UI thread, exactly like the AF / AE RESET control.

No Camera2 autofocus behavior was changed: continuous-picture mode, temporary AUTO touch focus, AF START/CANCEL/IDLE requests, AF/AE metering regions, 1000 ms AF timeout, and 3000 ms continuous-focus reset delay are unchanged.
