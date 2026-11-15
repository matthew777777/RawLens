// SPDX-License-Identifier: GPL-3.0-or-later
#include "hexa_v2.h"

#include <vulkan/vulkan.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace hexa {
namespace {

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }

inline int colourAt(int gx, int gy) {
    const int by = (gy >> 2) & 1, bx = (gx >> 2) & 1;
    return by == 0 ? (bx == 0 ? 1 : 2) : (bx == 0 ? 0 : 1);
}

// Slots (T*T vec4 each), see hexa_point.comp.
enum { V = 0, A = 1, DENG = 2, DENB = 3, D = 4, P = 5, L = 6, XR = 7, XG = 8, XB = 9, AB1 = 10, AB2 = 11,
       R = 12, TMP = 13 /* 13..15 */, KERN = 16, SLOTS = 17 };
// Halo = init (gauss 9 + gauss 9) + one RI round (box 5 + box 5 + gauss 9) = 37 -> 40.
// One RI round: synthetic PSNR within 0.4 dB of three rounds (equal on edges), 3x cheaper on Mali.
constexpr int kHalo = 40, kRiIters = 1, kGaussTaps = 9 /* radius */, kBoxRadius = 5;
constexpr float kEps0 = 0.5f, kEpsRi = 4.0f;
// Shadow regularisation of the colour/luma ratio (DN); see hexa_point.comp MODE 2.
constexpr float kRatioFloor = 2.0f;

struct Push { int32_t a[4]; int32_t b[4]; int32_t c[4]; float f[4]; };

struct Vk {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    bool coherent = true;
    float* map = nullptr;
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline filter = VK_NULL_HANDLE, point[8] = {}, box[2] = {};
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool queries = VK_NULL_HANDLE;
    ~Vk() {
        if (device) {
            vkDeviceWaitIdle(device);
            if (queries) vkDestroyQueryPool(device, queries, nullptr);
            if (fence) vkDestroyFence(device, fence, nullptr);
            if (cmdPool) vkDestroyCommandPool(device, cmdPool, nullptr);
            if (pool) vkDestroyDescriptorPool(device, pool, nullptr);
            if (filter) vkDestroyPipeline(device, filter, nullptr);
            for (VkPipeline pp : point) if (pp) vkDestroyPipeline(device, pp, nullptr);
            for (VkPipeline pp : box) if (pp) vkDestroyPipeline(device, pp, nullptr);
            if (layout) vkDestroyPipelineLayout(device, layout, nullptr);
            if (setLayout) vkDestroyDescriptorSetLayout(device, setLayout, nullptr);
            if (map) vkUnmapMemory(device, memory);
            if (buffer) vkDestroyBuffer(device, buffer, nullptr);
            if (memory) vkFreeMemory(device, memory, nullptr);
            vkDestroyDevice(device, nullptr);
        }
        if (instance) vkDestroyInstance(instance, nullptr);
    }
};

#define VKCHECK(x, what) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { *error = std::string(what) + " " + std::to_string(int(r_)); return false; } } while (0)

bool init(Vk& vk, size_t bytes, const std::vector<uint32_t>& fs, const std::vector<uint32_t>& ps,
          const std::vector<uint32_t>& bs, std::string* error) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "rawlens-hexa";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
#ifdef __APPLE__
    const char* ext[] = {"VK_KHR_portability_enumeration"};
    ici.flags = 0x00000001;  // VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
    ici.enabledExtensionCount = 1;
    ici.ppEnabledExtensionNames = ext;
