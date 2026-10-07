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
#include <mutex>
#include <thread>
#include <vector>

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
};

struct Context {
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
    std::map<AHardwareBuffer*, ImportedBuffer> inputs;
    std::vector<AHardwareBuffer*> inputOrder;
    AHardwareBuffer* outputBuffer = nullptr;
    ImportedImage output;
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
    // RCD demosaic (Direct-Log record path): STORAGE_BUFFER CFA in +
    // STORAGE_IMAGE rgb (read-write across the 3 mode dispatches). Own
    // set layout + per-slot sets and twin command buffers; rgb imports
    // are cached by pointer like the other recorder maps.
    VkDescriptorSetLayout rcdSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout rcdPipelineLayout = VK_NULL_HANDLE;
    VkPipeline rcdPipeline = VK_NULL_HANDLE;
    VkDescriptorPool rcdPool = VK_NULL_HANDLE;
    VkDescriptorSet rcdSets[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkCommandBuffer twinRcdCmd[3] = {VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE};
    std::map<AHardwareBuffer*, ImportedImage> rgbOuts;
    std::map<AHardwareBuffer*, ImportedImage> scratchOuts;
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

// motioncam ensureRawBuffer pattern: keep the buffer while it fits, else
// idle + recreate. Host-visible + coherent preferred, plain host-visible
// with an explicit per-frame flush as fallback. Mapped persistently.
int ensureStaging(Context* ctx, VkDeviceSize size) {
    const VkDeviceSize aligned = (size + 3u) & ~VkDeviceSize(3u);
    if (ctx->stagingBuffer != VK_NULL_HANDLE && ctx->stagingSize >= aligned) return VFVK_OK;
    vkDeviceWaitIdle(ctx->device);
    destroyStaging(ctx);
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = aligned;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult r = vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->stagingBuffer);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: staging create failed %d", r);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx->device, ctx->stagingBuffer, &req);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryTypeWithProps(
        req.memoryTypeBits, memProps,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    ctx->stagingCoherent = true;
    if (memType == UINT32_MAX) {
        memType = pickMemoryTypeWithProps(req.memoryTypeBits, memProps,
                                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        ctx->stagingCoherent = false;
    }
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: copy: no host-visible memory type");
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->stagingMemory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: staging alloc failed %d", r);
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    r = vkBindBufferMemory(ctx->device, ctx->stagingBuffer, ctx->stagingMemory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: staging bind failed %d", r);
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    r = vkMapMemory(ctx->device, ctx->stagingMemory, 0, VK_WHOLE_SIZE, 0, &ctx->stagingMapped);
    if (r != VK_SUCCESS || !ctx->stagingMapped) {
        LOGW("vf-vk: copy: staging map failed %d", r);
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    ctx->stagingSize = aligned;
    LOGI("vf-vk: copy: staging ready size=%llu coherent=%d",
         (unsigned long long)aligned, ctx->stagingCoherent ? 1 : 0);
    return VFVK_OK;
}

void destroyImported(Context* ctx, ImportedImage* img) {
    if (img->view != VK_NULL_HANDLE) vkDestroyImageView(ctx->device, img->view, nullptr);
    if (img->image != VK_NULL_HANDLE) vkDestroyImage(ctx->device, img->image, nullptr);
    if (img->memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, img->memory, nullptr);
    *img = ImportedImage{};
}

void destroyImportedBuffer(Context* ctx, ImportedBuffer* buf) {
    if (buf->buffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, buf->buffer, nullptr);
    if (buf->memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, buf->memory, nullptr);
    *buf = ImportedBuffer{};
}

// FIFO eviction for the pointer-keyed input import cache, shared by all
// get-or-import sites. The cap (24) covers the largest reader pool (22),
// so steady-state viewfinder frames never evict: any eviction means the
// live distinct-buffer set overflowed the cache and the next use of the
// evicted buffer pays a full 25MB re-import. The counter + rate-limited
// warning make that regime visible in logcat instead of silent churn.
void evictOldestInput(Context* ctx) {
    static uint64_t evictions = 0;
    AHardwareBuffer* oldest = ctx->inputOrder.front();
    ctx->inputOrder.erase(ctx->inputOrder.begin());
    auto oit = ctx->inputs.find(oldest);
    if (oit != ctx->inputs.end()) {
        destroyImportedBuffer(ctx, &oit->second);
        ctx->inputs.erase(oit);
    }
    AHardwareBuffer_release(oldest);
    evictions++;
    if (evictions == 1 || evictions % 64 == 0) {
        LOGW("vf-vk: input import cache evicted %llu buffer(s) (cap %d); live pool exceeds cache",
             (unsigned long long)evictions, INPUT_CACHE_CAP);
    }
}

// Import an AHB as a Vulkan image per the reference flow: query format
// properties, create with the reported VkFormat (or externalFormat), dedicated
// import-allocate, bind, view. Does NOT acquire the caller's buffer.
// Tiling: HAL camera buffers are linear (CPU-visible, rowStride-pitched), so the
// input tries LINEAR first — importing them as OPTIMAL lets the driver
// misinterpret the layout (observed: striped mis-samples). The app-allocated
// export buffer stays OPTIMAL (proven correct end-to-end by the grid test).
int importAhb(Context* ctx, AHardwareBuffer* buf, VkImageUsageFlags usage,
              const char* tag, bool linearFirst, ImportedImage* out) {
    *out = ImportedImage{};
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
    fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ahbProps.pNext = &fmtProps;
    VkResult r = ctx->getAhbProps(ctx->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: ahb-props failed %d", tag, r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    LOGI("vf-vk: %s: %ux%u ahbFormat=%u vkFormat=%d external=%llu size=%llu types=%#x", tag,
         desc.width, desc.height, desc.format, fmtProps.format,
         (unsigned long long)fmtProps.externalFormat,
         (unsigned long long)ahbProps.allocationSize, ahbProps.memoryTypeBits);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryType(ahbProps.memoryTypeBits, memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: %s: no memory type", tag);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    const bool useExternal = fmtProps.format == VK_FORMAT_UNDEFINED;
    if (useExternal && fmtProps.externalFormat == 0) {
        LOGW("vf-vk: %s: no usable format", tag);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkExternalFormatANDROID vkExtFmt{};
    vkExtFmt.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    vkExtFmt.externalFormat = fmtProps.externalFormat;
    VkExternalMemoryImageCreateInfo extImg{};
    extImg.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImg.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    if (useExternal) extImg.pNext = &vkExtFmt;
    const VkImageTiling tilings[2] = {
        linearFirst ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_OPTIMAL,
        linearFirst ? VK_IMAGE_TILING_OPTIMAL : VK_IMAGE_TILING_LINEAR,
    };
    const char* tilingNames[2] = {linearFirst ? "LINEAR" : "OPTIMAL", linearFirst ? "OPTIMAL" : "LINEAR"};
    for (int t = 0; t < 2; ++t) {
        VkImageCreateInfo imgInfo{};
        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imgInfo.pNext = &extImg;
        imgInfo.imageType = VK_IMAGE_TYPE_2D;
        imgInfo.format = useExternal ? VK_FORMAT_UNDEFINED : fmtProps.format;
        imgInfo.extent.width = desc.width;
        imgInfo.extent.height = desc.height;
        imgInfo.extent.depth = 1;
        imgInfo.mipLevels = 1;
        imgInfo.arrayLayers = 1;
        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imgInfo.tiling = tilings[t];
        imgInfo.usage = usage;
        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        r = vkCreateImage(ctx->device, &imgInfo, nullptr, &out->image);
        if (r != VK_SUCCESS) {
            LOGW("vf-vk: %s: image create (%s) failed %d (usage=%#x)", tag, tilingNames[t], r, usage);
            continue;
        }
        LOGI("vf-vk: %s: image tiling=%s", tag, tilingNames[t]);
        break;
    }
    if (out->image == VK_NULL_HANDLE) return VFVK_INPUT_IMPORT_FAILED;
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = out->image;
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buf;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = &import;
    alloc.allocationSize = ahbProps.allocationSize;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: import alloc failed %d", tag, r);
        vkDestroyImage(ctx->device, out->image, nullptr);
        *out = ImportedImage{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    r = vkBindImageMemory(ctx->device, out->image, out->memory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: bind failed %d", tag, r);
        vkFreeMemory(ctx->device, out->memory, nullptr);
        vkDestroyImage(ctx->device, out->image, nullptr);
        *out = ImportedImage{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = out->image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = useExternal ? VK_FORMAT_UNDEFINED : fmtProps.format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    r = vkCreateImageView(ctx->device, &viewInfo, nullptr, &out->view);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: view failed %d", tag, r);
        destroyImported(ctx, out);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    return VFVK_OK;
}

// Import an AHB as a storage buffer: byte-exact, no pitch/tiling/format risk.
// The shader does explicit (y * pitch + x) addressing with the Image plane's
// stride, the same value the correct CPU path uses.
int importInputBuffer(Context* ctx, AHardwareBuffer* buf, ImportedBuffer* out) {
    *out = ImportedBuffer{};
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    VkResult r = ctx->getAhbProps(ctx->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: ahb-props failed %d", r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryType(ahbProps.memoryTypeBits, memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: input: no memory type");
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkExternalMemoryBufferCreateInfo extBuf{};
    extBuf.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    extBuf.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.pNext = &extBuf;
    bufInfo.size = ahbProps.allocationSize;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    r = vkCreateBuffer(ctx->device, &bufInfo, nullptr, &out->buffer);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: buffer create failed %d", r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx->device, out->buffer, &req);
    // The imported allocation AND the storage buffer must support this type.
    // Choosing from AHB properties alone can make vkBindBufferMemory invalid.
    memType = pickMemoryType(ahbProps.memoryTypeBits & req.memoryTypeBits,
                             memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: input: no compatible buffer memory type");
        destroyImportedBuffer(ctx, out);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    if (req.size > ahbProps.allocationSize) {
        LOGW("vf-vk: input: requirements %llu exceed allocation %llu",
             (unsigned long long)req.size, (unsigned long long)ahbProps.allocationSize);
        vkDestroyBuffer(ctx->device, out->buffer, nullptr);
        *out = ImportedBuffer{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.buffer = out->buffer;
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buf;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = &import;
    alloc.allocationSize = ahbProps.allocationSize;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: import alloc failed %d", r);
        vkDestroyBuffer(ctx->device, out->buffer, nullptr);
        *out = ImportedBuffer{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    r = vkBindBufferMemory(ctx->device, out->buffer, out->memory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: bind failed %d", r);
        destroyImportedBuffer(ctx, out);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    // Field diagnostics for HALs whose import succeeds but reads back zeros
    // (Adreno 750): allocation identity + chosen memory type, one line per
    // pool buffer (the import cache makes repeats cheap and quiet).
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    LOGI("vf-vk: input: buffer import ok %ux%u ahbFormat=%u layers=%u usage=%llu size=%llu types=%#x memType=%u",
         desc.width, desc.height, desc.format, desc.layers, (unsigned long long)desc.usage,
         (unsigned long long)ahbProps.allocationSize, ahbProps.memoryTypeBits, memType);
    return VFVK_OK;
}

void layoutBarrier(VkCommandBuffer cmd, VkImage image,
                   VkPipelineStageFlags srcStage, VkAccessFlags srcAccess,
                   VkPipelineStageFlags dstStage, VkAccessFlags dstAccess,
                   VkImageLayout oldLayout, VkImageLayout newLayout,
                   uint32_t srcFamily, uint32_t dstFamily) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = srcFamily;
    barrier.dstQueueFamilyIndex = dstFamily;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

// Linked pipeline sharing the caller's layout (f16 superpixel variant:
// identical bindings + push block, different store format).
static bool createLinkedPipeline(Context* ctx, const uint32_t* code, size_t words, VkPipeline* out) {
    *out = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: linked shader module failed");
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->pipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, out);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: linked compute pipeline failed %d", r);
        return false;
    }
    return true;
}

bool createPipeline(Context* ctx, const uint32_t* code, size_t words) {    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 3;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->setLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(SuperpixelParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->setLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->pipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->setLayout, nullptr);
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->pipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->pipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[3]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->descriptorPool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->setLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->descriptorSet) != VK_SUCCESS) {
        return false;
    }
    return true;
}

// Phase-B grade pipeline: STORAGE_IMAGE in + STORAGE_IMAGE out (the
// superpixel stage is STORAGE_BUFFER in + STORAGE_IMAGE out, so it needs
// its own set layout). Push block is GradeParams (96 bytes).
bool createGradePipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: grade shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->gradeSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(GradeParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->gradeSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->gradePipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->gradeSetLayout, nullptr);
        ctx->gradeSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->gradePipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->gradePipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: grade compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->gradePool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->gradePool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->gradeSetLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->gradeSet) != VK_SUCCESS) {
        return false;
    }
    return true;
}

// True-10-bit pipeline: STORAGE_IMAGE (FP16 superpixel export) in +
// STORAGE_BUFFER (P010 codec bytes) out. Own set layout; push block is
// GradeYuvParams (112 bytes).
bool createYuvPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: yuv shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 3;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->yuvSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(GradeYuvParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->yuvSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->yuvPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->yuvSetLayout, nullptr);
        ctx->yuvSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->yuvPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->yuvPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: yuv compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[3]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[1].descriptorCount = 3;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = 3;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->yuvPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->yuvPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->yuvSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->yuvSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// Record-preview pipeline: STORAGE_BUFFER P010 in + STORAGE_IMAGE RGBA8
// out + PreviewParams push (32 bytes).
bool createPreviewPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: preview shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->previewSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(PreviewParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->previewSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->previewPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->previewSetLayout, nullptr);
        ctx->previewSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->previewPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->previewPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: preview compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 3;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->previewPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->previewPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->previewSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->previewSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// RCD demosaic pipeline: STORAGE_BUFFER CFA in + STORAGE_IMAGE rgb
// (read-write across the 3 mode dispatches) + RcdParams push (72 bytes).
bool createRcdPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: rcd shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 3;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->rcdSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(RcdParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->rcdSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->rcdPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rcdSetLayout, nullptr);
        ctx->rcdSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->rcdPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->rcdPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: rcd compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 6;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->rcdPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->rcdPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->rcdSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->rcdSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// Malvar-He-Cutler pipeline: STORAGE_BUFFER CFA in + write-only
// STORAGE_IMAGE rgb out + RcdParams push (72 bytes, u_mode 0).
bool createMhcPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: mhc shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->mhcSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(RcdParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->mhcSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->mhcPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->mhcSetLayout, nullptr);
        ctx->mhcSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->mhcPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->mhcPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: mhc compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 3;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->mhcPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->mhcPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->mhcSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->mhcSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

uint32_t pickMemoryTypeProps(uint32_t bits, const VkPhysicalDeviceMemoryProperties& props,
                             VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    return UINT32_MAX;
}

// Fused MHC+grade pipeline: CFA STORAGE_BUFFER in + P010 STORAGE_BUFFER
// out + params STORAGE_BUFFER (FusedParams SSBO) + shading sampler +
// grade-LUT sampler; no push constants.
bool createFusedPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: fused shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[5]{};
    for (int i = 0; i < 3; ++i) {
        bindings[i].binding = (uint32_t)i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[4].binding = 4;
    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[4].descriptorCount = 1;
    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 5;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->fusedSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->fusedSetLayout;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->fusedPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->fusedSetLayout, nullptr);
        ctx->fusedSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->fusedPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->fusedPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: fused compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 9;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[1].descriptorCount = 6;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->fusedPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->fusedPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->fusedSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->fusedSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// Per-slot fused param buffers: host-visible + coherent, mapped
// persistently (192B params in 256B of allocation comfort).
bool createFusedParams(Context* ctx) {
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    for (int i = 0; i < 3; ++i) {
        VkBufferCreateInfo bufInfo{};
        bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufInfo.size = 256;
        bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->fusedParamBufs[i]) != VK_SUCCESS) {
            LOGW("vf-vk: fused param buffer %d failed", i);
            return false;
        }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(ctx->device, ctx->fusedParamBufs[i], &req);
        uint32_t memType = pickMemoryTypeProps(
            req.memoryTypeBits, memProps,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memType == UINT32_MAX) {
            LOGW("vf-vk: fused param memory %d: no coherent type", i);
            return false;
        }
        VkMemoryAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = memType;
        if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->fusedParamMems[i]) != VK_SUCCESS) {
            LOGW("vf-vk: fused param alloc %d failed", i);
            return false;
        }
        if (vkBindBufferMemory(ctx->device, ctx->fusedParamBufs[i],
                               ctx->fusedParamMems[i], 0) != VK_SUCCESS) {
            LOGW("vf-vk: fused param bind %d failed", i);
            return false;
        }
        if (vkMapMemory(ctx->device, ctx->fusedParamMems[i], 0, req.size, 0,
                        &ctx->fusedParamMaps[i]) != VK_SUCCESS) {
            LOGW("vf-vk: fused param map %d failed", i);
            ctx->fusedParamMaps[i] = nullptr;
            return false;
        }
    }
    return true;
}

// HAL maps are tiny (17x17 typical); 64x64 cells is a generous cap
// against a corrupt HAL advertising gigabytes.
constexpr int kShadeMaxCells = 64;
constexpr VkFormat kShadeFormat = VK_FORMAT_R16G16B16A16_SFLOAT;

void destroyShadeSlot(Context* ctx, int slot) {
    if (ctx->shadeUploadCmds[slot] != VK_NULL_HANDLE && ctx->commandPool != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &ctx->shadeUploadCmds[slot]);
    }
    ctx->shadeUploadCmds[slot] = VK_NULL_HANDLE;
    if (ctx->shadeViews[slot] != VK_NULL_HANDLE)
        vkDestroyImageView(ctx->device, ctx->shadeViews[slot], nullptr);
    ctx->shadeViews[slot] = VK_NULL_HANDLE;
    if (ctx->shadeImgs[slot] != VK_NULL_HANDLE)
        vkDestroyImage(ctx->device, ctx->shadeImgs[slot], nullptr);
    ctx->shadeImgs[slot] = VK_NULL_HANDLE;
    if (ctx->shadeImgMems[slot] != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->shadeImgMems[slot], nullptr);
    ctx->shadeImgMems[slot] = VK_NULL_HANDLE;
    if (ctx->shadeStagingMaps[slot] != nullptr && ctx->shadeStagingMems[slot] != VK_NULL_HANDLE) {
        vkUnmapMemory(ctx->device, ctx->shadeStagingMems[slot]);
    }
    ctx->shadeStagingMaps[slot] = nullptr;
    if (ctx->shadeStagings[slot] != VK_NULL_HANDLE)
        vkDestroyBuffer(ctx->device, ctx->shadeStagings[slot], nullptr);
    ctx->shadeStagings[slot] = VK_NULL_HANDLE;
    if (ctx->shadeStagingMems[slot] != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->shadeStagingMems[slot], nullptr);
    ctx->shadeStagingMems[slot] = VK_NULL_HANDLE;
    ctx->shadeImgRows[slot] = 0;
    ctx->shadeImgCols[slot] = 0;
    ctx->shadeImgInit[slot] = false;
}

void destroyShade(Context* ctx) {
    for (int i = 0; i < 3; ++i) destroyShadeSlot(ctx, i);
}

void destroyShadeDummyImage(Context* ctx) {
    if (ctx->shadeDummyView != VK_NULL_HANDLE)
        vkDestroyImageView(ctx->device, ctx->shadeDummyView, nullptr);
    ctx->shadeDummyView = VK_NULL_HANDLE;
    if (ctx->shadeDummyImg != VK_NULL_HANDLE)
        vkDestroyImage(ctx->device, ctx->shadeDummyImg, nullptr);
    ctx->shadeDummyImg = VK_NULL_HANDLE;
    if (ctx->shadeDummyMem != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->shadeDummyMem, nullptr);
    ctx->shadeDummyMem = VK_NULL_HANDLE;
}

void destroyShadeSampler(Context* ctx) {
    if (ctx->shadeSampler != VK_NULL_HANDLE)
        vkDestroySampler(ctx->device, ctx->shadeSampler, nullptr);
    ctx->shadeSampler = VK_NULL_HANDLE;
}

// Shared linear sampler (mirrors the grade-LUT sampler, 2D). The
// format check doubles as the shading-availability probe: RGBA16F
// optimal linear filtering is what the grade LUT already proved.
bool ensureShadeSampler(Context* ctx) {
    if (ctx->shadeSampler != VK_NULL_HANDLE) return true;
    VkFormatProperties fmtProps{};
    vkGetPhysicalDeviceFormatProperties(ctx->gpu, kShadeFormat, &fmtProps);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                     VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((fmtProps.optimalTilingFeatures & need) != need) {
        LOGW("vf-vk: shade: RGBA16F linear filtering unsupported");
        return false;
    }
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(ctx->device, &samplerInfo, nullptr, &ctx->shadeSampler) != VK_SUCCESS) {
        LOGW("vf-vk: shade sampler failed");
        return false;
    }
    return true;
}

// Ensure slot's image+staging+upload-cmd for rows*cols (recreates on
// dims change; slot reuse is caller-fence-gated so the old image is
// idle). False on allocation failure: caller disables shading, loud.
bool ensureShadeImage(Context* ctx, int slot, int rows, int cols) {
    if (ctx->shadeImgs[slot] != VK_NULL_HANDLE && ctx->shadeImgRows[slot] == rows &&
        ctx->shadeImgCols[slot] == cols) {
        return true;
    }
    destroyShadeSlot(ctx, slot);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = kShadeFormat;
    imgInfo.extent = {(uint32_t)cols, (uint32_t)rows, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &ctx->shadeImgs[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade image %d (%dx%d) failed", slot, cols, rows);
        return false;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, ctx->shadeImgs[slot], &req);
    uint32_t memType =
        pickMemoryTypeProps(req.memoryTypeBits, memProps, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: shade image %d: no device-local type", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->shadeImgMems[slot]) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, ctx->shadeImgs[slot], ctx->shadeImgMems[slot], 0) !=
            VK_SUCCESS) {
        LOGW("vf-vk: shade image memory %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = ctx->shadeImgs[slot];
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kShadeFormat;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(ctx->device, &viewInfo, nullptr, &ctx->shadeViews[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade view %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    const VkDeviceSize bytes = (VkDeviceSize)rows * cols * 4 * sizeof(uint16_t);
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = bytes;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->shadeStagings[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade staging %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    vkGetBufferMemoryRequirements(ctx->device, ctx->shadeStagings[slot], &req);
    memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                  VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                      VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: shade staging %d: no coherent type", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->shadeStagingMems[slot]) != VK_SUCCESS ||
        vkBindBufferMemory(ctx->device, ctx->shadeStagings[slot], ctx->shadeStagingMems[slot],
                           0) != VK_SUCCESS ||
        vkMapMemory(ctx->device, ctx->shadeStagingMems[slot], 0, req.size, 0,
                    &ctx->shadeStagingMaps[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade staging memory %d failed", slot);
        ctx->shadeStagingMaps[slot] = nullptr;
        destroyShadeSlot(ctx, slot);
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->shadeUploadCmds[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade upload cmd %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    ctx->shadeImgRows[slot] = rows;
    ctx->shadeImgCols[slot] = cols;
    ctx->shadeImgInit[slot] = false;
    return true;
}

// Record + submit the staging->image blit for slot (barriers inline).
// Submitted unfenced just ahead of the main submit on the same queue;
// FIFO orders it — no fence, no WaitIdle. False on record/submit
// failure (caller disables shading for the frame, loud).
bool blitShadeImage(Context* ctx, int slot) {
    VkCommandBuffer cmd = ctx->shadeUploadCmds[slot];
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) return false;
    VkImageMemoryBarrier pre{};
    pre.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    pre.oldLayout =
        ctx->shadeImgInit[slot] ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    pre.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    pre.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.image = ctx->shadeImgs[slot];
    pre.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    pre.subresourceRange.levelCount = 1;
    pre.subresourceRange.layerCount = 1;
    pre.srcAccessMask =
        ctx->shadeImgInit[slot] ? (VkAccessFlags)VK_ACCESS_SHADER_READ_BIT : (VkAccessFlags)0;
    pre.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &pre);
    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;  // tight staging rows
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {(uint32_t)ctx->shadeImgCols[slot], (uint32_t)ctx->shadeImgRows[slot], 1};
    vkCmdCopyBufferToImage(cmd, ctx->shadeStagings[slot], ctx->shadeImgs[slot],
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    VkImageMemoryBarrier post{};
    post.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    post.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    post.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    post.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.image = ctx->shadeImgs[slot];
    post.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    post.subresourceRange.levelCount = 1;
    post.subresourceRange.layerCount = 1;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    post.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &post);
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) return false;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(ctx->queue, 1, &submit, VK_NULL_HANDLE) != VK_SUCCESS) return false;
    ctx->shadeImgInit[slot] = true;
    return true;
}

// Dummy 1x1 shading image (all ones: a wayward sample is identity) for
// the viewfinder, which shades in GL but must bind the layout's image.
// One-shot upload with an immediate idle (first VF frame only); failure
// degrades gracefully (VF falls back, record disables shading).
bool ensureShadeDummy(Context* ctx) {
    if (ctx->shadeDummyView != VK_NULL_HANDLE) return true;
    if (!ensureShadeSampler(ctx)) return false;
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = kShadeFormat;
    imgInfo.extent = {1, 1, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &ctx->shadeDummyImg) != VK_SUCCESS) {
        LOGW("vf-vk: shade dummy image failed");
        return false;
    }
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, ctx->shadeDummyImg, &req);
    uint32_t memType =
        pickMemoryTypeProps(req.memoryTypeBits, memProps, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (memType == UINT32_MAX ||
        vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->shadeDummyMem) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, ctx->shadeDummyImg, ctx->shadeDummyMem, 0) != VK_SUCCESS) {
        LOGW("vf-vk: shade dummy memory failed");
        destroyShadeDummyImage(ctx);
        return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = ctx->shadeDummyImg;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kShadeFormat;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(ctx->device, &viewInfo, nullptr, &ctx->shadeDummyView) != VK_SUCCESS) {
        LOGW("vf-vk: shade dummy view failed");
        destroyShadeDummyImage(ctx);
        return false;
    }
    // Upload four 1.0f halves (0x3C00) through a one-shot staging blit.
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    void* stagingMap = nullptr;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    bool ok = false;
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = 8;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    do {
        if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &staging) != VK_SUCCESS) break;
        vkGetBufferMemoryRequirements(ctx->device, staging, &req);
        memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memType == UINT32_MAX) break;
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = memType;
        if (vkAllocateMemory(ctx->device, &alloc, nullptr, &stagingMem) != VK_SUCCESS) break;
        if (vkBindBufferMemory(ctx->device, staging, stagingMem, 0) != VK_SUCCESS) break;
        if (vkMapMemory(ctx->device, stagingMem, 0, req.size, 0, &stagingMap) != VK_SUCCESS) {
            stagingMap = nullptr;
            break;
        }
        static const uint16_t ones[4] = {0x3C00, 0x3C00, 0x3C00, 0x3C00};
        memcpy(stagingMap, ones, sizeof(ones));
        vkUnmapMemory(ctx->device, stagingMem);
        stagingMap = nullptr;
        VkCommandBufferAllocateInfo cmdInfo{};
        cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdInfo.commandPool = ctx->commandPool;
        cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdInfo.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &cmd) != VK_SUCCESS) break;
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) break;
        VkImageMemoryBarrier bar{};
        bar.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        bar.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar.image = ctx->shadeDummyImg;
        bar.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        bar.subresourceRange.levelCount = 1;
        bar.subresourceRange.layerCount = 1;
        bar.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &bar);
        VkBufferImageCopy region{};
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.layerCount = 1;
        region.imageExtent = {1, 1, 1};
        vkCmdCopyBufferToImage(cmd, staging, ctx->shadeDummyImg,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        bar.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        bar.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &bar);
        if (vkEndCommandBuffer(cmd) != VK_SUCCESS) break;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &cmd;
        if (vkQueueSubmit(ctx->queue, 1, &submit, VK_NULL_HANDLE) != VK_SUCCESS) break;
        if (vkQueueWaitIdle(ctx->queue) != VK_SUCCESS) break;
        ok = true;
    } while (false);
    if (cmd != VK_NULL_HANDLE) vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &cmd);
    if (staging != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, staging, nullptr);
    if (stagingMem != VK_NULL_HANDLE) vkFreeMemory(ctx->device, stagingMem, nullptr);
    if (!ok) {
        LOGW("vf-vk: shade dummy upload failed");
        destroyShadeDummyImage(ctx);
        return false;
    }
    return true;
}

