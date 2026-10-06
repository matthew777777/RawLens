#pragma once
#include <cstdint>
#include <memory>

#include "image_scopes/types.h"
#include "image_scopes/version.h"

namespace image_scopes {

#if IMAGE_SCOPES_HAS_VULKAN
struct CreateInfo {
    VulkanContext context{};
    std::uint32_t maxFramesInFlight = 3;
};

struct DisplayExposureStatsRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    DisplayImageView input{};
    std::uint32_t frameSlot = 0;
    // AE-only sparse meter. One deterministic sample is taken directly from
    // each block; unsampled pixels are never visited by the shader.
    std::uint32_t sampleBlockSize = 4;  // supported: 2 or 4
};

struct DisplayWaveformRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    DisplayImageView input{};
    ScopeRenderTarget renderTarget{};
    WaveformRenderParams render{};
    SamplingMode samplingMode = SamplingMode::FullReference;
    std::uint32_t frameSlot = 0;
    bool renderEnabled = true;
    // Clockwise source-to-display rotation. Measurement columns follow displayed X.
    std::uint32_t sourceQuarterTurns = 0;
};

struct DisplayWaveformRenderInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    ScopeRenderTarget renderTarget{};
    WaveformRenderParams render{};
    std::uint32_t frameSlot = 0;
};

struct VectorscopeRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    DisplayImageView input{};
    ScopeRenderTarget renderTarget{};
    VectorscopeRenderParams render{};
    SamplingMode samplingMode = SamplingMode::FullReference;
    VectorscopeGrid grid = VectorscopeGrid::Reference256;
    std::uint32_t frameSlot = 0;
    bool renderEnabled = true;
};

struct VectorscopeRenderInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    ScopeRenderTarget renderTarget{};
    VectorscopeRenderParams render{};
    std::uint32_t frameSlot = 0;
};

struct RawWaveformRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    RawWaveformGpuView rawWaveform{};
    ScopeRenderTarget renderTarget{};
    RawWaveformRenderParams render{};
    std::uint32_t frameSlot = 0;
};

class ImageScopes {
   public:
    explicit ImageScopes(const CreateInfo& info);
    ~ImageScopes();
    ImageScopes(const ImageScopes&) = delete;
    ImageScopes& operator=(const ImageScopes&) = delete;
    ImageScopes(ImageScopes&&) noexcept;
    ImageScopes& operator=(ImageScopes&&) noexcept;

    void recordDisplayExposureStats(const DisplayExposureStatsRecordInfo& info);
    void recordDisplayWaveform(const DisplayWaveformRecordInfo& info);
    // Render the most recently measured display waveform for frameSlot without
    // rereading the source image or changing numerical density. The requested
    // WaveformMode must match the mode measured into that slot.
    void recordDisplayWaveformRender(const DisplayWaveformRenderInfo& info);
    // Frame-slot lifetime boundary. Call retireFrameSlot() only after all GPU
    // work recorded for the slot has completed. It promotes a recorded waveform
    // measurement to render-only-valid state and recycles all immutable per-invocation
    // descriptor arenas (waveform, vectorscope, and RAW render paths). If recorded work is abandoned and
    // never submitted, call discardFrameSlotRecordings() instead.
    void retireFrameSlot(std::uint32_t frameSlot);
    void discardFrameSlotRecordings(std::uint32_t frameSlot);
    void recordVectorscope(const VectorscopeRecordInfo& info);
    // Render vectorscope data valid for this recording context. A pending
    // measurement may be consumed only by its own command buffer; other command
    // buffers require the most recently retired measurement. Numerical
    // accumulation is unchanged; this records only the frozen photographic
    // presentation pass from the private 512x512 display-density field.
    void recordVectorscopeRender(const VectorscopeRenderInfo& info);
    void recordRawWaveformRender(const RawWaveformRecordInfo& info);

    DisplayWaveformGpuView displayWaveformView(std::uint32_t frameSlot) const;
    // Metadata describes only the most recently retired measurement.
    VectorscopeGpuView vectorscopeView(std::uint32_t frameSlot) const;

    // Explicit GPU copy helpers. They record only; the caller owns destination
    // memory, submission and synchronization/readback.
    void recordCopyDisplayExposureStats(VkCommandBuffer cmd, std::uint32_t frameSlot, VkBuffer dst,
                                        VkDeviceSize dstOffset) const;
    void recordCopyDisplayWaveform(VkCommandBuffer cmd, std::uint32_t frameSlot, VkBuffer dst,
                                   VkDeviceSize dstOffset) const;
    void recordCopyVectorscope(VkCommandBuffer cmd, std::uint32_t frameSlot, VkBuffer dst,
                               VkDeviceSize dstOffset) const;

    std::uint32_t maxFramesInFlight() const noexcept;

   private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
#endif

}  // namespace image_scopes
