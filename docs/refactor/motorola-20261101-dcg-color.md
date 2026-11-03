# Motorola DCG JPEG color investigation — 2026-11-01

## Inputs and evidence

Examined both original uncompressed 4000×3000 RGGB DNGs supplied by the user,
reading the TIFF directory and all 3000 strip offsets/counts and uint16 pixels.
The JPEG examples show severe green/clipped output and a milder purple cast.
The files are different captures, not byte-matched RAW/JPEG pairs.
The expanded log ends around 19:01, before these 19:09–19:10 captures.

| File | RAW maximum | AsShotNeutral | Black / white tags |
| --- | ---: | --- | --- |
| IMG_20261101_190932_840.dng | 15408 | 0.453125, 1, 0.400390625 | 63,63,63,63 / 16383 |
| IMG_20261101_191014_710.dng | 4688 | 0.4755859375, 1, 0.4541015625 | 63,63,63,63 / 16383 |

Both have BitsPerSample=16 (storage, not proof of native sensor precision),
Orientation=6, ActiveArea=(0,0,3000,4000), default crop origin=(8,8),
default crop size=(3984,2984), and the same calibration/forward matrices.
All strips are 8000 bytes, at successive offsets 8000 bytes apart.
Both contain exactly 748 completely zero rows, indices 2252 through 2999.
Row 2251 still contains 4000 nonzero samples. This explains the large black
band after orientation. Missing startup WB cannot create that zero-pixel band.
The exported files alone cannot identify whether its origin is HAL output,
a stream geometry mismatch, or buffer handling before export. Do not silently
crop based on dark pixels or claim this is a confirmed HAL defect.

SHA-256:

- 190932: fa25d6f5ab14e78067f983a6323ea899e8eb873b7349c197659f92ade10a8582
- 191014: c59f6ff55eef5e79767fbc0f3b15bb397417dd381cdfe7c6cc82e18e7cb8d36d

## Confirmed application mismatch and fix

RawFrameMetadataFactory previously used dynamic/static HAL black and white
levels without consulting the saved per-camera overrides. The preview used
setSensorLevels and the DNG writer applied overrides, but JPEG development,
RAW denoise and merge normalized using unmodified HAL metadata. The expanded
log reports dynamic white 1023 even while mode 8 is requested. Applying that
white to a RAW payload containing codes above 15000 is a substantial domain
mismatch; it can break exposure/highlight/color processing. It is not proof
that every observed green or purple frame has this single cause.

Capture metadata now selects explicitly saved black/white levels before
repair, WB fallback, RAW unpacking, denoise, and merge. All single-frame,
bracket HDR, HDR+ ZSL and MFSR ZSL capture paths pass their frozen overrides.
Absent overrides preserve the existing HAL and invalid-level repair behavior.
No changes to RAW pixel values, color matrices, assumed bit depth or automatic
cropping were made. USER_OVERRIDE sources and CAPTURE_LEVELS log entries show
the effective selection alongside the HAL white level.

## Validation and outstanding work

Kotlin compilation passed. The capture-level, level-repair and missing-WB
regression suites passed: 21 tests. The new regression decodes codes 63,
8223, 15408 and 16383 using selected levels and verifies 0, 0.5, approximately
0.9403 and 1.0 without modifying the RAW bytes. Partial overrides retain the
other HAL level and CFA phase order; absent metadata remains repairable.

Motorola hardware validation is still required. Capture DNG+JPEG together
with the fixed build and a fresh log; inspect CAPTURE_LEVELS. Compare the
same scene/mode with MotionCam. If the purple cast persists with consistent
levels, compare RAW channel black offsets, neutral and calibration rather
than applying an arbitrary tint correction. For the zero band, obtain fresh
stream geometry/crop/stride and per-frame payload measurements before choosing
a resolution or crop change. These samples do not establish DCG activation.
