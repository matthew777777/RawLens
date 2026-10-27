# H1 / H2: highlight metering and Active D-Lighting

**H1 and H2 closed 2026-10-26.** Source/host checks, ADB GPU/RAW/JPEG tests
and the final stationary bright-window comparison pass on Xiaomi 25080RABDG,
Android 16, camera 0, RAW 4080x3060, deviceTest build 1.0.424 (versionCode 511).
Existing unrelated workspace edits remain. Closure covers the implemented
behaviour and this acceptance set; the release-wide E22 device/performance
matrix remains separate.

## Sources and implementation boundary

* Nikon, [D810 Highlight-weighted Metering technical guide](https://nps.nikonimaging.com/technical_info/technical_solutions/d810_tips/highlight/):
  prioritize highlights, include their colour, and combine with Active
  D-Lighting to brighten shadows. Nikon explicitly warns that bright lights
  or backgrounds can darken the subject. H1 cannot promise to identify a
  subject semantically or protect every individual clipped pixel.
* Nikon, [D7500 Active D-Lighting](https://onlinemanual.nikonimglib.com/d7500/en/17_image_enhancement_02.html):
  exposure is adjusted before shooting; distinguish that from a shadow-only
  retouch operation.
* Nikon, [D780 Active D-Lighting options](https://onlinemanual.nikonimglib.com/d780/en/06_shooting_settings_10.html):
  Off, Low, Normal, High and Extra High, with high-contrast window and shaded
  subject examples. Auto is deliberately outside H2's requested five levels.
* Existing pinned source audits: [PROGRAM/libcamera](exposure-audit.md),
  [ETTR/Magic Lantern](ml-focus-ettr-audit.md). H1's 0.8 highlight placement
  matches the libcamera imx477 highlight constraint already represented in
  `ProgramAgc.ConstraintMode.HIGHLIGHT`.
* Existing AgX reference: Google Filament `filament/src/ToneMapper.cpp`,
  commit `2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad`, pinned in
  `AgxDisplayTransform`. The AgX implementation and shaders are retained;
  H2 changes their existing shadow-range parameter.

Nikon does not publish reusable source, the H1 weighting equation, or the
H2 EV calibration. The formula, exponent and level values below are RawLens
engineering choices. They are **not claimed as Nikon's algorithm or a measured
Nikon match**. No Nikon profiles, LUTs or code are bundled; no new third-party
licence entry is needed for this behaviour-only implementation.

## H1 rule and wiring

`ProgramMetering.HIGHLIGHT_WEIGHTED` is appended to the persisted enum; old
names/defaults and the MEDIAN migration remain intact. The PROGRAM panel,
settings and quick metering cycle expose it as HIGHLIGHT, after SPOT.

`RawProgramMeter.highlightBrightness` uses black/white-normalized histograms.
For each CFA channel, bin-centre brightness x receives weight `count * x²`;
its weighted mean is `sum(count*x³) / sum(count*x²)`. The largest channel
statistic wins. Count is sampled area; the square makes bright regions
outvote dark background without making one isolated pixel an absolute veto.
Taking the maximum over colours protects red/blue highlights that a green-only
statistic misses. This is sensor-channel protection, without white-balance
multiplication that would introduce a second colour-dependent exposure gain.

H1's subject-placement gain remains the existing full-frame average AGC gain.
It is capped at `0.8 / highlightBrightness`. The highlight statistic is not
fed into the AGC's 0.16 grey target; doing that would render highlights at grey.
The shared `protectedGain` applies the ceiling once and applies H2's bias once.
Histogram bins are full-frame even if a caller supplies cropped metering bins.
Both Kotlin and native request geometry scan the full frame for H1, with no
redundant cropped-region guard scan. The per-sample statistic costs 4 x 256
bins, no sort, new image buffer, shader or processing pass.

A tiny specular may still clip: this is area-weighted metering, not maximum-
pixel ETTR. A large bright background may still darken the foreground, as
Nikon documents. Neither tradeoff is a guarantee of zero clipped pixels.

## H2 rule and wiring

One `ActiveDLighting` enum and one per-lens preference, `active_d_lighting`,
shared by live capture, offline development and JPEG/JXL look settings.
Control: Settings -> Photo -> Active D-Lighting. Missing/unknown values are
Off. Settings backup already preserves keys in the preference store.

| Strength | PROGRAM bias b (EV) | ETTR minimum headroom | Default AgX shadow range |
| --- | ---: | ---: | ---: |
| Off | 0 | existing user target | 10 |
| Low | -0.25 | 0.75 | 10.6346 |
| Normal | -0.50 | 1.00 | 11.2692 |
| High | -0.75 | 1.25 | 11.9038 |
| Extra High | -1.00 | 1.50 | 12.5385 |

These are RawLens tuning values accepted against the GPU neutral ramp and
the stationary high-DR window scene below, not a measured Nikon camera match.
PROGRAM computes `min(subjectGain, 0.8/highlight) * 2^-b`. H1+H2 use this
same expression, never two independent ceiling reductions. Existing sensor
bounds, locks, handheld curve, flicker rounding and temporal filter remain.
ETTR uses `max(userHeadroom, 0.5+b)` as one absolute ML highlight target;
a user's already-safer target wins. PROGRAM is bypassed when ETTR is active.
Changing the level clears the old converged ETTR pair and PROGRAM target.
At H2 closure the effective ETTR target was also carried into a single-frame
develop cap. A6 subsequently removed that capture/develop coupling; see
[the A6 audit](ettr-development-audit.md) for the current display rule.

The look expands AgX's shadow log range S, retaining highlight range H:
`S' = S + b*(S+H)/H`. This follows directly from solving
`(S'-b)/(S'+H) = S/(S+H)` at middle grey. Thus a b-stop capture reduction
preserves grey placement while lifting lower values more than highlights.
It reuses the existing AgX contrast/gamut/shoulder machinery. Effective S is
computed separately from stored S, preventing repeated settings resolution
from compounding the effect. Extreme user ranges cap effective S at 18 EV;
exact grey preservation is not promised at that cap or with altered contrast.

Adaptive development resolves its middle-grey target by inverting the new
look at the original look's grey code. Otherwise it could restore the
capture reduction and then apply H2's lift a second time. Off retains the
original 0.18 target. Existing spike/broad highlight guards remain active.

CPU golden, scopes, BGU look parameters, GLES preview, JPEG, JXL and fused
AMaZE output consume the same effective shadow range. RAW pixels/DNG writer
logic are unchanged; the requested sensor exposure intentionally changes.
MANUAL/HAL-only fallback modes receive the look but no new sensor override.
HDR+/MFSR/AMaZE/HL algorithms were not audited or rewritten by H1/H2.
No per-maker copies of DRO/DR/i.Dynamic/HTP were added.

Limits to verify: manual-axis locks or sensor limits can prevent the intended
capture bias; an already-safer ETTR user target need not receive another full
b stops. In those cases the look still applies. Preview under hardware AE
cannot promise to show the ETTR-only still exposure before capture. These
limits remain part of the contract; the dark-scene device run exercised the
sensor-limit case. All-device parity is not inferred from one phone.

## Verification and remaining acceptance

`HighlightLightingTest` covers bright subject vs isolated spike, R/B colour
highlights, full-frame guard geometry, centre/spot failure fixtures, shared
H1/H2 ceiling, monotonic strength, actual ETTR pair strength, grey restoration,
shadow lift, highlight retention, idempotent settings and adaptive-double-lift
prevention. Existing exposure, AgX, output settings and sampler regressions
are included in the focused host run. Final result: **112 tests across 12
classes, zero failures/errors/skips**; `:app:compileDeviceTestAndroidTestKotlin`
also passes. Build log: `/tmp/rawlens-h12-final-host.log`. `git diff --check`
passes.

`HighlightLightingDeviceTest` passes both tests on the phone. The GPU neutral
ramp preserves middle grey (initial logged result: code 128 at every strength);
at input 0.01 after the corresponding capture bias, the shadow codes rise
18 / 20 / 23 / 25 / 27. Native/Kotlin H1 RAW transfer agrees at three exposure
times. The expanded run applies actual PROGRAM/ETTR solver pairs to the sensor
and successfully develops twelve full-resolution JPEGs (CENTER, SPOT, five
HIGHLIGHT/H2 levels, five ETTR/H2 levels). The dark-scene run takes 68.901 s;
the subsequent moving-scene run takes 74 s. Both report OK (2 tests).

The device run found an existing CFA sampling bug: an even pixel stride
visits just one Bayer phase. At this RAW size SPOT selected stride 4 and its
Kotlin sampler returned null. `meterCfaGridStep` now rounds pixel-grid strides
to the next odd number for Kotlin RAW scans and native ETTR/guard parameters.
Quad-grid scope and ETTR projection strides retain the original formula.
No native binary rebuild or extra image processing pass is needed. A host
regression verifies all four Bayer phases in spot/full/guard grid examples.
After the fix the full SPOT capture/develop succeeds.

The first completed expanded comparison was too dark: every case hit ISO
6400 and 66,666,666 ns, with zero clipping. It demonstrates the sensor-limit
path and progressively stronger shadow lift, not highlight protection.
The owner then prepared a bright monitor scene, but the phone moved during
the run: CENTER's image shows the monitor; later images show a hand and then
darkness. Its changing exposure/clipping statistics are excluded from scene
acceptance. No grey-card calibration or comparable bright-scene result is
claimed from either run. The owner requested another pause before the next
stationary comparison.

Evidence: [host results](results/h1-h2-2026-10-25/host-tests.txt),
[passing dark-scene instrumentation](results/h1-h2-2026-10-25/dark-scene-instrumentation.txt),
[excluded moving-scene measurements](results/h1-h2-2026-10-25/moving-scene-excluded.txt).
Dark-scene JPEGs: `/tmp/rawlens-h12-final/`; prepared-scene baseline:
`/tmp/rawlens-h12-prepared-center.jpg`. Large comparison JPEGs are not bundled
in the repository.

## Final stationary-scene acceptance, 2026-10-26

The owner supplied a stationary bright window with a dark interior and bedding.
All twelve JPEGs were captured, retrieved and the baseline/H1 Extra High/ETTR
Extra High images visually inspected; their framing remains comparable.
Instrumentation passes **2 tests in 75.805 s**. The 112 host regressions pass.

| Mode / strength | ISO at 66,666,666 ns | Worst CFA clipped area | JPEG median code |
| --- | ---: | ---: | ---: |
| CENTER | 2051 | 1.7313% | 23 |
| SPOT | 1618 | 1.0388% | 21 |
| HIGHLIGHT / Off | 1409 | 1.0388% | 20 |
| HIGHLIGHT / Low | 1010 | 0.4386% | 23 |
| HIGHLIGHT / Normal | 841 | 0.0923% | 26 |
| HIGHLIGHT / High | 568 | 0% | 29 |
| HIGHLIGHT / Extra High | 539 | 0% | 36 |
| ETTR / Off | 608 | 0.0231% | 6 |
| ETTR / Low | 505 | 0.0231% | 8 |
| ETTR / Normal | 411 | 0% | 10 |
| ETTR / High | 349 | 0% | 13 |
| ETTR / Extra High | 290 | 0% | 16 |

These are sampled CFA clipped fractions, not a census of every sensor pixel.
H1 reduces the worst clipped-channel area by 40% relative to CENTER while
retaining the bright window. H2 steadily reduces sensor exposure while lifting
the developed interior; High/Extra High have no sampled clipping in PROGRAM,
and Normal through Extra High have none in ETTR. The same H1/H2 ceiling is
exercised throughout. H1's isolated small highlight/spot-overdarkening cases
are covered by the synthetic fixtures; the physical scene covers the window.

Grey placement is verified with the GPU neutral ramp (2-code acceptance;
initial measured code 128 at every strength) and host grey/adaptive-meter
fixtures. This run did **not** contain a calibrated physical grey card; no
physical-card 0.15-EV measurement or Nikon camera colourimetry is claimed.
These historical H2-run ETTR JPEGs remained darker than PROGRAM. A6 was
subsequently fixed and closed with a separate paired-image audit; these
measurements predate that change.

Evidence: [instrumentation](results/h1-h2-2026-10-26/instrumentation.txt),
[raw capture report](results/h1-h2-2026-10-26/window-report.txt),
[acceptance assertions and scope](results/h1-h2-2026-10-26/verification.txt),
[JPEG hashes](results/h1-h2-2026-10-26/jpeg-sha256.txt).
Full JPEGs remain at `/tmp/rawlens-h12-oct26-window/`, not in the repository.

Remaining release-wide validation belongs to E22: multiple devices, whole-
preview timing against the original build, manual-lock/HAL-only fallback
coverage, JXL file encoding and the minified release smoke. The new look uses
the existing common JPEG/JXL renderer parameters; this camera run develops
JPEGs only. No extra image pass or allocation was added to the metering loop;
the initial histogram calculation measured approximately 3.3–5.7 microseconds
per call, which does not establish whole-preview performance parity.
