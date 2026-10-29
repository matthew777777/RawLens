# RawLens 2.0.0: remaining work (2026-10-22, cut revised 2026-10-25)

Proposed cut: **[2.0]** must land before the 2.0.0 release; **[2.0.x]**
can follow. Owner may move items.

Owner cut 2026-10-25: only A5, A6, all of B, D15 (Wavelet Vulkan per-pass
study only), F1, F2, F14, F15, H1, H2 stay [2.0]; everything else open
moves to [2.0.x]. Release mechanics (E19, E22-E24) kept [2.0] because the
2.0.0 release cannot happen without them.

## A. Rewrite batch (after the controller moves, owner 2026-10-22)
- [x] A1 [2.0] (done 113a004; device 2026-10-27: 5-press PROGRAM soak OK, no crash; convergence not judged - scene dark, held at ISO 12800 1/15 cap; recheck in light) PROGRAM AE on libcamera RPi AGC: one total-exposure filter,
      Y-target + q0.98-1.0 highlight constraint, flicker rounding
      (exposure-audit.md). Owner: no +0.5 EV default.
- [x] A2 [2.0] (done 15dee6b; device 2026-10-27: 5-press AUTO+ETTR soak OK, no crash; ETTR has no log line, so its decisions need a lit-scene check) ETTR on Magic Lantern: target <= -0.5 EV, overshoot
      extrapolation (E2), single-shot correction, midtone/shadow SNR limits.
      Owner: yes, Magic Lantern rules.
- [x] A3 [2.0] (done 78f5637; device 2026-10-27: FlashCaptureDeviceTest OK, flash fired in forced-auto and manual) Flash precapture as Open Camera's two-phase wait (flash-study F1/F2).
      Flash follow-up 2026-10-26: forced ON / hardware AUTO / manual verified
      on device; PROGRAM/ETTR preserve their own pairs and offer Off/On only
      ([flash evidence](flash-study.md)).
- [ ] A3b [2.0.x] RawLens-owned auto-flash metering for PROGRAM/ETTR.
      Owner 2026-10-26: keep Auto disabled with an explanatory note until implemented.
- [x] A4 [2.0] (done 566f66f; device check pending: no headless test drives ISO-only manual) ISO-only manual takes a left-over PROGRAM shutter (R-A3-1).
- [x] A5 [2.0] **BGU viewfinder: same treatment as the Vulkan VF.** Study
      `BguViewfinder`, `BguFit`, `BguSlice`, `BguSliceCpu`, `BguLook`,
      `BguSample`, `BguGeometry`, `BguSpike`, `bgu_slice_cpu.cpp`,
      `halide_bgu_*_jni.cpp` and their Halide generators against the
      references: Chen et al. 2016 "Bilateral Guided Upsampling" and the
      google/bgu reference implementation, Halide `apps/bgu`, plus related
      bilateral-grid work (HDRNet slicing, Halide `apps/bilateral_grid`).
      Find AI-written mistakes (unreferenced constants, wrong grid/slice
      maths, fit regularization, boundary handling), fix to the reference,
      verify on device (vf-native-study.md is the template).
      Closed 2026-10-26: pinned source/decision table, EGL/GL split,
      ownership/JNI/geometry fixes and encoded-guide haze correction.
      78 host + 17 device tests pass; frozen-RAW neutral-shadow lift reduced
      86% RAW / 64% JPEG; normal preview remains about 29–30 fps on the test
      phone. R8 release 1.0.468 mode/lens/resume smoke passed; AUTO restored.
      Residual approximation error and device/performance limits are explicit
      in the [study and evidence](bgu-study.md); E22 remains release-wide.
- [x] A6 [2.0] **ETTR JPEGs are too dark** (closed 2026-10-26).
      Removed the ETTR-only develop residual cap; capture exposure and DNG
      writing unchanged. Same-RAW before/after plus AUTO/PROGRAM device
      comparison passes: neutral areas within 0.11 EV of PROGRAM / 0.14 EV
      of AUTO, highlight detail retained. 112 host tests pass; F15 develop
      row updated. [References and evidence](ettr-development-audit.md).

