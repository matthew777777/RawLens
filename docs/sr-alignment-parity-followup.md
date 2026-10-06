# Follow-up: SR CPU alignment parity (residual single-pixel spikes)

## Note (2026-10-03)

### Done: sampling rules are JAMY-L verbatim

The CPU linear merge, mosaic reconstruction, and robustness warp were fixed
from bilinear to reference-verbatim nearest sampling
(`flowAtNearestInto`, nearest-quad r fetch with the one-quad shift):

- `app/.../rawlens/RawSrAlignment.kt` (+ sr-vulkan mirror)
- `app/.../rawlens/RawSrRobustness.kt` (+ mirror)
- `app/.../rawlens/RawSrBayerMerge.kt` (+ mirror)
- `app/.../rawlens/MosaicSrReconstructor.kt` (+ mirror)

Verified on burst `/Users/monikamalinowska/Downloads/IMG_20260927_134544_522`
(8x 4080x3060, Xiaomi GRBG): the white-blinds zipper cluster is gone,
robustness maps are structurally identical to ref, `JamyCoreParityTest` 7/7
unmasked full-field, app suite 1062 green.

Update 2026-10-03: the merge/robustness shader port has since landed, so the
interim CPU-snaps/GPU-blends contract is gone — both
`VkMergeAccumulateBlendTest` tests now pin full-field CPU/GPU parity
(nearest flow + nearest robustness on both sides, float32-vs-float64 twin
tolerance, step render included). The residual below is CPU-alignment-only
and unchanged by the port.

Update 2026-10-04: the unified `flowUpscale` default is now BILINEAR like
the reference (the interim NEAREST default is retired with the GPU 1:1
alignment port). Basin-tie counts below predate the flip — re-probe before
comparing — but the tie mechanism (both flows valid, low residuals) is
mode-independent, and `JamyAlignmentParityTest` still gates the port.

### Residual: 4 single-pixel spikes from alignment ties

Full-res compare (`/tmp/sr-parity/native-compare/OURS_NATIVE_LINEAR.dng` vs
`REF_NATIVE_LINEAR.dng`) still shows 4 isolated spike pixels on the blinds
where ours picked a different-but-valid alignment slat than ref:

| pixel (x,y) | ours G | ref G | kind |
|---|---|---|---|
| (1277,1023) | 5333 | 8259 | dark notch |
| (1276,1027) | 5979 | 8586 | dark notch |
| (1296,1219) | 10674 | 6860 | bright spike |
| (1328,1251) | 7582 | 2839 | bright spike |

Mechanism (proven at (1296,1219), frame f2): covariance matches ref
bitwise and r matches (1.0 both), but merge-time flow differs by ~0.3px in
dy (ours (-1.090,-0.458) vs ref (-1.135,-0.782)). The razor kernel there
(across-edge sigma ~0.13px) turns that into a 5.5x weight difference
(green den 0.019 vs 0.10) → single-tap latch → spike.

Key evidence (`/tmp/flow_quality2.py`, crop 1024,512,1024,1024, f2):

- 409/4096 tiles differ in integer basin, but at every one of them BOTH
  flows have LOW warp residuals (all <= 0.017, mean excess 0.0001).
- These are ambiguous ties (periodic blinds), not wrong minima.
- Symmetric: ref has 3 spikes of its own where ours is smooth
  (`/tmp/pit_swap.py`: (1305,990), (1294,1005), (1303,1108)).

### Why exact flow parity is hard

- Ref L2 block matching runs on torch (FFT cross-correlation); ours is a
  JVM port (`RawSrAlignment.blockMatchL1/L2`, `refineIca`). Ties are broken
  by float noise, so bit-identical flows across implementations are likely
  unachievable at ambiguous tiles.
- `JamyAlignmentParityTest` (10/10 green) pins the port on synthetic data;
  the gap only shows on real ambiguous texture.
- Flow diffs grow with frame index/motion (f0: 3% tiles >0.3px … f5/f6:
  10-13%), hinting at a coarse-level or grey-image nuance worth one look
  before declaring it irreducible.

### Definition of done for this follow-up

Either (a) find a systematic alignment bug (grey mismatch, search range,
pyramid, tie-break) that reduces basin diffs on the burst without hurting
`JamyAlignmentParityTest`, or (b) demonstrate with per-tile cost-surface
probes that the remaining diffs are all true ties and close this as
wontfix/parity-in-kind. Do not touch Vulkan; keep `JamyCoreParityTest`
green throughout.

## Prompt (paste to resume)

```text
Continue the SR CPU parity follow-up in docs/sr-alignment-parity-followup.md.

Context: CPU linear/mosaic/robustness sampling is JAMY-L verbatim (nearest).
Residual: 4 single-pixel spikes on the blinds burst
/Users/monikamalinowska/Downloads/IMG_20260927_134544_522 (coords in the note)
caused by alignment integer-basin ties (both flows valid, low residuals).

Do not touch Vulkan. First reproduce the flow-quality probe on the
1024-crop (1024,512,1024,1024, 8 frames, ref 0; scripts /tmp/flow_quality2.py,
/tmp/flowbasin.py, /tmp/flowhist.py if present, else rebuild them), then
either find the systematic alignment difference or prove the ties are
irreducible. Keep JamyCoreParityTest 7/7 and the app suite green.
```
