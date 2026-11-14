# Vivo Encoded Video RAW pitch candidate fix

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

> Historical report: the video RCD implementation described here was removed on 2026-11-01. These measurements do not describe the current MHC/Super pixel video path.


## Evidence (2026-10-31)

The supplied `IMG_20261031_115018_371_LOG709.mp4` and
`IMG_20261031_114953_659_LOG709.mp4` are 3840x1920 HEVC Main 10 recordings
at approximately 30 fps, tagged limited-range BT.709. Both fully decode
with FFmpeg without reported errors. Software-decoded frames preserve the
repeated stair-stepped geometry and magenta/green pattern seen in VLC.
This establishes that the visible corruption is present in the encoded
picture, rather than being specific to VLC's hardware playback.

RCD was reported as the likely demosaic setting, but is not confirmed.
Neither file establishes the camera's RAW dimensions, CPU plane stride,
AHardwareBuffer stride, app version, or selected RCD backend.

## Candidate correction

The buffer-tier viewfinder already replaces the Camera2 plane pitch with
the imported allocation's stride. Packed RCD previously retained the
plane pitch while reading the imported allocation as a storage buffer.
If those pitches differ, its `y * pitch + x` RAW addressing reads the
wrong rows before demosaic and can also disturb Bayer colour phase.

Packed RCD now uses the imported stride for 2D non-BLOB RAW inputs and
checks that the crop fits the imported geometry. The common entry point
covers RGB, fused P010, and asynchronous fused P010 processing. Explicitly
packed BLOB uploads retain their supplied pitch, since BLOB width is in
bytes rather than Bayer pixels.

`PackedRcdDeviceTest.importedRawStrideOverridesPlanePitch` compares GPU
output from the correct pitch and a deliberately incorrect plane pitch,
using a synthetic 2D RAW allocation with extra columns. The existing BLOB
flat-colour tests cover preserving explicit upload pitches.

Validation: `:app:assembleGithubDeviceTest` and
`:app:assembleGithubDeviceTestAndroidTest` passed, including native builds
for arm64-v8a and armeabi-v7a and release-style lint.

Headless GPU validation subsequently ran on the connected Xiaomi
(`ro.product.model=25080RABDG`) with the patched 2.0.44 device-test APK.
All 10 distinct `PackedRcdDeviceTest` tests passed: the initial full-class
run passed nine and skipped the LUT timing test for lack of a LUT; that
remaining test then passed with a generated 16-point identity LUT.
This includes the new mismatched-pitch regression, BLOB flat colours and
partial tiles, shader reference parity, fused/scaled P010 parity,
asynchronous slots, lens shading, 4K processing, and S-Log3/LUT processing.
Reference RGB comparisons changed zero values across all four Bayer
patterns. Fused versus separate P010 comparisons had zero code difference
across BT.709, S-Log3, HLG, and ACES. These are synthetic headless GPU
checks, not a before/after camera-recording or performance comparison.
The device-test app and instrumentation APKs were installed preserving
app data. Evidence: [suite](results/vivo-rcd-xiaomi-2026-10-31/gpu-suite.txt),
[LUT test](results/vivo-rcd-xiaomi-2026-10-31/lut-test.txt), and
[GPU parity output](results/vivo-rcd-xiaomi-2026-10-31/gpu-parity-logcat.txt).

This is a correction of an addressing inconsistency, not a confirmed
diagnosis of the Vivo recordings. Confirmation requires a new recording
on the affected phone with the patched native library. Matching strides
on that phone would point to another cause, such as an incompatible RAW
import layout or encoder input handling.

## Follow-up: corruption persists on Vivo

The user reported unchanged video corruption and supplied
`logcat-20261031_185252_731 (1).txt`. The user also confirmed that the idle
RAW viewfinder is clean and the recording preview is corrupted. The
stride candidate therefore did not resolve the reported problem.

