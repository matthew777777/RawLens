# Learned noise-model validation

Run from the repository root (C++17 Clang, Python 3 and JDK; no Python packages):

```sh
python3 tools/noise-model-validation/validate.py --out /tmp/rawlens-noise-validation
```

Optionally pass `--legacy-header /path/to/previous/noise_model.h` to compare the
previous RawLens fitter. The report records the current header and reference
Java SHA-256 values. Results go to `report.json` in the output directory.

`model_probe.cpp` compiles the shipping noise estimator/fitter directly.
`PhotonIsoBin.java` contains the unchanged `NoiseEstimate` and `IsoBin` classes
from PhotonCamera's GPL source at commit
[`b2eff821a858033515eb2700e7292befecc95225`](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/settings/DynamicNoiseStore.java).
Only the enclosing class and stdin driver are adapters. The Java reference
executes independently; the harness assigns the containing whole-stop ISO bin
using PhotonCamera's `round(log2(ISO/minISO))` rule.

This isolates persistent model behavior using identical normalized green-plane
measurements. It does **not** compare PhotonCamera's GPU estimator, ESD4D,
stacking, per-channel profiles, sensor-specific presets, or final image quality.

The 396 cases cover three noise scales, twelve seeded realizations, six
calibrated ISOs (100–3200), five held-out ISOs, 5% multiplicative measurement
variation, and 40% of measurements contaminated by 2–6x texture leakage.
Error is relative variance error at seven log-spaced brightness levels,
including deep shadows. Calibration cases require parity within one percentage
point of mean error; held-out cases must improve on the Photon store.

Additional checks exercise the analog/digital gain law, same-ISO warmup,
duplicate capture IDs, invalid inputs, bounded shot evidence, unsupported
extrapolation, persistence, old-schema rejection, malformed-row recovery,
and ten-sample windows. The frame-estimator diagnostics generate known Gaussian
Poisson-Gaussian variances in RGBA16F at all four CFA origins. Textured-frame
errors are reported without a passing-accuracy claim. A final comparison feeds
36 actual production estimates into both fitters and queries unseen ISOs.

Device checks use `-PwaveletCheck` and the isolated
`com.matthew.rawlens.waveletcheck` package. Target
`WaveletDenoiseDeviceTest` for both algorithms, stored-prior influence, sample
accumulation, and replay suppression.
