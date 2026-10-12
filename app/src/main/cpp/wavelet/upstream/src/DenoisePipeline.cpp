// Production tiled DT-denoiseprofile wavelet pipeline (image-based).
//
// Per-tile flow (all inside the caller's single submit):
//   precondition tile+H -> estimate chain (atrous mode 0 + partials) ->
//   threshold reduce (per scale) -> apply chain (atrous mode 1, thrs from
//   GPU buffer) -> backtransform (folds residue) -> output inner region.
// CPU-side profile math (p, WB-adaptive matrices) is float32, mirroring the
// oracle's stage order; thresholds reduce on-GPU so record() never blocks.

#include "raw_denoise/DenoisePipeline.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <stdexcept>

namespace raw_denoise {
namespace {

// Workgroup size of the shipped shaders (LOCAL_X/LOCAL_Y defaults).
constexpr uint32_t kLocal = 16;

void check(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(what);
}

uint32_t findMemory(VkPhysicalDevice physical, uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties props{};
    vkGetPhysicalDeviceMemoryProperties(physical, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) return i;
    }
    throw std::runtime_error("denoise: no memory type");
}

struct TileImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

TileImage makeTileImage(VkPhysicalDevice physical, VkDevice device, uint32_t extent) {
    TileImage t{};
    VkImageCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    ci.extent = {extent, extent, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    check(vkCreateImage(device, &ci, nullptr, &t.image), "denoise tile image");
    VkMemoryRequirements mr{};
    vkGetImageMemoryRequirements(device, t.image, &mr);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = findMemory(physical, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(device, &ai, nullptr, &t.memory), "denoise tile memory");
    check(vkBindImageMemory(device, t.image, t.memory, 0), "denoise tile bind");
    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = t.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    check(vkCreateImageView(device, &vi, nullptr, &t.view), "denoise tile view");
    // GENERAL once; all passes use GENERAL<->GENERAL memory barriers.
    VkCommandBuffer cmd = VK_NULL_HANDLE;  // transition recorded by caller below
    (void)cmd;
    return t;
}

void freeTileImage(VkDevice device, TileImage& t) noexcept {
    if (t.view) vkDestroyImageView(device, t.view, nullptr);
    if (t.image) vkDestroyImage(device, t.image, nullptr);
    if (t.memory) vkFreeMemory(device, t.memory, nullptr);
    t = TileImage{};
}

struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
};

Buffer makeBuffer(VkPhysicalDevice physical, VkDevice device, VkDeviceSize size) {
    Buffer b{};
    b.size = size;
    VkBufferCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    ci.size = size;
    ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(device, &ci, nullptr, &b.buffer), "denoise buffer");
    VkMemoryRequirements mr{};
    vkGetBufferMemoryRequirements(device, b.buffer, &mr);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = findMemory(physical, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    check(vkAllocateMemory(device, &ai, nullptr, &b.memory), "denoise buffer memory");
    check(vkBindBufferMemory(device, b.buffer, b.memory, 0), "denoise buffer bind");
    return b;
}

void freeBuffer(VkDevice device, Buffer& b) noexcept {
    if (b.buffer) vkDestroyBuffer(device, b.buffer, nullptr);
    if (b.memory) vkFreeMemory(device, b.memory, nullptr);
    b = Buffer{};
}

// 3x3 inverse (row-major), float32. Deterministic; ulp-level differences vs
// NumPy are absorbed by the strength-scaled round-trip (verified in parity).
bool invert3(const float in[9], float out[9]) {
    const float a = in[1 * 3 + 1] * in[2 * 3 + 2] - in[1 * 3 + 2] * in[2 * 3 + 1];
    const float b = in[1 * 3 + 0] * in[2 * 3 + 2] - in[1 * 3 + 2] * in[2 * 3 + 0];
    const float c = in[1 * 3 + 0] * in[2 * 3 + 1] - in[1 * 3 + 1] * in[2 * 3 + 0];
    const float det = in[0 * 3 + 0] * a - in[0 * 3 + 1] * b + in[0 * 3 + 2] * c;
    if (det == 0.0f) return false;
    out[0 * 3 + 0] = a / det;
    out[0 * 3 + 1] = (in[0 * 3 + 2] * in[2 * 3 + 1] - in[0 * 3 + 1] * in[2 * 3 + 2]) / det;
    out[0 * 3 + 2] = (in[0 * 3 + 1] * in[1 * 3 + 2] - in[0 * 3 + 2] * in[1 * 3 + 1]) / det;
    out[1 * 3 + 0] = -b / det;
    out[1 * 3 + 1] = (in[0 * 3 + 0] * in[2 * 3 + 2] - in[0 * 3 + 2] * in[2 * 3 + 0]) / det;
    out[1 * 3 + 2] = (in[0 * 3 + 2] * in[1 * 3 + 0] - in[0 * 3 + 0] * in[1 * 3 + 2]) / det;
    out[2 * 3 + 0] = c / det;
    out[2 * 3 + 1] = (in[0 * 3 + 1] * in[2 * 3 + 0] - in[0 * 3 + 0] * in[2 * 3 + 1]) / det;
    out[2 * 3 + 2] = (in[0 * 3 + 0] * in[1 * 3 + 1] - in[0 * 3 + 1] * in[1 * 3 + 0]) / det;
    return true;
}

}  // namespace

