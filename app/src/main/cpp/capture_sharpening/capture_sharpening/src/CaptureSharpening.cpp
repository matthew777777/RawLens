#include "capture_sharpening/CaptureSharpening.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <functional>
#include <vector>
#include <stdexcept>
#include <string>

#include "cs_apron.h"
#include "cs_blend_raw.h"
#include "cs_contrast.h"
#include "cs_deconv.h"
#include "cs_iir.h"
#include "cs_luma_blocks.h"
#include "cs_radius.h"
#include "cs_select.h"
#include "cs_tile_stats.h"
#include "cs_transpose.h"
#include "cs_yold.h"
#include "rawr/vk/DeviceObjects.h"
#include "rawr/vk/OwnedImage.h"

namespace rawr::capsharp {
namespace {

// Mirrors CsPush in cs_common.glsl.
struct Push {
    std::int32_t size[2];
    std::uint32_t pattern;
    std::uint32_t flags;
    float toRt[4];
    float radiusOffset;
    std::uint32_t iterations;
    std::uint32_t mode;
    std::uint32_t gridW;
    std::uint32_t gridH;
    std::uint32_t tileSize;
    std::uint32_t tileSkip;
    std::uint32_t tStride;
    std::int32_t strip[4];  // first context row, context rows, apron rows [z, w)
    std::uint32_t apronSel;
    std::uint32_t pad[3];
};
static_assert(sizeof(Push) == 96, "capture sharpening push layout");

// Mirrors CsParams in cs_common.glsl.
struct GpuParams {
    std::uint32_t maxRatioBits;
    float sigma;
    float contrast;
    std::uint32_t state;
    std::int32_t tileY, tileX;
    std::uint32_t tileSz;
    std::int32_t refineY0, refineX0, refineH, refineW;
    float minVar;
    float yvvB, yvvB1, yvvB2, yvvB3;
    float yvvM[9];
};
static_assert(sizeof(GpuParams) == 100, "capture sharpening params layout");

// Row stride of the transposed scratch: H padded to a multiple of 16 floats
// that is an odd number of 64-byte lines, so the 16 columns a transpose tile
// touches do not all land in the same memory channel (6144 rows cost 10x).
std::uint32_t transposedStride(std::uint32_t h) {
    std::uint32_t s = (h + 15u) & ~15u;
    if ((s / 16u) % 2u == 0u) s += 16u;
    return s;
}

// gauss.cc calculateYvVFactors<double> + the M scaling of gaussHorizontal and
// gaussVertical, for the blend-mask blur (sigma 2).
void yvvFactors(double sigma, GpuParams& out) {
    const double q = sigma < 2.5 ? 3.97156 - 4.14554 * std::sqrt(1.0 - 0.26891 * sigma) : 0.98711 * sigma - 0.96330;
    const double b0 = 1.57825 + 2.44413 * q + 1.4281 * q * q + 0.422205 * q * q * q;
    double b1 = 2.44413 * q + 2.85619 * q * q + 1.26661 * q * q * q;
    double b2 = -1.4281 * q * q - 1.26661 * q * q * q;
    double b3 = 0.422205 * q * q * q;
    const double B = 1.0 - (b1 + b2 + b3) / b0;
    b1 /= b0;
    b2 /= b0;
    b3 /= b0;
    double M[3][3];
    M[0][0] = -b3 * b1 + 1.0 - b3 * b3 - b2;
    M[0][1] = (b3 + b1) * (b2 + b3 * b1);
    M[0][2] = b3 * (b1 + b3 * b2);
    M[1][0] = b1 + b3 * b2;
    M[1][1] = -(b2 - 1.0) * (b2 + b3 * b1);
    M[1][2] = -(b3 * b1 + b3 * b3 + b2 - 1.0) * b3;
    M[2][0] = b3 * b1 + b2 + b1 * b1 - b2 * b2;
    M[2][1] = b1 * b2 + b3 * b2 * b2 - b1 * b3 * b3 - b3 * b3 * b3 - b3 * b2 + b3;
    M[2][2] = b3 * (b1 + b3 * b2);
    const double d = (1.0 + b1 - b2 + b3) * (1.0 + b2 + (b1 - b3) * b3);
    out.yvvB = float(B);
    out.yvvB1 = float(b1);
    out.yvvB2 = float(b2);
    out.yvvB3 = float(b3);
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) out.yvvM[i * 3 + j] = float(M[i][j] / d);
}

constexpr std::uint32_t kFlagAutoRadius = 1u, kFlagAutoContrast = 2u, kFlagIterCheck = 4u, kFlagClipState = 8u;
constexpr std::uint32_t kStateDone = 4u;
constexpr std::uint32_t kRefineTiles = 21;  // (2 * stride + 1) for the 40px/10px pass
constexpr std::uint32_t kMinSize = 48;      // largest tile window
// Strip mode context: the recursive blend blur settles within 48 rows (its
// poles are ~0.6, so 0.6^48 < 1e-10) plus the 5x5 contrast stencil.
constexpr std::uint32_t kContextRows = 56;
constexpr std::uint64_t kDefaultStripPixels = 24ull << 20;

// One strip: tile rows [tile0, tile1) and the image rows its passes need.
struct Strip {
    std::uint32_t tile0, tile1;
    std::uint32_t ctxTop, ctxRows;
    std::uint32_t apronTop, apronEnd;  // rows an earlier strip has sharpened (originals saved)
};

void check(VkResult result, const char* what) {
    if (result != VK_SUCCESS)
        throw std::runtime_error(std::string("capture_sharpening: ") + what + " (" + std::to_string(result) + ")");
}

std::uint32_t memoryType(VkPhysicalDevice physical, std::uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties props{};
    vkGetPhysicalDeviceMemoryProperties(physical, &props);
    for (std::uint32_t i = 0; i < props.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & want) == want) return i;
    throw std::runtime_error("capture_sharpening: no memory type");
}

void computeBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &b, 0,
                         nullptr, 0, nullptr);
}

}  // namespace

struct CaptureSharpening::Impl {
    rawr::vk::GpuContext gpu;
    rawr::vk::DeviceObjects objects;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline radius = VK_NULL_HANDLE, select = VK_NULL_HANDLE, stats = VK_NULL_HANDLE, contrast = VK_NULL_HANDLE,
               blendRaw = VK_NULL_HANDLE, iir = VK_NULL_HANDLE, transpose = VK_NULL_HANDLE, yold = VK_NULL_HANDLE,
               deconvGeneric = VK_NULL_HANDLE, lumaBlocks = VK_NULL_HANDLE;
    // Fixed kernel sizes 3, 5, 7, 9, 13 (cs_deconv KN specialization).
    VkPipeline deconvFixed[5]{};
    // Indirect sizes, per variant: one entry per band of tile rows, then the
    // full grid (edge-tile phases); written by cs_select mode 0.
    VkBuffer dispatchBuffer = VK_NULL_HANDLE;
    std::uint32_t dispatchStride = 0;  // entries per variant
    std::function<void()> tileBoundary;
    std::uint32_t lastStrips = 0;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkBuffer paramsBuffer = VK_NULL_HANDLE;
    void* paramsMapped = nullptr;
    rawr::vk::OwnedImage dummyClip{};
    bool dummyClipInitialized = false;

    // Size-dependent scratch.
    rawr::vk::DeviceObjects scratch;
    std::uint32_t width = 0, height = 0;
    std::uint32_t contextRows = 0, apronRows = 0;
    rawr::vk::OwnedImage blendImg{};
    rawr::vk::OwnedImage apron[2]{};
    VkPipeline apronCopy = VK_NULL_HANDLE;
    VkBuffer scratchBuffer = VK_NULL_HANDLE, variances = VK_NULL_HANDLE, blockStats = VK_NULL_HANDLE;
    bool scratchInitialized = false;
    std::uint64_t bytes = 0;

