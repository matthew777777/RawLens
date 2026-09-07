# Algorithm reference checkouts

This directory is for isolated upstream source checkouts used to study camera algorithms and implementation patterns. They are not RawLens build dependencies and are excluded from RawLens release artifacts.

## PhotonCamera

`PhotonCamera/` is a single-branch checkout of PhotonCamera's `dev` branch. It is a research-only checkout and is not packaged into the RawLens app:

```bash
git clone --branch dev --single-branch https://github.com/eszdman/PhotonCamera.git references/PhotonCamera
```

Use it as an encyclopedia for topics such as Camera2 request/session behavior, exposure selection, burst planning, RAW metadata, alignment, frame merging, denoising, demosaicing, tone mapping, color processing, sharpening, and device quirks.

Before applying an idea to RawLens:

1. Identify the upstream file and commit that informed the work.
2. Verify the algorithm against Android documentation and the target device's reported capabilities.
3. Reimplement only the parts appropriate for RawLens's architecture and memory limits.
4. Preserve upstream copyright/license notices when code is copied or adapted.
5. Record material derivations in `NOTICE.md` and the relevant source file.
6. Test fallback behavior on devices that reject the requested stream combination.

PhotonCamera is GPL-licensed. The local checkout retains its upstream `LICENSE`; RawLens is independently distributed under GPL-3.0-or-later.

### Current checkout

- Branch: `dev`
- Commit: `54d9febc596b34376b8be242a388f386d97e8f5d`
- Upstream commit date: `2026-08-27T01:07:28+06:00`
- Commit subject: `Merge pull request #178 from v1p3rrrrr/dev`

Treat the commit hash—not the moving branch name—as the citation for research performed against this checkout.

### Reference map

| Topic | Primary entry points |
|---|---|
| Camera2 sessions, RAW readers, bursts, and continuous RAW capture | `capture/CaptureController.java` |
| Exposure/ISO planning | `processing/parameters/IsoExpoSelector.java`, `ExposureIndex.java`, `FrameNumberSelector.java` |
| Device motion | `control/Gyro.java`, `control/GyroBurst.java` |
| Frame/result ownership and saving | `processing/ImageFrame.java`, `ImageSaver.java`, `ImageSaverSelector.java` |
| HDR and unlimited processing | `processing/processor/HdrxProcessor.java`, `UnlimitedProcessor.java` |
| Sensor/noise/color metadata | `processing/render/Parameters.java`, `NoiseModeler.java`, `ColorCorrectionTransform.java` |
| OpenGL post-processing graph | `processing/opengl/postpipeline/PostPipeline.java` |
| Alignment and merging | `processing/opengl/scripts/PyramidAlignment.java`, `FlowNetAlignment.java`, `assets/shaders/alignment/`, `assets/shaders/merge/` |
| Demosaicing | `processing/opengl/postpipeline/Demosaic*.java`, `assets/shaders/demosaic/`, `assets/shaders/amaze/` |
| Denoising and noise detection | `processing/opengl/scripts/NoiseDetection.java`, `assets/shaders/denoise/` |
| Exposure fusion and local tone mapping | `processing/opengl/postpipeline/ExposureFusion*.java`, `LFHDR.java`, `assets/shaders/ltm/` |
| Sharpening | `processing/opengl/postpipeline/CaptureSharpening.java`, `Sharpen*.java`, `assets/shaders/capturesharpen/`, `assets/shaders/sharpening/` |
| Ultra HDR | `processing/ultrahdr/`, `assets/shaders/ultrahdr/` |
| Sensor-specific workarounds | `pro/SensorSpecifics.java`, `pro/Specific*.java`, `settings/SensorConfig*.java` |

Paths in the table are relative to `references/PhotonCamera/app/src/main/java/com/particlesdevs/photoncamera/` unless they begin with `assets/`; shader paths are relative to `references/PhotonCamera/app/src/main/`.

### Updating the reference

Update deliberately, inspect upstream changes, and then revise the pinned commit above:

```bash
git -C references/PhotonCamera fetch origin dev
git -C references/PhotonCamera merge --ff-only origin/dev
git -C references/PhotonCamera rev-parse HEAD
```

Never add the PhotonCamera checkout to RawLens's Gradle settings or copy upstream code without recording its origin and license treatment.
