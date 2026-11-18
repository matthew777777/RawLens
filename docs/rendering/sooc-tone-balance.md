# SOOC shadow balance (2026-11-18)

The four supplied 2026-11-18 DNG/JPEG pairs used ACES 2.0, automatic development
exposure, and Active D-Lighting Off. Backlit foregrounds remain very dark while
large skies limit the global exposure correction. More global exposure would
also brighten the sky. A bounded, pointwise camera-rendering stage now lifts
shadows separately, before the selected reference display transform.

For the nonnegative maximum ACEScg channel `p`:

```
t = clamp((p - 0.18) / 0.42, 0, 1)
fade = 1 - t*t*(3 - 2*t)
toe = 0.06 / (0.06 + p)
gain = p >= 0.6 ? 1 : 1 + 3*toe*toe*fade
balanced = scene * gain
```

This is a shared RGB scale, including negative calibrated channels, preserving
scene chromaticity. The neutral ramp is monotonic. Black stays black; the gain
approaches 4 (two stops) only at the deepest toe. At 18% the gain is 1.1875
(about 0.25 EV), and at/above peak 0.6 the input is exactly unchanged. Existing
highlight shoulders, exposure guards and display transforms retain their bright
response. The stage is common to Basic, AgX, Sigmoid and ACES 2, independent of
DCG mode. It uses no neighborhood filter, texture, readback or additional pass.
It also affects low-light shadows; it does not denoise or recover clipped sensor
samples.

The CPU/reference output and neutral scope curve, standalone JPEG/JXL shader,
fused AMaZE shader, RAW preview tails and Halide BGU operator share this response.
The original scene remains the Ultra HDR gain-map numerator. DNG pixels and
capture exposure are unchanged. JPEG ImageDescription records the rendering
stage. Active D-Lighting's AgX range is solved using the balanced grey anchor,
so the new toe does not double-compensate its capture bias.

## Validation

- `CameraToneBalanceTest`: monotonic ramp, bounded gain, exact bright identity,
  zero pedestal, RGB ratios, all-mapper scope/output and inverse checks (excluding
  AgX's already black-clipped toe).
- Existing Active D-Lighting tests cover grey restoration and absence of a second
  automatic compensation. The AgX camera grey regression includes the new toe;
  the ACES 2 and Sigmoid reference transforms themselves remain unchanged.
- Halide host scalar/AOT parity: all 16 cases pass, maximum error 1.27e-5.
  Regenerated `bgu_look.a` for all four Android ABIs.
- glslang validates complete standalone and fused AMaZE compute shaders, plus
  the camera/ACES helper in ESSL 1.00, 3.00 and 3.10.
- External DNG review artifacts are in `/tmp/rawlens-sooc-review`. These use
  LibRaw/rawpy camera WB and AP0 -> AP1 followed by the application's reference
  ACES LUT. They isolate the toe with identical exposure before/after, but are
  **approximate host renders**, not exact Android AMaZE/calibration/meter replays.
  Source JPEGs are shown separately, with EXIF orientation applied. Dark-pixel
  average encoded levels increased from 23.0 -> 43.7, 4.2 -> 15.3, 3.3 -> 12.5,
  and 5.7 -> 15.9 codes respectively; pixels with scene peak >=0.6 are identical.
  Sensor samples at/above the DNG white tag account for approximately 0.71%,
  3.02%, 1.82% and 1.44% respectively; this includes clipped sun content.
  Personal images are not checked into the repository.
- No Android device was connected, so device rendering/runtime parity was not run.

Final host validation: full GitHub debug JVM suite: 2065 tests, 0 failures, 0 errors, 10 skipped. `:app:assembleGithubDebug` succeeded for the configured ARM64/ARMv7 APKs and universal APK.
