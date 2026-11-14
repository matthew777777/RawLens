# Feature and controls reference

See the [README](../README.md) for a quick start and [current repository state](CURRENT_STATE.md) for recent changes.

## Features

### Stills

- Full-resolution `RAW_SENSOR` capture saved as DNG, JPEG, JPEG+DNG, or JPEG XL
- AUTO, PROGRAM, ZSL, and MANUAL exposure modes; single-frame and six-frame (pipelined) burst release
- Optional RAW zero-shutter-lag capture from a bounded pre-shutter frame buffer
- **KB-SR** (the MFSR engine) handheld multi-frame super-resolution (Wronski et al. 2019 / IPOL reference) as a ZSL shutter merge or an offline merge, with Wronski or KernelNet reconstruction kernels
- **HDR+** burst merge, HDR brackets (±2 / ±4 EV, merged or separate DNGs), sequential-DCG trio merge (0 / −2 / 0 EV), and experimental two-exposure RAW HDR
- Offline merges of staged `IMG_<ts>_BURST.craw` takes from the Merges screen (KB-SR, LK-SR or HDR+)
- **LK-SR** Lucas–Kanade burst reconstruction, with independent zoom and sensor / 2× output-resolution controls
- Offline merges default to JPEG, expose JPEG / JPEG XL quality controls, support burst sharing, and retain the staged take’s device orientation
- darktable **inpaint-opposed** highlight reconstruction on normalized Bayer data before demosaic
- RAW development with AMaZE (or RCD) demosaic and AgX Base, Golden, and Punchy looks; contrast, saturation, purity, hue preservation, highlight/shadow range, and gamut-compression controls
- **Ultra HDR** JPEG (SDR base + fitted gain map, ISO 21496-1) and Display P3 output
- **JPEG XL** output (Settings → Photo): the same AgX develop up to 16-bit integer (plus 16/32-bit float) with darktable-style bit depth, quality, effort and decoding-speed options and embedded EXIF/XMP
- Optional lossless **JPEG XL DNG** (Settings → General → DNG compression, off by default): TinyDNG encodes single-frame RAW16 and 16-bit HDR+ mosaics directly with DNG 1.7 version tags and retained EXIF/GPS. Packed input and float-CFA exports keep their uncompressed paths. See [DNG integration](../docs/tinydng-integration.md)
- Denoise: profiled wavelet (Vulkan) and optional AI RAW denoise (RawNIND Bayer), which writes a denoised DNG and develops from it
- Complete capture metadata (aperture, focal length, focus distance, flash, AE/AWB mode, exposure compensation, zoom, lens spec, composite frame counts) frozen at the shutter press, so delayed and offline writers emit the same EXIF as live saves
- Per-camera DNG calibration overrides for black/white levels, noise profile, and color/calibration/forward matrices
- Per-lens vendor keys (OEM request/session keys such as Xiaomi's DCG operating mode)

### Exposure

- **PROGRAM** auto exposure ported from libcamera's Raspberry Pi AGC: Y target with modelled saturation, NORMAL / HIGHLIGHT constraint modes, per-frame exposure filter, flicker rounding; centre, average, spot and highlight-weighted RAW metering; shutter-vs-ISO priority, per-lens ISO/shutter bounds, ISO-lock / shutter-lock, brightness bias, automatic handheld shutter limit
- **ETTR** after Magic Lantern's auto ETTR: 99.9 % percentile of per-pixel max(RGB), headroom 0.5–2 EV, midtone/shadow SNR limits from the Camera2 noise profile; converged ETTR freezes PROGRAM and owns the exposure
- **Active D-Lighting** (Off … Extra high), shared by PROGRAM metering, ETTR headroom and development
- Manual ISO, shutter, white balance and exposure compensation; Auto, Center Weighted, Frame Average and Spot hardware AE metering
- RAW exposure assist (Magic Lantern `zebra.c`): RAW zebras, false colour and a RAW spot meter in EV relative to clipping
- Tap/drag focus and metering targets, optional face-priority AF/AE, focus peaking
- Flash with Open Camera's two-phase precapture wait (Off / On in PROGRAM and ETTR)

### Video

- **RAW Video** in the `.craw` (Cinematic RAW Studio) container: lossless predictive codecs (CRAW Live default: codec 3; CRAW Live v1 — Legacy: codec 2; CRAW Balanced/Rice: codec 1), mandatory chunk CRC32C with optional RAW-frame CRC, crash-recoverable streaming, PCM16 audio, gyro/accelerometer in Android axes, rolling-shutter skew, lens-shading maps (LSHD chunk) and a seek index. Float audio is supported by the format, not current capture. See the [CRAW evidence and reproducible JPEG XL comparison](https://github.com/matthew777777/CinematicRAWStudio-Spec/blob/main/BENCHMARKS.md); isolated codec throughput is separate from sustained camera recording.
- Optional half-resolution binning recorded as demosaiced RGB (best) or CFA (cheap)
- **Encoded Video** with Rec.709, Sony-exact **S-Gamut3.Cine / S-Log3**, HLG and ACES (BakingLab fit) profiles; MHC (default) or super-pixel demosaic; the former video RCD backend has been removed
- User `.cube` LUT library (up to 16 imports, `LUT_3D_SIZE` 16–65) for S-Log3 monitoring during a take, optionally baked into the recording as SDR Rec.709 (`_LUT709`)
- Locks on record (AE / AF / AWB), gyro stabilization v2 with L1 camera-path smoothing and self-calibration, stereo dBFS meters
- Recording health HUD with recent-drop, pressure, thermal, stalled-output and low-space warnings
- Optional preview sleep during recording (tap to wake; manual or 5/10/30/60-second idle timer)
- A/B rack-focus marks with 0.5/1.2/2.5/5-second pulls on manual-focus lenses

### Viewfinder and UI

- Live histogram / waveform scopes (log scale by default), rule-of-thirds grid, rotation-vector level, 2/5-second timer, torch, OIS toggle, Camera2 diagnostics
- Shared translucent overlay style for focus, level, framing guides, zebras and spot meter
- Feature tags (gold = active/manual), lens discs in a recessed track, video quick panel with torch, gallery seat with the last capture's thumbnail
- RAW-capable lens discovery with verdicts from real use, and per-lens RAW size / packed RAW format selection
- Portrait viewfinder layout with rotation-aware controls
- Optional ISP SurfaceView preview, including during LK-SR merges; the camera pauses behind Settings

## RAW ZSL

RAW ZSL is opt-in under **Settings → Burst → Zero shutter lag**, or by selecting the `ZSL` capture mode. The ZSL buffer can be set from 1 to 59 saved frames (16 by default), targeting a 30 FPS stream where the device advertises a compatible Camera2 range. At 30 FPS, 30 frames represent approximately one second of pre-shutter history. The effective ring is clamped to what the HAL buffer queue fits (pairing plus transition headroom is reserved first), so deep requests degrade gracefully instead of failing. JPEG/JPEG+DNG selections are bounded to six frames for development memory; DNG-only can save the whole effective ring. Optionally the shutter dumps the whole ring to one `IMG_<ts>_BURST.craw` for a later offline merge. The RAW badge changes to `RAW • ZSL` after the first paired frame is buffered. `ZSL …` means the buffer is warming, `ZSL OFF` means the setting is disabled, and `ZSL N/A` means RawLens has automatically fallen back to ordinary RAW capture.

The ZSL implementation continuously pairs full-resolution RAW images with Camera2 metadata by sensor timestamp. It requests the fastest advertised compatible AE range up to 30 FPS and retains only the configured number of recent frames, bounded by the configured ring and `ImageReader` capacity. On shutter press, it considers only fresh frames whose exposure and rolling-shutter readout completed by that instant, then scores them using recency, autofocus state, auto-exposure state, lens movement, ISO, exposure duration, and gyroscope motion. Request epochs prevent frames from an earlier control state entering a new buffer.

RawLens uses the Camera2 application-operated ZSL request template where the camera advertises a compatible reprocessing capability, and a direct preview-plus-RAW repeating request otherwise. If the full-resolution stream combination is rejected, does not produce paired frames, or exceeds the memory budget, capture automatically falls back to an ordinary forward RAW request.

ZSL and PROGRAM custom AE are mutually exclusive (enabling HDR+ pulls the mode into ZSL and restores the previous mode when turned off); MANUAL disables exposure compensation and uses the current metered pair when entered. Cameras without manual sensor control keep using Android AE, with PROGRAM locks and limits disabled.

## Current UI

The viewfinder includes the mode switcher, RAW status, lens switcher, manual control chips, focus/exposure metering targets, scopes, guide overlays, quick controls, and shutter. In PROGRAM, tap an ISO/S chip for that axis's slider (Auto releases both locks); hold a chip to lock that axis and let the other adjust; hold the second locked axis to enter MANUAL.

Settings has General, Photo, Video, Exposure, Burst, Merge, Denoise, Lenses, Debug, and About tabs; swiping left or right on empty space flips between them.

| Tab | Contents |
|---|---|
| General | RAW capture, DNG backend and compression, location, viewfinder, settings backup |
| Photo | Photo format, JPEG / JPEG XL quality, Ultra HDR, AgX look, Active D-Lighting, adaptive exposure, highlight handling, demosaic (AMaZE / RCD) |
| Video | Encoded Video (profile, resolution, frame rate, demosaic, crop, gain, LUT library), RAW Video (codec, crop, binning, sound), locks on record, stabilization |
| Exposure | PROGRAM custom AE per lens, ETTR, HDR brackets, two-exposure RAW HDR, face priority |
| Burst | Offline burst staging, zero shutter lag, burst gyro sidecars |
| Merge | HDR+, KB-SR and LK-SR burst merge controls |
| Denoise | Profiled wavelet (Vulkan), AI RAW denoise (RawNIND Bayer) |
| Lenses | Lens discovery and per-lens selection |
| Debug | HDR debug frames, camera diagnostics, app log |
| About | Version, licence and open-source notices, update check (GitHub builds) |

The DNG sensor calibration editor is guided by the active camera's declared metadata. Overrides are stored per Camera2 ID and are injected into native DNG metadata without changing the RAW pixel payload. Resetting a camera returns it to device-declared defaults; undeclared tags are not offered as safe overrides.

A user guide lives in [docs/user-guide/user-guide.md](../docs/user-guide/user-guide.md).

## Codec display names

| ID | Display name | Historical names / aliases |
|---:|---|---|
| 1 | **CRAW Balanced** | CRAW-Fast, Fast |
| 2 | **CRAW Live v1 — Legacy** | CRAW-Live, CRAW-Live v1, Live v1 |
| 3 | **CRAW Live** | CRAW-Live v2, Live v2 |
| 4 | **CRAW Compact — Offline** | CRAW-Compact, Desktop Compact |

These are display names. Codec IDs, encoded bytes, saved numeric preferences
and legacy metadata strings retain their existing meanings. Existing recordings
are not rewritten. Historical benchmark reports keep their original labels;
use the IDs to match them to this table. Balanced describes the intended
size/CPU tradeoff, not a guarantee of the smallest files. Live is the current
recording choice; Live v1 is retained for compatibility and Compact is offline.
