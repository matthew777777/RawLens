# Algorithm reference checkouts

This directory is for isolated upstream source checkouts used to study camera algorithms and implementation patterns. They are not RawLens build dependencies and are excluded from RawLens release artifacts.

## PhotonCamera

For the separate SkyKing working Bayer-direct merge reference used by RAW SR
Prompt 4 / future 4B–4D, see [the pinned source contract and adaptation notes](../docs/raw-sr-skyking-reference.md).
Its research-only checkout is `Photon-Camera-SkyKing/`, branch
`backup-26514-before-26515-short-bento-fix-20260820`, commit
`e9855a3af7a79801a762ec3f99b441474926f009`. It is not a build dependency.

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
- Commit: `4ee108e169496f429c0afa0cc33e57bb6b2ec724`
- Upstream commit date: `2026-09-20T19:48:10+06:00`
- Commit subject: `Merge pull request #192 from RealJohnGalt/fixup`

Reviewed but unmerged upstream work: PR #193 (`Fixup2`, RealJohnGalt,
head `068bfad8809d11f99db24f105dd1f8b636212c01`, open at review time,
42 commits over this checkout) was studied for memory/speed techniques;
its fetched head is available locally as `references/PhotonCamera`
branch `pr-193`. Portable takeaway applied: drop the terminal queue
drain on teardown (PR 193 `efa6e08a`), see `Gles31RawSrProcessor`.
Deliberately not taken: mirrored-tap folding (requires a symmetric tap
lattice; our kernel offsets are fractional and asymmetric), RG16F
gradient narrowing (our float32 precision is contract-pinned by the 4E
precision fix), fused 10-bit unpack (RAW16-only pipeline, already
single-pass), reciprocal normalization (breaks exact white-point
identity the oracle discipline requires), and PBO readback rings
(requires on-device GL verification).

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

## Burst-reconstruction rewrite references (`upstream/`, research-only)

Local-only checkouts for the `tools/burst-reconstruction-desktop` full rewrite.
They are git-ignored (`/references/upstream/`), never build dependencies, never
packaged. Study ideas, reimplement clean-room in stdlib-only Kotlin; do not
copy code verbatim (license notes below). Cite commit hash, not branch.

```bash
git clone --depth 1 https://github.com/timothybrooks/hdr-plus.git references/upstream/hdr-plus
git clone --depth 1 https://github.com/martin-marek/hdr-plus-pytorch.git references/upstream/hdr-plus-pytorch
git clone --depth 1 https://github.com/amonod/hdrplus-python.git references/upstream/hdrplus-python
git clone --depth 1 https://github.com/GuoShi28/GCP-Net.git references/upstream/GCP-Net
git clone --depth 1 https://github.com/GuoShi28/2StageAlign.git references/upstream/2StageAlign
git clone --depth 1 https://github.com/goutamgmb/deep-rep.git references/upstream/deep-rep
```

| Checkout | Pinned HEAD (2026-09-23) | License | Use for |
|---|---|---|---|
| `upstream/hdr-plus` (Tim Brooks, HDR+ C++) | `ef4dd2c` 2026-01-12 | MIT | FFT align, pairwise Wiener temporal merge per tile, `sigma=sqrt(a*y+b)` thresholds |
| `upstream/hdr-plus-pytorch` (Marek, HDR+ torch) | `e7091c3` 2024-09-08 | MIT | Vectorized align+merge reference, GPU/CPU structure |
| `upstream/hdrplus-python` (Monod IPOL2021) | `98ebf17` 2022-06-27 | AGPL-3.0 — do not copy code, ideas only | HDR+ pipeline stages, test bursts, finishing pipeline |
| `upstream/GCP-Net` (Guo et al. TIP2021 JDD-B) | `cef7513` 2021-08-09 | Apache-2.0 | Green-channel-prior guidance for R/B, feature-domain alignment pattern |
| `upstream/2StageAlign` (Guo et al. CVPR2022) | `f39218a` 2022-12-08 | MIT | Coarse patch-level + refined pixel-level alignment scheme (classical port, no ML) |
| `upstream/deep-rep` (Goutam et al. ICCV2021) | `154c51e` 2021-10-22 | CC BY-NC-SA 4.0 — do not copy code, ideas/eval only | BurstSR dataset + eval protocol, synthetic burst generation |

Already vendored (pre-existing, see `.gitignore`): `Handheld-Multi-Frame-Super-Resolution-Jamy-L`
(`07bc3f2`, MIT, Wronski steerable-kernel merge core), `-JVision`, `ImageStackAlignator`
(`b12e86e`, global NCC pre-align + rotation search).

Full attributions + rewrite mapping live in `NOTICE.md`.
