#pragma once

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>
#include <vulkan/vulkan.h>

// RawLens extensions over RAWR (algorithm/grain, wienerApply, recordToBuffer).
#define RAW_DENOISE_RAWLENS_EXT 1

namespace raw_denoise {

// Profiled wavelet denoise for production stills (DT-denoiseprofile
// wavelets/Y0U0V0/new-VST port). Operates pre-WB camera-linear RGBA16F.
//
// Tiling: undecimated a-trous support radius is 2*2^s per scale, so exact
// tiles carry halo H = 2*(2^maxScale-1) per side (254px at 7 scales).
// Thresholds are per-tile (darktable-tiled equivalent): each tile's detail
// sums reduce on-GPU, so no host round-trip exists anywhere in record().
// All profile math (VST p, WB-adaptive matrices) is float32, mirroring the
// float32-pinned Python oracle.

struct DenoiseSpirv {
    const uint32_t* words = nullptr;
    size_t wordCount = 0;
};

struct DenoiseShaders {
    DenoiseSpirv precondition;
    DenoiseSpirv atrous;
    DenoiseSpirv threshold;
    DenoiseSpirv backtransform;
    // Optional (RawLens): buffer-output backtransform for recordToBuffer().
    DenoiseSpirv backtransformBuffer;
    // Required for algorithm 1 (RawLens Wiener): denoise_img_wiener_apply.
    DenoiseSpirv wienerApply;
    // Optional (RawLens steps 1-3): RawLens-only shader variants, so RAWR mode
    // keeps running the unchanged (byte-identical) precondition/backtransform.
    DenoiseSpirv lowresChroma;
    DenoiseSpirv preconditionRawLens;
    DenoiseSpirv backtransformBufferRawLens;
};

struct DenoiseParams {
    // Raw profiled strength multiplier (validated 1..2, up to 8).
    // strength <= 0 bypasses: the caller must skip record() instead.
    float strength = 1.0f;
    // Shadow-preservation fulcrum (higher preserves more texture).
    float shadows = 1.0f;
    // Normalized-domain noise model (green channel): variance = a*x + b.
    float noiseA = 0.0f;
    float noiseB = 0.0f;
    // Camera white-balance multipliers (green = 1), pre-WB domain.
    std::array<float, 3> whiteBalance{1.0f, 1.0f, 1.0f};
    int maxScale = 7;
    float forceY = 0.25f;
    float forceUv = 0.75f;
    // Inner tile extent; extended buffers are (tile+2H)^2.
    uint32_t tileSize = 1536;
    // RawLens: 0 = RAWR soft-threshold BayesShrink (output byte-identical to
    // RAWR), 1 = RawLens local Wiener (single pass per scale, no statistics).
    int algorithm = 0;
    // RawLens Wiener only: fraction of removed fine-scale luma noise kept as
    // grain at scale 0 (scale 1 keeps 0.4x, coarser scales none). 0..1.
    float grain = 0.0f;
    // RawLens Wiener only: multiplies the luma noise prior (noise-visibility
    // adaptive strength; 1 = unchanged).
    float lumaScale = 1.0f;
    // Tile range [tileBegin, tileEnd) in row-major tile order, so callers can
    // split one frame over several short submits. Tiles are independent; the
    // default records every tile.
    uint32_t tileBegin = 0;
    uint32_t tileEnd = 0xFFFFFFFFu;
    // RawLens Wiener only (all off = previous RawLens output):
    // step 3: isolated-speck threshold in sigma before the VST (0 = off).
    float impulseT = 0.0f;
    // step 2: exact unbiased inverse VST (clip + RCD-aware tables, gated).
    bool exactInverse = false;
    // step 1: low-res luma-guided chroma correction weight (0 = off).
    float lowresWeight = 0.0f;
    float lowresEps = 1.0f;
};

// Maps a Camera2 SENSOR_NOISE_PROFILE (S,O) pair to the normalized domain:
// raw x_raw in [black, white], normalized x=(x_raw-black)/range gives
// a = S/range, b = (S*black+O)/range^2. Same formula as the oracle.
inline void CfaNoiseToNormalized(float s, float o, float black, float white, float& a,
                                 float& b) {
    const float range = white - black;
    a = (range > 0.0f) ? s / range : 0.0f;
    b = (range > 0.0f) ? (s * black + o) / (range * range) : 0.0f;
}

// Green-channel noise model from a SENSOR_NOISE_PROFILE / DNG NoiseProfile
// laid out per CFA site in row-major 2x2 order (S0,O0,..,S3,O3). The two green
// sites (by CFA code: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR) are averaged and mapped
// with the caller's normalization levels. Returns false (a/b untouched) when
// the profile or levels are invalid; callers then disable denoise rather than
// guess a model.
inline bool GreenNoiseToNormalized(const double* profile, size_t count, uint32_t cfa, float black,
                                   float white, float& a, float& b) {
    if (!profile || count < 8 || !(white > black) || !(black >= 0.0f)) return false;
    const size_t g0 = (cfa == 1u || cfa == 2u) ? 0 : 1;
    const size_t g1 = (cfa == 1u || cfa == 2u) ? 3 : 2;
    const float s = float(profile[g0 * 2] + profile[g1 * 2]) * 0.5f;
    const float o = float(profile[g0 * 2 + 1] + profile[g1 * 2 + 1]) * 0.5f;
    if (!std::isfinite(s) || !std::isfinite(o) || !(s >= 0.0f)) return false;
    CfaNoiseToNormalized(s, o, black, white, a, b);
    return true;
}

class DenoisePipeline {
   public:
    // Shaders are the default 16x16 builds of shaders/denoise_img_*.comp.
    DenoisePipeline(VkPhysicalDevice physicalDevice, VkDevice device, DenoiseShaders shaders);
    ~DenoisePipeline();
    DenoisePipeline(const DenoisePipeline&) = delete;
    DenoisePipeline& operator=(const DenoisePipeline&) = delete;

    // Denoises pre-WB linear srcView into dstView (RGBA16F GENERAL images,
    // caller-owned transitions; src and dst must be distinct images).
    // Writes timingPool queries 10->11 when non-null (matches the still
    // pipeline's denoiseMs slot). One submit by the caller covers everything.
    void record(VkCommandBuffer command, VkImageView srcView, VkImageView dstView, uint32_t width,
                uint32_t height, const DenoiseParams& params,
                VkQueryPool timingPool = VK_NULL_HANDLE);

    // Same as record(), but writes packed RGBA16F texels (w*h*8 bytes, row
    // major) into dstBuffer instead of an image. Needs backtransformBuffer.
    void recordToBuffer(VkCommandBuffer command, VkImageView srcView, VkBuffer dstBuffer,
                        VkDeviceSize dstSize, uint32_t width, uint32_t height,
                        const DenoiseParams& params, VkQueryPool timingPool = VK_NULL_HANDLE);

    // Optional hook run between tiles; the caller may submit and re-begin the
    // same command buffer (with a memory barrier) so one still is not a single
    // long GPU submission that starves the viewfinder. Empty = one submission.
    void setTileBoundary(std::function<void()> boundary) { tileBoundary_ = std::move(boundary); }

    // Debug: preconditioned first-tile image + extent (for offline diagnosis).
    VkImage debugFineImage() const noexcept;
    uint32_t debugFineExtent() const noexcept;

   private:
    struct Impl;
    Impl* impl_ = nullptr;
    std::function<void()> tileBoundary_;
};

}  // namespace raw_denoise
