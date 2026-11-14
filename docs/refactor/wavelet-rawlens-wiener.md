# Profiled wavelet denoise: optimization + RawLens Wiener (2026-11-03)

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Update 2026-11-04: both algorithms now use the revised learned noise model.
The parity and original step-4 behavior below are historical. See the
[PhotonCamera model audit](noise-model-photon-audit-2026-11-04.md) for the current
windowing, gain law, persistence schema, and measured comparison.

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
5. **Compare against PhotonCamera ESD3D2** (after step 4, before speed work).
   Same DNGs (night 181122_683, the 18 test DNGs, own tripod clean/noisy
   pairs ISO 50 vs high ISO as reference) through ESD3D2 and RawLens Wiener;
   metrics: PSNR/SSIM vs clean pair, per-band chroma residual, shadow bias,
   grain look (side-by-side crops), GPU time. Close any quality gap before
   moving on.
6. RawLens speed on Mali (load-bound): fused estimate/apply with ping-pong
   detail, fp16 apply math, skip regression at coarse scales.
   Also the CPU blind noise estimate (125 ms at 12 MP): block subsampling
   (~4x) or moving it to a GPU histogram.
7. Two-pass luma (WHT pilot) if night luma still needs it; learned non-local
   model if the ESD3D2 comparison shows it is worth it.
8. Last, maybe: pre-demosaic (Bayer, GALOSH-RAW style) denoise, only after
   the post-demosaic denoiser is pushed to its limit. Packed RGGB half-res,
   reuse VST/a-trous/Wiener; fixes the clip-before-RCD shadow bias; decide
   placement vs MFSR (per frame vs merged). Python prototype first, port
   only on a clear win.

## Steps 1-3 (2026-11-03, Mac-verified; device run pending)
Night DNG (IMG_20261102_181122_683), production chain clip01 -> RCD oracle -> denoise.
- Step 1 low-res chroma (1/4 grid guided filter, `denoise_img_lowres_chroma.comp`):
  road chroma mid/coarse 0.0031/0.0019 -> 0.0025/0.0007 (RAWR 0.0016/0.0005),
  palm luma detail kept. Weight fades in over relative noise 0.04..0.10
  (synthetic: +3-6% edge chroma error at low noise, nothing to gain there).
- Step 2 exact inverse VST (clip + RCD-aware tables, RCD kappa R/G/B
  0.82/0.71/0.84 from the oracle), faded out below ~2 sigma: mid-shadow bias
  -1.8/-1.4/-3.5% -> +0.1/+0.1/-2.4%; robust to the read-noise floor.
  Remaining deep-shadow blue +13% is in the input (raw clipped at 0 before
  RCD, photo_rcd_input.h): separate future item.
- Step 3 impulse (precondition_rl): 4 sigma, no-PSF-halo test, only at
  relative noise >= 0.10; low-ISO files would otherwise lose real detail.
- RAWR byte-identical 10/10 (RawLens uses separate `_rl` shader variants);
  RawLens tiles 256 vs 1536 byte-identical; GPU == prototype to fp16 level.
- Prototype fix: `guided` rule now applies the 2nd Wiener stage + luma guide
  like the shipped shader (earlier prototype numbers were slightly off).

## Step 4: learned per-device noise model (2026-11-03, verified on device)
`app/src/main/cpp/wavelet/noise_model.h`, used by `choose_noise` in RawLens mode.
- Every shot with a usable blind estimate (>= 3 brightness bins) appends
  (ISO, a, b, bins) to a per-key store, `filesDir/wavelet-noise/<sha>.txt`,
  where the key is camera id, physical id, pixel mode, quad Bayer, binning,
  raw size and the build fingerprint (a firmware update starts a fresh model).
  The newest 400 samples are kept; the file is rewritten atomically.
