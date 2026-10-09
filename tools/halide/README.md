# Halide AOT filters for the BGU viewfinder rewrite

Generators live in `generators/`; `build_aot.sh` compiles the host generator
binary, smoke-checks it, and emits one static archive per Android ABI into
`app/src/main/cpp/halide/filters/<abi>/` plus a shared header. The `.a` files
are checked in (same precedent as `app/src/main/cpp/ncnn/`); this directory is
the audit/regen path.

## Pinned host toolchain (step-1 spike verdict)

- Halide **21.0.0** (Homebrew, arm64 macOS): headers + `libHalide.dylib` +
  `libHalide_GenGen.a`. Override with `HALIDE_PREFIX`.
- C++17 (`Halide.h` requires it; the app's CMake stays C++14 — only the host
  generator build needs C++17).
- AOT targets: `arm-64-android`, `arm-32-android`, `x86-64-android`,
  `x86-32-android` (all four shipped; no `abiFilters` in the app).

## Schedule rules learned the hard way

- `parallel(y, N)` / `vectorize(x, N)` with the default `ShiftInwards` tail
  **assert `extent >= N`** in the lowered IR. Always pass
  `TailStrategy::GuardWithIf` on both so tiny buffers (tests, odd crops) work.
- `Halide::Buffer` is `Halide::Runtime::Buffer` when only `HalideBuffer.h` is
  included (Halide 21).
