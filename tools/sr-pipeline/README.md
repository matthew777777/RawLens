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
KernelNet's colour-difference path uses a narrower, area-normalized comparison
detail footprint at both 1x and 2x. The wider reference fallback and chroma
kernels remain active. This reduces reconstruction softness and exposes more
grain; it does not add display sharpening.
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
| `--rt-sharpen on\|off` | no | RT capture sharpening on the merged linear DNG, with up to four Richardson–Lucy iterations (default off) |
| `--kernel wronski\|kernelnet` | no | reconstruction kernel (`--kn-scale F`, `--kn-cap F` tune KernelNet) |
| `--kn-floor F` | no | override the base KernelNet width floor (including reference support); positive and no greater than `--kn-cap` |
| `--continuous-motion on\|off` | no | Wronski: KernelNet's regularized flow, smooth flow/robustness sampling and soft motion rejection (default off = IPOL parity; the app uses on; always on with KernelNet) |
| `--s3 on\|off` | no | supplement S.3 high-frequency artefact rejection |
| `--s1 F --s2 F`, `--kernel-gat on\|off` | no | IPOL-vs-paper A/B parameters |
| `--noise-ab a,b` | no | explicit normalized noise variance model `a*signal+b`, overriding tags/estimation |
| `--colour-diff auto\|off\|R,B` | no | Z1 zipper correction; Vulkan KernelNet defaults to reference `AsShotNeutral` gains and the app's scale-dependent chroma width. `off` retains independent-channel reconstruction. Wronski/CPU defaults remain off for parity. |
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

## Experimental organic reconstruction (CPU study)

`--reconstruction organic --backend cpu --kernel wronski` selects a new
pooled local polynomial reconstruction. Green fits a regularized quadratic
surface to the accepted burst samples; white-balanced colour differences fit
local planes with luminance and colour range gates. It targets sample-density
bias and texture smoothing in ordinary kernel averaging. Low temporal support
retains the conventional merge. This is a CPU prototype, not a validated Vulkan
upgrade or an assurance that painting/bleeding artefacts are eliminated.
Use 512-pixel sensor crops first; the extra moment buffers are expensive at full
resolution. Existing parity/default reconstruction remains `legacy`.

```bash
tools/sr-pipeline/run.sh \
  --in "$HOME/Downloads/hdrplus_subset/IMG_20210228_133623" \
  --out "$HOME/Downloads/IMG_20210228_133623_organic_crop" \
  --backend cpu --kernel wronski --scale 1 --tune app \
  --reconstruction organic --crop 1536,1536,512,512
```

Repeated `--tune` flags compose; later duplicate keys win. `--tune app` resets
the earlier overrides. For example, `--tune kStretch=2.5 --tune kShrink=2`
is equivalent to `--tune kStretch=2.5,kShrink=2`.
### Tiled green residual experiment

Set `SRP_GREEN_RESIDUAL=1` when running the Vulkan Wronski CLI at 1× or 2×
with colour differences enabled. The step reuses the merge flow and rejection
weights, predicts the recorded green sensor samples from the merged image,
and back-projects their residuals. The replay caches its inputs in a
temporary directory. GPU working buffers are tile-sized.

At 2×, it integrates each sensor pixel with separable box-overlap weights
covering up to 3×3 output pixels. Two damped passes reuse those weights for
back-projection. Low accepted sample mass reduces the update, and the replay
limits shadow darkening and damps two- and four-pixel repeats in the correction.
Combinations with the resident 1× fit are excluded.

For the streaming 1× experiment, set `SRP_GREEN_ONLINE=1`. It uses
every selected frame, accumulates the normal equations during the merge and runs
six GPU updates over the finished image. The desktop version allocates
full-frame fit buffers and uses no disk cache.

Add `SRP_GREEN_RESIDENT=1` to test the packed gather. Each 2×2 Bayer cell
shares one flow estimate, and each output pixel gathers the two green samples
in its bilinear footprint without atomic writes. The fit reuses the merge's
frame and output buffers and uses three momentum-accelerated updates. The least
reliable frame at each guide cell limits the final correction in motion
regions. The fit filters the correction layer and restores a bounded amount
of fine detail where frame agreement is high and the correction shows little
two-pixel alternation. The Android MFSR app uses the packed gather by default
at 1× with colour differences enabled and two-band reconstruction off.
