# Wall reconstruction: LK-R versus SR-pipeline — unresolved

The owner's comparison is that MFSR (`tools/sr-pipeline`) renders the wall
correctly while LK-R Lite loses fine wall texture and produces waviness.
The target is `IMG_20261112_125318_179`, native resolution, especially sensor
rectangle x=2100..2700, y=260..800. Do not substitute the car zipper issue.

The earlier .455 -> .333 px p95 figure is an approximate gradient/residual
fit against a noisy reference, not a quality score or proof of a wall fix.
The retained `wall-final-comparison.png` still shows reduced fine wall texture
in both LK-R merges relative to the single frame. It contains no SR-pipeline
panel and cannot establish a cross-engine result.

## Source comparison

- SR-pipeline builds reference grayscale gradients/Hessians, refines each
  comparison's flow against that reference (`AlignGpu.kt`, `ica.comp`), then
  accumulates RAW samples with reconstruction kernels (`VulkanPipeline.kt`,
  `merge_splat.comp`).
- LK-R already uses an MFSR block-search shader for an optional local-motion
  initialization (`lkrl_full.cpp`). Merely importing block search is therefore
  not a new correction. It does not import the complete SR-pipeline alignment
  and reconstruction path.
- LK-R's subsequent G steps fit sampled demosaiced RGB against moving CFA
  measurements (`jac.comp`, `jacblk.comp`). The first three G steps run before
  J reconstruction, against the RGB seed. Local updates include neighboring
  block regularization (`solveblk.comp`). This differs from grayscale-reference
  refinement; seed interpolation bias remains a hypothesis, not a diagnosis.
- LK-R rejection uses a separate reference-only MHC seed (`z0`), while the
  reconstruction seed can mix MHC and bilinear interpolation. Changing just
  the reconstruction seed does not isolate rejection-reference bias.
- LK-R also combines fitted luminance detail with an aligned average
  (`hyb.comp`). Prior wall controls did not establish this combine alone as
  the cause; neither a regularization adjustment nor sharper appearance
  establishes preserved wall geometry.

## Required comparison

Use identical source frames/reference, native scale, normalization and noise
model. Match sensor coordinates after each engine's RGGB normalization and
output flips; identical CLI crop numbers alone do not ensure that. Include
enough surrounding context to avoid crop-boundary and motion-pyramid effects.
Render reference, current LK-R and current SR-pipeline with the same display
transform. Inspect wall cracks/streaks and texture, not only residual energy.

Separate geometry from reconstruction: compare LK-R initialized flow versus
post-G flow, hold geometry fixed during the reconstruction comparison, then
isolate reference-based rejection and hybrid combination. Do not change all
three together or accept blur as a geometry correction.

## Runtime limitation in this session

Fresh 768x768 wall-crop attempts with both engines failed before GPU work.
The SR-pipeline loader reports `nativeCreate failed`; explicit MoltenVK
diagnostics from `lkrl_motion_check` establish:

> VK_ERROR_INCOMPATIBLE_DRIVER: Vulkan is not supported on this device.
> MoltenVK requires Metal, which is not available on this device.

Logs: `/tmp/srp-lkrl-wall-debug.log`, `/tmp/lkrl-wall-gpu-diagnostic.log`.
Setting `VK_DRIVER_FILES` to the installed MoltenVK ICD did not resolve it.
No new wall output or validated correction was produced. Production behavior
was not changed on the strength of the source comparison.