## B. HDR+ and HDR bracket merge (owner 2026-10-27: porting B5/B6/B8 moved to 2.0.x; only performance for 2.0)
- [ ] B5 [2.0.x] HDR+ 1:1 with Burst Photo default mode (hdrplus-parity-todo.md 1-5).
- [ ] B6 [2.0.x] Burst Photo bracketed path for AE-drift bursts (todo 6).
- [ ] B7 [2.0] HDR+ Vulkan speed/memory pass. First pass done 2026-10-27,
      bit-identical to the previous build (owner rule): Mali G615, 8x12 MP,
      Fast 4.6 -> 3.5 s wall (GPU 3.74 -> 3.04 s), HQ 6.6 -> 6.1 s (6.24 ->
      5.75 s); -50 MB GPU scratch. Per-dispatch profile: setprop
      debug.rawlens.hdrp_prof 1 (desktop HDRPLUS_PROFILE=1). Remaining top
      costs: blur 1.39 s, correct_upsampling 0.61 s, hdrq_merge 1.7 s,
      mismatch 0.74 s, forward DFT 0.72 s; further gains mostly need float
      reordering, which the bit-parity rule forbids.
- [ ] B8 [2.0.x] 3-bracket HDR merge on B5/B6, delete deghost/FlowNet route
      (hdr-bracket-merge-study.md). Owner: bracket DNGs with moving cars.

## D. Other open items
- [ ] D13 [2.0.x] ZSL copy-out pool (MotionCam; needs measurements).
- [ ] D14 [2.0.x] Video V7: late-result frames dropped vs PhotonCamera request values.
- [ ] D15 [2.0] Wavelet Vulkan per-pass study (study only; stabilization
      split out to D15b, owner 2026-10-25).
- [ ] D15b [2.0.x] Stabilization rewrite.
- [ ] D16 [2.0.x] AMaZE parity never established (excluded by owner).
- [ ] D17 [2.0.x] VF native A2 (deferred by owner).
- [ ] D18 [2.0.x] Record-only findings: ZSL job errors call onState on the writer
      thread; dual-RAW submit error skips the R3/R4 tail; unguarded first
      pairing clear in stop() (C5); soak extra-press race on HDR brackets.

## F. From Magic Lantern (owner 2026-10-22; ML source: reticulatedpines/magiclantern_simplified)
Port each against its ML source (cite file + commit), same as A2 ETTR.
- [x] F1 [2.0] Raw zebras + false colour on the viewfinder
      (closed 2026-10-26; [audit](raw-exposure-assist-audit.md)), from raw
      values (src/zebra.c): over/under-exposure marks relative to the
      raw white/black level, not the processed preview. Pairs with ETTR.
- [x] F2 [2.0] Raw spot meter in EV relative to clipping
      (closed 2026-10-26; compact text beside AUTO/status;
      [audit](raw-exposure-assist-audit.md)) (src/zebra.c
      spotmeter): extend the current spot meter. Real RAW oracle, UI, DirectLog
      recording/monitor and lease checks passed. Existing unavailable CRS backend
      is explicitly excluded; rejection/cleanup passed (not a recording pass).
      Average + clipped-area readout extension implemented 2026-10-26;
      [accuracy checks passed; UI/recording pending phone reconnect](raw-average-meter-audit.md).
- [ ] F3 [2.0.x] Intervalometer with raw deflicker (modules/adv_int,
      modules/deflick): steady exposure across timelapse frames from raw
      metering; bulb ramping for sunset-to-night.
- [ ] F4 [2.0.x] Trap focus and motion / lightning trigger (src/focus.c,
      motion detect): fire when the subject is in focus or the scene
      changes; reuse our focus-peaking and motion data.
- [ ] F5 [2.0.x] Focus stacking (src/focus.c focus stack): automatic
      focus-distance bracketing via Camera2; merge later.
- [ ] F6 [2.0.x] Auto-range HDR bracketing (src/shoot.c, Frames
      "Autodetect"; ISO shifting): add darker/brighter frames until
      highlights are unclipped and shadows are lifted, instead of a fixed
      count. Fits the HDR bracket rework (B section).
- [ ] F7 [2.0.x] White balance from raw (src/shoot.c kelvin_auto,
      wbs_gm_auto): set Kelvin and green-magenta from a neutral spot's raw
      values. Source caveat: those ML routines measure processed spot YUV;
      a RAW implementation requires a documented Camera2/calibration adaptation.
