# Frame-global recording RCD — 2026-11-03 session

Status: diagnostic kernels only. **Not integrated into the app; no recording,
encoder or delivered-FPS result.** Workload uncontrolled (camera HAL ~61% CPU,
GPU 61.7 °C at measurement). Phone: Xiaomi 25080RABDG, Mali-G615 MC2.

## Provenance

Equations and step order come from the approved public port
`tools/photo-rcd/baseline.comp` (RawTherapee b2ee504c `rcd_demosaic.cc`,
Luis Sanz Rodriguez / Ingo Weyrich, canonical border Gabor Horvath,
GPL-3.0-or-later). No disputed archive, Unspektrawesome material, prior
packed shader bodies or codex-session shader code were read or used.

## 1. Root cause of the bounded-VH "outside finite FP16 domain" failure

`tile_local.specialize_core()` rewrote array sizes by sequential string
replacement. For 8×8 cores the raw edge became 30 (8+22), which the next
replacement (LP, old edge 30) rewrote to 22, along with its `k<` load bounds
and `%30` indexing. Shared raw/LP tiles were partially unloaded, so the
kernel read stale shared memory: identical 4K input changed ~9.9M of 33.2M
half samples per frame, occasionally overflowing FP16. 8×16 had the same
collision; 16×16 (app default), 16×8 and 32×8 were unaffected.

Fix: two-phase placeholder substitution with per-array assertions
(`tools/photo-rcd/tile_local.py`). 16×16 text is byte-identical to before.
On-phone 4K check: all five shapes are deterministic and bit-identical to
each other (FNV hash `25ec6a7cd74a82bc`), no FP16 flag. Earlier 8×8/8×16
timings were of broken shaders. Corrected sweep:
`tile-local-shape-diagnostic-fixed-specialization.json` (32×8 best, 1636 ms).

The exact tile-local schedule stays the photo/oracle path (option B).
Profiling showed its cost is redundant response recomputation (~5.6× halo,
six 7-tap responses per VH/PQ value), not `precise` or the retained-VH scan.

## 2. Frame-global recording kernel (option A)

`tools/photo-rcd/frame_global/rcd_frame_strip.comp` (+ `unroll.py`, which must
be run before glslang). The frame is one RCD domain: no 194/176 tiles, no
retained-scratch emulation. Same equations, same order, same odd-column
diagonal-response convention, same 4/9-pixel domains and canonical border.

Measured Mali facts that drove the design (4K synthetic dispatches):

| probe | result |
|---|---|
| plain copy (read f32, write half RGBA) | ~10 ms per pass |
| 2.1 G dependent FMAs | 8.4 ms (ALU is plentiful) |
| 128 scalar shared loads/thread | 57 ms |
| 32 vec4 shared loads/thread (same bytes) | 15 ms |

So this GPU is bound by load *instructions*, not bytes or arithmetic.
Design: FP16 intermediates in shared memory, eight pixels per 128-bit load;
each invocation evaluates a 4-pixel strip in FP32 with fully unrolled lanes
(dynamic vector indexing spills to local memory: 476 ms vs 95 ms); warps
mapped to one CFA row parity so colour branches stay coherent.
Stage 0 writes FP16 planes of refined VH, refined PQ and LP; stage 1 does
green, R/B at R/B, R/B at G, border and the WB/highlight product step.

### Results (GPU timestamps, reconstruction + product only, warm medians)

| version | 1080p | 1440p | 2160p | quality vs exact oracle (4K) |
|---|---|---|---|---|
| exact tile-local 16×16 (B) | – | – | ~1000–3400 ms | reference |
| per-pixel FP32, host-cached memory | – | – | 506 ms | 102.9 dB, max 4.9e-4 |
| same, device-local memory | 57 ms | 206 ms | 265 ms | 102.9 dB |
| Bayer-cell schedule | 45.5 ms | 88 ms | 182 ms | 102.3 dB |
| **FP16-shared strips, parity warps** | **32.5 ms** | **62 ms** | **128 ms** | **75.4 dB, max 1.46e-3, 0 non-finite** |
| same with mediump reconstruct (rejected) | 33.0 ms | 56 ms | 137 ms | 72.9 dB |

Max error 1.46e-3 is ≈1.5 ten-bit code values; RMSE 1.7e-4 ≈ 0.17 code.
Oracle = exact engine half output (16×16 tile-local, product pass) on the
same synthetic 4K frame; it includes tile-seam/retained-VH behaviour the
frame-global kernel intentionally does not reproduce. Synthetic pattern
only; a real-scene visual/quality acceptance is still required.

### Targets: still unmet

