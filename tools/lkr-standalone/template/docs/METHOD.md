# Method and implementation

## Reading the paper

The underlying publication is **Lucas-Kanade Reloaded: End-to-End
Super-Resolution from Raw Image Bursts**, Bruno Lecouat, Jean Ponce and Julien
Mairal, ICCV 2021. Read the [paper](https://arxiv.org/abs/2104.06191) alongside
the [authors' project page](https://bruno-31.github.io/lkburst2/index.html).
It treats image reconstruction and subpixel registration as a coupled inverse
problem, with a learned prior in the original method.

This guide describes the actual RawLens implementation. A connection to an
equation does not imply reproduction of the paper's trained model or scores.

## Why a burst helps

A Bayer frame measures one colour at each sensor site. Slight camera movement
changes which scene positions those sites observe. After alignment, the burst
therefore supplies both repeated noisy measurements and complementary samples
between reference pixels. Reconstructing directly from these samples avoids
treating independently demosaiced frames as ground truth.

More frames help only where their measurements agree with the reference scene.
Motion blur removes information; object movement, occlusion and changed
reflections can violate the shared-scene assumption. Robust weighting reduces
their influence but cannot recover detail that no usable frame measured.

## Forward model: predict the RAW measurement

For frame `k`, the model is:

$$y_k = U_{p_k}x + \epsilon_k, \qquad U_{p_k} = D B W_{p_k}.$$

Here `x` is a high-resolution RGB image in reference camera coordinates,
`W` maps its values to frame coordinates using six affine parameters, `B`
integrates over a low-resolution pixel, and `D` selects the spatial samples
and Bayer colour. The implementation uses box integration and bilinear
sampling. The model predicts a mosaic, rather than comparing displayed RGB.

`shaders/lkr_common.glsl` defines the sampling conventions. `resid*.comp`
forms the prediction and residual; `resid_adj*.comp` and `scatter*.comp`
apply its adjoint to bring residual information back to the RGB grid.
Pixel centres and affine direction are documented in the shared shader.

## Reconstruction with half-quadratic splitting

The conceptual objective balances data agreement with an image prior:

$$\frac12\sum_k\|y_k-U_{p_k}x\|^2+\lambda\phi(x).$$

Half-quadratic splitting introduces a data image `z` and a prior image `x`:

$$\frac12\sum_k\|y_k-U_{p_k}z\|^2+
\frac{\mu_t}{2}\|z-x\|^2+\lambda\phi(x).$$

The implementation alternates these operations:

1. **Image step.** Compute the RAW residual and its adjoint, then update `z`
   with a gradient step. Current app defaults use three image steps per outer
   iteration. `zstep*.comp` implements this update.
2. **Prior step.** Optionally apply the Vulkan sliding-DCT prior. It shrinks
   transform coefficients with separate luminance, chrominance and dark-area
   controls. `dct_*.comp` implements this path. The standalone CLI defaults to
   no prior; enable it with `--prior 1`.
3. **Motion step.** Refine alignment against the current image using image
   gradients, Jacobians and a small Gauss-Newton solve. `grad.comp`, `jac.comp`
   and `solve.comp` handle affine motion; `jacblk.comp` and `solveblk.comp`
   handle the optional local field.

`lkr_solver.cpp` records these operations and manages reusable GPU buffers.
The default schedule moves from `10 × estimated sigma` to `1 × sigma`.
The step scale is `1.8 / (L + mu)`, with `L` normally obtained from a Schur
bound. These are engineering choices in this port, rather than learned
parameters supplied by the paper's authors.

## From a full photograph to tiles

`lkr_full.cpp` normalizes RAW codes using per-CFA black levels and the white
level. It estimates noise from the input itself, initializes coarse alignment,
and distributes overlapping tiles to workers. Each worker owns a GPU context
and solver buffers. Compiled pipelines are cached across contexts, and completed
tiles enter a bounded queue before feather-blending in a fixed order. Reference
alignment pyramids and scratch storage are reused to reduce repeated work.

The current production defaults use 512-pixel tiles with 32-pixel overlap,
three workers, and eight outer iterations. `AppDefaults()` in `lkr_tuning.h`
overrides the solver structure's older `T = 16` default. Prior calls occur
on alternating iterations when a prior is enabled. A crop changes the region
used for processing and noise estimation; it is not just a crop of a previously
computed full-frame output.

Coarse initialization is a three-level, forward-additive Lucas-Kanade
alignment on a bilinear grayscale image. Per-tile and subtile refinements
help when a global affine transformation does not explain all displacement.
Block-search initialization is available but disabled by default.

## Noise and robustness

`NoiseSigma` and `NoiseAB` estimate a noise scale and a Poisson-Gaussian model
from burst content. The variance model is `a × signal + b`. DNG `NoiseProfile`
and Camera2 noise coefficients do not drive this pipeline's fit. Fine texture
in a small crop can bias the estimate; compare noise estimates as well as images.

The production path adds a robust data term, temporal acceptance, local motion,
noise equalization where fewer frames contribute, and reference preservation.
These controls let static detail receive multi-frame support while reducing
ghosting where observations disagree. Dark areas have separate tolerances,
and chroma cleanup is controlled separately from luminance.

`weight*.comp` contains the weighting stages. `lkr_colour_guard.h` and
`lkr_denoise.h` handle finishing. The output also has Bayer-pattern suppression
and clipped-highlight handling. The values actually used can depend on the
estimated noise; command-line overrides and `--paper` interact with these
choices. See the [CLI reference](CLI.md).

## Paper versus this port

| Component | ICCV method / equation | This implementation |
| --- | --- | --- |
| RAW observation operator | Affine warp, integration, Bayer sampling, Eq. 1–2 | Explicit Vulkan operator and adjoint |
| Image optimization | HQS and gradient update, Eq. 4–5 | Reusable GPU buffers, three image steps by default |
| Alignment refinement | Gauss-Newton / Lucas-Kanade, Eq. 7 | Affine refinement plus optional local motion |
| Prior | Learned ResUNet | No prior by CLI default; optional sliding DCT |
| Schedule | Learned parameters | Deterministic noise-dependent schedule |
| Initialization | Coarse alignment | Three-level grayscale pyramid, optional tile/subtile refinement |
| Production robustness | See the paper for its model | Explicit temporal rejection, robust weights and noise equalization |
| Finishing | Authors' results have their own processing | RawLens colour, shadow and Bayer-pattern controls |
| Output scale | Paper experiments include other scales | Desktop CLI supports 1× or 2× per dimension |
| Training / weights | Part of the original research method | No model training or downloaded weights required |

The authors' online results are examples of their method and processing, not
outputs produced by this repository. In particular, their larger enlargement
examples do not establish support for those factors in this executable.

## What `--paper 1` means

This flag switches off RawLens robustness/finishing additions, local motion,
MHC initialization and the alternate motion cadence, moving the configuration
toward the paper-equation ablation. It still uses this code's noise fit and
schedule, and does not install the authors' learned prior or weights.
`--paper 1 --prior 0` is useful for isolating the data/registration solver;
it is not an exact reproduction command for the published trained model.

## GPU arithmetic and determinism

The default adjoint scatter uses fixed-point int32 atomic addition. Integer
addition makes that accumulation independent of GPU invocation order within
its representable range. C++ normalization disables fast-math and FP contraction.
Alternative gather and packing modes are exposed for experiments.

Those properties do not guarantee that complete output files are bit-identical
across every GPU, compiler or floating-point implementation. The repository's
strong byte-identity guarantee concerns copied **source files**. Numerical
parity must be measured on the chosen backend and configuration.

## Code map

All paths below are under `tools/lkr-pipeline/` unless specified.

| Path | Responsibility |
| --- | --- |
| `host/lkr_full_run.cpp` | Desktop DNG loading, arguments, merge and export |
| `src/main/cpp/lkr_full.*` | Tiling, noise-aware controls, workers and blending |
| `src/main/cpp/lkr_solver.*` | Iterative image / prior / motion solver |
| `src/main/cpp/lkr_host.*` | Normalization, noise estimation and coarse LK |
| `src/main/cpp/lkr_gpu.*` | Vulkan dispatch, pipelines, descriptors and buffers |
| `src/main/cpp/lkr_mhc.*` | Coherent MHC initialization |
| `src/main/cpp/lkr_blocksearch.*` | Optional motion initialization |
| `src/main/cpp/lkr_tuning.h` | Current application defaults and tuning indices |
| `src/main/cpp/shaders/` | Embedded-at-build-time Vulkan compute shaders |
| `tools/sr-pipeline/src/main/cpp/srp_context.*` | Shared Vulkan device/context |
| `tools/sr-pipeline/src/main/cpp/srp_dng.*` | DNG metadata, pixels and RGB writer |
| `app/src/main/cpp/deps/tinydng/` | Vendored TinyDNG reader/writer and notices |

See [validation](VALIDATION.md) for checking intermediate stages and recording
reproducible results.