void destroyFused(Context* ctx) {
    for (int i = 0; i < 3; ++i) {
        if (ctx->fusedParamMaps[i] != nullptr && ctx->fusedParamMems[i] != VK_NULL_HANDLE) {
            vkUnmapMemory(ctx->device, ctx->fusedParamMems[i]);
        }
        ctx->fusedParamMaps[i] = nullptr;
        if (ctx->fusedParamBufs[i] != VK_NULL_HANDLE)
            vkDestroyBuffer(ctx->device, ctx->fusedParamBufs[i], nullptr);
        ctx->fusedParamBufs[i] = VK_NULL_HANDLE;
        if (ctx->fusedParamMems[i] != VK_NULL_HANDLE)
            vkFreeMemory(ctx->device, ctx->fusedParamMems[i], nullptr);
        ctx->fusedParamMems[i] = VK_NULL_HANDLE;
    }
    if (ctx->fusedPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->fusedPipeline, nullptr);
    if (ctx->fusedPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->fusedPipelineLayout, nullptr);
    if (ctx->fusedSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->fusedSetLayout, nullptr);
    if (ctx->fusedPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->fusedPool, nullptr);
    ctx->fusedPipeline = VK_NULL_HANDLE;
    ctx->fusedPipelineLayout = VK_NULL_HANDLE;
    ctx->fusedSetLayout = VK_NULL_HANDLE;
    ctx->fusedPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->fusedSets[i] = VK_NULL_HANDLE;
}