- [ ] F8 [2.0.x] Vectorscope (src/vectorscope.c) next to histogram/waveform.
- [ ] F9 [2.0.x] Magic Zoom (src/zebra.c): persistent magnified window
      over the focus point for manual focus.
- [ ] F10 [2.0.x] Rack focus / follow focus for video (src/focus.c): two
      saved focus distances, smooth move between them while recording.
- [ ] F11 [2.0.x] Depth-of-field info (src/lens.c): near/far limits and
      hyperfocal distance from Camera2 lens data.
- [ ] F12 [2.0.x] Cropmarks and ghost-image overlay (src/cropmarks.c,
      src/zebra.c): aspect frame lines; last photo shown semi-transparent.
- [ ] F13 [2.0.x] Anamorphic desqueeze preview (src/zebra.c).
- [x] F14 [2.0] **Recheck focus peaking against ML**
      (closed 2026-10-26: pinned source table, native/Kotlin ratio fix,
      recording peaking handoff + crop geometry, real RAW comparisons,
      1080p/1440p/4K MF/AF recording checks and minified release UI smoke;
      CRS backend unavailable; [evidence and limits](ml-focus-ettr-audit.md)). Source: src/zebra.c focus
      peak (owner 2026-10-23). Original scope:
      `FocusPeaking.kt` (Tenengrad + Laplacian on the green quad,
      absolute threshold, wash guard, despeckle, black-mean floor).
      Re-derive ML's detector from source (kernel, threshold scale and
      units, colour/blink modes, behaviour at high gain) and table it
      against ours: ML rule -> our constant/code -> keep / fix /
      intentional-diff. Scene gates: high-ISO false positives,
      low-contrast sharp edges, defocus ramps, MF pull at record
      resolution. Fix deviations; document the rest in the file header.
- [x] F15 [2.0] **Recheck ETTR against ML**
      (closed 2026-10-26: pinned source table, minimum-channel overshoot
      gradient, analytic/ARM fixture, 18 real RAW comparisons including
      2,324 locally green-only clipped quads, paired sampler timings and
      release UI smoke; [evidence and limits](ml-focus-ettr-audit.md)). Source: modules/ettr (owner
      2026-10-23). Original scope: audit A2 against ML source.
      Same table as F14 over: target <= -0.5 EV, overexposed-pixel
      allowance, midtone/shadow SNR limits, highlight-ignore fraction,
      correction clamp and single-shot extrapolation (E2). Any ML rule
      we do not implement gets a named reason, not silence.
      Owner addition 2026-10-25: **min of channels for gradients.**
      When one channel clips first (typically G), the clipped channel
      flattens the highlight gradient the overshoot extrapolation (E2)
      depends on. Use the minimum of the CFA channels (or the still-
      unclipped channels, WB-normalised) to keep a usable gradient
      and estimate how far over clipping the highlights really are.
      Table it against ML's per-channel handling; gate on scenes where
      G clips while R/B do not (daylight sky, foliage highlights).

## G. From libcamera (owner 2026-10-22; gitlab.freedesktop.org/camera/libcamera @ 7278a59)
Port each against its libcamera source (cite file + commit), same as A1 PROGRAM.
- [ ] G1 [2.0.x] Green equalisation (src/ipa/rpi/controller/rpi/geq.cpp):
      even out Gr/Gb mismatch, strength from analogue gain and lux. Check
      first as a cause of the MFSR linear DNG zipper (Z1).
- [ ] G2 [2.0.x] Lux estimate (rpi/lux.cpp, libipa/lux.cpp): scene
      brightness from exposure, gain and image level; feeds G1, G4, G6.
- [ ] G3 [2.0.x] Wide dynamic range reference (src/ipa/rkisp1/algorithms/
      wdr.cpp): AE capped at ~2 % near-clipped pixels + global tone curve
      that restores the lost brightness (linear/power/exp/hist-eq). Use as
      the reference check for our ETTR + adaptive exposure pairing.
- [ ] G4 [2.0.x] Adaptive lens shading (rpi/alsc.cpp): refine the Camera2
      shading map from the image so corners stop tinting or darkening.
- [ ] G5 [2.0.x] Bayesian AWB (rpi/awb.cpp, libipa/awb_bayes.cpp): search
      along the colour-temperature curve with lux-dependent priors.
