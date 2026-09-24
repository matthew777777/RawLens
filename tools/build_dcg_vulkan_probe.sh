#!/bin/sh
set -eu
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ndk="$sdk/ndk/27.0.12077973"
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$root/app/build/dcg-probe/jniLibs/arm64-v8a"
"$ndk/shader-tools/darwin-x86_64/glslc" --target-env=vulkan1.1 \
  "$root/app/src/androidTest/assets/dcg/merge.comp" \
  -o "$root/app/src/androidTest/assets/dcg/merge.spv"
"$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android29-clang++" \
  -std=c++17 -O2 -fPIC -shared -static-libstdc++ \
  "$root/app/src/androidTest/cpp/dcg_vulkan_probe.cpp" \
  -landroid -llog -lvulkan -Wl,-z,max-page-size=16384 \
  -o "$root/app/build/dcg-probe/jniLibs/arm64-v8a/libdcg_probe.so"
