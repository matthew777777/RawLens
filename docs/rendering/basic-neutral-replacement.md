# Basic Neutral v2 (2026-11-18)

Basic now uses a photographic neutral response in place of the RapidRAW
brightness-gamma/cubic-contrast display curve. Persisted `BASIC` choices select
this replacement; the default mapper remains Sigmoid. Existing JPEGs are unchanged.

## Research and choice

- [Google Pixel HDR+](https://research.google/blog/live-hdr-and-dual-exposure-controls-on-pixel-4-and-4a/)
  uses local tone mapping for simultaneous subject and sky detail. A local operator
  is a larger architectural change and needs edge/detail validation; adding an
  unvalidated spatial gain field is inappropriate for a branch-halo report.
- [darktable Sigmoid](https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/sigmoid/)
  offers smooth per-channel roll-off and hue correction. RawLens already provides
  this as its separate Sigmoid option.
- [Khronos PBR Neutral](https://github.com/KhronosGroup/ToneMapping/blob/180b1a7bddec33f73fe41712a2963cc3ad8e5547/PBR_Neutral/README.md)
  supplies a small analytical, continuous highlight compressor and hue-preserving
  path to white, also implemented in Google Filament. This is the basis of Basic's
  replacement. It is a graphics operator adapted here, not a camera manufacturer's
  proprietary picture profile or an exact PBR Neutral implementation.

The old Basic curve raised scene 0.18 to code 0.493 (sRGB alone: 0.461), and
its cubic contrast blend reduced available bright-tone contrast after the shoulder.
Previous controlled branch investigation (see basic-tone-mapper.md) also found
exposure lift could produce pale twigs even with cleanup disabled. These facts
support removing that response; they do not prove all observed halos originate
in tone mapping. Sharpening, reconstruction and demosaicing remain separate stages.

## Contract

Convert calibrated ACEScg to selected sRGB/P3 linear primaries; floor negative
channels at zero. Preserve scene values below knee `k = .76 - .16 * strength`.
For peak `p > k`, set `d = 1-k`, `q = 1-d*d/(p+d-k)`, scale RGB by `q/p`,
then mix toward neutral `q` by `1-1/(1+.15*(p-q))`. Apply only the sRGB OETF.
This preserves RGB channel order and hue angle around the neutral axis after
negative-channel handling, with a continuous value and first derivative at the knee.

Differences from Khronos: omit the .04 Fresnel/shadow subtraction (that compensates
for rendered materials, not photographic sensor values); expose knee .76..6 via
our existing shoulder control; apply in selected output primaries including P3.
Negative calibrated colors are floored, so this is not a perceptual wide-gamut mapper.
At strength zero compression remains enabled with knee .76: zero no longer
reproduces RapidRAW or bypasses highlight compression. No blend with an unbounded
scene signal is used. The meter retains its existing pre-curve highlight guards.

The transform reads only the current RGB pixel: it cannot introduce spatial rings
or neighborhood-dependent halos. It cannot recover sensor-clipped detail, undo
upstream halos, or guarantee that bright physical branches look dark. Preview's
existing BGU approximation and superpixel demosaic remain distinct from full export.

CPU scopes/reference, shared standalone/fused output GLSL, RAW viewfinder tail,
and Halide BGU look use the same formula. Four shipped Android ABI archives are
regenerated. JPEG metadata identifies `Neutral v2` and the new shoulder contract.

## Validation

Halide host/AOT reference check passes all 16 existing cases, including Basic,
sRGB/P3, lens shading, and extreme settings. JVM tests cover linear midtones,
bounded monotonic extended highlights at all shoulder strengths, knee value/slope,
neutrality, negative and extreme colors, hue ordering/path to white, scope inversion,
metadata and unchanged exposure guard settings. Full JVM validation: 2075 tests, 0 failures, 0 errors, 10 skipped. Debug APK assembled successfully.
The full output compute shader and the actual Basic preview branch compile with
`glslangValidator` (preview: GLES 2.0, 3.0, 3.1). Follow-up device validation on 2026-11-19 passed all 14 selected scene-linear,
fused/output, BGU and preview-slider tests. A real branch/sky render must
be reviewed before declaring the reported photographic halo resolved.

Upstream code copyright 2024 The Khronos Group, Inc., Apache-2.0. See
`app/src/main/assets/licenses/khronos-neutral-NOTICE.txt` and Apache-2.0.txt.
