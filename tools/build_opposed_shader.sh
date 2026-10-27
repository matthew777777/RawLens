#!/bin/sh
# Rebuild the checked-in Vulkan module; run from the repository root.
set -eu
glslangValidator -V --target-env vulkan1.0 -S comp \
  app/src/main/assets/spirv/opposed.vk.glsl -o app/src/main/assets/spirv/opposed.spv
spirv-val --target-env vulkan1.0 app/src/main/assets/spirv/opposed.spv
