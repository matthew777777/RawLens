# GALOSH-on-Mali Phase 1 — probe results + scaffold

Date: 2026-09-26. Device: 25080RABDG (Mali-G615 MC2), Android 16.
Raw caps: `docs/galosh-caps-mali-g615.json` (via `GaloshCapsProbeTest`).

## 1. Probe verdict: all Phase 0 gates pass

| Gate | Result |
|---|---|
| Vulkan 1.2+ | 1.3.247 (loader 1.4.0) — pass |
| `shaderFloat16` | true — pass |
| `storageBuffer16BitAccess` | true — pass |
| 43/43 SPIR-V modules create on-driver | pass (`galosh: init: 43 shader modules`) |

## 2. Subgroup decision (answers Phase 0 gate 1)

`VK_EXT_subgroup_size_control` is present and enabled, but
`minSubgroupSize == maxSubgroupSize == 16`: subgroup 32 **cannot** be
pinned on this Mali driver. Phase 2 must use the classic `o32_pass12`
8x8 path (`GALOSH_SG=0` equivalent), never `o32_pass12_sg`. The SG
shader stays vendored for other GPUs; selection is a runtime caps check.

## 3. Budgets for Phase 2 (from caps)

* One queue family (compute-capable, 2 queues): single-queue sequential
  submission per HOST_BLUEPRINT, no async overlap assumed.
* 32 KB shared memory, 256 B push constants, 2 GB storage-buffer range,
  16384 max allocations — all comfortably above GALOSH o32 needs.
* 7.7 GB device-local heap (unified memory): full-res float planes fit,
  but keep banded submissions (2 shader cores + watchdog).

## 4. Scaffold delivered

* `app/src/main/cpp/galosh/`: `galosh_caps.cpp` (one-shot JSON probe),
  `galosh_host.cpp` (instance/device + 43-entry manifest loader),
  `galosh_jni.cpp` (`GaloshVulkan` bridge). CMake `galosh` lib, NDK
  `vulkan` linkage, 16K page-size flag, all 4 ABIs in the APK.
* `app/src/main/assets/spirv/galosh/`: 43 `.spv` (NDK r27 glslc,
  `--target-env=vulkan1.2 -O`) + `.comp` sources + rebuild README.
* `GaloshVulkan.kt` facade + `GaloshCapsProbeTest` (maintained gate:
  asserts the contract, logs `caps=` JSON, loads all modules).
* `NOTICE.md`: GALOSH Apache-2.0 attribution (pin `11de0593`).

## 5. Open for Phase 2

* CFA normalization match (Phase 0 gate 2): GALOSH blind fit vs RawLens
  normalized CFA scale/black-level — verify before first denoise run.
* 51-dispatch pipeline port behind `GaloshContext` + parity gate (≥69 dB
  vs CPU FP32 reference) before any quality claim.
* Band sizing / full-res perf on the MC2 (Phase 0 gate 3).
