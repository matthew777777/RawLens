// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// hdrplus host internals: context, per-shader descriptor layouts (matching
// the vendored GLSL bindings 1:1), run-scoped image/buffer allocation, and
// the record() helper mirroring rawr::raw_gpu_pipeline::VulkanExecutor.
#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <string>
#include <vector>

#include "RawMergeHdrPlusGpu.h"

// Forward declaration; completed after the namespace (host .cpps see the
// full definition through this header).
struct HdrPlusContext;

namespace rawlens {
namespace hdrplus {

namespace hp = ::rawr::raw_merge_hdrplus_gpu;

// 1:1 with RAWR's Hdrp*/Hdrq* ShaderId block (VulkanExecutor.h order).
enum class Shader : std::uint32_t {
    HdrpPrepare = 0,
    HdrpHotPixel,
    HdrpAvgPool,
    HdrpBlur,
    HdrpUpsampleAlign,
    HdrpCorrectUpsampling,
    HdrpTileDiff,
    HdrpBestTile,
    HdrpWarp,
    HdrpColorDiff,
    HdrpColumnSum,
    HdrpMean,
    HdrpMergeWeight,
    HdrpAccumulate,
    HdrpFinalize,
    HdrqToRgba,
    HdrqWarpRgba,
    HdrqRms,
    HdrqMismatch,
    HdrqRegionMean,
    HdrqMismatchNorm,
    HdrqForwardDft,
    HdrqMerge,
    HdrqDeconvolute,
    HdrqBackwardDft,
    HdrqBorder,
    HdrqAccumulate,
    HdrqShiftTable,
    Count
};

constexpr std::uint32_t kShaderCount = static_cast<std::uint32_t>(Shader::Count);

// Asset file stems under spirv/hdrplus/ (".spv" appended at load).
const char* shader_asset_name(Shader s);

// Descriptor bindings declared by each vendored shader (verified against
// the GLSL `layout(set = 0, binding = N)` lines and RAWR's record() calls).
struct BindingInfo {
    std::uint32_t binding;
    VkDescriptorType type;  // STORAGE_IMAGE or STORAGE_BUFFER only
};
struct ProgramLayout {
    const BindingInfo* bindings;
    std::uint32_t binding_count;
};
const ProgramLayout& shader_layout(Shader s);

struct GpuImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    std::uint32_t width = 0, height = 0;
};

struct GpuBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize bytes = 0;
    void* mapped = nullptr;  // set for host-visible staging only
};

struct Program {
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
};

struct ImageBinding {
    std::uint32_t binding;
    VkImageView view;
};

struct BufferBinding {
    std::uint32_t binding;
    VkBuffer buffer;
    VkDeviceSize offset = 0;
    VkDeviceSize range = VK_WHOLE_SIZE;
};

// Run-scoped resources: every image/buffer created for one merge, freed
// together at the end. Equivalent to one RAWR arena lifetime; unlike the
// arena there is no cross-lifetime aliasing (simpler host, same output).
class RunArena {
   public:
    RunArena(VkDevice device, VkPhysicalDevice physical) : device_(device), physical_(physical) {
        // image()/buffer()/staging() hand out pointers into these vectors, so
        // they must never reallocate: bounds are N <= 64 inputs, <= 8 pyramid
        // levels (2 images each), ~30 fixed scratch images, and ~16 buffers.
        images_.reserve(128);
        buffers_.reserve(32);
    }
    ~RunArena() { reset(); }
    RunArena(const RunArena&) = delete;
    RunArena& operator=(const RunArena&) = delete;

    // Storage image in GENERAL layout (transition recorded by caller via
    // transition_all_to_general), usable as STORAGE + transfer src/dst.
    GpuImage* image(VkFormat format, std::uint32_t w, std::uint32_t h, std::string& err);
    // Device-local storage buffer (transfer src/dst capable).
    GpuBuffer* buffer(VkDeviceSize bytes, std::string& err);
    // Host-visible staging buffer (transfer src/dst capable), persistently mapped.
    GpuBuffer* staging(VkDeviceSize bytes, std::string& err);

    void transition_all_to_general(VkCommandBuffer cmd);
    void reset() noexcept;

   private:
    VkDevice device_;
    VkPhysicalDevice physical_;
    std::vector<GpuImage> images_;
    std::vector<GpuBuffer> buffers_;
};

// Descriptor helper: one immutable set per dispatch from a run-scoped pool
// (same snapshot semantics as RAWR's per-batch transient pools).
class RunDescriptors {
   public:
    RunDescriptors(VkDevice device) : device_(device) {}
    ~RunDescriptors() { reset(); }
    RunDescriptors(const RunDescriptors&) = delete;
    RunDescriptors& operator=(const RunDescriptors&) = delete;

    bool init(std::string& err);
    // Allocates + writes a set for `bindings` against `program.dsl`.
    VkDescriptorSet snapshot(const Program& program, const ImageBinding* images, std::uint32_t image_count,
                             const BufferBinding* buffers, std::uint32_t buffer_count, std::string& err);
    void reset() noexcept;

   private:
    VkDevice device_;
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
};

// Mirrors VulkanExecutor::record: bind pipeline, push constants (128-byte
// range like RAWR), snapshot descriptors, dispatch.
bool record(HdrPlusContext* ctx, RunDescriptors& desc, VkCommandBuffer cmd, Shader shader,
            const ImageBinding* images, std::uint32_t image_count, const BufferBinding* buffers,
            std::uint32_t buffer_count, const void* push, std::uint32_t push_bytes, std::uint32_t gx,
            std::uint32_t gy, std::string& err);

// 1:1 with RAWR's computeWriteBarrier: SHADER_WRITE -> SHADER_READ|WRITE.
void compute_write_barrier(VkCommandBuffer cmd);

}  // namespace hdrplus
}  // namespace rawlens

// Completes the hdrplus_host.h forward declaration at global scope (the C
// API names ::HdrPlusContext; in-namespace code refers to it unqualified).
struct HdrPlusContext {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    std::uint32_t queue_family = 0;
    VkCommandPool pool = VK_NULL_HANDLE;
    double timestamp_period_ns = 0.0;
    rawlens::hdrplus::Program programs[rawlens::hdrplus::kShaderCount];
    std::uint32_t loaded_shaders = 0;
    double last_gpu_ms = 0.0;
    VkPipelineCache pipeline_cache = VK_NULL_HANDLE;
    std::string pipeline_cache_path;  // empty = in-memory cache only
};

// Lazily creates ctx->pipeline_cache, warming it from
// pipeline_cache_path when set. The result (possibly null on failure;
// null means "no cache") is the cache argument for pipeline creation.
VkPipelineCache hdrplus_pipeline_cache(HdrPlusContext* ctx);
// Persists ctx->pipeline_cache to pipeline_cache_path when a path is set.
// No-op otherwise; never fatal.
void hdrplus_save_pipeline_cache(HdrPlusContext* ctx);
