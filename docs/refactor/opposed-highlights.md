# Bayer inpaint opposed Vulkan port

Reference: darktable `891e46cd57b64d67dbde183d5d3c0e430edd11d6`.
Pinned files: `upstream/darktable-opposed.c` and `upstream/darktable-opposed.cl`.
The OpenCL implementation is the reduction-order reference. CPU OpenMP reductions
have a different, thread-dependent sum order, so universal bit identity to every
darktable CPU/OpenCL backend is not a meaningful guarantee.

## Ordered source mapping

| Step | Pinned upstream function | RawLens implementation |
| --- | --- | --- |
| 1 | `highlights_initmask` | Shader mode 0: floor(width/3) × floor(height/3), one RGB clipping mask per 3×3 block |
| 2 | `highlights_dilatemask`, `_mask_dilated` | Mode 1: exact 7×7 footprint excluding four corners; border blocks retain their input masks |
| 3 | `highlights_chroma`, `_calc_refavg` | Mode 2: one invocation per row, sequential columns, strict 0.2×clip < sample < clip candidates |
| 4 | `process_opposed_cl` host reduction | Host rows reduced top-to-bottom; chroma enabled only above 100 candidates |
| 5 | `highlights_opposed` | Mode 3: reconstruct only sample >= clip; never lower the measured sample |

`_calc_refavg` retains the upstream exclusive `min(maxrow-1,row+2)` and
`min(maxcol-1,col+2)` bounds, the per-channel arithmetic means, cube roots, opposing
channel averages, and `(v*v)*v` operation order. Shader arithmetic uses `precise`
where contraction could change rounding. The GLSL cube-root implementation starts
with `pow` but chooses the nearest binary32 root using exact 96-bit integer cubes
of neighboring float midpoints; the transcendental approximation does not decide
rounding. This replaces the backend-specific OpenCL `cbrt` primitive.
Positive normal divisions are likewise corrected with exact integer midpoint
products, avoiding hardware reciprocal approximation in shading normalization.

Clipping multiplier is 0.987. Upstream temperature coefficients and late
chromatic-adaptation correction are set to identity: input is pre-WB CFA.
All four Bayer arrangements are supported. `UnpackedRawCfa.pattern` already
includes its crop shift; sensor origin is used for lens shading, not shifted again.

## Storage and borders

Each mask cell is one R32UI word with three exact RGB bits. The original and
dilated masks share the left/right halves of one image. This cuts mask storage
75% compared with two RGBA32F images. Gain maps and precomputed coordinate axes
use sampled images, leaving four storage-image bindings: the core Vulkan 1.0
minimum. Chroma statistics occupy three RGBA32F pixels per row: sums, counts,
and exact clipping bounds. Each invocation still reduces one row left-to-right,
but an 8×8 workgroup now processes 64 rows with every lane active.

Only O(height) statistics return to the host between passes. The final pass uses
the exact clipped bounding rectangle; references continue reading the full CFA,
so region boundaries do not change neighboring samples. Mask/statistics images
release before allocating the output rectangle. The output readback touches only
that rectangle. A completely unclipped frame returns the input without creating
an output image.

JNI copies Java float arrays directly into mapped Vulkan staging, and copies the
output rectangle directly into destination array rows. There is no full-frame
Java direct transfer buffer. Capture-owned arrays can be updated after GPU reads
finish; shared CFA callers receive a copy. Native staging releases before AMaZE
starts, avoiding a retained 48 MiB allocation across the rest of the save.

The module and pipeline stay in a serial-worker session across photos and release
with the coordinator. A device/driver/UUID-checked pipeline cache persists to
`vulkan_pipeline_cache/opposed.bin`; corrupt or stale bytes start with an empty
cache. The camera controller queues prewarming on the idle serial save worker
when developed output and highlights are enabled. It skips prewarming when saves
are pending or queued.

The upstream floor-sized mask uses linear `(row/3)*mwidth+col/3` addressing.
That addressing (including its right-edge alias) is retained inside valid blocks;
the padded tail is defined as zero. This avoids out-of-allocation reads possible
in upstream for dimensions not divisible by three. Tests cover even dimensions
with partial blocks and a 4×4 crop.

## Production routing

Single RAW and in-memory CFA JPEG/JXL develop paths run the new stage before
AMaZE or RCD. The old RGB color-propagation stage is disconnected from production;
its references/tests remain available for historical comparison. Only wavelet
still requires the camera-linear RGB readback. With reconstruction alone the JPEG
path can use the fused demosaic/output path again. The existing toggle selects
opposed and retains its persisted preference key.

Lens shading is undone while sampling the opposed reference domain. The shader
interpolates the small Camera2 gain map using the CPU correction's channel and
sensor-coordinate rules. Shading is restored only on raised reconstructed samples;
unmodified values retain their original bits. This avoids treating lens gain as
sensor clipping. Adaptive exposure analyzes the measured CFA before reconstruction.
Caller-supplied shared CFAs remain unchanged, so sharing them with a DNG writer
remains safe; the private single-RAW unpacked array uses the owned in-place path.