struct DenoisePipeline::Impl {
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkShaderModule pre = VK_NULL_HANDLE, atr = VK_NULL_HANDLE, thr = VK_NULL_HANDLE,
                   back = VK_NULL_HANDLE;
    VkDescriptorSetLayout preLayout = VK_NULL_HANDLE, atrLayout = VK_NULL_HANDLE,
                          thrLayout = VK_NULL_HANDLE, backLayout = VK_NULL_HANDLE;
    VkPipelineLayout prePipeLayout = VK_NULL_HANDLE, atrPipeLayout = VK_NULL_HANDLE,
                     thrPipeLayout = VK_NULL_HANDLE, backPipeLayout = VK_NULL_HANDLE;
    VkPipeline prePipe = VK_NULL_HANDLE, atrPipe = VK_NULL_HANDLE, thrPipe = VK_NULL_HANDLE,
               backPipe = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    // Tile scratch (max size, reused across tiles/frames).
    TileImage fine{}, coarseA{}, coarseB{}, accum{};
    uint32_t scratchExtent = 0;
    Buffer partials{}, thrsBuf{};
    // Descriptor sets are written once per record(), before any dispatch uses
    // them: updating a set already bound in the recording command buffer is
    // undefined, so each a-trous scale owns its own set.
    VkDescriptorSet preSet = VK_NULL_HANDLE, atrSets[7] = {}, thrSet = VK_NULL_HANDLE,
                    backSet = VK_NULL_HANDLE;
};

namespace {

VkShaderModule makeModule(VkDevice device, DenoiseSpirv spv, const char* name) {
    if (!spv.words || !spv.wordCount) throw std::runtime_error(name);
    VkShaderModuleCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = spv.wordCount * 4;
    ci.pCode = spv.words;
    VkShaderModule m{};
    check(vkCreateShaderModule(device, &ci, nullptr, &m), name);
    return m;
}

struct BindInfo {
    uint32_t binding;
    VkDescriptorType type;
};

VkDescriptorSetLayout makeLayout(VkDevice device, std::initializer_list<BindInfo> binds,
                                 uint32_t pushSize, VkPipelineLayout* pipeLayout) {
    std::vector<VkDescriptorSetLayoutBinding> vb;
    for (const auto& b : binds) {
        VkDescriptorSetLayoutBinding v{};
        v.binding = b.binding;
        v.descriptorType = b.type;
        v.descriptorCount = 1;
        v.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        vb.push_back(v);
    }
    VkDescriptorSetLayout layout{};
    VkDescriptorSetLayoutCreateInfo li{};
    li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    li.bindingCount = (uint32_t)vb.size();
    li.pBindings = vb.data();
    check(vkCreateDescriptorSetLayout(device, &li, nullptr, &layout), "denoise set layout");
    VkPushConstantRange pr{};
    pr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pr.size = pushSize;
    VkPipelineLayoutCreateInfo pli{};
    pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &layout;
    pli.pushConstantRangeCount = 1;
    pli.pPushConstantRanges = &pr;
    check(vkCreatePipelineLayout(device, &pli, nullptr, pipeLayout), "denoise pipe layout");
    return layout;
}

VkPipeline makePipe(VkDevice device, VkShaderModule mod, VkPipelineLayout layout) {
    VkComputePipelineCreateInfo pi{};
    pi.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pi.stage.module = mod;
    pi.stage.pName = "main";
    pi.layout = layout;
    VkPipeline p{};
    check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pi, nullptr, &p), "denoise pipe");
    return p;
}

