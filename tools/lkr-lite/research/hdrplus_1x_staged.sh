#!/bin/zsh
O=$HOME/Downloads/LKRL_hdrplus_1x
for b in /private/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/dd8e0948-6166-4022-a986-573483c43e5b/scratchpad/hdrp_stage/*(/); do
  [[ -f $O/${b:t}-x1.json ]] && continue
  /tmp/rawlens-isp-analysis/bin/python -I ${0:A:h}/full_compare.py $b $O --s 1 --keep-dng --extra "--guide 2 --msmooth 1 --msr 1" || echo "FAILED ${b:t}"
done
