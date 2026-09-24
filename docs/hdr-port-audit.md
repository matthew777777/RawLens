# HDR port audit

## Source boundaries

Darktable merge equations were checked against `src/control/jobs/control_jobs.c` at
`52435b9a0c6bcf470f683e0c4455ecd321b9aec5`. Float DNG serialization is adapted from
`src/imageio/imageio_dng.c`, not from the merge function's file. The implementation is
a Kotlin adaptation, not a verbatim compilation of Darktable's desktop pipeline.

PhotonCamera's checked-in FlowNet model, JNI runtime and custom NCNN layers are compiled
directly. RawLens supplies the normalized Bayer proxy renderer and warp adapter.
The vendored files record their independently verified PhotonCamera source commit in
`app/src/main/cpp/flownet/UPSTREAM.md`; builds no longer require a local reference checkout.

## Corrections

- Apply sensor black/white normalization before merge; bake lens correction after merge.
- Include focal length in Darktable's aperture-area calibration and clamp final negative noise.
- Use darktable's f/22 + 8mm fallbacks when EXIF aperture/focal length are missing.
- Preserve CFA parity even when a warped position hits an image border.
- Warp once per frame before the 3x3 saturation envelope.
- Use smooth same-colour bilinear warp (never mixes R/G/B) instead of 2px-quantized
  nearest warp; evaluate darktable's 3x3 block extremes per 2x2 cell with darktable's
  border rule. Only the envelope *weight* rides the bilinearly interpolated block
  maximum (C0-continuous blending, no 2px teeth); the clip branch and the
  clipped-winner bookkeeping stay block-exact like darktable so adjacent pixels never
  pick different fallback winners (no contour lines in highlights).
- HDR+ deghosting (`HdrTileDeghost`, Hasinoff et al. 2016 §5 + hdr-plus-swift
  `merge/frequency.metal`): each warped alternate passes through a pairwise
  frequency-domain Wiener merge toward the reference (16x16/channel tiles,
  two-phase triangular overlap-add, shared per-bin weight). Bins broken by
  motion blur (the blurred long exposure previously smeared the merge via its
  dominant photon weight), ghosts, or misregistration collapse to the sharp
  reference; matched bins are kept for photon-weighted denoise. Gain matching
  is EXIF plus a clamped per-tile refinement so local bias does not force
  rejection, and the DC bin follows the low-frequency consensus instead of
  its own residual (its N^4 leverage would otherwise reject on harmless blur
  leakage and discard the long exposure's clean shadows). Tiles where the
  reference clips bypass the blend so short-exposure highlight rescue survives.
- `HdrRawMerge.mergeExact()` retains the verbatim darktable loop (border behaviour,
  negative-weight clipped bookkeeping, white-level normalization) for audit/tests.
- Fast exposure-compensated translation pre-align always runs (no native deps),
  using PhotonCamera's matcher cost model (`alignment/normalize.glsl` +
  `alignment/align2.glsl`): 4-channel quad mosaics (never gray-averaged) under a
  sigma-1.5 Gaussian prefilter, noise-normalized L1, and validity gating that
  excludes quads below the black floor or above the moving frame's matchable
  range. FlowNet stays as the dense coarse-to-fine refinement: the moving frame is
  translation-compensated first, FlowNet estimates the residual, and the chained flow
  feeds the merge. FlowNet rejection falls back to translation, never to identity.
- Single-resample chain (2026-09-16 bracket validation): the pre-shift is snapped to
  even integers and applied as a lossless pure reindex (`warpShiftedEven`); the merge
  performs the only interpolating resample. Double bilinear cost ~25% Nyquist power
  loss on real brackets (HDR.dng vs F01); single resample simulates at ~0.79 vs 0.72
  kept. FlowNet-rejected frames fall back to the full-precision shift warp.
- Accumulation is input-ordered like darktable (no reference-first reordering).
  Geometry uses the middle exposure as reference
  (`RawCameraController` sorts by exposure*ISO and picks `size/2`); the first
  frame still wins clipped ties like darktable.
- Phase 1 detail upgrades (2026-09-23, validated on 4080x3060 2EV ISO50
  bracket): Fourier subpixel refinement inside `HdrTileDeghost.processTile`
  (3x3 grid at +-0.5 channel-px via phase ramp on existing spectra, no extra
  FFTs, energy-gated); trimmed mid-mean shared Wiener weight instead of
  channel max; unified uncalibrated noise fallback on
  `CfaNoiseModel.FALLBACK_*` plus 1.25x warp-variance inflation on the moving
  term; `checkFrames` normalized-domain guard (-0.5..1.5) catching
  un-normalized callers.
