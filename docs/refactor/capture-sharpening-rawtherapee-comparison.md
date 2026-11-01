# Direct RawTherapee comparison (2026-11-01)

**Result: the RawLens/RAWR GPU implementation closely matches RawTherapee's
Bayer deconvolution stage at matched settings, but is not a bit-exact or
feature-complete port of RawTherapee Capture Sharpening.** Earlier exact-parity
results apply to RAWR, not to RawTherapee itself.

Reference: original RawTherapee C++ at
[`b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c`](https://github.com/RawTherapee/RawTherapee/tree/b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c),
2026-10-13, the revision identified by RAWR's port. The fetched `origin/dev`
pointed to this revision when checked. Original reference sources were not
modified.

## Direct numerical comparison

The scalar C++ reference compiles the actual `buildClipMaskBayer`,
`calcRadiusBayer`, `checkForStop`, `CaptureDeconvSharpening`, contrast-selection
and blend-mask functions extracted verbatim from RT, plus unchanged RT
`boxblur.cc` and `gauss.cc`. The small standalone harness reconstructs RT's
RGB/luminance orchestration and Lab LUT; it is not the complete RawTherapee app.
SIMD/OpenMP are disabled and FP contraction is off. Noise options and mask
preview are disabled. The out-of-range Lab cube-root helper uses `std::cbrt`
rather than SLEEF. See the saved provenance for these limits.

Each side receives identical half-float-quantized, pre-WB camera RGB. The RT
reference receives those same values in RT's WB/scaled 65535 domain, with the
same CFA native samples and per-site clipping thresholds. Its RGB output is
converted back to normalized camera RGB before comparison with the RawLens
GPU result. Thus demosaicing, camera profiles, tone curves and JPEG encoding
do not confound the comparison. The primary results always use the original
RT scratch-buffer behavior, including corner-boost tests.

**14 cases completed**, covering our four presets, all four Bayer patterns,
all five kernel sizes, 20 iterations, active/manual contrast masks, clipped
highlights, corner boost, iteration limiting on/off, strip/band processing,
partial tiles and 4208×3120 resolution.

| Matched test | Mean absolute RGB error | Maximum RGB error | PSNR (white = 1) | Sharpening-change correlation |
|---|---:|---:|---:|---:|
| Low, 2 iterations | 0.0001219 | 0.0009761 | 75.02 dB | 0.9999692 |
| Medium, 3 iterations | 0.0001127 | 0.0009761 | 75.55 dB | 0.9999854 |
| Sharp, 4 iterations | 0.0001085 | 0.0009760 | 75.86 dB | 0.9999870 |
| Sharpest, 6 iterations | 0.0001134 | 0.0009738 | 75.44 dB | 0.9999879 |
| RT native defaults, sharpening enabled, 20 iterations | 0.0001135 | 0.0011542 | 74.71 dB | 0.9999813 |
| 4208×3120, 6 iterations | 0.0000569 | 0.0009837 | 77.88 dB | 0.9999309 |
| RT UI-supported corner boost +0.5, 20 iterations | 0.0001135 | 0.0022129 | 74.48 dB | 0.9999815 |

These errors are in normalized **linear camera RGB**, not rendered sRGB or
perceptual units. PSNR uses a fixed reference white of 1, including inputs
with highlights above white. Small RGB errors do not prove identical final
8-bit exports. The two extra 20-iteration tests exercise the native GPU API;
20 iterations and corner boost are not selectable in RawLens's RAWR-style UI.

Auto contrast values agreed in every case; auto radius agreed at the precision
printed by the GPU validator. All outputs were finite. Complete per-case
metrics are saved alongside the report; no bit-exact RT parity is claimed.

## Why the results differ

1. **Precision and storage:** RT uses a float/double mix; RAWR's compute shaders
   use float and write RGBA16F. Half-float storage and GPU arithmetic introduce
   rounding differences even when the visual sharpening response closely
   matches. Only about 55% of the preset RGB samples equal the nearest-half
   representation of RT's output bit-for-bit.
2. **Lab lightness:** RT interpolates its 65,536-entry LUT. RAWR evaluates a
   formula in the shader. This can change the contrast mask slightly.
3. **Corner scratch:** RT reuses per-thread tile scratch. RAWR resets it per
   tile. With corner boost, RT's result can depend on prior tiles and OpenMP
   scheduling. The +0.5 UI-range test has maximum error 0.002213. A separate
   +0.7 stress test (outside RT's UI range, iteration limiting off) reaches
   0.033054 locally. Resetting RT's scratch in a *separate diagnostic build*
   reduces the latter to 0.001522, confirming the reuse difference. That
   diagnostic is not substituted for original RT in the primary comparison.
   RawLens currently fixes corner boost at zero.

## Settings and feature differences

| Feature | RawTherapee at this revision | RawLens via RAWR |
|---|---|---|
| Default enabled state in native settings | Off | On, matching RAWR settings |
| Iterations | UI 1–100; native default 20 | Low/Medium/Sharp/Sharpest = 2/3/4/6 |
| Radius | Auto or manual 0.4–2.0 | Auto; manual parameter exists natively, not exposed |
| Contrast threshold | Auto or manual 0–200 | Auto; manual parameter exists natively, not exposed |
| Corner radius boost | UI −0.5 to +0.5 | Fixed 0; RAWR native API accepts 0–1 |
| Automatic iteration limit | User control | Always enabled |
| Contrast-mask preview | Available | Absent |
| Presharpening denoise | Median or wavelet, adjustable | RT-specific implementation absent |
| Postsharpening denoise | Mask-aware, adjustable | RT-specific implementation absent |
| Sensor paths | Bayer, X-Trans and monochrome | Standard Bayer stage; Quad-Bayer bypassed |
| Original RAW clipping mask | Constructed from original scaled RAW data | Uses RAWR's native-sample fallback; no retained original sensor mask bound |

RawLens's separate profiled-wavelet denoiser is not the same implementation or
control as RT's capture-sharpening pre/post denoise. Equal iteration counts do
not imply equal whole-photo output between the applications.

## Evidence and reproduction

Evidence directory:
[`results/capture-sharpening-rawtherapee-2026-11-01`](results/capture-sharpening-rawtherapee-2026-11-01/).
It contains per-case metrics, source/extraction hashes, compiler flags and the
original reference comparison log. An ancillary NumPy-oracle check passes
all nine upstream test cases: eight against unmodified RT and the corner
case against the explicitly labeled ring-reset variant. The direct GPU/RT
numbers above do not use NumPy as an algorithm substitute.

First generate the GPU inputs/results with `prove_parity.py` as documented
in [the RAWR parity report](capture-sharpening-rawr-parity.md). Then:

```sh
git clone https://github.com/RawTherapee/RawTherapee /tmp/rt-reference
git -C /tmp/rt-reference checkout b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c
python tools/capture-sharpening-parity/build_rawtherapee_reference.py \
  --reference /tmp/rt-reference --out /tmp/rt-proof/rt_harness
python tools/capture-sharpening-parity/compare_rawtherapee.py \
  --gpu-proof /tmp/rawlens-capsharp-proof \
  --rt-reference /tmp/rt-proof/rt_harness/cs_ref \
  --rt-checkout /tmp/rt-reference --out /tmp/rt-proof/direct
```

The comparison requires NumPy, the host GPU validator already built for
MoltenVK, GPU access and a C++ compiler. No app processing or settings were
changed for this comparison.
