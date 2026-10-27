# Video format controls audit

The stale format badge came from `refreshCaptureFormatControl`: every stills
refresh in video mode wrote CRS/REC without checking Direct Log. The settings
checkbox separately updated only part of the video UI. The crop/profile tile
was labelled for video but hidden in the still-only list. RAW letterboxing
remained assigned when enabling Direct Log.

Mode changes now share `setDirectLogMode`, refreshing badge, info, shutter
accessibility label, guide and quick tiles. RAW keeps its crop preference when
switching to Direct Log, but the overlay receives null. Returning restores it.
RAW shows crop and binning; Direct Log shows profile, encoded size and FPS.
These buttons use existing recorder preferences, not new capture capabilities.
RAW FPS remains the recorder's fixed rate. Direct Log offers the existing
1080p/1440p/4K settings and 24/30 FPS; selection does not guarantee a device can
sustain the requested encoding rate.

## Historical source comparison

Camera2Video-derived source published March 20, 2021, attributed to the Android
Open Source Project (2020):
https://gist.github.com/selvan/f0d133e0007501be7587d7d13e0b8480

In `createRecorder`, `setVideoFrameRate(args.fps)` and
`setVideoSize(args.width, args.height)` precede prepare/start. The recording
request also uses the selected FPS for `CONTROL_AE_TARGET_FPS_RANGE`. This
supports keeping take format settings fixed during recording. RawLens already
copies its Direct Log FPS and size preferences into the recorder at take start;
the new UI uses those same preferences. No external source was copied.

The dated source predates current generative coding tools; that is evidence of
provenance, not an absolute guarantee of human authorship. It does not validate
RawLens-specific CRS crops, Bayer binning or HEVC grading. Those controls were
traced against the local recorder implementation.

## Verification

Debug Kotlin and Android resources compiled successfully. A device regression
test (`VideoFormatSwitchTest`) exercises repeated switches, a shared badge
refresh, crop restoration, tile visibility and rejecting switches while recording.
Actual camera rendering and touch layout still require device verification.
