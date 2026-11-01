# Execution prompt: independent packed photo RCD

Copy the following prompt into the implementation task:

---

Implement the replacement described in `docs/refactor/independent-packed-photo-rcd-plan.md`. Read that plan and the linked `docs/refactor/rcd-public-reference-replacement-plan.md` first. Treat the photo-RCD plan as the specific starting-point and execution contract. This request authorizes local implementation, required reversible cleanup and appropriate builds/tests; it does not authorize remote history rewriting, release deletion/publication or issue comments.

Start by recording HEAD, branch, working-tree changes and available devices. Preserve all unrelated edits. Maintain progress and evidence under `docs/refactor/rcd-replacement/` using the deliverables named in the plan. Work through the phase gates; do not stop after merely restating the plan. Continue all independent work when a gate has a recoverable obstacle, and report precise blockers when external evidence or hardware is required.

First verify provenance of our still-photo RCD and its public RawTherapee/original RCD sources. Existing headers are leads, not proof. Pin authoritative revisions, hashes, authors and licenses. Do not build on sources with unresolved disputed imports. If the photo baseline cannot be verified, document why and propose a pinned public-source replacement rather than pretending the gate passed.

Do not read or use the disputed Unspektrawesome shader bodies, archive contents, disassembly, median/ratio-grid design, old packed expected pixels or derived source as implementation guidance or correctness oracles. Inventory paths/symbols only as needed to remove the dependency closure. Derive packing, addressing, intermediate storage and scheduling from the approved photo algorithm and independently written requirements. Keep its reconstruction equations initially; packing is a layout/dispatch optimization. Do not rename or translate the disputed backend.

Establish an uncomplicated validated CPU/GPU baseline before packing or fusion. Define Bayer/crop/border/stride, normalization, correction order and precision contracts. Document justified tolerances before optimization. Cover all CFA phases, edges, odd crops/sizes, synthetic patterns, licensed real RAW fixtures and invalid inputs. Compare optimization results to the new baseline, never the disputed backend. Keep source-step traceability and reproducible reports.

Implement and integrate the replacement through grading, LUTs, scaling, P010 and recording with correct buffer/fence ownership, supported portable arithmetic and fallback behavior. Measure real release-device recordings and thermal/cadence behavior against the plan's targets; do not substitute GPU timings for delivered fps or invent unavailable device results. Preserve still-photo behavior.

Remove the entire disputed current dependency closure, generators, reference assets and compiled binaries before final package validation. Keep replacement licenses/attributions and factual provenance records. Inspect fresh APK contents and symbols. Prepare, but do not execute, external history/release cleanup and issue-response proposals.

Do not call this a strict clean-room implementation: this task's context includes prior exposure. Describe it accurately as a documented independent replacement or attributed public-source port, according to the evidence. Do not claim historical distribution problems or the ownership dispute are automatically resolved.

Finish with actual changes, validation results, artifact/report paths, performance outcomes and any incomplete gates. Ask only for genuinely missing evidence, required permission or decisions that cannot reasonably be resolved from the repository and public sources. Follow applicable AGENTS.md instructions and keep concise progress updates.

---
