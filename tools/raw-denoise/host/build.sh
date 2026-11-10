#!/bin/sh
# Builds the host driver and the SPIR-V into tools/raw-denoise/host/build (macOS + MoltenVK + glslang from Homebrew).
set -e
here="$(cd "$(dirname "$0")" && pwd)"; src="$here/../../../app/src/main/cpp/rawdn"; out="$here/build"
mkdir -p "$out"
for s in pack down dct resid fin unpack; do glslangValidator -V --quiet "$src/shaders/rawdn_$s.comp" -o "$out/rawdn_$s.spv"; done
c++ -std=c++17 -O2 -Wall -o "$out/rawdn_host" "$here/rawdn_host.cpp" "$src/rawdn.cpp" -I"$src" \
  -I/opt/homebrew/include -L/opt/homebrew/lib -lMoltenVK
echo "built $out/rawdn_host"
