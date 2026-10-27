// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vulkan zero-copy viewfinder: import the camera HAL's RAW AHardwareBuffer
// (which EGL cannot import on this gralloc) as R16_UINT, run the superpixel
// compute shader, and write the RGBA8 result into an app-allocated export AHB
// that GL re-imports for tonemap + present.
//
// All entry points run serialized on the viewfinder GL worker. Failures return
// stage codes so Kotlin falls back (EGL-direct, then the CPU sampler).
#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <map>
#include <memory>
#include <unordered_map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "vulkan_pipeline_cache.h"

#define LOG_TAG "RawLensVfVk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Result codes for VfVulkan.compute/ensureOutput/init.
#define VFVK_OK 0
#define VFVK_NOT_INITIALIZED 1
#define VFVK_NO_EXTENSION 2
#define VFVK_DEVICE_FAILED 3
#define VFVK_PIPELINE_FAILED 4
#define VFVK_INPUT_IMPORT_FAILED 5
#define VFVK_OUTPUT_IMPORT_FAILED 6
#define VFVK_SUBMIT_FAILED 7
#define VFVK_BAD_ARGUMENT 8
#define VFVK_DEVICE_LOST 9
// computeNative only: the previous dispatch is still in flight (entry
// check) or this one did not finish inside the bounded wait. Benign
// backpressure — the caller skips the frame, never falls back a tier.
// Distinct from SUBMIT_FAILED, which means something actually broke.
#define VFVK_BUSY 10
// computeCopyNative only: staging alloc, input lock, memcpy or flush failed.
// The zero-copy tiers are unaffected (different mechanism); Kotlin falls
// through to the next tier exactly like any other nonzero code.
#define VFVK_COPY_UPLOAD_FAILED 11
// computeImageNative only: the HAL buffer cannot be exposed as a sampled
// R16_UINT Vulkan image (BLOB layout, non-R16 external format, missing
// sampled feature, or no compatible memory type). This is the
// rejection: the stream is not zero-copyable, so Kotlin latches the image
// tier dead for the session and uses the upload (copy) tier instead of
// retrying the import per frame.
#define VFVK_IMAGE_UNSUPPORTED 12
// Viewfinder fence wait. The wait runs UNLOCKED (see computeNative),
// so it can be generous: 500ms. The old 2s wait held the shared queue
// mutex for whole fused record dispatches queued ahead (~30-50ms),
// stalling record submits past budget (drops = PTS gaps). A shorter
// bound was tried and reverted: record-mode offers phase-lock to the
// fused cycle, so the VF always queues behind a fresh ~30ms dispatch
// and any bound under that freezes the VF at 0fps (measured).
#define VFVK_VF_WAIT_NS 500000000ull

// Maximum compute extent per axis. The viewfinder caps its own geometry far
// below this (preview budgets); the Direct-Log recorder dispatches up to
// half sensor res (e.g. 2040x1530 for a 4080x3060 sensor at step 2), so the
// backstop must admit it. Cost scales linearly with extent; sensors much
// larger than this device want step 4 or tiling instead (follow-up).
#define VFVK_MAX_EDGE 4096

// Covers the full ImageReader pool (maxImages=22): the ZSL ring alone pins 8
// buffers, so a smaller cap FIFO-thrashes and re-imports 25 MB every frame
// (imports map existing pool memory; they allocate no new pages).
#define INPUT_CACHE_CAP 24
// Covers every live viewfinder export (BGU's 3 rotating slots + the legacy
// single export): steady state never evicts, so no dispatch-time churn.
#define OUTPUT_CACHE_CAP 6

