# RawLens 2.0.0: remaining work (2026-10-22)

Proposed cut: **[2.0]** must land before the 2.0.0 release; **[2.0.x]**
can follow. Owner may move items.

## A. Rewrite batch (after the controller moves, owner 2026-10-22)
- [x] A1 [2.0] (done 113a004; device check pending) PROGRAM AE on libcamera RPi AGC: one total-exposure filter,
      Y-target + q0.98-1.0 highlight constraint, flicker rounding
      (exposure-audit.md). Owner: no +0.5 EV default.
- [x] A2 [2.0] (done 15dee6b; device check pending) ETTR on Magic Lantern: target <= -0.5 EV, overshoot
      extrapolation (E2), single-shot correction, midtone/shadow SNR limits.
      Owner: yes, Magic Lantern rules.
- [x] A3 [2.0] (done 78f5637; device check pending) Flash precapture as Open Camera's two-phase wait (flash-study F1/F2).
- [x] A4 [2.0] (done 566f66f; device check pending) ISO-only manual takes a left-over PROGRAM shutter (R-A3-1).
- [ ] A5 [2.0] **BGU viewfinder: same treatment as the Vulkan VF.** Study
      `BguViewfinder`, `BguFit`, `BguSlice`, `BguSliceCpu`, `BguLook`,
      `BguSample`, `BguGeometry`, `BguSpike`, `bgu_slice_cpu.cpp`,
      `halide_bgu_*_jni.cpp` and their Halide generators against the
      references: Chen et al. 2016 "Bilateral Guided Upsampling" and the
      google/bgu reference implementation, Halide `apps/bgu`, plus related
      bilateral-grid work (HDRNet slicing, Halide `apps/bilateral_grid`).
      Find AI-written mistakes (unreferenced constants, wrong grid/slice
      maths, fit regularization, boundary handling), fix to the reference,
      verify on device (vf-native-study.md is the template).

## B. HDR+ and HDR bracket merge
- [ ] B5 [2.0] HDR+ 1:1 with Burst Photo default mode (hdrplus-parity-todo.md 1-5).
- [ ] B6 [2.0] Burst Photo bracketed path for AE-drift bursts (todo 6).
- [ ] B7 [2.0] HDR+ Vulkan speed/memory pass.
- [ ] B8 [2.0] 3-bracket HDR merge on B5/B6, delete deghost/FlowNet route
      (hdr-bracket-merge-study.md). Owner: bracket DNGs with moving cars.

## D. Other open items [2.0.x]
- [ ] D13 ZSL copy-out pool (MotionCam; needs measurements).
- [ ] D14 Video V7: late-result frames dropped vs PhotonCamera request values.
- [ ] D15 Wavelet Vulkan per-pass study; stabilization rewrite.
- [ ] D16 AMaZE parity never established (excluded by owner).
- [ ] D17 VF native A2 (deferred by owner).
- [ ] D18 Record-only findings: ZSL job errors call onState on the writer
      thread; dual-RAW submit error skips the R3/R4 tail; unguarded first
      pairing clear in stop() (C5); soak extra-press race on HDR brackets.

## F. From Magic Lantern (owner 2026-10-22; ML source: reticulatedpines/magiclantern_simplified)
Port each against its ML source (cite file + commit), same as A2 ETTR.
- [ ] F1 [2.0] Raw zebras + false colour on the viewfinder, from raw
      values (src/zebra.c): over/under-exposure marks relative to the
      raw white/black level, not the processed preview. Pairs with ETTR.
- [ ] F2 [2.0] Raw spot meter in EV relative to clipping (src/zebra.c
      spotmeter): extend the current spot meter.
- [ ] F3 [2.0.x] Intervalometer with raw deflicker (modules/adv_int,
      modules/deflick): steady exposure across timelapse frames from raw
      metering; bulb ramping for sunset-to-night.
- [ ] F4 [2.0.x] Trap focus and motion / lightning trigger (src/focus.c,
      motion detect): fire when the subject is in focus or the scene
      changes; reuse our focus-peaking and motion data.
- [ ] F5 [2.0.x] Focus stacking (src/focus.c focus stack): automatic
      focus-distance bracketing via Camera2; merge later.
Not applicable: dual ISO (needs per-line sensor gain, not exposed on
Android), silent pictures, FPS override, crop_rec (sensor register hacks).

## E. Build and release
- [ ] E19 [2.0] Owner's uncommitted UI files (MainActivity, manifest,
      activity_main, ScopeStyle, ic_gallery).
- [ ] E20 [2.0.x] androidx.core pin 1.9.0 vs declared 1.19.1 (device tests).
- [ ] E21 [2.0.x] android.newDsl=false -> listenToArtifacts (before AGP 10).
- [ ] E22 [2.0] Full on-device suite vs the original build (deviceTest), plus
      a smoke run of the minified release APK.
- [ ] E23 [2.0] NOTICE (drop GALOSH; add libcamera / Magic Lantern if ported),
      CHANGELOG and 2.0 release notes.
- [ ] E24 [2.0] Version 2.0.0, merge refactor/2.0 -> main, tag, attach the
      R8 mapping (release-mappings/) to the GitHub release; F-Droid recipe
      supplies libjxl source (else JXL is "not bundled").

## M. MFSR (moved last, owner 2026-10-22: after everything else)
- [ ] Z1 [2.0] The MFSR linear (demosaiced RGB) DNG output shows a zipper
      pattern: dots and small squares alternating one dark, the next
      brighter, along edges and in fine detail. Find and fix the root
      cause in the MFSR merge / linear DNG path (CFA phase, per-channel
      accumulation or normalisation, green split, DNG writer layout, etc.
      - to be found), no masking filter.
- [ ] C9 [2.0] Dim-burst divergence vs the Python reference (70.5 dB).
- [ ] C10 [2.0.x] Scale > 1 for zoom; S.3 thresholds; failed-tile
      alignment check (mfsr-paper-gaps.md).
- [x] C10a s1/s2 and kernel-GAT A/B: keep IPOL (closed 2026-10-22,
      mfsr-paper-gaps.md).
- [ ] C11 [2.0.x] UI toggles (kernel, S.3); duplicate KernelNet model in the
      APK; 150 MB FloatArray copy before the MFSR DNG; perf (CPU pyramid,
      KernelNet conv).
- [ ] C12 [2.0.x] One 122 s first-merge outlier (likely thermal).

- [ ] C13 [2.0] MFSR "Wronski 2.0" (owner goal: improve beyond the paper):
      fix the core (merge resolution gain, single-frame reconstruction,
      alignment precision, motion-blur cases); no sharpening/exposure
      post-fixes (mfsr-wronski-2.md).

## Done today (for reference)
Controller A1-A10 + ZSL merge jobs, MFSR back-to-back OOM fix, MFSR S.3 and
noise LUT, GALOSH removal, R8 for all releases with mapping archive,
F-Droid permission-check fix.
