# LK-R Lite maze investigation — handoff, unresolved

## Actual objective and status

Remove the repeated maze/zigzag/zipper within foliage, rocks, car, lamps and
road **without losing the current look or fine detail**. The owner explicitly
rejected `_2x_2.dng` and `_2x_3.dng`; screenshots show the remaining artifact.
There is no accepted maze-free output. Do not call the goal complete.

Input: `/Users/monikamalinowska/Downloads/My/Bursts/IMG_20260919_184641_979`
(eight 4080x3060 CFA DNGs). Owner's outputs are beside that directory in `My/`.
GPU currently works with unrestricted session access: Apple M1/MoltenVK.
Earlier zero-Metal-device failures were session-access-specific and resolved.

The worktree contains unrelated ongoing RapidRAW/JPEG work. Do not reset it.
No release, commit, installation or version bump was made for a complete fix.

## Reproduce and inspect

```sh
cmake -S tools/lkr-lite/src/main/cpp -B tools/lkr-lite/build/host
cmake --build tools/lkr-lite/build/host --target lkrl_full_run lkrl_run lkrl_denoise_check -j 4
tools/lkr-lite/build/host/lkrl_denoise_check

LKRL_TRACE_PREFIX=/tmp/ridge tools/lkr-lite/build/host/lkrl_full_run \
  /Users/monikamalinowska/Downloads/My/Bursts/IMG_20260919_184641_979 \
  /tmp/ridge.dng --s 2 --prior 0 --crop 512 512 --at 1280 1536 \
  --noise 0.00000821 0.00000161 --workers 1
```

Car tile: `--at 1792 512`. Full-frame acceptance must cover all named surfaces,
not only a 128x128 RAW research subregion. DNG orientation is 6; raw trace
images need clockwise rotation for display. WB is [1.84179688,1,1.57226562].

Trace stages include original RAW, seed, rejection reference, initial/final
affine/local motion, weights, pre-J1 gradient/coverage/divisor, J1..J8,
aligned average and hybrid. Tracing is opt-in and adds GPU readbacks.
The host runner now supports `--audit-prefix PATH` and `--data-steps N`.
`LKRL_STEPS` controls the full runner's total emulated data-step budget;
changing schedule length alone changes the gain per update.

## Confirmed, limited findings

- The GPU forward/adjoint and first update were independently checked against
  captured arrays. Their arithmetic agreed within rounding/fixed-point limits.
  That verifies implementation of the model, not correctness of the model.
- The old chroma guide gated brightness changes as colour edges. A retained
  chromaticity-guide correction passes edge regressions but leaves the maze.
- The smoothed coverage divisor can make repeated noiseless frames diverge.
  `zupd.comp` now caps the effective step using own coverage. The actual GPU
  negative control fails (loss 0.2048 -> 2.681); corrected test passes
  (0.2048 -> 0.00433). Only 1,381/3,145,728 divisor entries change on the car
  tile: this separate bug does **not** explain the broad remaining maze.
- On the ridge, patterned texture strengthens during fitting before finishing.
  A manufactured-noise audit starting at exact truth produces structured
  image noise even with exact captured geometry and fixed rejection. It is
  not proof that noise is the sole cause of the actual visible pattern.
- Same-sensor-position sky residuals correlate across frames (~0.25 green,
  ~0.30 blue after highpass); a one-phase-pixel shift removes that peak.
  This suggests an unmodelled sensor-coordinate component. Its physical
  source and contribution to the whole artifact remain unproven.

## Avoid repeating these as purported fixes

These controls did not establish the requested result: rollback; average
luminance; unconditional chroma smoothing; seed-only pixel-mean correction;
different initialization including AMaZE; point-sample versus pixel-area
forward operators; disabling G refinement; green-only luminance; shared Y
with fixed/coarse colour fields; global or adaptive derivative penalties;
simple shared sensor-offset fitting; blindly fewer/more iterations.

