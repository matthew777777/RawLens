# Requirements-derived packed Bayer recording RCD

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Date: 2026-11-01. This is the next design contract, not a claim that this path
is implemented, faster, legally cleared or accepted on a phone. The current
candidate remains a validated comparison and fallback experiment. Its measured
440–454 ms reconstruction and failed video cadence remain evidence in performance.md.

## Requirements

| Requirement | Observable acceptance |
| --- | --- |
| Packed Bayer input | Preserve every valid RAW16 code in a 2×2 cell representation; all sensor CFA phases, crop parity, strides and odd dimensions have explicit addressing. |
| Reduced memory traffic | Remove the CPU-normalized full-frame FP32 input and GPU→CPU→GPU RGB round trip from the intended fast route. Allocate only stage values required by reconstruction. Measure packing cost, actual transfer counts and total frame cost. |
| Vulkan execution | GPU normalization, FP32 reconstruction and final output handoff use supported Vulkan features with explicit memory dependencies and bounded resource ownership. Unsupported external formats use an identified staging fallback. |
| Video output | Preserve grading, WB/LSC/matrix order, profiles/LUT, scaling, P010/Main10, audio and frame identity. No implicit MHC substitution may count as RCD. |
| Quality and source detail | Retain the pinned public RCD equations and existing numerical gates; retain source crop/FOV/detail. Borders, tile compatibility and HDR extensions remain explicit. |
| Throughput | Stable 1920×1080/30, 2560×1440/30 and 3840×2160/24 under the research contract. Nominal whole-frame periods are 33.3/33.3/41.7 ms; prefer its headroom. Delivered unique-frame cadence, not shader time or retimed PTS, decides acceptance. |

Smaller encoded output does not authorize smaller source CFA or less RCD work.
Phone workload currently remains uncontrolled by the user's choice; diagnostic
runs cannot establish controlled acceptance. No Android test is added by this
specification task.

## Sources and derivation boundary

Mathematics comes from RawTherapee
`b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c`, as recorded in provenance.md and
independently exercised by the scalar oracle. Existing still-photo and 200 ms
candidate results are audited compatibility/performance comparisons, not layout
specifications or correctness oracles. This design derives storage from the
values required by those public equations and derives scheduling from their
read/write dependencies. It uses no disputed shader bodies, private archive,
old packed expected pixels, disassembly, median or ratio-grid design. Prior
exposure is acknowledged; this is an attributed public-source port with a
requirements-derived layout, not a strict clean-room claim.

The executable specification is `tools/photo-rcd/requirements_layout.py`.
Its report is requirements-layout-validation.json. It models input/guide
addressing and ownership only, not interpolation accuracy or GPU speed.

## Packing and input adapter

For a crop of W×H sensor samples, let Cx=ceil(W/2), Cy=ceil(H/2), C=Cx×Cy.
Cell (cx,cy) represents geometric crop coordinates (2cx,2cy) through
(2cx+1,2cy+1); lanes q=0/1/2/3 mean top-left/top-right/bottom-left/bottom-right.
Do not reorder lanes into colors: sensor phase is separate metadata.

Each cell occupies two uint32 words (8 bytes):

```
word[2*cell+0] = code(q0) | code(q1)<<16
word[2*cell+1] = code(q2) | code(q3)<<16
cell = (y/2)*Cx + x/2
q = 2*(y&1)+(x&1)
code(x,y) = (word[2*cell+q/2] >> (16*(q&1))) & 65535
```

This uses integer bit packing, not FP16 storage/arithmetic, and requires no
16-bit storage feature. Pack the actual crop once; do not unpack it to a
full-frame normalized FP32 SSBO by default. Compare optional GPU normalization
into FP32 cells only if measured repeated-normalization cost justifies its
additional allocation and pass.

A RAW sample resides at `(top+y)*rowStride + (left+x)*pixelStride`, measured
in bytes. Validate the complete accessible range, crop bounds, row capacity,
alignment and overflow before GPU dispatch. The common even-stride RAW16 path
can extract aligned uint32 words. Arbitrary byte/pixel strides use a separately
validated adapter; they cannot assume aligned 16-bit samples, safe final-word
padding, byte-addressable storage, or permission to read outside the plane.
A staging allocation may explicitly pad its own final word to four bytes.

