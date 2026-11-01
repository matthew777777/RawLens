#pragma once

#include <vulkan/vulkan.h>

#include <cmath>
#include <cstdint>
#include <functional>
#include <memory>

#include "rawr/vk/GpuContext.h"

// RawTherapee Capture Sharpening (rtengine/capturesharpening.cc, rt_algo.cc;
// Ingo Weyrich 2019) on the GPU: Richardson-Lucy deconvolution of luminance
// with a Gaussian PSF, gated by a contrast/clip blend mask, applied to RGB as
// a luminance ratio. Auto radius (from diagonal green ratios) and auto
// contrast (flat-tile search) run on the GPU too; nothing is read back.
//
// Domain: the demosaiced, pre-white-balance camera RGB the still pipeline
// produces (white = 1). The passes white-balance and scale to RT's 65535 domain
// internally, so thresholds and luminance weights match RT; the result is
// written back in place in the input encoding. Run it right after demosaic,
// before denoise and white balance (RT's order). Native CFA samples must be
// intact (AMaZE/RCD/VNG/merge outputs keep them): the auto radius and the
// fallback clip mask read them back from the RGB image.
namespace rawr::capsharp {

// CaptureSharpeningParams defaults (rtengine/params/raw.cc).
struct Params {
    bool autoRadius = true;
    float radius = 0.75f;        // used when autoRadius is false, 0.4..2
    float radiusOffset = 0.0f;   // corner boost, 0..1
    bool autoContrast = true;
    float contrast = 10.0f;      // used when autoContrast is false, 0..200 (RT units)
    std::uint32_t iterations = 20;  // 1..250
    bool iterationCheck = true;  // RT "auto limit iterations"
};

inline bool validParams(const Params& p) noexcept {
    return std::isfinite(p.radius) && p.radius >= 0.4f && p.radius <= 2.0f && std::isfinite(p.radiusOffset) &&
           p.radiusOffset >= 0.0f && p.radiusOffset <= 1.0f && std::isfinite(p.contrast) && p.contrast >= 0.0f &&
           p.contrast <= 200.0f && p.iterations >= 1 && p.iterations <= 250;
}

struct RecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    std::uint32_t width = 0, height = 0;
    // RGBA16F, GENERAL layout, storage usage; read and written in place. Prior
    // writes must be visible to compute reads.
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    // Optional R16_UINT per-2x2-cell sensor clip bits (1 << CFA channel label,
    // raw_demosaic sensor_clip_pack). Null: sites at >= 0.95 of white count as clipped.
    VkImageView clipState = VK_NULL_HANDLE;
    std::uint32_t cfaPattern = 0;  // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    float whiteBalance[3] = {1.0f, 1.0f, 1.0f};  // camera RGB gains (only ratios matter)
    Params params;
    // Optional: kTimestamps compute-stage timestamps from timingQuery on:
    // start, auto radius, auto contrast, raw blend, row blur, transpose,
    // column blur, Y copy, deconvolution.
    VkQueryPool timingPool = VK_NULL_HANDLE;
    std::uint32_t timingQuery = 0;
    // Tile rows (32 px) per deconvolution band; 0 = one band. With a tile
    // boundary set, the caller can submit between bands (and once before the
    // deconvolution) so the viewfinder is not starved by one long dispatch.
    std::uint32_t deconvBandRows = 0;
    // Scratch budget in pixels (context rows x width); larger images run in
    // horizontal strips with the same result (radius and contrast are still
    // chosen over the whole frame). 0 = default (24 Mpx, ~150 MB of scratch).
    std::uint64_t maxStripPixels = 0;
};
inline constexpr std::uint32_t kTimestamps = 9;

// Values the GPU chose, readable once the recorded work has completed.
struct Result {
    float radius = 0.0f;
    float contrast = 0.0f;  // RT units (threshold x 100); 0 = blend 1 everywhere
    bool valid = false;
};

class CaptureSharpening final {
   public:
    explicit CaptureSharpening(const rawr::vk::GpuContext& gpu);
    ~CaptureSharpening();
    CaptureSharpening(const CaptureSharpening&) = delete;
    CaptureSharpening& operator=(const CaptureSharpening&) = delete;

    // Records every pass into info.commandBuffer and ends with a compute
    // write -> compute/transfer read barrier on the image. Scratch (a float
    // buffer and the R16F blend mask, 6 bytes per context pixel; see
    // maxStripPixels) is (re)allocated when the
    // size changes, so a size change requires the previous submission to have
    // completed. Returns false (records nothing) for invalid input.
    bool record(const RecordInfo& info);
    // Optional hook run between deconvolution bands (see deconvBandRows); the
    // caller may submit and re-begin the same command buffer (with a memory
    // barrier). Clear it after record() if it captures locals.
    void setTileBoundary(std::function<void()> boundary);
    // Reads the radius/contrast of the last completed record().
    Result lastResult() const;
    std::uint64_t scratchBytes() const noexcept;
    // Strips used by the last record() (1 = whole image).
    std::uint32_t lastStripCount() const noexcept;
    // Frees the size-dependent scratch (pipelines stay); the next record()
    // reallocates it. The previous submission must have completed.
    void releaseScratch() noexcept;

   private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace rawr::capsharp
