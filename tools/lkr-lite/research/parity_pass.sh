#!/bin/zsh
# Parity pass: every burst (My/Bursts + HDR+ subset with frames), full 1x then 2x, LK-R and Lite
# with identical flags; both DNGs kept in OUT for side-by-side review (render_parity.py).
O=${1:-$HOME/Downloads/LKRL_parity}; mkdir -p $O
H=$HOME/Downloads/hdrplus_subset
B=($HOME/Downloads/My/Bursts/*(/) /private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/hdrp_stage/*(/) $H/33TJ_20150612_201525_012 $H/5a9e_20150328_160520_398 $H/IMG_20210228_133623 $H/Samsung)
for s in 1 2; do
  for b in $B; do
    [[ -f $O/${b:t}-x$s.json ]] && continue
    w=(); [[ $s == 2 ]] && w=(--workers 2)
    /tmp/rawlens-isp-analysis/bin/python -I ${0:A:h}/full_compare.py $b $O --s $s --keep-dng --extra "--guide 2 --msmooth 1 --msr 1" 2>&1 | grep '^{' || echo "FAILED ${b:t} x$s"
  done
done
