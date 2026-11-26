#!/bin/zsh
# Train PMRID v3 without slowing the Mac: lowest CPU priority (not taskpolicy -b: E-cores only, 2x slower data), paused while a Gradle build runs,
# resumes from the last saved state (every 500 steps) after a kill or reboot. Re-run this script to continue.
D=${0:A:h}; R=~/Downloads/pmrid_runs
pgrep -f "train_pmrid.py.*pmrid_v3b.pt" >/dev/null && { echo already running; exit 1 }
nice -n 19 env OMP_NUM_THREADS=1 ~/RawLens-prior/venv/bin/python -I $D/train_pmrid.py ~/Downloads/pmrid_cache $R/pmrid_v3b.pt \
  --init $R/pmrid_v3_it3500.pt --steps 26500 --struct 0.2 --amax 6e-3 --resume >>$R/pmrid_v3b.log 2>&1 &
T=$!; caffeinate -i -w $T &
trap "kill -CONT $T; kill $T" INT TERM
while kill -0 $T 2>/dev/null; do
  if pgrep -f "GradleWrapperMain|gradle-wrapper.jar" >/dev/null; then kill -STOP $T; else kill -CONT $T; fi
  sleep 20
done
