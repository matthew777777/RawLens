# F1/F2 historical handoff — resumed 2026-10-26

The owner subsequently requested continuation. The paused snapshot below is
historical; its UI/default descriptions are superseded by the
[current implementation and verification audit](raw-exposure-assist-audit.md).
F1/F2 were closed after recording acceptance on 2026-10-26; see the current audit
for results and the explicit existing CRS-backend exclusion.

## Owner requirements

- Implement F1 RAW zebras/false colour and F2 RAW spot meter by **reusing or
  porting open-source code**, rather than an independently invented mimic.
- Latest UI direction: “raw spot meter should say the text on the top on tab
  nice somewhere maybe below res”. Put the numeric reading in the **top HUD,
  below the resolution/sensor label**. Keep only the measuring box on the image.
- This top-HUD relocation is **not implemented yet**. The current working code
  still paints the spot EV text beneath the spot box. Fix this first on resume.

## Implemented so far (uncommitted)

New production files:

- `MagicLanternRawExposure.kt`: Kotlin port of RAW EV conversion and zebra
  clipping-mask colour classification. Retains upstream copyright and
  GPL-2.0-or-later SPDX header.
- `TurboColormap.kt`: Apache-2.0 Google Turbo polynomial, converted to an
  ARGB8 lookup table. Retains Google copyright/source revision.
- `RawExposureAssist.kt`: independent overlay/spot switches, sparse RAW quad
  sampling, complete small-ROI spot mean, per-phase black/white calibration,
  RAW_SENSOR plus packed RAW10/12 buffer adapter, spot rotation/mirror inverse,
  one-frame borrowed-image worker at 250 ms cadence, epoch invalidation.
- `RawExposureOverlayView.kt`: transparent Android overlay, RAW zebra or
  false-colour bitmap, measuring box, EV text (currently still on image),
  recording crop mapping and 1.5-second stale-data visual timeout.

Integration edits:

- `RawCameraController`: callback, worker and settings API; samples existing
  adopted RAW frames; requests the existing scope stream when assists need
  it even with histogram Off; clears on stop and closes worker on destroy.
- `RawVideoRecorder` / `DirectLogRecorder`: RAW borrowed-frame callbacks and
  worker teardown. Default Off. MainActivity transfers settings to both.
- `MainActivity`: overlay initialization, geometry, callbacks, preference
  persistence, recording transitions and shared settings updater.
- `SettingsGeneralTab`: Viewfinder controls (Off / RAW zebras / RAW false
  colour), independent RAW spot-meter checkbox, concise explanation.
- `activity_main.xml`: assist overlay above the preview and below peaking.
- `FocusPeakingGeometry.monitorRects`: generalized overload to reuse the
  existing proven recording crop/rotation/mirror geometry; old signature
  delegates and its existing tests still pass.
- `NOTICE.md`, `SettingsAboutTab`, bundled `assets/licenses/`: attribution,
  GPLv2 and Apache-2.0 texts, RAW assist open-source notices dialog.

The feature is Off by default. It does not change capture exposure, develop
settings, saved image/video pixels or the processed histogram/waveform.

## Sources already read and pinned

Magic Lantern repository: `reticulatedpines/magiclantern_simplified`
commit **142472e95054d73640f50789ac0fd66325499d82**.
Fetched using the GitHub connector (public files, no repository mutations).

- `src/zebra.c:622–685`, `802–857`: RAW zebra sampling / white and under floor.
- `src/zebra.c:923–940`: `zebra_rgb_solid_color`, ported switch and under priority.
- `src/zebra.c:2452–2484`: RAW spot-region arithmetic mean, then RAW-to-EV.
- `src/zebra.c:2531–2547`: one-decimal EV display.
- `src/raw.c:1769–1807`: `raw_to_ev`, with Canon ExpSim-specific branch.
- `src/raw.c:1809–1813`: `ev_to_raw`.
- `src/raw.c:2374–2380`: ten-stop log preview window, adapted for false colour.
- `src/bmp.h`: names for clipping colours (red/green/blue, combinations,
  black for all clipped, almost-white for under).
