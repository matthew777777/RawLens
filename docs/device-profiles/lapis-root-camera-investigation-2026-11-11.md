# Lapis root camera investigation — paused 2026-11-11

## Latest stop — 2026-11-11 around 12:38 device time

User explicitly said **"okay stop for now"**. Stop all further feature experiments until requested again.

- USB returned without a phone reboot; uptime was about 1h19m and root worker PID 19578 was still available.
- Installed the corrected paired-session/bit-diagnostics test APK successfully. The installed isolated test package is now the corrected build described below.
- Retrieved `rawlens-camera-metastore.tar.gz`, which includes `libmtkcam_metastore.so`, custom metadata/provider libraries, and prior kernel camera-hang logs. This collection is now complete (the earlier disconnected attempt was not).
- Newly found metastore properties, **not changed/tested**: `vendor.debug.camera.enablefullraw`, `vendor.debug.camera.enablelogicalraw`, `vendor.debug.camera.enableRaw10`. These are promising leads for exposing full-size RAW stream metadata.
- Started `dcg9-pair-fps10` with paired `[0,9]`, session parameters, fixed 10 fps and **no debug-property override**. It did not produce frames while the existing camera HAL was stuck. **Do not treat this as a valid rejection of the paired-tag configuration.**
- Actual camera process was PID 7944, a zombie `[camerahalserver]`; `pidof camerahalserver` did not report it, so the earlier TERM recovery had not recovered that process. The real executable path is `/vendor/bin/hw/mt6878/camerahalserver`.
- Saved kernel log from the previous hang ends after `common_feature_control: lock setting work queue +` and a successful HPE sensor ID read, with no matching completion in the selected log excerpt. This suggests a driver/worker stall, but a definitive stack diagnosis was not obtained.
- A root thread-diagnostic script was submitted, but broad `/proc/*/cmdline` scanning was slow/stuck. Avoid repeating broad proc scans; inspect known HAL PID/tasks directly. The diagnostic root queue may have been waiting on that script before reboot.
- Reboot command was sent to recover the camera stack **just before** the user asked to stop. No further root exploit or mode testing was started afterward. USB returned as transport 46. Post-reboot security/property state was checked separately.
- Final post-reboot check: boot complete (`sys.boot_completed=1`), SELinux **Enforcing**, bootloader locked (`1`), no KernelSU module in `/proc/modules`, all three experimental properties empty. Temporary root and diagnostic workers ended with the reboot.
- Next resume: confirm boot is complete, stock RAW captures work, root is absent/available as expected, and camera HAL is healthy; then test the already-installed paired session tag on a clean camera stack. No need to rebuild/reinstall unless source changes again.

## Resume attempt, 2026-11-11 around 12:04–12:15 device time

User resumed the goal: actual 14-bit DCG/DSG, ISZ, 50 MP and 200 MP RAW, all headless. **No new mode has yet produced verified frames.**

- Restored the diagnostic root channel using the final `u:r:ksu:s0` handoff. Latest worker PID was 19578; channel directories were reset to shell-owned 0700. KernelSU stayed loaded, SELinux was Enforcing.
- Ran the previously planned `vendor.debug.cameng.force_sensormode=9` + fixed 10 fps session experiment. Camera configuration hung rather than returning frames. Host timeout was 100 s. The experiment's `finally` restored the property to its previous empty value and reported **Enforcing**.
- Root recovery collected camera-process logs and selected kernel messages into `/data/local/tmp/rawlens-camera-hang/`, force-stopped only the isolated probe package, and sent TERM to `camerahalserver` for init-managed restart. Recovery reported Enforcing.
- Shortly afterward the phone disappeared from ADB. Cause is unknown (USB disconnect/restart not established). Repeated `adb devices -l` returned no devices. An asynchronous question was sent asking the user to check/reconnect the phone. No further hardware experiments or vendor changes occurred after loss of connection.
- Do **not** assume the worker was stopped: connection was unavailable before it could be closed. It is local/temporary, reachable only through ADB shell files; it has no network listener. Check and clean it on reconnect. If the phone rebooted, temporary root will be gone.
- A metastore library collection was prepared but failed before it could run because ADB had no device. Do not claim those additional libraries were retrieved.

### New binary finding: paired session tags

Disassembly of the actual stock `libmtkcam_hal_core_featurepolicy.so` shows its configuration parser expects **even-length sensor-ID/mode pairs** for these tags:

- `com.mediatek.seamlessfeature.sensorScenario` (`0x801e0001`): read around VA `0x13c654`, count checked for evenness at `0x13c708`, then compared `[sensorId, mode]` entries in steps of two.
- `com.mediatek.seamlessfeature.forceSensorMode` (`0x801e0002`): read around VA `0x13c920`, count checked at `0x13c950`, same pair iteration.

This is stronger evidence than the earlier scalar request echoes. The corrected next test should use `IntArray(0, 9)` in session parameters, not scalar `9`. **This paired path has not yet been run on the phone.** The scalar result tags may still be reported as single integers; distinguish configuration layout from result layout.

The driver header also confirms `HDR_RAW_DCG_COMPOSE = 1 << 6` (64), while `HDR_SUPPORT_DCG = 1 << 7` (128). Do not confuse a support mask with the actual mode value.

### Corrected probe prepared locally

`SensorModeProbeInstrumentedTest.kt` additionally supports:

- `probeForcePair=true`: send `forceSensorMode` as `[0, probeSensorMode]`.
- `probeScenario=N`: send `sensorScenario` as `[0, N]`.
- `probeHdrMode=N` and `probeSessionHdrMode=N`: set vendor HDR/SessionParamhdrMode keys.
- `probeWidth`, `probeHeight`: explicit RAW output size (framework/HAL may reject unadvertised sizes; not validated yet).
- `probeFrames=1..3`: reduce memory/storage for full-resolution tests.