#endif
    VKCHECK(vkCreateInstance(&ici, nullptr, &vk.instance), "vkCreateInstance");
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(vk.instance, &n, nullptr);
    if (!n) { *error = "no Vulkan device"; return false; }
    std::vector<VkPhysicalDevice> gpus(n);
    vkEnumeratePhysicalDevices(vk.instance, &n, gpus.data());
    vk.gpu = gpus[0];
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(vk.gpu, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(vk.gpu, &qn, qf.data());
    vk.family = UINT32_MAX;
    for (uint32_t i = 0; i < qn; ++i) if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { vk.family = i; break; }
    if (vk.family == UINT32_MAX) { *error = "no compute queue"; return false; }
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = vk.family; qci.queueCount = 1; qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
#ifdef __APPLE__
    const char* dext[] = {"VK_KHR_portability_subset"};
    dci.enabledExtensionCount = 1; dci.ppEnabledExtensionNames = dext;
#endif
    VKCHECK(vkCreateDevice(vk.gpu, &dci, nullptr, &vk.device), "vkCreateDevice");
    vkGetDeviceQueue(vk.device, vk.family, 0, &vk.queue);

    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = bytes; bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    VKCHECK(vkCreateBuffer(vk.device, &bci, nullptr, &vk.buffer), "vkCreateBuffer");
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(vk.device, vk.buffer, &req);
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(vk.gpu, &mp);
    uint32_t type = UINT32_MAX;
    // Prefer host-visible + coherent (+ device-local on unified-memory GPUs such as Mali).
    for (int pass = 0; pass < 3 && type == UINT32_MAX; ++pass)
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i) {
            if (!(req.memoryTypeBits & (1u << i))) continue;
            auto fl = mp.memoryTypes[i].propertyFlags;
            bool hv = fl & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, hc = fl & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                 dl = fl & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            if ((pass == 0 && hv && hc && dl) || (pass == 1 && hv && hc) || (pass == 2 && hv)) { type = i; vk.coherent = hc; break; }
        }
    if (const char* forced = getenv("HEXA_MEMTYPE")) type = uint32_t(atoi(forced));
    if (getenv("HEXA_PROFILE"))
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            fprintf(stderr, "MEMTYPE %u flags=0x%x heap=%u%s\n", i, mp.memoryTypes[i].propertyFlags, mp.memoryTypes[i].heapIndex,
                    i == type ? " <- used" : "");
    if (type == UINT32_MAX) { *error = "no host-visible memory"; return false; }
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size; mai.memoryTypeIndex = type;
    VKCHECK(vkAllocateMemory(vk.device, &mai, nullptr, &vk.memory), "vkAllocateMemory");
    VKCHECK(vkBindBufferMemory(vk.device, vk.buffer, vk.memory, 0), "vkBindBufferMemory");
    void* m = nullptr;
    VKCHECK(vkMapMemory(vk.device, vk.memory, 0, VK_WHOLE_SIZE, 0, &m), "vkMapMemory");
    vk.map = static_cast<float*>(m);

    VkDescriptorSetLayoutBinding bind{0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dl.bindingCount = 1; dl.pBindings = &bind;
    VKCHECK(vkCreateDescriptorSetLayout(vk.device, &dl, nullptr, &vk.setLayout), "setLayout");
    VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Push)};
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1; pl.pSetLayouts = &vk.setLayout; pl.pushConstantRangeCount = 1; pl.pPushConstantRanges = &pr;
    VKCHECK(vkCreatePipelineLayout(vk.device, &pl, nullptr, &vk.layout), "pipelineLayout");
    auto pipe = [&](const std::vector<uint32_t>& code, VkPipeline* out, std::vector<int32_t> spec = {}) {
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        sm.codeSize = code.size() * 4; sm.pCode = code.data();
        VkShaderModule mod;
        if (vkCreateShaderModule(vk.device, &sm, nullptr, &mod) != VK_SUCCESS) return false;
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        std::vector<VkSpecializationMapEntry> me(spec.size());
        for (size_t i = 0; i < spec.size(); ++i) me[i] = {uint32_t(i), uint32_t(i * 4), 4};
        VkSpecializationInfo si{uint32_t(spec.size()), me.data(), spec.size() * 4, spec.data()};
        cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, mod, "main",
                    spec.empty() ? nullptr : &si};
        cp.layout = vk.layout;
        bool ok = vkCreateComputePipelines(vk.device, VK_NULL_HANDLE, 1, &cp, nullptr, out) == VK_SUCCESS;
        vkDestroyShaderModule(vk.device, mod, nullptr);
        return ok;
    };
    bool ok = pipe(fs, &vk.filter) && pipe(bs, &vk.box[0], {0, kBoxRadius}) && pipe(bs, &vk.box[1], {1, kBoxRadius});
    for (int m = 0; m < 8 && ok; ++m) ok = pipe(ps, &vk.point[m], {m});
    if (!ok) { *error = "pipeline creation failed"; return false; }
    VkDescriptorPoolSize ps1{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
    VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dp.maxSets = 1; dp.poolSizeCount = 1; dp.pPoolSizes = &ps1;
    VKCHECK(vkCreateDescriptorPool(vk.device, &dp, nullptr, &vk.pool), "descriptorPool");
    VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    da.descriptorPool = vk.pool; da.descriptorSetCount = 1; da.pSetLayouts = &vk.setLayout;
    VKCHECK(vkAllocateDescriptorSets(vk.device, &da, &vk.set), "descriptorSet");
    VkDescriptorBufferInfo bi{vk.buffer, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = vk.set; w.descriptorCount = 1; w.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; w.pBufferInfo = &bi;
    vkUpdateDescriptorSets(vk.device, 1, &w, 0, nullptr);
    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT; cpi.queueFamilyIndex = vk.family;
    VKCHECK(vkCreateCommandPool(vk.device, &cpi, nullptr, &vk.cmdPool), "commandPool");
    VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cba.commandPool = vk.cmdPool; cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cba.commandBufferCount = 1;
    VKCHECK(vkAllocateCommandBuffers(vk.device, &cba, &vk.cmd), "commandBuffer");
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VKCHECK(vkCreateFence(vk.device, &fci, nullptr, &vk.fence), "fence");
    return true;
}

// scipy.ndimage.gaussian_filter(truncate=3) weights, zero-padded to kGaussTaps radius.
void gaussTaps(float sigma, float* w) {
    const int r = int(3.0f * sigma + 0.5f);
    double s = 0;
    for (int i = -kGaussTaps; i <= kGaussTaps; ++i) {
        double v = std::abs(i) <= r ? std::exp(-0.5 * i * i / (double(sigma) * sigma)) : 0.0;
        w[i + kGaussTaps] = float(v); s += v;
    }
    for (int i = 0; i <= 2 * kGaussTaps; ++i) w[i] = float(w[i] / s);
}

inline int periodicIndex(int g, int size) {  // keeps the 8-pixel CFA period outside the frame
    if (g < 0) return ((g % 8) + 8) % 8;
    if (g >= size) return size - 8 + ((g - size) % 8);
    return g;
}

}  // namespace