Some penalties reduce noise while destroying weak fine texture. The research
detail-transfer tests now include low contrast and multiple orientations;
passing a high-contrast edge test alone is insufficient. Cross-region tests
have repeatedly rejected controls that looked favourable on the ridge.

SR-pipeline was run with matching physical crop/noise/reference. It reduces
fine patterned grain but is substantially softer. Its CLI crops after CFA
normalization: this burst needs its vertical flip accounted for. See the
retained matched-engine comparison; identical CLI coordinates are wrong.

## Latest experiments, not production changes

Artifacts/builders below are in `/tmp/lkrl-source-audit/`; inspect their actual
source/results before relying on them. Some scripts are exploratory and use
hard-coded trace paths. No background jobs from these experiments remain.

1. **No-added-noise initialization:** analytic texture was rendered through
   captured geometry and reconstructed with the actual GPU initializer.
   Invalid observations must be filled from an analytic continuation outside
   the crop, **not zero**, or they contaminate initialization/refinement.
   `noiseless-extended/` uses that continuation. Production fp16 rounding stays
   active. Initializer dependence remains after 128 fixed-gain updates.
   `noiseless-affine/` additionally zeros local offsets and still shows drift.

2. **Motion-reference bias:** on the known-motion noiseless scene, ordinary
   G refinement drifts median 0.212 / p95 1.078 sensor pixels. An oracle image
   lowers drift to 0.0098 / 0.0665; fitting eight data steps before G lowers
   it to 0.0200 / 0.0869. Simple blurred-motion and green-only controls fail.
   An exact pixel-mean-consistent motion reference improves but does not
   eliminate drift. Latent-first schedules still leave real ridge artifacts,
   including when the aligned average is refreshed after G. This is a useful
   synthetic defect, **not an accepted global schedule change**.
   Builders: `build-motion-guide.py`, `build-green-motion.py`,
   `build-reference-motion-control.py`. Reports: `noiseless-extended/`.

3. **Measurement-preserving colour projection:** alternating chroma support
   with approximate data equality leaves the real pattern. LSMR reaches its
   iteration limit; sensor changes are small but not identically zero.
   Review `nullspace-ridge/`, not just the theoretical nullspace claim.

4. **Residual-domain noise filtering:** half-overlapped same-CFA DCT residual
   shrinkage before adjoint fitting reduces noise substantially. Pooling
   patch energy and retaining DC passes selected weak-detail probes, but the
   wider frequency sweep fails others. Including rejection inside the
   transform is important. No residual filter was integrated. Scripts/results:
   `residual_noise_*.py`, `residual-weighted-{ridge,car}/`,
   `residual-spectralpool-ridge/`.

5. **Measurement model projection:** fit shared full-resolution brightness
   plus coarse colour fields, project RAW measurements through that model,
   then use the original reconstruction (rather than displaying the inverse
   model solution). This approximately retains achromatic probe transfer,
   with modest noise reduction, but LSMR hits 500 iterations and the real
   pattern remains. Reinitializing from projected measurements was also
   tested. Review `measurement_projection*.py`, `projection-ridge/`, and
   `projection-init-ridge/`; no acceptance or implementation decision exists.

## Evidence and next-pass discipline

Durable detailed record: `lkrl-update-stability-2026-11-16.md` and `results/`
in this directory. Additional earlier record: `lkrl-residual-maze-2026-11-16.md`.
Portable research scripts are under `tools/lkr-lite/research/` and have CLI
help. Core source: `lkrl_solver.cpp`, `lkrl_adapter.cpp`, `shaders/adj.comp`,
`zupd.comp`, `jac.comp`, `jacblk.comp`, `hyb.comp`.

The independent-RGB inverse problem is underdetermined at eight frames/2x,
but that observation alone neither diagnoses the whole artifact nor excuses
loss of detail. Do not turn another denoising preview or favourable probe
subset into a completion claim. A successful change needs current full-burst
before/after renders with the same display transform, preserved colour/look,
fine detail on all named surfaces, relevant synthetic failures fixed, and
Android runtime validation. None of that full acceptance evidence exists yet.