The new log confirms Vivo V2562, Android 16, RAW 4080x3064, a CPU plane
row stride of 8160 bytes (4080 samples), crop 3840x1920 at (120,572),
`rcd-packed-p010`, and `OMX.qcom.video.encoder.hevc` with
`input=IMAGE_WRITER` and `RCD GPU writer=true`. It records 69 graded
frames, zero dropped frames, and zero frame errors. It does not capture
the native AHB import diagnostics or an app version, so it cannot prove
a GPU/CPU RAW-stride mismatch or identify the exact installed build.

The recording preview samples the same output P010 buffer used for
encoding. Corruption in both the recording preview and MP4 therefore
does not isolate the encoder itself: malformed native output addressing
can affect both. A clean idle viewfinder makes the recording output path
a stronger suspect, while RCD itself remains a possible cause.

The second candidate disables direct storage-buffer writes into native
Qualcomm ImageWriter P010 buffers. Stride and allocation-size checks
alone do not validate native plane offsets or linearity. Qualcomm codecs
now prefer direct P010 input filled via Image planes; if unavailable,
they use the CPU-writable ImageWriter plane-copy path. RCD, grading, and
10-bit output remain GPU based; a CPU copy delivers the app-owned linear
P010 result into the exposed encoder planes. The existing MediaTek GPU
writer policy is unchanged. The first graded frame and encoder selection
now log the app version, RAW plane geometry, and selected delivery path.

This is a compatibility fallback and needs a Vivo retest; native P010
tiling has not been demonstrated by the supplied log. Unit validation:
31 codec/probe tests and 16 recorder tests passed with zero failures.
Normal device-test APKs and a minified GitHub release built successfully.
The existing Xiaomi installation has a different signing certificate;
follow-up device checks use an isolated package to preserve its app/data.

Evidence: [Vivo field log](results/vivo-rcd-plane-copy-2026-10-31/vivo-field-log.txt),
[codec/probe tests](results/vivo-rcd-plane-copy-2026-10-31/LogVideoProbeTest.xml),
[recorder tests](results/vivo-rcd-plane-copy-2026-10-31/DirectLogRecorderTest.xml).

Follow-up Xiaomi validation on package `com.matthew.rawlens.vivocheck`,
version 2.0.50: all 10 packed-RCD GPU tests passed with an identity LUT
and no skips. The headless real-camera RCD recording test passed both
with `gpuWriter=true` and `gpuWriter=false` (three-second requested takes,
BT.709, no Activity launched). Each test validates the MP4 and decodes a
frame on-device. The plane-copy MP4 was also pulled and fully decoded by
FFmpeg without reported errors: HEVC Main 10, 3840x2160, yuv420p10le,
BT.709, 49 frames, average 22.62 fps. These short runs establish functional
coverage, not unchanged performance; plane-copy delivery adds CPU/DRAM
work and sustained cadence still needs measurement on the Vivo.

The normal device-test and release builds passed lint; the isolated test
variant hit an internal Kotlin/lint crash in `CameraGuideOverlay.kt`.
The isolated variant was then packaged with only its duplicate vital-lint
tasks excluded. Its app ID was changed through a temporary Gradle init
script; production application ID and signing settings were not edited.
The release-key credentials available in the old local transcript could
not unlock the current keystore, so no update to the existing signed
Xiaomi app was installed. A separately installable arm64 test APK is
available locally at `/tmp/RawLens-2.0.50-Vivo-check-arm64.apk` for the Vivo
retest; it uses the same recording implementation with an isolated app ID.

Device results: [GPU suite](results/vivo-rcd-plane-copy-2026-10-31/xiaomi-gpu-suite.txt),
[GPU-writer recording](results/vivo-rcd-plane-copy-2026-10-31/xiaomi-gpu-writer-record.txt),
[plane-copy recording](results/vivo-rcd-plane-copy-2026-10-31/xiaomi-plane-copy-record.txt),
[MP4 metadata](results/vivo-rcd-plane-copy-2026-10-31/xiaomi-plane-copy-ffprobe.json).
