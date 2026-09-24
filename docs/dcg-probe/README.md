# On-phone 0 versus 3/0 RAW probe — 2026-09-23

Device: Xiaomi 25080RABDG (`lapis`, Android API 36), ADB serial fe79feha9lmb6hhi.

The tested routes were standalone camera `0` and logical camera `3` with `OutputConfiguration.setPhysicalCameraId("0")`. This did not compare standalone 0 against an unassigned logical-camera stream.

## Observations

- Public camera list: `[0, 1]`. Advertised concurrent set: `{0, 1}`. Hidden camera `3` was nevertheless accessible and reported physical IDs `{0, 2}`.
- Each tested route independently delivered 4080×3060 RAW_SENSOR at ISO 100 / 33,333,333 ns and ISO 800 / 4,166,667 ns. Two frames were saved per setting per route (eight total). Image/result timestamps matched.
- Physical-0 results from route 3/0 reported the requested ISO/exposure. Black level was 64 in all channels; white level 1023. Noise-profile metadata matched across routes at each ISO. Logical 3 exposed no physical request keys, so manual controls were applied at the request level.
- Opening 0 then 3, or 3 then 0, failed on the second device with CameraDevice error 2 (`ERROR_MAX_CAMERAS_IN_USE`). This was reproduced in both runs. Failure occurred before physical-output session configuration; no simultaneous RAW pairs or simultaneous timestamp differences were obtained.
- A logical-3 session with two RAW outputs both bound to physical 0 configured successfully. Its capture returned no completed result within 12 seconds and the device disconnected. This was tested once and skipped on the second run to avoid repeating the disruption. Session acceptance is not evidence of usable capture or independent gain controls.

## RAW observations and limits

The captured scene was effectively black: mean approximately 64.13–64.17 DN against black level 64. There were no saturated pixels. Per-CFA-site temporal noise, estimated as std(frame1-frame2)/sqrt(2), was approximately 0.593–0.602 DN at ISO 100 and 0.746–0.763 DN at ISO 800, with nearly identical results across routes. These are output-code measurements from two frames, not calibrated electron read noise. Quantization, hidden RAW processing, black correction and exposure differences limit interpretation.

The experiment does not establish effective analog gain, a conversion-gain transition, highlight headroom, scene-response correlation or a fusion benefit. An ISO sweep and controlled flat/dark measurements were not performed. Near-black scene correlation would not provide useful evidence of equivalent scene response.

Conclusion: this firmware did not provide concurrent independent RAW gain paths through 0 + 3/0 using the tested Camera2 configurations. Sequential low/high-ISO RAW capture works through either route. A sequential RAW HDR experiment remains possible, but no fusion pipeline was added based on these results.

## Artifacts and reproduction

- `report.txt`: complete successful-storage run, including expected concurrency rejections.
- `first-run-log.txt`: initial run including dual-output disconnect. Its individual capture saves failed due to diagnostic storage permissions; fixed before the second run.
- `raw-analysis.json`: per-route temporal-noise measurements from the eight saved RAWs.
- `instrumentation.txt`: runner completion. `OK (1 test)` means the probe completed, not that unsupported camera combinations passed.
- RAWs: `/private/tmp/rawlens-dcg-probe/` on the Mac, and `/sdcard/Android/data/com.matthew.rawlens/files/dcg-probe/` on the phone. Packed little-endian uint16, 4080×3060, row stride 8160, pixel stride 2. Mac temporary storage is not permanent.
- Probe: `app/src/androidTest/java/com/matthew/rawlens/DcgProbeInstrumentedTest.kt`.

Build with `./gradlew :app:assembleDebugAndroidTest`, install the test APK, enable the target application's camera permission, then run:

```sh
adb shell am instrument -w -r -e skipDual true \
  -e class com.matthew.rawlens.DcgProbeInstrumentedTest \
  com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
```

Omitting `skipDual true` includes the potentially disconnecting two-output case. This opt-in hardware probe records unsupported configurations as findings rather than failing JUnit assertions. The installed main app was not replaced; only the test package was installed. Camera permission was restored to its initial denied state after the experiment. Captures and the test package remain available.

Camera2 control semantics: https://developer.android.com/reference/android/hardware/camera2/CameraDevice and https://developer.android.com/media/camera/camera2/multi-camera . Physical request controls address a physical camera, not an independently configurable gain per surface.

