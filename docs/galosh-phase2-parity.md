# Phase 2 — o32 pipeline port + 69 dB parity gate (Mali-G615)

Status: **PASS** (2026-09-26, Xiaomi 25080RABDG, Mali-G615 MC2, Vulkan 1.3.247).

## What was built

Full RAW o32 dispatch graph in `app/src/main/cpp/galosh/galosh_run.cpp`
(44 counted dispatches + banded Phase 5), FP16-storage/FP32-compute,
blind noise fit per frame, wht=8, classic (non-subgroup) kernels —
Mali's fixed subgroup-16 cannot pin 32. Entry: `GaloshVulkan.denoise()`;
parity vehicles: `GaloshParityTest.denoiseFixtureToDownloads` (K16 path)
and `denoiseFixtureFastup` (guided-bilinear path).

## Gate results (512x384 mosaic fixtures)

| config | ref | PSNR | gate |
|---|---|---|---|
| seed-2, K16 | CPU FP32 | **72.96 dB** | >= 69 PASS |
| seed-1, K16 | CPU FP32 | **74.65 dB** | >= 69 PASS |
| seed-2, fastup | CPU fastup | **94.59 dB** | info |

Per-phase vs CPU FP32 (`tools/galosh_parity.py phases`; f16 rows use the
`--f16-storage` oracle): p2 97.2, p4_C* 102.2/103.0/102.0, p10 73.0.
Same-input kernel fidelity (GPU-dump injection into CPU): pass1 68.9,
pass2 66.0, LOESS 86.7, pyramid q/e 92.8/94.2, blend 70.7/73.7/87.6,
LUT d/x 83.8/149.9. gpuMs ~661 (split P5) on the fixture.

## Mali deviations from upstream

1. **Split Phase 5** (classic wht8 full-phase path only; wht4/SG/stride!=1
   keep fused). The fused `o32_pass12` mis-executes its pass2 region on
   Mali (27.9 dB vs oracle with bit-identical inputs; fused pass1 verified
   bit-identical to `o32_pass1_dump`). Production runs
   `o32_pass1_dump -> pilot_p5 -> o32_pass2_only` (new kernel, same math),
   both halves banded. Split pass2 verifies at 66 dB same-input.
2. **`o32_build_inv_lut` multiplicative recurrence.** The Kahan log-domain
   Poisson sum accumulated Mali libm error over ~6000 terms (table rms
   0.144, non-monotone). Rewrote to a forward
   P(k+1)=P(k)*lambda/(k+1) recurrence from k0=lambda-12*sqrt(lambda)
   with arbitrary scale + end normalization and 1e10 magnitude guard
   rescales; zero log/exp in the loop. Table now 83.8 dB vs CPU.
3. **`pilot_dbg` allocation** was 4 bytes for a W*H f32 plane (Upstream
   transcription slip); sized to `full_f`, renamed `pilot_p5`.

## Shader toolchain

Checked-in `.spv` are upstream glslc -O builds EXCEPT `o32_pass2_only.spv`
(new) and `o32_build_inv_lut.spv` (rewritten), built with:

```
glslangValidator -V -I. <k>.comp -o <k>.spv && spirv-opt -O <k>.spv -o <k>.spv
```

`o32_pass12.spv` is pristine upstream (md5 3cc3a326fe855108da2b014163f81adb);
it only serves the wht4/SG/subsampled configs now.

## Reference provenance

Upstream `https://github.com/luxgrain/GALOSH` @ `11de059`
(`standalone/galosh_raw_cpu.c`, `galosh_cpu.h` — dump harness
`O32_CPU_DUMP`, oracle `GALOSH_F16_STAGE`, `--f16-storage` are upstream's).
Local instrumentation (dump-only + env-gated, zero numeric effect):
Phase-1 `zz_*` dumps, `GALOSH_INJECT_LCS` / `GALOSH_INJECT_P8DIR`,
`GALOSH_F16_NO_PILOT`, p7/p8 C2/C3 + `GALOSH_DUMP_GAT` dumps, x86-SSE
include guards (ARM macOS build). Build:

```
cc -O2 -Xpreprocessor -fopenmp -I$(brew --prefix libomp)/include \
   -o galosh_raw_cpu galosh_raw_cpu.c \
   -L$(brew --prefix libomp)/lib -lomp -lm
```

Reference: `galosh_raw_cpu in.bin out.bin W H galosh <s> <l> <c> 0 0`
(+ `--f16-storage` for the oracle; `GALOSH_DUMP_DIR` for phase dumps).

## Repro

```
python3 tools/galosh_parity.py gen --out /tmp/in.bin --sidecar /tmp/in.txt --seed 2
python3 tools/galosh_parity.py ref --exe /tmp/galosh_cpu/galosh_raw_cpu \
    --in /tmp/in.bin --out /tmp/ref.bin
adb push /tmp/in.bin /data/local/tmp/galosh_parity_in.bin
adb push /tmp/in.txt /data/local/tmp/galosh_parity_in.txt
./gradlew :app:connectedReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.matthew.rawlens.GaloshParityTest
adb pull /sdcard/Download/RawLens/galosh_parity_out.bin /tmp/got.bin  # (pick newest)
python3 tools/galosh_parity.py psnr --ref /tmp/ref.bin --got /tmp/got.bin --gate 69
```
