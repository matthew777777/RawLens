# Contributing to RawLens

Thank you for helping improve RawLens. By submitting a contribution, you agree that it may be distributed under the project's GPL-3.0-or-later license.

## Development setup

Use JDK 17 and install Android SDK 35. Build from the repository root with:

```bash
./gradlew assembleDebug
```

Camera behavior varies significantly across Android devices, so camera-pipeline changes should also be tested on physical RAW-capable hardware.

The current activity is portrait-locked in the source manifest. Preview and control positioning still account for sensor/display rotation, but contributions should not assume that the activity can rotate into a separate landscape layout.

The app currently supports AUTO, PROGRAM shutter-first AE, ZSL RAW motion selection, and MANUAL exposure modes; single-frame and six-frame forward capture; optional 1–30-frame ZSL output targeting up to 30 FPS; AE metering modes; OIS; adaptive development exposure; AgX JPEG controls; denoise controls; and per-camera DNG metadata overrides. Changes to these behaviors should update current-state documentation and include the relevant fallback behavior.

## Pull requests

- Keep changes focused and explain the user-visible behavior.
- Preserve existing copyright, attribution, and SPDX notices.
- Identify copied or adapted third-party code and confirm that its license is GPLv3-compatible.
- Update all affected project documentation (`README.md`, `CHANGELOG.md`, `PRIVACY.md`, `NOTICE.md`, and `references/README.md`) when behavior, requirements, data handling, or research references change.
- Do not commit keystores, signing passwords, local SDK paths, captured DNG files, or device logs containing personal data.
- Include device model, Android version, Camera2 ID, and reproduction steps for camera-specific fixes.

## Commit and release hygiene

A released APK must correspond to a reproducible source tag. Build scripts and other material required to produce the application are part of the GPL Corresponding Source and should remain in the repository.
