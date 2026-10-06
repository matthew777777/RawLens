// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Translation of RAWR's pipeline/src/HdrPlusRecorder.cpp (recordReference,
// per-companion prepare/align/merge, recordFinalize, and the frequency
// recorder) plus the runHdrPlus/runHdrPlusFrequency drive loops from
// AndroidBurstCoordinator.cpp.
//
// Preserved exactly: descriptor bindings, dispatch group counts, barrier
// placement and semantics, pass order, the align-slot copy, and the
// total-mismatch clear. Host-only differences (no effect on output bits):
// run-scoped allocation instead of RAWR's aliasing arena, one descriptor
// set per dispatch from a run pool, a single command buffer/submission
// instead of RAWR's interleaved chunks, and one whole-run GPU timestamp
// pair instead of per-chunk queries.
//
// Intentional deviations from RAWR toward Burst Photo (hdr-plus-swift
// 69cb057, exposure-control-off uniform path; see PARITY.md): the tile
// border pass is skipped (upstream returns early for unknown black), the
// frame count divides instead of multiplying by a reciprocal, and the
// reference accumulates in frame order over a cleared accumulator.
#include <array>
#include <cmath>
#include <cstring>
#include <functional>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "hdrplus_host.h"
#include "hdrplus_internal.h"
#include "hdrplus_platform.h"

namespace rawlens {
namespace hdrplus {
namespace {

constexpr std::uint32_t divUp(std::uint32_t x, std::uint32_t y) { return (x + y - 1u) / y; }

// Push-constant structs mirror the GLSL blocks (copied from RAWR's
// HdrPlusRecorder.cpp anonymous namespace; ivec2 members are 8-byte aligned),
// except AccumulatePc/MismatchNormPc carry the frame count for an upstream-
// exact division instead of RAWR's reciprocal multiply.
struct PreparePc {
    float blackDelta[4];
    std::int32_t padX, padY, width, height, paddedWidth, paddedHeight;
};
struct HotPixelPc {
    std::int32_t count, alignPad, padX, padY, width, height;
};
struct SizePc {
    std::int32_t width, height;
};
struct BlurPc {
    std::int32_t width, height, srcX, srcY, dstX, dstY, kernelSize, stride, direction, quantizeHalf;
};
struct UpsamplePc {
    std::int32_t srcX, srcY, dstX, dstY;
    float scaleX, scaleY;
};
struct TileCostPc {
    std::int32_t levelWidth, levelHeight, tilesX, tilesY, downscale, tileSize, useSsd;
};
struct BestTilePc {
    std::int32_t tilesX, tilesY, downscale;
};
struct WarpPc {
    std::int32_t width, height, padX, padY, paddedWidth, paddedHeight, tilesX, tilesY, halfTileSize;
};
struct ColorDiffPc {
    std::int32_t cellsX, cellsY, aX, aY, bX, bY;
};
struct MeanPc {
    std::int32_t count;
    float pixels;
};
struct WeightPc {
    std::int32_t cellsX, cellsY;
    float robustness;
};
struct AccumulatePc {
    std::int32_t width, height, padX, padY, cellsX, cellsY;
    std::int32_t count;  // upstream divides by N (not multiply by 1/N)
    std::int32_t mode;
};
struct FinalizePc {
    float black[4];
    float white;
    std::int32_t alignPad, width, height;
};
struct TilesPc {
    std::int32_t tilesX, tilesY;
};
struct ToRgbaPc {
    std::int32_t width, height, cropX, cropY, offsetX, offsetY, paddedWidth, paddedHeight;
};
struct WarpRgbaPc {
    std::int32_t width, height, cropX, cropY, paddedWidth, paddedHeight, tilesX, tilesY, halfTileSize;
    std::int32_t alignPad;  // ivec2 offset is 8-byte aligned in the GLSL block
    std::int32_t offsetX, offsetY;
    std::int32_t continuous;
};
struct MismatchPc {
    std::int32_t tilesX, tilesY, rgbaWidth, rgbaHeight;
};
struct RegionPc {
    std::int32_t originX, originY, width, height;
};
struct MismatchNormPc {
    std::int32_t tilesX, tilesY;
    std::int32_t frames;  // upstream divides by N (not multiply by 1/N)
};
struct FreqMergePc {
    std::int32_t tilesX, tilesY;
    float robustnessNorm, readNoise, maxMotionNorm;
};
struct BackwardPc {
    std::int32_t tilesX, tilesY;
    float frames;
};
struct FreqAccumulatePc {
    std::int32_t width, height, offsetX, offsetY, mode;
};

// Guards against compiler padding surprises (sizes derived from the GLSL
// std430 layouts, cross-checked with RAWR's structs).
static_assert(sizeof(PreparePc) == 40, "PreparePc layout");
static_assert(sizeof(HotPixelPc) == 24, "HotPixelPc layout");
static_assert(sizeof(BlurPc) == 40, "BlurPc layout");
static_assert(sizeof(TileCostPc) == 28, "TileCostPc layout");
static_assert(sizeof(WarpPc) == 36, "WarpPc layout");
static_assert(sizeof(AccumulatePc) == 32, "AccumulatePc layout");
static_assert(sizeof(FinalizePc) == 32, "FinalizePc layout");
static_assert(sizeof(WarpRgbaPc) == 52, "WarpRgbaPc layout");
static_assert(sizeof(FreqMergePc) == 20, "FreqMergePc layout");

VkDeviceSize alignUp256(VkDeviceSize v) { return (v + 255u) / 256u * 256u; }

std::string levelName(bool reference, std::size_t level) {
    return std::string(reference ? "hdrp_ref_l" : "hdrp_comp_l") + std::to_string(level);
}

struct RawNormalization {
    float blackByPhase[4] = {0, 0, 0, 0};
    float whiteLevel = 1.0f;
};

}  // namespace

// Named scratch store with RAWR-arena-like lookup (image(name)/buffer(name)).
class Scratch {
   public:
    Scratch(RunArena& arena, std::string& err) : arena_(arena), err_(err) {}

    bool addImage(const std::string& name, VkFormat format, std::uint32_t w, std::uint32_t h) {
        GpuImage* img = arena_.image(format, w, h, err_);
        if (img == nullptr) return false;
        images_[name] = img;
        return true;
    }
    bool addBuffer(const std::string& name, VkDeviceSize bytes) {
        GpuBuffer* buf = arena_.buffer(bytes, err_);
        if (buf == nullptr) return false;
        buffers_[name] = buf;
        return true;
    }
    const GpuImage& image(const std::string& name) const { return *images_.at(name); }
    const GpuBuffer& buffer(const std::string& name) const { return *buffers_.at(name); }
    // Debug dumps must not fail merges whose layout lacks the resource
    // (spatial-only images in frequency runs): presence queries for those.
    bool hasImage(const std::string& name) const { return images_.count(name) != 0u; }
    bool hasBuffer(const std::string& name) const { return buffers_.count(name) != 0u; }

