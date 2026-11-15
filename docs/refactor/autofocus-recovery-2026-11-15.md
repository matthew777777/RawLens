# Autofocus recovery, 2026-11-15

Input: `logcat-20261114_120139_460.txt`, Xiaomi M2010J19CG, SDK 29.
Route 4 has 199 logged AF results, all with missing AF state and reported focus
10 D. Its main RAW sessions have no preview target. Route 0 reports active
scan after a tap, then INACTIVE without a locked verdict; continuous-picture
results do show AF activity. These observations do not establish the cause on
the separately reported Poco M5s.

Changes:

- The bounded RAW-only preview remedy now evaluates continuous AF independently
  of AE/AWB. Working exposure or white balance cannot suppress AF recovery.
  Collect at least 45 continuous-AF results and wait at least three seconds;
  slow streams keep collecting rather than silently abandoning the probe.
  Manual focus and active touch locks pause observation. Existing preview
  rejection/persistence limits remain in force.
- An AUTO/MACRO tap scan that times out after receiving AF metadata gets one
  CANCEL/START retry in advertised CONTINUOUS_PICTURE. Missing metadata alone
  does not initiate that retry. Explicit NOT_FOCUSED_LOCKED remains a failure.
  The retry has a new run/frame boundary and a 3.5-second budget; it cannot
  recurse. Retap, manual focus and stop invalidate it. Regions and AE hold
  survive the retry. PASSIVE_FOCUSED never counts as a tap lock.

Validation: focused PhotoAutoFocusTest and SessionStreamComboTest plus
`:app:assembleGithubDebug -PfreezeVersion` passed. Full JVM regression suite passed: 2,019 tests, zero failures/errors, nine
skipped (including 29 autofocus and 30 stream-selection tests). Optical sharpness, preview-remedy success and
latency on the two reported phones still require device validation. A timed
out tap with metadata may now take up to seven seconds to reach its verdict.
