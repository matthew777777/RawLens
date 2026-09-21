// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vulkan zero-copy viewfinder: import the camera HAL's RAW AHardwareBuffer
// (which EGL cannot import on this gralloc) as R16_UINT, run the superpixel
// compute shader, and write the RGBA8 result into an app-allocated export AHB
// that GL re-imports for tonemap + present.
//
// All entry points run serialized on the viewfinder GL worker. Failures return
// stage codes so Kotlin falls back (EGL-direct, then the CPU sampler).
#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <cstring>
#include <map>
#include <vector>

#define LOG_TAG "RawLensVfVk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Result codes for VfVulkan.compute/ensureOutput/init.
#define VFVK_OK 0
#define VFVK_NOT_INITIALIZED 1
#define VFVK_NO_EXTENSION 2
#define VFVK_DEVICE_FAILED 3
#define VFVK_PIPELINE_FAILED 4
#define VFVK_INPUT_IMPORT_FAILED 5
#define VFVK_OUTPUT_IMPORT_FAILED 6
#define VFVK_SUBMIT_FAILED 7
#define VFVK_BAD_ARGUMENT 8

#define INPUT_CACHE_CAP 8

namespace {

// Mirrors the push-constant block in vf_superpixel.comp exactly (72 bytes).
struct SuperpixelParams {
    int32_t chans[4];
    float black[4];
    float invRange[4];
    int32_t quadBase[2];
    int32_t frameSize[2];
    int32_t step;
    int32_t pitch;
};
static_assert(sizeof(SuperpixelParams) == 72, "push constant layout drift");

struct ImportedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

// Byte-exact view of the sensor plane: no pitch/tiling ambiguity, unlike images.
struct ImportedBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
};

struct Context {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProps = nullptr;
    std::map<AHardwareBuffer*, ImportedBuffer> inputs;
    std::vector<AHardwareBuffer*> inputOrder;
    AHardwareBuffer* outputBuffer = nullptr;
    ImportedImage output;
};

Context* g = nullptr;

uint32_t pickMemoryType(uint32_t bits, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) {
        if (bits & (1u << i)) return i;
    }
    return UINT32_MAX;
}

void destroyImported(Context* ctx, ImportedImage* img) {
    if (img->view != VK_NULL_HANDLE) vkDestroyImageView(ctx->device, img->view, nullptr);
    if (img->image != VK_NULL_HANDLE) vkDestroyImage(ctx->device, img->image, nullptr);
    if (img->memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, img->memory, nullptr);
    *img = ImportedImage{};
}

void destroyImportedBuffer(Context* ctx, ImportedBuffer* buf) {
    if (buf->buffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, buf->buffer, nullptr);
    if (buf->memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, buf->memory, nullptr);
    *buf = ImportedBuffer{};
}