    // Same resource lists as RAWR's makeHdrPlusScratchLayout (lifetimes only
    // drive RAWR's aliasing, which this host skips: one allocation each).
    bool buildSpatial(const hp::Geometry& g) {
        const std::uint32_t cellsX = g.width / 2u, cellsY = g.height / 2u;
        if (!addImage("hdrp_ref_padded", VK_FORMAT_R32_SFLOAT, g.paddedWidth, g.paddedHeight) ||
            !addImage("hdrp_comp_padded", VK_FORMAT_R32_SFLOAT, g.paddedWidth, g.paddedHeight) ||
            !addImage("hdrp_aligned", VK_FORMAT_R32_SFLOAT, g.width, g.height) ||
            !addImage("hdrp_ref_blur", VK_FORMAT_R32_SFLOAT, g.width, g.height) ||
            !addImage("hdrp_comp_blur", VK_FORMAT_R32_SFLOAT, g.width, g.height) ||
            !addImage("hdrp_blur_tmp", VK_FORMAT_R32_SFLOAT, g.width, g.height) ||
            !addImage("hdrp_diff", VK_FORMAT_R32_SFLOAT, cellsX, cellsY) ||
            !addImage("hdrp_weight", VK_FORMAT_R32_SFLOAT, cellsX, cellsY) ||
            !addImage("hdrp_accum", VK_FORMAT_R32_SFLOAT, g.width, g.height) ||
            !addImage("hdrp_output", VK_FORMAT_R16G16B16A16_SFLOAT, g.width, g.height) ||
            !addImage("hdrp_cfa", VK_FORMAT_R32_SFLOAT, g.width, g.height) ||
            !addImage("hdrp_pyr_tmp_a", VK_FORMAT_R32_SFLOAT, g.levels.front().width,
                      g.levels.front().height) ||
            !addImage("hdrp_pyr_tmp_b", VK_FORMAT_R32_SFLOAT, g.levels.front().width,
                      g.levels.front().height))
            return false;
        std::uint64_t maxTiles = 1u;
        for (std::size_t i = 0; i < g.levels.size(); ++i) {
            if (!addImage(levelName(true, i), VK_FORMAT_R32_SFLOAT, g.levels[i].width,
                          g.levels[i].height) ||
                !addImage(levelName(false, i), VK_FORMAT_R32_SFLOAT, g.levels[i].width,
                          g.levels[i].height))
                return false;
            maxTiles = std::max<std::uint64_t>(
                maxTiles, std::uint64_t(g.levels[i].tilesX) * g.levels[i].tilesY);
        }
        return addBuffer("hdrp_align_a", maxTiles * 8u) && addBuffer("hdrp_align_b", maxTiles * 8u) &&
               addBuffer("hdrp_align_c", maxTiles * 8u) &&
               addBuffer("hdrp_tile_cost", maxTiles * 25u * 4u) &&
               addBuffer("hdrp_columns", std::uint64_t(cellsX) * 8u) && addBuffer("hdrp_noise", 16u);
    }

    // Same resource lists as RAWR's makeHdrPlusFrequencyScratchLayout.
    bool buildFrequency(const hp::FrequencyGeometry& f, std::uint32_t width, std::uint32_t height) {
        const auto& g = f.align;
        if (!addImage("hdrp_ref_padded", VK_FORMAT_R32_SFLOAT, g.paddedWidth, g.paddedHeight) ||
            !addImage("hdrp_comp_padded", VK_FORMAT_R32_SFLOAT, g.paddedWidth, g.paddedHeight) ||
            !addImage("hdrp_pyr_tmp_a", VK_FORMAT_R32_SFLOAT, g.levels.front().width,
                      g.levels.front().height) ||
            !addImage("hdrp_pyr_tmp_b", VK_FORMAT_R32_SFLOAT, g.levels.front().width,
                      g.levels.front().height))
            return false;
        std::uint64_t maxTiles = 1u;
        for (std::size_t i = 0; i < g.levels.size(); ++i) {
            if (!addImage(levelName(true, i), VK_FORMAT_R32_SFLOAT, g.levels[i].width,
                          g.levels[i].height) ||
                !addImage(levelName(false, i), VK_FORMAT_R32_SFLOAT, g.levels[i].width,
                          g.levels[i].height))
                return false;
            maxTiles = std::max<std::uint64_t>(
                maxTiles, std::uint64_t(g.levels[i].tilesX) * g.levels[i].tilesY);
        }
        if (!addImage("hdrq_ref_rgba", VK_FORMAT_R32G32B32A32_SFLOAT, f.rgbaWidth, f.rgbaHeight) ||
            !addImage("hdrq_aligned_rgba", VK_FORMAT_R32G32B32A32_SFLOAT, f.rgbaWidth, f.rgbaHeight) ||
            !addImage("hdrq_out_rgba", VK_FORMAT_R32G32B32A32_SFLOAT, f.rgbaWidth, f.rgbaHeight) ||
            !addImage("hdrq_ref_ft", VK_FORMAT_R32G32B32A32_SFLOAT, 2u * f.rgbaWidth, f.rgbaHeight) ||
            !addImage("hdrq_aligned_ft", VK_FORMAT_R32G32B32A32_SFLOAT, 2u * f.rgbaWidth,
                      f.rgbaHeight) ||
            !addImage("hdrq_final_ft", VK_FORMAT_R32G32B32A32_SFLOAT, 2u * f.rgbaWidth, f.rgbaHeight) ||
            !addImage("hdrq_rms", VK_FORMAT_R32G32B32A32_SFLOAT, f.tilesX, f.tilesY) ||
            !addImage("hdrq_mismatch", VK_FORMAT_R32_SFLOAT, f.tilesX, f.tilesY) ||
            !addImage("hdrq_total_mismatch", VK_FORMAT_R32_SFLOAT, f.tilesX, f.tilesY) ||
            !addImage("hdrp_accum", VK_FORMAT_R32_SFLOAT, width, height) ||
            !addImage("hdrp_output", VK_FORMAT_R16G16B16A16_SFLOAT, width, height) ||
            !addImage("hdrp_cfa", VK_FORMAT_R32_SFLOAT, width, height))
            return false;
        const auto& level0 = g.levels.front();
        return addBuffer("hdrp_align_a", maxTiles * 8u) && addBuffer("hdrp_align_b", maxTiles * 8u) &&
               addBuffer("hdrp_align_c", maxTiles * 8u) &&
               addBuffer("hdrp_tile_cost", maxTiles * 25u * 4u) && addBuffer("hdrq_mean", 16u) &&
               addBuffer("hdrq_shift_table", 49u * 64u * 8u) &&
               addBuffer("hdrq_align_store", hp::kMaxFrequencyFrames *
                                                 alignUp256(std::uint64_t(level0.tilesX) *
                                                            level0.tilesY * 8u));
    }

   private:
    RunArena& arena_;
    std::string& err_;
    std::unordered_map<std::string, GpuImage*> images_;
    std::unordered_map<std::string, GpuBuffer*> buffers_;
};

namespace {
ImageBinding ib(std::uint32_t b, const GpuImage& i) { return {b, i.view}; }
BufferBinding bb(std::uint32_t b, const GpuBuffer& buf) {
    return {b, buf.buffer, 0, buf.bytes};
}
}  // namespace

// 1:1 port of rawr::raw_gpu_pipeline::HdrPlusRecorder.
class Recorder {
   public:
    Recorder(HdrPlusContext* ctx, RunDescriptors& desc, VkCommandBuffer cmd, Scratch& scratch,
             hp::Config config, hp::Geometry geometry, std::string& err)
        : ctx_(ctx),
          desc_(desc),
          cmd_(cmd),
          scratch_(scratch),
          config_(config),
          geometry_(std::move(geometry)),
          err_(err) {}

    void setHotPixels(VkBuffer buffer, std::uint32_t count) noexcept {
        hotPixelBuffer_ = buffer;
        hotPixelCount_ = count;
    }
    void setPads(std::uint32_t left, std::uint32_t top) noexcept {
        geometry_.padLeft = left;
        geometry_.padTop = top;
    }
    std::uint32_t levelCount() const noexcept { return std::uint32_t(geometry_.levels.size()); }

    bool recordReference(VkImageView rawU16, const RawNormalization& frame, std::uint32_t frameCount);
    bool recordReferenceAccumulate(std::uint32_t frameCount);
    bool recordReferencePrepare(VkImageView rawU16, const RawNormalization& frame);
    bool recordCompanionPadded(VkImageView rawU16, const RawNormalization& frame);
    bool recordCompanionPrepare(VkImageView rawU16, const RawNormalization& frame);
    bool recordCompanionAlignLevel(std::uint32_t n);
    bool recordCompanionMerge(std::uint32_t frameCount);
    bool recordFinalize();

