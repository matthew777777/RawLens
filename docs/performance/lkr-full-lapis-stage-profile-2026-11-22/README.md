# Full LK-R stage profile on Lapis

The existing full LK-R Android standalone executable at `/data/local/tmp/lkrl/lkr_full_run` processed the eight-frame car burst from `/data/local/tmp/lkrl/car`. The test used 4080×3060 input and output, 1×, 63 tiles, three Vulkan workers on Mali-G615 MC2, and the executable's default prior setting (none). The profiled invocation was:

```sh
cd /data/local/tmp/lkrl
LD_LIBRARY_PATH="$PWD" ./lkr_full_run car /data/local/tmp/rawlens-pc-lkr-stage-profile/car-pc-1x.dng --frames 8 --s 1 --workers 3 --profile 1
```

## Profiled run

| Timing | Time |
|---|---:|
| DNG input load, outside merge timer | 0.657 s |
| Coarse LK including noise fit | 1.317 s |
| Noise fit, included above | 0.368 s |
| Tiled merge | 40.665 s |
| Native merge total | **41.982 s** |

With shader queries disabled, a second run took 45.159 s for the merge and 46.31 s for the complete CLI command. The timings varied across the two runs, and the unprofiled control showed that shader query overhead did not account for the roughly 42 s runtime. Both runs made zero prior calls. Vulkan profiling reported 9,576 dispatches and 315 submissions. Grouping the shader timers gives the following breakdown. These are accumulated shader times across overlapping worker intervals, not additive wall times.

| Shader group | Sum | Share of reported shader times |
|---|---:|---:|
| RAW prediction, residual, adjoint, scatter and image update | 43.206 s | 46.7% |
| Robust weighting | 37.641 s | 40.7% |
| Affine and local motion refinement | 11.712 s | 12.7% |

The largest single shader was `resid_adj_w` at 21.661 s accumulated. The earlier current-Lite result on the same burst was 13.672 s median. The synthetic NPU cost of three full-image width-4 prior passes was 2.529 s before cross-stage handoffs. The full solver's time is predominantly in operations that moving a prior to the NPU would leave in place. A learned prior could improve speed only if it enables a quality-validated reduction in reconstruction or weighting work, or if the production solver is independently made faster.

The separate Mac runs took 9.149 s at 1× and 21.869 s at 2×, both with the prior off. The 1× DCT control took 10.204 s. Those figures are for the built Mac standalone on Apple M1 and are not phone timings.
