# Broader autofocus review and RawLens design decision

Reviewed 2026-11-02. Research/design only. This is a targeted public-source review,
not a claim to cover every paper or proprietary implementation. No device speed,
accuracy or model-transfer claim has been established. Search included 2025/2026
work; recency does not override sensor/input compatibility.

## Evidence and feasibility matrix

| Candidate/source | Evidence inspected | Inputs/deployment limits | RawLens decision |
| --- | --- | --- | --- |
| Raspberry Pi AF | Local source at c682979; coarse/fine scan, constrained parabolic interpolation, reversal, settling, phase-confidence control, scene-change retrigger | ISP contrast stats and sensor-specific PDAF; IMX708-oriented defaults | Best starting reference for controller structure; adapt CDAF behavior, measure our own timing |
| Upstream libcamera IPU3 AF | Local source at df8e348; variance comparison, coarse/fine hill climb, fixed 1023 range and 10 ignored frames | Hardware AF statistics and actuator-specific defaults | Useful baseline to beat; do not copy step sizes, frame delays or variance thresholds |
| Proposed common libcamera hill climb | Public v6 patch/review, March/April 2023 | Platform-supplied contrast and lens adapter | Historical proposal, not proof of a current merged universal controller |
| Focus-measure operator analysis, Pertuz et al. | Author project and reference implementations; gradient/Laplacian/wavelet/statistics families | Noise, texture, saturation and support size affect performance | Benchmark several metrics on actual phone RAW; no universal winner selected |
| Revisiting Autofocus for Smartphone Cameras, ECCV 2018 | Paper and temporal focal-stack evaluation description | Dynamic scenes and lens-motion constraints matter | Adopt temporal replay and measure lens movement as well as sharpness |
| Learning to Autofocus, CVPR 2020 | Author project, dataset and code availability statement | Conventional and DP experiments; modified MobileNetV2/ordinal regression; project directs code questions to author | Not a verified deployable Android model; use as research evidence, not a ready-made dependency |
| Positional Characteristics of DP Data, ICCV 2023 | Official paper abstract | Requires DP observations; ROI/lens position encode optical effects | Ineligible without DP access; preserve ROI and lens metadata in future interfaces |
| Stable Autofocus with Focal Consistency Loss, WACV 2025 | Indexed official abstract/excerpts and author publication page; direct full text returned 403 | Training method addressing inconsistent predictions/hunting; no deployable checkpoint verified in this review | Stability is an explicit test objective; do not claim to implement its loss without training |
| Spatially-Varying Autofocus, ICCV 2025 | Official repository README | Custom DP camera/prototype and specialized optics; repo explicitly documents acquisition constraints | Available source does not make it compatible with a stock phone RAW stream |
| One-Step Event-Driven High-Speed AF, 2025 | Full arXiv HTML | Event polarity/timing plus grayscale or event-only apparatus | Ineligible: standard Camera2 RAW is not an event stream; frame differencing is not an equivalent input |
| Android depth/stereo-assisted AF | Official DEPTH_OUTPUT and logical multi-camera docs | Optional depth, calibration, synchronization, stream combinations and latency; calibrated mapping to lens required | Optional future initializer only after capability and latency probe; not base architecture |

## Primary sources

1. Raspberry Pi implementation:
   https://github.com/raspberrypi/libcamera/blob/c682979b50695b93526adb1e66db33743484b63b/src/ipa/rpi/controller/rpi/af.cpp
2. Upstream IPU3 source:
   https://gitlab.freedesktop.org/camera/libcamera/-/blob/v0.8.0/src/ipa/ipu3/algorithms/af.cpp
3. Common AF proposal/review:
   https://lists.libcamera.org/pipermail/libcamera-devel/2023-April/037412.html
4. Author focus-measure comparison/resources:
   https://sites.google.com/view/cvia/focus-measure
5. Temporal smartphone autofocus:
   https://openaccess.thecvf.com/content_ECCV_2018/html/Abdullah_Abuolaim_Revisiting_Autofocus_for_ECCV_2018_paper.html
6. Learned AF author project:
   https://learntoautofocus-google.github.io/
7. Position-aware DP autofocus:
   https://openaccess.thecvf.com/content/ICCV2023/html/Choi_Exploring_Positional_Characteristics_of_Dual-Pixel_Data_for_Camera_Autofocus_ICCV_2023_paper.html
