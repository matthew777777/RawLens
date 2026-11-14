# Rooted 50 MP DNG softness investigation — 2026-11-13

Input: `/Users/monikamalinowska/Downloads/IMG_20261112_153300_134_F00.dng`.

## Verified observations

- Uncompressed 8160×6120 CFA DNG, 16-bit storage, white1023/black64, 2×2 GBRG, full active/crop area.
- ISO50, exposure1/434.8s, aperture f/1.7, focal length6.1mm.
- EXIF SubjectDistance0.805916m. RawLens derives this directly from reciprocal CaptureResult.LENS_FOCUS_DISTANCE; it is not a fabricated profile distance. Vendor focus-distance accuracy is unverified, so this is a clue rather than proof of physical focus error.
- Filename F00 is the app's burst-source-frame naming convention. The normal native DNG writer passes the ImageReader CFA buffer directly to TinyDNG (or copies row layout when necessary); it does not run the developed-image denoiser. This is distinct from the explicitly labelled AI-denoised float DNG path.
- LibRaw/rawpy AHD development with camera WB, noise_thr=0, median_filter_passes=0, no sharpening, no auto brightness still shows soft foliage and substantially blurrier distant right-hand buildings than left-hand hard edges. A neutral render does not distinguish optical focus/field effects, sensor remosaic and upstream ISP processing.
- RAW sampled sky and foliage do not show a simple repeated-row/column explanation; phase statistics alone cannot certify native optical resolving power or rule out vendor interpolation/NR.

## Interpretation and next experiment

Focus deserves a controlled check before assigning the softness to rooted vendor noise reduction. Landscape image plus reported0.81m focus is suspicious; a single frame cannot identify its dominant blur source. A fixed, detailed distant scene with a manual focus bracket (including0diopters/infinity and small positive distances), identical ISO/exposure and50MP readout can separate focus error from mode-specific smoothing. Do not permanently force infinity for every50MP photograph. If the best-focused50MP source remains plasticky, compare RAW output with vendor RAW NR/remosaic settings under a bounded root lease; changing app JPEG denoise cannot remove smoothing already in the CFA.

No camera settings or vendor libraries were changed for this inspection. No Activity opened. ADB connected, shellUID2000, Enforcing; no root restoration/exploit attempted.

Artifacts (ignored): `.local/lapis-50mp-softness/overview.jpg`, `neutral-full.jpg`, `neutral-crops.jpg`, `analysis.json`, `mosaic.npy`. Original DNG unchanged.


## Remosaic / RAW pipeline investigation

Temporary root restored using the existing same-firmware headless helper. Two bounded50MP captures were made before the user requested analysis only; no captures after that request. Both used actual sensor12 (driver0x100c300e),8160×6120,ISO400/20ms,manual focus0diopters. Default CaptureResult noise/edge modes1/1; explicit OFF result0/0. Both produced populated RAW10 withwhite1023/black64. The phone was aimed at an almost-black scene, so these tests prove request acceptance and routing, not improved resolving power or noise suppression. Do not claim OFF improved the image.

Decisive current-route log evidence (lapis-50mp-remosaic-route.log):
- setupFunctionTableLocked: useP1DirectAppRaw.
- PipelineConfiguration: useP1DirectAppRaw=1; maxUserImageSize8160×6120.
- P1 RAW app buffer BAYER10_UNPAK,8160×6120; internal sensorFullraw BAYER10.
- RemosaicProvider negotiate: Sensor(0) scene(12) not a remosaic scenario.
- AE initial info is_remosaic(0).
- configProcessedRaw=-1 (automatic/default selection; alone does not prove pure or processed pixels).

Therefore the observed ordinary rooted50MP RAW route does not negotiate the separate software remosaic provider. The libraries are mapped at HAL startup; mappings alone do not prove execution. Altering libremosaiclib_hp3/tunable_param_hp3.bin is not an established fix for this route. Samsung wrapper contains configure_s5khpe and separatefullsize/ISZ tuning paths, so HP3 filename alone also does not establish wrong sensor tuning.

Sensor source mode12: cms_enabled=true, pdaf_cap=false, HDR_NONE,10fps,16320×12240 source window scaled8160×6120, ordinary RAW-format declaration. CMS acronym/algorithm must not be assumed from the flag alone. PDAF absence strengthens the need to test focusing carefully; it does not prove contrast autofocus is unavailable.

Pure RAW is a concrete alternative to investigate:
- Platform libmtkcam_dma_map.so isPureRawDeviceSupported atVA0x1090 returns1 unconditionally.
- MT6878 kernel vb2 support includes MTK_RAW_PURE_RAW_OUT and MTK_RAW_MAIN_STREAM_OUT.
- mtk_cam-job.c update_sv_pure_raw separates pure and processed nodes, supports normal/mstream scenarios; pure RAW may use CAMSV. This establishes platform plumbing, not that current50MP uses that node.
- Advertised request key com.mediatek.control.capture.processRaw.enable is **int[]**, verified through native key reflection; tag0x800b001e. Remosaic enable/seamless/recalculateRawType are also int[]. No scalar-key guessing.
- Research RootRawProbe now supports opt-in processRaw=0/1 as a single-entry int[] for a future bounded A/B; array-shape semantics, session applicability, actual selected DMA node and image-quality effect remain unverified. This option has NOT been run or shipped in the app.

Other controls found in libcam.halisp.imp.v4l2.so: vendor.isp.rnr_r1.disable/rnr_r2/rnr_r3/rnr_t1, snr_d1.disable, tnr_d1.disable, etc. These are leads, not validated RAW improvements. Static HwBlockSNR(img_snr_d1)::isBelongedToPass1 returnsfalse; isBelongedtoDip returnstrue, so a broad NR switch sweep would mix unrelated stages. No ISP-disable properties or remosaic tuning bytes were changed.

Next controlled test: fixed detailed scene/focus bracket, then default versus processRaw disabled at the best focus; verify P1/DMA route and calibration before assessing detail. Only then test relevant first-pass blocks individually. User requested analysis only for now; no app processing changes installed.

Final restore: original metastoreSHA25695eded2e…a7dc, no metastore bind, sensor override blank, no active root-camera lease, SELinuxEnforcing. Temporary KernelSU root remains loaded. Evidence in ignored .local/lapis-50mp-softness/remosaic-research.


## Screen target test and opcode question

User authorized live testing against the Mac screen. Brighter manual sweep (ISO400/50ms) showed a strong detail-energy peak at5diopters:363.1 versus152.2 at4 and137.7 at6, actual sensor12 retained, LENS_STATE stationary. Screen framing is off-axis and partly cropped; do not generalize its optimum to landscapes. Later default/pure/processed/NR-OFF comparison frames were almost entirely black (means63.94–63.96 at black64). This A/B is INVALID for sharpness/processing quality; no improvement or equivalence can be concluded. Script completed with original metastore restored, override reset andEnforcing.

Opcode source audit: LapisRootSession.normalized clears lensShadingMap for all verified root modes. TinyDngMetadata emits OpcodeList2 GainMap only when a non-null map exists and shading is not already applied. Root-profile DNGs therefore omit that vignetting-correction gain map. This was a deliberate conservative omission of unverified mode geometry/calibration, not proof shading is already corrected. Distortion is separate: the current app photo DNG writer has no WarpRectilinear/OpcodeList3 emission path; this is not merely a root-profile switch. TinyDNG library exposes opcode support, but the app's metadata bridge does not use it for distortion. Re-enabling stock calibration blindly can produce wrong corners/crops or double correction; full50MP and sensor-crop2x/4x need respective coordinate mapping and validation.
