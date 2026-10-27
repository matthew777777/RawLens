# CRAW recording work — 2026-10-27

Status: codec changes validated on host and cross-compiled for Android arm64;
**sustained phone 30 fps and Resolve import remain unverified**. The current
session denies local sockets: ADB cannot bind its server and Gradle cannot
create its FileLockContentionHandler. No APK was installed during this work.

## Changes

- Choose CRAW-Live or CRAW-Fast in Settings → Video → RAW video. Selection
  freezes at take start and is reflected in per-frame metadata.
- Restore three encode workers rather than six; keep bounded queues. This
  is a memory-conscious baseline pending phone profiling, not a 30 fps claim.
- For both modes, skip the redundant raw-frame CRC pass; CRAW container
  chunk CRC32C remains enabled and is verified by the reader.
- Encoder caller-buffer path at one thread now streams tiles directly to
  the pooled output, retaining one tile instead of a full frame of tile
  buffers. Live also uses a tighter tile bound and avoids the packed-data
  move for valid <=13-bit frames. No block-size or wire-format change.
- PCM16 recording remains enabled. Decoder WAV export has tests for PCM16,
  PCM24, float conversion, Broadcast WAV timecode, RIFF padding and partial
  reads. The guide uses manual timeline placement in Resolve.

## Host evidence

4080×3060 synthetic 10-bit, one thread, direct-buffer API, no frame CRC,
25 measured frames after warm-up; baseline and new binaries both compiled
with clang++ -O3 -DNDEBUG. Results vary with host load and are not phone fps.

| Mode | Baseline median | New median | Baseline peak RSS | New peak RSS | Output |
|---|---:|---:|---:|---:|---:|
| Live | 10.207 ms | 9.925 ms | 61,898,752 B | 51,740,672 B | 5.246 bpp |
| Fast | 21.454 ms | 21.087 ms | 61,865,984 B | 52,477,952 B | 5.145 bpp |

Both outputs are byte-identical to the reference. Native encoder/decoder
conformance covers 17 golden vectors, including full/partial blocks and
escaped widths through 17 bits; encoder tests cover caller-buffer bounds,
padded input stride and one/four-thread equality. Encoder ASan/UBSan passes.
Decoder WAV tests and rebuilt TUI development tests pass. ARM64 encoder
cross-build and JNI syntax check pass. Kotlin/UI device tests cannot run
under the socket restriction above.

## Device acceptance

Build/install the deviceTest app and instrumentation APK, then run each
mode separately (allow cooling between runs):

```sh
./gradlew :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
adb install -r app/build/outputs/apk/deviceTest/app-deviceTest.apk
adb install -r app/build/outputs/apk/androidTest/deviceTest/app-deviceTest-androidTest.apk
adb shell am instrument -w -e class com.matthew.rawlens.VideoModeUiTest -e rawlensVideoUi true -e rawlensCrawCodec live -e rawlensVideoSeconds 30 com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.matthew.rawlens.VideoModeUiTest -e rawlensVideoUi true -e rawlensCrawCodec fast -e rawlensVideoSeconds 30 com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
```

The opt-in UI test now checks actual FRAM timestamps: >=29.5 fps and no
inter-frame gap above 50 ms, plus the recorded codec and finalized footer.
It reports frame count, fps and maximum gap. `-e rawlensRequire30Fps false`
keeps the smoke checks for diagnosis. Repeat with longer takes (up to 300 s),
real textured scenes, audio enabled and the usual viewfinder. A short or
synthetic encode benchmark alone cannot establish thermal stability.

For failures, collect per-second `RawVideoRecorder` stats and thermal/memory
logs. Distinguish camera acquisition loss, queue drops, encode latency,
result waits, skipped commits and storage time before adjusting workers.
Then export a fresh take and verify WAV placement and playback in Resolve.

## Research used

Android documents that unreleased ImageReader images can stall the camera
producer; queue growth therefore cannot fix sustained encode overload:
https://developer.android.com/reference/android/media/ImageReader

Android performance guidance notes changing CPU clocks, heterogeneous cores
and thermal limits during sustained workloads:
https://developer.android.com/games/optimize/adpf

Microsoft RIFF requires even-byte chunk padding, excluded from chunkSize:
https://learn.microsoft.com/en-us/windows/win32/xaudio2/resource-interchange-file-format--riff-
