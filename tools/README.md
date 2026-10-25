# tools/

Host-side code: the super-resolution pipeline harness, capture-format
checks, and one-off verification scripts. Everything here runs on Linux/macOS
(M1 Apple Silicon included) with no device. Numerical parity is judged by the
error envelopes in `sr-pipeline/PARITY.md`.

## Super-resolution (start here)

| Path | What |
|---|---|
| `sr-pipeline/` | Desktop JVM CLI harness for the multi-frame SR pipeline (burst frames in, super-resolved output out). Wired into the build as `:tools:sr-pipeline`; [README](sr-pipeline/README.md) has run flags and tests. |
| `sr-vulkan/src/main/cpp` | SR Vulkan sources compiled into the app NDK build by path (not a standalone Gradle project). |
| `jamy_parity_oracle.py` | Independent NumPy transcription of the Jamy-L SR core; generates the `JamyCoreParityTest` goldens (`python3 tools/jamy_parity_oracle.py --out <dir>`). |

```bash
./gradlew :tools:sr-pipeline:installDist
./gradlew :tools:sr-pipeline:test
```

## Capture-format checks

| Path | What |
|---|---|
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
| `import_rawsr_forest.py`, `import_rawsr_sea.py` | Lossless DNG Bayer fixture importers (numpy/tifffile/exiftool). |
| `fp_sweep.py` | Mali-G615 float rounding vs correctly-rounded host. |
