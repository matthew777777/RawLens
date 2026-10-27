# RCD and saved-photo trace — 2026-10-26

RCD recorder submits modes 0–2 immediately with one slot index. Native code previously owned only one descriptor set per slot and returned BUSY if that set was still referenced by a GPU submission. Mode 1 could therefore fail after mode 0; the recorder dropped the frame. Allocate four sets per slot, select by mode, and retain the existing in-flight fence check for reuse across frames. Pool capacity and teardown follow the twelve-set allocation.

Device validation: rapid sequence (three rounds, four stages, no intermediate waits) and fused-finalize versus split output both passed on Xiaomi 25080RABDG. The rapid test now asserts submission/completion instead of skipping on failures. RCD reference (4) and adaptive exposure (34) JVM tests passed.

A fresh 4080×3060 JPEG and DNG were captured from the same RAW using the BGU image comparison probe. Artifacts: `/tmp/rawlens-photo-pair/capture.jpg`, `capture.dng`, `JPEG-direct.png`, and `report.txt`. Default JPEG look, exposure strength 1. Preview EV −1.4999999 and save EV −1.5. Median display-linear luminance after common downsampling: save 0.24815751; direct preview operator 0.24838218; fitted BGU 0.25536310. This capture does not reproduce a save-only darkening.

The separate ETTR scene audit failed its scene-specific former-cap improvement assertion: current and former corrections were identical for this scene. That run is not evidence of an ETTR regression or of a saved-photo fix. The later sampling regression below establishes a separate saved-meter defect; no global brightness offset was added.

Persisted look: contrast 1.15, saturation 1.08, purity 1.15, shadow range 9.5, highlight range 5.5. The probe now applies these settings to both preview and save and records relevant preferences. The later pair in `/tmp/rawlens-photo-current-look` has almost no visible scene detail; it cannot establish normal-scene brightness parity. A stable lit-scene recapture was subsequently completed.

## Saved-photo root cause and fix

The dense Bayer/CFA/HDR meters used a linear sampling stride computed by floor(pixelCount / 65536). For 4080×3060 that is 190: every sampled column is even, so half the Bayer sites never vote. The live legacy preview meters all four sites. Color-dependent exposure bias follows even on a spatially uniform scene. A 512×512 two-column-level regression failed before the fix: reference correction +1.5 EV, actual +0.584962533 EV (0.915-stop lost lift). The CPU and direct RAW regression both pass after forcing the Bayer linear stride odd. HDR CFA sampling uses the same fix. RGB sampling remains unchanged because each RGB sample already votes all channels.

The real app pair `/tmp/rawlens-app-pair.jpg` and `/tmp/rawlens-app-pair.dng` was captured through controller.capture(), with `/tmp/rawlens-app-preview.png`. Before the sampling fix its live preview correction was +0.62536 EV and save correction +0.56071 EV; the visible scene already matched closely. Replaying normalized DNG samples without lens shading separately confirms the Bayer sampling bias: middle-grey correction +2.30468 EV at stride 190 versus +2.74000 at stride 191. Final applied changes depend on highlight guards, low-key damping, and user strength; the patch adds no fixed brightness offset.

Final combined build and targeted JVM checks passed. An intermediate assembly hit an Android Lint internal FIR exception in HdrRawMerge.kt; the subsequent full assembly passed including lint. Device build installed: 1.0.491.

Final device validation: strict rapid RCD sequence, fused-finalize parity, and app JPEG+DNG capture all passed together (3 tests, 18.497 seconds). Targeted JVM total: 41 tests, no failures or skips.
