# Numerical and interface contract — initial baseline

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

2026-11-01. Primary source: pinned RawTherapee in provenance.md. This contract
precedes packing, precision reduction and fusion. Those optimizations are gated.

## Canonical mathematical domain

Input is finite normalized camera CFA in [0,1], RGB Bayer only. R=0, G=1, B=2.
Sensor pattern strings RGGB/GRBG/GBRG/BGGR refer to sensor (0,0), never crop (0,0).
Crop site (x,y) uses pattern[((y+top)&1)*2+((x+left)&1)]. Stride is in samples,
includes row padding, and must be at least sensor width. Crop must lie entirely
within the sensor rectangle. The host adapter bounds dimensions to 100000 and
input to 64M samples, checks exact byte count and rejects nonfinite values.

Oracle CLI accepts little-endian IEEE FP32 sensor samples, not Android RAW16.
It multiplies by 65536 before upstream entry and divides RGB by 65536 on exit;
both powers of two are exact in this domain. Upstream clips CFA to [0,1] internally.
Output is interleaved normalized camera-linear RGB, final negative values clamped
to zero, no upper RGB clamp. No WB, lens shading, matrix, grading, transfer, scaling
or LUT is included. `intp(a,b,c) = a*(b-c)+c` and grouping remain upstream's.

Canonical host minimum size is 19×19: a nonempty interior beyond the nine-pixel
border. Tiny inputs are predictably rejected, not sent through an unsafe upstream
border routine. This is an explicit baseline limit, not the final app tiny-image
policy. Supporting smaller images is a separately tested application extension.

Canonical border is the upstream nine-pixel `border_interpolate`: clipped 3×3
neighborhood means per missing CFA channel and native sample preservation. It
uses rawData (not intermediate RGB). It is **not** reflected RCD. Host inputs
are pre-normalized so its rawData scale is consistent with the interior.

HDR/signed suite: pending. Above-white, negative and nonfinite canonical input is
rejected. Photo float-CFA above-white handling and mirrored borders remain in the
existing product; no claim of canonical equivalence is made for those extensions.

## Stages and traceability

Source locations below refer to named step comments in pinned `rcd_demosaic.cc`.
The extractor leaves all equations and loop domains unchanged. Generated
`adaptation.patch` records include substitution and snapshot hooks.

| Public source step | Output / arithmetic | Host implementation and evidence |
| --- | --- | --- |
| CFA conversion | LIM01(rawData/65536) | Unmodified upstream tile load; fixture sample preservation |
| 1.1–1.2 | Sum of squared seven-tap directional responses, floor epssq=1e-10, V/(V+H) | Unmodified upstream; `vh.bin` snapshot |
| 2 | CFA + 0.5 cardinal sum + 0.25 diagonal sum, unnormalized LP | Unmodified upstream; `lpf.bin` at R/B sites |
| 3 | eps=1e-5 cardinal gradients, LP ratios, weighted estimates, direction refinement | Unmodified upstream; `green.bin` |
| 4.0–4.1 | Diagonal response squares / P/(P+Q) | Unmodified upstream; `pq.bin` at R/B sites, captured after LP alias lifetime ends |
| 4.2 | Opposite R/B via local color differences | Unmodified upstream; `opposite_r.bin` only R/B sites |
| 4.3 | R/B at green sites via full-resolution cardinal color differences | Unmodified upstream; `final_r.bin` plus complete RGB output |
| Output / border | max(0, RGB*65536), separate border call | Extracted original border body; full-image checks include all edges/corners |

Snapshot order: upstream tile traversal (tr,tc), then owned interior row/column;
omit G sites for LP/PQ/opposite-R snapshots. FP32 native little-endian samples.
Only initialized owned interior values are captured. They are not a complete
full-frame stage image and should not be indexed as one. Tile size 194, step 176,
border 9 belong to upstream oracle scheduling, not a prescribed GPU layout.

### Pinned implementation conventions discovered by stage comparison

The oracle is the actual pinned scalar implementation, OpenMP disabled, tile
order (tr,tc), chunkSize=1. It is not an idealized transcription of RCD prose.
Step 4.0 writes high-pass responses only at odd local columns; `index/2` accesses
map each horizontal pair to that response. Preserve this convention in the
baseline. A direct per-site full-resolution response translation failed it.

