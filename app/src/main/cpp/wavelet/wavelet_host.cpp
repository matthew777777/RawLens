// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include "wavelet_host.h"

#include <android/asset_manager.h>
#include <stdio.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "raw_denoise/DenoisePipeline.hpp"
#include "capture_sharpening/CaptureSharpening.h"
#include "noise_estimate.h"
#include <android/log.h>

#define WAVELET_FENCE_TIMEOUT_NS 30000000000ull
#define WAVELET_MAX_EDGE 16384
#define WAVELET_MIN_EDGE 64

namespace {

void set_err(char* errmsg, int errlen, const char* msg) {
    if (errmsg != nullptr && errlen > 0) {
        snprintf(errmsg, (size_t)errlen, "%s", msg != nullptr ? msg : "wavelet error");
    }
}

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
    throw std::runtime_error("wavelet: no suitable memory type");
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

class WaveletHost {
   public:
    WaveletHost() {
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "RawLensWavelet";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ci{};
        ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ci.pApplicationInfo = &app;
        check(vkCreateInstance(&ci, nullptr, &instance_), "wavelet: vkCreateInstance failed");

        uint32_t devCount = 0;
        check(vkEnumeratePhysicalDevices(instance_, &devCount, nullptr),
              "wavelet: vkEnumeratePhysicalDevices failed");
        if (devCount == 0) throw std::runtime_error("wavelet: no Vulkan physical device");
        std::vector<VkPhysicalDevice> devs(devCount);
        check(vkEnumeratePhysicalDevices(instance_, &devCount, devs.data()),
              "wavelet: vkEnumeratePhysicalDevices failed");
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
        if (phys_ == VK_NULL_HANDLE) throw std::runtime_error("wavelet: no compute queue family");
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
        check(vkCreateDevice(phys_, &di, nullptr, &device_), "wavelet: vkCreateDevice failed");
        vkGetDeviceQueue(device_, qfi, 0, &queue_);
        queueFamily_ = qfi;

        VkCommandPoolCreateInfo pi{};
        pi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pi.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pi.queueFamilyIndex = qfi;
        check(vkCreateCommandPool(device_, &pi, nullptr, &pool_), "wavelet: pool failed");
        VkCommandBufferAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        ai.commandPool = pool_;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = 1;
        check(vkAllocateCommandBuffers(device_, &ai, &cmd_), "wavelet: cmd alloc failed");
        VkFenceCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        check(vkCreateFence(device_, &fi, nullptr, &fence_), "wavelet: fence failed");
        VkQueryPoolCreateInfo qi2{};
        qi2.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qi2.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qi2.queryCount = 2;
        check(vkCreateQueryPool(device_, &qi2, nullptr, &tsPool_), "wavelet: ts pool failed");
    }

    ~WaveletHost() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        sharp_.reset();
        pipe_.reset();
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

    WaveletHost(const WaveletHost&) = delete;
    WaveletHost& operator=(const WaveletHost&) = delete;

    int load_shaders(AAssetManager* mgr) {
        static const char* kNames[6] = {
            "spirv/wavelet/denoise_img_precondition.spv",
            "spirv/wavelet/denoise_img_atrous.spv",
            "spirv/wavelet/denoise_img_threshold.spv",
            "spirv/wavelet/denoise_img_backtransform.spv",
            "spirv/wavelet/denoise_img_backtransform_buffer.spv",
            "spirv/wavelet/denoise_img_wiener_apply.spv",
        };
        for (const char* name : kNames) spv_.push_back(read_asset(mgr, name));
        raw_denoise::DenoiseShaders shaders{};
        auto bin = [&](size_t i) {
            return raw_denoise::DenoiseSpirv{reinterpret_cast<const uint32_t*>(spv_[i].data()),
                                             spv_[i].size() / 4};
        };
        shaders.precondition = bin(0);
        shaders.atrous = bin(1);
        shaders.threshold = bin(2);
        shaders.backtransform = bin(3);
        shaders.backtransformBuffer = bin(4);
        shaders.wienerApply = bin(5);
        pipe_ = std::make_unique<raw_denoise::DenoisePipeline>(phys_, device_, shaders);
        return 6;
    }

    void begin_commands() {
        check(vkResetFences(device_, 1, &fence_), "wavelet: fence reset failed");
        VkCommandBufferBeginInfo bi{};
        bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(cmd_, &bi), "wavelet: begin failed");
    }

