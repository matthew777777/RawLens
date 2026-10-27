# Program exposure cadence

Compared with Raspberry Pi libcamera `src/ipa/rpi/controller/rpi/agc_channel.cpp`:
https://github.com/raspberrypi/libcamera/blob/main/src/ipa/rpi/controller/rpi/agc_channel.cpp

Upstream `AgcChannel::process` calls `filterExposure` once per processed statistics
frame. `filterExposure` blends total exposure with speed 0.2, speeds up near the
target, and leaves the 2% stable region unchanged. Startup uses speed 1.

RawLens previously advanced multiple simulated sensor frames inside each 100 ms
callback, then submitted only the final exposure. At 30 fps, three filter steps
collapsed into one visible change (48.8% of a distant target difference rather
than 20%). Now each callback advances one filter step and submits that pair;
callbacks are spaced by the reported sensor frame duration, rounded up to
milliseconds (34 ms fallback). Delayed callbacks do not batch catch-up steps.
Settled Program metering keeps the existing 250 ms active cadence instead of
backing off to 1000 ms, so panning is detected sooner. Slow-meter backoff remains.

These are RawLens scheduling adaptations, not new upstream tuning constants.
This remains a partial port: RAW green metering, PhotonCamera exposure splitting,
and Camera2 request latency differ from the Raspberry Pi pipeline. The previously
documented abbreviated revision 0bf3fc5 could not be fetched during this review;
the comparison used the upstream main source, not a verified pinned revision.
Authorship of individual lines cannot be established from source text alone.

Device validation still required: pan between bright and dark scenes in both
directions, including ISO/shutter limits and 50/60 Hz lighting. JVM tests cannot
establish visible smoothness or the HAL's actual exposure quantisation.
