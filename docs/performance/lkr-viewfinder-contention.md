# GPU RAW viewfinder during LK-R (2026-11-18)

## Evidence and scope

The supplied log has a roughly 30 Hz sensor while RAW preview falls to
17.6 fps. One draw reaches 603 ms: 421 ms compute and 179 ms GL tone/present.
The example 4080x3060 eight-frame, three-worker merge takes 24,222 ms natively.
Those observations are not a controlled benchmark. The initial proposed NEON
fallback was removed; AUTO preview remains on its GPU path.

Reports are retained under `build/shader-analysis/raw-vf-lkr-20261118/`:

- `before/summary.json`: 79 packaged modules × A750/G615/G720, 237 passed.
- `after/summary.json`: same packaged scope and targets, 237 passed.
- `lkr-before/summary.json`: 44 embedded native LK-R modules, 132 passed.
- `aligned-before/summary.json`, `aligned-after/summary.json`: matching-path
  reports for the original versus explicitly enabled aligned specialization;
  three passes and zero failures in each.
- `candidate-specialized-true-analysis/summary.json`: the additional
  specialization, general module and unchanged image-input twin review.
- `manifest.json`: original/packaged shader SHA-256 and evidence paths.
- `parity-specialized-mali.log`, `parity-specialized-host.log`:
  578 cases and 8,194,944 RGBA16F half components, byte exact on both devices.
- `contention-mali.log`: baseline/candidate/candidate/baseline full merges
  with a separate 30 Hz RAW shader workload using real 4080x3060 RAW data.
- `contention-batch-mali.log`: command submission scheduling experiment.

AOC is 7.0.26 / E17.52.18.00; malioc is 2026.5.0. Physical testing uses
Mali-G615 MC2, driver 184553472. Host parity uses Apple M1 / MoltenVK.
A750 and G720 have offline analysis only. A740 was not tested; its three
known compiler assertions remain documented in the shader-analysis README.

## Portable shader change

`vf_superpixel_linear.comp` retains its original general fetch path and adds
specialization constant 0 (`ALIGNED_WORDS`, false by default). The native
buffer dispatch selects the specialized pipeline only when x origin, step,
and row pitch are even. It then loads two packed uint words for the 2×2
Bayer quad, selects the CFA sites, and applies the same per-site Q6 levels.
Other geometry uses the original general pipeline. This preserves zero-copy,
preview resolution, colour/lens processing and the saved image algorithm.

| Metric | Before | Aligned specialization |
|---|---:|---:|
| G615/G720 load/store longest-path cycles | 6 | 4 |
| G615/G720 arithmetic longest-path cycles | 1.03125 | 0.734375 |
| G615/G720 work registers | 17 | 16 |
| G615/G720 spill bytes | 0 | 0 |
| A750 main instructions | 203 | 179 |
| A750 overall register footprint | 6 | 5 |
| A750 scratch bytes | 0 | 0 |

The default unspecialized module retains the original estimates; assess the
explicitly specialized report to evaluate the runtime fast path. Estimates
are not interchangeable across GPU architectures. Earlier experimental
rewrites were rejected after a Mali cost regression or parity failure.

Mali interleaved ABBA shader timestamps at 1020×764, step 4, 112 samples
per variant: unshaded median 2.845154 → 2.398462 ms; shaded median
3.002539 → 2.867692 ms. The usual viewfinder shades later in GL, so the
unshaded compute case is the relevant isolated shader measurement.

## Concurrent merge check

The runner links the current app's ARM64 `liblkrl_core.a` and matching
`libsrpipeline.so`. It merges the existing eight-frame car burst at full
4080×3060, scale 1, three workers, with warmed pipeline caches. A separate
headless Vulkan process offers either original or candidate preview compute
at 30 Hz. No camera, UI, app installation or root access is used.

| Run | Merge ms | Shader median ms | Submit-to-completion p95 ms |
|---|---:|---:|---:|
| Baseline 1 | 17573 | 2.857923 | 105.276538 |
| Candidate 1 | 17012 | 2.603769 | 120.926077 |
| Candidate 2 | 17320 | 2.424923 | 92.856615 |
| Baseline 2 | 17139 | 2.757154 | 118.195154 |

