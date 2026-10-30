#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TASK_BUILD="$(mktemp -d "${TMPDIR:-/tmp}/rawlens-fd-smoke.XXXXXX")"
cd "$TASK_BUILD"
cmake -DCRAW_DIR="$TASK_ROOT/third_party/craw" -P "$TASK_ROOT/app/src/main/cpp/craw_fd_writer.cmake"
"${CXX:-clang++}" -std=c++20 -O1 -pthread \
  -I"$TASK_BUILD/craw-fd/include" -I"$TASK_ROOT/third_party/craw/encoder/include" \
  -I"$TASK_ROOT/third_party/craw/encoder/src" \
  "$TASK_ROOT/tools/direct-recording/craw_fd_smoke.cpp" \
  "$TASK_BUILD/craw-fd/src/Writer.cpp" "$TASK_ROOT/third_party/craw/encoder/src/Encoder.cpp" \
  -o "$TASK_BUILD/craw-fd-smoke"
"$TASK_BUILD/craw-fd-smoke"
