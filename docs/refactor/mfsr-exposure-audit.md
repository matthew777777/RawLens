# C14: adaptive exposure for MFSR development

Implemented 2026-10-26; acceptance remains open for same-scene MFSR / HDR+ /
single-frame saved-image and VF comparisons, plus device performance measurements.

## References and scope

- [darktable exposure](https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/exposure/):
  exposure is a scene-linear camera-RGB operation before the input colour profile.
  This supports the processing domain and stage; its automatic exposure algorithm
  is not copied or claimed as our algorithm.
- `RawSensorUnpacker.kt`, `BayerPattern`: R:G:B population is 1:2:1 for each
  supported Bayer pattern. This is the source for the RGB meter's channel votes.
- Existing `AdaptiveDevelopmentExposure.analyze` / `analyzeSamples`,
  `AdaptiveExposureTuning.fromOutputSettings`, `adaptivePreviewStrength`, and
  `HdrPlusZslSaveJob`: these are the internal parity references for placement,
  shoulder, highlight guards, strength and focus weighting. Their policy is reused.
- `MfsrMerge.runEngine` flips native output to top-down interleaved camera-linear
  float RGB before consumers; `processMfsrRgb` applies exposure in its colour tail.

No third-party code copied. No changes to capture AE, merge reconstruction,
HDR+, MFSR kernels, AMaZE or highlight reconstruction.

## Change

`analyzeRgb` samples the merged RGB, using R/G/G/B votes at each sampled site.
This reproduces Bayer channel population without picking a single CFA phase.
It deliberately does not substitute display luminance or apply white balance:
those would change the statistics relative to the existing camera-linear CFA meter.
It shares the existing sample policy, invalid/shadow rejection, shoulder, spatial
weights and correction limits. It uses the retained workspace, reads at most
16,384 sites (65,536 votes), and makes no full-resolution array copy.

Both MFSR developers resolve `exposureEv + correctionEv * strength` before the
camera-to-working-space transform. Strength zero bypasses metering. JPEG and JXL
use the same helper. The live ZSL job supplies the same mode-dependent strength
and look/focus tuning as HDR+. The DNG is written before development and neither
the meter nor exposure transform writes the RGB buffer.

Offline `BurstMergeRunner` keeps its existing default strength zero for both
HDR+ and MFSR; adding a new offline exposure preference is outside C14's live
HDR+ parity wiring.

## Validation

- Debug compilation passed.
- 49 host tests passed: adaptive placement, unusable samples, preview strength,
  Vulkan preview exposure and BGU EV parity.
- Four real linear DNG sample fixtures match an equivalent R/G/G/B CFA histogram
  to 1e-9 EV. Full-strength corrections: +0.045046, +0.135082, +0.301056,
  +0.562681 EV. These are meter comparisons, not full-image render parity.
- Synthetic RGB test covers nonzero buffer position, native byte order, unchanged
  input bytes/position and truncated input rejection.
- Device GPU test: adaptive strengths 0 / 0.5 / 1 produce the same JPEG pixels
  as explicit equivalent EV; source RGB bytes and buffer position remain unchanged.
  The same test also passes byte-exact half-float JXL output comparisons.
  Device model `25080RABDG`; instrumentation reports `OK (1 test)`, 0.88 s.

## Remaining acceptance

Do not close C14 based on histogram equivalence alone. Capture the same static
scene through live MFSR, HDR+ and single-frame saves with identical look, mode and
focus; compare neutral-region scene-linear brightness with a proposed tolerance
of 0.15 EV, record highlight clipping and dark-scene behaviour, and compare VF
placement. Confirm the tolerance against scene/meter sampling differences rather
than adjusting merge pixels to force it. Measure meter overhead on full-size
merged RGB and repeat captures to check memory and timing. Current checks do not
establish unchanged performance or compatibility on every previously working device.
