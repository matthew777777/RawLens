# tools/

Host-side code: the MFSR desktop harness, capture-format checks, shader
builders, and verification scripts and oracles. Everything here runs on
Linux/macOS (Apple Silicon included) with no device unless noted. Numerical
parity for MFSR is judged by the error envelopes in `sr-pipeline/PARITY.md`.

## Multi-frame super-resolution (start here)

| Path | What |
|---|---|
| `sr-pipeline/` | Desktop JVM CLI for the MFSR engine (DNG burst directory in, `merged.dng` out; CPU or Vulkan backend). Wired into the build as `:tools:sr-pipeline`; [README](sr-pipeline/README.md) has run flags and tests. |
| `sr-vulkan/src/main/cpp` | MFSR Vulkan sources compiled into the app NDK build by path (not a standalone Gradle project). |
| `jamy_parity_oracle.py` | Independent NumPy transcription of the Jamy-L SR core; writes golden files (`python3 tools/jamy_parity_oracle.py --out <dir>`). |

```bash
./gradlew :tools:sr-pipeline:installDist
./gradlew :tools:sr-pipeline:test
```

## Capture-format checks

| Path | What |
|---|---|
| `tinydng_roundtrip.c`, `verify_tinydng.py` | Host regression for the vendored TinyDNG writer. |
| `build_dng_native.sh` | Builds the host `librawLensDng` for the desktop CLI and JVM unit tests (mirrors the app's CMake recipe). |
| `verify_dng_gainmap.py` | DNG OpcodeList2 GainMap geometry/payload check (stdlib only). |
| `verify_highlights.py` | Highlight handling on a DNG+JPEG pair. |
| `jxl_proof/verify.py` | Host verification of the on-device JPEG XL proof set (`JxlProofDeviceTest`). |
| `sync_craw.sh` | Refresh the vendored Cinematic RAW Studio `.craw` encoder/decoder in `third_party/craw`. |

## Direct Log and video

| Path | What |
|---|---|
| `generate_sgamut3_cine.py` | Derives the D65 linear-sRGB → S-Gamut3.Cine matrix (no third-party modules). |
| `check_direct_log_lut.py` | Compares `DirectLogLutDeviceTest` artifacts with FFmpeg's independent `lut3d`. |
| `build_record_cube_shaders.sh` | Builds the `*_cube.spv` record-shader variants that apply a 3D LUT (spirv-val checked). |
| `build_packed_rcd_shaders.sh`, `verify_packed_rcd_median.py` | Packed RCD shader build and median check. |
| `rcd-baseline/` | Recorded RCD performance baselines. |

## Algorithm references and oracles

| Path | What |
|---|---|
| `amaze_reference_host.cpp`, `build_amaze_reference.py` | Pinned upstream scalar AMaZE kernel, host-buildable. |
| `generate_amaze_fixtures.py` | Host-upstream oracle fixtures (never derive expected values on Android). |
| `generate_amaze_gles_reference.py` | Experimental ordered GLES oracle from the pinned scalar body. |
| `verify_amaze_scalar.py` | Execute production GLSL scalar snippets as C++ (no GPU). |
| `build_opposed_shader.sh`, `verify_opposed_reference.py`, `hl_oracle/`, `hlrecon_transform.py` | darktable inpaint-opposed highlight reconstruction: shader build and host oracle from the pinned OpenCL kernels. |
| `hlmeasure/` | Highlight-reconstruction timing harness ([README](hlmeasure/README.md)). |
| `hdrplus-burst/`, `hdrplus-parity/` | HDR+ burst merge on desktop Vulkan (the shipped merge minus JNI) and its parity checks. |
| `wavelet-parity/` | Host parity gate for the vendored wavelet `raw_denoise` core (macOS + MoltenVK). |
| `parity/ab.py` | Saved-JPEG vs viewfinder-screenshot colour parity A/B. |
| `halide/` | Halide AOT filter builds and BGU checks ([README](halide/README.md)). |
| `fp_sweep.py` | Mali-G615 float rounding vs correctly-rounded host. |

## Release

| Path | What |
|---|---|
| `vendor_release_smoke.py` | Black-box checks of the fully optimized release APK on a connected device; backs up and restores the lens profile. |