    void submit_and_wait() {
        check(vkEndCommandBuffer(cmd_), "wavelet: end failed");
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd_;
        check(vkQueueSubmit(queue_, 1, &si, fence_), "wavelet: submit failed");
        VkResult wr = vkWaitForFences(device_, 1, &fence_, VK_TRUE, WAVELET_FENCE_TIMEOUT_NS);
        if (wr == VK_TIMEOUT) throw std::runtime_error("wavelet: GPU wait timed out");
        if (wr != VK_SUCCESS)
            throw std::runtime_error("wavelet: fence wait failed (VkResult " + std::to_string((int)wr) + ")");
    }

    // Optional capture sharpening (in place on the staged frame) followed by
    // optional denoise, in ONE submit: no intermediate download/upload. The
    // denoiser writes straight into the staging buffer (no output image).
    void process(const uint8_t* inHalf, uint8_t* outHalf, int w, int h, const WaveletParams* params,
                 int sharpIterations = 0, int cfa = 0, const uint8_t* clipState = nullptr,
                 bool denoise = true) {
        if (pipe_ == nullptr) throw std::runtime_error("wavelet: shaders not loaded");
        if (inHalf == nullptr || outHalf == nullptr || params == nullptr) {
            throw std::runtime_error("wavelet: null process argument");
        }
        if (w < WAVELET_MIN_EDGE || h < WAVELET_MIN_EDGE || w > WAVELET_MAX_EDGE ||
            h > WAVELET_MAX_EDGE) {
            throw std::runtime_error("wavelet: extent out of range");
        }
        if (denoise && !(params->strength > 0.0f)) throw std::runtime_error("wavelet: strength bypasses");
        if (!denoise && sharpIterations <= 0) throw std::runtime_error("wavelet: nothing to process");
        ensure_images(w, h);
        const VkDeviceSize bytes = VkDeviceSize(w) * VkDeviceSize(h) * 8u;
        ensure_buffer(staging_, bytes,
                      VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT |
                          VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        memcpy(staging_.mapped, inHalf, (size_t)bytes);
        flush(staging_);
        if (sharpIterations > 0 && clipState) {
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
        if (sharpIterations > 0) {
            if (!sharp_) sharp_ = std::make_unique<rawr::capsharp::CaptureSharpening>(
                rawr::vk::GpuContext{phys_, device_, queueFamily_});
            rawr::capsharp::RecordInfo info{};
            info.commandBuffer = cmd_;
            info.width = uint32_t(w);
            info.height = uint32_t(h);
            info.image = src_.image;
            info.view = src_.view;
            info.cfaPattern = uint32_t(cfa);
            memcpy(info.whiteBalance, params->wb, sizeof info.whiteBalance);
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
            info.params.iterations = uint32_t(sharpIterations);
            info.deconvBandRows = 16;
            if (!sharp_->record(info)) throw std::runtime_error("capture sharpening rejected input");
            if (denoise) {
                // Sharpened src_ feeds the denoiser in the same submit.
                VkMemoryBarrier mb{};
                mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
                mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
                mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            }
        }
        if (denoise) {
        raw_denoise::DenoiseParams p{};
        p.strength = params->strength;
        p.shadows = params->shadows;
        p.noiseA = params->noiseA;
        p.noiseB = params->noiseB;
        p.whiteBalance = {params->wb[0], params->wb[1], params->wb[2]};
        p.maxScale = params->maxScale;
        p.forceY = params->forceY;
        p.forceUv = params->forceUv;
        p.tileSize = params->tileSize;
        p.algorithm = params->algorithm;
        p.grain = params->grain;
        if (p.algorithm == 1) choose_noise(inHalf, w, h, params->cfa, p);
        // One tile per submit keeps each GPU job short (~0.5 s instead of ~3 s
        // for RawLens at 12 MP on Mali-G615), so the shared GPU stays responsive
        // for the viewfinder. Tiles are independent and the scratch persists
        // across submits: output is unchanged; measured wall cost none.
        const uint32_t tile = std::max(p.tileSize, 256u);
        const uint32_t tiles = ((uint32_t)w + tile - 1) / tile * (((uint32_t)h + tile - 1) / tile);
        for (uint32_t t = 0; t < tiles; t++) {
            if (t > 0) {
                submit_and_wait();
                begin_commands();
                // Previous tile's backtransform wrote staging; scratch reuse.
                VkMemoryBarrier mb{};
                mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
                mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            }
            p.tileBegin = t;
            p.tileEnd = t + 1;
            pipe_->recordToBuffer(cmd_, src_.view, staging_.buffer, staging_.size, (uint32_t)w,
                                  (uint32_t)h, p, VK_NULL_HANDLE);
        }
        }
        vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, tsPool_, 1);
        if (denoise) {
            // Denoiser wrote the staging buffer directly; make it host-visible.
            VkMemoryBarrier mb{};
            mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                                 0, 1, &mb, 0, nullptr, 0, nullptr);
        } else {
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
        }

        submit_and_wait();

        uint64_t ts[2] = {0, 0};
        if (vkGetQueryPoolResults(device_, tsPool_, 0, 2, sizeof(ts), ts, sizeof(uint64_t),
                                  VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS) {
            lastGpuMs_ = (double)(ts[1] - ts[0]) * (double)props_.limits.timestampPeriod / 1e6;
        } else {
            lastGpuMs_ = -1.0;
        }
        invalidate(staging_);
        memcpy(outHalf, staging_.mapped, (size_t)bytes);
    }

    double last_gpu_ms() const { return lastGpuMs_; }

    // RawLens: blind noise estimate from native green sites. Keep the Camera2
    // profile when it agrees within 2.5x (variance at the median exposure);
    // otherwise (missing, or e.g. 10x overstated at base ISO) use the estimate.
    // Then scale luma strength by noise visibility at that exposure.
    static void choose_noise(const uint8_t* in, int w, int h, int cfa, raw_denoise::DenoiseParams& p) {
        const auto e = rawlens_noise::estimate(in, w, h, cfa);
        const bool haveProfile = p.noiseA > 0.0f || p.noiseB > 0.0f;
        const char* source = "profile";
        if (e.ok) {
            const float x = std::max(e.medianX, 1e-3f);
            const float ratio = haveProfile ? (e.a * x + e.b) / std::max(p.noiseA * x + p.noiseB, 1e-12f) : 0.0f;
            if (!haveProfile || ratio < 0.4f || ratio > 2.5f) {
                source = haveProfile ? "estimate (profile disagrees)" : "estimate (no profile)";
                p.noiseA = e.a;
                p.noiseB = e.b;
            }
            p.lumaScale = rawlens_noise::lumaScaleFor(p.noiseA, p.noiseB, e.medianX);
        } else if (!haveProfile) {
            throw std::runtime_error("wavelet: no noise profile and blind estimate failed");
        }
        __android_log_print(ANDROID_LOG_INFO, "WaveletNoise",
                            "noise %s: a=%.3g b=%.3g (estimate ok=%d a=%.3g b=%.3g bins=%d median=%.4f) lumaScale=%.2f",
                            source, p.noiseA, p.noiseB, e.ok, e.a, e.b, e.bins, e.medianX, p.lumaScale);
    }

   private:
    static std::vector<uint8_t> read_asset(AAssetManager* mgr, const char* name) {
        AAsset* a = AAssetManager_open(mgr, name, AASSET_MODE_BUFFER);
        if (a == nullptr) throw std::runtime_error(std::string("wavelet: missing asset ") + name);
        const off_t len = AAsset_getLength(a);
        if (len <= 0 || len % 4 != 0) {
            AAsset_close(a);
            throw std::runtime_error(std::string("wavelet: bad SPIR-V asset ") + name);
        }
        std::vector<uint8_t> out((size_t)len);
        const int got = AAsset_read(a, out.data(), out.size());
        AAsset_close(a);
        if (got != len) throw std::runtime_error(std::string("wavelet: short read ") + name);
        return out;
    }

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
        check(vkCreateImage(device_, &ci, nullptr, &o.image), "wavelet: image failed");
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
        check(vkAllocateMemory(device_, &ai, nullptr, &o.memory), "wavelet: image memory failed");
        check(vkBindImageMemory(device_, o.image, o.memory, 0), "wavelet: image bind failed");
        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = o.image;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = format;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        check(vkCreateImageView(device_, &vi, nullptr, &o.view), "wavelet: view failed");
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
        check(vkCreateBuffer(device_, &ci, nullptr, &b.buffer), "wavelet: buffer failed");
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
        check(vkAllocateMemory(device_, &ai, nullptr, &b.memory), "wavelet: buffer memory failed");
        check(vkBindBufferMemory(device_, b.buffer, b.memory, 0), "wavelet: buffer bind failed");
        check(vkMapMemory(device_, b.memory, 0, VK_WHOLE_SIZE, 0, &b.mapped), "wavelet: map failed");
        // Buffer capacity is ci.size; the memory allocation may be padded.
        b.size = size;
    }

