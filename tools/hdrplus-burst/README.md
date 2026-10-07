# HDR+ burst runner (desktop Vulkan)

Merges a real DNG burst through the exact merge the app ships
(`app/src/main/cpp/hdrplus/`, minus JNI) on desktop Vulkan
(MoltenVK on macOS) — the RAWR-style workflow: point it at burst
frames, get a merged DNG back. DNG I/O uses the same vendored
tinydng the app uses natively.

## Build and run

```
cmake -S tools/hdrplus-burst -B build/hdrplus-burst -DCMAKE_BUILD_TYPE=Release
cmake --build build/hdrplus-burst
./build/hdrplus-burst/hdrplus_burst app/src/main/assets out.dng [--hq] \
    [--ref i] [--strength f] [--tile-size 16|32] [--search-distance 32|64|128] \
    [--crop x,y,w,h] frame0.dng [frame1.dng ...]
```

macOS needs `vulkan-headers` + `molten-vk` (Homebrew layout by
default; override with `-DHDRPLUS_VULKAN_INCLUDE=` /
`-DHDRPLUS_MOLTENVK_LIB=`). Linux uses `find_package(Vulkan)`.

Frames must be 16-bit mono Bayer DNGs (uncompressed or lossless
JPEG) with identical geometry and CFA pattern; 2..64 frames.
`--ref` defaults to the middle frame; `--crop` x/y must be even
(CFA phase preserved).

## Output

16-bit normalized CFA DNG (black 0, white 65535), inheriting the
reference frame's CFA + color metadata (matrices, neutral,
illuminants) and dropping its noise profile (merged noise differs
from any single frame) — the same layout as the app's
`HdrPlusDngWriter`, including its quantization
(`clamp[0,1]*65535`, round half up).

Notes:

- Input black levels come from tinydng's int32 parse, so fractional
  DNG black levels (e.g. 63.75) quantize to whole DN — a sub-DN
  shift, far below the noise floor.
- Exit 0 iff the merge ran, the DNG wrote, and every output sample
  is finite.
