#!/bin/bash
# Reproducible pinned-ICA build (FINAL-14 recipe).
#   ica_pristine.comp -> glslang -> force_fma(PINS) -> nr_div
#     -> wrap_clamp ALL -> spirv-as/val.
# Usage: build_ica.sh [pin_csv] [out.spv]
#   defaults: pins 0,1,4,5,7,8,11,12,13 (FINAL-14), out ica_final.spv
# Verified: default invocation reproduces host/build/ica.spv bit-identical
# (glslangValidator 11.16.6.0, --target-env vulkan1.2).
set -e
D="$(cd "$(dirname "$0")" && pwd)"
PINS="${1:-0,1,4,5,7,8,11,12,13}"
OUT="${2:-$D/ica_final.spv}"
glslangValidator --target-env vulkan1.2 -V "$D/ica_pristine.comp" -o "$D/b0.spv"
spirv-dis "$D/b0.spv" -o "$D/b0.spvasm"
python3 "$D/force_fma.py" "$D/b0.spvasm" "$D/b1.spvasm" "$PINS"
python3 "$D/nr_div.py" "$D/b1.spvasm" "$D/b2.spvasm"
python3 "$D/wrap_clamp.py" "$D/b2.spvasm" "$D/b3.spvasm" ALL
spirv-as "$D/b3.spvasm" -o "$OUT" && spirv-val "$OUT"
rm -f "$D/b0.spv" "$D/b0.spvasm" "$D/b1.spvasm" "$D/b2.spvasm" "$D/b3.spvasm"
echo "OK $OUT (pins $PINS)"
