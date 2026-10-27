# F14 / F15: Magic Lantern source audit (2026-10-25)

Source review began on 2026-10-25; connected-device acceptance was performed
on 2026-10-25/26. The measurements below cover the connected Redmi 25080RABDG,
Android 16 / API 36, camera 0, RAW16 4080x3060. They do not establish support
or performance on untested devices, or pixel parity with Magic Lantern.
Existing unrelated working-tree edits were preserved.

## Pinned source

All ML files were downloaded and read at commit
`142472e95054d73640f50789ac0fd66325499d82` (also the current dev head at audit time):

- [src/zebra.c](https://github.com/reticulatedpines/magiclantern_simplified/blob/142472e95054d73640f50789ac0fd66325499d82/src/zebra.c):
  settings 306–319, get_focus_color 1075–1096, calc_peak 1441–1465,
  display filters 1504–1649, focus_found_pixel 1652–1684,
  draw threshold control 1780–1874, initial convergence 1877–1891.
- [modules/ettr/ettr.c](https://github.com/reticulatedpines/magiclantern_simplified/blob/142472e95054d73640f50789ac0fd66325499d82/modules/ettr/ettr.c):
  auto_ettr_get_correction 172–503; exposure application follows separately.
- [src/histogram.c](https://github.com/reticulatedpines/magiclantern_simplified/blob/142472e95054d73640f50789ac0fd66325499d82/src/histogram.c):
  raw_hist_get_percentile_levels 434–547 and overexposure count 557–588.
- [src/raw.c](https://github.com/reticulatedpines/magiclantern_simplified/blob/142472e95054d73640f50789ac0fd66325499d82/src/raw.c):
  raw_get_gray_pixel 1716 onward (projection switch at 1739).

## F14: focus peaking

| ML rule | RawLens code / constants | Decision and reason |
|---|---|---|
| Absolute four-neighbour Laplacian `abs(4c-l-r-u-d)` on 8-bit LiveView Y | FocusPeaking.kt: laplacian is mean squared four-neighbour Laplacian over four interior green samples; tenengrad is mean squared central differences | Intentional difference: native RAW green lattice, not display Y. Sign disappears when squared. Different units; ML thresholds cannot be copied. Preserve existing detector; analytic fixtures and connected-device comparisons are recorded below. |
| Optional edge suppression `2*max(e-(max(abs(dx),abs(dy))<<setting)/4,0)` | combinedEnergy: ratio >= 0.65 then min(Ten,Lap) | Intentional difference: blur/contrast heuristic, not ML's optional first-derivative subtraction. 0.65 is RawLens tuning, not derived from ML. |
| Threshold servo targets `focus_peaking_pthr` per mille; default 5 = **0.5%**, despite nearby source comment saying 1%; dot path threshold clamped 15..255 | EDGE_THRESHOLD 0.008, BLACK_MEAN_FLOOR 0.03, WASH_FRACTION 0.45 doubles gate once, then 8-neighbour despeckle | Intentional difference: fixed squared-linear-energy floor rather than occupancy servo. Avoid changing shipped overlay density without device evidence. None is a calibrated sensor-noise threshold. |
| Servo reacts to noisy/high-gain Y; no explicit sensor noise model in calc_peak | RAW normalization uses black/white; ratio alone passes noise, fixed energy may pass it at high gain | Limitation documented in file header; removed unsupported claims that noise necessarily dies at the floor. Neither method proves high-ISO noise rejection. |
| Six fixed colours (R/G/B/C/M/Y), global threshold colour and local energy colour, with boundaries 20/30/40/50 | FocusPeakingColor: G/R/Y/C/M/white; user picks one | Intentional difference: static accessible user choice; no strength-colour modes. White replaces ML blue. |
| Dot overlay writes 2x2 display blocks; optional grayscale/alpha/sharpen filters | FocusPeakingOverlayView maps sensor-grid cells to view, auto-show during MF/touch AF | Intentional difference: Android overlay geometry and focus-assist policy. No destructive preview filter. |
| No peaking blink selector in these settings or focus_found_pixel; blinking comment elsewhere describes zebra palette flags | Static overlay | Keep; do not invent an ML blink requirement. |
| LiveView scan every 3 rows / 2 columns, initial threshold guessing up to 50 passes | 128 columns, 24..96 rows, 8x8 sensor patches, 250 ms throttle in LiveScopes | Intentional CPU-cost/coverage tradeoff; MF pull responsiveness must be measured at record resolution. |
| No Halide path | scope_meter generator used **0.5**, Kotlin **0.65** | Fixed native generator and independent scalar check to 0.65; regenerated all four checked-in ABI archives. This is a real implementation disagreement, not an ML difference. |
| LiveView peaking continues while focusing | Recording transfers the camera from RawCameraController to DirectLogRecorder / RawVideoRecorder | Fixed missing RAW peaking fan-out: RecordingFocusPeaking borrows at most one image, samples off the camera/encoder threads at the shared 250 ms cadence, invalidates pending output on AF, and drains before reader teardown. MainActivity shows/clears the overlay during recording. |
| Overlay follows displayed picture | Direct-Log monitor fits a cropped sensor region | Carry the sensor crop with the mask; monitorRects uses the same VfAspectFit geometry as the monitor, including rotation/mirror, and clips dots out of letterboxes. Overlay is stacked above both preview surfaces. |

The Halide path and Kotlin fallback share the energy definition and Kotlin mask
postprocessing. The separate monitor library's display-luma Sobel/Laplacian
shader (`mon_focus_peaking.comp`, matching cpu_reference.cpp and combined
shader) is another detector; it is not the RAW green detector or an ML port.
Its display normalization and alpha/dilation controls must not be presented as
ML thresholds. No monitor shader behavior was changed in this audit.

## F15: ETTR, single ISO

| ML rule / source block | RawLens code | Decision and reason |
|---|---|---|
| Projection selector MAX_RGB, MAX_RB, MEDIAN_RGB (182–184) | RawEttrSampler.sampleQuads: max(R, mean(Gr,Gb), B) | Keep default MAX_RGB; no ML clipping-mode menu. Pooled greens and per-phase black/white normalization are Android CFA adaptations; no WB multiplication before sensor-clipping detection. |
| Highlight percentile `1000-ignore`, default ignore 1 (188) | PERCENTILES 999/950/900/800/750/700/600/500/300/200/150/100/50 | Keep default 0.1% allowance, fixed rather than user-configurable. This is ignored highlight area, not a separate reconstruction allowance. |
| Histogram uses 14-bit raw-code bins; rank `floor(total*p/1000)-2`, cumulative >= rank (histogram.c 521–534) | EttrQuadStats: 1/64 EV bins, rank floor(total*p/1000), cumulative > rank | Intentional approximation retained: bounded 1025-bin log storage over 16 EV. Omits ML's two-stuck-pixel rank subtraction; fixed 0.1% ignore already rejects a sparse tail. Not bit-exact percentile parity, especially for tiny samples or isolated bright objects. |
| Target min(user level,-0.5), default -1 (315) | MagicLanternEttr.TARGET_CEILING_EV -0.5; EttrSettings defaults to **0.5 headroom** | Ceiling matches. Default differs deliberately: existing RawLens setting is -0.5, not ML's -1. Earlier exposure-audit text claiming default -1 is stale. |
| Good frame: store highlight-minus-lower percentile offsets (319–329) | Memory.diffFromLower | Keep. Stored only below -0.1 EV; no extrapolation history update from clipped highlights. |
| Clipped >= -0.1; lower values < -0.1, positive offset; only stronger corrections; weights 13-k (331–374) | correction overexposed branch | Keep weighting and acceptance. `num` is sum of weights, **not number of independent agreeing percentiles**; earlier KDoc wording corrected. |
| Weighted estimate rejects spread >1 EV or correction >-1 EV; fallback -log2(1+p²), p in percent, floor -10 (377–399) | correction fallback | Keep; explicit NaN handling makes zero-weight division deterministic. -10 floor belongs to fallback only, not a global step clamp. |
| Overexposed area: AVG_RGB >= 80% of raw white; percent*100 integer return (histogram.c 557–588) | Quad average RGB >= 0.8 after per-phase normalization; fraction converted to percent | Intentional scale adaptation: ML threshold includes black pedestal, ours is normalized black-subtracted range. Floating fraction omits ML's 0.01% truncation. |
| SNR uses median / p5, DR from Canon tables; targets 6 / 2 EV, zero disables each; clipped probe -2 EV when limits enabled (434–469) | dynamicRangeEv = -0.5 log2(Camera2 noise offset); correction SNR block | Keep Android engineering-DR substitute, skip when unavailable. Fixed zero-limit semantics so disabled limits cannot raise exposure or add the -2 probe. Camera2 noise-offset DR is not a measured ML/DxO equivalence. |
| Full correction, no generic +/-2 EV clamp | EttrLoop -> RawEttrMeter.solve | Keep; sensor ranges, ISO ceiling and gyro shutter ceiling can bind. Convergence tolerance controls cadence only. |
| Canon dual-ISO DR combination, black-offset correction, linked recovery ISO, lens register stepping | Not ported | Named exclusion: this is Camera2 single-ISO ETTR; RawLens dual captures are not ML alternating-row dual ISO. No Canon registers or recovery-ISO table apply. |
| Canon LiveView/non-LV polling, shutter limit and ISO adjustment | RAW repeat sample baseline + shutter-first/ISO-second solver, gyro safety | Intentional platform integration. Cadence/safety behavior retained; not an ML timing port. |
| Owner: min channels retain slope when G clips | New minChannelHistogram over min(R,Gr,Gb,B); Memory.diffFromMin | Extension beyond ML, which switches projections but does not use minimum CFA. On a good frame, anchor MAX highlight to each MIN lower percentile. On a clipped frame use MIN lower values plus remembered offsets. MAX still controls target/clipping and SNR; MIN cannot silently change the exposure target. |
| Develop compensation (A6 closed 2026-10-26) | AdaptiveDevelopmentExposure.analyzeSamples common display rule; ETTR-only residual cap and save parameter removed | ML ETTR concerns capture, not JPEG placement. Following darktable's scene-referred separation, use the shared middle-grey/display highlight rules. Paired AUTO/PROGRAM/ETTR window scene: fixed ETTR within 0.11 EV of PROGRAM / 0.14 EV of AUTO; original RAW hashes unchanged; highlight detail retained. Existing adaptive Off/strength controls remain. [A6 source audit and evidence](ettr-development-audit.md). |

MIN channels use the already black/white-normalized sensor values, without WB.
Offsets anchor their scale to the MAX highlight in the same good frame. This
requires stable scene colour/content between samples, just like ML's temporal
percentile extrapolation. Without good-frame history the ML fallback remains;
if every channel clips, no gradient can recover unobserved detail. MIN may
approach the noise floor in strongly coloured scenes; this change does not
claim reconstruction of arbitrary clipped scenes. Memory.clear resets both
histories on ETTR reset.

## Verification and acceptance evidence

**67 JUnit tests passed**, zero failures/errors: FocusPeakingTest (including
recording crop/rotation geometry), MagicLanternEttrTest, RawEttrMeterTest,
RawEttrSamplerTest, EttrLoopTest, VideoManualPolicyTest and
FrameLeaseRegistryTest. Analytic sharp-step, dim-edge, smooth-ramp and blur
fixtures give known focus ground truth; the live scene alone cannot label
individual detections as false positives.

Halide host check: **119 cases passed**, generated output bitwise identical
to the scalar reference. Android archives were regenerated for arm64-v8a,
armeabi-v7a, x86_64 and x86; the app packages the two ARM ABIs. Both ARM
variants compile/link in the release build. Only arm64 was executed on the
connected phone.

The device instrumentation build uses release native optimisation with R8
disabled, as required by app/build.gradle.kts. The minified release gets a
separate UI smoke. An attempted instrumentation attachment to R8 failed in
the test runner before application startup because its Kotlin runtime names
were removed; that attempt is not counted as a product regression or a test
pass. The unsupported black-box test was removed.

### RAW transfer and ETTR

- 18 current-build RAW frames: ISO 50 / 3200, shutter 1 / 10 / 100 ms,
  focus 0 / 5 / 10 diopters, full sensor 4080x3060. Every native/Kotlin
  scope/ETTR/focus-mask comparison passed. The pre-F15 quad sampler is an
  independent retained timing/output oracle in MlFocusEttrDeviceTest:
  MAX histogram and overexposure count match on every frame.
- 2,324 sampled live quads had at least one green at sensor white while R
  and B were each below 99% of white. This is a **local** clipping count;
  other parts of those frames can clip other channels. No claim that the
  entire frame has only green clipping. The earlier 2026-10-25 run also
  recorded 1,169 such quads. Daylight sky/foliage was not staged.
- On-device controlled fixture: G four stops above R/B, exposure +4 EV,
  MAX histogram clipped while MIN retains slope: correction -2.484375 EV
  versus analytic -2.5 EV (within 1/64 EV quantisation plus percentile
  selection). Memory reset and ordinary ML fallback remain covered.
- Median of per-frame sampler medians: new quad sampler **2.476 ms**, old
  **1.864 ms** in the 2026-10-26 run. This is an added ~0.61 ms, not exact
  speed parity. MIN histogram payload adds 4,100 bytes per ETTR sample.
  Production Kotlin peaking median was **10.50 ms** over these frames.
- The fused Halide diagnostic took ~1.77 seconds per full sample on this
  phone. It is **not wired into the production photo/record loops**; no
  claim that this diagnostic is faster. The peaking ratio fix does not
  enable it. Enabling the fused sampler would require a separate schedule
  performance task.

RAW measurements: [current sweep](results/f14-f15-2026-10-26/raw-sweep.txt),
[earlier sweep](results/f14-f15-2026-10-25/raw-sweep.txt).

### Recording peaking

Recording checks use the full 4080x3060 RAW source, then exercise the
available Direct-Log encode resolutions. They pull MF through 0 / 0.5 / 5
diopters, require new mask callbacks after each change, switch to AF and
require a clear with no stale re-publication, return to MF, stop and assert
zero remaining RAW leases. Baseline and peaking-enabled frame rates are
measured in consecutive phases of the same take, not inferred from render
settings. Short-window rate differences are not speedup claims.

| Requested encoder config | Baseline graded fps | Peaking graded fps | Recording drops / frame errors | Peaking median interval | Leases after stop |
|---|---:|---:|---|---:|---:|
| 1920x1080 @ 24 | 23.98 | 24.25 | 0 / 0 | 284 ms | 0 |
| 2560x1440 @ 30 | 29.91 | 30.09 | 0 / 0 | 268 ms | 0 |
| 3840x2160 @ 30 | 28.44 | 28.28 | 0 / 0 | 272 ms | 0 |

The sensor/compute crop is 3840x2160 at (120,450); the internal demosaic
export remains 1920x1080 before encoder-size scaling. Report `exportSize`
is this internal resolution, not proof of the container's encoded dimensions.
These are requested encoder configurations exercised through the real
recorder, not an independent codec-resolution certification. The 4K run
reported 95 over-budget camera cycles across the complete take; zero
recording drops does not imply a sustained 30 fps guarantee.

[1080p evidence](results/f14-f15-2026-10-26/direct-log-1080p24.txt),
[1440p evidence](results/f14-f15-2026-10-26/direct-log-1440p30.txt),
[4K evidence](results/f14-f15-2026-10-26/direct-log-4k30.txt).

The 1080p scene produced four detections at 0.5 diopters and zero at 5
in the recorded pull. This is a scene-dependent defocus observation, not a
universal threshold calibration. High-ISO coverage in the RAW sweep is
bounded by the measured mask counts; the fixed gate remains an explicitly
uncalibrated noise heuristic.

Recording sampler ownership follows the existing RawImageOwnership lease
registry and the platform's [Image lifetime contract](https://developer.android.com/reference/android/media/Image#close())
and [ImageReader acquisition limits](https://developer.android.com/reference/android/media/ImageReader#acquireNextImage()).
The single outstanding task is drained using [ExecutorService shutdown / awaitTermination](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ExecutorService.html).

CRS RAW video is an explicit unavailable-path exclusion: CrsWriter.available
is hardcoded false because its backend has not been implemented in this tree.
The CRS recording probe reports a JUnit assumption skip, not a pass. Its
peaking integration compiles but cannot be claimed device-validated.

### Minified release UI smoke

Release **1.0.425**, arm64, installed and launched through ADB normal UI
controls. Manual 0.9 m focus showed green dots on window detail; the 0.1 m
near endpoint visibly blurred the scene and retired the marks. AF reset
cleared peaking. ETTR on/off controls remained functional with a live RAW
preview. Private scene screenshots were inspected locally, not committed.
[Smoke receipt and APK hash](results/f14-f15-2026-10-26/release-smoke.txt).

### Scope of closure

F14/F15 close the pinned source audit, documented intentional differences,
implementation fixes, analytic regressions and connected-device acceptance.
Other-device, other-ABI and packed-RAW hardware acceptance remains part of
E22; no broader parity certificate is implied. A6's matched ETTR/AUTO/PROGRAM
JPEG brightness gate is separate and remains open. The F15 develop-compensation
row above records the current residual cap without claiming an A6 fix.

HDR+, HDR bracket merge, MFSR, AMaZE and highlight reconstruction were not
re-certified by this work; their checklist status is unchanged by F14/F15.


Final follow-up: release 1.0.434 carries the F14/F15 changes and the owner's
Off/On-only flash policy for PROGRAM/ETTR. Its six-path flash probe passed,
including AE_OFF preservation for those modes; 91 combined focused unit tests
passed. See [flash follow-up](flash-study.md). F14/F15 remain closed.
