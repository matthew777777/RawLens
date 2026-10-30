// Timing and parity harness for the tiled production pipeline (host MoltenVK
// or on-device). Loads the shipped shaders from --spv-dir, runs
// DenoisePipeline on an RGBA32F fixture (converted to the pipeline's RGBA16F),
// and prints GPU ms from the pipeline's own 10->11 timestamps plus wall time.
// --dump-out writes the RGBA32F result; tools/verify_denoise_parity.py
// compares it against the oracle goldens.
//
// Usage:
//   denoise_bench --spv-dir DIR --input-f32 IN --width W --height H \
//     --tile T [--dump-out OUT] [--scales N] [--wb R G B --a A --b B
//     --strength S --shadows SH --force-y FY --force-uv FUV]
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <tuple>
#include <vector>

#include "vk_test_common.hpp"  // RAWLENS: local copy, was ../../vk_common/testing/
#include "raw_denoise/DenoisePipeline.hpp"  // RAWLENS: -I to app cpp/wavelet/upstream/include

namespace {

// IEEE binary32 -> binary16, round to nearest even (host upload only).
uint16_t floatToHalf(float value) {
    uint32_t x;
    std::memcpy(&x, &value, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    const uint32_t absx = x & 0x7fffffffu;
    if (absx >= 0x7f800000u) return uint16_t(sign | 0x7c00u | (absx > 0x7f800000u ? 0x200u : 0u));
    if (absx >= 0x477ff000u) return uint16_t(sign | 0x7c00u);  // overflow -> inf
    if (absx < 0x38800000u) {                                   // subnormal or zero
        if (absx < 0x33000000u) return uint16_t(sign);
        const uint32_t mant = (absx & 0x7fffffu) | 0x800000u;
        const int shift = 126 - int(absx >> 23);  // 14..24
        uint32_t half = mant >> shift;
        const uint32_t rem = mant & ((1u << shift) - 1u), mid = 1u << (shift - 1);
        if (rem > mid || (rem == mid && (half & 1u))) ++half;
        return uint16_t(sign | half);
    }
    uint32_t half = ((absx - 0x38000000u) >> 13);
    const uint32_t rem = absx & 0x1fffu;
    if (rem > 0x1000u || (rem == 0x1000u && (half & 1u))) ++half;
    return uint16_t(sign | half);
}
using vktest::Buf;

std::vector<char> readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot open " + path);
    return std::vector<char>((std::istreambuf_iterator<char>(f)),
                             std::istreambuf_iterator<char>());
}

// Profile constants below are OVERRIDDEN by --wb/--a/--b/--strength/--shadows
// (defaults match the s1_sh1 books fixture only as a fallback).

struct Cfg {
    std::string spvDir, inPath, dumpPath, dumpPrePath;
    uint32_t w = 0, h = 0;
    uint32_t tile = 1536;
    int scales = 6;
    float wb[3] = {1.0f, 1.0f, 1.0f};
    float a = 0.0f, b = 0.0f;
    float strength = 1.0f, shadows = 1.0f;
    float forceY = 0.25f, forceUv = 0.75f;
};

Cfg parse(int argc, char** argv) {
    Cfg c;
    for (int i = 1; i < argc; i++) {
        std::string k = argv[i];
        auto need = [&]() -> std::string {
            if (i + 1 >= argc) throw std::runtime_error("missing " + k);
            return argv[++i];
        };
        if (k == "--spv-dir") c.spvDir = need();
        else if (k == "--input-f32") c.inPath = need();
        else if (k == "--width") c.w = (uint32_t)std::stoul(need());
        else if (k == "--height") c.h = (uint32_t)std::stoul(need());
        else if (k == "--tile") c.tile = (uint32_t)std::stoul(need());
        else if (k == "--dump-out") c.dumpPath = need();
        else if (k == "--dump-pre") c.dumpPrePath = need();
        else if (k == "--scales") c.scales = std::stoi(need());
        else if (k == "--wb") {
            for (int j = 0; j < 3; j++) c.wb[j] = std::strtof(need().c_str(), nullptr);
        } else if (k == "--a") c.a = std::strtof(need().c_str(), nullptr);
        else if (k == "--b") c.b = std::strtof(need().c_str(), nullptr);
        else if (k == "--strength") c.strength = std::strtof(need().c_str(), nullptr);
        else if (k == "--shadows") c.shadows = std::strtof(need().c_str(), nullptr);
        else if (k == "--force-y") c.forceY = std::strtof(need().c_str(), nullptr);
        else if (k == "--force-uv") c.forceUv = std::strtof(need().c_str(), nullptr);
        else throw std::runtime_error("unknown arg " + k);
    }
    if (c.spvDir.empty() || c.inPath.empty() || !c.w || !c.h) throw std::runtime_error("missing args");
    return c;
}

raw_denoise::DenoiseSpirv loadSpv(const std::string& path, std::vector<std::vector<char>>& keep) {
    keep.emplace_back(readFile(path));
    raw_denoise::DenoiseSpirv s;
    s.words = reinterpret_cast<const uint32_t*>(keep.back().data());
    s.wordCount = keep.back().size() / 4;
    return s;
}

void* mapBuf(VkDevice dev, const Buf& b) {
    void* p = nullptr;
    vktest::ck(vkMapMemory(dev, b.m, 0, b.size, 0, &p), "bench map");
    return p;
}
}  // namespace

