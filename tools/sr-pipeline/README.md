# sr-pipeline

Desktop JVM CLI harness for the multi-frame super-resolution (SR) pipeline:
burst frame directory in, one super-resolved output out.

- Pure JVM command-line module, no Android dependencies. `minSdk`/`compileSdk`
  do not apply here; it targets Java 17 like the rest of the desktop tooling.
- Package root: `com.rawlens.srpipeline`.
- Entry point: `com.rawlens.srpipeline.cli.MainKt`
  (`src/main/kotlin/com/rawlens/srpipeline/cli/Main.kt`).
- Toolchain (matches repo conventions): Gradle 8.13, Kotlin-JVM 2.0.21
  (pinned explicitly in this module's `build.gradle.kts`), `jvmTarget 17`,
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

# Vulkan backend (selection only for now; kernels land later)
./gradlew :tools:sr-pipeline:run --args="--in /tmp/burst --out /tmp/sr-out --backend vulkan"
```

CLI flags (see `Main.kt` usage text):

| Flag | Required | Meaning |
| ---- | -------- | ------- |
| `--in DIR` | yes | input frame directory |
| `--out DIR` | yes | output directory |
| `--backend cpu\|vulkan` | yes | compute backend |
| `--ref N` | no, default `0` | reference frame index, `>= 0` |
| `--crop x,y,w,h` | no | crop rectangle, `x,y >= 0`, `w,h > 0` |
| `--limit N` | no | max frames to process, `> 0` |
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
- No compute kernels yet: `--backend vulkan` currently only selects the
  backend name; the merge path is still `TODO` in `Main.kt`.
- No new repositories or version catalogs: dependencies stay inline literals
  per current repo convention; repo-wide repos stay locked.