- Phase 2 robust averaging (2026-09-23, same bracket set; formulas checked
  against upstream `burstphoto/merge/frequency.metal`): per-tile mismatch in
  noise-sigma units mapped to upstream's 0.12 operating point (fixed scale,
  not global mean — boost engages only where residual is truly at noise
  floor); motion boost up to 6x on static tiles / 1x cut on motion (Liba
  Fig. 9f shape); per-bin Delbracio magnitude preference (ratio^4 via
  squared mags, AC-only, gated mismatch<0.3, no uniform-exposure gate since
  spectra are gain-matched); mismatch-gated deconvolution lift (upstream
  cw[] for TILE=16, per pairwise blend); soft highlight handoff both sides
  (refTrust hands clipped ref to alternate preserving darktable rescue,
  altShrink discounts clipped mapped alternate) replacing the hard 0.95
  bypass. Real-bracket operating point: mid-bright static tiles mismatch
  ~0.07-0.14 (boosted ~57%), dark tiles ~0.3-0.56 (conservative, likely
  pedestal drift the multiplicative gain trim only partly absorbs),
  clipped tiles cut to 1x; refTrust touches ~0.3% of tiles (F01 rarely
  clips). Full suite 734/734 green.
- Phase 3 locality (2026-09-24, same bracket set): 8px/channel default
  (upstream `tile_size_merge`), 16/channel fallback via
  `deghost(tileSize)` / `HdrRawMerge.Options.deghostTileSize`; Hann
  analysis window (window-aware `binNoise` via window power sum) with
  triangular synthesis over a full half-tile 4-phase grid, analytic
  pattern-aware product-sum normalization (numerically verified exact);
  per-site anti-ringing clamp moved into windowed-domain bounds (clamping
  to unwindowed bounds inflated output ~2.2x — caught by passthrough
  tests). Real-bracket operating point transfers cleanly (boosted ~30% at
  both sizes). Host profile on 256px: 8px ~41ms vs 16px ~108ms (small
  tiles are cache-friendlier — the feared 2-4x cost went the other way).
  Full suite 738/738 green.
- Phase 3 validation on real brackets (2026-09-24,
  `HdrRealBracketValidationTest`, F00/F01/F02 2EV ISO50 4080x3060, identical
  alignment both runs, HEAD-vs-worktree A/B): sharpness tie (0.00994 vs
  0.00996 mean gradient), noise MAD -2.6%, shadow variance -1.1%,
  reference fidelity tie, highlight rescue parity (this set has no true
  clipped scene content — only hot pixels, rescued to short-frame values
  both sides), no stuck-bright leak (max 0.208 vs 0.210). The A/B caught
  one real regression first (fraction-only highlight trust leaked
  single-pixel clips at 0.25); fixed with max-aware trust plus a dedicated
  unit test. Merge cost ~1.5x on full frame (4-phase overlap), inside the
  quality-first budget. Full suite 743/743 green.
- Second set (2026-09-24, IMG_20260923_145207_528 F00/F01/F02, 1/1961 +
  1/490 + 1/123 ISO50, real handshake shifts (2.62,0.55)/(-3.45,-1.49)):
  all metrics tie within 0.5% (sharpness, noise, fidelity, shadows), no
  clipped scene content in F01. Full-res spectral A/B: LF/VHF tied, HF
  band -5.5% with a measured step edge identical (rise within 0.05px,
  overshoot within 0.001) — less HF noise, not less detail. Side-by-side
  PGMs show no ghosts/smear; rock texture and cracks preserved equally.
  Conclusion across both sets: no worse anywhere, cleaner shadows, edges
  intact — both scenes are alignment-limited, so gains materialize as
  noise rather than sharpness. A fast-motion bracket set would be needed
  to separate sharpness.
- Abort HDR on missing/nonfinite FlowNet output; clamp raw model flow to ±32 model px
  and reject fields that do not strictly improve exposure-matched proxy correspondence
  or leave fewer than 85% of sampled points in bounds (a field that merely ties
  identity still resamples/softens with zero geometric benefit).
- Guard capture state, clear partial brackets on failure/stop, allow exposure duration in timeout,
  and avoid stale Program exposure values in Auto mode.
- Compensate JPEG display exposure for shortest-frame merge normalization.
- Float DNG declares 1.4 backward compatibility and retains both camera calibration sets.
- AMaZE memory regression expects RGBA32F scratch (16 bytes/pixel), matching actual allocations.
  This is a memory-test correction, not proof of numerical parity with RawTherapee AMaZE.

## Validation limits

Local unit tests cover merge envelope, normalization, invalid flow, Bayer border parity and
float-DNG binary tags/sample preservation. Android APKs compile for all configured ABIs.
Emulator ABIs contain a stub and cannot perform FlowNet HDR merging.

On the connected Android 16 device, Vulkan model initialization took about 10.6 s and inference
about 1.6 s. A periodic checkerboard identity test failed with a large displacement. The
nonperiodic follow-up initially could not install (`INSTALL_FAILED_USER_RESTRICTED`).
On 2026-09-07 the retry installed successfully and the nonperiodic identical-frame test passed
on device 25080RABDG running Android 16, including the correspondence rejection check.

Real bracket capture, motion/occlusion quality, and
external Darktable/RawTherapee DNG decoding are not yet validated. The original claims of a
fully verified direct port were overstated. Float storage itself does not establish HDR quality.
