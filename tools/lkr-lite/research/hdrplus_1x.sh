#!/bin/zsh
# All HDR+ subset bursts at 1x, LK-R and Lite DNGs kept: OUT/<burst>-x1-{lkr,lite}.dng + worst-region PNGs.
O=${1:-$HOME/Downloads/LKRL_hdrplus_1x}; mkdir -p $O
for b in ~/Downloads/hdrplus_subset/*(/); do
  [[ -f $O/${b:t}-x1.json ]] && continue
  /tmp/rawlens-isp-analysis/bin/python -I ${0:A:h}/full_compare.py $b $O --s 1 --keep-dng --extra "--guide 2 --msmooth 1 --msr 1" || echo "FAILED ${b:t}"
done