void destroyStab(Context* ctx);
void destroyGrade(Context* ctx) {
    VkCommandBuffer twins[18] = {
        ctx->twinComputeCmd[0], ctx->twinComputeCmd[1], ctx->twinComputeCmd[2],
        ctx->twinGradeCmd[0], ctx->twinGradeCmd[1], ctx->twinGradeCmd[2],
        ctx->twinRcdCmd[0], ctx->twinRcdCmd[1], ctx->twinRcdCmd[2],
        ctx->twinMhcCmd[0], ctx->twinMhcCmd[1], ctx->twinMhcCmd[2],
        ctx->twinFusedCmd[0], ctx->twinFusedCmd[1], ctx->twinFusedCmd[2],
        ctx->twinPreviewCmd[0], ctx->twinPreviewCmd[1], ctx->twinPreviewCmd[2]};
    bool anyTwin = false;
    for (int i = 0; i < 18; ++i) anyTwin = anyTwin || (twins[i] != VK_NULL_HANDLE);
    if (anyTwin) vkFreeCommandBuffers(ctx->device, ctx->commandPool, 18, twins);
    for (int i = 0; i < 3; ++i) {
        ctx->twinComputeCmd[i] = VK_NULL_HANDLE;
        ctx->twinGradeCmd[i] = VK_NULL_HANDLE;
        ctx->twinRcdCmd[i] = VK_NULL_HANDLE;
        ctx->twinMhcCmd[i] = VK_NULL_HANDLE;
        ctx->twinFusedCmd[i] = VK_NULL_HANDLE;
        ctx->twinPreviewCmd[i] = VK_NULL_HANDLE;
        ctx->recComputeSets[i] = VK_NULL_HANDLE;
        ctx->recGradeSets[i] = VK_NULL_HANDLE;
    }
    destroyFused(ctx);
    destroyShade(ctx);
    if (ctx->recComputePool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->recComputePool, nullptr);
    if (ctx->recGradePool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->recGradePool, nullptr);
    ctx->recComputePool = VK_NULL_HANDLE;
    ctx->recGradePool = VK_NULL_HANDLE;
    for (auto& entry : ctx->recOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->recOuts.clear();
    for (auto& entry : ctx->yuvOuts) {
        destroyImportedBuffer(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->yuvOuts.clear();
    if (ctx->yuvPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->yuvPipeline, nullptr);
    if (ctx->yuvPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->yuvPipelineLayout, nullptr);
    if (ctx->yuvSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->yuvSetLayout, nullptr);
    if (ctx->yuvPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->yuvPool, nullptr);
    ctx->yuvPipeline = VK_NULL_HANDLE;
    ctx->yuvPipelineLayout = VK_NULL_HANDLE;
    ctx->yuvSetLayout = VK_NULL_HANDLE;
    ctx->yuvPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->yuvSets[i] = VK_NULL_HANDLE;
    for (auto& entry : ctx->previewOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->previewOuts.clear();
    if (ctx->previewPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->previewPipeline, nullptr);
    if (ctx->previewPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->previewPipelineLayout, nullptr);
    if (ctx->previewSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->previewSetLayout, nullptr);
    if (ctx->previewPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->previewPool, nullptr);
    ctx->previewPipeline = VK_NULL_HANDLE;
    ctx->previewPipelineLayout = VK_NULL_HANDLE;
    ctx->previewSetLayout = VK_NULL_HANDLE;
    ctx->previewPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->previewSets[i] = VK_NULL_HANDLE;
    for (auto& entry : ctx->rgbOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->rgbOuts.clear();
    for (auto& entry : ctx->scratchOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->scratchOuts.clear();
    if (ctx->rcdPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->rcdPipeline, nullptr);
    if (ctx->rcdPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->rcdPipelineLayout, nullptr);
    if (ctx->rcdSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rcdSetLayout, nullptr);
    if (ctx->rcdPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->rcdPool, nullptr);
    ctx->rcdPipeline = VK_NULL_HANDLE;
    ctx->rcdPipelineLayout = VK_NULL_HANDLE;
    ctx->rcdSetLayout = VK_NULL_HANDLE;
    ctx->rcdPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->rcdSets[i] = VK_NULL_HANDLE;
    if (ctx->mhcPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->mhcPipeline, nullptr);
    if (ctx->mhcPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->mhcPipelineLayout, nullptr);
    if (ctx->mhcSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->mhcSetLayout, nullptr);
    if (ctx->mhcPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->mhcPool, nullptr);
    ctx->mhcPipeline = VK_NULL_HANDLE;
    ctx->mhcPipelineLayout = VK_NULL_HANDLE;
    ctx->mhcSetLayout = VK_NULL_HANDLE;
    ctx->mhcPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->mhcSets[i] = VK_NULL_HANDLE;
    for (auto& entry : ctx->gradeIns) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->gradeIns.clear();
    for (auto& entry : ctx->gradeOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->gradeOuts.clear();
    if (ctx->gradePipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->gradePipeline, nullptr);
    if (ctx->gradePipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->gradePipelineLayout, nullptr);
    if (ctx->gradeSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->gradeSetLayout, nullptr);
    if (ctx->gradePool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->gradePool, nullptr);
    ctx->gradePipeline = VK_NULL_HANDLE;
    ctx->gradePipelineLayout = VK_NULL_HANDLE;
    ctx->gradeSetLayout = VK_NULL_HANDLE;
    ctx->gradePool = VK_NULL_HANDLE;
    ctx->gradeSet = VK_NULL_HANDLE;
    destroyStab(ctx);
}

// Post-record stab warp teardown (null-safe): fence, command buffer,
// staging upload buffer, descriptor pool, pipeline. The P010 output
// imports stay cached in yuvOuts (shared with the record stages).
void destroyStab(Context* ctx) {
    if (ctx->stabFence != VK_NULL_HANDLE)
        vkDestroyFence(ctx->device, ctx->stabFence, nullptr);
    ctx->stabFence = VK_NULL_HANDLE;
    if (ctx->stabCmd != VK_NULL_HANDLE && ctx->commandPool != VK_NULL_HANDLE)
        vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &ctx->stabCmd);
    ctx->stabCmd = VK_NULL_HANDLE;
    if (ctx->stabStagingMap != nullptr && ctx->stabStagingMem != VK_NULL_HANDLE)
        vkUnmapMemory(ctx->device, ctx->stabStagingMem);
    ctx->stabStagingMap = nullptr;
    if (ctx->stabStaging != VK_NULL_HANDLE)
        vkDestroyBuffer(ctx->device, ctx->stabStaging, nullptr);
    ctx->stabStaging = VK_NULL_HANDLE;
    if (ctx->stabStagingMem != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->stabStagingMem, nullptr);
    ctx->stabStagingMem = VK_NULL_HANDLE;
    if (ctx->stabPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->stabPool, nullptr);
    ctx->stabPool = VK_NULL_HANDLE;
    ctx->stabSet = VK_NULL_HANDLE;
    if (ctx->stabPipeline != VK_NULL_HANDLE)
        vkDestroyPipeline(ctx->device, ctx->stabPipeline, nullptr);
    ctx->stabPipeline = VK_NULL_HANDLE;
    if (ctx->stabPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->stabPipelineLayout, nullptr);
    ctx->stabPipelineLayout = VK_NULL_HANDLE;
    if (ctx->stabSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->stabSetLayout, nullptr);
    ctx->stabSetLayout = VK_NULL_HANDLE;
    ctx->stabW = 0;
    ctx->stabH = 0;
}

// Baked grade warp LUT: 129-entry 1D RGBA16F over a log2 lattice
// ([2^-10, 2^4]; see VfLogGrade.bakeLut), stored as a 129x1x1 3D image
// so the existing sampler path just works (the v/w fetch at 0.5
// degrades trilinear to a 1D linear interp, ~0.07% error). Created
// lazily on first upload (optimal-tiled, device-local, staging
// upload); re-uploaded when contrast changes (129 evals ≈ 0.1ms, so
// slider moves never jank). Shared by the fused + grade-YUV stages.
constexpr int kGradeLutSize = 129;
constexpr VkDeviceSize kGradeLutBytes =
    (VkDeviceSize)kGradeLutSize * 4 * sizeof(uint16_t);

bool createGradeLut(Context* ctx) {
    VkFormatProperties fmtProps{};
    vkGetPhysicalDeviceFormatProperties(ctx->gpu, VK_FORMAT_R16G16B16A16_SFLOAT, &fmtProps);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                     VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((fmtProps.optimalTilingFeatures & need) != need) {
        LOGW("vf-vk: grade LUT: RGBA16F linear filtering unsupported");
        return false;
    }
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_3D;
    imgInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    imgInfo.extent = {(uint32_t)kGradeLutSize, 1, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &ctx->gradeLutImage) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT image failed");
        return false;
    }
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, ctx->gradeLutImage, &req);
    uint32_t memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                           VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: grade LUT: no device-local type");
        return false;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->gradeLutMemory) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, ctx->gradeLutImage, ctx->gradeLutMemory, 0) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT memory failed");
        return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = ctx->gradeLutImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_3D;
    viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(ctx->device, &viewInfo, nullptr, &ctx->gradeLutView) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT view failed");
        return false;
    }
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(ctx->device, &samplerInfo, nullptr, &ctx->gradeLutSampler) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT sampler failed");
        return false;
    }
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = kGradeLutBytes;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->gradeLutStaging) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT staging failed");
        return false;
    }
    vkGetBufferMemoryRequirements(ctx->device, ctx->gradeLutStaging, &req);
    memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                  VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                      VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: grade LUT staging: no coherent type");
        return false;
    }
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->gradeLutStagingMem) != VK_SUCCESS ||
        vkBindBufferMemory(ctx->device, ctx->gradeLutStaging, ctx->gradeLutStagingMem, 0) != VK_SUCCESS ||
        vkMapMemory(ctx->device, ctx->gradeLutStagingMem, 0, req.size, 0,
                    &ctx->gradeLutStagingMap) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT staging memory failed");
        ctx->gradeLutStagingMap = nullptr;
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->gradeLutUploadCmd) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT upload cmd failed");
        return false;
    }
    return true;
}

void destroyGradeLut(Context* ctx) {
    if (ctx->gradeLutUploadCmd != VK_NULL_HANDLE && ctx->commandPool != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &ctx->gradeLutUploadCmd);
    }
    ctx->gradeLutUploadCmd = VK_NULL_HANDLE;
    if (ctx->gradeLutStagingMap != nullptr && ctx->gradeLutStagingMem != VK_NULL_HANDLE) {
        vkUnmapMemory(ctx->device, ctx->gradeLutStagingMem);
    }
    ctx->gradeLutStagingMap = nullptr;
    if (ctx->gradeLutStaging != VK_NULL_HANDLE)
        vkDestroyBuffer(ctx->device, ctx->gradeLutStaging, nullptr);
    ctx->gradeLutStaging = VK_NULL_HANDLE;
    if (ctx->gradeLutStagingMem != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->gradeLutStagingMem, nullptr);
    ctx->gradeLutStagingMem = VK_NULL_HANDLE;
    if (ctx->gradeLutSampler != VK_NULL_HANDLE)
        vkDestroySampler(ctx->device, ctx->gradeLutSampler, nullptr);
    ctx->gradeLutSampler = VK_NULL_HANDLE;
    if (ctx->gradeLutView != VK_NULL_HANDLE)
        vkDestroyImageView(ctx->device, ctx->gradeLutView, nullptr);
    ctx->gradeLutView = VK_NULL_HANDLE;
    if (ctx->gradeLutImage != VK_NULL_HANDLE)
        vkDestroyImage(ctx->device, ctx->gradeLutImage, nullptr);
    ctx->gradeLutImage = VK_NULL_HANDLE;
    if (ctx->gradeLutMemory != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->gradeLutMemory, nullptr);
    ctx->gradeLutMemory = VK_NULL_HANDLE;
    ctx->gradeLutReady = false;
}

// Upload baked half bits (kGradeLutBytes) to the LUT image. Settings-change
// path (~8ms bake + one staging upload): submits on the record queue and
// waits idle, so no fence plumbing and trivially ordered vs grade submits.
int uploadGradeLut(Context* ctx, const uint16_t* halfBits) {
    if (ctx->gradeLutImage == VK_NULL_HANDLE && !createGradeLut(ctx)) {
        return VFVK_SUBMIT_FAILED;
    }
    memcpy(ctx->gradeLutStagingMap, halfBits, (size_t)kGradeLutBytes);
    VkCommandBuffer cmd = ctx->gradeLutUploadCmd;
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    VkImageMemoryBarrier pre{};
    pre.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    pre.oldLayout = ctx->gradeLutReady ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                     : VK_IMAGE_LAYOUT_UNDEFINED;
    pre.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    pre.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.image = ctx->gradeLutImage;
    pre.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    pre.subresourceRange.levelCount = 1;
    pre.subresourceRange.layerCount = 1;
    pre.srcAccessMask = ctx->gradeLutReady ? VK_ACCESS_SHADER_READ_BIT : 0;
    pre.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &pre);
    VkBufferImageCopy region{};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {(uint32_t)kGradeLutSize, 1, 1};
    vkCmdCopyBufferToImage(cmd, ctx->gradeLutStaging, ctx->gradeLutImage,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    VkImageMemoryBarrier post{};
    post.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    post.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    post.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    post.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.image = ctx->gradeLutImage;
    post.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    post.subresourceRange.levelCount = 1;
    post.subresourceRange.layerCount = 1;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    post.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &post);
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(ctx->queue, 1, &submit, VK_NULL_HANDLE) != VK_SUCCESS) {
        return VFVK_SUBMIT_FAILED;
    }
    if (vkQueueWaitIdle(ctx->queue) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    ctx->gradeLutReady = true;
    return VFVK_OK;
}

}  // namespace

// Shared device bring-up for initNative (first use) and reinitNative (recovery
// after the queue wedges or the device is lost mid-session). Requires g == nullptr.
static int initContext(JNIEnv* env, jbyteArray spv);

// Wait (bounded) for an in-flight viewfinder dispatch. computeNative
// waits UNLOCKED, so teardown/reset must join it before destroying the
// imports/output it reads/writes. Bounded at ~2s: the wait itself is
// capped at 500ms, so expiry needs a driver ignoring timeouts —
// teardown then proceeds loudly (the worker re-validates g after its
// wait and bails). Requires vkMutex (released while spinning).
static void joinVfCompute(std::unique_lock<std::mutex>& lock) {
    int spins = 0;
    while (vfComputeInFlight.load() > 0 && spins < 400) {
        lock.unlock();
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        lock.lock();
        ++spins;
    }
    if (vfComputeInFlight.load() > 0) {
        LOGW("vf-vk: teardown with VF compute in flight (wedged worker?)");
    }
}

