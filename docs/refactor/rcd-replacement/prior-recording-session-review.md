# Prior recording work: processing time versus delivered cadence

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

The user clarified that our earlier work took the imported backend from roughly
16 fps recording to stable 30 fps; achieving roughly 33.3 ms processing was an
intermediate result, not the completed recording fix. That application pipeline
work should inform this replacement. The supplied session is
`codex-session-01a17924-46f0-77c2-acb2-beac869c424.md`; its hash and narrative
line references are in `prior-recording-session-review.json`.

Only narrative performance/integration evidence was examined for this review.
Historical shader bodies, private archives, old expected pixels and disassembly
were excluded. Historical measurements describe a different implementation and
are not evidence that the current replacement meets its targets.

The session records initial recording at 16.3 fps (line 18867), async improvement
to 21.0 fps (23876), and a roughly 25 ms P010 copy even when processing approached
33.3 ms (27162). Later encoder-owned output removed that CPU transfer and reached
25.4 fps (27755). Later narrative reports stable lower-resolution 30 fps (34101).
The useful application lessons are asynchronous work, explicit buffer/fence
ownership, eliminating delivery copies and auditing actual input/crop/output
work. Overlapping costs must not be summed as though all are serial.

## Current verified differences

`EncodedVideoRecorder.startEncoder()` sets both `requestGpuWriter` and
`gpuWriterMode` to false. The current test logs confirm `delivery=plane-copy` on
this MTK phone. The existing app still contains encoder-owned buffer acquisition
and acquire-fence handling, but that route is disabled. Reusing it requires a new
supported-layout/content probe with the independent grade/P010 implementation;
the old demosaic backend is not needed to establish that application feature.

The new RCD engine additionally waits synchronously for reconstruction, reads
back the final half buffer, and copies that data to an RGB HardwareBuffer before
submitting the separate grade pass. Thus restoring encoder-owned P010 delivery
alone will not make the whole replacement asynchronous or remove the RGB copy.
Those are separate outstanding integration costs.

The full-resolution reference route measured 0.55–0.69 delivered sensor fps;
960×540 approximate reconstruction measured 6.51–7.96 fps across the three
outputs. These diagnostic results identify a substantial reconstruction cost in
addition to delivery overhead. Packing alone is not the principal remaining
cost. See `requirements-recording-summary.json` for stage timings and immutable
recording hashes.

The next implementation priority is to recover the independently audited
application pipeline benefits, with per-frame traces from capture through mux,
while reducing reconstruction cost in parallel. The user permits a separate
approximate RCD mode, but reduced detail is not yet accepted quality and does not
make a failed cadence result a pass.