The capture path now writes the plane via a file channel rather than allocating a second entire frame byte array; uses maxImages=2 above 50 MP; records sampled pixel bitwise OR plus dynamic white/black level, frame duration, crop and zoom. `rootFeatureProbe` now throws if no real frame was captured, so JUnit OK cannot mask its logged FAIL as before.

Build: `./gradlew :app:assembleGithubDeviceTestAndroidTest -PfreezeVersion -PphotoRcdHeadless`. The updated probe APK was **built but not installed** because the phone is absent. Normal RawLens remains untouched.

Suggested first test on reconnect, after checking stock camera/security/root:

```sh
adb shell am instrument -w -r \
  -e class com.matthew.rawlens.SensorModeProbeInstrumentedTest#rootFeatureProbe \
  -e probeSensorMode 9 -e probeForcePair true \
  -e probeSessionParameters true -e probeFps 10 \
  com.matthew.rawlens.rcdcheck.test/androidx.test.runner.AndroidJUnitRunner
```

Try this **without a global debug override first**, then the HDR mode/session declarations as separate controlled experiments. This avoids repeating the known hanging camera-engine override. Root 50/200 MP support still needs a valid stream-table path; merely selecting the sensor mode will not prove full-resolution output.

New helper `experiment.py` saves a named experiment, snapshots debug properties, applies them via the root channel, runs headless instrumentation, and restores originals in `finally`. Timeout raises rather than producing a success report. Current outputs are under `experiments/dcg9-fps10/`. Saved disassembly `featurepolicy.disasm` contains the paired-tag parser. Sources and outputs are copied into the ignored durable evidence directory.

User requested hidden MediaTek DCG/DSG, ISZ, full-resolution and quad-Bayer camera modes, including vendor configuration/library changes if necessary. **All investigation must remain headless.** User explicitly stopped work to go out; resume only when requested.

## State when stopped

- No hidden feature has yet been enabled and validated for normal RawLens use.
- Stock RAW capture recovered and produced three 4080×3060 frames after the first property experiments were reverted.
- All three properties actually changed during this investigation were restored to their original empty values:
  - `vendor.debug.camera.support3rdISZ`
  - `vendor.debug.camera.seamlessen`
  - `vendor.debug.cameng.force_sensormode`
- Final device check: SELinux **Enforcing**, bootloader locked (`ro.boot.flash.locked=1`), KernelSU module still loaded. No reboot at stop, no camera libraries/configuration patched or mounted over stock files.
- Temporary diagnostic root workers were stopped. Latest worker PID 23674 is a zombie, not an active root command server. Some earlier exploit victim processes park indefinitely; do not mistake those for a usable shell.
- Normal RawLens package was not replaced. Only the existing isolated instrumentation package `com.matthew.rawlens.rcdcheck.test` was updated. No activity/UI was used for this camera investigation.
- One tracked source change remains: `app/src/androidTest/java/com/matthew/rawlens/SensorModeProbeInstrumentedTest.kt`. No commits made.

## Durable local evidence

Everything important from the temporary investigation directory was copied to the ignored workspace directory:

`.local/lapis-root-camera-2026-11-11/`

It includes original camera dumps, stock vendor library/module/config archives, test reports, root-channel helper sources and scripts, disassembly, build logs, and the relevant published HPE sensor driver sources. These are local artifacts, intentionally outside Git. The original working directory is `/tmp/rawlens-root-camera-research`; it can disappear on reboot/cleanup.

Key artifacts:

- `stock-camera-dump.txt`: complete pre-experiment `dumpsys media.camera` and camera properties.
- `inventory.txt`: root device/sysfs/module/process inventory.
- `driver-probe.txt`: live V4L2 mode/control enumeration, not just published source claims.
- `rawlens-camera-evidence.tar.gz`: stock camera HAL/remosaic/sensor-policy libraries and `imgsensor.ko`.
- `rawlens-camera-config.tar.gz`: protected camera JSON/XML configuration, process maps and properties.
- `rawlens-camera-policy-libs.tar.gz`: resource-decision, feature-policy and pipeline libraries.
- `baseline-probe.txt`, `stock-recovery.txt`: working standard RAW captures.
- `isz-props-zoom2-session.txt`, `isz-props-zoom2-request.txt`, `isz-only-zoom2-request.txt`: failed ISZ configurations.
- `dcg9-session.txt`: force-sensor-mode request/session echo without an actual mode switch.
- `dcg9-cameng.txt`: camera-engine forced DCG mode rejected at configure time.
- `seamless-rejection.txt`: HAL abort reason and backtrace excerpt.
- `final-restored-state.txt`: final property/security-state restoration.

## Device and root

- REDMI Note 15 Pro 5G, model `25080RABDG`, device `lapis`, MediaTek MT6878.
- Android 16, security patch 2026-09-01, firmware `OS3.0.303.0.WPPEUXM`.
- Exact kernel: `6.1.138-android14-11-g51f8c580613d-ab13911623`.
- Root is temporary, supplied by GhostLock plus KernelSU v3.3.1 (32680). KernelSU disappears on reboot until GhostLock runs again.
- KernelSU being loaded does **not** grant ADB shell root. Plain `su` is absent for ungranted shell, and `ksud debug su` from UID 2000 is denied. Do not claim the shell is rooted just because the module is listed.
- Current KernelSU root SELinux domain is **`u:r:ksu:s0`**, not `u:r:su:s0`. The latter exists but was not a usable enforcing diagnostic context. Confirmed against upstream v3.3.1 `kernel/selinux/selinux.h`.

