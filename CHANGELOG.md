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
- PROGRAM exposure glide: small capped measurements plus EMA feed a solver target that the live pair eases toward with adaptive steps (fast far away, gentle near, 1/8 EV max at 10 Hz); still frames match the preview pair
- Histogram sampler shares the bulk-row discipline (LUTs, no per-pixel divisions/calls)
- PROGRAM fast start: first metering result after mode/profile/lens entry applies immediately instead of gliding in from the previous engine's exposure
- PROGRAM metering performance: bulk row reads (zero per-pixel divisions/calls), region-cropped scans per metering mode with full-frame guard scan, 250 ms unconverged cadence with self-tuning backoff, 1.0 EV step cap
- PROGRAM locks honor static user bounds: a locked axis no longer drifts with the moving low-light cap; every lock engagement seeds from live values; unseeded legacy locks fall back to actual sensor exposure
- Converged ETTR freezes PROGRAM and meters from actual sensor exposure; PROGRAM seeds until convergence and resumes after
- Synchronous per-lens profile load on active-camera change; PROGRAM bounds linked from each lens's calibration editor
- No-manual-sensor cameras keep Android AE with locks/limits disabled and explanatory copy
- App logcat streams to a session text file from launch (no permission needed), with crashes appended before the process dies; 8 MB rotation keeping the newest 5 sessions, plus one-tap share to Download/RawLens/logs/. The live session mirrors itself into Download/RawLens/logs/ every 30 s and on stop/crash with no taps, the logcat child respawns if killed, and a crash on launch auto-exports that session on the next run and offers to share it, so the log is reachable without opening Settings
- RAW viewfinder CPU fallback rewritten as a native NEON sampler (single-digit ms per frame instead of the ~100+ ms Kotlin copy), with automatic zero-copy → NEON fallback preserved
- RAW VF debug overlay is tappable and cycles the engine AUTO → forced GPU → forced CPU (persisted across restarts); the overlay shows the live path as GPU/NEON plus the override
- RAW viewfinder resolution options raised to 480/640/960/1080 long edge: the new 1080 top step-demosaics a 12 MP sensor at 1020×765 (was 680×510). Stored 480/640/960 preferences map back to themselves; fresh installs default to 1080
- RAW-SR measured noise LUT: per-sensor-profile Monte Carlo calibration for the linear guide domain (stratified latent prior, Welford 3x3 patch statistics, R/B single-sample and G mean-of-two-greens channels), cached per profile in the app cache dir and wired into both save paths; robustness floors sigma² and shrinks d² at the measured reference brightness on CPU and GLES, with analytic fallback when no LUT resolves
- RAW-SR alignment options: the CPU mosaic chain now defaults to the SNR-derived tile size (matching the GPU path) instead of fixed 12-quad tiles, every-level ICA is available via `icaLevels`, and bilinear/bicubic inter-level flow upsampling is available on the CPU oracle (GLES stays nearest-only by explicit contract)
- RAW-SR analytic kernels: ISO kernel type plus hard-threshold selection law (Jamy-L mirrors, CPU and GLES); the analytic fallback behind KernelNet now uses the hard law (isotropic at A ≤ 1.95) instead of linear, so noisy texture degrades to a safe isotropic kernel instead of oil-painting. KernelNet remains the primary kernel source
- RAW-SR noise cross-check (`RawSrFrameNoiseMeter`): a DEBUG-gated (`adb shell setprop log.tag.RawLensMosaic DEBUG`) diagnostic that measures the shot/read noise actually present in the pre-lens-shading reference frame — per-CFA-phase Laplacian statistics with tap-level sigma clipping, median binning, and a non-negative (alpha, beta) fit — and logs the measured/profile sigma ratio at the exact operating point of the KernelNet sigma input. Replaces the circular LUT-vs-sigma comparison (the LUT simulates from the same OEM profile): a ratio well below 1.0 proves an over-conservative profile is over-smoothing the merge

### Removed

