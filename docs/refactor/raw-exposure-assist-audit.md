# RAW exposure assists — F1/F2

Closed 2026-10-26 after host, real-RAW, rendering, recording and live-monitor
checks. CRS recording is an explicit existing unavailable-backend exclusion;
its rejection and cleanup were tested, not its unimplemented encoder.

## Source and adaptation

Magic Lantern source is pinned to
`reticulatedpines/magiclantern_simplified@142472e95054d73640f50789ac0fd66325499d82`.
Retained source excerpts are in `upstream/magiclantern-raw-exposure-reference.c`.

| Source | RawLens implementation and adaptation |
| --- | --- |
| `src/raw.c:1769–1813`, RAW EV conversion | `MagicLanternRawExposure`: black-subtracted, white-normalized log2 with one-code floor. Camera2 per-phase black/white calibration replaces Canon calibration; Canon ExpSim workaround is omitted. |
| `src/zebra.c:622–685,802–857,923–940`, RAW zebra classification | `RawExposureAssistSampler`: per-channel clipping and green under-floor classification, preserving the ported mask switch. Renderer uses mask coverage with black/white diagonal bands, as requested by the owner’s Sony-camera reference. |
| `src/zebra.c:2452–2484,2531–2547`, RAW spot meter | Arithmetic mean of the complete small RAW ROI before logarithm; one decimal EV relative to clipping. Android rotation/mirroring maps the photo AE target into sensor coordinates. |
| `src/raw.c:2374–2380`, ten-stop RAW preview window | Hottest CFA channel maps into a ten-stop RAW EV display window for false colour. This is an adaptation, not ML’s YUV false-colour implementation or an IRE/skin-tone scale. |
| Google Turbo polynomial, revision `f0b77bf03aa5fce5a3ebe25988fef36ec43f5096` | `TurboColormap`: polynomial sampled into a 256-entry ARGB table. [Original source](https://gist.github.com/mikhailov-work/0d177465a8151eb6ede1768d51d476c7). |

Magic Lantern copyright and GPL-2.0-or-later attribution and Google Turbo
Apache-2.0 attribution are retained in source, root NOTICE, bundled licences
and Settings → About → RAW assist open-source notices.

## Display and ownership

The compact round button beside the bottom AUTO/GPU/CPU viewfinder switch
cycles Off → Zebra → False colour → Off. Its visible 40 dp circle retains a
48×48 dp touch target, with 8 dp between the two controls. The lens row
and original header dimensions are unchanged.
The independent spot meter defaults on; its switch stays in Settings.
An optional Average mode has been added; see the
[extension audit and pending device checks](raw-average-meter-audit.md).
Its compact numeric reading shares the AUTO/status row in the top HUD, with
only the measuring box over the scene. It adds no header row. The CRS/LOG
badge is explicitly single-line.

False colour uses a 512-column aspect-preserving RAW grid with filtered bitmap
rendering: continuous image-shaped colours rather than enlarged cell tiles.
Zebra classification uses 256 columns; the renderer masks constant screen-space
45-degree black/white bands into affected areas. Stripe width does not depend
on RAW resolution or the sampling-grid size. Outside the mask the preview stays
visible. These are sampled exposure assists, not an exhaustive per-pixel census.

Matching-timestamp CaptureResult dynamic black/white/noise metadata is preferred;
static black/white values are the fallback. Under-warning uses an available
noise-profile floor; otherwise only black is marked. Exposure classification
does not use processed preview brightness, white balance, AgX or LOG gain.

Photo and recorder integrations borrow existing RAW frames on one worker with
at most one outstanding borrow, without a RAW-frame queue. Overlay cadence is
125 ms; spot-only cadence is 250 ms. Configuration/session epochs discard old
results. Display/readout expires after 1.5 seconds. Saved RAW data is not modified.

## Verification

- Focused host suite passed: RAW EV, clipping masks, four CFA patterns,
  phase calibration, mean-before-log, packed RAW10/12, invalid buffers,
  geometry, preferences, text formatting and existing scope/stream tests.
- Device build and matching test APK installed on Xiaomi 25080RABDG / Android 16.
- Real camera sweep: six 4080×3060 RAW frames across ISO 50/3200 and
  1/1000, 1/62.5 and 1/20 second. Spot agreement with independent RAW ROI
  oracle was within 0.001 EV; source buffer SHA-256 hashes were unchanged.
  Zebra sampler median times were 7.6–12.3 ms in this run.
- Device rendering test passed rotation/mirroring, known RAW-to-Turbo pixels
  and clearing. Main-screen test passed the three-mode cycle, default-on spot,
  top HUD placement, photo AE-target tracking and fully disabled clearing.
- Actual phone false-colour and zebra screenshots were inspected. The scene
  remained recognizable in false colour. Few scene pixels clipped; a separate
  controlled mask test covers the requested full stripe pattern.

The final black/white stripe test passed: opaque alternating bands follow
the diagonal, repeat in screen space and leave unaffected areas transparent.
The compact header test passed photo/LOG/CRS spot-on versus spot-off bounds
and single-line badge checks. Startup uses the resumed activity lifecycle
rather than waiting for the continuously updating preview to become idle.
Full enter/exit-video transitions also passed, including the video HUD and
record-ready controls. Screenshots for both video formats were inspected;
CRS and LOG remain horizontal. The lens row and bottom panel retain their
original geometry; the small assist button sits beside the bottom viewfinder-engine switch. See [layout measurements](results/f1-f2-compact-ui-2026-10-26.txt).

## Recording acceptance and performance work

`RawExposureRecordingDeviceTest` exercises Off → spot-only → zebras → false
colour → Off, three seconds per measured phase after startup/drain grace.
It checks fresh callbacks, centre spot geometry, crop projection, mode payload,
no publications while disabled, no post-stop callbacks and zero RAW leases after
stop. Its comparison allows at most 5% or 1 fps below the lower surrounding
baseline and at most one extra dropped frame; final accepted runs had zero
recording drops and zero frame errors.

Initial 4K HLG runs exposed the recorder's 100 ms GPU fence timeout. A control
with every assist disabled throughout reproduced it. The initial failed runner
and assisted report are retained with the evidence; they were not counted as
passes. Two performance changes were then validated:

- The fused `vf_mhcyuv` shader now assigns one invocation per horizontal P010
  pair and dispatches half as many X invocations. Previously every odd-X
  invocation immediately returned. Pixel coordinates, MHC/grade arithmetic,
  white protection and packing remain unchanged, including the fp16 variant.
  This preserves existing ACES additions in the shared worktree. Dispatch
  mapping follows [Khronos dispatch semantics](https://docs.vulkan.org/spec/latest/chapters/dispatch.html)
  and [GLSL invocation IDs](https://docs.vulkan.org/glsl/latest/chapters/builtins.html).
  Regenerated both SPIR-V assets with `glslangValidator -V --target-env vulkan1.1`.
- RAW sampling uses a primitive `int codeAt(int,int)` interface rather than
  boxed `Function2` calls in the pixel loop, verified with `javap`. Its worker
  uses Android background priority so sampling yields to recording/UI, following
  [Android threading guidance](https://developer.android.com/topic/performance/threads).
  Sampling calibration, exposure decisions and display resolution are unchanged.

The existing fused GPU precision tests passed CPU reference P010 codes,
white-balance/exposure changes and lens-shading cases. The isolated 4K fused
stage measured 16.7 ms against its 30 ms gate. RAW sampler/buffer host tests
were rerun after the primitive-interface change (16 passed); the previous
focused scope/stream/peaking suite total was 48 passed.

Final measurements on Xiaomi 25080RABDG / Android 16:

| Recording path | Off baseline | Spot | Zebra | False colour | Drops/errors |
| --- | --- | --- | --- | --- | --- |
| Negotiated 3840×2160, 30 fps, HLG, recorder test | 28.31 / 28.64 fps | 28.30 fps | 29.27 fps | 29.32 fps | 0 / 0 |
| 2560×1440, 30 fps, HLG, actual MainActivity + monitor | 30.38 fps (Off/spot retained) | included | 29.99 fps | 29.99 fps | 0 / 0 |

The 4K row reports negotiated encoder dimensions; `Stats.exportSize=1920x1080`
is an internal compute-geometry label, not the selected codec output size.
The 1440p test used the app's real video start/stop flow, live preview display,
main-screen pill and spot HUD. The monitor presented 394 frames, no codec
starvation/degraded frames occurred, all recorded frames had zero CPU-chain
budget overruns, and photo RAW resumed after stop. Cleanup targets only that test's generated take; existing user media and saved
preferences are preserved. Photo/LOG/CRS headers and viewfinder bounds stayed identical when
spot display was enabled/disabled. TYPE-7 was removed from the RAW video UI.

CRS is hardcoded unavailable in `CrsWriter` in HEAD, predating F1/F2.
The test verifies `RawVideoRecorder.start` rejects before RAW acquisition or
output creation, then stop clears assists and leaves zero leases. The feature
integration compiles, but no CRS recording-throughput claim is made. This
matches the existing F14/F15 unavailable-path exclusion and does not add a
new CRS backend to F1/F2 scope.

All acceptance checks for F1/F2's available paths are complete. The measured
4K rate is approximately 28–29 fps on this phone; this is not a guarantee of
sustained 30 fps on all devices or a long thermal soak.

[Recording, UI, RAW oracle and retained initial-failure evidence](results/f1-f2-2026-10-26/)
are saved as text reports; private scene screenshots were inspected locally.

Final owner-approved control refinement: 40 dp round assist button beside the
AUTO/GPU/CPU switch (48 dp touch target, 8 dp spacing). Installed and the
photo/LOG/CRS mode-cycle/layout test passed again; F1/F2 remain closed.
