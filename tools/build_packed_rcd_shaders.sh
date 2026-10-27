#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
shader_dir="$repo_root/app/src/main/assets/shaders/rcd_packed"
for source in "$shader_dir"/*.comp; do
    glslangValidator -V --target-env vulkan1.1 "$source" -o "${source%.comp}.spv"
    spirv-val --target-env vulkan1.1 "${source%.comp}.spv"
done
python3 "$repo_root/tools/verify_packed_rcd_median.py"