## Uncovered repeat at the user's request

The initial near-black frames were explained by the phone being face-down on a bed. Repeated the complete single-output 0 versus 3/0 experiment with the camera uncovered, preserving the original files. Eight new RAWs were acquired successfully. Both concurrent-open orders again failed with error 2. The disruptive dual-output case was not repeated.

| Route | ISO / exposure | Mean above black (DN) | Pair-difference sigma across CFA sites (DN) |
|---|---|---:|---:|
| 0 | 100 / 1⁄30 s | 93.32 | 1.44–1.83 |
| 3/0 | 100 / 1⁄30 s | 92.41 | 1.43–1.82 |
| 0 | 800 / 1⁄240 s | 92.91 | 3.76–4.96 |
| 3/0 | 800 / 1⁄240 s | 92.59 | 3.74–4.94 |

There were no clipped pixels. Unregistered correlation between routes, using the same CFA site sampled every four pixels and averaging each repeated pair, was 0.99973 at ISO 100 and 0.99828 at ISO 800. These sequential captures support very similar image response through the two routes; they do not prove simultaneous readout or identical internal processing. Differences include scene drift, motion and lighting flicker. Pair-difference sigma includes shot noise and scene changes, not only read noise.

High ISO with eight times shorter exposure produced approximately equal output signal but greater temporal variation, consistent with reduced photon collection. This is not evidence of a DCG benefit, nor does it rule out an internal conversion-gain switch. Choosing equal ISO×exposure alone does not establish extra highlight range or lower shadow noise.

New artifacts: `report-uncovered.txt`, `raw-analysis-uncovered.json`, `instrumentation-uncovered.txt`. New RAWs: `/private/tmp/rawlens-dcg-probe-uncovered/`; original dark RAW backup: `/private/tmp/rawlens-dcg-probe-facedown/`. The phone directory now holds the uncovered repeat. Camera permission was restored to denied after the repeat.

## Simple merged DNG

At the user's request, captured a fresh sequential low-ISO frame through 0 and high-ISO frame through 3/0, with one repeat of each used only to estimate per-CFA-site variance. Source DNGs include actual Camera2 metadata via DngCreator. The first source timestamps differ by 1.894 seconds (route switching and source-file writing included).

`tools/merge_dcg_probe.py` performs black subtraction, exposure normalization and inverse-variance blending at identical pixel coordinates. There is no alignment, motion rejection, robustness mask, demosaic or denoising. Only saturation downweighting is applied. Low-ISO weights were 86.8–87.8% across CFA sites. Repeated-scene variance includes any motion/flicker; this is a simple diagnostic estimator. It uses the reference gain-map opcode and calibration metadata; no separate lens-shading correction is baked into the samples.

Output: `captures/dcg/RawLens_0_3-0_simple_merge.dng` locally and `/sdcard/DCIM/RawLens/RawLens_0_3-0_simple_merge.dng` on the phone. 4080×3060, 16-bit CFA, black pedestal 1024 and white 65535. The larger numerical container preserves fractional blend precision, not extra sensor dynamic range. No output samples clipped. Source exposure metadata describes reference camera 0. Source NoiseProfile was omitted because it does not describe the fused image. Orientation is set to normal sensor raster; the platform source DNGs emitted an invalid orientation value 9.

Verified the TIFF pixel round-trip exactly and decoded/demosaiced the DNG with LibRaw/rawpy; inspected the generated preview. The scene is visibly out of focus (probe focus was fixed at infinity), so this capture is not a sharpness comparison. The DNG remains a Bayer mosaic; only the separate preview is demosaiced. Main app pipeline remains unchanged. This experiment captured on the phone, merged on the Mac and copied the validated DNG back by ADB. The test app remains installed; camera permission was restored.

## One-camera adjacent-frame Vulkan experiment

Implemented and ran `DcgVulkanBurstInstrumentedTest` on the same phone. This test uses only camera 0, one RAW ImageReader and one Camera2 session. It submits eight warmup requests followed by four alternating manual-gain requests in one `captureBurst`. Callbacks pair Images and TotalCaptureResults by exact sensor timestamp and retain the Images; they do no RAW copying, processing or file writing. A test-only Vulkan library reuses RawLens's production AHardwareBuffer importer/device initialization. Inputs are imported as storage buffers with explicit row strides; the compute shader performs full-resolution Bayer fusion at identical pixel coordinates. Camera Images remain alive until Vulkan completion and foreign queue-family ownership is returned before releasing them.

