# Independent packed photo-RCD replacement plan

Date: 2026-10-31
Status: planning only; implementation, deletion, publishing and history rewriting have not been performed.

## Objective and terminology

Replace the disputed Unspektrawesome-derived packed RCD dependency with a packed GPU implementation derived from RawLens's verified still-photo RCD and traceable public RCD sources. Preserve the public algorithm's image reconstruction while changing its GPU storage and scheduling. A packed layout means grouping Bayer samples by 2×2 cells; it does not require the disputed half-resolution color-ratio reconstruction.

This is an independent replacement/public-source port plan, not a certification of a clean-room process or legal clearance. The current conversation and existing developers have been exposed to disputed material. A strict clean-room route requires a separately documented unexposed implementer and a reviewed specification built from approved public material. A fresh AI session by itself does not establish that separation. Existing and historical distribution claims remain separate. Public-source adaptations retain their upstream licenses; “ours” means our adaptation, not exclusive ownership of upstream RCD.

Related plan: [Public-reference replacement and distribution cleanup](rcd-public-reference-replacement-plan.md). This document adds the photo-RCD starting point, packed-layout design process, implementation gates and an execution prompt. Where the two differ, establish the verified photo algorithm as the candidate oracle first; do not silently substitute a different public RCD variant.

## Operating constraints

- Preserve unrelated working-tree changes. Inventory status before edits, use a separate worktree when practical, and never reset or overwrite the user's work.
- Do not consult disputed shader bodies, archive contents, compiled shader disassembly, private helper structure or old packed-reference expected pixels for design or correctness. Path/symbol searches to inventory and remove dependencies are allowed; they are not algorithm research.
- Do not rename, translate or mechanically restructure the disputed implementation. Do not use its median-of-ten/ratio-grid family as a template.
- Existing performance summaries may identify product targets, but are not correctness oracles or evidence of replacement performance.
- Record uncertainties honestly. Do not invent authors, permissions, clean-room claims, successful checks or unavailable device results.
- Local implementation and normal reversible verification may proceed under an implementation request. Remote messages, release publication/deletion and history rewriting require separate explicit authorization; prepare concrete reviewable proposals first.

## Phase 0 — Snapshot and provenance gate

1. Record branch, HEAD, status, available devices/toolchains and pre-existing changes in an execution log. Record exposure to disputed material.
2. Trace the still-photo path starting with `RcdReference.kt`, `VfRcd.kt`, `vf_vulkan_vf_rcd.inc`, `assets/shaders/vf/vf_rcd.comp`, its precision variants, and `assets/shaders/rcd/rcd_final.glsl`. These are candidates, not automatically approved sources. Trace actual current callers before choosing scope.
3. Establish the history of each candidate: introduction revision, source references, subsequent algorithm changes and any import from disputed packed code. Review algorithm source only after identifying it as outside the disputed dependency closure. Record whether a shared file contains mixed-origin code.
4. Resolve the declared RawTherapee RCD v2.3 origin to an authoritative upstream repository, full commit, exact files, authors, license texts and SHA-256 values. Verify actual math and border behavior, not just header claims. The original public RCD repository is an alternative if the photo implementation cannot be established; pin it independently and document that this changes the numerical baseline.
5. Produce `docs/refactor/rcd-replacement/provenance.md` with an approved/uncertain/excluded source table and evidence. Do not use an uncertain candidate as the foundation. Continue independent inventory work while unresolved sources are investigated; ask only for evidence that cannot be recovered independently.

Exit gate: a traceable, properly licensed algorithm source and eligible photo baseline, with disputed imports excluded. If that cannot be established, document the blocker and propose a public-source port; do not label the current photo source verified.

## Phase 1 — Dependency inventory and removal design

Inventory production and test sources, shader includes, native state/JNI, initialization/teardown, Kotlin dispatch, portable generators, shader build scripts, checked-in SPIR-V, test assets, fixtures, packaging, notices and documentation.

Known starting points (discover current additions rather than treating this as exhaustive):

- `third_party/rcd-packed-upstream/`
- `app/src/main/assets/shaders/rcd_packed/`, including `portable/`
- `app/src/androidTest/assets/rcd_packed_reference/`
- `VfPackedRcd.kt` and `vf_vulkan_vf_packed_rcd*.inc`
- `tools/build_packed_rcd_shaders.sh`, `tools/build_packed_rcd_portable.py`, `tools/verify_packed_rcd_median.py`
- Unspektrawesome-specific bundled notices, archived README/license copies and copied source excerpts.