- Wavelet chroma denoise (darktable profiled à-trous path, strength slider, and `denoise_enabled` / `denoise_profiled_wavelet_strength` preferences): AMaZE output is now always the plain demosaic and the fused JPEG path is always eligible. AI RAW denoise (RawNIND-tiny) is untouched and remains the only denoise stage.
- Slow Kotlin RAW viewfinder fallback sampler (`RawPreviewSampler`), replaced by the native NEON `VfCpuNeon` path

### Fixed

- SR Linear DNG zipper along edges: the KernelNet anisotropic bridge fed the model out-of-distribution full-res mosaiced luma (it trains on quad-mean luma), transposed s1/s2 so every kernel rotated 90° (narrow along the edge instead of across it), evaluated the noise sigma at the frame mean instead of mid-brightness, and scaled precision x4 instead of x2. The bridge now matches the upstream PhotonCamera contract on all four points; kernel inference is also ~4x cheaper (quad-res input) with ~22 MB instead of ~87 MB of scratch
- Residual SR chroma speckle in texture and on weak diagonals: the KernelNet bridge now floors kernel support at σ1σ2 ≥ 0.3 quad², widening sub-lattice kernels uniformly (orientation and anisotropy preserved). Ultra-sharp isotropic triples no longer collapse the per-channel merge to a nearest-tap lottery; strong edges only widen ×1.4 and stay sub-pixel sharp
- Mosaic SR DNG zipper after third-party demosaic: the mosaic chain now clamps every kernel's narrow axis to σ ≥ 0.5 quad px. Sub-lattice across-axes reconstructed single-target-row bright lines and registered each colour's edges on its own sparse taps (~±1 px inter-channel straddle); both read as zipper once demosaiced. Orientation and along-edge smoothing are untouched, and the 1x RGB merge (no downstream demosaic) is unaffected
- SR over-smoothing (both paths): the KernelNet bridge now caps model sigmas at 1.0 (was: saturated 1.9+ in darks/flats, σ ≈ 1.34 mush with shadow mottling) since the multi-frame average already denoises; the joint cap preserves orientation/anisotropy exactly. The kernel-area floor relaxes 0.3 → 0.2 with it (texture σ 0.55 → 0.45, strong edges sharper), keeping the anti-speckle guard
- SR Linear DNG zipper and colour leaks along edges (regression): KernelNet's per-triple width cap and area floor preserve anisotropy, so strong edges kept sub-lattice across-axes (≈0.17 quads) that collapsed each colour channel onto its own sparse taps (~1 px inter-channel straddle plus ringing) — the same failure the mosaic target's narrow-axis clamp already cured. KernelNet fields now floor every kernel's narrow axis at σ ≥ 0.5 quads on both paths (the 1x RGB GPU upload consumed them verbatim); orientation and the wide axis are untouched. Linear provenance is now `RawLens-RawSr/4H-scale1-neutral-highlights`
- Black RAW viewfinder on HALs whose repeating RAW stream stays stillborn (Samsung): after one fruitless request-level recovery with zero delivered buffers, the camera rebuilds once into a compat session (DEFAULT plan without stream-use-case hints, HAL-default frame rate instead of forced 30 fps) instead of retrying forever. Compat mode persists, so later cold starts skip the stillborn attempt; the status shows RAW VF UNAVAILABLE if the compat session also delivers nothing
- RAW stream field diagnostics: each session logs its stream inventory (RAW/preview sizes, queue depth, HAL min-frame/stall timing) and first-buffer arrival, and every preview recovery reports delivered-image / result / failure counters with the session plan, so a stillborn stream is distinguishable from a rendering failure in logcat
- Instant crash on launch on HALs without session-configuration-query support (Redmi Note 13 4G / Snapdragon 685 throws `UnsupportedOperationException` from `isSessionConfigurationSupported`): the session-plan probe now stops and uses the DEFAULT plan without hints, modern session creation falls back to the legacy surface-list API, and any residual failure lands in SESSION ERROR state instead of a FATAL on the camera thread
- Camera route/characteristics re-resolution on the main thread (binder + OEM overhead per call, repeated per lens on every controls publication): resolved routes are now memoized per controller lifetime, which also quiets the Vivo `VivoCameraManager.getIfLiveApp` reflection spam to one probe per lens

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