static void teardownContext() {
    if (g == nullptr) return;
    Context* ctx = g;
    g = nullptr;
    vkGeneration.fetch_add(1);
    // Best-effort idle first: returns promptly (with an error) on a lost device.
    vkDeviceWaitIdle(ctx->device);
    destroyGrade(ctx);
    destroyShadeDummyImage(ctx);
    destroyShadeSampler(ctx);
    destroyGradeLut(ctx);
    for (auto& entry : ctx->inputs) destroyImportedBuffer(ctx, &entry.second);
    for (AHardwareBuffer* b : ctx->inputOrder) AHardwareBuffer_release(b);
    ctx->inputs.clear();
    ctx->inputOrder.clear();
    destroyImported(ctx, &ctx->output);
    if (ctx->outputBuffer) AHardwareBuffer_release(ctx->outputBuffer);
    ctx->outputBuffer = nullptr;
    destroyStaging(ctx);
    if (ctx->fence != VK_NULL_HANDLE) vkDestroyFence(ctx->device, ctx->fence, nullptr);
    if (ctx->commandPool != VK_NULL_HANDLE)
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
    if (ctx->pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->pipeline, nullptr);
    if (ctx->f16Pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->f16Pipeline, nullptr);
    if (ctx->pipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->pipelineLayout, nullptr);
    if (ctx->setLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->setLayout, nullptr);
    if (ctx->descriptorPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->descriptorPool, nullptr);
    VkDevice device = ctx->device;
    VkInstance instance = ctx->instance;
    delete ctx;
    if (device != VK_NULL_HANDLE) vkDestroyDevice(device, nullptr);
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_initNative(
    JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g != nullptr) return VFVK_OK;
    return initContext(env, spv);
}

// Full teardown + bring-up after persistent submit failures (wedged queue or
// lost device): imports are dropped and re-created on demand, so the next
// frame re-probes a fresh device instead of failing on a dead one forever.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_reinitNative(
    JNIEnv* env, jobject, jbyteArray spv) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    LOGW("vf-vk: reinitializing device after submit failures");
    joinVfCompute(vkGuard);
    teardownContext();
    int r = initContext(env, spv);
    if (r == VFVK_OK) {
        LOGI("vf-vk: device reinit ok");
    } else {
        LOGW("vf-vk: device reinit failed %d", r);
    }
    return r;
}

static int initContext(JNIEnv* env, jbyteArray spv) {
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;

    Context* ctx = new Context();
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "RawLensVf";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &app;
    if (vkCreateInstance(&instInfo, nullptr, &ctx->instance) != VK_SUCCESS) {
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    uint32_t gpuCount = 0;
    vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, nullptr);
    VkPhysicalDevice gpus[8];
    uint32_t n = gpuCount > 8 ? 8 : gpuCount;
    if (n == 0 || vkEnumeratePhysicalDevices(ctx->instance, &n, gpus) != VK_SUCCESS) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    for (uint32_t i = 0; i < n && ctx->gpu == VK_NULL_HANDLE; ++i) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &qCount, nullptr);
        VkQueueFamilyProperties props[16];
        uint32_t m = qCount > 16 ? 16 : qCount;
        vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &m, props);
        for (uint32_t q = 0; q < m; ++q) {
            if ((props[q].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) != 0) {
                ctx->gpu = gpus[i];
                ctx->queueFamily = q;
                break;
            }
        }
    }
    if (ctx->gpu == VK_NULL_HANDLE) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(ctx->gpu, nullptr, &extCount, nullptr);
    bool hasExternalMem = false, hasAhb = false, hasForeign = false;
    bool hasSem = false, hasSemFd = false;
    {
        uint32_t cap = extCount > 256 ? 256 : extCount;
        std::vector<VkExtensionProperties> exts(cap);
        if (vkEnumerateDeviceExtensionProperties(ctx->gpu, nullptr, &cap, exts.data()) == VK_SUCCESS) {
            for (uint32_t i = 0; i < cap; ++i) {
                if (!std::strcmp(exts[i].extensionName, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME))
                    hasForeign = true;
                if (!std::strcmp(exts[i].extensionName, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME))
                    hasExternalMem = true;
                if (!std::strcmp(exts[i].extensionName,
                                 VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME))
                    hasAhb = true;
                if (!std::strcmp(exts[i].extensionName, VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME))
                    hasSem = true;
                if (!std::strcmp(exts[i].extensionName, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME))
                    hasSemFd = true;
            }
        }
    }
    if (!hasExternalMem || !hasAhb || !hasForeign) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_NO_EXTENSION;
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = ctx->queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* devExt[] = {VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                            VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
                            VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME,
                            VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME};
    // Semaphore-fd export is optional (wait-free fast path); the required
    // three come first so a missing pair only disables the fast path.
    const uint32_t devExtCount = (hasSem && hasSemFd) ? 5 : 3;
    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &queueInfo;
    devInfo.enabledExtensionCount = devExtCount;
    devInfo.ppEnabledExtensionNames = devExt;
    if (vkCreateDevice(ctx->gpu, &devInfo, nullptr, &ctx->device) != VK_SUCCESS) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    vkGetDeviceQueue(ctx->device, ctx->queueFamily, 0, &ctx->queue);
    ctx->getAhbProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(ctx->device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!ctx->getAhbProps) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_NO_EXTENSION;
    }
    if (devExtCount == 5) {
        ctx->getSemFd = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
            vkGetDeviceProcAddr(ctx->device, "vkGetSemaphoreFdKHR"));
        ctx->hasSemaphoreFd = (ctx->getSemFd != nullptr);
        LOGI("vf-vk: semaphore-fd export %s", ctx->hasSemaphoreFd ? "ready" : "unavailable");
    }
    if (!createPipeline(ctx, code.data(), code.size())) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_PIPELINE_FAILED;
    }
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = ctx->queueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(ctx->device, &poolInfo, nullptr, &ctx->commandPool) != VK_SUCCESS) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->commandBuffer) != VK_SUCCESS) {
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(ctx->device, &fenceInfo, nullptr, &ctx->fence) != VK_SUCCESS) {
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(ctx->gpu, &props);
    LOGI("vf-vk: device ready: %s", props.deviceName);
    g = ctx;
    return VFVK_OK;
}

static bool readSuperpixelParams(JNIEnv* env, jintArray iparams, jfloatArray fparams,
                                   SuperpixelParams* out) {
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return false;
    jint ipairs[10];
    jfloat fppairs[8];
    env->GetIntArrayRegion(iparams, 0, 10, ipairs);
    env->GetFloatArrayRegion(fparams, 0, 8, fppairs);
    if (env->ExceptionCheck()) return false;
    for (int i = 0; i < 4; ++i) out->chans[i] = ipairs[i];
    for (int i = 0; i < 4; ++i) out->black[i] = fppairs[i];
    for (int i = 0; i < 4; ++i) out->invRange[i] = fppairs[4 + i];
    out->quadBase[0] = ipairs[4];
    out->quadBase[1] = ipairs[5];
    out->frameSize[0] = ipairs[6];
    out->frameSize[1] = ipairs[7];
    out->step = ipairs[8];
    out->pitch = ipairs[9];
    if (out->frameSize[0] <= 0 || out->frameSize[1] <= 0 ||
        out->frameSize[0] > VFVK_MAX_EDGE || out->frameSize[1] > VFVK_MAX_EDGE ||
        out->pitch <= 0 || out->pitch > 16384) {
        return false;
    }
    return true;
}

// Shared superpixel dispatch tail (zero-copy import + staging copy):
// descriptor write, record, submit, bounded fence wait. `foreignInput`
// selects the external-memory acquire/release barriers (imported HAL
// buffer) vs host-write visibility (staging upload, no foreign owner).
// The fence wait runs UNLOCKED (see computeNative); the generation check
// after it catches a teardown that gave up the join.
static int dispatchSuperpixel(std::unique_lock<std::mutex>& vkGuard, const SuperpixelParams& params,
                              VkBuffer input, bool foreignInput) {
    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = input;
    inputInfo.offset = 0;
    inputInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = g->output.view;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inputInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outputInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    // Shading binding (viewfinder shades in GL: dummy image, enable
    // stays 0).
    if (!ensureShadeSampler(g) || !ensureShadeDummy(g)) return VFVK_SUBMIT_FAILED;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = g->shadeDummyView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet shadeWrite{};
    shadeWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    shadeWrite.dstSet = g->descriptorSet;
    shadeWrite.dstBinding = 2;
    shadeWrite.descriptorCount = 1;
    shadeWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    shadeWrite.pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 1, &shadeWrite, 0, nullptr);

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.buffer = input;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    if (foreignInput) {
        // Adopt the HAL's fresh contents (buffer barrier + cache invalidate).
        bufBarrier.srcAccessMask = 0;
        bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        bufBarrier.dstQueueFamilyIndex = g->queueFamily;
        vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    } else {
        // Make the CPU memcpy visible to the shader (staging upload).
        bufBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    }
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->pipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->pipelineLayout, 0, 1, &g->descriptorSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.frameSize[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.frameSize[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    // Make writes available for the cross-API (GL) consumer before queue idle.
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    if (foreignInput) {
        // Return the borrowed RAW allocation to Camera2/CPU as well. A fence alone
        // orders execution; it does not transfer external-memory ownership.
        bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bufBarrier.dstAccessMask = 0;
        bufBarrier.srcQueueFamilyIndex = g->queueFamily;
        bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    }
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: submit failed %d", vr);
        // Fence was reset but nothing is in flight: disarm so the next
        // call does not reap-check a fence that will never signal.
        g->computeArmed = false;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = true;
    // Unlocked fence wait: the GL import below must see finished writes,
    // but this thread holds no mutex while record dispatches queued ahead
    // drain (~30ms in record mode), so record submits proceed freely.
    // Teardown/reset join vfComputeInFlight before destroying anything
    // this dispatch touches; the generation check below catches a
    // teardown that gave up the join (wedged worker) without touching
    // freed memory. A timeline-semaphore export is the follow-up.
    vfComputeInFlight.fetch_add(1);
    const uint64_t gen = vkGeneration.load();
    VkFence fence = g->fence;
    VkDevice device = g->device;
    vkGuard.unlock();
    vr = vkWaitForFences(device, 1, &fence, VK_TRUE, VFVK_VF_WAIT_NS);
    vkGuard.lock();
    vfComputeInFlight.fetch_sub(1);
    if (g == nullptr || vkGeneration.load() != gen) return VFVK_NOT_INITIALIZED;
    // Timeout: NO retire (a WaitIdle here would reintroduce the convoy
    // on the failure path). The dispatch may still be reading its input
    // when the caller releases the Image lease, but its output is never
    // presented (BUSY skips; the next submit overwrites the export), so
    // the worst case is garbage pixels nobody samples. Memory stays
    // mapped (import holds the buffer), so no fault is possible.
    if (vr == VK_TIMEOUT) return VFVK_BUSY;
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: fence wait failed %d; retiring submitted RAW reads before release", vr);
        // The caller releases its camera Image lease on return. A failed fence
        // alone is not permission to let the HAL overwrite that allocation.
        const VkResult retired = vkQueueWaitIdle(g->queue);
        // Queue idle => nothing in flight (armed cleared).
        g->computeArmed = false;
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = false;
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_ensureOutputNative(
    JNIEnv* env, jobject, jobject outputBuffer) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!outputBuffer) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, outputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == buf) return VFVK_OK;
    destroyImported(g, &g->output);
    if (g->outputBuffer) AHardwareBuffer_release(g->outputBuffer);
    g->outputBuffer = nullptr;
    int r = importAhb(g, buf, VK_IMAGE_USAGE_STORAGE_BIT, "output", false, &g->output);
    if (r != VFVK_OK) {
        LOGW("vf-vk: output storage import failed");
        return VFVK_OUTPUT_IMPORT_FAILED;
    }
    AHardwareBuffer_acquire(buf);
    g->outputBuffer = buf;
    LOGI("vf-vk: output import ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeNative(
    JNIEnv* env, jobject, jobject inputBuffer, jintArray iparams, jfloatArray fparams) {
    // unique_lock: the fence wait below runs UNLOCKED so record submits
    // never queue behind a viewfinder completion wait (that convoy cost
    // ~30-50ms per collision = dropped record frames). Everything else
    // stays serialized: imports, descriptor writes, record, submit.
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!inputBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;

    // Reap check: a previous dispatch still in flight (bounded-wait
    // BUSY last call) must complete before its command buffer is reset.
    // No work is done on this path — the caller just skips the frame.
    if (g->computeArmed) {
        const VkResult fs = vkGetFenceStatus(g->device, g->fence);
        if (fs == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        if (fs != VK_SUCCESS) return VFVK_BUSY;
        g->computeArmed = false;
    }

    // Get-or-import the input (AHB pool cycles, so cache by pointer with FIFO evict).
    auto it = g->inputs.find(buf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, buf, &imported);
        if (r != VFVK_OK) return VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(buf);
        g->inputs[buf] = imported;
        g->inputOrder.push_back(buf);
        it = g->inputs.find(buf);
    }
    const ImportedBuffer& input = it->second;

    SuperpixelParams params{};
    if (!readSuperpixelParams(env, iparams, fparams, &params)) return VFVK_BAD_ARGUMENT;

    return dispatchSuperpixel(vkGuard, params, input.buffer, true);
}

// GPU-copy tier (motioncam pattern): lock the input AHB, memcpy into the
// host-visible staging buffer, flush when not coherent, then the shared
// superpixel dispatch with host-write visibility instead of the foreign
// acquire/release barriers. Same fence, command buffer, export and
// reap/BUSY semantics as computeNative; only the input mechanism differs.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeCopyNative(
    JNIEnv* env, jobject, jobject inputBuffer, jintArray iparams, jfloatArray fparams) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!inputBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;

    // Same reap gate as computeNative: shared fence/command buffer. Runs
    // before the upload so BUSY frames skip the memcpy too.
    if (g->computeArmed) {
        const VkResult fs = vkGetFenceStatus(g->device, g->fence);
        if (fs == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        if (fs != VK_SUCCESS) return VFVK_BUSY;
        g->computeArmed = false;
    }

    // Size the staging upload from the allocation (layout-preserving whole
    // copy; pitch still comes from the Image planes via push constants).
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    VkResult r = g->getAhbProps(g->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: ahb-props failed %d", r);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    int sr = ensureStaging(g, ahbProps.allocationSize);
    if (sr != VFVK_OK) return sr;
    void* src = nullptr;
    // Rect null = whole buffer. Fence -1: the caller holds the camera Image
    // lease, so the HAL is done writing (same guarantee as zero-copy).
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &src) != 0 ||
        !src) {
        LOGW("vf-vk: copy: input lock failed");
        return VFVK_COPY_UPLOAD_FAILED;
    }
    std::memcpy(g->stagingMapped, src, (size_t)ahbProps.allocationSize);
    AHardwareBuffer_unlock(buf, nullptr);
    if (!g->stagingCoherent) {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = g->stagingMemory;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        r = vkFlushMappedMemoryRanges(g->device, 1, &range);
        if (r != VK_SUCCESS) {
            LOGW("vf-vk: copy: flush failed %d", r);
            return VFVK_COPY_UPLOAD_FAILED;
        }
    }

    SuperpixelParams params{};
    if (!readSuperpixelParams(env, iparams, fparams, &params)) return VFVK_BAD_ARGUMENT;
    return dispatchSuperpixel(vkGuard, params, g->stagingBuffer, false);
}

