#!/bin/sh
# Rebuild the checked-in RawTherapee capture-sharpening modules (one per RT
# kernel class: 3x3, 5x5, 7x7 with border 5; 9x9, 13x13 with border 8 and a
# 24 px tile so four shared arrays fit Mali's 32 KB). Run from the repo root.
set -eu
src=app/src/main/assets/spirv/rtcs.vk.glsl
for spec in "1 32 5" "2 32 5" "3 32 5" "4 24 8" "6 24 8"; do
  set -- $spec
  out=app/src/main/assets/spirv/rtcs_r$1.spv
  glslangValidator -V --target-env vulkan1.0 -S comp -DR=$1 -DTILE=$2 -DBORDER=$3 "$src" -o "$out"
  spirv-val --target-env vulkan1.0 "$out"
done