The single-RAW route materializes a normalized CFA when the toggle is on and
uploads one full float plane to Vulkan; readback is limited to the clipped region.
This is not a zero-copy GLES ↔ Vulkan bridge. Unsupported Vulkan falls back to the Kotlin implementation.
Quad Bayer and already-demosaiced MFSR RGB bypass this Bayer-only operation.
HDR+ now runs opposed after the same-exposure CFA merge and before demosaic.
Exposure-bracket HDR carries a row-padded one-bit `CfaHighlightClipping` map from
the merge: fallback winners retain whether their selected sample was clipped;
weighted samples clear the bit when an unclipped measurement contributes. The
Sequential Trio route sets bits for clipped reference samples and clears them
only after successful short-frame rescue. Lens correction and denoise preserve
the map with the CFA. Opposed uses these bits instead of a radiance threshold,
so rescued values above 1.0 and valid near-white samples remain unchanged. The
mask takes approximately 1.5 MiB at 12 MP. Unknown HDR inputs without a mask are
left unreconstructed. Both merged JPEG and JXL routes use the same policy, meter
before reconstruction, and disable sensor-white desaturation when opposed is on.

Opposed is enabled by default. A one-time per-lens update enables the new method
even if the previous color-propagation preference was off; subsequent user switch
choices persist normally.

MFSR remains linear RGB without Bayer reconstruction or another demosaic. The
pinned darktable source contains `_process_linear_opposed` for linear RGB, but
that is a separate algorithm variant (per-channel masks and a 30-candidate gate)
and is not part of this Bayer port. Existing MFSR RGB and linear DNG pixels stay
unchanged; using opposed there requires a dedicated RGB implementation and
clipping-domain calibration, not a fabricated Bayer pattern.

## Vulkan support

The opposed host explicitly requests Vulkan 1.0. The checked-in shader is SPIR-V
1.0 with only `OpCapability Shader`, 8×8 workgroups, four storage images, and up to three
sampled images (including the optional HDR eligibility map). It needs neither subgroups, FP16/int64 arithmetic, external-memory
extensions, nor optional float-controls features. Other users of the shared
compute host keep their existing API-version choice. The 1.0 device-info path
uses only core 1.0 queries.

RawLens as a whole still has the existing Vulkan 1.1 install/startup gate because
viewfinder/video paths import AHardwareBuffers. This change lowers the highlight
stage's own requirement; it does not claim the whole app supports 1.0-only devices.

## RCD quality boundary

The old normalized-CFA RCD entry point clamped to [0,1] and quantized back to
uint16. It now uploads float32 CFA directly to the BLOB without a Java staging
copy. Bit 4 of the existing RCD mode selects float input; raw/video uint16 input
retains its original decoding and mode behavior. The RCD color tail receives the
white-window setting and keeps camera-linear output free of desaturation. This
preserves reconstructed values above sensor white through either demosaic backend.

## Verification and reproduction

- `python3 tools/verify_opposed_reference.py` compiles the pinned OpenCL kernel
  bodies as a scalar C++ oracle with FP contraction disabled. It regenerates
  `app/src/test/resources/opposed-opencl-reference.bin` (little-endian IEEE float).
- `sh tools/build_opposed_shader.sh` compiles and validates SPIR-V for Vulkan 1.0.
- `OpposedHighlightsTest`: 7 tests, including strict bit equality against 27,648
  source-derived output floats. `VulkanPipelineCacheTest`: 5 tests.
- `OpposedHighlightsDeviceTest`: 5 tests covering 20 strict bit-parity fixtures,
  partial blocks, unclipped samples, constant/varying shading, owned arrays,
  changed image sizes, corrupt/reloaded caches, and sparse/dense 12 MP images.
- `OpposedCaptureSaveDeviceTest`: real RAW capture, production JPEG development,
  full-size JPEG encoding and `fsync` in app cache; checks decoded dimensions.
- `OpposedRcdQualityDeviceTest`: above-white float CFA through all Bayer patterns,
  camera-linear output and the ACEScg tail with white desaturation disabled.

Measured on device model `25080RABDG`, 2026-10-26. The 20 deterministic/randomized
Vulkan fixtures match Kotlin output bit-for-bit, including varying lens shading.
The source-derived oracle fixture also matches Kotlin bit-for-bit. These are
measured fixture guarantees, not a universal promise across all drivers or every
darktable CPU/OpenCL reduction implementation.

