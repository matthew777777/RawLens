#!/bin/sh
# Uses existing checkouts only; never downloads dependencies.
set -eu
motion=${1:?Usage: verify.sh motioncam-decoder-dir MediaCinemaRAW-Decoder-dir [sample.mcraw]}
media=${2:?Missing MediaCinemaRAW-Decoder checkout}
out=$(mktemp -d /private/tmp/rawlens-mcraw-check.XXXXXX)
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$root"
c++ -std=c++17 -O2 -Iapp/src/main/cpp/cinemaraw/include \
    -I"$media/include" -I"$motion/lib/include" -I"$motion/thirdparty" \
    tools/mcraw/roundtrip.cpp app/src/main/cpp/cinemaraw/src/Encoder.cpp \
    "$media/src/Decoder.cpp" "$motion/lib/RawData.cpp" -o "$out/roundtrip"
"$out/roundtrip"
c++ -std=c++17 -O2 -Iapp/src/main/cpp/cinemaraw/include \
    -I"$media/include" -I"$motion/lib/include" -I"$motion/thirdparty" \
    tools/mcraw/interop.cpp app/src/main/cpp/cinemaraw/src/Encoder.cpp \
    app/src/main/cpp/cinemaraw/src/ContainerWriter.cpp \
    "$media/src/Decoder.cpp" "$media/src/ContainerReader.cpp" \
    "$motion/lib/Decoder.cpp" "$motion/lib/RawData.cpp" \
    "$motion/lib/RawData_Legacy.cpp" -o "$out/interop"
"$out/interop"
if [ "$#" -ge 3 ]; then "$out/interop" "$3"; fi
