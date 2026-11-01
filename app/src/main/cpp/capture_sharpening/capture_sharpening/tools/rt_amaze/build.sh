#!/bin/sh
# Builds RT's AMaZE (amaze_demosaic_RT.cc) + border_interpolate verbatim into a standalone tool.
# Scalar path, single thread, FP contraction off.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
# RawTherapee checkout: git clone https://github.com/RawTherapee/RawTherapee tmp/ref/RawTherapee
# (git -C tmp/ref/RawTherapee checkout b2ee504)
RT=${RT:-$REPO/tmp/ref/RawTherapee}
E=$RT/rtengine
OUT=$REPO/tmp/capture_sharpening/rt_amaze
mkdir -p "$OUT"
test "$(git -C "$RT" rev-parse --short=7 HEAD)" = b2ee504 || { echo "RT checkout is not b2ee504" >&2; exit 1; }
check() { got=$(sed -n "$2p" "$1"); case "$got" in "$3"*) ;; *) echo "anchor mismatch $1:$2: $got" >&2; exit 1;; esac; }
check "$E/amaze_demosaic_RT.cc" 38 "namespace"
check "$E/amaze_demosaic_RT.cc" 48 "void RawImageSource::amaze_demosaic_RT("
check "$E/amaze_demosaic_RT.cc" 1609 "}"
check "$E/demosaic_algos.cc" 46 "void RawImageSource::border_interpolate("
check "$E/demosaic_algos.cc" 199 "}"
{
  echo '// GENERATED from RawTherapee b2ee504 by build.sh - do not edit'
  sed -n '38,43p' "$E/amaze_demosaic_RT.cc"
  echo 'namespace rtengine {'
  sed -n '48,1609p' "$E/amaze_demosaic_RT.cc"
  sed -n '46,199p' "$E/demosaic_algos.cc"
  echo '}'
} > "$OUT/amaze_extract.inc"
c++ -std=c++17 -O2 -ffp-contract=off -fno-fast-math -I"$E" -I"$OUT" "$HERE/amaze_ref.cpp" -o "$OUT/amaze_ref"
echo "built $OUT/amaze_ref"
