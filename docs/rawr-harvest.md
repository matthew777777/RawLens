# RAWR harvest — P0.1 exposure/focus monitoring (IN PROGRESS, paused)

Source: https://github.com/adityawarmanfw/rawr @ f41e6c2 (cloned to /tmp/rawr).
Both repos GPL-3.0 — ports keep rawr notices. HDR+/Wroński/multiframe excluded per user.

## What was decided

- Port `native/image_scopes` (waveform, vectorscope, exposure stats) +
  `native/monitoring_overlays` (focus peaking, false color, tonemap-shadow zebra,
  RAW-highlight overlay) nearly verbatim behind a new native module.
- New SHARED lib `rawLensMonitor` (C++20, owns its own Vulkan compute device —
  simpler than sharing handles with srvulkan at 2–5 Hz throttle).
- SPIR-V checked into `app/src/main/assets/spirv/mon/` (RawLens pattern, same as
  `spirv/hdrplus/`); overlays take runtime binaries already. image_scopes needs
  its `image_scopes_shaders_spv.hpp` generated via rawr's
  `native/image_scopes/tools/embed_spirv.py` (stdlib-only python3) — keeps the
  vendored .cpp 100% pristine.
- Data taps, all from OUR raw VF (user requirement):
  - Developed RGBA8: throttled FBO re-render + glReadPixels on RawViewfinder's
    GL worker (same program/uniforms, ~480px wide, ~3 Hz). Feeds scopes measure
    + focus/false-color/zebra.
  - RAW clip state: `VfCpuNeon.copy` at monitor geometry on camera thread
    (normalized R/Gr/Gb/B bytes) → new `rawlens_clip_pack.comp` packs rawr's
    R16 word (clip nibble@0, warn@4, shadowWarn@8, floor@12; thresholds
    0.995/0.98/0.01/<=black → bytes 254/250/2/0).
- Skip rawr's RAW-CFA-waveform path: rawr's own app never calls
  `recordRawWaveformRender` (no raw_stats producer lib exists). Revisit later.
- Overlay display v1: overlay RGBA8 shown in an Android View aligned to the VF
  rect (zero hot-path risk). Exact GLES composite in the present shader is a
  follow-up (needs FBO present restructure).
- New UI: Monitor button + panel (waveform/vectorscope ScopeViews, toggles for
  4 layers, focus-sensitivity slider, exposure readout), persisted prefs like
  histogram (MainActivity ~line 3109 HISTOGRAM toggle is the pattern).

## Vendored so far (pristine copies, NOT yet wired)

- `app/src/main/cpp/monitor/scopes/`: image_scopes.h, types.h, version.h,
  image_scopes_vulkan.cpp, layouts.hpp
- `app/src/main/cpp/monitor/overlays/`: monitoring_overlays.h, types.h,
  config_types.h, version.h, cpu_reference.h, monitoring_overlays.cpp,
  cpu_reference.cpp
- `app/src/main/assets/spirv/mon/`: 8 `scopes_*.comp` + `scopes_common.glsl`,
  5 `mon_*.comp` (rawr names preserved under prefix)
- Nothing added to CMake yet; nothing references these files yet.

## Key API facts (verified by reading full sources)

- image_scopes: `recordDisplayExposureStats` (block 2/4) → 8-float stats;
  `recordDisplayWaveform` (Luma/RgbOverlay, 512 dispatches) + render
  (512x384 target in rawr app); `recordVectorscope` (grid 128/256);
  frame-slot retire/discard discipline; copy helpers for readback.
  Input/target must be R8G8B8A8 GENERAL.
- monitoring_overlays: `recordCombined` runs standalone sequence
  false→focus→shadow→RAW unless 4 layers on Adreno 840 (fused path);
  input/output extents must match; R16 raw-state + RGBA8 display/output.
- False-color default preset: 7 IRE ranges (0-3/3-10/10-40/40-60/60-80/80-95/
  95-100.001) in cpu_reference.cpp — reuse for UI legend.
- Shader compile flags: `glslangValidator -V --target-env vulkan1.1`
  (/opt/homebrew/bin/glslangValidator on this Mac).
- No NOTICE.md files exist for these two libs upstream; attribute via
  UPSTREAM.md + root LICENSE (same as hdrplus precedent).

## RawLens facts gathered

- No Compose; MainActivity 5969 lines, histogram pattern: prefs
  `viewfinder_histogram`/`histogram_source_raw`, 700 ms tick.
- RawLens has histogram only; UltraHdrPlatform is a 14-line stub; LUT work is
  DORMANT comments; no film sim, no journal, no DNG editor (for later P0s).
- VF: RawViewfinder SurfaceView + EGL, CPU (NEON quads) and GPU (zero-copy)
  paths; present via eglSwapBuffers, no FBO currently.

## Resume steps

1. Compile 13 shaders + write `rawlens_clip_pack.comp`; generate
   `image_scopes_shaders_spv.hpp`; write `assets/spirv/mon/{UPSTREAM,README}.md`.
2. Write `cpp/monitor/{monitor_host,monitor_jni}.cpp` + CMake `rawLensMonitor`.
3. RawViewfinder taps: FBO readback hook + throttled quad sample callback.
4. Kotlin: VfMonitor.kt, ScopeView.kt, overlay view, MainActivity MONITOR UI.
5. Build (`./gradlew :app:assembleDebug`), host unit tests for throttle/prefs,
   on-device verification, update this log with verdicts.
