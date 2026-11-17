# RAW60 performance handoff — 2026-11-17

User asked to finish and let Claude take over. Stop further feature work here.

## Latest instructions / constraints

- Goal: real recorded 60 fps RAW video. Test 4080×2304 (9.4MP), not lower resolution or duplicated frames.
- User accepts lossy capture, but **do not change CRAW, its encoder, format or JNI interface / require encoder updates**.
- User clarified that workers, buffering, monitoring and OS performance hints are allowed. Retain those app-side changes.
- PFOR experiments and proposed quantization were reverted. No quantization implementation was made. `git diff -- app/src/main/java/com/matthew/rawlens/CrawWriter.kt app/src/main/cpp/craw_writer_jni.cpp third_party/craw` is empty.
- All phone work headless. Recover the camera after tests; preserve working DCG without reboot. No governor/thermal modifications made.
- Earlier standing instruction: commit and push completed work. Latest performance changes are NOT committed/pushed; do not claim otherwise. Existing app-wide FPS/mode commits were pushed previously.

## Repository / ownership

Root: /Users/monikamalinowska/AndroidStudioProjects/RawLens
Branch: refactor/2.0; HEAD at handoff b4215f152802e098cf443ecad1fb50f122939053.

Other people/agents are editing this shared worktree. There are MANY unrelated staged deletions and unstaged rendering, RapidRAW, SR and LK-R changes. **Do not reset, stash wholesale, add -A or commit all staged changes.** Commit only reviewed task paths (`git commit --only ...` if unrelated changes remain staged).

Task-owned current files:
- Modified app/src/main/java/com/matthew/rawlens/RawVideoRecorder.kt
- Modified app/src/main/java/com/matthew/rawlens/RawCameraController.kt (correct misleading FPS selection diagnostic for active root video mode)
- Modified app/src/main/java/com/matthew/rawlens/RecordingTools.kt (health budget uses selected FPS rather than hardcoded30)
- New app/src/main/java/com/matthew/rawlens/RawVideoCpuHint.kt
- New app/src/main/java/com/matthew/rawlens/RawVideoPerformance.kt
- New app/src/main/java/com/matthew/rawlens/RawVideoWorkerHealth.kt
- New app/src/test/java/com/matthew/rawlens/RawVideoPerformanceTest.kt
- New app/src/test/java/com/matthew/rawlens/RawVideoWorkerHealthTest.kt
- New app/src/androidTest/java/com/matthew/rawlens/RawVideoAppPerformanceDeviceTest.kt
- This handoff file.
App/version.properties was already dirty and auto-bumped by builds; other builds also modify it.

## Current app changes (allowed; no encoder edits)

- Four default workers for >30fps on >=8 cores; existing <=30fps/smaller-device default remains <=3. Explicit worker override retained.
- High-rate monitoring offers capped near30fps; recorded images are not intentionally discarded by this cap.
- Preallocate the existing bounded payload pool before high-rate requests, instead of allocating during initial capture.
- Worker retirement: previously one >250ms head delay permanently retired a worker. A long test retired two of four, causing persistent throughput collapse. New health tracker requires3 slow incidents, resetting after a normal completion. Frame skip timeout still bounded.
- PerformanceHintManager (API31+) per encoder worker, target workers×1s/FPS; report actual encoding duration; close sessions at worker exit. Unsupported devices safely no-op. <=30fps unchanged.
- OS hints verified live: service reported all4 sessions allowed, not paused, on big cores. No root frequency changes.

## Phone and exact mode

ADB: /Users/monikamalinowska/Library/Android/sdk/platform-tools/adb
Serial fe79feha9lmb6hhi. Redmi lapis, model25080RABDG, OS3.0.303.0.WPPEUXM, MT6878.
Temporary root currently ADB UID0 / KernelSU; SELinux Enforcing.
Mode4: 4080×2304 RAW10, nominal60.00698fps, actual code0x1004300e. Needs owned video metadata wrapper and companion640×480YUV stream for best throughput; those were previously integrated and committed.
Fixed24/30/60 choices app-wide, mode4 lens option, root lease rollback and RAW/encoded integration were previously committed/pushed as cd4f9e89. Preserve them.

At final handoff check: no /data/adb/rawlens-lapis/lease, sensor override blank, Enforcing, no RawLens performance hint sessions left active. No camera capture/build should be started automatically by Claude without reading status.
Installed packages:
- com.matthew.rawlens.redmi versionCode2365 (user-facing separate app; NOT the final current source bundle)
- com.matthew.rawlens.rcdcheck versionCode2369 (isolated diagnostics: hints + older experimental app-side changes, original CRAW encoder)
- com.matthew.rawlens.rcdcheck.test versionCode0
Test app root UID11665; Redmi UID11666. KernelSU manager UID10305. Existing grant helper /data/local/tmp/rawlens-ksu-app-profile supports args managerUid package appUid grant.
Do not replace regular com.matthew.rawlens blindly: its signing key differs. User authorized separate APK names previously.

## Evidence / honest status

