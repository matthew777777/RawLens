// Host validator: capture_sharpening_validate <dir>
//   dir/params.txt: W H cfaPattern wbR wbG wbB autoRadius radius radiusOffset autoContrast contrast iterations
//                   iterationCheck repeats
//   dir/rgb.f32:    H*W*3 float32, pre-WB camera RGB (white = 1), uploaded as RGBA16F
// Writes dir/gpu_rgb.f32 (H*W*3 float32 read back from RGBA16F) and dir/gpu_info.txt
// (chosen radius/contrast, GPU milliseconds per run). Compare with
// tools/compare_gpu.py.
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "capture_sharpening/CaptureSharpening.h"
#include "vk_test_common.hpp"

namespace {
uint16_t floatToHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    int32_t exp = int32_t((x >> 23) & 0xffu) - 127 + 15;
    uint32_t mant = x & 0x7fffffu;
    if (((x >> 23) & 0xffu) == 0xffu) return uint16_t(sign | 0x7c00u | (mant ? 0x200u : 0u));
    if (exp >= 31) return uint16_t(sign | 0x7c00u);
    if (exp <= 0) {
        if (exp < -10) return uint16_t(sign);
        mant |= 0x800000u;
        const uint32_t shift = uint32_t(14 - exp);
        uint32_t h = mant >> shift;
        const uint32_t rem = mant & ((1u << shift) - 1u), half = 1u << (shift - 1u);
        if (rem > half || (rem == half && (h & 1u))) ++h;
        return uint16_t(sign | h);
    }
    uint32_t h = (uint32_t(exp) << 10) | (mant >> 13);
    const uint32_t rem = mant & 0x1fffu;
    if (rem > 0x1000u || (rem == 0x1000u && (h & 1u))) ++h;
    return uint16_t(sign | h);
}

