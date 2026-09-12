# Forest RAW fixture — provenance and license

Copyright: RawLens user (forest GCam ZSL burst contributor).

The contributor supplied 32 original forest DNG payloads (GCam ZSL debug dump,
HDR+ port tuning on Redmi 25080RABDG, 4080×3060 GBRG, 1/100 s, ISO 458);
the oldest 30 were extracted. The contributor confirmed ownership and the
requested CC BY 4.0 redistribution permission in the RawLens development
conversation on 2026-09-11.

The photographs, extracted Bayer samples and associated fixture metadata are
licensed under **Creative Commons Attribution 4.0 International (CC BY 4.0)**:
https://creativecommons.org/licenses/by/4.0/

Legal terms: https://creativecommons.org/licenses/by/4.0/legalcode

Attribution: “RawLens user (forest GCam ZSL burst contributor), Forest RAW
burst, 2026. CC BY 4.0. Lossless Bayer-region extraction and metadata
transcription by RawLens.”

Changes: a rectangular 514×386 region at DNG active-array origin (800,1200)
is retained from each DNG and losslessly gzip-packed as little-endian
uint16. DNG metadata, sensor-clock timestamps (GCam T field), and pending
lens-shading maps are transcribed into JSON. No normalization, demosaic, tone
mapping, resampling, alignment or fusion is applied. Original filenames and
SHA-256 digests are retained in the manifest.

No endorsement by the contributor, camera manufacturer, or Google is implied.
