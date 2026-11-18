# Lapis root camera investigation — paused 2026-11-11

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

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

Logical19 was initially tried for4×, but decoded policy logs identify it as kBinModeCMS (flag0x40), not kCropMode4x (flag0x80). Logical19 + actualmode8 with camera-engine override still fails initial kBinMode registration. Adding startup.sensorScenario19 + startup.forceSensorMode8 and removing cameng gets past configuration, but no completed RAW sequence yet; capture errors and request-level logs saved. 200MPlogical3 + sensor11 still fails kBinMode with cameng. Next bounded200MPtest sets startup.sensorScenario3 + startup.forceSensorMode11 without cameng, with original metadata exposure patch; automatic rollback retained.


### 2026-11-12: evidence recheck of 50 MP and 2× ISZ

Rechecked saved capture logs, RAW payload and sensor-driver source. 50 MP: actual active driver0x100c300e (mode12),8160×6120; payload99,878,400bytes exactly. Sampled full rows0,100,2000,3059,3060,4000,6119 have8160 nonzero samples each and signal across all four horizontal quarters. Driver mode12 reads full16320×12240 area and scales to8160×6120. This establishes a sensor50MP readout rather than merely a larger app allocation; optical resolving power and remosaic accuracy still require scene/chart validation.

2×: actual active driver0x1007301a (mode7),4080×3060; payload24,969,600bytes. Driver custom3 explicitly selects central8160×6128 region (x4080,y3080) from full16320×12288, scales4080×3064 and trims4080×3060. This is a real sensor crop with2× horizontal field-of-view relative to full width, not an app-only digital crop. Delivered RAW in this verified route remains10-bit (white1023/black64), despite RAW14 at the sensor interface. Previously completed native Camera2 acceptance for both modes passed ZSL, production merge and six-frame bursts.

Current connection has shell UID2000, su unavailable at standard and KernelSU paths, sensor subdevice permission denied; no root worker observed. Therefore this recheck validates recorded evidence, not a fresh post-reboot rooted capture. Readable experimental DCG/sensor override properties are blank and no matching vendor bind mounts appear in shell mount output. No re-root exploit was run in this recheck.

Interrupted manual-DCG test completed its EXIT rollback before this recheck: core hash28741847…b8cc, Enforcing. AE_OFF with AE_LOCK=true still disconnected after switch frame45; no verified manual DCG result.


## 2026-11-12 16:20: real 4× sensor RAW achieved; 200 MP deferred

Temporary root restored headlessly on identical kernel/firmware. Fresh hashes matched all original vendor libraries, Enforcing. Corrected mode-class diagnostics preserve original SMH and resource validation. ConfigIn mode8 needs general crop+Bayer flags0x801, and libcam.sensor_mode_helper_custom.so isCrop must register documented sensor0/mode8 as a crop. Separate kCropMode4x flag0x80 has no registered scenario in this HAL. No assertion bypass was applied. Builders: tools/lapis-profile/research/build_smh_class_probe.py and build_crop8_registration_probe.py; firmware-hash guarded, generate copies from local original libraries, do not ship proprietary binaries.

Successful 4× recipe: both helper patches temporarily bound at /vendor/lib64 and /vendor/lib64/mt6878 aliases; cameng8, startup logical4, no startup forced mode; request/session configSensorScenarios=[0,1,4], configCellCropSensorIds=[0], forceSensorMode=[0,8], per-frame sensorScenario=[0,5], zoom4. 30-frame AE ON test measured10.1405fps, in-session DRIVER ACTIVE0x1008301a,4080×3060, populated RAW. Repeated90frames at30.1057582fps, same actual driver. RAW14 app-format patch produced white16383/black1024 but **all sampled Bayer phases lie on the left-shifted10-bit lattice**; no14-bit precision claim. Stock app-format selector, manual AE OFF ISO400/20ms also passed10frames with actual driver8, white1023/black64. This stock RAW10 path is selected for app integration.

200MP progress: ConfigIn mode11 needs full+Bayer0x802. This passes initial sensor-mode checks, then kernel RAW-resource allocation rejects the pipeline. Both on-the-fly and direct-coupled paths tested; requesting1fps does not reduce the kernel resource calculation's sensor-native5fps. Kernel logs show one available RAW engine (mask0x2). Explicit two-engine request rejected by ResourceEvaluator: max possible raw for one sensor1, budget1. No200MP frame. User explicitly deferred200MP and requested app integration of4×. All200MP properties/binds restored, normal RAW baseline passed, actual driver0x1000300e, Enforcing.

4× app integration and headless acceptance recorded below when complete. No app UI launched. Experimental evidence remains in ignored .local/lapis-root-camera-2026-11-11 and /tmp/rawlens-root-camera-research.


### 4× app integration completed (2026-11-12 17:29 local)

