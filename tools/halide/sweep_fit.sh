#!/bin/bash
# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Step-5 fit-param sweep: builds the host bgu_fit once, then probes
# s/r/lambda over the synthetic fixtures (affine/curve/lenslike) at
# production low-res dims. Prints SWEEP rows:
#   W H S R LAMBDA mode GW GH GZ ms meanAbs maxAbs
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HALIDE="$(brew --prefix halide 2>/dev/null)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CXX="${CXX:-c++}"

echo "== host bgu_fit build" >&2
mkdir -p "$WORK/gen" "$WORK/host"
"$CXX" -std=c++17 -O2 -fno-rtti -I "$HALIDE/include" \
    "$ROOT/tools/halide/generators/bgu_fit.cpp" "$HALIDE/lib/libHalide_GenGen.a" \
    -L "$HALIDE/lib" -lHalide -lpthread -ldl \
    -o "$WORK/gen/bgu_fit" 2>"$WORK/gen.log" || { tail -n 20 "$WORK/gen.log"; exit 1; }
"$WORK/gen/bgu_fit" -g bgu_fit -o "$WORK/host" -e static_library,h target=host || exit 1
"$CXX" -std=c++17 -O2 -I "$HALIDE/include" -I "$WORK/host" \
    "$ROOT/tools/halide/fit_check.cpp" "$WORK/host/bgu_fit.a" \
    -o "$WORK/fit_check" -ldl -lpthread || exit 1

# Gate run first (default args, tripwires live).
"$WORK/fit_check" || exit 1

# Production low-res dims (1020x764 guide / 8).
W=128
H=96
for S in 8 16 32; do
    for R in 0.125 0.0625; do
        for LAMBDA in 1e-6 1e-4 1e-2; do
            for MODE in 0 1 2; do
                "$WORK/fit_check" "$W" "$H" "$S" "$R" "$LAMBDA" "$MODE" | grep SWEEP
            done
        done
    done
done
