# Production clip-only RAW merge

Settings → General → RAW capture → Experimental two-exposure RAW HDR
replaces the former DUAL RAW quick-control tile. It is off by default and
session-only. The test submits a short-first, same-ISO exposure bracket on camera 0.
The long frame targets metered brightness, capped at 1/30 second; the short frame
targets one quarter of its exposure. Sensor limits may reduce that separation;
unsupported pairs fail rather than silently use incorrect normalization.
This is sequential RAW HDR, not sensor DCG.

For CFA phase c, use actual CaptureResult exposure times and per-frame dynamic
black/white levels (static characteristics only as fallback):

    gain = tLong / tShort  (verified equal returned ISO)
    longRange[c] = whiteLong - blackLong[c]
    longValue = (DNLong - blackLong[c]) / longRange[c]
    shortValue = (DNShort - blackShort[c]) * gain / longRange[c]

If DNLong < whiteLong, use longValue alone. There is no blend or pre-clipping
ramp. Otherwise use shortValue; if both samples clip, mark output saturated.
Do not normalize the short frame by its own white range before applying gain.
Do not clamp negative black-corrected values prematurely.

Reserve headroom H = max(1, max_c gain*(whiteShort-blackShort[c])/longRange[c]).
Encode round(1024 + 64511*value/H) into unsigned 16-bit, clamping only at the
storage endpoints. DNG black=1024, white=65535; add log2(H) to BaselineExposure.
Keep calibration/WB/crop/lens metadata and remove stale source NoiseProfile.
The writer appends a replacement root IFD without moving pixel/calibration data.

Camera AHardwareBuffers remain imported directly into Vulkan. No CPU RAW upload
or extra input copy was introduced. The merged output is still copied for DNG
writing; this is zero-copy input, not end-to-end zero-copy file output.

RAWBracket 0.2.5 informed the clip-only policy, but its RGB luminance/max/WB-cap
reconstruction is not copied into this linear Bayer path. Its optional empirical
ratio refinement is not implemented: same-ISO capture avoids the ISO-to-effective-
gain uncertainty, while actual exposure metadata supplies the ratio. Sensor
nonlinearity, lighting flicker, inaccurate vendor metadata and motion can still
produce errors. No alignment or deghosting is performed. The 16-bit container
preserves recovered headroom; it does not create 16-bit sensor color precision.

References:
- https://developer.android.com/reference/android/hardware/camera2/CaptureResult
- https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html
- https://jcelaya.github.io/hdrmerge/

Validation: planner and TIFF metadata unit tests (both byte orders). Device test
DualRawMergeInstrumentedTest invokes the production Vulkan shader and checks
CPU/GPU agreement plus exact invariance of every unclipped long pixel when short
black subtraction is perturbed by 100 DN. It writes diagnostic files only to the
app external-files dualraw-validation directory; pair 1 uses an artificial long
white level to exercise clipping. Device execution awaits ADB reconnection.