    explicit Impl(const rawr::vk::GpuContext& context) : gpu(context), objects(context.device), scratch(context.device) {
        if (!gpu.device || !gpu.physicalDevice) throw std::invalid_argument("capture_sharpening: missing device");
        VkDescriptorSetLayoutBinding bindings[9]{};
        const VkDescriptorType types[9] = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,  VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                           VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                           VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                           VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                           VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
        for (std::uint32_t b = 0; b < 9; ++b) {
            bindings[b].binding = b;
            bindings[b].descriptorType = types[b];
            bindings[b].descriptorCount = b == 8 ? 2 : 1;  // binding 8: the two apron images
            bindings[b].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo lci{};
        lci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        lci.bindingCount = 9;
        lci.pBindings = bindings;
        check(vkCreateDescriptorSetLayout(gpu.device, &lci, nullptr, &setLayout), "set layout");
        objects.adoptDescriptorSetLayout(setLayout);
        VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
        VkPipelineLayoutCreateInfo pci{};
        pci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pci.setLayoutCount = 1;
        pci.pSetLayouts = &setLayout;
        pci.pushConstantRangeCount = 1;
        pci.pPushConstantRanges = &range;
        check(vkCreatePipelineLayout(gpu.device, &pci, nullptr, &layout), "pipeline layout");
        objects.adoptPipelineLayout(layout);
        radius = pipeline(cs_radius_spv, cs_radius_spv_size);
        select = pipeline(cs_select_spv, cs_select_spv_size);
        stats = pipeline(cs_tile_stats_spv, cs_tile_stats_spv_size);
        contrast = pipeline(cs_contrast_spv, cs_contrast_spv_size);
        blendRaw = pipeline(cs_blend_raw_spv, cs_blend_raw_spv_size);
        iir = pipeline(cs_iir_spv, cs_iir_spv_size);
        transpose = pipeline(cs_transpose_spv, cs_transpose_spv_size);
        yold = pipeline(cs_yold_spv, cs_yold_spv_size);
        lumaBlocks = pipeline(cs_luma_blocks_spv, cs_luma_blocks_spv_size);
        apronCopy = pipeline(cs_apron_spv, cs_apron_spv_size);
        deconvGeneric = pipeline(cs_deconv_spv, cs_deconv_spv_size, 0);
        const int fixedSizes[5] = {3, 5, 7, 9, 13};
        for (int v = 0; v < 5; ++v) deconvFixed[v] = pipeline(cs_deconv_spv, cs_deconv_spv_size, fixedSizes[v]);

        const VkDescriptorPoolSize sizes[] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 5},
                                              {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 5}};
        VkDescriptorPoolCreateInfo poolInfo{};
        poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount = 2;
        poolInfo.pPoolSizes = sizes;
        check(vkCreateDescriptorPool(gpu.device, &poolInfo, nullptr, &pool), "descriptor pool");
        objects.adoptDescriptorPool(pool);
        VkDescriptorSetAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = pool;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &setLayout;
        check(vkAllocateDescriptorSets(gpu.device, &ai, &set), "descriptor set");

