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

TileImage makeTileImage(VkPhysicalDevice physical, VkDevice device, uint32_t extent,
                        VkFormat format = VK_FORMAT_R16G16B16A16_SFLOAT) {
    TileImage t{};
    VkImageCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = format;
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
    vi.format = format;
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

Buffer makeHostBuffer(VkPhysicalDevice physical, VkDevice device, VkDeviceSize size, void** mapped) {
    Buffer b{};
    b.size = size;
    VkBufferCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    ci.size = size;
    ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    check(vkCreateBuffer(device, &ci, nullptr, &b.buffer), "denoise host buffer");
    VkMemoryRequirements mr{};
    vkGetBufferMemoryRequirements(device, b.buffer, &mr);
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = findMemory(physical, mr.memoryTypeBits,
                                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    check(vkAllocateMemory(device, &ai, nullptr, &b.memory), "denoise host buffer memory");
    check(vkBindBufferMemory(device, b.buffer, b.memory, 0), "denoise host buffer bind");
    check(vkMapMemory(device, b.memory, 0, size, 0, mapped), "denoise host buffer map");
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

// RawLens step 2: exact unbiased inverse of the shadow chain, per channel.
// Raw z ~ N(x, A x + B) clipped at 0 (photo_rcd_input), RCD (mean kept,
// variance x kappa_c, flat-field oracle), then the generalized-Anscombe VST
// with max(y+b,0). y = E[f(RCD out)] for scene level x, inverted onto a
// uniform y grid of kLutN. Mirrors exact_inverse_luts() in
// tools/wavelet-parity/rawlens_denoise_proto.py (fixed 97-point quadrature).
constexpr uint32_t kLutN = 1024;

void buildInverseLuts(float* out, double a, double b, const float p[3], double A, double B) {
    static const double kappa[3] = {0.82, 0.71, 0.84};
    constexpr int kX = 2999, kQ = 97;
    std::vector<double> xs(kX), m(kX), v(kX), F(kX);
    for (int i = 0; i < 1500; i++) xs[i] = 0.02 * i / 1499.0;
    for (int i = 1; i < 1500; i++) xs[1499 + i] = 0.02 + (1.5 - 0.02) * i / 1499.0;
    double qz[kQ], qw[kQ], qs = 0.0;
    for (int k = 0; k < kQ; k++) {
        qz[k] = -6.0 + 0.125 * k;
        qw[k] = std::exp(-0.5 * qz[k] * qz[k]);
        qs += qw[k];
    }
    for (int k = 0; k < kQ; k++) qw[k] /= qs;
    const double invSqrt2 = 0.70710678118654752440, invSqrt2Pi = 0.39894228040143267794;
    for (int i = 0; i < kX; i++) {
        const double x = xs[i], sd = std::sqrt(std::max(A * x + B, 1e-14)), t = x / sd;
        const double cdf = 0.5 * std::erfc(-t * invSqrt2), pdf = invSqrt2Pi * std::exp(-0.5 * t * t);
        m[i] = x * cdf + sd * pdf;
        const double m2 = (x * x + sd * sd) * cdf + x * sd * pdf;
        v[i] = std::max(m2 - m[i] * m[i], 1e-14);
    }
    for (int c = 0; c < 3; c++) {
        const double pc = p[c], e = 1.0 - pc / 2.0, sc = 2.0 / ((2.0 - pc) * std::sqrt(a));
        double run = -1e300;
        for (int i = 0; i < kX; i++) {
            const double s = std::sqrt(kappa[c] * v[i]);
            double acc = 0.0;
            for (int k = 0; k < kQ; k++) {
                const double y = std::max(m[i] + s * qz[k], 0.0);
                acc += qw[k] * std::pow(std::max(y + b, 0.0), e) * sc;
            }
            run = std::max(run, acc);  // monotone
            F[i] = run;
        }
        const double y0 = F[0], dy = (F[kX - 1] - y0) / (kLutN - 1);
        out[c] = (float)y0;
        out[3 + c] = (float)dy;
        float* tab = out + 8 + c * kLutN;
        int j = 0;
        for (uint32_t n = 0; n < kLutN; n++) {  // np.interp(yg, F, xs)
            const double yg = y0 + dy * n;
            while (j < kX - 2 && F[j + 1] < yg) j++;
            const double f0 = F[j], f1 = F[j + 1];
            double val;
            if (yg <= F[0]) val = xs[0];
            else if (yg >= F[kX - 1]) val = xs[kX - 1];
            else val = f1 > f0 ? xs[j] + (yg - f0) / (f1 - f0) * (xs[j + 1] - xs[j]) : xs[j + 1];
            tab[n] = (float)val;
        }
    }
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
    // Optional buffer-output backtransform (recordToBuffer).
    VkShaderModule backBuf = VK_NULL_HANDLE;
    VkDescriptorSetLayout backBufLayout = VK_NULL_HANDLE;
    VkPipelineLayout backBufPipeLayout = VK_NULL_HANDLE;
    VkPipeline backBufPipe = VK_NULL_HANDLE;
    // Optional RawLens Wiener apply (shares the atrous layout and sets).
    VkShaderModule wap = VK_NULL_HANDLE;
    VkPipeline wapPipe = VK_NULL_HANDLE;
    VkDescriptorSet backBufSet = VK_NULL_HANDLE;
    VkBuffer outBuffer = VK_NULL_HANDLE;
    VkDeviceSize outSize = 0;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    // Tile scratch (max size, reused across tiles/frames).
    TileImage fine{}, coarseA{}, coarseB{}, accum{};
    uint32_t scratchExtent = 0;
    Buffer partials{}, thrsBuf{};
    // RawLens steps 1-2: low-res chroma pipeline + images, inverse-VST tables.
    VkShaderModule lrc = VK_NULL_HANDLE;
    VkDescriptorSetLayout lrcLayout = VK_NULL_HANDLE;
    VkPipelineLayout lrcPipeLayout = VK_NULL_HANDLE;
    VkPipeline lrcPipe = VK_NULL_HANDLE;
    VkDescriptorSet lrcSet = VK_NULL_HANDLE;
    VkShaderModule preRl = VK_NULL_HANDLE, backBufRl = VK_NULL_HANDLE;
    VkPipeline preRlPipe = VK_NULL_HANDLE, backBufRlPipe = VK_NULL_HANDLE;
    VkDescriptorSetLayout backBufRlLayout = VK_NULL_HANDLE;
    VkPipelineLayout backBufRlPipeLayout = VK_NULL_HANDLE;
    VkDescriptorSet backBufRlSet = VK_NULL_HANDLE;
    TileImage lrY{}, lrAB{}, lrD{}, lrDummy{};
    uint32_t lrExtent = 0;
    Buffer lut{};
    float* lutMapped = nullptr;
    float lutKey[7] = {};
    bool lutValid = false;
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
                              68, &im.atrPipeLayout);
    im.thrLayout = makeLayout(device, {{0, STORAGE_BUFFER}, {1, STORAGE_BUFFER}}, 36,
                              &im.thrPipeLayout);
    im.backLayout = makeLayout(device, {{0, STORAGE_IMAGE}, {1, STORAGE_IMAGE}, {2, STORAGE_IMAGE}},
                               96, &im.backPipeLayout);
    im.prePipe = makePipe(device, im.pre, im.prePipeLayout);
    im.atrPipe = makePipe(device, im.atr, im.atrPipeLayout);
    im.thrPipe = makePipe(device, im.thr, im.thrPipeLayout);
    im.backPipe = makePipe(device, im.back, im.backPipeLayout);
    // Exact per-set counts (MoltenVK does not enforce them, Mali does):
    // images: pre 2 + atrous 4 x 7 + back 3 + backBuf 2 + backBufRl 3 + lowres 5;
    // buffers: atrous 2 x 7 + threshold 2 + backBuf 1 + backBufRl 2.
    VkDescriptorPoolSize ps{};
    ps.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    ps.descriptorCount = 2 + 4 * 7 + 3 + 2 + 3 + 5;
    VkDescriptorPoolSize ps2{};
    ps2.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    ps2.descriptorCount = 2 * 7 + 2 + 1 + 2;
    VkDescriptorPoolSize sizes[2] = {ps, ps2};
    VkDescriptorPoolCreateInfo pi{};
    pi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pi.maxSets = 1 + 7 + 1 + 1 + 1 + 1 + 1;
    pi.poolSizeCount = 2;
    pi.pPoolSizes = sizes;
    check(vkCreateDescriptorPool(device, &pi, nullptr, &im.pool), "denoise pool");
    im.preSet = allocSet(device, im.pool, im.preLayout);
    for (auto& set : im.atrSets) set = allocSet(device, im.pool, im.atrLayout);
    im.thrSet = allocSet(device, im.pool, im.thrLayout);
    im.backSet = allocSet(device, im.pool, im.backLayout);
    if (shaders.wienerApply.words) {
        im.wap = makeModule(device, shaders.wienerApply, "denoise wiener apply");
        im.wapPipe = makePipe(device, im.wap, im.atrPipeLayout);
    }
    if (shaders.backtransformBuffer.words) {
        im.backBuf = makeModule(device, shaders.backtransformBuffer, "denoise back buffer");
        im.backBufLayout = makeLayout(device, {{0, STORAGE_IMAGE}, {1, STORAGE_IMAGE}, {2, STORAGE_BUFFER}},
                                      96, &im.backBufPipeLayout);
        im.backBufPipe = makePipe(device, im.backBuf, im.backBufPipeLayout);
        im.backBufSet = allocSet(device, im.pool, im.backBufLayout);
    }
    if (shaders.backtransformBufferRawLens.words) {
        // + 3 low-res chroma correction, 4 inverse-VST tables; push +24 B.
        im.backBufRl = makeModule(device, shaders.backtransformBufferRawLens, "denoise back buffer rl");
        im.backBufRlLayout = makeLayout(device, {{0, STORAGE_IMAGE}, {1, STORAGE_IMAGE}, {2, STORAGE_BUFFER},
                                                 {3, STORAGE_IMAGE}, {4, STORAGE_BUFFER}},
                                        120, &im.backBufRlPipeLayout);
        im.backBufRlPipe = makePipe(device, im.backBufRl, im.backBufRlPipeLayout);
        im.backBufRlSet = allocSet(device, im.pool, im.backBufRlLayout);
        void* mapped = nullptr;
        im.lut = makeHostBuffer(physical, device, (8u + 3u * kLutN) * sizeof(float), &mapped);
        im.lutMapped = static_cast<float*>(mapped);
        std::memset(im.lutMapped, 0, (size_t)im.lut.size);
    }
    if (shaders.preconditionRawLens.words) {
        im.preRl = makeModule(device, shaders.preconditionRawLens, "denoise pre rl");
        im.preRlPipe = makePipe(device, im.preRl, im.prePipeLayout);
    }
    if (shaders.lowresChroma.words) {
        im.lrc = makeModule(device, shaders.lowresChroma, "denoise lowres chroma");
        im.lrcLayout = makeLayout(device, {{0, STORAGE_IMAGE}, {1, STORAGE_IMAGE}, {2, STORAGE_IMAGE},
                                           {3, STORAGE_IMAGE}, {4, STORAGE_IMAGE}},
                                  52, &im.lrcPipeLayout);
        im.lrcPipe = makePipe(device, im.lrc, im.lrcPipeLayout);
        im.lrcSet = allocSet(device, im.pool, im.lrcLayout);
    }
}

DenoisePipeline::~DenoisePipeline() {
    Impl& im = *impl_;
    VkDevice device = im.device;
    if (device) {
        for (auto p : {im.prePipe, im.atrPipe, im.thrPipe, im.backPipe, im.backBufPipe, im.wapPipe, im.lrcPipe,
                       im.preRlPipe, im.backBufRlPipe})
            if (p) vkDestroyPipeline(device, p, nullptr);
        for (auto l : {im.prePipeLayout, im.atrPipeLayout, im.thrPipeLayout, im.backPipeLayout,
                       im.backBufPipeLayout, im.lrcPipeLayout, im.backBufRlPipeLayout})
            if (l) vkDestroyPipelineLayout(device, l, nullptr);
        for (auto s : {im.preLayout, im.atrLayout, im.thrLayout, im.backLayout, im.backBufLayout, im.lrcLayout,
                       im.backBufRlLayout})
            if (s) vkDestroyDescriptorSetLayout(device, s, nullptr);
        for (auto m : {im.pre, im.atr, im.thr, im.back, im.backBuf, im.wap, im.lrc, im.preRl, im.backBufRl})
            if (m) vkDestroyShaderModule(device, m, nullptr);
        if (im.pool) vkDestroyDescriptorPool(device, im.pool, nullptr);
        freeTileImage(device, im.fine);
        freeTileImage(device, im.coarseA);
        freeTileImage(device, im.coarseB);
        freeTileImage(device, im.accum);
        freeBuffer(device, im.partials);
        freeBuffer(device, im.thrsBuf);
        for (TileImage* t : {&im.lrY, &im.lrAB, &im.lrD, &im.lrDummy}) freeTileImage(device, *t);
        if (im.lutMapped) vkUnmapMemory(device, im.lut.memory);
        freeBuffer(device, im.lut);
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

void DenoisePipeline::recordToBuffer(VkCommandBuffer command, VkImageView srcView, VkBuffer dstBuffer,
                                     VkDeviceSize dstSize, uint32_t width, uint32_t height,
                                     const DenoiseParams& params, VkQueryPool timingPool) {
    if (!impl_->backBufPipe) throw std::runtime_error("denoise: no buffer backtransform shader");
    if (!dstBuffer || dstSize < (VkDeviceSize)width * height * 8u)
        throw std::runtime_error("denoise: output buffer too small");
    impl_->outBuffer = dstBuffer;
    impl_->outSize = dstSize;
    try {
        record(command, srcView, VK_NULL_HANDLE, width, height, params, timingPool);
    } catch (...) {
        impl_->outBuffer = VK_NULL_HANDLE;
        throw;
    }
    impl_->outBuffer = VK_NULL_HANDLE;
}

void DenoisePipeline::record(VkCommandBuffer command, VkImageView srcView, VkImageView dstView,
                             uint32_t width, uint32_t height, const DenoiseParams& params,
                             VkQueryPool timingPool) {
    Impl& im = *impl_;
    VkDevice device = im.device;
    const int maxScale = std::clamp(params.maxScale, 1, 7);
    const bool wiener = params.algorithm == 1;
    if (wiener && !im.wapPipe) throw std::runtime_error("denoise: no wiener apply shader");
    // Wiener apply adds 3 pixels of window support per scale, the low-res
    // chroma pass 40 (1/4 grid: 2 boxes of radius 4 + bilinear + block), and
    // RawLens halos/tiles are multiples of 4 so the 1/4 grid is global.
    const uint32_t haloRawLens = (2u * ((1u << (uint32_t)maxScale) - 1u) + 3u * (uint32_t)maxScale + 40u + 3u) & ~3u;
    const uint32_t halo = wiener ? haloRawLens : 2u * ((1u << (uint32_t)maxScale) - 1u);
    const uint32_t tile = wiener ? ((std::max(params.tileSize, 256u) + 3u) & ~3u) : std::max(params.tileSize, 256u);
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
    // Tile scratch never exceeds the frame: a frame smaller than one tile only
    // needs (frame + 2H). Grow-once; the detail image and statistics buffers
    // exist only once the RAWR algorithm is used (Wiener needs neither).
    // Size for the larger (Wiener) halo whatever the algorithm, so switching
    // RAWR <-> RawLens never reallocates scratch. RAWR output is unaffected
    // (allocation extent only, +4% scratch in RAWR mode).
    const uint32_t haloMax = haloRawLens;
    const uint32_t tileMax = (std::max(params.tileSize, 256u) + 3u) & ~3u;
    const uint32_t needExtent = std::min(tileMax + 2u * haloMax, std::max(width, height) + 2u * haloMax);
    if (im.scratchExtent < needExtent) {
        freeTileImage(device, im.fine);
        freeTileImage(device, im.coarseA);
        freeTileImage(device, im.coarseB);
        freeTileImage(device, im.accum);
        freeBuffer(device, im.partials);
        im.fine = makeTileImage(im.physical, device, needExtent);
        im.coarseA = makeTileImage(im.physical, device, needExtent);
        im.accum = makeTileImage(im.physical, device, needExtent);
        transitionToGeneral(command, im.fine.image);
        transitionToGeneral(command, im.coarseA.image);
        transitionToGeneral(command, im.accum.image);
        im.scratchExtent = needExtent;
    }
    if (!wiener && im.partials.buffer &&
        im.partials.size < (VkDeviceSize)((extent + 7) / 8) * ((im.scratchExtent + 7) / 8) * 16u)
        freeBuffer(device, im.partials);
    if (!im.coarseB.image) {  // detail image (both algorithms)
        im.coarseB = makeTileImage(im.physical, device, im.scratchExtent);
        transitionToGeneral(command, im.coarseB.image);
    }
    if (!wiener && !im.partials.buffer) {
        // Rows use the full-extent stride (see threshold pass); columns/rows
        // dispatched never exceed the scratch extent. Fits local sizes >= 8x8.
        const uint32_t gStride = (extent + 7) / 8, gRows = (im.scratchExtent + 7) / 8;
        im.partials = makeBuffer(im.physical, device, (VkDeviceSize)gStride * gRows * 16u);
    }
    if (!im.thrsBuf.buffer) im.thrsBuf = makeBuffer(im.physical, device, 8u * 16u);
    // RawLens steps 1-2 resources. The dummy keeps binding 3 valid otherwise.
    // RawLens variants (when loaded) run the whole RawLens path; RAWR keeps the
    // unchanged shaders.
    const bool rl = wiener && im.backBufRlPipe && im.preRlPipe;
    const bool lowres = rl && params.lowresWeight > 0.0f && im.lrcPipe;
    if (!im.lrDummy.image) {
        im.lrDummy = makeTileImage(im.physical, device, 4, VK_FORMAT_R32G32B32A32_SFLOAT);
        transitionToGeneral(command, im.lrDummy.image);
    }
    if (lowres && im.lrExtent < im.scratchExtent / 4u + 2u) {
        for (TileImage* t : {&im.lrY, &im.lrAB, &im.lrD}) {
            freeTileImage(device, *t);
            *t = makeTileImage(im.physical, device, im.scratchExtent / 4u + 2u, VK_FORMAT_R32G32B32A32_SFLOAT);
            transitionToGeneral(command, t->image);
        }
        im.lrExtent = im.scratchExtent / 4u + 2u;
    }
    const bool exactInv = rl && params.exactInverse && im.lutMapped;
    if (exactInv) {
        const float key[7] = {a, b, p[0], p[1], p[2], params.noiseA, params.noiseB};
        if (!im.lutValid || std::memcmp(key, im.lutKey, sizeof key) != 0) {
            buildInverseLuts(im.lutMapped, a, b, p, params.noiseA, params.noiseB);
            std::memcpy(im.lutKey, key, sizeof key);
            im.lutValid = true;
        }
    }
    // Detail/partials bindings must be valid even when unused (Wiener).
    const VkImageView detailView = im.coarseB.view ? im.coarseB.view : im.accum.view;
    const VkBuffer partialsBuf = im.partials.buffer ? im.partials.buffer : im.thrsBuf.buffer;
    const VkDeviceSize partialsSize = im.partials.buffer ? im.partials.size : im.thrsBuf.size;
    // Rebind tile views (scratch may have been reallocated).
    writeImageSet(device, im.preSet, 0, srcView);
    writeImageSet(device, im.preSet, 1, im.fine.view);
    writeBufferSet(device, im.thrSet, 0, partialsBuf, partialsSize);
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
        writeBufferSet(device, set, 3, partialsBuf, partialsSize);
        writeBufferSet(device, set, 4, im.thrsBuf.buffer, im.thrsBuf.size);
        writeImageSet(device, set, 5, detailView);
    }
    // Backtransform folds the residue: (accum, residue) -> out. Writes
    // alternate coarseA, fine, coarseA... from scale 0, so the last write
    // (scale maxScale-1) sits in (maxScale odd ? coarseA : fine).
    writeImageSet(device, im.backSet, 0, im.accum.view);
    writeImageSet(device, im.backSet, 1, (maxScale % 2 == 1) ? im.coarseA.view : im.fine.view);
    const bool toBuffer = im.outBuffer != VK_NULL_HANDLE;
    const VkImageView residueView = (maxScale % 2 == 1) ? im.coarseA.view : im.fine.view;
    if (toBuffer) {
        writeImageSet(device, im.backBufSet, 0, im.accum.view);
        writeImageSet(device, im.backBufSet, 1, residueView);
        writeBufferSet(device, im.backBufSet, 2, im.outBuffer, im.outSize);
        if (rl) {
            writeImageSet(device, im.backBufRlSet, 0, im.accum.view);
            writeImageSet(device, im.backBufRlSet, 1, residueView);
            writeBufferSet(device, im.backBufRlSet, 2, im.outBuffer, im.outSize);
            writeImageSet(device, im.backBufRlSet, 3, lowres ? im.lrD.view : im.lrDummy.view);
            writeBufferSet(device, im.backBufRlSet, 4, im.lut.buffer, im.lut.size);
        }
    }
    if (lowres) {
        writeImageSet(device, im.lrcSet, 0, im.accum.view);
        writeImageSet(device, im.lrcSet, 1, residueView);
        writeImageSet(device, im.lrcSet, 2, im.lrY.view);
        writeImageSet(device, im.lrcSet, 3, im.lrAB.view);
        writeImageSet(device, im.lrcSet, 4, im.lrD.view);
    }
    if (!toBuffer) {
        writeImageSet(device, im.backSet, 2, dstView);
    }

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
    uint32_t tileIndex = 0;
    const float varf = std::sqrt(2.0f + 2.0f * 16.0f + 36.0f) / 16.0f;
    for (uint32_t ty = 0; ty < height; ty += tile) {
        for (uint32_t tx = 0; tx < width; tx += tile) {
            const uint32_t thisTile = tileIndex++;
            if (thisTile < params.tileBegin || thisTile >= params.tileEnd) continue;
            const uint32_t tw = std::min(tile, width - tx);
            const uint32_t th = std::min(tile, height - ty);
            // Crop the extended tile to inner+2H: inner pixels depend only on
            // positions inside it, so partial edge tiles are exact with less work.
            const uint32_t ew = tw + 2u * halo, eh = th + 2u * halo;
            const uint32_t gx = (ew + kLocal - 1u) / kLocal, gy = (eh + kLocal - 1u) / kLocal;
            // Precondition: extended tile from source image.
            {
                struct PP {
                    uint32_t srcW, srcH;
                    int32_t ox, oy;
                    uint32_t tileEW, tileEH;
                    float a, p0, p1, p2, b, pad0;
                    float m[12];
                } pp{};
                pp.srcW = width;
                pp.srcH = height;
                pp.ox = (int32_t)tx - (int32_t)halo;
                pp.oy = (int32_t)ty - (int32_t)halo;
                pp.tileEW = ew;
                pp.tileEH = eh;
                pp.a = a;
                pp.p0 = p[0];
                pp.p1 = p[1];
                pp.p2 = p[2];
                pp.b = b;
                pp.pad0 = rl ? params.impulseT : 0.0f;  // impT (RawLens step 3)
                // GLSL mat3 is column-major: column c must hold column c of
                // the row-major yuvM, i.e. upload the transpose.
                for (int col = 0; col < 3; col++)
                    for (int row = 0; row < 3; row++) pp.m[col * 4 + row] = yuvM[row * 3 + col];
                dispatch(rl ? im.preRlPipe : im.prePipe, im.prePipeLayout, im.preSet, &pp, sizeof(pp), gx, gy);
            }
            // Statistics rows keep the full-extent stride so the threshold
            // reduction sums partials in exactly RAWR's order.
            const uint32_t groupsX = (extent + kLocal - 1u) / kLocal;
            struct PA {
                uint32_t tileEW, tileEH, innerW, innerH, halo;
                uint32_t mult;
                float inv;
                uint32_t mode, scaleIdx, groupsX, applyPrev, accumFirst;
                float noiseY, noiseUv, guide, keepY, eps;
            };
            for (int scale = 0; scale < maxScale; scale++) {
                const float sb = std::pow(varf, (float)scale);
                const float sb2 = sb * sb;
                PA pa{ew, eh, tw, th, halo, 1u << (uint32_t)scale, 1.0f / sb2, 0u, (uint32_t)scale,
                      groupsX, scale > 0 ? 1u : 0u, scale == 1 ? 1u : 0u, 0, 0, 0, 0, 0};
                if (wiener) {
                    // Effective noise variance per band; Luma/Chroma sliders
                    // scale it (defaults 0.25/0.75 -> 1.0 / 1.5 fine, 3.0 coarse).
                    pa.mode = 2u;
                    pa.applyPrev = 0u;
                    pa.eps = 4.0f;
                    pa.accumFirst = scale == 0 ? 1u : 0u;
                    pa.noiseY = sb2 * std::max(4.0f * params.forceY * params.lumaScale, 1e-3f);
                    pa.noiseUv = sb2 * std::max((scale < 2 ? 2.0f : 4.0f) * params.forceUv, 1e-3f);
                    pa.guide = 1.0f;
                    const float g = std::clamp(params.grain, 0.0f, 1.0f);
                    pa.keepY = scale == 0 ? g : (scale == 1 ? 0.4f * g : 0.0f);
                    dispatch(im.atrPipe, im.atrPipeLayout, im.atrSets[scale], &pa, sizeof(pa), gx, gy);
                    dispatch(im.wapPipe, im.atrPipeLayout, im.atrSets[scale], &pa, sizeof(pa), gx, gy);
                    continue;
                }
                dispatch(im.atrPipe, im.atrPipeLayout, im.atrSets[scale], &pa, sizeof(pa), gx, gy);
                struct PT {
                    uint32_t partialCount, innerPixels;
                    float sb2, forceY, forceUv;
                    uint32_t scaleIdx, stride, dispGX, dispGY;
                } pt{groupsX * groupsX, tw * th, sb2, params.forceY, params.forceUv,
                     (uint32_t)scale, groupsX, gx, gy};
                dispatch(im.thrPipe, im.thrPipeLayout, im.thrSet, &pt, sizeof(pt), 1, 1);
            }
            if (!wiener) {
                // Last scale's apply (earlier ones are fused into the next estimate).
                PA pa{ew, eh, tw, th, halo, 1u, 1.0f, 1u, (uint32_t)(maxScale - 1), groupsX, 0u,
                      maxScale == 1 ? 1u : 0u, 0, 0, 0, 0, 0};
                dispatch(im.atrPipe, im.atrPipeLayout, im.atrSets[maxScale - 1], &pa, sizeof(pa), gx, gy);
            }
            // RawLens step 1: low-res chroma (block means, regression, correction).
            const int lrOX = ((int)tx - (int)halo) / 4, lrOY = ((int)ty - (int)halo) / 4;
            const int lrNW = (int)((width + 3u) / 4u), lrNH = (int)((height + 3u) / 4u);
            if (lowres) {
                struct LP {
                    uint32_t lrW, lrH;
                    int32_t lrOX, lrOY, lrNW, lrNH, pixOX, pixOY;
                    uint32_t frameW, frameH, mode;
                    int32_t r;
                    float epsN2;
                } lp{(ew + 3u) / 4u, (eh + 3u) / 4u, lrOX, lrOY, lrNW, lrNH,
                     (int)tx - (int)halo, (int)ty - (int)halo, width, height, 0u, 4,
                     params.lowresEps * (yuvM[0] * yuvM[0] + yuvM[1] * yuvM[1] + yuvM[2] * yuvM[2]) / 16.0f};
                const uint32_t lgx = (lp.lrW + kLocal - 1u) / kLocal, lgy = (lp.lrH + kLocal - 1u) / kLocal;
                for (uint32_t mode = 0; mode < 3; mode++) {
                    lp.mode = mode;
                    dispatch(im.lrcPipe, im.lrcPipeLayout, im.lrcSet, &lp, sizeof(lp), lgx, lgy);
                }
            }
            {
                struct PB {
                    uint32_t tileW, tileH, outX, outY, halo;
                    float a, p0, p1, p2, b, bias, pad;
                    float m[12];
                    uint32_t lutN;
                    float lrWeight;
                    int32_t lrOX, lrOY, lrNW, lrNH;
                } pb{};
                pb.lutN = exactInv ? kLutN : 0u;
                pb.lrWeight = lowres ? params.lowresWeight : 0.0f;
                pb.lrOX = lrOX;
                pb.lrOY = lrOY;
                pb.lrNW = lrNW;
                pb.lrNH = lrNH;
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
                pb.pad = 0.0f;
                if (toBuffer) std::memcpy(&pb.pad, &width, sizeof width);  // outStride
                for (int col = 0; col < 3; col++)
                    for (int row = 0; row < 3; row++) pb.m[col * 4 + row] = rgbM[row * 3 + col];
                // RAWR shaders keep their 96-byte push block.
                const bool rlBack = toBuffer && rl;
                dispatch(rlBack ? im.backBufRlPipe : (toBuffer ? im.backBufPipe : im.backPipe),
                         rlBack ? im.backBufRlPipeLayout : (toBuffer ? im.backBufPipeLayout : im.backPipeLayout),
                         rlBack ? im.backBufRlSet : (toBuffer ? im.backBufSet : im.backSet), &pb,
                         rlBack ? sizeof(pb) : 96u,
                         (tw + kLocal - 1u) / kLocal, (th + kLocal - 1u) / kLocal);
            }
            if (tileBoundary_ && !(ty + tile >= height && tx + tile >= width)) tileBoundary_();
        }
    }
    if (timingPool) vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, timingPool,
                                        11);
}

}  // namespace raw_denoise