int main(int argc, char** argv) {
    try {
        Cfg c = parse(argc, argv);
        const size_t npix = (size_t)c.w * c.h;
        std::vector<float> hostIn(npix * 4);
        {
            std::ifstream f(c.inPath, std::ios::binary);
            if (!f) throw std::runtime_error("cannot open input");
            f.read(reinterpret_cast<char*>(hostIn.data()), hostIn.size() * sizeof(float));
        }
        vktest::Ctx ctx{};
        {
            // Minimal compute-only init (no MoltenVK portability flags needed on Android).
            VkApplicationInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
            ai.apiVersion = VK_API_VERSION_1_1;
            VkInstanceCreateInfo ii{};
            ii.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
            ii.pApplicationInfo = &ai;
#ifdef __APPLE__
            // RAWLENS: enable portability enumeration only when the driver
            // advertises it (direct-linked MoltenVK may not; physical-device
            // enumeration still works without it).
            {
                uint32_t ieCount = 0;
                vkEnumerateInstanceExtensionProperties(nullptr, &ieCount, nullptr);
                std::vector<VkExtensionProperties> ieProps(ieCount);
                vkEnumerateInstanceExtensionProperties(nullptr, &ieCount, ieProps.data());
                for (const auto& e : ieProps) {
                    if (std::strcmp(e.extensionName, "VK_KHR_portability_enumeration") == 0) {
                        ii.flags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
                        static const char* ie[] = {"VK_KHR_portability_enumeration"};
                        ii.enabledExtensionCount = 1;
                        ii.ppEnabledExtensionNames = ie;
                        break;
                    }
                }
            }
#endif
            vktest::ck(vkCreateInstance(&ii, nullptr, &ctx.inst), "instance");
            uint32_t n = 0;
            vktest::ck(vkEnumeratePhysicalDevices(ctx.inst, &n, nullptr), "pd");
            std::vector<VkPhysicalDevice> ps(n);
            vkEnumeratePhysicalDevices(ctx.inst, &n, ps.data());
            ctx.pd = ps[0];
            vkGetPhysicalDeviceProperties(ctx.pd, &ctx.prop);
            uint32_t nq = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(ctx.pd, &nq, nullptr);
            std::vector<VkQueueFamilyProperties> qp(nq);
            vkGetPhysicalDeviceQueueFamilyProperties(ctx.pd, &nq, qp.data());
            ctx.qf = UINT32_MAX;
            for (uint32_t i = 0; i < nq; i++)
                if (qp[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                    ctx.qf = i;
                    break;
                }
            float pr = 1;
            VkDeviceQueueCreateInfo qi{};
            qi.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
            qi.queueFamilyIndex = ctx.qf;
            qi.queueCount = 1;
            qi.pQueuePriorities = &pr;
            VkDeviceCreateInfo di{};
            di.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
            di.queueCreateInfoCount = 1;
            di.pQueueCreateInfos = &qi;
#ifdef __APPLE__
            // RAWLENS: same advertise-check as the instance extension above.
            {
                uint32_t deCount = 0;
                vkEnumerateDeviceExtensionProperties(ps[0], nullptr, &deCount, nullptr);
                std::vector<VkExtensionProperties> deProps(deCount);
                vkEnumerateDeviceExtensionProperties(ps[0], nullptr, &deCount, deProps.data());
                for (const auto& e : deProps) {
                    if (std::strcmp(e.extensionName, "VK_KHR_portability_subset") == 0) {
                        static const char* de[] = {"VK_KHR_portability_subset"};
                        di.enabledExtensionCount = 1;
                        di.ppEnabledExtensionNames = de;
                        break;
                    }
                }
            }
#endif
            vktest::ck(vkCreateDevice(ctx.pd, &di, nullptr, &ctx.dev), "device");
            vkGetDeviceQueue(ctx.dev, ctx.qf, 0, &ctx.q);
        }
        VkDevice dev = ctx.dev;
        std::vector<std::vector<char>> keep;
        auto spv = [&](const std::string& n) { return loadSpv(c.spvDir + "/" + n + ".spv", keep); };
        raw_denoise::DenoiseShaders shaders{};
        shaders.precondition = spv("denoise_img_precondition");
        shaders.atrous = spv("denoise_img_atrous");
        shaders.threshold = spv("denoise_img_threshold");
        shaders.backtransform = spv("denoise_img_backtransform");

        raw_denoise::DenoisePipeline pipe(ctx.pd, dev, shaders);

        // Input/output images: device-local + staging (production-like).
        auto mkImage = [&](bool hostVisible) {
            VkImageCreateInfo ci{};
            ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
            ci.imageType = VK_IMAGE_TYPE_2D;
            ci.format = VK_FORMAT_R16G16B16A16_SFLOAT;
            ci.extent = {c.w, c.h, 1};
            ci.mipLevels = 1;
            ci.arrayLayers = 1;
            ci.samples = VK_SAMPLE_COUNT_1_BIT;
            ci.tiling = hostVisible ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_OPTIMAL;
            ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                       VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            VkImage img{};
            vktest::ck(vkCreateImage(dev, &ci, nullptr, &img), "bench image");
            VkMemoryRequirements mr{};
            vkGetImageMemoryRequirements(dev, img, &mr);
            VkMemoryAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            ai.allocationSize = mr.size;
            ai.memoryTypeIndex = vktest::memType(
                ctx.pd, mr.memoryTypeBits,
                hostVisible ? (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                            : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            VkDeviceMemory mem{};
            vktest::ck(vkAllocateMemory(dev, &ai, nullptr, &mem), "bench mem");
            vktest::ck(vkBindImageMemory(dev, img, mem, 0), "bench bind");
            VkImageViewCreateInfo vi{};
            vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            vi.image = img;
            vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
            vi.format = VK_FORMAT_R16G16B16A16_SFLOAT;
            vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            VkImageView view{};
            vktest::ck(vkCreateImageView(dev, &vi, nullptr, &view), "bench view");
            return std::tuple<VkImage, VkDeviceMemory, VkImageView>(img, mem, view);
        };
        auto [inImg, inMem, inView] = mkImage(false);
        auto [outImg, outMem, outView] = mkImage(false);
        Buf staging = vktest::mkBuf(ctx.pd, dev, hostIn.size() * sizeof(float),
                                     VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                         VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        // The images are RGBA16F: convert on the host, buffer<->image copies do not.
        {
            auto* dst = static_cast<uint16_t*>(mapBuf(dev, staging));
            for (size_t i = 0; i < hostIn.size(); ++i) dst[i] = floatToHalf(hostIn[i]);
            vkUnmapMemory(dev, staging.m);
        }

        VkCommandPool pool{};
        {
            VkCommandPoolCreateInfo ci{};
            ci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
            ci.queueFamilyIndex = ctx.qf;
            vktest::ck(vkCreateCommandPool(dev, &ci, nullptr, &pool), "bench pool");
        }
        VkCommandBuffer cmd{};
        {
            VkCommandBufferAllocateInfo ai{};
            ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
            ai.commandPool = pool;
            ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            ai.commandBufferCount = 1;
            vktest::ck(vkAllocateCommandBuffers(dev, &ai, &cmd), "bench cmd");
        }
        // Opt-in per-dispatch timestamps; production never requests these.
        const bool profilePasses = std::getenv("DENOISE_PROFILE") != nullptr;
        const uint32_t tile = std::max(c.tile, 256u);
        const int scales = std::clamp(c.scales, 1, 7);
        const uint32_t passesPerTile = 2u + 3u * uint32_t(scales);
        const uint32_t passCount = ((c.w + tile - 1) / tile) *
                                   ((c.h + tile - 1) / tile) * passesPerTile;
        const uint32_t queryCount = profilePasses ? 2 + 2 * passCount : 2;
        VkQueryPool tsPool{};
        {
            VkQueryPoolCreateInfo qi{};
            qi.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
            qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
            qi.queryCount = queryCount;
            vktest::ck(vkCreateQueryPool(dev, &qi, nullptr, &tsPool), "bench ts");
        }
        auto imgBarrier = [&](VkImage img, VkAccessFlags dst, VkImageLayout next) {
            VkImageMemoryBarrier b{};
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.dstAccessMask = dst;
            b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            b.newLayout = next;
            b.image = img;
            b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr,
                                 1, &b);
        };
        auto runOnce = [&](bool timed, bool upload) {
            VkCommandBufferBeginInfo bi{};
            bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vktest::ck(vkBeginCommandBuffer(cmd, &bi), "bench begin");
            // Upload. Once per process: the image stays GENERAL with valid
            // pixels across iterations (fence-waited), and re-uploading with
            // oldLayout=UNDEFINED would let the driver discard contents.
            if (upload) {
            {
                VkBufferImageCopy r{};
                r.imageExtent = {c.w, c.h, 1};
                r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
                imgBarrier(inImg, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                vkCmdCopyBufferToImage(cmd, staging.b, inImg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                       1, &r);
                VkImageMemoryBarrier b{};
                b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
                b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
                b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b.image = inImg;
                b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0,
                                     nullptr, 1, &b);
            }
            }
            imgBarrier(outImg, VK_ACCESS_SHADER_WRITE_BIT, VK_IMAGE_LAYOUT_GENERAL);
            if (timed) {
                vkCmdResetQueryPool(cmd, tsPool, 0, queryCount);
                vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, tsPool, 0);
            }
            raw_denoise::DenoiseParams p{};
            p.strength = c.strength;
            p.shadows = c.shadows;
            p.noiseA = c.a;
            p.noiseB = c.b;
            p.forceY = c.forceY;
            p.forceUv = c.forceUv;
            p.whiteBalance = {c.wb[0], c.wb[1], c.wb[2]};
            p.maxScale = c.scales;
            p.tileSize = c.tile;
            pipe.record(cmd, inView, outView, c.w, c.h, p, VK_NULL_HANDLE,
                        timed && profilePasses ? tsPool : VK_NULL_HANDLE);
            if (timed) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, tsPool, 1);
            vktest::ck(vkEndCommandBuffer(cmd), "bench end");
            VkSubmitInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cmd;
            VkFence fence{};
            VkFenceCreateInfo fi{};
            fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
            vktest::ck(vkCreateFence(dev, &fi, nullptr, &fence), "bench fence");
            auto t0 = std::chrono::steady_clock::now();
            vktest::ck(vkQueueSubmit(ctx.q, 1, &si, fence), "bench submit");
            vktest::ck(vkWaitForFences(dev, 1, &fence, VK_TRUE, 30000000000ull), "bench wait");
            auto t1 = std::chrono::steady_clock::now();
            vkDestroyFence(dev, fence, nullptr);
            vktest::ck(vkResetCommandPool(dev, pool, 0), "bench reset");
            double wallMs =
                std::chrono::duration<double, std::milli>(t1 - t0).count();
            double gpuMs = -1;
            if (timed) {
                uint64_t ts[2] = {0, 0};
                vktest::ck(vkGetQueryPoolResults(dev, tsPool, 0, 2, sizeof(ts), ts, sizeof(uint64_t),
                                                  VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
                            "bench ts");
                VkPhysicalDeviceProperties prop{};
                vkGetPhysicalDeviceProperties(ctx.pd, &prop);
                gpuMs = (double)(ts[1] - ts[0]) * (double)prop.limits.timestampPeriod / 1e6;
            }
            return std::pair<double, double>(wallMs, gpuMs);
        };
        runOnce(false, true);  // warmup (pipeline caches, allocator) + upload
        if (std::getenv("BENCH_CHECK_UPLOAD")) {
            // Round-trip the input image through staging to validate upload.
            std::vector<float> rt(hostIn.size());
            VkCommandBufferBeginInfo bi2{};
            bi2.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            bi2.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vktest::ck(vkBeginCommandBuffer(cmd, &bi2), "ck begin");
            VkImageMemoryBarrier b{};
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
            b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            b.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            b.image = inImg;
            b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                                 &b);
            VkBufferImageCopy r2{};
            r2.imageExtent = {c.w, c.h, 1};
            r2.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            vkCmdCopyImageToBuffer(cmd, inImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.b,
                                   1, &r2);
            vktest::ck(vkEndCommandBuffer(cmd), "ck end");
            VkSubmitInfo si2{};
            si2.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si2.commandBufferCount = 1;
            si2.pCommandBuffers = &cmd;
            vktest::ck(vkQueueSubmit(ctx.q, 1, &si2, VK_NULL_HANDLE), "ck submit");
            vktest::ck(vkQueueWaitIdle(ctx.q), "ck wait");
            vktest::ck(vkResetCommandPool(dev, pool, 0), "ck reset");
            {
                const auto* src = static_cast<const uint16_t*>(mapBuf(dev, staging));
                for (size_t i = 0; i < rt.size(); ++i) rt[i] = vktest::halfToFloat(src[i]);
                vkUnmapMemory(dev, staging.m);
            }
            // Restore GENERAL so later timed iterations read a valid layout.
            {
                VkCommandBufferBeginInfo bi4{};
                bi4.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                bi4.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
                vktest::ck(vkBeginCommandBuffer(cmd, &bi4), "ck restore begin");
                VkImageMemoryBarrier bb2{};
                bb2.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
                bb2.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
                bb2.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                bb2.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                bb2.newLayout = VK_IMAGE_LAYOUT_GENERAL;
                bb2.image = inImg;
                bb2.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0,
                                     nullptr, 1, &bb2);
                vktest::ck(vkEndCommandBuffer(cmd), "ck restore end");
                VkSubmitInfo si4{};
                si4.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                si4.commandBufferCount = 1;
                si4.pCommandBuffers = &cmd;
                vktest::ck(vkQueueSubmit(ctx.q, 1, &si4, VK_NULL_HANDLE), "ck restore submit");
                vktest::ck(vkQueueWaitIdle(ctx.q), "ck restore wait");
                vktest::ck(vkResetCommandPool(dev, pool, 0), "ck restore reset");
            }
            std::ofstream f("/tmp/dbg/upload_rt.f32", std::ios::binary);
            f.write(reinterpret_cast<const char*>(rt.data()), rt.size() * sizeof(float));
            std::printf("BENCH_UPLOAD_RT written\n");
        }
        double best = 1e9;
        for (int i = 0; i < 5; i++) {
            auto [wall, gpu] = runOnce(true, false);
            best = std::min(best, gpu);
            std::printf("BENCH_ITER %d wall=%.1fms gpu=%.2fms\n", i, wall, gpu);
        }
        std::printf("BENCH_RESULT tile=%u scales=%d w=%u h=%u best_gpu_ms=%.2f\n", c.tile, c.scales,
                    c.w, c.h, best);
        if (profilePasses) {
            std::vector<uint64_t> stamps(2 * passCount);
            vktest::ck(vkGetQueryPoolResults(dev, tsPool, 2, uint32_t(stamps.size()),
                          stamps.size() * sizeof(uint64_t), stamps.data(), sizeof(uint64_t),
                          VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT), "bench pass ts");
            VkPhysicalDeviceProperties prop{};
            vkGetPhysicalDeviceProperties(ctx.pd, &prop);
            double sums[5] = {};
            const char* names[] = {"precondition", "estimate", "threshold", "apply", "backtransform"};
            for (uint32_t i = 0; i < passCount; ++i) {
                const uint32_t local = i % passesPerTile;
                const uint32_t kind = local == 0 ? 0 : local == passesPerTile - 1 ? 4 :
                                      1 + (local - 1) % 3;
                sums[kind] += double(stamps[2*i+1] - stamps[2*i]) * prop.limits.timestampPeriod / 1e6;
            }
            for (int i = 0; i < 5; ++i)
                std::printf("BENCH_PASS %s gpu_ms=%.3f (last iteration, all tiles/scales)\n",
                            names[i], sums[i]);
        }
        if (!c.dumpPrePath.empty()) {
            // Download the preconditioned first tile (device-local OPTIMAL).
            VkImage fineImg = pipe.debugFineImage();
            uint32_t fe = pipe.debugFineExtent();
            Buf pre2 = vktest::mkBuf(ctx.pd, dev, (VkDeviceSize)fe * fe * 4 * sizeof(float),
                                      VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            std::vector<float> preHost((size_t)fe * fe * 4);
            VkCommandBufferBeginInfo bi3{};
            bi3.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            bi3.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vktest::ck(vkBeginCommandBuffer(cmd, &bi3), "pre begin");
            VkImageMemoryBarrier bb{};
            bb.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            bb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            bb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            bb.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            bb.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            bb.image = fineImg;
            bb.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                                 &bb);
            VkBufferImageCopy r3{};
            r3.imageExtent = {fe, fe, 1};
            r3.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            vkCmdCopyImageToBuffer(cmd, fineImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pre2.b,
                                   1, &r3);
            vktest::ck(vkEndCommandBuffer(cmd), "pre end");
            VkSubmitInfo si3{};
            si3.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si3.commandBufferCount = 1;
            si3.pCommandBuffers = &cmd;
            vktest::ck(vkQueueSubmit(ctx.q, 1, &si3, VK_NULL_HANDLE), "pre submit");
            vktest::ck(vkQueueWaitIdle(ctx.q), "pre wait");
            vktest::ck(vkResetCommandPool(dev, pool, 0), "pre reset");
            std::memcpy(preHost.data(), mapBuf(dev, pre2), preHost.size() * sizeof(float));
            vkUnmapMemory(dev, pre2.m);
            vktest::delBuf(dev, pre2);
            std::ofstream f(c.dumpPrePath, std::ios::binary);
            f.write(reinterpret_cast<const char*>(preHost.data()), preHost.size() * sizeof(float));
            std::printf("BENCH_DUMP_PRE %s extent=%u\n", c.dumpPrePath.c_str(), fe);
        }
        if (!c.dumpPath.empty()) {
            std::vector<float> out(hostIn.size());
            VkCommandBufferBeginInfo bi{};
            bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vktest::ck(vkBeginCommandBuffer(cmd, &bi), "dl begin");
            VkImageMemoryBarrier b{};
            b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            b.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
            b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            b.image = outImg;
            b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                                 &b);
            VkBufferImageCopy r{};
            r.imageExtent = {c.w, c.h, 1};
            r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            vkCmdCopyImageToBuffer(cmd, outImg, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.b,
                                   1, &r);
            vktest::ck(vkEndCommandBuffer(cmd), "dl end");
            VkSubmitInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cmd;
            vktest::ck(vkQueueSubmit(ctx.q, 1, &si, VK_NULL_HANDLE), "dl submit");
            vktest::ck(vkQueueWaitIdle(ctx.q), "dl wait");
            {
                const auto* src = static_cast<const uint16_t*>(mapBuf(dev, staging));
                for (size_t i = 0; i < out.size(); ++i) out[i] = vktest::halfToFloat(src[i]);
                vkUnmapMemory(dev, staging.m);
            }
            std::ofstream f(c.dumpPath, std::ios::binary);
            f.write(reinterpret_cast<const char*>(out.data()), out.size() * sizeof(float));
            std::printf("BENCH_DUMP %s\n", c.dumpPath.c_str());
        }
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "BENCH_FAIL " << e.what() << std::endl;
        return 1;
    }
}