| 4096×3072 stage | Before memory optimization | Optimized measurement |
| --- | ---: | ---: |
| Sparse clipping, full frame analyzed | 440.82 ms | 179.78–193.69 ms |
| Sparse clipping with lens shading, owned array | — | 218.28 ms |
| Fully clipped lens-shaded frame, owned array | — | 575.89 ms |
| Sparse peak compute-image payload | ~138.7 MiB | 58.8 MiB |
| Dense peak compute-image payload | ~138.7 MiB | 96.0 MiB |
| Java direct full-frame transfer buffer | 48 MiB | 0 |
| Extra output float array, private capture-owned path | 48 MiB | 0 |
| Shader setup, empty cache vs disk-warmed cache | 446.90 ms | 0.86 ms |

The synthetic wall times include post-run finite-value checks; logged phase times
separate upload, analysis, reconstruction and download. Image payload sizes exclude
driver allocation padding and pipeline overhead; they are not whole-process RSS.
The 301×301 reconstruction rectangle does not reduce the 4096×3072 analysis or
output resolution. Dense clipping still reconstructs all 4096×3072 samples.

Real sensor run: RAW and JPEG both 4080×3060; camera 576.13 ms, develop 3198.83 ms,
JPEG encode plus disk sync 346.81 ms, total 4121.77 ms. Highlight reconstruction
within development took 196 ms. The existing AMaZE/output readback wait dominated
(the 2349 ms wait includes pending GPU work; it is not an isolated transfer-time
measurement). This is one camera-to-cache-file run, excluding gallery/EXIF work,
with no paired old-reconstruction end-to-end run and no universal latency claim.

RCD above-white preservation, the existing integer-input RCD reference comparison,
and the shared MFSR RGB-tail regression all passed on the device after the float
upload change (3 instrumented tests).

Evidence: `results/opposed-vulkan-2026-10-26.txt`.

## HDR routing validation

`MergedHighlightReconstructionTest` covers default-on adoption, packed-mask row
boundaries, clipped-channel fallback winners, an empty HDR mask, untouched rescued
radiance, mask preservation through CFA copies, and Sequential Trio rescue to
1.6 followed by bit-exact preservation through reconstruction. The existing
`HdrRawMergeTest`, `HdrSeq*`, and `OpposedHighlightsTest` suites run alongside it.
`MergedHighlightsDeviceTest` compares GPU/CPU mask-mode output and compares both
merged JPEG/JXL public routes against explicitly reconstructed CFA development;
it also verifies that shared CFA/DNG inputs are unchanged.

Validated HDR routing update (2026-10-26): 44 focused JVM tests passed, including
Sequential Trio rescue to 1.6 and preservation by the highlight stage. Eight device
tests passed: masked HDR GPU/CPU bit parity; both HDR+ and HDR public JPEG/JXL
routes against explicit reconstruction; unchanged shared CFA/DNG input arrays;
the existing full-resolution/parity/cache suite; and unchanged MFSR RGB behavior.
Evidence: `results/merged-highlights-2026-10-26.txt`.


## Real HDR+ DNG and AMaZE OFF validation (2026-10-27)

Tested `IMG_20261022_165407_773_HDRPLUS.dng`, 4080×3060 GBRG,
against actual darktable 5.6.2 highlight-module input/output PFM dumps.
The input dump matches the DNG's normalized visible CFA exactly. Temperature
and color calibration were disabled; temperature's preset must also be changed
from late D65 (4) to as-shot (0), otherwise late correction remains active even
with those modules disabled. The comparison uses inpaint opposed, clip 1.0.
It compares the CFA stage, not final JPEG rendering.

Vulkan versus a scalar execution of the pinned, verbatim OpenCL kernels on that
same full-resolution input: **0 bit mismatches out of 12,484,800 samples**.
Vulkan versus the installed darktable CPU module: 128 bit mismatches, maximum
absolute error 0.0000125169754, mean absolute error 1.2802433e-10. Both paths
change 128 samples. This installed CPU comparison is not bit-exact; source/version
and CPU/OpenCL implementation differences remain relevant. Do not advertise
bit-exact parity with the installed darktable CPU renderer.

`verify_opposed_reference.py --input <CFA.f32> --width 4080 --height 3060
--pattern GBRG --output <oracle.bin>` reproduces the full-resolution source
oracle (input, output, row reductions, chroma, all float32). The opt-in
`OpposedRealDngParityDeviceTest` imports shell-owned fixtures through
instrumentation, reports all sample errors and asserts strict bits when the
manifest sets `expect_bit_exact`. Fixtures remain outside the repository.

With reconstruction OFF, both AMaZE final shaders detect sensor clipping after
undoing demosaic balance. This catches clipped green even when every balanced
channel is below the white-window threshold. The contract test now checks this
sensor basis. `AmazeHighlightDeviceTest` passes all four Bayer patterns through
fused JPEG and unfused scene-linear output with realistic non-unity white balance,
checks neutral clipped highlights, and verifies the disabled white fallback keeps
color when opposed reconstruction is ON. This is a synthetic GPU regression;
visual validation on additional real OFF captures remains useful.

Evidence: `results/opposed-real-dng-2026-10-27.txt`.
