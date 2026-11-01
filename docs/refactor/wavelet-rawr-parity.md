# Photo profiled wavelet: RAWR parity audit (2026-11-01)

Reference: https://github.com/adityawarmanfw/rawr at
`537852ec0e109f78fb4ed54bc6a692d9c7cfca92`.
Scope: existing photo denoiser, as confirmed by the user; video is excluded.

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
