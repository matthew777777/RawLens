# RapidRAW JPEG chroma and detail preprocessing

RawLens now applies RapidRAW's default chroma reduction (0.5) and gentle detail
enhancement (0.35) to the developed linear image before JPEG tone mapping. These
stages run independently of optional AI/wavelet denoise, on single-frame, CFA
merge, AI RGB and merged RGB JPEG paths. DNG and JXL output are unaffected.

Settings → Details → JPEG cleanup → **RapidRAW cleanup** switches both stages
on/off together (default on). The saved preference is carried by `ChromaCleanup`
inside the capture-frozen JPEG settings, which also supplies offline merges.
Off skips all cleanup GPU work and restores fused JPEG output where otherwise
eligible. JPEG metadata records the actual on/off setting.

The chroma stage estimates noise in square-root luminance/color coordinates,
filters half- and quarter-resolution planes, uses guided upsampling and residual
suppression, and reconstructs RGB at the original luminance. Detail enhancement
uses a noise-gated 5x5 binomial high pass, limits bright/dark overshoot separately,
and scales RGB together. The two strengths match upstream defaults.

The GPU filter operates in linear sRGB, converting the incoming ACEScg
texture with the same matrices as the output shader, then converting back. The
final reconstruction/detail step is fused with tone mapping and SDR/Ultra HDR
output; no full-resolution cleaned RGB intermediate is allocated in production.
Half-float rounding is emulated in registers to preserve the former intermediate's
numerical behavior. SDR and the center-sampled Ultra HDR gain map use the same
cleaned value, including partial 4x4 cells at odd image edges. CFA-to-JPEG fusion
is disabled while cleanup is on so it cannot bypass preprocessing. The existing
AMaZE/RCD choice, false-color/defringe settings, optional capture sharpening,
exposure, Basic/AgX curves and highlight processing retain their behavior.

This matches the two preprocessing algorithms, not the entire RapidRAW renderer.
Different demosaicing, camera color calibration, exposure and highlight processing
can still produce different pictures from the same DNG.

## Source and validation

GPU port: `app/src/main/assets/shaders/display/rapidraw_cleanup.glsl`, orchestrated by
`RapidRawCleanup.kt`. Numerical CPU reference: `app/src/main/cpp/rapidraw_cleanup.{cpp,h}`. Original:
`src-tauri/src/image_processing.rs` at RapidRAW commit
`1a39ae4e76d825c5abf0d4bafee798f99e0a0b63`.
The original is AGPLv3; notices, the license text and the app's About page include
that component and the combined-work terms.

`tools/rapidraw-cleanup/check.py` extracts the original Rust functions, replacing
only Rayon scheduling with sequential iteration, and compares the production C++
kernel with the original. It checks the upstream source hash before running.
100 comparisons (five dimensions, four image classes, five strength combinations)
cover neutral fields, noisy shadows, sharp edges, negative/HDR values, odd sizes,
one-pixel images and bypass. Host result: maximum absolute RGB difference **0**.

`RapidRawCleanupInstrumentedTest` covers the GPU/reference comparison, source texture
preservation, chroma suppression, brightness-edge preservation, neutral/black/HDR
fields, and 12.5 MP JPEG output with and without an Ultra HDR gain map.

## Runtime cost

Production filtering runs on the GPU. Only sparse noise/residual samples are
mapped to the CPU for exact median selection; full-resolution RGB is never read
back or uploaded. At 4080x3060 the two sparse mappings total about 5 MB rather
than a 100 MB RGBA16F readback plus upload. The C++ full-frame implementation is
retained as a test reference, not selected by the production JPEG path.

The full-resolution pass fuses chroma reconstruction and detail enhancement.
Each 8x8 workgroup caches 12 rows of horizontal blur, local extrema and luminance
in 1536 bytes of shared memory. This avoids two full-resolution luma/blur scratch
images (about 100 MB at 12.5 MP). Fused JPEG output also removes the former 100 MB
cleaned RGB intermediate and its store/load traffic. Color intermediates use RGBA16F;
padded regression inputs pack the same half-precision reliability/widening values
alongside color in RGBA32F. Each filter tap now fetches one texture instead of two,
without reducing its stored precision. Shader arithmetic remains 32-bit.
The existing color-plane quantization means GPU results are not bit-exact
to the CPU/Rust reference. Source textures are preserved and all scratch is
released inside the callback lifetime.