### Working headless root handoff

No manager UI is necessary. A separate LD_PRELOAD helper intercepts only the verified exploit's rooted shell handoff, leaving exploit source/core unchanged. It transitions the diagnostic shell with `runcon u:r:ksu:s0` and executes the original GhostLock policy-repair script before serving commands. Root identity and enforcing-mode file access were verified.

Sources: `handoff.c`, `exports.map`, `root-entry.sh`, `root-su-worker.sh`, `sucmd.py`. `execl` must be exported with the `LIBC` symbol version (`exports.map`); the first unversioned attempt did not provide the needed handoff. Helpers are temporary and trusted only to root/ADB shell; remove them after research.

Device paths:

- `/data/local/tmp/rawlens-ghostlock`
- `/data/local/tmp/rawlens-ghostlock-profile.bin`
- `/data/local/tmp/rawlens-root-handoff-v3.so`
- `/data/local/tmp/rawlens-root-entry.sh`
- `/data/local/tmp/rawlens-root-su-worker.sh`
- `/data/local/tmp/rawlens-root-su/` (request/output/status files)

Prior launch command (do not run unless resuming; it is another kernel exploit attempt):

```sh
adb shell 'LD_PRELOAD=/data/local/tmp/rawlens-root-handoff-v3.so /data/local/tmp/rawlens-ghostlock --load-prebuilt-profile /data/local/tmp/rawlens-ghostlock-profile.bin --force-attack'
```

Before resuming the channel, clear **its** stale `stop`, `request`, `running`, `status` and output files, ensure scripts match the final saved versions, and restore owner/mode of channel directories to shell/0700 (they were temporarily relaxed during troubleshooting). Never run overlapping root command requests. `sucmd.py` currently contains the original absolute ADB path and device channel path; its script-file arguments are uploaded and executed as root. It times out after 120 seconds.

The early exploit-root shell has an invalid/random group ID and `kernel` context; `id` may fail to resolve that group. `id -u` correctly reports 0. Use the final KSU-context handoff. The earlier `rootcmd.py` / `root-worker.sh` kernel-context channel is superseded.

## Important sensor discovery

Camera2 advertises the name `S5KHP3` and only a 4080×3060 RAW stream with white level 1023 and black level 64. **The loaded kernel module actually contains `lapiss5khpewide` and its mode tables.** Do not continue using generic HP3 12-bit/8× DCG assumptions.

Published source:

- Repository: https://github.com/MiCode/mtkcam-kernel_device_modules
- Branch: `lapis-v-oss`
- Inspected commit: `28ccc0a419b5ac1d1e468711c1c76e8d9a22cd06`
- Driver: `imgsensor/src-v4l2/common/lapiss5khpewide_mipi_raw/lapiss5khpewidemipiraw_Sensor.c`
- GhostLock source commit: `4352a5b67bbf19fadc989d0f0302821ff8468a41` in `/tmp/rawlens-phone-root-research`.

HPE source specifies DCG/DSG compose with gain ratio **16000 / 1000 = 16×**, with `SENSOR_OUTPUT_FORMAT_RAW14_Gb`. Main sensor is `/dev/v4l-subdev24` (`sensor0`). The root-built `sensor-enum.c` uses read-only enumeration/get-format/query-control ioctls; it does not set a mode or start streaming.

Live enumeration confirmed these 20 entries:

| Index | Dimensions | Media-bus base code | Source interpretation |
| --- | --- | --- | --- |
| 0 | 4080×3060 | 0x300e | normal preview |
| 1 | 4080×3060 | 0x300e | capture |
| 2 | 4080×2304 | 0x301a | normal video, 14-bit |
| 3 | 1920×1080 | 0x300e | high-speed |
| 4 | 4080×2304 | 0x300e | slim/video |
| 5 | 4080×3060 | 0x300e | custom1 |
| 6 | 4080×3060 | 0x301a | custom2 LN2, 14-bit |
| 7 | 4080×3060 | 0x301a | custom3 2× sensor crop, 14-bit |
| 8 | 4080×3060 | 0x301a | custom4 4× sensor crop, 14-bit |
| 9 | 4080×3060 | 0x301a | custom5 capture iDCG + DSG, 14-bit |
| 10 | 4080×2304 | 0x301a | custom6 video iDCG + DSG, 14-bit |
| 11 | 16320×12240 | 0x300e | custom7 200 MP, source max 5 fps |
| 12 | 8160×6120 | 0x300e | custom8 50 MP, source max 10 fps |
| 13 | 4080×2304 | 0x301a | custom9 video ISZ 2× DSG, 14-bit |
| 14 | 1920×1080 | 0x300e | custom10 120 fps |
| 15 | 4080×3060 | 0x301c | inspect remaining source before assigning semantics |
| 16–19 | 4080×2304 | 0x301a | additional video paths; inspect source |

Extended media-bus code encodes scenario: e.g. mode 9 is `0x1009301a`, mode 11 `0x100b300e`.

Live driver also exposes controls for seamless scenarios, start-seamless-switch, frame descriptors, per-scenario static params, AE and HDR shutter. Enumeration proves driver support; it does not prove full Camera2 capture support. Do not switch a sensor underneath an active HAL pipeline without matching ISP format/resource configuration.

## HAL findings and experiments

Protected libraries were copied with root; plain shell could list but could not read several of them.

