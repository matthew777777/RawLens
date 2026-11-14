# Bounded Encoded Video buffer diagnostics

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Each take samples the first two submitted output frames. RAW sampling is
also capped at two attempts so failed initial GPU submissions cannot
produce repeated pixel dumps. Remaining frames do not perform diagnostic
readbacks. Failures to inspect a buffer are logged and do not abort a take.

`EVdiag` lines are emitted through the app logger, including its OEM-safe
file tee. They include:

- RAW AHardwareBuffer width, height, stride, format, layers, and usage.
- An 8x4 grid of Bayer quads in the selected sensor crop, frame timestamp,
  channel order, black levels, and white level.
- An 8x4 grid of GPU-produced P010 Y/U/V samples. Linear staging uses a
  single CPU lock after the existing GPU fence has completed. Native
  GPU-writer output is sampled through exposed Image planes instead of
  guessing proprietary buffer offsets.
- Destination image dimensions, format, crop, and per-plane row/pixel
  strides, positions, limits, and capacities. Some codec images do not
  expose an AHardwareBuffer descriptor; plane metadata remains available.
- For copied P010, a comparison of 96 sampled source/destination words,
  mismatch count, maximum word difference, P010 low-bit checks, and the
  sampling time. Sequence numbers and sensor timestamps correlate stages.

The sparse grid helps distinguish RAW geometry, output-format, and copy
problems. It does not reconstruct full frames or reveal undocumented GPU
tiling. An original MP4 remains useful for spatial artifacts and decoding.

## Validation and handoff (2026-10-31)

The diagnostics unit tests passed (6 tests), alongside the codec/probe
and recorder suites (31 and 16 tests). Native libraries built for both ARM
targets; the normal device-test and minified release builds passed.
The isolated test package uses the previously documented exclusion of
duplicate lint tasks after the isolated variant's internal lint crash.

On Xiaomi 25080RABDG, both native readback tests and the headless session-log
verification passed. The real recording proof captured samples for frames
0 and 1 only and reported `checked=96 mismatches=0 maxWordDelta=0` for
each copied frame. A separate headless GPU-writer recording also passed.
Sampling stages in this run took roughly 1–3 ms; these are startup samples,
not a sustained-FPS A/B benchmark.

Evidence: [device tests](results/directlog-diagnostics-2026-10-31/device-tests.txt),
[exported diagnostic lines](results/directlog-diagnostics-2026-10-31/exported-diagnostics.txt),
[GPU-writer recording](results/directlog-diagnostics-2026-10-31/gpu-writer-record.txt).

Handoff APK: `/tmp/RawLens-2.0.52-Vivo-diagnostics-arm64.apk`, package
`com.matthew.rawlens.vivocheck`, separately installable alongside the
existing app. APK signature verification passed. SHA-256:
`0eab96a8e89b68f5cc8750a7621bc3555a28efebc535c5834a68110957408a63`.
It includes the Qualcomm plane-copy fallback; the Vivo must retest the
same lens, RCD setting, resolution, and crop, then return its log and clip.
