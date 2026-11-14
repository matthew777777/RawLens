# Photo profiled wavelet: RAWR parity audit (2026-11-01)

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

## Follow-up: exact RAWR integration requested (2026-11-02)

The Camera2 adapter correction described below is now superseded by an explicit
request for RAWR behavior. Noise coefficients again use RAWR GreenNoiseToNormalized
(including raw-range conversion and acceptance rules). Denoise WB now uses
COLOR_CORRECTION_GAINS with both greens averaged and absolute scale preserved.
See `denoise-source-reaudit-2026-11-02.md` for the implementation follow-up.


Reference: https://github.com/adityawarmanfw/rawr at
`537852ec0e109f78fb4ed54bc6a692d9c7cfca92`.
Scope: existing photo denoiser, as confirmed by the user; video is excluded.

## Camera2 integration correction (2026-11-01)

The initial audit below missed a units mismatch. Its core source/GPU evidence
remains valid for identical normalized coefficients, but did not prove the
Camera2 metadata adapter was correct. Commit 0162125 accepted eight-value
profiles and applied a raw-DN conversion to already-normalized coefficients.
Previously, eight-value profiles took the six-value-only adapter's fixed fallback.
This change could therefore reduce denoising substantially while still recording
successful GPU execution.

[SENSOR_NOISE_PROFILE](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_NOISE_PROFILE)
models noise for signal x in [0,1]. The corrected adapter averages the two
CFA green S/O pairs and passes them directly to noiseA/noiseB. Black/white
levels are validated but no longer rescale the coefficients. Invalid, negative,
non-finite or all-zero green coefficients bypass denoise. No fixed noise model
is fabricated. Native sources and shaders remain unchanged.

Regression coverage uses a known normalized mid-gray variance across 10-, 12-
and 14-bit normalization ranges, all four CFA layouts, invalid coefficients,
and non-finite levels. Logs now include Detail, Luma, Chroma and noiseA/noiseB,
and an explicit warning when enabled wavelet denoise bypasses an invalid profile.

## Initial parity audit (superseded metadata claims)

The noise-offset and normalization-black rows below describe the initial raw-DN
mapping, not the corrected Camera2 adapter above.

## Corrections

| Item | Before | RAWR / corrected RawLens |
|---|---|---|
| Strength UI and preferences | 0–2 | 0–8, slider step 0.1 |
| Detail | Hidden; clamped 0–1; gated luma | Exposed, persisted 0–1.8, step 0.05; passed as `DenoiseParams.shadows` |
| Luma | Disabled whenever Detail < 1 | Independent `forceY`, 0–1, step 0.05 |
| Scales | Slider included a duplicate zero position | 1–7, step 1 |
| Chroma | 0.75 | 0.75 (fixed in both UIs) |
| Noise profile | Expected six values, green RGB pair | Eight row-major CFA-site values; averages both greens for each CFA |
| Noise offset | O / range² | (S × black + O) / range², float operation order matching C++ |
| Normalization black | Mean of all CFA sites | Mean of the two green black levels |
| Invalid profile | Invented fallback noise constants | Bypass denoise |
| Native/shader source | Local optimization and timing changes | Byte-identical RAWR header, implementation, four shaders, notice |

Defaults remain off, strength/detail 1, luma 0.25, scales 7, chroma 0.75.
Detail is the VST shadow fulcrum; RAWR exposes no additional Shadows control.
Settings survive disable/re-enable and are read by both the shutter and merge
settings readers. All preferences, including Detail, participate in the
existing complete preferences backup.

## Proof

Saved evidence under `results/wavelet-rawr-parity-2026-11-01/`:

- `source-identity.json`: all seven files byte-identical, with SHA-256 hashes.
- `gpu-bit-parity.json`: 10/10 independent RAWR/RawLens builds produce
  identical complete RGBA output bytes, with matching hashes and finite output.
- `oracle-parity.json`: separate Python-reference tolerance/seam gate passes:
  max 0.001697, p99 0.000257, mean 0.0000498, seam ratio 1.09.

GPU cases cover scales 1/6/7, strength 1/2/8, Detail 0/0.5/1/1.8, luma
0/0.25/0.4/1, tile sizes 256/1536, 521×519 and 1544×1032 partial tiles, and
4208×3120 full resolution. Reference and candidate shaders were separately
compiled and their SPIR-V bytes compared. Processing sources are compiled
independently using the same host upload/readback harness and compiler flags.
The harness only adapts includes and instance-extension detection for direct
MoltenVK linking; it does not alter denoise arithmetic.

Reproduce with NumPy, MoltenVK and glslangValidator installed:

```sh
PATH=/usr/bin:/bin:/opt/homebrew/bin python3 tools/wavelet-parity/prove_rawr_parity.py \
  --reference /path/to/rawr --out /tmp/rawlens-wavelet-proof
PATH=/usr/bin:/bin:/opt/homebrew/bin tools/wavelet-parity/run_parity.sh
./gradlew :app:testGithubDebugUnitTest --tests com.matthew.rawlens.WaveletDenoiseTest
./gradlew :app:assembleGithubDebug
```

The targeted Kotlin suite passes 10 tests, covering CFA green selection,
black-level normalization, invalid-profile bypass, independent luma/detail,
bounds and stage resolution. The GitHub debug APK build also passed, including native arm64-v8a and
armeabi-v7a compilation.

## Limits

Exact source and same-GPU output parity apply to the denoiser given identical
camera-linear input, WB, noise coefficients and settings. The Python oracle
uses a separate float implementation and is checked with upstream tolerances.
Cross-vendor GPU bit identity is not established. Full JPEG equality is not
claimed: RawLens has its own demosaic, highlights, colour/output stages and
buffer transport. No on-device photo capture comparison was run in this audit.

The exact upstream restoration removes RawLens's atrous apply shortcut and
smaller partial-buffer allocation; exact source parity takes precedence over
those local performance optimizations in this change.
