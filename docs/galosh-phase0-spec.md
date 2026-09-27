# GALOSH-on-Mali Phase 0 — frozen spec + device probe

Date: 2026-09-26. Upstream: https://github.com/luxgrain/GALOSH (Apache-2.0),
Vulkan V2 engine in `standalone/vk/`. Target: RawLens on the probed Mali device.

## 1. Device probe (adb, this session)

| Item | Value |
|---|---|
| Model / product / board | 25080RABDG / lapis_eea / mt6878 (MediaTek) |
| GPU | ARM Mali-G615 MC2, GLES 3.2 v1.r44p1 (`ro.hardware.vulkan=mali`) |
| Vulkan | 1.3.0 (pm `vulkan.version=4206592`), level 1, `vulkan.compute` — exceeds GALOSH 1.2 requirement |
| OS / ABI | Android 16 (SDK 36), arm64-v8a only, 16K page size (`max-page-size=16384` already used in `app/src/main/cpp/CMakeLists.txt`) |
| RAM | 8 GB total (~2 GB available at probe time) |
| App minSdk | 29 — Vulkan via NDK is fine; device is 1.3 |

No on-device `vulkaninfo`; float16/subgroup caps need the Phase 1 in-app probe.

## 2. Frozen decisions

* Path: GALOSH-RAW o32 host (`standalone/vk/galosh_vk.c`) only — Bayer CFA
  in/out matches `UnpackedRawCfa`. YUV engine out of scope.
* Numeric contract: FP16 inter-phase storage, FP32 compute, no
  RelaxedPrecision/fast-math. Parity gate: >=69 dB PSNR vs CPU FP32
  (upstream: 69.7–70.6 dB).
* Photo defaults: `strength=1.0, luma=1.0, chroma=1.0`, blind `noise=fit`
  every frame, `wht=8`, `upsample=jinc` (paper config; `fast` is
  quality-neutral per `standalone/README.md` and a candidate mobile default
  after parity is proven; `wht=4` costs -1.21 dB high-noise — speed toggle only).
* Subgroup path: Mali Valhall subgroups are 16-wide; the B7g SG kernel pins
  32, so expect the classic `o32_pass12` fallback (`GALOSH_SG=0` equivalent)
  on Mali. Confirm via in-app `subgroupSizeControl` probe; never require it.
* `check_no_int64.sh` constrains only the INT fixed-point path — not ours.
* License: keep upstream Apache-2.0 `LICENSE`/`NOTICE` attribution in
  `NOTICE.md`; ADOPTERS.md entry is a friendly request, not a condition.

## 3. Slider mapping (mirrors RawNind)

RawNind semantics kept (`DenoiseSettings.kt`, `RawNindDenoiser.kt`): master
strength is a post-blend `out = s*den + (1-s)*src`, no re-inference.

* Master 0–100% → post-blend `s` (instant, same as `aiStrength`).
* Luma / Chroma sliders → GALOSH `luma_str` / `chroma_str` args (re-run).
* Advanced: WHT 8/4, upsample jinc/fast. Noise mode fixed `fit` for stills
  (`hold/every/ema` are video-only, deferred).
* New `GaloshSettings` mirrors `DenoiseSettings` incl. `saveOriginalDng`.

## 4. RawLens mirror points (Phase 1 targets)

* `GaloshDenoiser.kt` — `UnpackedRawCfa` pack/unpack + post-blend.
* `RawDevelopmentCoordinator.denoiseCaptureCfaGalosh()` — denoise-once;
  `develop()` stays GALOSH-free (same as AI-free rule).
* `DngSaver.saveGaloshDenoised()` via `FloatCfaDngWriter` (same float-DNG
  flavor as `saveAiDenoised`); `CaptureFileNames.TYPE_GALOSH` →
  `IMG_<ts>_GALOSH.dng` / `IMG_<ts>_F00GALOSH.dng`.
* Native `app/src/main/cpp/galosh/` lib + JNI, NDK `vulkan` linkage (pattern
  exists: `srvulkan` in CMakeLists), SPIR-V in `assets/spirv/`, 16K page-size
  link flag. Banded submissions stay (2-core MC2 + watchdog).

## 5. Phase 1 gates (must answer before/while coding)

1. In-app caps probe: `shaderFloat16`, 16-bit storage, `subgroupSizeControl`,
   subgroup size, compute limits.
2. CFA normalization match: GALOSH blind fit expects linear sensor-scale
   Bayer; verify against RawLens normalized CFA (black/white-level handling).
3. Full-res memory/perf budget on G615 MC2 (band sizing, tile cores).
4. Optional later: seed P_ALPHA/P_SIGMA_SQ from `CfaNoiseModel` (ext-model
   override exists); Phase 1 stays pure blind.
