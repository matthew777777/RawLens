# Profiled wavelet denoise: optimization + RawLens Wiener (2026-11-03)

## RAWR parity before changes
Re-checked against RAWR HEAD `e7c844192008e1006d48ef85d931bf298e928192`:
all seven sources byte-identical, 10/10 GPU cases byte-identical.

## Optimized RAWR mode (output byte-identical to RAWR)
Source identity with RAWR is intentionally given up; output identity is kept and
gated by `tools/wavelet-parity/compare_optimization.py` (RAWR reference build vs
ours, same GPU): 10/10 cases byte-identical, see
`results/wavelet-wiener-2026-11-03/rawr-bit-parity-optimized.json`.

- Apply pass no longer recomputes the 25 edge-aware taps; it is fused into the
  next scale's estimate (last scale: tap-free apply). Same values, same order.
- Accum clear removed (first apply writes).
- Partial edge tiles are cropped to inner+2H (inner pixels never read beyond it);
  statistics keep RAWR's partial-sum order (full-extent stride, skipped zero groups).
- Tile scratch never exceeds frame+2H.

Host GPU (MoltenVK) 4208x3120, 7 scales: 790-820 ms -> 292-307 ms (2.6-2.7x);
512x512: 7-10x.

## RawLens Wiener (new default)
Same VST, Y0U0V0 and edge-aware a-trous; only shrinkage differs. One pass per
scale (no statistics, no threshold pass, no detail image => one fewer tile image):

- Local Wiener gain from 3x3 detail energy (shared-memory apron) with the known
  per-band noise variance; closed-form second stage (signal estimated from the
  stage-1 estimate's energy). Chroma energy is leave-one-out (isolated chroma
  spikes cannot protect themselves); luma keeps the centre (texture).
- Luma-guided chroma, multiplicative: luma structure amplifies chroma's own
  signal evidence (s2_uv *= 1 + s2_y/n2_y). An earlier additive prior scored
  better on synthetic tests but kept colour speckle on real luma texture
  (gravel) in IMG_20261102_181122_683 (ISO 4523): flat road chroma 0.0142 vs
  0.0068 now, RAWR 0.0055; palm-frond detail 0.0194 now vs RAWR 0.0148.
- Chroma noise prior 2x stronger at scales >= 2 (blotches) than at 0-1 (detail).
- Grain: fraction of removed scale-0 luma noise kept (0.4x at scale 1). Real
  sensor noise in the VST domain: tonally even, monochrome. Not synthetic grain.

Real ISO 4523 night frame (half-res debayer, GPU): chroma speckle about RAWR
level, clearly more foliage/fence/gravel detail, film-like fine luma residue.
Synthetic results below were measured with the earlier additive guide; with
the final multiplicative guide synthetic chroma-edge error is about RAWR's.

Quality (synthetic Poisson-Gaussian noise on low-ISO DNGs, 2x binned ground truth,
`tools/wavelet-parity/rawlens_denoise_proto.py`): vs RAWR at defaults, chroma edge
error -28..-45%, chroma detail retained 0.96-0.98 (RAWR 0.89-0.99), flat chroma
noise equal or lower, luma error equal or lower with finer residual.
GPU output matches the prototype (mean |diff| 3e-4, fp16 storage); 256 and 1536
tiles produce identical bytes. Host GPU 4208x3120: 367 ms.

## Host / capture-to-file
- Denoise writes packed RGBA16F straight into the readback staging buffer
  (`denoise_img_backtransform_buffer.comp`): no full-frame output image
  (-8 B/px, ~100 MB at 13 MP) and no image->buffer copy.
- Capture sharpening + denoise run in one submit (no intermediate
  download/upload/memcpy); falls back to separate sharpening if denoise fails.

