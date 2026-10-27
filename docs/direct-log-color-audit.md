# Direct Log color audit — 2026-10-26

## Result and limits

The ACES option is **Stephen Hill's ACES fitted approximation**, rendered to
Rec.709 primaries / gamma 2.4, with the look baked into the file. It is not
scene-linear ACES, ACEScct, ACEScg, the full ACES 1.x RRT/ODT, or ACES 2.
It cannot be described as an exact match to DaVinci Resolve's ACES rendering.
The fitted rendering intentionally changes saturation/hue in highlights; it
has no full ACES reference gamut-compression stage. Numeric correctness does
not establish color-chart accuracy for every camera/illuminant.

Resolve is installed on the host, but its scripting API returned no active
connection. No Resolve project was opened or changed. An actual Resolve
render-to-render comparison remains unperformed.

## Corrections made by this audit

- The fit returns display-linear light. The ACES shader branches now use the
  inverse gamma-2.4 display EOTF, rather than the scene-linear BT.709 camera
  OETF. The latter darkened the fitted mids/shadows on a gamma-2.4 display.
  All five split/fused and fp32/fp16 shader variants were rebuilt and validated.
- Quarter-size previews previously rounded the output dimensions to even,
  violating the native exact-box contract for widescreen dimensions. For
  example, 1920x802 could fail take initialization, and 3840x1606 is also not
  divisible by four. Preview sizing now chooses an exact box of four or two.
  RGBA preview output heights can be odd; chroma input taps remain aligned.
- The post-record stabilizer now permits the same SDR encoder fallback for
  ACES as for plain BT.709. It warps the already rendered pixels; it must not
  apply the fitted look or gamma a second time.
- Settings identify ACES fitted as a baked Rec.709/gamma-2.4 approximation,
  and identify S-Log3 as using BT.709 primaries rather than Sony S-Gamut3.

## Numeric verification

128 focused JVM regression tests passed, covering output profiles, camera
matrix math, Sony S-Log3/HLG/BT.709 functions, fitted reference anchors, grayscale
monotonicity/neutrality, saturated and negative inputs, legal-range packing,
quantized RGB/YUV round trips, crop geometry, and preview sizing.

Real-device synthetic tests executed bundled SPIR-V through production JNI,
using neutral Bayer data plus controlled color matrices and exposures. Inputs
included black, shadows, transfer knees, 18% gray, white, primaries, secondaries,
a skin-like RGB sample, a negative channel, and gains of 0, +4, and +10 EV.
All four output profiles were checked. P010 low bits were checked as well.

| GPU recording path | Channel comparisons | Max error vs CPU reference (10-bit codes) |
|---|---:|---:|
| Split grade-YUV | 468 | 0 |
| Fused MHC, fp32 | 468 | 0 |
| Fused MHC, fp16 | 468 | 2 |
| Fused RCD final, fp32 | 468 | 0 |
| Fused RCD final, fp16 | 468 | 2 |

Total: 2,340 comparisons; all passed. This validates the grade, matrices,
channel order, and legal-range packing for the tested inputs. It is not a
test of a physical ColorChecker, all scene colors, codec reconstruction error,
or accuracy relative to Resolve's full reference ACES transform.

The production recording path currently selects fp32 fused MHC. The fp16
variants were included to detect dormant-path regressions.

## File/codec checks

The first device recording pass verified these real HEVC Main10 files at 24 fps:

| Selected width / crop | Actual encoded dimensions |
|---|---|
| 1920 / 16:9 | 1920x1080 |
| 1920 / 2.39:1 | 1920x802 |
| 1920 / 2.00:1 | 1920x960 |

All had BT.709 primaries/matrix, SDR transfer signalling, limited range, encoded
frames, and no reported frame errors. Independently, macOS AVFoundation decoded
all frames of all three completed clips into 10-bit pixel buffers: 36, 34, and
34 frames respectively, at their expected dimensions. The first sandboxed
decode attempt could not access the macOS codec service; the unrestricted
retry succeeded. See results/direct-log-color-audit/desktop-decode.txt. The 1440p take began encoding, but the
matrix was interrupted by another instrumentation run. Android exit-info
explicitly records “stop ... due to start instr”, not a GPU/codec crash.
The full 1440p/4K recording matrix is pending completion. Aspect-ratio rounding
to even dimensions is expected for 4:2:0.

## Resolve interpretation

| App profile | Signal interpretation |
|---|---|
| ACES fitted | Rec.709 primaries / gamma 2.4, baked SDR look |
| Plain BT.709 | Rec.709 primaries / BT.709 camera transfer |
| S-Log3 | Rec.709 primaries / Sony S-Log3 transfer |
| HLG | Rec.2020 primaries / HLG |

For ACES-fitted clips, use **Rec.709 Gamma 2.4** as the input interpretation and
video/limited data levels (the file is tagged limited). Do not select an
ACEScg/ACEScct input transform or apply another ACES/log-to-709 LUT to the baked
clip. In a managed project, let the normal input/timeline/output conversion run.

For S-Log3, use a custom color-space transform with **Rec.709 input color space**
and **Sony S-Log3 input gamma**. A Sony S-Gamut3 or S-Gamut3.Cine camera preset
assumes different primaries and can create incorrect colors. Container SDR
transfer tags cannot express Sony S-Log3, so manual interpretation is required.

HLG uses a BT.2020 YUV matrix for recorded pixels. The existing take-monitor
kernel currently decodes all profiles using the BT.709 matrix and does not
provide an HDR-managed display conversion. The HLG monitor is therefore not
an HDR color reference; this limitation does not change the HLG file's encoded
BT.2020 matrix or tags.

## References

- [Published Hill/BakingLab fit](https://github.com/TheRealMJP/BakingLab/blob/master/BakingLab/ACES.hlsl)
- [ACES output transforms](https://docs.acescentral.com/system-components/output-transforms/)
- [ACES display encoding presets, including BT.1886/gamma 2.4](https://docs.acescentral.com/system-components/output-transforms/parameters/)
- [Blackmagic Resolve color-management guide](https://documents.blackmagicdesign.com/UserManuals/DaVinci-Resolve-17-Colorist-Guide.pdf)