Classify each dependency as remove, replace, independently retain, or investigate. Treat shared camera, grade, P010 and fence code as unverified until its independent origin is checked. Create `inventory.md` including compiled/generated dependencies and historical-artifact scope. Preserve provenance evidence outside the distributable replacement tree as appropriate; do not erase evidence or claim that a deletion commit removes history.

Exit gate: complete source/build/runtime dependency map and a replacement interface design. Final removal occurs before release validation; temporary coexistence during local development must be clearly tracked and cannot be called a clean release.

## Phase 2 — Numerical and interface contract

Write `design.md` before optimized code. Freeze the eligible photo RCD's actual equations, stages, epsilon values, normalization, clipping, boundary extension, neighborhood radii, intermediate precision and output convention. Separate demosaic from white balance, lens shading, matrix conversion and output transfer functions. Establish which corrections happen before interpolation versus after it; preserve current intended product behavior through explicit tests.

Specify:

- RAW16 packing/stride, crop origin and dimensions, active sensor rectangle, per-CFA black levels and white level.
- RGGB/GRBG/GBRG/BGGR mappings using sensor coordinates, including odd crop origins.
- Odd dimensions, tiny images, invalid metadata and allocation/overflow handling.
- Scene/camera-linear RGB output, negatives and highlights, and supported numeric domains. Signed/HDR extensions require separately stated behavior.
- Vulkan image/buffer formats, descriptor bindings, resource lifetime, barriers, queue ownership and synchronization.
- CPU versus GPU floating-point comparison rules; document FMA, denormals and FP16 storage. Derive and freeze justified tolerances from the reference arithmetic/quantization before optimization. Do not loosen them merely to pass a failure.

Build a traceability table linking each approved algorithm step to a reference location, baseline implementation and test. Do not mix photo RCD and another public implementation into an unspecified hybrid.

## Phase 3 — Correctness baseline

Establish a reproducible CPU/public-source oracle and an uncomplicated GPU baseline from approved material. Reuse `RcdReference.kt` only after the provenance gate; do not assume its current tests independently establish correctness. Capture intermediate stage values where useful.

Fixtures: uniform colors, black/near-black, per-channel impulses, horizontal/vertical/diagonal ramps and edges, checkerboards, high-frequency chroma, saturation, deterministic noise and licensed real RAW crops. Cross with all Bayer phases, odd/even crop origins, edge/corner pixels, odd sizes, small sizes, nontrivial row strides and representative black levels. Invalid inputs must fail predictably without out-of-bounds GPU access.

Record CPU-to-GPU max/mean absolute error, ULP where meaningful, channel errors and border-only statistics. Document precision differences. Use exact output equality only where the arithmetic contract supports it. Keep the validated same-device GPU baseline as the optimization oracle; it complements rather than replaces public-algorithm validation.

Exit gate: reproducible passing baseline and reviewed numerical tolerances, with source/fixture hashes and toolchain recorded in `correctness.md`.

## Phase 4 — Pack the verified photo algorithm

1. Define cell coordinates and local Bayer site indices from the verified photo algorithm. Derive sensor-coordinate neighbor addressing explicitly, including odd crop phases and borders.
2. Choose a packed input representation and intermediate layouts from the photo algorithm's required values. A candidate is four CFA samples per cell; final channel order and format must follow the approved design and device support.
3. Initially keep the photo algorithm's full-resolution reconstruction equations and stage dependencies. Packing is a storage/dispatch optimization; do not introduce a half-resolution color-ratio method to imitate the removed backend.
4. Implement loading/storing and dispatch with full bounds checks. Trace each packed neighbor read back to its scalar reference coordinate.
5. Compare unpacked intermediates and final RGB against the validated GPU baseline. Preserve operation ordering and storage-rounding points where exact equality is required.
6. Add safe resource reuse, shared-memory neighborhoods and workgroup tuning one change at a time. Derive halo widths from the real dependency chain. Keep an untuned supported path.
7. Consider fusion only after the separate path passes. Preserve required intermediate quantization and barriers; if changing precision or math is necessary, treat it as a separately reviewed numerical change.

Use distinct implementation names (for example `VfPhotoRcdPacked` and `shaders/photo_rcd_packed/`) to distinguish replacement artifacts during review. Names are bookkeeping, not proof of independence. Never copy old helper names or layouts for convenience.

Exit gate: equivalent verified photo-RCD reconstruction in a packed layout, plus reproducible stage-level and final-output results.

## Phase 5 — Recording and product integration

