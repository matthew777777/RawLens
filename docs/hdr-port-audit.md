# HDR port audit

## Source boundaries

Darktable merge equations were checked against `src/control/jobs/control_jobs.c` at
`52435b9a0c6bcf470f683e0c4455ecd321b9aec5`. Float DNG serialization is adapted from
`src/imageio/imageio_dng.c`, not from the merge function's file. The implementation is
a Kotlin adaptation, not a verbatim compilation of Darktable's desktop pipeline.

PhotonCamera's checked-in FlowNet model, JNI runtime and custom NCNN layers are compiled
directly. RawLens supplies the normalized Bayer proxy renderer and warp adapter.
The vendored files record their independently verified PhotonCamera source commit in
`app/src/main/cpp/flownet/UPSTREAM.md`; builds no longer require a local reference checkout.

## Corrections

- Apply sensor black/white normalization before merge; bake lens correction after merge.
- Include focal length in Darktable's aperture-area calibration and clamp final negative noise.
- Use darktable's f/22 + 8mm fallbacks when EXIF aperture/focal length are missing.
- Preserve CFA parity even when a warped position hits an image border.
- Warp once per frame before the 3x3 saturation envelope.
- Use smooth same-colour bilinear warp (never mixes R/G/B) instead of 2px-quantized
  nearest warp; evaluate darktable's 3x3 block extremes per 2x2 cell, then bilinearly
  interpolate the saturation mask per pixel so shadow/highlight blending is smooth
  (no 2px teeth edges or hard per-block clip verdicts on signs).
- HDR+-style deghosting: noise-aware Wiener weight on the exposure-compensated residual
  against the reference collapses moving outliers to the reference; a residual floor
  keeps flat shadows merging, and reference-clipped highlights bypass deghosting so the
  short exposure still rescues them via darktable's clipped fallback.
- `HdrRawMerge.mergeExact()` retains the verbatim darktable loop (border behaviour,
  negative-weight clipped bookkeeping, white-level normalization) for audit/tests.
- Fast exposure-compensated translation pre-align always runs (no native deps);
  FlowNet stays as the dense coarse-to-fine refinement: the moving frame is
  translation-compensated first, FlowNet estimates the residual, and the chained flow
  feeds the merge. FlowNet rejection falls back to translation, never to identity.
- Single-resample chain (2026-09-16 bracket validation): the pre-shift is snapped to
  even integers and applied as a lossless pure reindex (`warpShiftedEven`); the merge
  performs the only interpolating resample. Double bilinear cost ~25% Nyquist power
  loss on real brackets (HDR.dng vs F01); single resample simulates at ~0.79 vs 0.72
  kept. FlowNet-rejected frames fall back to the full-precision shift warp.
- Accumulation is input-ordered like darktable (no reference-first reordering).
- Use bilinear proxy and flow interpolation, with the shortest exposure as reference.
- Abort HDR on missing/nonfinite FlowNet output; reject fields that worsen proxy correspondence
  by more than 1% of the input range or leave fewer than 80% of sampled points in bounds.
- Guard capture state, clear partial brackets on failure/stop, allow exposure duration in timeout,
  and avoid stale Program exposure values in Auto mode.
- Compensate JPEG display exposure for shortest-frame merge normalization.
- Float DNG declares 1.4 backward compatibility and retains both camera calibration sets.
- AMaZE memory regression expects RGBA32F scratch (16 bytes/pixel), matching actual allocations.
  This is a memory-test correction, not proof of numerical parity with RawTherapee AMaZE.

## Validation limits

Local unit tests cover merge envelope, normalization, invalid flow, Bayer border parity and
float-DNG binary tags/sample preservation. Android APKs compile for all configured ABIs.
Emulator ABIs contain a stub and cannot perform FlowNet HDR merging.

On the connected Android 16 device, Vulkan model initialization took about 10.6 s and inference
about 1.6 s. A periodic checkerboard identity test failed with a large displacement. The
nonperiodic follow-up initially could not install (`INSTALL_FAILED_USER_RESTRICTED`).
On 2026-09-07 the retry installed successfully and the nonperiodic identical-frame test passed
on device 25080RABDG running Android 16, including the correspondence rejection check.

Real bracket capture, motion/occlusion quality, and
external Darktable/RawTherapee DNG decoding are not yet validated. The original claims of a
fully verified direct port were overstated. Float storage itself does not establish HDR quality.
