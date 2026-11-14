# Motorola 17:42 diagnostic follow-up

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Reviewed all 1,646 lines of logcat-20261101_174213_253 (1).txt.

Unlike the earlier export, this is a forward single-RAW capture with ZSL off.

- Cold sessions immediately carrying Qualcomm current_mode=8 fail their initial
  AF CANCEL request then report camera-device error 4. Both key and FPS are
  accepted at builder/session configuration, so acceptance alone is insufficient.
- At 17:42:38 the key is absent on startup and preview succeeds. Sensor timestamps
  establish 60.02 FPS, requested and returned [60,60].
- At 17:43:01 the live request receives current_mode=[8]. Preview remains stable
  at 60.04 FPS. Returned current_mode is unavailable; dynamicWhite stays 1023.
  A user calibration sets VF white=16383, so that UI value is not sensor proof.
- At 17:43:23.512 forward single RAW id=16 submits. At 17:43:24.450 it fails
  reason=0, then at 17:43:24.809 camera-device error 4. No RAW/DNG save begins.
- Subsequent cold Request-mode startups fail again. No app OOM or fatal exception.

Current-code mismatch: selected explicit FPS was set on session parameters and
repeating preview, but not on TEMPLATE_STILL_CAPTURE or one-shot AF builders.
Those builders still received the vendor mode key. Their template default FPS
could therefore conflict with the working high-rate mode. The log establishes
the failing forward request and live 60 FPS operation; it does not independently
prove which HAL readout/intent constraint causes rejection.

Implemented compatibility changes:

1. Apply the explicitly chosen advertised RAW FPS range in shared camera controls,
   so still, AF and precapture builders preserve it. Defaults remain unchanged;
   manual shutter/frame duration is not truncated or replaced.
2. For enabled Qualcomm current_mode Int32 Request value 8 with explicit FPS >30,
   stage startup: initialize preview at the chosen FPS, then apply mode 8 only
   after the first RAW arrives. Each new session resets that stage; generation
   and reader checks reject stale activation. No saved profile/scope is changed.
   Other values, types, keys, Session-scoped modes, and default FPS are untouched.
3. Log VENDOR_MODE_BOOTSTRAP transitions and STILL_REQUEST FPS/intent/exposure.

Regression tests check scoped mode filtering and restoration without mutating
saved settings, plus vendor-key/FPS tests and APK compilation. Need Motorola
hardware confirmation: cold start, live 60 FPS, single DNG, then pause/resume.
If still capture fails with [60,60] explicitly logged, the next investigation is
TEMPLATE_STILL_CAPTURE / capture-intent compatibility or a stream-preserving
capture path; do not claim this patch confirms DCG or fixes every HAL constraint.
