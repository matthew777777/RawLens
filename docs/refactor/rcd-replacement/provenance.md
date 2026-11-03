# Source provenance gate

Date: 2026-11-01. Technical evidence, not legal clearance. The exact source hashes
are in [the machine-readable manifest](../../../tools/photo-rcd/upstream/manifest.json).
Original notices and full GPL texts accompany the host tools, outside app assets.

| Source | Decision | Evidence and limitations |
| --- | --- | --- |
| RawTherapee `rtengine/rcd_demosaic.cc` | Approved mathematical source / host oracle | Pin `b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c`; SHA-256 `646262a75c72ff8bad8ef199bf5edb48b8d1a230b196914cc07c59be8547b083`. Public GitHub commit API verified pin; fetched exact public file also matches existing public checkout byte-for-byte. |
| Same pin `rtengine/demosaic_algos.cc`, `border_interpolate` only | Approved canonical border dependency | Original GPL-3.0-or-later notice retained; copyright Gabor Horvath, 2004–2010. Remaining demosaic algorithms in the evidence file are not compiled or used. |
| Same pin `rtengine/rt_math.h`, SQR/LIM01/intp only | Approved arithmetic dependency | Extracted verbatim, not replaced by a different interpolation primitive. Repository license retained. Header has no individual copyright statement: do not invent one. History contributors include Ingo Weyrich, Alberto Griggio, Flössie, Hombre, DrSlony and torger; not an exclusive authorship determination. |
| Original LuisSR `rcd_demosaicing.c` | Approved historical source evidence, not second oracle | Pin `0aa9d6e234f55adb0abf55a82be371ad32dbc60d`, Git blob `a530d7c7c2580345c783210ae0ab8a2b079b59da`; public contents API verified. Release 2.3 @ 171125, Luis Sanz Rodríguez. Repository GPL v3 LICENSE retained. No original dcraw binaries downloaded. |
| `RcdReference.kt` | Compatibility comparison eligible for further tests; not primary oracle | Introduced in `a244814c1fcbcbb0d3f9d268a1bca5a8a32cde86`, 2026-10-06, matthew777777. Unchanged since introduction. Expressions inspected against public source; border and input adapters differ. Original translation's exact input revision is undocumented. |
| `vf_rcd.comp` at initial photo commit | Historical compatibility lead | Pre-dispute chronology supports further audit, but the header gives no exact upstream commit. Not copied into the replacement. |
| Current `vf_rcd.comp`, FP16 variants and native integration | Uncertain; excluded as implementation foundations | Later photo shader changes include stage specialization, paired-site dispatch and tiny-edge reflection; no complete origin evidence for each change. Full history and hashes in photo-history.json. A comment referring to packed tiles is a lead requiring investigation, not proof either way. |
| `rcd_final.glsl` | Application correction stage, not canonical RCD | Pre-dispute origin; later wavelet output and highlight-window adaptations. Separate matrix/white-desaturation behavior, not upstream interpolation. Preserve current path. |
| Disputed backend/archive/expected pixels | Excluded | Previously removed closure; no restoration or design use. |
| vkdt / darktable GPU RCD | Architectural references only | No implementation source copied or compiled in this milestone. |

RawTherapee file notice credits Luis Sanz Rodriguez and Ingo Weyrich, 2017–2020,
GPL-3.0-or-later. It credits Ingo for tiling and Hanno Schwalm and Luis for
performance collaboration. Last file commit at the pin is
`711f274403dea27d39e34b6938980affce05e6ca`, Daniel Gao, 2024-11-16 (include-path
change). A last committer is not necessarily an algorithm author.

Authoritative sources:

- [Pinned RawTherapee RCD](https://github.com/RawTherapee/RawTherapee/blob/b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c/rtengine/rcd_demosaic.cc)
- [Pinned border source](https://github.com/RawTherapee/RawTherapee/blob/b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c/rtengine/demosaic_algos.cc)
- [Pinned arithmetic](https://github.com/RawTherapee/RawTherapee/blob/b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c/rtengine/rt_math.h)
- [Original RCD](https://github.com/LuisSR/RCD-Demosaicing/blob/0aa9d6e234f55adb0abf55a82be371ad32dbc60d/rcd_demosaicing.c)
- [RawTherapee license](https://github.com/RawTherapee/RawTherapee/blob/b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c/LICENSE)

Gate outcome: approved public-source port route is available. The entire existing
photo baseline is **not verified**. Its headers and chronology cannot prove the
origin of every later change. Use the pinned RawTherapee replacement route rather
than building on unresolved candidates. No photo production files changed.

New host adapter/extractor/fixtures: written for RawLens by Codex, 2026-11-01,
GPL-3.0-or-later; adapted upstream mathematics retains upstream authorship. Before
distribution of an app port, include its notices/license and corresponding source
and review all dependency obligations. Nothing here resolves the historical dispute
or constitutes qualified legal review.

The new `baseline.comp` is an explicitly attributed public-source port of that
same pin, with a new FP32 SSBO atlas and host runner. It copies no candidate
photo scheduling code. Stage comparison established the pinned source's
odd-column HPF lookup, retained VH scratch and exactly-18-pixel tile skip;
see design.md. Licensed real crops have a separate CC0 fixture manifest and
official legal text; the tool source license does not replace the fixture license.

## Continued port and integration

New app/src/main/cpp/photo_rcd/ and PhotoRcdRenderer.kt are new attributed
GPL-3.0-or-later integration, not renamed retired backend. build_variants.py
keeps baseline reconstruction equations while changing storage/addressing and
pass scheduling; no private source/old expected pixels consulted. Exact
shader/license/source hashes are packaged and independently inspected. Existing
grade/LUT/P010 lineage review is recorded in integration-history.json; reuse
is separate from reconstruction provenance. This is technical traceability,
not a legal opinion about the existing dispute.

## Preserved photo candidate audit and corrected port

The user's subsequent selection of the 200 ms photo candidate prompted a direct
source-history comparison, without archive access. The saved candidate differs
from photo shader `bf16f46c3d35d86e6cc0253c8a3e9fe1fd3b2243` only in a recording-name
comment and omission of the tiny-dimension reflection and odd-width initialization
fixes. Comparing that photo revision to initial pre-dispute `a244814` identifies
FP32 input, specialization, a widened shared CFA tile, direct halo addressing and
paired-site dispatch/mate initialization; interpolation expressions are unchanged.
These observable transformations establish the candidate's photo-port lineage;
the original photo port's exact upstream revision remains undocumented.

The new generated recording candidate explicitly corrects the photo port's
missing pinned-RT odd-column diagonal-response convention, uses separate FP32
stage buffers, and adopts the independently validated public-source tile scratch
and border rules. It is an attributed adaptation of the photo port and pinned
RawTherapee, not an unchanged copy of a universally verified historical baseline.
`recording-candidate-original-mismatch.json` preserves the original numerical
failure; `recording-candidate-canonical-validation.json` records the corrected
267-case host result (maximum interior 2.3841858e-7, border zero). It does not use
historical candidate expected pixels. No archived source or binary was consulted.
This is technical traceability and GPL attribution, not legal clearance.
