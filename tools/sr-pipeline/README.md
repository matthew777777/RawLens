# sr-pipeline

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

From the repo root:

```bash
# Minimal: CPU backend, defaults (ref 0, no crop, all frames)
./gradlew :tools:sr-pipeline:run --args="--in /tmp/burst --out /tmp/sr-out --backend cpu"

# With reference frame, crop, and frame limit
./gradlew :tools:sr-pipeline:run --args="--in /tmp/burst --out /tmp/sr-out --ref 2 --crop 0,0,512,512 --limit 8 --backend cpu"

# Vulkan backend (needs the compiled stage shaders)
./gradlew :tools:sr-pipeline:run --args="--in /tmp/burst --out /tmp/sr-out --backend vulkan --spv-dir <dir with the 10 stage .spv modules>"
```

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
| `--spv-dir DIR` | with `--backend vulkan` | directory with the 10 stage `.spv` modules |
| `--lib PATH` | no | JNI library to load (default: `System.loadLibrary`) |
| `--lut PATH\|auto` | no | noise LUT `.npz`, or `auto` to simulate it in-process; enables noise correction |
| `--kernel wronski\|kernelnet` | no | reconstruction kernel (`--kn-scale F`, `--kn-cap F` tune KernelNet) |
| `--s3 on\|off` | no | supplement S.3 high-frequency artefact rejection |
| `--s1 F --s2 F`, `--kernel-gat on\|off` | no | IPOL-vs-paper A/B parameters |
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
