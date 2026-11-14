# Camera result diagnostics

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Photo preview and RAW-video callbacks emit separate route/pipeline-labelled diagnostics:

- `SENSOR_MODE`: values read from the actual request and advertised result keys for
  Qualcomm current_mode and Vivo forceSensorMode. Session-scoped values are recorded
  when attached before session creation; they need not appear in repeating requests.
  Missing result keys explicitly report unavailable. Builder acceptance / reported
  white level never establishes DCG activation or effective sensor precision.
- `SENSOR_FPS`: completed-result cadence from SENSOR_TIMESTAMP over at least two
  seconds. Logs requested/returned AE FPS ranges, frame duration and exposure.
  This measures delivered sensor capture cadence, independently of file FPS and
  display refresh. Duplicate/out-of-order timestamps do not inflate it.
- `AF_TRIGGER`: submitted START/CANCEL, route, run, mode, focus distance and regions.
- `AF_RESULT`: mode/trigger/state changes, frame number, returned lens focus distance,
  lens state, requested regions, active physical route. One-shot command results
  are also logged, including CANCEL; command failure includes frame and reason.
- `RAW_FPS_SELECTION`: requested selection and advertised ranges. Unsupported saved
  selections say NOT APPLIED. `RAW_FPS_MISMATCH` flags returned ranges differing
  from the request. Vendor editor displays this status plus measured sensor FPS;
  dropping session parameters in fallback is also explicit. Values are a snapshot
  when the editor opens. A matching target range is not a guarantee of actual FPS.

Sensor-mode polling is limited to the initial result, command results and FPS
windows. AF results log only when state/mode/trigger changes. Diagnostic read
failures are isolated from capture/result pairing and reported once per callback.
No HAL settings or focus policy are changed by these diagnostics.

Validation: timestamp cadence fixtures for 24/30/60 FPS, missing and duplicate /
out-of-order timestamps; vendor-key and autofocus regressions; Android APK build.
Retest on the affected Xiaomi is required to obtain its new result-level trace.