The scalar `VH_Dir` allocation is zeroed once, not once per tile. Its written
domain is local [4,tileExtent-4). Green is initialized to CFA before interpolation
in [4,tileExtent-4). Short tail tiles can therefore read retained VH entries from
an earlier tile. These are initialized, deterministic scalar values under the
frozen traversal, but cannot be silently replaced with newly evaluated full-frame
guides. Boundary-dependent differences can propagate into the nine-pixel owned
interior through later gradient reads. The new baseline preserves them by
recomputing the most recent written response from immutable CFA, not by racing
neighboring workgroups or carrying mutable cross-frame state.

An exactly 18-pixel tile extent is skipped before updating any scratch in the
upstream function. Retained-guide recomputation must exclude such skipped tiles.
The expanded 194×195 cases specifically exercise this convention.

This is a pinned implementation compatibility convention, not a general RCD
mathematical requirement. Do not assert parity with arbitrary RawTherapee OpenMP
thread scheduling. A future artifact-free/full-frame variant must explicitly
separate stable algorithm interior, tile-boundary effects and application borders,
and justify the numerical change rather than relaxing a test threshold.

## Precision and acceptance before optimization

Host arithmetic/storage FP32; no OpenMP, fast-math or contraction. Build with
`-ffp-contract=off -fno-fast-math`. Preserve epssq: it underflows in binary16.
Do not substitute mix/FMA/reordered sums as if universally bit identical.
No FP16 arithmetic, FP16 stage storage or fused P010 in this baseline.
Host MoltenVK validation explicitly sets `MVK_CONFIG_FAST_MATH_ENABLED=0`; this
driver setting is part of the recorded host arithmetic environment.

- Host extraction self-check: exact bytes across O0/O2/sanitized output and stage
  captures for the frozen fixture corpus. This detects compiler/harness drift;
  it is not independent proof of the upstream algorithm itself.
- Sample preservation: exactly zero error for native CFA channel, including
  borders, on canonical fixtures. FP32 normalized powers-of-two scale is exact.
- Flat-field sanity: interior max absolute error <=1e-5. This allows upstream's
  explicit eps bias: for equal LP L the ratio is 2L/(eps+2L), not exactly one.
  This invariant is not a general CPU/GPU tolerance.
- CPU/GPU FP32 proposed initial diagnostic budgets: final normalized RGB max
  absolute 2e-5, mean absolute 2e-6; VH/PQ max absolute 2e-5. Record relative error
  and ULP separately, excluding near-zero values from relative summaries. These
  are conservative investigation thresholds, **not yet a validated acceptance
  tolerance**. High-pass cancellation and discriminator branch ties can amplify
  roundoff; investigate every breach before defining a reviewed GPU gate.
- Optimizations on the same GPU/arithmetic path must initially match the
  validated untuned GPU baseline exactly. Any changed rounding/storage requires
  a separately justified contract. Do not loosen thresholds to pass a failure.

## Future application adapter and resources

RAW16 adapter must decode unsigned little-endian codes using row stride bytes,
pixel stride and crop/active sensor bounds explicitly; never assume tight rows.
Per-site black levels are indexed in sensor CFA phase. Require finite white >
each black, normalize (code-black)/(white-black). Canonical clamp occurs before
interpolation. Lens shading, WB, highlight reconstruction and above-white policy
must each have explicit pre/post interpolation tests before product integration.
Do not copy the photo helper's max(1,white-black) as invalid-metadata policy.

GPU baseline representation: full-resolution scalar CFA and final RGB, with
separate per-tile FP32 scalar VH, LP, PQ and G plus two-channel opposite-color
storage. The tile atlas preserves the pinned implementation conventions above;
it is not packed CFA or shared-memory reconstruction optimization. FP32 SSBOs
avoid reliance on optional storage image formats. No pass reads a neighboring
texel from its own output. Use one command buffer, compute-write → compute-read
barriers between dependent passes, host-read barrier before completion/readback.
Four atlas passes plus a separate canonical border pass are in one command buffer.
No per-stage CPU waits. Reject overflow/unsupported allocations before dispatch.
One frame owns all buffers/descriptors until its completion fence signals;
timeout/cancellation cannot release/reuse unfinished RAW storage. Native Android
queue/fence/encoder integration remains a later independently audited gate.