Reimplement the required host interfaces around the replacement's own descriptors and lifetimes. Preserve RAW ownership until GPU completion; exercise cancellation, timeout, shutdown, reuse and failure paths. Audit portable arithmetic/device capabilities and select a supported precision path explicitly.

Validate independent grade/LUT, lens shading, WB/matrix order, scaling coordinates, crop, output ranges, chroma placement, P010 strides and Main10 encoding. Include BT.709, S-Log3/S-Gamut3.Cine, HLG and ACES as currently supported. Compare fused demosaic/grade/output against the replacement's separate path, requiring identical bytes only under an equivalent declared arithmetic contract.

Test synchronous/asynchronous submissions, distinct parameters on consecutive frames, descriptor/resource reuse, acquire/release fences, repeated start/stop, preview on/off and recoverable allocation/initialization failures. Keep the supported MHC fallback functional and visible in diagnostics; never silently call it RCD. Check preview, still-photo development, DNG capture, audio synchronization, decoded output, muxed timestamps and MediaStore completion.

Exit gate: targeted JVM/native/device checks pass and actual replacement recordings decode correctly. If devices are unavailable, identify device validation as incomplete.

## Phase 6 — Performance validation

Treat 3840×2160 at 24 fps and 2560×1440 / 1920×1080 at 30 fps as initial targets, subject to confirmation against current product requirements and device evidence. 4K30 is a stretch target. Do not guarantee old-backend performance.

Record release build hash, device/driver, input/crop/output sizes, profile, LUT, preview, precision, battery/thermal conditions and run duration. Measure warm GPU stage median/p95, memory, camera cadence, waits/copies, encoder throughput, muxed PTS, duplicate/missing frames and p95/max gaps. Distinguish overlapping stages from serial costs.

Run at least 30-second recordings per required mode on available target devices, then a 10-minute soak for representative highest-load modes. Report throttling and cadence by interval. Optimize verified bottlenecks in small patches; rerun relevant numerical and ownership gates after each change. Never claim a target passed from GPU time alone or by reducing reconstruction detail.

Exit gate: `performance.md` reports measured passes/failures and tradeoffs. Any unmet required target remains an explicit open item rather than a completion claim.

## Phase 7 — Remove disputed dependency and validate packages

Delete the classified disputed runtime/test/vendor/generator closure and stale compiled artifacts. Replace the tests with approved-oracle fixtures. Update CMake/Gradle/JNI/resource hooks and clean generated outputs so a build cannot accidentally ship an old shader. Remove obsolete shipped archive-specific notices when no corresponding material remains; retain accurate replacement attribution and a factual historical audit outside shipped assets.

Search source/build inputs and inspect built APK contents, shader hashes and native symbols. Names alone are insufficient: inspect generators and dependency edges for copied content. Rebuild supported variants, run appropriate checks, and verify the photo path has not regressed. Do not run broad unrelated tests repeatedly after relevant gates pass without a reason.

Exit gate: a fresh replacement release build has no disputed code or binaries in its current source/dependency/package closure. Save source and APK inventories and artifact hashes in `completion.md`.

## Phase 8 — Historical distribution and review

Prepare a concrete inventory of affected branches/tags/releases/APKs/source archives/CI artifacts and issue attachments. Specify what can be replaced, removed or remains outside our control. A new implementation does not remove earlier copies or settle the claim.

Prepare reviewable release notes, cleanup commands, replacement artifact hashes and proposed issue response, without executing remote publication, messaging, deletion or history rewriting. Obtain explicit authorization for those actions. Given the existing ownership dispute, recommend legal review of the process and any claim of resolution. Do not claim the project is legally cleared.

## Deliverables and completion checklist

Store execution evidence under `docs/refactor/rcd-replacement/`:

- `execution-log.md`: starting state, decisions, exposure record, commands, results and remaining work.
- `provenance.md`: approved sources, pinned revisions/hashes, authors/licenses and uncertainties.
- `inventory.md`: removal/replacement map and final dependency closure.
- `design.md`: numerical contract, packed mapping, resource lifetimes and algorithm traceability.
- `correctness.md`: fixture provenance, error/bit comparisons and reproducible commands.
- `performance.md`: device modes, cadence, GPU timings, soak results and limitations.
- `completion.md`: checks, clean package inventory, build hashes, remaining risks and proposed historical cleanup.

Done means provenance established, public algorithm validated, packed replacement integrated, required available-device checks completed, current disputed dependency removed, licenses correct and results recorded. External cleanup is a separate pending milestone until authorized and executed. No strict clean-room or freedom-from-liability claim is part of acceptance.
