#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
if [[ $# -lt 2 || "${1:-}" == --help ]]; then
  echo 'Usage: ./run.sh BURST_DIR OUT.dng [--s 1|2] [--frames K] [--crop W H] [--prior 0|1]'
  echo 'Example: ./run.sh ./burst ./merged.dng --s 2'
  if [[ "${1:-}" == --help ]]; then exit 0; else exit 2; fi
fi
if [[ ! -d "$1" ]]; then echo "Burst directory not found: $1" >&2; exit 2; fi
if [[ ! -x "$ROOT/build/pipeline/lkr_full_run" ]]; then "$ROOT/build.sh"; fi
if [[ "$(uname -s)" == Darwin ]]; then
  export DYLD_FALLBACK_LIBRARY_PATH="/opt/homebrew/lib:/usr/local/lib:${DYLD_FALLBACK_LIBRARY_PATH:-}"
fi
exec "$ROOT/build/pipeline/lkr_full_run" "$@"