- `libcom.xiaomi.sensorpolicy.so` has an explicit `vendor.debug.camera.support3rdISZ` gate, ISZ force-enable/ISO/zoom thresholds, quad-RAW mode handling and logs about skipping third-party app modes.
- `libcam.sensor_mode_helper_custom.so` has `vendor.debug.camera.seamlessen`.
- `libmtkcam_hal_core_featurepolicy.so` contains:
  - `vendor.debug.cameng.force_sensormode` (integer, default -1; read in `evaluateSensorSetting` around VA 0x1352c4)
  - `vendor.debug.camera.start.forceSensorMode`
  - `vendor.debug.camera.start.sensorScenario`
  - `vendor.debug.force.sensor.fps.enable`, `.minfps`, `.maxfps`
  - `persist.vendor.camera3.sensor.bitdepth.device`
- `libmtkcam_resourcedecision.so` has debug resource controls including `vendor.debug.camera.p1.hwmode`, `.resourcedecision.skip_freq_check`, `.specifyRawNum`, `.assignRaw`. **Found but not changed.** Investigate values/semantics before using them; don't merely suppress an assertion.
- Root camera configuration is under `/vendor/odm/etc/camera/`, not just `/vendor/etc/camera/`.
- `CameraImageSize_gl.json` advertises a separate `supperresolutionsize` of 16320×12240 for rear wide. Camera2's standard RAW stream table remains 4080×3060.
- Xiaomi override settings include ISZ quad RAW SR support, ISO threshold 1200 and zoom threshold 2.

Actual capture outcomes:

1. Baseline: 4080×3060 RAW16 container, 8160-byte row stride, manual ISO400 / 20 ms; reported scenario 0, Xiaomi streaming mode 0, ISZ status 0.
2. Existing request-only force values 0/1, ISZ hints 0/1, internalAP and remosaic flags: echoed settings but no actual sensor transition.
3. `support3rdISZ=1` + `seamlessen=1`, 2× zoom / ISZ hint, with and without session parameters: camera HAL aborted at configure time.
4. Narrowed to `support3rdISZ=1` alone: still rejected at configuration.
5. Restored both properties: stock RAW capture succeeded (three real images, `stock-recovery.txt`).
6. `forceSensorMode=9` in session/request metadata, no root debug override: three valid images but **scenario remained 0**, so this is not working DCG.
7. Root `vendor.debug.cameng.force_sensormode=9`: rejected during stream configuration. Then cleared again.

Captured abort reason:

> Throughput not enough with specified hwMode(1), with raws(2). PLEASE CHECK USER'S SETTING! (Do adb cmds meet expectation?)

Origin: `TryBuildParamGeneratorPerf.cpp`, `updateTryBuildResult`, line 371. HAL process was `camerahalserver`; Android restarted it. This is a pipeline/resource limitation, not proof the sensor lacks the modes.

## Headless probe changes and exact next step

`SensorModeProbeInstrumentedTest.kt` now has `rootFeatureProbe` plus optional session parameter application in `Route`.

Arguments:

- `probeSensorMode` (integer request/session vendor key)
- `probeZoom` (float, default 1)
- `probeIszHints` (integer)
- `probeQuadRaw` (integer)
- `probeFps` (integer, sets fixed AE FPS and frame duration)
- `probeSessionParameters=true` (also applies extras when configuring the session)

The test captures three images and writes `root-feature-report.txt` under the isolated app's `files/sensormode-probe/` directory. **JUnit success is not sufficient:** `attempt` catches/logs camera failures, so a test can say OK while the report says FAIL. Require actual IMAGE lines and actual scenario/status changes.

Build (successful):

```sh
./gradlew :app:assembleGithubDeviceTestAndroidTest -PfreezeVersion -PphotoRcdHeadless --console=plain
```

Explicit flavor required: `assembleDeviceTestAndroidTest` alone is ambiguous. `-PfreezeVersion` prevents a version bump; the application target is `com.matthew.rawlens.rcdcheck`, not the user's normal camera app.

Updated test APK was installed successfully through the root channel:

`app/build/outputs/apk/androidTest/github/deviceTest/app-github-deviceTest-androidTest.apk`

**Next planned experiment was NOT RUN:** force camera-engine scenario 9 and configure a supported fixed 10 fps session to reduce throughput. The `probeFps` option is built and installed, ready to use. Restore root first and verify security state; reapply only the needed temporary override, then run:

```sh
adb shell am instrument -w -r \
  -e class com.matthew.rawlens.SensorModeProbeInstrumentedTest#rootFeatureProbe \
  -e probeFps 10 -e probeSessionParameters true \
  com.matthew.rawlens.rcdcheck.test/androidx.test.runner.AndroidJUnitRunner
```

Pull report from:

`/sdcard/Android/data/com.matthew.rawlens.rcdcheck/files/sensormode-probe/root-feature-report.txt`

Other promising, untested directions: correct HDR session declaration (`com.mediatek.hdrfeature.SessionParamhdrMode`, `hdrMode`; advertised CHDR modes include 64), constrain reserved seamless scenario list, trace resource-policy hardware-mode selection, then consider a narrowly scoped reversible HAL/configuration patch. No permanent library change is justified yet. Full-resolution RAW exposure needs the stream-table/framework side addressed separately from driver mode selection.

## Resume constraints

- User wants autonomous root-level investigation and explicitly headless operation.
- Do not open manager/camera UIs or ask them to keep an app foreground.
- Keep originals and a rollback for every property/config/library experiment.
- Restore failed overrides immediately; confirm stock captures and Enforcing before ending.
- Avoid more broad request-value sweeps: available keys and physical sensor modes are already recorded.
- No features should be described as working until real frames and the correct mode are verified.


