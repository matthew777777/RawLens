# Remaining fixed-axis green interpolation zipper

Checking the matching broad-leaf/fence and balcony/window regions after
1e4ec4c1 showed that removing inverse-filter sharpening reduced the problem
but did not remove all of the repeated contour pattern. The remaining sampler
still interpolated green on a fixed diagonal Bayer lattice. Near-horizontal
and near-vertical edges were reconstructed across that edge rather than along
its locally smooth direction. This leaves Bayer-phase contour bias even with
exact constant translations and no sharpening boost.

Controlled checks retained the same flow, colour differences and six-frame
grain policy. Monotone interpolation on the same diagonal lattice made little
visual difference. Averaging all frames reduced some pattern but also changed
grain; it was not adopted. Edge-directed reconstruction reduced the balcony
pattern with the grain setting unchanged.

The new sampler preserves measured green sites. At missing sensor-grid sites,
compute horizontal and vertical neighbor means and blend them with continuous
positive inverse-squared local-difference weights. Prefer the direction that
varies least without a hard direction switch. Then interpolate the complete
green grid with the existing unboosted cubic and support-range clamp. Sensor
border reads preserve CFA parity. No spatial grain mask, extra sharpening,
additional resident image or JNI change is introduced. Legacy/non-detail paths
keep their prior calculations.

Validation:

- New GPU KernelNetAxisEdgeTest integrates a known nearly vertical/horizontal
  edge over sensor pixels and reconstructs six exactly translated Bayer frames.
  The previous committed sampler fails at 1x (RMS 0.03744, maximum 0.03).
  The new sampler gives vertical RMS 0.02008/0.02086 at 1x/2x and horizontal
  RMS 0.02090/0.02108, with the same test threshold at both scales.
- Forced final pipeline test run: 273 tests, 197 executed, 76 skipped, zero
  failures/errors. Existing diagonal contour, texture gain and source-grain
  tests pass without relaxing their thresholds in this change.
- Native flow oracle updated for the new directional sampler; affine-intensity
  reproduction, border handling, gather continuity and unchanged reference/
  chroma paths remain covered.
- Grain-retention noise RMS ratios remain approximately 1.60 in flat regions
  and 1.66-1.67 in textured regions at both scales, versus full-frame averaging.
- Reprocessed all frames of the matching Paris balcony crop (2176,1024,512,512)
  at 2x, garden leaves/fence crop (2048,2048,768,768) at 1x, and the garden
  fence/foliage crop (2304,768,512,512) at 2x. Visual comparisons show fewer
  repeated contours with a more natural texture. Crop processing has different
  border/flow context from a full-frame merge; full outputs were not regenerated.

Review artifacts:
- /tmp/srp-directional-compare.png (balcony: committed sampler vs directional)
- /tmp/srp-exact-leaves-final-compare.png (matching plant region: prior saved
  output vs final sampler; includes the preceding sharpening removal)
- /tmp/srp-foliage-final-comparison.png (boosted, unboosted diagonal, directional)

This fixes a measured interpolation contributor, not a guarantee that every
subpixel alias or every possible motion artifact in a burst is eliminated.
