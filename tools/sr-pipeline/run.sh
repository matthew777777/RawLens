#!/usr/bin/env bash
# Build the desktop CLI, JNI library and shaders, then forward CLI arguments.
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$module_dir/../.." && pwd)"

if [[ $# == 0 || ${1:-} == --help || ${1:-} == -h ]]; then
    cat <<'HELP'
Usage: tools/sr-pipeline/run.sh --in BURST_DIR --out OUTPUT_DIR [options]

Defaults: --backend vulkan --kernel kernelnet --scale 2 --lut auto
Builds the desktop CLI, native DNG/Vulkan library and shaders automatically.
Additional sr-pipeline options (e.g. --ref, --crop, --limit) are forwarded.
Requires JDK 21+, CMake, Vulkan headers/loader, glslangValidator and a Vulkan
driver. On macOS the driver is MoltenVK. See tools/sr-pipeline/README.md.
JAVA_OPTS defaults to -Xmx6g; set it to override the desktop JVM heap.
HELP
    exit 0
fi

for tool in cmake java; do
    command -v "$tool" >/dev/null || { echo "Missing $tool; see $module_dir/README.md" >&2; exit 1; }
done
if [[ -z ${JAVA_HOME:-} ]]; then
    if [[ $(uname -s) == Darwin ]]; then
        export JAVA_HOME="$(/usr/libexec/java_home)"
    else
        java_bin="$(readlink -f "$(command -v java)")"
        export JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
    fi
fi
native_dir="$module_dir/build/host"
cmake -S "$module_dir/host" -B "$native_dir" -DCMAKE_BUILD_TYPE=Release \
    "-DSRP_JAVA_HOME=$JAVA_HOME"
# Only build the CLI's library and shader targets, not the proof executables.
targets=(srpipeline_jni)
for shader in merge_accumulate gat align corr fft ica demosaic grad kernels divide robustness merge_splat kernelnet grey_fft corr_direct ica_wg; do
    targets+=("srp_${shader}_spv")
done
cmake --build "$native_dir" --parallel 8 --target "${targets[@]}"
(cd "$repo_dir" && ./gradlew :tools:sr-pipeline:installDist --console=plain)
case "$(uname -s)" in
    Darwin) native_lib="$native_dir/libsrpipeline_jni.dylib" ;;
    Linux) native_lib="$native_dir/libsrpipeline_jni.so" ;;
    *) echo "Unsupported host; use the manual build instructions." >&2; exit 1 ;;
esac
export JAVA_OPTS="${JAVA_OPTS:--Xmx6g}"
exec "$module_dir/build/install/sr-pipeline/bin/sr-pipeline" \
    --backend vulkan --kernel kernelnet --scale 2 --lut auto \
    --lib "$native_lib" --spv-dir "$native_dir" "$@"