// Tier black-output probe: stride-aware max byte over a coarse grid of the
// export buffer (RGBA8 linear superpixel, pre-tonemap). The caller samples
// after the compute fence, with GL drained, so the read is deterministic.
// Returns 0..255, or -1 when the export cannot be locked (no CPU_READ
// usage: the tier is assumed healthy and probation stays pending).
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_sampleOutputNative(JNIEnv*, jobject) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr || g->outputBuffer == nullptr) return -1;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(g->outputBuffer, &desc);
    if (desc.width <= 0 || desc.height <= 0) return -1;
    AHardwareBuffer_Planes planes{};
    if (AHardwareBuffer_lockPlanes(g->outputBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1,
                                   nullptr, &planes) != 0) {
        return -1;
    }
    int maxByte = 0;
    if (planes.planeCount >= 1 && planes.planes[0].data) {
        const uint8_t* base = static_cast<const uint8_t*>(planes.planes[0].data);
        const int32_t rowStride = planes.planes[0].rowStride;
        const int stepX = (desc.width / 16 > 0) ? (int)(desc.width / 16) : 1;
        const int stepY = (desc.height / 16 > 0) ? (int)(desc.height / 16) : 1;
        for (uint32_t y = 0; y < desc.height && maxByte < 255; y += (uint32_t)stepY) {
            const uint8_t* row = base + (size_t)y * (size_t)rowStride;
            for (uint32_t x = 0; x < desc.width && maxByte < 255; x += (uint32_t)stepX) {
                const uint8_t* px = row + (size_t)x * 4u;
                for (int c = 0; c < 4; ++c) {
                    if (px[c] > maxByte) maxByte = px[c];
                }
            }
        }
    }
    AHardwareBuffer_unlock(g->outputBuffer, nullptr);
    return maxByte;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfVulkan_resetNative(JNIEnv*, jobject) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return;
    // Called from non-worker threads (recorder stop): join an in-flight
    // viewfinder wait before destroying the imports/output it uses.
    joinVfCompute(vkGuard);
    if (g == nullptr) return;
    vkDeviceWaitIdle(g->device);
    destroyGrade(g);
    for (auto& entry : g->inputs) destroyImportedBuffer(g, &entry.second);
    for (AHardwareBuffer* b : g->inputOrder) AHardwareBuffer_release(b);
    g->inputs.clear();
    g->inputOrder.clear();
    destroyImported(g, &g->output);
    if (g->outputBuffer) AHardwareBuffer_release(g->outputBuffer);
    g->outputBuffer = nullptr;
}

// ---- Phase-B grade stage (probe-only) ----

// Get-or-import a grade image (superpixel export as input, graded export as
// output). The probe owns a fixed pair; maps are cleared by reset/teardown.
static int gradeImage(Context* ctx, AHardwareBuffer* buf, const char* tag,
                      std::map<AHardwareBuffer*, ImportedImage>* cache, ImportedImage* out) {
    auto it = cache->find(buf);
    if (it != cache->end()) {
        *out = it->second;
        return VFVK_OK;
    }
    ImportedImage imported;
    int r = importAhb(ctx, buf, VK_IMAGE_USAGE_STORAGE_BIT, tag, false, &imported);
    if (r != VFVK_OK) return VFVK_OUTPUT_IMPORT_FAILED;
    AHardwareBuffer_acquire(buf);
    (*cache)[buf] = imported;
    *out = imported;
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_initF16Native(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->f16Pipeline != VK_NULL_HANDLE) return VFVK_OK;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createLinkedPipeline(g, code.data(), code.size(), &g->f16Pipeline)) {
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: f16 superpixel pipeline ready");
    return VFVK_OK;
}

// Wait-free twin resources: per-slot command buffers + isolated descriptor
// pools/sets (3 slots). Idempotent; shared by the grade and grade-YUV inits
// (whichever runs first brings them up).
static bool ensureTwinResources(Context* ctx) {
    if (ctx->twinComputeCmd[0] != VK_NULL_HANDLE) return true;
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 18;
    VkCommandBuffer twins[18];
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, twins) != VK_SUCCESS) {
        LOGW("vf-vk: twin command buffers failed");
        return false;
    }
    ctx->twinComputeCmd[0] = twins[0];
    ctx->twinComputeCmd[1] = twins[1];
    ctx->twinComputeCmd[2] = twins[2];
    ctx->twinGradeCmd[0] = twins[3];
    ctx->twinGradeCmd[1] = twins[4];
    ctx->twinGradeCmd[2] = twins[5];
    ctx->twinRcdCmd[0] = twins[6];
    ctx->twinRcdCmd[1] = twins[7];
    ctx->twinRcdCmd[2] = twins[8];
    ctx->twinMhcCmd[0] = twins[9];
    ctx->twinMhcCmd[1] = twins[10];
    ctx->twinMhcCmd[2] = twins[11];
    ctx->twinFusedCmd[0] = twins[12];
    ctx->twinFusedCmd[1] = twins[13];
    ctx->twinFusedCmd[2] = twins[14];
    ctx->twinPreviewCmd[0] = twins[15];
    ctx->twinPreviewCmd[1] = twins[16];
    ctx->twinPreviewCmd[2] = twins[17];
    VkDescriptorPoolSize recComputeSizes[3]{};
    recComputeSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    recComputeSizes[0].descriptorCount = 3;
    recComputeSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    recComputeSizes[1].descriptorCount = 3;
    recComputeSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    recComputeSizes[2].descriptorCount = 3;
    VkDescriptorPoolCreateInfo recComputePoolInfo{};
    recComputePoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    recComputePoolInfo.maxSets = 3;
    recComputePoolInfo.poolSizeCount = 3;
    recComputePoolInfo.pPoolSizes = recComputeSizes;
    VkDescriptorPoolSize recGradeSizes[1]{};
    recGradeSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    recGradeSizes[0].descriptorCount = 6;
    VkDescriptorPoolCreateInfo recGradePoolInfo{};
    recGradePoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    recGradePoolInfo.maxSets = 3;
    recGradePoolInfo.poolSizeCount = 1;
    recGradePoolInfo.pPoolSizes = recGradeSizes;
    if (vkCreateDescriptorPool(ctx->device, &recComputePoolInfo, nullptr, &ctx->recComputePool) != VK_SUCCESS ||
        vkCreateDescriptorPool(ctx->device, &recGradePoolInfo, nullptr, &ctx->recGradePool) != VK_SUCCESS) {
        LOGW("vf-vk: recorder descriptor pools failed");
        destroyGrade(ctx);
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo recAlloc{};
        recAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        recAlloc.descriptorPool = ctx->recComputePool;
        recAlloc.descriptorSetCount = 1;
        recAlloc.pSetLayouts = &ctx->setLayout;
        if (vkAllocateDescriptorSets(ctx->device, &recAlloc, &ctx->recComputeSets[i]) != VK_SUCCESS) {
            LOGW("vf-vk: recorder compute set %d failed", i);
            destroyGrade(ctx);
            return false;
        }
        recAlloc.descriptorPool = ctx->recGradePool;
        recAlloc.pSetLayouts = &ctx->gradeSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &recAlloc, &ctx->recGradeSets[i]) != VK_SUCCESS) {
            LOGW("vf-vk: recorder grade set %d failed", i);
            destroyGrade(ctx);
            return false;
        }
    }
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_initGradeNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->gradePipeline != VK_NULL_HANDLE) return VFVK_OK;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createGradePipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    if (!ensureTwinResources(g)) {
        destroyGrade(g);
        return VFVK_DEVICE_FAILED;
    }
    LOGI("vf-vk: grade pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeNative(
    JNIEnv* env, jobject, jobject inBuffer, jobject outBuffer,
    jintArray idims, jfloatArray fparams) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!inBuffer || !outBuffer || !idims || !fparams) return VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(fparams) < 15) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* inBuf = AHardwareBuffer_fromHardwareBuffer(env, inBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, outBuffer);
    if (!inBuf || !outBuf) return VFVK_BAD_ARGUMENT;
    ImportedImage inImg, outImg;
    int r = gradeImage(g, inBuf, "grade-in", &g->gradeIns, &inImg);
    if (r != VFVK_OK) return r;
    r = gradeImage(g, outBuf, "grade-out", &g->gradeOuts, &outImg);
    if (r != VFVK_OK) return r;

    GradeParams params{};
    jint ii[2];
    jfloat ff[15];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetFloatArrayRegion(fparams, 0, 15, ff);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.pad[0] = params.pad[1] = 0;
    for (int i = 0; i < 4; ++i) params.gains[i] = ff[i];
    for (int i = 0; i < 9; ++i) params.ccm[i / 3][i % 3] = ff[4 + i];
    params.misc[0] = ff[13];
    params.misc[1] = ff[14];
    params.misc[2] = params.misc[3] = 0.0f;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE) {
        return VFVK_BAD_ARGUMENT;
    }

    VkDescriptorImageInfo inInfo{};
    inInfo.imageView = inImg.view;
    inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = outImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->gradeSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->gradeSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    // Acquire the superpixel output (owned by FOREIGN after its dispatch).
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    // Fresh graded output.
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->gradePipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->gradePipelineLayout, 0, 1, &g->gradeSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->gradePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    // Release both images for the GL consumer (same pattern as superpixel).
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    vr = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, 2000000000ull);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade fence wait failed %d", vr);
        const VkResult retired = vkQueueWaitIdle(g->queue);
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

// ---- Wait-free submit twins (Direct-Log): same queue, ordered submits,
// no vkQueueWaitIdle. The consumer synchronizes via an exported sync fd
// (grade) or queue order (compute feeding grade). Blocking originals above
// are untouched (viewfinder + fallback path). ----

// Parse + upload one frame's lens-shading map (fp16 bits) into the
// per-slot RGBA16F image, via staging + an inline barriered blit
// submitted just ahead on the same queue. Null dims/gains (or any
// shape violation) disables shading for the frame; content validity
// (finite, >= 1) is enforced Kotlin-side by LensShadingModel. Always
// binds a valid view (slot or dummy), so a bad/missing map degrades
// the look, never the take.
void uploadShadeMap(JNIEnv* env, jintArray shadeDims, jshortArray shadeGains,
                    int slot, int32_t outDims[4], float outActive[4], VkImageView* outView) {
    outDims[0] = outDims[1] = 1;
    outDims[2] = 0;
    outDims[3] = 0;
    outActive[0] = outActive[1] = 0.0f;
    outActive[2] = outActive[3] = 1.0f;
    *outView = VK_NULL_HANDLE;
    bool usable = false;
    jint dd[6] = {0, 0, 0, 0, 1, 1};
    jsize gainLen = 0;
    if (shadeDims != nullptr && shadeGains != nullptr &&
        env->GetArrayLength(shadeDims) >= 6) {
        env->GetIntArrayRegion(shadeDims, 0, 6, dd);
        if (!env->ExceptionCheck()) {
            gainLen = env->GetArrayLength(shadeGains);
            const int rows = dd[0], cols = dd[1];
            if (rows >= 1 && rows <= kShadeMaxCells && cols >= 1 && cols <= kShadeMaxCells &&
                dd[4] > dd[2] && dd[5] > dd[3] &&
                gainLen == (jsize)rows * cols * 4) {
                usable = true;
            } else {
                LOGW("vf-vk: shade map shape %dx%d+%d,%d len=%d rejected",
                     cols, rows, (int)dd[2], (int)dd[3], (int)gainLen);
            }
        } else {
            env->ExceptionClear();
        }
    }
    if (usable && ensureShadeSampler(g) &&
        ensureShadeImage(g, slot, (int)dd[0], (int)dd[1])) {
        env->GetShortArrayRegion(shadeGains, 0, gainLen,
                                 reinterpret_cast<jshort*>(g->shadeStagingMaps[slot]));
        if (!env->ExceptionCheck()) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = g->shadeStagingMems[slot];
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            vkFlushMappedMemoryRanges(g->device, 1, &range);
            if (blitShadeImage(g, slot)) {
                outDims[0] = dd[0];
                outDims[1] = dd[1];
                outDims[2] = 1;
                // gainAt normalization, reciprocal form (the shader
                // multiplies): 1/max(w-1, 1) mirrors its clamp exactly.
                const int aw = dd[4] - dd[2] - 1;
                const int ah = dd[5] - dd[3] - 1;
                outActive[0] = (float)dd[2];
                outActive[1] = (float)dd[3];
                outActive[2] = 1.0f / (float)(aw > 1 ? aw : 1);
                outActive[3] = 1.0f / (float)(ah > 1 ? ah : 1);
                *outView = g->shadeViews[slot];
                return;
            }
        } else {
            env->ExceptionClear();
        }
    }
    // Disabled: bind the dummy so the layout stays valid.
    if (ensureShadeSampler(g) && ensureShadeDummy(g)) *outView = g->shadeDummyView;
}