Both low-first and high-first orders were measured. Each run produced two merged DNGs, entirely on the phone. High-first is now the test default:

| Measurement | High-first result |
|---|---:|
| First frame | ISO 800, 4.166667 ms exposure |
| Second frame | ISO 100, 33.333333 ms exposure |
| Sensor exposure-start difference, both pairs | 4.312334 ms |
| Exposure midpoint difference, both pairs | 18.895667 ms |
| Reported rolling shutter skew | 12.685090 ms |
| Reported frame duration | 33.350 ms |
| High-to-next-high start spacing | 66.808 ms |
| Vulkan submit-to-fence completion | 16.742 / 19.319 ms |
| Input import/output allocation/command setup | 5.389 / 1.791 ms |
| Output mapping/cache invalidation/copy | 31.967 / 15.753 ms |

The advertised minimum RAW frame duration was 50 ms, while actual result metadata reported 33.35 ms. The low-first run produced approximately 62.496 ms low-to-high start gaps. Alternating exposure lengths changes the reported exposure-start spacing; the 4.312 ms high-to-low figure is not a sustained frame interval. For the high-first pair, the first-row high exposure finishes about 0.146 ms before the low exposure starts. They remain separate exposures with different integration windows and rolling shutter; movement can still ghost or blur without alignment.

Fusion uses normalized black-subtracted data, actual ISO×exposure scale, an 8:1 low/high photon-count weight, and saturation tapering. There is no registration, motion mask, deghosting or demosaic. Zero-copy applies to camera inputs: no CPU RAW upload. The merged output is read back once for DNG writing. Camera2 DngCreator writes the GPU-produced uint16 raster, then the test patches TIFF black/white levels to 1024/65535 and removes the reference NoiseProfile, retaining camera calibration/crop metadata. Saving a 16-bit container does not prove 16-bit sensor precision or a DCG dynamic-range gain.

Validation: both on-device sampled CPU/GPU comparisons differed by at most one output code. Independently compared all 12,484,800 pixels of pair 0 against a host implementation: maximum error one code, 99.914% exact. LibRaw decoded both saved DNGs with the expected CFA, dimensions and black/white levels. Inspected the demosaiced preview. The scene has clipped highlights (4.59% of merged samples at white), and autofocus reported passive-unfocused with a constant focus distance; this is a pipeline/timing demonstration, not a quality or dynamic-range proof.

An initial run stopped before GPU import because `SyncFence.await()` returned false for the vendor's invalid-fence sentinel. The rerun logs `isValid=false` and treats an absent fence as no pending producer work; valid fences still require successful waiting. This matches the invalid-fence meaning documented by Android: https://developer.android.com/reference/android/hardware/SyncFence . Successful runs are in `vulkan-instrumentation.txt`, `vulkan-high-first-instrumentation.txt`, `vulkan-low-first-report.txt`, `vulkan-high-first-report.txt` and `vulkan-validation.json`.

Final phone files:

- `/sdcard/DCIM/RawLens/RawLens_Vulkan_4ms_pair0.dng`
- `/sdcard/DCIM/RawLens/RawLens_Vulkan_4ms_pair1.dng`

Local outputs and source DNGs: `captures/dcg/vulkan-high-first/`. The low-first outputs are retained in `captures/dcg/vulkan/`. These capture directories are ignored by Git. Main application/native pipeline was not replaced or modified; only the instrumentation APK was installed. Camera permission was restored to denied.

Build/run (NDK 27.0.12077973 on this Mac):

```sh
sh tools/build_dcg_vulkan_probe.sh
./gradlew :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.matthew.rawlens android.permission.CAMERA
adb shell am instrument -w -r -e highFirst true \
  -e class com.matthew.rawlens.DcgVulkanBurstInstrumentedTest \
  com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
```

Set `highFirst false` for the other order. The optional native test library is generated under `app/build/dcg-probe/jniLibs` and included only in androidTest through its source set. Shader source/SPIR-V reside under `app/src/androidTest/assets/dcg/`. Restore the prior permission state after manually running the probe.
