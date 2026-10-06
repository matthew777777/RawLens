// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// hdrplus host: Vulkan context, SPIR-V load, run arena, descriptors, record.
#include "hdrplus_internal.h"

#include "hdrplus_platform.h"

#include <algorithm>
#include <cstring>
#include <utility>

namespace rawlens {
namespace hdrplus {
namespace {

constexpr VkDescriptorType kImg = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
constexpr VkDescriptorType kBuf = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;

// Binding tables transcribed from the vendored GLSL (see host probing in
// development: every table entry cross-checked against both the shader's
// `layout(binding = N)` lines and RAWR's record() call sites).
const BindingInfo kPrepare[] = {{0, kImg}, {1, kImg}};
const BindingInfo kHotPixel[] = {{0, kImg}, {1, kBuf}};
const BindingInfo kAvgPool[] = {{0, kImg}, {1, kImg}};
const BindingInfo kBlur[] = {{0, kImg}, {1, kImg}};
const BindingInfo kUpsampleAlign[] = {{0, kBuf}, {1, kBuf}};
const BindingInfo kCorrectUpsampling[] = {{0, kImg}, {1, kImg}, {2, kBuf}, {3, kBuf}};
const BindingInfo kTileDiff[] = {{0, kImg}, {1, kImg}, {2, kBuf}, {3, kBuf}};
const BindingInfo kBestTile[] = {{0, kBuf}, {1, kBuf}, {2, kBuf}};
const BindingInfo kWarp[] = {{0, kImg}, {1, kImg}, {2, kBuf}};
const BindingInfo kColorDiff[] = {{0, kImg}, {1, kImg}, {2, kImg}};
const BindingInfo kColumnSum[] = {{0, kImg}, {1, kBuf}};
const BindingInfo kMean[] = {{0, kBuf}, {1, kBuf}};
const BindingInfo kMergeWeight[] = {{0, kImg}, {1, kImg}, {2, kBuf}};
const BindingInfo kAccumulate[] = {{0, kImg}, {1, kImg}, {2, kImg}, {3, kImg}};
const BindingInfo kFinalize[] = {{0, kImg}, {1, kImg}, {2, kImg}};
const BindingInfo kToRgba[] = {{0, kImg}, {1, kImg}};
const BindingInfo kWarpRgba[] = {{0, kImg}, {1, kImg}, {2, kBuf}};
const BindingInfo kRms[] = {{0, kImg}, {1, kImg}};
const BindingInfo kMismatch[] = {{0, kImg}, {1, kImg}, {2, kImg}, {3, kImg}};
const BindingInfo kRegionMean[] = {{0, kImg}, {1, kBuf}};
const BindingInfo kMismatchNorm[] = {{0, kImg}, {1, kImg}, {2, kBuf}};
const BindingInfo kForwardDft[] = {{0, kImg}, {1, kImg}};
const BindingInfo kMerge[] = {{0, kImg}, {1, kImg}, {2, kImg}, {3, kImg}, {4, kImg}, {5, kBuf}};
const BindingInfo kDeconvolute[] = {{0, kImg}, {1, kImg}};
const BindingInfo kBackwardDft[] = {{0, kImg}, {1, kImg}};
const BindingInfo kBorder[] = {{0, kImg}, {1, kImg}};
const BindingInfo kFreqAccumulate[] = {{0, kImg}, {1, kImg}};
const BindingInfo kShiftTable[] = {{0, kBuf}};

#define LAYOUT(name) \
    { k##name, static_cast<std::uint32_t>(sizeof(k##name) / sizeof(k##name[0])) }

const char* kAssetNames[kShaderCount] = {
    "hdrp_prepare", "hdrp_hot_pixel", "hdrp_avg_pool", "hdrp_blur", "hdrp_upsample_align",
    "hdrp_correct_upsampling", "hdrp_tile_diff", "hdrp_best_tile", "hdrp_warp", "hdrp_color_diff",
    "hdrp_column_sum", "hdrp_mean", "hdrp_merge_weight", "hdrp_accumulate", "hdrp_finalize",
    "hdrq_to_rgba", "hdrq_warp_rgba", "hdrq_rms", "hdrq_mismatch", "hdrq_region_mean",
    "hdrq_mismatch_norm", "hdrq_forward_dft", "hdrq_merge", "hdrq_deconvolute", "hdrq_backward_dft",
    "hdrq_border", "hdrq_accumulate", "hdrq_shift_table",
};

const ProgramLayout kLayouts[kShaderCount] = {
    LAYOUT(Prepare), LAYOUT(HotPixel), LAYOUT(AvgPool), LAYOUT(Blur), LAYOUT(UpsampleAlign),
    LAYOUT(CorrectUpsampling), LAYOUT(TileDiff), LAYOUT(BestTile), LAYOUT(Warp), LAYOUT(ColorDiff),
    LAYOUT(ColumnSum), LAYOUT(Mean), LAYOUT(MergeWeight), LAYOUT(Accumulate), LAYOUT(Finalize),
    LAYOUT(ToRgba), LAYOUT(WarpRgba), LAYOUT(Rms), LAYOUT(Mismatch), LAYOUT(RegionMean),
    LAYOUT(MismatchNorm), LAYOUT(ForwardDft), LAYOUT(Merge), LAYOUT(Deconvolute), LAYOUT(BackwardDft),
    LAYOUT(Border), LAYOUT(FreqAccumulate), LAYOUT(ShiftTable),
};

bool find_memory_type(VkPhysicalDevice physical, std::uint32_t bits, VkMemoryPropertyFlags want,
                      std::uint32_t* index) {
    VkPhysicalDeviceMemoryProperties props{};
    vkGetPhysicalDeviceMemoryProperties(physical, &props);
    for (std::uint32_t i = 0; i < props.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & want) == want) {
            *index = i;
            return true;
        }
    }
    return false;
}

}  // namespace

const char* shader_asset_name(Shader s) { return kAssetNames[static_cast<std::uint32_t>(s)]; }

const ProgramLayout& shader_layout(Shader s) { return kLayouts[static_cast<std::uint32_t>(s)]; }

GpuImage* RunArena::image(VkFormat format, std::uint32_t w, std::uint32_t h, std::string& err) {
    VkImageCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = format;
    ci.extent = {w, h, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    GpuImage img;
    img.format = format;
    img.width = w;
    img.height = h;
    if (vkCreateImage(device_, &ci, nullptr, &img.image) != VK_SUCCESS) {
        err = "hdrplus: vkCreateImage failed";
        return nullptr;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(device_, img.image, &req);
    std::uint32_t type = 0;
    if (!find_memory_type(physical_, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &type)) {
        err = "hdrplus: no device-local memory type for image";
        vkDestroyImage(device_, img.image, nullptr);
        return nullptr;
    }
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    if (vkAllocateMemory(device_, &ai, nullptr, &img.memory) != VK_SUCCESS) {
        err = "hdrplus: image memory allocation failed (OOM?)";
        vkDestroyImage(device_, img.image, nullptr);
        return nullptr;
    }
    vkBindImageMemory(device_, img.image, img.memory, 0);
    VkImageViewCreateInfo vi{};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = img.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (vkCreateImageView(device_, &vi, nullptr, &img.view) != VK_SUCCESS) {
        err = "hdrplus: vkCreateImageView failed";
        vkFreeMemory(device_, img.memory, nullptr);
        vkDestroyImage(device_, img.image, nullptr);
        return nullptr;
    }
    images_.push_back(img);
    return &images_.back();
}

GpuBuffer* RunArena::buffer(VkDeviceSize bytes, std::string& err) {
    VkBufferCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    ci.size = bytes ? bytes : 1;
    ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
               VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    GpuBuffer buf;
    buf.bytes = ci.size;
    if (vkCreateBuffer(device_, &ci, nullptr, &buf.buffer) != VK_SUCCESS) {
        err = "hdrplus: vkCreateBuffer failed";
        return nullptr;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, buf.buffer, &req);
    std::uint32_t type = 0;
    if (!find_memory_type(physical_, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &type)) {
        err = "hdrplus: no device-local memory type for buffer";
        vkDestroyBuffer(device_, buf.buffer, nullptr);
        return nullptr;
    }
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    if (vkAllocateMemory(device_, &ai, nullptr, &buf.memory) != VK_SUCCESS) {
        err = "hdrplus: buffer memory allocation failed (OOM?)";
        vkDestroyBuffer(device_, buf.buffer, nullptr);
        return nullptr;
    }
    vkBindBufferMemory(device_, buf.buffer, buf.memory, 0);
    buffers_.push_back(buf);
    return &buffers_.back();
}

GpuBuffer* RunArena::staging(VkDeviceSize bytes, std::string& err) {
    VkBufferCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    ci.size = bytes ? bytes : 1;
    ci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    GpuBuffer buf;
    buf.bytes = ci.size;
    if (vkCreateBuffer(device_, &ci, nullptr, &buf.buffer) != VK_SUCCESS) {
        err = "hdrplus: vkCreateBuffer (staging) failed";
        return nullptr;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device_, buf.buffer, &req);
    std::uint32_t type = 0;
    const VkMemoryPropertyFlags want =
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    if (!find_memory_type(physical_, req.memoryTypeBits, want, &type)) {
        err = "hdrplus: no host-visible memory type for staging";
        vkDestroyBuffer(device_, buf.buffer, nullptr);
        return nullptr;
    }
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    if (vkAllocateMemory(device_, &ai, nullptr, &buf.memory) != VK_SUCCESS) {
        err = "hdrplus: staging allocation failed (OOM?)";
        vkDestroyBuffer(device_, buf.buffer, nullptr);
        return nullptr;
    }
    vkBindBufferMemory(device_, buf.buffer, buf.memory, 0);
    if (vkMapMemory(device_, buf.memory, 0, req.size, 0, &buf.mapped) != VK_SUCCESS) {
        err = "hdrplus: staging map failed";
        vkFreeMemory(device_, buf.memory, nullptr);
        vkDestroyBuffer(device_, buf.buffer, nullptr);
        return nullptr;
    }
    buffers_.push_back(buf);
    return &buffers_.back();
}

void RunArena::transition_all_to_general(VkCommandBuffer cmd) {
    if (images_.empty()) return;
    std::vector<VkImageMemoryBarrier> barriers;
    barriers.reserve(images_.size());
    for (const auto& img : images_) {
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.srcAccessMask = 0;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                          VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = img.image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        barriers.push_back(b);
    }
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                         nullptr, 0, nullptr, static_cast<std::uint32_t>(barriers.size()),
                         barriers.data());
}

void RunArena::reset() noexcept {
    if (device_ == VK_NULL_HANDLE) return;
    for (auto& img : images_) {
        if (img.view) vkDestroyImageView(device_, img.view, nullptr);
        if (img.image) vkDestroyImage(device_, img.image, nullptr);
        if (img.memory) vkFreeMemory(device_, img.memory, nullptr);
    }
    images_.clear();
    for (auto& buf : buffers_) {
        if (buf.mapped) vkUnmapMemory(device_, buf.memory);
        if (buf.buffer) vkDestroyBuffer(device_, buf.buffer, nullptr);
        if (buf.memory) vkFreeMemory(device_, buf.memory, nullptr);
    }
    buffers_.clear();
}

bool RunDescriptors::init(std::string& err) {
    // One set per dispatch; the frequency path records ~4.5k dispatches for
    // a 30-frame burst, so size generously (pool entries are just caps).
    VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 65536},
                                     {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 32768}};
    VkDescriptorPoolCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    ci.maxSets = 16384;
    ci.poolSizeCount = 2;
    ci.pPoolSizes = sizes;
    if (vkCreateDescriptorPool(device_, &ci, nullptr, &pool_) != VK_SUCCESS) {
        err = "hdrplus: descriptor pool creation failed";
        return false;
    }
    return true;
}

