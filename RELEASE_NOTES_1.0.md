# RawLens 1.0 — release notes

First public release: a Camera2 RAW camera with zero-shutter-lag bursts, HDR+ and super-resolution multi-frame merges, RAW/Log video, and a fully RAW-driven viewfinder.

## Capture

- Full-resolution DNG stills, 6-frame bursts, JPEG / JPEG+DNG / DNG-only selector
- Zero-shutter-lag RAW ring (1–30 frames) with gyro-, focus- and exposure-aware frame picking
- Manual ISO / shutter / WB / EV, independent AF/AE targets, OIS, timer, torch, grid, level, horizon guide
- Per-lens DNG calibration overrides (noise profile, color matrices, black/white levels)
- Face-priority AF/AE with tap override; hardened tap-to-focus (debounce, pinch rejection, stuck-lock recovery)
- Gyro sidecars saved next to DNGs in `DCIM/RawLens/`, with a one-time folder grant on first install
- Settings backup: export/import every setting as one JSON file

## Exposure: PROGRAM AE + ETTR

- Custom RAW-driven auto exposure: center/average/spot metering, ISO↔shutter priority slider, ISO/shutter locks, MANUAL seeding, highlight guard, smooth exposure glide
- Per-lens exposure profiles; ETTR takes over still exposure once converged

## HDR+ burst merge

- Vulkan Fast (robust spatial) and HQ (Wiener) merges to 16-bit DNG + JPEG from the quick tile
- Auto mode meters motion, gyro and brightness to pick strength, path, frames, and base frame
- Per-block strength maps with a dense-MAD danger term: HQ-class denoise where still, no ghosts where moving
- Strength slider (1–22), EXIF plus readable provenance in every DNG
- Desktop `tools/hdrplus-burst` CLI merges real DNG bursts on PC

## RAW super-resolution

- Linear and Mosaic burst merges at 1x or √2 super-resolved output (~25 MP)
- GPU FFT alignment, KernelNet anisotropic kernels, measured per-sensor noise LUT
- Sharpest-frame reference and fixed-kernel presets for repeatable comparisons
- ~25% faster merges, safe on 512 MB-heap devices

## JPEG engine

- AMaZE demosaic with optional RCD backend; AgX Base/Golden/Punchy looks plus full tone controls
- Adaptive development exposure; HDR bracket merges get HDR-aware exposure, a capped highlight shoulder, and correct DNG exposure tags for darktable/Lightroom
- AI RAW denoise (RawNIND-tiny); legacy wavelet denoise removed

## Video

- Direct Log: sensor RAW → 4K P010 → HEVC Main10 + AAC, with BT.709 / S-Log3 / HLG profiles and a live EV slider
- RAW video (MotionCam container): per-frame lens-shading maps, stereo audio, files open cleanly in desktop tools / reference decoder (`tools/crs/repair_motion.py` repairs old takes)
- Redesigned video HUD, orientation-aware crop guides, thermal step-down instead of wedging

## Viewfinder & scopes

- Live RAW viewfinder (zero-copy GPU → EGL → native NEON fallback), up to 1080p, tappable engine switch
- darktable-style histogram + RGB waveform parade with AgX-matched curve and automatic RAW/JPEG source
- Focus peaking on by default with color cycler

## Robustness

- Session fallback ladder + compat recovery for strict HALs (Xiaomi, vivo, Samsung bake-tested): no more black-viewfinder wedges or launch crashes on limited devices
- Fixed: white ETTR skies, dark/blown HDR renders, SR zippers/speckle/edge teeth, magenta night dots, black-corner JPEG tiles, magenta binned-sensor JPEGs, AI-denoise maze on non-RGGB sensors
- HDR+ always merges now (thin rings top up live); merges exempt from the 6-frame JPEG cap
- Background session logging with auto-export/share for bug reports

## Known issues

- During ETTR sessions the live preview renders ~0.5 EV brighter than the capture (the headroom cap applies at develop, not yet in preview)

Full engineering detail: `CHANGELOG.md`.