Packing/layout is undecided. Derive tile halos from the complete dependency DAG,
test tiling against this baseline, measure scalar/paired/2×2-cell alternatives
including input packing time. Four scalar samples in RGBA at quarter area do not
reduce sample bytes. FP16 and output fusion remain later numerical gates.

## Packing/scheduling experiment and internal app route

Generators in tools/photo-rcd/build_variants.py derive only from baseline.comp.
Packed input cell=(x/2,y/2), lane=(y&1)*2+(x&1), pitch=ceil(width/2).
All four samples retain FP32; incomplete odd cells have zero unused lanes.
Packing has its own dispatch/barrier and its cost is included in timing.

Shared reconstruction experiment uses 8×8 output cores. Final color gradients
read opposite-color halo 3; opposite reconstruction reads green two sites
further, so green halo is 5. Local green 18×18 floats and opposite 14×14 vec2
consume 2,864 bytes. Workgroup barriers separate those computations; only
owned core sites write final RGB. Guides remain immutable external inputs.
Full-corpus exact equivalence passed; redundant halo work made it slower here.

Direct-address optimization removes reflection only for executed stage domains
whose source/atlas stencils are provably in range, including upstream retained
guide tile selection. Full corpus and product-size equality passed. Per-pass
specialization constant 9 selects independent pipelines; operation order and
FP32 storage stay unchanged. Baseline remains available.

App validation route uses its own Vulkan context and synchronous completion,
then explicit camera-white protection (0.70..0.99), post-reconstruction bilinear
LSC with mean Gr/Gb, final FP16 ties-even storage, separate existing WB/CCM/EV,
profile/LUT/scaling and P010. This is an application extension, not canonical
upstream output. RAW16 adapter validates crop, row/pixel stride, capacity, finite
white/black and per-sensor-site phase. It rejects rather than repairs bad metadata.

RGB output allocations are distinct per recorder slot. An original grade fd
protects the RGB buffer; a duplicate travels to delivery. Reuse requires signal,
not elapsed timeout. Timed-out buffers are retained; no fallback writes that
slot's P010 while an earlier grade is incomplete. Render failures before grade
submission can select visibly counted MHC. No app setting exposes this route
until Android quality/cadence gates pass. Synchronous upload/readback is a
validated integration baseline, not a throughput architecture claim.

Delivery-fd duplication is a post-submit operation: it can fail (for example
EMFILE) after grade starts writing P010. `PhotoRcdCompletion` first adopts the
original fence and keeps it across duplicate/wait failures. The recorder stops
accepting frames if duplication fails; it cannot fall back or reuse a destination
which has no delivery job yet. Host tests exercise timeout, wait exception,
duplicate failure, no double-close and successful subsequent slot reuse.

## Recording candidate source-step traceability

| Public operation | Generated candidate operation | Adapter distinction |
| --- | --- | --- |
| RT step 1 HPF/VH | hpfV/hpfH, vhDir, upstreamVh | Current tile uses shared CFA; unwritten tail scratch follows pinned scalar behavior. |
| RT step 2 LPF | lpf | Only R/B sites inside margin 2 initialize LPF. |
| RT step 3 green | stage 1 | Original photo gradient/ratio expressions; paired discriminator summation follows pin. |
| RT step 4.0 diagonal HPF | hpfP/hpfQ | Corrected odd-column lookup and margin 3 domain. |
| RT step 4.1 PQ | pqDir | Only R/B sites inside margin 4 initialize PQ. |
| RT step 4.2 opposite color | stage 2 | Reads immutable green; writes separate opposite buffer and owned final R/B. |
| RT step 4.3 green-site colors | stage 3 | Reads immutable green/opposite; writes owned final green sites. |
| RT border_interpolate | stage 4 | Canonical border, separate from preserved photo mirror extension. |
| App correction contract | stage 6 | Existing tested white protection/LSC and final half storage; grade/LUT/P010 separate. |

Direct atlas addressing is justified by domains: stage 1 interpolates only margin
4 and reads guides at offsets at most 2; stage 2 interpolates only margin 4 and
reads previous green at offsets at most 2; stage 3 executes only margin 9 and
reads intermediates at offsets at most 3. Others initialize/carry known CFA or
return. Therefore these intermediate indices are inside the 194×194 tile and
need no reflection. Global raw/shared-halo loads retain safe reflection.
Paired stages 2/3 dispatch 13×25 groups per tile with horizontal span 16;
stages 0/1 use 25×25 with span 8. Workgroup-uniform early exits occur before
shared barriers, per-site bounds/ownership exits after shared loading.

