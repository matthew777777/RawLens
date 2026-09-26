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
`motion_coalesce.cpp` mimics the recorder (251 tiny motion drains per sensor
over 5 frames) and asserts the writer coalesces them to <= frames + 1 chunks
with every sample preserved in both decoders.
`legacy_audio.cpp` builds against the Jan-2026 decoder sources from the
checkout's git history (the MotionCam Tools v1.0 era, pre-gyro) and asserts
its tail scan — which stops at the first motion item — still finds the
audio: the audio index must precede any trailing motion data. `verify.sh`
also runs it against a sample file when one is given.

`repair_motion.py` salvages pre-fix takes: it rewrites the file with
identical frames, audio and motion *samples* but one coalesced gyro/accel
chunk each, rebases every timestamp to the first frame's (dropping
pre-roll audio/motion without video, like PhotonCamera), and emits the
audio index before any trailing motion data for pre-gyro readers. It never
modifies the input, refuses to overwrite the output, and is idempotent
(byte-identical re-run on an already-relative file). `--stereo`
additionally duplicates mono audio to L=R stereo:

```sh
python3 tools/mcraw/repair_motion.py [--stereo] INPUT.mcraw OUTPUT.mcraw
```

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

## Findings on 2026-09-26

`IMG_20260926_082102_593_VID.mcraw` (95 frames, 4080x3060) carries intact
audio — 91 chunks, 186,368 mono samples, peak 16460, correctly declared in
`extraData` — yet motioncam-decoder cannot open it at all: `Decoder::init`
throws `Invalid gyro index`. The writer emitted one motion chunk per sensor
drain (~100/s), producing 318 gyro + 313 accel chunks for 95 frames, while
the decoder requires motion chunks <= frames + 1. The open abort hides the
intact audio too, which is why the take plays silent.

The native writer now buffers motion and coalesces it to at most one chunk
per sensor per committed frame plus a trailing chunk at close, so the bound
holds for any drain cadence (same fix applied upstream in
MediaCinemaRAW-Encoder). `RawVideoRecordTest` pins the bound on-device, and
`motion_coalesce.cpp` pins it on-host against both decoders.

## Findings on 2026-09-26 (audio sync)

The motion fix made the take openable, but MotionCam Tools still played it
silent — while PhotonCamera's `VID_20260926_091629.mcraw` is heard in the
same app. Diffing the two: PhotonCamera writes recording-relative
timestamps (first frame 0, audio origin ~52ms, stereo), we wrote absolute
boot-time nanoseconds (first frame ~2.46e14, audio origin ~68h in). Their
`RawVideoProcessor` carries the explanation: absolute sensor timestamps
overflow 32-bit ms math in some readers, "breaking audio sync entirely".

The recorder now rebases every on-disk timestamp to the first queued
frame's sensor ts (frames, audio, motion, and the timestamp/filename/
metadataTimestamp frame-metadata strings), dropping pre-roll audio/motion
without video so all timelines start together — the PhotonCamera rule.
`repair_motion.py` does the same for old takes (plus the motion
coalescing): the repaired copy (95 frames from ts 0, 79 audio chunks, 1
motion chunk per sensor) opens in motioncam-decoder with pixels,
kept audio/motion samples and non-timestamp metadata all identical to the
original. New recordings need no repair.

## Findings on 2026-09-26 (stereo)

The recorder is now stereo-first with mono fallback, declaring the actual
channel count like the audible reference (PhotonCamera records stereo,
motioncam-decoder's own fixture declares `audioChannels: 2`); the container
path was already channel-agnostic (verbatim int16 arrays). `interop.cpp`
pins a distinct-L/R stereo round-trip through both decoders. Stereo was
initially suspected as the silence barrier, but a stereo conversion still
played silent — the actual barrier is the tail order below. Stereo stays
as reference parity (it also satisfies any player that does require 2ch).

## Findings on 2026-09-26 (tail order — the actual silence fix)

MotionCam Tools v1.0's own log (`~/Library/Logs/MotionCam Tools/fuse.txt`)
reports `No audio chunks found` for our takes but `Loaded 262560 samples`
for PhotonCamera's. Decoder archaeology explains it: Tools v1.0 (Feb 2026)
predates gyro support (Aug 2026) and accelerometer support (Sep 2026), and
its era's tail scan stops at the first motion item. Our trailing motion
data sat between the last frame and the audio index, so the scan broke
before discovering any audio — while current decoders (order-free) read
the same files fine. PhotonCamera's take has no motion, so its scan
reaches the audio index.

Both writers now emit the audio index before flushing trailing motion
(no motion data may sit between the last frame and the audio index);
`legacy_audio.cpp` pins this against the Jan-2026 parser, `EncoderTests`
and `RawVideoRecordTest` pin the structure, and `repair_motion.py`
rewrites old takes in the fixed order. The Jan-2026 parser finds 0 chunks
in pre-fix files and all 101 in repaired ones — the exact Tools symptom
and cure, reproduced locally.
