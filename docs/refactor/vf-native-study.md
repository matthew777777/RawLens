# Native viewfinder (vf_vulkan_vf.cpp) split

`vf_vulkan_vf.cpp` (7888 lines) is one translation unit by design: its
first 3240 lines are an anonymous namespace holding the param layouts and
the global Vulkan `Context` every stage shares, and `dual_raw_vulkan.cpp`
compiles the whole module again via `#include "vf_vulkan_vf.cpp"`. Splitting
it into separately compiled .cpp files would give each its own copy of that
state (and of every `static`), so it is split into ordered `.inc` parts that
`vf_vulkan_vf.cpp` includes; the preprocessed source is unchanged.

| Part | Contents |
|---|---|
| vf_vulkan_vf_types.inc | includes, logging, push-constant layouts (static_assert-pinned), Context |
| vf_vulkan_vf_resources.inc | LUT, staging, bridge, AHardwareBuffer buffer/image imports |
| vf_vulkan_vf_pipelines.inc | compute pipeline creation for every stage |
| vf_vulkan_vf_shade_lut.inc | lens-shading images, stage teardown, grade LUT; closes the namespace |
| vf_vulkan_vf_viewfinder.inc | context lifecycle, RAW viewfinder superpixel dispatch |
| vf_vulkan_vf_grade_twins.inc | Phase-B grade stage, Direct-Log wait-free submit twins |
| vf_vulkan_vf_p010_preview.inc | true-10-bit P010 stage, record-preview monitor |
| vf_vulkan_vf_rcd.inc | RCD demosaic, fused RCD finalize + grade |
| vf_vulkan_vf_mhc_stab.inc | MHC demosaic, fused MHC + grade, stabilization warp |

Cuts sit between top-level items (each part starts at its item's comment
block); the file has no preprocessor conditionals and no __LINE__/__FILE__,
so concatenating the parts reproduces the original byte for byte (checked).

Proof: release arm64-v8a librawLensVfEgl.so and librawLensDualRaw.so built
before and after; `.text`, `.rodata`, `.data` and `.data.rel.ro` are
byte-identical in both libraries (298856 / 291400 bytes of .text). No
machine code changed, so no device run is needed for this step.

Not done here (behaviour changes, each needs its own study and device
parity run): de-duplicating the per-stage pipeline creators (createGrade /
Yuv / Preview / Rgb8 / Rcd / Mhc / Fused pipeline share most of their
descriptor-set boilerplate) and importP010Out-style long JNI bodies. The
V9 GPU wedge (docs/refactor/video-study.md) lives in this module's submit
path and is the first behaviour item to study.

## Algorithm check against references (2026-10-21)

Benchmark: Kodak 24 (r0k.us), sRGB-decoded to linear, RGGB mosaic; "hot"
= x4 exposure clipped at sensor white (glint stand-in). CPSNR on sRGB,
10 px border excluded. References: colour-demosaicing 0.2.5 (bilinear,
Malvar 2004, Menon 2007) and RawTherapee rcd_demosaic.cc (dev, RCD 2.3)
ported to a single-tile standalone (dbench/rcd_ref.cpp). RawLens CPU twins
via DemosaicBenchHarnessTest ($RAWLENS_DEMOSAIC_BENCH).

| Algorithm | CPSNR | CPSNR hot | HL chroma err | cyan % white HL |
|---|---|---|---|---|
| bilinear | 29.86 | 27.22 | 0.0252 | 2.83 |
| Malvar 2004 | 31.59 | 28.87 | 0.0185 | 0.91 |
| RawLens MHC (MhcReference) | 32.78 | 29.41 | 0.0186 | 0.81 |
| Menon 2007 | 33.63 | 30.60 | 0.0176 | 0.41 |
| RawTherapee RCD | 34.95 | 31.51 | 0.0166 | 0.47 |
| RawLens RCD (RcdReference) | 34.96 | 31.53 | 0.0165 | 0.41 |

