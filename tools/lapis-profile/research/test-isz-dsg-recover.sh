#!/system/bin/sh
# One ISZ+DSG experiment, bracketed by proven DCG9 capture checks. No reboot.
set -eu
[ "$(id -u)" = 0 ]
[ "$(getenforce)" = Enforcing ]
test ! -e /data/adb/rawlens-lapis/lease
MODE=${1:-13}
case "$MODE" in 13|16) ;; *) exit 2;; esac
BASE=/data/local/tmp/rawlens-dsg-$MODE
mkdir -p "$BASE"
chmod 700 "$BASE"
CORE=/vendor/lib64/mt6878/libmtkcam_hal_core_utils.so
META=/vendor/lib64/mt6878/libmtkcam_metastore.so
CORE_SHA=287418474d0ddb781e5c6c33f4c38367e690061617a0bb599ddedb836a8bd8cc
META_SHA=95eded2eef1767fdb07f74d31a4ef1a9b40668e6d5984d30444e0165a489a7dc
[ "$(sha256sum "$CORE" | cut -d ' ' -f1)" = "$CORE_SHA" ]
[ "$(sha256sum "$META" | cut -d ' ' -f1)" = "$META_SHA" ]
PROPS='vendor.debug.cameng.force_sensormode vendor.debug.vhdr.strategy.config.force vendor.debug.vhdr.strategy.force vendor.debug.camera.start.sensorScenario vendor.debug.camera.start.forceSensorMode'
for prop in $PROPS; do printf '%s|%s\n' "$prop" "$(getprop "$prop")"; done > "$BASE/previous.properties"
CORE_MOUNT=0
META_MOUNT=0
TEST_STARTED=0
restore() {
 set +e
 if [ "$META_MOUNT" = 1 ]; then umount -l "$META"; META_MOUNT=0; fi
 if [ "$CORE_MOUNT" = 1 ]; then umount -l "$CORE"; CORE_MOUNT=0; fi
 while IFS='|' read -r prop value; do setprop "$prop" "$value"; done < "$BASE/previous.properties"
 setprop ctl.restart camerahalserver
 setprop ctl.restart cameraserver
 sleep 9
}
core_on() {
 chmod 644 /data/local/tmp/rawlens-dsg-core.so
 chcon u:object_r:vendor_file:s0 /data/local/tmp/rawlens-dsg-core.so
 mount -o bind /data/local/tmp/rawlens-dsg-core.so "$CORE"
 CORE_MOUNT=1
}
configure() {
 setprop vendor.debug.cameng.force_sensormode "$1"
 setprop vendor.debug.vhdr.strategy.config.force 2
 setprop vendor.debug.vhdr.strategy.force 64
 setprop vendor.debug.camera.start.sensorScenario ''
 setprop vendor.debug.camera.start.forceSensorMode ''
 setprop ctl.restart camerahalserver
 setprop ctl.restart cameraserver
 sleep 9
}
probe() {
 local sensor=$1 height=$2 label=$3
 local pid=$(getprop init.svc_debug_pid.camerahalserver)
 logcat -b all --pid="$pid" -v threadtime > "$BASE/$label-hal.log" &
 local logger=$!
 CLASSPATH=/data/local/tmp/rawlens-isz-ab-probe.jar timeout -s KILL 35 app_process /system/bin RootRawProbe capture=true auto=true fps=30 frames=15 repeat=true template=3 width=4080 height="$height" hdr=3 sessionHdr=3 force="$sensor" configScenario=12 scenario=12 noRaw=true output="$BASE/$label.raw16" > "$BASE/$label-result.log" 2>&1
 local rc=$?
 kill "$logger" 2>/dev/null || true
 cat "$BASE/$label-result.log"
 [ "$rc" = 0 ] || return "$rc"
 grep -q "IMAGE 4080x$height " "$BASE/$label-result.log" || return 72
 case "$sensor" in
  9) grep -q 'DRIVER ACTIVE code=0x1009301a size=4080x3060' "$BASE/$label-result.log";;
  13) grep -q 'DRIVER ACTIVE code=0x100d301a size=4080x2304' "$BASE/$label-result.log";;
  16) grep -q 'DRIVER ACTIVE code=0x1010301a size=4080x2304' "$BASE/$label-result.log";;
 esac
}
cleanup() {
 local rc=$?
 trap - EXIT
 restore
 if [ "$TEST_STARTED" = 1 ]; then
  core_on
  configure 9
  probe 9 3060 dcg-after
  recovery=$?
  echo "$recovery" > "$BASE/dcg-recovery-status"
  restore
  if [ "$recovery" != 0 ]; then rc=73; fi
 fi
 while IFS='|' read -r prop value; do [ "$(getprop "$prop")" = "$value" ] || rc=74; done < "$BASE/previous.properties"
 [ "$(sha256sum "$CORE" | cut -d ' ' -f1)" = "$CORE_SHA" ] || rc=74
 [ "$(sha256sum "$META" | cut -d ' ' -f1)" = "$META_SHA" ] || rc=74
 [ "$(getenforce)" = Enforcing ] || rc=74
 echo "FINAL_STATUS=$rc"
 echo "$rc" > "$BASE/status"
 exit "$rc"
}
trap cleanup EXIT
trap 'exit 124' HUP INT TERM
core_on
configure 9
set +e
probe 9 3060 dcg-before
before=$?
set -e
[ "$before" = 0 ] || exit 75
restore
set -e
core_on
chmod 644 /data/local/tmp/rawlens-dsg-video-metastore.so
chcon u:object_r:vendor_file:s0 /data/local/tmp/rawlens-dsg-video-metastore.so
mount -o bind /data/local/tmp/rawlens-dsg-video-metastore.so "$META"
META_MOUNT=1
TEST_STARTED=1
configure "$MODE"
set +e
probe "$MODE" 2304 isz-dsg
rc=$?
exit "$rc"
