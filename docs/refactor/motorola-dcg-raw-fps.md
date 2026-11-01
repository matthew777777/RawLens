# Motorola Signature DCG: RAW frame-rate selection

The reported working MotionCam configuration uses main camera ID 2, standard operating mode 0,
`org.codeaurora.qcamera3.sensor_meta_data.current_mode` as Int32 value 8, RAW_SENSOR output,
and 48/50/60 FPS. The reporter sets the key at session scope, raises FPS, then switches the
key to request scope. The supplied RawLens screenshot only shows CAMERA ERROR; it does not
identify the failing Camera2 operation or prove the RAW bit depth.

RawLens previously selected a 30 FPS RAW repeating range and had no per-lens override.
The LOG recording frame-rate selector controls another pipeline. This makes the reported
sensor-mode selection sequence impossible to reproduce through the existing UI.

The per-lens vendor editor now stores an opt-in RAW preview FPS override. Only advertised
ranges whose upper bound equals the target are offered; fixed ranges are preferred. This
range is attached before session creation (even with no session vendor keys), and used for
RAW repeating requests. Existing profiles keep the default 30 FPS policy. Presets and export
preserve the override. Frame-rate changes flag the session as needing a reopen. Request-key
edits remain live and do not automatically reopen, preserving the existing session's startup
parameters when switching a key from Session to Request.

## Device verification still required

1. Select the main sensor route (reported ID 2) and RAW_SENSOR output.
2. Add the reported vendor key as Int32, value 8, Session scope, operating mode 0.
3. Select RAW preview frame rate 60 in the vendor editor (48/50 are alternatives only if offered).
4. Reopen the camera to initialize with both the key and the target FPS.
5. Change the key's scope to Request and save. Do not reopen at this step: the pending-session
   badge reflects that the already-open session still has its initial Session parameters.
6. Check frame delivery and capture a DNG. Compare RAW samples, white/black levels, and
   dynamic range with a working MotionCam DNG. The reported calibration is white 16383,
   black 63,63,63,63; metadata overrides alone do not prove DCG or 14-bit data.
7. Restart the app and repeat to determine whether the HAL requires the scope transition
   every time. Collect logcat if configuration fails or the device disconnects.

This is a compatibility control, not an automatic device-specific DCG preset or a verified
fix for the Motorola HAL error. Actual frame cadence depends on streams and exposure.
It does not expand the LOG recorder's supported output rates.

Camera2 recommends providing nondefault target FPS through session parameters:
https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
