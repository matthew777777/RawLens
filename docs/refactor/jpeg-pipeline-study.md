# JPEG pipeline study (AgX, auto exposure, AMaZE, quad Bayer, HL, speed)

## Capture-to-file timing baseline (build 418, Xiaomi 25080RABDG, 12 MP)

AUTO, JPEG only, AMaZE, highlight reconstruction ON (user setting):

| Stage | Time |
|---|---|
| RAW prep + adaptive exposure | 15-60 ms |
| GPU develop ("GPU+output") | 11.5-19.2 s |
| of which HL reconstruction | 3.3-9.7 s (scales with clipped ROI) |
| JPEG encode + save | 0.37-0.50 s |

Isolated AMaZE (PhotoDemosaicKodakDeviceTest, 12 MP mosaic, AmazeProfiler):
2.9 s; heaviest passes hvwt 443 ms, gcorr 393, nyqref 281, rbpm 249,
gradcd 223 (12 tiles of 1024 px, RGBA32F scratch). Heavy Java GC churn
during the develop (70-220 MB freed repeatedly, heap ~500 MB): full-frame
CPU round trips around HL (readback, convert, per-tile upload/download,
pack).

## AgX: PASS

RawLens pins Google Filament ToneMapper.cpp @2a8018f; the fetched file's
SHA-256 matches the pinned 1e3212b6...aa42cd. Filament's mat3f lists
columns; RawLens rows equal Filament columns: inset diff 0, outset (=
inverse of Filament's OutsetInv) 4e-16. Official-base path vs Filament +
colour-science matrices over 20 000 random scene colours (-12..+10 EV):
max 1.9e-4, mean 5.4e-5 (one 8-bit step = 3.9e-3). ACEScg->Rec.2020
(Bradford) and Rec.2020->sRGB differ from colour-science by ~2e-4
(primaries rounding); Display P3 by 8e-11. GLSL agx_srgb8.glsl: every
matrix is the exact transpose of the Kotlin reference (<=3e-16), all
constants present; HDR tail slope 0.1702 = d(contrast)/dx at 1 (checked).
darktable's agx.c is a different parameterisation (not the pinned base).

- AgX-1 (open, minor): "Reset official AgX Base" keeps the current
  highlightShoulder (default 1), so the reset is not pure Filament AgX.

## Auto exposure (scene-referred): PASS

Trimmed log-average (p5..p95) to 0.18 (Reinhard et al. 2002 key value; the
HDR+ metering basis), p99 hard white guard (1 % clip budget) and p95 soft
guard for broad highlights (HDR+ protects highlights first), low-key
damping and a night tier. Meters before AgX in scene-linear, with the AgX
shoulder applied to samples so the meter sees what AgX sees.

- AE-1 (fixed) log2 of a zero sample made the log-average -inf (+2.5 EV:
  max lift plus the HDR-scene ceiling); a negative made it NaN. Latent:
  all three callers filter > SHADOW_FLOOR, but analyzeSamples did not
  enforce it. Samples are now compacted to finite positives first
  (AdaptiveExposureMeterableSamplesTest); unit suite unchanged (1556 pass).
- AE-2 (observation) the meter averages un-white-balanced per-CFA samples,
  not luminance: ~0.4 EV constant bias for a neutral under daylight gains
  (absorbed by tuning), ~0.07 EV daylight vs tungsten. Kept.

## AMaZE: PASS (production verified against RawTherapee)

references/RawTherapee/amaze_demosaic_RT.cc is byte-identical to upstream
RawTherapee dev @b2ee504c. tools/build_amaze_reference.py strips includes
and applies two patches; both fix real upstream undefined behaviour:
the last-tile pad loops write up to 15 rows past rgbgreen into the next
region of the same calloc block (rrmax can reach ts-1 while the loop writes
16 rows; invisible to ASan, but the output changes at exactly those sizes:
130x130, 278, 1030x774 differ, 150/290/406x306 do not), and a reused
nyquist2 buffer read as flags without clearing.

- AMZ-1 (open) The repo's AMaZE parity tests (RawTherapeeAmazeInstrumentedTest,
  AmazeParityDebugTest) test amaze/ordered_reference.glsl, a serial
  test-only shader production never loads. The production 12-pass tiled
  pipeline had no parity test. Now measured: PhotoDemosaicKodakDeviceTest
  output vs the host RT reference (rawData scaled 0..65535 as RT expects;
  outputs /65535) on Kodak:
  - half brightness (no clip, no white window): identical PSNR (+-0.02 dB),
    mean |diff| 1e-4..4e-4, p99.9 0.007-0.025;
  - full brightness (+ the final.glsl white-window tail applied to RT):
    identical PSNR, mean 2e-4..1.5e-3, p99.9 <= 0.093.
  Rare larger single-pixel differences are AMaZE's binary Nyquist/direction
  decisions flipping on fp32 (GPU) vs CPU rounding and the RGBA16F store.
  Should become a production parity test.

## Quad Bayer: PASS (faithful to PhotonCamera, with a PhotonCamera bug fixed)