Odd crop dimensions leave invalid lanes. Initialize them to zero for deterministic
storage, but never treat them as captured pixels. All reconstruction accesses
first apply the declared scalar border/domain rule, then locate the valid packed
sample. No crop/FOV adjustment or invented edge samples is permitted. Packing
can represent tiny images; canonical RCD still rejects dimensions below 19 until
an application extension is specified and tested.

Normalization is FP32 `clamp((float(code)-black[sensorQuad]) /
(white-black[sensorQuad]),0,1)` with the existing operation order.
`sensorQuad=2*((top+y)&1)+((left+x)&1)` selects black level and CFA color.
Validate finite black/white metadata and white>black. Keep normalization apart
from WB, lens shading, matrix/exposure and transfer/profile/LUT corrections.
Above-white/signed HDR behavior is a later explicit extension, not silent clipping
of a previously supported domain.

RAW AHardwareBuffer import is a capability path, not an assumption. Query the
actual buffer's format, usage, import/memory and access support. Reject unsupported
interpretations; never infer a RAW integer storage buffer from an opaque external
format or use color conversion as an exact sensor-code reader. A fallback copies
RAW codes into a supported staging buffer and then packs them on GPU, or performs
bounded CPU integer packing if GPU access is unavailable. Record its copy/packing
cost separately. Do not claim that staging fallback is zero-copy or target-qualified.

## Intermediates derived from the equations

Use distinct FP32 scalar storage until each stage's last reader has finished.
For each cell, one R/B site exists in each row for all four CFA phases.

| Resource | Values per cell | Payload |
| --- | --- | ---: |
| Packed RAW16 | 4 native codes | 8C bytes |
| Guides | VH at 4 sites; LP and PQ at the 2 R/B sites | 32C bytes |
| Green | green at every full-resolution site | 16C bytes |
| Missing opposite color | missing B at R; missing R at B | 8C bytes |
| Separate grade input | RGBA16F at W×H, final validated storage boundary | 8WH bytes |

The opposite-color buffer holds only interpolated values. Known R/B values are
read from packed RAW, rather than duplicated there. No RGB value at green sites
is stored in this buffer. A requested color at an R/B site is its normalized
native code if known, otherwise its stored missing color. This follows directly
from the public color-difference reconstruction.

One possible scalar guide layout is eight floats per cell: VH q0..q3, LP for
row0/row1, PQ for row0/row1. Green uses four floats per cell; missing opposite
uses two floats selected by row. Writers own distinct scalar addresses. A
whole-vector read/modify/write must not overwrite another invocation's lanes.
Padding domains and pinned tile domains still determine which values are defined.

For even dimensions N=WH, input+guides+green+missing values occupy 16N bytes;
with a separate RGBA16F output, payload is 24N bytes. At 3840×2160 this is
199,065,600 bytes (about 189.8 MiB), before alignment, codec buffers, imports,
metadata, optional tiled duplication and queue depth. This is allocated payload,
not a measured memory-traffic number or a total-app memory promise. Multiple
output slots do not require duplicating all scratch when GPU execution is serial.

Avoid a global RGBA32F output plus CPU readback plus CPU half upload in the intended
fast route. The final kernel writes a supported GPU-owned grade input and retains
FP32 arithmetic until the already validated final storage boundary. Keep the
separate grade/P010 route as the initial product reference. Optional FP16 stage
storage/arithmetic and P010 fusion need later numerical gates.

### Lifetimes and optional aliasing

| Value | Last reader |
| --- | --- |
| Packed native codes | final reconstruction |
| VH | final green-site color reconstruction |
| LP | green reconstruction |
| PQ | opposite-color reconstruction |
| Green and missing color | final reconstruction |
| Grade input | grading completion fence |
| P010 output | encoder completion/ownership return |

LP and missing-color payloads are both two FP32 scalars per cell and have
non-overlapping lifetimes. After validating separate buffers, optionally reuse
LP slots for missing colors after the green→opposite dependency. This saves 8C
bytes; it does not change the initial 24N-byte separate-buffer sizing above.
Use field-specific guide loads: an opposite-stage helper must not read a whole
vector containing LP lanes while another invocation overwrites those lanes.
Declare write-after-read dependencies and prove all scalar ownership before
aliasing. No same-dispatch read/write overlap is accepted as value-benign.

## Interpolation and dependency graph