namespace {

// Serializes the shared device across threads: the viewfinder GL worker and
// a Direct-Log recorder camera thread submit interleaved work (each holds the
// lock for one compute+grade pair, ~5-7ms, inside the 33ms budget). Without
// this, concurrent submits race the shared command buffer, descriptor set,
// import caches and fence. No entry point nests, so a plain mutex suffices.
static std::mutex vkMutex;
// Viewfinder dispatches with an unlocked fence wait in flight (0/1:
// the GL worker is single-threaded). Teardown/reset join on this
// before destroying imports/outputs the dispatch reads/writes.
static std::atomic<int> vfComputeInFlight{0};
// Bumped (under vkMutex) whenever the context is destroyed, so a
// worker returning from an unlocked wait can tell its captured state
// is gone without dereferencing freed memory.
static std::atomic<uint64_t> vkGeneration{0};

// Mirrors the push-constant block in vf_superpixel.comp exactly (112
// bytes): the shading tail (rows/cols/enable + active rect) rides with
// the f16 record twin; the viewfinder leaves it zeroed (it shades in
// GL). The ivec4 pair needs 8 bytes of pad after pitch (72 -> 80).
struct SuperpixelParams {
    int32_t chans[4];
    // Q6 fixed-point blackQ/denQ bit-cast to float (VfLevels.kt): this
    // struct only memcpys the words into the push block; the shader
    // recovers the ints with floatBitsToInt. Never interpret as float.
    float black[4];
    float invRange[4];
    int32_t quadBase[2];
    int32_t frameSize[2];
    int32_t step;
    int32_t pitch;
    int32_t pad1[2];
    int32_t shadeDims[4];  // rows, cols, enable, 0
    float shadeActive[4];  // active l, t, 1/w, 1/h (sensor pixels)
};
static_assert(sizeof(SuperpixelParams) == 112, "push constant layout drift");

// Mirrors the push-constant block in vf_loggrade.comp exactly (96 bytes:
// ivec2 dims needs 16-byte alignment before the first vec4).
struct GradeParams {
    int32_t dims[2];
    int32_t pad[2];
    float gains[4];
    float ccm[3][4];
    float misc[4];  // exposureEV, bypass, 0, 0
};
static_assert(sizeof(GradeParams) == 96, "grade push constant layout drift");

// Mirrors vf_gradeyuv.comp exactly (96 bytes): full-res dims, FP16
// source dims, WB+exposure-folded 3x3 CCM rows, bypass/strides,
// fullRgb/outMode. Semi-planar P010 in OUR OWN buffer (tight packing
// validated per submit).
struct GradeYuvParams {
    int32_t dims[2];
    int32_t srcDims[2];
    float ccm[3][4];
    float misc[4];   // bypass, yStrideW, uvStrideW, uvBaseOffB
    float misc2[4];  // fullRgb, outMode, reserved, 0
};
static_assert(sizeof(GradeYuvParams) == 96, "grade-yuv push layout drift");

// Mirrors vf_p010preview.comp exactly (32 bytes): preview dims, encode
// (P010-domain) dims, P010 strides + downsample box. The record monitor
// shows this INSTEAD of a separately-demosaiced viewfinder: the preview
// IS the staged encode pixels, so no WB/CCM/grade rides along.
struct PreviewParams {
    int32_t dims[2];
    int32_t srcDims[2];
    int32_t strides[4];  // yStrideW, uvStrideW (u16), uvBaseOffB, box
};
static_assert(sizeof(PreviewParams) == 32, "preview push layout drift");

// Mirrors vf_p010torgb8.comp exactly (28 bytes): full-res out dims (==
// P010 dims, no box), P010 strides. The 8-bit SDR fallback converts the
// staged P010 1:1 instead of box-downsampling like the preview kernel.
struct Rgb8Params {
    int32_t dims[2];
    int32_t srcDims[2];
    int32_t strides[3];  // yStrideW, uvStrideW (u16), uvBaseOffB
};
static_assert(sizeof(Rgb8Params) == 28, "rgb8 push layout drift");

// Mirrors vf_stabwarp.comp exactly (68 bytes): encode dims (out == src),
// tight-input P010 strides, staging-output P010 strides, UV base offsets,
// and the row-major out->source homography (last row 0 0 1, affine).
struct StabWarpParams {
    int32_t dims[2];
    int32_t srcStrides[2];  // yStrideW, uvStrideW (u16 units, tight input)
    int32_t outStrides[2];  // yStrideW, uvStrideW (u16 units, staging)
    int32_t uvBase[2];      // srcUvBaseOffB, outUvBaseOffB
    float mat[9];
};
static_assert(sizeof(StabWarpParams) == 68, "stab warp push layout drift");

// Fold WB gains + exposure into CCM rows (record-path order: wb=rgb*gains,
// ccm=CCM*wb, ex=ccm*exp2(ev)): out[r][i] = ccm[r][i]*gains[i]*evMult.
// Shared by the fused + grade-YUV submits so the two record paths agree
// by construction; the probe-only loggrade stage applies gains in-shader.
static void foldGradeCcm(const float* gf, float rows[3][4]) {
    const float evMult = exp2f(gf[13]);
    for (int r = 0; r < 3; ++r) {
        for (int i = 0; i < 3; ++i) rows[r][i] = gf[4 + r * 3 + i] * gf[i] * evMult;
        rows[r][3] = 0.0f;
    }
}

// Mirrors vf_rcd.comp exactly (72 bytes): crop dims, crop origin (CFA
// phase), quad-offset map, per-quad black/invRange, sensor pitch, mode.
struct RcdParams {
    int32_t dims[2];
    int32_t origin[2];
    int32_t chans[4];
    float black[4];
    float invRange[4];
    int32_t pitch;
    int32_t mode;
};
static_assert(sizeof(RcdParams) == 72, "rcd push constant layout drift");

// Mirrors vf_rcdyuv.comp RcdYuvParams exactly (160 bytes, std430):
// the RCD block (crop dims/origin/chans, per-quad black/invRange,
// pitch) + the grade block (export dims, folded 3x3 CCM, P010
// strides/base, fullRgb=1, profile). SSBO because 160 bytes exceeds
// the 128-byte push-constant guarantee (same reason FusedParams is an
// SSBO). GradeYuvParams field order is preserved (misc/misc2) so
// foldGradeCcm output drops in unchanged.
struct RcdYuvParams {
    int32_t cDims[2];
    int32_t cOrigin[2];
    int32_t cChans[4];
    float cBlack[4];
    float cInvRange[4];
    int32_t cPitch;
    int32_t pad0;
    int32_t oDims[2];
    float gCcm[3][4];
    float gMisc[4];   // bypass(0), yStrideW, uvStrideW, uvBaseOffB
    float gMisc2[4];  // fullRgb(1), outMode, reserved, 0
};
static_assert(sizeof(RcdYuvParams) == 160, "rcd-yuv ssbo layout drift");

// Mirrors vf_mhcyuv.comp FusedParams exactly (192 bytes, std430): the
// MHC block (crop dims/origin/chans, FMA bias + inv-range per quad,
// pitch) + the grade block (output dims, WB gains, folded 3x3 CCM,
// exposure, P010 strides/base) + the lens-shading tail (map
// rows/cols/enable + active rect; gains ride binding 4) + the output
// profile word (sDims.w). The grade is a direct per-pixel encode, not
// a LUT fetch (binding 3 is reserved for upcoming LUT support). No
// bypass word: fused is always full-RGB MHC and never bypasses.
struct FusedParams {
    int32_t cDims[2];
    int32_t cOrigin[2];
    int32_t cChans[4];
    float cNormBias[4];
    float cInvRange[4];
    int32_t cPitch;
    int32_t pad0;
    int32_t oDims[2];
    float gGains[4];
    float gCcm[3][4];  // folded rows (shader names them gCcmR/G/B)
    float gMisc[4];  // exposureEV, yStrideW, uvStrideW, uvBaseOffB
    int32_t sDims[4];  // shade rows, cols, enable, outMode
    float sActive[4];    // shade active l, t, 1/w, 1/h (sensor px)
};
static_assert(sizeof(FusedParams) == 192, "fused params layout drift");

struct ImportedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

// Byte-exact view of the sensor plane: no pitch/tiling ambiguity, unlike images.
struct ImportedBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    // AHB row stride in pixels: authoritative row math for the imported
    // layout (the Camera2 plane stride can disagree on padded grallocs).
    uint32_t stridePixels = 0;
    // AHB pixel dims: the full-res geometry the bridge rung copies (the push
    // block's frameSize is the stepped OUTPUT geometry, not the import).
    uint32_t width = 0;
    uint32_t height = 0;
};