Mean merge times are 17,356 ms versus 17,166 ms. This small sample shows no
merge slowdown signal, not proof of universally unchanged throughput. All
four merged float outputs have SHA-256
`333a3de4b94bba69457935049bebb73aafe34168634c63fe293047a7d0af2946`.
Shader parity also passed for the real RAW input. Queue stalls remain:
shader optimization alone does not establish a smooth 30 fps viewfinder.

## LK-R shader bottlenecks on both vendors

The log's dominant stages (`adj_s1`, `fin`, `jac`, `jacblk`) are load/store
bound in both Mali reports. `adj_s1` uses 32 work registers and 100% occupancy;
`fin` also uses 32/100%; `jac` uses 64/50%; `jacblk` uses 58/50%. None spills.
A750 reports no scratch allocation for these stages, with register footprints
6, 4, 10 and 10 respectively. Its reported ALU fiber occupancy is 200%, 200%,
100%, 100%; this vendor metric is not the same as Mali thread occupancy.
This argues against treating the dominant merge problem as shader spilling.

Some available DCT variants are expensive on both architectures:
`lkr_dct_xo_y` spills 516 bytes on G615 and 528 on G720; A750 instead reports
37 registers, no scratch, and 25% ALU fiber occupancy. These are not the active
`dct_y/u/v` variants named in the supplied log. Offline inclusion alone does
not make a shader a hot path. No LK-R shader or numerical algorithm was changed.

## Rejected scheduling experiment

The test patch `rejected-command-batching.patch` split LK-R recording into
separately submitted command buffers, keeping all dispatches, barriers,
worker count and one final host fence wait. It was opt-in through
`LKRL_DISPATCH_BATCH`; it has been removed from the source tree.

| Four-dispatch experiment | Merge ms | Preview completion p95 ms |
|---|---:|---:|
| Original jobs A | 18270 | 37.234539 |
| Four-dispatch A | 18373 | 26.129538 |
| Four-dispatch B | 18346 | 66.562385 |
| Original jobs B | 18952 | 105.941769 |

All four merged float outputs have the same SHA-256 as the earlier shader
comparison. These results suggest potential scheduling effects but do not
establish stable sub-33-ms service. A subsequent one-dispatch ABBA test was
inconsistent: p95 was 193.736385 and 110.256846 ms versus original-job
148.476692 and 110.731384 ms. One-dispatch merge times were 15,445/16,126 ms
versus 15,995/15,974 ms. Thus progressively smaller batches are not a verified
fix; no batching policy is shipped. The complete second test is retained in
`contention-single-mali.log`.

The final release build contains the GPU shader specialization and no NEON
merge fallback or LK-R scheduling changes. The full camera/AHB/GL presentation
path remains unmeasured in the foreground; this work does not claim to solve
stable 30 fps during merging.

## Reproduction

From the repository root:

```sh
python3 tools/shader-analysis/analyze.py --backend both --adreno-core a750 \
  --core Mali-G615 --core Mali-G720 --output build/shader-analysis/NAME
# Existing shader recipe, also recorded at the top of its source:
glslangValidator -V --target-env vulkan1.1 \
  app/src/main/assets/shaders/vf/vf_superpixel_linear.comp \
  -o app/src/main/assets/shaders/vf/vf_superpixel_linear.spv
spirv-val --target-env vulkan1.1 app/src/main/assets/shaders/vf/vf_superpixel_linear.spv
# Materialize the runtime specialization for offline analysis:
spirv-opt --set-spec-const-default-value '0:true' --freeze-spec-const -O \
  app/src/main/assets/shaders/vf/vf_superpixel_linear.spv -o ALIGNED.spv
```

`tools/shader-analysis/parity/superpixel_parity.cpp` contains host/Android
build commands. It tests all CFA layouts, odd/even origins and pitches,
steps 2/3/8, 10/12/14-bit values, shading, odd dispatch extents, and native-
equivalent specialization selection. Its benchmark uses single-dispatch
ABBA samples and GPU timestamps. `--variant 0|1 --hz 30 --seconds N
--unshaded-only --raw16 PATH` selects a paced real-RAW workload. SIGINT
stops it cleanly and prints timing summaries. Host wall measurements
include submission, queue completion and query retrieval; they are not
displayed-frame timings or a measurement of the Camera2/AHB/GL pipeline.

Final validation: `:app:assembleGithubRelease` passed after removing the
command-batching experiment; `git diff --check` passed. No APK was installed.