void barrier(VkCommandBuffer cmd, VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags src,
             VkAccessFlags dst, VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = src;
    b.dstAccessMask = dst;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}
}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "usage: " << argv[0] << " <dir>\n";
        return 2;
    }
    const std::string dir = argv[1];
    uint32_t W, H, pattern, autoRadius, autoContrast, iterations, iterCheck, repeats;
    float wb[3], radius, offset, contrast;
    {
        std::ifstream pf(dir + "/params.txt");
        if (!(pf >> W >> H >> pattern >> wb[0] >> wb[1] >> wb[2] >> autoRadius >> radius >> offset >> autoContrast >>
              contrast >> iterations >> iterCheck >> repeats)) {
            std::cerr << "bad params.txt\n";
            return 1;
        }
    }
    const size_t n = size_t(W) * H;
    std::vector<float> rgb(n * 3);
    {
        std::ifstream f(dir + "/rgb.f32", std::ios::binary);
        if (!f.read(reinterpret_cast<char*>(rgb.data()), std::streamsize(rgb.size() * 4))) {
            std::cerr << "short rgb.f32\n";
            return 1;
        }
    }
    try {
        auto c = vktest::ctx();
        std::cerr << "device: " << c.prop.deviceName << "\n";
        const VkDeviceSize bytes = VkDeviceSize(n) * 8;
        auto staging = vktest::mkBuf(c.pd, c.dev, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        auto img = vktest::mkImg(c.pd, c.dev, W, H, VK_FORMAT_R16G16B16A16_SFLOAT,
                                 VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        VkCommandPoolCreateInfo cpi{};
        cpi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cpi.queueFamilyIndex = c.qf;
        VkCommandPool cmdPool;
        vktest::ck(vkCreateCommandPool(c.dev, &cpi, nullptr, &cmdPool), "cmd pool");
        VkCommandBufferAllocateInfo cai{};
        cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cai.commandPool = cmdPool;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = 1;
        VkCommandBuffer cmd;
        vktest::ck(vkAllocateCommandBuffers(c.dev, &cai, &cmd), "cmd buffer");
        VkQueryPoolCreateInfo qpi{};
        qpi.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        qpi.queryType = VK_QUERY_TYPE_TIMESTAMP;
        qpi.queryCount = 2 + rawr::capsharp::kTimestamps;
        VkQueryPool queries;
        vktest::ck(vkCreateQueryPool(c.dev, &qpi, nullptr, &queries), "query pool");

        rawr::capsharp::CaptureSharpening cs({c.pd, c.dev, c.qf});
        rawr::capsharp::RecordInfo info{};
        info.width = W;
        info.height = H;
        info.image = img.i;
        info.view = img.v;
        info.cfaPattern = pattern;
        std::memcpy(info.whiteBalance, wb, sizeof wb);
        info.params.autoRadius = autoRadius != 0;
        info.params.radius = radius;
        info.params.radiusOffset = offset;
        info.params.autoContrast = autoContrast != 0;
        info.params.contrast = contrast;
        info.params.iterations = iterations;
        info.params.iterationCheck = iterCheck != 0;
        // CS_BAND_ROWS=n: deconvolution in bands of n tile rows, submitting
        // between them as the app does (results must not change).
        if (const char* band = std::getenv("CS_BAND_ROWS")) info.deconvBandRows = uint32_t(std::atoi(band));
        // CS_STRIP_PIXELS=n: scratch budget, forcing strip mode on smaller images.
        if (const char* strip = std::getenv("CS_STRIP_PIXELS")) info.maxStripPixels = uint64_t(std::atoll(strip));
        uint32_t boundaries = 0;

        auto submit = [&] {
            vktest::ck(vkEndCommandBuffer(cmd), "end");
            VkSubmitInfo si{};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.commandBufferCount = 1;
            si.pCommandBuffers = &cmd;
            vktest::ck(vkQueueSubmit(c.q, 1, &si, VK_NULL_HANDLE), "submit");
            vktest::ck(vkQueueWaitIdle(c.q), "wait");
        };
        auto begin = [&] {
            vktest::ck(vkResetCommandBuffer(cmd, 0), "reset");
            VkCommandBufferBeginInfo bi{};
            bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            vktest::ck(vkBeginCommandBuffer(cmd, &bi), "begin");
        };
        auto upload = [&] {
            uint16_t* p;
            vktest::ck(vkMapMemory(c.dev, staging.m, 0, bytes, 0, reinterpret_cast<void**>(&p)), "map");
            for (size_t i = 0; i < n; ++i) {
                p[i * 4 + 0] = floatToHalf(rgb[i * 3 + 0]);
                p[i * 4 + 1] = floatToHalf(rgb[i * 3 + 1]);
                p[i * 4 + 2] = floatToHalf(rgb[i * 3 + 2]);
                p[i * 4 + 3] = floatToHalf(1.0f);
            }
            vkUnmapMemory(c.dev, staging.m);
            begin();
            barrier(cmd, img.i, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy region{};
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            region.imageExtent = {W, H, 1};
            vkCmdCopyBufferToImage(cmd, staging.b, img.i, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
            barrier(cmd, img.i, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            submit();
        };

        if (info.deconvBandRows) {
            cs.setTileBoundary([&] {
                ++boundaries;
                vktest::ck(vkEndCommandBuffer(cmd), "band end");
                VkSubmitInfo si{};
                si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                si.commandBufferCount = 1;
                si.pCommandBuffers = &cmd;
                vktest::ck(vkQueueSubmit(c.q, 1, &si, VK_NULL_HANDLE), "band submit");
                vktest::ck(vkQueueWaitIdle(c.q), "band wait");
                vktest::ck(vkResetCommandBuffer(cmd, 0), "band reset");
                VkCommandBufferBeginInfo bi{};
                bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
                vktest::ck(vkBeginCommandBuffer(cmd, &bi), "band begin");
                VkMemoryBarrier mb{};
                mb.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
                mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                     VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, 1, &mb,
                                     0, nullptr, 0, nullptr);
            });
        }
        std::vector<double> gpuMs;
        for (uint32_t r = 0; r < std::max(1u, repeats); ++r) {
            upload();
            begin();
            vkCmdResetQueryPool(cmd, queries, 0, 2 + rawr::capsharp::kTimestamps);
            info.commandBuffer = cmd;
            info.timingPool = queries;
            info.timingQuery = 2;
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, 0);
            if (!cs.record(info)) {
                std::cerr << "record rejected the input\n";
                return 1;
            }
            vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, 1);
            submit();
            uint64_t ts[2 + rawr::capsharp::kTimestamps];
            vktest::ck(vkGetQueryPoolResults(c.dev, queries, 0, 2 + rawr::capsharp::kTimestamps, sizeof ts, ts,
                                             sizeof(uint64_t), VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
                       "query results");
            const double period = c.prop.limits.timestampPeriod * 1e-6;
            gpuMs.push_back(double(ts[1] - ts[0]) * period);
            const char* names[] = {"radius", "contrast", "blendRaw", "iirRows", "transpose", "iirCols", "yold", "deconv"};
            std::cerr << "  stages ms:";
            for (uint32_t k = 0; k + 1 < rawr::capsharp::kTimestamps; ++k)
                std::cerr << ' ' << names[k] << '=' << double(ts[3 + k] - ts[2 + k]) * period;
            std::cerr << "\n";
        }
        begin();
        barrier(cmd, img.i, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {W, H, 1};
        vkCmdCopyImageToBuffer(cmd, img.i, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.b, 1, &region);
        submit();
        std::vector<float> out(n * 3);
        {
            uint16_t* p;
            vktest::ck(vkMapMemory(c.dev, staging.m, 0, bytes, 0, reinterpret_cast<void**>(&p)), "map");
            for (size_t i = 0; i < n; ++i)
                for (int ch = 0; ch < 3; ++ch) out[i * 3 + ch] = vktest::halfToFloat(p[i * 4 + ch]);
            vkUnmapMemory(c.dev, staging.m);
        }
        std::ofstream(dir + "/gpu_rgb.f32", std::ios::binary)
            .write(reinterpret_cast<const char*>(out.data()), std::streamsize(out.size() * 4));
        const auto res = cs.lastResult();
        std::ofstream infoFile(dir + "/gpu_info.txt");
        infoFile << "radius " << res.radius << "\ncontrast " << res.contrast << "\nvalid " << res.valid
                 << "\nscratch_bytes " << cs.scratchBytes() << "\ngpu_ms";
        for (double ms : gpuMs) infoFile << ' ' << ms;
        infoFile << "\n";
        if (info.deconvBandRows) std::cerr << "bands: " << boundaries << " boundaries per run x " << repeats << "\n";
        std::cerr << "strips " << cs.lastStripCount() << "\n";
        std::cerr << "radius " << res.radius << " contrast " << res.contrast << " valid " << res.valid << " gpu_ms";
        for (double ms : gpuMs) std::cerr << ' ' << ms;
        std::cerr << "\n";

        vkDestroyQueryPool(c.dev, queries, nullptr);
        vkDestroyCommandPool(c.dev, cmdPool, nullptr);
        vktest::delImg(c.dev, img);
        vktest::delBuf(c.dev, staging);
        // cs must be destroyed before the device.
    } catch (const std::exception& e) {
        std::cerr << "error: " << e.what() << "\n";
        return 1;
    }
    return 0;
}
