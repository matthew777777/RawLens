# Manual lens feedback probe — 2026-11-02

> Repository context: [current source state](../../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Connected device: Xiaomi model 25080RABDG, camera 0, RAW_SENSOR 4080x3060 +
640x480 YUV. Isolated com.matthew.afprobe package; normal RawLens not replaced.
User confirmed a steady detailed nearby target. Test passed in 24.027 seconds.

18 commands, two forward/reverse sweeps through 0/2.5/5/7.5/10 reported focus
units, including two no-change zero-position commands. 696 exact RAW/result pairs
(including warmup), no capture failures. Lens calibration reports UNCALIBRATED;
these values must not be translated into physical subject distances.

| Measurement | Median | p95 |
| --- | --- | --- |
| Submit to sensor timestamp of first of three matching stationary samples | 108.4 ms | 123.1 ms |
| Submit to capture-start callback of that first sample | 158.5 ms | 173.1 ms |
| Submit to availability of third matching stationary RAW/result pair | 245.8 ms | 264.6 ms |

The first two rows describe the start of a retrospectively verified sequence,
not the time at which the controller could already verify three-frame stability.
REALTIME sensor timestamp source permits sensor/elapsedRealtime comparison here.
The last row includes three-frame confirmation and callback/image delivery.

Exposure was held at reported ISO 2484 and 9,997,740 ns during all tagged commands.
A provisional central pooled-green gradient metric varied by 1.87x across tail
measurements. This supports optical response but does not establish best focus,
mechanical settling, useful fine step resolution or a noise-robust AF metric.
The two sweeps also show score variation, and the high ISO requires caution.
Optical plateau detection uses a 15% score band and must not be treated as an
independent focus-success verdict. Black normalization is an approximate four-phase
average for this diagnostic; production scoring must normalize Bayer phases first.

## Interpretation

Manual commands and exact-frame metadata work on this route. A controller should
be driven by matching observations, not assume a new focus score every preview
frame corresponds to its last command. The observed three-frame evidence delay
is roughly a quarter second for this stream combination and this qualification
rule. Do not impose that delay on every future step or call it actuator travel
time: determine the minimum safe evidence requirement and smaller-step timing next.
One run cannot qualify another lens, RAW mode, lighting condition or recording path.

## Reproduce

Build :app:assembleGithubDeviceTestAndroidTest, then use
`tools/autofocus-probe/run_probe.py --manual-latency` with app/test APK paths,
`--camera-id 0` and an output JSONL path. The runner repackages test/runtime DEX
into the isolated probe package. Analyze with `analyze_manual_latency.py`.

The first diagnostic run failed during shutdown from a late callback writing to
a closed trace. The corrected run disables callback output before closing resources;
this directory contains only the corrected passing run.