   private:
    bool run(Shader shader, std::initializer_list<ImageBinding> images,
             std::initializer_list<BufferBinding> buffers, const void* push, std::uint32_t pushBytes,
             std::uint32_t gx, std::uint32_t gy) {
        return record(ctx_, desc_, cmd_, shader, images.begin(),
                      static_cast<std::uint32_t>(images.size()), buffers.begin(),
                      static_cast<std::uint32_t>(buffers.size()), push, pushBytes, gx, gy, err_);
    }
    bool recordPrepare(bool reference, VkImageView rawU16, const RawNormalization& frame);
    bool recordPyramid(bool reference);
    bool recordBlur(const GpuImage& src, std::array<std::int32_t, 2> srcOffset, const GpuImage& tmp,
                    const GpuImage& dst, std::uint32_t w, std::uint32_t h, std::int32_t kernelSize,
                    std::int32_t stride, bool quantizeHalf);

    HdrPlusContext* ctx_;
    RunDescriptors& desc_;
    VkCommandBuffer cmd_;
    Scratch& scratch_;
    hp::Config config_{};
    hp::Geometry geometry_{};
    RawNormalization reference_{};
    VkBuffer hotPixelBuffer_ = VK_NULL_HANDLE;
    std::uint32_t hotPixelCount_ = 0;
    std::string& err_;
};

bool Recorder::recordPrepare(bool reference, VkImageView rawU16, const RawNormalization& frame) {
    const auto& dst = scratch_.image(reference ? "hdrp_ref_padded" : "hdrp_comp_padded");
    PreparePc pc{};
    for (int i = 0; i < 4; ++i) pc.blackDelta[i] = reference_.blackByPhase[i] - frame.blackByPhase[i];
    pc.padX = std::int32_t(geometry_.padLeft);
    pc.padY = std::int32_t(geometry_.padTop);
    pc.width = std::int32_t(geometry_.width);
    pc.height = std::int32_t(geometry_.height);
    pc.paddedWidth = std::int32_t(geometry_.paddedWidth);
    pc.paddedHeight = std::int32_t(geometry_.paddedHeight);
    if (!run(Shader::HdrpPrepare, {{0u, rawU16}, ib(1, dst)}, {}, &pc, sizeof(pc),
             divUp(geometry_.paddedWidth, 16), divUp(geometry_.paddedHeight, 16)))
        return false;
    compute_write_barrier(cmd_);
    if (hotPixelBuffer_ && hotPixelCount_) {
        HotPixelPc hpc{std::int32_t(hotPixelCount_), 0, pc.padX, pc.padY, pc.width, pc.height};
        if (!run(Shader::HdrpHotPixel, {ib(0, dst)}, {{1u, hotPixelBuffer_}}, &hpc, sizeof(hpc),
                 divUp(hotPixelCount_, 64), 1))
            return false;
        compute_write_barrier(cmd_);
    }
    return true;
}

bool Recorder::recordBlur(const GpuImage& src, std::array<std::int32_t, 2> srcOffset,
                          const GpuImage& tmp, const GpuImage& dst, std::uint32_t w, std::uint32_t h,
                          std::int32_t kernelSize, std::int32_t stride, bool quantizeHalf) {
    BlurPc x{std::int32_t(w), std::int32_t(h), srcOffset[0], srcOffset[1], 0, 0, kernelSize, stride,
             0,           quantizeHalf ? 1 : 0};
    if (!run(Shader::HdrpBlur, {ib(0, src), ib(1, tmp)}, {}, &x, sizeof(x), divUp(w, 16),
             divUp(h, 16)))
        return false;
    compute_write_barrier(cmd_);
    BlurPc y = x;
    y.srcX = y.srcY = 0;
    y.direction = 1;
    if (!run(Shader::HdrpBlur, {ib(0, tmp), ib(1, dst)}, {}, &y, sizeof(y), divUp(w, 16),
             divUp(h, 16)))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

bool Recorder::recordPyramid(bool reference) {
    const auto& padded = scratch_.image(reference ? "hdrp_ref_padded" : "hdrp_comp_padded");
    const auto& tmpA = scratch_.image("hdrp_pyr_tmp_a");
    const auto& tmpB = scratch_.image("hdrp_pyr_tmp_b");
    for (std::size_t i = 0; i < geometry_.levels.size(); ++i) {
        const auto& level = geometry_.levels[i];
        const auto& dst = scratch_.image(levelName(reference, i));
        const GpuImage* src = &padded;
        if (i > 0) {
            // Upstream build_pyramid: blur(previous level, pattern 1, kernel 2), then pool.
            const auto& prev = geometry_.levels[i - 1];
            if (!recordBlur(scratch_.image(levelName(reference, i - 1)), {0, 0}, tmpA, tmpB,
                            prev.width, prev.height, 2, 1, true))
                return false;
            src = &tmpB;
        }
        SizePc pc{std::int32_t(level.width), std::int32_t(level.height)};
        if (!run(Shader::HdrpAvgPool, {ib(0, *src), ib(1, dst)}, {}, &pc, sizeof(pc),
                 divUp(level.width, 16), divUp(level.height, 16)))
            return false;
        compute_write_barrier(cmd_);
    }
    return true;
}

bool Recorder::recordReference(VkImageView rawU16, const RawNormalization& frame,
                               std::uint32_t frameCount) {
    (void)frameCount;  // the reference's own accumulation happens in frame order (below)
    reference_ = frame;
    if (!recordPrepare(true, rawU16, frame)) return false;
    if (!recordPyramid(true)) return false;
    const auto& padded = scratch_.image("hdrp_ref_padded");
    const auto& blur = scratch_.image("hdrp_ref_blur");
    const std::array<std::int32_t, 2> pad{std::int32_t(geometry_.padLeft),
                                         std::int32_t(geometry_.padTop)};
    if (!recordBlur(padded, pad, scratch_.image("hdrp_blur_tmp"), blur, geometry_.width,
                    geometry_.height, 16, 2, false))
        return false;
    // Noise level: mean per-cell |ref - blur(ref)| (upstream estimate_color_noise).
    const std::uint32_t cellsX = geometry_.width / 2u, cellsY = geometry_.height / 2u;
    const auto& diff = scratch_.image("hdrp_diff");
    ColorDiffPc cd{std::int32_t(cellsX), std::int32_t(cellsY), pad[0], pad[1], 0, 0};
    if (!run(Shader::HdrpColorDiff, {ib(0, padded), ib(1, blur), ib(2, diff)}, {}, &cd, sizeof(cd),
             divUp(cellsX, 16), divUp(cellsY, 16)))
        return false;
    compute_write_barrier(cmd_);
    SizePc cs{std::int32_t(cellsX), std::int32_t(cellsY)};
    if (!run(Shader::HdrpColumnSum, {ib(0, diff)}, {bb(1, scratch_.buffer("hdrp_columns"))}, &cs,
             sizeof(cs), divUp(cellsX, 64), 1))
        return false;
    compute_write_barrier(cmd_);
    MeanPc mp{std::int32_t(cellsX), float(cellsX) * float(cellsY)};
    if (!run(Shader::HdrpMean, {},
             {bb(0, scratch_.buffer("hdrp_columns")), bb(1, scratch_.buffer("hdrp_noise"))}, &mp,
             sizeof(mp), 1, 1))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

// The reference's own contribution (ref / N) is accumulated in frame order
// with the companions (upstream adds it when the frame loop reaches ref_idx;
// accumulating first would associate the running sum differently).
bool Recorder::recordReferenceAccumulate(std::uint32_t frameCount) {
    const auto& padded = scratch_.image("hdrp_ref_padded");
    const std::uint32_t cellsX = geometry_.width / 2u, cellsY = geometry_.height / 2u;
    AccumulatePc ap{std::int32_t(geometry_.width),  std::int32_t(geometry_.height),
                    std::int32_t(geometry_.padLeft), std::int32_t(geometry_.padTop),
                    std::int32_t(cellsX), std::int32_t(cellsY), std::int32_t(frameCount), 0};
    if (!run(Shader::HdrpAccumulate,
             {ib(0, padded), ib(1, scratch_.image("hdrp_aligned")), ib(2, scratch_.image("hdrp_weight")),
              ib(3, scratch_.image("hdrp_accum"))},
             {}, &ap, sizeof(ap), divUp(geometry_.width, 16), divUp(geometry_.height, 16)))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

// 1:1 port of rawr::raw_gpu_pipeline::HdrPlusFrequencyRecorder.
class FrequencyRecorder {
   public:
    FrequencyRecorder(HdrPlusContext* ctx, RunDescriptors& desc, VkCommandBuffer cmd, Scratch& scratch,
                      hp::Config config, hp::FrequencyGeometry geometry, std::string& err)
        : ctx_(ctx),
          desc_(desc),
          cmd_(cmd),
          scratch_(scratch),
          config_(config),
          geometry_(std::move(geometry)),
          align_(ctx, desc, cmd, scratch, config, geometry_.align, err),
          err_(err) {}

    Recorder& alignment() noexcept { return align_; }
    void beginPass(std::uint32_t pass) noexcept {
        pass_ = pass;
        // Align-once keeps every frame in the pass-0 padding; passOffset() maps.
        const std::uint32_t prepared = config_.frequencyAlignOnce ? 0u : pass;
        align_.setPads(geometry_.padLeft(prepared), geometry_.padTop(prepared));
    }
    bool alignsThisPass() const noexcept { return !config_.frequencyAlignOnce || pass_ == 0u; }
    bool recordReference(VkImageView rawU16, const RawNormalization& frame);
    bool recordCompanionAlign(VkImageView rawU16, const RawNormalization& frame, std::uint32_t slot);
    bool recordCompanionMerge(std::uint32_t frameCount, std::uint32_t slot);
    bool recordPassFinish(std::uint32_t frameCount);
    bool recordFinalize() { return align_.recordFinalize(); }

   private:
    bool run(Shader shader, std::initializer_list<ImageBinding> images,
             std::initializer_list<BufferBinding> buffers, const void* push, std::uint32_t pushBytes,
             std::uint32_t gx, std::uint32_t gy) {
        return record(ctx_, desc_, cmd_, shader, images.begin(),
                      static_cast<std::uint32_t>(images.size()), buffers.begin(),
                      static_cast<std::uint32_t>(buffers.size()), push, pushBytes, gx, gy, err_);
    }
    std::array<std::int32_t, 2> passOffset() const noexcept {
        if (!config_.frequencyAlignOnce) return {0, 0};
        return {std::int32_t(geometry_.padLeft(0)) - std::int32_t(geometry_.padLeft(pass_)),
                std::int32_t(geometry_.padTop(0)) - std::int32_t(geometry_.padTop(pass_))};
    }
    VkDeviceSize alignSlotBytes() const noexcept {
        const auto& level0 = geometry_.align.levels.front();
        return alignUp256(VkDeviceSize(level0.tilesX) * level0.tilesY * 8u);
    }

    HdrPlusContext* ctx_;
    RunDescriptors& desc_;
    VkCommandBuffer cmd_;
    Scratch& scratch_;
    hp::Config config_{};
    hp::FrequencyGeometry geometry_{};
    Recorder align_;
    std::uint32_t pass_ = 0;
    std::string& err_;
};

bool FrequencyRecorder::recordCompanionAlign(VkImageView rawU16, const RawNormalization& frame,
                                             std::uint32_t slot) {
    if (!alignsThisPass()) return align_.recordCompanionPadded(rawU16, frame);
    if (!align_.recordCompanionPrepare(rawU16, frame)) return false;
    for (std::uint32_t level = align_.levelCount(); level-- > 0;) {
        if (!align_.recordCompanionAlignLevel(level)) return false;
    }
    if (!config_.frequencyAlignOnce) return true;
    if (slot >= hp::kMaxFrequencyFrames) {
        err_ = "hdrplus frequency: too many frames";
        return false;
    }
    const auto& level0 = geometry_.align.levels.front();
    VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_SHADER_WRITE_BIT,
                               VK_ACCESS_TRANSFER_READ_BIT};
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                         1, &toTransfer, 0, nullptr, 0, nullptr);
    const VkBufferCopy copy{0, slot * alignSlotBytes(), VkDeviceSize(level0.tilesX) * level0.tilesY * 8u};
    vkCmdCopyBuffer(cmd_, scratch_.buffer("hdrp_align_a").buffer,
                    scratch_.buffer("hdrq_align_store").buffer, 1, &copy);
    VkMemoryBarrier toCompute{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT};
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                         1, &toCompute, 0, nullptr, 0, nullptr);
    return true;
}

bool FrequencyRecorder::recordReference(VkImageView rawU16, const RawNormalization& frame) {
    if (pass_ == 0u) {
        if (!run(Shader::HdrqShiftTable, {}, {bb(0, scratch_.buffer("hdrq_shift_table"))}, nullptr,
                 0u, divUp(49u * 64u, 64u), 1))
            return false;
    }
    if (alignsThisPass() && !align_.recordReferencePrepare(rawU16, frame)) return false;
    const auto& rgba = scratch_.image("hdrq_ref_rgba");
    const std::uint32_t tx = geometry_.tilesX, ty = geometry_.tilesY;
    const auto offset = passOffset();
    ToRgbaPc tr{std::int32_t(geometry_.rgbaWidth),         std::int32_t(geometry_.rgbaHeight),
                std::int32_t(geometry_.cropX),             std::int32_t(geometry_.cropY),
                offset[0],                                 offset[1],
                std::int32_t(geometry_.align.paddedWidth), std::int32_t(geometry_.align.paddedHeight)};
    if (!run(Shader::HdrqToRgba, {ib(0, scratch_.image("hdrp_ref_padded")), ib(1, rgba)}, {}, &tr,
             sizeof(tr), divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16)))
        return false;
    compute_write_barrier(cmd_);
    TilesPc tp{std::int32_t(tx), std::int32_t(ty)};
    if (!run(Shader::HdrqRms, {ib(0, rgba), ib(1, scratch_.image("hdrq_rms"))}, {}, &tp, sizeof(tp),
             divUp(tx, 16), divUp(ty, 16)))
        return false;
    // The reference spectrum seeds both the reference and the running merge.
    // (RAWR records these two back-to-back with no barrier between: same
    // input, disjoint outputs — no hazard. The barrier below orders them
    // against the later merge reads; the clear's barrier also separates.)
    if (!run(Shader::HdrqForwardDft, {ib(0, rgba), ib(1, scratch_.image("hdrq_ref_ft"))}, {}, &tp,
             sizeof(tp), tx, ty))
        return false;
    if (!run(Shader::HdrqForwardDft, {ib(0, rgba), ib(1, scratch_.image("hdrq_final_ft"))}, {}, &tp,
             sizeof(tp), tx, ty))
        return false;
    // Per-pass total mismatch starts at zero.
    VkMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr,
                               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                               VK_ACCESS_TRANSFER_WRITE_BIT};
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 1, &toTransfer, 0, nullptr, 0, nullptr);
    const VkClearColorValue zero{};
    const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(cmd_, scratch_.image("hdrq_total_mismatch").image, VK_IMAGE_LAYOUT_GENERAL,
                         &zero, 1, &range);
    VkMemoryBarrier toCompute{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &toCompute, 0, nullptr, 0, nullptr);
    return true;
}

