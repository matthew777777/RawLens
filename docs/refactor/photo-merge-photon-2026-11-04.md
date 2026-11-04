# Photo merging alongside ZSL

HDR+ and MFSR now accept fresh bursts in AUTO, PROGRAM and MANUAL. ZSL
continues to select buffered RAWs. Enabling, disabling, switching exposure
modes, and restarting the app no longer force a merger into ZSL or turn it off
when leaving ZSL.

## PhotonCamera reference

Inspected PhotonCamera dev commit `b2eff821a858033515eb2700e7292befecc95225`:

- [CaptureController.java](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/capture/CaptureController.java):
  `isZslMode` makes MOTION eligible for ZSL only without HDR and a dual session;
  `triggerZslCapture` selects buffered frames. Ordinary Photo capture builds
  per-frame still requests through `IsoExpoSelector.setExpo` and submits a
  `captureBurst`.
- [IsoExpoSelector.java](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/processing/parameters/IsoExpoSelector.java):
  manual shutter/ISO override the selected exposure. Photo exposure balancing
  and Motion exposure constraints are separate.
- [FrameNumberSelector.java](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/processing/parameters/FrameNumberSelector.java):
  frame selection is distinct from exposure control.

RawLens adopts the acquisition split while retaining its own Program/ETTR
exposure selection, configured frame count, HDR+ Vulkan merger, MFSR engine,
and output development. This change does not port PhotonCamera's complete
exposure or processing pipeline.

## Implementation

`RawCameraController.beginSingleCapture` routes non-ZSL merge captures through
the existing fresh still burst path. The still exposure selected by existing
controls is frozen before requests are submitted. Auto without ETTR freezes
the metered result; ignored sensor defaults from AE-on templates are excluded.
Program/ETTR and manual sensor values retain their existing priority.

Fresh RAWs retain their exact `TotalCaptureResult` and timing. On completion,
the collected frames transfer to the same owned merge jobs used by ZSL.
Partial sequences need at least two arrived frames; error/timeout/teardown
releases any retained collection. The request exposure determines the burst
timeout, including long manual shutters.

The reader upgrades at an idle boundary to retain the configured merge burst.
Input capacity is checked separately from the one-output save reservation,
so an eight-frame JPEG+DNG merge is allowed without admitting eight individual
JPEG development jobs. Degraded readers that cannot retain the requested
burst report unavailable. Fresh merging requires manual sensor control.

Both mergers use a single-flight busy gate. ZSL recovery cannot fall through
to saving an unmerged top-up burst. ZSL frame storage also accommodates the
two-frame merge minimum when the configured selection is one.

Photo merge tiles no longer wait for a ZSL ring to fill. Settings describe
fresh versus buffered acquisition and share the existing frame-count control.
HDR bracket, HDR+ and MFSR remain mutually exclusive. Flash and explicit
BURST 6 / long-press keep their existing single-frame / individual-burst paths.

## Validation

The isolated package `com.matthew.rawlens.photomergecheck` was built as
`githubDeviceTest` version 2.1.45, alongside the daily app. The existing opt-in
`ZslCaptureSoakTest` accepts `zslMerge=hdrplus|mfsr` with all four exposure
modes. `exerciseMergeToggles=true` additionally toggles the tile off/on and
checks that the selected exposure mode and ring request remain correct.

Results are retained under [results/photo-merge-2026-11-04](results/photo-merge-2026-11-04/).
The checks count published artifacts, reject duplicate queued shutter presses,
wait for shutter and RAW preview recovery, and check drained save ownership.
Fresh-capture logs record every paired ISO/exposure to verify burst consistency.

All eight combinations of merger and exposure mode passed with two-frame
DNG captures and tile off/on exercises. Additional cases passed: eight-frame
AUTO/HDR+ and PROGRAM/MFSR JPEG+DNG, a MANUAL/HDR+ capture, and MFSR/ZSL with
a configured frame count of one (two-frame merge minimum). All twelve have
native merge completion logs and the expected published outputs. Every fresh
burst's paired ISO and exposure values were identical across its frames.

The MANUAL/HDR+ extra case requested six seconds, but the connected sensor
clamped it to 400 ms. This validates the supported manual exposure, not a real
six-second capture; long exposures beyond this phone's range remain untested
on hardware.

The focused exposure, reader-capacity, merge-count and settings suite passed
69 tests. The earlier full unit suite ran 1,894 tests with seven skipped and
one failure: `ProgramAeLoopTest.startAppliesTheTargetAtOnce` at line 43.
That test exercises the previously modified Program loop; this change does
not alter that loop. Full-suite success is not claimed.

These are functional capture/output checks on one connected phone, not a
cross-device or image-quality comparison against PhotonCamera. Native merge
algorithms were retained.