## Resume 15:25–16:40: clean probes and a root camera client

- Clean baseline, correctly shaped forceSensorMode `[0,9]`, HDR session value 64, and startup forceSensorMode=9 still produced scenario 0 / RAW10 when they captured. None verifies DCG.
- enablefullraw=1 + enablelogicalraw=1 with a provider restart did not expose larger main-sensor RAW sizes. Both properties were restored.
- Earlier force camera-engine scenario 9 at 10 fps hung the sensor worker, requiring the authorized reboot. Do not repeat this blindly.
- Root RPC remains available in SELinux ksu context; Enforcing. ADB shell itself cannot su.
- Built `/tmp/rawlens-root-camera-research/RootRawProbe.java`, a DEX app_process client running as UID 0. No activity/UI. Prepare ActivityThread on the main thread, run its Looper, and perform capture work on another thread. The Android 16 DesktopModeFlags settings lookup rejects this unregistered process; set only this diagnostic process's sCachedToggleOverride to enum OVERRIDE_UNSET. Metadata uses private getCameraCharacteristics(String,int); openCameraImpl uses neutral integer arguments 0,0,false.
- Root client captured a real 4080x3060 frame, 8160-byte row stride, 24,969,600 bytes, ISO400, 20 ms, white1023/black64, scenario0/streammode0/ISZstatus0. Root does not automatically expose extra modes.
- Vendor `availableSuperResolutionStreamConfigurations` explicitly contains RAW_SENSOR 16320x12240, but root capture using sessionMode=0x9007 (ManualSuperHD) fails cameraserver roundBufferDimensionNearest size validation before HAL configure. `/odm/etc/camera/xiaomi/mivivendorstreamconfig.json` defines 0x9007 and 0x9004 with remosaic RAW16 internal streams.
- Stock package com.android.camera is absent. Do not assume it can be opened or used for package-context routing.
- `configSensorScenarios` (0x801e0007) native layout is [sensorID, numberOfModes, modes...], confirmed in updateStreamConfiguration_Sensor around 0xca294. [0,1,9] alone captures scenario0; adding hdr=1 + SessionHdr=64 caused endConfigure Broken pipe and provider restarted. No DCG verified.
- User explicitly requires information-bearing precision, not RAW10 expanded into a 14-bit container. A bpp setting, white16383, nonzero low bits, or CSI RAW14 alone is insufficient. Correlate actual driver mode, CSI format, gain mode, pixel quantization/exposure series and dynamic-range response.

### Temporary metadata patch experiment (in progress)

Original /vendor/lib64/mt6878/libmtkcam_metastore.so SHA256 `95eded2eef1767fdb07f74d31a4ef1a9b40668e6d5984d30444e0165a489a7dc`.
Offline ARM64 wrapper calls the original main HPE PROJECT constructor, then appends RAW16 8160x6120 and 16320x12240 plus minimum/stall duration entries. It does not change bit depth or active array. Entry hook VA0x60d50; original first instruction reproduced in trampoline. Wrapper occupies 792 bytes at unused IMX989LITE CUSTOM constructor VA0x509a0 (not an installed sensor); no active HPE/bokeh constructor is overwritten. All external BL targets were checked after linking; assembler absolute .equ operands initially generated wrong targets and were corrected with linker --defsym before any upload.

First bind test left public RAW sizes unchanged. Normal unmount returned EBUSY; immediately detached with umount -l and restarted HAL, confirming original hash and Enforcing. Test cleanup now always uses lazy detach and provider restart. No permanent vendor file writes, flash, or disabled security. Second test verifies mount namespaces, loaded mapping, and library hash inside the actual HAL root before evaluating metadata.

Scripts, logs, source, ELF/bin, original and patched copies are in /tmp/rawlens-root-camera-research; preserve in ignored .local evidence directory before ending. Goal remains incomplete.


### User-supplied Wyroczen modules examined (16:45)

Read-only inspection of Downloads/RedmiNote10ProGW3_RullResRaw_Wyroczen_{1.4,1.5,1.6,1.7,1.8,2.0}.zip; no installer executed and none of their files installed on lapis. Extracted relevant payloads under /tmp/rawlens-root-camera-research/wyroczen.

These target Qualcomm CamX/CHI and S5KGW3 (sweet_sunny), not this MediaTek HPE HAL. All set maxRAWSizes=2; exposeFullSizeForQCFA=TRUE is commented out. v1.4–1.6 include com.qti.chi.override.so.bak; v1.7 activates the same payload as .so. v1.7→1.8 changes just 16 bytes in com.qti.chi.override.so: four size pairs at 0x1aa1dc, 0x1ba8f4, 0x23ed14, 0x25b544 change 6016x4512 to 9280x6944. v1.8→2.0 keeps that library identical; sensor module changes six bytes in one 8-byte field near0xb4570, whose semantics are not verified. No inference about DCG/14-bit is justified from these patches. Transferable lesson: inspect exact size-limit table and sensor descriptor, not generic bit-depth properties.

### Metadata hook tracing refinement

Bind-mount visibility was verified inside the actual restarted HAL: worker/init/HAL mount namespaces all4026533596; /proc/HAL/root library hash was the patched copy, mapping inode on data partition. Original restored afterward. Thus unchanged RAW table was not a mount-visibility failure.

Moving the hook to updateData's final updateColorSpaceProfilesMap call and logging every invocation produced only sensor field3 at provider startup. This suggests the main camera metadata uses a separate import/overwrite path; field0x10 is used as sensorId by native validation. Investigating setStaticDataXiaoMi (VA0x87240), which imports caller-supplied entries into provider storage, rather than continuing to modify a constructor that may be bypassed. All tests so far rolled back; no hidden mode capture success.


