# RawLens 1.0 — release notes

First public release: a photography-first, fully offline Camera2 RAW camera. No gallery, cloud sync, accounts, or runtime network use.

Requires Android 10+ and a rear camera advertising the Camera2 `RAW` capability (RAW availability is device- and lens-dependent).

## Capture

- Full-resolution `RAW_SENSOR` stills as DNG; JPEG / JPEG+DNG / DNG-only selector; single or six-frame burst release
- Four exposure modes: AUTO, PROGRAM (custom RAW-driven AE), ZSL, MANUAL
- Zero-shutter-lag ring of 1–59 frames (8 by default) with gyro-, focus- and exposure-aware frame picking, hybrid top-up, and automatic clamping to what the HAL buffer queue fits; JPEG selections stay capped at 6 for development memory
- Manual ISO / shutter / WB / EV chips (tap for slider, hold to lock, lock-both enters MANUAL); independent tap/drag AF+AE targets
- Face-priority AF/AE from HAL face detection with tap override; hardened tap-to-focus (bounce/pinch gating, stuck-lock recovery)
- HDR brackets at ±2 or ±4 EV merged into one HDR DNG (or saved as 3 separate DNGs), plus an experimental two-exposure RAW HDR mode and HDR debug frames
- Per-lens DNG calibration overrides (levels, noise profile, color matrices); Android/TinyDNG writer backend with automatic fallback
- GPS geotagging in DNG + JPEG; timer, torch, OIS toggle, grid, horizon/level guide, diagnostics overlay
- Gyro sidecars (`burst.json` + gyro CSVs) saved next to the DNGs in `DCIM/RawLens/`

## Exposure

- PROGRAM per-lens profiles: ISO↔shutter priority slider, center/average/spot RAW metering, ISO/shutter locks, min/max bounds, brightness bias, highlight guard, smooth exposure glide; AE METER tile switches metering
- ETTR RAW-measured single exposure with headroom and ISO ceiling; converged ETTR freezes PROGRAM and owns still exposure

## Multi-frame merges (ZSL)

- HDR+: Vulkan Fast (spatial) / HQ (Wiener) merge to 16-bit `_HDRPLUS` DNG + JPEG; strength slider 1–22 with auto-strength and auto-quality; per-block strength maps; readable EXIF provenance
- RAW super-resolution: Linear and Mosaic merges at 1x or √2 (~25 MP); sharpest-frame reference; reference/decoupled-sharp kernel presets; GPU FFT alignment
- Burst-fusion quick tile; desktop `mosaic` / `linear-sr` / `hdrplus-burst` CLIs merge real bursts on PC

## JPEG engine

- AMaZE demosaic by default with optional RCD (Vulkan) backend; AgX Base/Golden/Punchy looks plus contrast, saturation, purity, hue, highlight/shadow range, and gamut controls
- Adaptive development exposure; highlight headroom + shoulder, sky protection; Ultra HDR, Display P3, quality and 4:2:2/4:4:4 chroma options

## Denoise (all three live in the Denoise tab)

- AI RAW denoise (RawNIND Bayer) with a 0–100% blend slider and keep-original-DNG option
- GALOSH Vulkan RAW denoise: writes a `_GALOSH` DNG and develops the JPEG from it; strength/luma/chroma sliders plus fast paths; wins over AI when both are on
- Profiled wavelet (Vulkan): post-demosaic, JPEG only, sensor-profile driven; automatically off while Galosh is on

## Video

- Direct Log: sensor RAW → 4K P010 → HEVC Main10 + AAC, with BT.709 / S-Log3 / HLG profiles, frame-rate choice, MHC/RCD demosaic, live gain, sound toggle, and thermal step-down
- RAW video (MotionCam container): open-gate crop options, per-frame lens-shading maps, stereo audio; files open in desktop tools / reference decoder (`tools/crs/repair_motion.py` repairs pre-fix takes)
- Stabilized-output option; redesigned video HUD with timecode stats strip

## Viewfinder & scopes

- BGU bilateral-grid engine by default (Vulkan superpixel + fitted look grid, vsync-paced EGL present), with the legacy engine as automatic fallback; the debug overlay tap-cycles AUTO → BGU → BGU_CPU → GPU → CPU
- Up to 1080 long edge; preview follows the capture format (linear RAW for DNG-only, AgX JPEG preview otherwise)
- darktable-style histogram + RGB waveform parade with AgX-matched curve; focus peaking on by default with color cycler

## Robustness & diagnostics

- Session fallback ladder + compat recovery for strict HALs (Xiaomi, vivo, Samsung bake-tested); no black-viewfinder wedges or launch crashes on limited devices
- HDR+ always merges (thin rings top up live); SR merges release camera frames immediately so the ZSL ring survives
- Session log file with rotation, crash capture, and auto-export/share; per-module memory attribution and device-impact probes for leak triage; camera diagnostics screen
- One-tap settings backup (all stores to one JSON); one-time photo-folder grant on first install; fullscreen chrome reliably re-hides after dialogs

Settings live in nine tabs: General, JPEG, Video, Exposure, Burst, Denoise, Lenses, Debug, About.

## Known issues

- During ETTR sessions the live preview renders ~0.5 EV brighter than the capture (the headroom cap applies at develop, not yet in preview)

Full engineering detail: `CHANGELOG.md`.
