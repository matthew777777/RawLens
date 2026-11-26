#!/bin/zsh
# Day fine-tune of PMRID v3b: half of each batch from make_day_crops.py (bright, textured, low noise), lr 1e-4, 6k steps.
# Same rules as run_v3.sh: lowest priority, paused during Gradle builds, resumes from the last state when re-run.
D=${0:A:h}; R=~/Downloads/pmrid_runs
pgrep -f "train_pmrid.py.*pmrid_v3day.pt" >/dev/null && { echo already running; exit 1 }
nice -n 19 env OMP_NUM_THREADS=1 ~/RawLens-prior/venv/bin/python -I $D/train_pmrid.py ~/Downloads/pmrid_cache $R/pmrid_v3day.pt \
  --init $R/pmrid_v3b.pt --steps 6000 --lr 1e-4 --struct 0.2 --amax 6e-3 --day ~/Downloads/pmrid_cache_day --resume >>$R/pmrid_v3day.log 2>&1 &
T=$!; caffeinate -i -w $T &
trap "kill -CONT $T; kill $T" INT TERM
while kill -0 $T 2>/dev/null; do
  if pgrep -f "GradleWrapperMain|gradle-wrapper.jar" >/dev/null; then kill -STOP $T; else kill -CONT $T; fi
  sleep 20
done
