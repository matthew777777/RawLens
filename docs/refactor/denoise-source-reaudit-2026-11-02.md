# Profiled denoise source re-audit — 2026-11-02

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

## Verdict

RawLens's vendored denoise core is byte-identical (including every line,
comments and whitespace) to both RAWR revisions checked. The complete RawLens
integration is not identical. The core is a modified darktable port, not an
exact darktable implementation. No processing code was changed in this audit.

References fetched from GitHub:

- RAWR previous pin: `537852ec0e109f78fb4ed54bc6a692d9c7cfca92`.
- [RAWR current HEAD](https://github.com/adityawarmanfw/rawr/tree/e7c844192008e1006d48ef85d931bf298e928192): `e7c844192008e1006d48ef85d931bf298e928192`.
- [darktable current HEAD](https://github.com/darktable-org/darktable/tree/e58544408dc9396dde06d564aadc64bcccf65327): `e58544408dc9396dde06d564aadc64bcccf65327`.

## Implementation follow-up — explicit RAWR behavior requested

The user subsequently requested the same RAWR integration. The Kotlin adapter
now matches `GreenNoiseToNormalized`: CFA green selection, cast-before-average
float arithmetic, range conversion, black-dependent offset, and acceptance
rules (including zero profiles, finite negative offsets and infinite white).
This deliberately reverses the prior Camera2 normalized-units correction.
The same mean green black level and effective white are used as before.

A second input difference was found and corrected: RAWR's live capture uses
`colorCorrectionGainsRggb` collapsed to RGB by averaging the two green gains,
without green normalization. RawLens denoise now uses `metadata.wbGains` in
that same way, instead of calibrated color-transform gains normalized to green.
Missing/invalid WB bypasses denoise, consistent with RAWR rejecting invalid
capture WB metadata. Sharpening and the final color transform retain their
existing inputs; they are separate processing stages.

The C++ core, shaders, tile size 1536, chroma default 0.75, strength/detail/luma
bounds and scale count remain aligned with the checked RAWR revision. Local
JNI/buffer transport and the surrounding development pipeline remain RawLens
code; complete photo-pipeline identity is not claimed.

Noise coefficients and WB affect chroma suppression and edge weights, so these
differences can change color spread at edges. No artifact was reproduced from
a user capture and no claim is made that this fixes a particular color leak.
Exact upstream behavior is the objective of this follow-up, not proof of
correct Camera2 units in upstream. See the regression suite for 10/12/14-bit,
all-CFA, unusual-profile acceptance, and absolute-WB-scale coverage.

Validation after the adapter changes: all 12 `WaveletDenoiseTest` tests pass
with zero failures; `:app:assembleGithubDebug` passes, including native arm64
and armeabi-v7a builds. `git diff --check` passes. The earlier fresh 10-case
GPU proof remains applicable to the unchanged core; it does not cover the
new metadata adapter or constitute an on-device capture comparison.

## Exact RAWR comparison

All seven files pass against both references: `DenoisePipeline.hpp`,
`DenoisePipeline.cpp`, `NOTICE.md`, and all four `denoise_img_*.comp` shaders.
Freshly compiled reference SPIR-V also matches all four packaged SPIR-V files.
The proof script aborts on any source or SPIR-V mismatch.

Fresh independently compiled RAWR/RawLens GPU runs pass all 10 cases with
identical complete RGBA bytes and finite outputs. Cases cover scales 1/6/7,
strength up to 8, Detail 0/0.5/1/1.8, luma off/default/full, tile sizes
256/1536, partial tiles, and 4208×3120 output. This establishes same-host-GPU
core equality given identical inputs and parameters, not full JPEG equality
or cross-device equality. The sandbox initially prevented Vulkan instance
creation; the successful GPU run used approved execution outside the sandbox.

The RawLens Kotlin/JNI/host adapters are local code. In particular,
`WaveletDenoiser.noiseAB` passes normalized Camera2 coefficients directly;
RAWR's `GreenNoiseToNormalized` helper performs a raw-DN conversion. The
helper itself is byte-identical in the vendored header but is not used by
RawLens's Camera2 adapter. This audit does not reverse that deliberate units
correction.

## darktable stage comparison

Line locations below refer to the pinned darktable revision above.

| Stage | darktable source | RawLens / RAWR comparison |
|---|---|---|
| WB inputs | `src/iop/denoiseprofile.c:1214`, `compute_wb_factors` | DT uses pixelpipe temperature/processed-maximum state and adaptive options. RawLens derives green-normalized gains from Camera2 neutral metadata. Equal effective WB must be supplied for a core comparison. |
| Y0U0V0 matrices | `denoiseprofile.c:1291` | Same normal-case matrix formulas and strength multiplier 2.5. DT has an inversion fallback; RAWR throws on a singular matrix. Arithmetic order/compiler behavior can differ. |
| VST | `denoiseprofile.c:1491`, `data/kernels/denoiseprofile.cl:81` | Same basic power transform and 0.05 fulcrum compensation, specialized to full-resolution scale 1. GLSL rearranges arithmetic; DT OpenCL also clamps the transformed value to nonnegative. |
| Wavelet taps | `denoiseprofile.cl:425` | Same 5×5 B3 spline taps, stride `1 << scale`, edge-aware RGB distance constants 0.02 and 9. |
| Edge weights | `denoiseprofile.cl:412`, `data/kernels/common.h:205`; CPU `src/common/eaw.c:277` | **Different:** DT calls bit-based `fast_mexp2f`; RAWR GLSL uses `exp2(-max(e,0))` and zeros `e > 20`. For e=1.5, DT's approximation gives 0.375, while true exp2 gives about 0.353553. This changes actual filter weights, not only rounding. |
| Storage | `denoiseprofile.c:2288` and device allocations | DT OpenCL requests four float channels (float32); RAWR tile images are `VK_FORMAT_R16G16B16A16_SFLOAT` with `rgba16f` shaders. Intermediate values round to half precision in RAWR. |
| Threshold formula | `denoiseprofile.c:1349`, `denoiseprofile.cl:480` | Same BayesShrink structure: sum(detail²)/(n−1), subtract band variance, floor at 1e−6, multiply by 8×force²×4. DT supports forces indexed by scale/channel; RAWR uses constant forceY/forceUv across scales. Reduction order differs. |
| Statistics region | `denoiseprofile.c:2465` versus RAWR `record` | DT uses the current processing ROI's pixel count; RAWR uses each tile's inner pixels, excluding its halo. Arbitrary tile configurations are not equivalent, even when neighborhood support is sufficient. |
| Scales | `denoiseprofile.c:1439` | DT derives visible scales from image dimensions and ROI scale. RAWR clamps an explicit requested count to 1–7. Small images/previews can therefore differ. |
| Reconstruction | `denoiseprofile.c:1561`, `denoiseprofile.cl:461` | Same soft-threshold concept and final residue. DT CPU accumulates forward; OpenCL reconstructs from coarse scales. RAWR accumulates forward through half-float images. |
| Inverse VST | `denoiseprofile.c:1164`, `denoiseprofile.cl:377` | RAWR fixes bias=0 and scale=1; DT supports bias×WB and scale correction. DT OpenCL clamps final RGB to >=0; RAWR does not. DT CPU also leaves the final subtraction unclamped, so distinguish CPU and OpenCL behavior. |
| Alpha | `denoiseprofile.cl:81,377` | DT OpenCL preserves alpha; RAWR writes alpha 1 at output. |
| Module coverage | `denoiseprofile.c` | RAWR implements the new-VST Y0U0V0 wavelet subset. DT additionally supports RGB wavelets, older transforms, non-local means and profile/curve controls. |

### Existing oracle limitation

`tools/wavelet-parity/denoiseprofile_oracle.py:154` explicitly copies RAWR's
exp2/tail-cutoff behavior. It is not an independent execution of darktable's
CPU/OpenCL implementation. Its frozen noise-mapping commentary also predates
the Camera2 units correction. Prior oracle tolerances establish agreement
with that port, not exact equality to darktable. No fresh darktable binary
output comparison was performed; source inspection already disproves exact
algorithmic and line identity.

## Evidence

`results/denoise-source-reaudit-2026-11-02/` contains both source identity
reports (hashes and line counts), the fresh 10-case GPU comparison, and hashes
of the four inspected darktable source files. No old GPU result was reused.

Reproduce the RAWR proof with:

```sh
PATH=/usr/bin:/bin:/opt/homebrew/bin python3 tools/wavelet-parity/prove_rawr_parity.py \
  --reference /tmp/rawlens-denoise-rawr-current --out /tmp/rawlens-denoise-reaudit-current
```
