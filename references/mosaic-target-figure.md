# Mosaic target figure (T3 reference — do not implement yet)

User-supplied concept figure for the mosaic SR path (2026-09-23). Filed here
so the T3 audit can check our reconstruction against it pixel-path by pixel-path.

## What the figure shows

- **Left — "1 FRAME · SPARSE"**: one Bayer frame, one color per site, gaps everywhere.
- **Middle — "11 FRAMES · SUB-PIXEL"**: eleven frames overlaid at their true
  sub-pixel offsets — the plane fills with real measurements.
- **Right — "2× · RESOLVED"**: resolve onto a 2x grid → dense RGB, no
  interpolation guess.

Caption: *"Left one frame – sparse, one color per site, gaps everywhere.
Middle eleven frames overlaid at their true sub-pixel offsets – the plane
fills with real measurements. Right resolve onto a 2x grid → dense RGB,
no interpolation guess."*

## Deltas vs our current MosaicSr path (for the T3 audit)

1. **Output scale**: the figure resolves onto a **2x** grid; our
   `MosaicSrReconstructor.LINEAR_SCALE` is **√2** (area doubling). Decide:
   match 2x, keep √2 with justification, or make scale selectable.
2. **Output domain**: the figure resolves to **dense RGB** directly from the
   overlaid measurements. Our mosaic outputs a **re-mosaicable Bayer CFA**
   for external demosaic (RawTherapee), i.e. we still interpolate later.
   The figure's "no interpolation guess" claim requires the resolve step to
   emit all three channels per site from gathered same-scene samples —
   audit whether our same-colour-only gate + external demosaic meets that
   bar, and what fills cross-colour sites at resolve time.
3. **Coverage gating**: the middle panel implies a coverage precondition —
   resolve only where sub-pixel overlays actually filled the plane. Our
   chain has no phase/coverage gate before reconstruct (any `Rc` + fallback
   stands). T3 must decide the coverage metric and the fallback where the
   plane did not fill.
4. **Frame count**: figure shows 11 frames; our `MAX_MERGE_FRAMES = 30`,
   typical bursts smaller. Coverage math must scale with N, not assume 11.

## Audit outcome (T3 implementation, 2026-09-23)

Figure conformance fixes shipped (unit suite 706/706):

- **R1 — CFA phase routing**: the merge routed `shifted(origin).colorAt(origin
  + tap)` (R/B swap + wrong target CFA tags on odd crop origins; external
  demosaics then invent chroma). Now crop-relative throughout
  (`RawSrBayerMerge`, mosaic kernel gate + nearest gate, `planTarget`
  takes the origin-shifted phase verbatim). Odd origins covered by the
  all-patterns/all-origins merge tests.
- **R2 — fallback quality**: unsupported sites preferred single-tap
  burst-nearest (jagged/blocky where the plane never filled). Now the
  reference kernel mean (smooth, honest single frame) wins wherever the
  reference kernel has support; nearest survives only where it has none.
  Pinned by `unsupportedSitePrefersReferenceKernelOverNearest` and the
  strengthened overwrite test (unsupported-everywhere output equals
  ref-only output exactly).
- **R3 — censored nearest**: mosaic nearest admitted clipped taps (white
  fallback dots). Now censored like the kernel loop; pinned by
  `censoredNearestTapNeverBecomesFallback`.
- **Deliberately unchanged**: √2 scale (figure shows 2x — product decision,
  needs device A/B; 2x quadruples area against the 512 MB heap budget),
  single-sample CFA output domain (figure shows dense RGB — same decision),
  `clampMinorAxis` zipper guard, bilinear precision interpolation (matches
  Jamy-L `merge.py`), smooth flow (Jamy-L uses nearest-tile; ours avoids
  16px quilts by design).
- Open scale/phase-coverage follow-ups from the figure: explicit
  phase-diversity coverage gate before resolve (today: Rc overwrite only),
  2x-grid evaluation.

## Audit entry points (existing code)

- `MosaicSrReconstructor.kt` — `planTarget` (grid geometry/phase),
  `accumulateFrame` (same-colour gate, kernel splats), `refSiteTap`
  (censored-site rule), eager-vs-streaming finalizers.
- `RawSrMergeJob.kt` — `mosaicChain` / `mosaicStream` (no coverage gate today).
- `RawSrRobustness.kt` — per-quad `r` (the only density signal; is it enough
  for a coverage gate?).
- References: `references/Handheld-Multi-Frame-Super-Resolution-Jamy-L`
  (`merge.py`, `robustness.py`, `kernels.py`), GCam Sabre notes (mosaic-domain
  merge output + post-merge `demosaic_raw`), PhotonCamera `anisoupscale`.
