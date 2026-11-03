# Claude handoff — full-resolution recording RCD

Status: **stopped at the user's request**, 2026-11-02 (Europe/Athens). The user
wants to move continuation to Claude tomorrow. No further implementation/tests
are authorized by this handoff alone. Preserve the workspace as it stands.

## Objective and nonnegotiable requirements

Deliver actual RCD recording at 1920×1080/30, 2560×1440/30 and 3840×2160/24.
Full source detail and field of view must remain. The user briefly allowed lower
reconstruction resolution, then explicitly rejected it. **Do not resume the
960×540 or 480×270 experiments.** Those failed and are quality-unaccepted.

Budget: 33.333 ms per delivered frame at 30 fps; 41.667 ms at 24 fps. Shader
benchmarks, container rate and manipulated PTS are not delivered cadence. No
frame drops/duplicates, detail/FOV reduction or timestamp tricks can hide failure.
Targets remain unmet. Do not suggest that packing or tiling alone establishes FPS.

Phone testing is authorized **headlessly**, including real camera/audio recordings.
The user chose diagnostic testing under uncontrolled competing workload. Do not
stop other apps or change settings. Controlled long thermal/cadence acceptance
has not happened. Do not claim a soak or qualified mobile FPS result.

Local edits/builds/reversible cleanup are authorized. No remote history rewriting,
release publication/deletion, issue comments or external messaging. No commits
were made by this implementation work. Still-photo behavior stays unchanged.

## Workspace and preservation

- `/Users/monikamalinowska/AndroidStudioProjects/RawLens` (actual cwd resolves to
  lowercase `rawlens` on this filesystem).
- HEAD `28977494032f743742eafbbf77ff437317544554`, branch `refactor/2.0`.
- Many pre-existing/unrelated changes, including concurrent MHC highlights,
  native grading/resources, camera, metering and wavelet work. Do not reset,
  checkout, clean or overwrite them. No `AGENTS.md` was found.
- Snapshot of path status: `handoff-working-tree-2026-11-02.txt`.
- Current task-source hashes: `handoff-state-2026-11-02.json`. Current source is
  newer than tested APKs; preserve each report's APK/source identity.
- Build isolation: `/tmp/rawlens-rcd-full-source-snapshot`. Shared main-tree builds
  collided with other work (missing Kotlin output classes and Lint/FIR crashes).
  The isolated source tree is task-owned and has expensive native caches. It is
  **not necessarily up to date with current main sources**. Its latest APK tested
  the initial image-output import, before the queried-allocation-usage fix and
  later shape/early-VH work. Do not mislabel that APK as current.
- No own active recording/test/benchmark/build process was found when stopping.
  Gradle daemons may remain idle; do not kill unrelated daemons/builds.

## Provenance and excluded material

Read these first, preserving the user contract:

1. `docs/refactor/independent-packed-photo-rcd-plan.md` including its research refinement.
2. `docs/refactor/rcd-replacement/research-2026-11-01.md`.
3. `docs/refactor/rcd-public-reference-replacement-plan.md`.
4. `docs/refactor/issue1-removal.md`: original disputed closure/history cleanup is
   already completed technical work. Do not repeat it or reopen private archives.
5. This directory's `provenance.md`, `requirements-derived-design.md`,
   `prior-recording-session-review.md`, `full-source-optimization.md` and
   `tile-local-redesign.md` (some entries are chronological and superseded; this
   handoff/current hashes describe the latest stop).

Primary oracle: pinned RawTherapee `b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c`,
RCD SHA256 `646262a75c72ff8bad8ef199bf5edb48b8d1a230b196914cc07c59be8547b083`.
Authors Luis Sanz Rodriguez / Ingo Weyrich, canonical border Gabor Horvath,
GPL-3.0-or-later; upstream source/license under `tools/photo-rcd/upstream/`.
The audited still-photo candidate is compatibility evidence, not the primary oracle.

