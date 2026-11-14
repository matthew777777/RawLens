# Current dependency inventory and replacement boundary

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

2026-11-01. Completed removal is recorded in ../issue1-removal.md and its package
and history reports. Do not repeat it or consult removed artifacts. No current
runtime replacement is integrated by M1.

| Dependency | Classification / action |
| --- | --- |
| Removed packed vendor/runtime/assets/generators/native wrapper/reference pixels | Already removed; do not restore. Final fresh-package check remains required after replacement integration. |
| `RcdReference.kt` | Preserve unchanged; compatibility comparison only. Pre-dispute history verified. |
| `VfRcd.kt`, `vf_vulkan_vf_rcd.inc` | Preserve shipped photo interface; origin audit incomplete for later shared/native changes. Do not copy wrapper layouts by convenience. |
| `vf_rcd.comp` and SPIR-V | Preserve photo path; uncertain later scheduling; not replacement source. |
| `vf_rcd_f16`, `vf_rcdyuv*` and compiled variants | Preserve pending caller/origin audit; optional arithmetic/fusion excluded from new baseline. |
| `Gles31AmazeProcessor` | Actual photo caller: uploads CFA, submits RCD, imports result; reads `vf_rcd.spv` in initialization. Current recorder does not select retired RCD. |
| `rcd/rcd_final.glsl` | Preserve photo negative clip / white-desaturation / camera matrix / wavelet output. Treat as separate product behavior. |
| Shared grade/LUT/P010/scaling/fences | Independently audit before integration. Existing removal does not prove every surviving integration component suitable for reuse. |
| `tools/rcd-baseline/fast-20261026/*` | Paths inventoried only; source/binary bodies not read or used. Origin unresolved; exclude as implementation/benchmark oracle. |
| `tools/photo-rcd/upstream/RawTherapee/*` | New pinned public evidence; compile only explicit algorithm/helper/border closure. Not Android assets. |
| `tools/photo-rcd/upstream/LuisSR/*` | Historical public provenance evidence; not compiled, no downloaded upstream binaries. |
| `tools/photo-rcd/{build_oracle.py,oracle_*,validate.py}` | New host tooling, public-source-derived oracle. Generated code/binaries in explicit /tmp work directory. |

Replacement interface starts from normalized CFA + explicit sensor phase/crop,
returns camera-linear RGB and completion ownership; baseline SSBOs and immutable
stage inputs are defined in design.md. Android RAW16 and encoder integration are
not inherited from the deleted packed backend. Complete runtime dependency graph,
compiled asset inspection and final APK/symbol inventory remain later gates.

## New integration closure

app/src/main/cpp/photo_rcd/: new engine, input/corrections and JNI; built as
librawLensPhotoRcd.so on supported ARM variants. PhotoRcdRenderer.kt retains
per-slot RGB and completion fences. EncodedVideoRecorder's internal validation
switch calls it; public MHC/SUPER enumeration is unchanged. Gradle generates
baseline/optimized SPIR-V from approved source with pinned NDK glslc, packages
source, hash manifest, GPL text and attribution. No checked-in new SPIR-V.

Retained grade/source lineage is recorded in integration-history.json.
vf_gradeyuv's original path predates the disputed import; reviewed later changes
cover independently documented Sony/ACES/profile/LUT/chroma behavior. Existing
separate grade is reused; no disputed demosaic/output implementation was read.
Final APK path/binary identifier inventory passed (package-validation.json),
including native exports and DEX checks. No private archive/disassembly used.
