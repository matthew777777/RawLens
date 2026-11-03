# Tile-local full-source RCD redesign

Resumed 2026-11-01, HEAD 28977494032f743742eafbbf77ff437317544554,
branch refactor/2.0. Unrelated working-tree changes preserved. No AGENTS.md was
found. The Xiaomi was initially disconnected and subsequently reconnected.
No disputed shader/archive/expected pixels were used.

The new source is `tools/photo-rcd/tile_local.py`, generated from the approved
RawTherapee baseline adapter. A 16×16 owned output core uses a 38×38 FP32 CFA
halo, 28×28 VH, 30×30 lowpass, 24×24 diagonal guide, 26×26 green and 22×22
missing-color arrays: 19,456 bytes of workgroup-local storage. All invocations
cooperate in loading and pass barriers before per-pixel returns. Workgroups
have disjoint 16×16 ownership inside each canonical 194/176 tile. Small/tail
tiles retain the pinned behavior, including exactly-18-pixel tails and retained
VH values recomputed from immutable source samples. No source decimation or
FOV change is introduced.

Recording-mode dispatches are normalization, local interior reconstruction plus
existing product corrections, and canonical border plus product corrections.
The five global reconstruction planes and RGBA32F frame are no longer allocated
in this mode (unused descriptors receive bounded 16-byte buffers). FP32 oracle
readback mode still stores RGBA32F and skips product correction. Half output
uses the existing explicit rounding operation. WB, sensor-white protection and
nonuniform lens shading keep the existing equations and order. Grading/LUT/P010
remains separate. GPU RGB handoff is a separately gated extension.

The fused-product host gate passed 71 frames with maximum FP32 error zero,
maximum mean FP32 error zero and maximum half error zero, versus the validated
production baseline, including changing WB/nonuniform 2×3 LSC. Original budgets
remain 2e-5 max / 2e-6 mean before half storage. The unchanged baseline also
passed 267 exact host frames. These are host results, not phone performance.
See tile-local-fused-product-validation.json and tile-local-legacy-regression.json.

At 3840×2160, removing 286 canonical tile planes saves 150,694,544 payload bytes
and dropping RGBA32F saves 132,710,400 more, before allocator alignment. Shared
arrays are per active workgroup, not per frame. This is a memory inventory, not
a speedup claim.

## GPU-resident RGB handoff gate

The separate image-output module substitutes binding 8 with a storage RGBA16F
image. It keeps the explicit packHalf/unpackHalf boundary before imageStore,
so it stores already-representable half values. The Android engine queries
external-image format/import support and required HardwareBuffer usage, checks
the actual buffer's equivalent format, imports dedicated memory, and caches
imports per output slot. Compute ownership is acquired from FOREIGN and released
back to FOREIGN around every complete overwrite. The existing grade completion
fence must finish before a slot is selected for writing again. Descriptor and
command re-recording when the output slot changes does not reallocate input or
reconstruction buffers.

This first bridge is deliberately synchronous: the native GPU completion fence
is awaited before the separate grade context submits its read. No CPU readback
of RGB or HardwareBuffer CPU upload occurs in this mode; only product-status
metadata is read. RAW input still uses CPU byte staging. Asynchronous overlap
requires a later external completion dependency and per-slot in-flight resources;
it is not claimed by removal of RGB pixel copies. Unsupported storage-image
format/usage fails the internal route, preserving explicit recorder fallback.

Host C++ regression with the new import infrastructure passed 71 FP32 and final
half comparisons exactly; Android engine/JNI compiled with NDK27. Image shader
compilation and Vulkan1.1 SPIR-V validation passed. External-memory correctness
and image-boundary half equality still require the phone gate.

Reference: https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html

## First phone findings (2026-11-01/02)

The six-test tile-local suite passed: 36 direct RAW FP32 comparisons had maximum
error 4.7683716e-7 and final-half comparisons had maximum error 0.00048828125.
However, the first full-source 1080p diagnostic regressed to a median 1364.5ms
render time (zero MHC frames and zero P010 CPU copy). This schedule is not a
performance-qualified replacement. The isolated original shape sweep likewise
measured roughly 1508–2310ms reconstruction GPU medians; smaller groups did not
fix it. Reports preserve failures rather than promoting memory reduction alone.

An exact early-zero bound was added for retained VH coordinates outside every
canonical tile's maximum written domain (>=190). Host comparisons still pass,
but the first large-frame 8×8 phone sweep encountered a non-finite product-domain
error after three dispatches. This is an unresolved device numerical/implementation
failure despite passing small host fixtures, not acceptable quality. Other shapes
need independent testing; no speedup or diagnosis of compiler spills is claimed.

The image-output gate initially rejected allocation usage: the device requested
0x200000001000100 while the generic RGB buffer had 0x333. This includes a vendor
bit and GPU-data usage. The implementation now queries the required Vulkan usage
before allocation rather than guessing a generic Android usage. The capability
check is retained. A standalone small image/pixel probe separates allocation,
import and stores from the full recorder. Its result and subsequent phone gates
must decide whether the direct GPU image handoff is usable.

## Claude handoff, stopped — 2026-11-02

The user requested noting all work and continuing in Claude tomorrow. No active
own build/test/recording/benchmark job was found at the stop. Current source,
validated historical artifacts, failures and exact next steps are recorded in
[claude-handoff-2026-11-02.md](claude-handoff-2026-11-02.md), with current hashes
in handoff-state-2026-11-02.json. Full-source tile-local recording regressed; a
large-frame bounded-VH shape test has an unresolved non-finite product failure.
The queried-usage GPU image probe passed only its small native pixel test; the
new allocation fix has not passed the complete rebuilt app gate. All mandatory
FPS/quality/thermal acceptance remains unmet. Do not resume without user direction.
