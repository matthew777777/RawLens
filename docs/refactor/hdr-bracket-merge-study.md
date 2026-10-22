# HDR 3-bracket merge: study (2026-10-22, in progress)

Reported (owner): blockiness, smear, square blocks around moving cars.

## Current pipeline (HdrBracketSaveJob -> HdrRawMerge), ~2,960 lines
- Alignment: global translation (HdrBracketAligner) + **FlowNet** dense flow
  (neural network, HdrFlowNetAligner), same-colour bilinear warp.
- Deghost: in-house "HDR+-style" pairwise tile Wiener toward the reference
  (HdrTileDeghost, 1,053 lines): 8x8 tiles, EXIF + median + per-tile gain
  matching, Delbracio-style magnitude preference, deconvolution lift,
  per-quad highlight handoff, frame drop on DC-reject census.
- Radiance merge: darktable `_control_merge_hdr_process` photon weighting
  (long exposure dominates wherever it is unclipped) + saturation envelope.
- Separate sequential-DCG trio path (HdrSeqMerge).
- Phone cost: align 17.1 s + merge 11.5 s for a 12 MP -2/0/+2 set.

## Likely causes (to confirm on a real set)
- Squares: hard per-tile decisions (collapse / drop / gain refinement)
  layered on top of window blending; any tile whose decision differs from
  its neighbour shows as a block.
- Smear: photon weighting gives the long (motion-blurred) frame most of
  the weight in mid/shadow tones; the deghost only removes it where its
  heuristics detect a mismatch.
- Neither layer is a published method as combined; FlowNet is the only
  dense aligner.

## Non-AI references
- **Burst Photo / hdr-plus-swift @ 69cb057** (open, GPL-3; follows Google's
  "HDR+ with bracketing" design): darkest frame as reference, every frame
  exposure-corrected (prepare_texture exposure_diff), tile alignment on a
  white-balanced pyramid, robust merge where motion falls back to the
  reference (frequency: exposure_corr robustness, highlights norm,
  bracketed max_motion_norm; spatial: same robustness weighting), linear
  or curve exposure control at the end. Our HDR+ Vulkan engine already
  reproduces its uniform-exposure merge exactly (parity runs 2026-10-22).
- darktable `hdrmerge` (control_jobs.c): radiance merge only, no alignment
  or deghosting (what HdrRawMerge.mergeExact ports).
- HDRMerge (jcelaya): per-pixel exposure selection with a feathered mask,
  no automatic deghosting.

## Proposed
Replace the bracket merge with Burst Photo's bracketed-exposure path on
the HDR+ Vulkan engine (needs the exposure-control-on pieces from
hdrplus-parity-todo.md items 1-5 plus the bracketed path, item 6), proven
against the headless Burst Photo runner on real bracket sets, then delete
HdrTileDeghost / FlowNet / photon-weight path from the bracket route.

## Needed
Real -2/0/+2 bracket source DNGs with motion (cars), saved with "save each
bracket" / debug frames.