- Fit (once >= 6 samples over >= 3 ISOs spanning >= 2x): a = A*ISO + B,
  b = C*ISO^2 + D, by relative-error weighted LS, non-negative, with 3-MAD
  log-residual outlier rejection. Max analog ISO isn't plumbed, so digital
  gain is folded into the ISO^2 term.
- Per shot: the model (fitted before adding this shot) is the prior, and the
  shot's estimate is evidence with weight bins / (bins + 8), mixed in log
  space and clamped to 0.5-2x of the model. Until the model is ready, the old
  estimate-or-profile rule applies. Logged as `WaveletNoise model ...`, plus
  `estWeight`.
- Real DNG estimates support the linear a(ISO): Redmi ISO 50 a = 1.21e-5 and
  ISO 4523 a = 1.40e-3 (ratio 116 vs ISO ratio 90). Motorola ISO 1463/1600
  agree (3.9e-4 / 3.8e-4). One vivo ISO 50 frame estimates 8x the other five
  (a busy frame), which is exactly what the outlier rejection and the mixing
  handle.
- Synthetic ISO sweep (Redmi ISO 50 frame, noise added to an a/b truth,
  ISO 50-6400, plus an injected 8x outlier): fitted a within 1-2% of truth at
  ISO 50/400/4523, outlier rejected. b at high ISO is about 40% low, but that
  comes from the per-shot estimator (it inherits the b bias of the
  estimates); it only matters in the deepest shadows.
- Device (Redmi Note 15 Pro): `learnedNoiseModelStoresSamplesAndApplies`
  passes. The store fills, and the model turns on at the 7th shot (6 samples,
  6 ISOs) with estimate weight 0.38. All wavelet device tests pass; RAWR and
  RawLens timings are unchanged.
- Blind estimate CPU cost: 8 ms at 1 MP and 125 ms at 12 MP, which is over the
  30 ms budget.
- Open: estimator cost (moved to step 6); max analog ISO (dGain^2 term).

## Step 5: RawLens vs PhotonCamera ESD3D2 (2026-11-03, Mac)
`tools/wavelet-parity/esd3d2_port.py` ports ESD3D2 to NumPy: the default
"steered" shader, the scale-down colour pass and the guided upsample, with
default tunables. It gets the same noise a/b as RawLens.
`esd3d2_compare.py` drives the chain clip01 -> RCD oracle -> {RAWR, RawLens
(steps 1-3 + host gates), ESD3D2}. PNGs: `~/Downloads/*_esd3d2_compare.png`.

| frame | crop | colour noise (in / RawLens / ESD3D2) | grain in flat areas (RawLens / ESD3D2) | texture kept (RawLens / ESD3D2) |
|---|---|---|---|---|
| night ISO 4523 | region | 0.0553 / 0.0439 / 0.0431 | 0.0226 / 0.0157 | 0.92 / 0.79 |
| other phone ISO 1442 | flat | 0.0131 / 0.0050 / 0.0016 | 0.0092 / 0.0067 | 0.79 / 0.57 |
| other phone ISO 3210 | flat | 0.0051 / 0.0025 / 0.0017 | 0.0038 / 0.0024 | 0.88 / 0.58 |
| motorola ISO 1463 | flat | 0.0137 / 0.0044 / 0.0013 | 0.0074 / 0.0065 | 0.66 / 0.55 |
| vivo ISO 72 | flat | 0.0172 / 0.0109 / 0.0103 | 0.0109 / 0.0057 | 0.94 / 0.79 |
| Redmi ISO 50 | flat | 0.0035 / 0.0023 / 0.0020 | 0.0025 / 0.0013 | 0.95 / 0.54 |

Findings:
- ESD3D2 is smoother everywhere: it removes 30-50% more fine luma and keeps
  much less texture (wood grain, gravel). RawLens keeps grain by design.
- **Gap to close: chroma at mid noise (relative noise 0.04-0.10).** ESD3D2
  leaves 2-3x less colour noise on flat areas of the ISO 1442/3210/1463
  frames. Our low-res chroma pass is gated off or weak there (weight
  0.0-0.4); at high noise (night) the two are equal.
