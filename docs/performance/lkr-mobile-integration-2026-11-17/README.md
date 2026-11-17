# LK-R Lite mobile integration — 2026-11-17

Integrated into `refactor/2.0` as separate commits:

- `9723ed1b`: cooperative DCT and per-scale adjoint (from `f7b91ee0`).
- `3fb67b7d`: CPU preparation of the next tile during the GPU solve (from `38589825`).

The source branch predates the noise-following DCT look already on refactor/2.0.
The integration preserves total-noise accumulation, luma floor/cap/grain thresholds,
and full-noise chroma in the cooperative implementation. The previous shader is
retained as `dct_x_old.glsl`, including the existing aperture research variant.
`LKRL_DCT_OLD=1` / `debug.lkr.dctold=1` selects the reference implementation;
`LKRL_NO_PREFETCH=1` / `debug.lkr.noprefetch=1` selects sequential preparation.

## Fresh integration checks

Host native build and Android GitHub debug/release builds passed.
The release build produced version 2.3.96 (base code 2396).

DCT GPU parity: compare old and cooperative implementations on identical inputs,
all three channels, four overlap phases, six noise/threshold configurations and
five shapes (8x8, 12x20, 32x36, 64x64, 68x84). Includes inactive lanes,
partial workgroups, deep shadows, total-noise chroma and noise-following luma.
All 30 cases passed on the Mac and connected Mali-G615 MC2 phone. Max absolute
error: host 1.78813934e-7; phone 2.38418579e-7. Raw outputs are in the attached logs.

Prefetch parity: deterministic synthetic four-frame 192x160 burst, six tiles,
two workers, 1x and 2x. Separate processes with/without LKRL_NO_PREFETCH produced
bit-identical outputs on both host and phone.

## Real burst on the phone

Eight-frame car burst from `/data/local/tmp/lkrl/car`, 4080x3060, 1x full frame,
63 tiles, three workers, DCT enabled. Same integrated native runner for all cases.

| Configuration | Merge wall time | Summed tile prior time |
|---|---:|---:|
| Old DCT, sequential preparation | 22.751 s | 3.044 s |
| Cooperative DCT, sequential preparation | 20.698 s | 1.844 s |
| Cooperative DCT, prefetch enabled | 18.101 s | 3.008 s |

These are one run per configuration, not a controlled repeated performance study.
The combined wall-time reduction was 20.4%. Summed tile work overlaps across
workers and must not be interpreted as wall time.

Comparison of all 37,454,400 output floats:

- Old vs cooperative DCT, sequential preparation: max absolute error
  2.98023224e-7, RMS 1.25189931e-8, PSNR 158.049 dB (unit peak).
- Cooperative DCT with vs without prefetch: zero differing floats.

The phone ran the freshly built native code. Updating the installed application
was rejected because the locally built APK used a different certificate. The
user chose to handle release signing and installation themselves; no uninstall
or app-data reset was performed.