VkDescriptorSet allocSet(VkDevice device, VkDescriptorPool pool, VkDescriptorSetLayout layout) {
    VkDescriptorSetAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = pool;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &layout;
    VkDescriptorSet s{};
    check(vkAllocateDescriptorSets(device, &ai, &s), "denoise alloc set");
    return s;
}

void writeImageSet(VkDevice device, VkDescriptorSet set, uint32_t binding, VkImageView view) {
    VkDescriptorImageInfo ii{};
    ii.imageView = view;
    ii.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet w{};
    w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w.dstSet = set;
    w.dstBinding = binding;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    w.pImageInfo = &ii;
    vkUpdateDescriptorSets(device, 1, &w, 0, nullptr);
}

void writeBufferSet(VkDevice device, VkDescriptorSet set, uint32_t binding, VkBuffer buffer,
                    VkDeviceSize size) {
    VkDescriptorBufferInfo bi{};
    bi.buffer = buffer;
    bi.range = size;
    VkWriteDescriptorSet w{};
    w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    w.dstSet = set;
    w.dstBinding = binding;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    w.pBufferInfo = &bi;
    vkUpdateDescriptorSets(device, 1, &w, 0, nullptr);
}

// Orders every compute/transfer write before later compute/transfer reads and
// writes. The accum clear is a transfer op sandwiched between compute passes
// of consecutive tiles; a compute-only barrier let it race the previous
// tile's backtransform (and the next tile's first accumulate).
void barrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb{};
    mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    const VkPipelineStageFlags stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
    vkCmdPipelineBarrier(cmd, stages, stages, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

void transitionToGeneral(VkCommandBuffer cmd, VkImage image) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = 0;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &b);
}

}  // namespace

DenoisePipeline::DenoisePipeline(VkPhysicalDevice physical, VkDevice device, DenoiseShaders shaders)
    : impl_(new Impl()) {
    Impl& im = *impl_;
    im.physical = physical;
    im.device = device;
    im.pre = makeModule(device, shaders.precondition, "denoise pre");
    im.atr = makeModule(device, shaders.atrous, "denoise atrous");
    im.thr = makeModule(device, shaders.threshold, "denoise threshold");
    im.back = makeModule(device, shaders.backtransform, "denoise back");
    const auto STORAGE_IMAGE = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    const auto STORAGE_BUFFER = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    // Push sizes mirror the .comp layouts (mat3 = 48B, 16-aligned).
    im.preLayout = makeLayout(device, {{0, STORAGE_IMAGE}, {1, STORAGE_IMAGE}}, 96,
                              &im.prePipeLayout);
    im.atrLayout = makeLayout(device, {{0, STORAGE_IMAGE}, {1, STORAGE_IMAGE}, {2, STORAGE_IMAGE},
                                       {3, STORAGE_BUFFER}, {4, STORAGE_BUFFER}, {5, STORAGE_IMAGE}},
                              36, &im.atrPipeLayout);
    im.thrLayout = makeLayout(device, {{0, STORAGE_BUFFER}, {1, STORAGE_BUFFER}}, 24,
                              &im.thrPipeLayout);
    im.backLayout = makeLayout(device, {{0, STORAGE_IMAGE}, {1, STORAGE_IMAGE}, {2, STORAGE_IMAGE}},
                               96, &im.backPipeLayout);
    im.prePipe = makePipe(device, im.pre, im.prePipeLayout);
    im.atrPipe = makePipe(device, im.atr, im.atrPipeLayout);
    im.thrPipe = makePipe(device, im.thr, im.thrPipeLayout);
    im.backPipe = makePipe(device, im.back, im.backPipeLayout);
    VkDescriptorPoolSize ps{};
    ps.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    ps.descriptorCount = 2 + 4 * 7 + 3;
    VkDescriptorPoolSize ps2{};
    ps2.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    ps2.descriptorCount = 2 * 7 + 2;
    VkDescriptorPoolSize sizes[2] = {ps, ps2};
    VkDescriptorPoolCreateInfo pi{};
    pi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pi.maxSets = 1 + 7 + 1 + 1;
    pi.poolSizeCount = 2;
    pi.pPoolSizes = sizes;
    check(vkCreateDescriptorPool(device, &pi, nullptr, &im.pool), "denoise pool");
    im.preSet = allocSet(device, im.pool, im.preLayout);
    for (auto& set : im.atrSets) set = allocSet(device, im.pool, im.atrLayout);
    im.thrSet = allocSet(device, im.pool, im.thrLayout);
    im.backSet = allocSet(device, im.pool, im.backLayout);
}

