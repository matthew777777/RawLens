# Coherent-green video demosaic investigation (2026-11-01)

## Final candidate

The strong white-glint smoothing experiment was rejected. The recording shaders now reconstruct a coherent green plane, then interpolate red-minus-green and blue-minus-green differences using green at the actual chroma sample positions. They do not force specular reflections to neutral or replace their luminance with a blurred estimate. Existing highlight protection in the grade is unchanged.

A compact prepass writes two binary16 greens per uint. The second pass reads this plane and performs chroma reconstruction, grading and complete-2x2 P010 chroma averaging. Green decisions and chroma math use fp32; the optional mixed-precision variant retains fp16 grading. The recording path still selects fp32. Coverage scales are computed once in native code in spare CCM-row components, removing repeated per-output-pixel division; same-size coverage directly uses the output coordinate. Native per-slot green buffers are fenced by the existing submission slots, with an explicit compute-write/compute-read barrier. CMake compiles the green source using the pinned NDK and embeds the generated SPIR-V into the native library.

This is a new MHC-family candidate, **not an exact RCD implementation**. Its green uses continuous squared-gradient weighting rather than a hard directional switch. Canonical normalized CFA input is clamped to [0,1]. Measured CFA values are retained; interpolated channels clamp to [0,4]. Full-resolution coherent green costs two bytes per crop pixel per slot, in addition to existing recording allocations.

## Controlled comparison

The supplied frame is 4080x2040 GBRG, black 64, white 1023. The independent oracle is built from the pinned, checksum-verified RawTherapee RCD source in `tools/photo-rcd`. `tools/mhc-highlights/compare_reconstruction.py INPUT.dng ORACLE OUTPUT` applies identical WB/CCM/709 development to standard MHC, the candidate, and RCD; no additional neutralization or creative look is used. It excludes crop borders from numeric comparisons.

In the water crop, normalized RGB mean absolute difference from RCD falls from 0.00356339 for standard MHC to 0.00180171 for coherent-green differences. This is reference agreement, not an objective ground-truth quality score. The attached `coherent-green-difference-vs-RCD.png` shows candidate left and RCD right, at identical coordinates and magnification. The screenshot supplied by the user was a qualitative target, not used as numerical pixel ground truth.

## Validation

19 JVM regression tests pass, including coherent measured-sample preservation in all Bayer phases and flat-color preservation. A real 64x64 clipped-water patch passes shipping fp32 and mixed-precision shader/P010 comparison against the CPU twin. All affected SPIR-V binaries pass `spirv-val`.

Headless tests use isolated package `com.matthew.rawlens.mhccheck` (`-PmhcHeadless`). `MhcHighlightDeviceTest#sustainedHeadlessFrameBudgets` measures 1,000 frames per requested mode after 30 warmups. The final harness uses the real recording geometry: 4080x3060 input, 3840x2160 crop at (120,450), scaling to the requested output. It includes CPU submit/fence polling, not Vulkan timestamp queries. It excludes camera/codec/lens shading/preview.

`tools/mhc-highlights/run_headless.py --precision fp32` additionally runs three 60-second headless camera/encoder takes with strict zero-recorder-drop assertions and minimum frame count/duration. Packet timestamps alone cannot establish sensor cadence: the existing recorder manufactures CFR timestamps from frame index, so a slow acquisition path can still emit perfectly spaced timestamps. Actual duration and frame-count checks are therefore mandatory. Earlier 4K runs with the previous algorithm failed duration despite zero recorder drops; those results must not be reported as a pass.

Final device reports are stored alongside this document after the run completes. No universal zero-drop or temporal-quality guarantee follows from a finite run on one device.

## Research

* [MHC explanation and reference implementation](https://www.ipol.im/pub/art/2011/g_mhcd/): Laplacian cross-channel corrections.
* [RawTherapee demosaicing documentation](https://github.com/RawTherapee/RawPedia/blob/master/content/Demosaicing/index.md): RCD is effective for rounded detail such as stars.
* [Original RCD source](https://github.com/LuisSR/RCD-Demosaicing): ratio-corrected reconstruction intended to reduce interpolation overshoot.
* [P010 storage and sampling](https://learn.microsoft.com/en-us/windows/win32/medfound/10-bit-and-16-bit-yuv-video-formats): 4:2:0 format.

The old `validate_frame.py` and `comparison-left-before-right-after.png` retain the superseded whitening experiment for provenance; use `compare_reconstruction.py` and the coherent comparison for the final candidate.

## Final optimized compute measurements

On Xiaomi 25080RABDG, 1,000 measured frames per mode, fp32 shipping reconstruction:

| Output | Average ms | p99 ms | Worst ms | Budget ms | Misses |
|---|---:|---:|---:|---:|---:|
| 1920x1080 / 30 | 14.768 | 20.482 | 27.825 | 33.333 | 0 |
| 2560x1440 / 30 | 25.199 | 29.062 | 32.590 | 33.333 | 0 |
| 3840x2160 / 24 | 28.756 | 32.684 | 37.237 | 41.667 | 0 |

These are headless fused-chain measurements with real crop geometry, without codec or camera. The 1440p worst frame leaves only 0.743 ms to its deadline; finite zero-miss observation is not a guarantee under every scheduling/thermal condition. See separate recording acceptance results.

## Recording acceptance: NOT release-ready

The final optimized candidate passed real-DNG device correctness and the complete 3,000-frame compute timing matrix. Full headless recording acceptance **did not pass**:

* 1080p30: 1,781 frames / 59.333 seconds of timestamp span, zero explicit recorder drops; test passed.
* 1440p30: 1,669 frames / 55.600 seconds from a 60-second take; duration test failed. CFR timestamps must not be mistaken for sustained real cadence.
* 4K24: test failed with one frame exception before exporting its artifact. A first cadence extraction incorrectly reused the previous 1440p file; that report is invalid and has been explicitly marked invalid. The runner now removes the prior artifact before every test and treats missing output as failure. An earlier coherent build passed one 60-second 4K take; this does not override the final failure.

Captured diagnostics include 83.34 ms and 125.01 ms sensor/capture gaps in the 4K run. No universal zero-drop claim is made. Camera acquisition, exception recovery, and delivery still need investigation before shipping this candidate as satisfying all requested video modes. The image reconstruction improvement is implemented and reviewable; the original end-to-end stability objective remains incomplete. No production release was installed or published; only the isolated test package was updated.
