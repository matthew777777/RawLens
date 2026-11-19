# GPU offline shader analysis

Run from the repository root (Python 3, no pip dependencies):

```sh
python3 tools/shader-analysis/analyze.py --core Mali-G615 --core Mali-G720
```

This analyzes every packaged `.spv` under `app/src/main/assets`, including
precompiled variants, using Arm Mali Offline Compiler. It discovers `malioc`
on PATH or in `/Applications/Arm Performance Studio */mali_offline_compiler`.
Override with `MALIOC` or `--malioc`. List available targets with `malioc --list`.
The local installation is Performance Studio 2026.6 / malioc 2026.5.0.

Pass specific SPIR-V files or directories to narrow the run. Use the same
paths and distinct `--output` directories for before/after comparisons.
Reports include commands, compiler version, input SHA-256, vendor JSON,
stdout/stderr, and exit codes. Outputs default to ignored `build/shader-analysis`.
Any compilation failure makes the command fail. Vendors run concurrently with
`--backend both`; `--jobs` controls the shared concurrency limit.

## Adreno on Apple Silicon

The user-supplied Linux x86-64 AOC 7.0.26 archive is extracted under the ignored
`.local/adreno-offline-compiler/7.0.26/` directory. Its bundled
`OfflineCompiler.html` documents the command options and GPU support.
The licensed compiler stays outside Git and outside the container image.

This Mac uses Colima's dedicated `rawlens-shaders` profile (4 CPUs, 4 GiB RAM,
20 GiB sparse data disk), an ARM Linux VM with Rosetta for x86-64 execution.
It does not change the active Docker context or start automatically at login.

Recreate the runtime if needed:

```sh
brew install colima docker
# If absent, install Rosetta using Apple's installer.
colima start --profile rawlens-shaders --vm-type vz --vz-rosetta \
  --cpus 4 --memory 4 --disk 20 --mount "$PWD" \
  --ssh-config=false --activate=false
docker --context colima-rawlens-shaders build --platform linux/amd64 \
  -f tools/shader-analysis/Dockerfile.aoc -t rawlens-aoc:7.0.26 tools/shader-analysis
```

Ubuntu 24.04 supplies glibc >= 2.38 plus `libstdc++6`, `libtinfo6`, and `zlib1g`.
Older images may not satisfy this AOC binary's glibc requirements.
Run both vendors together:

```sh
python3 tools/shader-analysis/analyze.py --backend both \
  --core Mali-G615 --core Mali-G720 --adreno-core a750
```

Repeat `--adreno-core` to analyze additional GPUs. The bundled documentation
lists a608, a640, a650, a660, a730, a740, a750, a830, and a840 for Vulkan.
A690 and A741 are Direct3D targets and should not be used for these Vulkan shaders.
The default is a750. The stage is read from SPIR-V rather than inferred from
filenames, many of which end only in `.spv`.

`aoc.py` launches each compiler job in a temporary container, mounts the repository
read-only at the same absolute path, disables network access, and returns vendor
stdout/stderr. Compiler scratch files use container `/tmp`. Inputs must be inside
the repository. Override the Docker context or image with
`RAWLENS_AOC_DOCKER_CONTEXT` or `RAWLENS_AOC_IMAGE`.

Start/stop this dedicated VM when needed:

```sh
colima start --profile rawlens-shaders
colima stop --profile rawlens-shaders
```

The runner automatically selects `aoc-command.json` when the local compiler is
present. `--adreno-command PATH` still supports another installation via a JSON
argv array. Templates support `{input}`, `{output}` (report prefix), `{name}`,
`{root}`, `{core}`, and `{stage}` (e.g. `-cs`); commands execute without a shell.
For another host, obtain the licensed package from Qualcomm:
https://softwarecenter.qualcomm.com/catalog/item/Adreno_GPU_Offline_Compiler

## Verified baseline (2026-11-17)

77 packaged Vulkan compute modules, AOC 7.0.26 / compiler E17.52.18.00 and
malioc 2026.5.0:

| Target | Passed | Failed |
|---|---:|---:|
| Mali-G615 | 77 | 0 |
| Mali-G720 | 77 | 0 |
| Adreno A750 | 77 | 0 |
| Adreno A740 | 74 | 3 |

Full raw evidence is in ignored
`build/shader-analysis/baseline-2026-11-17/summary.json` and its per-shader
directories. A740 emits `FATAL ERROR: Internal assertion error` for
`vf_rcd.spv`, `vf_rcd_f16.spv`, and `denoise_img_wiener_apply.spv`.
All three pass `spirv-val --target-env vulkan1.1` and analysis on the other
three GPU targets. Repeating A740 analysis serially with `-dump=stats` also
fails; disabling performance projection does not avoid these assertions.
The runner retains and reports the failures. An offline compiler assertion
does not establish whether the shader will work on an A740 device driver.

## Using reports to optimize

Inspect vendor register pressure, spilling, instruction/cycle estimates, and
arithmetic versus texture/load-store bottlenecks. Compare each GPU against its
own baseline; cycle estimates from different architectures are not interchangeable.
Offline estimates do not include dispatch overhead, real cache behavior, or thermal
effects. Measure representative scenes on devices and check image/numerical parity
before accepting source changes. This command does not rewrite shaders or package
vendor machine code: Android drivers still compile the portable SPIR-V.

This initial default scope covers packaged Vulkan modules, not runtime-generated
GLES strings or unbuilt native shader sources. Rebuild a changed module with its
existing build recipe before analyzing it. For RapidRAW's assembled GLES variants,
use the existing specialized harness:

```sh
python3 tools/rapidraw-develop/malioc.py --core Mali-G615 \
  --output build/shader-analysis/rapidraw-g615.json
```

Arm reference: https://developer.arm.com/Tools%20and%20Software/Mali%20Offline%20Compiler

## RAW superpixel A/B harness

`parity/superpixel_parity.cpp` compares the original and candidate RGBA16F
buffer superpixel modules, including native-equivalent `ALIGNED_WORDS`
specialization selection. The source header contains host/Android build
commands. It exercises CFA layouts, alignment, pitches, sample steps, levels,
and lens shading, then reports interleaved single-dispatch GPU timestamps.

For concurrent headless testing, `--bench-only --unshaded-only --variant 0`
(or `1`) `--hz 30 --seconds 60 --raw16 PATH` uses tight 4080x3060 RAW16 data
at a paced offer rate. SIGINT prints timing summaries and exits cleanly.
Wall latency includes queue completion and query retrieval; it does not
measure actual displayed viewfinder FPS. See
[the 2026-11-18 investigation](../../docs/performance/lkr-viewfinder-contention.md)
for before/after reports and the rejected command-batching experiment.