- GenGen needs the output dir to exist (`-o` won't create it).
- NEVER write `d * (1.0f/x)` in a generator: the simplifier rewrites it to
  `d/x` (single rounding vs the double rounding a multiply-by-reciprocal
  performs — observed 1-2 ULP drift, caught by scope_meter's gate). Pass
  host-computed reciprocals as opaque scalar params and multiply by those.
- LLVM forms FMA for ANY bare `fadd(fmul, .)` — even `a*a+b*b` in a pure
  Func (proven against exact rationals: FMA(gy,gy,gx²) vs separate
  roundings). Every float add must provably see no multiply operand:
  materialize products in `compute_root` Funcs (memory barriers) and add
  loads. Serial reductions that add one select-wrapped term or one load
  per iteration are safe (selects block the fusion heuristic, gate-verified).
- Scalar references compile with `-ffp-contract=off` (in `build_aot.sh`):
  default Apple Clang fuses mult+add into FMA, but the ground truth is
  JVM/ART separate-rounding semantics — a fused reference can match no
  Kotlin-matching filter (observed: scalar tenor `0x...717` vs `0x...716`).

## Step-1 decisions

- `.a` check-in, not build-time codegen: keeps every dev/CI build free of a
  Halide install. Regen with `tools/halide/build_aot.sh` (runs the host check).
- Each archive embeds the Halide runtime (~200 KB/filter). When filter #2
  lands, switch to one shared runtime (`-no_runtime` + single runtime archive).
- Slice API: GL ES 3.x `sampler3D` port of BGU's `apply_local_curves.fs.glsl`
  first — the test device reports OpenGL ES 3.2 (Mali-G615) and the app already
  owns an ES3 context path with ES2 fallback. Vulkan slice stays the backup;
  Halide-GPU slice is step 6's challenger, not the opener.

## Parity contract (Halide vs Vulkan/Kotlin)

No Halide path replaces an existing stage until its parity gate passes;
the gates live in this directory (`*_check.cpp`, run by `build_aot.sh`),
not in scratch scripts.

- Integer-domain stages (meter maps, hot-pixel lists, alignment
  vectors, tile decisions): **bitwise identical**, enforced by `memcmp`
  over every output on shapes covering tiny/odd-edge/real geometries
  plus flat/extreme/spike frames. Rationale: 1-ULP drift can flip a
  threshold comparison and change a merge decision.
- Float-domain stages (merged pixels), if a Halide fallback merge is
  ever built: PSNR >= 80 dB vs the Vulkan reference on fixed corpora
  (the RAWR parity precedent) **plus** bitwise-identical integer
  side-decisions. No such fallback exists today; Vulkan stays primary.
- Bitwise method that works: flat SERIAL RDom reductions (one index,
  ascending). Unrolled Expr chains do NOT work — Halide's simplifier
  re-nests left-deep float sums into right-deep form (observed 1-ULP
  drift, caught by the gate). Multiple update stages run back-to-back,
  so interleaved accumulators (prev/curr texture terms) need one
  doubled RDom with an even/odd phase. Only independent output cells
  may be parallelized/vectorized.
- Every gate additionally cross-checks a FNV-1a hash against an
  independent third implementation; hashes are printed, not asserted,
  so the cross-check stays human-verified.

## Generators

- `bgu_spike_downsample`: box-downsample a u16 plane (step-1 toolchain spike;
  becomes the BGU low-res input stage). Checked by `host_check.cpp`.
- `bgu_look`: the full VF look (RAW WB/CCM/Reinhard/gamma, JPEG calibrated
  AgX chain) on one low-res quad texel, transcribed from
  `VfGpuImport.TONEMAP_TAIL_TEMPLATE`. Dual output: unlensed guide RGB (fit
  input) + developed RGB (fit output). Dither deliberately excluded
  (display-res noise the fit must never see). Checked by `look_check.cpp`:
  independent double-precision scalar reference x {raw-identity,
  raw-realistic, jpeg-default, jpeg-extreme} x {lens-off, lens-on} on
  non-multiple-of-8 extents; tripwires guide 1e-6 / developed 5e-4 (observed
  max 1.87e-05, float-vs-double + libm formulation).
- `hdrplus_meter`: HDR+ motion meter. Stride-16 ratio/MAD/texture
  maps + hot fraction replicate `HdrPlusMotionMeter` bitwise (the
  parity anchor for a future native meter); dense full-res MAD/texture
  maps per 32px cell are new (stride-16 sampling skips the 1-2px wires
  that actually ghost). Checked by `meter_check.cpp`: 61 cases bitwise
  vs an independent scalar transcription (oracle hash `f1e9326f8f513b02`).
  No app wiring yet — the gate passes, the JNI switch is a later step.
- `bgu_fit`: bilateral-grid affine fit (fit_only, f32). Checked by
  `fit_check.cpp`: fits a 5x4x9 grid to synthetic pairs and verifies the
  defining property with an independent scalar trilinear slice (affine
  target: mean 5.35e-05; gamma-curve target: mean 0.00723 — dark-region
  curvature over 9 luma bins is expected BGU behavior). Host fit: ~0.4 ms;
  on-device (Xiaomi 25080RABDG, 34x25): ~2 ms. Grid contract: gw =
  ceil(w/s), gh = ceil(h/s), gz = round(1/r) + 1; cell (x,y,z) centers on
  low-res (x*s, y*s), luma z*r; storage x-stride-1, channel outermost.
- `scope_meter`: fused scope+meter sampler. One call over a strided u16
  Bayer plane (W,H >= 8, packed pixels, arbitrary row stride) reproduces
  all four Kotlin camera-thread samplers bitwise: 64-bin R/G/B+luminance
  histogram, 96x48 RGB waveform parade, ETTR 256-bin x4 channels +
  saturated/totals + f64 green/spot sums (main region + full-frame guard
  scan when cropped), and focus 96xR Tenengrad-energy/mean grids.
  Percentile levels, brightness means, and focus/peaking masks stay
  Kotlin (scalar post-processing on these outputs). Geometry authority
  stays host-side: steps, metering region, guard flag, and per-phase
  f64/f32 reciprocals arrive as params (the filter never divides by the
  range itself — see the reciprocal rule above). Checked by
  `scope_meter_check.cpp`: 119 cases bitwise vs an independent scalar
  transcription (hash `710f60b7a2942a8d`; third-implementation oracle is
  future work). App wiring is in: `rawLensScopeMeter` JNI (zero-copy
  direct buffers, HAL row stride honored) + `ScopeMeterNative` facade
  (one call per frame, existing samplers as fallback) + `ScopeMeterAB`
  on-device transfer check (bitwise diff + timings via logcat). Step and
  density formulas are shared helpers so tuning can never diverge
  between Kotlin and native. Controller rewiring (single call site per
  tick) is the remaining step.

## BGU provenance

`generators/bgu_spike_downsample.cpp` adapts google/bgu
`src/halide/box_downsample_generator.cpp` (Apache 2.0). The BGU fit/slice
port in later steps derives from `fit_and_slice_affine_grid_halide.cpp`
(modern Generator style — base new code on it, not on the 2016-era
`fit_and_slice_3x4.cpp`). License entries go to `NOTICE.md` at hardening time.