### Verified metadata breakthrough 16:48

The v6 reversible patch hooks setStaticDataXiaoMi VA0x87240, appending RAW_SENSOR 8160x6120 and16320x12240 plus100ms/200ms min frame durations to main sensorId0 input. Native logs confirm scaler stream entry count1144 and duration counts1132, update status0. Camera2 ordinary RAW list still4080x3060 because the slower modes are classified as high resolution. **getHighResolutionOutputSizes(RAW_SENSOR) now returns [8160x6120,16320x12240] under the patch.** Earlier unchanged ordinary list was not proof of patch failure. RootRawProbe now prints HIGH_RES_RAW explicitly.

This verifies exposure only, not real capture. A single50MP capture test is in progress, with trap removing the mount and restarting provider+cameraserver on completion. First test uses ordinary sessionMode0 at10fps, no forced sensor mode. Preserve this working patch and source. Bit depth/white level unchanged.


###50MP container is NOT full-resolution capture (16:49)

Patched stream table allowed configuring and capturing an8160x6120 RAW16 buffer (99,878,400bytes), but result scenario0/streammode0/ISZ0 and white1023. Pulled pixels verify precisely4080nonzero samples per row through row3059, zeros across columns4080..8159 and rows3060..6119. This is the binned4080x3060 image in the top-left quarter of the larger allocation, not real50MP. Do not count this as feature success or upscaling. Current follow-up: actual driver scenario12 via temporary cameng override at10fps, same validated stream exposure patch, with automatic property/mount rollback.


###Actual50MP sensor capture verified16:50–16:51

Combining the v6 metadata patch with temporary vendor.debug.cameng.force_sensormode=12 at10fps produced a full8160x6120 RAW16 buffer. Sampled rows0,100,2000,3059,3060,4000,6119 each contain8160nonzero samples; all four horizontal quarters show scene signal. No zero-padded bottom/right region. Stronger repeated test queries V4L2 while the camera session remains open: **DRIVER ACTIVE code=0x100c300e size=8160x6120**, matching driver scenario12 RAW10. File99,878,400bytes, stride16320, white1023 black64 ISO400/20ms. This is real50MP sensor output; optical resolving power/remosaic CFA and DNG calibration remain to validate. Vendor result sensorScenario and streaming.sensormode still report0, so these are not reliable mode evidence under cameng forcing. Driver query plus populated full frame is required.

Both tests detached metadata bind and restored original sensor override/provider+cameraserver, Enforcing.50MP is an experimental headless capture path, not yet integrated plug-and-play UI. Current test: same route scenario11,16320x12240,5fps for200MP. Do not claim14-bit from50MP RAW10.


###200MP first actual-mode attempt rejected16:51

Stream exposure patch + cameng scenario11,16320x12240 at5fps reached native sensor-mode policy but aborted at SensorModeHelper.cpp evaluateConfiguration line273: checkConfigSensorMode failed. Not the earlier framework size rejection. No200MP frame produced. Overrides and mount restored, Enforcing. Need decode checkConfigSensorMode constraints and declare the remosaic/full-cell session route; do not simply bypass this assertion. Current2x crop test uses actual scenario7 at10fps without metadata patch, requesting normal4080x3060 RAW, and queries driver while open.


###2x and remaining session-type gate16:53–16:56

Root capture with cameng7 at10fps returned4080x3060 RAW and in-session driverACTIVE code0x1007301a,4080x3060, matching the driver's2x crop mode7 and RAW14 sensor bus. Delivered RAW still white1023/black64;14-bit information preservation remains unverified. Do not treat CSI RAW14 as proof of14-bit output.

Modes8(4x),9(DCG),11(200MP) fail sensor-mode helper configuration in this root ordinary-RAW session.200MP cellFull=true/configScenario11 and4x cellCrop=true/configScenario8 did not resolve it. Debug logs reveal exact4x failure: config scenario{kBinMode|kBayerPattern|->8}, sensor_mode8 is not kBinMode, SensorModeHelper.cpp1026, thenabort273. Correct session scenario classification is needed, rather than suppressing the check.4x E2E alternate sensor mode15 is being tested. All failed tests have restored sensor override and Enforcing.


###Latest checkpoint16:59

4x E2E alternate scenario15 fails the same kBinMode check as8. Full-cell declaration plus companion16320x12240 JPEG stream and patched JPEG/RAW tables still did not configure200MP. None bypasses the mode-helper assertion; this remains a session-scenario registration issue, with no200MP,4x,orDCG frame.

Test persist.vendor.camera3.sensor.bitdepth.device=14 with actual2x mode7 produced the same white1023/black64, driver RAW14 format, and dark pixels62–71. No14-bit preservation is established; this property's precise parser semantics are not established and should be decoded before more use. Restored original value automatically.

Working experimental50MP recipe: v6 metadata bind, cameng12,10fps, rootrawprobe. Trusted evidence: test-fullraw-capture-50mp-mode12-driver.log and root-raw-50mp-mode12.raw16. Source, binaries, logs, module comparisons and RAW evidence copied to ignored .local/lapis-root-camera-2026-11-11. No permanent module installation; all experiment mounts detached and properties restored. Final baseline recovery test running. Root still temporary and loaded; Enforcing.

Final recovery check passed: original metastore SHA25695eded2e…a7dc, no metastore bind mount, experimental properties empty, Enforcing, real4080x3060 baseline frame and driverACTIVE0x1000300e. Evidence final-camera-check.log.


## Native Camera2 session integration (21:57 local)

