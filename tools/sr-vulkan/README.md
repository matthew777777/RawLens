# sr-vulkan — unified SR merging library + desktop CLIs

One library for Android (NDK) and desktop (Linux/macOS): the phone's exact
SR core (see `PARITY.md`) with CPU and unified Vulkan compute backends.
Thin CLIs iterate on burst DNGs with no device:

- `tools/mosaic-desktop`: Mosaic SR → derived-Bayer DNG (CPU streaming,
  same calls as `runSrMosaicDng`)
- `tools/linear-sr-desktop`: Linear RGB SR → LinearRaw DNG (CPU oracle
  `RawSrBayerMerge.merge`, or `--backend vulkan` for the GPU path)

## Prerequisites

- JDK 17+, Gradle wrapper (repo root), Python 3
- `glslangValidator` + glslang library (shader transform and host ncnn
  build; `brew install glslang`)
- `exiftool` (output inspection only)
- Vulkan SDK pieces for the native turn: `molten-vk`, `vulkan-headers`
  (macOS); `glslang-tools` + Vulkan SDK (Linux)
- Network once per clean build dir (pinned upstream ncnn sources, ~15MB;
  `-PsrvkSkipNcnn` skips the host ML lib and keeps analytic kernels)

## Build

```bash
./gradlew :tools:mosaic-desktop:installDist :tools:linear-sr-desktop:installDist
./gradlew :tools:sr-vulkan:check   # parity + unit tests
MOSAIC=tools/mosaic-desktop/build/install/mosaic-desktop/bin/mosaic-desktop
LINEAR=tools/linear-sr-desktop/build/install/linear-sr-desktop/bin/linear-sr-desktop
```

## Run with your burst DNGs

```bash
# Fast iteration: 512px crop of the first 2 DNGs
$MOSAIC --in <dng-dir> --out /tmp/mos --crop 0,0,512,512
$LINEAR --in <dng-dir> --out /tmp/lin --crop 0,0,512,512

# Full burst, 8x12MP on M1: mosaic 33s CPU; linear 28s CPU / 9.5s Vulkan
$MOSAIC --in <dng-dir> --out /tmp/mos-full
$LINEAR --in <dng-dir> --out /tmp/lin-full --backend vulkan

# Hand-picked files (order kept; paths resolve against the working
# directory, NOT --in), explicit reference, cache dir
$MOSAIC --in <dng-dir> --out /tmp/mos --files <dng-dir>/a.dng,<dng-dir>/b.dng --ref 1
```

Flags: `--in DIR` `--out DIR` `--ref N` (default middle) `--cache DIR`
(default `<out>/cache`) `--crop x,y,w,h` (even) `--limit N` `--files …`
`--backend cpu|vulkan` (linear only; mosaic is CPU on the phone too)
`--no-kernelnet` `--capture-id ID`.

Desktop Vulkan dispatch defaults to full-grid (`-Dsrvk.sliceBudget=MAX`
in every launch path); run with `-Dsrvk.sliceBudget=` (empty) to
exercise the phone's exact watchdog-sliced schedule instead.

GPU proofs: `VkRoundTripTest` (transfers + clear pass), `ShaderStagingTest`
(staged ESSL == phone `shaderSource`, all 20 shaders), `VkMergeParityTest`
(full Vulkan merge vs CPU oracle: meanAbs 1.0e-8 on a 256px crop).

Inputs: 2–30 uncompressed 16-bit/packed-10 CFA DNGs, same camera/geometry/
phase (checked by `DngAdmission.admit`). Frames without `NoiseProfile` merge
fine (analytic fallback, tag omitted — never fabricated). Mixed-exposure
input is NOT rejected yet (planner wiring is pending): keep bursts
single-exposure like the phone planner would.

## Native library + vkcheck

`src/main/cpp` is the unified Vulkan backend: identical sources build the
desktop `libsrvulkan` (via `:tools:sr-vulkan:buildNative`, staged into the
distribution) and the Android NDK `libsrvulkan.so` (an `srvulkan` target in
the app's own CMakeLists compiles the same files by path — the app
cut over; the GLES SR orchestrator is deleted). Prove the driver on your
machine with either CLI:

```bash
mosaic-desktop vkcheck   # or: linear-sr-desktop vkcheck
```

Expected on Apple Silicon: `device=Apple M1 ... subgroup=32` plus two
`module ... ok` lines for real SR shaders, ending in `vkcheck: OK`.

## Host ncnnMl (KernelNet inference)

`src/main/cpp-ncnn` compiles the SAME `app/src/main/cpp/ncnnMl.cpp` bridge
+ FlowNet custom layers (referenced by path, zero drift) against pinned
upstream ncnn (`c6b351b…`, see the CMakeLists) for Linux/macOS. The desktop
`libncnnMl` exports the same 10 JNI entry points Android loads, so the
ported `KernelNetNcnnProcessor` (byte-identical, now actually compiled —
see the `sourceSets` note in `build.gradle.kts`) runs real inference:
`NcnnKernelNetHostTest` proves Vulkan load + finite in-contract output +
bitwise determinism.

Platform differences (all `#else` branches; Android lines untouched):

- KernelNet defaults to Vulkan on desktop (`MoltenVK`/desktop drivers
  don't share the Adreno CPU rationale); `KN_CPU=1` forces CPU.
- Model files resolve under the shim `AssetManager` root; logging goes to
  stderr with identical messages.
- The JVM only dedups `loadLibrary` by name, so the lib must sit on
  `java.library.path`: tests/`run` set it via `systemProperty`, installDist
  ships the loose lib under `lib/native/<plat>` and injects the flag via
  `JAVA_OPTS` (`NcnnLoader`). Plain `java -jar` needs the flag by hand.
- Env knobs work on both: `KN_CPU`/`KN_GPU`/`KN_FP32`/`KN_TILE`/
  `KN_BORDER`/`KN_NOTILE`/`KN_STAGETIMING`. (The one-line `dlopen
  libvulkan…` message on macOS is ncnn's normal driver fallback chain
  before it reaches statically linked MoltenVK.)
- Measured on M1 (128px synthetic tile): Vulkan fp16 vs CPU meanAbs
  0.0026, maxAbs 0.037 — multi-backend rounding, each backend
  self-deterministic; both satisfy the s1/s2/rho contract.

## Current limits (next work items)

1. FlowNet/RawNIND Java wrappers are not ported (SR needs KernelNet
   only); their native code compiles into `libncnnMl` but the custom
   Vulkan layers are not runtime-exercised on desktop yet.
2. No burst-planner admission yet; the CLI prints per-frame exposure/ISO.
3. 12MP linear CPU needs heap: `-Xmx5g` is set on the install scripts
   (the Vulkan path streams and needs far less).

## Shader transform

```bash
python3 tools/srvk_shader_transform.py \
  --shaders tools/sr-vulkan/src/main/resources/shaders --out /tmp/srvk-spirv
```

Emits `*.vk.glsl` + `*.spv` + `manifest.json` (binding/offset contract for
the Vulkan host). All 22 SR shaders compile clean for `vulkan1.2`.
