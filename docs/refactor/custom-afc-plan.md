# Custom RawLens continuous autofocus: research and staged plan

Date: 2026-11-02. Status: design only; no AF behavior changes in this task.

## Broader review update

See [research comparison and revised decisions](custom-afc-research-review.md).
The architecture remains a candidate until measured against alternatives. Add an
early optional depth/DP capability audit; benchmark multiple focus metrics and
local search policies; add temporally correct moving-scene replay; distinguish
static HOLD and active TRACK. Delay-compensated prediction is a gated experiment
after reliable focus trajectories exist, not a dependency of the first release.

## Objective and scope

Build an optional contrast-based AF controller for Camera2 routes that demonstrably
accept manual lens movement. Optimize repeatable sharpness, recovery and low hunting;
do not promise to outperform working vendor PDAF at tracking approaching subjects.
No ML training or vendor PDAF dependency. Begin in photo RAW preview, then qualify
recording separately. HAL AF-C remains available as a user-selectable mode.

## Research findings

1. Raspberry Pi AF combines PDAF corrections with contrast scans. Without phase
   information, its continuous controller detects scene changes, waits for stability,
   then scans. It uses coarse/fine states, peak interpolation, settling and final
   contrast verification. Its IMX708-specific defaults are not phone calibration.
2. Camera2 manual focus requires AF OFF. Focus distance is in diopters only when
   calibration permits; uncalibrated values must not be advertised as real metres.
   Lens state and reported distance are evidence, not proof of optical sharpness.
3. RAW contrast scores alone do not reveal defocus direction. A controlled probe is
   normally necessary. Motion, exposure, noise, texture changes and occlusion can
   all change the score without a change in focus.
4. Camera ITS provides a useful model for testing commanded lens movement against
   captured image sharpness. Advertised support alone is insufficient qualification.

Sources:
- Pinned AF implementation studied locally:
  https://github.com/raspberrypi/libcamera/blob/c682979b50695b93526adb1e66db33743484b63b/src/ipa/rpi/controller/rpi/af.cpp
- Raspberry Pi tuning guide:
  https://datasheets.raspberrypi.com/camera/raspberry-pi-camera-guide.pdf
- Manual focus contract:
  https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#LENS_FOCUS_DISTANCE
- Calibration contract:
  https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION
- Result metadata:
  https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LENS_STATE
- Functional camera tests:
  https://source.android.com/docs/compatibility/cts/camera-its-tests

## Existing code to reuse

- PhotoAutoFocus.kt: existing HAL tap AF lifecycle and cancellation. Keep it as HAL
  implementation; custom AF does not simulate HAL AF states.
- RawFocusPeakingSampler.kt and FocusPeakingDetail.kt: Bayer access, clipping and
  noise rejection. Peaking marks are not an AF score and must not be counted as one.
- RawCameraController.kt: request submission, manual lens commands, physical routes,
  capture lifecycle and exact timestamp metadata infrastructure.
- FaceTracking.kt: optional subject region input. Separate subject selection from
  lens control, and preserve explicit taps as the highest-priority target.

## Phase 1 initial device evidence

The initial probe is implemented and passed on camera 0 of model 25080RABDG.
See [trace, measurements and limitations](results/manual-focus-latency-2026-11-02/README.md).
Manual optical response is supported by this run. Fine step resolution, minimum
settling policy and other routes remain unqualified; phase 1 is not fully complete.

## Phase 1 — measure lens behavior before designing timing

Add a developer/device-test probe with AF OFF, bounded focus commands, request/run
identities, submitted/applied frame numbers, RAW timestamps, physical route, reported
focus distance and lens state. Sweep in both directions against a static textured
near/far setup; record image evidence as well as metadata. Measure request latency,
settling distribution, useful step resolution, range endpoints and direction effects.
Use a normalized bounded actuator coordinate internally for uncalibrated lenses.
Do not silently run intrusive full-range calibration during normal app use.

Gate: repeatable optical changes from manual commands, no stalled buffers, bounded
cancellation, and a measured conservative settling policy per tested route/mode.
When lens state is absent or unreliable use measured frame delays with an explicit
qualification status; never require observing MOVING before accepting STATIONARY.

## Phase 2 — implement and validate a RAW focus score

Add RawFocusMetric with small ROI crops, pooled-green sampling, black/white and noise
metadata from the exact physical RAW frame. Evaluate noise-subtracted multiscale
squared gradients (Tenengrad-style) against a Laplacian-based alternative on real
focus stacks; select from evidence rather than assuming a universally best metric.
Use multiple spatial tiles, robust aggregation, texture support and clipping masks.
Return score, uncertainty, usable area and exposure/motion validity, not just a scalar.
Test moderate exposure changes; normalization must not amplify noise or normalize
away the sharpness signal. Associate every sample with timestamp, lens command epoch,
ROI identity and actual reported lens setting when available.

