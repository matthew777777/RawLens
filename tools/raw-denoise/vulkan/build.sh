#!/bin/sh
# Compile shaders -> app assets spirv/rawdn and the specialised modules for the offline analysers -> build/shader-analysis/v1dn-spv
set -e
cd "$(dirname "$0")/../../../app/src/main/cpp/rawdn/shaders"; O=../../../assets/spirv/rawdn; B=../../../../../../build/shader-analysis/v1dn-spv; mkdir -p "$O" "$B"; rm -f "$B"/*.spv
for s in pack down dct resid fin sites unpack coarse mix; do
  glslangValidator -V --target-env vulkan1.1 -I. rawdn_$s.comp -o "$O"/rawdn_$s.spv | grep -E "ERROR|error" && exit 1
  spirv-opt -O "$O"/rawdn_$s.spv -o "$O"/rawdn_$s.spv; spirv-val --target-env vulkan1.1 "$O"/rawdn_$s.spv
  [ $s = dct ] || cp "$O"/rawdn_$s.spv "$B"
done
for k in 0 1 2; do spirv-opt --set-spec-const-default-value "0:$k" --freeze-spec-const -O "$O"/rawdn_dct.spv -o "$B/rawdn_dct_s$k.spv"; done