## On-device verification (Redmi Note 15 Pro, Mali-G615, 2026-11-03)
Headless: `./gradlew -PwaveletCheck :app:connectedGithubDeviceTestAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=com.matthew.rawlens.WaveletDenoiseDeviceTest`
(isolated `.waveletcheck` package; the user's app is untouched). 3/3 runs pass:
both algorithms denoise, fused sharpen+denoise == sequential byte for byte.

| 4080x3060 | GPU ms | wall ms |
|---|---|---|
| RAWR (optimized) | ~1200-1370 | ~1300-1500 |
| RawLens | ~3120-3210 | ~3350-3460 |

RawLens is ~2.6x RAWR on Mali (only ~1.5x on the Mac GPU): step 5 matters.

Bugs found on device (MoltenVK accepted both):
- Descriptor pool had 2 storage-image descriptors too few for the buffer
  backtransform set -> init failed. Fixed.
- RAWR then RawLens at 12 MP on one VkDevice reproducibly gave
  VK_ERROR_DEVICE_LOST (any other order, a fresh device, or a 5 s pause did
  not). Not a long-submit watchdog (still failed with one tile per submit),
  not pipeline state (still failed with a rebuilt DenoisePipeline), no OOB
  found in the shaders. Enabling `robustBufferAccess` removes it at no
  measurable cost, so a buffer access out of range is likely somewhere (driver
  or ours): open item. Also kept: scratch sized for the larger halo in both
  modes (no reallocation on algorithm switch) and one tile per submit (short
  GPU jobs for viewfinder responsiveness; no wall-time cost).

## Item 1: luma-guided chroma regression (2026-11-03)

Inspired by GALOSH (arXiv 2607.03768) and luma-guided chroma NR patents.
Per wavelet band, in `denoise_img_wiener_apply.comp` (separate pipeline so its
shared memory does not lower occupancy of the RAWR passes):
chroma = a * Y_denoised over a 5x5 window, a = cov/(var + 4*n2_y); the
unexplained remainder is Wiener-shrunk by chroma's own evidence; result clamped
to the local 3x3 noisy chroma range. Wiener now runs estimate (atrous mode 2,
writes detail) + apply per scale; halo +3 px per scale; detail image reused.

Synthetic (two low-ISO DNGs, heavy/mild noise) vs previous Wiener: chroma error
-14%/-16%, chroma edge error -23%/-30%, flat chroma noise -9%/-11%; now ahead
of RAWR on every chroma error measure. Real ISO 4523 frame: fronds keep more
colour; flat road chroma 0.0080 (previous 0.0068, RAWR 0.0055).
GPU vs prototype mean |diff| 4e-4 (126 px in clipped highlights differ more:
fp16 detail at very high VST values); 256/1536 tiles byte-identical.
Host GPU 4208x3120: Wiener 426 ms; RAWR mode 287 ms (RAWR 742 ms), RAWR output
still byte-identical (10/10, fresh RAWR build).
Full guided-filter averaging of (a,b) was within ~1% and not worth a 9x9 apron.

## Item 3 + adaptive strength: blind noise estimate (2026-11-03)

`app/src/main/cpp/wavelet/noise_estimate.h`, called by the host in RawLens mode
before upload. Native green CFA sites only (raw samples after demosaic):
residual vs 4 diagonal greens, 8x8 blocks per green lattice, per-brightness-bin
10% quantile of block variance (chi^2 corrected), weighted LS fit var = a*x + b.
Policy: keep the Camera2 profile when it agrees within 2.5x (variance at the
median exposure); otherwise, or when missing, use the estimate. Luma noise prior
scales by noise visibility at the median exposure: clamp(rel/0.08, 0.4, 1).
Chroma keeps full strength. RAWR (exact) mode is unchanged. Logged as
`WaveletNoise`.

Findings on device DNGs:
- Base-ISO Camera2 profile overstates real noise ~10x in variance: five flat
  sky patches of IMG_20261102_165507_615 (ISO 50) measured 0.10-0.11x the
  profile. This caused most of the low-ISO detail loss. Estimate is within 1.2x
  of the sky measurement.
- ISO 4523 night frame: estimate agrees with profile (1.25x), profile kept.
- Other phone (ISO 1442/3210): profiles 500-9000x too low, so denoise did
  nothing before; now uses the estimate.

Results (GPU bench, app-equivalent noise and lumaScale):
| frame | RawLens texture kept | removed-layer/edge correlation |
|---|---|---|
| ISO 50 landscape (before 98.2%) | 99.2% | 0.26 |
| ISO 1442 other phone (before: untouched) | 93.7% | 0.003 (pure noise) |
| ISO 4523 night | unchanged decision | |
On the ISO 1442 frame with corrected noise, RAWR removes visible structure
(corr 0.28) while RawLens removes noise only.

## Revised plan (2026-11-03)

0. ~~On-device verification (Mali) + commit.~~ Done (see above); open:
   root cause of the robustBufferAccess-masked device loss.
1. Coarse chroma: luma-guided regression on the downsampled residue (night
   gravel speckle, large blotches).
2. Exact unbiased inverse VST (Makitalo-Foi) for deep shadows.
3. Impulse (isolated speck) stage, high-noise only.
4. **Learned per-device noise model** (from PhotonCamera's NoiseModeler and
   DynamicNoise, GPL-3, github.com/eszdman/PhotonCamera):
   - Estimator: second gated regression pass (keep bins consistent with
     pass-1 model), read-noise floor, clamp of per-shot deviation from model.
   - On-phone calibration store per camera id: per shot (ISO, max analog
     ISO, a, b, confidence = bins). Fit S(ISO) = A*ISO + B,
     O(ISO) = C*ISO^2 + D*dGain^2 (dGain = ISO / max analog ISO) with
     outlier rejection once >= 3 distinct ISOs / enough shots.
   - Per shot: calibrated model as prior, this shot's estimate as evidence,
     combined by confidence (few flat areas -> trust model); fall back to the
     current estimate-or-profile rule until calibrated. Log `WaveletNoise`.
   - Guards: key by camera id + raw mode (HAL raw NR differs between apps and
     modes); forget samples after firmware/fingerprint change; never let one
     shot move the model more than a bounded step.
   - Validate: replay the 18 test DNGs (Redmi, vivo, other phone); Redmi
     model must reproduce the ~10x base-ISO correction and keep the ISO 4523
     profile; measure estimator CPU time on device (GPU histogram if > ~30 ms).
5. RawLens speed on Mali (load-bound): fused estimate/apply with ping-pong
   detail, fp16 apply math, skip regression at coarse scales.
6. Two-pass luma (WHT pilot) if night luma still needs it.
7. Own clean/noisy tripod pairs (ISO 50 vs high ISO) as the benchmark.
8. Later: pre-demosaic denoise; learned non-local model.
