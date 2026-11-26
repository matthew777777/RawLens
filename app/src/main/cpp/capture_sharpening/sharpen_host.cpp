// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Capture sharpening host: owns a private Vulkan compute device; one blocking
// process() call sharpens a pre-WB camera-linear RGBA16F frame in place.
// Single-threaded by contract.
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "capture_sharpening/CaptureSharpening.h"

#define SHARPEN_FENCE_TIMEOUT_NS 30000000000ull

namespace {

void check(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(what);
}

uint32_t find_memory(VkPhysicalDevice pd, uint32_t bits, VkMemoryPropertyFlags need,
                     VkMemoryPropertyFlags want = 0) {
    VkPhysicalDeviceMemoryProperties p{};
    vkGetPhysicalDeviceMemoryProperties(pd, &p);
    for (int pass = 0; pass < 2; ++pass) {
        for (uint32_t i = 0; i < p.memoryTypeCount; ++i) {
            if ((bits & (1u << i)) == 0) continue;
            const auto flags = p.memoryTypes[i].propertyFlags;
            if ((flags & need) != need) continue;
            if (pass == 0 && want != 0 && (flags & want) != want) continue;
            return i;
        }
    }
    throw std::runtime_error("capture sharpening: no suitable memory type");
}

struct OwnedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

struct OwnedBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* mapped = nullptr;
    VkDeviceSize size = 0;
    bool coherent = true;
};