bool FrequencyRecorder::recordCompanionMerge(std::uint32_t frameCount, std::uint32_t slot) {
    const auto& a = geometry_.align;
    const auto& level0 = a.levels.front();
    const std::uint32_t tx = geometry_.tilesX, ty = geometry_.tilesY;
    const auto& refRgba = scratch_.image("hdrq_ref_rgba");
    const auto& alignedRgba = scratch_.image("hdrq_aligned_rgba");
    const auto& mismatch = scratch_.image("hdrq_mismatch");
    const auto offset = passOffset();
    WarpRgbaPc wp{std::int32_t(geometry_.rgbaWidth), std::int32_t(geometry_.rgbaHeight),
                  std::int32_t(geometry_.cropX), std::int32_t(geometry_.cropY),
                  std::int32_t(a.paddedWidth), std::int32_t(a.paddedHeight),
                  std::int32_t(level0.tilesX), std::int32_t(level0.tilesY),
                  std::int32_t(level0.tileSize), 0, offset[0], offset[1],
                  config_.frequencyAlignOnce ? 1 : 0};
    // Align-once: this companion's pass-0 shifts from its store slot.
    const GpuBuffer& alignStore = scratch_.buffer("hdrq_align_store");
    const GpuBuffer& alignA = scratch_.buffer("hdrp_align_a");
    const BufferBinding shifts =
        config_.frequencyAlignOnce
            ? BufferBinding{2u, alignStore.buffer, slot * alignSlotBytes(),
                            VkDeviceSize(level0.tilesX) * level0.tilesY * 8u}
            : bb(2, alignA);
    if (!run(Shader::HdrqWarpRgba, {ib(0, scratch_.image("hdrp_comp_padded")), ib(1, alignedRgba)},
             {shifts}, &wp, sizeof(wp), divUp(geometry_.rgbaWidth, 16), divUp(geometry_.rgbaHeight, 16)))
        return false;
    compute_write_barrier(cmd_);
    MismatchPc mp{std::int32_t(tx), std::int32_t(ty), std::int32_t(geometry_.rgbaWidth),
                  std::int32_t(geometry_.rgbaHeight)};
    if (!run(Shader::HdrqMismatch,
             {ib(0, refRgba), ib(1, alignedRgba), ib(2, scratch_.image("hdrq_rms")), ib(3, mismatch)},
             {}, &mp, sizeof(mp), tx, ty))
        return false;
    // RAWR only barriers here when a profiling mark is set; the mismatch
    // image is consumed by HdrqRegionMean/HdrqMerge after the forward DFT
    // below, whose own barrier orders them. Keep RAWR's exact barrier
    // placement: no barrier here.
    TilesPc tp{std::int32_t(tx), std::int32_t(ty)};
    if (!run(Shader::HdrqForwardDft, {ib(0, alignedRgba), ib(1, scratch_.image("hdrq_aligned_ft"))}, {},
             &tp, sizeof(tp), tx, ty))
        return false;
    compute_write_barrier(cmd_);
    // Mean mismatch excluding the tile row/column introduced by this pass's shift.
    const std::uint32_t left = hp::FrequencyGeometry::shiftLeft(pass_) ? 1u : 0u;
    const std::uint32_t right = hp::FrequencyGeometry::shiftLeft(pass_) ? 0u : 1u;
    const std::uint32_t top = hp::FrequencyGeometry::shiftTop(pass_) ? 1u : 0u;
    const std::uint32_t bottom = hp::FrequencyGeometry::shiftTop(pass_) ? 0u : 1u;
    RegionPc rp{std::int32_t(left), std::int32_t(top), std::int32_t(tx - left - right),
                std::int32_t(ty - top - bottom)};
    if (!run(Shader::HdrqRegionMean, {ib(0, mismatch)}, {bb(1, scratch_.buffer("hdrq_mean"))}, &rp,
             sizeof(rp), 1, 1))
        return false;
    compute_write_barrier(cmd_);
    MismatchNormPc np{std::int32_t(tx), std::int32_t(ty), std::int32_t(frameCount)};
    if (!run(Shader::HdrqMismatchNorm,
             {ib(0, mismatch), ib(1, scratch_.image("hdrq_total_mismatch"))},
             {bb(2, scratch_.buffer("hdrq_mean"))}, &np, sizeof(np), divUp(tx, 16), divUp(ty, 16)))
        return false;
    compute_write_barrier(cmd_);
    const auto norms = hp::frequencyNorms(config_.strength);
    FreqMergePc fm{std::int32_t(tx), std::int32_t(ty), norms.robustnessNorm, norms.readNoise,
                   norms.maxMotionNorm};
    if (!run(Shader::HdrqMerge,
             {ib(0, scratch_.image("hdrq_ref_ft")), ib(1, scratch_.image("hdrq_aligned_ft")),
              ib(2, scratch_.image("hdrq_final_ft")), ib(3, scratch_.image("hdrq_rms")), ib(4, mismatch)},
             {bb(5, scratch_.buffer("hdrq_shift_table"))}, &fm, sizeof(fm), tx, ty))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

bool FrequencyRecorder::recordPassFinish(std::uint32_t frameCount) {
    const std::uint32_t tx = geometry_.tilesX, ty = geometry_.tilesY;
    const auto& finalFt = scratch_.image("hdrq_final_ft");
    const auto& out = scratch_.image("hdrq_out_rgba");
    TilesPc tp{std::int32_t(tx), std::int32_t(ty)};
    if (!run(Shader::HdrqDeconvolute, {ib(0, finalFt), ib(1, scratch_.image("hdrq_total_mismatch"))}, {},
             &tp, sizeof(tp), tx, ty))
        return false;
    compute_write_barrier(cmd_);
    BackwardPc bp{std::int32_t(tx), std::int32_t(ty), float(frameCount)};
    if (!run(Shader::HdrqBackwardDft, {ib(0, finalFt), ib(1, out)}, {}, &bp, sizeof(bp), tx, ty))
        return false;
    compute_write_barrier(cmd_);
    // No tile-border pass: upstream's reduce_artifacts_tile_border returns
    // early when the black level is unknown (-1), which is exactly the
    // exposure-control-off path this port matches — running it with a
    // synthetic floor would blend every tile border away from upstream.
    // (HdrqBorder stays vendored + loaded for provenance, undispatched.)
    const auto& accum = scratch_.image("hdrp_accum");
    FreqAccumulatePc ap{std::int32_t(accum.width), std::int32_t(accum.height),
                        std::int32_t(geometry_.padLeft(pass_) - geometry_.cropX),
                        std::int32_t(geometry_.padTop(pass_) - geometry_.cropY), pass_ == 0u ? 0 : 1};
    if (!run(Shader::HdrqAccumulate, {ib(0, out), ib(1, accum)}, {}, &ap, sizeof(ap),
             divUp(accum.width, 16), divUp(accum.height, 16)))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

bool Recorder::recordCompanionPadded(VkImageView rawU16, const RawNormalization& frame) {
    return recordPrepare(false, rawU16, frame);
}

bool Recorder::recordReferencePrepare(VkImageView rawU16, const RawNormalization& frame) {
    reference_ = frame;
    return recordPrepare(true, rawU16, frame) && recordPyramid(true);
}

bool Recorder::recordCompanionPrepare(VkImageView rawU16, const RawNormalization& frame) {
    return recordPrepare(false, rawU16, frame) && recordPyramid(false);
}

bool Recorder::recordCompanionAlignLevel(std::uint32_t n) {
    if (n >= geometry_.levels.size()) {
        err_ = "hdrplus recorder: invalid alignment level";
        return false;
    }
    const auto& cur = scratch_.buffer("hdrp_align_a");
    const auto& prev = scratch_.buffer("hdrp_align_b");
    const auto& corrected = scratch_.buffer("hdrp_align_c");
    const auto& cost = scratch_.buffer("hdrp_tile_cost");
    const auto& level = geometry_.levels[n];
    const bool coarsest = n + 1u == geometry_.levels.size();
    // Alignment from the next-coarser level, in its (2x smaller) pixel units.
    const std::int32_t srcX = coarsest ? 1 : std::int32_t(geometry_.levels[n + 1u].tilesX);
    const std::int32_t srcY = coarsest ? 1 : std::int32_t(geometry_.levels[n + 1u].tilesY);
    const std::int32_t downscale = coarsest ? 0 : 2;
    UpsamplePc up{srcX, srcY, std::int32_t(level.tilesX), std::int32_t(level.tilesY),
                  float(double(level.tilesX) / double(srcX)), float(double(level.tilesY) / double(srcY))};
    if (!run(Shader::HdrpUpsampleAlign, {}, {bb(0, cur), bb(1, prev)}, &up, sizeof(up),
             divUp(level.tilesX, 16), divUp(level.tilesY, 16)))
        return false;
    compute_write_barrier(cmd_);
    const auto& refLevel = scratch_.image(levelName(true, n));
    const auto& compLevel = scratch_.image(levelName(false, n));
    TileCostPc tc{std::int32_t(level.width), std::int32_t(level.height), std::int32_t(level.tilesX),
                  std::int32_t(level.tilesY), downscale, std::int32_t(level.tileSize), n != 0u ? 1 : 0};
    if (!run(Shader::HdrpCorrectUpsampling, {ib(0, refLevel), ib(1, compLevel)},
             {bb(2, prev), bb(3, corrected)}, &tc, sizeof(tc), level.tilesX, level.tilesY))
        return false;
    compute_write_barrier(cmd_);
    if (!run(Shader::HdrpTileDiff, {ib(0, refLevel), ib(1, compLevel)}, {bb(2, corrected), bb(3, cost)},
             &tc, sizeof(tc), level.tilesX, level.tilesY))
        return false;
    compute_write_barrier(cmd_);
    BestTilePc bt{std::int32_t(level.tilesX), std::int32_t(level.tilesY), downscale};
    if (!run(Shader::HdrpBestTile, {}, {bb(0, cost), bb(1, corrected), bb(2, cur)}, &bt, sizeof(bt),
             divUp(level.tilesX, 16), divUp(level.tilesY, 16)))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

bool Recorder::recordCompanionMerge(std::uint32_t frameCount) {
    const auto& level0 = geometry_.levels.front();
    const auto& aligned = scratch_.image("hdrp_aligned");
    WarpPc wp{std::int32_t(geometry_.width), std::int32_t(geometry_.height), std::int32_t(geometry_.padLeft),
              std::int32_t(geometry_.padTop), std::int32_t(geometry_.paddedWidth),
              std::int32_t(geometry_.paddedHeight), std::int32_t(level0.tilesX), std::int32_t(level0.tilesY),
              std::int32_t(level0.tileSize)};
    if (!run(Shader::HdrpWarp,
             {ib(0, scratch_.image("hdrp_comp_padded")), ib(1, aligned)},
             {bb(2, scratch_.buffer("hdrp_align_a"))}, &wp, sizeof(wp), divUp(geometry_.width, 16),
             divUp(geometry_.height, 16)))
        return false;
    compute_write_barrier(cmd_);
    const auto& compBlur = scratch_.image("hdrp_comp_blur");
    if (!recordBlur(aligned, {0, 0}, scratch_.image("hdrp_blur_tmp"), compBlur, geometry_.width,
                    geometry_.height, 16, 2, false))
        return false;
    const std::uint32_t cellsX = geometry_.width / 2u, cellsY = geometry_.height / 2u;
    const auto& diff = scratch_.image("hdrp_diff");
    const auto& weight = scratch_.image("hdrp_weight");
    ColorDiffPc cd{std::int32_t(cellsX), std::int32_t(cellsY), 0, 0, 0, 0};
    if (!run(Shader::HdrpColorDiff, {ib(0, scratch_.image("hdrp_ref_blur")), ib(1, compBlur), ib(2, diff)},
             {}, &cd, sizeof(cd), divUp(cellsX, 16), divUp(cellsY, 16)))
        return false;
    compute_write_barrier(cmd_);
    WeightPc wt{std::int32_t(cellsX), std::int32_t(cellsY), hp::robustness(config_.strength)};
    if (!run(Shader::HdrpMergeWeight, {ib(0, diff), ib(1, weight)},
             {bb(2, scratch_.buffer("hdrp_noise"))}, &wt, sizeof(wt), divUp(cellsX, 16),
             divUp(cellsY, 16)))
        return false;
    compute_write_barrier(cmd_);
    AccumulatePc ap{std::int32_t(geometry_.width), std::int32_t(geometry_.height),
                    std::int32_t(geometry_.padLeft), std::int32_t(geometry_.padTop),
                    std::int32_t(cellsX), std::int32_t(cellsY), std::int32_t(frameCount), 1};
    if (!run(Shader::HdrpAccumulate,
             {ib(0, scratch_.image("hdrp_ref_padded")), ib(1, aligned), ib(2, weight),
              ib(3, scratch_.image("hdrp_accum"))},
             {}, &ap, sizeof(ap), divUp(geometry_.width, 16), divUp(geometry_.height, 16)))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

bool Recorder::recordFinalize() {
    FinalizePc fp{};
    for (int i = 0; i < 4; ++i) fp.black[i] = reference_.blackByPhase[i];
    fp.white = reference_.whiteLevel;
    fp.width = std::int32_t(geometry_.width);
    fp.height = std::int32_t(geometry_.height);
    if (!run(Shader::HdrpFinalize,
             {ib(0, scratch_.image("hdrp_accum")), ib(1, scratch_.image("hdrp_output")),
              ib(2, scratch_.image("hdrp_cfa"))},
             {}, &fp, sizeof(fp), divUp(geometry_.width, 16), divUp(geometry_.height, 16)))
        return false;
    compute_write_barrier(cmd_);
    return true;
}

// Drive loops mirroring AndroidBurstCoordinator::runHdrPlus /
// runHdrPlusFrequency (same pass/companion/level order; RAWR's chunk
// submissions are collapsed into one command buffer — submission
// boundaries only schedule work, they cannot change output bits).
bool run_spatial(HdrPlusContext* ctx, RunDescriptors& desc, VkCommandBuffer cmd, Scratch& scratch,
                 const hp::Config& config, const hp::Geometry& geometry,
                 const std::vector<VkImageView>& views, const std::vector<RawNormalization>& norms,
                 std::uint32_t ref, VkBuffer hotPixels, std::uint32_t hotPixelPairs, std::string& err) {
    Recorder recorder(ctx, desc, cmd, scratch, config, geometry, err);
    if (hotPixels != VK_NULL_HANDLE && hotPixelPairs > 0) recorder.setHotPixels(hotPixels, hotPixelPairs);
    const auto frameCount = std::uint32_t(views.size());
    if (!recorder.recordReference(views[ref], norms[ref], frameCount)) return false;
    // Upstream zero-fills the final texture and adds every frame — reference
    // included — in frame order; the accumulator starts cleared here so the
    // reference lands at its own loop position with the same association.
    VkMemoryBarrier accumClearBar{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr,
                                 VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                                 VK_ACCESS_TRANSFER_WRITE_BIT};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 1, &accumClearBar, 0, nullptr, 0, nullptr);
    const VkClearColorValue accumZero{};
    const VkImageSubresourceRange accumRange{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdClearColorImage(cmd, scratch.image("hdrp_accum").image, VK_IMAGE_LAYOUT_GENERAL, &accumZero,
                         1, &accumRange);
    VkMemoryBarrier accumUseBar{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
                               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 1, &accumUseBar, 0, nullptr, 0, nullptr);
    for (std::uint32_t i = 0; i < frameCount; ++i) {
        if (i == ref) {
            if (!recorder.recordReferenceAccumulate(frameCount)) return false;
            continue;
        }
        if (!recorder.recordCompanionPrepare(views[i], norms[i])) return false;
        for (std::uint32_t level = recorder.levelCount(); level-- > 0;) {
            if (!recorder.recordCompanionAlignLevel(level)) return false;
        }
        if (!recorder.recordCompanionMerge(frameCount)) return false;
    }
    return recorder.recordFinalize();
}

bool run_frequency(HdrPlusContext* ctx, RunDescriptors& desc, VkCommandBuffer cmd, Scratch& scratch,
                   const hp::Config& config, const hp::FrequencyGeometry& geometry,
                   const std::vector<VkImageView>& views, const std::vector<RawNormalization>& norms,
                   std::uint32_t ref, VkBuffer hotPixels, std::uint32_t hotPixelPairs, std::string& err) {
    FrequencyRecorder recorder(ctx, desc, cmd, scratch, config, geometry, err);
    if (hotPixels != VK_NULL_HANDLE && hotPixelPairs > 0)
        recorder.alignment().setHotPixels(hotPixels, hotPixelPairs);
    const auto frameCount = std::uint32_t(views.size());
    for (std::uint32_t pass = 0; pass < 4u; ++pass) {
        recorder.beginPass(pass);
        if (!recorder.recordReference(views[ref], norms[ref])) return false;
        for (std::uint32_t i = 0; i < frameCount; ++i) {
            if (i == ref) continue;
            // RAWR passes the frame index as the align-store slot.
            if (!recorder.recordCompanionAlign(views[i], norms[i], i)) return false;
            if (!recorder.recordCompanionMerge(frameCount, i)) return false;
        }
        if (!recorder.recordPassFinish(frameCount)) return false;
    }
    return recorder.recordFinalize();
}

}  // namespace hdrplus
}  // namespace rawlens

namespace {
void set_err(char* errmsg, size_t n, const std::string& s) {
    if (errmsg != nullptr && n > 0) {
        std::strncpy(errmsg, s.c_str(), n - 1);
        errmsg[n - 1] = '\0';
    }
}
}  // namespace

extern "C" {

void hdrplus_default_params(HdrPlusParams* p) {
    if (p == nullptr) return;
    p->strength = 13.0f;
    p->tile_size = 32;
    p->search_distance = 64;
    p->high_quality = 1;  // matches HdrPlusSettings default (HQ frequency)
    p->frequency_align_once = 1;
}

int hdrplus_merge(HdrPlusContext* ctx, const uint16_t* const* frames, const float* black_by_phase,
                  const float* white, int frame_count, int width, int height, int ref_index,
                  const int32_t* hot_pixels, int hot_pixel_count, const HdrPlusParams* params, float* out,
                  char* errmsg, size_t errmsg_len) {
    using namespace rawlens::hdrplus;
    std::string err;
    auto fail = [&](const std::string& m) -> int {
        set_err(errmsg, errmsg_len, m);
        return -1;
    };
    if (ctx == nullptr || ctx->device == VK_NULL_HANDLE) return fail("hdrplus: null context");
    if (ctx->loaded_shaders != kShaderCount) return fail("hdrplus: shaders not loaded");
    if (frames == nullptr || black_by_phase == nullptr || white == nullptr || out == nullptr ||
        params == nullptr)
        return fail("hdrplus: null merge argument");
    if (frame_count < 2 || frame_count > 64) return fail("hdrplus: need 2..64 frames");
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1))
        return fail("hdrplus: dimensions must be nonzero/even");
    if (ref_index < 0 || ref_index >= frame_count) return fail("hdrplus: ref_index out of range");
    for (int i = 0; i < frame_count; ++i) {
        if (frames[i] == nullptr) return fail("hdrplus: null frame pointer");
    }

    hp::Config config;
    config.strength = params->strength;
    config.tileSize = params->tile_size;
    config.searchDistance = params->search_distance;
    config.frequencyAlignOnce = params->frequency_align_once != 0;
    if (!hp::valid(config)) return fail("hdrplus: invalid config (strength 1..22, tile 16/32, search 32/64/128)");

    const std::uint32_t W = static_cast<std::uint32_t>(width), H = static_cast<std::uint32_t>(height);
    const std::uint32_t N = static_cast<std::uint32_t>(frame_count);
    const bool frequency = params->high_quality != 0;

    hp::Geometry spatial;
    hp::FrequencyGeometry freq;
    try {
        if (frequency) {
            freq = hp::makeFrequencyGeometry(W, H, config);
        } else {
            spatial = hp::makeGeometry(W, H, config);
        }
    } catch (const std::exception& e) {
        return fail(std::string("hdrplus geometry: ") + e.what());
    }

    RunArena arena(ctx->device, ctx->physical);
    RunDescriptors desc(ctx->device);
    if (!desc.init(err)) return fail(err);
    Scratch scratch(arena, err);
    if (frequency) {
        if (!scratch.buildFrequency(freq, W, H)) return fail(err);
    } else {
        if (!scratch.buildSpatial(spatial)) return fail(err);
    }

    // Input frames as R16UI images (same contract as RAWR's ring views).
    const VkDeviceSize frameBytes = VkDeviceSize(W) * H * 2u;
    std::vector<GpuImage*> inputs;
    for (std::uint32_t i = 0; i < N; ++i) {
        GpuImage* img = arena.image(VK_FORMAT_R16_UINT, W, H, err);
        if (img == nullptr) return fail(err);
        inputs.push_back(img);
    }
    GpuBuffer* upload = arena.staging(frameBytes * N, err);
    if (upload == nullptr) return fail(err);
    for (std::uint32_t i = 0; i < N; ++i) {
        std::memcpy(static_cast<char*>(upload->mapped) + i * frameBytes, frames[i],
                    static_cast<size_t>(frameBytes));
    }
    // Hot pixels: flat ivec2 list in a device-local buffer (RAWR caps at 2^20 pairs).
    GpuBuffer* hotBuf = nullptr;
    GpuBuffer* hotStage = nullptr;
    std::uint32_t hotPairs = 0;
    if (hot_pixels != nullptr && hot_pixel_count > 0) {
        hotPairs = std::min<std::uint32_t>(static_cast<std::uint32_t>(hot_pixel_count),
                                           1u << 20);
        hotStage = arena.staging(VkDeviceSize(hotPairs) * 2u * sizeof(std::int32_t), err);
        if (hotStage == nullptr) return fail(err);
        std::memcpy(hotStage->mapped, hot_pixels, static_cast<size_t>(hotPairs) * 2u * sizeof(std::int32_t));
        hotBuf = arena.buffer(VkDeviceSize(hotPairs) * 2u * sizeof(std::int32_t), err);
        if (hotBuf == nullptr) return fail(err);
    }
    GpuBuffer* download = arena.staging(VkDeviceSize(W) * H * sizeof(float), err);
    if (download == nullptr) return fail(err);

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo cai{};
    cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cai.commandPool = ctx->pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cai, &cmd) != VK_SUCCESS)
        return fail("hdrplus: command buffer allocation failed");

    VkQueryPool queryPool = VK_NULL_HANDLE;
    VkQueryPoolCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    qci.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qci.queryCount = 2;
    if (vkCreateQueryPool(ctx->device, &qci, nullptr, &queryPool) != VK_SUCCESS) {
        vkFreeCommandBuffers(ctx->device, ctx->pool, 1, &cmd);
        return fail("hdrplus: query pool creation failed");
    }
    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fci{};
    fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(ctx->device, &fci, nullptr, &fence) != VK_SUCCESS) {
        vkDestroyQueryPool(ctx->device, queryPool, nullptr);
        vkFreeCommandBuffers(ctx->device, ctx->pool, 1, &cmd);
        return fail("hdrplus: fence creation failed");
    }

    struct DumpStage {
        std::string path;
        GpuBuffer* stage;
        VkDeviceSize bytes;
    };
    std::vector<DumpStage> dumpStages;
    bool ok = false;
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &bi) == VK_SUCCESS) {
        vkCmdResetQueryPool(cmd, queryPool, 0, 2);
        arena.transition_all_to_general(cmd);
        // Uploads.
        for (std::uint32_t i = 0; i < N; ++i) {
            VkBufferImageCopy region{};
            region.bufferOffset = VkDeviceSize(i) * frameBytes;
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.imageExtent = {W, H, 1};
            vkCmdCopyBufferToImage(cmd, upload->buffer, inputs[i]->image, VK_IMAGE_LAYOUT_GENERAL, 1,
                                   &region);
        }
        if (hotBuf != nullptr && hotStage != nullptr) {
            VkBufferCopy hotCopy{0, 0, VkDeviceSize(hotPairs) * 2u * sizeof(std::int32_t)};
            vkCmdCopyBuffer(cmd, hotStage->buffer, hotBuf->buffer, 1, &hotCopy);
        }
        // Barrier: transfer writes visible to compute.
        VkMemoryBarrier upBar{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr, VK_ACCESS_TRANSFER_WRITE_BIT,
                              VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT};
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 1, &upBar, 0, nullptr, 0, nullptr);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0);

        std::vector<VkImageView> views;
        std::vector<RawNormalization> norms;
        for (std::uint32_t i = 0; i < N; ++i) {
            views.push_back(inputs[i]->view);
            RawNormalization rn{};
            for (int k = 0; k < 4; ++k) rn.blackByPhase[k] = black_by_phase[i * 4 + k];
            rn.whiteLevel = white[i];
            norms.push_back(rn);
        }
        const std::uint32_t ref = static_cast<std::uint32_t>(ref_index);
        VkBuffer hotHandle = hotBuf != nullptr ? hotBuf->buffer : VK_NULL_HANDLE;
        if (frequency) {
            ok = run_frequency(ctx, desc, cmd, scratch, config, freq, views, norms, ref, hotHandle,
                               hotPairs, err);
        } else {
            // Spatial geometry lives in `spatial`; run_spatial takes it by value.
            ok = run_spatial(ctx, desc, cmd, scratch, config, spatial, views, norms, ref, hotHandle,
                             hotPairs, err);
        }
        if (ok && std::getenv("HDRPLUS_DUMP_DIR") != nullptr) {
            // Diagnostic readback (dev only): alignment vectors, warped
            // companion, and merge weights, for parity debugging.
            struct DumpItem {
                const char* name;
                bool isImage;
            };
            const DumpItem items[] = {{"hdrp_align_a", false},
                                      {"hdrp_aligned", true},
                                      {"hdrp_weight", true},
                                      {"hdrp_tile_cost", false},
                                      {"hdrp_ref_l0", true},
                                      {"hdrp_comp_l0", true},
                                      {"hdrp_ref_padded", true},
                                      {"hdrp_comp_padded", true}};
            for (const auto& item : items) {
                if (item.isImage && !scratch.hasImage(item.name)) continue;
                if (!item.isImage && !scratch.hasBuffer(item.name)) continue;
                char path[1024];
                std::snprintf(path, sizeof(path), "%s/%s.bin", std::getenv("HDRPLUS_DUMP_DIR"),
                              item.name);
                VkMemoryBarrier bar{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr,
                                    VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT};
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                     VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &bar, 0, nullptr, 0,
                                     nullptr);
                if (item.isImage) {
                    const GpuImage& img = scratch.image(item.name);
                    GpuBuffer* stage =
                        arena.staging(VkDeviceSize(img.width) * img.height * sizeof(float), err);
                    if (stage == nullptr) {
                        ok = false;
                        break;
                    }
                    VkBufferImageCopy region{};
                    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                    region.imageExtent = {img.width, img.height, 1};
                    vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_GENERAL, stage->buffer,
                                           1, &region);
                    dumpStages.push_back(
                        {std::string(path), stage, VkDeviceSize(img.width) * img.height * 4u});
                } else {
                    const GpuBuffer& buf = scratch.buffer(item.name);
                    GpuBuffer* stage = arena.staging(buf.bytes, err);
                    if (stage == nullptr) {
                        ok = false;
                        break;
                    }
                    VkBufferCopy region{0, 0, buf.bytes};
                    vkCmdCopyBuffer(cmd, buf.buffer, stage->buffer, 1, &region);
                    dumpStages.push_back({std::string(path), stage, buf.bytes});
                }
            }
            VkMemoryBarrier backBar{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr,
                                    VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &backBar, 0, nullptr,
                                 0, nullptr);
        }
        if (ok) {
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1);
            // Download: merged CFA (hdrp_cfa, normalized float) to staging.
            VkMemoryBarrier downBar{VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr,
                                    VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &downBar, 0, nullptr, 0,
                                 nullptr);
            const GpuImage& cfa = scratch.image("hdrp_cfa");
            VkBufferImageCopy region{};
            region.bufferOffset = 0;
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.imageExtent = {W, H, 1};
            vkCmdCopyImageToBuffer(cmd, cfa.image, VK_IMAGE_LAYOUT_GENERAL, download->buffer, 1,
                                   &region);
        }
        ok = ok && vkEndCommandBuffer(cmd) == VK_SUCCESS;
    } else {
        err = "hdrplus: vkBeginCommandBuffer failed";
    }

    if (ok) {
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        ok = vkQueueSubmit(ctx->queue, 1, &si, fence) == VK_SUCCESS;
        if (!ok) err = "hdrplus: vkQueueSubmit failed";
    }
    if (ok) {
        ok = vkWaitForFences(ctx->device, 1, &fence, VK_TRUE, 60'000'000'000ull) == VK_SUCCESS;
        if (!ok) err = "hdrplus: GPU wait timed out";
    }
    if (ok) {
        std::uint64_t stamps[2] = {0, 0};
        if (vkGetQueryPoolResults(ctx->device, queryPool, 0, 2, sizeof(stamps), stamps,
                                  sizeof(std::uint64_t),
                                  VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS) {
            ctx->last_gpu_ms =
                (stamps[1] - stamps[0]) * ctx->timestamp_period_ns / 1'000'000.0;
        }
        std::memcpy(out, download->mapped, static_cast<size_t>(W) * H * sizeof(float));
        for (const auto& d : dumpStages) {
            FILE* f = std::fopen(d.path.c_str(), "wb");
            if (f != nullptr) {
                std::fwrite(d.stage->mapped, 1, static_cast<size_t>(d.bytes), f);
                std::fclose(f);
            }
        }
    }
    vkDestroyFence(ctx->device, fence, nullptr);
    vkDestroyQueryPool(ctx->device, queryPool, nullptr);
    vkFreeCommandBuffers(ctx->device, ctx->pool, 1, &cmd);
    if (!ok) return fail(err.empty() ? "hdrplus: merge failed" : err);
    HP_LOGI("hdrplus merge %s %ux%u N=%u ref=%d gpu=%.1fms", frequency ? "frequency" : "spatial", W, H,
            N, ref_index, ctx->last_gpu_ms);
    return 0;
}

}  // extern "C"
