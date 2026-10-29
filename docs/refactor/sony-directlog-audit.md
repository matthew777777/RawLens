# Sony DirectLog colour audit, 2026-10-28

## Scope and acceptance status

This implements the I1 numeric correction and the J1 subset required by I2/I4:
an independently implemented 3D `.cube` parser, private user LUT library,
selection/removal/import UI, intensity saved per LUT, and S-Log3 **take-monitor**
rendering. Optional recorded LUT baking is also implemented and numerically
tested: off retains S-Gamut3.Cine/S-Log3; on saves the LUT result as an SDR
`_LUT709` file. Its strict sustained-cadence acceptance is still open. Before
recording, the existing RAW viewfinder remains; it has no pre-record LUT preview.

I1/I2 remain open for the physical chart/grey-ramp take and reference-tool
render acceptance required by the original checklist, and a real Sony-file
metadata comparison. Numeric tests are not physical camera calibration.
The source-derived transform does not establish accuracy of every phone's
sensor matrices or illuminants. I3 stays open by owner reply 2026-10-28:
no Sony camera/Catalyst reference is available. No S-Cinetone fit or profile
has been invented. J1 remains open for final performance acceptance and the
before-record preview limitation is explicit.

## Sources and decisions

| Rule | Primary reference | Implementation and decision |
|---|---|---|
| S-Gamut3.Cine chromaticities | [Sony Technical Summary v1.00](https://www.sony.jp/ls-camera/knowledge/pdf/TechnicalSummary_for_S-Gamut3Cine_S-Gamut3_S-Log3_V1_00.pdf), final page, visually inspected | R (.766,.275), G (.225,.800), B (.089,-.087), D65 (.3127,.3290). `tools/generate_sgamut3_cine.py` derives RGB-to-XYZ, solves primary scales against D65, inverts and composes with the published sRGB/709 primaries. No CAT between identical D65 whites. |
| Sony curve and inverse | Same Sony summary, appendix | `VfLogGrade.sonySlog3` / `invSonySlog3`; all seven GLSL paths. Toe extends linearly below zero, as Sony's lower branch specifies. No intermediate nonnegative clamp after CCM or gamut conversion. |
| Rec.709 source primaries/OETF/YUV | [ITU-R BT.709-6](https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.709-6-201506-I!!PDF-E.pdf), tables 1 and 2 | Retain existing developed-linear-sRGB input, BT.709 YUV coefficients, and legal-range pack. No change to BT709/HLG/ACES rendering. |
| File code range | Sony summary FAQ Q4 and appendix | Sony describes full/extended-range files. RawLens intentionally retains its existing limited-range HEVC pipeline for device compatibility. Differences and exact interpretation below. |
| `.cube` syntax/order | [OpenColorIO v2.5.2 IridasCube format description](https://github.com/AcademySoftwareFoundation/OpenColorIO/blob/v2.5.2/src/OpenColorIO/fileformats/FileFormatIridasCube.cpp), introductory format comment | Independently written Kotlin, no copied parser. RGB triples, red-fastest lattice, domain defaults [0,1], optional TITLE/DOMAIN_MIN/DOMAIN_MAX. App policy: size 16–65, 32 MiB import limit, 8192-character lines, exact row count, finite half-float-representable outputs, no mixed/1D LUT or unknown extension accepted. |
| LUT texture interpolation | [OpenGL ES 3.0 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/es_spec_3.0.pdf), texture filtering | Optional GLES3 RGB16F 3D texture, GL_LINEAR (trilinear), clamp to edge. Coordinates map lattice endpoints to texel centers. CPU golden uses the same interpolation. Not tetrahedral. |
| Independent render | [FFmpeg lut3d](https://ffmpeg.org/ffmpeg-filters.html#lut3d-1) | `tools/check_direct_log_lut.py` compares device CPU/GPU exports against explicitly trilinear FFmpeg output. Supply float-planar input normalized exactly as GLES UNORM8; FFmpeg 9.0.2 swscale RGB8-to-float conversion was measured to bias those inputs. |
| Official monitoring LUTs | [Sony LUT guide](https://helpguide.sony.net/di/pp/v1/en/contents/TP1000756721.html), [Sony LUT downloads](https://sony-cinematography.com/resources/luts/), [BURANO LUTs](https://pro.sony/en_DJ/technology/cinealta-burano-luts) | User imports matching S-Gamut3.Cine/S-Log3-to-709 cubes. Sony files used for testing stay outside the repo/APK. Creative tints are preserved. |
| S-Cinetone | [Sony white paper](https://sony-cinematography.com/articles/s-cinetone-whitepaper-version-1/) | Qualitative characteristics do not supply a complete numeric tone/gamut transform. Do not claim proprietary rendering parity without the required reference. |

## I1 pipeline

Developed signed linear sRGB -> generated S-Gamut3.Cine matrix -> Sony S-Log3
-> optional user LUT -> BT.709 YUV matrix -> existing limited P010 pack.
The matrix rows are:

```
0.6456794776  0.2591145470  0.0952059754
0.0875299915  0.7596995626  0.1527704459
0.0369574199  0.1292809048  0.8337616753
```

One generator owns the primary definitions and emits `SonyGamut.kt` plus
marked GLSL blocks. Regenerate with `python3 tools/generate_sgamut3_cine.py`.
Rebuild affected SPIR-V using glslangValidator `-V --target-env vulkan1.1`,
then spirv-val with the same target. Assets rebuilt/validated: grade-YUV,
MHC fp32/fp16, RCD-output fp32/fp16, Packed RCD output, and probe loggrade.
The CPU golden now calls `SonyGamut.fromLinearSrgb` before Sony encoding.
All existing profile wire IDs, persisted names and HUD cycle order remain.

The old divisor-.4625 function is renamed `workingLog`, and its inverse
`inverseWorkingLog`. It remains dormant working-space code for contrast/LUT
baking and cannot be mistaken for the Sony output transfer. No curve values
or record behaviour of the other profiles were intentionally changed.

Signed gamut channels retain the linear toe even if the resulting signal
is below the 95-code pedestal. Values sufficiently far below black can
produce negative signals; final YUV storage still saturates to its finite
range. This is a stated storage limit, not a claim of lossless representation
of arbitrary negative RGB. Tests include negative channels that survive
the wide-gamut conversion, not only negative source channels that become
positive after it.

### Codes and post-production interpretation

For a neutral, Sony normalized signal `s = SonyCode / 1023`. RawLens stores
`Y = round(64 + 876*s)` (P010 shifts this code left six bits). Decoding at
Video/limited levels yields `(Y-64)/876`, recovering `s` within quantization.
Neutral chroma is code 512. Chroma uses nominal 64–960. The table refers
to uncompressed P010; the codec can add reconstruction error.

| Linear reflectance | Sony code before pack | RawLens stored neutral Y | Limited-decoded signal |
|---|---:|---:|---:|
| 0 | 95 | 145 | .09246575 |
| .01125 | 171.21029 | 211 | .16780822 |
| .18 | 420 | 424 | .41095890 |
| .90 | 597.89526 | 576 | .58447489 |

These files **do not carry Sony camera luma codes directly**. Do not force
Full data levels to recover Sony's pedestal; that applies the wrong
normalization to this limited-range file. SonyCode anchors describe the
curve, not RawLens's post-pack raw Y integers.

* DaVinci Resolve: Clip Attributes -> Data Levels **Video**. For a technical
  CST use input colour space **Sony S-Gamut3.Cine**, input gamma **Sony S-Log3**;
  choose the desired timeline/output. Alternatively use a matching Sony
  log-to-709 LUT on the decoded log signal. Do not apply both the input CST
  and the same log-to-709 LUT in series.
* Premiere: manually interpret/override the input as **Sony S-Gamut3.Cine /
  S-Log3** and retain the limited-range decode. For an unmanaged workflow,
  apply the matching Sony cube as the input conversion. Avoid adding a
  second automatic log conversion. Menu names differ across versions;
  an actual Premiere render comparison has not been performed.

`targetColors()` intentionally remains BT.709 matrix/SDR transfer/limited.
Android's standard colour keys do not describe these Sony primaries/log.
Those tags describe the transport matrix/range; automatic SDR interpretation
will look wrong. Actual RawLens HEVC files have been checked, but comparison
with a real Sony camera file remains open. Earlier RawLens SLOG3 recordings
used Rec.709 primaries and require their earlier interpretation; the old
wire ID/filename suffix alone cannot distinguish the two generations.

## I2/J1/I4 import and take monitor

Settings -> Video -> Direct Log has choose, import, intensity and remove
controls. Import uses Android's document picker and a bounded copy into
private storage; complete parsing precedes publication. Up to 16 imported
cubes, with their own intensities, persist across activity/process restarts.
The source file is not edited. The picker accepts a `.cube` file, not a ZIP;
extract ZIP packs first. Import errors leave the existing selection intact.

The monitor configuration freezes at take start. Parsing and GL upload run
on the display worker, never on camera/copy workers. Only the optional
assisted monitor requests GLES3; off retains the existing GLES2 blit. An
unsupported/failed assist reports a toast and falls back to unassisted log;
it does not change recording. Source sampling is explicitly highp: the
first device pass exposed up to three codes of error with the default
sampler precision, and highp removed that discrepancy.
For baked recordings the monitor instead displays the already baked P010
pixels, without applying its input LUT again.

The monitor consumes the existing downsampled RGBA8 staging image. This is
an 8-bit SDR aid, not a 10-bit grading reference. LUT values are retained
as half-float texels; output clips at the SDR display surface. The built-in
technical assist uses analytic inverse Sony log -> inverse gamut -> BT.709,
with hard display clipping; it is not s709 or S-Cinetone. A coarse LUT of
that inverse introduced neutral interpolation bias during development and
was replaced by the analytic transform. User cubes use their own supplied
rendering, including intentional creative tints.

### User's BURANO-LUTs.zip

Verified archive contains four S-Gamut3.Cine cubes (`SL3SG3CtoCool_709`,
`SL3SG3CtoTealOR_709`, `SL3SG3CtoVintage_709`, `SL3SG3CtoWarm_709`) and four
S-Gamut3 cubes (`SL3SG3to...`). Use the **SG3C** files for RawLens I1 output.
All four are 33-grid, parse successfully and pass actual GPU/FFmpeg numeric
comparison within 1 RGB8 code/channel on the tested inputs. The SG3 files
use different input primaries and are not corresponding RawLens transforms.

## Verification and remaining gates

Evidence lives in `results/sony-directlog-2026-10-28/`.

* Host: 1,712 tests, zero failures/errors, six existing skips. Includes
  white preservation, primary patches, negative toe/inverse, parser ordering,
  domain remapping/clamping, signed output, intensity, malformed/truncated
  files, BOM/comments and 65-grid LUTs.
* Device: split/MHC/RCD fp32/fp16 synthetic colour comparisons, including
  all profiles and signed gamut cases; Packed RCD separate/fused/scaled
  and shading checks. See final numeric report for counts and tolerances.
* Official Sony s709 V200 and all four supplied BURANO SG3C LUTs were
  compared to an independent FFmpeg trilinear render. Hashes and measured
  errors are retained. Display intensity 0/.5/1 and analytic-assist rendering
  are checked against CPU on real GLES3.
* Nine 24-fps-configured HEVC Main10 S-Log3 resolution/crop combinations
  recorded and passed metadata/frame-error checks. FFmpeg independently
  decoded every frame of all nine files without decode errors. This checks
  functioning modes, not sustained 24-fps sensor throughput or thermal soak.
* First combined GPU-stress/recording run hit the existing 100 ms copy-fence
  deadline once at 1440p; all nine passed on a separate recording-only run.
  The deadline was not relaxed or hidden. No original-build performance
  comparison has established whether that transient is pre-existing.

Outstanding: physical chart + grey-ramp take, reference render of that take,
real Sony container comparison, original-build/device performance parity,
and broader-device checks. E22 is still parked. Excluded algorithms remain
HDR+, MFSR, AMaZE and HL/highlight reconstruction; no parity claim or change
to them is made by this work.

## Resumed performance checks and J1 recording extension

`uploadShadeMap` now compares the incoming half-float map with the previous
map in the same fence-protected slot. Identical initialized images skip the
staging write/flush/transfer submission; dimensions and active-area parameters
are still updated. Changed maps follow the existing upload and barriers.
The four opt-in Packed RCD fused/scaled/asynchronous/shading tests passed
with `-e rawlensPackedRcd true`. A run without that flag only exercises JUnit
assumption skips and is not accepted as GPU evidence.

The resumed 30-second, shading-enabled S-Log3 tests with a real foreground
monitor and audio passed all three targets:

| Output / request | Muxed frames | Complete steady seconds | Largest steady gap | Drops / starves / frame errors |
|---|---:|---|---:|---|
| 3840x2160 / 24 | 707 | 27, each 24 frames | 41778 us | 0 / 0 / 0 |
| 2560x1440 / 30 | 884 | 27, each 30 frames | 33434 us | 0 / 0 / 0 |
| 1920x1080 / 30 | 884 | 27, each 30 frames | 33434 us | 0 / 0 / 0 |

These are measured passes, not an unconditional thermal/device guarantee.
Later back-to-back tests of the J1 candidate missed cadence with LUT baking
both enabled and disabled, despite zero recorder drops. Evidence retains both
sets. At the later check the HAL reported SoC 55.841 C / battery 41.2 C,
compared with 48.145 C / 38.5 C before the resumed runs; Android thermal
status remained zero. Temperature alone has not been established as the cause.
Do not call the final candidate performance-accepted on the earlier passes.

J1 now also provides **optional recorded LUT baking for S-Log3 input**:

* The selected user cube and intensity freeze before streaming. Its RGBA16F
  3D texture and float32 domain/intensity UBO upload once on the Vulkan queue,
  before the camera opens. Native input validation rejects malformed sizes,
  domains and non-finite texels. No per-frame LUT upload or CPU image copy.
* A separate descriptor set is shared by split grade, fused MHC, legacy RCD
  and Packed RCD output stages. The cube is sampled in the existing output
  dispatch after Sony encoding. LUT-off uses the original shader variant.
  Rebuild optional assets with `bash tools/build_record_cube_shaders.sh`.
* Settings exposes the baking checkbox; other source profiles ignore it.
  Baking requires an imported S-Gamut3.Cine/S-Log3-to-SDR-Rec.709 cube; Off
  and the technical monitor assist cannot be baked. Bad selections fail before
  the current camera preview is stopped. LUT intensity mixes the encoded
  input and the LUT result; at zero it retains the flat input appearance.
* Baked filenames end in `_LUT709`, the HUD identifies LUT709, transport tags
  remain BT.709/SDR/limited, and stats/stabilization identify the output as
  BT.709. The take monitor displays already baked pixels without a second LUT.
  For Sony s709, interpret the baked result as Rec.709/gamma 2.4, not log.
* CPU/GPU tests passed at intensity 0/.5/1 for official s709 and a generated
  signed-output/custom-domain cube through all five recording shader variants;
  LUT-off regressions and Packed RCD fused/scaled/shading checks also passed.
  This is 18,900 P010 channel comparisons, plus the Packed RCD checks. Gates
  are <=1 ten-bit code for fp32 and <=4 for fp16, not a claim of bit identity
  with arbitrary 32-bit cube values. The native descriptor-retirement BUSY
  response is retried by the synthetic fixture; production cadence tests do
  not retry away dropped frames or missing sensor intervals.
* Four real take-monitor cases (off, technical, imported, imported+baked)
  passed; baked takes report BT.709 output and unbaked takes SLOG3.

The manager still imports individual extracted `.cube` files, not ZIP packs.
The before-record RAW viewfinder still has no LUT preview. Physical I2 chart
acceptance, real Sony camera-file comparison and final candidate sustained
cadence remain open. I3 remains deferred by the owner. E22 remains parked.


## Release closure, 2026-10-29

The owner requested software closure without a chart. Physical grey-card/colour-chart
acceptance remains pending, separate from completed software implementation. I3
remains deferred because no Sony/Catalyst reference is available.

The final 30-second LUT-off runs passed at 3840×2160/24, 2560×1440/30 and
1920×1080/30. Baked-LUT runs passed at 4K/24 and 1080p/30. The 1440p/30
baked run failed the strict timestamp gate with a 66,689 µs interval despite
zero recorder-reported drops; an isolated repeat also failed. These results
do not establish unconditional zero-drop parity. Logs are retained in
`results/sony-directlog-2026-10-28/2026-10-29-final-*`.