Replaced the separate UI shutter route with a session-scoped root lease. The ordinary lens switcher now offers 1× 12.5 MP, 1× 50 MP, and 2× SENSOR on the exact supported firmware when Redmi sensor modes are enabled. Camera2 in the regular app process owns repeating RAW, ZSL, forward bursts and merge inputs. Root only selects/restores the sensor mode and temporarily exposes the 50 MP stream. RAW_SENSOR is required, with 10 fps requests. The special root zoom chip and single-DNG format restrictions were removed.

A camera service restart is asynchronous: the first native test failed because camera 0 was not registered yet. Session open now polls public characteristics after the worker is ready instead of treating a fixed seven-second delay as readiness. Full-resolution viewfinder crop mapping and DNG metadata normalization use verified frame dimensions; unverified lens shading/distortion/noise calibration is omitted for these readouts. Invalid/empty quadrants fail closed.

Headless on-device acceptance, isolated com.matthew.rawlens.rcdcheck target:

- `LapisNativeSessionInstrumentedTest#fiftyMpNativePipeline`, lapisNativeSession=true, lapisMerge=true: PASS, 22.845 seconds. Real 8160×6120 sensor format checked by root JNI, 3 pre-shutter ZSL frames, production HDR+ merge of those frames, source DNG, merged DNG, and normal six-frame Camera2 burst. `/tmp/rawlens-lapis-native-50mp-merge.log`.
- `LapisNativeSessionInstrumentedTest#sensor2xNativePipeline`, same flags: PASS, 14.124 seconds. 4080×3060, driver crop scenario 7, 3 pre-shutter ZSL frames, production HDR+ merge and 6-frame burst. `/tmp/rawlens-lapis-native-2x-merge.log`.
- Focused host tests: 5 passed (4 DNG validation, 1 exact firmware gate).
- Original metastore SHA256 restored after tests, sensor-force property blank, SELinux Enforcing.

50 MP source DNG `IMG_20261111_215444_886.dng`: 8160×6120; 16-bit storage; white1023, black64×4; CFA1,2,0,1 (GBRG); ActiveArea0,0,6120,8160; crop origin0,0 and size8160,6120. Full RAW payload is 99,878,400 bytes; original information remains 10-bit. Merge `IMG_20261111_215454_540_HDR.dng` stores processed output, not proof of sensor14 precision. 2× source `IMG_20261111_215628_028.dng`, merge `IMG_20261111_215630_402_HDR.dng`.

These tests exercise the real public Camera2/ZSL/burst and production merge components without launching any Activity. They do not constitute a visual UI acceptance test of RawCameraController. Signed-release installation and killed-worker recovery validation are recorded below when completed. 4×/200 MP/DCG remain unverified and are not exposed as working modes.


### Recovery and separate installation (22:31 local)

Killed-worker acceptance `killedWorkerRestoresSensor`, lapisKillWorker=true: PASS, 41.982s. Deliberately SIGKILLed only the active 2× root worker, stopped app heartbeats, waited for the independent watchdog, then verified no active lease, original vendor SHA256, original sensor property and Enforcing. A failed close retains the lease reference for subsequent recovery instead of discarding unknown state. `/tmp/rawlens-lapis-killed-worker.log`.

The main-app update was rejected with INSTALL_FAILED_UPDATE_INCOMPATIBLE. User explicitly requested a different APK/app name and said signing did not matter. Added opt-in `-PlapisStandalone` release build: application ID `com.matthew.rawlens.redmi`, label **RawLens Redmi**, version2.2.50/code2252. Installed successfully beside the original. Granted CAMERA/notifications and the specific app's KernelSU root profile (UID10323, verified allow_su=1). Enabled the exact-firmware Redmi profile, initial50MP selection, and completed basic onboarding headlessly. Other old-app preferences were not copied. No Activity launched.

Additional4× controls tested with the bounded root probe:
- `vendor.debug.camera.start.forceSensorMode=8`, no cameng forcing: captures but driver remains0x1000300e/4080×3060, ordinary1×RAW10. Not4×.
- Combined startup8 + cameng8 + forceSensorMode[0,8] + cellCrop[0]: still rejects at SensorModeHelper1026, `{kBinMode|kBayerPattern|->8} sensor_mode8 is not kBinMode`, abort273. Automatic rollback confirmed. No4× frame.

Static analysis: libmtkcam_hal_core_featurepolicy.so reads startup force mode as a single integer at VA0x13c6b4–0x13c6c0. It has a userForceSensorMode path that can skip SMH configuration, but the tested ordinary RAW route does not activate it correctly. Correct crop/full/HDR session classification remains the blocker; no new200MP/DCG success is claimed.

Final checks after4× diagnostics: both startup/cameng overrides blank, original metastore SHA256, Enforcing. Standalone build log `/tmp/rawlens-lapis-standalone-release.log`; changes compile and git diff --check passes.

Installed minified standalone release root-worker smoke capture: PASS. Real2× driver code0x1007301a, 4080×3060, populated quadrants, GBRG DNG, white1023/black64, 24,971,240-byte DNG, restored=true. Report saved as `.local/lapis-root-camera-2026-11-11/standalone-release-capture.json`. No14-bit precision claim. Root remains active; SELinux Enforcing.


## DCG/DSG first: corrected HDR configuration and real 30 fps capture (23:20 local)

Important corrections to earlier experiments:

