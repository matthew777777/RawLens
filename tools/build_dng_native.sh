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

# Enable the same TinyDNG encoder as Android when host libjxl is installed.
JXL_LIBS=(-lm)
if command -v pkg-config >/dev/null 2>&1 && pkg-config --exists 'libjxl >= 0.8' 'libjxl_threads >= 0.8'; then
    read -r -a JXL_CFLAGS <<< "$(pkg-config --cflags libjxl libjxl_threads)"
    read -r -a JXL_LIBS <<< "$(pkg-config --libs libjxl libjxl_threads)"
    # libjxl may use @rpath install names on macOS; keep host JNI loadable.
    JXL_LIBS+=("-Wl,-rpath,$(pkg-config --variable=libdir libjxl)"
               "-Wl,-rpath,$(pkg-config --variable=libdir libjxl_threads)")
    CFLAGS+=(-DTINYDNG_HAVE_JXL ${JXL_CFLAGS[@]+"${JXL_CFLAGS[@]}"})
fi

OBJS=()
for src in tinydng_api tinydng_io tinydng_tiff tinydng_dng tinydng_codec tinydng_write tinydng_psd tinydng_psd_write tiny_dng_ljpeg92_v2; do
    cc "${CFLAGS[@]}" -c "$TD/$src.c" -o "$TMP/$src.o"
    OBJS+=("$TMP/$src.o")
done
c++ "${CXXFLAGS[@]}" -c "$ROOT/app/src/main/cpp/tinydng_jni.cpp" -o "$TMP/tinydng_jni.o"
OBJS+=("$TMP/tinydng_jni.o")

if [[ "$OUT" == *.dylib ]]; then
    c++ -dynamiclib -O2 "${OBJS[@]}" "${JXL_LIBS[@]}" -o "$OUT"
else
    c++ -shared -O2 "${OBJS[@]}" "${JXL_LIBS[@]}" -o "$OUT"
fi
echo "built $OUT"