shaders/quad vs PhotonCamera @b2eff82 demosaicp0quad / p12quad / p2quad,
line by line: same green taps, inverse-sqrt gradient-energy weights
(eps 1e-4), 5x5 Gaussian (sigma 1.5) H/V decision, same pixels
interpolated; same colour taps (offsets -1-shift / 2-shift), green-ratio
gate 0.01..0.99 with linear fallback. Generalised to every Bayer order and
crop phase; same-colour border clamp checked.
- Q-1 (doc) PhotonCamera's p12 energy loop reads neighbour gradients
  without removing its +0.5 storage offset ((g+0.5)^2 instead of g^2);
  RawLens uses centred gradients throughout. Correct, but the shader's
  "adapted from" comment should say it deviates.

## Highlight reconstruction (colour propagation): PASS

references/RawTherapee/hilite_recon.cc is byte-identical to upstream @b2ee504c.
The existing HL test goldens were "machine-generated" from an oracle kept
in /tmp (not in the repo, unreproducible). New oracle: tools/hl_oracle
builds the verbatim HLRecovery_inpaint body (blur 2 = HLBL_DEFAULT, with
guided filter, box blur, LUT, sleef, rescale, array2D from RT at the
pinned commit, SHA-256 checked) and a driver that converts/packs exactly
like HlReconstructionStage. HlOracleHarnessTest runs the production stage
(CPU path) on the same input. 4 clipped Kodak scenes (26-42 % clipped):
max |RawLens - RT| 0.0005-0.0008 (half-float storage), same pixels
changed, identical error vs the unclipped truth.
RawLens chmax = white, clmax = white - black: RT's thresholds reduce to
clmax * pct either way, so this matches RT's behaviour on clipped frames.

## Speed: HL (step 2)

Baseline (build 420, HlDeviceParityDeviceTest, production Vulkan path,
4-worker pool, 12 MP fully clipped scene): 8.76-9.41 s
(convert 0.3, stage 2.7, GPU tiles 5.4, pack 0.2 s). GPU output vs the
RawTherapee oracle on the Kodak scenes: max 0.0005-0.0008 (half floats).

- H1 (done) GPU tile upload/download streamed through the pooled staging,
  one row at a time, instead of building full-image FloatArrays per tile
  (interleaveRoi/interleaveMaps ~39 MB each, flat() copies, a fresh direct
  buffer + FloatArray per download). Output byte-identical to the baseline
  on all 5 scenes; 12 MP: 7.13-7.49 s (-20 %); per 1568^2 tile up
  333->109-236 ms, down 210->66-122 ms. Dispatch now dominates (340-710
  ms/tile).
- Profile of the CPU pre-stage (host, JFR): computeVariation 39 %
  (inclusive, mostly boxBlur2; computed twice per tile: reduce and gate),
  reduceOne loop 18 %, convertTap 9 %, accumulateRgb 9 %.
- H2 (done) pre-stage variation computed once per tile: pass 1 parks each
  tile's variation in a direct buffer (off the Java heap, ~65 MB at 12 MP,
  released tile by tile in pass 2) instead of pass 2 recomputing it.
  Byte-identical on all 5 scenes; gate pass 1.13-1.33 s -> 0.69-0.82 s,
  pre-stage 2.4-2.9 s -> 1.8-2.2 s. Worker pool 4/6/8 measured: no gain
  (kept 4). VkBound slice budget measured: 4x larger -0.04 s, unsliced
  -0.4 s but hangs; slicing kept for the viewfinder.

## Capture -> file breakdown (after H1/H2, build 426, AUTO JPEG, AMaZE + HL + wavelet on, 12 MP)

| stage | ms |
|---|---|
| AMaZE (queued ~230 ms, finishes inside the readback wait) + readback | 2840-2980 |
| HL reconstruction (scene: most of frame clipped) | 5260-6070 |
| Wavelet denoise (CPU) | 3630-4350 |
| upload + camera->ACEScg | 255-665 |
| output chain (colour, AgX, JPEG base) | 290-360 |
| JPEG save | 400-635 |

The colour/AgX output chain is not a target; HL, wavelet and AMaZE are.

## Direct-Log MHC (real frame IMG_20261019_142925_030_VID_frame0.dng)

Display error vs RawTherapee AMaZE on the frame (camera WB + matrix + sRGB):
ours (heuristic MHC) 0.0225, textbook MHC 0.0237, RCD 0.0161.
- Water "cyan" is demosaic aliasing on 1-3 px unclipped glints (0.09 %
  photosites clipped; 94 % of the error away from clip), not highlights.
- MHC heuristics KEPT: on the glints they clearly beat textbook MHC (which
  rims every glint blue/orange); worst-case regression (sand) is invisible.
  They cost ~5.5 ms/4K frame (33.2 vs 27.6 ms fp32).
- 3x3 chroma median (dcraw median_filter / RT false colour suppression,
  pre-WB) removes most dots (0.0225 -> 0.0206) but costs +20-28 ms/frame
  on Mali in every fused form tried (shared tile + barrier); a separate
  pass needs the full RGB round trip. Parked on branch
  future/mhc-false-color (CPU twin, both shaders, real-frame device test).
- Fused record shader: fp32 selected even where f16 math exists (measured
  slower on the Mali test phone: 35.0 vs 33.2 ms; MHC-only 31.5 vs 26.4).
  fullResFusedProof still misses its 30 ms margin (32.9-33.2 ms vs the
  33.3 ms frame): MHC itself (~26 ms) is the remaining lever.
