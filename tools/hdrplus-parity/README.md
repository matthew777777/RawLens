# HDR+ merge parity runner (desktop Vulkan)

Runs the exact host + recorder sources the app ships
(`app/src/main/cpp/hdrplus/`, minus JNI) on desktop Vulkan
(MoltenVK on macOS) and checks both merge paths on synthetic bursts:

- **identity**: N identical frames merge back to the input —
  float-rounding only for spatial (~140 dB expected, bar 80 dB),
  plus mild deconvolution gain for frequency (bar 50 dB);
- **denoise**: noisy bursts merge closer to clean than any single
  frame (bar: +2 dB gain, 45 dB absolute);
- **determinism**: repeated runs are bit-identical;
- **shift**: a translated alternate still merges (exercises tile
  alignment + warp end to end);
- **HQ per-pass**: the upstream-exact schedule
  (`frequency_align_once = 0`) clears the same identity/shift bars;
- every output sample is finite.

This is the executable counterpart of the parity proof in
`app/src/main/cpp/hdrplus/PARITY.md`: same shaders (plus marked
upstream-parity patches), same geometry, same dispatch sequence —
verified by running, not just by reading.

## Build and run

```
cmake -S tools/hdrplus-parity -B build/hdrplus-parity -DCMAKE_BUILD_TYPE=Release
cmake --build build/hdrplus-parity
./build/hdrplus-parity/hdrplus_parity app/src/main/assets
```

macOS needs `vulkan-headers` + `molten-vk` (Homebrew layout by
default; override with `-DHDRPLUS_VULKAN_INCLUDE=` /
`-DHDRPLUS_MOLTENVK_LIB=`). Linux uses `find_package(Vulkan)`.

Exit 0 iff every check passes. GPU milliseconds print per run
for information only (device-dependent, not asserted).

A second argument restricts the run for debugging: `fast`, `hq`,
`shift-fast`, or `shift-hq`. With `HDRPLUS_DUMP_DIR` set to a directory,
each merge also writes raw intermediate dumps (`hdrp_align_a`,
`hdrp_aligned`, `hdrp_weight`, `hdrp_tile_cost`, level images, padded
frames — whichever the path's layout holds) from the last merge:

```
HDRPLUS_DUMP_DIR=/tmp/hdrdump ./build/hdrplus-parity/hdrplus_parity \
    app/src/main/assets shift-fast
```

Reference numbers (Apple M1, 2026-10-06): Fast identity 159.7 dB,
Fast denoise +5.8 dB, HQ identity 56.0 dB, HQ denoise +5.3 dB,
Fast shift 159.8 dB (center crop), HQ shift 55.6 dB,
HQ-perpass identity 56.0 dB, HQ-perpass shift 55.6 dB.
