# Phase 3 — GALOSH in-app UI + denoised DNG (mirrors RawNIND)

Status: **done** (2026-09-26). Classical Vulkan denoise as a capture stage
with the same UX contract as the AI (RawNIND) path.

## User surface

Denoise tab, "Classical denoise (GALOSH Vulkan)" section, mirroring the AI
section: master enable checkbox, save-original-DNG checkbox, three
sliders, fast-upsample checkbox, fast-mode checkbox. Per-lens prefs (`KEY_GALOSH_*`):

| control | range | maps to |
|---|---|---|
| Strength | 0–100% | `strength` 0..1 (0% keeps source) |
| Luma | 0–200% | `luma` 0..2 (100% neutral) |
| Chroma | 0–200% | `chroma` 0..2 (100% neutral) |

Fixed: blind noise fit (alpha/sigma 0), wht=8.

Fast mode (4-phase WHT, `KEY_GALOSH_FAST_MODE`, default off): Phase 5
runs 4 of 16 WHT phases (ox,oy ∈ {0,2}), ~4x faster denoise, ~95 dB
PSNR vs full on the 128x96 fixture — visually identical there, slightly
less smooth on real full-res frames. Outside the Phase 2 parity gate
(parity always runs stride 1). Recommended on Mali-G615, where full-res
full mode takes ~50 s.

## Capture flow

`GaloshSettings` (cf. `DenoiseSettings`) flows MainActivity → controller
(`setGaloshSettings`, frozen at shutter press) → `RawDevelopmentSettings`
→ `RawDevelopmentCoordinator.galoshCaptureCfa` (unpack + pre-demosaic +
one Vulkan run) → `DngSaver.saveGaloshDenoised` (`IMG_<ts>_GALOSH.dng`)
and JPEG via `developCfaJpeg` — the same denoise-once shape as AI.

Precedence: Galosh wins when enabled, AI is skipped for that capture
(single stage; documented in settings kdoc + UI text). Every failure mode
(device, geometry, OOM) falls back silently to the legacy path.

## Guards + Bayer remap (bridge, `GaloshDenoiser`)

o32 shaders hardcode an RGGB slot/sign map, so any non-RGGB effective
arrangement is losslessly reshuffled to RGGB for the run and restored
after (`GaloshBayerRemap`, cf. PhotonCamera's remosaic normalization):
pure 2x2 permutation, physical R/Gr/Gb/B identity preserved (Gr/Gb by
sensor R/B row, not quad position), round-trip is the identity. All four
Bayer patterns at any crop-origin parity work; already-RGGB crops skip
the copy. Even W/H required. Input is normalized float mosaic like the
parity fixtures; values above 1 clip at output (same contract as the AI
path, which coerces to [0,1]).

## Perf: VF pacing + preload + fast mode (2026-09-26)

Full-res runs starved the RAW viewfinder (Slow RAW draws, ~50 s GPU at
4080x3060). Fixes:

- Band pacing: Phase 5 splits into ≤1024-row bands (full-res) with a
  120 ms per-band GPU budget and an 8 ms host yield between bands, so
  the GPU drains VF work between slices. Small frames stay one band.
- Segment timing: native logs `galosh: segments ne=.. bc=.. p5=..
  segd=.. ms` per run; Phase 5 dominates (~70% of GPU time).
- Enable-time preload: toggling GALOSH on warms the Vulkan device +
  SPIR-V modules on a background thread
  (`MainActivity` → `RawCameraController.prewarmGalosh` →
  `RawDevelopmentCoordinator.prewarmGalosh` → `GaloshDenoiser.prewarm`),
  so the first capture skips init (~2.4 s at 128x96 was init, not GPU).
- Fast mode (above): Phase 5 93→25 ms, total 107.3→37.6 ms on 128x96.

## Verification

- JVM: `GaloshSettingsTest` (ranges), `GaloshBridgeTest` (pattern gate,
  `_GALOSH` naming); full `:app:testReleaseUnitTest` green.
- Device: `GaloshDenoiserInstrumentedTest` (4/4) — guards + real
  128x96 run (finite CFA, geometry preserved) + BGGR remap run
  (non-null, pattern preserved) + fast-vs-full (PSNR 94.76 dB,
  > 40 dB gate; full-run change exceeds fast-vs-full diff).
- Native parity unchanged (Phase 2 gate: seed-2 72.96 dB, seed-1 74.65 dB).
