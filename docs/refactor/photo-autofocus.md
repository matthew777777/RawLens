# Photo autofocus replacement

2026-10-31. Implementation, host verification and device acceptance on the connected MediaTek main-camera route. See [device results](results/photo-af-2026-10-31/README.md).
This supersedes the previous autofocus policy and its proposed recovery heuristics.
Historical reports remain evidence of those runs, not acceptance of this implementation.

## Contract and source review

1. [Android Camera2 CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AF_TRIGGER): START and CANCEL are single-request events; repeating requests use IDLE. Settings on a request apply to its trigger. There is no need for an arbitrary wall-clock delay between selecting AUTO and submitting START with AUTO.
2. [AOSP 3A state machines](https://source.android.com/docs/core/camera/camera3_3Amodes): continuous-picture tracks autonomously; START in that mode locks rather than guaranteeing a new sweep. AUTO starts an active sweep. Mode changes reset AF; stream configuration alone does not. PASSIVE_FOCUSED is not a lock. A scan can finish between reported frames, so ACTIVE_SCAN is not a required intermediate observation.
3. [CameraCaptureSession callbacks](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.CaptureCallback): the started callback identifies the frame for a submitted command. Use the START frame as the lower bound for accepted AF results, together with the requested mode and a monotonically increasing app run identity. Reject non-increasing result frames and callbacks from old sessions.
4. [StreamConfigurationMap](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap): use advertised SurfaceTexture output sizes. A listed size is not proof that every simultaneous RAW combination will configure; keep the session rejection ladder.
5. [Xiaomi's OEM 3A guide](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1598): recommends hardware continuous-picture preview AF, explicit touch regions and CANCEL/START/IDLE. It is OEM guidance, not access to proprietary MediaTek or Qualcomm algorithms.

### Qualcomm HAL inspection

Reviewed the published Qualcomm HAL implementation in AOSP:
[QCamera3HWI.cpp, Android 8.1.0 r6, msm8998](https://android.googlesource.com/platform/hardware/qcom/camera/+/android-8.1.0_r6/msm8998/QCamera2/HAL3/QCamera3HWI.cpp).
This is a historical implementation, not proof about current Snapdragon firmware.

- Lines 239–250 map framework AF modes to Qualcomm focus modes; continuous-picture and AUTO are separate modes.
- Lines 12461–12479 translate the requested AF mode into the parameter batch.
- Lines 12619–12632 translate a framework AF trigger and its framework trigger ID into `CAM_INTF_META_AF_TRIGGER`. Application run identities are local bookkeeping, not private HAL trigger IDs; RawLens does not invent vendor keys.
- Lines 8411–8445 handle trigger-associated early AF state and pending request frame numbers. That supports correlating AF observations to frames rather than treating the next callback as the new tap's result.
- Lines 12998–13013 convert framework active-array AF regions into sensor coordinates and crop them. RawLens supplies normal Camera2 metering coordinates, not Qualcomm's internal ROI units.

### MediaTek evidence and limits

Reviewed MediaTek's public [Genio RAW/ISP documentation](https://mediatek.gitlab.io/aiot/doc/aiot-dev-guide/master/sw/yocto/app-dev/camera/camera-g700-evk-imgsensor-raw.html).
It describes Linux V4L2 streams and shared preview/video 3A controls. Its example sensor explicitly has no usable AF. It does **not** document the Android Dimensity phone HAL AF algorithm and cannot justify a phone-specific AF workaround.

No current proprietary Dimensity AF implementation or phone-specific tuning source was available from these public primary sources. Therefore this replacement does not claim a MediaTek algorithm deep dive, infer a permanently defective AUTO mode from a motionless lens, or add guessed vendor tags. The Android HAL contract governs the implementation. The existing measured RAW-rate probe remains separate from AF; when it requires an ISP stream, selection requests an advertised normal HD preview rather than a tiny placeholder.

## Implementation sequence and review map

1. Delete `TouchFocus.kt`, `AutoFocusPolicy.kt` and their heuristic-specific tests. Remove persisted dead-AUTO scan handling, startup AUTO-mode kicks, tap bounce suppression and AF health convictions based on missing AWB. Old saved flags no longer influence focus or lens ranking.
2. Implement `PhotoAutoFocus.kt` as the sole photo tap/hold controller, serialized on the camera handler. Idle prefers CONTINUOUS_PICTURE. Tap prefers AUTO. Capability fallbacks use only advertised modes. A lens with zero AF regions can still perform whole-frame autofocus; OFF/EDOF-only lenses receive metering without a fake AF scan.
3. Each accepted tap replaces the previous run and its timers, installs regions, updates the repeating request, and queues CANCEL then START with the same new mode/region. No fixed settle delay, learned device verdict or repeated START loop.
4. Tag command requests with run/trigger identity. The START callback records its frame. Accept only results in the requested mode at or beyond that frame, strictly advancing frame order. A retap invalidates old acknowledgements and timeouts. Preview callbacks retain existing lifecycle guards; command callbacks additionally check session identity.
5. Only FOCUSED_LOCKED publishes success. NOT_FOCUSED_LOCKED publishes failure. INACTIVE, missing state and passive states remain inconclusive until the existing 3.5-second budget expires. Successful tap holds for the existing 10 seconds; failure displays for the existing 1.5 seconds. Both then cancel AF and restore continuous-picture, clearing the tap AF region.
6. Long press keeps the requested AF lock until explicit release, retap, manual focus or camera stop. Hardware AE lock is requested only if advertised. A failed long-press scan does not publish a successful AF lock badge. Manual/PROGRAM sensor exposure retains its existing exposure controls; AF with AE_OFF remains device-dependent and requires separate device acceptance.
7. At session start, explicitly CANCEL an inherited AF lock because reconfiguration alone does not reset 3A. AUTO/MACRO-only lenses get one startup scan; this is not advertised as continuous tracking.
8. ISP preview selection prefers RAW-matching HD sizes inside 1920×1080, otherwise the largest bounded advertised size. Reader/RAW retries keep that preview. If the device exposes only smaller sizes, the app cannot invent unsupported HD sizes.
9. Replace obsolete JVM tests with frame/run/mode correlation, retap races, skipped scan state, passive state rejection, timeout, failure, hold, manual/reset and fixed/regionless-lens cases. Keep geometry, manual focus, peaking and video focus as separate features.

## Validation and real-device acceptance

Run JVM regression tests and build the app plus instrumentation APK with `-PfreezeVersion` to preserve the workspace version. The focused device test is `RefactorDeviceChecksTest#touchFocusReachesVerdictHoldsAndReleases`, opt-in with `-e rawlensRefactor true`.

On a connected, authorized device:

1. Record model, SoC, Android build, selected Camera2 route, available AF modes and AF-region count.
2. Check startup logs show continuous-picture and repeating trigger IDLE. On an ISP-required session, record the actual selected normal preview dimensions.
3. Place a textured near target and far target in view. Tap each repeatedly, including retaps while scanning. Record START frame, mode, AF state, lens distance and visible sharpness. Do not interpret no lens motion at an already-focused target as a defective algorithm.
4. Verify the first tap triggers, old frames do not show a false lock, and ten seconds after a successful verdict AUTO returns to continuous-picture. Verify near/far AF-C tracking resumes.
5. Long press, wait longer than ten seconds, and verify the lens remains locked. Reset/double-tap and retap must release it. Repeat on a low-contrast target: a failed scan must not show a successful AF lock.
6. Repeat for each relevant lens/route with hardware AE, PROGRAM and manual exposure. Check preview cadence, still capture, resume, lens switching and absence of camera errors.
7. Tune only from recorded device evidence. Optical sharpness and latency require physical targets; metadata/unit tests alone cannot certify either.

ADB was unavailable during initial host verification. It later connected; the main-camera device acceptance is recorded in the linked report.

### Completed host/build checks

- `./gradlew :app:testGithubDebugUnitTest :app:assembleGithubDeviceTest :app:assembleGithubDeviceTestAndroidTest -PfreezeVersion --console=plain`: successful final run (34 seconds).
- JUnit: 1,762 tests, zero failures/errors, six skipped; includes 23 new autofocus tests.
- `git diff --check`: clean.
- Device-test app: `app/build/outputs/apk/github/deviceTest/RawLens-2.0.39-deviceTest-arm64-v8a.apk`.
- Instrumentation: `app/build/outputs/apk/androidTest/github/deviceTest/app-github-deviceTest-androidTest.apk`.
- Existing workspace version and unrelated edits were preserved. This initial host run preceded the device connection. The later 2.0.40 install and device checks are recorded in the linked report.
