#include "monitoring_overlays/monitoring_overlays.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <vector>

namespace monitoring_overlays {
namespace {

enum class Mode : uint32_t { Raw = 0, Focus = 1, FalseColor = 2, Shadow = 3, Count = 4 };
constexpr uint32_t modeCount = uint32_t(Mode::Count);

struct alignas(16) RawGpuParams {
    uint32_t meta[4];
    float highlight[3][4];
    float shadowWarning[3][4];
    float shadowClipped[3][4];
};
static_assert(sizeof(RawGpuParams) == 160, "raw shader ABI changed");

struct alignas(16) FocusGpuParams {
    float color[4];
    float controls[4];
    uint32_t meta[4];
};
static_assert(sizeof(FocusGpuParams) == 48, "focus shader ABI changed");

struct alignas(16) FalseColorGpuParams {
    uint32_t meta[4];
    float bounds[kMaxFalseColorRanges][4];
    float colors[kMaxFalseColorRanges][4];
};
static_assert(sizeof(FalseColorGpuParams) == 528, "false-color shader ABI changed");

struct alignas(16) ShadowGpuParams {
    uint32_t meta[4];
    float controls[4];
    float colorA[4];
    float colorB[4];
};
static_assert(sizeof(ShadowGpuParams) == 64, "tonemap-shadow shader ABI changed");

struct alignas(16) CombinedGpuParams {
    uint32_t meta0[4];
    uint32_t meta1[4];
    float focusColor[4];
    float focusControls[4];
    float highlight[3][4];
    float shadowControls[4];
    float shadowColorA[4];
    float shadowColorB[4];
    float falseBounds[kMaxFalseColorRanges][4];
    float falseColors[kMaxFalseColorRanges][4];
};
static_assert(sizeof(CombinedGpuParams) == 672, "combined shader ABI changed");

constexpr VkDeviceSize modeBufferSize(Mode mode) {
    switch (mode) {
        case Mode::Raw:
            return sizeof(RawGpuParams);
        case Mode::Focus:
            return sizeof(FocusGpuParams);
        case Mode::FalseColor:
            return sizeof(FalseColorGpuParams);
        case Mode::Shadow:
            return sizeof(ShadowGpuParams);
        default:
            return 0;
    }
}

bool finite(float v) noexcept { return std::isfinite(v); }

void setReason(const char** reason, const char* text) noexcept {
    if (reason) *reason = text;
}

bool validColor(const ColorRgba& c) noexcept {
    return finite(c.r) && finite(c.g) && finite(c.b) && finite(c.a) && c.r >= 0 && c.r <= 1 && c.g >= 0 && c.g <= 1 &&
           c.b >= 0 && c.b <= 1 && c.a >= 0 && c.a <= 1;
}

bool validFalseColor(const FalseColorParams& p) noexcept {
    if (p.rangeCount > kMaxFalseColorRanges) return false;
    float previousHigh = -1.0e30f;
    for (uint32_t i = 0; i < p.rangeCount; i++) {
        const auto& r = p.ranges[i];
        if (!finite(r.lowIre) || !finite(r.highIre) || r.lowIre >= r.highIre || !validColor(r.color)) return false;
        if (i && r.lowIre < previousHigh) return false;  // deterministic first-match semantics, no overlaps
        previousHigh = r.highIre;
    }
    return true;
}

bool validRawParams(const RawStateOverlayParams& p) noexcept {
    for (const auto& c : p.highlightSeverity)
        if (!validColor(c)) return false;
    for (const auto& c : p.shadowWarningSeverity)
        if (!validColor(c)) return false;
    for (const auto& c : p.shadowClippedSeverity)
        if (!validColor(c)) return false;
    return true;
}

bool validFocusParams(const FocusPeakingParams& p) noexcept {
    return finite(p.sensitivity) && p.sensitivity >= 0 && p.sensitivity <= 1 && validColor(p.color) &&
           finite(p.absoluteNoiseFloor) && p.absoluteNoiseFloor >= 0 && finite(p.normalizationFloor) &&
           p.normalizationFloor > 0;
}

bool validShadowParams(const TonemapShadowParams& p) noexcept {
    return finite(p.shadowsUI) && finite(p.blacksUI) && finite(p.thresholdIre) && finite(p.stripePeriod) &&
           p.shadowsUI >= -100.0f && p.shadowsUI <= 100.0f && p.blacksUI >= -100.0f && p.blacksUI <= 100.0f &&
           p.thresholdIre >= 0.0f && p.thresholdIre <= 100.0f && p.stripePeriod >= 2.0f &&
           p.stripePeriod <= 64.0f && validColor(p.stripeColorA) && validColor(p.stripeColorB);
}

bool supportedStorage(VkPhysicalDevice pd, VkFormat f) noexcept {
    VkFormatProperties props{};
    vkGetPhysicalDeviceFormatProperties(pd, f, &props);
    return (props.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
}

void vkCheck(VkResult r, const char* msg) {
    if (r != VK_SUCCESS) throw std::runtime_error(msg);
}

uint32_t findHostMemory(VkPhysicalDevice pd, uint32_t mask, VkMemoryPropertyFlags& properties) {
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (int pass = 0; pass < 2; pass++)
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
            if ((mask & (1u << i)) == 0) continue;
            auto p = mp.memoryTypes[i].propertyFlags;
            if ((p & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) continue;
            const bool coherent = (p & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            if ((pass == 0 && coherent) || (pass == 1 && !coherent)) {
                properties = p;
                return i;
            }
        }
    throw std::runtime_error("MonitoringOverlays: no host-visible memory type");
}

void copyColor(float dst[4], const ColorRgba& c) {
    dst[0] = c.r;
    dst[1] = c.g;
    dst[2] = c.b;
    dst[3] = c.a;
}

}  // namespace

struct MonitoringOverlays::Impl {
    VulkanContext context{};
    uint32_t slots = 0;
    uint32_t rawWgX = 16, rawWgY = 16;
    uint32_t focusWgX = 8, focusWgY = 8;
    uint32_t falseWgX = 16, falseWgY = 16;
    uint32_t shadowWgX = 16, shadowWgY = 16;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    std::array<VkPipeline, modeCount> pipelines{};
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> sets;
    std::vector<VkBuffer> buffers;
    std::vector<VkDeviceMemory> memories;
    std::vector<void*> mapped;
    std::vector<bool> coherent;
    uint32_t combinedWgX = 8, combinedWgY = 8;
    bool fusedCombinedExact = false;
    VkDescriptorSetLayout combinedSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout combinedPipelineLayout = VK_NULL_HANDLE;
    VkPipeline combinedPipeline = VK_NULL_HANDLE;
    VkDescriptorPool combinedDescriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> combinedSets;
    std::vector<VkBuffer> combinedBuffers;
    std::vector<VkDeviceMemory> combinedMemories;
    std::vector<void*> combinedMapped;
    std::vector<bool> combinedCoherent;

    explicit Impl(const MonitoringOverlaysCreateInfo& ci)
        : context(ci.context),
          slots(ci.maxFramesInFlight),
          rawWgX(ci.rawWorkgroupSizeX),
          rawWgY(ci.rawWorkgroupSizeY),
          focusWgX(ci.focusWorkgroupSizeX),
          focusWgY(ci.focusWorkgroupSizeY),
          falseWgX(ci.falseColorWorkgroupSizeX),
          falseWgY(ci.falseColorWorkgroupSizeY),
          shadowWgX(ci.tonemapShadowWorkgroupSizeX),
          shadowWgY(ci.tonemapShadowWorkgroupSizeY),
          combinedWgX(ci.combinedWorkgroupSizeX),
          combinedWgY(ci.combinedWorkgroupSizeY) {
        const char* why = nullptr;
        if (!MonitoringOverlays::validateCreateInfo(ci, &why))
            throw std::invalid_argument(why ? why : "MonitoringOverlays: invalid create info");
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(context.physicalDevice, &props);
        // Fused all-three equivalence depends on reproducing the implementation's
        // intermediate RGBA8 UNORM storage round-trip exactly. The optimized fused
        // path is release-qualified on the target Adreno 840 only. Other devices
        // use the standalone semantic reference sequence.
        fusedCombinedExact = (props.vendorID == 0x5143u && props.deviceID == 0x44050a31u);
        try {
            createLayout();
            createCombinedLayout();
            createPipelines(ci);
            createDescriptors();
            createCombinedDescriptors();
            createBuffers();
            createCombinedBuffers();
        } catch (...) {
            destroy();
            throw;
        }
    }
    ~Impl() { destroy(); }

    uint32_t index(Mode m, uint32_t slot) const { return uint32_t(m) * slots + slot; }

    void createLayout() {
        VkDescriptorSetLayoutBinding b[3]{};
        b[0] = {0, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        b[1] = {1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        b[2] = {2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo d{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        d.bindingCount = 3;
        d.pBindings = b;
        vkCheck(vkCreateDescriptorSetLayout(context.device, &d, context.allocator, &setLayout),
                "MonitoringOverlays: descriptor set layout creation failed");
        VkPipelineLayoutCreateInfo p{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        p.setLayoutCount = 1;
        p.pSetLayouts = &setLayout;
        vkCheck(vkCreatePipelineLayout(context.device, &p, context.allocator, &pipelineLayout),
                "MonitoringOverlays: pipeline layout creation failed");
    }

    void createCombinedLayout() {
        VkDescriptorSetLayoutBinding b[5]{};
        for (uint32_t i = 0; i < 4; i++)
            b[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        b[4] = {4, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo d{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        d.bindingCount = 5;
        d.pBindings = b;
        vkCheck(vkCreateDescriptorSetLayout(context.device, &d, context.allocator, &combinedSetLayout),
                "MonitoringOverlays: combined descriptor set layout creation failed");
        VkPipelineLayoutCreateInfo p{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        p.setLayoutCount = 1;
        p.pSetLayouts = &combinedSetLayout;
        vkCheck(vkCreatePipelineLayout(context.device, &p, context.allocator, &combinedPipelineLayout),
                "MonitoringOverlays: combined pipeline layout creation failed");
    }

    VkPipeline makePipeline(const ShaderBinary& bin, VkPipelineLayout layout) {
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        sm.codeSize = bin.byteSize;
        sm.pCode = bin.words;
        VkShaderModule module = VK_NULL_HANDLE;
        vkCheck(vkCreateShaderModule(context.device, &sm, context.allocator, &module),
                "MonitoringOverlays: shader module creation failed");
        VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = module;
        stage.pName = "main";
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage = stage;
        cp.layout = layout;
        VkPipeline out = VK_NULL_HANDLE;
        VkResult r = vkCreateComputePipelines(context.device, VK_NULL_HANDLE, 1, &cp, context.allocator, &out);
        vkDestroyShaderModule(context.device, module, context.allocator);
        vkCheck(r, "MonitoringOverlays: compute pipeline creation failed");
        return out;
    }

    void createPipelines(const MonitoringOverlaysCreateInfo& ci) {
        pipelines[uint32_t(Mode::Raw)] = makePipeline(ci.rawStateShader, pipelineLayout);
        pipelines[uint32_t(Mode::Focus)] = makePipeline(ci.focusPeakingShader, pipelineLayout);
        pipelines[uint32_t(Mode::FalseColor)] = makePipeline(ci.falseColorShader, pipelineLayout);
        pipelines[uint32_t(Mode::Shadow)] = makePipeline(ci.tonemapShadowShader, pipelineLayout);
        combinedPipeline = makePipeline(ci.combinedShader, combinedPipelineLayout);
    }

    void createDescriptors() {
        const uint32_t n = slots * modeCount;
        VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2u * n},
                                         {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, n}};
        VkDescriptorPoolCreateInfo p{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        p.maxSets = n;
        p.poolSizeCount = 2;
        p.pPoolSizes = sizes;
        vkCheck(vkCreateDescriptorPool(context.device, &p, context.allocator, &descriptorPool),
                "MonitoringOverlays: descriptor pool creation failed");
        std::vector<VkDescriptorSetLayout> layouts(n, setLayout);
        sets.resize(n);
        VkDescriptorSetAllocateInfo a{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        a.descriptorPool = descriptorPool;
        a.descriptorSetCount = n;
        a.pSetLayouts = layouts.data();
        vkCheck(vkAllocateDescriptorSets(context.device, &a, sets.data()),
                "MonitoringOverlays: descriptor allocation failed");
    }

    void createCombinedDescriptors() {
        VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4u * slots},
                                         {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, slots}};
        VkDescriptorPoolCreateInfo p{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        p.maxSets = slots;
        p.poolSizeCount = 2;
        p.pPoolSizes = sizes;
        vkCheck(vkCreateDescriptorPool(context.device, &p, context.allocator, &combinedDescriptorPool),
                "MonitoringOverlays: combined descriptor pool creation failed");
        std::vector<VkDescriptorSetLayout> layouts(slots, combinedSetLayout);
        combinedSets.resize(slots);
        VkDescriptorSetAllocateInfo a{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        a.descriptorPool = combinedDescriptorPool;
        a.descriptorSetCount = slots;
        a.pSetLayouts = layouts.data();
        vkCheck(vkAllocateDescriptorSets(context.device, &a, combinedSets.data()),
                "MonitoringOverlays: combined descriptor allocation failed");
    }

    void createBuffers() {
        const uint32_t n = slots * modeCount;
        buffers.resize(n, VK_NULL_HANDLE);
        memories.resize(n, VK_NULL_HANDLE);
        mapped.resize(n, nullptr);
        coherent.resize(n, false);
        for (uint32_t m = 0; m < modeCount; m++)
            for (uint32_t s = 0; s < slots; s++) {
                const uint32_t i = index(Mode(m), s);
                VkDeviceSize sz = modeBufferSize(Mode(m));
                VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
                bi.size = sz;
                bi.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
                vkCheck(vkCreateBuffer(context.device, &bi, context.allocator, &buffers[i]),
                        "MonitoringOverlays: uniform buffer creation failed");
                VkMemoryRequirements req{};
                vkGetBufferMemoryRequirements(context.device, buffers[i], &req);
                VkMemoryPropertyFlags props = 0;
                uint32_t mt = findHostMemory(context.physicalDevice, req.memoryTypeBits, props);
                VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
                ai.allocationSize = req.size;
                ai.memoryTypeIndex = mt;
                vkCheck(vkAllocateMemory(context.device, &ai, context.allocator, &memories[i]),
                        "MonitoringOverlays: uniform memory allocation failed");
                vkCheck(vkBindBufferMemory(context.device, buffers[i], memories[i], 0),
                        "MonitoringOverlays: uniform buffer bind failed");
                vkCheck(vkMapMemory(context.device, memories[i], 0, VK_WHOLE_SIZE, 0, &mapped[i]),
                        "MonitoringOverlays: uniform memory map failed");
                coherent[i] = (props & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            }
    }

    void createCombinedBuffers() {
        combinedBuffers.resize(slots, VK_NULL_HANDLE);
        combinedMemories.resize(slots, VK_NULL_HANDLE);
        combinedMapped.resize(slots, nullptr);
        combinedCoherent.resize(slots, false);
        for (uint32_t s = 0; s < slots; s++) {
            VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
            bi.size = sizeof(CombinedGpuParams);
            bi.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
            bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            vkCheck(vkCreateBuffer(context.device, &bi, context.allocator, &combinedBuffers[s]),
                    "MonitoringOverlays: combined uniform buffer creation failed");
            VkMemoryRequirements req{};
            vkGetBufferMemoryRequirements(context.device, combinedBuffers[s], &req);
            VkMemoryPropertyFlags props = 0;
            uint32_t mt = findHostMemory(context.physicalDevice, req.memoryTypeBits, props);
            VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            ai.allocationSize = req.size;
            ai.memoryTypeIndex = mt;
            vkCheck(vkAllocateMemory(context.device, &ai, context.allocator, &combinedMemories[s]),
                    "MonitoringOverlays: combined uniform memory allocation failed");
            vkCheck(vkBindBufferMemory(context.device, combinedBuffers[s], combinedMemories[s], 0),
                    "MonitoringOverlays: combined uniform buffer bind failed");
            vkCheck(vkMapMemory(context.device, combinedMemories[s], 0, VK_WHOLE_SIZE, 0, &combinedMapped[s]),
                    "MonitoringOverlays: combined uniform memory map failed");
            combinedCoherent[s] = (props & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        }
    }

    void flushCombined(uint32_t s) {
        if (combinedCoherent[s]) return;
        VkMappedMemoryRange r{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        r.memory = combinedMemories[s];
        r.offset = 0;
        r.size = VK_WHOLE_SIZE;
        vkCheck(vkFlushMappedMemoryRanges(context.device, 1, &r), "MonitoringOverlays: combined uniform flush failed");
    }

    void flush(uint32_t i) {
        if (coherent[i]) return;
        VkMappedMemoryRange r{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        r.memory = memories[i];
        r.offset = 0;
        r.size = VK_WHOLE_SIZE;
        vkCheck(vkFlushMappedMemoryRanges(context.device, 1, &r), "MonitoringOverlays: uniform flush failed");
    }

    void updateSet(Mode m, uint32_t slot, VkImageView input, VkImageView output) {
        uint32_t i = index(m, slot);
        VkDescriptorImageInfo in{};
        in.imageView = input;
        in.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkDescriptorImageInfo out{};
        out.imageView = output;
        out.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkDescriptorBufferInfo ub{};
        ub.buffer = buffers[i];
        ub.offset = 0;
        ub.range = modeBufferSize(m);
        VkWriteDescriptorSet w[3]{};
        for (auto& q : w) q.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[0].dstSet = sets[i];
        w[0].dstBinding = 0;
        w[0].descriptorCount = 1;
        w[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w[0].pImageInfo = &in;
        w[1].dstSet = sets[i];
        w[1].dstBinding = 1;
        w[1].descriptorCount = 1;
        w[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w[1].pImageInfo = &out;
        w[2].dstSet = sets[i];
        w[2].dstBinding = 2;
        w[2].descriptorCount = 1;
        w[2].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        w[2].pBufferInfo = &ub;
        vkUpdateDescriptorSets(context.device, 3, w, 0, nullptr);
    }

    void outputDependency(VkCommandBuffer cmd) {
        VkMemoryBarrier b{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &b,
                             0, nullptr, 0, nullptr);
    }

    void dispatch(VkCommandBuffer cmd, Mode m, uint32_t slot, uint32_t w, uint32_t h, bool compose) {
        if (compose) outputDependency(cmd);
        VkPipeline p = pipelines[uint32_t(m)];
        VkDescriptorSet set = sets[index(m, slot)];
        uint32_t wgX = 16, wgY = 16;
        if (m == Mode::Focus) {
            wgX = focusWgX;
            wgY = focusWgY;
        } else if (m == Mode::Raw) {
            wgX = rawWgX;
            wgY = rawWgY;
        } else if (m == Mode::Shadow) {
            wgX = shadowWgX;
            wgY = shadowWgY;
        } else {
            wgX = falseWgX;
            wgY = falseWgY;
        }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, p);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, 1, &set, 0, nullptr);
        vkCmdDispatch(cmd, (w + wgX - 1) / wgX, (h + wgY - 1) / wgY, 1);
    }

    void writeRaw(uint32_t slot, const RawStateRecordInfo& ri) {
        RawGpuParams g{};
        g.meta[0] = (ri.params.highlightEnabled ? 1u : 0u) | (ri.params.shadowWarningEnabled ? 2u : 0u) |
                    (ri.params.shadowClippedEnabled ? 4u : 0u);
        g.meta[1] = ri.input.width;
        g.meta[2] = ri.input.height;
        g.meta[3] = ri.composeOverExisting ? 1u : 0u;
        for (uint32_t i = 0; i < 3; i++) {
            copyColor(g.highlight[i], ri.params.highlightSeverity[i]);
            copyColor(g.shadowWarning[i], ri.params.shadowWarningSeverity[i]);
            copyColor(g.shadowClipped[i], ri.params.shadowClippedSeverity[i]);
        }
        uint32_t n = index(Mode::Raw, slot);
        std::memcpy(mapped[n], &g, sizeof(g));
        flush(n);
    }
    void writeFocus(uint32_t slot, const FocusPeakingRecordInfo& ri) {
        FocusGpuParams g{};
        copyColor(g.color, ri.params.color);
        g.controls[0] = ri.params.sensitivity;
        g.controls[1] = ri.params.absoluteNoiseFloor;
        g.controls[2] = ri.params.normalizationFloor;
        g.meta[0] = ri.input.width;
        g.meta[1] = ri.input.height;
        g.meta[2] = ri.composeOverExisting ? 1u : 0u;
        g.meta[3] = ri.params.enabled ? 1u : 0u;
        uint32_t n = index(Mode::Focus, slot);
        std::memcpy(mapped[n], &g, sizeof(g));
        flush(n);
    }
    void writeFalse(uint32_t slot, const FalseColorRecordInfo& ri) {
        FalseColorGpuParams g{};
        g.meta[0] = ri.params.rangeCount;
        g.meta[1] = ri.input.width;
        g.meta[2] = ri.input.height;
        g.meta[3] = ri.composeOverExisting ? 1u : 0u;
        for (uint32_t i = 0; i < ri.params.rangeCount; i++) {
            g.bounds[i][0] = ri.params.ranges[i].lowIre;
            g.bounds[i][1] = ri.params.ranges[i].highIre;
            copyColor(g.colors[i], ri.params.ranges[i].color);
        }
        uint32_t n = index(Mode::FalseColor, slot);
        std::memcpy(mapped[n], &g, sizeof(g));
        flush(n);
    }
    void writeShadow(uint32_t slot, const TonemapShadowRecordInfo& ri) {
        ShadowGpuParams g{};
        g.meta[0] = ri.params.enabled ? 1u : 0u;
        g.meta[1] = ri.input.width;
        g.meta[2] = ri.input.height;
        g.meta[3] = ri.composeOverExisting ? 1u : 0u;
        g.controls[0] = ri.params.shadowsUI;
        g.controls[1] = ri.params.blacksUI;
        g.controls[2] = ri.params.thresholdIre;
        g.controls[3] = ri.params.stripePeriod;
        copyColor(g.colorA, ri.params.stripeColorA);
        copyColor(g.colorB, ri.params.stripeColorB);
        uint32_t n = index(Mode::Shadow, slot);
        std::memcpy(mapped[n], &g, sizeof(g));
        flush(n);
    }

    void writeCombined(uint32_t slot, const CombinedRecordInfo& r) {
        CombinedGpuParams g{};
        g.meta0[0] = (r.rawParams.highlightEnabled ? 1u : 0u);
        g.meta0[1] = r.output.width;
        g.meta0[2] = r.output.height;
        g.meta0[3] = r.falseColorParams.rangeCount;
        g.meta1[0] = r.focusParams.enabled ? 1u : 0u;
        g.meta1[1] = r.falseColorParams.enabled ? 1u : 0u;
        g.meta1[2] = r.tonemapShadowParams.enabled ? 1u : 0u;
        copyColor(g.focusColor, r.focusParams.color);
        g.focusControls[0] = r.focusParams.sensitivity;
        g.focusControls[1] = r.focusParams.absoluteNoiseFloor;
        g.focusControls[2] = r.focusParams.normalizationFloor;
        for (uint32_t i = 0; i < 3; i++) {
            copyColor(g.highlight[i], r.rawParams.highlightSeverity[i]);
        }
        g.shadowControls[0] = r.tonemapShadowParams.shadowsUI;
        g.shadowControls[1] = r.tonemapShadowParams.blacksUI;
        g.shadowControls[2] = r.tonemapShadowParams.thresholdIre;
        g.shadowControls[3] = r.tonemapShadowParams.stripePeriod;
        copyColor(g.shadowColorA, r.tonemapShadowParams.stripeColorA);
        copyColor(g.shadowColorB, r.tonemapShadowParams.stripeColorB);
        for (uint32_t i = 0; i < r.falseColorParams.rangeCount; i++) {
            g.falseBounds[i][0] = r.falseColorParams.ranges[i].lowIre;
            g.falseBounds[i][1] = r.falseColorParams.ranges[i].highIre;
            copyColor(g.falseColors[i], r.falseColorParams.ranges[i].color);
        }
        std::memcpy(combinedMapped[slot], &g, sizeof(g));
        flushCombined(slot);
    }

    void updateCombinedSet(uint32_t slot, const CombinedRecordInfo& r) {
        VkDescriptorImageInfo images[4]{};
        images[0].imageView = r.rawState.view;
        images[0].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        // Focus now shares the display (tonemapped) image with false-color and
        // tonemap-shadow. Bind the same view to slots 1 and 2.
        images[1].imageView = r.display.view;
        images[1].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        images[2].imageView = r.display.view;
        images[2].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        images[3].imageView = r.output.view;
        images[3].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkDescriptorBufferInfo ub{};
        ub.buffer = combinedBuffers[slot];
        ub.offset = 0;
        ub.range = sizeof(CombinedGpuParams);
        VkWriteDescriptorSet w[5]{};
        for (uint32_t i = 0; i < 4; i++) {
            w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            w[i].dstSet = combinedSets[slot];
            w[i].dstBinding = i;
            w[i].descriptorCount = 1;
            w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            w[i].pImageInfo = &images[i];
        }
        w[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[4].dstSet = combinedSets[slot];
        w[4].dstBinding = 4;
        w[4].descriptorCount = 1;
        w[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        w[4].pBufferInfo = &ub;
        vkUpdateDescriptorSets(context.device, 5, w, 0, nullptr);
    }

    void dispatchCombined(VkCommandBuffer cmd, uint32_t slot, uint32_t w, uint32_t h) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, combinedPipeline);
        VkDescriptorSet set = combinedSets[slot];
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, combinedPipelineLayout, 0, 1, &set, 0, nullptr);
        vkCmdDispatch(cmd, (w + combinedWgX - 1) / combinedWgX, (h + combinedWgY - 1) / combinedWgY, 1);
    }

    void destroy() {
        if (context.device == VK_NULL_HANDLE) return;
        for (size_t i = 0; i < combinedMapped.size(); i++)
            if (combinedMapped[i] && combinedMemories[i]) vkUnmapMemory(context.device, combinedMemories[i]);
        for (auto b : combinedBuffers)
            if (b) vkDestroyBuffer(context.device, b, context.allocator);
        for (auto m : combinedMemories)
            if (m) vkFreeMemory(context.device, m, context.allocator);
        if (combinedDescriptorPool) vkDestroyDescriptorPool(context.device, combinedDescriptorPool, context.allocator);
        if (combinedPipeline) vkDestroyPipeline(context.device, combinedPipeline, context.allocator);
        if (combinedPipelineLayout) vkDestroyPipelineLayout(context.device, combinedPipelineLayout, context.allocator);
        if (combinedSetLayout) vkDestroyDescriptorSetLayout(context.device, combinedSetLayout, context.allocator);
        for (size_t i = 0; i < mapped.size(); i++)
            if (mapped[i] && memories[i]) vkUnmapMemory(context.device, memories[i]);
        for (auto b : buffers)
            if (b) vkDestroyBuffer(context.device, b, context.allocator);
        for (auto m : memories)
            if (m) vkFreeMemory(context.device, m, context.allocator);
        if (descriptorPool) vkDestroyDescriptorPool(context.device, descriptorPool, context.allocator);
        for (auto p : pipelines)
            if (p) vkDestroyPipeline(context.device, p, context.allocator);
        if (pipelineLayout) vkDestroyPipelineLayout(context.device, pipelineLayout, context.allocator);
        if (setLayout) vkDestroyDescriptorSetLayout(context.device, setLayout, context.allocator);
    }
};

bool MonitoringOverlays::validateCreateInfo(const MonitoringOverlaysCreateInfo& ci, const char** reason) noexcept {
    if (ci.context.physicalDevice == VK_NULL_HANDLE || ci.context.device == VK_NULL_HANDLE) {
        setReason(reason, "MonitoringOverlays: Vulkan physicalDevice/device required");
        return false;
    }
    if (ci.maxFramesInFlight == 0) {
        setReason(reason, "MonitoringOverlays: maxFramesInFlight must be > 0");
        return false;
    }
    if (ci.rawWorkgroupSizeX != 16 || ci.rawWorkgroupSizeY != 16 || ci.focusWorkgroupSizeX != 8 ||
        ci.focusWorkgroupSizeY != 8 || ci.falseColorWorkgroupSizeX != 16 || ci.falseColorWorkgroupSizeY != 16 ||
        ci.tonemapShadowWorkgroupSizeX != 16 || ci.tonemapShadowWorkgroupSizeY != 16 ||
        ci.combinedWorkgroupSizeX != 8 || ci.combinedWorkgroupSizeY != 8) {
        setReason(reason,
                  "MonitoringOverlays 1.1.0: validated shaders require RAW 16x16, focus 8x8, false-color 16x16, "
                  "tonemap-shadow 16x16, combined 8x8 workgroups");
        return false;
    }
    const ShaderBinary bins[5] = {ci.rawStateShader, ci.focusPeakingShader, ci.falseColorShader,
                                  ci.tonemapShadowShader, ci.combinedShader};
    for (auto& b : bins)
        if (!b.words || b.byteSize < 4 || (b.byteSize % 4) != 0) {
            setReason(reason, "MonitoringOverlays: all five valid SPIR-V binaries are required");
            return false;
        }
    if (!supportedStorage(ci.context.physicalDevice, VK_FORMAT_R16_UINT) ||
        !supportedStorage(ci.context.physicalDevice, VK_FORMAT_R8G8B8A8_UNORM)) {
        setReason(reason, "MonitoringOverlays: required storage-image format unsupported");
        return false;
    }
    return true;
}

static bool commonRecord(VkCommandBuffer cmd, uint32_t slot, uint32_t max, uint32_t iw, uint32_t ih, uint32_t ow,
                         uint32_t oh, const char** reason) noexcept {
    if (cmd == VK_NULL_HANDLE) {
        setReason(reason, "MonitoringOverlays: commandBuffer required");
        return false;
    }
    if (slot >= max) {
        setReason(reason, "MonitoringOverlays: frameSlot out of range");
        return false;
    }
    if (iw == 0 || ih == 0 || iw != ow || ih != oh) {
        setReason(reason, "MonitoringOverlays: nonzero matching input/output extents required");
        return false;
    }
    return true;
}

bool MonitoringOverlays::validateRecordInfo(const RawStateRecordInfo& r, uint32_t max, const char** reason) noexcept {
    if (!commonRecord(r.commandBuffer, r.frameSlot, max, r.input.width, r.input.height, r.output.width, r.output.height,
                      reason))
        return false;
    if (r.input.view == VK_NULL_HANDLE || r.output.view == VK_NULL_HANDLE || r.input.format != VK_FORMAT_R16_UINT ||
        r.output.format != VK_FORMAT_R8G8B8A8_UNORM || r.input.layout != VK_IMAGE_LAYOUT_GENERAL ||
        r.output.layout != VK_IMAGE_LAYOUT_GENERAL) {
        setReason(reason, "MonitoringOverlays: invalid RAW-state image contract");
        return false;
    }
    if (!validRawParams(r.params)) {
        setReason(reason, "MonitoringOverlays: invalid RAW-state parameters");
        return false;
    }
    return true;
}
bool MonitoringOverlays::validateRecordInfo(const FocusPeakingRecordInfo& r, uint32_t max,
                                            const char** reason) noexcept {
    if (!commonRecord(r.commandBuffer, r.frameSlot, max, r.input.width, r.input.height, r.output.width, r.output.height,
                      reason))
        return false;
    if (r.input.view == VK_NULL_HANDLE || r.output.view == VK_NULL_HANDLE ||
        r.input.format != VK_FORMAT_R8G8B8A8_UNORM || r.output.format != VK_FORMAT_R8G8B8A8_UNORM ||
        r.input.layout != VK_IMAGE_LAYOUT_GENERAL || r.output.layout != VK_IMAGE_LAYOUT_GENERAL) {
        setReason(reason, "MonitoringOverlays: invalid focus image contract (display-referred RGBA8 required)");
        return false;
    }
    if (!validFocusParams(r.params)) {
        setReason(reason, "MonitoringOverlays: invalid focus parameters");
        return false;
    }
    return true;
}
bool MonitoringOverlays::validateRecordInfo(const FalseColorRecordInfo& r, uint32_t max, const char** reason) noexcept {
    if (!commonRecord(r.commandBuffer, r.frameSlot, max, r.input.width, r.input.height, r.output.width, r.output.height,
                      reason))
        return false;
    if (r.input.view == VK_NULL_HANDLE || r.output.view == VK_NULL_HANDLE ||
        r.input.format != VK_FORMAT_R8G8B8A8_UNORM || r.output.format != VK_FORMAT_R8G8B8A8_UNORM ||
        r.input.layout != VK_IMAGE_LAYOUT_GENERAL || r.output.layout != VK_IMAGE_LAYOUT_GENERAL) {
        setReason(reason, "MonitoringOverlays: invalid false-color image contract");
        return false;
    }
    if (!validFalseColor(r.params)) {
        setReason(reason, "MonitoringOverlays: invalid false-color parameters");
        return false;
    }
    return true;
}
bool MonitoringOverlays::validateRecordInfo(const TonemapShadowRecordInfo& r, uint32_t max,
                                            const char** reason) noexcept {
    if (!commonRecord(r.commandBuffer, r.frameSlot, max, r.input.width, r.input.height, r.output.width, r.output.height,
                      reason))
        return false;
    if (r.input.view == VK_NULL_HANDLE || r.output.view == VK_NULL_HANDLE ||
        r.input.format != VK_FORMAT_R8G8B8A8_UNORM || r.output.format != VK_FORMAT_R8G8B8A8_UNORM ||
        r.input.layout != VK_IMAGE_LAYOUT_GENERAL || r.output.layout != VK_IMAGE_LAYOUT_GENERAL) {
        setReason(reason, "MonitoringOverlays: invalid tonemap-shadow image contract");
        return false;
    }
    if (!validShadowParams(r.params)) {
        setReason(reason, "MonitoringOverlays: invalid tonemap-shadow parameters");
        return false;
    }
    return true;
}
bool MonitoringOverlays::validateRecordInfo(const CombinedRecordInfo& r, uint32_t max, const char** reason) noexcept {
    if (r.commandBuffer == VK_NULL_HANDLE || r.frameSlot >= max) {
        setReason(reason, "MonitoringOverlays: invalid combined commandBuffer/frameSlot");
        return false;
    }
    if (r.output.view == VK_NULL_HANDLE || r.output.format != VK_FORMAT_R8G8B8A8_UNORM ||
        r.output.layout != VK_IMAGE_LAYOUT_GENERAL || r.output.width == 0 || r.output.height == 0) {
        setReason(reason, "MonitoringOverlays: invalid combined output");
        return false;
    }
    // Combined uses RAW highlights only; RAW shadow flags are standalone-legacy
    // and intentionally ignored here in favor of the tonemap-shadow zebra.
    if (r.rawParams.highlightEnabled &&
        (r.rawState.view == VK_NULL_HANDLE || r.rawState.format != VK_FORMAT_R16_UINT ||
         r.rawState.layout != VK_IMAGE_LAYOUT_GENERAL || r.rawState.width != r.output.width ||
         r.rawState.height != r.output.height || !validRawParams(r.rawParams))) {
        setReason(reason, "MonitoringOverlays: invalid enabled combined RAW mode");
        return false;
    }
    if (r.focusParams.enabled &&
        (r.display.view == VK_NULL_HANDLE || r.display.format != VK_FORMAT_R8G8B8A8_UNORM ||
         r.display.layout != VK_IMAGE_LAYOUT_GENERAL || r.display.width != r.output.width ||
         r.display.height != r.output.height || !validFocusParams(r.focusParams))) {
        setReason(reason, "MonitoringOverlays: invalid enabled combined focus mode");
        return false;
    }
    if (r.falseColorParams.enabled &&
        (r.display.view == VK_NULL_HANDLE || r.display.format != VK_FORMAT_R8G8B8A8_UNORM ||
         r.display.layout != VK_IMAGE_LAYOUT_GENERAL || r.display.width != r.output.width ||
         r.display.height != r.output.height || !validFalseColor(r.falseColorParams))) {
        setReason(reason, "MonitoringOverlays: invalid enabled combined false-color mode");
        return false;
    }
    if (r.tonemapShadowParams.enabled &&
        (r.display.view == VK_NULL_HANDLE || r.display.format != VK_FORMAT_R8G8B8A8_UNORM ||
         r.display.layout != VK_IMAGE_LAYOUT_GENERAL || r.display.width != r.output.width ||
         r.display.height != r.output.height || !validShadowParams(r.tonemapShadowParams))) {
        setReason(reason, "MonitoringOverlays: invalid enabled combined tonemap-shadow mode");
        return false;
    }
    return true;
}

MonitoringOverlays::MonitoringOverlays(const MonitoringOverlaysCreateInfo& ci) : impl_(std::make_unique<Impl>(ci)) {}
MonitoringOverlays::~MonitoringOverlays() = default;
uint32_t MonitoringOverlays::maxFramesInFlight() const noexcept { return impl_->slots; }

void MonitoringOverlays::recordRawStateOverlay(const RawStateRecordInfo& r) {
    const char* why = nullptr;
    if (!validateRecordInfo(r, impl_->slots, &why)) throw std::invalid_argument(why);
    if (!r.params.highlightEnabled && !r.params.shadowWarningEnabled && !r.params.shadowClippedEnabled) return;
    impl_->writeRaw(r.frameSlot, r);
    impl_->updateSet(Mode::Raw, r.frameSlot, r.input.view, r.output.view);
    impl_->dispatch(r.commandBuffer, Mode::Raw, r.frameSlot, r.input.width, r.input.height, r.composeOverExisting);
}
void MonitoringOverlays::recordFocusPeaking(const FocusPeakingRecordInfo& r) {
    const char* why = nullptr;
    if (!validateRecordInfo(r, impl_->slots, &why)) throw std::invalid_argument(why);
    if (!r.params.enabled) return;
    impl_->writeFocus(r.frameSlot, r);
    impl_->updateSet(Mode::Focus, r.frameSlot, r.input.view, r.output.view);
    impl_->dispatch(r.commandBuffer, Mode::Focus, r.frameSlot, r.input.width, r.input.height, r.composeOverExisting);
}
void MonitoringOverlays::recordFalseColor(const FalseColorRecordInfo& r) {
    const char* why = nullptr;
    if (!validateRecordInfo(r, impl_->slots, &why)) throw std::invalid_argument(why);
    if (!r.params.enabled) return;
    impl_->writeFalse(r.frameSlot, r);
    impl_->updateSet(Mode::FalseColor, r.frameSlot, r.input.view, r.output.view);
    impl_->dispatch(r.commandBuffer, Mode::FalseColor, r.frameSlot, r.input.width, r.input.height,
                    r.composeOverExisting);
}
void MonitoringOverlays::recordTonemapShadow(const TonemapShadowRecordInfo& r) {
    const char* why = nullptr;
    if (!validateRecordInfo(r, impl_->slots, &why)) throw std::invalid_argument(why);
    if (!r.params.enabled) return;
    impl_->writeShadow(r.frameSlot, r);
    impl_->updateSet(Mode::Shadow, r.frameSlot, r.input.view, r.output.view);
    impl_->dispatch(r.commandBuffer, Mode::Shadow, r.frameSlot, r.input.width, r.input.height,
                    r.composeOverExisting);
}

void MonitoringOverlays::recordCombined(const CombinedRecordInfo& r) {
    const char* why = nullptr;
    if (!validateRecordInfo(r, impl_->slots, &why)) throw std::invalid_argument(why);
    const bool rawOn = r.rawParams.highlightEnabled;
    const bool focusOn = r.focusParams.enabled;
    const bool falseOn = r.falseColorParams.enabled;
    const bool shadowOn = r.tonemapShadowParams.enabled;
    const uint32_t count = uint32_t(rawOn) + uint32_t(focusOn) + uint32_t(falseOn) + uint32_t(shadowOn);
    if (count == 0) return;
    if (count < 4 || !impl_->fusedCombinedExact) {
        // Resource-safe mode-independent contract: do not bind or read a disabled
        // mode's source image. Partial combinations always use the validated
        // standalone sequence in false -> focus -> shadow -> RAW order.
        // All-four also falls back to that sequence on devices where fused RGBA8
        // round-trip equivalence is not release-qualified.
        bool have = false;
        if (falseOn) {
            FalseColorRecordInfo x{};
            x.commandBuffer = r.commandBuffer;
            x.input = r.display;
            x.output = r.output;
            x.params = r.falseColorParams;
            x.frameSlot = r.frameSlot;
            x.composeOverExisting = false;
            recordFalseColor(x);
            have = true;
        }
        if (focusOn) {
            FocusPeakingRecordInfo x{};
            x.commandBuffer = r.commandBuffer;
            x.input = r.display;
            x.output = r.output;
            x.params = r.focusParams;
            x.frameSlot = r.frameSlot;
            x.composeOverExisting = have;
            recordFocusPeaking(x);
            have = true;
        }
        if (shadowOn) {
            TonemapShadowRecordInfo x{};
            x.commandBuffer = r.commandBuffer;
            x.input = r.display;
            x.output = r.output;
            x.params = r.tonemapShadowParams;
            x.frameSlot = r.frameSlot;
            x.composeOverExisting = have;
            recordTonemapShadow(x);
            have = true;
        }
        if (rawOn) {
            RawStateRecordInfo x{};
            x.commandBuffer = r.commandBuffer;
            x.input = r.rawState;
            x.output = r.output;
            x.params = r.rawParams;
            x.frameSlot = r.frameSlot;
            x.composeOverExisting = have;
            recordRawStateOverlay(x);
        }
        return;
    }
    impl_->writeCombined(r.frameSlot, r);
    impl_->updateCombinedSet(r.frameSlot, r);
    impl_->dispatchCombined(r.commandBuffer, r.frameSlot, r.output.width, r.output.height);
}

}  // namespace monitoring_overlays
