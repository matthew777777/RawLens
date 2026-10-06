#!/bin/bash
# Host librawLensDng for the desktop CLI and JVM unit tests.
# Mirrors the tinydng_v3/rawLensDng CMake recipe in
# app/src/main/cpp/CMakeLists.txt (same sources, same defines); the JNI
# bridge compiles against the running JDK's headers.
#
# Usage: build_dng_native.sh <output-lib> <jni-include> <jni-platform-include>
set -euo pipefail

OUT="$1"
JNI_INC="$2"
JNI_PLAT="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TD="$ROOT/app/src/main/cpp/deps/tinydng"

mkdir -p "$(dirname "$OUT")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

DEFINES=(-DTINYDNG_NO_ZIP -DTINYDNG_NO_BASELINE_JPEG -DTINYDNG_NO_PSD -DTINYDNG_DISABLE_THREADS)
CFLAGS=(-O2 -fPIC -std=c11 -I"$TD" "${DEFINES[@]}")
CXXFLAGS=(-O2 -fPIC -std=c++17 -I"$TD" -I"$JNI_INC" -I"$JNI_PLAT" "${DEFINES[@]}")

OBJS=()
for src in tinydng_api tinydng_io tinydng_tiff tinydng_dng tinydng_codec tinydng_write tinydng_psd tinydng_psd_write tiny_dng_ljpeg92_v2; do
    cc "${CFLAGS[@]}" -c "$TD/$src.c" -o "$TMP/$src.o"
    OBJS+=("$TMP/$src.o")
done
c++ "${CXXFLAGS[@]}" -c "$ROOT/app/src/main/cpp/tinydng_jni.cpp" -o "$TMP/tinydng_jni.o"
OBJS+=("$TMP/tinydng_jni.o")

if [[ "$OUT" == *.dylib ]]; then
    c++ -dynamiclib -O2 "${OBJS[@]}" -o "$OUT"
else
    c++ -shared -O2 "${OBJS[@]}" -o "$OUT"
fi
echo "built $OUT"