Packing changes addressing and dispatch ownership; it changes no reconstruction
formula. Preserve eps=1e-5, epssq=1e-10, operation grouping and declared contraction
policy. Source steps remain traceable to the pinned source:

1. Direction/LP/PQ pass: squared directional high-pass statistics and public
   low-pass; preserve the pinned odd-column diagonal-response lookup.
2. Green pass: public refined VH, cardinal gradients, LP ratios and weighted
   estimates. Initialize native green directly from packed CFA.
3. Opposite-color pass: public PQ refinement and diagonal color differences;
   store only the missing R/B value, reading immutable green and known CFA.
4. Green-site color pass: reconstruct R and B from public full-resolution
   cardinal color differences. Combine native samples and missing colors at
   R/B sites. Write each final output site exactly once.
5. Canonical border output remains the pinned nine-pixel border interpolation.
   Product corrections and grading follow the existing tested order.

Guide→green→opposite→final is a real dependency chain. No in-place RGB updates
or value-benign race arguments are needed. A response cache is an optional
experiment because its extra pass/traffic has not demonstrated a reliable gain.
Do not impose it on the design just because an earlier implementation has one.

Two numerical modes must not be conflated: exact pinned-implementation compatibility
(including 194/176 tile domains, skipped 18-pixel tails and retained VH scratch),
and a possible future mathematical full-frame variant. The first is the baseline.
Any second mode needs separate interior/border/tile evidence and a reviewed
contract change. Packing never silently removes those conventions.

## Tiling derived from reads

Start with separate passes and compact global intermediates. Then compare a
reconstruction tile that keeps green/missing values local while reading immutable
guides/CFA. Shared memory and direct reads are alternatives to measure, especially
on Mali; neither is presumed faster. Full-frame, upstream-compatible tiled and
reconstruction-tiled costs must be reported separately.

For written-domain interior behavior, the cardinal guide needs CFA offsets up
to 4. Diagonal statistics plus the pinned odd-column shift need up to 5 in X
and 4 in Y. Green's refined guide adds a neighbor offset, giving a conservative
CFA radius 5. Opposite reconstruction reads green up to 2 away, giving radius 7.
Final green-site reconstruction reads opposite colors up to 3 away, giving a
conservative radius 10. These are bounds, not a proof of minimal load footprints.

A fused reconstruction core therefore needs green halo 5 and opposite halo 3;
its combined CFA footprint uses conservative halo 10. For an 8×8 core, a simple
upper allocation is 28×28 raw floats, 18×18 green floats and 14×14/2 missing-color
floats: about 4.8 KiB before other state/alignment. Derive exact site counts and
register pressure for each candidate rather than importing workgroup dimensions.
Test 8×8 and 16×8 cores initially within queried device limits.

Pinned retained-VH scratch is an exception to a purely local halo: its deterministic
compatibility adapter may read CFA at an earlier tile origin. Keep an explicit
immutable-input fallback for those reads; do not claim halo 10 covers them.
Only owned final core sites write output. Halo lanes participate in uniform
workgroup barriers before any per-site early exit. Cross-workgroup dependencies
use separate dispatches and Vulkan memory dependencies.

## Scheduling and video-buffer ownership

A bounded frame record owns RAW/import, metadata, packed input, output and frame
identity. Honor the producer acquire fence before any GPU input access; use a
supported semaphore import or an explicit validated wait path. Metadata stays immutable until its last GPU reader completes.

```
camera acquire → import/stage + integer pack → guides → green → opposite → final RGB
                                                                    ↓
                                     separate correction/grade/LUT/scale → P010 → codec
```

At each producer→consumer edge, scope compute writes to the actual subsequent
shader reads/writes; a command submission order alone is insufficient. Use legal
image layouts and required external queue-family ownership transfers. Release
RAW after packing completion only if later passes read an owned packed copy;
otherwise retain it through its last raw reader. Recycle packed input/scratch
only after last GPU use. Keep RGB alive through the grading completion fence and
P010 alive through the encoder handoff/completion contract. Retain an original
completion fence even if duplication fails. Cancellation/timeout never authorizes
premature reuse or destruction.

Reuse recorded commands, descriptors and allocations when compatible, updating
only a free frame's data. Begin with two bounded frame records and measure any
third; do not allow unbounded buffering to mask lag. Scratch can be shared only
with explicit non-overlapping use. Use asynchronous completion for steady-state
work; keep a synchronous diagnostic path. Never assume multiple queues overlap
on the hardware. Backpressure and starvation are visible failures for acceptance,
not reasons to duplicate frames, retime PTS or silently switch to MHC.

