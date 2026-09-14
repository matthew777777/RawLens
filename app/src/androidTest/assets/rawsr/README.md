# RAW-SR device fixtures

Real fixtures must be captured as an immutable set of normalized, uncropped Bayer planes plus a
JSON manifest containing width, height, Bayer phase, black levels, white level, timestamps, ISO,
exposure, and the chosen reference index. Do not use rendered PNG/JPEG images as alignment inputs.

Licensing and repository-size constraints mean no camera burst is checked in yet. A fixture is
accepted only after its source device and redistribution permission are recorded here. Until then,
`RawSrGpuInstrumentedTest` supplies deterministic synthetic Bayer fixtures and the production
capture path must retain its reference-frame fallback.