    void flush(const OwnedBuffer& b) {
        if (b.coherent) return;
        VkMappedMemoryRange r{};
        r.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        r.memory = b.memory;
        r.size = VK_WHOLE_SIZE;
        check(vkFlushMappedMemoryRanges(device_, 1, &r), "wavelet: flush failed");
    }

    void invalidate(const OwnedBuffer& b) {
        if (b.coherent) return;
        VkMappedMemoryRange r{};
        r.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        r.memory = b.memory;
        r.size = VK_WHOLE_SIZE;
        check(vkInvalidateMappedMemoryRanges(device_, 1, &r), "wavelet: invalidate failed");
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
    std::unique_ptr<raw_denoise::DenoisePipeline> pipe_;
    std::vector<std::vector<uint8_t>> spv_;
    OwnedImage src_, sharpClip_;
    OwnedBuffer sharpClipStaging_;
    bool sharpClipFresh_ = true;
    OwnedBuffer staging_;
    int curW_ = 0, curH_ = 0;
    bool imagesFresh_ = false;
    double lastGpuMs_ = -1.0;
};

}  // namespace

struct WaveletContext {
    WaveletHost host;
};

WaveletContext* wavelet_create(char* errmsg, int errlen) {
    try {
        return new WaveletContext();
    } catch (const std::exception& e) {
        set_err(errmsg, errlen, e.what());
        return nullptr;
    } catch (...) {
        set_err(errmsg, errlen, "wavelet: unknown create failure");
        return nullptr;
    }
}

