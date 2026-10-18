# Direct-Log Cross-Device Test Plan (build 1.0.284)

Build: `app/build/outputs/apk/debug/RawLens-1.0.284-debug.apk` (debug-signed,
install with `adb install -r`). Needs Android 11+; 10-bit LOG realistically
needs Android 13+ with a HW HEVC encoder; the 8-bit SDR fallback needs any
HW HEVC Main encoder + Vulkan.

## What changed (why this build exists)

- Encode-size ladder: 4K tried first only when the Video tab selects 4K;
  1080p is the new default. Failures step down instead of failing the take.
- Per-size Main10 gate: the app checks what the encoder advertises for the
  exact WxH@fps before configuring.
- ImageWriter path (API 33+): Surface-only Main10 encoders record 10-bit
  through a P010 ImageWriter — take and stab warp pass alike.
- Bitrate clamped to the encoder's advertised max; 1080p default bitrate is
  pixel-proportional to the 4K default.
- AE fps range validated against the sensor (fails fast, names ranges).
- New Video tab row: "LOG resolution: 1920x1080 / 3840x2160".

## Scenarios

1. **Flagship (4K Main10, P010):** default prefs → expect 4K LOG take,
   `input=DIRECT_P010` in logcat, no `encode fallback` line.
2. **Mid-range (1080p-only Main10):** expect `encode fallback 3840x2160 ->
   1920x1080` (4K pref) or straight 1080p (default pref). File must play.
3. **Surface-only Main10 (no P010):** expect `input=IMAGE_WRITER` in the
   `output profile=` line. Record 10 s, then run the stab warp pass.
4. **No Main10 at all (BT709 profile):** expect `input=SDR_EGL` in the
   `output profile=` line and a playable 8-bit SDR file. S-Log3/HLG on the
   same phone must fail naming the 10-bit requirement.
5. **Resolution toggle:** Video tab → LOG resolution → 4K on a 1080p-only
   phone must still land a 1080p file via fallback.
6. **Stab warp on writer/8-bit takes:** run the warp pass on files from
   scenarios 3-4; expect `stab encoder=... input=IMAGE_WRITER|SDR_EGL`.

## Logcat

```
adb logcat -s "DirectLog:V" "DirectLogStab:V" | grep -E "output profile|encode fallback|no HEVC|no AE fps|writer P010|codec P010"
```

Key lines: `output profile=... codec=... input=DIRECT_P010|IMAGE_WRITER`,
`encode fallback WxH -> WxH (...)`, `writer P010 yStride=...`,
`tried ...` inside failure messages.

## Report template (paste per device)

- Device / SoC / Android version:
- Scenario (1-5):
- Result (file plays Y/N, length, size):
- Matching logcat lines:

## Emulator-verified (rawlensemu, API 34, swiftshader, 2026-10-17)

`LogVideoProbeDeviceTest` passes on-device: all Main10/Main8 selectors
false (software codecs only), `writerPathAvailable=true`,
`maxBitrateMbps=100` fallback, `bitrateModes=[CBR]`, null plans with null
SDR — the failure-branch inputs are exactly what the recorder reports.

`DirectLogRecordDeviceTest` reaches encoder negotiation on the emulator
(RAW_SENSOR 1856x1392 present, fused Vulkan init OK) and fails with the
new tried-list error — no wedge, no crash:
`no HEVC Main10 encoder for 3840x2160@30fps (tried 3840x2160@30fps: no
HW Main10 encoder; 1920x1080@30fps: no HW Main10 encoder;
3840x2160@30fps: no HW Main encoder; 1920x1080@30fps: no HW Main encoder)`.

App-level path verified: via MainActivity the take requests 1920x1080
(the new 1080p default pref) and lands on `direct-log start failed`
status handling.

## Hardware-verified (Xiaomi 25080RABDG, MT6878, Android 16, 2026-10-17)

Headless `DirectLogMatrixDeviceTest` (BT709/SLOG3/HLG x 1080p/4K x
24/30fps + warp), all passing:

- BT709 1080p24/30, 4K24/30 record via `input=SDR_EGL` on the Main-only
  MTK encoder: 10-33 MB files, graded 77-132 frames, dropped <= 5,
  degraded 0, errors 0.
- SLOG3/HLG refuse with the designed message (10-bit required).
- Stab warp on an 8-bit take: ok, decoded=132, warped=132,
  degraded=0, 41 MB output.
- Two real bugs found and fixed by this pass: missing Vulkan descriptor
  writes in the rgb8 submit (faulted Mali), and EGL context migration
  across gate threads (bind-present-unbind per frame).

## Known limits of this build (not bugs for this round)

- 8-bit SDR is BT709-only and its pixels are sRGB-transfer math in a
  BT.709 container (same approximation as the take monitor). Watchable,
  not color-critical.
- API 30-32 without advertised P010: direct path only, no writer fallback
  (ImageWriter.Builder is API 33+).
- New-device code paths (ladder fallback, writer input, 8-bit convert +
  EGL present, warp writer/8-bit) have unit coverage for the selection
  logic and compile coverage for the native side, but pixels, stride
  edge cases, and encoder-specific behavior need this hardware pass.
  Watch for: `rgb8 convert failed`, `sdr present failed`, green/pink
  frames (convert bug), and `encode fallback ... SDR` lines.

## Raw-VF parity vs git HEAD (Xiaomi 25080RABDG, MT6878, A16)

Clean `git worktree` at HEAD `b039ed3` vs this tree, same device,
same flags. Every probe behaves identically:

- MHC `gpuMatchesCpuReference` (incl. 25 ms perf stage): pass / pass.
- RCD `gpuMatchesCpuReference`: pass / pass.
- RCD `fusedFinalizeMatchesSplit` (uncommitted, this tree only): pass;
  `rcdStillsRealData` / `rcdRapidSequence`: skip (need data flags).
- GradeYuv 5 proofs + timing: pass / pass.
- GradeYuv `fullResFusedProof`: FAIL both, timing only
  (HEAD 35.1 ms, this tree 37.7 ms vs 30 ms budget; heat-sensitive).
- VfVulkan init/reinit x3: pass / pass.
- VfVulkan `syntheticRecordFirstFrameGrowsFile`: FAIL both,
  byte-identical `P010 center implausible: [43712, 32896, 32640]`
  (pre-existing synthetic-path oracle issue, untouched by this work).

## 1440p + high-quality bitrates (Xiaomi 25080RABDG)

- Ladder is now 4K / 1440p / 1080p; defaults 75 / 45 / 30 Mbps CBR
  (1080p was ~18.75 Mbps before). Res toggle cycles 1080 → 1440 → 4K.
- Negotiation is size-agnostic: 1440p flows through direct-P010,
  ImageWriter, and 8-bit SDR plans (unit-covered for all three modes).
- Device: BT709 1440p24/30 record ~25 MB / ~40 Mbps, 0 dropped,
  0 degraded, warp 132/132. SLOG3/HLG 1440p refuse by design (no
  Main10 encoder on this phone) with a tried-list that walks
  1440p → 1080p. 10-bit 1440p needs a Main10-capable phone to prove
  on hardware; negotiation picks it when the codec exists.
