#!/bin/zsh
# Self-supervised tiles from REAL bursts: Lite 2x with one frame held out, traces kept (hybrid merge +
# held-out raw + motion). gen_selfsup.sh BURSTS_DIR OUT_DIR [tiles_per_burst=6]
B=${1:?bursts}; O=${2:?out}; N=${3:-6}; mkdir -p $O
L=${0:A:h}/../../build/host/lkrl_full_run
for d in $B/*(/); do
  nf=$(ls $d/*.dng 2>/dev/null | wc -l | tr -d ' '); [ $nf -lt 4 ] && continue
  dims=($(~/RawLens-prior/venv/bin/python -c "import rawpy,sys;r=rawpy.imread(sys.argv[1]);print(r.raw_image_visible.shape[1],r.raw_image_visible.shape[0])" $(ls $d/*.dng | head -1)))
  for t in $(seq 1 $N); do
    x=$(( (RANDOM % ((dims[1] - 600) / 2)) * 2 + 32 )); y=$(( (RANDOM % ((dims[2] - 600) / 2)) * 2 + 32 ))
    k=$(( RANDOM % (nf - 2) + 1 )); [ $k -ge $nf ] && k=1
    pre=$O/${d:t}_x${x}_y${y}_k$k
    [ -f ${pre}_tile_0_0-holdout-raw.f32 ] && continue
    LKRL_TRACE_PREFIX=$pre $L $d $pre.dng --s 2 --crop 512 512 --at $x $y --holdout $k >/dev/null 2>&1
    rm -f $pre.dng $pre.dng.f32 $pre.dng.holdout.f32
    find ${pre:h} -maxdepth 1 -type f -name "${pre:t}_tile_0_0-*" ! -name "*-hybrid.f32" ! -name "*-holdout-*" ! -name "*-adjoint-push.bin" -delete
    echo "${d:t} $x $y k$k $( [ -f ${pre}_tile_0_0-holdout-raw.f32 ] && echo ok || echo FAIL)"
  done
done
