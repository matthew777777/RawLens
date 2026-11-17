# Lapis seamless-switch investigation — 2026-11-17

Status: driver partners confirmed; live Camera2 switching NOT verified. No production app integration enabled.

Device: Redmi Note 15 Pro 5G / lapis / MT6878, OS3.0.303.0.WPPEUXM.
All experiments headless. SELinux remained Enforcing. CRAW untouched.

## Compiled driver evidence

`g_seamless_switch_scenario` debug output from the running driver confirms these groups (actual driver indices, NOT Camera2 logical scenario IDs):

| Driver modes | Observed/readout family | Limitation |
| --- | --- | --- |
| 6, 7, 8, 9, 15 | 4080 × 3060; LN2 1×, 2×, 4×, DSG, alternate grouped mode | 15 has a different CFA bus format; app RAW precision is separate from interface format |
| 0, 11, 12 | Normal, grouped 200 MP, 50 MP | Dimensions and resource requirements differ |
| 2, 10, 13, 16, 17, 18 | 4080 × 2304 video family | 17/18 function not identified; driver grouping alone does not establish usable app support |

No partners advertised for 1, 3, 4, 5, 14, 19 (sentinel 255). In particular driver 4 / 60 fps is outside these groups.

The matching source handles seamless switching with group hold, register tables, mode update, AWB, AE and gain handoff. DSG compose uses me_gain. Do not write a switch directly below an active HAL: ISP routing and resource reservations must also agree.

## Probe and rollback

`tools/lapis-profile/research/RootSeamlessProbe.java` uses one CameraDevice and one CameraCaptureSession, matches RAW timestamps to CaptureResults, records cadence and boundary gaps, checks active driver mode and unchanged service PIDs. Actual seamless-handler logs are additionally required: unchanged PIDs alone would not exclude internal stream restart.

`test-seamless-recover.sh` snapshots properties/debug state, verifies stock library hashes, checks a DCG baseline, runs a bounded experiment, restores original state, verifies a fresh DCG capture, then restores original state again. Requires pre-existing root diagnostic assets, original firmware libraries and no active app lease. These are research tools, not a working user-facing feature.

## Live tests

1. `group1`, logical 0 → 4 → 0, driver 6 → 7 → 6: initial mode 6 streamed 45 matched frames at 30.42 fps. Logical 4 maps to crop4× / 4-cell, not 2× Bayer. Provider aborted during transition. DCG recovery passed, active driver 9 at 30.66 fps.
2. `group1-enable`, logical 0 → 7 → 0 with seamlessen=1 and support3rdISZ=1: session configuration rejected before streaming: `Incorrect config support list (0x10), support list is (0x40)`. DCG recovery passed.
3. `group1-logical7`, same logical mapping with enable flags absent: same configuration rejection. Thus the enable flags are not sufficient to explain the reservation failure. DCG recovery passed.

Logical 7 was a mapping hypothesis, not a verified 2× route. Do not reuse that hypothesis as production configuration. Logical IDs and actual driver IDs are distinct. No switch success, image quality improvement or pause-free behavior is claimed.

## Evidence and next work

Local traces and stock helper disassembly: `.local/lapis-seamless-2026-11-17/`. First test: `seamless-hal.log`, `kernel-after.log`; second: `enabled-*`; third: `logical7-hal.log`. Device outputs remain under `/data/local/tmp/rawlens-seamless-*`.

Next resolve the logical-scenario registry and support mask in stock `libcam.sensor_mode_helper.so` and FeatureSettingPolicy. Avoid further guessed reservations. Then prove a forward AND reverse switch with driver seamless-handler trace, matched RAW frames, correct mode and metadata, unchanged service PIDs, bounded boundary gap, and successful DCG recovery. Only afterward change the app worker/session ownership and discard stale frames at transition boundaries. Existing stock driver 0 cannot be treated as group-compatible with 2× driver 7.

### Compiled partner dump

```json
{
  "0": [
    11,
    12
  ],
  "1": [
    255
  ],
  "2": [
    10,
    13,
    16,
    17,
    18
  ],
  "3": [
    255
  ],
  "4": [
    255
  ],
  "5": [
    255
  ],
  "6": [
    7,
    8,
    9,
    15
  ],
  "7": [
    6,
    8,
    9,
    15
  ],
  "8": [
    6,
    7,
    9,
    15
  ],
  "9": [
    6,
    7,
    8,
    15
  ],
  "10": [
    2,
    13,
    16,
    17,
    18
  ],
  "11": [
    0,
    12
  ],
  "12": [
    0,
    11
  ],
  "13": [
    2,
    10,
    16,
    17,
    18
  ],
  "14": [
    255
  ],
  "15": [
    6,
    7,
    8,
    9
  ],
  "16": [
    2,
    10,
    13,
    17,
    18
  ],
  "17": [
    2,
    10,
    13,
    16,
    18
  ],
  "18": [
    2,
    10,
    13,
    16,
    17
  ],
  "19": [
    255
  ]
}
```
