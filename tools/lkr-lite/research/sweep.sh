#!/bin/zsh
# Acceptance sweep: every burst in ~/Downloads/My/Bursts and ~/Downloads/hdrplus_subset, 1x and 2x,
# LK-R vs Lite with identical flags. Usage: sweep.sh OUT_DIR [extra flags...]
O=$1; shift
EXTRA=${*:-"--guide 2 --msmooth 1 --msr 1"}
PY=/tmp/rawlens-isp-analysis/bin/python
HERE=${0:A:h}
mkdir -p $O
for b in ~/Downloads/My/Bursts/*(/) ~/Downloads/hdrplus_subset/*(/); do
  for s in 2 1; do
    [[ -f $O/${b:t}-x$s.json ]] && continue
    $PY -I $HERE/full_compare.py $b $O --s $s --extra "$EXTRA" || echo "FAILED ${b:t} x$s"
  done
done