- [ ] G6 [2.0.x] Noise-model-driven denoise (rkisp1/dpf.cpp,
      rpi/denoise.cpp): one noise model drives every denoise stage
      (strength per channel from gain; spatial vs temporal).
- [ ] G7 [2.0.x] Lateral chromatic aberration correction (rpi/cac.cpp):
      shift R/B planes pre-demosaic to remove colour fringes.
- [ ] G8 [2.0.x] Multi-frame HDR / night reference (rpi/hdr.cpp): exposure
      cadence, stitch, tonemap; second reference for B5-B8 next to HDR+.
- [ ] G9 [2.0.x] Software ISP as a parity reference (src/libcamera/
      software_isp/, src/ipa/softisp/): compact BLC/AGC/AWB/CCM/LSC/debayer.
- [ ] G10 [2.0.x] Adaptive contrast (rpi/contrast.cpp): optional JPEG look.
Already covered: CCM interpolation by colour temperature, defect pixels.
Not applicable: hybrid PDAF/CDAF AF (rpi/af.cpp; HAL owns AF), ISP
register tuning (sharpen, gamma tables).

## H. From Nikon (owner 2026-10-23; survey: camera-maker-harvest.md §2, §9)
Clean-room mimicry only: Nikon publishes no reusable code. Behaviour
and parameter sets are ours to reimplement; no Nikon profiles bundled.
- [x] H1 [2.0] **Highlight-weighted metering** (closed 2026-10-26:
      host regressions + ADB GPU/RAW/JPEG + stationary window scene pass;
      CFA stride bug fixed; [rules and evidence](nikon-metering-audit.md)):
      fourth PROGRAM metering weight in `RawProgramMeter` beside
      centre / average / spot. Weight by highlight brightness x area
      so small bright regions (spotlit subject, window in a dark room)
      stop clipping without dragging the whole frame down. Gates:
      synthetic + on-device scenes where centre/spot each fail one
      way; must not fight the H2 capture bias when both are on.
- [x] H2 [2.0] **Active D-Lighting** (closed 2026-10-26:
      all five strengths verified in PROGRAM/ETTR on the stationary window
      scene, GPU grey ramp passes; [rules and evidence](nikon-metering-audit.md)): one control,
      Off / Low / Normal / High / Extra High, coupling (a) a
      capture-side highlight-protection bias in PROGRAM/ETTR with
      (b) develop-side shadow + midtone lift in the JPEG look. Both
      halves exist today as separate knobs (highlight guard,
      adaptive exposure, shadow range); this packages and calibrates
      them as one strength, per strength, against grey-card /
      high-DR scenes. Implement **once**: the same control is Sony
      DRO, Fuji DR200/400, Panasonic i.Dynamic and Canon Highlight
      Tone Priority (harvest §9). No second copy under another name.
- [ ] H3 [2.0.x] Picture Controls + Flexible Colour (harvest §2):
      parameterised looks (sharpening / clarity / contrast /
      brightness / saturation / hue, mono + Y/O/R/G filters + toning,
      HSL colour blender). Lands in the shared look system J2, not a
      Nikon-only fork of `JpegOutputSettings`.
- [ ] H4 [2.0.x] `.NCP` / `.NP2` / `.NP3` look import (harvest §2):
      parser against `dumpncp.pl` (simeonpilgrim/nikon-firmware-tools,
      licence check first) and the NP3 reader/writer notes. User
      supplies files (nikonpc.com, Imaging Cloud recipes); nothing
      downloaded or bundled by the app.

