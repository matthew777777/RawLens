# MCRAW compatibility checks

Run from any directory with existing reference checkouts (no downloads):

```sh
sh tools/mcraw/verify.sh /private/tmp/motioncam-decoder /private/tmp/MediaCinemaRAW-Decoder [sample.mcraw]
```

`roundtrip.cpp` adapts PhotonCamera MCRAW's `tools/mcraw/roundtrip.cpp`
(GPL-3.0) and checks 240 synthetic RAW16/RAW10, cropped, binned, padded-stride,
non-64-aligned-width and bit-depth cases against **both** reference decoders.
`interop.cpp` tests our native container writer's audio/index layout and optionally
compares the first/middle/last frames and every audio sample in an existing file.
The fixture goes to `/private/tmp/rawlens-interop.mcraw`.

## Findings on 2026-09-25

`IMG_20260925_175654_426_VID.mcraw` has 77 frames (4080x3060). Both decoders
agree exactly on the three sampled frames and all 55 audio chunks (112,640
mono samples, peak 2902). Its frame compression is readable. The file is missing:

- Container `sensorArrangment` (intentional upstream spelling), `blackLevel`,
  `whiteLevel`, color/forward/calibration matrices and illuminants.
- Per-frame `asShotNeutral`, exposure and ISO.
- `extraData.audioSampleRate` and `extraData.audioChannels`. A root-level
  `audioSampleRate` does not satisfy either decoder's audio discovery API.

These missing fields prevent consumers from interpreting the Bayer pixels and
embedded PCM correctly. The recorder now serializes Camera2 calibration and
matches capture results by sensor timestamp; frames without a matched result are
dropped instead of being written with invented white balance. Optional camera
matrices are emitted only when supplied by the camera. Audio now uses BOOTTIME
anchors, matching `elapsedRealtimeNanos`, and startup must succeed before the
container advertises an audio stream.

The fix applies to new recordings. Old files have no recorded calibration or
white balance to recover; the original sample is unchanged. Its PCM audio was
extracted locally to `/private/tmp/IMG_20260925_175654_426_VID-audio.wav`.

`RawVideoRecordTest` now checks the calibration, matched frame metadata and
nested audio declarations in actual camera recordings. No phone was connected
for an on-device run; visual rendering and fresh-recording playback remain to
be verified. This investigation does not establish a fix for separate still-DNG
artifacts without a corresponding DNG sample.

Reference commits: PhotonCamera MCRAW `8a9660c`, MediaCinemaRAW-Decoder
`ac4c420`, motioncam-decoder `5a0ccd2`.
