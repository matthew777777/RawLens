# Experimental lapis Hexadeca → 50 MP Bayer

Headless prototype; not yet connected to the app's lens switcher or merge pipeline.
Input is a **16320×12240 RAW10-unpacked** frame from the verified sensor-11 CAMSV
pure-RAW route. It is little-endian, tightly packed uint16, with 4×4 colour
blocks in GBRG order. Output is **8160×6120 conventional GBRG Bayer**. Ten-bit
signal levels remain black 64 / white 1023, stored in uint16.

## Upstream

The converter uses `binBlockCfaToBayer` and its model from
[utalpt/rawr](https://github.com/utalpt/rawr), pinned to
`8cd02944025e55c00e0ce8df4fe14a9da1d91bc5`:
`app/src/main/cpp/imaging/CfaRemosaicFast.h`. GPL-3.0-only; upstream license
and copyright notice are retained in `third_party/rawr/`.

Local adaptation: neighbouring **block** coordinates reflect at the image
border instead of clamping, preserving CFA parity. A constant-colour synthetic
test exposed a 42-DN edge error with the original clamp. The local reflection
fix passes all four CFA orders, including borders. No other algorithm change.

The model estimates per-position intra-block gains from the frame, constructs
coarse colour planes, then averages each 2×2 same-colour group and uses a colour
ratio to reconstruct the required Bayer colour at that output position. It
does not create a 200 MP remosaiced intermediate. This is a derived mosaic;
the original 200 MP samples must be retained for future method comparisons.

## Build and run

```sh
c++ -std=c++17 -O3 -pthread convert.cpp -o convert
./convert --selftest
./convert source200.raw16 output50.raw16 16320 12240 2 64 1023 8
```

Order codes: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. CLI refuses existing output files,
incorrect input size, invalid geometry and invalid parameters. Dimensions
must be multiples of eight. Input is never changed. Timing JSON excludes
file I/O and includes both model construction and pixel conversion.

Android: compile the same source with the NDK's
`aarch64-linux-android29-clang++`, adding `-static-libstdc++`, then run via ADB.
No Activity or additional root operation is required to convert an already
readable source file. The sensor capture itself still needs the experimental
root recipe and automatic restoration described in the device investigation.

## DNG export

`RootRawProbe metadataOut=...` saves static factory matrices in row-major
order and white balance/exposure/black levels from the timestamp-matched frame.
Pass the **matching frame's** JSON, not metadata from another shot:

```sh
python export_dng.py output50.raw16 capture-metadata.json output50.dng
```

Requires numpy and tifffile. Exporter is intentionally lapis/GBRG-specific.
It preserves factory colour matrices and frame white balance; it does not
claim a new mode-specific colour calibration. Orientation remains sensor
orientation because device orientation is not in the sidecar. Noise profile,
shading and distortion opcodes are not fabricated. Production integration
must supply the actual readout's residual shading and correctly transformed
metadata. Existing stock-mode noise models are not valid automatically after
gain correction, binning and colour reconstruction.

## Validation and limits

- Host and Android self-tests cover all CFA orders, constant-colour output
  including borders, black/saturation, thread invariance, and source retention.
- Actual 200 MP capture converted on MT6878: first run 724 ms total
  (464 ms model + 260 ms conversion); next capture 828 ms total. These are
  observed runs, not a sustained benchmark or a 60-ms performance claim.
- Source CFA has measured 4×4 grouping. A colour chart is still needed to
  independently verify R/B identity and factory matrices in this readout.
- Frame-derived gain estimation can be biased by repetitive scene structure;
  future comparisons need a fixed illuminated scene, matched exposure/focus,
  noise measurements, colour chart and vendor-50-MP reference.
- Clipped groups retain upstream's plain-mean fallback. Colour reconstruction
  around partially clipped chromatic highlights remains a quality limitation.
- No claim that this is sharper than the vendor 50 MP path yet; no app UI,
  burst merging, ZSL or production camera-session integration in this prototype.

## Version 2 prototype (2026-11-14): 4x4-aware demosaic -> 2x downsample

`research/hexa_v2_proto.py` (numpy/scipy, crops only). Method: initial ratio demosaic,
then 3 rounds of residual interpolation (each colour fitted locally as a*L+b on its own
samples with a box guided filter, r=5; residual interpolated by normalised Gaussian),
full-resolution RGB, 2x2 area downsample to 50 MP. Compared with the current path
(rawr bin -> 50 MP Bayer -> Malvar-He-Cutler).

Synthetic 200 MP hexadeca GBRG sensor, 6 % per-position gain mismatch (estimated from
the frame), lens blur sigma 0.7 px, Poisson + read noise. PSNR vs truth, dB:

| region          | current | v2 (ratio) | v2 (RI) | v2 (RI, true gains) |
|-----------------|--------:|-----------:|--------:|--------------------:|
| zone plate      | 26.3    | 30.9       | 30.8    | 31.4                |
| slanted / colour edges | 32.8 | 30.5   | 31.3    | 32.4                |
| photo texture   | 36.6    | 37.2       | 37.3    | 37.6                |
| 1-2 px colour hatching | 14.9 | 15.1   | 14.5    | 14.5 (both fail: below the 4x4 CFA's colour sampling) |

- v2 removes the current path's colour moire (zone plate) and colour fringes on grey edges.
- The edge-region PSNR gap is noise, not structure: on flat fields v2 has ~5-15 % higher
  luma noise (current averages 4 same-colour samples per output) and no periodic error.
- Real `bright200.raw16`: the intra-block gain pattern drifts across the field by 5-14 % RMS
  (worst bottom-right) and ~2-3 % with signal level. A global gain table leaves a block grid;
  local (2048 px window) tables bring v2 level with rawr's tiled model. The capture is
  defocused, so it cannot rank detail.

Needed before porting to Vulkan: a field-varying flat-field gain map (per colour x 4x4 position
x coarse grid, possibly per level), and a sharp tripod capture of the same scene in 200 MP and
the existing 50 MP mode for a real-detail A/B.

### Flat-field calibration attempt (2026-11-14)

Three 200 MP captures of a defocused light (`.local/lapis-200mp-flat-2026-11-14`, auto 20 ms and
manual 10 ms at ISO 542; the 5 ms frame came out black and was dropped). `research/flat_gain_map.py`
builds a 16x12-tile per-colour 4x4 gain map (block-mean and de-trended references gave the same result).
The 20 ms and 10 ms maps differ by 2.5 % RMS. Applied to `bright200.raw16`, block-frequency artefact
energy was equal or worse than per-frame local estimation in all five crops (worse in 3).
Conclusion: the intra-block gains depend on signal level and illumination, so a fixed flat-field table
does not transfer. Version 2 uses per-frame tiled gain estimation (as rawr does), with no calibration step.

## Version 2 in the app (2026-11-14)

Replaces the rawr binning path completely (rawr stays here in tools/ for comparisons).
`app/src/main/cpp/hexa/hexa_v2.{h,cpp}` + `assets/shaders/hexa/hexa_{point,filter,box}.comp`:
per-frame 16x12-tile gain table (CPU), 768 px tiles with a 40 px halo, ratio init + one
residual-interpolation round (one round = three rounds within 0.4 dB synthetic, equal on edges),
2x2 area downsample, 50 MP LinearRaw DNG via `LinearRgbDngWriter` (`LapisHexaDng.kt`).
Parity vs `hexa_v2_proto.py demosaic_ri(iters=1)`: max 0.64 / 65535 interior, exact tile seams
(host harness `research/hexa_v2_host.cpp`, MoltenVK or adb binary; `HEXA_PROFILE=1` prints per-pass GPU ms).

Phone (Mali-G615 MC2), full 200 MP frame: 352 tiles, GPU 39.7 s, conversion 45 s, DNG write 2.2 s.
The worker copies the frame to a private file, restores the sensor and signals
`camera-released.json` first, so the app preview returns while conversion runs.
Mali notes: per-mode specialised point shaders (no dynamic vector indexing); shared-memory
X filters; running-sum box Y. A register-window Gaussian Y was 10x slower (too few threads).
Next speedups to try: fp16-packed multi-channel slots, guided-filter coefficients at half resolution.
