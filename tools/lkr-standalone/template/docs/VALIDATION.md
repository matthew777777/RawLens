# Validation, traces and comparisons

## Build checks

```sh
./build.sh
ctest --test-dir build --output-on-failure
```

CTest includes the self-contained colour-guard CPU check and the Vulkan
robustness check. The latter exercises signed-residual cancellation, static
noise acceptance, reference preservation, shadow chroma/luminance separation,
and clipped-colour protection. A missing Vulkan device is a failure, not a
numerical regression. On macOS, GPU access may require running outside an
application sandbox.

`lkr_mhc_check IN.u16 META.txt OUT.f32` is a CPU parity helper, not a test that
can run without fixtures. Its metadata format is documented at the top of
`host/lkr_mhc_check.cpp`; compare its output with the matching app reference.
`lkr_parity` similarly requires a reference dump.

## Source equality

From RawLens:

```sh
python3 tools/lkr-standalone/sync.py /path/to/LK-Reloaded-Super-Resolution --check
```

This reads the current canonical files and standalone templates and compares
them with every managed destination file and the recorded SHA-256 manifest.
It also detects stale managed files. No line-ending, license-header or path
rewrite is performed during export. Runtime numerical agreement is a separate
check from source equality.

## Compare settings fairly

Keep the same frames, reference index, crop, exposure and output scale.
Save the command and the printed noise fit (`sigma`, `a`, `b`), selected look,
reference and timings. Compare linear data before applying the same display
transform to every image. A different RAW editor auto-exposure or sharpening
setting can make two equivalent reconstructions look quite different.

Start with a crop:

```sh
./run.sh burst base.dng --s 2 --frames 8 --crop 512 512
./run.sh burst dct.dng --s 2 --frames 8 --crop 512 512 --prior 1
```

These measure the prior's effect under production defaults. For a solver
ablation, use `--paper 1 --prior 0` on the same inputs. Do not call that an
evaluation of the paper's trained network.

## Held-out frame consistency

```sh
./run.sh burst heldout.dng --s 2 --frames 8 --crop 512 512 --holdout 7
```

The selected frame is excluded from merging, aligned against the finished
estimate, then compared through the RAW forward model. The console reports
noise-normalized residual energy and block statistics. The additional file
`heldout.dng.holdout.f32` has three planar float32 maps on the evaluated
held-out source grid: squared residual / variance, usable share, and signed
residual / sigma. Record the evaluated grid dimensions; they may differ from
the exported RGB image because of crop alignment and internal processing.

A static, well-explained region is expected near a noise-normalized energy of
1 plus the reconstruction's remaining variance contribution. Rejected frames
or independent motion can raise it; a biased noise fit can move the entire
scale. This diagnostic is not a PSNR score or a ground-truth quality ranking.
Compare the same held-out frame and crop across configurations.

## Trace the iterative stages

Use a DCT prior run so scheduled prior calls produce the intermediate stages
expected by the renderer:

```sh
LKR_TRACE_PREFIX=/tmp/lkrtrace LKR_TRACE_TILE=0 \
  ./run.sh burst trace.dng --s 2 --frames 8 --crop 512 512 --prior 1

python3 -m venv .venv
.venv/bin/pip install numpy pillow
.venv/bin/python tools/lkr-pipeline/inspect_trace.py \
  /tmp/lkrtrace_tile_0_0_meta.txt --frame 1
```

Use the actual emitted `*_meta.txt` filename. A tile index selects a tile in
processing order; the filename suffix records its crop-relative origin.
Tracing is disabled unless `LKR_TRACE_PREFIX` is set.

Dumps contain input frames, initial/final motion, prior input/output,
temporal residuals and row moments, and final stages before/after finishing.
RGB is planar camera-linear. Per-frame temporal maps are in source-frame
coordinates rather than a displayed RGB warp. `temporal_rows` is
`K × h × w × 4`: sum residual, sum variance, count, squared-residual sum.
The supplied renderer uses one exposure across its stage views.

## Diagnostic maps and developer hooks

```sh
LKR_DEBUG_MAPS=/tmp/lkr.maps \
  ./run.sh burst maps.dng --s 2 --frames 8 --crop 512 512
```

Five planar float32 maps summarize effective frame weight, temporal
acceptance, prior noise factor, shadow mask and maximum local displacement.
They are source-grid diagnostic summaries, not per-pixel ground truth after
warping. Consult `kDebugMaps` and the output grid in `lkr_full.cpp` before
reshaping a dump.

Additional developer variables exist for local motion/noise/weight inspection:
`LKR_DEBUG_SUB`, `LKR_DEBUG_BS`, `LKR_DEBUG_NS`, `LKR_DUMP_NS`, `LKR_DUMP_WT`,
and `LKR_DUMP_ZX`. Their file formats are defined at the write sites in
`lkr_full.cpp` and `lkr_solver.cpp`. Prefer a single worker and small crop
when using experimental dump paths that are shared across tiles.

## Historical equation parity

The canonical technical README records measurements from **2026-11-04 on an
Apple M1**, using eight 512 × 512 crops, scale 2 and 16 iterations. It reports
solver-vs-PyTorch PSNR of 128.1 dB / 122.3 dB on its day/night cases, with small
motion differences and matching displayed noise estimates. These compare two
implementations of an equation configuration; they are not image-quality
PSNR against scene ground truth, and were not remeasured as part of packaging.

Those historical settings differ from today's eight-iteration CLI defaults.
The Python parity tooling is in RawLens `python/lkr`, not bundled here. Follow
its source instructions to create a dump, run `build/pipeline/lkr_parity DUMP`,
then compare the generated arrays with the reference in that environment.
Record the exact configuration and both commits for any new parity claim.

## Performance measurements

Report GPU/driver, CPU, frame count, sensor/crop size, output scale, prior,
iterations, tiles, workers and cold/warm status. Loading time and merge time
are printed separately; the sum of per-tile GPU/prior times is not necessarily
the end-to-end wall time under parallel workers. DNG export and display
development add further work. No universal speed claim is made by this package.

Use `--profile 1` for per-shader GPU timings. The current executable also prints
setup, initialization, finishing, blending and worker-wait timings. Pipeline
cache reuse affects warm timings, so record whether a run starts in a fresh
process and keep profiling settings consistent across comparisons.