VkDescriptorSet RunDescriptors::snapshot(const Program& program, const ImageBinding* images,
                                         std::uint32_t image_count, const BufferBinding* buffers,
                                         std::uint32_t buffer_count, std::string& err) {
    VkDescriptorSetAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    ai.descriptorPool = pool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &program.dsl;
    VkDescriptorSet set = VK_NULL_HANDLE;
    if (vkAllocateDescriptorSets(device_, &ai, &set) != VK_SUCCESS) {
        err = "hdrplus: descriptor set allocation failed";
        return VK_NULL_HANDLE;
    }
    std::vector<VkWriteDescriptorSet> writes;
    std::vector<VkDescriptorImageInfo> image_infos(image_count);
    std::vector<VkDescriptorBufferInfo> buffer_infos(buffer_count);
    writes.reserve(image_count + buffer_count);
    for (std::uint32_t i = 0; i < image_count; ++i) {
        image_infos[i] = {VK_NULL_HANDLE, images[i].view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet w{};
        w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet = set;
        w.dstBinding = images[i].binding;
        w.descriptorCount = 1;
        w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        w.pImageInfo = &image_infos[i];
        writes.push_back(w);
    }
    for (std::uint32_t i = 0; i < buffer_count; ++i) {
        buffer_infos[i] = {buffers[i].buffer, buffers[i].offset, buffers[i].range};
        VkWriteDescriptorSet w{};
        w.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w.dstSet = set;
        w.dstBinding = buffers[i].binding;
        w.descriptorCount = 1;
        w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w.pBufferInfo = &buffer_infos[i];
        writes.push_back(w);
    }
    vkUpdateDescriptorSets(device_, static_cast<std::uint32_t>(writes.size()), writes.data(), 0,
                           nullptr);
    return set;
}

void RunDescriptors::reset() noexcept {
    if (device_ != VK_NULL_HANDLE && pool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device_, pool_, nullptr);
        pool_ = VK_NULL_HANDLE;
    }
}