struct PackedRcdState {
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipelines[4] = {};
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet sets[4] = {};
    VkSampler sampler = VK_NULL_HANDLE;
    VkImage images[3] = {};
    VkImageView views[3] = {};
    VkDeviceMemory memory[3] = {};
    VkQueryPool queries = VK_NULL_HANDLE;
    float stageMs[4] = {};
    uint64_t shaderHash = 0;
    uint32_t timestampBits = 0;
    bool yuv = false;
    int width = 0, height = 0, localX = 8, localY = 8;
    bool initialized = false;
};

struct Context {
    PackedRcdState packedRcd;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    // Direct-Log f16 superpixel variant (same bindings + push block, so the
    // shared set layout and recorder descriptor sets are reused as-is; only
    // the shader module (rgba16f store) differs). Viewfinder keeps `pipeline`.
    VkPipeline f16Pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    // True while fence guards a submitted-but-unreaped viewfinder
    // dispatch. The fence starts unsignaled with nothing in flight, so
    // the flag (not the fence state) is the source of truth.
    bool computeArmed = false;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProps = nullptr;
    // Optional wait-free handoff (grade submit -> EGL native fence): binary
    // semaphore signal exported as a sync fd. Absent -> blocking fallback.
    PFN_vkGetSemaphoreFdKHR getSemFd = nullptr;
    bool hasSemaphoreFd = false;
    // shaderFloat16 (VK_KHR_shader_float16_int8): the fused record kernel
    // selects its fp16 SPIR-V when true, the fp32 twin otherwise.
    bool hasF16Math = false;
    bool hasExtendedStorageFormats = false;
    VkPipelineCache pipelineCache = VK_NULL_HANDLE;
    std::string pipelineCachePath;  // empty = in-memory cache only
    std::map<AHardwareBuffer*, ImportedBuffer> inputs;
    std::vector<AHardwareBuffer*> inputOrder;
    // Sampled-image zero-copy (Adreno-safe primary): the HAL
    // buffer imported as an R16_UINT VkImage, fetched via usampler2D. Own
    // pipeline/layout/set because binding 0 is a combined sampler, not an
    // SSBO. Imports are cached by pointer like the buffer inputs.
    VkDescriptorSetLayout imgSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout imgPipelineLayout = VK_NULL_HANDLE;
    VkPipeline imgPipeline = VK_NULL_HANDLE;
    VkDescriptorPool imgPool = VK_NULL_HANDLE;
    VkDescriptorSet imgSet = VK_NULL_HANDLE;
    VkSampler rawSampler = VK_NULL_HANDLE;
    bool imgLutBound = false;
    std::map<AHardwareBuffer*, ImportedImage> imgInputs;
    std::vector<AHardwareBuffer*> imgInputOrder;
    // TRANSFER bridge-copy rung: when the HAL allocation imports but direct
    // compute reads misbehave, the copy engine (vkCmdCopyBufferToImage) is a
    // separate read path that still avoids every CPU copy. The camera buffer
    // is imported once (shared `inputs` cache), copied per frame into this
    // owned R16 image, and the proven img shader reads the owned image. One
    // slot: reallocated on geometry change (lens switch), torn down with the
    // device. Written only under vkMutex.
    VkImage bridgeImage = VK_NULL_HANDLE;
    VkDeviceMemory bridgeMemory = VK_NULL_HANDLE;
    VkImageView bridgeView = VK_NULL_HANDLE;
    uint32_t bridgeW = 0;
    uint32_t bridgeH = 0;
    AHardwareBuffer* outputBuffer = nullptr;
    // Current viewfinder output import (non-owning copy of the cached entry;
    // OutSwap keeps swapping it by value for recorder twins).
    ImportedImage output;
    // Output import cache: BGU rotates 3 export slots, so a single slot
    // destroyed + re-imported every dispatch. The map owns the imports;
    // eviction never takes the current buffer (the one in-flight dispatch,
    // if any, references it — see the reap gate in computeNative).
    std::map<AHardwareBuffer*, ImportedImage> outputs;
    std::vector<AHardwareBuffer*> outputOrder;
    // GPU-copy tier (motioncam-style staging memcpy): host-visible buffer the
    // CPU fills per frame; the same superpixel pipeline reads it instead of
    // the imported HAL allocation. For HALs whose external-memory import
    // reads back zeros (Adreno 750) while lock+memcpy works. Session-
    // independent: sized on demand, kept across reset, torn down with the
    // device. Written only under vkMutex.
    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    void* stagingMapped = nullptr;
    VkDeviceSize stagingSize = 0;
    bool stagingCoherent = true;
    // Normalize LUT (superpixel binding 3): 4 sites x 65536 codes,
    // host-visible + coherent, mapped persistently. Fixed full-range size
    // so uploads are pure memcpys (no per-frame alloc, no descriptor
    // churn); the tail beyond the uploaded prefix is 0xFF (codes above
    // the LUT bound always normalize to 255). Default fill is 0x00
    // (black): a dispatch before the first upload reads black, which the
    // tier probe catches, instead of garbage. Written under vkMutex; a
    // torn read only affects a BUSY-skipped (unpresented) dispatch.
    VkBuffer lutBuffer = VK_NULL_HANDLE;
    VkDeviceMemory lutMemory = VK_NULL_HANDLE;
    void* lutMapped = nullptr;
    bool lutCoherent = true;
    bool lutBound = false;
    uint32_t lutRow = 0;
    // Phase-B grade stage (probe-only; the viewfinder never initializes it):
    // superpixel export -> WB/CCM/log -> graded export. Shares the device,
    // queue, fence and command buffer; imports are cached by AHB pointer
    // (the probe owns a fixed pair, cleared by reset/teardown).
    VkDescriptorSetLayout gradeSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout gradePipelineLayout = VK_NULL_HANDLE;
    VkPipeline gradePipeline = VK_NULL_HANDLE;
    VkDescriptorPool gradePool = VK_NULL_HANDLE;
    VkDescriptorSet gradeSet = VK_NULL_HANDLE;
    // Recorder-isolated state (Direct-Log wait-free twins): the viewfinder
    // keeps the shared single-slot output + descriptor set above. Sharing
    // either across in-flight frames destroys/re-writes objects a queued
    // submit still references (device loss on strict drivers), so the twins
    // use per-pointer output imports and per-slot descriptor sets. Slot
    // reuse stays gated by the caller's EGL fence, downstream of the work.
    // Command buffers are per slot for the same reason (reset-while-in-
    // flight); CmdSwap swaps them in with RAII restore.
    VkCommandBuffer twinComputeCmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkCommandBuffer twinGradeCmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDescriptorPool recComputePool = VK_NULL_HANDLE;
    VkDescriptorSet recComputeSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDescriptorPool recGradePool = VK_NULL_HANDLE;
    VkDescriptorSet recGradeSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    std::map<AHardwareBuffer*, ImportedImage> recOuts;
    // True-10-bit stage (Direct-Log P010): FP16 superpixel export in,
    // codec-input P010 bytes out. STORAGE_IMAGE in + STORAGE_BUFFER out
    // needs its own set layout; sets are per slot (3) like the twins.
    VkDescriptorSetLayout yuvSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout yuvPipelineLayout = VK_NULL_HANDLE;
    VkPipeline yuvPipeline = VK_NULL_HANDLE;
    VkDescriptorPool yuvPool = VK_NULL_HANDLE;
    VkDescriptorSet yuvSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    std::map<AHardwareBuffer*, ImportedBuffer> yuvOuts;
    // Record-preview stage (Direct-Log monitor): staged P010 storage
    // buffer in + RGBA8 preview image out. Own set layout + per-slot
    // sets and twins; preview imports are cached by pointer. The P010
    // input reuses the yuvOuts validation (same buffers the encode
    // submit just wrote, same-queue ordered ahead of this dispatch).
    VkDescriptorSetLayout previewSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout previewPipelineLayout = VK_NULL_HANDLE;
    VkPipeline previewPipeline = VK_NULL_HANDLE;
    VkDescriptorPool previewPool = VK_NULL_HANDLE;
    VkDescriptorSet previewSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkCommandBuffer twinPreviewCmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    std::map<AHardwareBuffer*, ImportedImage> previewOuts;
    // 8-bit SDR fallback (Direct-Log): staged P010 in + full-res RGBA8
    // out for the EGL encoder present. Own set layout + per-slot sets
    // and twins; imports cached by pointer like the preview path.
    VkDescriptorSetLayout rgb8SetLayout = VK_NULL_HANDLE;
    VkPipelineLayout rgb8PipelineLayout = VK_NULL_HANDLE;
    VkPipeline rgb8Pipeline = VK_NULL_HANDLE;
    VkDescriptorPool rgb8Pool = VK_NULL_HANDLE;
    VkDescriptorSet rgb8Sets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkCommandBuffer twinRgb8Cmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    std::map<AHardwareBuffer*, ImportedImage> rgb8Outs;
    // RCD demosaic (Direct-Log record path): STORAGE_BUFFER CFA in +
    // STORAGE_IMAGE rgb (read-write across the 3 mode dispatches). Own
    // set layout + per-slot sets and twin command buffers; rgb imports
    // are cached by pointer like the other recorder maps.
    VkDescriptorSetLayout rcdSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout rcdPipelineLayout = VK_NULL_HANDLE;
    VkPipeline rcdPipelines[4] = {};
    VkDescriptorPool rcdPool = VK_NULL_HANDLE;
    // Each slot owns a descriptor set per mode: modes are submitted without CPU waits.
    VkDescriptorSet rcdSets[12] = {};
    VkCommandBuffer twinRcdCmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    std::map<AHardwareBuffer*, ImportedImage> rgbOuts;
    std::map<AHardwareBuffer*, ImportedImage> scratchOuts;
    // Fused RCD finalize + grade (Direct-Log RCD path): CFA + rgb +
    // scratch in, P010 out. Own set layout + per-slot sets; dynamic
    // per-submit command buffers (see acquireRcdCmd) and the
    // rgb/scratch/P010 import caches.
    VkDescriptorSetLayout rcdYuvSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout rcdYuvPipelineLayout = VK_NULL_HANDLE;
    VkPipeline rcdYuvPipeline = VK_NULL_HANDLE;
    VkDescriptorPool rcdYuvPool = VK_NULL_HANDLE;
    VkDescriptorSet rcdYuvSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkBuffer rcdYuvParamBufs[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory rcdYuvParamMems[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    void* rcdYuvParamMaps[3] = {nullptr, nullptr, nullptr};
    // RCD dynamic command buffers (see acquireRcdCmd): per-submit CBs
    // retired by fence, replacing the twin discipline which reset
    // pending CBs (submit gaps are sub-ms, exec is 55ms+).
    struct RcdInflight {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        // A5/A4: the exported-fd semaphore and the per-slot descriptor
        // set stay owned by the submission until its fence signals.
        VkSemaphore sem = VK_NULL_HANDLE;
        VkDescriptorSet set = VK_NULL_HANDLE;
    };
    std::vector<VkCommandBuffer> rcdFreeCbs;
    std::vector<RcdInflight> rcdPending;
    // Slot gate (A3-A5, docs/refactor/vf-native-study.md): every per-slot
    // resource a wait-free submit touches (twin command buffer, per-slot
    // descriptor set) maps to the fence + exported-fd semaphore of the last
    // submit that used it. A submit first checks those fences without
    // blocking and returns VFVK_BUSY while any is pending, instead of
    // resetting/rewriting objects the GPU still executes (the caller's
    // 100 ms fd valve can release a slot whose work is still queued).
    struct SlotInFlight {
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore sem = VK_NULL_HANDLE;
    };
    std::unordered_map<uint64_t, std::shared_ptr<SlotInFlight>> slotGate;
    std::vector<VkFence> gateFencePool;
    // A1: stabFence has a submit that has not been observed complete (a
    // timed-out wait must not reset it: VUID-vkResetFences-pFences-01123).
    bool stabPending = false;
    // Malvar-He-Cutler fast demosaic (Direct-Log record path): single
    // dispatch, CFA in + write-only rgb out. Own set layout + per-slot
    // sets and twins; rgb imports share the rgbOuts map.
    VkDescriptorSetLayout mhcSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout mhcPipelineLayout = VK_NULL_HANDLE;
    VkPipeline mhcPipeline = VK_NULL_HANDLE;
    VkDescriptorPool mhcPool = VK_NULL_HANDLE;
    VkDescriptorSet mhcSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkCommandBuffer twinMhcCmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    // Fused MHC+grade record stage (vf_mhcyuv.comp): CFA storage buffer
    // in + P010 storage buffer out + per-slot params SSBO (no push).
    // Param buffers are host-visible + coherent, mapped persistently.
    VkDescriptorSetLayout fusedSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout fusedPipelineLayout = VK_NULL_HANDLE;
    VkPipeline fusedPipeline = VK_NULL_HANDLE;
    VkDescriptorPool fusedPool = VK_NULL_HANDLE;
    VkDescriptorSet fusedSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkCommandBuffer twinFusedCmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkBuffer fusedParamBufs[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory fusedParamMems[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    void* fusedParamMaps[3] = {nullptr, nullptr, nullptr};
    // Lens-shading gain maps (HAL order, rows*cols RGBA16F texels):
    // per-slot optimal-tiled images shared by the fused + f16 record
    // stages (the two never run in one take; slot reuse is
    // caller-fence-gated, so a per-submit upload is race-free and
    // mid-take map changes just work). Hardware bilinear in-shader: a
    // manual 16-tap bilinear cost ~7ms on the fused kernel; one texture
    // fetch is ~0.5ms (both measured). Staging is host-visible +
    // coherent, mapped persistently; uploads copy through it with an
    // inline barriered blit submitted just ahead on the same queue (FIFO
    // orders it, no fence/WaitIdle). Reallocated only when dims change
    // (practically once: the HAL map size is static).
    VkImage shadeImgs[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory shadeImgMems[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkImageView shadeViews[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkBuffer shadeStagings[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory shadeStagingMems[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    void* shadeStagingMaps[3] = {nullptr, nullptr, nullptr};
    int32_t shadeImgRows[3] = {0, 0, 0};
    int32_t shadeImgCols[3] = {0, 0, 0};
    bool shadeImgInit[3] = {false, false, false};
    // Per-slot upload command buffers: reuse is caller-fence-gated,
    // so a reset never touches an in-flight blit (strictly safe, unlike
    // one shared buffer re-recorded 33ms after an unfenced submit).
    VkCommandBuffer shadeUploadCmds[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    // Linear sampler shared by all shading bindings (device-global).
    VkSampler shadeSampler = VK_NULL_HANDLE;
    // Dummy 1x1 shading image (all ones) for the viewfinder, which
    // shades in GL: the shared set layout requires a bound image.
    VkImage shadeDummyImg = VK_NULL_HANDLE;
    VkDeviceMemory shadeDummyMem = VK_NULL_HANDLE;
    VkImageView shadeDummyView = VK_NULL_HANDLE;
    // Baked grade warp LUT (129-entry 1D RGBA16F as a 129x1x1 image,
    // shared by the fused + grade-YUV record stages): created lazily on
    // first upload, re-uploaded when contrast changes.
    VkImage gradeLutImage = VK_NULL_HANDLE;
    VkDeviceMemory gradeLutMemory = VK_NULL_HANDLE;
    VkImageView gradeLutView = VK_NULL_HANDLE;
    VkSampler gradeLutSampler = VK_NULL_HANDLE;
    VkBuffer gradeLutStaging = VK_NULL_HANDLE;
    VkDeviceMemory gradeLutStagingMem = VK_NULL_HANDLE;
    void* gradeLutStagingMap = nullptr;
    VkCommandBuffer gradeLutUploadCmd = VK_NULL_HANDLE;
    bool gradeLutReady = false;
    // Post-record stabilization warp (offline): tight-P010 storage buffer
    // in (one decoded frame, host-uploaded) + OUR P010 staging storage
    // buffer out (reuses the yuvOuts import validation). Single command
    // buffer + blocking fence submit — the pass runs on a background
    // thread after the take, so no twin ping-pong and no fd export.
    VkDescriptorSetLayout stabSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout stabPipelineLayout = VK_NULL_HANDLE;
    VkPipeline stabPipeline = VK_NULL_HANDLE;
    VkDescriptorPool stabPool = VK_NULL_HANDLE;
    VkDescriptorSet stabSet = VK_NULL_HANDLE;
    VkCommandBuffer stabCmd = VK_NULL_HANDLE;
    VkFence stabFence = VK_NULL_HANDLE;
    VkBuffer stabStaging = VK_NULL_HANDLE;
    VkDeviceMemory stabStagingMem = VK_NULL_HANDLE;
    void* stabStagingMap = nullptr;
    int32_t stabW = 0;
    int32_t stabH = 0;

    // Swaps in a per-slot command buffer for the scope; restores afterwards
    // so the blocking twins (shared buffer) are unaffected on any path.
    struct CmdSwap {
        Context* ctx;
        VkCommandBuffer saved;
        CmdSwap(Context* c, VkCommandBuffer b) : ctx(c), saved(c->commandBuffer) {
            c->commandBuffer = b;
        }
        ~CmdSwap() { ctx->commandBuffer = saved; }
    };

    // Swaps in a per-slot descriptor set for the scope (same pattern).
    struct SetSwap {
        VkDescriptorSet* ptr;
        VkDescriptorSet saved;
        SetSwap(VkDescriptorSet* p, VkDescriptorSet s) : ptr(p), saved(*p) { *p = s; }
        ~SetSwap() { *ptr = saved; }
    };

    // Swaps in a recorder output import for the scope: lets the wait-free
    // twin use per-pointer imports without touching the viewfinder's
    // single-slot output (whose eviction mid-flight lost the device).
    struct OutSwap {
        Context* ctx;
        AHardwareBuffer* savedBuf;
        ImportedImage savedImg;
        OutSwap(Context* c, AHardwareBuffer* b, ImportedImage i)
            : ctx(c), savedBuf(c->outputBuffer), savedImg(c->output) {
            c->outputBuffer = b;
            c->output = i;
        }
        ~OutSwap() {
            ctx->outputBuffer = savedBuf;
            ctx->output = savedImg;
        }
    };

    // Swaps in the f16 superpixel pipeline for the scope (recorder twins
    // only; the viewfinder keeps the rgba8 pipeline). Same layout, so
    // descriptor sets and push packing are untouched.
    struct PipeSwap {
        Context* ctx;
        VkPipeline saved;
        PipeSwap(Context* c, VkPipeline p) : ctx(c), saved(c->pipeline) { c->pipeline = p; }
        ~PipeSwap() { ctx->pipeline = saved; }
    };
    std::map<AHardwareBuffer*, ImportedImage> gradeIns;
    std::map<AHardwareBuffer*, ImportedImage> gradeOuts;
};

Context* g = nullptr;

// Pipeline-cache file set from Kotlin (VulkanPipelineCache). Static: the
// Context is deleted + recreated across reinit, but the path persists.
static std::string gPipelineCachePath;

// Lazily creates ctx->pipelineCache, warming it from the file when one is
// set. The result (possibly null; null means "no cache") is the cache
// argument for pipeline creation. Called under vkMutex.
static VkPipelineCache pipelineCacheFor(Context* ctx) {
    if (ctx == nullptr || ctx->device == VK_NULL_HANDLE) return VK_NULL_HANDLE;
    if (ctx->pipelineCache != VK_NULL_HANDLE) return ctx->pipelineCache;
    const char* path = ctx->pipelineCachePath.empty() ? nullptr : ctx->pipelineCachePath.c_str();
    size_t warmed = 0;
    ctx->pipelineCache = rawlens::vpc::load(ctx->gpu, ctx->device, path, &warmed);
    if (ctx->pipelineCache == VK_NULL_HANDLE) {
        LOGW("vf-vk: pipeline cache unavailable; compiling without it");
    } else if (path != nullptr) {
        LOGI("vf-vk: pipeline cache ready (%zu warmed bytes)", warmed);
    }
    return ctx->pipelineCache;
}

// Persists ctx->pipelineCache when a file is set. No-op otherwise; never
// fatal. Called under vkMutex after pipeline creation (rare, idempotent).
static void savePipelineCache(Context* ctx) {
    if (ctx == nullptr || ctx->device == VK_NULL_HANDLE || ctx->pipelineCache == VK_NULL_HANDLE ||
        ctx->pipelineCachePath.empty()) {
        return;
    }
    if (rawlens::vpc::save(ctx->device, ctx->pipelineCache, ctx->pipelineCachePath.c_str())) {
        LOGI("vf-vk: pipeline cache saved");
    } else {
        LOGW("vf-vk: pipeline cache save failed");
    }
}

uint32_t pickMemoryType(uint32_t bits, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        if (bits & (1u << i)) return i;
    }
    return UINT32_MAX;
}

uint32_t pickMemoryTypeWithProps(uint32_t bits, const VkPhysicalDeviceMemoryProperties& props,
                                 VkMemoryPropertyFlags req) {
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & req) == req) return i;
    }
    return UINT32_MAX;
}

void destroyStaging(Context* ctx) {
    if (ctx->stagingMapped != nullptr && ctx->stagingMemory != VK_NULL_HANDLE) {
        vkUnmapMemory(ctx->device, ctx->stagingMemory);
    }
    if (ctx->stagingBuffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, ctx->stagingBuffer, nullptr);
    if (ctx->stagingMemory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, ctx->stagingMemory, nullptr);
    ctx->stagingBuffer = VK_NULL_HANDLE;
    ctx->stagingMemory = VK_NULL_HANDLE;
    ctx->stagingMapped = nullptr;
    ctx->stagingSize = 0;
    ctx->stagingCoherent = true;
}

// Normalize-LUT table: 4 sensor sites x full 16-bit code range, one byte
// per (site, code). Fixed size so uploads never realloc and the binding-3
// descriptor is written once; the tail past each upload stays 0xFF.
constexpr uint32_t kLutSites = 4;
constexpr uint32_t kLutCodes = 65536;
constexpr VkDeviceSize kLutBytes = (VkDeviceSize)kLutSites * kLutCodes;

void destroyLut(Context* ctx) {
    if (ctx->lutMapped != nullptr && ctx->lutMemory != VK_NULL_HANDLE) {
        vkUnmapMemory(ctx->device, ctx->lutMemory);
    }
    if (ctx->lutBuffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, ctx->lutBuffer, nullptr);
    if (ctx->lutMemory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, ctx->lutMemory, nullptr);
    ctx->lutBuffer = VK_NULL_HANDLE;
    ctx->lutMemory = VK_NULL_HANDLE;
    ctx->lutMapped = nullptr;
    ctx->lutCoherent = true;
    ctx->lutBound = false;
    ctx->lutRow = 0;
}

