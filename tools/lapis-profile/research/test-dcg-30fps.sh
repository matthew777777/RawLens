#!/system/bin/sh
set -eu
test ! -e /data/adb/rawlens-lapis/lease
test -z "$(getprop vendor.debug.cameng.force_sensormode)"
OLD_RUNTIME=$(getprop vendor.debug.vhdr.strategy.force)
OLD_STRATEGY=$(getprop vendor.debug.vhdr.strategy.config.force)
OLD_MODE=$(getprop vendor.debug.cameng.force_sensormode)
cleanup() { setprop vendor.debug.vhdr.strategy.force "$OLD_RUNTIME"; if [ -n "${LOGGER:-}" ]; then kill "$LOGGER" 2>/dev/null || true; fi; setprop vendor.debug.vhdr.strategy.config.force "$OLD_STRATEGY"; setprop vendor.debug.cameng.force_sensormode "$OLD_MODE"; setprop ctl.restart camerahalserver; echo RESTORED; getenforce; }
trap cleanup EXIT
setprop vendor.debug.vhdr.strategy.force 64
setprop vendor.debug.vhdr.strategy.config.force 2
setprop vendor.debug.cameng.force_sensormode 9
setprop ctl.restart camerahalserver
sleep 9
HALPID=$(getprop init.svc_debug_pid.camerahalserver)
logcat -b all --pid="$HALPID" -v threadtime > /data/local/tmp/rawlens-dcg-30fps-hal.log &
LOGGER=$!
set +e
CLASSPATH=/data/local/tmp/rawlens-dcg-rootrawprobe.jar timeout -s KILL 30 app_process /system/bin RootRawProbe capture=true auto=true fps=30 frames=90 repeat=true template=3 hdr=3 sessionHdr=3 force=9 configScenario=12 scenario=12 output=/data/local/tmp/root-dcg-30fps.raw16
echo PROBE_EXIT=$?
logcat -d -b all --pid="$HALPID" | grep -E 'HDRAppMode|SupportList|CfgHdrHalMode|Incorrect HDR|sensorMode:|select size|checkConfigSensorMode|mismatch sensorMode|HDR HAL MODE|HDRPolicyHelper' | tail -45