- Both zebra.c and raw.c explicitly permit **GPL v2 or any later version**.
  Whole app remains GPL-3.0-or-later. Copyright 2009 Trammell Hudson and
  2013 Magic Lantern Team retained.
- `src/falsecolor.c` was examined: its ordinary false colour is **YUV luma**,
  not RAW EV. That implementation is not claimed as RAW false-colour parity
  and its palette was not copied. Do not label the new RAW palette as ML's
  Marshall/SmallHD or as IRE/skin-tone calibration.

Google Turbo source: Anton Mikhailov / Ruofei Du,
https://gist.github.com/mikhailov-work/0d177465a8151eb6ede1768d51d476c7
revision **f0b77bf03aa5fce5a3ebe25988fef36ec43f5096**,
Copyright 2019 Google LLC., **Apache-2.0**. The source was viewed directly;
its raw URL had a web-cache miss. Coefficients and SPDX were available on
that revision's gist page. Turbo is fed ML-style RAW EV -10..0, not YUV.

Original ML function excerpts retained in
`docs/refactor/upstream/magiclantern-raw-exposure-reference.c` (not compiled).
Licence texts/notices retained in `app/src/main/assets/licenses/`.
The production comments currently refer to
`docs/refactor/raw-exposure-assist-audit.md`, which **has not been written yet**.
Create that audit with a source -> port -> adaptation table on resume.

## Intentional platform adaptations to document

- Four individually black/white-normalized CFA phases; both green phases
  participate, clipping uses the hotter green. No WB, lens-shading gain,
  AgX, JPEG gain or LOG grade influences metering.
- Android code at white counts as clipped (`>=`), whereas ML source uses `>`.
- Spot averages normalized sample values before log2. It keeps fractional
  precision; ML averages integer codes and truncates before log2. Include
  this difference rather than claiming bit-exact Canon-code results.
- RAW spot is mean EV plus separate clipped R/G/B flags; a channel can clip
  even while the mean is below 0 EV. Box follows photo AE target; recording
  currently uses centre (shown by the box).
- Under uses ML's negative-DR rule only with a timestamp-matched Camera2
  noise profile. Without one, mark black level, not a fabricated DR floor.
  Physical capture-result black/white/noise keys are preferred when available;
  stale results fall back to static characteristics. Canon ExpSim / dual ISO
  handling is omitted because it does not apply to these Camera2 frames.
- Sparse 128-column quad grid, rows from aspect with a 128-row cap. This is
  sampled clipping, not a census of every sensor pixel. Zebra hatching is
  generated over this grid. Colours are Android ARGB adaptations of ML names.
- Worker borrows at most one image, queues no frames and releases in finally;
  disabled settings do not sample or borrow. Epoch and elapsed-time barriers
  reject old configuration/session samples; stale display becomes waiting.

## Verification completed

Latest command:

`./gradlew :app:testDebugUnitTest --tests 'com.matthew.rawlens.RawExposureAssistTest' --tests 'com.matthew.rawlens.RawExposureImageTest' --tests 'com.matthew.rawlens.FocusPeakingTest' --tests 'com.matthew.rawlens.LiveScopesTest' --tests 'com.matthew.rawlens.RawStreamPlanTest' :app:compileDeviceTestAndroidTestKotlin --console=plain`

Log: `/tmp/rawlens-f12-tests.log`: **BUILD SUCCESSFUL**, 46 tests, zero
failures/errors/skips (11 port/grid tests, 3 buffer tests, 22 peaking geometry
regressions, 4 LiveScopes, 6 RawStreamPlan). Debug Kotlin/resources also compile.

Tests cover all eight clipping colours, under priority, EV anchors and black
floor, four CFA patterns, phase black levels, one-green clipping, mean-before-
log, noise-floor fallback, tap inverse geometry, Turbo anchors, Off/spot-only,
invalid calibration, packed RAW10 vs padded/nonzero-position RAW_SENSOR,
calibration effects and truncated/unknown buffers.