1. SessionParamhdrMode is an app HDR enum, not a HAL HDR bit mask. Current HDRPolicyHelper::updateAppConfigMode compares app mode3 (VIDEO_ON); passing64 was not the correct video-HDR app declaration.
2. configSensorScenarios contains logical policy scenario IDs, not driver indices. Actual sensor mode9 requested as logical9 failed with config support0x200 vs available0x40. Logical12 maps to DCG14 / HAL0x40. Mapping decoded from libcam.vhdr.so sensorScenario2HdrHalMode tables VA0x6130/0x61d8. This also invalidates assumptions in some earlier4×/200MP session-list probes.
3. HDR cfg mode and active mode differ. HDRPolicyHelper::getHDRHalMode reads current state+0x28; configuration could select64 while AE strategy turned current state off and validation rejected sensor9 with hdrHalMode0. Binary-decoded debug control vendor.debug.vhdr.strategy.config.force=2 prevents configuration-off selection (1 explicitly forces off). Runtime vendor.debug.vhdr.strategy.force=64 keeps current HDR HAL mode64.
4. DNG capture policy defaults to logical kRcfg1Exp(0x20000), which switched the sensor back to LN2 mode6 even when configuration started9. Per-frame com.mediatek.seamlessfeature.sensorScenario=[0,12] is needed as well as configuration logical12. forceSensorMode=[0,9] alone did not retainDCG.
5. Manual AE in the corrected DCG request caused AE manager to terminate the provider: updateAEInfo2ISP exp_cnt(1)/valid_exp(2) no match, line680. Automatic exposure successfully initializes the required gain/exposure states. Manual DCG controls remain unsupported/unverified.

Successful headless repeatable probe settings:

- Properties temporarily set: vendor.debug.cameng.force_sensormode=9; vendor.debug.vhdr.strategy.config.force=2; vendor.debug.vhdr.strategy.force=64.
- Request/session app HDR3, configSensorScenarios=[0,1,12], forceSensorMode=[0,9], per-frame sensorScenario=[0,12], AE ON, video template3/repeating.
- root probe9 delivered a real4080×3060 RAW frame with actual active driver0x1009301a (not the prior mode6 fallback).
- 90-frame test at requested30fps: timestamp-matched output, measured29.768179652 fps, active driver0x1009301a,25MB RAW16 source. `/tmp/rawlens-root-camera-research/test-dcg-30fps.log`.
- Another90-frame trial measured30.103787224fps, same actual driver9. Internal HAL buffer is BAYER14/0x2203, but app ImageReader stream is BAYER10_UNPAK/0x2215. Static/dynamic white remains1023, black~64, pixels~63–66. Therefore **DCG readout at ~30fps is delivered, but true14-bit information is NOT yet preserved/verified in the app**.

Precision loss traced to AppConfigUtil::extractDNGFormatInfo using static MTK_SENSOR_INFO_WHITE_LEVEL (native0xf0007) to select app RAW unpacking. Found duplicated format selectors in libmtkcam_hal_android_device.so and libmtkcam_hal_core_utils.so. Temporary hash-guarded android-layer selector override atVA0x1e178 (movw20,#16383) did not change the actual core app stream; still BAYER10_UNPAK. Original library restored, SHA256ec4057836ff00faba968e4996325441410ff84ea6fbcbbcb160a75d13424eb22. No metadata precision claim is made.

Core selector diagnostic in progress: original libmtkcam_hal_core_utils.so SHA256287418474d0ddb781e5c6c33f4c38367e690061617a0bb599ddedb836a8bd8cc, extractDNGFormatInfo VA0x41410. Temporary bind copy returns format0x2217 (BAYER14_UNPAK) instead of deciding from static10-bit white. This changes the requested format, not pixel values or reported white. All properties/binds have automatic restore traps; no permanent vendor writes. Result recorded below when complete.

Added tools/lapis-profile/analyze_precision.py for per-Bayer-phase code distributions and10-bit lattice checks. It explicitly does not certify sensor precision from code histograms. Driver DCG saturation structures specify gain_ratio16000, OB1024, saturation16368 and adc_bit10: composed DCG14 is not the same claim as a native14-bit ADC. Real range/precision must be measured, including exposure/noise response, not inferred from a14-bit label.


## 2026-11-12: manual DCG and corrected crop/full scenario investigation

Confirmed earlier core-format RAW14 success: 300 actual scenario9 frames at30.0324fps, source RAW14 unpacked. Original core-library hash restored after each test.

Warmed-up manual experiment: initialize DCG RAW14 with AE ON for45frames, switch CONTROL_AE_MODE_OFF with ISO400/10ms while retaining logical12/DCG settings. Logged SWITCH_REQUEST frame45 ae0; provider terminated, no completed120-frame manual sequence. Warm initialization alone does not fix standard manual AE. This is not proof all vendor/manual implementations are impossible.

AE lock comparison:120frames, lock at45, measured30.078018477fps. Timestamp-matched final result aeMode1, aeLocktrue, ISO12800/30.007ms; white15424, black~1024; actual driver0x1009301a. Exposure can be frozen through supported AE lock, but this is explicitly AE ON, not arbitrary manual ISO/shutter.

Request key discovery includes Xiaomi stagger exposure/gain/ISO lists, but their applicability/units are unverified; no arbitrary setters or HAL assertion suppression applied. The practical supported DCG path remains AE ON, optionally locked.

Corrected4×logical19 + actualmode8 with camera-engine override still fails initial kBinMode registration. Adding startup.sensorScenario19 + startup.forceSensorMode8 and removing cameng gets past configuration, but no completed RAW sequence yet; capture errors and request-level logs saved. 200MPlogical3 + sensor11 still fails kBinMode with cameng. Next bounded200MPtest sets startup.sensorScenario3 + startup.forceSensorMode11 without cameng, with original metadata exposure patch; automatic rollback retained.
