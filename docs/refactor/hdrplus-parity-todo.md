# HDR+ vs Burst Photo: parity findings (2026-10-22, parked)

Oracle: headless runner built from Burst Photo's own Swift + Metal sources
(hdr-plus-swift @ 69cb057, Metal compiled at runtime, only DNG I/O replaced),
compared with tools/hdrplus-burst on the same 1024^2 crops.

## Measured (exposure control "Off", the mode PARITY.md assumed)
- Fast: 58.1 dB; with upstream hot-pixel detection disabled 177.9 dB (exact).
- HQ (our default align-once): 70.4 dB; upstream per-pass mode 157.8 dB (exact).

## Measured vs Burst Photo's DEFAULT ("Linear (full bit range)", also the
## user's saved setting), final gain excluded
- Bright burst 134544: Fast 80.8 dB, HQ 78.1 dB.
- Dim burst 194618: Fast 58.9 dB (11.7 % px > 1 DN), HQ 59.3 dB (14.3 %).

## To port for 1:1
1. Burst hot-pixel detector (find_hotpixels_bayer): average of all frames,
   per-phase mean, threshold 2 / mult 1 (1 / 2 when "Off"), strength from
   sum(ISO*t)/sqrt(N) -> ((clamp 5..80) - 5)/75 (1.0 when "Off"), fp16
   weight; blend in prepare_texture (mean of 4 same-colour neighbours,
   2 px border). Runs in BOTH modes. Verified numpy model: 89 px on 194618.
2. Level-0 pyramid avg_pool_normalization (AsShotNeutral factors, frame
   black mean) when exposure control is on.
3. HQ: default to per-pass alignment (frequency_align_once = 0); run
   reduce_artifacts_tile_border with the real black levels.
4. Optional final LinearFullRange gain (probably off in the app, which sets
   its own exposure; on in the desktop tool).
5. Upstream quirks: black levels int-truncated and stored transposed
   (black_levels[row + col*2]).
6. Non-uniform ISO*t bursts (AE drift, e.g. 184641) take Burst Photo's
   bracketed path (darkest frame as reference, exposure weights): not ported.

Then: HDR+ Vulkan speed/memory pass (all N frames resident as R16 images
plus an N-frame staging buffer today).
