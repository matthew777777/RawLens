# Learned noise model versus PhotonCamera (2026-11-04)

The persistent green-plane model now matches PhotonCamera's lower-half-window
averaging at calibrated ISOs in controlled tests and improves prediction between
calibrated ISOs. This is a model-level result, not a claim of better final photos
or parity with the complete PhotonCamera pipeline.

Reference: PhotonCamera dev commit `b2eff821a858033515eb2700e7292befecc95225`,
[`DynamicNoiseStore.java`](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/settings/DynamicNoiseStore.java)
and [`NoiseModeler.java`](https://github.com/eszdman/PhotonCamera/blob/b2eff821a858033515eb2700e7292befecc95225/app/src/main/java/com/particlesdevs/photoncamera/processing/render/NoiseModeler.java).
The store keeps ten measurements per whole-stop ISO bin and averages its lower
half by slope. Its static ISO law scales the read-noise floor with digital gain.
Our previous global fit lacked both protections.

Changes:

- Ten samples per third-stop ISO bin prevent one heavily sampled ISO from
  overwhelming the fit. Lower-half averaging suppresses positive texture leakage.
- Three independent samples at a nearby ISO can supply a local prior without
  waiting for a multi-ISO sweep. Global fitting still requires six samples,
  three ISO bins, and a twofold ISO span.
- Global read variance follows `C*ISO² + D*max(ISO/maxAnalogISO,1)²`.
  Camera2's analog limit now reaches the native fitter through frozen metadata.
- Global predictions are restricted to the calibrated ISO range and declined
  when retained calibration bins disagree by more than 50%. Local priors remain
  available at calibrated ISOs. Unknown analog limits retain the constant-floor
  approximation; no analog limit is invented.
- Per-shot evidence weight is capped at 0.25; coefficient disagreements outside
  0.5–2x leave the prior intact. Brightness-bin count alone is not strong evidence
  that a textured image is a cleaner measurement than the learned floor.
- Nonzero capture IDs prevent reprocessing one capture from training it again.
  This is capture identity, not visual scene recognition. Samples remain keyed
  by camera/raw mode and firmware. Marked multi-frame composites do not use or
  train the single-frame prior. Old `v1` stores restart as `v2`; malformed rows
  and nonfinite samples are ignored.
- RAWR and RawLens Wiener share this selection; MFSR's read-only predictor uses
  the same model and checks whether the queried ISO is supported.

Mean relative variance errors, using identical input measurements:

| Test | Previous RawLens | Revised RawLens | PhotonCamera store |
| --- | ---: | ---: | ---: |
| Calibrated ISOs, texture-contaminated samples | 15.18% | 1.85% | 1.85% |
| Held-out ISOs | 14.80% | 1.28% | 24.40% |
| Production frame estimates, then held-out ISOs | — | 1.02% | 24.56% |

The first two rows contain 396 controlled cases across three noise scales and
twelve random realizations. The last uses 36 synthetic frames and
the shipping C++ estimator. The comparator executes PhotonCamera's actual Java
`IsoBin` classes, rather than a Python reimplementation. PhotonCamera is given
the same estimates and uses its whole-stop bin result; RawLens interpolates.
The synthetic gain law matches the fitted family, so these figures do not prove
that interpolation wins for every real sensor or across gain-mode transitions.

The standalone frame estimator measures flat injected-noise patches within
1.1–2.3% mean variance error here, but textured scenes still have 19.5–20.2%
error. Lower-half history helps when cleaner scenes exist; it cannot recover an
unknown sensor noise floor from an entirely contaminated history. Real repeated
RAW captures with independently measured residuals remain necessary for claims
about sensor-wide accuracy and final photo quality. Per-channel and correlated
post-merge noise are outside this comparison.

Reproduction and scope: [validation harness](../../tools/noise-model-validation/README.md).
Machine-readable evidence: [model-comparison.json](results/noise-model-photon-2026-11-04/model-comparison.json).

Validation completed: the final isolated GitHub device-test build passed for both
native ABIs. Four device tests passed in 23.756 seconds: RawLens sample training,
RAWR training without a sensor profile, stored-prior influence and replay
suppression, and both algorithms plus fused/sequential sharpening consistency.
See [device-tests.txt](results/noise-model-photon-2026-11-04/device-tests.txt).
The host persistence/invariant checks and all 396 comparative cases passed.
