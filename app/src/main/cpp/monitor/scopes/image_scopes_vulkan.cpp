#include "image_scopes/image_scopes.h"
#if IMAGE_SCOPES_HAS_VULKAN
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

#include "image_scopes_shaders_spv.hpp"
#include "layouts.hpp"

namespace image_scopes {
namespace {
using detail::DisplayWaveformStd430;
using detail::VectorscopeStd430;
constexpr std::uint32_t kSetKinds = 8;
constexpr std::uint32_t kDescriptorInvocationsPerKind = 16;
enum SetKind : std::uint32_t {
    ExposureMeasure,
    ExposureReduce,
    WaveMeasure,
    WaveRender,
    VecMeasure,
    VecReduce,
    VecRender,
    RawWaveRender
};
constexpr VkDeviceSize kExposureScratchBytes = 16ull * 260ull * 4ull;
constexpr VkDeviceSize kVecScratchBytes = static_cast<VkDeviceSize>(detail::kVectorScratchBytes);
constexpr std::uint32_t kMaxDisplayWidth = (std::numeric_limits<std::uint32_t>::max() - 511u) / 512u;
constexpr std::uint32_t kMaxDisplayHeight = std::numeric_limits<std::uint32_t>::max() / 64u;
void check(VkResult r, const char* s) {
    if (r != VK_SUCCESS) throw std::runtime_error(s);
}
std::uint32_t memType(VkPhysicalDevice pd, std::uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties p{};
    vkGetPhysicalDeviceMemoryProperties(pd, &p);
    for (std::uint32_t i = 0; i < p.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (p.memoryTypes[i].propertyFlags & flags) == flags) return i;
    throw std::runtime_error("image_scopes: no suitable memory type");
}
VkShaderModule shader(VkDevice d, const std::uint32_t* p, std::size_t n, const VkAllocationCallbacks* a) {
    VkShaderModuleCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = n;
    ci.pCode = p;
    VkShaderModule m{};
    check(vkCreateShaderModule(d, &ci, a, &m), "image_scopes: vkCreateShaderModule failed");
    return m;
}
void validateImage(const DisplayImageView& v) {
    if (!v.view || !v.width || !v.height) throw std::invalid_argument("image_scopes: invalid display image");
    if (v.width > kMaxDisplayWidth || v.height > kMaxDisplayHeight)
        throw std::invalid_argument("image_scopes: display dimensions exceed shader-safe bounds");
    if (std::uint64_t(v.width) * std::uint64_t(v.height) > std::numeric_limits<std::uint32_t>::max())
        throw std::invalid_argument("image_scopes: source image exceeds uint32 sample-count contract");
    if (v.format != VK_FORMAT_R8G8B8A8_UNORM)
        throw std::invalid_argument("image_scopes: display input must be R8G8B8A8_UNORM");
    if (v.layout != VK_IMAGE_LAYOUT_GENERAL) throw std::invalid_argument("image_scopes: display input must be GENERAL");
}
void validateTarget(const ScopeRenderTarget& v) {
    if (!v.view || !v.width || !v.height) throw std::invalid_argument("image_scopes: invalid render target");
    if (v.format != VK_FORMAT_R8G8B8A8_UNORM)
        throw std::invalid_argument("image_scopes: render target must be R8G8B8A8_UNORM");
    if (v.layout != VK_IMAGE_LAYOUT_GENERAL) throw std::invalid_argument("image_scopes: render target must be GENERAL");
}
void validateSamplingMode(SamplingMode m) {
    if (m != SamplingMode::FullReference && m != SamplingMode::Production50 && m != SamplingMode::Production25)
        throw std::invalid_argument("image_scopes: unsupported sampling mode");
}
std::uint32_t sourcePixelCount(std::uint32_t w, std::uint32_t h) {
    const std::uint64_t n = std::uint64_t(w) * std::uint64_t(h);
    if (n > std::numeric_limits<std::uint32_t>::max())
        throw std::invalid_argument("image_scopes: source image exceeds uint32 sample-count contract");
    return static_cast<std::uint32_t>(n);
}
void validateVectorscopeRenderParams(const VectorscopeRenderParams& p) {
    if (!std::isfinite(p.densityGain) || p.densityGain < 0.0f)
        throw std::invalid_argument("image_scopes: vectorscope densityGain must be finite and nonnegative");
    if (!std::isfinite(p.opacity) || p.opacity < 0.0f || p.opacity > 1.0f)
        throw std::invalid_argument("image_scopes: vectorscope opacity must be finite and in [0,1]");
    if (p.densityScale != DensityScale::Logarithmic || p.pointSpreadBins != 1u)
        throw std::invalid_argument(
            "image_scopes: V22 vectorscope presentation is frozen to Logarithmic densityScale and pointSpreadBins=1");
}
void validateWaveformMode(WaveformMode mode) {
    if (mode != WaveformMode::Luma && mode != WaveformMode::RgbOverlay)
        throw std::invalid_argument("image_scopes: unsupported waveform mode");
}
void validateWaveformRenderParams(const WaveformRenderParams& p) {
    validateWaveformMode(p.mode);
    if (!std::isfinite(p.densityGain) || p.densityGain < 0.0f)
        throw std::invalid_argument("image_scopes: waveform densityGain must be finite and nonnegative");
    if (!std::isfinite(p.opacity) || p.opacity < 0.0f || p.opacity > 1.0f)
        throw std::invalid_argument("image_scopes: waveform opacity must be finite and in [0,1]");
    if (p.densityScale != DensityScale::Linear || p.pointSpreadBins != 1u)
        throw std::invalid_argument(
            "image_scopes: 0.1.0 waveform presentation is frozen to Linear densityScale and pointSpreadBins=1");
}
std::uint32_t sm(SamplingMode m) {
    validateSamplingMode(m);
    return static_cast<std::uint32_t>(m);
}
std::uint32_t hash2host(std::uint32_t x, std::uint32_t y) {
    std::uint32_t h = x * 0x9E3779B9u ^ y * 0x85EBCA6Bu ^ 0xC2B2AE35u;
    h ^= h >> 16u;
    h *= 0x7FEB352Du;
    h ^= h >> 15u;
    h *= 0x846CA68Bu;
    h ^= h >> 16u;
    return h;
}
bool selectedHost(std::uint32_t x, std::uint32_t y, SamplingMode m) {
    validateSamplingMode(m);
    if (m == SamplingMode::FullReference) return true;
    std::uint32_t lane = (y & 1u) * 2u + (x & 1u), h = hash2host(x >> 1u, y >> 1u);
    if (m == SamplingMode::Production25) return lane == (h & 3u);
    bool d = (h & 1u) != 0u;
    return d ? (lane == 0u || lane == 3u) : (lane == 1u || lane == 2u);
}
std::uint32_t sampledCount(std::uint32_t w, std::uint32_t h, SamplingMode m) {
    validateSamplingMode(m);
    const auto source = sourcePixelCount(w, h);
    if (m == SamplingMode::FullReference) return source;
    std::uint64_t count = 0;
    const std::uint32_t fullW = w & ~1u, fullH = h & ~1u;
    const std::uint32_t perBlock = m == SamplingMode::Production25 ? 1u : 2u;
    count = std::uint64_t(fullW / 2u) * (fullH / 2u) * perBlock;
    if (w & 1u)
        for (std::uint32_t y = 0; y < fullH; ++y) count += selectedHost(fullW, y, m);
    if (h & 1u)
        for (std::uint32_t x = 0; x < fullW; ++x) count += selectedHost(x, fullH, m);
    if ((w & 1u) && (h & 1u)) count += selectedHost(fullW, fullH, m);
    if (count > source) throw std::logic_error("image_scopes: internal sampled count exceeds source pixels");
    return static_cast<std::uint32_t>(count);
}

struct Buffer {
    VkBuffer b = VK_NULL_HANDLE;
    VkDeviceMemory m = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
};
Buffer makeBuffer(VkPhysicalDevice pd, VkDevice d, VkDeviceSize size, const VkAllocationCallbacks* a) {
    Buffer o{};
    o.size = size;
    VkBufferCreateInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bi.size = size;
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(d, &bi, a, &o.b), "image_scopes: vkCreateBuffer failed");
    VkMemoryRequirements mr{};
    vkGetBufferMemoryRequirements(d, o.b, &mr);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = memType(pd, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    try {
        check(vkAllocateMemory(d, &ai, a, &o.m), "image_scopes: vkAllocateMemory failed");
        check(vkBindBufferMemory(d, o.b, o.m, 0), "image_scopes: vkBindBufferMemory failed");
    } catch (...) {
        if (o.m) vkFreeMemory(d, o.m, a);
        vkDestroyBuffer(d, o.b, a);
        throw;
    }
    return o;
}
void destroyBuffer(VkDevice d, Buffer& b, const VkAllocationCallbacks* a) {
    if (b.b) vkDestroyBuffer(d, b.b, a);
    if (b.m) vkFreeMemory(d, b.m, a);
    b = {};
}
struct ExposureMeasurePush {
    std::uint32_t width, height, sampleBlockSize, p1, p2, p3, p4, p5;
};
static_assert(sizeof(ExposureMeasurePush) == 32);
struct ExposureReducePush {
    std::uint32_t width, height, p0, p1, p2, p3, p4, p5;
};
static_assert(sizeof(ExposureReducePush) == 32);
struct WaveMeasurePush {
    std::uint32_t width, height, mode, samplingMode, sampledCountOffset, maxOffset, sampledPixelCount, p1;
};
static_assert(sizeof(WaveMeasurePush) == 32);
struct VecMeasurePush {
    std::uint32_t width, height, samplingMode, sampledCountOffset, maxOffset, sampledPixelCount, gridBins, p2;
};
static_assert(sizeof(VecMeasurePush) == 32);
struct WaveRenderPush {
    std::uint32_t mode;
    float gain, opacity;
    std::uint32_t showGrid, showBg, sampledPixels, reserved0, reserved1;
};
static_assert(sizeof(WaveRenderPush) == 32);
struct VecRenderPush {
    float gain, opacity;
    std::uint32_t showGrid, showTargets, showBg, sampledPixelCount, reserved0, reserved1;
};
static_assert(sizeof(VecRenderPush) == 32);
struct RawWavePush {
    std::uint32_t mode;
    float gain, opacity;
    std::uint32_t showGrid, showBg, p0, p1, p2;
};
static_assert(sizeof(RawWavePush) == 32);
}  // namespace

struct ImageScopes::Impl {
    VkPhysicalDevice pd{};
    VkDevice d{};
    const VkAllocationCallbacks* alloc{};
    std::uint32_t slots{};
    std::uint32_t maxRenderWidth{}, maxRenderHeight{};
    VkDescriptorSetLayout measureDsl{}, reduceDsl{}, renderDsl{};
    VkPipelineLayout measurePl{}, reducePl{}, renderPl{};
    VkDescriptorPool pool{};
    VkPipeline exposureMeasure{}, exposureReduce{}, waveMeasure{}, vecMeasure{}, vecReduce{}, waveRender{},
        vecRender{}, rawWaveRender{};
    std::vector<std::array<VkDescriptorSet, kSetKinds>> sets;
    std::vector<std::array<std::array<VkDescriptorSet, kDescriptorInvocationsPerKind>, kSetKinds>> invocationSets;
    std::vector<std::array<std::uint32_t, kSetKinds>> invocationCursor;
    std::vector<Buffer> exposure, exposureScratch, wave, vec, vecScratch;
    std::vector<SamplingMetadata> waveMeta, vecMeta;
    std::vector<SamplingMetadata> pendingWaveMeta, pendingVecMeta;
    std::vector<WaveformMode> pendingWaveMode, validWaveMode;
    std::vector<bool> pendingWaveMeasurement, validWaveMeasurement, pendingVecMeasurement, validVecMeasurement;
    std::vector<VkCommandBuffer> pendingWaveCommandBuffer, pendingVecCommandBuffer;
    Impl(const CreateInfo& ci)
        : pd(ci.context.physicalDevice),
          d(ci.context.device),
          alloc(ci.context.allocator),
          slots(ci.maxFramesInFlight) {
        if (!pd || !d || slots == 0 || slots > kMaxFramesInFlight)
            throw std::invalid_argument("image_scopes: invalid CreateInfo/maxFramesInFlight");
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(pd, &props);
        const auto dim = props.limits.maxImageDimension2D;
        const std::uint64_t gx = std::uint64_t(props.limits.maxComputeWorkGroupCount[0]) * 16ull,
                            gy = std::uint64_t(props.limits.maxComputeWorkGroupCount[1]) * 16ull;
        maxRenderWidth = static_cast<std::uint32_t>(std::min<std::uint64_t>(dim, gx));
        maxRenderHeight = static_cast<std::uint32_t>(std::min<std::uint64_t>(dim, gy));
        try {
            std::array<VkDescriptorSetLayoutBinding, 2> mb{
                {{0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                 {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
            VkDescriptorSetLayoutCreateInfo dl{};
            dl.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
            dl.bindingCount = 2;
            dl.pBindings = mb.data();
            check(vkCreateDescriptorSetLayout(d, &dl, alloc, &measureDsl), "image_scopes: measure DSL failed");
            std::array<VkDescriptorSetLayoutBinding, 2> xb{
                {{0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                 {1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
            dl.pBindings = xb.data();
            check(vkCreateDescriptorSetLayout(d, &dl, alloc, &reduceDsl), "image_scopes: reduce DSL failed");
            std::array<VkDescriptorSetLayoutBinding, 2> rb{
                {{0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr},
                 {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr}}};
            dl.pBindings = rb.data();
            check(vkCreateDescriptorSetLayout(d, &dl, alloc, &renderDsl), "image_scopes: render DSL failed");
            VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, 32};
            VkPipelineLayoutCreateInfo pli{};
            pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            pli.setLayoutCount = 1;
            pli.pushConstantRangeCount = 1;
            pli.pPushConstantRanges = &pr;
            pli.pSetLayouts = &measureDsl;
            check(vkCreatePipelineLayout(d, &pli, alloc, &measurePl), "image_scopes: measure PL failed");
            pli.pSetLayouts = &reduceDsl;
            check(vkCreatePipelineLayout(d, &pli, alloc, &reducePl), "image_scopes: reduce PL failed");
            pli.pSetLayouts = &renderDsl;
            check(vkCreatePipelineLayout(d, &pli, alloc, &renderPl), "image_scopes: render PL failed");
            auto mkpipe = [&](const std::uint32_t* words, std::size_t bytes, VkPipelineLayout pl) {
                VkShaderModule m = shader(d, words, bytes, alloc);
                VkPipelineShaderStageCreateInfo st{};
                st.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
                st.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                st.module = m;
                st.pName = "main";
                VkComputePipelineCreateInfo pc{};
                pc.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
                pc.stage = st;
                pc.layout = pl;
                VkPipeline p{};
                VkResult r = vkCreateComputePipelines(d, VK_NULL_HANDLE, 1, &pc, alloc, &p);
                vkDestroyShaderModule(d, m, alloc);
                check(r, "image_scopes: compute pipeline failed");
                return p;
            };
            exposureMeasure =
                mkpipe(generated::exposure_stats_measure, generated::exposure_stats_measure_bytes, measurePl);
            exposureReduce = mkpipe(generated::exposure_stats_reduce, generated::exposure_stats_reduce_bytes, reducePl);
            waveMeasure = mkpipe(generated::waveform_measure, generated::waveform_measure_bytes, measurePl);
            vecMeasure = mkpipe(generated::vectorscope_measure, generated::vectorscope_measure_bytes, measurePl);
            vecReduce = mkpipe(generated::vectorscope_reduce, generated::vectorscope_reduce_bytes, reducePl);
            waveRender = mkpipe(generated::render_waveform, generated::render_waveform_bytes, renderPl);
            vecRender = mkpipe(generated::render_vectorscope, generated::render_vectorscope_bytes, renderPl);
            rawWaveRender = mkpipe(generated::render_raw_waveform, generated::render_raw_waveform_bytes, renderPl);
            // Every recorded descriptor invocation gets a descriptor set that is
            // immutable until the owning frame slot is retired/discarded. This is
            // deliberately cumulative across measure/reduce/render and RAW paths:
            // Vulkan descriptor contents are consumed at execution, not recording.
            const std::uint32_t perSlotSets = kSetKinds * kDescriptorInvocationsPerKind;
            // Per kind each set has exactly two descriptors. Count by its DSL.
            const std::uint32_t measureKinds = 3u, reduceKinds = 2u, renderKinds = 3u;
            std::array<VkDescriptorPoolSize, 2> ps{
                {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                  (measureKinds + renderKinds) * kDescriptorInvocationsPerKind * slots},
                 {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                  (measureKinds + 2u * reduceKinds + renderKinds) * kDescriptorInvocationsPerKind * slots}}};
            VkDescriptorPoolCreateInfo pi{};
            pi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            pi.maxSets = perSlotSets * slots;
            pi.poolSizeCount = 2;
            pi.pPoolSizes = ps.data();
            check(vkCreateDescriptorPool(d, &pi, alloc, &pool), "image_scopes: descriptor pool failed");
            invocationSets.resize(slots);
            invocationCursor.resize(slots);
            for (auto& c : invocationCursor) c.fill(0u);
            std::vector<VkDescriptorSetLayout> layouts;
            layouts.reserve(perSlotSets * slots);
            auto layoutFor = [&](std::uint32_t k) {
                if (k == ExposureMeasure || k == WaveMeasure || k == VecMeasure) return measureDsl;
                if (k == ExposureReduce || k == VecReduce) return reduceDsl;
                return renderDsl;
            };
            for (std::uint32_t slot = 0; slot < slots; ++slot)
                for (std::uint32_t k = 0; k < kSetKinds; ++k)
                    for (std::uint32_t n = 0; n < kDescriptorInvocationsPerKind; ++n) layouts.push_back(layoutFor(k));
            std::vector<VkDescriptorSet> flat(layouts.size());
            VkDescriptorSetAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            ai.descriptorPool = pool;
            ai.descriptorSetCount = static_cast<std::uint32_t>(layouts.size());
            ai.pSetLayouts = layouts.data();
            check(vkAllocateDescriptorSets(d, &ai, flat.data()),
                  "image_scopes: invocation-safe descriptor allocation failed");
            std::size_t fi = 0;
            for (std::uint32_t slot = 0; slot < slots; ++slot)
                for (std::uint32_t k = 0; k < kSetKinds; ++k)
                    for (std::uint32_t n = 0; n < kDescriptorInvocationsPerKind; ++n)
                        invocationSets[slot][k][n] = flat[fi++];

            exposure.reserve(slots);
            exposureScratch.reserve(slots);
            wave.reserve(slots);
            vec.reserve(slots);
            vecScratch.reserve(slots);
            waveMeta.resize(slots);
            vecMeta.resize(slots);
            pendingWaveMeta.resize(slots);
            pendingVecMeta.resize(slots);
            pendingWaveMode.resize(slots, WaveformMode::Luma);
            validWaveMode.resize(slots, WaveformMode::Luma);
            pendingWaveMeasurement.assign(slots, false);
            validWaveMeasurement.assign(slots, false);
            pendingVecMeasurement.assign(slots, false);
            validVecMeasurement.assign(slots, false);
            pendingWaveCommandBuffer.assign(slots, VK_NULL_HANDLE);
            pendingVecCommandBuffer.assign(slots, VK_NULL_HANDLE);
            for (std::uint32_t s = 0; s < slots; ++s) {
                exposure.push_back(makeBuffer(pd, d, sizeof(DisplayExposureStatsData), alloc));
                exposureScratch.push_back(makeBuffer(pd, d, kExposureScratchBytes, alloc));
                wave.push_back(makeBuffer(pd, d, sizeof(DisplayWaveformStd430), alloc));
                vec.push_back(makeBuffer(pd, d, sizeof(VectorscopeStd430), alloc));
                vecScratch.push_back(makeBuffer(pd, d, kVecScratchBytes, alloc));
            }
        } catch (...) {
            cleanup();
            throw;
        }
    }
    ~Impl() { cleanup(); }
    void validateTargetDevice(const ScopeRenderTarget& t) const {
        validateTarget(t);
        if (t.width > maxRenderWidth || t.height > maxRenderHeight)
            throw std::invalid_argument("image_scopes: render target exceeds device compute/image limits");
    }
    void cleanup() {
        for (auto& b : exposure) destroyBuffer(d, b, alloc);
        for (auto& b : exposureScratch) destroyBuffer(d, b, alloc);
        for (auto& b : wave) destroyBuffer(d, b, alloc);
        for (auto& b : vec) destroyBuffer(d, b, alloc);
        for (auto& b : vecScratch) destroyBuffer(d, b, alloc);
        if (pool) vkDestroyDescriptorPool(d, pool, alloc);
        for (VkPipeline p : {exposureMeasure, exposureReduce, waveMeasure, vecMeasure, vecReduce, waveRender,
                             vecRender, rawWaveRender})
            if (p) vkDestroyPipeline(d, p, alloc);
        if (measurePl) vkDestroyPipelineLayout(d, measurePl, alloc);
        if (reducePl) vkDestroyPipelineLayout(d, reducePl, alloc);
        if (renderPl) vkDestroyPipelineLayout(d, renderPl, alloc);
        if (measureDsl) vkDestroyDescriptorSetLayout(d, measureDsl, alloc);
        if (reduceDsl) vkDestroyDescriptorSetLayout(d, reduceDsl, alloc);
        if (renderDsl) vkDestroyDescriptorSetLayout(d, renderDsl, alloc);
    }
    void slot(std::uint32_t s) const {
        if (s >= slots) throw std::invalid_argument("image_scopes: frameSlot out of range");
    }
    VkDescriptorSet acquireSet(std::uint32_t s, SetKind k) {
        auto& cursor = invocationCursor[s][static_cast<std::uint32_t>(k)];
        if (cursor >= kDescriptorInvocationsPerKind)
            throw std::logic_error(
                "image_scopes: descriptor invocation arena exhausted; retire or discard the frame slot before reuse");
        return invocationSets[s][static_cast<std::uint32_t>(k)][cursor++];
    }
    VkDescriptorSet bindMeasure(std::uint32_t s, SetKind k, VkImageView im, VkImageLayout layout, Buffer& b) {
        auto set = acquireSet(s, k);
        VkDescriptorImageInfo ii{VK_NULL_HANDLE, im, layout};
        VkDescriptorBufferInfo bi{b.b, 0, b.size};
        std::array<VkWriteDescriptorSet, 2> w{};
        for (auto& x : w) x.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[0].dstSet = set;
        w[0].dstBinding = 0;
        w[0].descriptorCount = 1;
        w[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w[0].pImageInfo = &ii;
        w[1].dstSet = set;
        w[1].dstBinding = 1;
        w[1].descriptorCount = 1;
        w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w[1].pBufferInfo = &bi;
        vkUpdateDescriptorSets(d, 2, w.data(), 0, nullptr);
        return set;
    }
    VkDescriptorSet bindReduce(std::uint32_t s, SetKind k, Buffer& src, Buffer& dst) {
        auto set = acquireSet(s, k);
        VkDescriptorBufferInfo a{src.b, 0, src.size}, b{dst.b, 0, dst.size};
        std::array<VkWriteDescriptorSet, 2> w{};
        for (auto& x : w) x.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[0].dstSet = set;
        w[0].dstBinding = 0;
        w[0].descriptorCount = 1;
        w[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w[0].pBufferInfo = &a;
        w[1].dstSet = set;
        w[1].dstBinding = 1;
        w[1].descriptorCount = 1;
        w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w[1].pBufferInfo = &b;
        vkUpdateDescriptorSets(d, 2, w.data(), 0, nullptr);
        return set;
    }
    VkDescriptorSet bindRender(std::uint32_t s, SetKind k, const BufferView& b, const ScopeRenderTarget& t) {
        auto set = acquireSet(s, k);
        VkDescriptorBufferInfo bi{b.buffer, b.offset, b.size};
        VkDescriptorImageInfo ii{VK_NULL_HANDLE, t.view, t.layout};
        std::array<VkWriteDescriptorSet, 2> w{};
        for (auto& x : w) x.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[0].dstSet = set;
        w[0].dstBinding = 0;
        w[0].descriptorCount = 1;
        w[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w[0].pBufferInfo = &bi;
        w[1].dstSet = set;
        w[1].dstBinding = 1;
        w[1].descriptorCount = 1;
        w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w[1].pImageInfo = &ii;
        vkUpdateDescriptorSets(d, 2, w.data(), 0, nullptr);
        return set;
    }
    static void computeToTransferWrite(VkCommandBuffer c, VkBuffer b, VkDeviceSize n) {
        VkBufferMemoryBarrier x{};
        x.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        x.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        x.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        x.srcQueueFamilyIndex = x.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        x.buffer = b;
        x.offset = 0;
        x.size = n;
        vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1,
                             &x, 0, nullptr);
    }
    static void transferBarrier(VkCommandBuffer c, VkBuffer b, VkDeviceSize n) {
        VkBufferMemoryBarrier x{};
        x.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        x.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        x.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        x.srcQueueFamilyIndex = x.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        x.buffer = b;
        x.offset = 0;
        x.size = n;
        vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1,
                             &x, 0, nullptr);
    }
    static void computeReuseBarrier(VkCommandBuffer c, VkBuffer b, VkDeviceSize n) {
        VkBufferMemoryBarrier x{};
        x.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        x.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        x.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        x.srcQueueFamilyIndex = x.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        x.buffer = b;
        x.offset = 0;
        x.size = n;
        vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                             nullptr, 1, &x, 0, nullptr);
    }
    static void computeBarrier(VkCommandBuffer c, VkBuffer b, VkDeviceSize n) {
        VkBufferMemoryBarrier x{};
        x.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        x.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        x.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        x.srcQueueFamilyIndex = x.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        x.buffer = b;
        x.offset = 0;
        x.size = n;
        vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                             nullptr, 1, &x, 0, nullptr);
    }
    static void computeToTransfer(VkCommandBuffer c, VkBuffer b, VkDeviceSize n) {
        VkBufferMemoryBarrier x{};
        x.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        x.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        x.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        x.srcQueueFamilyIndex = x.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        x.buffer = b;
        x.offset = 0;
        x.size = n;
        vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1,
                             &x, 0, nullptr);
    }
    void render(VkCommandBuffer c, std::uint32_t s, SetKind k, VkPipeline p, const BufferView& b,
                const ScopeRenderTarget& t, const void* push) {
        auto set = bindRender(s, k, b, t);
        vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_COMPUTE, p);
        vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_COMPUTE, renderPl, 0, 1, &set, 0, nullptr);
        vkCmdPushConstants(c, renderPl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, push);
        vkCmdDispatch(c, (t.width + 15u) / 16u, (t.height + 15u) / 16u, 1);
    }
    void renderWaveformWithSampleCount(VkCommandBuffer c, std::uint32_t s, const WaveformRenderParams& rp,
                                       const ScopeRenderTarget& target, std::uint32_t sampled) {
        WaveRenderPush wrp{static_cast<std::uint32_t>(rp.mode), rp.densityGain, rp.opacity, rp.showGrid ? 1u : 0u,
                           rp.showBackground ? 1u : 0u,         sampled,        0u,         0u};
        auto& b = wave[s];
        render(c, s, WaveRender, waveRender, {b.b, 0, b.size}, target, &wrp);
    }
    void renderWaveformDirect(VkCommandBuffer c, std::uint32_t s, const WaveformRenderParams& rp,
                              const ScopeRenderTarget& target) {
        // A render-only dispatch may consume a measurement recorded earlier in
        // the *same* command buffer, because execution order makes that data
        // valid for this dispatch. Across command buffers only retired/completed
        // metadata is trusted.
        if (pendingWaveMeasurement[s] && pendingWaveCommandBuffer[s] == c) {
            if (pendingWaveMode[s] != rp.mode)
                throw std::invalid_argument(
                    "image_scopes: waveform render mode must match same-command-buffer measured mode");
            renderWaveformWithSampleCount(c, s, rp, target, pendingWaveMeta[s].sampledPixelCount);
            return;
        }
        if (pendingWaveMeasurement[s])
            throw std::logic_error(
                "image_scopes: waveform frame slot has a pending measurement; cross-command-buffer render-only is "
                "suspended until retire/discard");
        if (!validWaveMeasurement[s])
            throw std::logic_error("image_scopes: waveform frame slot has no retired/valid measurement");
        if (validWaveMode[s] != rp.mode)
            throw std::invalid_argument(
                "image_scopes: waveform render mode must match retired measured frame-slot mode");
        renderWaveformWithSampleCount(c, s, rp, target, waveMeta[s].sampledPixelCount);
    }

    void renderVectorscopeWithSampleCount(VkCommandBuffer c, std::uint32_t s, const VectorscopeRenderParams& rp,
                                          const ScopeRenderTarget& target, std::uint32_t sampled) {
        auto& scratch = vecScratch[s];
        VecRenderPush vrp{rp.densityGain,
                          rp.opacity,
                          rp.showGrid ? 1u : 0u,
                          rp.showTargets ? 1u : 0u,
                          rp.showBackground ? 1u : 0u,
                          sampled,
                          0u,
                          0u};
        render(c, s, VecRender, vecRender,
               {scratch.b, detail::kVectorPresentationOffsetBytes, detail::kVectorPresentationBytes}, target, &vrp);
    }
};

ImageScopes::ImageScopes(const CreateInfo& i) : impl_(std::make_unique<Impl>(i)) {}
ImageScopes::~ImageScopes() = default;
ImageScopes::ImageScopes(ImageScopes&&) noexcept = default;
ImageScopes& ImageScopes::operator=(ImageScopes&&) noexcept = default;
void ImageScopes::recordDisplayExposureStats(const DisplayExposureStatsRecordInfo& i) {
    if (!impl_ || !i.commandBuffer) throw std::invalid_argument("image_scopes: invalid exposure stats record");
    impl_->slot(i.frameSlot);
    validateImage(i.input);
    if (i.sampleBlockSize != 2u && i.sampleBlockSize != 4u)
        throw std::invalid_argument("image_scopes: exposure stats sampleBlockSize must be 2 or 4");
    auto& out = impl_->exposure[i.frameSlot];
    auto& scratch = impl_->exposureScratch[i.frameSlot];
    Impl::computeReuseBarrier(i.commandBuffer, scratch.b, scratch.size);
    Impl::computeReuseBarrier(i.commandBuffer, out.b, out.size);
    auto ms = impl_->bindMeasure(i.frameSlot, ExposureMeasure, i.input.view, i.input.layout, scratch);
    ExposureMeasurePush mp{i.input.width, i.input.height, i.sampleBlockSize, 0, 0, 0, 0, 0};
    vkCmdBindPipeline(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->exposureMeasure);
    vkCmdBindDescriptorSets(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->measurePl, 0, 1, &ms, 0, nullptr);
    vkCmdPushConstants(i.commandBuffer, impl_->measurePl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, &mp);
    vkCmdDispatch(i.commandBuffer, 16u, 1u, 1u);
    Impl::computeBarrier(i.commandBuffer, scratch.b, scratch.size);
    auto rs = impl_->bindReduce(i.frameSlot, ExposureReduce, scratch, out);
    ExposureReducePush rp{i.input.width, i.input.height, 0, 0, 0, 0, 0, 0};
    vkCmdBindPipeline(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->exposureReduce);
    vkCmdBindDescriptorSets(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->reducePl, 0, 1, &rs, 0, nullptr);
    vkCmdPushConstants(i.commandBuffer, impl_->reducePl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, &rp);
    vkCmdDispatch(i.commandBuffer, 1u, 1u, 1u);
}
void ImageScopes::recordDisplayWaveform(const DisplayWaveformRecordInfo& i) {
    if (!impl_ || !i.commandBuffer) throw std::invalid_argument("image_scopes: invalid waveform record");
    impl_->slot(i.frameSlot);
    if (impl_->pendingWaveMeasurement[i.frameSlot])
        throw std::logic_error("image_scopes: frame slot already has an unretired waveform measurement recording");
    validateImage(i.input);
    validateWaveformMode(i.render.mode);
    if (i.renderEnabled) {
        impl_->validateTargetDevice(i.renderTarget);
        validateWaveformRenderParams(i.render);
    }
    const auto sc = sampledCount(i.input.width, i.input.height, i.samplingMode);
    const auto source = sourcePixelCount(i.input.width, i.input.height);
    impl_->pendingWaveMeta[i.frameSlot] = {
        i.samplingMode, sc, source, float(sc) / float(source), kDisplayWaveformColumns, kDisplayWaveformBins};
    impl_->pendingWaveMode[i.frameSlot] = i.render.mode;
    impl_->pendingWaveMeasurement[i.frameSlot] = true;
    impl_->pendingWaveCommandBuffer[i.frameSlot] = i.commandBuffer;
    auto& b = impl_->wave[i.frameSlot];
    vkCmdFillBuffer(i.commandBuffer, b.b, static_cast<VkDeviceSize>(detail::kWaveRgbElements) * 4u, 16u, 0u);
    Impl::transferBarrier(i.commandBuffer, b.b, b.size);
    auto waveMeasureSet = impl_->bindMeasure(i.frameSlot, WaveMeasure, i.input.view, i.input.layout, b);
    WaveMeasurePush p{i.input.width,
                      i.input.height,
                      static_cast<std::uint32_t>(i.render.mode),
                      sm(i.samplingMode),
                      detail::kWaveRgbElements,
                      detail::kWaveRgbElements + 1u,
                      sc,
                      i.sourceQuarterTurns & 3u};
    vkCmdBindPipeline(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->waveMeasure);
    vkCmdBindDescriptorSets(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->measurePl, 0, 1, &waveMeasureSet, 0,
                            nullptr);
    vkCmdPushConstants(i.commandBuffer, impl_->measurePl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, &p);
    vkCmdDispatch(i.commandBuffer, 512u, 1u, 1u);
    if (i.renderEnabled) {
        Impl::computeBarrier(i.commandBuffer, b.b, b.size);
        impl_->renderWaveformWithSampleCount(i.commandBuffer, i.frameSlot, i.render, i.renderTarget, sc);
    }
}
void ImageScopes::recordDisplayWaveformRender(const DisplayWaveformRenderInfo& i) {
    if (!impl_ || !i.commandBuffer) throw std::invalid_argument("image_scopes: invalid waveform render record");
    impl_->slot(i.frameSlot);
    impl_->validateTargetDevice(i.renderTarget);
    validateWaveformRenderParams(i.render);
    auto& b = impl_->wave[i.frameSlot];
    Impl::computeBarrier(i.commandBuffer, b.b, b.size);
    impl_->renderWaveformDirect(i.commandBuffer, i.frameSlot, i.render, i.renderTarget);
}
void ImageScopes::recordVectorscope(const VectorscopeRecordInfo& i) {
    if (!impl_ || !i.commandBuffer) throw std::invalid_argument("image_scopes: invalid vectorscope record");
    impl_->slot(i.frameSlot);
    if (impl_->pendingVecMeasurement[i.frameSlot])
        throw std::logic_error("image_scopes: frame slot already has an unretired vectorscope measurement recording");
    validateImage(i.input);
    if (i.renderEnabled) {
        impl_->validateTargetDevice(i.renderTarget);
        validateVectorscopeRenderParams(i.render);
    }
    const auto sc = sampledCount(i.input.width, i.input.height, i.samplingMode);
    const auto grid = static_cast<std::uint32_t>(i.grid);
    if (grid != 128u && grid != 256u) throw std::invalid_argument("image_scopes: unsupported vectorscope grid");
    const auto source = sourcePixelCount(i.input.width, i.input.height);
    impl_->pendingVecMeta[i.frameSlot] = {i.samplingMode, sc, source, float(sc) / float(source), grid, grid};
    impl_->pendingVecMeasurement[i.frameSlot] = true;
    impl_->pendingVecCommandBuffer[i.frameSlot] = i.commandBuffer;
    auto& out = impl_->vec[i.frameSlot];
    auto& scratch = impl_->vecScratch[i.frameSlot];
    Impl::computeToTransferWrite(i.commandBuffer, scratch.b, scratch.size);
    Impl::computeToTransferWrite(i.commandBuffer, out.b, out.size);
    vkCmdFillBuffer(i.commandBuffer, scratch.b, detail::kVectorPresentationOffsetBytes,
                    detail::kVectorPresentationBytes, 0u);
    Impl::transferBarrier(i.commandBuffer, scratch.b, scratch.size);
    vkCmdFillBuffer(i.commandBuffer, out.b, static_cast<VkDeviceSize>(detail::kVectorElements) * 4u, 16u, 0u);
    Impl::transferBarrier(i.commandBuffer, out.b, out.size);
    auto vecMeasureSet = impl_->bindMeasure(i.frameSlot, VecMeasure, i.input.view, i.input.layout, scratch);
    VecMeasurePush p{i.input.width,
                     i.input.height,
                     sm(i.samplingMode),
                     detail::kVectorElements,
                     detail::kVectorElements + 1u,
                     sc,
                     grid,
                     0};
    vkCmdBindPipeline(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->vecMeasure);
    vkCmdBindDescriptorSets(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->measurePl, 0, 1, &vecMeasureSet, 0,
                            nullptr);
    vkCmdPushConstants(i.commandBuffer, impl_->measurePl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, &p);
    vkCmdDispatch(i.commandBuffer, 16u, 1u, 1u);
    Impl::computeBarrier(i.commandBuffer, scratch.b, scratch.size);
    auto vecReduceSet = impl_->bindReduce(i.frameSlot, VecReduce, scratch, out);
    vkCmdBindPipeline(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->vecReduce);
    vkCmdBindDescriptorSets(i.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, impl_->reducePl, 0, 1, &vecReduceSet, 0,
                            nullptr);
    vkCmdPushConstants(i.commandBuffer, impl_->reducePl, VK_SHADER_STAGE_COMPUTE_BIT, 0, 32, &p);
    vkCmdDispatch(i.commandBuffer, (grid * grid + 255u) / 256u, 1u, 1u);
    if (i.renderEnabled) {
        Impl::computeBarrier(i.commandBuffer, out.b, out.size);
        impl_->renderVectorscopeWithSampleCount(i.commandBuffer, i.frameSlot, i.render, i.renderTarget, sc);
    }
}
void ImageScopes::recordVectorscopeRender(const VectorscopeRenderInfo& i) {
    if (!impl_ || !i.commandBuffer) throw std::invalid_argument("image_scopes: invalid vectorscope render record");
    impl_->slot(i.frameSlot);
    impl_->validateTargetDevice(i.renderTarget);
    validateVectorscopeRenderParams(i.render);
    const bool usePending =
        impl_->pendingVecMeasurement[i.frameSlot] && impl_->pendingVecCommandBuffer[i.frameSlot] == i.commandBuffer;
    const bool pendingElsewhere = impl_->pendingVecMeasurement[i.frameSlot] && !usePending;
    if (pendingElsewhere)
        throw std::logic_error(
            "image_scopes: vectorscope render-only cannot use a frame slot with an unretired measurement from another "
            "command buffer");
    if (!usePending && !impl_->validVecMeasurement[i.frameSlot])
        throw std::logic_error("image_scopes: vectorscope frame slot has no retired measurement");
    const std::uint32_t grid =
        (usePending ? impl_->pendingVecMeta[i.frameSlot] : impl_->vecMeta[i.frameSlot]).gridWidth;
    if (grid != 128u && grid != 256u)
        throw std::logic_error("image_scopes: vectorscope frame slot has no valid measured grid");
    auto& scratch = impl_->vecScratch[i.frameSlot];
    Impl::computeBarrier(i.commandBuffer, scratch.b, scratch.size);
    const std::uint32_t sampled =
        (usePending ? impl_->pendingVecMeta[i.frameSlot] : impl_->vecMeta[i.frameSlot]).sampledPixelCount;
    impl_->renderVectorscopeWithSampleCount(i.commandBuffer, i.frameSlot, i.render, i.renderTarget, sampled);
}
void ImageScopes::recordRawWaveformRender(const RawWaveformRecordInfo& i) {
    if (!impl_ || !i.commandBuffer || !i.rawWaveform.waveform.buffer)
        throw std::invalid_argument("image_scopes: invalid RAW waveform record");
    impl_->slot(i.frameSlot);
    impl_->validateTargetDevice(i.renderTarget);
    if (i.rawWaveform.waveform.size < 262144) throw std::invalid_argument("image_scopes: raw waveform view too small");
    RawWavePush p{static_cast<std::uint32_t>(i.render.mode),
                  i.render.densityGain,
                  i.render.opacity,
                  i.render.showGrid ? 1u : 0u,
                  i.render.showBackground ? 1u : 0u,
                  0,
                  0,
                  0};
    impl_->render(i.commandBuffer, i.frameSlot, RawWaveRender, impl_->rawWaveRender, i.rawWaveform.waveform,
                  i.renderTarget, &p);
}
void ImageScopes::retireFrameSlot(std::uint32_t s) {
    if (!impl_) throw std::logic_error("image_scopes: moved-from");
    impl_->slot(s);
    if (impl_->pendingWaveMeasurement[s]) {
        impl_->waveMeta[s] = impl_->pendingWaveMeta[s];
        impl_->validWaveMode[s] = impl_->pendingWaveMode[s];
        impl_->validWaveMeasurement[s] = true;
        impl_->pendingWaveMeasurement[s] = false;
        impl_->pendingWaveCommandBuffer[s] = VK_NULL_HANDLE;
    }
    if (impl_->pendingVecMeasurement[s]) {
        impl_->vecMeta[s] = impl_->pendingVecMeta[s];
        impl_->validVecMeasurement[s] = true;
        impl_->pendingVecMeasurement[s] = false;
        impl_->pendingVecCommandBuffer[s] = VK_NULL_HANDLE;
    }
    impl_->invocationCursor[s].fill(0u);
}
void ImageScopes::discardFrameSlotRecordings(std::uint32_t s) {
    if (!impl_) throw std::logic_error("image_scopes: moved-from");
    impl_->slot(s);
    impl_->pendingWaveMeasurement[s] = false;
    impl_->pendingWaveCommandBuffer[s] = VK_NULL_HANDLE;
    impl_->pendingVecMeasurement[s] = false;
    impl_->pendingVecCommandBuffer[s] = VK_NULL_HANDLE;
    impl_->invocationCursor[s].fill(0u);
}
DisplayWaveformGpuView ImageScopes::displayWaveformView(std::uint32_t s) const {
    if (!impl_) throw std::logic_error("image_scopes: moved-from");
    impl_->slot(s);
    auto& b = impl_->wave[s];
    DisplayWaveformGpuView v{};
    v.storage = {b.b, 0, b.size};
    if (!impl_->validWaveMeasurement[s])
        throw std::logic_error("image_scopes: waveform frame slot has no retired/valid measurement");
    v.sampling = impl_->waveMeta[s];
    v.mode = impl_->validWaveMode[s];
    return v;
}
VectorscopeGpuView ImageScopes::vectorscopeView(std::uint32_t s) const {
    if (!impl_) throw std::logic_error("image_scopes: moved-from");
    impl_->slot(s);
    if (!impl_->validVecMeasurement[s])
        throw std::logic_error("image_scopes: vectorscope frame slot has no retired/valid measurement");
    auto& b = impl_->vec[s];
    VectorscopeGpuView v{};
    v.storage = {b.b, 0, b.size};
    v.sampling = impl_->vecMeta[s];
    return v;
}
void ImageScopes::recordCopyDisplayExposureStats(VkCommandBuffer c, std::uint32_t s, VkBuffer dst,
                                                 VkDeviceSize off) const {
    impl_->slot(s);
    Impl::computeToTransfer(c, impl_->exposure[s].b, impl_->exposure[s].size);
    VkBufferCopy r{0, off, sizeof(DisplayExposureStatsData)};
    vkCmdCopyBuffer(c, impl_->exposure[s].b, dst, 1, &r);
}
void ImageScopes::recordCopyDisplayWaveform(VkCommandBuffer c, std::uint32_t s, VkBuffer dst, VkDeviceSize off) const {
    impl_->slot(s);
    Impl::computeToTransfer(c, impl_->wave[s].b, impl_->wave[s].size);
    VkBufferCopy r{0, off, impl_->wave[s].size};
    vkCmdCopyBuffer(c, impl_->wave[s].b, dst, 1, &r);
}
void ImageScopes::recordCopyVectorscope(VkCommandBuffer c, std::uint32_t s, VkBuffer dst, VkDeviceSize off) const {
    impl_->slot(s);
    Impl::computeToTransfer(c, impl_->vec[s].b, impl_->vec[s].size);
    VkBufferCopy r{0, off, impl_->vec[s].size};
    vkCmdCopyBuffer(c, impl_->vec[s].b, dst, 1, &r);
}
std::uint32_t ImageScopes::maxFramesInFlight() const noexcept { return impl_ ? impl_->slots : 0; }

}  // namespace image_scopes
#endif
