# A6: ETTR JPEG brightness

**Closed 2026-10-26.** Removed the ETTR-only develop exposure cap. Capture
ETTR, sensor ISO/shutter selection and DNG writing are unchanged by A6.

## References and rule

- [Magic Lantern ETTR documentation](https://wiki.magiclantern.fm/ettr): ETTR
  maximizes usable capture information; a dark result can be corrected during
  postprocessing. The pinned ML capture source remains
  `reticulatedpines/magiclantern_simplified@142472e95054d73640f50789ac0fd66325499d82`,
  `modules/ettr/ettr.c`, already studied in [F15](ml-focus-ettr-audit.md).
- [darktable scene-referred workflow](https://docs.darktable.org/usermanual/3.6/en/overview/workflow/edit-scene-referred/):
  meter capture for the sensor, then place midtones during development;
  highlight/shadow rendering belongs to later stages.
- [darktable exposure module](https://darktable-org.github.io/dtdocs/en/module-reference/processing-modules/exposure/):
  development exposure operates in scene-linear RGB. It is distinct from
  capture settings and can compensate withheld highlight-protection exposure.

No third-party code was copied. These sources justify separating sensor
headroom from display placement; RawLens retains its own existing shared
meter, percentile guards, low-key policy, AgX look and user strengths. In
particular, darktable's fixed default exposure offset is **not** adopted.

## Diagnosis

`AdaptiveDevelopmentExposure.analyzeSamples` first calculated the normal
JPEG placement and shared display highlight guards, then applied:

`min(correction, log2(2^-captureHeadroom / p99.5))`

This reused the RAW capture target as a developed-image white point. A
converged ETTR frame had little or no residual to that target, so it could
not receive the display lift requested for its dark midtones. The old
`RawDevelopmentSettings` and save-job comments still described a hard zero
for bright frames, but the implementation actually used this residual cap.

A failing host regression established the difference on identical RAW data:
normal display correction **+0.400195 EV**, ETTR headroom 0.5 correction
**-0.146410 EV**. This was a rendering policy difference, not a sensor
exposure or DNG-normalization fault.

## Changes

- Removed that cap and the ETTR exclusion from the existing night-platform
  display relief. ETTR now uses the same display rule as an equivalent AUTO
  frame. Shared spike/broad guards, correction ceilings and low-key damping
  remain. Capture ETTR retains its ML target and SNR/safety rules.
- Removed `ettrHeadroomEv` from adaptive analysis, `RawDevelopmentSettings`,
  `RawFrameSaveInput`, the controller's save snapshot and their call sites.
  It no longer needs to be threaded through the pipeline for an unsupported
  display restriction. Updated stale comments and migrated the old cap tests
  into display-brightness/highlight regressions.
- Confirmed AUTO/ETTR use the existing AUTO adaptive setting (default 1.0),
  PROGRAM uses its existing slider (default 0.5), MANUAL remains 0. Adaptive
  Off still disables automatic development exposure. No new knob or fixed
  EV boost was added. The H1 device fixture now uses these production
  strengths instead of giving its simulated ETTR case PROGRAM's 0.5.
- JPEG, CFA/AI-CFA, JXL and Quad-Bayer develop paths share this change.
  The existing viewfinder estimators already used the common display rule;
  removing the save-only cap removes that source of disagreement. Also
  carried H2's resolved middle-grey target into both preview estimators;
  previously their reconstructed tuning omitted it while saves retained it.
- No new image pass, shader, frame copy or per-pixel allocation. HDR+,
  MFSR, AMaZE and highlight-reconstruction algorithms were not rewritten.

## Verification

**112 host tests pass across 11 classes**, including adaptive development,
invalid/meterable samples, output settings, AgX, preview strength/BGU parity,
H2 integration, ML ETTR, ETTR loop and RAW exposure solver. Device app and test
APKs build; final test source compiles. `git diff --check` passes.

`EttrDevelopmentDeviceTest` uses actual Camera2 AUTO (eight converged AE
warm-up results), PROGRAM and ETTR sensor pairs. It applies the same
production develop strengths and saves full 4080x3060 JPEGs. On the ETTR RAW
it additionally replays the removed residual cap through the same renderer,
so before/after are the **same frame**, not two potentially different sensor
captures. RAW buffer SHA-256 before/after development agrees in all modes.

Final run: Xiaomi 25080RABDG, Android 16, camera 0, app 1.0.427 deviceTest;
**1 instrumented test passes in 25.182 s** on the bright-window/dark-room scene.

| Render | ISO | Shutter ns | Applied develop EV | JPEG median code | Neutral-area EV vs PROGRAM |
| --- | ---: | ---: | ---: | ---: | ---: |
| AUTO | 1811 | 9,997,740 | +1.1434 | 27 | -0.2453 |
| PROGRAM | 751 | 66,666,666 | -0.0744 | 32 | 0 |
| ETTR, former cap replay | 212 | 66,666,666 | +0.4471 | 12 | -1.1230 |
| ETTR, fixed | 212 | 66,666,666 | +1.4998 | 28 | -0.1060 |

Fixed ETTR is **0.106 EV below PROGRAM** and **0.139 EV above AUTO** in the
measured neutral areas (acceptance +/-0.3 EV), improving by 1.017 EV over the
former render. Exposure correction itself gains 1.053 EV; the output metric
includes the tone curve, so those numbers are not interchangeable.
The instrumented test checks improvement and PROGRAM tolerance. The final
report also passes the AUTO tolerance check, which is retained in the final
test source for future runs and compilation-checked after this capture.

Sampled RAW clipped-channel maxima: AUTO **0.1385%**, PROGRAM **1.5235%**,
ETTR **0.0462%**. A6 does not expose ETTR hotter to obtain the lift. None of
the four JPEG sample grids has fully white pixels (all RGB codes >=250), and
visual inspection retains the exterior window/blind detail while lifting
the interior. This is sampled clipping evidence, not a guarantee that every
sensor/display pixel is unclipped.

Neutral-area measurement: a 96x72 grid, selecting 2,561 PROGRAM pixels with
mean code 35..150 and channel spread <35; compare mean linear sRGB luminance
at those positions. This is a real-scene brightness comparison, not a
calibrated physical grey-card or colour-chart measurement. Small framing
changes between separate modes remain possible; the ETTR old/new pair is
identical by construction. The four final JPEGs were visually inspected.

Evidence: [host tests](results/a6-2026-10-26/host-tests.txt),
[instrumentation](results/a6-2026-10-26/instrumentation.txt),
[paired report](results/a6-2026-10-26/paired-report.txt),
[report assertions](results/a6-2026-10-26/verification.txt),
[JPEG hashes](results/a6-2026-10-26/jpeg-sha256.txt).
Full JPEGs: `/tmp/rawlens-a6-final/` (not committed).

An earlier paired run also demonstrated why exact mode identity is not a
universal contract: AUTO was 0.69 EV above PROGRAM, while fixed ETTR was
0.23 EV below PROGRAM. HAL exposure, clipping, different strengths and scene
variation can change relative placement. A6 removes the extra ETTR-only
restriction; it does not force every mode/scene to an identical image.

Broader device coverage, minified release smoke and full original-build
performance parity remain E22. JXL uses the same analysis, but this device
acceptance encodes JPEG only. The prior H1/H2 evidence remains historical;
its ETTR residual-cap note is superseded by this audit.
