#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
shader_dir="$repo_root/app/src/main/assets/shaders"
for relative in vf/vf_gradeyuv vf/vf_mhcyuv vf/vf_mhcyuv_f16 vf/vf_rcdyuv vf/vf_rcdyuv_f16 rcd_packed/rcd_output_yuv; do
    glslangValidator -V --target-env vulkan1.1 -DRECORD_CUBE=1 \
        "$shader_dir/$relative.comp" -o "$shader_dir/${relative}_cube.spv"
    spirv-val --target-env vulkan1.1 "$shader_dir/${relative}_cube.spv"
done
