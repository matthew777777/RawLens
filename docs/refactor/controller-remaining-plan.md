# RawCameraController: remaining areas (2026-10-22)

Controller: 8,354 lines after R2/S9 (`30345d1`). Done so far: TouchFocus,
ZslStreamHealth, SessionLadder, FramePairing, ProgramAeSolver, RawStreamPlan,
RawFrameSaveJob, StillChain, plus C/S/R/F/W study fixes.

Method for each area, as before: study against a reference where one
exists -> verbatim move behind a Host interface (normalized diff vs HEAD)
-> JVM tests for the extracted logic -> full suite -> phone run -> commit.

| # | Area | Lines | Members (largest) | Proposed |
|---|---|---|---|---|
| A1 | HDR bracket capture + save | ~354 | saveHdrBracket (291), applyHdrExposure, captureHdrBracket, resetHdrBracketState | `HdrBracketSaveJob` like RawFrameSaveJob (W3); exposure-per-frame stays |
| A2 | Metering / AE dispatch | ~651 | dispatchMeteringSample, updateProgramFromSample, updateEttrFromSample, updateDynamicExposureFromMetering, setCaptureExposureMode | `MeteringController` (sample routing + PROGRAM/ETTR/dynamic state); solver math already in ProgramAeSolver |
| A3 | Repeating request / camera controls | ~486 | updateRepeatingRequest (204), previewCaptureCallback (122), applyCameraControls (111), manualControlRange | study vs Camera2 spec first; split request building from state |
| A4 | RAW readers | ~329 | closeRetiredRawReaders (182), drainQueuedRawImages, GPU-reader trial/verdict | `RawReaderPool` (retire/close/drain, GPU trial verdict) |
| A5 | Scopes / peaking / histogram publish | ~208 | publishRawScopeIfDue, publishDevelopedScope, publishFocusPeakingIfDue | `LiveScopes` publisher |
| A6 | Flash / torch | ~166 | runFlashPrecaptureThenCapture, captureSingleWithFlash, applyStillFlash | study vs Camera2 precapture sequence (Open Camera) then extract |
| A7 | Debug / status | ~160 | publishDebugState, publishRawZslStatus, formatRawVfDebug | `ControllerDebug` formatter (pure) |
| A8 | Faces | ~127 | handlePreviewFaces, publishFaces, applyFaceDetectMode | `FaceTracking` |
| A9 | Dual RAW | ~119 | captureDualRaw, saveDualRaw | small; with A1 or alone |
| A10 | Companion object | ~627 | 67 constants + ~27 internal pure helpers (session combos, preview sizes, fps probe, ZSL capacity) | move helpers next to their owners (SessionLadder, RawStreamPlan, ZSL) |
| -- | ZSL merge jobs (HDR+/MFSR orchestration) | ~719 | runHdrPlusJob, runMfsrJob, selectAndSaveRawZsl | **deferred with HDR+** (owner, 2026-10-22) |

Suggested order: A1, A9, A7, A8, A5, A4, A10, A2, A6, A3 (largest
behaviour surface last; A3/A6 need a study first).

## Progress

- A1 done: `fix(hdr)` froze the bracket's save settings at shutter time
  (W4 for brackets); `HdrBracketSaveJob` + `HdrBracketSaveInput` hold the
  job, verbatim (normalized diff: only Host substitutions). Phone: HDR
  bracket soak (`-e hdrBracket true`, JPEG_DNG) saves merged DNG + JPEG.
  Known flake, pre-existing: the soak's "3 extra presses rejected" check
  races against an HDR-bracket press (1 of 3 runs on the moved build, the
  baseline build passed its single run); shutter admission is untouched.
- A9 done: the dual-RAW save job already delegates to DualRawSaver (no
  extraction needed); its camera id is now frozen at shutter time (W4).
  `captureDualRaw` is request building and moves with A3. Finding, record
  only: its submit-failure catch calls `finishCapture` alone, without the
  shared R3/R4 error tail (no ZSL resume, no dead-chain count).
