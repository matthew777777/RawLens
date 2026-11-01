#!/bin/sh
# Builds the RawTherapee capture-sharpening reference harness.
# Algorithm code is extracted verbatim from the RT checkout (pinned commit) by line range;
# boxblur.cc and gauss.cc are compiled unchanged. Scalar path only (no SSE/SIMDe, no OpenMP),
# FP contraction off so float evaluation order is plain IEEE.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
# RawTherapee checkout: git clone https://github.com/RawTherapee/RawTherapee tmp/ref/RawTherapee
# (git -C tmp/ref/RawTherapee checkout b2ee504)
RT=${RT:-$REPO/tmp/ref/RawTherapee}
OUT=$REPO/tmp/capture_sharpening/rt_harness
mkdir -p "$OUT"
test "$(git -C "$RT" rev-parse --short=7 HEAD)" = b2ee504 || { echo "RT checkout is not b2ee504" >&2; exit 1; }
E=$RT/rtengine
check() { # file line expected-prefix
  got=$(sed -n "$2p" "$1"); case "$got" in "$3"*) ;; *) echo "anchor mismatch $1:$2: $got" >&2; exit 1;; esac
}
check "$E/capturesharpening.cc" 47 "void buildClipMaskBayer"
check "$E/capturesharpening.cc" 134 "float calcRadiusBayer"
check "$E/capturesharpening.cc" 279 "bool checkForStop"
check "$E/capturesharpening.cc" 299 "void CaptureDeconvSharpening"
check "$E/capturesharpening.cc" 534 "}"
check "$E/rt_algo.cc" 49 "float calcBlendFactor(float"
check "$E/rt_algo.cc" 67 "float tileAverage"
check "$E/rt_algo.cc" 177 "float calcContrastThreshold("
check "$E/rt_algo.cc" 380 "void buildBlendMask("
check "$E/rt_algo.cc" 566 "}"
{
  echo '// GENERATED from RawTherapee b2ee504 by build.sh - do not edit'
  echo 'namespace {'
  sed -n '47,75p;134,186p;279,534p' "$E/capturesharpening.cc"
  echo '}'
  echo 'namespace rtengine { namespace {'
  sed -n '49,55p;67,112p;177,235p' "$E/rt_algo.cc"
  echo '}'
  sed -n '380,566p' "$E/rt_algo.cc"
  echo '}'
} > "$OUT/rt_extract.inc"
CXX=${CXX:-c++}
FLAGS="-std=c++17 -O2 -ffp-contract=off -fno-fast-math -I$E -Wno-unused-parameter"
$CXX $FLAGS -c "$E/boxblur.cc" -o "$OUT/boxblur.o"
$CXX $FLAGS -c "$E/gauss.cc" -o "$OUT/gauss.o"
$CXX $FLAGS -I"$OUT" -c "$HERE/cs_ref.cpp" -o "$OUT/cs_ref.o"
$CXX "$OUT/boxblur.o" "$OUT/gauss.o" "$OUT/cs_ref.o" -o "$OUT/cs_ref"
# Variant: reset the per-thread tmpThr scratch per tile (removes RT's schedule-dependent ring reuse with corner offset).
mkdir -p "$OUT/ringreset"
sed 's|^                // fill tiles$|                tmpThr.fill(1.f); // RING_RESET|' "$OUT/rt_extract.inc" > "$OUT/ringreset/rt_extract.inc"
grep -q RING_RESET "$OUT/ringreset/rt_extract.inc" || { echo "ring reset patch failed" >&2; exit 1; }
$CXX $FLAGS -I"$OUT/ringreset" -c "$HERE/cs_ref.cpp" -o "$OUT/ringreset/cs_ref.o"
$CXX "$OUT/boxblur.o" "$OUT/gauss.o" "$OUT/ringreset/cs_ref.o" -o "$OUT/cs_ref_ringreset"
echo "built $OUT/cs_ref"
