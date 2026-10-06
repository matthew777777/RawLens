#pragma once
#include <cstddef>
#include <cstdint>
namespace image_scopes::detail {
inline constexpr std::uint32_t kWavePlaneElements=512u*256u;
inline constexpr std::uint32_t kWaveRgbElements=3u*kWavePlaneElements;
struct alignas(16) DisplayWaveformStd430 {
    std::uint32_t density[kWaveRgbElements];
    std::uint32_t sampledPixelCount;
    std::uint32_t maxima[3];
};
static_assert(sizeof(DisplayWaveformStd430)==1572880);
static_assert(offsetof(DisplayWaveformStd430,sampledPixelCount)==1572864);

inline constexpr std::uint32_t kVectorElements=256u*256u;
struct alignas(16) VectorscopeStd430 {
    std::uint32_t density[kVectorElements];
    std::uint32_t sampledPixelCount;
    std::uint32_t maximum;
    std::uint32_t pad[2];
};
static_assert(sizeof(VectorscopeStd430)==262160);
static_assert(offsetof(VectorscopeStd430,sampledPixelCount)==262144);

// Private presentation-only vectorscope field.  The public numerical
// VectorscopeStd430 remains unchanged at 128/256 bins.  This 512² field is
// accumulated directly from source samples before 8-bit Cb/Cr bin collapse.
inline constexpr std::uint32_t kVectorPartialPartitions = 16u;
inline constexpr std::uint32_t kVectorPartialStride = 65536u;
inline constexpr std::uint32_t kVectorPartialElements =
    kVectorPartialPartitions * kVectorPartialStride;
inline constexpr std::uint32_t kVectorPresentationGrid = 512u;
inline constexpr std::uint32_t kVectorPresentationElements =
    kVectorPresentationGrid * kVectorPresentationGrid;
inline constexpr std::uint32_t kVectorPresentationOffsetElements =
    kVectorPartialElements;
inline constexpr std::size_t kVectorPresentationOffsetBytes =
    std::size_t(kVectorPresentationOffsetElements) * sizeof(std::uint32_t);
inline constexpr std::size_t kVectorPresentationBytes =
    std::size_t(kVectorPresentationElements) * sizeof(std::uint32_t);
inline constexpr std::size_t kVectorScratchBytes =
    kVectorPresentationOffsetBytes + kVectorPresentationBytes;

}