- A7 done: `ControllerDebugText` (debug overlay, RAW VF line, ZSL chip
  decision, formatExposure/clipPercent) is pure; the controller keeps the
  throttles, the idle-AF log and the callbacks and passes a
  `DebugOverlaySnapshot`. Strings unchanged (normalized diff), 6 JVM tests.
- A8 done: `FaceTracking` owns the 10 face fields, select/debounce/
  move-tolerance/push-throttle/overlay logic behind a Host; the controller
  keeps Camera2 reads, the face-detect key and the region -> MeteringRectangle
  step. `Rect.intersect` became the identical integer test; the two
  always-equal AF/AE regions became one. 8 JVM tests (first coverage).
- A5 done: `LiveScopes` (scope/peaking switches, throttles, AgX LUT cache,
  cost telemetry, saved-frame JPEG/JXL scopes) and `SamplerGuard`
  (throttled sampler-failure guard, shared with metering) behind a Host;
  the stream plan (`rawHistogramStreaming`, disabled-for-session) stays in
  the controller. All 8 moved bodies identical after renames; 4 JVM tests.
  Phone (A5/A7/A8 together): 3 forward-burst JPEG+DNG cycles, live scope
  sampling throughout, no sampler failures or crashes; 36 test files deleted.
- A10 done: 24 companion helpers + their constants moved verbatim into
  `SessionStreamSelection` (12, with SessionStreamSize/Combo),
  `SessionRecoveryPolicy` (6), `FlashStillPolicy` (3), `ZslPolicy` (3);
  shared limits (MAX_IN_FLIGHT_JPEG_SAVES, BURST_FRAME_COUNT, pairing and
  transition slots) stay in the controller as `internal`. Line-multiset
  check: nothing lost or added. Five pre-existing misplaced KDoc blocks
  re-attached to the functions they describe (comment-only).
  `shouldRunRawStream` and `whiteLevelBitDepth` stay (stream gate / info).
- A4 re-scoped: real function sizes (brace-matched) show the reader code is
  5-15-line helpers (retire/close, drain, GPU-reader trial verdict), not
  ~329 lines; it stays with the session code, no separate class.
- A2 part 1 done: `EttrLoop` (state + solve). Part 2 (PROGRAM loop) waits on
  the reference audit in `exposure-audit.md` (owner request 2026-10-22: check
  ETTR/PROGRAM for unreferenced heuristics before moving more of them).
- A2 part 2 done: `ProgramAeLoop` (applied pair, limit flags, EMA, targets,
  fast start; one sample step returning FAST_START/GLIDE/HOLD and one glide
  step). The controller keeps gates, ranges, the 10 Hz ramp timer, pushes
  and the dispatch. Moved body identical apart from return values. 3 JVM
  tests. Phone: PROGRAM soak 3/3, fast-start logged (ISO 2891, 1/15 s).
  Behaviour rewrites (exposure-audit.md) follow after all moves (owner).
- A6 studied (`flash-study.md`): policy already in FlashStillPolicy; F1/F2
  (precapture fires on a possibly stale AE state; Open Camera two-phase
  wait) go to the rewrite batch together with the exposure audit.
- A3 done: `RequestExposure` (exposure-source decision of
  applyCameraControls: manual / ETTR still / PROGRAM live or glide / hardware
  AE, with range clamp), verbatim, 6 JVM tests; the two repeating-request
  catch blocks share `onRepeatingRequestFailed`. Session/epoch/watchdog
  orchestration and previewCaptureCallback stay (stream decisions already
  in RawStreamPlan). Phone: AUTO, MANUAL, ZSL soaks 2/2 each, no request
  failures. Finding for the rewrite batch (R-A3-1): with only ISO manual,
  the shutter is taken from a left-over PROGRAM pair when one exists.