Do not read/use disputed shader bodies, private archives, disassembly, median/
ratio-grid designs, old packed expected pixels or derived source as guidance or
oracles. Paths/symbols may be inventoried for closure removal. No old backend
translation/renaming. This is an attributed public-source port / documented
independent replacement, not a strict clean-room claim, ownership determination
or legal clearance. Historical distribution questions are separate.

The user-supplied historical conversation was reviewed only for application-level
performance/ownership lessons, not shader bodies. It showed our async delivery,
encoder-owned P010, copy removal and fence fixes improving recording after a
~33 ms shader still yielded ~16 fps. Those application benefits are relevant,
but old results do not qualify the new algorithm. See the review report instead
of reading Activity/source bodies in the large conversation file.

## Completed prior infrastructure

- Independent CPU oracle and approved race-free FP32 GPU baseline, all CFA phases,
  canonical 194/176 tile behavior, border 9, retained VH scratch compatibility,
  exact 18-pixel tail skip. Do not simplify those silently.
- Fixed pre-half FP32 budgets: max 2e-5, mean 2e-6. Half-boundary adapter budget
  .001 is separate; it does not weaken FP32 correctness.
- RAW16 Bayer-cell packing/input and crop/stride/normalization models, licensed
  CC0 Ricoh crops reencoded for adapter tests, host/phone validation reports.
- Internal `PhotoRcdRenderer` + recorder route, separate grade/LUT/scale/P010,
  explicit MHC fallback before grade. Defaults remain hidden/unqualified.
- `PhotoRcdCompletion` retains RGB slots through duplicated grade completion fds.
- Independent encoder-owned P010 route (`--gpu-writer`), with a startup synthetic
  spatial content probe against Android's actual plane strides. No synthetic
  probe frame is queued. It removed CPU P010 copies on this MTK phone.
- Writer worker completion/lifetime correction: longer bounded wait; if GPU
  completion is unproved, retain Image/AHB/RAW lease/fd rather than close/reuse.
  Longer shutdown join applies only to that internal writer route. No PTS changes.
- P010 separate grade now processes complete 2×2 output cells: writes four
  individual luma samples and averages all four for existing 4:2:0 chroma.
  **This is not Bayer binning.** Nonuniform-row tests covered 32 combinations
  of profile, scaling, row phase and fullRGB/superpixel, max code error zero.
  MHC work belongs to the other edits; do not replace its shaders with ours.

Relevant files: `app/src/main/cpp/photo_rcd/`, `PhotoRcdRenderer.kt`,
`PhotoRcdWriterProbe.kt`, `PhotoRcdCompletion.kt`, `EncodedVideoRecorder.kt`,
`PhotoRcdCorrectnessDeviceTest.kt`, `PhotoRcdRecordingDeviceTest.kt`,
`tools/photo-rcd/` scripts, and separate grade source/native dispatch updates.

## Latest architectural implementation

`tools/photo-rcd/tile_local.py` generates from the approved public baseline.
Default workgroup owns 16×16 full-resolution pixels inside a canonical tile.
Shared FP32 arrays: raw 38×38, VH 28×28, LP 30×30, PQ 24×24, green 26×26,
missing color 22×22 = 19,456 bytes. Barriers precede per-pixel bounds returns;
canonical borders, tails and retained VH behavior are preserved.

Recording-mode passes: RAW normalization; tile-local interior + unchanged product
corrections; canonical border + product corrections. Five global reconstruction
planes and the full RGBA32F frame are replaced by bounded dummy descriptors.
At 4K this removes ~150.7 MB of plane payload +132.7 MB RGBA32F, but **memory
savings did not produce a speedup**. FP32 readback mode still allocates RGBA32F
and skips product correction for numerical comparisons.

Host fused-product gate: 71 frames with zero FP32 and half differences, including
changing WB and nonuniform 2×3 LSC; old baseline regression 267 exact frames.
Phone tile-local gate: all 6 tests passed; 36 RAW FP32 comparisons max
4.7683716e-7; final-half max .00048828125. See `android-tile-local/`.

