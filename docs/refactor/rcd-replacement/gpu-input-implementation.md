# Requirements-derived GPU input and compact reconstruction prototype

2026-11-01. Status: correctness-validated standalone prototype. It is not wired
into camera/recording and does not replace the application's current route.
The mandatory 1080p30, 1440p30 and 4K24 recording targets remain unmet.

## Implemented

- `raw_cell_pack.comp`: each invocation owns one geometric Bayer cell and writes
  two uint32 words containing four unchanged RAW16 codes. Odd invalid lanes are
  zero; valid stencil coordinates never read those padding lanes. Byte extraction
  handles nontrivial row/pixel strides and unaligned samples using a validated,
  explicitly word-padded staging allocation. No FP16 feature/arithmetic is used.
- Its default specialization emits only integer cells. A separate diagnostic
  specialization writes FP32 normalized cells to verify the normalization
  contract; this diagnostic buffer is not part of the intended fast route.
- `requirements_input_shaders.py`: generates a packing producer and RCD consumer
  around the approved `baseline.comp`, never the historical 200 ms candidate.
  RCD reads native codes directly from packed cells and normalizes on GPU.
  Its interpolation equations, numerical domains, pinned scratch/tile behavior
  and canonical border are unchanged from the independently validated public port.
- The new consumer stores LP/PQ only at R/B sites and stores only missing B-at-R
  or R-at-B. Known R/B values come from packed input. Green remains full-resolution.
  Separate scalar planes and dispatch dependencies avoid in-place reconstruction
  races. No LP/missing aliasing, FP16 stage storage or P010 fusion is introduced.
- `requirements_input_host.cc`: one Vulkan command buffer executes packing,
  an explicit producer-write→consumer-read dependency, then the public five-pass
  reconstruction. There is no CPU normalization or intermediate readback between
  packing and RCD. Host staging upload precedes the chain; final FP32 output and
  packed readback are validation probes. Their CPU readback is not a video route.
- `run_android_requirements_input.py`: compiles the same standalone runner with
  the Android NDK and tests it through headless Vulkan on the connected Xiaomi.
  No application is installed, camera opened, activity launched or recording made.

The input is 8*ceil(W/2)*ceil(H/2) bytes rather than 4WH normalized floats.
Per upstream tile site, guide/green/opposite payload decreases from 24 to 14
bytes: VH 4, LP 2, PQ 2, green 4, missing color 2. These are allocation reductions,
not measured DRAM transaction counts. Upstream tile duplication, RAW staging,
FP32 output probes and driver allocation alignment still consume memory. The
three guide planes implement the same 4 VH + 2 LP + 2 PQ values per Bayer cell
as the design contract; contiguous eight-float cells are not required.

## Validation

| Gate | Result | Evidence |
| --- | --- | --- |
| Integer packing/normalization host GPU | 84 cases, both production/probe specializations; exact integer output; max normalization error zero; six invalid metadata cases rejected | raw-cell-pack-gpu-validation.json |
| Packing→compact RCD host GPU | 67 cases; maximum error zero against pinned scalar oracle on this host | requirements-gpu-input-validation.json |
| Packing→compact RCD Android GPU | 35 cases; exact integer packing; max RGB error 4.172325134277344e-7 | android-requirements-input-compact/run.json |
| Initial noncompact prototype | Passing baseline evidence retained separately | requirements-gpu-input-initial-validation.json; android-requirements-input/run.json |

Cases cover all four CFA phases, crop parity, odd dimensions, row/pixel strides
2/3/4, truncated final-row capacity, unaligned samples and upstream tail domains.
Three licensed CC0 CFA crops are explicitly reencoded to RAW16 for adapter tests;
these do not establish original full-sensor RAW/camera integration quality.
Existing max/mean reconstruction budgets remain 2e-5/2e-6. The input-normalization
probe fixed its tighter 2e-7 diagnostic bound before execution; no threshold was
widened after a failure. Outputs are tested against the pinned public oracle,
not historical packed expected pixels.

## Packing-overhead diagnostic

Xiaomi 25080RABDG / Mali-G615 MC2, driver 184553472. The user requested continuing
with an uncontrolled phone workload, so this is a diagnostic microbenchmark.
A 4080×3060 RAW16 source is uploaded once per process into ordinary Vulkan storage.
Twelve packing dispatches reuse the same allocations; the first two are explicitly
excluded from warm summaries. GPU timestamps cover packing only. Output hashes
were checked against the requirements-derived integer layout.

| Crop origin/size | Warm median GPU pack | Warm p95 (10 samples) |
| --- | ---: | ---: |
| (120,450), 3840×2160 | 3.725845 ms | 4.86923 ms |
| (121,451), 3839×2159 | 3.961345 ms | 7.13392 ms |

See android-raw-pack-diagnostic/run.json. These times exclude camera acquisition,
CPU staging/upload, normalization consumed by reconstruction, RCD, grading, P010,
encoding, audio and video delivery. They establish neither total packing/import
overhead nor a 33.3 ms frame. No source detail/FOV was changed to get these numbers.

## Reproduce

```sh
python3 tools/photo-rcd/validate_raw_cell_pack.py \
  --work /tmp/rawlens-raw-cell-pack-gpu \
  --report docs/refactor/rcd-replacement/raw-cell-pack-gpu-validation.json
python3 tools/photo-rcd/validate_requirements_input.py \
  --work /tmp/rawlens-requirements-compact-input \
  --report docs/refactor/rcd-replacement/requirements-gpu-input-validation.json
python3 tools/photo-rcd/run_android_requirements_input.py \
  --serial fe79feha9lmb6hhi --work /tmp/rawlens-android-requirements-input-compact \
  --out docs/refactor/rcd-replacement/android-requirements-input-compact
python3 tools/photo-rcd/benchmark_android_raw_pack.py \
  --serial fe79feha9lmb6hhi --work /tmp/rawlens-android-raw-pack-benchmark \
  --out docs/refactor/rcd-replacement/android-raw-pack-diagnostic
```

## Remaining work

Connect supported Android camera-buffer imports or explicit staging fallback;
query actual capabilities and synchronize producer ownership. Integrate these
compact resources into a reusable native frame backend with bounded lifetimes.
Remove the validation FP32 output round trip and write the supported GPU grade
input with the existing final-storage precision contract. Then validate separate
grade/LUT/scaling/P010, stop/reuse/failure and encoder ownership. Measure normalization
and reconstruction cost at real source sizes; compare on-fetch normalization,
local normalization and optional GPU pre-normalization rather than presuming a win.
Only after correctness/product gates should tiled reconstruction/fusion and real
recording performance be evaluated. The application's previous failed recording
results remain the current end-to-end evidence. No APK/package change or new
release validation is claimed by these standalone tools.

This is attributed public-source implementation plus a requirements-derived input
and layout adapter. Prior exposure is acknowledged; it is not strict clean-room
certification or legal clearance. No private archive or disputed implementation
was consulted, no history cleanup repeated and no external publication performed.