bool record(HdrPlusContext* ctx, RunDescriptors& desc, VkCommandBuffer cmd, Shader shader,
            const ImageBinding* images, std::uint32_t image_count, const BufferBinding* buffers,
            std::uint32_t buffer_count, const void* push, std::uint32_t push_bytes, std::uint32_t gx,
            std::uint32_t gy, std::string& err) {
    const Program& program = ctx->programs[static_cast<std::uint32_t>(shader)];
    if (program.pipeline == VK_NULL_HANDLE) {
        err = "hdrplus: pipeline not loaded";
        return false;
    }
    VkDescriptorSet set = desc.snapshot(program, images, image_count, buffers, buffer_count, err);
    if (set == VK_NULL_HANDLE) return false;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, program.pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, program.layout, 0, 1, &set, 0,
                            nullptr);
    if (push != nullptr && push_bytes > 0) {
        vkCmdPushConstants(cmd, program.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push_bytes, push);
    }
    vkCmdDispatch(cmd, gx, gy, 1);
    return true;
}

void compute_write_barrier(VkCommandBuffer cmd) {
    VkMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &b, 0, nullptr, 0, nullptr);
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

int hdrplus_shader_count(void) { return 28; }

const char* hdrplus_shader_name(int i) {
    if (i < 0 || i >= 28) return nullptr;
    return rawlens::hdrplus::shader_asset_name(static_cast<rawlens::hdrplus::Shader>(i));
}

