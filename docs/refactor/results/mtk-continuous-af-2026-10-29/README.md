# MTK continuous-picture touch-focus investigation

Local branch: `fix/mtk-continuous-picture-tap-focus`.
Device connected over ADB: model `25080RABDG`, device `lapis`, camera `0`.
Do not treat earlier Xiaomi 14T Pro comments in the app as evidence about this device.

The requested objective is a tap that makes continuous-picture AF refocus without
holding `AF_MODE_AUTO`. Existing uncommitted application changes were preserved.

## Research

- [Android AF state transitions](https://source.android.com/docs/core/camera/camera3_3Amodes):
  in continuous-picture, START locks the current or ongoing passive result; it
  does not guarantee a new full sweep. CANCEL releases/reset AF; the HAL chooses
  when to run a passive scan.
- [CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest):
  triggers belong in one request, followed by IDLE. AF with AE_OFF is device
  dependent. AF regions apply to scanning modes, but do not mandate a new sweep.
- [Xiaomi 3A guidance](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1598):
  recommends setting the touch region and CANCEL→START→IDLE. The standard
  continuous-picture state machine still permits START to lock without a sweep.

## Method and limits

`ContinuousPictureAfDeviceTest` is opt-in (`-e rawlensAfProbe true`). It tests
manual infinity/near seeds, both AE_ON and AE_OFF, and warmed continuous sessions.
The warm trials intentionally retap the same region: this checks the user's
ability to restart AF even without a scene/region change. A static scene already
in focus legitimately needs no lens movement, so little movement alone does not
prove a defective HAL. AF state and focus distance are HAL metadata, not an
optical sharpness measurement. The subject and lighting are not laboratory
controlled and change between sweeps; compare strategies cautiously.

Cold trials include mode transitions and cannot establish that CANCEL itself
caused the motion. Warm reports include the preceding baseline and actual result
AF modes. Only distances reported under continuous-picture contribute to their
range; transition frames can still reflect a preceding manual lens command.
In particular, OFF-reset movement to zero is not evidence of successful AF.

The independent `com.matthew.afprobe` diagnostic avoids replacing the installed
RawLens app and its signing key. It merges the non-minified app's DEX runtime into
the instrumentation APK, uses its own manifest/package, and signs with the local
Android debug key. It does not exercise the app UI, its metering-coordinate
mapping, or the complete production request controls.

## Reproduce

Build:

```sh
./gradlew -PfreezeVersion :app:assembleGithubDeviceTest :app:assembleGithubDeviceTestAndroidTest
```

Run with a nearby, detailed, well-lit subject:

```sh
python3 tools/autofocus-probe/run_probe.py \
  --app-apk app/build/outputs/apk/github/deviceTest/RawLens-2.0.7-deviceTest-arm64-v8a.apk \
  --test-apk app/build/outputs/apk/androidTest/github/deviceTest/app-github-deviceTest-androidTest.apk \
  --warm --ae 0 --strategies region,cancel,off-reset,auto-cancel,video-reset,ae-pulse \
  --output /tmp/continuous-af-warm.txt
```

Use `--raw` to add the largest advertised RAW stream to each repeating request.
The probe remains installed for reruns; `adb uninstall com.matthew.afprobe`
removes only the diagnostic app.

## Outcome

Completed four instrumented sweeps: 32 cold, 12 warm, 10 template/control,
and 8 RAW+YUV trials (62 total, 15 distinct sequences). All four instrumented
runs completed successfully; success means measurements were collected, not
that autofocus achieved optical focus. The RAW stream was 4080×3060.

- Continuous-picture does move this lens: e.g. cold AE_OFF region-only trials
  covered 0–3.46 D; warmed RAW CANCEL trials covered 1.77–2.35 D.
- Same-region retaps do not guarantee a new full sweep. Region changes,
  CANCEL/START combinations, OFF/AUTO/video resets, temporary AE_ON, region
  clearing, ZSL/still templates, CONTROL_MODE reset, and repeating restart
  did not establish a reliable forced scan. Some combinations yielded useful
  corrections; others stayed passive or locked the starting distance.
- OFF without a distance can reset to infinity and stay there. Do not ship it
  as an autofocus fix merely because metadata reports movement.
- Vendor keys were enumerated, but no documented, verified force-rescan key
  was established. Unknown factory/zoom keys were not written.
- No continuous-only replacement is justified by these trials. Keep the
  existing AUTO fallback. The probe does not prove this is impossible on
  every MTK firmware or with all vendor controls.

A separate application race was fixed in `TouchFocus.onAfState`: before the
scheduled START/CANCEL has been submitted, ignore results from the old AF
cycle. Previously a PASSIVE_FOCUSED/locked result during the 60 ms settle could
complete the new AUTO tap and remove its pending START, so no scan was ever
requested. Regression coverage verifies all three stale verdicts leave START
queued and that the later actual scan can complete.

Validation: 25 TouchFocus tests and 21 AutoFocusPolicy tests passed; the device
probe compiled and the independent probe packaging script completed the RAW
run end to end. The production race fix is local and was not installed over
the signed RawLens app. The saved signing configuration was rejected, so the
ADB experiments used the separate diagnostic package instead.
