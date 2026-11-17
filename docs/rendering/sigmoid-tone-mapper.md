# Sigmoid tone mapper

Third still-photo tone mapper beside Basic and AgX. The same CPU reference and GLSL function are used for JPEG/JXL export, fused Bayer export, and both viewfinders. Persisted value: `SIGMOID`; existing Basic/AgX preferences retain their meaning.

Reference: darktable 5.6.2, commit `20891f6c6fa7be995e5bf9dff9d1ee5062051773`, `src/iop/sigmoid.c`, GPL-3.0-or-later, copyright darktable developers (2020–2026). The port uses the default per-channel generalized log-logistic response, contrast 1.5, skew 0, display black 0.0152%, white 100%, and full hue/energy preservation. No primaries attenuation or rotation; working primaries are Rec.2020 D65. The analytical zero-skew solution avoids the upstream finite-difference estimate of contrast, whose rounding can vary by platform. This is not an exact port of the complete darktable workflow.

The curve maps 0 to 0.000152, 0.1845 to itself, and infinity to 1. Negative input primaries are desaturated around their channel average before the curve. Hue correction preserves the input channel ordering and relative middle-channel position, with energy correction matching upstream's algorithm. Output converts from Rec.2020 to sRGB or Display P3 before final clipping/OETF. Basic/AgX's additional shoulder is not applied to Sigmoid.

Exposure remains a separate control. darktable's scene-referred raw default exposure module adds +0.7 EV and a small negative black offset; neither belongs in the tone curve. To compare the supplied branch DNG with darktable's default render, use manual +0.7 EV. Camera calibration, cleanup, white balance and other modules can still cause differences.

Headless checks: curve anchors and monotonicity, hue/order/neutrality/negative-primary tests; optional external-DNG CPU/GPU comparison; fused cleanup versus standalone cleanup; current preview shader versus the unchanged pre-Sigmoid preview shader. The historical preview performance fixture predates subsequent AgX changes and differs from current HEAD; use the unchanged HEAD shader to isolate this change.

Investigation: the supplied DNG's RawTherapee 5.13 neutral output is explained by a pointwise matrix plus sRGB OETF (fitted linear RGB RMSE 2.44e-6). Our camera matrix, after accounting for exposure, agrees closely. Cleanup disabled still reproduces the reported Basic appearance. This rules out claiming a matrix or defringe fix from these controls alone. RapidRAW Basic also includes default preprocessing; its supplied edited export remains the comparison target for the separate Basic investigation.
