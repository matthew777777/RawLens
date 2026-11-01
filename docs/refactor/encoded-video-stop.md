# Encoded Video naming and EOS stop fix

## Vivo confirmation

The user confirmed clean RCD and MHC output after the Qualcomm compatibility
change. The follow-up files `IMG_20261031_223421_098_LOG709.mp4` and
`IMG_20261031_223440_483_LOG709.mp4` are HEVC Main 10, 3840x1920, 30 fps,
with 67 and 47 video frames respectively. Decoded sample frames show clean
geometry. The matching log selects `portable-fixed16-packed32-shared` for
RCD. This confirms that compatibility path resolves the reported RCD
artifacts; it does not distinguish fixed workgroups from packed shared
storage as the necessary driver workaround.

## Stop delay

The matching Vivo log shows stop-to-preview-detach delays of 8.166 s for
RCD and 8.178 s for MHC. `drainLoop` consumed output EOS in its primary
loop, then initialized the fallback tail loop with `eos=false`. That loop
waited for a second EOS marker for another eight seconds, while `stop()`
waited for the drain thread before closing the muxer.

The primary and fallback loops now retain the same output-EOS state.
Already-consumed EOS skips the fallback tail. Final audio-queue flushing
completes before the drain-completion latch is released. The eight-second
fallback remains available when the main loop exits without EOS; its
deadline now uses monotonic elapsed time. Normal shutdown no longer waits
for a second marker. A file-log line records total stop duration.

The headless recording regression now asserts stop completes within five
seconds and continues validating the muxed video/audio output. Its normal
configurations explicitly select MHC or superpixel; relying on the recorder
default had silently selected RCD after that default changed.

Xiaomi results: RCD stop 607 ms; MHC stop 472 ms; superpixel stop 472 ms.
The RCD regression passed. The first explicit cold MHC run met its stop
bound but failed a zero-starvation assertion with one codec input starved
during startup. The warm rerun passed the full MHC and superpixel checks.
These are Xiaomi results; the shortened stop still needs Vivo confirmation.

## Visible mode names

The app now calls its video modes **RAW Video** and **Encoded Video** in
quick controls, settings, the mode badge, shutter accessibility labels,
status messages, welcome copy, and user-visible save errors. The encoded
profile tile is **PROFILE**, since output can be BT.709 or a log profile.
Current README terminology and existing UI assertions were updated.
Preference keys and implementation identifiers retain compatibility.

The combined isolated test APK and minified release built successfully.
As in previous isolated builds, duplicate isolated lint tasks were excluded
after that variant's lint-tool crash; regular release checks passed.
Handoff: `/tmp/RawLens-2.0.60-Encoded-Video-stop-fix-arm64.apk`, package
`com.matthew.rawlens.vivocheck`, separately installable. APK signature verified;
the portable RCD shader payload matches the validated compatibility files.

Evidence: [RCD regression](results/encoded-video-stop-2026-10-31/rcd-test.txt),
[RCD timing](results/encoded-video-stop-2026-10-31/rcd-timing.txt),
[cold MHC run](results/encoded-video-stop-2026-10-31/mhc-cold-run.txt),
[MHC/superpixel rerun](results/encoded-video-stop-2026-10-31/mhc-retest.txt),
[MHC timing](results/encoded-video-stop-2026-10-31/mhc-timing.txt),
[superpixel timing](results/encoded-video-stop-2026-10-31/superpixel-timing.txt),
[release build](results/encoded-video-stop-2026-10-31/release-build.txt).
