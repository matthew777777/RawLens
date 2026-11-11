#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
if [[ "$(uname -s)" == Darwin ]]; then
  if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -z "$JAVA_HOME" ]] && command -v brew >/dev/null; then
      JAVA_HOME="$(brew --prefix openjdk)/libexec/openjdk.jdk/Contents/Home"
    fi
    export JAVA_HOME
  fi
fi
cmake -S "$ROOT" -B "$ROOT/build" -DCMAKE_BUILD_TYPE=Release "$@"
cmake --build "$ROOT/build" --parallel "${JOBS:-4}"
