# 2026-09-22 capture log fixes

Source: `logcat-20260922_083313_059 (1).txt` (08:33–08:35).

## Capture failure and foreground-service crash

At 08:35:53.620 RAW development rejects `rawBinningFactorUsed=true`. The
writer completes before the service's `onCreate` at 08:35:53.667. The queued
foreground start is stopped externally, followed by
`ForegroundServiceDidNotStartInTimeException` at 08:35:53.702.

Shutdown now uses an ordered service STOP command. Every `onStartCommand`
acknowledges foreground startup, and STOP uses `stopSelf(startId)` so a newer
start is not cancelled by an older completion.

## Full-resolution Quad-Bayer JPEG

`rawBinningFactorUsed` is read from API 31, where Android introduced it. Layout
selection checks advertised capabilities and `SENSOR_INFO_BINNING_FACTOR`;
a 2x2 same-color group selects the dedicated Quad-Bayer path. Other grouped
layouts and unknown group sizes on cameras advertising remosaic remain
explicitly unsupported. A spurious flag on a camera without grouped-layout
metadata or the relevant capabilities does not alone reject regular Bayer.

The full-resolution algorithm is adapted from PhotonCamera commit
`4ee108e169496f429c0afa0cc33e57bb6b2ec724`:

- `demosaicp0quad.glsl`: gradients between neighboring photosites in a color group.
- `demosaicp12quad.glsl`: directional green reconstruction.
- `demosaicp2quad.glsl`: green-guided red/blue ratios, with linear interpolation
  near black and clipping.

RawLens uses two compute passes, computing the small gradient neighborhood
on demand. It retains the processing crop's original dimensions and each
measured color sample before the normal highlight/color/output transforms.
There is no pixel-group averaging or upscaling. Bayer order and sensor-origin
phase are explicit; border reads preserve the four-pixel CFA period.

Normalization retains Camera2's per-photosite black-offset tile. Lens shading
uses grouped color and green-row identities. Defect correction samples the
four-pixel same-color period. Existing color transforms, exposure placement,
JPEG rendering and Ultra HDR output follow demosaic.

Scope: single-frame RAW-to-JPEG development. The original DNG is preserved.
Bayer-only AI denoising, HDR CFA merging and RAW SR do not consume grouped CFA;
their existing unsupported-layout guards remain. Full-resolution sensor modes
are not overridden or changed by this fix.

## Vulkan preview import failure

Adreno repeatedly rejects imported RAW buffers with bad metadata signatures.
After the existing consecutive-failure threshold, EGL/NEON remains the fallback.
Import incompatibility no longer schedules device recreation. Submission/device
failures remain recoverable, and a new camera session can still probe its buffers.
Vendor startup and camera-close diagnostics in this log are not all application
faults; no attempt was made to suppress those messages.

## Verification

- Main Kotlin compilation passed.
- Both compute shaders passed `glslangValidator` ES 3.1 compute validation.
- 658 unit tests ran: 657 passed. The remaining existing failure is
  `RawNindNcnnContractTest.rawnindBlobsMatchExportedParam`, because
  `app/src/main/assets/models/rawnind_tiny.ncnn.param` is absent.
- New regressions cover ordered service shutdown, layout selection, all Bayer
  orders/crop phases in preparation, shading channels, truncation, Quad-Bayer
  defect repair, and Vulkan recovery policy.
- New device tests check original output dimensions, constant-color reconstruction
  including borders, and preservation of distinct within-group sensor samples.
  They compile in isolation. The complete device-test source set fails on six
  obsolete `DenoiseSettings(enabled, strength)` arguments in the existing
  `MergedJpegDevelopInstrumentedTest`.
- No Android device was connected. Device shader execution, real captured-image
  quality, preview fallback behavior and the foreground-service race need a phone
  validation run; static compilation does not establish those outcomes.
