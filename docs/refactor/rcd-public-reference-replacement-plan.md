# Public-reference RCD replacement plan

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Status: proposed implementation plan, 2026-10-30. No replacement or repository-history cleanup has been performed by this document.

Context: https://github.com/matthew777777/RawLens/issues/1 disputes the provenance of the packed-ratio shader family introduced in a4f58a717c92b1049feaf3e8bf8c5fb3c1034411. This plan does not determine authorship or validate the archive's licensing claim.

## Replacement contract

Replace the disputed implementation and its dependency closure with RCD derived exclusively from verifiable public references. Candidate primary reference: Luis Sanz Rodríguez's original GPL-3.0 RCD implementation, https://github.com/LuisSR/RCD-Demosaicing, `rcd_demosaicing.c`. Public darktable and RawTherapee RCD implementations may provide additional documented references, but choose one numerical oracle rather than mixing algorithms.

Before implementation, resolve and record the primary reference's full commit, source hashes, authors, applicable license text, boundary behavior and floating-point assumptions. Keep required attribution for any port or adaptation. A source translation is an attributed public-source port; do not describe copied or adapted code as clean-room. An independent implementation must use a documented public algorithm specification and an exposure record; a label alone cannot establish independence.

Do not use the disputed archive, shaders, private variant's helper structure, median/ratio-grid design or reference binaries as implementation material or correctness oracles. Do not rename or mechanically rewrite them. The audit so far identified integration paths and historical performance records; further implementation research should stay within the approved public sources.

Line-by-line parity means a traceability table from each public algorithm step to the replacement and its test. It does not mean identical source text. Bit parity means zero differing output bits for a specified oracle, precision, device/compiler environment and input domain. Public RCD need not produce the disputed variant's pixels. Do not promise equality between different algorithms or universal CPU/GPU float bit identity.

## 1. Remove the dependency closure

- Remove `third_party/rcd-packed-upstream/`, `app/src/main/assets/shaders/rcd_packed/` and `app/src/androidTest/assets/rcd_packed_reference/`, including generated SPIR-V.
- Remove `VfPackedRcd.kt`, `vf_vulkan_vf_packed_rcd*.inc`, associated state/resources, JNI symbols, initialization and teardown hooks.
- Replace the affected recording dispatch, direct P010 output, asynchronous submission, scaling and shading integration with independently implemented interfaces. Audit shared writer/fence code separately; retain only code with established independent provenance.
- Remove or replace packed-specific device oracles, build scripts, median verifier, generator targets, packaging references and assertions of verified licensing. Audit copied snippets, local archives, generated assets and release artifacts as well as filenames.
- Keep independently sourced still-photo RCD only after checking provenance and absence of imports from the disputed family. Keep historical measurement summaries clearly marked as retired-backend results; exclude disputed code from retained benchmark fixtures.

Exit: a full source and packaged-artifact inventory finds no disputed implementation, compiled shader or runtime reference; builds and supported recording fallback work.

## 2. Establish an independent correctness baseline

Build a host oracle from the pinned public source with reproducible compiler flags and documented input/output conventions. Specify CFA phase, crop origin, borders, tiny dimensions, normalization, epsilon constants, operation order, rounding and output precision before optimization. Document any upstream input-domain limits; signed or HDR extensions are separate tested adaptations.

Implement a straightforward Vulkan version first. Map every public stage and equation to implementation locations and independent tests. Compare intermediate stages to localize differences. Fixtures cover all four Bayer orders, crop offsets, borders, constants, impulses, ramps, edges, checkerboards, reproducible noise and licensed real RAW crops. Verify memory safety and odd-size policy.

Attempt strict bit equality where the arithmetic environment permits it. Record FMA, division, denormal and storage-rounding behavior. If the CPU oracle and GPU cannot match exactly, keep that failure visible, quantify ULP/error and select an explicit acceptance contract before claiming parity. A same-device unoptimized GPU baseline can establish exact optimization parity, but does not replace algorithm validation against the public oracle.

## 3. Preserve recording behavior

Test lens shading, white balance, camera matrix, output scaling, LUTs, BT.709, S-Log3, HLG and ACES independently of demosaic. Compare fused output against the new separate demosaic-plus-grade path, requiring identical P010 bytes where mathematically equivalent. Preserve the declared intermediate precision when fusing.

Exercise synchronous and asynchronous paths, changing per-frame shading/grade parameters, buffer strides, acquire/release fences, slot reuse, start/stop and failure recovery. Verify actual sensor timestamps, audio sync, Main10 encode, decoding, preview and MediaStore publication. No duplicated frames or lower-detail demosaic to manufacture throughput.

## 4. Recover and measure performance

Historical targets from `rcd-recording-performance.md`: sustained 3840x2160 at 24 fps minimum; sustained 1920x1080 and 2560x1440 at 30 fps; 4K30 remains a stretch goal, not an established baseline. These are acceptance targets for the replacement, not guaranteed outcomes. Old packed-backend measurements do not validate the replacement.

Measure actual RAW/crop/output dimensions, stage GPU timestamps, camera cadence, queue and mutex waits, copies, codec delivery and muxed PTS. Use the same phone, release optimization, profiles, preview state and controlled thermal conditions. Report warm median/p95 GPU time, peak memory, end-to-end fps, per-second cadence, p95/max frame gaps and drops. Account for overlap rather than adding overlapping stage averages.

Optimize in isolated steps: resource reuse, bounded asynchronous ownership, independently implemented encoder-buffer output, shared-memory tiling, workgroup tuning and safe pass fusion. After each change require exact equality to the new unoptimized GPU baseline on the same arithmetic environment. Precision reduction, reordered reductions and algorithm shortcuts need explicit numerical review and cannot pass as bit-preserving optimizations.

Run at least 30-second foreground/preview recordings for each target, then a longer thermal soak. Report measured results even when targets fail. Correctness and cadence are separate gates.

## 5. Distribution cleanup and completion evidence

Inventory affected local and remote branches, tags, releases, APKs, source archives, CI artifacts and the archive attachment in the issue reply. A new commit does not remove old distributed code. Prepare a concrete ref/artifact cleanup list and coordinated history/release procedure before any destructive remote rewrite or release deletion. Preserve necessary provenance evidence separately from distributed source; do not claim third-party copies can be erased.

Rebuild all supported release variants from the cleaned tree; inspect APK assets and native symbols; publish replacement artifacts and update affected download metadata. No issue comments or external changes are authorized by this planning document.

Completion evidence: pinned provenance manifest, removal inventory, public-step traceability table, oracle and optimization parity reports, recording/thermal performance results, reproducible release hashes and remote cleanup report. Describe any remaining limitations explicitly. A replacement alone does not resolve the issue's request concerning historical branches and releases.