DenoisePipeline::~DenoisePipeline() {
    Impl& im = *impl_;
    VkDevice device = im.device;
    if (device) {
        for (auto p : {im.prePipe, im.atrPipe, im.thrPipe, im.backPipe})
            if (p) vkDestroyPipeline(device, p, nullptr);
        for (auto l : {im.prePipeLayout, im.atrPipeLayout, im.thrPipeLayout, im.backPipeLayout})
            if (l) vkDestroyPipelineLayout(device, l, nullptr);
        for (auto s : {im.preLayout, im.atrLayout, im.thrLayout, im.backLayout})
            if (s) vkDestroyDescriptorSetLayout(device, s, nullptr);
        for (auto m : {im.pre, im.atr, im.thr, im.back})
            if (m) vkDestroyShaderModule(device, m, nullptr);
        if (im.pool) vkDestroyDescriptorPool(device, im.pool, nullptr);
        freeTileImage(device, im.fine);
        freeTileImage(device, im.coarseA);
        freeTileImage(device, im.coarseB);
        freeTileImage(device, im.accum);
        freeBuffer(device, im.partials);
        freeBuffer(device, im.thrsBuf);
    }
    delete impl_;
}


VkImage DenoisePipeline::debugFineImage() const noexcept { return impl_ ? impl_->fine.image : VK_NULL_HANDLE; }

uint32_t DenoisePipeline::debugFineExtent() const noexcept {
    if (!impl_) return 0;
    // Extent is tile + 2H for the last record() call; recompute cheaply is
    // impossible without params, so derive from the image itself is skipped:
    // callers pass the extent they configured (tileSize + 2*halo).
    return impl_->scratchExtent;
}

