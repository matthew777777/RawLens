# Changelog

Notable changes to RawLens are documented here. The project follows [Semantic Versioning](https://semver.org/) for public releases.

## [Unreleased]

### Added

- PROGRAM custom RAW-driven AE: center-weighted / median metering sets sensor ISO + shutter live (AE_OFF), replacing the hardware-AE rebalance
- Shutter-vs-ISO priority slider (0=ISO priority … 0.5=balanced … 1=shutter priority)
- Per-lens PROGRAM profiles (metering, ISO/shutter min/max, locks, balance, brightness bias) with migration from legacy global ceilings
- ISO-lock (shutter auto) and shutter-lock (ISO auto) single-axis modes
- PROGRAM brightness bias (default +0.5 EV) against stock + spektra underexposure
- Viewfinder PROGRAM editor via ISO/S chips; ETTR overrides PROGRAM still exposure when converged
- Chip contract: hold ISO/SHUTTER to lock, tap for slider, slider Auto releases both, lock-both enters MANUAL seeded from live pair
- Quick panel refreshes in place after PROGRAM profile and mode changes instead of waiting for collapse/reopen
- PROGRAM metering modes center / average / spot (median renamed to full-frame average with silent migration); AE METER quick tile cycles RAW metering in PROGRAM and hardware metering elsewhere
- PROGRAM metering sampling moved off the camera thread (bounded single in-flight frame, stale generations dropped): fixes ~1 fps viewfinder/whole-app stall on sensors where a full-res sample costs hundreds of ms
- Quick panel tile lookups cached; lens switcher skips redundant rebuilds
- PROGRAM defaults: ISO priority (0.0) and neutral 0.0 EV bias; per-lens store bumped to v2 so old 0.5/+0.5 EV defaults do not linger
- PROGRAM highlight guard: brightening never pushes the hottest channel past 0.9
- PROGRAM exposure glide: small capped measurements plus EMA feed a solver target that the live pair eases toward in ~1/6 EV steps (~1 stop/s), unified for ETTR-driven preview; still frames match the preview pair
- Converged ETTR freezes PROGRAM and meters from actual sensor exposure; PROGRAM seeds until convergence and resumes after
- Synchronous per-lens profile load on active-camera change; PROGRAM bounds linked from each lens's calibration editor
- No-manual-sensor cameras keep Android AE with locks/limits disabled and explanatory copy

## [1.0.0] - 2026-08-31

### Added

- Camera2 RAW lens discovery and switching
- Opt-in RAW zero-shutter-lag capture with a memory-bounded pre-shutter buffer
- Timestamp-paired ZSL selection using exposure/readout completion, freshness, focus, exposure, lens state, ISO, and frame-time gyroscope motion
- Request-epoch isolation and RAW queue draining across ZSL control and capture transitions
- Explicit repeating-RAW flush and ImageReader overlap capacity for reliable six-frame bursts after ZSL
- Live ZSL state badge and automatic fallback to normal RAW capture
- Full-resolution DNG capture and six-frame RAW burst mode
- Manual ISO, shutter, white-balance, and exposure-compensation controls
- Independent focus and exposure metering targets
- Histogram, grid, level, capture timer, torch, and diagnostic overlay
- Portrait camera layout with rotation-aware preview and controls
- AUTO, PROGRAM shutter-first AE, and MANUAL capture modes alongside ZSL RAW motion selection
- Dynamic exposure balance with configurable ISO and shutter ceilings
- Standard Auto, Center Weighted, Frame Average, and Spot AE metering modes
- OIS toggle, configurable 2/5-second timer, and portrait-oriented control layout
- Configurable RAW ZSL output count from 1 to 30 frames, targeting up to 30 FPS where supported
- Six-frame JPEG/JPEG+DNG ZSL output; DNG-only supports the full 30-frame selection
- Per-camera DNG metadata overrides for noise profiles, color matrices, calibration matrices, and black/white levels
- Rotation-vector horizon guide with orientation-aware remapping, gravity fallback, and smoothed roll/pitch rendering
- Persisted JPEG / JPEG+DNG / DNG-only capture-format selector without ISP-JPEG substitution
- AgX JPEG output controls: Base/Golden/Punchy looks, contrast, saturation, purity, hue preservation,
  highlight/shadow range, gamut compression, and reset-to-official-Base
- Bounded adaptive RAW development exposure for AUTO/ZSL, adjustable PROGRAM strength, shared across bursts
- Optional RAW prefilter and post-demosaic denoise controls with chroma/luma cleanup, grain retention, and edge protection
- Exact RAW image/result metadata snapshots and stride-safe unclamped CFA unpacking
- CPU-reference lens-shading, hot/dead-pixel correction, and pre-demosaic darktable
  inpaint-opposed highlight reconstruction
- PhotonCamera-derived 13-pass tiled AMaZE GLES 3.1 demosaic executor with explicit capability
  gating, fixed-memory scratch resources, actual-CFA phase handling, and unbounded RGB output

### Behavior and documentation

- ZSL and dynamic PROGRAM exposure are mutually exclusive
- RAW ZSL requests the same per-frame lens-shading map as forward capture, and every Camera2
  capture uses PhotonCamera's checked-in `dngCreator.cpp` and its unmodified TinyDNG dependency
- Still DNG saving follows PhotonCamera's complete Java path: `DngCreator.setParameters`,
  `setCompression(false)`, and `writeBuffer(OutputStream, raw, width, height)`
- Camera calibration, color, and forward matrices use PhotonCamera's transposed
  `Converter.convertColorspaceTransform` layout instead of direct Camera2 row-major serialization
- Removed RawLens's custom TinyDNG serialization patches, JPEG SubIFD preview, preview development,
  ExifIFD extension, and native file-descriptor writer; PROGRAM and ZSL receive PhotonCamera's one-image DNG
- ZSL falls back to ordinary forward RAW when the device cannot sustain the requested stream or memory budget
- ZSL requests the fastest advertised compatible AE range up to 30 FPS and logs the per-device stream ceiling
- Adaptive development exposure is applied only to JPEG development; DNG pixels and capture metadata remain unchanged
- README and related project documentation track the current UI, 30-frame ZSL, six-frame bursts, adaptive exposure,
  AgX controls, denoise settings, build-time native dependency, and portrait-only activity behavior

### Planned

- Physical-device validation across supported Camera2 capability combinations
- Motion-aware shutter-priority exposure policy refinement
