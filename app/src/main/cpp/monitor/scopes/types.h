#pragma once
#include <cstddef>
#include <cstdint>

#if IMAGE_SCOPES_HAS_VULKAN
#include <vulkan/vulkan.h>
#endif

namespace image_scopes {

inline constexpr std::uint32_t kDisplayWaveformColumns = 512;
inline constexpr std::uint32_t kDisplayWaveformBins = 256;
inline constexpr std::uint32_t kVectorscopeBins = 256;
inline constexpr std::uint32_t kMaxFramesInFlight = 8;
inline constexpr std::uint32_t kRawWaveformColumns = 256;
inline constexpr std::uint32_t kRawWaveformBins = 64;

enum class SamplingMode : std::uint32_t {
    FullReference = 0,
    Production50 = 1,
    Production25 = 2,
};

enum class WaveformMode : std::uint32_t { Luma = 0, RgbOverlay = 1 };
enum class RawWaveformMode : std::uint32_t { PhysicalOverlay = 0, R = 1, G1 = 2, G2 = 3, B = 4 };

// 128 is a production candidate that must earn release through visual/perf testing.
// 256 remains the high-resolution/reference representation.
enum class VectorscopeGrid : std::uint32_t { Compact128 = 128, Reference256 = 256 };
enum class DensityScale : std::uint32_t { Linear = 0, Logarithmic = 1, SquareRoot = 2 };

struct SamplingMetadata {
    SamplingMode mode = SamplingMode::FullReference;
    std::uint32_t sampledPixelCount = 0;
    std::uint32_t sourcePixelCount = 0;
    float samplingFraction = 1.0f;
    std::uint32_t gridWidth = 0;
    std::uint32_t gridHeight = 0;
};

struct DisplayExposureStatsData {
    float lumaP50 = 0.0f;
    float lumaP95 = 0.0f;
    float lumaP99 = 0.0f;
    float brightFraction90 = 0.0f;
    float brightFraction96 = 0.0f;
    float anyChannelClippedFraction = 0.0f;
    std::uint32_t sampleCount = 0;
    std::uint32_t reserved = 0;
};

struct RawStatus {
    std::uint32_t completeCellCount = 0;
    std::uint32_t logicalClippedCount[3]{};
    std::uint32_t logicalNearHighlightCount[3]{};
    std::uint32_t logicalNearBlackCount[3]{};
    std::uint32_t logicalBlackFloorCount[3]{};
    float logicalClippedFraction[3]{};
    float logicalNearHighlightFraction[3]{};
    float logicalNearBlackFraction[3]{};
    float logicalBlackFloorFraction[3]{};
    std::uint32_t logicalClipCount[4]{};
    float logicalClipFraction[4]{};
};

struct WaveformRenderParams {
    WaveformMode mode = WaveformMode::Luma;
    float densityGain = 1.0f;
    float opacity = 1.0f;

    // Compatibility fields retained in the 0.1.0 public layout. The accepted
    // photographic waveform presentation is frozen to Linear / 1. Other
    // values are rejected instead of being silently ignored.
    DensityScale densityScale = DensityScale::Linear;
    std::uint32_t pointSpreadBins = 1;

    bool showGrid = true;
    bool showBackground = true;
};

struct RawWaveformRenderParams {
    RawWaveformMode mode = RawWaveformMode::PhysicalOverlay;
    float densityGain = 1.0f;
    float opacity = 1.0f;
    bool showGrid = true;
    bool showBackground = true;
};

struct VectorscopeRenderParams {
    float densityGain = 1.0f;
    float opacity = 1.0f;

    // Compatibility fields retained in the 0.1.0 public layout. The accepted
    // photographic presentation is intentionally frozen: callers must leave
    // these at Logarithmic / 1. Non-default values are rejected rather than
    // silently ignored.
    DensityScale densityScale = DensityScale::Logarithmic;
    std::uint32_t pointSpreadBins = 1;

    bool showGrid = true;
    bool showTargets = true;
    bool showBackground = true;
};

#if IMAGE_SCOPES_HAS_VULKAN
struct VulkanContext {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    const VkAllocationCallbacks* allocator = nullptr;
};

struct DisplayImageView {
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
    VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
};

struct ScopeRenderTarget {
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
    VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
};

struct BufferView {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceSize offset = 0;
    VkDeviceSize size = 0;
};

struct DisplayWaveformGpuView {
    BufferView storage{};
    SamplingMetadata sampling{};
    WaveformMode mode = WaveformMode::Luma;
    std::uint32_t planeCount = 3;  // storage always reserves R/G/B-sized planes; luma uses plane 0
    std::uint32_t planeStrideElements = kDisplayWaveformColumns * kDisplayWaveformBins;
    std::uint32_t sampledPixelCountOffsetBytes = 3u * kDisplayWaveformColumns * kDisplayWaveformBins * 4u;
};

struct VectorscopeGpuView {
    BufferView storage{};
    SamplingMetadata sampling{};
    std::uint32_t densityOffsetBytes = 0;
    // Numerical storage reserves the 256x256 maximum, while sampling.gridWidth/gridHeight
    // state the active grid. Metadata/max counters remain at the fixed tail offsets.
    std::uint32_t sampledPixelCountOffsetBytes = kVectorscopeBins * kVectorscopeBins * 4u;
    std::uint32_t maximumOffsetBytes = (kVectorscopeBins * kVectorscopeBins + 1u) * 4u;
};

// Zero-copy view of the raw_stats 1.1.0 CFA waveform output (frozen ABI).
struct RawWaveformGpuView {
    BufferView waveform{};  // sizeof(raw_stats::RawCfaWaveformStd430) == 262144
};
#endif

}  // namespace image_scopes
