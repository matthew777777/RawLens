#!/usr/bin/env bash
# Compare existing native alignment options with identical reconstruction settings.
set -euo pipefail
if (( $# < 3 )); then
  printf 'Usage: bash %s LKRL_FULL_RUN BURST_DIR OUTPUT_DIR [shared host arguments]\n' "$0" >&2
  exit 2
fi
runner=$1 burst=$2 output=$3
shift 3
mkdir -p "$output"
for variant in baseline block_search no_subtiles; do
  case "$variant" in
    baseline) "$runner" "$burst" "$output/$variant.dng" "$@" ;;
    block_search) "$runner" "$burst" "$output/$variant.dng" "$@" --bsearch 1 ;;
    no_subtiles) "$runner" "$burst" "$output/$variant.dng" "$@" --sublk 0 ;;
  esac > "$output/$variant.log" 2>&1
done
