# RawLens 2.0

RawLens 2.0 rebuilds exposure on published camera software, adds handheld
multi-frame super-resolution, and records RAW and Direct Log video faster
and in a new container. The full list is in [CHANGELOG.md](CHANGELOG.md).

## Exposure

- **PROGRAM** auto exposure is now a port of libcamera's Raspberry Pi
  algorithm: steadier convergence, highlight-aware metering and flicker
  handling. A new highlight-weighted metering mode uses all colour channels.
- **ETTR** follows Magic Lantern's auto ETTR: it exposes as far to the right
  as the highlights allow (default 1 EV below clipping, adjustable 0.5–2 EV)
  while keeping midtones and shadows above a noise limit.
- **Active D-Lighting** (Off to Extra high) protects highlights and lifts
  shadows from one control.
- **RAW zebras, false colour and a RAW spot meter** show clipping and
  exposure from the actual sensor values, not the preview.
- **Flash** waits for the camera to finish its pre-flash metering before the
  photo, as Open Camera does. In PROGRAM and ETTR the flash is Off or On.

## Photos

- **MFSR** (multi-frame super-resolution): one press merges the frames
  captured just before it into a single cleaner photo, using natural hand
  shake for sub-pixel detail and leaving out blurred frames or frames taken
  while focus or exposure was still adjusting.
- **HDR+** merges are faster (Fast 4.6 → 3.5 s on the test phone) with
  identical results.
- **Highlight reconstruction** now uses darktable's "inpaint opposed" method
  on the raw data, for more natural clipped highlights.
- **Ultra HDR JPEG**, **JPEG XL** photos and **lossless JPEG XL DNG** output.
- Photos carry complete capture details (aperture, focal length, focus
  distance, flash, exposure compensation and more) in DNG, JPEG and JPEG XL,
  including merged and offline-developed ones.

## Video

- **RAW video** is saved as `.craw` (Cinematic RAW Studio): lossless, about
  5 % smaller with the new default codec, 32-bit float audio that cannot clip
  digitally, and stereo level meters. Binning now records half-resolution
  RGB, so desktop tools no longer show colour fringes.
- **Direct Log** can record with the RCD demosaic in real time: 4K at 24 fps,
  1440p and 1080p at 30 fps. A new **ACES** look is available.

## Other

- New Exposure settings tab, clearer feature tags and lens selector, a video
  quick panel with torch, and gallery access from the camera screen.
- Many reliability fixes, including an MFSR crash when saving JPEG and DNG
  together, and captures that no longer stop when one frame fails.
- Removed: the old RAW super-resolution pipeline (replaced by MFSR) and the
  GALOSH denoiser.

Built with R8; the matching R8 mapping file is attached to this release.
