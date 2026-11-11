# Command-line reference

```sh
./run.sh BURST_DIR OUT.dng [OPTIONS]
```

The wrapper locates the executable, builds it if missing, and forwards options
unchanged to `lkr_full_run`. Relative input/output paths refer to your current
working directory. Create the output's parent directory before running.

Use `./run.sh --help` for a short usage message. The native executable has a
minimal parser: unknown options can be ignored, and missing option values are
not safely diagnosed. Use the spellings below and supply every value.

## Current defaults

The production CLI calls `AppDefaults()` in `lkr_tuning.h`: scale 1, eight
iterations, three image steps per iteration, 512-pixel tiles, 32-pixel overlap,
three workers, prior disabled, and production robustness enabled. It reads
all lowercase `.dng` files in the folder, sorts filenames lexically, and uses
the first as reference. Frame indices are zero-based.

Default values below describe the source snapshot shipped with this guide.
Noise-dependent values are identified rather than presented as fixed constants.

## Input, output and geometry

| Option | Default | Meaning |
| --- | --- | --- |
| `--s 1\|2` | `1` | Scale per dimension; 2 produces four times as many pixels |
| `--frames K` | all | Limit the selected burst to K frames |
| `--reference N` | `0` | Move sorted frame N to the front, before the frame limit is applied |
| `--crop W H` | full frame | Process a sensor-space region of this size |
| `--at X Y` | centred | Crop origin in sensor coordinates; combine with `--crop` |
| `--tile N` | `512` | Low-resolution tile edge, including overlap; use an even value |
| `--overlap N` | `32` | Low-resolution overlap, even; clamped to at most tile/4 |
| `--workers N` | `3` | Concurrent tile solvers; use 1 to reduce memory |

The executable reads dimensions, CFA, black/white levels and colour metadata
from the selected reference, then uses these to process the entire burst.
Supply matching frames. Mixed dimensions, sensor layouts or exposure settings
are not a supported input-validation workflow.

## Iterations, alignment and model

| Option | Default | Meaning |
| --- | --- | --- |
| `--iterations N` | `8` | Outer iterations; at least 2; rebuilds alternating prior-call indices |
| `--smax X` | `10` | Starting schedule noise in multiples of estimated sigma |
| `--refine 0\|1` | `1` | Iterative motion refinement |
| `--mev N` | `2` | Motion update cadence after warmup |
| `--local N` | `16` | Local motion block setting; 0 disables the local field; use the supported 16-pixel mode |
| `--llam X` | automatic | Explicit local-motion regularization; disables its automatic noise scaling |
| `--tilelk 0\|1` | `1` | Per-tile coarse LK refinement |
| `--sublk N` | `128` | Subtile LK size; noise-dependent processing can choose the night setting |
| `--bsearch 0\|1` | `0` | Block-search motion initialization |
| `--mhc 0\|1` | `1` | MHC initialization; 0 selects bilinear initialization |
| `--paper 0\|1` | `0` | Disable production additions for a paper-equation ablation |

