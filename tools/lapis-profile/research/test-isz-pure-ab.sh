#!/system/bin/sh
# Exact-firmware, bounded headless A/B. Run only with usable temporary root.
# No permanent vendor writes; no shipped app defaults are changed.
set -eu
[ "$(id -u)" = 0 ]
[ "$(getenforce)" = Enforcing ]
test ! -e /data/adb/rawlens-lapis/lease
MODE=${1:-2x}
case "$MODE" in 2x) SENSOR=7; START=''; EXTRA='';; 4x) SENSOR=8; START=4; EXTRA='force=8 configScenario=4 scenario=5 zoom=4 cellCrop=true';; *) exit 2;; esac
ISO=${2:-100}
EXPOSURE=${3:-10000000}
FOCUS=${4:-0}
BASE=/data/local/tmp/rawlens-isz-ab-$MODE
mkdir -p "$BASE"
chmod 755 "$BASE"
SMH=/vendor/lib64/mt6878/libcam.sensor_mode_helper.so
CUSTOM=/vendor/lib64/mt6878/libcam.sensor_mode_helper_custom.so
test "$(sha256sum "$SMH" | cut -d ' ' -f1)" = ec0224cf5054e8ed100d024cbbb6150cd64944a0e26b9377586f0e5971e639a1
test "$(sha256sum "$CUSTOM" | cut -d ' ' -f1)" = c1eecbf9ff25c137b59eb48a62cef8bef15c22b9006e910fc1d118fdaa654fd2
DEBUG=/sys/module/mtk_cam_isp7sp/parameters/debug_opts
OLDDEBUG=$(cat "$DEBUG")
PROPS='vendor.debug.cameng.force_sensormode vendor.debug.camera.start.sensorScenario vendor.debug.camera.start.forceSensorMode vendor.debug.camera.raw.type vendor.debug.camera.cfg.ProcRaw vendor.debug.camera.app.process.raw'
for prop in $PROPS; do printf '%s|%s\n' "$prop" "$(getprop "$prop")"; done > "$BASE/previous.properties"
MOUNTED_SMH=0
MOUNTED_CUSTOM=0
cleanup() {
 rc=$?
 set +e
 [ -n "${LOGGER:-}" ] && kill "$LOGGER" 2>/dev/null
 [ "$MOUNTED_CUSTOM" = 1 ] && umount -l "$CUSTOM"
 [ "$MOUNTED_SMH" = 1 ] && umount -l "$SMH"
 while IFS='|' read -r prop value; do setprop "$prop" "$value"; done < "$BASE/previous.properties"
 echo "$OLDDEBUG" > "$DEBUG"
 setprop ctl.restart camerahalserver
 setprop ctl.restart cameraserver
 while IFS='|' read -r prop value; do
  [ "$(getprop "$prop")" = "$value" ] || rc=71
 done < "$BASE/previous.properties"
 [ "$(cat "$DEBUG")" = "$OLDDEBUG" ] || rc=71
 [ "$(sha256sum "$SMH" | cut -d ' ' -f1)" = ec0224cf5054e8ed100d024cbbb6150cd64944a0e26b9377586f0e5971e639a1 ] || rc=71
 [ "$(sha256sum "$CUSTOM" | cut -d ' ' -f1)" = c1eecbf9ff25c137b59eb48a62cef8bef15c22b9006e910fc1d118fdaa654fd2 ] || rc=71
 echo RESTORATION_STATUS=$rc
 sha256sum "$SMH" "$CUSTOM"
 getenforce
 echo "$rc" > "$BASE/status"
 chmod 644 "$BASE"/* 2>/dev/null
}
trap cleanup EXIT
if [ "$MODE" = 4x ]; then
 chmod 644 /data/local/tmp/rawlens-isz-crop-helper.so /data/local/tmp/rawlens-isz-crop-custom.so
 chcon u:object_r:vendor_file:s0 /data/local/tmp/rawlens-isz-crop-helper.so /data/local/tmp/rawlens-isz-crop-custom.so
 mount -o bind /data/local/tmp/rawlens-isz-crop-helper.so "$SMH"
 MOUNTED_SMH=1
 mount -o bind /data/local/tmp/rawlens-isz-crop-custom.so "$CUSTOM"
 MOUNTED_CUSTOM=1
fi
setprop vendor.debug.cameng.force_sensormode "$SENSOR"
setprop vendor.debug.camera.start.sensorScenario "$START"
setprop vendor.debug.camera.start.forceSensorMode ''
echo $((OLDDEBUG | 514)) > "$DEBUG"
for route in baseline pure; do
 # Baseline preserves the original raw-policy property values.
 for prop in vendor.debug.camera.raw.type vendor.debug.camera.cfg.ProcRaw vendor.debug.camera.app.process.raw; do
  value=$(sed -n "s/^$prop|//p" "$BASE/previous.properties")
  setprop "$prop" "$value"
 done
 RAW_EXTRA=''
 if [ "$route" = pure ]; then
  setprop vendor.debug.camera.raw.type 1
  setprop vendor.debug.camera.cfg.ProcRaw 0
  setprop vendor.debug.camera.app.process.raw 0
  RAW_EXTRA='processRaw=0'
 fi
 setprop ctl.restart camerahalserver
 setprop ctl.restart cameraserver
 sleep 9
 PID=$(getprop init.svc_debug_pid.camerahalserver)
 logcat -b all --pid="$PID" -v threadtime > "$BASE/$route-hal.log" &
 LOGGER=$!
 set +e
 CLASSPATH=/data/local/tmp/rawlens-isz-ab-probe.jar timeout -s KILL 35 app_process /system/bin RootRawProbe capture=true width=4080 height=3060 fps=10 frames=8 repeat=true template=3 iso="$ISO" exposure="$EXPOSURE" focus="$FOCUS" nr=0 edge=0 metrics=true $EXTRA $RAW_EXTRA output="$BASE/$route.raw16" > "$BASE/$route-result.log" 2>&1
 rc=$?
 set -e
 kill "$LOGGER" 2>/dev/null || true
 LOGGER=''
 dmesg > "$BASE/$route-kernel.log"
 cat "$BASE/$route-result.log"
 [ "$rc" = 0 ] || exit "$rc"
done
