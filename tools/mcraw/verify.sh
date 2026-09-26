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
c++ -std=c++17 -O2 -Iapp/src/main/cpp/cinemaraw/include \
    -I"$media/include" -I"$motion/lib/include" -I"$motion/thirdparty" \
    tools/mcraw/motion_coalesce.cpp app/src/main/cpp/cinemaraw/src/Encoder.cpp \
    app/src/main/cpp/cinemaraw/src/ContainerWriter.cpp \
    "$media/src/Decoder.cpp" "$media/src/ContainerReader.cpp" \
    "$motion/lib/Decoder.cpp" "$motion/lib/RawData.cpp" \
    "$motion/lib/RawData_Legacy.cpp" -o "$out/motion_coalesce"
"$out/motion_coalesce"
# Jan-2026 decoder (MotionCam Tools v1.0 era): its tail scan stops at the
# first motion item, so the audio index must precede trailing motion data.
# Sources come from the checkout's own git history (no downloads); a
# shallow clone without 6b49328 skips this check with a warning.
if git -C "$motion" archive 6b49328 lib thirdparty 2>/dev/null | tar -x -C "$out" -f - 2>/dev/null; then
    mkdir -p "$out/legacy" && mv "$out/lib" "$out/thirdparty" "$out/legacy/"
    c++ -std=c++17 -O2 -w -Iapp/src/main/cpp/cinemaraw/include \
        -I"$out/legacy/lib/include" -I"$out/legacy/thirdparty" \
        tools/mcraw/legacy_audio.cpp app/src/main/cpp/cinemaraw/src/Encoder.cpp \
        app/src/main/cpp/cinemaraw/src/ContainerWriter.cpp \
        "$out/legacy/lib/Decoder.cpp" "$out/legacy/lib/RawData.cpp" \
        "$out/legacy/lib/RawData_Legacy.cpp" -o "$out/legacy_audio"
    "$out/legacy_audio"
else
    echo "warning: shallow motioncam-decoder checkout, legacy audio check skipped"
fi
if [ "$#" -ge 3 ]; then
    "$out/interop" "$3"
    if [ -x "$out/legacy_audio" ]; then "$out/legacy_audio" "$3"; fi
fi