**First recording regressed badly:** full-source 1080p diagnostic median render
~1364.5 ms, 22 RCD frames, zero MHC/zero P010 CPU copy, target failed. See
`recordings-tile-local-baseline/`. Earlier separate/shared route was ~444–456 ms,
~2.2 delivered fps, already ~10.7× over the 24 fps budget. Do not promote this
new tile schedule on correctness/memory savings alone.

Isolated original full-source GPU shape sweep (`tile-local-shape-diagnostic.json`):
8×8 ~2310 ms GPU; 16×8 ~1856; 8×16 ~1871; 16×16 ~1508; 32×8 ~1967 warm
reconstruction medians. Synthetic native benchmark, no encoder/FPS acceptance.
Host shape 16×8 also passed 71 exact comparisons. Engine now accepts a native
`tileShape` array; Android app currently uses default 16×16. All shapes must be
paired with matching generated shader dimensions and dispatch geometry.

`validate_tile_local_layout.py` passed 96 coverage/ownership cases. It models
index/halo ownership, not shader numerical correctness or hardware behavior.

### Critical unresolved large-frame failure

`app_normalized_reconstruction_source()` now includes an early-zero bound in
`upstreamVh`: canonical VH coordinates >=190 are never written by any 194 tile,
so retained calloc scratch must be zero. This passed 71 host cases exactly.
It was hypothesized to avoid expensive backward history scans; it did **not**
establish a speedup.

The first large-frame bounded-VH 8×8 phone sweep produced three ~2.1 s GPU
samples and then `GPU product output outside finite FP16 domain`. See
`handoff-bounded-vh-failure.log` and `tile-local-bounded-vh-shape-diagnostic.json`.
This failure is unresolved. Do not equate small host/phone fixture passes with
large-frame numerical safety, or diagnose compiler spills without evidence.
Other shapes were not completed in that failed sweep. The benchmark script was
subsequently changed to retain per-shape failures and continue independent
shapes; that updated sweep has **not been rerun**.

Suggested first debugging: deterministic large-frame FP32/readback checks,
instrument shared/global index bounds and product error bits, check repeated
frame reuse and exact generated dispatch dimensions. Preserve the failing
source/binary hashes. Decide whether to reject the tile schedule or split guide
calculation/reconstruction if it cannot win. Do not silently loosen tolerances.

## GPU RGB handoff extension: current state

`tile_local.image_source()` swaps the half SSBO for an RGBA16F storage image,
keeping explicit packHalf/unpackHalf before imageStore. Android engine imports
queried dedicated AHB memory, uses FOREIGN ownership acquire/release barriers,
and caches output imports. Descriptor/command changes across slots do not
reallocate RAW/scratch buffers. JNI has an image-output branch with no CPU RGB
readback/upload; product status metadata is still read. RAW is still byte-staged.

It is **synchronous**: native fence completion is awaited before the existing
separate grade context submits its read. No asynchronous overlap claim. Async
still needs explicit external completion dependencies plus per-slot in-flight
resources; simply returning early would race shared buffers.

Initial APK image gate failed 3/6 tests: generic HardwareBuffer usage 0x333 did
not include GPU storage usage requested by Vulkan (0x200000001000100, including
a vendor flag). The check was not bypassed. See `android-tile-image/` and
`handoff-image-initial-probe.log`.

**Latest source fixes allocation:** Engine `outputUsage()` queries Vulkan usage;
JNI `outputUsageNative` exposes it; Kotlin ORs those bits into HardwareBuffer
allocation for GPU-image mode. A standalone 40×40 native probe using the queried
usage succeeded: `pixel_differences=0 unlock=0 stride=48
usage=144115188092633907` (all expected .25/.25/.25/1 half samples). See
`handoff-image-queried-usage-probe.log`, `probe_tile_image.cc`.