int wavelet_load_shaders(WaveletContext* ctx, struct AAssetManager* mgr, char* errmsg, int errlen) {
    if (ctx == nullptr || mgr == nullptr) {
        set_err(errmsg, errlen, "wavelet: null context/asset manager");
        return -1;
    }
    try {
        return ctx->host.load_shaders(mgr);
    } catch (const std::exception& e) {
        set_err(errmsg, errlen, e.what());
        return -1;
    } catch (...) {
        set_err(errmsg, errlen, "wavelet: unknown shader load failure");
        return -1;
    }
}

int wavelet_process(WaveletContext* ctx, const uint8_t* inHalf, uint8_t* outHalf, int w, int h,
                    const WaveletParams* params, char* errmsg, int errlen) {
    if (ctx == nullptr) {
        set_err(errmsg, errlen, "wavelet: null context");
        return -1;
    }
    try {
        ctx->host.process(inHalf, outHalf, w, h, params, params->sharpIterations, params->sharpCfa,
                          params->sharpClipState, params->strength > 0.0f);
        return 0;
    } catch (const std::exception& e) {
        set_err(errmsg, errlen, e.what());
        return -1;
    } catch (...) {
        set_err(errmsg, errlen, "wavelet: unknown process failure");
        return -1;
    }
}

double wavelet_last_gpu_ms(WaveletContext* ctx) {
    if (ctx == nullptr) return -1.0;
    try {
        return ctx->host.last_gpu_ms();
    } catch (...) {
        return -1.0;
    }
}

void wavelet_destroy(WaveletContext* ctx) { delete ctx; }

extern "C" int capture_sharpen_process(WaveletContext* ctx, uint8_t* half, int w, int h,
                                       const float* wb, int cfa, int iterations, const uint8_t* clipState,
                                       char* errmsg, int errlen) {
    try {
        if (!ctx || !wb || cfa < 0 || cfa > 3 || iterations < 1 || iterations > 250)
            throw std::runtime_error("invalid capture sharpening arguments");
        WaveletParams p{};
        p.strength = 1;
        memcpy(p.wb, wb, sizeof p.wb);
        ctx->host.process(half, half, w, h, &p, iterations, cfa, clipState, false);
        return 0;
    } catch (const std::exception& e) {
        set_err(errmsg, errlen, e.what());
        return -1;
    }
}