- ESD3D2 artefacts: a magenta fringe along the bright edge (ISO 1442 detail),
  and a hue shift on the night road (warm -> grey-purple). Both come from its
  luma-renormalized colour upsample.
- RAWR with the wrong Motorola profile wipes all texture (texture kept 0.006).

### Step 5 fixes from the comparison (2026-11-03, Mac-verified; device run pending)
High noise only (relative noise >= 0.10, the impulse-filter gate); RAWR is
byte-identical (10/10).
- 5a, neutral specks (`backtransform_buffer_rl`): a luma dip through the
  per-channel inverse VST emptied the weakest (red) channel first, so specks
  came out teal (R/G/B 0.23/0.50/0.40 of local, 12.6% clipped to 0). Dips now
  take their colour at the local 3x3 median luma (rank 4 of 8) and keep their
  own brightness. Specks are neutral: 0.39/0.48/0.48, none clipped.
- 5b, fewer specks: leave-one-out luma energy in `wiener_apply` (looY), plus
  a two-sided luma despeckle in the VST domain: |Y - med8| > 3 sigma on a flat
  ring (median |n - med8| < 1.5 sigma). On the night clouds, dark specks drop
  from 2374 to about 290 (ESD3D2: 573).
- Night region after 5a/5b: colour noise 0.0424 (ESD3D2 0.0431), flat luma
  0.0163 (0.0157), texture kept 0.825 (0.789), removed noise vs edges 0.045
  (0.220). GPU vs prototype: mean abs 8.6e-5; tiles 256/1536 byte-identical.
- 5c, correlated noise (HDR+): the 2x2-binning variance ratio separates the
  files cleanly (HDR+ 3.1-3.9, single-frame DNGs 1.0-1.4; vivo base ISO reads
  5-8 from texture). A noise boost from it, global or coarse bands only, did
  not close the gap and smeared texture. ESD3D2's remaining edge on HDR+ is
  rendering, not noise level: its structure-tensor steered kernel keeps
  oriented low-contrast texture (grass lines) crisp and leaves fine,
  even-looking grain. `correlationRatio()` is kept in noise_estimate.h but
  not wired in.
- Open: an edge-steered pass for low-contrast oriented texture (the HDR+ gap),
  and the mid-noise chroma gap (low-res chroma gate 0.04-0.10).

### Beat-ESD3D2 scorecard (2026-11-03, in progress)
`tools/wavelet-parity/esd3d2_compare.py` prints `SCORE` per crop. RawLens has
to win or tie ESD3D2 on all four: chromaticity noise on flat areas (std of
log R/G, log B/G; the brightness-independent version, since grain on a
coloured surface faked colour noise), texture fidelity min(t, 1/t) (ESD3D2
adds content: t > 1 on detail crops), flat luma at equal fidelity, and
removed-noise vs edges. ESD3D2 outputs are cached.
- Root cause of the mid-noise colour gap: not the chroma bands (forceUv and
  slope eps changed nothing). In the VST domain a pure brightness change
  scales Y, U, V together; denoised U, V under grainy Y gives
  brightness-dependent colour, and ESD3D2 removes all chroma < 14 px.
- 5d (all noise levels): the low-res pass now stores the 1/4-grid luma->chroma
  model (aU, bU, aV, bV), and the backtransform evaluates it per pixel at the
  local median luma (guided upsample). Then U, V are scaled by Y/median
  (neutral = 3). Flat chroma: ISO 1442 0.0288 -> 0.0095 (ESD3D2 0.0084),
  Motorola 0.0401 -> 0.0118 (0.0111), Redmi ISO 50 0.0186 -> 0.0065 (0.0123).