Automatic local regularization starts from 0.1, grows with the noise at
mid-grey relative to 0.0025, and is capped at 5. `--llam` selects a fixed
value. `--paper 1` has precedence over many robustness and finishing settings;
see [the method guide](METHOD.md#what---paper-1-means).

## Prior and image finishing

| Option | Default | Meaning |
| --- | --- | --- |
| `--prior 0\|1` | `0` | Enable the Vulkan sliding-DCT x-step |
| `--pgain X` | auto endpoints `0.35` | Fixed prior noise gain; disables automatic look interpolation |
| `--grain X` | auto endpoints `0` | Luminance detail addback; disables automatic look interpolation |
| `--auto-prior X` | `0.35` | Set both automatic prior-gain endpoints |
| `--auto-grain X` | `0` | Set both automatic grain endpoints |
| `--dluma X` | `1` | DCT luminance threshold multiplier |
| `--dchroma X` | `16` | DCT chrominance threshold multiplier |
| `--ddark X` | `8` | DCT dark-area boost cap |
| `--ddl X` | `0.02` | DCT dark transition level in normalized signal |
| `--ddy X` | `0.25` | Share of dark boost applied to luminance |
| `--mean N` | `8` | Prior mean-preservation radius in HR pixels; 0 disables |
| `--notch 0\|1` | `1` | Suppress Bayer-period patterning |
| `--sb X` | `0` | Daylight-shadow prior boost |
| `--sc X` | `0.7` before daylight weighting | Shadow chroma cleanup |

DCT controls affect the DCT path; setting `--pgain` does not itself enable a
prior. `--pgain` and `--grain` both turn off automatic look selection, which
also changes whether other noise-interpolated settings are selected. The raw
`SolveParams` initializers differ from app defaults in some cases; inspect
the printed `look:` line when comparing settings. An 8-iteration run has four
scheduled prior slots, but zero actual prior calls when no prior is enabled.

## Robustness, motion rejection and noise equalization

| Option | Default | Meaning |
| --- | --- | --- |
| `--robust 0\|1` | `1` | Robust data-term weighting |
| `--rk X` | auto `2` day to `3` night | Residual threshold in noise sigmas; explicit value sets both endpoints |
| `--rpow X` | auto `2` day to `1` night | Weight-curve steepness; explicit value sets both endpoints |
| `--rstart N` | `0` | Iteration at which robust weighting begins |
| `--tau X` | `0.1` | Edge/misregistration tolerance in LR pixels |
| `--spread N` | `3` | Spatial spread/min-filter radius for rejection; 0 disables |
| `--tk X` | `3` | Temporal test threshold; 0 disables temporal testing |
| `--ttau X` | `0.6` | Temporal edge tolerance |
| `--tdet X` | `4` | Temporal detail tolerance |
| `--tdw 0\|1` | `1` | Wider dark-area temporal tolerance |
| `--tdl X` | `0.02` | Dark-area transition level |
| `--eq 0\|1` | `1` | Adapt prior noise where fewer frames contribute |
| `--eqday X` | `0.25` | Daylight noise-equalization strength; night endpoint remains 1 |

Noise is fitted from the data rather than imported from DNG NoiseProfile.
There is no CLI switch to supply a camera noise LUT. Robust data weights and
temporal acceptance are distinct stages; disabling one does not disable the
other. Use `--paper 1` when you need the complete production-ablation preset.

## Experiments and diagnostics

| Option | Default | Meaning |
| --- | --- | --- |
| `--holdout N` | disabled | Keep selected frame N out of the merge and evaluate RAW residuals against it |
| `--profile 0\|1` | `0` | Print per-shader GPU timing details |
| `--repeat-reference 0\|1` | `0` | Replace selected frames with copies of the reference; diagnostic only |
| `--rgather 0\|1` | `0` | Alternate robust adjoint/gather path |
| `--quad 0\|1` | `0` | Experimental tap representation mode |
| `--pack 0\|1` | `0` | Experimental tap packing |

Holdout indices refer to the list **after** reference selection and frame
limiting. Keep at least one other frame for merging. Repeating the reference
does not provide independent observations and is not a valid denoising or
super-resolution quality benchmark.

## Files written

| File | Contents |
| --- | --- |
| `OUT.dng` | Uncompressed 16-bit linear camera RGB, clamped to [0,1] before export |
| `OUT.dng.f32` | Same clamped RGB values as planar float32, 3 × output height × output width |
| `OUT.dng.holdout.f32` | Optional three-plane held-out RAW-domain diagnostics on the evaluated source grid |

The DNG writer preserves reference colour matrix / neutral metadata when
present and copies orientation, make and model. It does not copy every EXIF
field. A `.f32` file has no embedded dimensions, colour space or byte-order
header; record output dimensions from the console. On the validated macOS
host these floats are little-endian.

The wrapper reports missing input directories and basic usage. The native CLI
can return status 1 for input/GPU/merge failures and status 2 for some argument
errors. Some original output-write paths have minimal error checking; use a
writable output directory and check that both files were created successfully.

See [validation](VALIDATION.md) for environment variables, traces and evaluation.
