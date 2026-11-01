# Encoded Video internal namespace

The application and tooling use Encoded Video in class/file names, APIs,
fields, preference keys, JNI symbols, native log tags, diagnostics, shader
asset names, tests, and tool names. The former mode-specific probe/ladder
helpers and grade wrapper have also been renamed. S-Log3 and mathematical
logarithm terminology retain their technical meanings.

Application startup migrates stored settings, LUT selections/intensities,
and the previous crash marker. Current values take precedence. Settings
backup import normalizes the historical key namespace as well. The old
on-disk identifier is represented as numeric migration data, with no
retired spelling in current source identifiers or literals.

Source-content and filename scans over `app` and `tools` report zero
retired-name matches. Historical captured logs under the results archive
are retained unchanged; their links keep the original evidence paths.

Validation: all 1,814 JVM tests completed with zero failures and six skips.
Device migration, native readback, and all 12 RCD GPU tests passed. One
combined recording invocation initially supplied a recording LUT with a
BT.709 input profile and was rejected as intended; the recording check
passed when rerun with S-Log3 input. App startup and all nine settings tabs
passed the UI snapshot smoke check. Both ARM native targets and the normal
minified release built successfully. The separate test variant retains
the previously documented exclusion of duplicate isolated lint tasks.

Handoff: `/tmp/RawLens-2.0.62-Encoded-Video-renamed-arm64.apk`, separately
installable package `com.matthew.rawlens.vivocheck`; APK signature verified.

Evidence: [source audit](results/encoded-video-namespace-2026-10-31/source-audit.txt),
[JVM summary](results/encoded-video-namespace-2026-10-31/unit-summary.txt),
[migration/GPU checks](results/encoded-video-namespace-2026-10-31/migration-gpu-native-tests.txt),
[recording and UI smoke](results/encoded-video-namespace-2026-10-31/recording-and-ui-smoke.txt).
