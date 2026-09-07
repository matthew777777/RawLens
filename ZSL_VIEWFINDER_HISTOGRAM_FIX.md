# RAW ZSL viewfinder + histogram responsiveness fix

## Symptoms

- Viewfinder became slightly laggy while full-resolution RAW ZSL was active.
- The RAW sensor histogram did not replace the processed-preview histogram immediately after entering ZSL.

## Changes

1. `MainActivity.scheduleHistogram()` no longer calls `TextureView.getBitmap()` while RAW ZSL is requested. This removes a synchronous preview GPU-to-CPU readback from the UI thread while the camera HAL is simultaneously producing full-resolution RAW frames.
2. Entering RAW ZSL resets the RAW histogram throttle so the first paired sensor frame is eligible for histogram publication immediately.
3. The live RAW histogram sampler target was reduced from 24,000 to 8,000 Bayer blocks (up to roughly 32k channel samples). A 64-bin display histogram does not need ~96k samples every update, and the sampler currently runs in the camera callback path where CPU time directly affects callback latency.
4. ZSL image/result pairing now posts a timeout only when the counterpart has not already arrived. This avoids posting and immediately cancelling timeout runnables for the common exact-pair case at ZSL frame rate.

## Preserved behavior

- Full-resolution RAW ZSL stream and timestamp-exact image/result pairing are unchanged.
- The RAW ring buffer, capture selection, lens-shading metadata, motion metadata, and DNG/JPEG paths are unchanged.
- When ZSL is disabled, the processed-preview histogram still uses the existing `TextureView` bitmap path.

## Verification

- `git diff --check` passes.
- Gradle unit tests could not execute in the isolated build environment because Gradle 8.9 is not cached and the wrapper cannot access `services.gradle.org`.