void DenoisePipeline::record(VkCommandBuffer command, VkImageView srcView, VkImageView dstView,
                             uint32_t width, uint32_t height, const DenoiseParams& params,
                             VkQueryPool timingPool) {
    Impl& im = *impl_;
    VkDevice device = im.device;
    const int maxScale = std::clamp(params.maxScale, 1, 7);
    const uint32_t halo = 2u * ((1u << (uint32_t)maxScale) - 1u);
    const uint32_t tile = std::max(params.tileSize, 256u);
    const uint32_t extent = tile + 2u * halo;

    // ---- CPU profile math (float32, oracle stage order) ----
    const float wb[3] = {params.whiteBalance[0], params.whiteBalance[1], params.whiteBalance[2]};
    float p[3];
    for (int i = 0; i < 3; i++) p[i] = std::max(params.shadows + 0.1f * std::log(1.0f / wb[i]), 0.0f);
    const float fulcrum = 0.05f;
    const float compensateP = fulcrum / std::pow(fulcrum, params.shadows);
    const float a = params.noiseA * compensateP;
    const float b = params.noiseB;
    // WB-adaptive Y0U0V0 rows then strength scaling (mirrors set_up_conversion_matrices).
    float m[9] = {1.0f / 3, 1.0f / 3, 1.0f / 3, 0.5f, 0.0f, -0.5f, 0.25f, -0.5f, 0.25f};
    {
        const float sumInv = (1 / wb[0] + 1 / wb[1] + 1 / wb[2]) * std::sqrt(3.0f);
        m[0] = sumInv / wb[0];
        m[1] = sumInv / wb[1];
        m[2] = sumInv / wb[2];
        const float stdU = std::sqrt(0.25f * wb[0] * wb[0] + 0.25f * wb[2] * wb[2]);
        const float stdV = std::sqrt(0.0625f * wb[0] * wb[0] + 0.25f * wb[1] * wb[1] +
                                     0.0625f * wb[2] * wb[2]);
        for (int c = 0; c < 3; c++) {
            m[3 + c] /= stdU;
            m[6 + c] /= stdV;
        }
    }
    float rgb[9];
    if (!invert3(m, rgb)) throw std::runtime_error("denoise: singular Y0U0V0 matrix");
    const float f = params.strength * 2.5f;
    float yuvM[9], rgbM[9];
    for (int i = 0; i < 9; i++) {
        yuvM[i] = m[i] / f;
        rgbM[i] = rgb[i] * f;
    }

    // ---- tile scratch (grow-once, reused) ----
    if (im.scratchExtent < extent) {
        freeTileImage(device, im.fine);
        freeTileImage(device, im.coarseA);
        freeTileImage(device, im.coarseB);
        freeTileImage(device, im.accum);
        freeBuffer(device, im.partials);
        freeBuffer(device, im.thrsBuf);
        im.fine = makeTileImage(im.physical, device, extent);
        im.coarseA = makeTileImage(im.physical, device, extent);
        im.coarseB = makeTileImage(im.physical, device, extent);
        im.accum = makeTileImage(im.physical, device, extent);
        transitionToGeneral(command, im.fine.image);
        transitionToGeneral(command, im.coarseA.image);
        transitionToGeneral(command, im.coarseB.image);
        transitionToGeneral(command, im.accum.image);
        const uint32_t gmax = (extent + 7) / 8;  // fits local sizes down to 8x8
        im.partials = makeBuffer(im.physical, device, (VkDeviceSize)gmax * gmax * 16u);
        im.thrsBuf = makeBuffer(im.physical, device, 8u * 16u);
        im.scratchExtent = extent;
    }
    // Rebind tile views (scratch may have been reallocated).
    writeImageSet(device, im.preSet, 0, srcView);
    writeImageSet(device, im.preSet, 1, im.fine.view);
    writeBufferSet(device, im.thrSet, 0, im.partials.buffer, im.partials.size);
    writeBufferSet(device, im.thrSet, 1, im.thrsBuf.buffer, im.thrsBuf.size);
    // Ping-pong alternates (fine, coarse): even scales read fine/write coarseA,
    // odd scales read coarseA/write fine (overwriting consumed
    // precondition/coarse). The detail buffer reuses the idle coarseB tile
    // image (zero extra memory): estimate writes it, apply reads it back.
    for (int scale = 0; scale < maxScale; scale++) {
        const bool even = (scale % 2) == 0;
        VkDescriptorSet set = im.atrSets[scale];
        writeImageSet(device, set, 0, even ? im.fine.view : im.coarseA.view);
        writeImageSet(device, set, 1, even ? im.coarseA.view : im.fine.view);
        writeImageSet(device, set, 2, im.accum.view);
        writeBufferSet(device, set, 3, im.partials.buffer, im.partials.size);
        writeBufferSet(device, set, 4, im.thrsBuf.buffer, im.thrsBuf.size);
        writeImageSet(device, set, 5, im.coarseB.view);
    }
    // Backtransform folds the residue: (accum, residue) -> out. Writes
    // alternate coarseA, fine, coarseA... from scale 0, so the last write
    // (scale maxScale-1) sits in (maxScale odd ? coarseA : fine).
    writeImageSet(device, im.backSet, 0, im.accum.view);
    writeImageSet(device, im.backSet, 1, (maxScale % 2 == 1) ? im.coarseA.view : im.fine.view);
    writeImageSet(device, im.backSet, 2, dstView);

    auto dispatch = [&](VkPipeline pipe, VkPipelineLayout layout, VkDescriptorSet set,
                        const void* push, uint32_t pushSize, uint32_t gx, uint32_t gy) {
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0,
                                nullptr);
        vkCmdPushConstants(command, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushSize, push);
        vkCmdDispatch(command, gx, gy, 1);
        barrier(command);
    };

    if (timingPool) vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, timingPool,
                                        10);
    const float varf = std::sqrt(2.0f + 2.0f * 16.0f + 36.0f) / 16.0f;
    for (uint32_t ty = 0; ty < height; ty += tile) {
        for (uint32_t tx = 0; tx < width; tx += tile) {
            const uint32_t tw = std::min(tile, width - tx);
            const uint32_t th = std::min(tile, height - ty);
            // Clear the accum tile (read-modify-write below). The barrier
            // after the previous tile's backtransform orders it after that read.
            {
                VkClearColorValue clear{};
                VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                vkCmdClearColorImage(command, im.accum.image, VK_IMAGE_LAYOUT_GENERAL, &clear,
                                     1, &range);
                barrier(command);
            }
            // Precondition: extended tile from source image.
            {
                struct PP {
                    uint32_t srcW, srcH;
                    int32_t ox, oy;
                    uint32_t tileE;
                    float a, p0, p1, p2, b, pad0, pad1;
                    float m[12];
                } pp{};
                pp.srcW = width;
                pp.srcH = height;
                pp.ox = (int32_t)tx - (int32_t)halo;
                pp.oy = (int32_t)ty - (int32_t)halo;
                pp.tileE = extent;
                pp.a = a;
                pp.p0 = p[0];
                pp.p1 = p[1];
                pp.p2 = p[2];
                pp.b = b;
                // GLSL mat3 is column-major: column c must hold column c of
                // the row-major yuvM, i.e. upload the transpose.
                for (int col = 0; col < 3; col++)
                    for (int row = 0; row < 3; row++) pp.m[col * 4 + row] = yuvM[row * 3 + col];
                dispatch(im.prePipe, im.prePipeLayout, im.preSet, &pp, sizeof(pp),
                         (extent + kLocal - 1u) / kLocal, (extent + kLocal - 1u) / kLocal);
            }
            // Per-scale estimate -> threshold -> apply.
            const uint32_t groupsX = (extent + kLocal - 1u) / kLocal;
            const uint32_t groupsY = groupsX;
            for (int scale = 0; scale < maxScale; scale++) {
                const float sb = std::pow(varf, (float)scale);
                const float sb2 = sb * sb;
                const uint32_t mult = 1u << (uint32_t)scale;
                const VkPipeline atrPipe = im.atrPipe;
                VkDescriptorSet atrSet = im.atrSets[scale];
                struct PA {
                    uint32_t tileE, innerW, innerH, halo;
                    uint32_t mult;
                    float inv;
                    uint32_t mode, scaleIdx, groupsX;
                } pa{extent, tw, th, halo, mult, 1.0f / sb2, 0u, (uint32_t)scale, groupsX};
                dispatch(atrPipe, im.atrPipeLayout, atrSet, &pa, sizeof(pa), groupsX, groupsY);
                {
                    struct PT {
                        uint32_t partialCount, innerPixels;
                        float sb2, forceY, forceUv;
                        uint32_t scaleIdx;
                    } pt{groupsX * groupsY, tw * th, sb2, params.forceY, params.forceUv,
                         (uint32_t)scale};
                    dispatch(im.thrPipe, im.thrPipeLayout, im.thrSet, &pt, sizeof(pt), 1, 1);
                }
                pa.mode = 1u;
                dispatch(atrPipe, im.atrPipeLayout, atrSet, &pa, sizeof(pa), groupsX, groupsY);
            }
            {
                struct PB {
                    uint32_t tileW, tileH, outX, outY, halo;
                    float a, p0, p1, p2, b, bias, pad;
                    float m[12];
                } pb{};
                pb.tileW = tw;
                pb.tileH = th;
                pb.outX = tx;
                pb.outY = ty;
                pb.halo = halo;
                pb.a = a;
                pb.p0 = p[0];
                pb.p1 = p[1];
                pb.p2 = p[2];
                pb.b = b;
                pb.bias = 0.0f;
                for (int col = 0; col < 3; col++)
                    for (int row = 0; row < 3; row++) pb.m[col * 4 + row] = rgbM[row * 3 + col];
                dispatch(im.backPipe, im.backPipeLayout, im.backSet, &pb, sizeof(pb),
                         (tw + kLocal - 1u) / kLocal, (th + kLocal - 1u) / kLocal);
            }
            if (tileBoundary_ && !(ty + tile >= height && tx + tile >= width)) tileBoundary_();
        }
    }
    if (timingPool) vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, timingPool,
                                        11);
}

}  // namespace raw_denoise
