# Adjustable SOOC tone balance (2026-11-19)

The original fixed two-stop toe below is historical. Its strongest lift was in
very dark pixels, reducing perceived contrast with Sigmoid. The default now uses
20% of that lift and a smooth near-black gate from scene peak 0.001 to 0.008.
Below the gate, gain is exactly one; no pedestal is added. At middle grey the
new default adds approximately 0.05 EV rather than 0.25 EV. Bright pixels remain
unchanged by the shadow control. Both adjustments are opt-in relative to a
zeroed control: 0% shadow lift exactly bypasses the toe; 0% highlight recovery
retains the selected display transform's highlight roll-off.

Photo output settings now expose **Shadow lift** (0–100%, default 20%),
**Highlight recovery** (0–100%, default 0%), and a dedicated reset button.
Values are saved per lens and carried in the immutable development settings,
including offline merge settings. Old preferences without the new keys take the
new defaults. JPEG descriptions record the actual amounts. The common GLSL
uniform, CPU/reference transform, preview snapshots, scopes and BGU/JNI/AOT
parameters carry the same two amounts. The Ultra HDR scene tap is unchanged.

The highlight control uses the neutral flat-field stop-shift formula documented
for [RapidRAW 1.7](https://getrapidraw.com/docs/technical/tone-and-color):

```
distance = max(log2(peak) + 3, 0)
depth = distance * distance / (distance + 1)
shift = -amount * (1 - exp(-1.6 * depth))
gain = exp2(shift)
```

It starts at 0.125, lowers bright tones by at most one stop, and applies one RGB
scale before the display mapper. Unlike RapidRAW's full implementation it uses
pixel peak, not an edge-aware luminance base, and does not include its local
texture compensation or clipped-region map. It is not claimed as a full port of
RapidRAW's local Highlights module. It cannot reconstruct all-channel clipping.
The meter's broad-highlight guard is evaluated with recovery disabled so turning
the slider up cannot buy global exposure and undo its effect.

Sigmoid itself retains the generalized log-logistic response from
[darktable 5.6.2](https://github.com/darktable-org/darktable/blob/release-5.6.2/src/iop/sigmoid.c),
including the original contrast 1.5, zero skew, black target and hue correction.
No extra Basic/AgX shoulder is inserted into Sigmoid.

Validation of this revision includes neutral flat-field reference values,
monotonic ramps over both slider endpoints, exact bypass/black protection,
all-mapper scope/output/inverse consistency and unchanged automatic highlight
guards. Device preview parity now sweeps both sliders; output GPU/CPU parity
covers all mappers at off/default/maximum combinations. Halide parity covers
default controls and maximum recovery/lift, and all four ABI archives are
regenerated.

The original personal DNG/JPEG paths under Downloads/images are no longer
present in this session; the earlier review JPEGs remain under /tmp. No claim is
made that the revised response was re-rendered against those unavailable RAWs.

Final revision validation: 2,077 JVM tests, zero failures/errors, 10 skipped
(2,067 passed); the isolated device-test app and instrumentation APK built.
All 14 selected phone tests passed: six scene-linear/fused output checks,
seven BGU/JNI checks, and the preview sweep over both sliders. The shared
standalone/fused shaders and GLES 2/3/3.1 helper compile; all 16 Halide host
parity cases pass with maximum absolute error 1.27e-5.

## Original fixed toe implementation (2026-11-18, superseded)


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