HdrPlusContext* hdrplus_create(char* errmsg, size_t errmsg_len) {
    std::string err;
    auto fail = [&](const std::string& m) -> HdrPlusContext* {
        set_err(errmsg, errmsg_len, m);
        return nullptr;
    };
    auto* ctx = new (std::nothrow) HdrPlusContext();
    if (ctx == nullptr) return fail("hdrplus: out of memory");
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "RawLens HDR+";
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;
    if (vkCreateInstance(&ici, nullptr, &ctx->instance) != VK_SUCCESS) {
        delete ctx;
        return fail("hdrplus: vkCreateInstance failed");
    }
    std::uint32_t device_count = 0;
    vkEnumeratePhysicalDevices(ctx->instance, &device_count, nullptr);
    if (device_count == 0) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return fail("hdrplus: no Vulkan physical devices");
    }
    std::vector<VkPhysicalDevice> devices(device_count);
    vkEnumeratePhysicalDevices(ctx->instance, &device_count, devices.data());
    // Prefer a discrete GPU, else the first device with a compute queue.
    VkPhysicalDevice chosen = VK_NULL_HANDLE;
    std::uint32_t queue_family = 0;
    for (int pass = 0; pass < 2 && chosen == VK_NULL_HANDLE; ++pass) {
        for (auto dev : devices) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(dev, &props);
            if (pass == 0 && props.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) continue;
            std::uint32_t family_count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(dev, &family_count, nullptr);
            std::vector<VkQueueFamilyProperties> families(family_count);
            vkGetPhysicalDeviceQueueFamilyProperties(dev, &family_count, families.data());
            for (std::uint32_t i = 0; i < family_count; ++i) {
                if (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                    chosen = dev;
                    queue_family = i;
                    break;
                }
            }
            if (chosen != VK_NULL_HANDLE) break;
        }
    }
    if (chosen == VK_NULL_HANDLE) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return fail("hdrplus: no compute queue found");
    }
    ctx->physical = chosen;
    ctx->queue_family = queue_family;
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(chosen, &props);
    ctx->timestamp_period_ns = static_cast<double>(props.limits.timestampPeriod);
    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = queue_family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci{};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    // No extra features: R16UI/R32F/RGBA32F/RGBA16F storage images are core.
    if (vkCreateDevice(chosen, &dci, nullptr, &ctx->device) != VK_SUCCESS) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return fail("hdrplus: vkCreateDevice failed");
    }
    vkGetDeviceQueue(ctx->device, queue_family, 0, &ctx->queue);
    VkCommandPoolCreateInfo pci{};
    pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pci.queueFamilyIndex = queue_family;
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(ctx->device, &pci, nullptr, &ctx->pool) != VK_SUCCESS) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return fail("hdrplus: command pool creation failed");
    }
    VkPhysicalDeviceProperties chosen_props{};
    vkGetPhysicalDeviceProperties(chosen, &chosen_props);
    HP_LOGI("hdrplus device: %s", chosen_props.deviceName);
    return ctx;
}

