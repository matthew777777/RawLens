#!/usr/bin/env bash
# Vendors the Cinematic RAW Studio (.CRAW) encoder and decoder sources
# (Apache-2.0) into third_party/craw. Run after updating the sibling repos.
#   tools/sync_craw.sh [encoder-repo] [decoder-repo]
set -euo pipefail
cd "$(dirname "$0")/.."
ENC="${1:-$HOME/CinematicRAWStudio-Encoder}"
DEC="${2:-$HOME/CinematicRAWStudio-Decoder}"
DST=third_party/craw
rm -rf "$DST/encoder" "$DST/decoder"
mkdir -p "$DST/encoder" "$DST/decoder"
cp -R "$ENC/include" "$ENC/src" "$ENC/LICENSE" "$ENC/NOTICE" "$DST/encoder/"
cp -R "$DEC/include" "$DEC/src" "$DEC/LICENSE" "$DEC/NOTICE" "$DST/decoder/"
{
    echo "# Cinematic RAW Studio (.CRAW) — vendored sources"
    echo
    echo "Apache-2.0. Synced by tools/sync_craw.sh; do not edit here."
    echo
    echo "| part | repo | commit |"
    echo "|------|------|--------|"
    echo "| encoder | CinematicRAWStudio-Encoder | $(git -C "$ENC" rev-parse HEAD) |"
    echo "| decoder | CinematicRAWStudio-Decoder | $(git -C "$DEC" rev-parse HEAD) |"
} > "$DST/SOURCE.md"
echo "synced into $DST"
