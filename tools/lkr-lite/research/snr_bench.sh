#!/bin/zsh
# SNR baseline: LK-R and Lite on synthetic bursts (vs truth) and real-burst crops (flat-patch SNR).
# snr_bench.sh OUT_DIR [TAG] ; extra env (LKRL_*, LKR flags in XTRA) is passed through.
O=${1:?out}; T=${2:-base}; mkdir -p $O
R=${0:A:h}/../..; PY=/tmp/rawlens-isp-analysis/bin/python
export DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib
F=(--guide 2 --msmooth 1 --msr 1 ${=XTRA})
ALL=(lkr:$R/lkr-pipeline/build/host/lkr_full_run lkrp:$R/lkr-pipeline/build/host/lkr_full_run lite:$R/lkr-lite/build/host/lkrl_full_run litep:$R/lkr-lite/build/host/lkrl_full_run)
BIN=(); for bb in $ALL; do [[ -z $ONLY || " $ONLY " == *" ${bb%%:*} "* ]] && BIN+=$bb; done
for d in $O/syn_n*(/); do
  for s in 1 2; do for bb in $BIN; do
    tag=${bb%%:*}; exe=${bb#*:}; P=(); [[ $tag == *p ]] && P=(--prior 1); f=$O/$T-$tag-${d:t}-s$s.dng
    $exe $d $f --s $s $F $P --crop 512 512 --at 1536 1024 >/dev/null 2>&1 || { echo "FAIL $f"; continue; }
    echo "$T $tag ${d:t} s$s $($PY -I ${0:A:h}/snr_bench.py syn $f.f32 $d --s $s)"
    rm -f $f $f.f32
  done; done
done
M=$HOME/Downloads/My/Bursts; H=$HOME/Downloads/hdrplus_subset
for spec in "$M/IMG_20260921_194618_520 1536 1024" "$M/IMG_20261112_074919_397 1536 1024" "$M/IMG_20260929_170823_957 1536 1024" "$H/33TJ_20150612_201525_012 1536 1024"; do
  b=${spec%% *}; xy=(${=spec#* })
  for s in 1 2; do for bb in $BIN; do
    tag=${bb%%:*}; exe=${bb#*:}; P=(); [[ $tag == *p ]] && P=(--prior 1); f=$O/$T-$tag-${b:t}-s$s.dng
    $exe $b $f --s $s $F $P --crop 1024 1024 --at $xy >/dev/null 2>&1 || { echo "FAIL $f"; continue; }
    echo "$T $tag ${b:t} s$s $($PY -I ${0:A:h}/snr_bench.py flat $f.f32 $((1024*s)) $((1024*s)))"
    rm -f $f $f.f32
  done; done
done
