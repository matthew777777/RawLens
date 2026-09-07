# HDR port audit

## Source boundaries

Darktable merge equations were checked against `src/control/jobs/control_jobs.c` at
`52435b9a0c6bcf470f683e0c4455ecd321b9aec5`. Float DNG serialization is adapted from
`src/imageio/imageio_dng.c`, not from the merge function's file. The implementation is
a Kotlin adaptation, not a verbatim compilation of Darktable's desktop pipeline.

PhotonCamera's checked-in FlowNet model, JNI runtime and custom NCNN layers are compiled
directly. RawLens supplies the normalized Bayer proxy renderer and warp adapter.
The reference directory has no Git metadata in this workspace; its recorded commit is
provenance supplied by `references/README.md`, not independently verified checkout HEAD.

## Corrections

- Apply sensor black/white normalization before merge; bake lens correction after merge.
- Include focal length in Darktable's aperture-area calibration and clamp final negative noise.
- Preserve CFA parity even when a warped position hits an image border.
- Warp once per frame before the 3x3 saturation envelope.
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