Added exact-firmware CROP_4X mode/persisted selection, “4× · SENSOR” lens-switcher entry and4× capture badge. Photo-only route follows2×: standard4080×3060 RAW10, manual AE supported,10fps session, ordinary app ZSL/bursts/merges. No CRAW14/native precision claim; the RAW14 app-format override is not used for4×. Session/request crop keys retain actual driver mode8. Worker builds two small classification patches from hash-matched local original helper libraries; proprietary binaries are not shipped. Temporary binds cover both vendor library aliases. Nonce-scoped EXIT/watchdog restoration verifies all four helper paths plus core/metastore hashes before releasing the lease. Firmware/hash mismatch fails closed.

Headless acceptance on isolated com.matthew.rawlens.rcdcheck, no Activities:
- sensor4xNativePipeline, lapisNativeSession=true/lapisMerge=true: PASS11.975s. Hardware verified,4080×3060,white1023/black64,3 pre-shutter ZSL frames, production3-frame HDR+ merge and6-frame forward burst. Source IMG_20261112_172318_989.dng; merge IMG_20261112_172321_153_HDR.dng.
- killedWorkerRestoresSensor, lapisKillWorker=true/lapisKillMode=4x: PASS37.179s. Killed the actual4× worker; independent watchdog restored all added library binds and sensor state.
- sensor2xNativePipeline regression: PASS6.346s, ZSL/DNG/6-frame burst.

Built using isolated /tmp/rawlens-4x-isolated-build because parallel builds replaced the common APK output with lkrcheck artifacts. GitHub device-test and minified release builds passed. Installed separate **RawLens Redmi2.2.82**, com.matthew.rawlens.redmi, versionCode2282. Root grant retained for UID10323. Local ABI-targeted build required adb install -t. Existing app data/profile preserved; user can choose4× in the lens switcher. No UI launched.

Installed minified-release worker smoke: PASS,10frames at10.000633fps, actual driver0x1008301a,4080×3060 RAW10,AE OFF ISO400/20ms,white1023/black64; report restored=true. Evidence/logs copied to .local/lapis-root-camera-2026-11-11/app-4x-acceptance. Temporary root remains active, Enforcing.200MP deferred at user request; no200MP overrides remain and no200MP mode is exposed.


## 2026-11-14: 200 MP before remosaic — resource boundary verified

Target: a populated 16320×12240 mode-11 RAW frame before vendor remosaic, then verify native CFA before designing 200→50 MP conversion. No 200 MP frame delivered yet. Merely exposing a stream or accepting sensor configuration does not prove capture.

Two bounded headless probes used metadata-v6 stream exposure, mode-11 full+Bayer ConfigIn classification (0x802), logical startup scenario 2, actual sensor override 11, RAW_SENSOR 16320×12240 at 5 fps, template 3, processRaw=0. The second additionally set vendor.debug.camera.raw.type=1, vendor.debug.camera.cfg.ProcRaw=0, vendor.debug.camera.app.process.raw=0. These are requested debug settings; logs do not prove that downstream pure routing was activated, because configuration fails earlier. Both fail endConfigure with Broken pipe before frames.

Kernel evidence: raw sink TRY format 16320×12240 code 0x100b300e; mtk_cam_raw_check_line_buffer rejects width 16320 with max_w 4704 unbinned and 9408 with ordinary binning. One RAW engine available, mask 0x2. Thus reduced requested fps or RAW output type alone does not solve this width constraint. Do not bypass the resource validator or misreport a smaller sensor width.

Source inspection: ISP7sp CAMSV declares maximum 18000×16000 (mtk_cam-sv_pipeline.h), large enough dimensionally, and update_sv_pure_raw in mtk_cam-job.c routes MTK_RAW_PURE_RAW_OUT through SV in normal/mstream scenarios. This is a candidate route, not proof of a working 200 MP pipeline; regular resource allocation still precedes that job routing in the tested HAL. Kernel CBN_4X4 support exists; userspace session library contains NCell CBN handling in PipelineModelSession4Cell::doCapture. Need decode intended session/resource plumbing to preserve full pure output while reducing the ISP branch, or construct a standalone CAMSV capture. No assertion-bypass patch tested.

Cleanup verified original metastore SHA256 95eded2eef1767fdb07f74d31a4ef1a9b40668e6d5984d30444e0165a489a7dc and SMH ec0224cf5054e8ed100d024cbbb6150cd64944a0e26b9377586f0e5971e639a1, all five changed vendor properties restored blank, kernel debug_opts restored, camera services restarted, SELinux Enforcing. Temporary root remains active. Scripts and HAL/kernel logs saved locally in .local/lapis-200mp-2026-11-14/. No UI change or 200 MP capability claim.

### Capture-time switching follow-up, 2026-11-14

Removed the startup/camera-engine mode overrides and used a single still capture with AE on. The HAL already selects its 4Cell session; vendor.debug.camera.PipelineModelFCell is read in that session constructor and was not assumed to be a session selector. Normal startup successfully configured at mode 0 with a 16320×12240 app RAW buffer and pure-RAW node polling. Request scenario 2 translates to kFullMode|k4CellPattern (0x102), but no matching sensor mode was registered: inferSensorMode fails. Corrected request/config logical scenario 3 translates to kFullMode (0x2), selects mode 11, then fails inferSwitchPolicy: sensor_mode:11 no mode_2_hw_setting_. No output image. This establishes a later failure than forced startup, but hardware-resource registration remains unresolved. No resource or assertion checks bypassed.

