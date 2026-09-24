# Packed RAW-SR executor — 2026-09-10

## Contract

`Gles31RawSrProcessor.processPacked` consumes planner-approved, reference-first
`RawSrPackedFrame` descriptors. `RawSrPackedFrame.fromMetadata` uses the frame's frozen
`RawFrameMetadata`, never a later preview result. Descriptors borrow the original
Camera2 RAW_SENSOR plane; the capture remains responsible for Image lifetime and
exactly-once closure. Buffers must remain valid and unchanged until processing returns.
Buffer position/limit are frozen without copying pixels. Stride, crop, sensor origin,
CFA, black/white, shading map/state and noise profile are immutable snapshots.

Supported input is RAW16 with two-byte pixel stride and even row stride, including
row padding and nonzero buffer position. RAW10/RAW12 and other pixel strides reject
before allocation. Processing crops must be even-sized and all frames must share
dimensions and local CFA phase. The burst planner remains responsible for camera,
exposure, color and other compatibility checks. Noise profiles are retained, not yet
used by the diagnostic merge.

Normalization reuses AMaZE's `GlTexture.uploadRaw16` and `raw/preprocess.glsl`.
Black levels and CFA shift by **sensor origin + crop origin**. Lens coordinates remain
sensor coordinates, including green-row parity. Lens shading runs only here and only
when not already applied; an absent map retains AMaZE's identity behavior.

## Lifetimes and algorithm boundaries

1. Allocate two fixed accumulator pairs; clear the first pair on the GPU.
2. Upload/normalize the reference once, build its pyramid, accumulate its diagnostic
   RGB, then retire full-resolution reference CFA/RGB.
3. Upload/normalize one moving frame, build its pyramid, align coarse-to-fine, retire
   intermediate flows, and accumulate its GPU-prepared RGB.
4. Retire the moving CFA/pyramid/RGB before uploading the next frame. Ping-pong the
   same accumulator pairs. Reference gradients are still evaluated inline by the
   existing LK shader from the retained reference pyramid, not CPU arrays.
5. Finish queued GPU work at each frame boundary to prevent deferred deletions from
   retaining a burst of workspaces. A fenced texture pool is a future performance
   optimization; this implementation prioritizes bounded in-flight memory.
6. Release all textures on success, callback exception or processing failure on the
   owning thread. Callbacks cannot re-enter processing or close a live processor.

The original FloatArray API remains a **test-only CPU-oracle adapter**. The packed
path creates no full-resolution CPU float CFA/RGB/zero arrays. Its float staging
storage is limited to lens maps and tiny uniforms.

Per-frame flow/confidence textures are available synchronously through `onFlow`;
only the last flow is retained in the final output. Callbacks must not retain GPU
IDs beyond their documented lifetime. This intentionally removes the former
burst-length flow-texture history.

The new diagnostic demosaic shader implements the existing CPU oracle's weighted
neighborhood operation. This does **not** implement the final Wronski Bayer-direct
fusion or connect GPU output to saving. DNG/JPEG and Phase-A reference fallback are
unchanged.

## Memory accounting and verification

`RawSrTextureMemory` accounts actual live texture-storage allocations and exposes
the peak through `RawSrGpuOutput.peakTextureBytes`. This excludes borrowed Camera2
planes, GL driver overhead, program caches and upload staging. Retained original
RAW planes can still scale with frame count; processing workspaces do not.

On ARM / Mali-G615 MC2, all six headless `RawSrGpuInstrumentedTest` tests passed:

- 64×48 packed bursts of 2, 8, 15 and 30 frames each peak at **188,560 bytes**.
- Packed input tested across all four CFA patterns, odd/even crop and sensor
  offsets, padded rows, nonzero buffer position, unequal phase black levels,
  spatial/channel-varying lens gains and already-applied shading.
- Callback failure followed by successful processing passes.
- Static/integer flow CPU parity is exact; subpixel CPU parity MAE is approximately
  1.19e-7 X / 2.46e-7 Y Bayer-quad pixels. Reliable interior coverage is 100%.
- Existing two-frame RGB CPU comparison has zero maximum normalized difference.
- Full unit suite: 104 passing tests. Debug and Android-test APK builds pass.

These are synthetic correctness/storage-accounting checks, **not full-resolution
device-memory or performance qualification**. Two RGBA32F/R32F accumulator pairs
alone cost 40 bytes/output pixel; peak storage remains substantial. Full-resolution
budget/admission checks, real RAW burst fixtures and Adreno validation remain open.

## References used

- Supplied Wronski paper, section 5 and performance discussion: online accumulation
  makes processing memory independent of frame count (`1905.03277v2.md`).
- Supplied IPOL article: sequential accumulation formulation (`article_lr.md`).
- Jamy-L reference, `handheld_super_resolution/super_resolution.py`: reference
  initialization followed by per-comparison-frame accumulation.
- RawLens AMaZE direct RAW16 upload and normalization are the implementation
  reference for the Camera2 layout and lens-shading contract.