## Evidence gates and implementation order

1. Executable integer packing/address/ownership model; all phases, odd origins,
   odd dimensions, padding, nontrivial row/pixel strides and invalid capacities.
2. GPU integer pack/normalize against that model and the existing normalized
   scalar input contract; licensed real RAW fixtures remain required.
3. Compact missing-color and guide buffers against stage captures and the pinned
   oracle. Preserve original numerical budgets before performance changes.
4. Separate-pass Vulkan execution and supported Android import/staging/output
   capabilities. Test release fences, reuse, stop/failure and unsupported formats.
5. Tile/fuse reconstruction only after the separate path passes; require exact
   same-device optimization comparisons under equivalent arithmetic.
6. Grade/LUT/profile/scaling/P010/Main10/audio integration and complete decoding;
   validate byte strides, luma/chroma placement, 10-bit packing, color/range metadata
   and A/V alignment against the existing separate product baseline. Test changing
   per-frame parameters, not only static fixtures.
7. Compare packing/import, each GPU pass, copies/waits, memory, camera cadence,
   encoder delivery and real wall time. Use controlled named-device/warm recordings
   for the mandatory research acceptance; diagnostic timing is insufficient.

The first model report passes 84 crop/stride cases across four CFA phases, ownership
and padding properties, plus seven invalid inputs. It establishes no interpolation,
Android or performance pass. These new requirements do not make existing failed
fps measurements pass or resolve the historical distribution dispute.

Public Vulkan contracts consulted:

- [Android hardware-buffer interoperability](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html)
- [Synchronization and cache control](https://docs.vulkan.org/spec/latest/chapters/synchronization.html)

## GPU requirements prototype — implemented

See [gpu-input-implementation.md](gpu-input-implementation.md). Added GPU RAW16 cell packing, on-GPU normalization from cells and compact LP/PQ/missing-color planes around the pinned public baseline. The GPU producer feeds RCD through an explicit dependency without CPU intermediate normalization/readback. Host input tests passed 84 cases; host complete-chain tests passed 67; standalone headless Xiaomi tests passed 35 (max RGB error 4.172325134277344e-7). A pack-only 4K-source diagnostic measured 3.725845 ms warm median, excluding staging, reconstruction and recording. The source crop was preserved. Native recorder/camera-buffer and video-output integration remain unfinished; no new FPS, APK or acceptance result is claimed.

## User-authorized approximate mode — 2026-11-01

The user explicitly permits reducing source geometry and changing reconstruction
equations to pursue the recording targets while retaining RCD principles. This
supersedes the earlier full-source-detail/equation-equivalence restriction for a
**separate approximate mode**, not for the pinned reference baseline or unchanged
still-photo route. Field of view remains preserved by default. The first
experiment averages each CFA color independently over rectangular Bayer-cell
blocks before running the current RCD equations. No cross-color RAW averaging
is allowed. Integer averaging uses `(sum+count/2)/count`; partial edge blocks
include only valid source samples. Output dimensions round up to whole Bayer
cells. Full crop coverage and fine-detail loss must be reported. Grading scales
the reconstructed RGB to the requested video size; LSC coordinates refer to the
source block center. Scales are explicit diagnostic choices, not dynamic
quality reductions hidden during a recording.

The old FP32 oracle tolerances continue to apply to the reference mode. Comparing
reduced reconstruction to a separately area-reduced input tests implementation
correctness only; it does not prove quality equivalent to full-source RCD.
Changes to interpolation equations remain future experiments, with separate
quality evidence required. Mandatory delivered 1080p30, 1440p30 and 4K24, source
identity auditing and honest PTS remain required; none is established here.

## Full-source requirement restored — 2026-11-01

The user rejected lower-resolution reconstruction as the optimization direction.
Stop pursuing the 960×540/480×270 experiments; keep their reports as explicitly
failed/quality-unaccepted diagnostics. Current optimization must preserve the
full-source crop detail and field of view. The exact baseline equations and
original FP32 tolerance remain the active numerical contract. Caching decoded
normalized RAW or directional responses is allowed as measured representation/
scheduling work, not an implicit resolution or equation change.
