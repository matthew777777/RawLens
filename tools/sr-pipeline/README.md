# sr-pipeline

> See [current repository state](../../docs/CURRENT_STATE.md) for app integration, current merge names, build requirements and the scope of historical evidence.

Desktop JVM CLI for the MFSR engine (multi-frame super-resolution after
Wronski et al. 2019 / the IPOL reference) that the app runs for ZSL and
offline merges: DNG burst directory in, one `merged.dng` out.

- Pure JVM command-line module, no Android dependencies. `minSdk`/`compileSdk`
  do not apply here; it targets Java 21 like the app.
- Package root: `com.rawlens.srpipeline`.
- Entry point: `com.rawlens.srpipeline.cli.MainKt`
  (`src/main/kotlin/com/rawlens/srpipeline/cli/Main.kt`).
- Toolchain (matches repo conventions): Gradle 9.8.2, Kotlin-JVM 2.4.21
  (pinned once in the root `build.gradle.kts`), `jvmTarget 21`,
  JUnit 4.13.2 for tests. Repositories are locked at the root
  (`FAIL_ON_PROJECT_REPOS`, `google()` + `mavenCentral()`).
- Wired into the build via `include(":tools:sr-pipeline")` in
  `settings.gradle.kts`.

## Run

### Terminal launcher (macOS / Linux)

The launcher builds the JVM CLI, native JNI library and all Vulkan shaders,
then passes the correct absolute paths to the CLI. Run it from any directory.
It defaults to **2× Vulkan KernelNet**, all frames, reference 0, full frame,
automatic noise correction, and a 6 GB maximum Java heap.

Prerequisites: JDK 21 or newer, CMake, Vulkan headers and loader,
`glslangValidator`, and a working Vulkan driver. On macOS with Homebrew:

```bash
brew install openjdk cmake glslang vulkan-headers vulkan-loader molten-vk
export JAVA_HOME="$(brew --prefix openjdk)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

From the repo root:

```bash
tools/sr-pipeline/run.sh \
  --in "/Users/monikamalinowska/Documents/IMG_20261112_125318_179" \
  --out "$PWD/output/IMG_20261112_125318_179-kernelnet-vulkan-2x"
```

The result is `merged.dng` inside the output directory (an existing file of
that name is replaced). This is a demosaiced linear RGB DNG, with camera color
metadata and orientation copied from the reference, rather than a CFA mosaic.
`--scale 2` doubles both pixel dimensions. Paths with spaces must be quoted.
Override defaults by appending flags, e.g. `--scale 1 --kernel wronski`.
For a quick check, append `--crop 0,0,512,512 --limit 2` and use a separate
output directory. Use `JAVA_OPTS='-Xmx8g' tools/sr-pipeline/run.sh ...` to
change the heap limit.

If `NoiseProfile` is absent, the CLI estimates per-plane shot/read noise from
the full reference frame using same-CFA Haar coefficients and brightness-bin
MADs, and prints the values. Strong texture can inflate this blind estimate;
use `--noise-ab a,b` for a measured model. Frames without enough unclipped,
nonzero-variance samples require this explicit override.

### Manual Gradle invocation

These commands require a built native library **for both backends**.
`--lib` must point to it unless it is already on `java.library.path`.
Build it and the shaders with the launcher above, or configure
`tools/sr-pipeline/host` with CMake and `-DSRP_JAVA_HOME="$JAVA_HOME"`.

```bash
# Minimal: CPU backend, defaults (ref 0, no crop, all frames)
./gradlew :tools:sr-pipeline:run --args="--in /tmp/burst --out /tmp/sr-out --backend cpu --lib $PWD/tools/sr-pipeline/build/host/libsrpipeline_jni.dylib"

# With reference frame, crop, and frame limit
tools/sr-pipeline/run.sh --in /tmp/burst --out /tmp/sr-out --ref 2 --crop 0,0,512,512 --limit 8 --backend cpu

# Vulkan backend (needs the compiled stage shaders)
tools/sr-pipeline/run.sh --in /tmp/burst --out /tmp/sr-out --backend vulkan --kernel kernelnet --scale 2
```

On Linux the native library suffix is `.so`. The launcher handles this.
If Vulkan cannot find a macOS driver, set `VK_DRIVER_FILES` to the installed
`MoltenVK_icd.json` (typically `$(brew --prefix)/etc/vulkan/icd.d/MoltenVK_icd.json`).

CLI flags (see `Main.kt` usage text):

| Flag | Required | Meaning |
| ---- | -------- | ------- |
| `--in DIR` | yes | input directory of `.dng` burst frames |
| `--out DIR` | yes | output directory, receives `merged.dng` |
| `--backend cpu\|vulkan` | yes | compute backend |
| `--ref N` | no, default `0` | reference frame index, `>= 0` |
| `--crop x,y,w,h` | no | crop rectangle, `x,y >= 0`, `w,h > 0` |
| `--limit N` | no | max frames to process, `> 0` |
| `--scale F` | no, default `1` | super-resolution factor, `>= 1` |
| `--spv-dir DIR` | with `--backend vulkan` | directory with the compiled stage `.spv` modules; supplied by launcher |
| `--lib PATH` | no | JNI library to load (default: `System.loadLibrary`) |
| `--lut PATH\|auto` | no | noise LUT `.npz`, or `auto` to simulate it in-process; enables noise correction |
| `--kernel wronski\|kernelnet` | no | reconstruction kernel (`--kn-scale F`, `--kn-cap F` tune KernelNet) |
| `--s3 on\|off` | no | supplement S.3 high-frequency artefact rejection |
| `--s1 F --s2 F`, `--kernel-gat on\|off` | no | IPOL-vs-paper A/B parameters |
| `--noise-ab a,b` | no | explicit normalized noise variance model `a*signal+b`, overriding tags/estimation |
| `--colour-diff R,B` | no | Z1 zipper fix: colour-difference merge with white-balance gains R,B (e.g. `1/AsShotNeutral`); off by default (Python parity) |
| `--heap-budget-mb N` | no | fail the run if the JVM heap pool exceeds N MB |
| `--help` / `-h` | no | print usage |

Run the tests:

```bash
./gradlew :tools:sr-pipeline:test
```

## Non-goals (explicit)

- No bit-exactness claim: JVM/Vulkan outputs are NOT promised pixel-identical
  to the Python CPU source or to each other. Numerical parity is judged by the
  error envelopes in [PARITY.md](PARITY.md), never by exact equality.
- No Android app code and no on-device behavior: this module never touches
  `minSdk`/`compileSdk`, camera, or UI code.
- No new repositories or version catalogs: dependencies stay inline literals
  per current repo convention; repo-wide repos stay locked.
