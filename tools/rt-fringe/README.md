# RawTherapee CA and purple-fringe experiments

Standalone, reproducible prototypes; **not connected to an app setting or enabled in
production**. User request: test an inexpensive way to reduce purple highlight halos.

## Findings on the supplied DNG

Input: `IMG_20261019_142925_030_VID_frame0.dng`, 4080×2040, GBRG, black 64,
white 1023. User image/output files remain outside the repository.

- RT Bayer RAW CA auto-correction (one iteration, avoid-colour-shift off) left the
  purple water halos largely unchanged. It corrects spatial channel misalignment,
  not every kind of purple fringe. The standalone reference needed at least
  33,292,800 additional scratch bytes (~31.75 MiB), plus tile/block scratch.
- The RT-derived defringe prototype visibly reduced the halos at radius 1, threshold
  13, with purple-only hue selection. The tested RGBA8 image was the existing
  RawLens GPU development (same white balance, exposure and AgX rendering).
- On the connected Xiaomi 25080RABDG, the four-strip RGBA8 prototype took 371.252
  and 380.266 ms for 8.3232 MP, excluding CLI file I/O. Algorithm scratch was
  1,396,520 bytes (~1.33 MiB), **excluding thread stacks and runtime/allocator
  overhead**. The existing 33.3 MB RGBA image is input/output, not a second image.
  This is an experimental CPU timing, not a guaranteed app latency or preview FPS.
- A serial RGBA run took 1,419.79 ms and used 1,143,716 bytes of algorithm scratch.
  Parallel strips use an identical global threshold and preserve narrow immutable
  boundary halos to avoid in-place read/write races.

Do not conclude that all purple colour should be removed. Uniform real purple is
preserved by the local-residual test; textured purple objects at contrast edges
can still be affected. This requires more scene validation before integration.

## What is ported

`build_ca.py` builds the original RT `CA_correct_RT` scalar numerical body with
minimal container/progress adapters. GUI dependencies, SSE and OpenMP are omitted.
The optional avoid-colour-shift branch is explicitly unsupported in the harness,
not silently approximated; it would need more full-frame storage and sigma-30 blurs.
The normalized float32 CFA is converted to RT's 65535-scale domain and back. All
four conventional RGB Bayer patterns can be supplied; default is GBRG.

`defringe.h` retains RT's core equations:

1. Blur the CIELab a/b channels and measure squared local chroma residual.
2. Compute its whole-image mean (double accumulation).
3. Weight neighbourhood colours by `1 / (residual + mean)`.
4. Replace chroma only where the RT threshold condition is met.

The Gaussian is an explicit separable FIR truncated at 3σ, **not RT's recursive
Gaussian implementation**. The optional hue curve is reduced to a purple-only
CIELab quadrant gate (`a > 0`, `b < 0`), or disabled for all-hue reference tests.
There is no claim of bit-for-bit equivalence with desktop RawTherapee.

The implementation uses two streaming passes and ring rows instead of RT's full
residual image, two full blur images and full original Lab image copy. Weighted
box sums are separable/rolling, avoiding the nested per-pixel window scan. Lab L
is never changed. Four parallel strips share a global mean; narrow immutable
RGBA boundary snapshots make in-place parallel writes safe.

`defringe_rgba.h` supplies an opaque, straight **sRGB RGBA8, D65 CIELab** adapter
with small conversion LUTs. It leaves unselected pixels bit-exact and preserves
alpha bytes. Selected pixels preserve Lab L before RGB gamut clipping/8-bit
rounding. Transparent/premultiplied images, Display P3, linear/HDR buffers and
JPEG XL float output need different adapters. Do not run this adapter on them.

## Build and test

```sh
clang++ -std=c++17 -O3 tools/rt-fringe/rgba_cli.cpp -o /tmp/rgba_defringe
clang++ -std=c++17 -O2 -fsanitize=address,undefined \
  tools/rt-fringe/test_defringe.cpp -o /tmp/test_defringe
/tmp/test_defringe
python3 tools/rt-fringe/build_ca.py /tmp/rt-ca
```

Tests compare streaming chroma output against an independent full-frame direct
Gaussian/weighted-window reference, including tiny images and all borders.
Maximum observed error: 0.000021 Lab units. They also exercise parallel versus
serial processing, alpha/stride, uniform purple, neutral/blue/green images,
unchanged Lab L, and invalid dimensions. Address/undefined-behaviour sanitizers
passed. Serial and parallel outputs were byte-identical on the supplied image.

Existing PNG demo (NumPy and Pillow required):

```sh
python3 tools/rt-fringe/demo.py input-srgb.png /tmp/rt-fringe-demo
```

The CLI accepts tightly packed RGBA8 and writes the same layout:

```sh
/tmp/rgba_defringe off.rgba on.rgba 4080 2040 1 13
```

CA reference (normalized, nonnegative float32 CFA, host byte order):

```sh
/tmp/rt-ca/raw_ca input.f32 ca.f32 4080 2040 GBRG
```

For Android benchmarking, compile `rgba_cli.cpp` with the NDK's
`aarch64-linux-android30-clang++ -std=c++17 -O3 -static-libstdc++` and run the
binary on an explicit test image under `/data/local/tmp`. No app install needed.

## Next integration boundary

If retained after broader image validation, the inexpensive integration point is
an **optional** operation on an already-read-back opaque SDR sRGB bitmap, before
JPEG compression. This needs no extra full-resolution texture or bitmap. It
must not be applied automatically to P3 or gain-map/linear HDR data. There are
no app defaults, preferences, settings UI, or shipping native dependencies added
by this prototype.

See [NOTICE.md](NOTICE.md) for pinned sources and attribution.