        // Host-visible so lastResult() can read the chosen radius/contrast.
        paramsBuffer = createBuffer(objects, sizeof(GpuParams),
                                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                    &paramsMapped);
        std::memset(paramsMapped, 0, sizeof(GpuParams));
        dummyClip = objects.adoptOwnedImage(rawr::vk::createOwnedImage(gpu.physicalDevice, gpu.device, 1, 1,
                                                                       VK_FORMAT_R16_UINT, VK_IMAGE_USAGE_STORAGE_BIT));
    }

    // kernelSize feeds cs_deconv's KN specialization constant (-1: none).
    VkPipeline pipeline(const unsigned char* code, std::size_t size, int kernelSize = -1) {
        VkShaderModuleCreateInfo mi{};
        mi.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        mi.codeSize = size;
        mi.pCode = reinterpret_cast<const std::uint32_t*>(code);
        VkShaderModule module = VK_NULL_HANDLE;
        check(vkCreateShaderModule(gpu.device, &mi, nullptr, &module), "shader module");
        objects.adoptShaderModule(module);
        VkComputePipelineCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        ci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        ci.stage.module = module;
        ci.stage.pName = "main";
        const VkSpecializationMapEntry entry{0, 0, sizeof(int)};
        VkSpecializationInfo spec{1, &entry, sizeof(int), &kernelSize};
        if (kernelSize >= 0) ci.stage.pSpecializationInfo = &spec;
        ci.layout = layout;
        VkPipeline out = VK_NULL_HANDLE;
        check(vkCreateComputePipelines(gpu.device, VK_NULL_HANDLE, 1, &ci, nullptr, &out), "pipeline");
        return objects.adoptPipeline(out);
    }

    VkBuffer createBuffer(rawr::vk::DeviceObjects& owner, VkDeviceSize size, VkBufferUsageFlags usage,
                          VkMemoryPropertyFlags props, void** mapped = nullptr) {
        VkBufferCreateInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size = size;
        bi.usage = usage;
        bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VkBuffer buffer = VK_NULL_HANDLE;
        check(vkCreateBuffer(gpu.device, &bi, nullptr, &buffer), "buffer");
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(gpu.device, buffer, &req);
        VkMemoryAllocateInfo mai{};
        mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = memoryType(gpu.physicalDevice, req.memoryTypeBits, props);
        VkDeviceMemory memory = VK_NULL_HANDLE;
        if (vkAllocateMemory(gpu.device, &mai, nullptr, &memory) != VK_SUCCESS) {
            vkDestroyBuffer(gpu.device, buffer, nullptr);
            throw std::runtime_error("capture_sharpening: buffer memory");
        }
        owner.adoptMemory(memory);
        owner.adoptBuffer(buffer);
        check(vkBindBufferMemory(gpu.device, buffer, memory, 0), "bind buffer");
        if (mapped) check(vkMapMemory(gpu.device, memory, 0, VK_WHOLE_SIZE, 0, mapped), "map params");
        return buffer;
    }

    // ctxRows: largest strip context; aprons: rows of saved originals (0 = one strip).
    void ensureScratch(std::uint32_t w, std::uint32_t h, std::uint32_t ctxRows, std::uint32_t aprons) {
        if (w == width && h == height && ctxRows == contextRows && aprons == apronRows) return;
        // A size change replaces the scratch; the previous submission has completed (see header).
        scratch.reset();
        rawr::vk::DeviceObjects fresh(gpu.device);
        blendImg = fresh.adoptOwnedImage(rawr::vk::createOwnedImage(gpu.physicalDevice, gpu.device, w, ctxRows,
                                                                    VK_FORMAT_R16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT));
        for (auto& a : apron)
            a = fresh.adoptOwnedImage(rawr::vk::createOwnedImage(gpu.physicalDevice, gpu.device, w,
                                                                 std::max(aprons, 1u), VK_FORMAT_R16G16B16A16_SFLOAT,
                                                                 VK_IMAGE_USAGE_STORAGE_BIT));
        scratchBuffer = createBuffer(fresh, VkDeviceSize(w) * transposedStride(ctxRows) * sizeof(float),
                                     VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                     VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        const std::uint64_t pass0 = std::uint64_t(w / 80) * (h / 80);
        const std::uint64_t pass1 = std::uint64_t(w / 10 > 3 ? w / 10 - 3 : 0) * (h / 10 > 3 ? h / 10 - 3 : 0);
        const std::uint64_t tiles = std::max<std::uint64_t>({pass0, pass1, kRefineTiles * kRefineTiles, 1});
        variances = createBuffer(fresh, tiles * sizeof(float), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                 VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        // Bands are at least one tile row; tile rows for the smallest border (5).
        dispatchStride = (h - 10 + 31) / 32 + 1;
        dispatchBuffer = createBuffer(fresh, VkDeviceSize(5) * dispatchStride * 16,
                                      VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT,
                                      VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        const std::uint64_t blocks = std::max<std::uint64_t>(std::uint64_t(w / 10) * (h / 10), 1);
        blockStats = createBuffer(fresh, blocks * 2 * sizeof(float), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                  VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        scratch = std::move(fresh);
        bytes = std::uint64_t(w) * transposedStride(ctxRows) * 4u + std::uint64_t(w) * ctxRows * 2u +
                std::uint64_t(w) * std::max(aprons, 1u) * 16u + tiles * sizeof(float) + blocks * 2 * sizeof(float);
        width = w;
        height = h;
        contextRows = ctxRows;
        apronRows = aprons;
        scratchInitialized = false;
    }

    void writeSet(VkImageView rgb, VkImageView clip) {
        // Bindings 0, 1, 3 images; 2, 4, 5 buffers (see cs_common.glsl).
        VkDescriptorImageInfo images[4] = {{VK_NULL_HANDLE, rgb, VK_IMAGE_LAYOUT_GENERAL},
                                           {VK_NULL_HANDLE, clip, VK_IMAGE_LAYOUT_GENERAL},
                                           {},
                                           {VK_NULL_HANDLE, blendImg.view, VK_IMAGE_LAYOUT_GENERAL}};
        VkDescriptorBufferInfo buffers[8] = {{}, {}, {scratchBuffer, 0, VK_WHOLE_SIZE}, {},
                                             {paramsBuffer, 0, VK_WHOLE_SIZE}, {variances, 0, VK_WHOLE_SIZE},
                                             {dispatchBuffer, 0, VK_WHOLE_SIZE}, {blockStats, 0, VK_WHOLE_SIZE}};
        VkDescriptorImageInfo aprons[2] = {{VK_NULL_HANDLE, apron[0].view, VK_IMAGE_LAYOUT_GENERAL},
                                           {VK_NULL_HANDLE, apron[1].view, VK_IMAGE_LAYOUT_GENERAL}};
        VkWriteDescriptorSet writes[9]{};
        writes[8].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[8].dstSet = set;
        writes[8].dstBinding = 8;
        writes[8].descriptorCount = 2;
        writes[8].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[8].pImageInfo = aprons;
        for (std::uint32_t b = 0; b < 8; ++b) {
            const bool image = b == 0 || b == 1 || b == 3;
            writes[b].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[b].dstSet = set;
            writes[b].dstBinding = b;
            writes[b].descriptorCount = 1;
            writes[b].descriptorType = image ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            if (image)
                writes[b].pImageInfo = &images[b];
            else
                writes[b].pBufferInfo = &buffers[b];
        }
        vkUpdateDescriptorSets(gpu.device, 9, writes, 0, nullptr);
    }

    void toGeneral(VkCommandBuffer cmd, std::initializer_list<VkImage> images) {
        VkImageMemoryBarrier barriers[5]{};
        std::uint32_t n = 0;
        for (VkImage image : images) {
            auto& b = barriers[n++];
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = image;
            b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        }
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0,
                             nullptr, 0, nullptr, n, barriers);
    }

    void dispatch(VkCommandBuffer cmd, VkPipeline pipe, Push push, std::uint32_t mode, std::uint32_t gx,
                  std::uint32_t gy) {
        if (gx == 0 || gy == 0) return;
        push.mode = mode;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push), &push);
        vkCmdDispatch(cmd, gx, gy, 1);
        computeBarrier(cmd);
    }

    // Splits the tile rows into strips whose context fits the pixel budget.
    // Tile row t's window starts at row 32 t for every border (i - border),
    // spans at most 48 rows, and the last tile row's window is shifted up to
    // end at H; context adds kContextRows on both sides. Strips are whole
    // bands so the per-band indirect sizes stay valid. One strip covers the
    // image when it fits.
    static std::vector<Strip> planStrips(std::uint32_t W, std::uint32_t H, std::uint32_t tileRows,
                                         std::uint64_t budget, std::uint32_t& bandRows) {
        std::vector<Strip> strips;
        if (std::uint64_t(W) * H <= budget) {
            strips.push_back({0, tileRows, 0, H, 0, 0});
            return strips;
        }
        const std::uint64_t rowsFit = budget / W;
        std::uint32_t stripTiles = rowsFit > 4 * kContextRows + 96 ? std::uint32_t((rowsFit - 2 * kContextRows - 96) / 32) : 0;
        stripTiles = std::max(stripTiles, 4u);  // keeps the saved apron inside one earlier strip
        stripTiles = std::max(bandRows, stripTiles / bandRows * bandRows);
        for (std::uint32_t t0 = 0; t0 < tileRows; t0 += stripTiles) {
            const std::uint32_t t1 = std::min(t0 + stripTiles, tileRows);
            const bool last = t1 == tileRows;
            std::uint32_t top = 32 * t0, bottom = std::min(32 * (t1 - 1) + 48, H);
            if (last) {
                top = std::min(top, H - 48);
                bottom = H;
            }
            Strip st{};
            st.tile0 = t0;
            st.tile1 = t1;
            st.ctxTop = top > kContextRows ? top - kContextRows : 0;
            st.ctxRows = std::min(bottom + kContextRows, H) - st.ctxTop;
            // Rows above this strip's first core row (border <= 8) were
            // sharpened by earlier strips; their originals come from the apron.
            st.apronTop = t0 > 0 ? st.ctxTop : 0;
            st.apronEnd = t0 > 0 ? std::min(8 + 32 * t0, H) : 0;
            strips.push_back(st);
        }
        return strips;
    }

    bool record(const RecordInfo& info) {
        const VkCommandBuffer cmd = info.commandBuffer;
        if (!cmd || !info.image || !info.view || info.width < kMinSize || info.height < kMinSize ||
            info.cfaPattern > 3 || !validParams(info.params))
            return false;
        const float* wb = info.whiteBalance;
        if (!(wb[0] > 0.0f && wb[1] > 0.0f && wb[2] > 0.0f) || !std::isfinite(wb[0] + wb[1] + wb[2])) return false;
        const Params& p = info.params;
        const std::uint32_t W = info.width, H = info.height;
        const std::uint32_t tileRows = (H - 10 + 31) / 32;  // smallest border (5)
        // Without requested bands, strips size freely and each strip is one band.
        std::uint32_t bandRows = info.deconvBandRows ? std::min(info.deconvBandRows, tileRows) : 1u;
        const std::uint64_t budget = info.maxStripPixels ? info.maxStripPixels : kDefaultStripPixels;
        const std::vector<Strip> strips = planStrips(W, H, tileRows, budget, bandRows);
        if (!info.deconvBandRows) bandRows = strips[0].tile1 - strips[0].tile0;
        std::uint32_t ctxMax = 0, apronMax = 0;
        for (const auto& st : strips) {
            ctxMax = std::max(ctxMax, st.ctxRows);
            apronMax = std::max(apronMax, st.apronEnd - st.apronTop);
        }
        ensureScratch(W, H, ctxMax, apronMax);
        writeSet(info.view, info.clipState ? info.clipState : dummyClip.view);
        if (!scratchInitialized || !dummyClipInitialized) {
            if (!dummyClipInitialized)
                toGeneral(cmd, {blendImg.image, apron[0].image, apron[1].image, dummyClip.image});
            else
                toGeneral(cmd, {blendImg.image, apron[0].image, apron[1].image});
            scratchInitialized = dummyClipInitialized = true;
        }

        GpuParams init{};
        const float one = 1.0f;
        std::memcpy(&init.maxRatioBits, &one, sizeof one);
        init.sigma = p.radius;
        init.contrast = p.autoContrast ? 0.0f : p.contrast / 100.0f;
        init.state = p.autoContrast ? 0u : kStateDone;
        yvvFactors(2.0, init);
        vkCmdUpdateBuffer(cmd, paramsBuffer, 0, sizeof init, &init);
        VkMemoryBarrier upd{};
        upd.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        upd.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        upd.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &upd, 0,
                             nullptr, 0, nullptr);

        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
        std::uint32_t stamp = 0;
        const auto timestamp = [&] {
            if (info.timingPool && stamp < kTimestamps)
                vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, info.timingPool,
                                    info.timingQuery + stamp);
            ++stamp;
        };
        timestamp();
        Push push{};
        push.size[0] = std::int32_t(W);
        push.size[1] = std::int32_t(H);
        push.pattern = info.cfaPattern;
        push.flags = (p.autoRadius ? kFlagAutoRadius : 0u) | (p.autoContrast ? kFlagAutoContrast : 0u) |
                     (p.iterationCheck ? kFlagIterCheck : 0u) | (info.clipState ? kFlagClipState : 0u);
        const float minWb = std::min({wb[0], wb[1], wb[2]});
        for (int c = 0; c < 3; ++c) push.toRt[c] = 65535.0f * wb[c] / minWb;
        push.toRt[3] = 0.0f;
        push.radiusOffset = p.radiusOffset;
        push.iterations = p.iterations;
        // Whole-image passes: no apron (all reads are originals).
        push.strip[0] = 0;
        push.strip[1] = std::int32_t(H);

        // Global passes: radius and contrast see the whole frame in every mode.
        if (p.autoRadius) dispatch(cmd, radius, push, 0, (W / 2 + 255) / 256, H > 8 ? H - 8 : 0);
        {
            // Finalizes the radius and writes the deconvolution dispatch sizes
            // (tileSize = band rows, tileSkip = entries per variant here).
            Push s = push;
            s.tileSize = bandRows;
            s.tileSkip = dispatchStride;
            dispatch(cmd, select, s, 0, 1, 1);
        }
        timestamp();
        if (p.autoContrast) {
            Push s = push;
            dispatch(cmd, lumaBlocks, s, 0, W / 10, H / 10);
            const auto tileGroups = [](const Push& q) { return (q.gridW * q.gridH + 255u) / 256u; };
            s.tileSize = 80;
            s.tileSkip = 80;
            s.gridW = W / 80;
            s.gridH = H / 80;
            dispatch(cmd, stats, s, 0, tileGroups(s), 1);
            dispatch(cmd, select, s, 1, 1, 1);
            s.tileSize = 40;
            s.tileSkip = 10;
            s.gridW = W / 10 > 3 ? W / 10 - 3 : 0;
            s.gridH = H / 10 > 3 ? H / 10 - 3 : 0;
            dispatch(cmd, stats, s, 1, tileGroups(s), 1);
            dispatch(cmd, select, s, 2, 1, 1);
            dispatch(cmd, stats, s, 2, kRefineTiles, kRefineTiles);
            dispatch(cmd, select, s, 3, 1, 1);
            dispatch(cmd, contrast, s, 0, 1, 1);
        }
        timestamp();
        VkMemoryBarrier indirect{};
        indirect.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        indirect.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        indirect.dstAccessMask = VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, 1,
                             &indirect, 0, nullptr, 0, nullptr);
        // The caller may submit and re-begin the command buffer here; state is
        // re-bound after each boundary.
        const bool chunked = strips.size() > 1 || bandRows < tileRows;
        const auto boundary = [&] {
            if (!tileBoundary) return;
            tileBoundary();
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
        };

        // Deconvolution in RT's tile order: per strip, the interior tiles by
        // bands then the row-end tiles; the bottom row and corner last.
        // gridH = first tile row, gridW = tile rows the dispatch may cover.
        const auto deconvPhase = [&](const Push& sp, std::uint32_t phase, std::uint32_t firstRow, std::uint32_t rows,
                                     std::uint32_t entry) {
            Push ph = sp;
            ph.mode = phase;
            ph.gridH = firstRow;
            ph.gridW = rows;
            if (p.radiusOffset > 0.0f) {
                // Per-tile kernel size: generic path, grid for the smallest border (5).
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, deconvGeneric);
                vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push), &ph);
                vkCmdDispatch(cmd, (W - 10 + 31) / 32, rows, 1);
            } else {
                for (std::uint32_t v = 0; v < 5; ++v) {
                    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, deconvFixed[v]);
                    vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push), &ph);
                    vkCmdDispatchIndirect(cmd, dispatchBuffer, (VkDeviceSize(v) * dispatchStride + entry) * 16u);
                }
            }
            computeBarrier(cmd);
        };
        const std::uint32_t fullEntry = dispatchStride - 1;
        for (std::size_t k = 0; k < strips.size(); ++k) {
            const Strip& st = strips[k];
            if (k > 0 && chunked) boundary();
            Push sp = push;
            sp.strip[0] = std::int32_t(st.ctxTop);
            sp.strip[1] = std::int32_t(st.ctxRows);
            sp.strip[2] = std::int32_t(st.apronTop);
            sp.strip[3] = std::int32_t(st.apronEnd);
            sp.apronSel = std::uint32_t(k % 2);
            sp.tStride = transposedStride(st.ctxRows);
            // Save the originals the next strip needs before this one writes them.
            if (k + 1 < strips.size()) {
                const Strip& next = strips[k + 1];
                Push ap = sp;
                ap.strip[2] = std::int32_t(next.apronTop);
                ap.strip[3] = std::int32_t(next.apronEnd);
                dispatch(cmd, apronCopy, ap, std::uint32_t((k + 1) % 2), (W + 15) / 16,
                         (next.apronEnd - next.apronTop + 15) / 16);
            }
            // Blend mask: raw (transposed) -> row blur -> transpose -> column blur; then Y.
            const std::uint32_t gx16 = (W + 15) / 16, gy16 = (st.ctxRows + 15) / 16;
            dispatch(cmd, blendRaw, sp, 0, gy16, gx16);  // y-major, see shader
            if (k == 0) timestamp();
            dispatch(cmd, iir, sp, 0, (st.ctxRows + 63) / 64, 1);
            if (k == 0) timestamp();
            dispatch(cmd, transpose, sp, 0, gy16, gx16);
            if (k == 0) timestamp();
            dispatch(cmd, iir, sp, 1, (W + 63) / 64, 1);
            if (k == 0) timestamp();
            dispatch(cmd, yold, sp, 0, gx16, gy16);
            if (k == 0) timestamp();
            for (std::uint32_t t = st.tile0; t < st.tile1; t += bandRows) {
                if (chunked) boundary();
                deconvPhase(sp, 0, t, bandRows, t / bandRows);
            }
            deconvPhase(sp, 1, st.tile0, st.tile1 - st.tile0, fullEntry);
            if (k + 1 == strips.size()) {
                deconvPhase(sp, 2, 0, tileRows, fullEntry);
                deconvPhase(sp, 3, 0, tileRows, fullEntry);
            }
        }
        while (stamp < kTimestamps) timestamp();

        VkMemoryBarrier done{};
        done.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
        done.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        done.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT |
                                 VK_PIPELINE_STAGE_HOST_BIT,
                             0, 1, &done, 0, nullptr, 0, nullptr);
        lastStrips = std::uint32_t(strips.size());
        return true;
    }

    Result lastResult() const {
        GpuParams gp{};
        std::memcpy(&gp, paramsMapped, sizeof gp);
        Result r{};
        r.radius = gp.sigma;
        r.contrast = gp.contrast * 100.0f;
        r.valid = gp.state == kStateDone;
        return r;
    }
};

CaptureSharpening::CaptureSharpening(const rawr::vk::GpuContext& gpu) : impl_(std::make_unique<Impl>(gpu)) {}
CaptureSharpening::~CaptureSharpening() = default;
bool CaptureSharpening::record(const RecordInfo& info) { return impl_->record(info); }
void CaptureSharpening::setTileBoundary(std::function<void()> boundary) { impl_->tileBoundary = std::move(boundary); }
Result CaptureSharpening::lastResult() const { return impl_->lastResult(); }
std::uint64_t CaptureSharpening::scratchBytes() const noexcept { return impl_->bytes; }
std::uint32_t CaptureSharpening::lastStripCount() const noexcept { return impl_->lastStrips; }
void CaptureSharpening::releaseScratch() noexcept {
    impl_->scratch.reset();
    impl_->width = impl_->height = impl_->contextRows = impl_->apronRows = 0;
    impl_->bytes = 0;
    impl_->scratchInitialized = false;
}

}  // namespace rawr::capsharp