GainTable estimateGains(const uint16_t* raw, size_t stride, int w, int h, float black, float white, int TY, int TX) {
    GainTable t; t.ty = TY; t.tx = TX;
    const size_t cells = size_t(TY) * TX * 3;
    std::vector<double> num(cells * 16, 0.0), den(cells, 0.0);
    std::vector<double> gnum(3 * 16, 0.0), gden(3, 0.0);
    const int bw = w / 4, bh = h / 4;
    for (int by = 0; by < bh; ++by) {
        const int ty = std::min(TY - 1, by * TY / bh);
        for (int bx = 0; bx < bw; ++bx) {
            float v[16], mean = 0, mx = 0;
            for (int iy = 0; iy < 4; ++iy) for (int ix = 0; ix < 4; ++ix) {
                const float s = raw[size_t(by * 4 + iy) * stride + bx * 4 + ix];
                mx = std::max(mx, s); v[iy * 4 + ix] = s - black; mean += s - black;
            }
            mean /= 16;
            if (mean <= 2 || mx >= white - 1) continue;
            const int c = colourAt(bx * 4, by * 4), tx = std::min(TX - 1, bx * TX / bw);
            const size_t cell = (size_t(ty) * TX + tx) * 3 + c;
            for (int k = 0; k < 16; ++k) { num[cell * 16 + k] += v[k]; gnum[c * 16 + k] += v[k]; }
            den[cell] += mean; gden[c] += mean;
        }
    }
    t.g.assign(cells * 16, 1.0f);
    for (size_t cell = 0; cell < cells; ++cell) {
        const int c = int(cell % 3);
        const bool local = den[cell] > 2000.0 * 16;  // enough signal for a stable per-tile table
        for (int k = 0; k < 16; ++k) {
            const double n = local ? num[cell * 16 + k] : gnum[c * 16 + k], d = local ? den[cell] : gden[c];
            t.g[cell * 16 + k] = d > 0 ? float(n / d) : 1.0f;
        }
    }
    return t;
}