Added tools/lapis-profile/research/media_survey.c, read-only enumeration of media entities and links, compiled for arm64 with -Wall -Wextra -Werror. Device /dev/media0 exposes raw-0 pure-raw (81:6), raw-1 pure-raw (81:26), and CAMSV 0–15. Presence does not establish independent full-resolution capture capability. Graph and probe artifacts saved in .local/lapis-200mp-2026-11-14. Both detached probes completed rollback with original library hashes and Enforcing.

### 2026-11-14 11:55: 200 MP RAW buffer delivered with CBN 4×4 resources

First real full-size buffer: 16320×12240, stride 32640, pixelStride 2, 399513600 bytes. Probe completed two frames at 5.00025 fps; active sensor subdev code 0x100b300e, mode 11. RAW10 unpacked into 16-bit storage, not 14-bit. Timestamp-paired result ISO400, 20ms, AE off, white1023, black64. Samples near black (central probe min49/max85/mean64.0676); no illuminated scene/CFA or image quality validation yet. Offline 1024-square sample min42/max86/std2.0916, adjacent equality13.86%, not a uniform or simply repeated-pixel buffer; this alone does not prove optical resolution or CFA. File saved .local/lapis-200mp-2026-11-14/pure200.raw16, integrity.json alongside it.

Key fix: resource coordinator Bin mapping at VA0x34000 maps 3→CBN2x2(0x10),4→CBN3x3(0x20),5→CBN4x4(0x40). New firmware-guarded build_mode11_cbn_probe.py changes the requested Bin in TryBuildParamGeneratorBasic::parseBinMode only for sensor0/mode11, retaining all hardware-resource validators. Original resource library SHA2566b61f4fdbe97630556d83472f02e7b8a168a6bbdaf644838a5a338c8c2d97f90; diagnostic584db957e220ed8c543e1b367c75efc80ab381e534d940956b28a743e80be693. Stub independently checked against clang AArch64 assembly. This is experimental, not an app asset.