## I. From Sony: DirectLog colour correctness (owner 2026-10-23; harvest §3, §8)
Implementation/evidence 2026-10-28: [Sony DirectLog audit](sony-directlog-audit.md).
I1's numeric pipeline and I2's LUT machinery are implemented; their original
physical-chart/reference-render acceptance remains open. E22 stays parked.
Owner 2026-10-29: close software work without a chart; retain the physical gate.
Final LUT-off 30-second cadence passes all three targets; baked LUT passes
4K/24 and 1080p/30 but fails the strict 1440p/30 timestamp gate, including a repeat.
See the audit for retained evidence; this is an unresolved performance limitation.
- [ ] I1 [2.0.x] **Full S-Gamut3.Cine / S-Log3, replacing the current
      "SLo3".** Numeric correction implemented 2026-10-28 (CPU, probe,
      split/fused MHC/RCD fp32/fp16, Packed RCD; generated gamut matrix,
      signed toe, dormant working-log rename). 2,700 device channel checks
      pass (fp32 exact; fp16 <=2 ten-bit codes); nine resolution/crop
      recordings and independent decode pass. Limited-range transport
      intentionally differs from Sony full-range files; exact code mapping
      and input settings documented. Physical I2 gate, real Sony-file
      metadata comparison and original-build performance parity remain open.
      Original finding: `DirectLogProfile.SLOG3` applied the Sony curve
      formula (420 / 261.5 / divisor 0.19 / 95-code pedestal,
      `VfLogGrade.sonySlog3`, mirrored in `vf_gradeyuv.comp`,
      `vf_mhcyuv.comp`, `vf_loggrade.comp`) to **linear sRGB**. That
      is the right curve on the wrong gamut: every official Sony LUT
      expects S-Log3-encoded **S-Gamut3.Cine** (or S-Gamut3)
      primaries, so greens/reds and saturation land wrong before any
      LUT is even applied. Required, all of it:
      - Gamut: developed linear -> XYZ (D65) -> S-Gamut3.Cine matrix
        (Sony-published primaries) before the curve, in CPU golden
        (`VfLogGrade.encodePixelSlog3`), both record shaders and
        their f16 twins, and the probe shader. One matrix constant,
        host-tested (white-preserving, known patch conversions).
      - Negative / out-of-gamut handling after the wide-gamut matrix:
        no silent `max(x, 0)` hue skew on saturated colours; define
        and test the toe behaviour for negative channels.
      - Range and codes: pin what the file carries (10-bit pedestal
        95, cut 0.01125, 18% -> 420, 90% -> ~598 anchors stay) and
        how the limited-range YUV pack (64-940) presents them after
        decode; document the exact DaVinci/Premiere input settings
        (S-Gamut3.Cine/S-Log3, data levels) the file is made for.
      - Container signalling audit vs real Sony camera files
        (`DirectLogProfile.targetColors` currently signals
        BT.709/SDR/limited for SLOG3): match or document the diff.
      - Kill the ambiguity: the dormant approximate `slog3()`
        (divisor 0.4625) in `VfLogGrade.developLinear`/`bakeLut`
        must never feed a Sony-named output; rename it as the
        internal working log or make it Sony-exact.
- [ ] I2 [2.0.x] **Official Sony LUT acceptance gate** (depends on the
      `.cube` parser half of J1, now implemented): official s709 and all four
      SG3C LUTs in the owner's BURANO ZIP pass device/independent FFmpeg
      trilinear comparisons within one RGB8 code/channel on synthetic
      ramps/patches. Physical chart/grey-ramp take and reference render
      still pending. Vendor creative tints are preserved, not neutralized.
      Original gate: user imports Sony's own free LUTs (e.g.
      SLog3S-Gamut3.Cine -> s709 / 709(800%)); never bundled. Grey
      ramp + colour-chart take recorded as I1 S-Log3, LUT applied in
      a reference tool and in-app: neutral greys stay neutral,
      chart patches within a stated code tolerance of the reference
      render. This gate is the definition of "official LUTs work";
      I1 is not done without it.
- [ ] I3 [2.0.x] **Full S-Cinetone look for DirectLog** (Sony)
      (owner 2026-10-28: keep open; no Sony/Catalyst reference available;
      no speculative fit/profile added). A new
      ready-to-watch `DirectLogProfile`, not a LUT and not a curve
      tweak. S-Cinetone = Venice-derived colour rendering in a
      BT.709 deliverable: film-like suppressed highlight contrast
      and soft highlight rolloff, low-contrast midtones, protected
      skin tones, Sony's black handling. Implement from Sony's
      published S-Cinetone characteristics (tone curve + gamut /
      matrix behaviour), our own fit; plumb end-to-end (profile
      enum + wire id, shader branches + f16 twins, CPU golden,
      BT.709 SDR container signalling, HUD cycle, file suffix,
      preview/record parity). Gate: chart + skin-tone takes vs a
      Sony reference render (camera or Catalyst), stated tolerance;
      no Sony data files bundled.
