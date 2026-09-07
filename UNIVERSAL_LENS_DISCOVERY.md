# Universal RAW lens discovery

RawLens now treats a selectable lens as a **camera route**, not as a hard-coded camera number.

It discovers:

1. Every rear RAW-capable standalone ID returned by `CameraManager.cameraIdList`.
2. Every valid logical -> physical member advertised by each logical camera's `physicalCameraIds`.
   These are displayed/persisted as `logical/physical` (for example `3/2`) but are implemented
   with standard Camera2: RawLens opens the logical CameraDevice and calls
   `OutputConfiguration.setPhysicalCameraId()` for both preview and RAW outputs.
3. Best-effort OEM composite IDs (`logical/physical` and `logical-physical`) only when the vendor
   camera service itself recognizes that composite characteristics ID. Numeric probing remains a
   compatibility supplement; no numeric ID has a fixed meaning.

A physical ID that is hidden from `cameraIdList` is never incorrectly presented as a standalone
camera merely because its characteristics are queryable. Android 10+ explicitly allows querying
hidden physical characteristics even though such an ID cannot be opened directly.

Lens labels and ordering use actual focal length and sensor physical size. The UI's 1x baseline is
selected as the available route closest to a conventional ~24 mm full-frame-equivalent main camera,
not camera ID `0`. Therefore IDs like `0`, `2`, `3/0`, and `3/2` carry no hard-coded semantics.

Physical stream combinations are still subject to each OEM HAL's advertised/session support. RawLens
uses `CameraDevice.isSessionConfigurationSupported()` before choosing its performance session plan.