bool convert(const uint16_t* raw, size_t stride, int w, int h, float black, float white, const GainTable& gains,
             const std::vector<uint32_t>& fs, const std::vector<uint32_t>& ps, const std::vector<uint32_t>& bs,
             uint16_t* out, Timing* timing, std::string* error, int interior) {
    const auto t0 = Clock::now();
    if (w % 8 || h % 8 || interior % 16 || gains.g.size() != size_t(gains.ty) * gains.tx * 3 * 16) {
        *error = "invalid geometry"; return false;
    }
    const int T = interior + 2 * kHalo;
    const size_t TT = size_t(T) * T;
    Vk vk;
    if (!init(vk, TT * SLOTS * 16, fs, ps, bs, error)) return false;
    float* d = vk.map;
    // Kernels in slot KERN: [0, 2*9] gauss (sigma 3, 2.2, 3), then box radius 5 (offset 32).
    {
        float gr[2 * kGaussTaps + 1], gg[2 * kGaussTaps + 1];
        gaussTaps(3.0f, gr); gaussTaps(2.2f, gg);
        float* k = d + KERN * TT * 4;
        for (int i = 0; i <= 2 * kGaussTaps; ++i) { k[i * 4] = gr[i]; k[i * 4 + 1] = gg[i]; k[i * 4 + 2] = gr[i]; k[i * 4 + 3] = 0; }
        for (int i = 0; i <= 2 * kBoxRadius; ++i) for (int c = 0; c < 4; ++c) k[(32 + i) * 4 + c] = 1.0f / (2 * kBoxRadius + 1);
    }
    const float scale = 65535.0f / (white - black);
    const int gx = (w + interior - 1) / interior, gy = (h + interior - 1) / interior;
    const uint32_t groups = uint32_t((T + 15) / 16);
    const bool profile = getenv("HEXA_PROFILE") != nullptr;
    std::vector<std::string> labels;
    if (profile) {
        VkQueryPoolCreateInfo q{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
        q.queryType = VK_QUERY_TYPE_TIMESTAMP; q.queryCount = 256;
        vkCreateQueryPool(vk.device, &q, nullptr, &vk.queries);
    }
    double gpuMs = 0;
    for (int tyI = 0; tyI < gy; ++tyI) for (int txI = 0; txI < gx; ++txI) {
        const int ox = txI * interior - kHalo, oy = tyI * interior - kHalo;
        // Fill V: gain-corrected, black-subtracted samples, periodic extension beyond the frame.
        for (int y = 0; y < T; ++y) {
            const int sy = periodicIndex(oy + y, h);
            const float fy = std::clamp(float(sy) / h * gains.ty - 0.5f, 0.0f, float(gains.ty - 1));
            const int y0 = std::min(int(fy), gains.ty - 2 < 0 ? 0 : gains.ty - 2);
            const float ay = gains.ty > 1 ? fy - y0 : 0.0f;
            float* row = d + (V * TT + size_t(y) * T) * 4;
            const uint16_t* src = raw + size_t(sy) * stride;
            for (int x = 0; x < T; ++x) {
                const int sx = periodicIndex(ox + x, w);
                const int c = colourAt(sx, sy), k = (sy & 3) * 4 + (sx & 3);
                const float fx = std::clamp(float(sx) / w * gains.tx - 0.5f, 0.0f, float(gains.tx - 1));
                const int x0 = std::min(int(fx), gains.tx - 2 < 0 ? 0 : gains.tx - 2);
                const float ax = gains.tx > 1 ? fx - x0 : 0.0f;
                auto G = [&](int yy, int xx) {
                    yy = std::min(yy, gains.ty - 1); xx = std::min(xx, gains.tx - 1);
                    return gains.g[((size_t(yy) * gains.tx + xx) * 3 + c) * 16 + k];
                };
                const float g = (1 - ay) * ((1 - ax) * G(y0, x0) + ax * G(y0, x0 + 1)) +
                                ay * ((1 - ax) * G(y0 + 1, x0) + ax * G(y0 + 1, x0 + 1));
                row[x * 4] = (float(src[sx]) - black) / g;
            }
        }
        // Record the pass sequence.
        const auto tg = Clock::now();
        vkResetCommandBuffer(vk.cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(vk.cmd, &bi);
        const bool prof = profile && tyI == 0 && txI == 0;
        uint32_t qi = 0;
        if (prof) { vkCmdResetQueryPool(vk.cmd, vk.queries, 0, 256); vkCmdWriteTimestamp(vk.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, vk.queries, qi++); labels.clear(); }
        auto stamp = [&](const std::string& what) {
            if (prof) { vkCmdWriteTimestamp(vk.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, vk.queries, qi++); labels.push_back(what); }
        };
        vkCmdBindDescriptorSets(vk.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk.layout, 0, 1, &vk.set, 0, nullptr);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        auto barrier = [&] {
            vkCmdPipelineBarrier(vk.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 0, 1, &mb, 0, nullptr, 0, nullptr);
        };
        const bool firstTile = tyI == 0 && txI == 0;
        auto point = [&](int mode, uint32_t gxN, uint32_t gyN) {
            Push p{};
            p.a[0] = mode; p.a[1] = firstTile ? 1 : 0; p.b[0] = T; p.b[1] = ox; p.b[2] = oy; p.b[3] = kHalo; p.c[0] = interior / 2;
            p.f[0] = kEps0; p.f[1] = kEpsRi; p.f[2] = kRatioFloor;
            vkCmdBindPipeline(vk.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk.point[mode]);
            vkCmdPushConstants(vk.cmd, vk.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof p, &p);
            vkCmdDispatch(vk.cmd, gxN, gyN, 1);
            barrier();
            stamp("point" + std::to_string(mode));
        };
        // Gaussian: shared-memory filter both ways. Box: shared-memory X, running-sum Y (one thread per
        // column walking down: coalesced, ~2 loads per output). A register-window Gaussian Y was 10x
        // slower on Mali-G615 MC2 (too few threads in flight).
        auto filterX = [&](int slot, int count, int radius, int kernOffset) {
            Push p{};
            p.a[1] = slot; p.a[2] = TMP; p.a[3] = KERN; p.b[0] = T; p.b[1] = radius; p.b[2] = 0; p.b[3] = kernOffset;
            vkCmdBindPipeline(vk.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk.filter);
            vkCmdPushConstants(vk.cmd, vk.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof p, &p);
            vkCmdDispatch(vk.cmd, groups, groups, uint32_t(count));
            barrier();
        };
        auto gauss = [&](int slot, int count) {
            filterX(slot, count, kGaussTaps, 0); stamp("gaussX");
            Push p{};
            p.a[1] = TMP; p.a[2] = slot; p.a[3] = KERN; p.b[0] = T; p.b[1] = kGaussTaps; p.b[2] = 1; p.b[3] = 0;
            vkCmdBindPipeline(vk.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk.filter);
            vkCmdPushConstants(vk.cmd, vk.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof p, &p);
            vkCmdDispatch(vk.cmd, groups, groups, uint32_t(count));
            barrier(); stamp("gaussY");
        };
        auto box = [&](int slot, int count) {
            filterX(slot, count, kBoxRadius, 32); stamp("boxX");
            Push p{};
            p.a[1] = TMP; p.a[2] = slot; p.b[0] = T;
            vkCmdBindPipeline(vk.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, vk.box[1]);
            vkCmdPushConstants(vk.cmd, vk.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof p, &p);
            vkCmdDispatch(vk.cmd, uint32_t((T + 63) / 64), uint32_t((T + 31) / 32), uint32_t(count));
            barrier(); stamp("boxY");
        };
        point(0, groups, groups);
        if (firstTile) { gauss(DENG, 1); box(DENB, 1); }   // masks are 8-periodic: same for every tile
        gauss(A, 1);
        point(1, groups, groups); gauss(D, 1);
        point(2, groups, groups);
        for (int it = 0; it < kRiIters; ++it) {
            point(3, groups, groups); box(XR, 3);
            point(4, groups, groups); box(AB1, 2);
            point(5, groups, groups); gauss(R, 1);
            point(6, groups, groups);
        }
        const uint32_t og = uint32_t((interior / 2 + 15) / 16);
        point(7, og, og);
        vkEndCommandBuffer(vk.cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1; si.pCommandBuffers = &vk.cmd;
        VKCHECK(vkQueueSubmit(vk.queue, 1, &si, vk.fence), "vkQueueSubmit");
        VKCHECK(vkWaitForFences(vk.device, 1, &vk.fence, VK_TRUE, 30ull * 1000000000ull), "vkWaitForFences");
        vkResetFences(vk.device, 1, &vk.fence);
        if (!vk.coherent) {
            VkMappedMemoryRange rg{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
            rg.memory = vk.memory; rg.size = VK_WHOLE_SIZE;
            vkInvalidateMappedMemoryRanges(vk.device, 1, &rg);
        }
        gpuMs += msSince(tg);
        if (prof && qi > 1) {
            std::vector<uint64_t> ts(qi);
            vkGetQueryPoolResults(vk.device, vk.queries, 0, qi, ts.size() * 8, ts.data(), 8, VK_QUERY_RESULT_64_BIT);
            VkPhysicalDeviceProperties pp; vkGetPhysicalDeviceProperties(vk.gpu, &pp);
            std::vector<std::pair<std::string, double>> agg;
            for (uint32_t i = 1; i < qi; ++i) {
                double ms = (ts[i] - ts[i - 1]) * pp.limits.timestampPeriod / 1e6;
                auto it = std::find_if(agg.begin(), agg.end(), [&](auto& a) { return a.first == labels[i - 1]; });
                if (it == agg.end()) agg.push_back({labels[i - 1], ms}); else it->second += ms;
            }
            for (auto& a : agg) fprintf(stderr, "PROFILE %-8s %8.2f ms\n", a.first.c_str(), a.second);
        }
        // Collect the interior/2 output block.
        const int half = interior / 2, ow = w / 2, oh = h / 2, bx0 = txI * half, by0 = tyI * half;
        const float* o = d + D * TT * 4;
        for (int y = 0; y < half && by0 + y < oh; ++y) {
            uint16_t* dst = out + (size_t(by0 + y) * ow + bx0) * 3;
            for (int x = 0; x < half && bx0 + x < ow; ++x)
                for (int c = 0; c < 3; ++c) {
                    const float v = o[(size_t(y) * half + x) * 4 + c] * scale;
                    dst[x * 3 + c] = uint16_t(std::clamp(v + 0.5f, 0.0f, 65535.0f));
                }
        }
        if (timing) timing->tiles++;
    }
    if (timing) { timing->gpuMs = gpuMs; timing->totalMs = msSince(t0); }
    return true;
}

}  // namespace hexa