Budgets include grade, LUT, scale and P010, which these numbers exclude.
1080p RCD alone is ~32.5 ms of the 33.3 ms budget; 1440p ~1.9× over;
4K ~3.1× over the 41.7 ms budget. Do not report a delivered-FPS pass.

## Next steps (ranked by expected gain)

1. Load-count reduction: 8-pixel strips with 24-pixel windows (3 loads)
   where register pressure allows; register reuse across vertically adjacent
   rows; fuse VH/PQ refinement loads. Per-phase ablation at 4K: guides 46,
   green ~30, missing ~22, final ~44 ms.
2. Move read-only RAW to an R16F/RGBA16F image and fetch through the
   texture unit, offloading the load/store pipe.
3. Fuse RAW16 normalization into `loadRaw` (drop the f32 CFA pass) and fuse
   the separate grade/P010 work into stage 1 output to remove a full-frame
   RGBA16F round trip.
4. Integrate into `photo_rcd_engine.cpp` as a separate recording mode
   (FP16 planes buffer, full product LSC path, GPU image output), then run
   the image-correctness gate, real-scene quality review and headless
   recordings with timestamp/cadence analysis.

Reproduce: `python3 unroll.py rcd_frame_strip.comp out.comp`, glslang
`-V --target-env vulkan1.1 -Os`, NDK-build `bench_frame.cc` with `-lvulkan`,
then on device `bench_frame shader.spv W H [oracle.half|-] repeats`.
Oracle dump: `oracle_dump_diag.cc` (against a copy of the engine whose FP16
throw is replaced by `photo_rcd::diagFlag`) with argv[6] output path.
SPIR-V SHA256 of measured kernel:
`ef1b327e63a259743124114edd3424cc1806f523a9413099fa40cecf0637b677`.

---

## Session 2 (same day): subgroup walker, engine integration, real recordings

### Kernel: `tools/photo-rcd/frame_global/rcd_frame_walk.comp.in` (+ `generate.py`)

Further Mali probes: texture fetch is *slower* than shared vec4 loads (20 vs
15 ms for 32/thread) and does not overlap with load/store; subgroup shuffles
are very cheap (64 shuffles/thread over 4K in 6.5 ms); barriers are costly.
Design: one 16-lane subgroup per 64-pixel band (4 px/lane), no shared memory,
no barriers; horizontal neighbours by `subgroupShuffleUp/Down`, vertical by a
rolling packed-FP16 register history; bands overlap by stencil erosion
(8 px guides/green-missing, 4 px final). Passes: 0 guides (VH/PQ refined, LP)
→ 1 green + missing (walker) → 2 R/B at G + product (one subgroup per row) →
3 canonical border (1-D dispatch over border pixels only; inline border code
cost ~50 ms through register pressure). Same FP16 storage points as the strip
kernel, so quality is unchanged.

Product step: `generate.py` extracts the RAW metadata block and `productPixel`
verbatim from `tile_local.py`; the walker's per-pixel path (`productFast`)
uses the same equations with configuration hoisted and LSC evaluated once per
lane-row plus the exact in-cell x-slope (four evaluations per lane spilled:
~100 ms). Module-scope `precise` variables crash glslang / break glslc's
optimizer — avoid them.

Isolated 4K timings (synthetic, phone otherwise loaded; best-of samples):

| | guides | green+missing | final+product | border | normalize | total |
|---|---|---|---|---|---|---|
| strip kernel (session 1) | 46 | — | 82 (both) | — | — | 128 |
| walker, LSC off | 9.4 | 10.7 | 22.7 | 0.4 | 5–6 | ~49 |
| walker + row-final, LSC on, slope LSC | 10–11 | 10.5 | 30–34 | 0.4 | 5–6 | ~56 |

Quality (4K synthetic, real engine incl. RAW16 normalize and product):
LSC off 75.44 dB / max 1.46e-3; LSC on (17×13 map, WB 1.7/1/1.3) vs exact
tile-local engine 70.21 dB, max 1.95e-3, border max 7.6e-6, 0 non-finite,
deterministic across frames.

### Engine/app integration (not committed)

* `photo_rcd_engine.{h,cpp}`: `frameGlobal` mode (requires tile-local route):
  planes buffer on binding 1 (10 B/px), stages {7,0,1,2,3}, band dispatch,
  normalized CFA GPU-only, product/RAW/metadata in device-local uncached
  host-visible memory (`deviceUpload`).
* JNI `openNative(..., frameGlobal)`, `PhotoRcdRenderer.open(frameGlobal=)`,
  assets `requirements_frame_global_{,image_}rcd`, `EncodedVideoRecorder
  .validatePhotoRcdFrameGlobal`, recording test arg `frameGlobal`, scripts
  `--frame-global`, Gradle generator inputs.