int hdrplus_load_shaders(HdrPlusContext* ctx, void* assetMgr, char* errmsg, size_t errmsg_len) {
    if (ctx == nullptr) {
        set_err(errmsg, errmsg_len, "hdrplus: null context");
        return -1;
    }
#ifdef __ANDROID__
    AAssetManager* mgr = static_cast<AAssetManager*>(assetMgr);
    if (mgr == nullptr) {
        set_err(errmsg, errmsg_len, "hdrplus: null asset manager");
        return -1;
    }
#else
    const char* baseDir = static_cast<const char*>(assetMgr);
    if (baseDir == nullptr) {
        set_err(errmsg, errmsg_len, "hdrplus: null desktop asset base dir");
        return -1;
    }
#endif
    for (std::uint32_t i = 0; i < rawlens::hdrplus::kShaderCount; ++i) {
        const char* name = rawlens::hdrplus::shader_asset_name(static_cast<rawlens::hdrplus::Shader>(i));
        std::string path = std::string("spirv/hdrplus/") + name + ".spv";
#ifdef __ANDROID__
        AAsset* asset = AAssetManager_open(mgr, path.c_str(), AASSET_MODE_BUFFER);
        if (asset == nullptr) {
            set_err(errmsg, errmsg_len, "hdrplus: missing asset " + path);
            return -1;
        }
        const auto* bytes = static_cast<const std::uint32_t*>(AAsset_getBuffer(asset));
        const size_t size = AAsset_getLength(asset);
#else
        const std::string full = std::string(baseDir) + "/" + path;
        FILE* f = std::fopen(full.c_str(), "rb");
        if (f == nullptr) {
            set_err(errmsg, errmsg_len, "hdrplus: missing file " + full);
            return -1;
        }
        std::fseek(f, 0, SEEK_END);
        const long size = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        std::vector<std::uint32_t> words(size > 0 ? (static_cast<size_t>(size) + 3) / 4 : 0);
        const size_t got = size > 0 ? std::fread(words.data(), 1, static_cast<size_t>(size), f) : 0;
        std::fclose(f);
        const auto* bytes = words.data();
#endif
        bool ok = bytes != nullptr && size >= 4 && size % 4 == 0;
#ifdef __ANDROID__
        (void)0;
#else
        ok = ok && got == static_cast<size_t>(size);
#endif
        VkShaderModule module = VK_NULL_HANDLE;
        if (ok) {
            VkShaderModuleCreateInfo mci{};
            mci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
            mci.codeSize = static_cast<size_t>(size);
            mci.pCode = bytes;
            ok = vkCreateShaderModule(ctx->device, &mci, nullptr, &module) == VK_SUCCESS;
        }
#ifdef __ANDROID__
        AAsset_close(asset);
#endif
        if (!ok) {
            set_err(errmsg, errmsg_len, "hdrplus: bad SPIR-V " + path);
            return -1;
        }
        const rawlens::hdrplus::ProgramLayout& layout_info =
            rawlens::hdrplus::shader_layout(static_cast<rawlens::hdrplus::Shader>(i));
        std::vector<VkDescriptorSetLayoutBinding> bindings;
        for (std::uint32_t b = 0; b < layout_info.binding_count; ++b) {
            VkDescriptorSetLayoutBinding binding{};
            binding.binding = layout_info.bindings[b].binding;
            binding.descriptorType = layout_info.bindings[b].type;
            binding.descriptorCount = 1;
            binding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings.push_back(binding);
        }
        rawlens::hdrplus::Program program;
        VkDescriptorSetLayoutCreateInfo dci{};
        dci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        dci.bindingCount = static_cast<std::uint32_t>(bindings.size());
        dci.pBindings = bindings.data();
        ok = vkCreateDescriptorSetLayout(ctx->device, &dci, nullptr, &program.dsl) == VK_SUCCESS;
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, 128};  // same range as RAWR
        if (ok) {
            VkPipelineLayoutCreateInfo lci{};
            lci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            lci.setLayoutCount = 1;
            lci.pSetLayouts = &program.dsl;
            lci.pushConstantRangeCount = 1;
            lci.pPushConstantRanges = &pr;
            ok = vkCreatePipelineLayout(ctx->device, &lci, nullptr, &program.layout) == VK_SUCCESS;
        }
        if (ok) {
            VkPipelineShaderStageCreateInfo st{};
            st.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            st.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            st.module = module;
            st.pName = "main";
            VkComputePipelineCreateInfo pci{};
            pci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
            pci.stage = st;
            pci.layout = program.layout;
            ok = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pci, nullptr,
                                          &program.pipeline) == VK_SUCCESS;
        }
        vkDestroyShaderModule(ctx->device, module, nullptr);
        if (!ok) {
            if (program.layout) vkDestroyPipelineLayout(ctx->device, program.layout, nullptr);
            if (program.dsl) vkDestroyDescriptorSetLayout(ctx->device, program.dsl, nullptr);
            set_err(errmsg, errmsg_len, std::string("hdrplus: pipeline creation failed for ") + name);
            return -1;
        }
        ctx->programs[i] = program;
        ctx->loaded_shaders++;
    }
    HP_LOGI("hdrplus: %u shader modules loaded", ctx->loaded_shaders);
    return static_cast<int>(ctx->loaded_shaders);
}

int hdrplus_loaded_shader_count(const HdrPlusContext* ctx) {
    return ctx == nullptr ? 0 : static_cast<int>(ctx->loaded_shaders);
}

void hdrplus_destroy(HdrPlusContext* ctx) {
    if (ctx == nullptr) return;
    if (ctx->device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(ctx->device);
        for (auto& program : ctx->programs) {
            if (program.pipeline) vkDestroyPipeline(ctx->device, program.pipeline, nullptr);
            if (program.layout) vkDestroyPipelineLayout(ctx->device, program.layout, nullptr);
            if (program.dsl) vkDestroyDescriptorSetLayout(ctx->device, program.dsl, nullptr);
        }
        if (ctx->pool) vkDestroyCommandPool(ctx->device, ctx->pool, nullptr);
        vkDestroyDevice(ctx->device, nullptr);
    }
    if (ctx->instance) vkDestroyInstance(ctx->instance, nullptr);
    delete ctx;
}

double hdrplus_last_gpu_ms(const HdrPlusContext* ctx) {
    return ctx == nullptr ? 0.0 : ctx->last_gpu_ms;
}

}  // extern "C"
