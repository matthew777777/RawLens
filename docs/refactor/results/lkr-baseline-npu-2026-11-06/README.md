# LK baseline rename and NPU test

> Repository context: [current source state](../../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Renamed the user-facing mode to **LK baseline with FFDNet** and corrected its
explanation. Existing preference keys remain compatible. The new label was
verified in the APK. Installed package: `com.matthew.rawlens.lkrcheck`, version
2.2.23 (2223). The normal app was not replaced.

APK: `/tmp/rawlens-lkr-npu-device/RawLens-LK-baseline-NPU-test.apk`.

Eight-frame fixture: `/data/local/tmp/lkr/night`, 4080×3060 RAW16. Tests require
backend 3 (LiteRT NPU), rejecting fallback. Both tests passed; crop floats have
correct dimensions and finite values.

| Case | Merge wall time | Native merge | FFDNet calls | Mean execution/call |
|---|---:|---:|---:|---:|
| Baseline 1×, 1024×1024 crop | 9.766 s | 8.069 s | 36 | 107.9 ms |
| Enhanced 1×, 1024×1024 crop | 13.136 s | 10.711 s | 36 | 107.2 ms |
| Enhanced 2×, 512×512 → 1024×1024 | 8.695 s | 5.621 s | 4 | 394.3 ms |
| Enhanced full, 4080×3060 | 70.862 s | 68.928 s | 252 | 105.7 ms |

Single runs. Wall time includes model setup, excludes fixture loading and
instrumentation startup. Reconstruction uses Vulkan; FFDNet uses NPU.
Execution/call excludes CPU packing/unpacking and prior lock waiting. Aggregate
worker times are not additive wall times. This validates execution and basic
output validity, not full visual quality or paper parity.

The old test exposed both vendor dispatch libraries and wrongly chose Qualcomm
on MediaTek. It now uses the production app’s vendor-specific
`LiteRtLibraries.directory(context)`. The first failed run was replaced by the
successful rerun recorded here. Unrelated existing Android tests did not compile;
a temporary Gradle init script isolated this test without changing them.

See baseline/enhanced instrumentation and logcat files alongside this report.
