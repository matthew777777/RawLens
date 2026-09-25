# tools/

Host-side code: the unified super-resolution library, desktop burst CLIs,
and one-off verification scripts. Everything here runs on Linux/macOS
(M1 Apple Silicon included) with no device; the Android app lives in
`app/` and shares the SR sources below line-for-line (see
`sr-vulkan/PARITY.md`).

## Super-resolution (start here)

| Path | What |
|---|---|
| `sr-vulkan/` | Unified SR merging library for Android (NDK) + desktop. [README](sr-vulkan/README.md) has prerequisites, build/run, vkcheck, host ncnn, and limits. |
| `mosaic-desktop/` | Mosaic SR → derived-Bayer DNG CLI on the library. |
| `linear-sr-desktop/` | Linear RGB SR → LinearRaw DNG CLI on the library (`--backend vulkan`). |
| `parity_sr_vulkan.py` | Mechanical 1:1 enforcement (runs in `:tools:sr-vulkan:check`). |
| `srvk_shader_transform.py` | Stages GLSL → `*.vk.glsl` + `*.spv` + `manifest.json`. |
| `srvk_spirv_check.py` | Pins checked-in SPIR-V to the shader tree. |

```bash
./gradlew :tools:mosaic-desktop:installDist :tools:linear-sr-desktop:installDist
./gradlew :tools:sr-vulkan:check   # parity + unit tests
```

## Capture-format checks

| Path | What |
|---|---|
| `mcraw/` | MCRAW compatibility harness vs both reference decoders. [README](mcraw/README.md). |
| `tinydng_roundtrip.c`, `verify_tinydng.py` | Host regression for the vendored TinyDNG writer. |
| `verify_dng_gainmap.py` | DNG OpcodeList2 GainMap geometry/payload check (stdlib only). |
| `verify_highlights.py` | Highlight handling on a DNG+JPEG pair. |

## Algorithm references and oracles

| Path | What |
|---|---|
| `amaze_reference_host.cpp`, `build_amaze_reference.py` | Pinned upstream scalar AMaZE kernel, host-buildable. |
| `generate_amaze_fixtures.py` | Host-upstream oracle fixtures (never derive expected values on Android). |
| `generate_amaze_gles_reference.py` | Experimental ordered GLES oracle from the pinned scalar body. |
| `verify_amaze_scalar.py` | Execute production GLSL scalar snippets as C++ (no GPU). |
| `build_dcg_vulkan_probe.sh`, `merge_dcg_probe.py` | Dual-camera-gain Vulkan probe build + linear CFA fusion. |
| `import_rawsr_forest.py`, `import_rawsr_sea.py` | Lossless DNG Bayer fixture importers (numpy/tifffile/exiftool). |
| `fp_sweep.py` | Mali-G615 float rounding vs correctly-rounded host. |