**Sustained60fps full-resolution RAW has NOT been achieved.** Do not relabel selected FPS or CFR timeline FPS as measured recorded RAW cadence.

User log:
/Users/monikamalinowska/Downloads/logcat-20261116_200909_194 (1).txt
It shows capture53–57fps, 3-worker encode50–65ms/frame, queue drops, 370 committed/458 acquired in one take. FPS NOT APPLIED diagnostic was false for root mode: actual request/result [60,60].

Headless 8s matrix (sound off, scene varied; not paired identical frames):
- Live codec3,3 workers:53.913fps,408frames,15drops
- Live,4 workers:57.864fps,433frames,1drop
- Live,5 workers:55.587fps,416frames,4drops
- Balanced codec1:27.25/29.80/29.73fps at3/4/5workers
Do not infer universal codec ranking from scene-varying trials.

Long takes with sound revealed much worse transient failures / sustained writes:
- Four workers before health fix:32.03fps; repeat23.56fps. Logs demonstrate two workers permanently parked after transient delays.
- Health/buffer/monitor changes alone, confirmed lit scene:21.15fps, encode140.95ms, write30.47ms,1148acquired/625committed.
- Adding OS performance hints:48.9457fps,1448committed/1501acquired,51drops, encode34.20ms, write25.11ms,5,959,578,770bytes. Capture dimensions unchanged. This was30s with sound, four workers. Hint sessions were accepted/not paused. Big cores observed at2.6GHz afterward. No60fps claim.

User confirmed main lens uncovered / normally lit for final tests. Still do not assume compression complexity fixed between takes.

Artifacts:
- .local/lapis-raw60-performance-2026-11-16/ (some prior timing logs/config snapshots)
- /tmp/raw60-long-timings.log (before worker recovery fix, clear parking evidence)
- /tmp/raw60-fixed-long-timings.log
- /tmp/raw60-hints-device.log
- /tmp/raw60-throughput-device.log
- /data/user/0/com.matthew.rawlens.rcdcheck/files/raw60-throughput.json (last long hint trial; pull before overwriting if needed)
Older mode/app reports under .local/lapis-raw60-2026-11-16 and .local/lapis-video60-app-2026-11-16.

## Next bounded test (already prepared; NOT run)

Existing `RawDct` GPU temporal/spatial Bayer denoise is an allowed way to test lossy preprocessing WITHOUT changing encoder or CRAW format. No denoiser implementation was modified. It is already a user option, default off. It may reduce entropy/write size, but performance is UNVERIFIED; never silently enable it or claim60.

New test RawVideoAppPerformanceDeviceTest#recordAndMeasure:
- opt-in -e lapisVideo60 true
- -e denoise true/false, -e seconds 15 (5..60)
- Uses existing CRAW Live encoder, automatic worker policy, sound,4080×2304, actual CRAW timestamps, decodes a frame, records stage timings/ISO metadata and targetMet(fps>=59.5).
- If denoise=true asserts existing RawDct pipeline actually initialized.
- Writes files/raw-app-performance-{true,false}.json, deletes large test take, restores NORMAL in finally.
- Test smoke success is NOT a60fps acceptance: read targetMet and measuredFps.

## Build status / isolation

Shared-output builds collided with other work:
- /tmp/raw60-app-only-build.log: packageGithubDeviceTest NullPointerException.
- /tmp/raw60-app-dct-build.log: bundleGithubDeviceTestClassesToCompileJar missing generated R.jar.
These are packaging/output-state failures, not evidence that the final current source passed.
Earlier helper tests passed (2 performance +2 health); latest reintroduced combination/new DCT comparison has NOT completed a successful build or device run.

An isolated build was started:
./gradlew --init-script /tmp/raw60-isolated.gradle :app:assembleGithubDeviceTest :app:assembleGithubDeviceTestAndroidTest -PphotoRcdHeadless -Pandroid.injected.build.abi=arm64-v8a --console=plain
Init script sets app buildDirectory=/tmp/rawlens-raw60-application-build.
Log /tmp/raw60-isolated-build.log, configured2.3.73.
At user stop request the exact Gradle client PID50127 was identified and sent SIGINT (only this task's client, not shared daemons). Check log/process before resuming; no successful isolated artifacts were verified. Do not install stale files from shared app/build outputs. Always inspect fresh output-metadata.json/applicationId.

## Suggested continuation

1. Check remaining build process/log; use isolated outputs, preserve others' staged changes.
2. Confirm encoder/JNI/third_party diff remains empty.
3. Compile final app-only changes and tests. Run existing denoise-off/on comparison headlessly; inspect actual cadence, dropped frames, stage timings and image quality.
4. If no >=59.5fps sustained result, report that limitation plainly. User forbids encoder changes, so do not reintroduce PFOR, bit shifting, quantization or format work.
5. Verify normal DCG after experiments using existing LapisNativeSessionInstrumentedTest#dcg14NativePipeline and final original hashes/cleared props/Enforcing.
6. Install final allowed app bundle only after verification; commit/push only task-owned reviewed files. Current performance work remains uncommitted.
