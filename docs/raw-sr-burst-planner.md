# RAW SR burst admission (2026-09-10)

`RawSrBurstPlanner` performs CPU admission before GPU allocation. It reads duplicate RAW buffers,
samples at most approximately 64 by 64 Bayer quads, and returns source indices, accepted indices,
rejection reasons, and a captured reference index. It never closes or transfers Images.

Reference priority is valid input, stable captured AF/AE/lens state, angular travel over exposure
plus rolling-shutter readout, sampled sharpness, distance from median timestamp, then timestamp
and input index. Missing AF/AE/lens values rank below known stable values.

V1 permits a maximum 1.10 ratio independently for exposure time and ISO. Dynamic black levels may
differ provided each frame has valid normalization; white-level encoding must match. Uncorrected
lens shading requires a valid positive gain map and active area. These are conservative RawLens
admission choices, not thresholds claimed by Wronski or IPOL.

Sampled saturation at normalized 0.98 or higher may occupy at most 10% of sampled sensor sites.
Global translation search uses bounded thumbnail support; a boundary optimum, displacement above
32 RAW pixels, inadequate texture, or residual above 0.12 is rejected. Sparse sampling cannot
prove absence of local motion or detect every large aliased displacement. Per-pixel robustness
remains a later mandatory merge stage.

Sources: supplied Wronski paper section 3 (burst input/base frame), supplied IPOL registration
description, and Jamy-L `handheld_super_resolution/super_resolution.py` (separate reference and
comparison inputs). None supplies Camera2 admission policy directly.

The controller retains ownership of the entire original selection. With fewer than two eligible
frames it saves the chosen reference with a one-frame fallback label. If none is eligible, it
attempts the existing temporal-middle source diagnostic with a no-eligible-frames label. Even
eligible bursts still produce reference diagnostics until fusion and output work is implemented.
