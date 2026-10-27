#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Compare original fitting, reference curved coordinates and guide conditioning
# on the SAME frozen operator pairs. Never modifies Android archives.
set -euo pipefail
if [ "$#" -ne 2 ]; then echo "Usage: bash tools/halide/replay_bgu.sh pair_directory output_directory" >&2; exit 2; fi
PAIR="$1"
OUT="$2"
HALIDE="${HALIDE_PREFIX:-/opt/homebrew/opt/halide}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
c++ -std=c++17 -O2 -fno-rtti -I "$HALIDE/include" \
    "$ROOT/tools/halide/generators/bgu_fit.cpp" "$HALIDE/lib/libHalide_GenGen.a" \
    -L "$HALIDE/lib" -lHalide -lpthread -ldl -o "$WORK/gen"
for alpha in 0 0.8; do
    mkdir -p "$WORK/$alpha"
    "$WORK/gen" -g bgu_fit -o "$WORK/$alpha" -e static_library,h target=host "curve_alpha=$alpha"
    c++ -std=c++17 -O2 -ffp-contract=off -I "$HALIDE/include" -I "$WORK/$alpha" \
        "$ROOT/tools/halide/fit_image_check.cpp" "$WORK/$alpha/bgu_fit.a" \
        -lpthread -ldl -o "$WORK/check"
    for mode in RAW JPEG; do
        "$WORK/check" "$PAIR/$mode-pairs.bin" "$alpha" "$OUT/$mode-original-$alpha" > "$OUT/$mode-original-$alpha.txt"
        "$WORK/check" "$PAIR/$mode-pairs.bin" "$alpha" "$OUT/$mode-linear-$alpha" "$mode" > "$OUT/$mode-linear-$alpha.txt"
        "$WORK/check" "$PAIR/$mode-pairs.bin" "$alpha" "$OUT/$mode-guide-$alpha" GUIDE_SRGB > "$OUT/$mode-guide-$alpha.txt"
    done
done