// Mirror of computeNative without the fence wait: the following grade submit
// on the same queue is ordered after this one, so no CPU stall is needed.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeSubmitNative(
    JNIEnv* env, jobject, jobject inputBuffer, jobject expBuffer,
    jintArray iparams, jfloatArray fparams, jint slot,
    jintArray shadeDims, jshortArray shadeGains) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return VFVK_BAD_ARGUMENT;
    if (!inputBuffer || !expBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->twinComputeCmd[slot] == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (g->recComputeSets[slot] == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    AHardwareBuffer* expBuf = AHardwareBuffer_fromHardwareBuffer(env, expBuffer);
    if (!expBuf) return VFVK_BAD_ARGUMENT;
    // Recorder-private output import (never evicts the viewfinder's):
    // ping-pong pair cached by pointer, cleared by reset/teardown.
    ImportedImage recOut;
    auto roit = g->recOuts.find(expBuf);
    if (roit == g->recOuts.end()) {
        ImportedImage imported;
        int rout = importAhb(g, expBuf, VK_IMAGE_USAGE_STORAGE_BIT, "rec-out", false, &imported);
        if (rout != VFVK_OK) return VFVK_OUTPUT_IMPORT_FAILED;
        AHardwareBuffer_acquire(expBuf);
        g->recOuts[expBuf] = imported;
        recOut = imported;
    } else {
        recOut = roit->second;
    }
    Context::CmdSwap cmdSwap(g, g->twinComputeCmd[slot]);
    Context::SetSwap setSwap(&g->descriptorSet, g->recComputeSets[slot]);
    if (g->f16Pipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    Context::PipeSwap pipeSwap(g, g->f16Pipeline);
    Context::OutSwap outSwap(g, expBuf, recOut);
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;

    auto it = g->inputs.find(buf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, buf, &imported);
        if (r != VFVK_OK) return VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(buf);
        g->inputs[buf] = imported;
        g->inputOrder.push_back(buf);
        it = g->inputs.find(buf);
    }
    const ImportedBuffer& input = it->second;

    SuperpixelParams params{};
    jint ipairs[10];
    jfloat fppairs[8];
    env->GetIntArrayRegion(iparams, 0, 10, ipairs);
    env->GetFloatArrayRegion(fparams, 0, 8, fppairs);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    for (int i = 0; i < 4; ++i) params.chans[i] = ipairs[i];
    for (int i = 0; i < 4; ++i) params.black[i] = fppairs[i];
    for (int i = 0; i < 4; ++i) params.invRange[i] = fppairs[4 + i];
    params.quadBase[0] = ipairs[4];
    params.quadBase[1] = ipairs[5];
    params.frameSize[0] = ipairs[6];
    params.frameSize[1] = ipairs[7];
    params.step = ipairs[8];
    params.pitch = ipairs[9];
    if (params.frameSize[0] <= 0 || params.frameSize[1] <= 0 ||
        params.frameSize[0] > VFVK_MAX_EDGE || params.frameSize[1] > VFVK_MAX_EDGE ||
        params.pitch <= 0 || params.pitch > 16384) {
        return VFVK_BAD_ARGUMENT;
    }
    VkImageView shadeView = VK_NULL_HANDLE;
    uploadShadeMap(env, shadeDims, shadeGains, slot,
                   params.shadeDims, params.shadeActive, &shadeView);
    if (shadeView == VK_NULL_HANDLE) return VFVK_SUBMIT_FAILED;

    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = input.buffer;
    inputInfo.offset = 0;
    inputInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = g->output.view;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = shadeView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inputInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outputInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->descriptorSet;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 3, writes, 0, nullptr);

    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: submit-twin begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = 0;
    bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    bufBarrier.dstQueueFamilyIndex = g->queueFamily;
    bufBarrier.buffer = input.buffer;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->pipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->pipelineLayout, 0, 1, &g->descriptorSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.frameSize[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.frameSize[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.dstAccessMask = 0;
    bufBarrier.srcQueueFamilyIndex = g->queueFamily;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: submit-twin end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    // No fence, no wait: the grade submit below is ordered after this one on
    // the same queue, and the fd handoff carries completion to GL.
    vr = vkQueueSubmit(g->queue, 1, &submit, VK_NULL_HANDLE);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: submit-twin submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

// Mirror of gradeNative that signals an exportable binary semaphore instead
// of waiting, then returns the sync fd for the EGL native-fence handoff.
// Returns the fd (>= 0) or a negative -VFVK_* code (caller falls back to the
// blocking gradeNative). The exported fd owns the signal; the VkSemaphore is
// destroyed after export.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeSubmitNative(
    JNIEnv* env, jobject, jobject inBuffer, jobject outBuffer,
    jintArray idims, jfloatArray fparams, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    Context::CmdSwap cmdSwap(g, g->twinGradeCmd[slot]);
    if (g->recGradeSets[slot] == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    Context::SetSwap setSwap(&g->gradeSet, g->recGradeSets[slot]);
    if (g->gradePipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!inBuffer || !outBuffer || !idims || !fparams) return -VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(fparams) < 15) return -VFVK_BAD_ARGUMENT;
    AHardwareBuffer* inBuf = AHardwareBuffer_fromHardwareBuffer(env, inBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, outBuffer);
    if (!inBuf || !outBuf) return -VFVK_BAD_ARGUMENT;
    ImportedImage inImg, outImg;
    int r = gradeImage(g, inBuf, "grade-in", &g->gradeIns, &inImg);
    if (r != VFVK_OK) return -r;
    r = gradeImage(g, outBuf, "grade-out", &g->gradeOuts, &outImg);
    if (r != VFVK_OK) return -r;

    GradeParams params{};
    jint ii[2];
    jfloat ff[15];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetFloatArrayRegion(fparams, 0, 15, ff);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.pad[0] = params.pad[1] = 0;
    for (int i = 0; i < 4; ++i) params.gains[i] = ff[i];
    for (int i = 0; i < 9; ++i) params.ccm[i / 3][i % 3] = ff[4 + i];
    params.misc[0] = ff[13];
    params.misc[1] = ff[14];
    params.misc[2] = params.misc[3] = 0.0f;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE) {
        return -VFVK_BAD_ARGUMENT;
    }

    VkDescriptorImageInfo inInfo{};
    inInfo.imageView = inImg.view;
    inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = outImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->gradeSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->gradeSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        LOGW("vf-vk: grade-submit semaphore create failed");
        return -VFVK_SUBMIT_FAILED;
    }

    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        LOGW("vf-vk: grade-submit begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->gradePipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->gradePipelineLayout, 0, 1, &g->gradeSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->gradePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        LOGW("vf-vk: grade-submit end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    vr = vkQueueSubmit(g->queue, 1, &submit, VK_NULL_HANDLE);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        LOGW("vf-vk: grade-submit submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    vkDestroySemaphore(g->device, sem, nullptr);
    if (vr != VK_SUCCESS || fd < 0) {
        LOGW("vf-vk: grade-submit fd export failed %d", vr);
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- True-10-bit stage (Direct-Log P010): superpixel FP16 in, codec P010
// bytes out. Returns the completion sync fd (>= 0) or a negative -VFVK_*
// code; -20 means the codec buffer layout is not tight P010 (loud abort,
// never silent corruption). Shares the twin command buffers (per slot).
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_initGradeYuvNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->yuvPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin resources (command buffers, isolated sets) live with the grade
    // init; the yuv submit reuses them, so grade must be initialized first.
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createYuvPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: grade-yuv pipeline ready");
    return VFVK_OK;
}

// Codec P010 bytes out (storage buffer, byte-exact), cached by pointer
// in yuvOuts. Tight semi-planar validation: Y + interleaved UV must fit
// with less than a page of slack (allocators round total size up;
// strides themselves come from describe, so addressing is exact).
// Anything structurally divergent aborts loudly (-20) rather than
// misplacing UV. Shared by grade-YUV and fused.
static int importP010Out(Context* ctx, AHardwareBuffer* dstBuf, int yStrideB, int uvStrideB,
                         int fw, int fh, ImportedBuffer* out) {
    auto it = ctx->yuvOuts.find(dstBuf);
    if (it != ctx->yuvOuts.end()) {
        *out = it->second;
        return VFVK_OK;
    }
    ImportedBuffer imported;
    int rr = importInputBuffer(ctx, dstBuf, &imported);
    if (rr != VFVK_OK) {
        LOGW("vf-vk: yuv-out storage import failed %d", rr);
        return rr;
    }
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    if (ctx->getAhbProps(ctx->device, dstBuf, &ahbProps) != VK_SUCCESS) {
        destroyImportedBuffer(ctx, &imported);
        return VFVK_SUBMIT_FAILED;
    }
    const uint64_t expect =
        (uint64_t)yStrideB * (uint64_t)fh + (uint64_t)uvStrideB * (uint64_t)(fh / 2);
    if (ahbProps.allocationSize < expect ||
        ahbProps.allocationSize - expect > 4096) {
        LOGW("vf-vk: yuv-out not tight P010: alloc=%llu expect=%llu (y=%d uv=%d %dx%d)",
             (unsigned long long)ahbProps.allocationSize, (unsigned long long)expect,
             yStrideB, uvStrideB, fw, fh);
        destroyImportedBuffer(ctx, &imported);
        return -20;
    }
    AHardwareBuffer_acquire(dstBuf);
    ctx->yuvOuts[dstBuf] = imported;
    *out = imported;
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeYuvSubmitNative(
    JNIEnv* env, jobject, jobject expBuffer, jobject codecBuffer,
    jintArray idims, jintArray isrc, jfloatArray fparams, jintArray istrides, jint slot,
    jint fullRgb) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    if (fullRgb != 0 && fullRgb != 1) return -VFVK_BAD_ARGUMENT;
    if (g->yuvPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!expBuffer || !codecBuffer || !idims || !isrc || !fparams || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    const jsize fLen = env->GetArrayLength(fparams);
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(isrc) < 2 ||
        fLen < 17 || env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* expBuf = AHardwareBuffer_fromHardwareBuffer(env, expBuffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, codecBuffer);
    if (!expBuf || !dstBuf) return -VFVK_BAD_ARGUMENT;

    GradeYuvParams params{};
    jint ii[2], si[2], st[4];
    jfloat ff[17];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetIntArrayRegion(isrc, 0, 2, si);
    env->GetFloatArrayRegion(fparams, 0, 17, ff);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    // ff[17] selects the output profile (0 = BT.709, 1 = S-Log3,
    // 2 = HLG); short arrays heal to BT.709.
    int outMode = 0;
    if (fLen >= 18) {
        jfloat m = 0;
        env->GetFloatArrayRegion(fparams, 17, 1, &m);
        outMode = (int)(m + 0.5f);
    }
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    if (outMode < 0 || outMode > 2) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.srcDims[0] = si[0];
    params.srcDims[1] = si[1];
    foldGradeCcm(ff, params.ccm);
    params.misc[0] = ff[14];
    params.misc[1] = params.misc[2] = params.misc[3] = 0.0f;
    // ff[15]/ff[16] (contrast/saturation) are reserved: the record path
    // is direct-encode, no look.
    params.misc2[1] = (float)outMode;
    params.misc2[2] = ff[16];
    params.misc2[3] = 0.0f;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE * 2 || params.dims[1] > VFVK_MAX_EDGE * 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    // OUR OWN semi-planar P010: Y rows + interleaved UV rows, tight.
    // Anything else aborts loudly (-20) rather than misplacing chroma.
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0) {
        LOGW("vf-vk: yuv strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.misc[1] = (float)(yStrideB / 2);
    params.misc[2] = (float)(uvStrideB / 2);
    params.misc[3] = (float)((int64_t)yStrideB * fh);
    params.misc2[0] = (fullRgb != 0) ? 1.0f : 0.0f;

    // FP16 source in (superpixel export or full-res MHC rgb; shared
    // grade-in import, same image layout either way).
    ImportedImage inImg;
    int r = gradeImage(g, expBuf, "yuv-in", &g->gradeIns, &inImg);
    if (r != VFVK_OK) return -r;
    // Codec P010 bytes out (storage buffer, byte-exact). Import first so a
    // layout failure aborts before any dispatch is recorded.
    ImportedBuffer dstImg;
    {
        int rr = importP010Out(g, dstBuf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : -rr;
    }

    VkCommandBuffer cmd = g->twinGradeCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorImageInfo inInfo{};
    inInfo.imageView = inImg.view;
    inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    if (!g->gradeLutReady) return -VFVK_NOT_INITIALIZED;
    VkDescriptorImageInfo lutInfo{};
    lutInfo.sampler = g->gradeLutSampler;
    lutInfo.imageView = g->gradeLutView;
    lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->yuvSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->yuvSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->yuvSets[slot];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].pImageInfo = &lutInfo;
    vkUpdateDescriptorSets(g->device, 3, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        return -VFVK_SUBMIT_FAILED;
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    layoutBarrier(cmd, inImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    // Fresh output allocation: acquire ownership before the first write
    // (without this the store lands nowhere observable).
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = dstImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->yuvPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->yuvPipelineLayout, 0, 1, &g->yuvSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->yuvPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(cmd, gx, gy, 1);
    // Release for the codec (GL/EGL-free path): ownership back to FOREIGN;
    // completion in wall time rides the exported sync fd, which the caller
    // polls before queueInputBuffer (the codec has no fd channel).
    layoutBarrier(cmd, inImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    VkBufferMemoryBarrier outBarrier{};
    outBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outBarrier.dstAccessMask = 0;
    outBarrier.srcQueueFamilyIndex = g->queueFamily;
    outBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    outBarrier.buffer = dstImg.buffer;
    outBarrier.offset = 0;
    outBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &outBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    vr = vkQueueSubmit(g->queue, 1, &submit, VK_NULL_HANDLE);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    vkDestroySemaphore(g->device, sem, nullptr);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- Record preview (Direct-Log monitor): staged P010 in, RGBA8 out.
// Same-queue ordered after the encode submit, own exported sync fd for
// the EGL present path. Best-effort: any failure skips the preview for
// that frame, the encode is unaffected.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRecordPreview_initPreviewNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->previewPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin resources (command buffers, isolated sets) live with the grade
    // init; the preview submit reuses them, so grade must be up first.
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createPreviewPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: record-preview pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRecordPreview_previewSubmitNative(
    JNIEnv* env, jobject, jobject p010Buffer, jobject previewBuffer,
    jintArray idims, jintArray isrc, jintArray istrides, jint box, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    if (g->previewPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!p010Buffer || !previewBuffer || !idims || !isrc || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(isrc) < 2 ||
        env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* srcBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, previewBuffer);
    if (!srcBuf || !dstBuf) return -VFVK_BAD_ARGUMENT;

    PreviewParams params{};
    jint ii[2], si[2], st[4];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetIntArrayRegion(isrc, 0, 2, si);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.srcDims[0] = si[0];
    params.srcDims[1] = si[1];
    params.strides[3] = box;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE ||
        params.srcDims[0] <= 0 || params.srcDims[1] <= 0 ||
        params.srcDims[0] > VFVK_MAX_EDGE * 2 || params.srcDims[1] > VFVK_MAX_EDGE * 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    // Exact integer downsample on both axes, even box (the chroma grid is
    // half-res; an odd box would skew the UV taps). A wrong-scale preview
    // is worse than none: reject loudly, the recorder skips the frame.
    if (box < 2 || (box % 2) != 0 ||
        params.srcDims[0] != params.dims[0] * box ||
        params.srcDims[1] != params.dims[1] * box) {
        LOGW("vf-vk: preview box mismatch %dx%d -> %dx%d box=%d",
             params.srcDims[0], params.srcDims[1], params.dims[0], params.dims[1], box);
        return -VFVK_BAD_ARGUMENT;
    }
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0 ||
        fw != params.srcDims[0] || fh != params.srcDims[1]) {
        LOGW("vf-vk: preview strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.strides[0] = yStrideB / 2;
    params.strides[1] = uvStrideB / 2;
    params.strides[2] = yStrideB * fh;

    // P010 in (the encode submit just validated + wrote it; cache hit).
    ImportedBuffer srcImg;
    {
        int rr = importP010Out(g, srcBuf, yStrideB, uvStrideB, fw, fh, &srcImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : -rr;
    }
    // RGBA8 preview out (STORAGE_IMAGE, byte-exact for EGL re-import).
    ImportedImage dstImg;
    {
        int r = gradeImage(g, dstBuf, "preview-out", &g->previewOuts, &dstImg);
        if (r != VFVK_OK) return -r;
    }

    VkCommandBuffer cmd = g->twinPreviewCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = srcImg.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = dstImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->previewSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->previewSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        return -VFVK_SUBMIT_FAILED;
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    // Same-queue ordered after the encode submit (FIFO): re-acquire both
    // buffers from FOREIGN (the encode submit just released them).
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = srcImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->previewPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->previewPipelineLayout, 0, 1, &g->previewSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->previewPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.dims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    // Release for the EGL present path: ownership back to FOREIGN;
    // completion in wall time rides the exported sync fd, which the
    // presenter adopts before sampling (GL waits on the GPU timeline).
    {
        VkBufferMemoryBarrier release{};
        release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        release.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        release.dstAccessMask = 0;
        release.srcQueueFamilyIndex = g->queueFamily;
        release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        release.buffer = srcImg.buffer;
        release.offset = 0;
        release.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &release, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    vr = vkQueueSubmit(g->queue, 1, &submit, VK_NULL_HANDLE);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    vkDestroySemaphore(g->device, sem, nullptr);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- RCD demosaic (Direct-Log record path): one pipeline, u_mode
// selects dirs / green / R-B-at-opposite / R-B-at-green. Same
// fd-exporting convention as the grade submits (the recorder closes
// the intermediate fds; probes poll them for per-mode GPU timings). ----

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRcd_initRcdNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->rcdPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin command buffers live with the grade init (as for grade-YUV).
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createRcdPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: rcd demosaic pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRcd_rcdSubmitNative(
    JNIEnv* env, jobject, jobject cfaBuffer, jobject rgbBuffer, jobject scratchBuffer,
    jintArray iparams, jfloatArray fparams, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    if (g->rcdPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!cfaBuffer || !rgbBuffer || !scratchBuffer || !iparams || !fparams) {
        return -VFVK_BAD_ARGUMENT;
    }
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* cfaBuf = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, rgbBuffer);
    AHardwareBuffer* scrBuf = AHardwareBuffer_fromHardwareBuffer(env, scratchBuffer);
    if (!cfaBuf || !outBuf || !scrBuf) return -VFVK_BAD_ARGUMENT;

    RcdParams params{};
    jint ii[10];
    jfloat ff[8];
    env->GetIntArrayRegion(iparams, 0, 10, ii);
    env->GetFloatArrayRegion(fparams, 0, 8, ff);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.origin[0] = ii[2];
    params.origin[1] = ii[3];
    for (int i = 0; i < 4; ++i) params.chans[i] = ii[4 + i];
    params.pitch = ii[8];
    params.mode = ii[9];
    for (int i = 0; i < 4; ++i) params.black[i] = ff[i];
    for (int i = 0; i < 4; ++i) params.invRange[i] = ff[4 + i];
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE ||
        params.pitch <= 0 || params.pitch > 16384 ||
        params.mode < 0 || params.mode > 3 ||
        params.origin[0] < 0 || params.origin[1] < 0) {
        return -VFVK_BAD_ARGUMENT;
    }

    // CFA bytes in (shared input-buffer import, byte-exact).
    auto it = g->inputs.find(cfaBuf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, cfaBuf, &imported);
        if (r != VFVK_OK) return -VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(cfaBuf);
        g->inputs[cfaBuf] = imported;
        g->inputOrder.push_back(cfaBuf);
        it = g->inputs.find(cfaBuf);
    }
    const ImportedBuffer& input = it->second;
    // Full-res rgb out (written mode 1, refined modes 2-3).
    ImportedImage rgbImg;
    int r = gradeImage(g, outBuf, "rcd-rgb", &g->rgbOuts, &rgbImg);
    if (r != VFVK_OK) return -r;
    // Direction scratch (written mode 0, read modes 1-3).
    ImportedImage scrImg;
    r = gradeImage(g, scrBuf, "rcd-scratch", &g->scratchOuts, &scrImg);
    if (r != VFVK_OK) return -r;

    VkCommandBuffer cmd = g->twinRcdCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = input.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = rgbImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo scrInfo{};
    scrInfo.imageView = scrImg.view;
    scrInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->rcdSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->rcdSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->rcdSets[slot];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[2].pImageInfo = &scrInfo;
    vkUpdateDescriptorSets(g->device, 3, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        return -VFVK_SUBMIT_FAILED;
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = 0;
    bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    bufBarrier.dstQueueFamilyIndex = g->queueFamily;
    bufBarrier.buffer = input.buffer;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    // Mode 0 writes scratch fresh (rgb untouched); mode 1 writes rgb
    // fresh and reads scratch; modes 2-3 read-modify-write rgb texels
    // (same-invocation RMW only — no intra-dispatch hazard by the
    // shader's mosaic-from-CFA discipline) and read scratch.
    const int mode = params.mode;
    const bool scrWrite = mode == 0;
    const bool rgbWrite = mode >= 1;
    const bool rgbRead = mode >= 2;
    const bool scrRead = mode >= 1;
    if (scrWrite) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                      VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    } else if (scrRead) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    }
    if (rgbWrite) {
        layoutBarrier(cmd, rgbImg.image,
                      VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                      rgbRead ? (VkAccessFlags)(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                              : (VkAccessFlags)VK_ACCESS_SHADER_WRITE_BIT,
                      rgbRead ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
                      VK_IMAGE_LAYOUT_GENERAL,
                      VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->rcdPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->rcdPipelineLayout, 0, 1, &g->rcdSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->rcdPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(cmd, gx, gy, 1);
    if (scrWrite) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    } else if (scrRead) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    }
    if (rgbWrite) {
        layoutBarrier(cmd, rgbImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    }
    bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.dstAccessMask = 0;
    bufBarrier.srcQueueFamilyIndex = g->queueFamily;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    vr = vkQueueSubmit(g->queue, 1, &submit, VK_NULL_HANDLE);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    vkDestroySemaphore(g->device, sem, nullptr);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- Malvar-He-Cutler fast demosaic (Direct-Log record path): single
// dispatch, CFA in + write-only rgb out. Same fd-exporting convention
// as the other submits (the recorder closes the fd when the grade's fd
// supersedes it... in practice MHC runs right before the grade, whose
// fd carries completion; probes poll the MHC fd for GPU timings). ----

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfMhc_initMhcNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->mhcPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin command buffers live with the grade init (as for RCD/YUV).
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createMhcPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: mhc demosaic pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfMhc_mhcSubmitNative(
    JNIEnv* env, jobject, jobject cfaBuffer, jobject rgbBuffer,
    jintArray iparams, jfloatArray fparams, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    if (g->mhcPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!cfaBuffer || !rgbBuffer || !iparams || !fparams) return -VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* cfaBuf = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, rgbBuffer);
    if (!cfaBuf || !outBuf) return -VFVK_BAD_ARGUMENT;

    RcdParams params{};
    jint ii[10];
    jfloat ff[8];
    env->GetIntArrayRegion(iparams, 0, 10, ii);
    env->GetFloatArrayRegion(fparams, 0, 8, ff);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.origin[0] = ii[2];
    params.origin[1] = ii[3];
    for (int i = 0; i < 4; ++i) params.chans[i] = ii[4 + i];
    params.pitch = ii[8];
    params.mode = ii[9];
    for (int i = 0; i < 4; ++i) params.black[i] = ff[i];
    for (int i = 0; i < 4; ++i) params.invRange[i] = ff[4 + i];
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE ||
        params.pitch <= 0 || params.pitch > 16384 ||
        params.mode != 0 ||
        params.origin[0] < 0 || params.origin[1] < 0) {
        return -VFVK_BAD_ARGUMENT;
    }

    // CFA bytes in (shared input-buffer import, byte-exact).
    auto it = g->inputs.find(cfaBuf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, cfaBuf, &imported);
        if (r != VFVK_OK) return -VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(cfaBuf);
        g->inputs[cfaBuf] = imported;
        g->inputOrder.push_back(cfaBuf);
        it = g->inputs.find(cfaBuf);
    }
    const ImportedBuffer& input = it->second;
    // Full-res rgb out (fresh overwrite every dispatch; imports share
    // the rgbOuts map with RCD — same buffers, same usage).
    ImportedImage rgbImg;
    int r = gradeImage(g, outBuf, "mhc-rgb", &g->rgbOuts, &rgbImg);
    if (r != VFVK_OK) return -r;

    VkCommandBuffer cmd = g->twinMhcCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = input.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = rgbImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->mhcSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->mhcSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        return -VFVK_SUBMIT_FAILED;
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = 0;
    bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    bufBarrier.dstQueueFamilyIndex = g->queueFamily;
    bufBarrier.buffer = input.buffer;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    layoutBarrier(cmd, rgbImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->mhcPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->mhcPipelineLayout, 0, 1, &g->mhcSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->mhcPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    // MHC uses 16x16 workgroups (vf_mhc.comp; all other vf stages 8x8).
    const uint32_t gx = (uint32_t)(params.dims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.dims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    layoutBarrier(cmd, rgbImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.dstAccessMask = 0;
    bufBarrier.srcQueueFamilyIndex = g->queueFamily;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    vr = vkQueueSubmit(g->queue, 1, &submit, VK_NULL_HANDLE);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    vkDestroySemaphore(g->device, sem, nullptr);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- Fused MHC+grade record stage (vf_mhcyuv.comp) ---------------------
// Single dispatch: CFA mosaic -> demosaic -> grade -> P010. The split
// stages serialize on one queue (measured 38.6ms > the 33.3ms frame)
// and round-trip 150MB of RGB; fusing removes both. Params reuse the
// packMhc + packGrade arrays (same semantics) via a per-slot SSBO.

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_initFusedYuvNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->fusedPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin command buffers live with the grade init (as for RCD/MHC/YUV).
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createFusedPipeline(g, code.data(), code.size())) {
        destroyFused(g);
        return VFVK_PIPELINE_FAILED;
    }
    if (!createFusedParams(g)) {
        destroyFused(g);
        return VFVK_DEVICE_FAILED;
    }
    LOGI("vf-vk: fused mhc+yuv pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeLutUploadNative(JNIEnv* env, jobject, jshortArray halfBits) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    // 129 RGBA half words; must match kGradeLutBytes above.
    constexpr jsize kHalfWords = 129 * 4;
    if (!halfBits || env->GetArrayLength(halfBits) != kHalfWords) {
        return VFVK_BAD_ARGUMENT;
    }
    std::vector<uint16_t> bits((size_t)kHalfWords);
    env->GetShortArrayRegion(halfBits, 0, (jsize)bits.size(),
                             reinterpret_cast<jshort*>(bits.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    return uploadGradeLut(g, bits.data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_fusedYuvSubmitNative(
    JNIEnv* env, jobject, jobject cfaBuffer, jobject p010Buffer,
    jintArray mhcIparams, jfloatArray mhcFparams,
    jintArray gradeIdims, jfloatArray gradeFparams, jintArray istrides, jint slot,
    jintArray shadeDims, jshortArray shadeGains) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    if (g->fusedPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!cfaBuffer || !p010Buffer || !mhcIparams || !mhcFparams ||
        !gradeIdims || !gradeFparams || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    const jsize gfLen = env->GetArrayLength(gradeFparams);
    if (env->GetArrayLength(mhcIparams) < 10 || env->GetArrayLength(mhcFparams) < 8 ||
        env->GetArrayLength(gradeIdims) < 2 || gfLen < 17 ||
        env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    if (g->fusedParamMaps[slot] == nullptr) return -VFVK_DEVICE_FAILED;
    AHardwareBuffer* cfaBuf = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    if (!cfaBuf || !dstBuf) return -VFVK_BAD_ARGUMENT;

    FusedParams params{};
    jint mi[10], gi[2], st[4];
    jfloat mf[8], gf[17];
    env->GetIntArrayRegion(mhcIparams, 0, 10, mi);
    env->GetFloatArrayRegion(mhcFparams, 0, 8, mf);
    env->GetIntArrayRegion(gradeIdims, 0, 2, gi);
    env->GetFloatArrayRegion(gradeFparams, 0, 17, gf);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    // gf[17] selects the output profile (0 = BT.709, 1 = S-Log3,
    // 2 = HLG); short arrays heal to BT.709.
    int outMode = 0;
    if (gfLen >= 18) {
        jfloat m = 0;
        env->GetFloatArrayRegion(gradeFparams, 17, 1, &m);
        outMode = (int)(m + 0.5f);
    }
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    if (outMode < 0 || outMode > 2) return -VFVK_BAD_ARGUMENT;
    params.cDims[0] = mi[0];
    params.cDims[1] = mi[1];
    params.cOrigin[0] = mi[2];
    params.cOrigin[1] = mi[3];
    for (int i = 0; i < 4; ++i) params.cChans[i] = mi[4 + i];
    params.cPitch = mi[8];
    const int mhcMode = mi[9];
    for (int i = 0; i < 4; ++i) params.cNormBias[i] = mf[i];
    for (int i = 0; i < 4; ++i) params.cInvRange[i] = mf[4 + i];
    params.oDims[0] = gi[0];
    params.oDims[1] = gi[1];
    for (int i = 0; i < 4; ++i) params.gGains[i] = gf[i];
    foldGradeCcm(gf, params.gCcm);
    params.gMisc[0] = gf[13];
    // gf[15]/gf[16] (contrast/saturation) are reserved: the record path
    // is direct-encode, no look. gGains.w still carries gf[16] (the
    // shader ignores it) so the SSBO layout never drifts.
    params.gGains[3] = gf[16];
    const float bypass = gf[14];
    if (params.cDims[0] <= 0 || params.cDims[1] <= 0 ||
        params.cDims[0] > VFVK_MAX_EDGE || params.cDims[1] > VFVK_MAX_EDGE ||
        params.cPitch <= 0 || params.cPitch > 16384 ||
        mhcMode != 0 || bypass != 0.0f ||
        params.cOrigin[0] < 0 || params.cOrigin[1] < 0 ||
        params.oDims[0] <= 0 || params.oDims[1] <= 0 ||
        params.oDims[0] > VFVK_MAX_EDGE * 2 || params.oDims[1] > VFVK_MAX_EDGE * 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0) {
        LOGW("vf-vk: fused strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.gMisc[1] = (float)(yStrideB / 2);
    params.gMisc[2] = (float)(uvStrideB / 2);
    params.gMisc[3] = (float)((int64_t)yStrideB * fh);
    VkImageView shadeView = VK_NULL_HANDLE;
    uploadShadeMap(env, shadeDims, shadeGains, slot,
                   params.sDims, params.sActive, &shadeView);
    if (shadeView == VK_NULL_HANDLE) return -VFVK_SUBMIT_FAILED;
    // After the uploader (it zeroes sDims.w): the output profile word.
    params.sDims[3] = outMode;

    // CFA bytes in (shared input-buffer import, byte-exact).
    auto it = g->inputs.find(cfaBuf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, cfaBuf, &imported);
        if (r != VFVK_OK) return -VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(cfaBuf);
        g->inputs[cfaBuf] = imported;
        g->inputOrder.push_back(cfaBuf);
        it = g->inputs.find(cfaBuf);
    }
    const ImportedBuffer& input = it->second;
    // P010 bytes out (shared tight validation; -20 aborts loudly).
    ImportedBuffer dstImg;
    {
        int r = importP010Out(g, dstBuf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (r != VFVK_OK) return (r == -20) ? -20 : -r;
    }
    // Params in (per-slot coherent SSBO; flush for good measure).
    memcpy(g->fusedParamMaps[slot], &params, sizeof(params));
    {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = g->fusedParamMems[slot];
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        vkFlushMappedMemoryRanges(g->device, 1, &range);
    }

    VkCommandBuffer cmd = g->twinFusedCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = input.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo paramInfo{};
    paramInfo.buffer = g->fusedParamBufs[slot];
    paramInfo.offset = 0;
    paramInfo.range = sizeof(FusedParams);
    if (!g->gradeLutReady) return -VFVK_NOT_INITIALIZED;
    VkDescriptorImageInfo lutInfo{};
    lutInfo.sampler = g->gradeLutSampler;
    lutInfo.imageView = g->gradeLutView;
    lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = shadeView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[5]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->fusedSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->fusedSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->fusedSets[slot];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[2].pBufferInfo = &paramInfo;
    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = g->fusedSets[slot];
    writes[3].dstBinding = 3;
    writes[3].descriptorCount = 1;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[3].pImageInfo = &lutInfo;
    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[4].dstSet = g->fusedSets[slot];
    writes[4].dstBinding = 4;
    writes[4].descriptorCount = 1;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[4].pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 5, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        return -VFVK_SUBMIT_FAILED;
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkBufferMemoryBarrier inBarrier{};
    inBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inBarrier.srcAccessMask = 0;
    inBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    inBarrier.dstQueueFamilyIndex = g->queueFamily;
    inBarrier.buffer = input.buffer;
    inBarrier.offset = 0;
    inBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &inBarrier, 0, nullptr);
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = dstImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->fusedPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->fusedPipelineLayout, 0, 1, &g->fusedSets[slot], 0, nullptr);
    // Fused uses 16x16 workgroups (vf_mhcyuv.comp; verified in the .spv):
    // /8 over-dispatches 4x (harmless via the oDims guard, but wasted GPU).
    const uint32_t gx = (uint32_t)(params.oDims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.oDims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    inBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inBarrier.dstAccessMask = 0;
    inBarrier.srcQueueFamilyIndex = g->queueFamily;
    inBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &inBarrier, 0, nullptr);
    VkBufferMemoryBarrier outBarrier{};
    outBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outBarrier.dstAccessMask = 0;
    outBarrier.srcQueueFamilyIndex = g->queueFamily;
    outBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    outBarrier.buffer = dstImg.buffer;
    outBarrier.offset = 0;
    outBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &outBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    vr = vkQueueSubmit(g->queue, 1, &submit, VK_NULL_HANDLE);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    vkDestroySemaphore(g->device, sem, nullptr);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- Post-record stabilization warp (vf_stabwarp.comp) -------------------
// Tight-P010 storage buffer in (one decoded frame, host-uploaded through
// a persistently-mapped staging buffer) + OUR P010 staging storage buffer
// out (importP010Out validation, shared yuvOuts cache). Single command
// buffer + blocking fence submit: the pass runs on a background thread
// after the take, so no twin ping-pong and no fd export. Plain VFVK_*
// return codes (non-negative, unlike the fd-exporting submits).
bool createStabPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: stab shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->stabSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(StabWarpParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->stabSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr,
                               &ctx->stabPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->stabSetLayout, nullptr);
        ctx->stabSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->stabPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr,
                                          &ctx->stabPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: stab compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->stabPool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->stabPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->stabSetLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->stabSet) != VK_SUCCESS) {
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->stabCmd) != VK_SUCCESS) {
        LOGW("vf-vk: stab command buffer failed");
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(ctx->device, &fenceInfo, nullptr, &ctx->stabFence) != VK_SUCCESS) {
        LOGW("vf-vk: stab fence failed");
        return false;
    }
    return true;
}

// Tight-P010 upload staging for one WxH frame (host-visible + coherent,
// mapped persistently): Y rows of W u16 + H/2 chroma rows of W u16.
bool createStabStaging(Context* ctx, int w, int h) {
    const uint64_t bytes = (uint64_t)w * (uint64_t)h * 3;
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = bytes;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->stabStaging) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging buffer failed");
        return false;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx->device, ctx->stabStaging, &req);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryTypeProps(
        req.memoryTypeBits, memProps,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: stab staging: no coherent type");
        return false;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->stabStagingMem) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging alloc failed");
        return false;
    }
    if (vkBindBufferMemory(ctx->device, ctx->stabStaging, ctx->stabStagingMem, 0) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging bind failed");
        return false;
    }
    if (vkMapMemory(ctx->device, ctx->stabStagingMem, 0, req.size, 0,
                    &ctx->stabStagingMap) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging map failed");
        ctx->stabStagingMap = nullptr;
        return false;
    }
    ctx->stabW = w;
    ctx->stabH = h;
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfStab_initStabNative(JNIEnv* env, jobject, jbyteArray spv,
                                              jint width, jint height) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (width <= 0 || height <= 0 || width > VFVK_MAX_EDGE || height > VFVK_MAX_EDGE ||
        (width % 2) != 0 || (height % 2) != 0) {
        return VFVK_BAD_ARGUMENT;
    }
    if (g->stabPipeline != VK_NULL_HANDLE) {
        // Idempotent per dims: a same-size re-init is a no-op, a new size
        // reallocates the staging (the pipeline itself is size-agnostic).
        if (g->stabW == width && g->stabH == height) return VFVK_OK;
        if (g->stabStagingMap != nullptr && g->stabStagingMem != VK_NULL_HANDLE)
            vkUnmapMemory(g->device, g->stabStagingMem);
        g->stabStagingMap = nullptr;
        if (g->stabStaging != VK_NULL_HANDLE)
            vkDestroyBuffer(g->device, g->stabStaging, nullptr);
        g->stabStaging = VK_NULL_HANDLE;
        if (g->stabStagingMem != VK_NULL_HANDLE)
            vkFreeMemory(g->device, g->stabStagingMem, nullptr);
        g->stabStagingMem = VK_NULL_HANDLE;
        g->stabW = 0;
        g->stabH = 0;
        if (!createStabStaging(g, width, height)) {
            destroyStab(g);
            return VFVK_DEVICE_FAILED;
        }
        return VFVK_OK;
    }
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createStabPipeline(g, code.data(), code.size()) ||
        !createStabStaging(g, width, height)) {
        destroyStab(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: stab warp pipeline ready %dx%d", width, height);
    return VFVK_OK;
}

// One decoded frame into the tight-P010 staging: Y rows then interleaved
// UV rows, u16 units, 10-bit codes in the upper bits (grade convention).
// 8-bit decoder output upscales video-range (v<<2); 10-bit copies as-is
// (decoder P010 matches the codec P010 convention by construction).
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfStab_stabUploadNative(
    JNIEnv* env, jobject, jobject yBuf, jobject uBuf, jobject vBuf,
    jint yStrideB, jint uStrideB, jint vStrideB,
    jint uPxB, jint vPxB, jint width, jint height, jboolean is10Bit) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->stabStagingMap == nullptr || g->stabW != width || g->stabH != height) {
        return VFVK_BAD_ARGUMENT;
    }
    if (!yBuf || !uBuf || !vBuf) return VFVK_BAD_ARGUMENT;
    if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) {
        return VFVK_BAD_ARGUMENT;
    }
    const int pxB = is10Bit ? 2 : 1;
    if (yStrideB < width * pxB || uStrideB < (width / 2) * uPxB ||
        vStrideB < (width / 2) * vPxB || uPxB <= 0 || vPxB <= 0) {
        LOGW("vf-vk: stab upload strides implausible");
        return VFVK_BAD_ARGUMENT;
    }
    uint8_t* yPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    uint8_t* uPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
    uint8_t* vPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPtr || !uPtr || !vPtr) return VFVK_BAD_ARGUMENT;
    const jlong yCap = env->GetDirectBufferCapacity(yBuf);
    const jlong uCap = env->GetDirectBufferCapacity(uBuf);
    const jlong vCap = env->GetDirectBufferCapacity(vBuf);
    const int64_t yNeed = (int64_t)yStrideB * (height - 1) + (int64_t)width * pxB;
    const int64_t uNeed = (int64_t)uStrideB * (height / 2 - 1) + (int64_t)(width / 2) * uPxB;
    const int64_t vNeed = (int64_t)vStrideB * (height / 2 - 1) + (int64_t)(width / 2) * vPxB;
    if (yCap < yNeed || uCap < uNeed || vCap < vNeed) {
        LOGW("vf-vk: stab upload planes too small");
        return VFVK_BAD_ARGUMENT;
    }
    uint16_t* dst = static_cast<uint16_t*>(g->stabStagingMap);
    uint16_t* dstUv = dst + (int64_t)width * height;
    if (is10Bit) {
        for (int y = 0; y < height; ++y) {
            const uint8_t* row = yPtr + (int64_t)y * yStrideB;
            uint16_t* out = dst + (int64_t)y * width;
            for (int x = 0; x < width; ++x) {
                // Little-endian u16 load (ByteBuffer order is irrelevant).
                out[x] = (uint16_t)(row[x * 2] | (row[x * 2 + 1] << 8));
            }
        }
        for (int y = 0; y < height / 2; ++y) {
            const uint8_t* uRow = uPtr + (int64_t)y * uStrideB;
            const uint8_t* vRow = vPtr + (int64_t)y * vStrideB;
            uint16_t* out = dstUv + (int64_t)y * width;
            for (int x = 0; x < width / 2; ++x) {
                const uint8_t* us = uRow + (int64_t)x * uPxB;
                const uint8_t* vs = vRow + (int64_t)x * vPxB;
                out[x * 2] = (uint16_t)(us[0] | (us[1] << 8));
                out[x * 2 + 1] = (uint16_t)(vs[0] | (vs[1] << 8));
            }
        }
    } else {
        for (int y = 0; y < height; ++y) {
            const uint8_t* row = yPtr + (int64_t)y * yStrideB;
            uint16_t* out = dst + (int64_t)y * width;
            for (int x = 0; x < width; ++x) {
                out[x] = (uint16_t)(row[x] << 8);  // v<<2 code, stored <<6
            }
        }
        for (int y = 0; y < height / 2; ++y) {
            const uint8_t* uRow = uPtr + (int64_t)y * uStrideB;
            const uint8_t* vRow = vPtr + (int64_t)y * vStrideB;
            uint16_t* out = dstUv + (int64_t)y * width;
            for (int x = 0; x < width / 2; ++x) {
                out[x * 2] = (uint16_t)(uRow[(int64_t)x * uPxB] << 8);
                out[x * 2 + 1] = (uint16_t)(vRow[(int64_t)x * vPxB] << 8);
            }
        }
    }
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfStab_stabSubmitNative(
    JNIEnv* env, jobject, jobject p010Buffer, jintArray iparams, jfloatArray fparams) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->stabPipeline == VK_NULL_HANDLE || g->stabCmd == VK_NULL_HANDLE ||
        g->stabFence == VK_NULL_HANDLE || g->stabStaging == VK_NULL_HANDLE) {
        return VFVK_PIPELINE_FAILED;
    }
    if (!p010Buffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(iparams) < 4 || env->GetArrayLength(fparams) < 9) {
        return VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    if (!dstBuf) return VFVK_BAD_ARGUMENT;
    StabWarpParams params{};
    jint ii[4];
    jfloat ff[9];
    env->GetIntArrayRegion(iparams, 0, 4, ii);
    env->GetFloatArrayRegion(fparams, 0, 9, ff);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    const int w = ii[0], h = ii[1];
    const int yStrideB = ii[2], uvStrideB = ii[3];
    const int fw = w, fh = h;
    if (w != g->stabW || h != g->stabH || w <= 0 || h <= 0 ||
        w > VFVK_MAX_EDGE || h > VFVK_MAX_EDGE || (w % 2) != 0 || (h % 2) != 0) {
        return VFVK_BAD_ARGUMENT;
    }
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || (yStrideB % 2) != 0 ||
        (uvStrideB % 4) != 0) {
        LOGW("vf-vk: stab out strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return VFVK_BAD_ARGUMENT;
    }
    params.dims[0] = w;
    params.dims[1] = h;
    params.srcStrides[0] = w;
    params.srcStrides[1] = w;
    params.outStrides[0] = yStrideB / 2;
    params.outStrides[1] = uvStrideB / 2;
    params.uvBase[0] = w * h * 2;
    params.uvBase[1] = yStrideB * fh;
    for (int i = 0; i < 9; ++i) {
        if (!std::isfinite(ff[i])) return VFVK_BAD_ARGUMENT;
        params.mat[i] = ff[i];
    }

    ImportedBuffer dstImg;
    {
        int rr = importP010Out(g, dstBuf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (rr != VFVK_OK) return rr;
    }
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = g->stabStaging;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->stabSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->stabSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    VkCommandBuffer cmd = g->stabCmd;
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    // Host upload above happened-before this call (same thread): make it
    // visible to the shader read.
    {
        VkBufferMemoryBarrier upload{};
        upload.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        upload.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        upload.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        upload.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        upload.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        upload.buffer = g->stabStaging;
        upload.offset = 0;
        upload.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &upload,
                             0, nullptr);
    }
    // Re-acquire the staging output from FOREIGN (the CPU copy released it).
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = dstImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire,
                             0, nullptr);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->stabPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->stabPipelineLayout, 0, 1, &g->stabSet, 0, nullptr);
    vkCmdPushConstants(cmd, g->stabPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(w + 15) / 16;
    const uint32_t gy = (uint32_t)(h + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    // Release for the CPU copy path: ownership back to FOREIGN; the fence
    // below carries wall-time completion (no fd export on this stage).
    {
        VkBufferMemoryBarrier release{};
        release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        release.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        release.dstAccessMask = 0;
        release.srcQueueFamilyIndex = g->queueFamily;
        release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        release.buffer = dstImg.buffer;
        release.offset = 0;
        release.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &release,
                             0, nullptr);
    }
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->stabFence);
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    vr = vkWaitForFences(g->device, 1, &g->stabFence, VK_TRUE, 5000000000ULL);
    vkResetFences(g->device, 1, &g->stabFence);
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfStab_resetStabNative(JNIEnv*, jobject) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return;
    vkDeviceWaitIdle(g->device);
    destroyStab(g);
}
