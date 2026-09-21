# ZSL capture/save and RAW viewfinder verification

Capture admission is sequential: a new press is accepted after physical capture and all image-owning save jobs finish. Repeated presses are discarded. Hybrid ON transfers a partial buffered selection to a forward top-up, including a fully forward burst when no selection is available. Hybrid OFF retains incomplete selections in the ring during its bounded refill wait.

Grouped ZSL saves now have an explicit image owner. Save admission releases only after that owner closes. Session teardown retires readers until outstanding saves and metering release their images. Completion invalidates capture callbacks and closes pending pairs and top-up holdings.

The current RAW display uses the user's Vulkan compute + GL SurfaceView path, with direct EGL and CPU sampling fallbacks. Android preview remains attached as a hidden stream target. The renderer's existing tone mapping, resolution controls and reconstruction remain separate from saved-image development.

Zero-copy import does not grant ownership of the camera frame contents after `Image.close()`. The controller now adopts each acquired RAW image into `RawImageOwnership`. Ring eviction, metering and saving release their camera-side claim. A queued/executing GPU preview has a bounded borrow that must finish before the underlying image closes. Dropped previews release their borrow immediately. The reader tracks actual acquired images, including GPU-only holdings, and has two additional slots reserved for the pending/executing GPU frames. Retired readers close only when their own acquired-image count reaches zero.

The Vulkan fence completes camera-buffer reads before the GPU borrow releases. A fence timeout drains submitted queue work before returning; direct EGL sampling completes its reads before release too. This is synchronization and lifetime integration, not a change to the user's Vulkan reconstruction or tone-mapping shaders.

See the [Android Image hardware-buffer lifetime contract](https://developer.android.com/reference/android/media/Image#getHardwareBuffer()). Keeping an AHardwareBuffer allocation referenced prevents its memory from disappearing; it does not prevent the camera from reusing the frame after its Image is returned.

## Local checks

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

JVM coverage includes GPU borrows across ring eviction/save completion, duplicate completion, pending-frame dropping, independent reader retirement, and ring retention during repeated incomplete hybrid-OFF selections, partial hybrid-ON ownership through save failure, existing exact-once save ownership/cancellation tests, all Bayer patterns, row/pixel strides, buffer offsets, level clipping and rotated/mirrored coordinates.

## Device soak test

The opt-in test writes real photographs and retains them for inspection. Complete initial lens setup in the app first. Install both APKs, then run:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class com.matthew.rawlens.ZslCaptureSoakTest \
  -e rawlensSoak true -e cycles 50 -e frames 2 \
  -e hybrid true -e format DNG_ONLY \
  com.matthew.rawlens.test/androidx.test.runner.AndroidJUnitRunner
```

The test checks expected artifact counts, shutter rearming, repeated-press rejection, advancing Android preview timestamps and RAW display recovery after each cycle. Repeat with `hybrid false`, `format JPEG`, `format JPEG_DNG`, and supported `frames` values up to 30 (JPEG selections are capped at six by the existing development queue).

Optional soak arguments: `-e exposureMs 1000` tests slow manual exposure in ZSL; `-e lifecycleEvery 1` backgrounds/resumes during each save. Each completed cycle logs PSS/native allocation, pending image/save counts and RAW viewfinder GPU/FPS status.

Also validate on hardware:

- Neutral gray and a color chart; all supported lenses, crop/focus agreement, portrait framing and Bayer layouts.
- Slow exposures; expected fallback timing and fresh-frame restoration.
- Background/resume during capture and saving, lens switching, surface recreation, and save failure/storage exhaustion.
- Memory and acquired-image counts over repeated cycles, after warmup; no sustained growth or persistent preview freeze.
- Larger strict-buffered bursts may need more than the default three-second prefill delay in the test at low sensor frame rates.

The initial CPU-viewfinder build passed 50 default ZSL cycles on Xiaomi 25080RABDG: 100 DNG artifacts, no queued duplicate presses, automatic rearm and advancing previews (106.805 seconds). That result predates the Vulkan changes and does not validate GPU image lifetime. The Vulkan-integrated runs are recorded below after execution.