Copy only the required ROI before releasing RAW ownership where practical. At most
one score job is in flight; drop excess frames rather than create a growing queue.
Measure ROI access cost on real camera buffers before deciding CPU versus GPU.

Gate: focus-stack peak agrees with independent chart/detail assessment; dark noise,
clipped edges and smooth walls do not create confident focus success. Simulated
blur tests supplement, rather than replace, real optical sweeps.

## Phase 3 — single acquisition and verification

Add a pure deterministic ContrastAfController and a Camera2 adapter. Start locally
around the current reported position, probe both directions when needed, bracket a
peak, refine with smaller steps, return to the measured best position, then verify.
Use bounded interpolation only for a valid, supported peak; measured samples win
when fitting is unstable. Expand to a bounded coarse search only after local search
fails. Do not assume a unimodal contrast curve across layered scenes.

Each observation must follow the applied focus command and settling policy. Bound
travel, samples and elapsed time. Insufficient evidence produces uncertain/failure,
not FOCUSED. Retain the best valid position, including boundary peaks when verified.
Tap changes ROI and restarts with a new epoch. Manual focus cancels immediately.

Gate: near/far targets acquired from both starting directions; flat/noisy scenes,
timeouts and reordered metadata handled; switching sessions cannot move an old lens.

## Phase 4 — continuous hold and local reacquisition

States: DISABLED, ACQUIRE, SETTLE, VERIFY, HOLD, LOCAL_SEARCH, REACQUIRE, SUSPENDED.
In HOLD, monitor stable subject-region statistics without perpetual lens dithering.
Require sustained score loss relative to a recent trustworthy baseline. Establish a
new baseline after ROI, crop, exposure or scene changes; old scores are not globally
comparable. Gate decisions using gyro/image motion and a lightweight appearance
check. Sharpness loss alone cannot distinguish motion blur from defocus.

When stable and likely defocused, run small directional probes and local refinement.
Rate-limit reversals and wider scans. Low confidence holds the last good position;
occlusion waits for subject recovery instead of chasing the background. A tracked
subject moving across the image invalidates spatial comparisons unless aligned.
Prediction of lens velocity is a later experiment after trustworthy trajectories
exist; do not extrapolate from noisy contrast measurements initially.

Gate: lower hunting on static targets than repeated full scans; repeatable recovery
after depth changes; no self-sustaining oscillation or scans during camera shake.

## Phase 5 — integration and ownership

Introduce explicit focus ownership: HAL, CUSTOM or MANUAL. Custom owns AF OFF and
focus distance; never reuse selectedFocusDistanceDiopters as though the user selected
manual focus. HAL tap triggers and face-region request changes must not steal ownership.
Submit lightweight repeating updates without draining RAW/ZSL or recreating sessions.
Separate AF target changes from AE changes so the loop does not destabilize exposure.

Freeze lens commands across still/burst exposures and reset score history on resume.
Define shutter behavior during scanning: capture the present position or wait for a
bounded acquisition according to explicit product policy; never wait indefinitely.
Cancel on stop, route change, manual focus, disabled mode and fatal stream errors.
Display custom scanning/holding/uncertain states independently of HAL AF metadata.

Gate: existing manual/HAL AF, capture, ZSL and lifecycle tests remain passing; no
reader starvation, leaked images, command-after-cancel or session restart per step.

## Phase 6 — device acceptance and per-lens tuning

Compare HAL continuous, HAL AUTO tap, custom single acquisition and custom continuous
on identical scenes. Include near/far chart transitions, textured/slanted targets,
low light, backlight, blank walls, multiple depths, lateral movement, approaching
subjects, brief occlusion, pan/shake and focal-length/route changes. Run cold/warm.

Measure acquisition/recovery p50/p95, sharp-frame proportion, final sharpness versus
an independently captured dense focus sweep, static-scene lens travel/reversals,
false success, CPU time, memory, preview cadence and recording dropped frames.
Use at least 20 repetitions per controlled transition/direction as an initial test
protocol, with success thresholds fixed before evaluating the final candidate.
Do not publish an absolute millisecond claim until lens/pipeline latency is measured.

Recording is enabled only after separate acceptance for supported resolution/frame
rates and capture paths. Save tuning keyed by physical route and relevant sensor
mode; invalidate it when configuration changes invalidate the measurements.

## Initial deliverable order

1. Lens movement probe, optional depth/DP capability audit and trace analyzer.
2. Competing focus metrics and local search policies with held-out focus-stack evaluation.
3. Single acquisition controller, timestamp/delay replay and temporally advancing scene tests.
4. Continuous HOLD/local reacquisition with replay and device tests.
5. Product mode selector, capture/recording qualification and per-lens tuning.

ML face selection can be added later without redesigning this lens controller.