`tools/rapidraw-cleanup/malioc.py` compiles the preprocessing/reference passes and
four specialized Basic/AgX × SDR/Ultra HDR JPEG output variants for the
connected phone's Mali-G615. The report is
`docs/refactor/results/rapidraw-cleanup-mali-g615.json`. There are no register
spills. All four fused output variants use 32 work registers, 100% occupancy, and
1536 bytes of shared memory. The half/quarter regression filters use 64/62 registers,
50% occupancy; all other variants have 100% occupancy. These are offline compiler
estimates, not elapsed-time measurements. Compared with the first implementation,
the compiler's half-filter texture cost falls from 1.62 to 0.88 and quarter-filter
cost from 3.00 to 1.75 in its normalized total-cycle report.

Large dispatches use the existing `GpuScheduling.slices` coordinate contract:
262,144 invocations per submission, or 131,072 for regression filters. Each slice
fence-waits with a timeout and yields for 1 ms after every fourth slice, bounding
in-flight still-save work so the independent viewfinder queue gets submission
opportunities. Both global pixel IDs and shared-tile workgroup origins include
the slice offset. An odd-sized-image test compares sliced and monolithic output
bit-for-bit. This optimizes still-JPEG processing; it does not introduce this
full-resolution filter into the live video/viewfinder path.

Initial implementation timings on the Xiaomi 25080RABDG / Mali-G615 MC2, 4080x3060
synthetic noisy ramp (GPU shader cache warmed, `glFinish` around each complete
cleanup, excluding tone mapping and JPEG encoding): CPU reference **1271 ms**;
GPU **488, 449, 411 ms**, median **449 ms**, **2.83x** faster. This is one short
device run, not a thermal/endurance or real-scene benchmark. GPU peak tracked
scratch is **153,073,312 bytes** including the cleaned output texture and sparse
sample buffer, excluding the caller's source texture and driver allocations.

The noisy-edge GPU/reference comparison measured maximum absolute ACEScg RGB
error **0.0005951**, mean **0.000007688** after half-float texture storage.
All **13** targeted device tests passed, including 12.5 MP SDR/Ultra HDR output
and existing merged-output tests. See `results/rapidraw-cleanup-device.txt` and
`results/rapidraw-cleanup-timings.txt` for the device evidence.

## Mobile optimization validation

The optimized implementation passes **16** rendering tests. Sliced and monolithic
workgroup output is bit-identical on odd image dimensions. Fused and separate
JPEG passes differ by at most **one stored 8-bit code** across Basic/AgX,
sRGB/Display P3 and SDR/Ultra HDR, including odd gain-map edges. Comparisons use
native bitmap codes: Android `getPixels()` converts P3 to sRGB and can amplify
small quantization differences during that conversion.

The same-run 4080x3060 Ultra HDR bitmap benchmark (includes cleanup, tone mapping,
SDR/gain-map readback and bitmap construction, excludes JPEG file encoding) measured
separate passes **940/1002/1086 ms** and fused output **1035/910/669 ms**: medians
**1002 vs 910 ms**, about **9% faster**. These are three short samples on one phone;
they do not establish thermal endurance or real-time frame-rate guarantees.
Do not compare their absolute times directly with the earlier 449 ms cleanup-only
result: the measured scope and phone operating conditions differ. The standalone
CPU/GPU comparison in this run measured 2038 ms vs a median 815 ms, 2.50x.
Tracked peak GPU scratch is about 128 MB at this resolution, excluding the source,
driver allocations and transient CPU median scratch. It is lower than the initial
153 MB GPU implementation despite the wider packed regression inputs.

The actual Details UI test passes: the switch is present, changes the stored and
capture settings, survives reopening and restores its initial state. Four JPEG
metadata unit tests also pass. A concurrent-viewfinder test was skipped because
the first isolated-app session had no active RAW viewfinder while the phone was
asleep. The test was updated to wake/restore the screen and compiled successfully,
but the phone disconnected before that repeat could run. **Live viewfinder FPS
under load remains unverified.** Bounded GPU submissions are implemented and their
coordinate parity is verified; they should not be reported as a measured FPS result.

Evidence: `results/rapidraw-cleanup-mobile-device.txt`,
`results/rapidraw-cleanup-mobile-timings.txt`,
`results/rapidraw-cleanup-daily-device.txt`, and
`results/rapidraw-cleanup-daily-results.txt`.
