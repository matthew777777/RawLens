# Photo autofocus device acceptance — 2026-10-31

> Repository context: [current source state](../../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Tested current workspace build 2.0.40, githubDeviceTest, on Redmi 25080RABDG (`lapis`), MediaTek MT6878, Android 16 / OS3.0.303.0.WPPEUXM. Main Camera2 route `0`; RAW 4080×3060. This is evidence for this device/route/build, not Qualcomm or all MediaTek firmware.

## Results

| Check | Observation |
|---|---|
| Idle AF-C with user moving a textured object | CONTINUOUS_PICTURE remained requested; reported lens distance varied 1.00–6.56 D in the RAW-only sample window and 1.64–4.65 D in the ISP-preview window. Passive focused/unfocused transitions occurred. |
| Tap focus | AUTO -> START frame 39 -> ACTIVE_SCAN -> FOCUSED_LOCKED frame 43; 322 ms from queue submission. |
| Timed release | Locked 08:32:15.338; restored CONT_PICTURE 08:32:25.341: 10.003 seconds. |
| Long-press controller lock | FOCUSED_LOCKED; remained active beyond the normal 10-second timeout; explicit reset restored continuous-picture. |
| Normal ISP preview | Temporarily exercised the preview-required branch: full RAW plus 1440×1080 SurfaceTexture preview configured. The original saved requirement flag was restored; this forced trial does not establish that the device requires an ISP stream. |
| Three rapid retaps, AUTO exposure | Final run 6 locked in 474 ms; AE_ON; no stale run completed the final scan. |
| Three rapid retaps, PROGRAM exposure | Final run 10 locked in 864 ms; AE_OFF. |
| Three rapid retaps, manual exposure | Final run 14 locked in 707 ms; AE_OFF. |
| Reset after each exposure mode | Results returned to CONTINUOUS_PICTURE. |
| Actual touch UI | Single tap locked in 394 ms. Long press locked in 394 ms and displayed AE/AF LOCKED ∞. A later screenshot still showed that indefinite lock. Same-point double-tap cleared the reticles and restored CONTINUOUS AF. |
| Camera errors | No capture-failure or AndroidRuntime crash appeared in the filtered capture. |

Both instrumentation invocations passed: `touchFocusReachesVerdictHoldsAndReleases` (25.355 s) and `photoAfPreviewAndExposureMatrix` (33.526 s). Logs show seven completed successful AF runs, 322–864 ms from queueing to lock. Rapid-retap trials intentionally replace earlier runs before completion.

## Evidence and limits

- [Tap/hold instrumentation](tap-hold-test.txt)
- [Preview/exposure instrumentation](preview-exposure-test.txt)
- [Frame/state/distance telemetry](telemetry.txt)
- [Camera log](logcat.txt)
- [Indefinite lock screenshot](hold-after-timeout.png)
- [Double-tap release screenshot](double-tap-release.png)

The user moved a near textured bottle against a distant background. Screenshots show the live scene and lock/release UI; metadata shows lens movement and successful HAL lock reports. This was not a controlled optical MTF or distance/latency benchmark. Low-contrast failure behavior, other lenses, other devices and Qualcomm hardware were not certified by these two runs. No production AF tuning was needed from these observations.

The device retained RawLens 2.0.40 with the tested AF implementation, was left in continuous AF, and retained its original preview-required flag. Builds used `-PfreezeVersion`; unrelated workspace changes were preserved.