## FP32 cell guide layout experiment

Each 194×194 canonical tile contains 97×97 Bayer cells. For local pixel (x,y),
cell offset is `tileId*(194*194*2) + ((y/2)*97+x/2)*8`. Its VH value occupies
`offset+(y&1)*2+(x&1)`. Each cell row has exactly one R/B site for every supported
CFA phase: LP occupies `offset+4+(y&1)`, PQ `offset+6+(y&1)`. Only that R/B
invocation writes LP/PQ. The other site in the row is green and writes only its
own VH scalar. Scalar SSBO stores address disjoint FP32 locations, avoiding
whole-texel read/modify/write races. The next dispatch reads only after the
existing compute memory dependency. Green-site LP/PQ reads are explicitly zero.

Guides decrease from 602176 to 301088 bytes per tile. Green stores one scalar
(150544 bytes); opposite R/B stores two scalars (301088 bytes). Together these
three intermediates decrease from 1806528 to 752720 bytes per tile, without
quantization. CFA and final reconstruction retain full source resolution.
No half-resolution reconstruction or changed equations are introduced.

Paired green reconstruction assigns one R/B site and its native-green mate to
one invocation; odd-width missing R/B mates explicitly initialize the final
green. The existing all-phase, odd-size, edge and licensed-real host corpus
covers these cases. The direct-CFA variant keeps the same packed guide layout
and math but removes shared loads and barriers, reading the immutable CFA through
the same safe sensor-edge adapter. Its performance is device-dependent.

## Requirements-derived design revision

The next layout and execution contract is [requirements-derived-design.md](requirements-derived-design.md). It starts with packed RAW16 input (the current guide-packed experiment still uploads normalized FP32 CFA), compact public-algorithm intermediates, dependency-derived halos and explicit video-buffer lifetimes. The integer layout/ownership model passed 84 cases across four CFA phases and seven invalid inputs; see requirements-layout-validation.json. No Android tests, runtime promotion, changed interpolation tolerance or performance pass is claimed by this revision. HEAD remains 28977494032f743742eafbbf77ff437317544554 on refactor/2.0; unrelated working-tree edits were preserved. Xiaomi 25080RABDG is available; no device tests were needed for this specification step.

## GPU requirements prototype — implemented

See [gpu-input-implementation.md](gpu-input-implementation.md). Added GPU RAW16 cell packing, on-GPU normalization from cells and compact LP/PQ/missing-color planes around the pinned public baseline. The GPU producer feeds RCD through an explicit dependency without CPU intermediate normalization/readback. Host input tests passed 84 cases; host complete-chain tests passed 67; standalone headless Xiaomi tests passed 35 (max RGB error 4.172325134277344e-7). A pack-only 4K-source diagnostic measured 3.725845 ms warm median, excluding staging, reconstruction and recording. The source crop was preserved. Native recorder/camera-buffer and video-output integration remain unfinished; no new FPS, APK or acceptance result is claimed.

## Recording integration and prior-work review — 2026-11-01

The requirements-derived RAW16 GPU path is now connected to the hidden recorder
route. Headless Android correctness passed five tests, including 36 full-source
and 28 independently area-reduced adapter cases; unchanged native baseline passed
267 exact host comparisons. Two fresh release APKs passed package inspection.
Six real 30-second recordings fully decoded with actual RCD and no MHC frames,
but all failed cadence: full-source 0.55–0.69 fps; approximate 960×540 reconstruction
6.51–7.96 fps. Neither mobile performance nor approximate quality is accepted.

The user authorized reduced geometry/equation experiments, then directed review
of our earlier 16-to-30 fps recording work. That review found the current recorder
forces encoder GPU-writer delivery off, and the new RCD route also performs a
synchronous RGB readback/copy. Recovering the application scheduling/encoder
benefits is the next integration priority. No excluded shader implementation is
needed for that work. See [integration results](requirements-recording-integration.md),
[measured summary](requirements-recording-summary.json) and
[prior session review](prior-recording-session-review.md).