* Not yet adapted: `PhotoRcdCorrectnessDeviceTest` (needs a documented
  frame-global budget; the exact FP32 budgets do not apply to option A).

### Real headless recordings (full 3840×2160 source crop in every mode)

Every mode reconstructs the full 3840×2160 crop (full detail/FOV contract),
so 1080p/1440p carry 4K-source RCD cost.

| run | 1080p30 fps | 1440p30 fps | 2160p24 fps | 1080p RCD wall median |
|---|---|---|---|---|
| `recordings-frame-global-walker-2026-11-03` (walker final, no LSC fix) | 8.2 | 6.7 | 5.6 | 108 ms |
| `...-v2` (row final + LSC fix + device-local) | 5.1 | 4.3 | 5.1 | 157 ms |

v2 ran under heavier competing load (other camera/benchmark apps at ~90% CPU,
GPU 64 °C); in-recording stage medians (final 77 ms, min 44) are far above
isolated runs, and isolated runs at the same time varied 40–170 ms. **Targets
remain unmet; no delivered-cadence pass.** Decode complete; source timestamps
unique; one duplicate mux PTS warning observed in run 1 (investigate).

### Next steps

1. Controlled measurement window (competing camera apps idle) to separate GPU
   contention from kernel cost; interleaved A/B only.
2. Final pass (largest): 2 output rows per subgroup sharing loads, vector
   stores in buffer mode, explicit FP16 arithmetic to relieve registers, then
   re-try the walker final.
3. Fuse RAW16 normalization into row loads (drop the 5–6 ms pass and FP32 CFA).
4. Async/pipelined submission (per-slot planes) so CPU staging and grade/P010
   overlap RCD; measure grade/P010 GPU cost inside the 33.3 ms budget.
5. Adapt the correctness device test with an explicit frame-global budget.

## Session 3: fused RAW16 normalization, FP16 final step

* Normalize pass removed: walker row loads read RAW16 (one aligned 8-byte
  word = four crop pixels) and apply the same clamp((code-black)/(white-black))
  — output bit-identical to the separate pass. Engine requires 2-byte pixels,
  crop left %4==0, stride %8==0 (else throws → existing MHC fallback).
* Product status: per-pixel atomic replaced by subgroup-merged flag (no
  timing change; kept).
* Final pass `precision mediump float`: 22.5 → 19 ms isolated; quality vs
  exact engine with LSC 68.5 dB, max 3.9e-3, 0 non-finite.
* Ablation (final pass, 4K, LSC): product step ≈ 9 ms, G-site math ≈ 5 ms.
  Isolated 4K total ≈ 15 + 12.7 + 19 + 0.6 ≈ 47 ms. Estimated memory traffic
  ≈ 300 MB/frame vs ~10 GB/s measured → ~30 ms floor for this structure.

Real recordings (`recordings-frame-global-walker-2026-11-03-v3`, `-v3-1080`):

| mode | delivered fps | RCD wall median | GPU guides/green/final |
|---|---|---|---|
| 1920×1080/30 | 11.9 | 68.7 ms | 14.9 / 14.2 / 29.6 |
| 2560×1440/30 | 13.7 | 68.4 ms | — |
| 3840×2160/24 | 13.0 | 72.3 ms | 15.0 / 15.2 / 28.1 |

Targets still unmet. Remaining large levers (estimated):
1. Fuse grade/scale/P010 into the final pass (removes the 66 MB RGBA16F
   write + grade read, ~13 ms) — touches the shared vf grade code.
2. Merge green/missing into the final pass (drops G/M planes, ~7 ms).
3. Pipeline submissions (CPU RAW staging + waits, ~10 ms of wall time).
4. 8-bit VH/PQ planes (~2–3 ms).

## Session 4: steps 2–4 (planes, staging); results

* Tried packing green+missing into one FP16 plane (G sites carry their R/B
  partner's missing value) and VH|PQ into one unorm8 plane: pass 1 unchanged,
  final pass ~2× slower (extra selects/loads past the register budget). Pass 1
  being unaffected by halved writes shows it is not bandwidth-bound. Reverted.
* RAW staging copies only the crop rows (frame-global loads are crop-guarded).
* True cross-frame pipelining needs the separate grade to wait on an exported
  RCD fence (grade API change) — moved into step 1 (fused grade), where it
  becomes engine-internal.

Recordings `recordings-frame-global-walker-2026-11-03-v4`: 1080p30 12.7 fps,
1440p30 13.1 fps, 2160p24 11.9 fps (RCD wall medians 72 / 69 / 75 ms).
Targets unmet.
