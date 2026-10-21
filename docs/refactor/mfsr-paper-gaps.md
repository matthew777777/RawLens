# MFSR vs Wronski et al. 2019: remaining gaps (audit 2026-10-22)

Done and matching the paper: HDR+ style coarse-to-fine alignment + 3 ICA
iterations, gradient-structure kernels with S.1 heuristics (KernelNet as
our chosen replacement, Wronski kernel selectable), Eq. 1-2 accumulation,
Eq. 5-9 robustness with motion prior and 5x5 minimum, S.3 high-frequency
rejection (mfsr-s3.md), IPOL SNR tuning table, and Sec. 5.2.1 noise
correction now also in the app (in-app Monte Carlo LUT, NoiseLutGen).

## Noise correction (fixed)
- NoiseLutGen ports `monte_carlo.generate_noise_lut` (same model, prior,
  binning, interpolation; different RNG). 2M trials: curves within MC
  error of the 10M Python LUT (sigma^2 median 0.6 %, d^2 1.6 %); merge
  with it vs the Python LUT: 122.6 dB (desktop crop), 111.2 dB max 1.7e-3
  (phone full frame).
- App: generated once per noise profile and cached in cacheDir/noise_lut
  (phone: 1.7 s cold, 1 ms cached); CLI `--lut auto`.

## Still to do (in order)
1. Dim-burst divergence vs the Python reference: 70.5 dB, max 0.195 on the
   IMG_20260921_194618_520 crop (bright burst 107 dB). Pre-existing,
   independent of S.3. Find the low-light stage that differs.
2. App merges at scale 1 only. The paper's resolution gain is at ~1.5-2x;
   consider scale > 1 for zoom captures.
3. Settings that follow IPOL rather than the paper text, to A/B on real
   bursts: swapped s1/s2 (motion regions get s = 2, not 12), and GAT
   before kernel estimation.
4. S.3 thresholds (rho 0.1, K 16) are our measured choice; revisit with
   more bursts.
5. Verify the handling of tiles whose alignment fails outright.
