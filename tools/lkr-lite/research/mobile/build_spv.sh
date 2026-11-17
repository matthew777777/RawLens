#!/bin/zsh
# SPDX-License-Identifier: Apache-2.0
# Compile every LK-R Lite compute shader to binary SPIR-V (same glslc + flags as CMakeLists.txt)
# for offline GPU analysis (tools/shader-analysis/analyze.py):  build_spv.sh OUT_DIR
set -e
O=${1:?out}; mkdir -p $O
R=${0:A:h}/../..; S=$R/src/main/cpp/shaders
G=${GLSLC:-$(ls -d ~/Library/Android/sdk/ndk/*/shader-tools/darwin-x86_64 | tail -1)/glslc}
for f in $(sed -n 's/^set(LKRL_SHADERS \(.*\))/\1/p' $R/src/main/cpp/CMakeLists.txt); do
  $G --target-env=vulkan1.1 -fshader-stage=compute -O -I $S -o $O/$f.spv $S/$f.comp
done
for f in resid weight_tdet weight_th weight_t weight_tv weight_td weight_guide weight_minh weight_min dct_x_y dct_x_u dct_x_v dct_yuv; do
  $G --target-env=vulkan1.1 -fshader-stage=compute -O -I $S/lkr -o $O/lkr_$f.spv $S/lkr/$f.comp
done
ls $O | wc -l