- MHC fallback kernels equal Malvar-He-Cutler 2004 coefficients exactly;
  the edge-directed/bilateral additions are RawLens-own (no published
  source) but measure +1.2 dB over plain MHC and no worse in clipped
  highlights.
- RcdReference equals RawTherapee RCD (+0.01 dB).
- Device parity (GPU vs CPU twin): MHC mean 8.6e-5 max 9.8e-4; RCD mean
  1.1e-4 max 5.7e-3. Both shaders compute their references.

## Findings

- D1 RCD Direct-Log is unusable: 235 ms per 4K frame (dirs 55, green 51,
  opp 59, atG 69 ms) against a 33 ms budget; a 4 s take acquired 94 and
  graded 4 frames. Correct output, wrong performance; offered in the UI as
  a normal choice although VideoDemosaic's KDoc calls it A/B only.
- D2 (hypothesis, unconfirmed) MHC Direct-Log cyan on sun glints:
  vf_mhcyuv desaturates near-clip pixels to the WB neutral BEFORE the
  per-channel lens-shading gains, then the WB-folded CCM runs with no
  clip. A clipped glint therefore takes the colour of the shading map's
  channel imbalance at that position (cyan where the R gain is lowest).
  darktable/RawTherapee clip to the common white after per-channel gains.
  Needs a RAW sample of the scene (or the phone's shading map) to confirm.
- D3 RcdParityDeviceTest.rcdStillsRealData inits RCD without the grade
  stage (initRcdNative requires it) and needs /data/local/tmp/dng_win.bin:
  test bug, not app bug. The photo path (ensureRcdVulkan) inits in order.
- Photo RCD (JpegDemosaic.RCD) not yet exercised on device.
- D4 (fixed) Photo RCD (JpegDemosaic.RCD) produced structure-free striped
  garbage (4-9 dB on Kodak; most pixels 0, max 0.46). Cause:
  VfEglImport.blitBytesToBlob released the AHardwareBuffer it got from
  AHardwareBuffer_fromHardwareBuffer, which per the NDK docs "does not
  acquire any additional reference": the unbalanced release freed the CFA
  BLOB before RCD imported it (use-after-free). The release calls are
  removed; a scan of every native file found no other unbalanced
  fromHardwareBuffer/release pair. Proof (PhotoDemosaicKodakDeviceTest,
  8 Kodak mosaics through Gles31AmazeProcessor.processRcdCfa): photo RCD
  now equals CPU RcdReference + the rcd_final desaturation tail to a mean
  abs difference of 1e-4 (kodim01n 31.73 dB vs CPU 31.74).
- D5 (open, HL: excluded from this refactor) The shared still tail's
  sensor-white desaturation (DESAT_LO 0.70 .. 0.99, AMaZE and RCD alike)
  pulls any pixel with one channel above 70 % of sensor white toward
  neutral, washing out bright saturated colours: kodim23 RCD 38.4 dB raw
  -> 19.5 dB after the tail (AMaZE 19.5 too). Highlight handling is on the
  excluded list (HL); recorded for its own study.

## Line audit of the native module (2026-10-21)

Method: (1) Khronos validation layers 1.4.363.0 on the phone (debuggable
throwaway build, never committed) over MHC/RCD parity, photo Kodak,
start-failure, Direct-Log record and a ZSL soak; (2) static scans
(ignored VkResults, queue-submit locking, fence/semaphore lifetimes,
32-bit size math, unbalanced AHardwareBuffer refs); (3) mechanical
checks: every C++ param struct vs its shader's SPIR-V member offsets
(exact, all 15 shaders), every shipped .spv vs a fresh glslangValidator
11:16.6.0 compile of its source (all 15 byte-identical), spirv-val (all
valid), every dispatch vs its shader local_size (all match).

Fixed:
- A3 twin command buffers reset/re-recorded/resubmitted while pending
  (VUID-vkResetCommandBuffer-commandBuffer-00045, vkBeginCommandBuffer
  -00049, vkQueueSubmit-pCommandBuffers-00071) and A4 per-slot descriptor
  sets / fused param buffers rewritten while in use
  (VUID-vkUpdateDescriptorSets-None-03047): native code trusted the
  caller's 100 ms fd valve, which releases a slot whose work is still
  queued. New slot gate (Context::slotGate): each per-slot resource maps
  to its last submit's fence + semaphore; a submit waits at most 2 ms for
  that fence (fd and fence are unordered) and otherwise returns
  VFVK_BUSY (the frame is dropped exactly as before). RCD keeps its own
  pool and now records sem + set per pending submit.
- A5 exported-fd semaphores destroyed while their batch was pending
  (VUID-vkDestroySemaphore-semaphore-05149): now destroyed when the fence
  signals.
- A1 stabSubmitNative reset its fence after a timed-out wait
  (VUID-vkResetFences-pFences-01123): stabPending flag, BUSY until done.
- A6 pending RCD command buffers leaked at teardown.
- B4/B5 photo RCD leaked ~215 MB of graphics memory per photo: the native
  import caches acquire every buffer (the output cache never evicts) and
  the photo path allocated a fresh CFA BLOB + RGBA16F pair per photo. One
  set per size is now reused. 6 x 12 MP photos: PSS 975 -> 2048 MB before,
  flat 972-975 MB after.

Validation after the fix: zero A3/A4/A5 VUIDs; MHC parity and the ZSL soak
pass under validation (both failed before). V9 Direct-Log wedges, 5 runs
each, same phone: original 10/9/0/5/0, gate 1/0/0/0/0.

Open (logged, not changed):
- A2 camera RAW AHBs (format 57, usage 0x33, no GPU usage bits) imported
  as storage buffers, the plain path without VkExternalMemoryBufferCreateInfo
  (VUID-VkMemoryAllocateInfo-pNext-02384, VkImportAndroidHardwareBufferInfo
  ANDROID-buffer-01881, vkGetAndroidHardwareBufferPropertiesANDROID-buffer-
  01884, vkBindBufferMemory-memory-02986). Deliberate driver workaround
  ("not spec-shaped"); undefined on untested GPUs. Needs the sampled-image
  or copy tier as the spec-legal default per device.
- B1 teardown proceeds after a 2 s join while a VF worker may still be in
  vkWaitForFences on the fence/device being destroyed.
- B3 evictOldestInput destroys an import without checking pending use.
- A7 orphan asset shaders/vf/comp.spv (no source, never loaded).
- Perf: vf_mhcyuv dispatches every x and returns on odd x (half idle).
- A8 (fixed, synchronization validation) RCD mode acquire barriers used
  srcStage TOP_OF_PIPE with no access, which orders nothing: mode N could
  read or re-layout mode N-1's rgb/scratch while still being written
  (SYNC-HAZARD-WRITE-AFTER-WRITE; the matching read-after-write has the
  same cause). Harmless on the photo path (CPU waits each mode's fd) but
  real on Direct-Log, which submits modes 0-2 back to back. All RCD mode
  and fused-finalize acquires now wait on COMPUTE_SHADER/SHADER_WRITE.
  Sync validation rerun: zero SYNC-HAZARD; release: RCD/MHC parity, photo
  RCD scores identical (kodim01n 31.73 dB), Direct-Log record pass.
- B3 (fixed) evictOldestInput/Output now vkQueueWaitIdle before destroying
  an evicted import (rare path; the caps cover the live pools).
- A7 (fixed) orphan shaders/vf/comp.spv removed.
- A2 deferred by owner decision (2026-10-21): future work.
- Still open: B1 (teardown vs a VF worker stuck in vkWaitForFences) and
  the manual read of pipeline creation, lens-shading upload and stab warp.
