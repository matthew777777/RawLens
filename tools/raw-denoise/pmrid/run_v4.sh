#!/bin/zsh
# PMRID v4: every phone DNG on the Mac (make_crops_v4.py: Redmi + iPhone 11 + others, ~4.9k crops), warm start from v3b, 60k steps.
# Validation = the same held-out crops as v3 (--val pmrid_cache). Same rules as run_v3.sh: lowest priority, paused during Gradle builds,
# resumes from the last state (every 500 steps) when re-run.
D=${0:A:h}; R=~/Downloads/pmrid_runs
pgrep -f "train_pmrid.py.*pmrid_v4.pt" >/dev/null && { echo already running; exit 1 }
nice -n 19 env OMP_NUM_THREADS=1 ~/RawLens-prior/venv/bin/python -I $D/train_pmrid.py ~/Downloads/pmrid_cache_v4 $R/pmrid_v4.pt \
  --init $R/pmrid_v3b.pt --steps 60000 --struct 0.2 --amax 6e-3 --val ~/Downloads/pmrid_cache --resume >>$R/pmrid_v4.log 2>&1 &
T=$!; caffeinate -i -w $T &
trap "kill -CONT $T; kill $T" INT TERM
while kill -0 $T 2>/dev/null; do
  if pgrep -f "GradleWrapperMain|gradle-wrapper.jar" >/dev/null; then kill -STOP $T; else kill -CONT $T; fi
  sleep 20
done