- 5d refined: the colour reference luma is a 5x5 mean on flat areas, or the
  3x3 median within 2 sigma of luma edges, and colour is taken in the linear
  domain (neutral = 2: reference colour, pixel brightness). Night clouds,
  chroma < 2 px: 0.059 -> 0.014 (ESD3D2 0.004). Flat chroma now beats ESD3D2
  on 7 of 8 flat crops/regions (night region 0.294 vs 0.262 is the
  exception).
- Wins 5/15 crops (was 3). Open losses: low-ISO detail crops (grain slightly
  above ESD3D2, removed noise correlates with edges), night region chroma
  (0.294 vs 0.262), Motorola texture fidelity, vivo luma.
- Prototype (`rawlens_denoise_proto.py`) still has the old low-res
  correction semantics: parity check pending.

## Step 6: edge-steered luma + uniform film-like grain (2026-11-03, Mac-verified)
User goal: a natural, unified grain floor (film-like), and beating ESD3D2 on
every image.
- Grain used to be per-band `keep x removed noise`, so it showed only where
  the Wiener filter judged an area flat: patchy. Now (neutral >= 10) the
  Wiener stage writes `grain x noisy fine band` (scales 0 and 1) to accum.w
  and outputs denoised luma only. The backtransform runs a structure-tensor
  steered 5x5 luma filter (along the edge sigma 1.5; across sigma 1.2 -> 0.4
  with coherence; range 1.5 sigma), then adds the grain back. That gives one
  noise floor everywhere: in the VST domain the grain has constant amplitude,
  so in linear it follows sqrt(signal), like shot noise or film grain.
- Colour: brightness is matched in the VST luma, mapped through the power law
  (camera-space weights shifted perceived brightness on coloured detail).
- Default grain 0.25 -> 0.45 (stored user preferences keep their value).
  Host: neutral = 12, lowres = 1.
- Scorecard 6/15 wins (was 2): both HDR+ crops, mid-noise flats, Redmi ISO 50
  flat, vivo ISO 72 flat. Remaining losses are mostly the luma-grain criterion
  on detail crops: the film-grain floor is grainier than ESD3D2's smoothing
  there by design. Plus night-region chroma, and Motorola flat texture/edges.
- RAWR byte-identical 10/10; tiles 256/1536 byte-identical. The prototype has
  not been updated for steps 5d/6 (GPU only so far).

## RawLens v2 (from scratch, luminance/colour split): prototype (2026-11-03)
`tools/wavelet-parity/rawlens_v2_proto.py`; scorecard: `RL_V2=1 RL_V2_PRM='{...}'`
(v2 replaces the RawLens column; the current GPU path shows as "RawLens v1").
- Pipeline: WB'd linear -> L = sum q_c wb_c x_c (analytic aL, bL with WB +
  RCD kappa) -> single-channel GAT -> B3 a-trous Wiener (leave-one-out,
  2nd stage), per-scale noise = white model x image-measured factor (flattest
  blocks) -> steered 5x5 -> grain = fine bands of (input - clean) -> closed-
  form unbiased GAT inverse. Colour: 1/4-grid guided RGB-on-L model at an
  edge-aware smooth luma, scaled to the final luma.
- Fixed along the way: grain taken from the noisy fine band amplified
  texture 1.45x where the Wiener kept the signal. Taking it from the residual
  gives luma texture fidelity 0.999.
- Status: v2 straddles ESD3D2 on the luma/texture trade-off, but is not ahead
  yet (0-1 wins vs v1's 6). fmax 1.5 + grain 0.6 is close to ESD3D2 on flat
  texture (0.51-0.59 vs 0.54-0.58) with equal or lower grain.
- Open, in order:
  (1) colour on detail crops: vivo ISO 72 detail chroma 0.28 vs 0.097, ISO
      1442 0.317 vs 0.263; check clipped highlights / dark fallback.
  (2) removed vs edges on flats 0.19-0.28 vs 0.13-0.16: the single-channel
      Wiener removes edge-correlated content; try edge-aware a-trous weights
      (as RAWR had) or a smaller steered range.
  (3) then retune luma / grain / fmax.