That small probe is **not** full nonuniform/LUT/ownership/recording validation.
The current queried-usage allocation fix and subsequent native shape/VH source
changes are not built into the tested APK. Rebuild the isolated tree coherently,
run the image correctness gate, then large-frame validation before new recording.

When image mode is selected, adapter JSON explicitly marks pre-product FP32
comparison as unperformed (`fp32_compared=false`); this mode tests final half
image output against the established adapter. The buffer-output tile route
separately tests pre-product FP32. Do not report the skipped comparison as zero.

## Useful commands after user-authorized resume

ADB: `/Users/monikamalinowska/Library/Android/sdk/platform-tools/adb`
Phone when connected: Xiaomi 25080RABDG / Mali-G615 MC2 / Android16 SDK36,
serial `fe79feha9lmb6hhi`. Last transport ID 6; discover rather than hardcode it.
NDK27 compiler:
`/Users/monikamalinowska/Library/Android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang++`

Host gates:

```sh
python3 tools/photo-rcd/validate_tile_local_layout.py --report /tmp/layout.json
python3 tools/photo-rcd/validate_normalized_engine.py --tile-local --core 16x16 \
  --work /tmp/tile-check --report /tmp/tile-check.json
python3 tools/photo-rcd/engine_validate.py --work /tmp/legacy-check --report /tmp/legacy-check.json
```

Before building, update the task-owned isolated tree with a **coherent** set of
current app sources/assets, tools, Gradle/CMake inputs; preserve main-tree edits.
Do not sync `build`, `.cxx`, `.git`, archives or prior disputed content. Missing
new MHC shader dependencies caused one snapshot build failure; source coherence
matters. Use `-PfreezeVersion` to avoid modifying unrelated version metadata.

```sh
./gradlew -p /tmp/rawlens-rcd-full-source-snapshot \
  :app:assembleGithubDeviceTest :app:assembleGithubDeviceTestAndroidTest \
  -PphotoRcdHeadless -PfreezeVersion --max-workers=2
```

Run scripts from the **snapshot** to select its APKs, with reports in the main
workspace. The isolated application ID `.rcdcheck` preserves the normal app.

```sh
python3 /tmp/rawlens-rcd-full-source-snapshot/tools/photo-rcd/run_android_correctness.py \
  --serial fe79feha9lmb6hhi --requirements-input --normalize-once --tile-local \
  --out /absolute/path/to/new-report-directory
# Add --gpu-image only for the separately rebuilt image-output gate.
```

Do not start these recordings until the unresolved large-frame error is addressed:

```sh
python3 /tmp/rawlens-rcd-full-source-snapshot/tools/photo-rcd/run_android_recordings.py \
  --serial fe79feha9lmb6hhi --requirements-input --normalize-once --tile-local \
  --gpu-image --gpu-writer --seconds 30 --label UNIQUE \
  --out /absolute/path/to/new-report-directory
python3 tools/photo-rcd/analyze_recordings.py /absolute/path/to/new-report-directory
```

`--no-install` requires the installed APK hash to match. Use a unique report
folder/label; never overwrite historical failure evidence. Full file decode,
source timestamp identity/cadence and no fallback are necessary, not just GPU
queries. `inspect_packages.py` regenerates expected source/hash inventory, validates
SPIR-V, licenses and identifiers; final fresh release inspection is unfinished
for the current source. Do not reuse an old package pass.

## Public interoperability references consulted

- Vulkan AHB memory/usage rules:
  https://docs.vulkan.org/spec/latest/chapters/memory.html
- Query usage before allocation:
  https://docs.vulkan.org/refpages/latest/refpages/source/VkAndroidHardwareBufferUsageANDROID.html
- AHB imports:
  https://docs.vulkan.org/refpages/latest/refpages/source/VkImportAndroidHardwareBufferInfoANDROID.html

The actual queried usage and real pixel probe support the allocation fix; do not
infer storage layout from a successful import or a descriptor alone. No further
benchmark/test/release task is running or claimed complete at this handoff.
