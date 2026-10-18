#!/bin/sh
# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build the hlmeasure harness for Android (arm64) with the NDK toolchain and
# run the staged bundle on a connected device via adb.
#
# Usage: tools/hlmeasure/run_android.sh [bundle-dir] [spv] [out-dir]
# Defaults: /tmp/hlmeasure/bundle, app spv, /tmp/hlmeasure/android-out.
set -e
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/27.0.12077973}"
PREBUILT="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/ | head -1)"
TC="${PREBUILT}bin/aarch64-linux-android29-clang++"
BUNDLE="${1:-/tmp/hlmeasure/bundle}"
SPV="${2:-$ROOT/app/src/main/assets/spirv/hlrecon.spv}"
OUT="${3:-/tmp/hlmeasure/android-out}"
DEVDIR=/data/local/tmp/hlmeasure

"$TC" -std=c++17 -O1 -Wall -o "$ROOT/tools/hlmeasure/harness-android" \
    "$ROOT/tools/hlmeasure/harness.cpp" -lvulkan -static-libstdc++
echo "built: tools/hlmeasure/harness-android"

adb push "$ROOT/tools/hlmeasure/harness-android" $DEVDIR/harness
adb shell mkdir -p $DEVDIR/bundle $DEVDIR/out
adb push "$BUNDLE/plan.json" $DEVDIR/bundle/plan.json
for f in "$BUNDLE"/in_*.bin "$BUNDLE"/twin_*.bin; do
  adb push "$f" $DEVDIR/bundle/ > /dev/null
done
adb push "$SPV" $DEVDIR/hlrecon.spv
adb shell "cd $DEVDIR && rm -f out/*.bin && ./harness $DEVDIR/bundle $DEVDIR/hlrecon.spv $DEVDIR/out"
rm -rf "$OUT" && mkdir -p "$OUT"
adb pull $DEVDIR/out/. "$OUT/" > /dev/null
echo "outputs in $OUT"