8. Focal consistency:
   https://openaccess.thecvf.com/content/WACV2025/papers/Lee_Stable_Autofocus_with_Focal_Consistency_Loss_WACV_2025_paper.pdf
   https://myungsub.github.io/publication/lee-2025-stable/
9. SVAF source:
   https://github.com/Image-Science-Lab-cmu/SVAF
10. Event-driven AF full paper:
    https://arxiv.org/html/2503.01214v1
11. Depth capability contract:
    https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT
12. Multi-camera contract:
    https://source.android.com/docs/core/camera/multi-camera

## Revised recommendation (engineering inference)

Use a modular, delay-aware adaptive CDAF controller with explicit uncertainty,
subject continuity and a latency-versus-hunting objective. This is the strongest
feasible candidate from the sources reviewed for the currently available inputs,
not a proven best algorithm. Keep alternative metrics and search policies swappable.

### Decisions changed or made more explicit

- Select the focus metric experimentally: compare noise-corrected multiscale gradient
  energy, modified Laplacian and a cheap Brenner baseline. Include saturation/noise
  gates and report peak-location error and false peaks, not only runtime.
- Compare local adaptive hill climb with safeguarded parabolic refinement. Test a
  bracketed golden-section refinement baseline on static ROIs. Do not apply global
  unimodal optimization to layered/moving scenes or assume every focus curve has
  one peak. These are proposed experiment candidates, not benchmarked winners.
- Make command-to-image delay a first-class input to the controller. A faster
  CPU metric cannot remove sensor queuing or lens settling; optimize valid samples
  and request timing, not an arbitrary number of iterations per second.
- Preserve small tiles belonging to the selected subject across frames, with cheap
  translation/scale alignment where feasible. Reject comparisons after substantial
  appearance/ROI changes. Focus breathing itself must not be mistaken for subject motion.
- Use confidence from repeated/agreeing tile observations to choose extra samples,
  step size, settling margin and whether to hold. Keep these heuristics bounded
  and traceable; introduce no unvalidated statistical probability claims.
- Separate static HOLD from active TRACK. HOLD prioritizes low lens motion. TRACK
  permits more frequent local correction when valid evidence indicates depth motion.
  Offer no predictive guarantee from a 2D subject tracker alone.
- Experiment with a bounded constant-velocity/alpha-beta predictor only after
  collecting repeated verified focus-position estimates. Predict to the measured
  command-effect time, cap travel, and disable on reversals/low confidence. This is
  an engineering hypothesis; no public result reviewed proves it improves this phone.
- Probe optional depth/DP availability early without assuming access. Require
  image-space registration, timestamps, confidence and a calibrated lens mapping
  before allowing any depth estimate to seed a lens move. Verify with contrast.
- Qualify low-latency analysis sources: RAW ROI is the quality baseline. Existing
  YUV/preview can be a tracking aid only if available without destabilizing stream
  combinations; sharpening/denoising can bias its focus score. Do not add a new
  camera output without measuring RAW cadence and pipeline delay.
- Require dynamic evaluation: frozen optical stacks test scoring, not AF-C success.
  Temporal replay must advance scene time during lens motion, queued controls and
  dropped frames. Controlled moving targets and actual hardware remain final evidence.

## Benchmark selection protocol

Record per-lens near/far/stacked-depth and low-light data, including a slow reference
sweep for static scenes. Split tuning and held-out scenes. Run identical controller
replays with measured latency and noise, then validate finalists on the device.

Report final sharpness, time out of focus, recovery p50/p95, false focus success,
static-scene lens travel, reversal count, visible breathing, CPU/buffer cost and
preview cadence. Compare a simple coarse/fine baseline, the proposed adaptive policy,
and working HAL AF-C. Report a tradeoff frontier instead of selecting a winner from
one weighted score. Select phone defaults only after held-out/device acceptance.

## What remains unverified

- No trained autofocus weights with validated ordinary-RAW Camera2 deployment and
  cross-lens calibration were verified. This does not establish that none exist.
- No device depth/DP availability or manual actuation latency was measured here.
- Specialized microscopy/astronomy results are not evidence of moving-subject phone
  AF-C performance; those domains require separate transfer validation.
- Public search cannot expose proprietary HAL controllers or establish exhaustive
  state-of-the-art coverage. Current-source implementation licensing must be checked
  at the file level before copying code; this review introduces no copied AF code.