// Import an AHB as a Vulkan image per the reference flow: query format
// properties, create with the reported VkFormat (or externalFormat), dedicated
// import-allocate, bind, view. Does NOT acquire the caller's buffer.
// Tiling: HAL camera buffers are linear (CPU-visible, rowStride-pitched), so the
// input tries LINEAR first — importing them as OPTIMAL lets the driver
// misinterpret the layout (observed: striped mis-samples). The app-allocated
// export buffer stays OPTIMAL (proven correct end-to-end by the grid test).
int importAhb(Context* ctx, AHardwareBuffer* buf, VkImageUsageFlags usage,
              const char* tag, bool linearFirst, ImportedImage* out) {
    *out = ImportedImage{};
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
    fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ahbProps.pNext = &fmtProps;
    VkResult r = ctx->getAhbProps(ctx->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: ahb-props failed %d", tag, r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    LOGI("vf-vk: %s: %ux%u ahbFormat=%u vkFormat=%d external=%llu size=%llu types=%#x", tag,
         desc.width, desc.height, desc.format, fmtProps.format,
         (unsigned long long)fmtProps.externalFormat,
         (unsigned long long)ahbProps.allocationSize, ahbProps.memoryTypeBits);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryType(ahbProps.memoryTypeBits, memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: %s: no memory type", tag);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    const bool useExternal = fmtProps.format == VK_FORMAT_UNDEFINED;
    if (useExternal && fmtProps.externalFormat == 0) {
        LOGW("vf-vk: %s: no usable format", tag);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkExternalFormatANDROID vkExtFmt{};
    vkExtFmt.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    vkExtFmt.externalFormat = fmtProps.externalFormat;
    VkExternalMemoryImageCreateInfo extImg{};
    extImg.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImg.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    if (useExternal) extImg.pNext = &vkExtFmt;
    const VkImageTiling tilings[2] = {
        linearFirst ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_OPTIMAL,
        linearFirst ? VK_IMAGE_TILING_OPTIMAL : VK_IMAGE_TILING_LINEAR,
    };
    const char* tilingNames[2] = {linearFirst ? "LINEAR" : "OPTIMAL", linearFirst ? "OPTIMAL" : "LINEAR"};
    for (int t = 0; t < 2; ++t) {
        VkImageCreateInfo imgInfo{};
        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imgInfo.pNext = &extImg;
        imgInfo.imageType = VK_IMAGE_TYPE_2D;
        imgInfo.format = useExternal ? VK_FORMAT_UNDEFINED : fmtProps.format;
        imgInfo.extent.width = desc.width;
        imgInfo.extent.height = desc.height;
        imgInfo.extent.depth = 1;
        imgInfo.mipLevels = 1;
        imgInfo.arrayLayers = 1;
        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imgInfo.tiling = tilings[t];
        imgInfo.usage = usage;
        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        r = vkCreateImage(ctx->device, &imgInfo, nullptr, &out->image);
        if (r != VK_SUCCESS) {
            LOGW("vf-vk: %s: image create (%s) failed %d (usage=%#x)", tag, tilingNames[t], r, usage);
            continue;
        }
        LOGI("vf-vk: %s: image tiling=%s", tag, tilingNames[t]);
        break;
    }
    if (out->image == VK_NULL_HANDLE) return VFVK_INPUT_IMPORT_FAILED;
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = out->image;
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buf;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = &import;
    alloc.allocationSize = ahbProps.allocationSize;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: import alloc failed %d", tag, r);
        vkDestroyImage(ctx->device, out->image, nullptr);
        *out = ImportedImage{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    r = vkBindImageMemory(ctx->device, out->image, out->memory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: bind failed %d", tag, r);
        vkFreeMemory(ctx->device, out->memory, nullptr);
        vkDestroyImage(ctx->device, out->image, nullptr);
        *out = ImportedImage{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = out->image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = useExternal ? VK_FORMAT_UNDEFINED : fmtProps.format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    r = vkCreateImageView(ctx->device, &viewInfo, nullptr, &out->view);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: view failed %d", tag, r);
        destroyImported(ctx, out);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    return VFVK_OK;
}

// Import an AHB as a storage buffer: byte-exact, no pitch/tiling/format risk.
// The shader does explicit (y * pitch + x) addressing with the Image plane's
// stride, the same value the correct CPU path uses.
int importInputBuffer(Context* ctx, AHardwareBuffer* buf, ImportedBuffer* out) {
    *out = ImportedBuffer{};
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    VkResult r = ctx->getAhbProps(ctx->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: ahb-props failed %d", r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryType(ahbProps.memoryTypeBits, memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: input: no memory type");
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkExternalMemoryBufferCreateInfo extBuf{};
    extBuf.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    extBuf.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.pNext = &extBuf;
    bufInfo.size = ahbProps.allocationSize;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    r = vkCreateBuffer(ctx->device, &bufInfo, nullptr, &out->buffer);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: buffer create failed %d", r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx->device, out->buffer, &req);
    if (req.size > ahbProps.allocationSize) {
        LOGW("vf-vk: input: requirements %llu exceed allocation %llu",
             (unsigned long long)req.size, (unsigned long long)ahbProps.allocationSize);
        vkDestroyBuffer(ctx->device, out->buffer, nullptr);
        *out = ImportedBuffer{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.buffer = out->buffer;
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buf;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = &import;
    alloc.allocationSize = ahbProps.allocationSize;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: import alloc failed %d", r);
        vkDestroyBuffer(ctx->device, out->buffer, nullptr);
        *out = ImportedBuffer{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    r = vkBindBufferMemory(ctx->device, out->buffer, out->memory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: bind failed %d", r);
        destroyImportedBuffer(ctx, out);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    LOGI("vf-vk: input: buffer import ok size=%llu", (unsigned long long)ahbProps.allocationSize);
    return VFVK_OK;
}

void layoutBarrier(VkCommandBuffer cmd, VkImage image,
                   VkPipelineStageFlags srcStage, VkAccessFlags srcAccess,
                   VkPipelineStageFlags dstStage, VkAccessFlags dstAccess,
                   VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

bool createPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->setLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(SuperpixelParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->setLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->pipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->setLayout, nullptr);
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->pipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, &ctx->pipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: compute pipeline failed %d", r);
        return false;
    }
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->descriptorPool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->setLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->descriptorSet) != VK_SUCCESS) {
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_initNative(
    JNIEnv* env, jobject, jbyteArray spv) {
    if (g != nullptr) return VFVK_OK;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;

    Context* ctx = new Context();
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "RawLensVf";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &app;
    if (vkCreateInstance(&instInfo, nullptr, &ctx->instance) != VK_SUCCESS) {
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    uint32_t gpuCount = 0;
    vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, nullptr);
    VkPhysicalDevice gpus[8];
    uint32_t n = gpuCount > 8 ? 8 : gpuCount;
    if (n == 0 || vkEnumeratePhysicalDevices(ctx->instance, &n, gpus) != VK_SUCCESS) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    for (uint32_t i = 0; i < n && ctx->gpu == VK_NULL_HANDLE; ++i) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &qCount, nullptr);
        VkQueueFamilyProperties props[16];
        uint32_t m = qCount > 16 ? 16 : qCount;
        vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &m, props);
        for (uint32_t q = 0; q < m; ++q) {
            if ((props[q].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) != 0) {
                ctx->gpu = gpus[i];
                ctx->queueFamily = q;
                break;
            }
        }
    }
    if (ctx->gpu == VK_NULL_HANDLE) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(ctx->gpu, nullptr, &extCount, nullptr);
    bool hasExternalMem = false, hasAhb = false;
    {
        uint32_t cap = extCount > 256 ? 256 : extCount;
        std::vector<VkExtensionProperties> exts(cap);
        if (vkEnumerateDeviceExtensionProperties(ctx->gpu, nullptr, &cap, exts.data()) == VK_SUCCESS) {
            for (uint32_t i = 0; i < cap; ++i) {
                if (!std::strcmp(exts[i].extensionName, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME))
                    hasExternalMem = true;
                if (!std::strcmp(exts[i].extensionName,
                                 VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME))
                    hasAhb = true;
            }
        }
    }
    if (!hasExternalMem || !hasAhb) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_NO_EXTENSION;
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = ctx->queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* devExt[] = {VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                            VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME};
    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &queueInfo;
    devInfo.enabledExtensionCount = 2;
    devInfo.ppEnabledExtensionNames = devExt;
    if (vkCreateDevice(ctx->gpu, &devInfo, nullptr, &ctx->device) != VK_SUCCESS) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    vkGetDeviceQueue(ctx->device, ctx->queueFamily, 0, &ctx->queue);
    ctx->getAhbProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(ctx->device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!ctx->getAhbProps) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_NO_EXTENSION;
    }
    if (!createPipeline(ctx, code.data(), code.size())) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_PIPELINE_FAILED;
    }
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = ctx->queueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(ctx->device, &poolInfo, nullptr, &ctx->commandPool) != VK_SUCCESS) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->commandBuffer) != VK_SUCCESS) {
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(ctx->device, &fenceInfo, nullptr, &ctx->fence) != VK_SUCCESS) {
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(ctx->gpu, &props);
    LOGI("vf-vk: device ready: %s", props.deviceName);
    g = ctx;
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_ensureOutputNative(
    JNIEnv* env, jobject, jobject outputBuffer) {
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!outputBuffer) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, outputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == buf) return VFVK_OK;
    destroyImported(g, &g->output);
    if (g->outputBuffer) AHardwareBuffer_release(g->outputBuffer);
    g->outputBuffer = nullptr;
    int r = importAhb(g, buf, VK_IMAGE_USAGE_STORAGE_BIT, "output", false, &g->output);
    if (r != VFVK_OK) {
        LOGW("vf-vk: output storage import failed");
        return VFVK_OUTPUT_IMPORT_FAILED;
    }
    AHardwareBuffer_acquire(buf);
    g->outputBuffer = buf;
    LOGI("vf-vk: output import ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeNative(
    JNIEnv* env, jobject, jobject inputBuffer, jintArray iparams, jfloatArray fparams) {
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!inputBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;

    // Get-or-import the input (AHB pool cycles, so cache by pointer with FIFO evict).
    auto it = g->inputs.find(buf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, buf, &imported);
        if (r != VFVK_OK) return VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            AHardwareBuffer* oldest = g->inputOrder.front();
            g->inputOrder.erase(g->inputOrder.begin());
            auto oit = g->inputs.find(oldest);
            if (oit != g->inputs.end()) {
                destroyImportedBuffer(g, &oit->second);
                g->inputs.erase(oit);
            }
            AHardwareBuffer_release(oldest);
        }
        AHardwareBuffer_acquire(buf);
        g->inputs[buf] = imported;
        g->inputOrder.push_back(buf);
        it = g->inputs.find(buf);
    }
    const ImportedBuffer& input = it->second;

    SuperpixelParams params{};
    jint ipairs[10];
    jfloat fppairs[8];
    env->GetIntArrayRegion(iparams, 0, 10, ipairs);
    env->GetFloatArrayRegion(fparams, 0, 8, fppairs);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    for (int i = 0; i < 4; ++i) params.chans[i] = ipairs[i];
    for (int i = 0; i < 4; ++i) params.black[i] = fppairs[i];
    for (int i = 0; i < 4; ++i) params.invRange[i] = fppairs[4 + i];
    params.quadBase[0] = ipairs[4];
    params.quadBase[1] = ipairs[5];
    params.frameSize[0] = ipairs[6];
    params.frameSize[1] = ipairs[7];
    params.step = ipairs[8];
    params.pitch = ipairs[9];
    if (params.frameSize[0] <= 0 || params.frameSize[1] <= 0 ||
        params.frameSize[0] > 960 || params.frameSize[1] > 960 ||
        params.pitch <= 0 || params.pitch > 16384) {
        return VFVK_BAD_ARGUMENT;
    }

    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = input.buffer;
    inputInfo.offset = 0;
    inputInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = g->output.view;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inputInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outputInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(g->commandBuffer, &begin) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    // Adopt the HAL's fresh contents (buffer barrier + cache invalidate).
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = 0;
    bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bufBarrier.buffer = input.buffer;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->pipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->pipelineLayout, 0, 1, &g->descriptorSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.frameSize[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.frameSize[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    // Make writes available for the cross-API (GL) consumer before queue idle.
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL);
    if (vkEndCommandBuffer(g->commandBuffer) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    if (vkQueueSubmit(g->queue, 1, &submit, g->fence) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    // Serial correctness over pipelining for v1: the GL import below must see
    // finished writes. A timeline-semaphore export is the follow-up, not v1.
    if (vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, 2000000000ull) != VK_SUCCESS) {
        LOGW("vf-vk: fence wait failed; retiring submitted RAW reads before release");
        // The caller releases its camera Image lease on return. A timed-out fence
        // alone is not permission to let the HAL overwrite that allocation.
        vkQueueWaitIdle(g->queue);
        return VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfVulkan_resetNative(JNIEnv*, jobject) {
    if (g == nullptr) return;
    vkDeviceWaitIdle(g->device);
    for (auto& entry : g->inputs) destroyImportedBuffer(g, &entry.second);
    for (AHardwareBuffer* b : g->inputOrder) AHardwareBuffer_release(b);
    g->inputs.clear();
    g->inputOrder.clear();
    destroyImported(g, &g->output);
    if (g->outputBuffer) AHardwareBuffer_release(g->outputBuffer);
    g->outputBuffer = nullptr;
}
