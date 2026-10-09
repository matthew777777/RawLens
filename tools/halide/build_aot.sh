#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the Halide generators on the host, smoke-check each, and AOT-compile
# every filter for every Android ABI into app/src/main/cpp/halide/filters/.
#
# Usage: tools/halide/build_aot.sh
# Env:   HALIDE_PREFIX (default /opt/homebrew/opt/halide)
#
# The generated .a files are checked in (ncnn precedent); this script + the
# generator sources under tools/halide/generators/ are the audit/regen path.
# Pinned host toolchain: Halide 21.0.0 (brew) on arm64 macOS.
set -euo pipefail

HALIDE="${HALIDE_PREFIX:-/opt/homebrew/opt/halide}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GEN="$ROOT/tools/halide/generators"
OUT="$ROOT/app/src/main/cpp/halide/filters"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ ! -f "$HALIDE/lib/libHalide_GenGen.a" ]; then
    echo "Halide not found at $HALIDE (need lib/libHalide_GenGen.a)" >&2
    exit 1
fi

build_one() {
    local name="$1"   # e.g. bgu_spike_downsample
    local check="$2"  # host check source or empty
    echo "== generator: $name"
    c++ -std=c++17 -O2 -fno-rtti \
        -I "$HALIDE/include" \
        "$GEN/$name.cpp" "$HALIDE/lib/libHalide_GenGen.a" \
        -L "$HALIDE/lib" -lHalide -lpthread -ldl \
        -o "$WORK/gen-$name"
    if [ -n "$check" ]; then
        echo "== host AOT + check: $name"
        mkdir -p "$WORK/host-$name"
        "$WORK/gen-$name" -g "$name" -o "$WORK/host-$name" -e static_library,h target=host
        # -ffp-contract=off: scalar references must use JVM/ART
        # separate-rounding semantics (each op rounded once). Default
        # Apple Clang fuses mult+add into FMA (observed: scalar tenor
        # term 0x...717 vs separate-rounding 0x...716), which no
        # Kotlin-matching filter can reproduce.
        c++ -std=c++17 -O2 -ffp-contract=off \
            -I "$HALIDE/include" -I "$WORK/host-$name" \
            "$ROOT/tools/halide/$check" "$WORK/host-$name/$name.a" \
            -lpthread -ldl \
            -o "$WORK/check-$name"
        "$WORK/check-$name"
    fi
    echo "== cross AOT: $name"
    for spec in "arm64-v8a:arm-64-android" "armeabi-v7a:arm-32-android" \
                "x86_64:x86-64-android" "x86:x86-32-android"; do
        abi="${spec%%:*}"
        target="${spec##*:}"
        mkdir -p "$OUT/$abi"
        "$WORK/gen-$name" -g "$name" -o "$OUT/$abi" -e static_library,h "target=$target"
    done
    # One shared header (target-independent signature); per-ABI .a files differ.
    cp "$OUT/arm64-v8a/$name.h" "$OUT/$name.h"
    for abi in arm64-v8a armeabi-v7a x86_64 x86; do
        rm -f "$OUT/$abi/$name.h"
    done
}

build_one bgu_spike_downsample host_check.cpp
build_one bgu_look look_check.cpp
build_one bgu_fit fit_check.cpp
build_one hdrplus_meter meter_check.cpp
build_one scope_meter scope_meter_check.cpp

ls -la "$OUT" | head -n 12
echo OK