Successful recipe adds that resource bind to metastore-v6 + mode11 full/Bayer SMH bind, forces sensor11 at startup, uses full-Bayer request/config logical scenario3 (not scenario2's 4Cell requirement), raw.type=1, cfg.ProcRaw=0, app.process.raw=0, processRaw=0, repeating template3 at5fps. Kernel accepted actual sink and PURE_RAW_OUT at16320×12240, bin64, one RAW engine mask0x2 at416MHz; CAMSV tag reports full16320×12240 code0x100b300e, CAMSV1 starts. HAL useP1DirectAppRaw=1. Follow-up job-level debug will verify pure-RAW assignment directly. Do not yet claim Hexadeca output: driver CELL_4X4 declaration remains commented out and CFA must be measured under illumination.

First CBN launch was invalid because the pushed file lacked vendor_file SELinux label: loader reported resource library not found, IDS empty. After chmod644/chcon vendor_file it loaded with Enforcing; this early loader failure is not a sensor-mode failure. Initial loaded run accepted resources but request scenario2 failed 4Cell inference. Scenario3 run passed. All binds/properties/kernel debug flags restored automatically afterward, original three library hashes verified, Enforcing.

Job-level routing follow-up passed (PROBE_EXIT0), with active sensor11 and restoration hashes verified. Kernel explicitly logs update_sv_pure_raw has_pure/processed_raw:1/0 is_supported_scen:1 sv_pure_raw:1 repeatedly for actual capture jobs; initialization-only jobs have0/0 and are not capture evidence. This verifies CAMSV pure-RAW routing before normal ISP processing. It does not establish whether the sensor itself remosaics, nor Hexadeca CFA. Follow-up artifacts saved beside the original capture.

### Bright-scene validation, 2026-11-14

User aimed at illuminated room. Same verified mode11/CBN4/pure route captured8frames,5.03625fps; saved last 16320×12240 RAW10-unpacked frame (399513600bytes). AEON ISO688/20.005621ms; frame signal mean146.20, sparse min51/max542, active driver0x100b300e. Valid room image seen in CFA-independent 8×8 block-average overview; not a black/dummy buffer. Kernel again has has_pure/processed_raw:1/0,sv_pure_raw:1. Restored all three original library hashes, properties and debug flags; Enforcing.

CFA analysis: subtract black64, split central8192×12288 into8×8 tiles, retain1299020 tiles with mean signal>25 and maximum<900, normalize each by its mean. 8×8 phase response has four 4×4 quadrants with means1.2798,0.7011 /0.7436,1.2756. Best4×4 grouped model phase residual MSE0.004254 vs ordinary1-pixel Bayer0.078891 and2×2 grouped0.081411, about18.5×lower error. Strong evidence of native4×4 grouped/Hexadeca CFA, not ordinary remosaiced Bayer. The matching diagonal quadrants are consistent with green in driver's grouped GBRG orientation; R/B identity remains based on driver declaration rather than independent colour-chart calibration.

A diagnostic preview averaging each4×4 group and assembling2×2 GBRG RGB superpixels produces plausible room colour, supporting this interpretation. Preview is low-resolution inspection only, with estimated white balance and no calibrated colour matrix. No claim of 200MP optical detail: focus held at0diopters,ISO688, indoor scene; requires separate detail/quality testing. Simple2×2 averaging of this stream would yield50MP with2×2 grouped CFA, not conventional Bayer. The200→50MP Bayer route therefore requires CFA-aware rearrangement/interpolation or remosaic, not just averaging. Artifacts in .local/lapis-200mp-2026-11-14/:bright200.raw16,bright-analysis.json,bright-overview.jpg,bright-grouped-gbrg-diagnostic.jpg,bright-mosaic-patch.png,bright-{result,hal,kernel}.log.

### 2026-11-15: Hexadeca linear RGB JPEG output integration

The completed v2 Hexadeca worker produces50MP linear cameraRGB and LinearRaw DNG. Added output-format snapshot in RawCameraController and JPEG/JPEG+DNG/DNG dispatch in LapisRootCapture. Worker retains RGB16 only when a develop-request marker exists and writes an exact-frame color-metadata sidecar (matrices, illuminants, neutral, orientation and exposure). LapisHexaDevelop normalizes uint16 to float through mmap outside ART heap and calls RawDevelopmentCoordinator.developMfsrJpeg, the existing RGB-merge path (no Bayer reconstruction/second demosaic), then JpegSaver with the selected look and sharpening settings. Shared timestamp/stem pairs JPEG and DNG. Temporary buffers are deleted; worker relinquishes ownership to the app. DNG-only callers retain default behavior. Kotlin and worker Java compilation passed. Headless JPEG device validation pending at time of entry.

JPEG acceptance completed: synthetic linearRGB/sidecar test PASS0.395s, full200MP→50MP JPEG+DNG PASS81.951s headlessly in isolatedcom.matthew.rawlens.rcdcheck. Actual files IMG_20261115_083200_752_200TO50MP.jpg (38134196bytes, decoded8160×6120RGB,orientation1) and matching.dng (299637530bytes,LinearRaw). Reportrestored=true,no camera lease,Enforcing. Existing v2conversion55587ms (GPU49643ms,352tiles),DNGwrite3293ms; JPEG feature does not imply faster conversion. Tests use fixedISO400/20ms/focus0,so image/exposure quality comparison is not part of acceptance. Report/JPEG/validation stored.local/lapis-hexa-jpeg-2026-11-15/. RegularRedmirelease build/install pending at this entry.

## 2026-11-16: ISZ oil-painting report, saved-data analysis only

User reports poor detail in2x/4x and chose saved-data analysis before a fresh illuminated comparison. No device mutations or production changes made. USB is present,adb shellUID2000,Enforcing; root availability not established by this read-only check.

Current LapisRootSession.Spec.applyRequest selects crop scenarios but does not explicitly request processRaw=0 or force pure-RAW policy. This is a candidate test, not proof that current output is processed RAW. Archived4x HAL logs show useP1DirectAppRaw=1; directP1 does not independently prove sv_pure_raw routing. The successful200MP recipe uses raw.type1,cfg.ProcRaw0,app.process.raw0 and kernel job-level verification; test an equivalent guarded ISZ recipe before assuming benefit. Camera2NR/edgeOFF alone cannot bypass upstream sensor processing.

Saved driver source,lapiss5khpewide-source/lapiss5khpewidemipiraw_Sensor.c: mode7custom3 2X crops8160x6128 from(4080,3080),scales4080x3064 then trims3060high. Mode8custom4 4X crops4080x3072 from(6120,4608),scale1:1,then trims3060high. Both declareRAW14_Gb,without grouped cell declaration. Actual optics/detail remains unproven. Mode15custom11 4XE2E uses identical4x crop geometry but declaresRAW14_4CELL_R andCELL_2X2. Thus candidate groupedRAW route exists in source; it is NOT working yet: older attempts failed mode-helper registration. Need correct session registration,active driver15verification, actual pixel CFA verification,proper R/G/B phase and normalization. Do not expose it under ordinary Bayer processing or copy unknown sensor register values.

Compared mode8/custom4 and mode15/custom11 source tables page-aware:20 differing direct register assignments; semantics not decoded. Saved comparison.local/lapis-isz-detail-2026-11-16/saved-analysis.json. Oldisz4-register-crop8.raw16 is RAW14-container/leftshift10bit: sparse normalizedDNmin51,max83,mean65.07,black64. It cannot establish remosaic softness,scene detail orCFAcolour grouping. Earlier2xprobe min62,max73 likewise unusable for sharpness. No quality conclusion from these near-black files.

Next controlled comparisons: same bright detailed scene,matched focus/exposure/gain; save original RAW and neutral noNR/no-sharpen development. Compare current modes7/8 vs explicitpureRAW with kernel routing proof. Then explore15grouped mode; alternatively centre-crop verified200MPgrouped source and reconstruct the intended output,retaining native samples. The200MPfallback operatesat5fps and cannot be presentedas a30fpsISZreplacement. Preserve current working profiles pending actual improvement evidence.

### ISZ pure-RAW test preparation, 2026-11-16

User authorized boundedISZpure-RAW tests. CurrentADB isUID2000;su unavailable. User reported root restored, but retry still shell and vendor reads denied. Built and installed a separate minimal headless instrumentation com.rawlens.iszdiag targeting existingcom.matthew.rawlens.rcdcheck, signed with matching debug key; its app-context ProcessBuilder(su,-M,-c,id) failed error13Permissiondenied. Thus neither diagnostic channel currently has usable root. No camera overrides, mounts, service restarts or captures were performed. Asked user to verify GhostLock recovery and grant the already-installed targetapp root inKernelSU.

Prepared tools/lapis-profile/research/test-isz-pure-ab.sh: mode7or8baseline/pure pair, fixedISO/exposure/focus,NR0edge0,8frames10fps, originalproperties saved, exactSMH/customhashguards, mode8classificationbindsatcanonicalpathsonly,debugJOB+resource logs,35sprobe limit,EXITrestoration with originalhash/property/debug checks. FreshRootRawProbe compiled and stagedasrawlens-isz-ab-probe.jar, script template andknown4xhelpersuploaded; none executed. Shellsyntaxcheckpassed. Minimalrunner source+manifest saved.local/lapis-isz-detail-2026-11-16. Production app unchanged. Need actualrootpermission andilluminated stationaryscene to establishquality;kernelroutealonewillnot prove recovered detail.

## 2026-11-16: DCG ratio cross-check against published source, vendor module and app

User asks whether actual maximum is8x ratherthan16x. Rechecked archived device imgsensor.ko directly viaELFsection/symbol parsing,notjustpublicsource: imgsensor_saturation_info_idcg at.data+0x10024 containsu32[16000,1024,16368,10,64]; lapiss5khpewide_dcg_ratio_table at.data+0x10038 contains16000. TheHPE20-entry mode_struct is9600bytes,480bytespermode. Mode9 has dcg_info[mode,gain_mode,gain_base,min,max,step]=[1,1,1,16000,16000,0],minoffset420; modes10/13/16 also16000. No8x clamp is in these mode tables.

Publishedlapis-v-oss source atcommit28ccc0a419b5ac1d1e468711c1c76e8d9a22cd06 agrees: mode9commentcaptureiDCG+DSG,HDR_RAW_DCG_COMPOSE,LCGbase,min=max16000,step0,table16000,RAW14_Gb. Adaptorsubdrv-ctrlget_dcg_gain_ratio_range_by_scenario returnsdeclaredvalues andget_info forwards saturation_info gain_ratio toHAL;baselinegain_ratio1000. Currentapp selects sensor9 andHDRcompose64,requests appRAW14,usesblack1024/white16383;itdoesnotprogram16000orapply16xpixelgain. Thus16:1 is thedriverdeclaredCOMPOSED iDCG+DSGratio,not a measuredphysicalconversiongainratio,nor measured4stopDRimprovement. An8:1physicalDCGlimitcanpotentiallycoexistwitha16:1combinedDCG/DSGdeclaredratio,but8xDCGtimes2xDSGisnotprovedbythesefieldsandmustnotbeasserted. Previoususer-facing16:1claimshouldbecaveatedaccordingly.

CurrentUSBwasunavailableduringthischeck;no freshlivedriverqueryorhardwareratiomeasurementperformed. Evidence+downloadedmatchingadaptorcode saved.local/lapis-dcg-ratio-2026-11-16/.

### 2026-11-16: ISZ+DSG tests prepared with mandatory DCG recovery check

User authorized modes13(2xDSG)and16(4xDSG),explicitlyrequiringDCGsessionrecoveryaftereachwithoutreboot. Preparedresearch/test-isz-dsg-recover.sh: exactcore/metastorehashguards,AEONHDR3/config12/scenario12,knownmode9capturebeforeexperiment,one4080x2304mode13or16attempt,EXITunmount/propertyrestore/servicesrestart,thenfreshmode9RAW14capture,finaloriginalhash/property/Enforcingchecks. StopsifDCGbaselineorrecoveryfails;noassertionsbypassed. Builttemporaryownedmetadatawrappervariantadding4080x2304RAWstreamanddurations(basedonexistingmetadata-hook.S),appliedtodiagnosticcopyoforiginalmetastoreonly;existingRAW14formatselectorcopystagedseparately. Neitheractivated.

Rootblock:ADBUID2000,suunavailableafteruserreportedrecovery;oldrcdchecktargetappremoved.KernelSU/GhostLockinstalledbutthisdoesnotproverootADB. Oldheadlessrunnercouldnotfindtarget;newtemporaryrunneragainstregularappwasrejectedbysignaturecheckandneverstartedinsideRawLens.Noattempttoreplaceregularapporsignaturechecks. AskeduserforShellUID2000rootorrootADBrecovery. NoDCG/ISZDSGcaptures,propertychanges,bindmounts,cameraservicerestartsorrebootsperformedinthisturn. Savedwrapperresearch.local/lapis-isz-dsg-2026-11-16;testfilesuploaded/data/local/tmp/rawlens-{dsg-core.so,dsg-video-metastore.so,test-isz-dsg-recover.sh}. Do notreportmode13or16asworkinguntilactualframesandactiveformatsverified.


### 2026-11-16: 2× DSG integrated and verified headlessly

Root restored as ADB UID0 / KernelSU, SELinux Enforcing. Mode13 produced illuminated 4080×2304 RAW at 29.923 fps (actual driver code 0x100d301a); subsequent mode9 recovery passed. Earlier dark experimental samples were taken with the lens covered, as clarified by the user. Mode16 failed the unmodified sensor-mode-helper binning validation and is not exposed in the app.

Added a separate `2xdsg14` / “2× DSG · Auto AE” lens route, retaining plain 2× and DCG14. Uses mode13, HDR configuration2/runtime64, Auto AE, 30fps, RAW14 unpacking, and a separate owned metadata wrapper advertising 4080×2304. The exact-firmware lease/watchdog restores both metadata and core library bind mounts, HDR properties and sensor override; cameraserver stream metadata is refreshed on entry/exit and handoff. No proprietary library payload is shipped. Video UI recognizes this route; shared DCG metadata, CRAW bit depth and automatic-exposure restrictions apply.

Production app headless test `twoXDsgNativePipeline` passed: hardware-verified 4080×2304, 90 frames at 30.1044fps, three pre-shutter ZSL frames, six-frame burst, three-frame HDR+ merge. Source and merged DNGs retained a measured 17×17 remaining shading map (max1.74353); white15424/black1024. `twoXDsgRawVideoContainer` passed CRAW encode/decode and >10-bit sample checks. These verify RAW14 handling, not 14 effective ADC bits or a measured gain ratio.

`killedWorkerRestoresSensor` passed for 2xdsg14 (37.344s); subsequent `dcg14NativePipeline` passed without reboot (10.57s). Original vendor hashes, no lease and Enforcing verified afterward. Built and installed separate user-facing RawLens Redmi 2.3.52 (com.matthew.rawlens.redmi), configured its root grant and selected 2× DSG headlessly. Existing regular RawLens remains installed. Builds used the device-targeted test-only flag; installation requires adb -t. Evidence: .local/lapis-isz-dsg-2026-11-16/mode13-bright and app-native-2xdsg14.json; host logs /tmp/dsg-{native-test,video-test,watchdog-test,dcg-regression}.log. Optical detail and effective precision remain separate validation tasks.

### 2026-11-16: encoded-video stale stream-size fix; stored-code precision

User logcat logcat-20261116_130950_653 (1).txt shows encoded-video startup rejecting `DCG14 RAW size 4080x2304 unavailable`, after reporting only stock4080x3060. This is distinct from the passing CRAW recorder test. EncodedVideoRecorder now resolves sensor sizes from the active root lease when present, matching photo capture's existing approach. Normal devices/lenses retain Camera2 stream-size discovery and the existing resolution ladder. Output codec dimensions remain independent from sensor readout dimensions. Live root-driver verification and pixel validation remain enforced.

Added headless MP4 capture tests for2×DSG at720p,1080p,4K; tests inspect video track dimensions and require >=20 encoded frames. Phone disconnected before installing this follow-up; user requested finishing builds for now. These new tests are compiled but NOT yet device-validated; fix is NOT yet installed. Earlier CRAW/ZSL tests remain valid only for their tested paths.

User DNG IMG_20261116_131028_304.dng metadata checked:4080x2304,GBRG,16-bit storage,white15424,black1024,four GainMap opcodes. User v7.1 report finds all four CFA planes on a strict4DN lattice, not8DN: about11.913 bits across the nominal stored range. Therefore this sample demonstrates roughly12-bit stored-code granularity in a14-bit nominal range, NOT full14-bit code precision. It does not establish physicalADC depth, noise-limited precision or dynamic range. Heuristic11.2931 score is not measured sensor bit depth. Do not call this sample true14-bit precision merely because its format isRAW14.

Follow-up validation: Github release and isolated device-test APK builds succeeded; EncodedVideoLadderTest and VideoCameraRouteTest passed. MP4 device tests and installation pending USB reconnection.

### 2026-11-16: 60 fps mode4 investigated; delivery gaps remain

User requested60fps investigation. Phone reconnected; ADB UID0/KernelSU, Enforcing, no production root lease. Matching source mode4/slim declares4080×2304,full-width16:9,binned from16320×12288,max_framerate600,CSI datatype0x2b RAW10. Clock1760000000/(7296*4020)=60.00698fps. This is ordinary single-exposure RAW10, not DCG/DSG or RAW14.

Added bounded research/test-raw60-recover.sh, preserving sensor/HDR/startup/raw-policy props, original core+metadata hashguards, EXIT rollback, and actual DCG9 capture before and after each trial. Exposes4080×2304 using the previously tested owned video metadata wrapper; no helper assertion bypass or permanent vendor write. RootRawProbe gains configurable reader queue and pixel format, packedRAW10 sample inspection, optional640×480YUVpreview, configurable bounded image timeout, frame-duration and interval/steady-cadence diagnostics. Defaults remain the earlier probe behavior.

Measured actual driver0x1004300e,4080×2304 in every successful trial:
- RAW16 container,one reader buffer,240frames:46.1146fps.
- RAW16,eight buffers,240frames:53.1171fps; median16.665ms,max33.330ms.
- Eight buffers,explicitpureRAW controls/NR0/edge0,600frames:46.3197fps. No improvement; property requests alone are not fresh kernel routing proof.
- Eight buffers plus640×480YUVpreview,600RAWframes:58.9247fps; median16.665ms,max49.994ms.
- Same streaming shape,1200frames:55.9902fps overall,55.8390fps after60-frame warmup; max66.660ms. This disproves startup-only loss explanation. Initial long attempt hit old15s diagnostic timeout; DCG recovered; corrected to bounded30s image wait and35s outer timeout for rerun.
- PackedRAW10 pluspreview,eight buffers,600frames:58.1619fps overall,58.0676fps afterwarmup. Payload11796480bytes,rowStride5120,pixelStride0,versus18800640bytes RAW16. Owned metadata wrapper variant advertisesformat37 at4080×2304, applied to exact-hash original metastore copy; no core-format patch duringmode4. RAW10 inspection decodes each4pixels/5bytes group. Resultwhite1023/black64. Packed transfer works, but alone does not remove delivery gaps.

Mode4 result frameDuration16675000ns and median timestamp interval16.665ms establish nominal60fps sensor cadence. Sensor60fps is NOT equivalent to continuous60 delivered or encoded frames. No app60fps option added; no CRAW/HEVC60fps acceptance claimed. Next bottleneck investigation should examine capture/streaming scheduling and request/buffer stalls; both buffers and presence ofpreview materially changed delivery, but this does not uniquely identify the cause. Optical quality remains untested.

All completed experiments, including failed diagnostic timeout, restored original libraries/properties and passed a freshDCG9 stream afterward. Final packed trial status0/recovery0, metastore95eded2e…97dc/core28741847…8cc,no production lease, sensor override blank,Enforcing; no reboot. Evidence .local/lapis-raw60-2026-11-16 contains RAW samples,HAL logs and recovery reports; packed wrapper source/bin/build provenance saved there. User-facing camera mode remains unchanged.

### 2026-11-16: app-wide FPS options and mode4 app integration

User explicitly requested24/30/60fps choices in both RAW and encoded video, app-wide rather than restricted to root. Encoded settings/quick control now persist and cycle24→30→60; recorder accepts60 and uses it for encoder/CFR timing and manual exposure budget. RAW picker always exposes24/30/60 alongside Default and other advertised rates; its per-lens selection now drives RawVideoRecorder requests, manual frame budget, CRAW nominal metadata, sensor-info text and timecode (previously recorder used hardcoded30). General routes retain advertised range selection/capability checks: exact fixed ranges when available, containing advertised ranges otherwise; UI labels actual AE ranges or unadvertised status. These options do not unlock unsupported sensor or encoder modes on other phones.

Separate exact-firmware `video60` route added to lens switcher as main1×·60FPS: mode4,4080×2304 RAW10, metadata-only temporary bind, no RAW14 core patch. Spec supports fixed24–24,30–30,60–60 requests, frame-duration budgets and HDRoff;24/30 step-down operates in mode4 rather than silently switching sensor modes. RAW per-lens selection also updates the preview rate via root profile and serialized lease refresh. Root video lease transfers to either recorder; mode-specific driver verification/pixel checks remain. Both recorders attach640×480YUV companion stream only for this mode, closing it during teardown, as supported by throughput experiments. Generic devices retain their previous stream topology. Existing DCG/DSG readouts keep their fixed30fps restriction. Recording FPS is separate from sensor dimensions and encoded output dimensions.

Headless Video60ModeInstrumentedTest passed both methods / all six recordings in49.84s. RAW4080×2304 CRAW requested24→23.99795fps/83frames,30→30.00341fps/103frames,60→46.96184fps/163frames. CRAW frame dimensions decoded successfully and nominal FPS metadata matched selections. Thus60fps is selectable but full-res compressed RAW delivery did not sustain60 in this test. Encoded1280×720 MP4:82/103/203frames at requested24/30/60; extracted CFR timelines24/30/60.00001fps. These are output timeline rates, NOT independent proof of60 real-time sensor/encode throughput: existing EncodedVideoRecorder uses constantPtsUs(frameCounter,fps). No full-res encoded or sustained4K60 acceptance claimed.

Tests passed: LapisRootProfileTest2,EncodedVideoProbeTest31,VideoCameraRouteTest9,VideoManualPolicyTest10 (52 unit tests). Headless DCG regression after recordings passed10.07s. Modevideo60 killed-worker recovery passed37.011s; freshDCG capture afterward passed9.811s, no reboot. Final original metastore/core hashes, blank sensor override, no active lease and Enforcing verified. Release and device-test builds passed. Updated RawLens Redmi separately from regular RawLens; selected newreadout headlessly. Final FPS-label-only packaging/install noted below. Reports saved.local/lapis-video60-app-2026-11-16/video60-{raw,encoded}.json; host tests/tmp/rawlens-60fps-{device,dcg-recovery,watchdog,final-dcg}.log. All device testing used noActivity.

Final FPS-label build passed and RawLens Redmi2.3.62/versionCode2362 installed successfully with adb-r-t-g. Installed package verified; original readout selection preserved, no active root lease, blank override,Enforcing. NoActivity launched.

## 2026-11-18: 50 MP B&W Detail crash — JPEG readback ART heap OOM

User log Downloads/logcat-20261118_082937_226 (1).txt. At08:38:10.545 monoDETAIL8160×6120 completed2102.8ms; BW DNG IMG_20261118_083807_521_BW.dng saved08:38:11.597. At08:38:13.800/853 ART refused199756819-byte allocation, Javaused459MB, heaplimit512MB. Fatal pool-5-thread-1 stack ByteBuffer.allocateDirect→aj.k:83→u20.w. Exact release mapping hash43516f171379f9a31021dab29d46a9d8a67683caa6cbe1211e5373e4eb826ff2 matches log; aj=Gles31JpegOutputProcessor,k:83 resolvesreadBitmap source250, the fullRGBA8 allocateDirect. Thus recorded app crash is JPEG readback allocation, not mono shader or DNG writer. Log ends at process death; persistent camera/provider failure afterward cannot be diagnosed from this app-only log.

Fix: JPEG readBitmap uses HdrPlusVulkan.allocBuffer native staging, frees in finally after bitmapcopy and on GL/bitmap failures, removes full-photo base/gain readback caches. Bitmap is recycled if copying fails. RawFrameSaveJob catches JPEG OutOfMemoryError, reports JPEG failure while retaining existing DNG and letting normal RAW/mono/root lifecycle cleanup run. This prevents the recorded heap allocation failure path; native/GPU memory limits still apply.

Added JpegReadbackMemoryDeviceTest: repeated GPU readback checks native outstanding bytes return to baseline; opt-in jpeg50MpMemory=true tests8160×6120 with a retained full CFA FloatArray. App and Android-test Kotlin compilation PASS (final3s); device tests NOT RUN. ADB devices empty, no phone-side settings/root/camera changes, no APK installed. Need reconnect for50MP B&W JPEG+DNG acceptance and camera recovery diagnostics.

### 2026-11-18: DCG14 preview residual shading

Removed RawViewfinder.snapshotLens early identity return for metadataVerified root profiles. DCG14 now follows the existing HAL residual map -> RGBA16F gain texture -> preview shader correction, as ordinary RAW preview does. For verified matching readout dimensions use fullbuffer(0,0,width,height), matching LapisRootSession.normalized; this avoids a second ISZ crop or stale stockactive bounds on50MP. A missing/invalid map still usesidentity, and returned residualmaps are applied independently of sensor-side shading flags. Existing1Hzcache andGPU/NEON map paths retained. compileGithubDeviceTestKotlin and29 existingVfGpuImport/RawShadingCalibration unit tests PASS. Phone reconnected, normalapp2.3.96/code2398;ADBUID2000, so no liveDCG/root status claim. Releasebuild/install pending at this entry.

### 2026-11-18: HDR+ DNG double-shading confirmed and fixed

ZslMergeSaveJobs and offlineHDR+ both call RawPreDemosaicPipeline.process on the merged CFA (bakes lens gains), then pass the same metadata carrying the residualmap to HdrPlusDngWriter. Writer addedOpcodeList2 GainMap again; editors therefore multiplied already-corrected corners twice. This is a definiteHDR+DNGexportbug, not yet evidence of duplicategain inJPEG orpreview.

AddedUnpackedRawCfa.lensShadingApplied (defaultfalse) as pixel-domain state distinctfromsensor-sideflags. LensShadingCorrector sets it when gains are baked/alreadyapplied and skips repeatedmultiplication; saturationMap stillretainsgainthresholds forhighlightprocessing. Data-classcopies,AIandopposed preserve it. HdrPlusDngWriter omitsGainMapwhenCFAlensShadingApplied ormetadataalreadyApplied; uncorrectedCFAs stillgetopcode. All41 tests passed:RawPreDemosaicPipeline5,HdrPlusDngWriter7,VfGpuImport25,RawShadingCalibration4,none skipped. Includesapply-twiceidentity,saturationthresholdretention,andrealDNGopcodepresence/absence. DCGpreviewfixretained. Combinedreleasebuildinprogress;phonebrieflyconnectedthenADBdisappeared,so noinstallationorlivecaptureverificationyet.
