# RawLens privacy statement

Effective date: 2026-10-29

RawLens processes camera preview frames, RAW images, camera metadata, microphone audio while a video is recording, and—when RAW ZSL, video, or the level guide is in use—sensor motion samples locally on the user's device. Optional adaptive RAW development analyzes luminance locally before JPEG output; these statistics are not transmitted or stored as a separate database. It does not include analytics, advertising, or user accounts, and it does not collect or transmit personal information. Camera and lens preferences, including JPEG/AgX, adaptive-exposure, denoise, video, and per-camera DNG calibration settings, and any `.cube` LUTs the user imports, are stored locally in Android app preferences. The only network communication is the optional in-app update check described below.

## Permissions and files

RawLens requests camera permission so it can display the viewfinder and capture photographs and video. Photos, `.craw` RAW video, Direct Log video, and burst sidecars are saved at the user's request through Android `MediaStore`, normally under `DCIM/RawLens`, and remain under the user's control. Media read permission is used only to show the user's earlier RawLens captures in the in-app gallery seat.

Microphone permission is requested the first time a video is recorded; audio is captured only while recording and is stored inside the user's video file. Denying it records silent video.

The optional level guide uses the device rotation sensor. RAW ZSL uses gyroscope samples to score buffered frames for motion. RAW video stores gyroscope and accelerometer samples inside the `.craw` file for desktop stabilization, and optional burst gyro sidecars are written next to the user's DNGs. Motion samples are never written into a separate RawLens database or transmitted.

Location is off by default. Enabling "Save GPS location in photos" requests Android location permission and embeds the current fix (latitude, longitude, altitude when available, and UTC fix time) as EXIF GPS tags in saved JPEG and JPEG XL files and as GPS tags in saved DNGs. Location updates run only while the app is in the foreground with the option enabled; fixes older than five minutes are discarded rather than written, and captures taken without a fresh fix carry no GPS tags. Location data never leaves the device except inside the user's own photo files.

Android or the device manufacturer may provide system services used by the camera and media APIs. Their behavior is governed by the device and operating-system provider, not RawLens.

## Update checks

GitHub builds can compare the installed version against the latest RawLens release on GitHub: the first launch asks with an on/off switch, and only an opt-in enables the automatic check (at most once every 24 hours; the choice can be flipped anytime under Settings → About). Off means fully offline — the app makes no network connections on its own. A manual "Check for updates" button on the same screen is always an explicit per-tap decision. The check fetches only the public release metadata (tag, notes, APK link) over HTTPS; choosing to update downloads the release APK and hands it to the system installer. Nothing is uploaded, and no identifiers, telemetry, or analytics are sent — GitHub inevitably observes the request the way any web server observes a page view. Builds distributed through F-Droid disable the update check entirely, so they never contact the network for it.

## Changes

If a future version adds network services, telemetry, or other data handling, this statement and the release notes must be updated before that version is distributed.
