#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Host BGU gates only; does not rewrite any checked-in Android archive.
set -euo pipefail
HALIDE="${HALIDE_PREFIX:-/opt/homebrew/opt/halide}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
for name in bgu_spike_downsample bgu_look bgu_fit; do
    mkdir -p "$WORK/$name"
    c++ -std=c++17 -O2 -fno-rtti -I "$HALIDE/include" \
        "$ROOT/tools/halide/generators/$name.cpp" "$HALIDE/lib/libHalide_GenGen.a" \
        -L "$HALIDE/lib" -lHalide -lpthread -ldl -o "$WORK/gen"
    "$WORK/gen" -g "$name" -o "$WORK/$name" -e static_library,h target=host
    case "$name" in
        bgu_spike_downsample) checks="host_check.cpp" ;;
        bgu_look) checks="look_check.cpp" ;;
        bgu_fit) checks="fit_check.cpp fit_reference_check.cpp" ;;
    esac
    for check in $checks; do
        c++ -std=c++17 -O2 -ffp-contract=off -I "$HALIDE/include" -I "$WORK/$name" \
            "$ROOT/tools/halide/$check" "$WORK/$name/$name.a" -lpthread -ldl -o "$WORK/check"
        "$WORK/check"
    done
done
