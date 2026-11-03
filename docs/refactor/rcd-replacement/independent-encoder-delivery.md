# Independent encoder delivery recovery

2026-11-01. Starting HEAD 28977494032f743742eafbbf77ff437317544554,
branch refactor/2.0; existing unrelated changes preserved. Xiaomi 25080RABDG
fe79feha9lmb6hhi was connected. No archived shader body or old numerical
reference was used.

Added internal `validatePhotoRcdGpuWriter` / headless `--gpu-writer` opt-in.
The independent RCD route can request ImageWriter encoder-owned storage instead
of disabling it unconditionally. Supported codec/layout selection remains
explicit; other recording defaults are unchanged.

Before camera streaming, `PhotoRcdWriterProbe` develops a spatial synthetic
RAW pattern using the approved baseline adapter. It grades that RGB through the
current shader into both app-owned linear P010 and an encoder-owned image at the
actual requested output dimensions. After blocking GPU completion it compares a
16×16 Y/U/V grid against Android's reported plane row/pixel strides. Descriptor
shape or successful Vulkan import alone cannot pass this content check. The
synthetic image is closed, never queued to the encoder. Probe failures retain
plane-copy delivery. Sparse-grid coverage is a capability diagnostic, not full
image/encoder-output quality acceptance.

The existing encoder image acquire fence, completion fd, ordered queue gate and
writer image ownership carry real frames. The native RCD engine still waits and
reads half output back to CPU; JNI still copies RGB into HardwareBuffer. This
change addresses P010 delivery only, not native RCD async submission or RGB copy
removal. Those remain required next work. Scale-4 recordings are explicitly
approximate 960×540 reconstruction, not full-detail 1080p/1440p/4K quality.

Validation and measurements follow in the recording reports. No release APK
inspection or current JVM-suite result is asserted by this incremental change.

## First measured diagnostic

`recordings-independent-writer/1920x1080-30-writer-probe/` records the initial
30-second 1080p run. Its own matching APK identity is in `runs.json`. The
content probe logged success, CPU P010 copy average/max were both zero, and
276 frames used RCD with zero MHC frames. Full Main10/AAC decode passed.
Delivered sensor cadence was 9.3178 fps, median RCD render 104.4866 ms, p95
111.9368 ms, and the target failed. Reconstruction was explicitly scale 4
(960×540), not full-source detail. Competing workload was uncontrolled; no
causal speedup is established against earlier runs.

A subsequent source change includes CPU_WRITE usage in the GPU writer's image
allocation, so content-probe rejection can use the ordinary plane-copy fallback.
The final-build three-mode reports are separate from this initial run.

## Remaining dependency for asynchronous reconstruction

The portable host engine currently owns a separate Vulkan instance/device and a
single reusable frame allocation. Its half-output buffer is a host-visible SSBO;
the existing grade pipeline imports a sampled Android RGBA16F image. A correct
async bridge therefore requires a supported GPU-written shared RGB representation
and a completion semaphore/fence dependency between those devices/contexts, plus
per-slot native frame resources. Returning early from `renderRawHalf` would race
its upload/intermediates, while queueing grade before RGB completion would read
unfinished data. No such shortcut is introduced. GPU-writer P010 delivery is a
separate recovered benefit; it does not prove the RGB bridge exists.

Reproduction (matching isolated headless APKs built with `-PphotoRcdHeadless`):

```sh
python3 tools/photo-rcd/run_android_recordings.py --serial fe79feha9lmb6hhi \
  --requirements-input --reconstruction-scale 4 --gpu-writer --seconds 30 \
  --label writer-final --out docs/refactor/rcd-replacement/recordings-independent-writer-final
python3 tools/photo-rcd/analyze_recordings.py \
  docs/refactor/rcd-replacement/recordings-independent-writer-final
```

Each recording report pins the installed APK; changing source/builds does not
retroactively change its evidence. Default MHC and still-photo behavior remain
unchanged. No release publication, remote history operation or issue comment
was made.

## Delivery fence correction

The first three-mode run exposed eleven 4K delivery failures at the inherited
100 ms worker wait. Timeout did not establish that the GPU had stopped accessing
an encoder image. The independent GPU-writer route now waits up to 30 seconds
for actual completion. If completion remains unproved, the take fails visibly
and retains the writer Image, AHB, RAW lease and original fd across teardown.
The slot's stale fd is cleared, and later takes collect those resources only
after the original fence signals; a new take is refused while work is unresolved.
The normal staging/copy wait remains unchanged. Longer waits do not manufacture
throughput or qualify cadence: misses remain visible in sensor timestamps.

Writer worker shutdown uses a longer join budget than its bounded per-job fence
waits, so teardown cannot routinely overtake the new 30-second completion proof.
This affects only opt-in GPU-writer recording. It is a shutdown/lifetime bound,
not a frame pacing adjustment; missed frame deadlines still fail cadence.

The five-test headless correctness suite also passed on the initial delivery
build (`android-independent-writer-regression/run.json`). This covers reference
RCD, integer RAW adapters and independent grade/LUT/P010 checks; it does not
force a GPU timeout or establish throughput. The final fence correction is
validated separately by a fresh recording.