- [x] I4 [2.0.x] (take-monitor implementation 2026-10-28: analytic BT.709
      assist or imported cube, per-LUT intensity, optional GLES3 3D texture;
      CPU/GPU and real take-monitor integration pass; R8 build passes.
      Before-record RAW VF is unchanged; [scope/evidence](sony-directlog-audit.md)).
      Gamma Display Assist / user-LUT monitoring for
      DirectLog (Sony): preview shows the I2 LUT (or a built-in
      709 assist) while the file stays S-Log3. Depends on J1.

## J. From the other makers, shared builds (owner 2026-10-23; harvest §4-§8, §10)
- [ ] J1 [2.0.x] **`.cube` LUT import + library** (Panasonic Real Time
      LUT is the UX model: slots, intensity per LUT; harvest §8):
      Parser/library/monitor UI implemented 2026-10-28 for I2/I4: bounded
      16–65 grids, domain, 16 user imports, select/remove, persisted per-LUT
      intensity, real import lifecycle and monitor checks pass. ZIP packs
      must be extracted first. Recorded-grade extension is now implemented:
      fused Vulkan 3D LUT, S-Log3 input / `_LUT709` output, no double monitor
      transform, 0/.5/1 intensity + custom-domain/signed-output GPU gates
      pass. Final candidate cadence remains open: cooler baseline soaks pass
      all requested modes; later back-to-back LUT-on and LUT-off runs fail
      strict timestamp checks. Before-record RAW VF still has no LUT preview.
      parser (LUT_3D_SIZE 16-65, domain), 3D texture in the
      DirectLog preview first (view assist, I4), then recorded grade.
      This one build unlocks the free Nikon RED, Sony, Fuji F-Log,
      Canon Log and Panasonic VariCam LUT packs as user imports.
      Importability alone does not convert their expected source gamuts/log
      curves: current recording support requires a matching
      S-Gamut3.Cine/S-Log3-to-SDR-Rec.709 cube.
- [ ] J2 [2.0.x] **Look / recipe system** (harvest §7): parameterised
      look + named packs (own approximations, generic names) +
      mono filters/toning + Fuji recipe layer (Highlight/Shadow
      tone, Colour Chrome, WB Shift R/B grid, grain stage with
      strength/size/ISO linkage) + Pentax entries (Bleach Bypass,
      Reversal, Cross Process) and Olympus art-filter entries.
      One slot in `JpegOutputSettings`; no per-maker forks, no
      bundled third-party LUT packs without a licence check.
- [ ] J3 [2.0.x] **Live Composite + Live ND / GND** (Olympus;
      harvest §5): brighter-pixel-only composite and burst-average
      ND simulation on `BurstMergeRunner` / ZSL frames, with the
      progressive (Live Bulb-style) preview. Star-trail / light-
      painting and long-exposure-look modes we cannot do today.
- [ ] J4 [2.0.x] Zebra with a user-set IRE range (Sony; harvest §9):
      extends F1 (e.g. 95+ only), same raw-value basis.

## E. Build and release
- [x] E19 [2.0] Uncommitted work committed in topical commits 2026-10-27
      (bae736a, e147592, 57922e7, 7dcbf90).
- [ ] E20 [2.0.x] androidx.core pin 1.9.0 vs declared 1.19.1 (device tests).
- [ ] E21 [2.0.x] android.newDsl=false -> listenToArtifacts (before AGP 10).
- [ ] E22 [2.0] (PARKED by owner 2026-10-27 until they say go) Full on-device suite vs the original build (deviceTest), plus
      a smoke run of the minified release APK.
- [x] E23 [2.0] (done 2026-10-27: NOTICE adds libcamera, Open Camera, ML ETTR,
      BakingLab; CHANGELOG 2.0.0 since 1.0.192; RELEASE_NOTES_2.0.md) NOTICE (drop GALOSH; add libcamera / Magic Lantern if ported;
      H-J maker items are mimicry + user-imported data, so add entries only
      if code is actually ported, e.g. H4), CHANGELOG and 2.0 release notes.
- [ ] E24 [2.0] (version 2.0.0 / code 2000 committed 2026-10-28, tag 2.0.0 local;
      merge to main and push pending) Version 2.0.0, merge refactor/2.0 -> main, tag, attach the
      R8 mapping (release-mappings/) to the GitHub release; F-Droid recipe
      supplies libjxl source (else JXL is "not bundled").

