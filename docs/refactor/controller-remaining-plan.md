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