class SharpenHost {
   public:
    SharpenHost() {
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "RawLensSharpen";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ci.pApplicationInfo = &app;
        check(vkCreateInstance(&ci, nullptr, &instance_), "capture sharpening: vkCreateInstance failed");

        uint32_t devCount = 0;
        check(vkEnumeratePhysicalDevices(instance_, &devCount, nullptr),
              "capture sharpening: vkEnumeratePhysicalDevices failed");
        if (devCount == 0) throw std::runtime_error("capture sharpening: no Vulkan physical device");
        std::vector<VkPhysicalDevice> devs(devCount);
        check(vkEnumeratePhysicalDevices(instance_, &devCount, devs.data()),
              "capture sharpening: vkEnumeratePhysicalDevices failed");
        uint32_t qfi = UINT32_MAX;
        for (auto pd : devs) {
            uint32_t n = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(pd, &n, nullptr);
            std::vector<VkQueueFamilyProperties> props(n);
            vkGetPhysicalDeviceQueueFamilyProperties(pd, &n, props.data());
            for (uint32_t i = 0; i < n; ++i) {
                if (props[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                    phys_ = pd;
                    qfi = i;
                    break;
                }
            }
            if (phys_ != VK_NULL_HANDLE) break;
        }
        if (phys_ == VK_NULL_HANDLE) throw std::runtime_error("capture sharpening: no compute queue family");
        vkGetPhysicalDeviceProperties(phys_, &props_);

        float prio = 1.0f;
        VkDeviceQueueCreateInfo qi{};
        qi.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qi.queueFamilyIndex = qfi;
        qi.queueCount = 1;
        qi.pQueuePriorities = &prio;
        VkDeviceCreateInfo di{};
        di.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        di.queueCreateInfoCount = 1;
        di.pQueueCreateInfos = &qi;
        VkPhysicalDeviceFeatures supported{};
        vkGetPhysicalDeviceFeatures(phys_, &supported);
        VkPhysicalDeviceFeatures enabled{};
        enabled.shaderStorageImageExtendedFormats = supported.shaderStorageImageExtendedFormats;
        // Mali-G615: without robust buffer access, RAWR then RawLens at 12 MP on
        // one device reproducibly lost the device (layout-dependent; no OOB found
        // in our shaders). Measured cost: none.
        enabled.robustBufferAccess = supported.robustBufferAccess;
        di.pEnabledFeatures = &enabled;
        check(vkCreateDevice(phys_, &di, nullptr, &device_), "capture sharpening: vkCreateDevice failed");
        vkGetDeviceQueue(device_, qfi, 0, &queue_);
        queueFamily_ = qfi;

        VkCommandPoolCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pi.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pi.queueFamilyIndex = qfi;
        check(vkCreateCommandPool(device_, &pi, nullptr, &pool_), "capture sharpening: pool failed");
        VkCommandBufferAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool = pool_;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device_, &ai, &cmd_), "capture sharpening: cmd alloc failed");
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        check(vkCreateFence(device_, &fi, nullptr, &fence_), "capture sharpening: fence failed");
        VkQueryPoolCreateInfo qi2{};
        qi2.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qi2.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qi2.queryCount = 2;
        check(vkCreateQueryPool(device_, &qi2, nullptr, &tsPool_), "capture sharpening: ts pool failed");
    }

    ~SharpenHost() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        sharp_.reset();
        destroy_owned(src_);
        destroy_owned(sharpClip_);
        destroy_buffer(sharpClipStaging_);
        destroy_buffer(staging_);
        if (tsPool_ != VK_NULL_HANDLE) vkDestroyQueryPool(device_, tsPool_, nullptr);
        if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, fence_, nullptr);
        if (pool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, pool_, nullptr);
        if (device_ != VK_NULL_HANDLE) vkDestroyDevice(device_, nullptr);
        if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
    }

    SharpenHost(const SharpenHost&) = delete;
    SharpenHost& operator=(const SharpenHost&) = delete;

    void begin_commands() {
        check(vkResetFences(device_, 1, &fence_), "capture sharpening: fence reset failed");
        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(cmd_, &bi), "capture sharpening: begin failed");
    }

    void submit_and_wait() {
        check(vkEndCommandBuffer(cmd_), "capture sharpening: end failed");
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd_;
        check(vkQueueSubmit(queue_, 1, &si, fence_), "capture sharpening: submit failed");
        VkResult wr = vkWaitForFences(device_, 1, &fence_, VK_TRUE, SHARPEN_FENCE_TIMEOUT_NS);
        if (wr == VK_TIMEOUT) throw std::runtime_error("capture sharpening: GPU wait timed out");
        if (wr != VK_SUCCESS)
            throw std::runtime_error("capture sharpening: fence wait failed (VkResult " + std::to_string((int)wr) + ")");
    }

    void process(uint8_t* half, int w, int h, const float wb[3], int iterations, int cfa, const uint8_t* clipState) {
        ensure_images(w, h);
        const VkDeviceSize bytes = VkDeviceSize(w) * VkDeviceSize(h) * 8u;
        ensure_buffer(staging_, bytes,
                      VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        memcpy(staging_.mapped, half, (size_t)bytes);
        flush(staging_);
        if (clipState) {
            const VkDeviceSize clipBytes = VkDeviceSize(w / 2) * (h / 2) * 2u;
            ensure_buffer(sharpClipStaging_, clipBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            memcpy(sharpClipStaging_.mapped, clipState, size_t(clipBytes));
            flush(sharpClipStaging_);
            if (!sharpClip_.image) {
                sharpClip_ = make_image(uint32_t(w / 2), uint32_t(h / 2), VK_FORMAT_R16_UINT);
                sharpClipFresh_ = true;
            }
        }

        begin_commands();

        barrier(cmd_, src_.image, imagesFresh_ ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                imagesFresh_ ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);
        {
            VkBufferImageCopy r{};
            r.imageExtent = {(uint32_t)w, (uint32_t)h, 1};
            r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            vkCmdCopyBufferToImage(cmd_, staging_.buffer, src_.image,
                                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
        }
        barrier(cmd_, src_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        imagesFresh_ = false;
        vkCmdResetQueryPool(cmd_, tsPool_, 0, 2);
        vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, tsPool_, 0);
        if (!sharp_) sharp_ = std::make_unique<rawr::capsharp::CaptureSharpening>(
            rawr::vk::GpuContext{phys_, device_, queueFamily_});
        rawr::capsharp::RecordInfo info{};
        info.commandBuffer = cmd_;
        info.width = uint32_t(w);
        info.height = uint32_t(h);
        info.image = src_.image;
        info.view = src_.view;
        info.cfaPattern = uint32_t(cfa);
        memcpy(info.whiteBalance, wb, sizeof info.whiteBalance);
        if (clipState) {
            barrier(cmd_, sharpClip_.image, sharpClipFresh_ ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    sharpClipFresh_ ? 0 : VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    sharpClipFresh_ ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy copy{};
            copy.imageExtent = {uint32_t(w / 2), uint32_t(h / 2), 1};
            copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            vkCmdCopyBufferToImage(cmd_, sharpClipStaging_.buffer, sharpClip_.image,
                                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
            barrier(cmd_, sharpClip_.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            sharpClipFresh_ = false;
            info.clipState = sharpClip_.view;
        }
        info.params.iterations = uint32_t(iterations);
        info.deconvBandRows = 16;
        if (!sharp_->record(info)) throw std::runtime_error("capture sharpening rejected input");
        vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, tsPool_, 1);
        const VkImage resultImage = src_.image;

        barrier(cmd_, resultImage, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        {
            VkBufferImageCopy r{};
            r.imageExtent = {(uint32_t)w, (uint32_t)h, 1};
            r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            vkCmdCopyImageToBuffer(cmd_, resultImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                   staging_.buffer, 1, &r);
        }
        barrier(cmd_, resultImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        submit_and_wait();

        uint64_t ts[2] = {0, 0};
        if (vkGetQueryPoolResults(device_, tsPool_, 0, 2, sizeof(ts), ts, sizeof(uint64_t),
                                  VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS) {
            lastGpuMs_ = (double)(ts[1] - ts[0]) * (double)props_.limits.timestampPeriod / 1e6;
        } else {
            lastGpuMs_ = -1.0;
        }
        invalidate(staging_);
        memcpy(half, staging_.mapped, (size_t)bytes);
    }

    double last_gpu_ms() const { return lastGpuMs_; }

   private:
    void destroy_owned(OwnedImage& o) {
        if (o.view != VK_NULL_HANDLE) vkDestroyImageView(device_, o.view, nullptr);
        if (o.image != VK_NULL_HANDLE) vkDestroyImage(device_, o.image, nullptr);
        if (o.memory != VK_NULL_HANDLE) vkFreeMemory(device_, o.memory, nullptr);
        o = OwnedImage{};
    }

    void destroy_buffer(OwnedBuffer& b) {
        if (b.mapped != nullptr) vkUnmapMemory(device_, b.memory);
        if (b.buffer != VK_NULL_HANDLE) vkDestroyBuffer(device_, b.buffer, nullptr);
        if (b.memory != VK_NULL_HANDLE) vkFreeMemory(device_, b.memory, nullptr);
        b = OwnedBuffer{};
    }

    OwnedImage make_image(uint32_t w, uint32_t h, VkFormat format = VK_FORMAT_R16G16B16A16_SFLOAT) {
        OwnedImage o{};
        VkImageCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        ci.imageType = VK_IMAGE_TYPE_2D;
        ci.format = format;
        ci.extent = {w, h, 1};
        ci.mipLevels = 1;
        ci.arrayLayers = 1;
        ci.samples = VK_SAMPLE_COUNT_1_BIT;
        ci.tiling = VK_IMAGE_TILING_OPTIMAL;
        ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                   VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        check(vkCreateImage(device_, &ci, nullptr, &o.image), "capture sharpening: image failed");
        VkMemoryRequirements mr{};
        vkGetImageMemoryRequirements(device_, o.image, &mr);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = mr.size;
        try {
            ai.memoryTypeIndex =
                find_memory(phys_, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        } catch (...) {
            ai.memoryTypeIndex = find_memory(phys_, mr.memoryTypeBits, 0);
        }
        check(vkAllocateMemory(device_, &ai, nullptr, &o.memory), "capture sharpening: image memory failed");
        check(vkBindImageMemory(device_, o.image, o.memory, 0), "capture sharpening: image bind failed");
        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = o.image;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = format;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        check(vkCreateImageView(device_, &vi, nullptr, &o.view), "capture sharpening: view failed");
        return o;
    }

    void ensure_buffer(OwnedBuffer& b, VkDeviceSize size, VkBufferUsageFlags usage) {
        if (b.buffer != VK_NULL_HANDLE && b.size >= size) return;
        destroy_buffer(b);
        VkBufferCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        ci.size = size;
        ci.usage = usage;
        ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        check(vkCreateBuffer(device_, &ci, nullptr, &b.buffer), "capture sharpening: buffer failed");
        VkMemoryRequirements mr{};
        vkGetBufferMemoryRequirements(device_, b.buffer, &mr);
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = mr.size;
        try {
            ai.memoryTypeIndex = find_memory(phys_, mr.memoryTypeBits,
                                             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                 VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            b.coherent = true;
        } catch (...) {
            ai.memoryTypeIndex =
                find_memory(phys_, mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            b.coherent = false;
        }
        check(vkAllocateMemory(device_, &ai, nullptr, &b.memory), "capture sharpening: buffer memory failed");
        check(vkBindBufferMemory(device_, b.buffer, b.memory, 0), "capture sharpening: buffer bind failed");
        check(vkMapMemory(device_, b.memory, 0, VK_WHOLE_SIZE, 0, &b.mapped), "capture sharpening: map failed");
        // Buffer capacity is ci.size; the memory allocation may be padded.
        b.size = size;
    }

    void flush(const OwnedBuffer& b) {
        if (b.coherent) return;
        VkMappedMemoryRange r{};
        r.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        r.memory = b.memory;
        r.size = VK_WHOLE_SIZE;
        check(vkFlushMappedMemoryRanges(device_, 1, &r), "capture sharpening: flush failed");
    }

    void invalidate(const OwnedBuffer& b) {
        if (b.coherent) return;
        VkMappedMemoryRange r{};
        r.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        r.memory = b.memory;
        r.size = VK_WHOLE_SIZE;
        check(vkInvalidateMappedMemoryRanges(device_, 1, &r), "capture sharpening: invalidate failed");
    }

    void ensure_images(int w, int h) {
        if (w == curW_ && h == curH_) return;
        destroy_owned(src_);
        destroy_owned(sharpClip_);
        src_ = make_image((uint32_t)w, (uint32_t)h);
        imagesFresh_ = true;
        curW_ = w;
        curH_ = h;
    }

    void barrier(VkCommandBuffer cmd, VkImage img, VkImageLayout oldL, VkImageLayout newL,
                 VkAccessFlags src, VkAccessFlags dst, VkPipelineStageFlags ss,
                 VkPipelineStageFlags ds) {
        VkImageMemoryBarrier b{};
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.srcAccessMask = src;
        b.dstAccessMask = dst;
        b.oldLayout = oldL;
        b.newLayout = newL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = img;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(cmd, ss, ds, 0, 0, nullptr, 0, nullptr, 1, &b);
    }

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties props_{};
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkQueryPool tsPool_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    std::unique_ptr<rawr::capsharp::CaptureSharpening> sharp_;
    OwnedImage src_, sharpClip_;
    OwnedBuffer sharpClipStaging_;
    bool sharpClipFresh_ = true;
    OwnedBuffer staging_;
    int curW_ = 0, curH_ = 0;
    bool imagesFresh_ = false;
    double lastGpuMs_ = -1.0;
};

void raise(JNIEnv* env, const char* msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) env->ThrowNew(cls, msg != nullptr ? msg : "capture sharpening error");
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_matthew_rawlens_CaptureSharpener_nativeInit(JNIEnv* env, jobject) {
    try {
        return reinterpret_cast<jlong>(new SharpenHost());
    } catch (const std::exception& e) {
        raise(env, e.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_matthew_rawlens_CaptureSharpener_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<SharpenHost*>(handle);
}

// Returns GPU milliseconds (-1 unavailable).
extern "C" JNIEXPORT jdouble JNICALL Java_com_matthew_rawlens_CaptureSharpener_nativeProcess(
    JNIEnv* env, jobject, jlong handle, jobject pixels, jint w, jint h, jfloatArray wb, jint cfa, jint iterations,
    jobject clipState) {
    auto* host = reinterpret_cast<SharpenHost*>(handle);
    auto* ptr = static_cast<uint8_t*>(pixels != nullptr ? env->GetDirectBufferAddress(pixels) : nullptr);
    if (host == nullptr || !ptr || w < 64 || h < 64 || w > 16384 || h > 16384 || cfa < 0 || cfa > 3 || iterations < 1 ||
        iterations > 250 || env->GetDirectBufferCapacity(pixels) < jlong(w) * h * 8 || !wb || env->GetArrayLength(wb) < 3) {
        raise(env, "invalid capture sharpening arguments");
        return -1;
    }
    const auto* clip = clipState ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(clipState)) : nullptr;
    if (clipState && (!clip || (w & 1) || (h & 1) ||
        env->GetDirectBufferCapacity(clipState) != jlong(w / 2) * (h / 2) * 2)) {
        raise(env, "invalid capture sharpening sensor clip state");
        return -1;
    }
    float gains[3];
    env->GetFloatArrayRegion(wb, 0, 3, gains);
    if (env->ExceptionCheck()) return -1;
    try {
        host->process(ptr, w, h, gains, iterations, cfa, clip);
        return host->last_gpu_ms();
    } catch (const std::exception& e) {
        raise(env, e.what());
        return -1;
    }
}