## M. MFSR (moved last, owner 2026-10-22: after everything else; whole programme [2.0.x] per owner cut 2026-10-25)
- [ ] C13 [2.0.x] MFSR "Wronski 2.0" (owner goal: improve beyond the paper;
      Z1 and C9-C12 merged into this item 2026-10-23 - the zipper, the
      dim-burst divergence and the core reconstruction are one programme,
      diagnosed together below before any fix):
      fix the core (merge resolution gain, single-frame reconstruction,
      alignment precision, motion-blur cases); no sharpening/exposure
      post-fixes (mfsr-wronski-2.md).
      Merged-in items (former standalone entries, tags kept):
      - [ ] Z1 [2.0.x] The MFSR linear (demosaiced RGB) DNG output shows
            a zipper pattern: dots and small squares alternating one
            dark, the next brighter, along edges and in fine detail.
            Find and fix the root cause in the MFSR merge / linear DNG
            path (CFA phase, per-channel accumulation or normalisation,
            green split, DNG writer layout, etc. - to be found), no
            masking filter. Prime suspect list overlaps the C13 core
            hypothesis below; diagnose with C13a, do not fix blind.
      - [ ] C9 [2.0.x] Dim-burst divergence vs the Python reference
            (70.5 dB).
      - [ ] C10 [2.0.x] Scale > 1 for zoom; S.3 thresholds; failed-tile
            alignment check (mfsr-paper-gaps.md).
      - [x] C10a s1/s2 and kernel-GAT A/B: keep IPOL (closed
            2026-10-22, mfsr-paper-gaps.md).
      - [x] C11a [2.0.x] UI toggles (kernel, S.3) in Settings -> Burst
            (closed 2026-10-23): KernelNet / Wronski (paper S.1) cycle
            button + S.3 on/off checkbox, persisted as mfsr_kernel /
            mfsr_s3 through MfsrSettings and applied by both the live
            merge and offline merges. Device check pending.
      - [x] C11b [2.0.x] Duplicate KernelNet model in the APK (closed
            2026-10-23): deleted the old assets/models
            kernelnet_aniso_v2_2_params.ncnn.{bin,param} pair and its
            dead PhotonCamera ncnn consumer
            (KernelNetNcnnProcessor.java, no callers); the live model
            is the sr-pipeline classpath resource used by
            KernelNet.bundledWeights(). Orphaned KernelNet JNI exports
            remain in ncnnMl.cpp / flownet_stub.cpp, harmless.
      - [ ] C11 [2.0.x] 150 MB FloatArray copy before the MFSR DNG;
            perf (CPU pyramid, KernelNet conv).
      - [ ] C12 [2.0.x] One 122 s first-merge outlier (likely thermal).
      - [ ] C14 [2.0.x] **MFSR auto exposure wiring, like HDR+** (owner
            2026-10-23). Implementation added 2026-10-26: merged-RGB
            meter + shared JPEG/JXL strength/tuning; 49 host tests pass,
            including four local linear-DNG sampled fixtures; JPEG/JXL GPU
            equivalence and unchanged-buffer checks pass on device. Remains open
            for same-scene GPU/save/VF and device-performance acceptance.
            [Audit](mfsr-exposure-audit.md). The HDR+ ZSL save job develops its merge
            through the adaptive path (`adaptivePreviewStrength` +
            `AdaptiveExposureTuning.fromOutputSettings` on the merged
            CFA); `MfsrZslSaveJob` previously passed only
            `RawDevelopmentSettings(denoise)` — the merged RGB develops
            at fixed reference exposure, as its own comment admits
            ("adaptive metering has no RGB leg yet"). Merged JPEGs
            therefore landed at whatever brightness the reference frame
            had. Work: give the adaptive meter an RGB leg (analyse
            the merged RGB, or resolve the correction on the
            reference CFA pre-merge and carry it through), wire the
            same strength/tuning HDR+ uses into both MFSR develop
            calls (JPEG and JXL), keep the linear DNG untouched.
            Gate: same-scene MFSR vs HDR+ vs single-frame JPEGs,
            brightness placement within a stated tolerance; preview
            (VF adaptive strength) matches the save.
      Research scan 2026-10-23 (online papers + open-source repos,
      6 angles; softness findings: mfsr-wronski-2.md):
      RE-EVALUATE BEFORE STARTING ANY ITEM BELOW: the scan's
      flow-error / density numbers are hypotheses, not measurements;
      Lecouat repo is 404 (paper-only), no Vulkan prototype was
      benchmarked, and exact Lecouat registration-error table values
      are unconfirmed. Re-check premises, licenses and costs first.
      Root-cause hypothesis from the scan: Wronski Eq.1 is order-0
      kernel regression (Nadaraya-Watson) - positive-lobe averaging,
      no local slope fit, no PSF/pixel-integration forward model,
      kernel ignores realised sample density. Predicts all measured
      symptoms (single-frame 0.91-0.96x AHD, merge 0.93-0.98x,
      noise 0.12-0.46x, 2x->1x no gain, narrower kernel = only noise).
      Ranked candidates (gain x evidence / Vulkan port cost):
      - [ ] C13a [2.0.x] Diagnosis FIRST (zero new code): synthetic
            known-shift bursts (0.05 px steps) through Align/Ica;
            histogram frac(flow), ICA residual RMS/bias per tile,
            phase-coverage per output pixel. Tests whether
            acceptance (R 0.95-0.99) != accuracy and whether
            fractional phases cluster. Gate for C13b-C13e.
      - [ ] C13b [2.0.x] Density-adaptive + order-1 kernel: log sample
            density/support vs sharpness; scale Omega by realised
            density (Drizzle pixfrac, BSD-3); then local-linear
            steering kernel regression (Takeda, equations only -
            toolbox license unstated, related patent ~2029).
      - [ ] C13c [2.0.x] Iterative MAP reconstruction (structural fix):
            stripped Lecouat et al. 2021 "Lucas-Kanade Reloaded" /
            Farsiu (forward DBW model + BTV, 10-20 it, ICA flow as
            init, per-tile affine refine against the reconstructed
            HR image, not the LR reference). Paper-only (repo 404).
            Only class that inverts blur inside the merge.
      - [ ] C13d [2.0.x] Phase-correlation alignment cross-check:
            Guizar-Sicairos single-step DFT (BSD-3, scikit-image),
            OpenCV phaseCorrelate (Apache-2.0) as offline oracle;
            ship only if C13a shows ICA RMS > ~0.1 px.
      - [ ] C13e [2.0.x] Diff vs ImageStackAlignator (GPL-3.0, only
            GPL-compatible + portable repo found): documents
            Wronski paper errors inherited via Jamy-L (subsampled
            tile structure tensor, kernel size, alignment outliers).
      - [ ] C13f [2.0.x] Extend KernelNet (do not replace): per-frame
            kernel prediction, density/SNR-conditioned width,
            learned deconv-kernel params; train on own bursts
            (AHD/RL oracles), export via existing pnnx->ncnn path.
      Calibration: pure-reconstruction gain realistically bounded
      at parity to ~+7 % edge sharpness (RL probe 1.07x); larger
      gains imply a learned prior + hallucination risk (teeth
      metric is the guardrail).
      Do NOT pursue: learned burst nets (DBSR CC BY-NC-SA =
      GPL-incompatible; BIPNet/EBSR no license; Burstormer/BSRT
      PyTorch transformer, no Vulkan 1.2 path, no Android repo
      found); RAFT as shipped flow (EPE ~2.9 px >> 0.1 px need,
      offline falsification oracle only); capture sharpening as
      the fix (diagnostic/headroom probe only, per Direction);
      PhotonCamera SABRE (checked 2026-10-23: full reverse-eng.
      MGC SABRE, but 1x denoise merge - no density/forward-model/
      2x help; usable only as same-burst A/B oracle and for
      rejection/merged-noise tuning reference).

## Device checks 2026-10-27 (Mali G615 phone)
- MFSR JPEG+DNG OOM fix (9d6aa37): 3 back-to-back 8-frame presses, all
  saved, no OutOfMemoryError.
- HDR+ perf (50a01af): byte-identical outputs vs the previous build.

## Done today (for reference)
Controller A1-A10 + ZSL merge jobs, MFSR back-to-back OOM fix, MFSR S.3 and
noise LUT, GALOSH removal, R8 for all releases with mapping archive,
F-Droid permission-check fix.
