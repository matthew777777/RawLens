# RawLens privacy statement

Effective date: 2026-08-31

RawLens processes camera preview frames, RAW images, camera metadata, and—when RAW ZSL or the level guide is enabled—sensor motion samples locally on the user's device. Optional adaptive RAW development analyzes luminance locally before JPEG output; these statistics are not transmitted or stored as a separate database. It does not include analytics, advertising, user accounts, or runtime network communication, and it does not collect or transmit personal information. Camera and lens preferences, including JPEG/AgX, adaptive-exposure, denoise, and per-camera DNG calibration settings, are stored locally in Android app preferences.

## Permissions and files

RawLens requests camera permission so it can display the viewfinder and capture photographs. DNG files are saved at the user's request through Android `MediaStore`, normally under `DCIM/RawLens`, and remain under the user's control.

The optional level guide uses the device rotation sensor. RAW ZSL uses gyroscope samples to score buffered frames for motion; those samples are not written into a separate RawLens database or transmitted.

Android or the device manufacturer may provide system services used by the camera and media APIs. Their behavior is governed by the device and operating-system provider, not RawLens.

## Changes

If a future version adds network services, telemetry, or other data handling, this statement and the release notes must be updated before that version is distributed.
