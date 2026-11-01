# Headless RAW peaking acceptance — 2026-10-31

Device: Redmi `25080RABDG`, MediaTek MT6878, Android 16 / SDK 36. Build: 2.0.45 githubDeviceTest, main route `0`, RAW 4080×3060. No test Activity, TextureView, screenshots, or injected gestures were used after the headless-only instruction. Camera2 configured the RAW ImageReader alone (`PREVIEW=none`).

## Passed checks

- [Three-test suite](headless-suite.txt): detector fixtures, native/format parity, real RAW sweep; `OK (3 tests)`, 15.469 s.
- [Final sweep plus worker ownership](worker-suite.txt): `OK (1 test)`, 18.081 s.
- [Detail fixtures](detail-fixtures.txt): low-contrast axis/diagonal boundaries, flat surfaces and ramps, broad defocus, clipped highlights, 400 noise-only patches and a hot sample.
- [12 native-format fixtures](native-formats.txt): all four CFA patterns × RAW16/10/12. Padding, nonzero buffer origin, mixed-channel clipping and truncated inputs included.
- Every real RAW native mask and edge position matched the Kotlin reference exactly. Across the two native sweeps, 3,724,784 marked-support RAW-channel checks found no clipped sample inside any marked neighborhood. These are pixel checks, not independent images.
- The photo worker delivered on `photo-peaking` after the camera owner released its image, and released its own lease before teardown. Stop reported hidden; no borrowed RAW image remained.

## Runtime and focus response

The initial [Kotlin-only sweep](initial-kotlin-raw.txt) measured roughly 175–720 ms/sample and motivated the native optimization.

The [first native sweep](native-raw.txt) measured 80.7–154.0 ms/sample (median 103.8 ms). Its Kotlin reference median was 420.2 ms. The [final worker sweep](worker-raw.txt) measured 83.4–165.0 ms/sample (median 143.5 ms); reference median 639.1 ms. Timings include reading and processing the sampled RAW patches. The worker drops backlog and the camera thread remains separate.

In the first native sweep, the 2 D stage produced 1,954–2,094 marks, the 4 D stage 276–288, and the 10 D stage none. At 0 D, results were 6–129 marks while AE was still changing. This demonstrates a focus-dependent response in that scene; it is not a calibrated optical-focus score or a guarantee that every mark is true optical focus. High-exposure stages retained valid un-clipped detail rather than blanking the entire picture.

## Scope and limits

The evidence is specific to this device and captured scenes. Synthetic fixtures establish the intended rejection rules, not universal noise or optical guarantees. The test did not launch the overlay UI, measure display placement visually, record video, or validate other physical lenses/devices. Source geometry tests and exact returned-coordinate comparisons cover mapping logic; no new screenshot-based acceptance is claimed.

RAW-only was sufficient throughout. Peaking adds no ISP stream. The existing production compatibility probe/session ladder remains responsible for ISP fallback where configured; this test did not force or change its stored verdicts. The installed build contains the new peaking implementation; the headless tests closed their camera session at completion.