A failed first attempt mocked CameraCharacteristics in host tests; Android's
stub static keys are null, so mocks collided. That test was correctly replaced
with the platform-free `sampleBuffer` tests rather than modifying framework
static keys. No production failure was hidden. A Turbo test anchor was also
corrected to the rounded upstream polynomial value (green endpoint 13).

New `RawExposureAssistDeviceTest.kt` **compiles but has NOT run**:

1. Android Canvas raw-ramp render through 90-degree/front-mirror geometry,
   saved synthetic overlay PNGs and clear/waiting behaviour.
2. Real RAW six-case ISO/shutter sweep, independent full-ROI EV oracle,
   input SHA-256 immutability, sampler timing and actual borrow/release worker.

**No F1/F2 APK has been installed or run on the phone. No camera tests,
recording performance tests, real top-HUD screenshots or minified checks yet.**
Do not mark F1/F2 complete based on compilation/host fixtures.

## Known fix to make first on resume

`RawVideoRecorder.kt` new lookup currently reads
`captureResults[img.timestamp]` inside `synchronized(captureResults)`.
The existing writer protects this TreeMap with **resultLock**. Change the new
read to `synchronized(resultLock)` before any device/recording tests; the
current lock mismatch is a real race in the unfinished integration.

## Remaining work / resume sequence

1. Implement the owner's top-HUD request. Add a compact TextView/pill beneath
   the resolution row inside `rawStatusGroup` in `activity_main.xml`.
   The resolution/sensor label is `sensorInfo`, beside `dngInfo` and `rawBadge`.
   Top bar is shared with video in the current code (ignore stale XML comment).
   Prefer mono 11sp, accent text, subtle rounded background, right alignment,
   content `RAW SPOT  −3.2 EV`; append clipped channel flags and distinguish
   black floor. Hide when disabled; show a dash/waiting when data is stale.
2. Remove the EV text from `RawExposureOverlayView.onDraw`; retain the spot
   box. Provide an update/clear/stale callback to MainActivity for the HUD.
   Do not call TextView layout changes from onDraw. Use a delayed invalidation/
   callback with generation checks so old updates cannot restore a stale EV.
   Currently the overlay visually times out but its numeric accessibility
   contentDescription can remain old: fix that with the HUD/stale change.
   Add the new top label to fixed-orientation handling if appropriate.
3. Fix the RawVideoRecorder resultLock mismatch noted above. Review worker
   close/reconfigure, lens/record transitions and no-RAW fallback after the
   HUD change. Preserve all unrelated existing edits.
4. Finish device coverage: run compiled Canvas/oracle test; add real MainActivity
   cycle/tap/persistence/HUD placement screenshot test. Verify zebras vs false
   colour, independent spot switch and stale/off behaviour at both VF routes.
5. Add short baseline-vs-enabled DirectLog / RAW recording tests. Existing
   `MlRecordingPeakingDeviceTest.kt` is a useful lifecycle/performance template;
   new workers use the same image lease contract. Test source closure/callbacks
   on stop and Off and verify no leaked `RawImageOwnership` entries. Record
   throughput/drop evidence; do not infer whole-app parity from sampler timing.
6. Run focused host suite and build deviceTest app/test APKs. Read the freshly
   generated output-metadata.json for the APK name: Gradle auto-increments
   version.properties, so do not reuse an earlier filename. ADB tests opt-in
   `-e rawlensF12Audit true`, class `com.matthew.rawlens.RawExposureAssistDeviceTest`.
   Grant CAMERA; audio only if a chosen recording test needs it. Ask for a
   suitable stationary scene only if needed, with clear run duration.
7. Write `raw-exposure-assist-audit.md`, preserve source/licence attribution,
   record genuine test results under docs/refactor/results, and then decide
   whether F1/F2 acceptance actually passes. F1/F2 are still unchecked.

Other work: H1/H2 and A6 are already closed with their own earlier evidence.
F14/F15 have also since been closed in the shared checklist by other work.
Do not revert those changes or treat all dirty files as this task's changes.
No subagents were used. No commits, external publications or messages sent.
