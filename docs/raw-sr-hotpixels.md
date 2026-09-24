# Hot-pixel pre-mask (normative)

Sabre analogue: `suppress_hot_pixels_bayer`. A stuck sensor tap carries
no scene signal, yet one tap corrupts every downstream stage: kernel means
smear it into neighbours, the burst-nearest fallback re-emits it, and the
third-party demosaic reads it as chroma. This stage marks such taps so the
guide rails their quads and fusion reads inpainted samples instead. Both
polarities are gated: stuck-bright taps read as hot colour dots,
stuck-dark taps as complementary (e.g. yellow) dots, and DNG forensics show
both phase-locked to the sensel lattice in comparable counts.

## 1. Detection (code domain)

`RawSrHotPixel.detectPacked` reads raw sensor codes — the same integer inputs
the GPU `hot_mask.glsl` pass consumes, so oracle and GPU agree exactly on
unambiguous spikes.

Per tap, against its **5x5 same-phase ring** (eight taps at even offsets
`(±2,0)`, `(0,±2)`, `(±2,±2)` — a 3x3 window holds zero same-colour neighbours
under a 2x2 Bayer repeat, so the ring is the smallest phase-exact
neighbourhood for any pattern or crop origin):

- ring = distinct in-bounds taps only (no clamp duplication);
- bright: `code − ringMean > gate AND code − ringMax > gate`;
- dark: `ringMean − code > gate AND ringMin − code > gate`;
  with `gate = max(HOT_SIGMA·σ(code), HOT_ABS_FLOOR·white)`,
  `HOT_SIGMA = 6.0`, `HOT_ABS_FLOOR = 0.01`, `σ` from the per-phase
  `S·code+O` model;
- the extremum arms are load-bearing: a stuck tap is the local extremum of
  its colour plane, while texture and step edges routinely clear a mean-only
  gate on one side. Uniform blocks (whose interior taps are not local
  extrema) never flag — saturation rail already covers near-white ones;
- fewer than `MIN_RING_TAPS = 4` visible ring taps (exact crop corners) never
  flags; null/invalid/zero-noise profiles yield an empty mask — no model, no
  gate, never a fabricated flag.

The floor is 1% of white, not 2%: at low ISO the photon sigma is sub-code,
so the floor governs, and 1% still towers over read noise while catching
weak warm/cool taps that tone mapping would lift into visible dots.
Known limitation: a 1px dark wire with an all-dark ring neighbourhood reads
as defective — the AND-gate restricts this to isolated spikes, and
inpainting a true wire costs one pixel of its line; accepted, documented
here.

## 2. Consumption

- **Rail:** `linearGuide` folds the mask into `rail` (a hot quad always
  rails) and records it in `hot`; `evaluate` / `robustness.glsl` force
  exact-zero weight through the shared rail gate with the distinct
  `FLAG_HOTPIXEL = 512` (stuck tap vs saturated highlight).
- **Inpaint:** `inpaintNormalized` / `hot_inpaint.glsl` replace masked taps
  with their unmasked finite same-colour ring mean **before** fusion, so the
  pyramid, guide, kernel means, and nearest path all read clean taps while
  the rail gate still zeroes the quad's weight. Replacements read the
  original plane (clusters cannot feed each other); a tap whose whole ring
  is masked keeps its sample visibly instead of being smeared.
- **Reference included:** the reference inpaints like every frame (it merges
  with `r = 1` and has no robustness gate to hide behind).

## 3. Guarantees

- One detection per frame, shared by guide and inpaint
  (`RawSrMergeJob.buildMovingFrame` / `buildReferenceFrame`; the GPU keeps
  one transient mask per moving frame plus the burst-persistent reference
  mask for the robustness gate).
- Legit point detail is safe: agreement across frames still merges (only the
  quad weight zeroes, and only where the tap disagrees with its own colour
  neighbourhood at 6σ).
- Pixel-affecting change: Linear `RawLens-RawSr/4E-scale1`, Mosaic
  `RawLens-MosaicSr/5D`.
