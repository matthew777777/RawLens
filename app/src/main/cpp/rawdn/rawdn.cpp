// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include "rawdn.h"

#include <vulkan/vulkan.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Buf {
    VkBuffer b{};
    VkDeviceMemory m{};
    VkDeviceSize size{};
    bool incoherent{};  // host cached without HOST_COHERENT: invalidate before reading a map
};

void ck(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(std::string(what) + " failed: VkResult " + std::to_string(int(r)));
}

struct Level {
    int qw{}, qh{}, chans{};
    Buf z, p, e;
    VkDescriptorSet pilot{}, wiener{}, down{}, resid{}, fin{};
};

}  // namespace

struct RawdnContext {
    VkInstance inst{};
    VkPhysicalDevice pd{};
    VkDevice dev{};
    VkQueue queue{};
    uint32_t qf{};
    VkCommandPool pool{};
    VkDescriptorSetLayout dsl{};
    VkPipelineLayout layout{};
    VkDescriptorPool dpool{};
    VkPipeline pipe[RAWDN_SHADER_COUNT + 2]{};  // last two: DCT with STAGE = 1, 2
    VkShaderModule mod[RAWDN_SHADER_COUNT]{};
    Buf gains, io, sites;  // sites: denoised CFA, then the luma residual (host visible); residual rows reuse lv[0].p
    std::vector<Level> lv;
    VkDescriptorSet packSet{}, sitesSet{}, unpackSet{}, mixSet{};
    VkQueryPool qp{};
    double stampNs{1};
    int nStamp{0};
    double stage[8]{};
    int w{}, h{}, levels{};
    double gpuMs{-1}, copyInMs{0}, copyOutMs{0};
    // Outputs handed out without a copy (rawdn_process_mapped) keep the device alive until released.
    std::mutex exportMu;
    int exported{0};
    bool dying{false};
    void* sitesMap{};  // set by a strip with no output frame: the map stays open for export

    uint32_t memType(uint32_t bits, VkMemoryPropertyFlags want) const {
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
        throw std::runtime_error("no suitable Vulkan memory type");
    }
    bool hasType(uint32_t bits, VkMemoryPropertyFlags want) const {
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return true;
        return false;
    }
    Buf mkBuf(VkDeviceSize n, bool host, bool cached = false) {
        Buf x{};
        x.size = n;
        VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        ci.size = n;
        ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        ck(vkCreateBuffer(dev, &ci, nullptr, &x.b), "vkCreateBuffer");
        VkMemoryRequirements mr{};
        vkGetBufferMemoryRequirements(dev, x.b, &mr);
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = mr.size;
        const VkMemoryPropertyFlags hostFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        // Readback through uncached memory is many times slower than a cached map on mobile. Mali offers cached
        // memory only without HOST_COHERENT: then the map is invalidated after the GPU finishes (see invalidate()).
        const VkMemoryPropertyFlags hostCached = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
        if (cached && hasType(mr.memoryTypeBits, hostFlags | VK_MEMORY_PROPERTY_HOST_CACHED_BIT))
            ai.memoryTypeIndex = memType(mr.memoryTypeBits, hostFlags | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        else if (cached && hasType(mr.memoryTypeBits, hostCached)) {
            ai.memoryTypeIndex = memType(mr.memoryTypeBits, hostCached);
            x.incoherent = true;
        } else
            ai.memoryTypeIndex = memType(mr.memoryTypeBits, host ? hostFlags : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        VkResult r = vkAllocateMemory(dev, &ai, nullptr, &x.m);
        if (r != VK_SUCCESS) {
            vkDestroyBuffer(dev, x.b, nullptr);
            ck(r, "vkAllocateMemory");
        }
        ck(vkBindBufferMemory(dev, x.b, x.m, 0), "vkBindBufferMemory");
        return x;
    }
    void delBuf(Buf& x) {
        if (x.b) vkDestroyBuffer(dev, x.b, nullptr);
        if (x.m) vkFreeMemory(dev, x.m, nullptr);
        x = {};
    }
    void freeFrame() {
        if (!dev) return;
        vkDeviceWaitIdle(dev);
        if (dpool) vkDestroyDescriptorPool(dev, dpool, nullptr);
        dpool = {};
        delBuf(io);
        delBuf(sites);
        delBuf(gains);
        for (auto& l : lv) {
            delBuf(l.z);
            delBuf(l.p);
            delBuf(l.e);
        }
        lv.clear();
        w = h = levels = 0;
    }
    VkDescriptorSet mkSet(const Buf* b0, const Buf* b1, const Buf* b2, const Buf* b3, VkDeviceSize off1 = 0) {
        VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        ai.descriptorPool = dpool;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &dsl;
        VkDescriptorSet s{};
        ck(vkAllocateDescriptorSets(dev, &ai, &s), "vkAllocateDescriptorSets");
        const Buf* bs[4] = {b0, b1, b2, b3};
        VkDescriptorBufferInfo bi[4]{};
        VkWriteDescriptorSet wr[4]{};
        for (int i = 0; i < 4; i++) {
            bi[i] = {bs[i]->b, i == 1 ? off1 : 0, VK_WHOLE_SIZE};
            wr[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            wr[i].dstSet = s;
            wr[i].dstBinding = uint32_t(i);
            wr[i].descriptorCount = 1;
            wr[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            wr[i].pBufferInfo = &bi[i];
        }
        vkUpdateDescriptorSets(dev, 4, wr, 0, nullptr);
        return s;
    }
    void prepare(int width, int height, int wantLevels) {
        // Level-1 quads cover 4x4 CFA pixels. Every level halves; the deepest
        // keeps at least 16 quads (32 px) per side and an even quad count.
        int q1w = (width + 3) / 4, q1h = (height + 3) / 4;
        int n = std::max(1, wantLevels);
        while (n > 1 && (std::min(q1w, q1h) >> (n - 1)) < 16) n--;
        int align = 1 << n;
        q1w = (q1w + align - 1) / align * align;
        q1h = (q1h + align - 1) / align * align;
        if (width == w && height == h && n == levels) return;
        freeFrame();
        try {
            io = mkBuf(VkDeviceSize(width) * height * 2, true);
            sites = mkBuf(VkDeviceSize(width) * height * 4, true, true);
            gains = mkBuf(VkDeviceSize((q1w + 2) / 4 + 1) * ((q1h + 2) / 4 + 1) * 64 * 4, false);
            lv.resize(size_t(n));
            for (int l = 0; l < n; l++) {
                Level& L = lv[size_t(l)];
                L.qw = q1w >> l;
                L.qh = q1h >> l;
                L.chans = l == 0 ? 4 : 3;
                VkDeviceSize q = VkDeviceSize(L.qw) * L.qh;
                L.z = mkBuf(q * 8 * L.chans, false);
                L.p = mkBuf(q * (l == 0 ? 48 : 8 * L.chans), false);  // level 0: later the residual rows
                L.e = mkBuf(q * 8 * L.chans, false);
            }
            VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, uint32_t(4 * (5 * n + 4))};
            VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pi.maxSets = uint32_t(5 * n + 4);
            pi.poolSizeCount = 1;
            pi.pPoolSizes = &ps;
            ck(vkCreateDescriptorPool(dev, &pi, nullptr, &dpool), "vkCreateDescriptorPool");
            // After the final estimate the level-0 pilot is free: it holds the removed-noise rows.
            packSet = mkSet(&io, &lv[0].z, &io, &io);
            sitesSet = mkSet(&lv[0].e, &io, &sites, &lv[0].p);
            unpackSet = mkSet(&lv[0].p, &sites, &sites, &sites, VkDeviceSize(width) * height * 2);
            mixSet = mkSet(&lv[0].z, &lv[0].e, &lv[0].e, &lv[0].e);
            for (int l = 0; l < n; l++) {
                Level& L = lv[size_t(l)];
                Level& C = lv[size_t(std::min(l + 1, n - 1))];
                L.pilot = mkSet(&L.z, &L.z, &L.p, &L.p);
                L.wiener = mkSet(&L.z, &L.p, &L.e, &gains);
                L.down = mkSet(&L.z, &C.z, &C.z, &C.z);
                L.resid = mkSet(&C.e, &L.e, &C.p, &C.p);   // coarse pilot is free too
                L.fin = mkSet(&C.p, &L.e, &L.e, &L.e);
            }
        } catch (...) {
            freeFrame();
            throw;
        }
        w = width;
        h = height;
        levels = n;
    }
};

namespace {

constexpr size_t kStripPixels = 13u << 20;

struct PackPc {
    int32_t qw, qh, cw, ch, diagGreen;
    float range;        // full scale of the snorm16 planes
    int32_t pad;
    float keptColour;   // variance share of the colour noise left in, (1 - chromaStrength)^2
    float a, b, scale, grain;
    float black[4];
    float gain[4];
    float white, outWhite, coarseCut;
};
struct DctPc {
    int32_t qw, qh, plane0, phase;
    float var[4], hf[4], lf[4], lam2, range;
};
struct FoldPc {
    int32_t fqw, fqh, cqw, cqh;
};

void barrier(VkCommandBuffer cb) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    const VkPipelineStageFlags st = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
    vkCmdPipelineBarrier(cb, st, st, 0, 1, &mb, 0, nullptr, 0, nullptr);
}

bool hasExt(VkPhysicalDevice pd, const char* name) {
    uint32_t n = 0;
    if (pd) vkEnumerateDeviceExtensionProperties(pd, nullptr, &n, nullptr);
    else vkEnumerateInstanceExtensionProperties(nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> e(n);
    if (pd) vkEnumerateDeviceExtensionProperties(pd, nullptr, &n, e.data());
    else vkEnumerateInstanceExtensionProperties(nullptr, &n, e.data());
    for (const auto& x : e)
        if (!std::strcmp(x.extensionName, name)) return true;
    return false;
}

void fail(char* errmsg, int errlen, const char* what) {
    if (errmsg && errlen > 0) std::snprintf(errmsg, size_t(errlen), "%s", what);
}

}  // namespace

extern "C" void rawdn_default_params(RawdnParams* p) {
    std::memset(p, 0, sizeof(*p));
    p->wb[0] = p->wb[1] = p->wb[2] = 1.f;
    p->white = 1023.f;
    p->lambda = 2.7f;
    p->luma = 1.f;
    p->chroma = 4.f;
    p->lumaFine = 0.8f;
    p->diffFine = 0.5f;
    p->chromaFine = 1.f;
    p->grain = 0.55f;
    p->coarseCut = 1.f;
    p->chromaStrength = 1.f;
    p->levels = 3;
}

extern "C" void rawdn_auto_luma(RawdnParams* p, float snr, float texture, float lumaStrength) {
    // ISO 12232: SNR 10 acceptable, 40 excellent. Noisy frames keep .6 of the removed luma, clean frames .9.
    const float t = std::fmin(1.f, std::fmax(0.f, std::log(snr / 10.f) / std::log(4.f)));
    const float s = std::clamp(lumaStrength, 0.f, 1.f);
    const float grain = std::fmin(1.f, (0.55f + 0.35f * t) * (1.1f - 0.1f * t) * texture);
    p->grain = 1.f - s * (1.f - grain);
    // The coarse part is blotches when the frame is noisy and real texture when it is clean.
    p->coarseCut = s * (1.f - t);
}

extern "C" RawdnContext* rawdn_create(char* errmsg, int errlen) {
    auto* c = new RawdnContext();
    try {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "rawlens_rawdn";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ii{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ii.pApplicationInfo = &app;
        // MoltenVK behind the loader needs portability enumeration; ask only where it exists.
        const char* ie[] = {"VK_KHR_portability_enumeration"};
        if (hasExt(nullptr, ie[0])) {
            ii.flags |= 0x00000001;  // VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
            ii.enabledExtensionCount = 1;
            ii.ppEnabledExtensionNames = ie;
        }
        ck(vkCreateInstance(&ii, nullptr, &c->inst), "vkCreateInstance");
        uint32_t n = 0;
        ck(vkEnumeratePhysicalDevices(c->inst, &n, nullptr), "vkEnumeratePhysicalDevices");
        if (!n) throw std::runtime_error("no Vulkan device");
        std::vector<VkPhysicalDevice> ps(n);
        vkEnumeratePhysicalDevices(c->inst, &n, ps.data());
        c->pd = ps[0];
        uint32_t nq = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(c->pd, &nq, nullptr);
        std::vector<VkQueueFamilyProperties> qp(nq);
        vkGetPhysicalDeviceQueueFamilyProperties(c->pd, &nq, qp.data());
        c->qf = UINT32_MAX;
        for (uint32_t i = 0; i < nq; i++)
            if (qp[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                c->qf = i;
                break;
            }
        if (c->qf == UINT32_MAX) throw std::runtime_error("no compute queue");
        float pr = 1;
        VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qi.queueFamilyIndex = c->qf;
        qi.queueCount = 1;
        qi.pQueuePriorities = &pr;
        VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        di.queueCreateInfoCount = 1;
        di.pQueueCreateInfos = &qi;
        // Mali-G615 lost the device in the previous denoiser without robust access; it costs nothing measurable.
        VkPhysicalDeviceFeatures have{}, on{};
        vkGetPhysicalDeviceFeatures(c->pd, &have);
        on.robustBufferAccess = have.robustBufferAccess;
        di.pEnabledFeatures = &on;
        const char* de[] = {"VK_KHR_portability_subset"};
        if (hasExt(c->pd, de[0])) {
            di.enabledExtensionCount = 1;
            di.ppEnabledExtensionNames = de;
        }
        ck(vkCreateDevice(c->pd, &di, nullptr, &c->dev), "vkCreateDevice");
        vkGetDeviceQueue(c->dev, c->qf, 0, &c->queue);
        VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cp.queueFamilyIndex = c->qf;
        ck(vkCreateCommandPool(c->dev, &cp, nullptr, &c->pool), "vkCreateCommandPool");
        VkPhysicalDeviceProperties pp{};
        vkGetPhysicalDeviceProperties(c->pd, &pp);
        c->stampNs = pp.limits.timestampPeriod;
        if (qp[c->qf].timestampValidBits) {
            VkQueryPoolCreateInfo qci{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
            qci.queryType = VK_QUERY_TYPE_TIMESTAMP;
            qci.queryCount = 8;
            ck(vkCreateQueryPool(c->dev, &qci, nullptr, &c->qp), "vkCreateQueryPool");
        }
        VkDescriptorSetLayoutBinding b[4]{};
        for (uint32_t i = 0; i < 4; i++) b[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        li.bindingCount = 4;
        li.pBindings = b;
        ck(vkCreateDescriptorSetLayout(c->dev, &li, nullptr, &c->dsl), "vkCreateDescriptorSetLayout");
        VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, 96};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &c->dsl;
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &pcr;
        ck(vkCreatePipelineLayout(c->dev, &pl, nullptr, &c->layout), "vkCreatePipelineLayout");
        return c;
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        rawdn_destroy(c);
        return nullptr;
    }
}

extern "C" int rawdn_load_shaders(RawdnContext* c, const uint32_t* const code[RAWDN_SHADER_COUNT],
                                  const size_t bytes[RAWDN_SHADER_COUNT], char* errmsg, int errlen) {
    try {
        for (int i = 0; i < RAWDN_SHADER_COUNT; i++) {
            VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            mi.codeSize = bytes[i];
            mi.pCode = code[i];
            ck(vkCreateShaderModule(c->dev, &mi, nullptr, &c->mod[i]), "vkCreateShaderModule");
            VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            pi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            pi.stage.module = c->mod[i];
            pi.stage.pName = "main";
            pi.layout = c->layout;
            ck(vkCreateComputePipelines(c->dev, VK_NULL_HANDLE, 1, &pi, nullptr, &c->pipe[i]), "vkCreateComputePipelines");
            if (i == RAWDN_DCT) {
                for (int32_t k = 1; k <= 2; k++) {
                    const VkSpecializationMapEntry me{0, 0, 4};
                    const VkSpecializationInfo sp{1, &me, 4, &k};
                    pi.stage.pSpecializationInfo = &sp;
                    ck(vkCreateComputePipelines(c->dev, VK_NULL_HANDLE, 1, &pi, nullptr, &c->pipe[RAWDN_SHADER_COUNT + k - 1]), "vkCreateComputePipelines");
                }
            }
        }
        return 0;
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        return -1;
    }
}

namespace {

struct Strip {
    const uint16_t* in;        // whole frame
    size_t stride;             // samples per input row
    int fw, fh;                // frame size
    int y0;                    // first frame row of the strip
    int keep0, keep1;          // frame rows this strip delivers
    uint16_t *out, *luma;      // whole output frames (tight fw x fh); null: leave the result mapped in sites
};

// Mirror that keeps the Bayer phase (2n - 2 - i has the parity of i).
int mirror(int i, int n) { return i < n ? i : std::max(2 * n - 2 - i, i & 1); }

// One strip of w x h (multiples of 4) samples; the frame is mirrored beyond its right and bottom edges.
void runStrip(RawdnContext* c, const Strip& S, int w, int h, const RawdnParams* prm) {
    VkCommandBuffer cb{};
    try {
        c->prepare(w, h, prm->levels > 0 ? prm->levels : 3);
        auto tc = std::chrono::steady_clock::now();
        void* map = nullptr;
        ck(vkMapMemory(c->dev, c->io.m, 0, VK_WHOLE_SIZE, 0, &map), "vkMapMemory");
        for (int y = 0; y < h; y++) {
            const uint16_t* src = S.in + size_t(mirror(S.y0 + y, S.fh)) * S.stride;
            uint16_t* dst = static_cast<uint16_t*>(map) + size_t(y) * size_t(w);
            std::memcpy(dst, src, size_t(S.fw) * 2);
            for (int x = S.fw; x < w; x++) dst[x] = src[mirror(x, S.fw)];
        }

        c->copyInMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tc).count();
        // Site colours for the pattern, then sqrt(wb) gains and role variances.
        static const int kColour[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        const int cfa = prm->cfa & 3;
        const bool diag = cfa == 1 || cfa == 2;
        float meanBlack = 0.25f * (prm->black[0] + prm->black[1] + prm->black[2] + prm->black[3]);
        PackPc pk{};
        pk.qw = c->lv[0].qw;
        pk.qh = c->lv[0].qh;
        pk.cw = w / 2;
        pk.ch = h / 2;
        pk.diagGreen = diag ? 1 : 0;
        pk.a = prm->noiseA;
        pk.b = prm->noiseB;
        pk.scale = 1.f / (prm->white - meanBlack);
        pk.grain = std::clamp(prm->grain, 0.f, 1.f);
        pk.keptColour = (1.f - std::clamp(prm->chromaStrength, 0.f, 1.f)) * (1.f - std::clamp(prm->chromaStrength, 0.f, 1.f));
        pk.white = prm->white;
        pk.outWhite = 65535.f;
        pk.coarseCut = std::clamp(prm->coarseCut, 0.f, 1.f);
        for (int i = 0; i < 4; i++) {
            pk.black[i] = prm->black[i];
            pk.gain[i] = std::sqrt(std::max(prm->wb[kColour[cfa][i]], 1e-3f) / std::max(prm->wb[1], 1e-3f));
        }
        // Stabilised full scale with headroom for ringing (the opponent planes never exceed the site values).
        pk.range = 1.2f * std::max({pk.gain[0], pk.gain[1], pk.gain[2], pk.gain[3]}) * (2.f / pk.a) *
                   std::sqrt(pk.a * 1.1f + 0.375f * pk.a * pk.a + pk.b);
        const float sr2 = pk.gain[diag ? 1 : 0] * pk.gain[diag ? 1 : 0], sb2 = pk.gain[diag ? 2 : 3] * pk.gain[diag ? 2 : 3];
        const float var1[4] = {(sr2 + 2.f + sb2) / 16.f, (sr2 + sb2) / 4.f, (sr2 + sb2 + 2.f) / 16.f, 0.5f};

        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool = c->pool;
        ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ca.commandBufferCount = 1;
        ck(vkAllocateCommandBuffers(c->dev, &ca, &cb), "vkAllocateCommandBuffers");
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        ck(vkBeginCommandBuffer(cb, &bi), "vkBeginCommandBuffer");
        auto run = [&](int pipe, VkDescriptorSet set, const void* pc, uint32_t pcBytes, int nx, int ny, int nz = 1, int ls = 8) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, c->pipe[pipe]);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, c->layout, 0, 1, &set, 0, nullptr);
            vkCmdPushConstants(cb, c->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pcBytes, pc);
            vkCmdDispatch(cb, uint32_t((nx + ls - 1) / ls), uint32_t((ny + ls - 1) / ls), uint32_t(nz));
            barrier(cb);
        };
        auto stamp = [&] {
            if (c->qp && c->nStamp < 8) vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, c->qp, uint32_t(c->nStamp++));
        };
        c->nStamp = 0;
        if (c->qp) vkCmdResetQueryPool(cb, c->qp, 0, 8);
        stamp();
        run(RAWDN_PACK, c->packSet, &pk, sizeof(pk), pk.qw, pk.qh);
        const int n = c->levels;
        const float chroma = std::clamp(prm->chromaStrength, 0.f, 1.f);
        for (int l = 0; l + 1 < n; l++) {
            int32_t d[4] = {c->lv[size_t(l)].qw, c->lv[size_t(l)].qh, c->lv[size_t(l) + 1].qw, c->lv[size_t(l) + 1].qh};
            run(RAWDN_DOWN, c->lv[size_t(l)].down, d, sizeof(d), d[2], d[3]);
        }
        stamp();
        for (int l = n - 1; l >= 0; l--) {
            Level& L = c->lv[size_t(l)];
            const float lscale = 1.f / float(1 << (2 * l));
            DctPc dp{};
            dp.qw = L.qw;
            dp.qh = L.qh;
            dp.lam2 = prm->lambda * prm->lambda;
            dp.range = pk.range;
            for (int ch = 0; ch < 4; ch++) {
                dp.var[ch] = var1[ch] * lscale;
                // Frozen v1 strengths: luma .8 (fine) .. 1, colour 1 .. 4, green difference .5 .. 4; coarser levels flat.
                dp.hf[ch] = l ? (ch ? prm->chroma : prm->luma) : (ch == 0 ? prm->lumaFine : ch == 3 ? prm->diffFine : prm->chromaFine);
                dp.lf[ch] = ch ? prm->chroma : prm->luma;
            }
            // Colour off: only luma (plane 0) and, at level 0, the green difference (plane 3) are estimated.
            const bool colour = chroma > 0.f;
            const int runs[2][2] = {{0, colour ? L.chans : 1}, {3, !colour && L.chans == 4 ? 1 : 0}};
            for (int st = 0; st < 2; st++) {
                for (int ph = 0; ph < 4; ph++) {
                    dp.phase = ph;
                    const int gw = (L.qw + 2 - 2 * (ph & 1) + 3) / 4, gh = (L.qh + 2 - 2 * (ph >> 1) + 3) / 4;
                    for (const auto& r : runs) {
                        if (!r[1]) continue;
                        dp.plane0 = r[0];
                        if (!st) run(RAWDN_DCT, L.pilot, &dp, sizeof(dp), gw, gh, r[1], 4);
                        else for (int k = 0; k < 2; k++) run(RAWDN_SHADER_COUNT + k, L.wiener, &dp, sizeof(dp), gw, gh, r[1], 4);
                    }
                }
                if (l == 0) stamp();
            }
            if (l + 1 < n) {
                FoldPc fp{L.qw, L.qh, L.qw / 2, L.qh / 2};
                run(RAWDN_RESID, L.resid, &fp, sizeof(fp), fp.cqw, fp.cqh, colour ? 3 : 1);
                run(RAWDN_FIN, L.fin, &fp, sizeof(fp), fp.cqw, fp.cqh, colour ? 3 : 1);
            }
            if (l == 1) stamp();
        }
        stamp();
        if (chroma < 1.f) {
            const struct { int32_t qw, qh; float s; } mp{pk.qw, pk.qh, chroma};
            run(RAWDN_MIX, c->mixSet, &mp, sizeof(mp), pk.qw, pk.qh, 2);
        }
        run(RAWDN_SITES, c->sitesSet, &pk, sizeof(pk), pk.qw, pk.qh);
        if (pk.coarseCut > 0.f) run(RAWDN_COARSE, c->unpackSet, &pk, sizeof(pk), pk.qw, pk.qh);
        run(RAWDN_UNPACK, c->unpackSet, &pk, sizeof(pk), pk.qw, pk.qh);
        stamp();
        {   // GPU writes -> host reads of the mapped output
            VkMemoryBarrier hb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            hb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            hb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &hb, 0, nullptr, 0, nullptr);
        }
        ck(vkEndCommandBuffer(cb), "vkEndCommandBuffer");
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cb;
        auto t0 = std::chrono::steady_clock::now();
        ck(vkQueueSubmit(c->queue, 1, &si, VK_NULL_HANDLE), "vkQueueSubmit");
        ck(vkQueueWaitIdle(c->queue), "vkQueueWaitIdle");
        c->gpuMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        if (c->qp) {
            uint64_t t[8]{};
            if (vkGetQueryPoolResults(c->dev, c->qp, 0, uint32_t(c->nStamp), sizeof(t), t, 8, VK_QUERY_RESULT_64_BIT) == VK_SUCCESS)
                for (int i = 1; i < c->nStamp; i++) c->stage[i - 1] += double(t[i] - t[i - 1]) * c->stampNs * 1e-6;
        }
        vkUnmapMemory(c->dev, c->io.m);
        tc = std::chrono::steady_clock::now();
        ck(vkMapMemory(c->dev, c->sites.m, 0, VK_WHOLE_SIZE, 0, &map), "vkMapMemory");
        if (c->sites.incoherent) {
            const VkMappedMemoryRange mr{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, nullptr, c->sites.m, 0, VK_WHOLE_SIZE};
            ck(vkInvalidateMappedMemoryRanges(c->dev, 1, &mr), "vkInvalidateMappedMemoryRanges");
        }
        if (!S.out) {
            c->sitesMap = map;
        } else {
            const uint16_t *o = static_cast<const uint16_t*>(map), *l = o + size_t(w) * size_t(h);
            for (int y = S.keep0; y < S.keep1; y++) {
                const size_t si = size_t(y - S.y0) * size_t(w), di = size_t(y) * size_t(S.fw);
                std::memcpy(S.out + di, o + si, size_t(S.fw) * 2);
                if (S.luma) std::memcpy(S.luma + di, l + si, size_t(S.fw) * 2);
            }
            vkUnmapMemory(c->dev, c->sites.m);
        }
        c->copyOutMs += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tc).count();
        vkFreeCommandBuffers(c->dev, c->pool, 1, &cb);
    } catch (...) {
        vkDeviceWaitIdle(c->dev);
        if (cb) vkFreeCommandBuffers(c->dev, c->pool, 1, &cb);
        c->freeFrame();
        throw;
    }
}

}  // namespace

struct RawdnOutput {
    RawdnContext* c;
    Buf buf;
    const uint16_t *out, *luma;
};

namespace {

void checkArgs(RawdnContext* c, size_t inStride, int w, int h, const RawdnParams* prm) {
    if (w < 64 || h < 64 || (w & 1) || (h & 1) || inStride < size_t(w)) throw std::runtime_error("unsupported frame size");
    if (!(prm->noiseA > 0.f) || prm->noiseB < 0.f || !(prm->white > 0.f)) throw std::runtime_error("invalid noise model");
    for (int i = 0; i < RAWDN_SHADER_COUNT; i++)
        if (!c->pipe[i]) throw std::runtime_error("shaders not loaded");
}

void destroyNow(RawdnContext* c);

}  // namespace

extern "C" int rawdn_process_mapped(RawdnContext* c, const uint16_t* in, size_t inStride, int w, int h, const RawdnParams* prm,
                                    RawdnOutput** res, const uint16_t** out, const uint16_t** luma, size_t* stride,
                                    char* errmsg, int errlen) {
    *res = nullptr;
    try {
        checkArgs(c, inStride, w, h, prm);
        const int w4 = (w + 3) & ~3, h4 = (h + 3) & ~3;
        if (size_t(w4) * size_t(h4) > kStripPixels) return 1;
        c->gpuMs = c->copyInMs = c->copyOutMs = 0;
        std::fill(std::begin(c->stage), std::end(c->stage), 0.0);
        c->sitesMap = nullptr;
        Strip s{in, inStride, w, h, 0, 0, h, nullptr, nullptr};
        runStrip(c, s, w4, h4, prm);
        const auto* o = static_cast<const uint16_t*>(c->sitesMap);
        auto* r = new RawdnOutput{c, c->sites, o, o + size_t(w4) * size_t(h4)};
        c->sites = {};  // owned by the output now: the next frame allocates its own
        {
            std::lock_guard<std::mutex> lock(c->exportMu);
            c->exported++;
        }
        *res = r;
        *out = r->out;
        *luma = r->luma;
        *stride = size_t(w4);
        c->sitesMap = nullptr;
        return 0;
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        return -1;
    }
}

extern "C" const uint16_t* rawdn_output_plane(const RawdnOutput* r, int which) { return r ? (which ? r->luma : r->out) : nullptr; }

extern "C" void rawdn_release_output(RawdnOutput* r) {
    if (!r) return;
    RawdnContext* c = r->c;
    bool last;
    {
        std::lock_guard<std::mutex> lock(c->exportMu);
        vkUnmapMemory(c->dev, r->buf.m);
        c->delBuf(r->buf);
        last = --c->exported == 0 && c->dying;
    }
    delete r;
    if (last) destroyNow(c);
}

extern "C" int rawdn_process(RawdnContext* c, const uint16_t* in, size_t inStride, uint16_t* out, uint16_t* luma, int w, int h,
                             const RawdnParams* prm, char* errmsg, int errlen) {
    try {
        checkArgs(c, inStride, w, h, prm);
        // Large frames run as full-width strips so GPU memory stays near that of 13 MP (about 15 bytes per pixel: snorm16 planes).
        // Strip starts are multiples of 32 rows: every pyramid level keeps the whole-frame block grid.
        const int w4 = (w + 3) & ~3, h4 = (h + 3) & ~3, ov = 96;
        int n = 1, step = h, sh = h4;
        if (size_t(w4) * size_t(h4) > kStripPixels) {
            n = int((size_t(w4) * size_t(h4) + kStripPixels - 1) / kStripPixels);
            step = (h / n + 31) / 32 * 32;
            sh = step + 2 * ov;
        }
        c->gpuMs = c->copyInMs = c->copyOutMs = 0;
        std::fill(std::begin(c->stage), std::end(c->stage), 0.0);
        for (int y = 0; y < h; y += step) {
            Strip s{in, inStride, w, h, std::max(0, y - ov), y, std::min(h, y + step), out, luma};
            runStrip(c, s, w4, sh, prm);
        }
        return 0;
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        return -1;
    }
}

extern "C" void rawdn_trim(RawdnContext* c) { if (c) c->freeFrame(); }

extern "C" float rawdn_midtone_snr(const uint16_t* in, size_t stride, int w, int h, const float black[4], float white, float a, float b) {
    const float scale = 1.f / (white - 0.25f * (black[0] + black[1] + black[2] + black[3]));
    double sum = 0;
    size_t n = 0;
    for (int y = 0; y < h; y += 5)
        for (int x = 0; x < w; x += 5, n++) {
            const float v = (float(in[size_t(y) * stride + size_t(x)]) - black[(y & 1) * 2 + (x & 1)]) * scale;
            sum += std::log(std::max(v, 1e-4f));
        }
    const float mid = float(std::exp(sum / double(std::max<size_t>(n, 1))));
    return mid / std::sqrt(a * mid + b);
}

extern "C" const double* rawdn_stage_ms(RawdnContext* c, int* n) { *n = std::max(0, c->nStamp - 1); return c->stage; }
extern "C" double rawdn_last_gpu_ms(RawdnContext* c) { return c ? c->gpuMs : -1; }
extern "C" double rawdn_last_copy_ms(RawdnContext* c, int out) { return c ? (out ? c->copyOutMs : c->copyInMs) : -1; }

extern "C" void rawdn_destroy(RawdnContext* c) {
    if (!c) return;
    {
        std::lock_guard<std::mutex> lock(c->exportMu);
        if (c->exported > 0) {
            // Outputs still in use: free the frame now, the device when the last one is released.
            c->freeFrame();
            c->dying = true;
            return;
        }
    }
    destroyNow(c);
}

namespace {

void destroyNow(RawdnContext* c) {
    if (c->dev) {
        c->freeFrame();
        for (int i = 0; i < RAWDN_SHADER_COUNT + 2; i++) {
            if (c->pipe[i]) vkDestroyPipeline(c->dev, c->pipe[i], nullptr);
            if (i < RAWDN_SHADER_COUNT && c->mod[i]) vkDestroyShaderModule(c->dev, c->mod[i], nullptr);
        }
        if (c->layout) vkDestroyPipelineLayout(c->dev, c->layout, nullptr);
        if (c->dsl) vkDestroyDescriptorSetLayout(c->dev, c->dsl, nullptr);
        if (c->qp) vkDestroyQueryPool(c->dev, c->qp, nullptr);
        if (c->pool) vkDestroyCommandPool(c->dev, c->pool, nullptr);
        vkDestroyDevice(c->dev, nullptr);
    }
    if (c->inst) vkDestroyInstance(c->inst, nullptr);
    delete c;
}

}  // namespace

extern "C" int rawdn_estimate_noise(const uint16_t* in, size_t stride, int w, int h, int cfa, const float black[4], float white,
                                    float* a, float* b) {
    // Green pair of each 2x2 cell: difference cancels the signal, so its
    // variance over a 16x16 px block is twice the noise variance at the block mean.
    const bool diag = (cfa & 3) == 1 || (cfa & 3) == 2;
    const int o1 = diag ? 0 : 1, o2 = diag ? int(stride) + 1 : int(stride);
    const float bk1 = black[diag ? 0 : 1], bk2 = black[diag ? 3 : 2];
    const float scale = 1.f / (white - 0.25f * (black[0] + black[1] + black[2] + black[3]));
    const int B = 8, by = h / 2 / B, bx = w / 2 / B;
    struct S {
        float mean, var, spread;
    };
    std::vector<S> s;
    s.reserve(size_t(by) * size_t(bx) / 2);
    for (int y = 0; y < by; y++)
        for (int x = (y & 1); x < bx; x += 2) {   // checkerboard subsample: half the blocks
            double sm = 0, sm2 = 0, sd = 0, sd2 = 0;
            for (int j = 0; j < B; j++) {
                const uint16_t* row = in + size_t(2 * (y * B + j)) * stride + size_t(2 * x * B);
                for (int i = 0; i < B; i++) {
                    float g1 = (float(row[2 * i + o1]) - bk1) * scale, g2 = (float(row[2 * i + o2]) - bk2) * scale;
                    float m = 0.5f * (g1 + g2), d = g1 - g2;
                    sm += m; sm2 += double(m) * m; sd += d; sd2 += double(d) * d;
                }
            }
            const double nn = B * B;
            float mean = float(sm / nn);
            if (mean <= 0.002f || mean >= 0.7f) continue;
            s.push_back({mean, float((sd2 / nn - (sd / nn) * (sd / nn)) * 0.5), float(std::sqrt(std::max(sm2 / nn - (sm / nn) * (sm / nn), 0.0)))});
        }
    if (s.size() < 24 * 40) return 0;
    std::sort(s.begin(), s.end(), [](const S& p, const S& q) { return p.mean < q.mean; });
    const int bins = 24;
    double sxx = 0, sx = 0, s1 = 0, sxy = 0, sy = 0;
    int used = 0;
    std::vector<float> tmp;
    for (int k = 0; k < bins; k++) {
        size_t lo = s.size() * size_t(k) / bins, hi = s.size() * size_t(k + 1) / bins;
        std::vector<S> bin(s.begin() + long(lo), s.begin() + long(hi));
        size_t keep = std::max<size_t>(bin.size() * 3 / 10, 8);
        if (bin.size() < keep) continue;
        std::nth_element(bin.begin(), bin.begin() + long(keep), bin.end(), [](const S& p, const S& q) { return p.spread < q.spread; });
        tmp.clear();
        double mx = 0;
        for (size_t i = 0; i < keep; i++) {
            tmp.push_back(bin[i].var);
            mx += bin[i].mean;
        }
        std::nth_element(tmp.begin(), tmp.begin() + long(keep / 2), tmp.end());
        double x = mx / double(keep), y = double(tmp[keep / 2]) / 0.98;
        if (!(y > 0)) continue;
        double wgt = 1.0 / (y * y);   // relative error
        sxx += wgt * x * x; sx += wgt * x; s1 += wgt; sxy += wgt * x * y; sy += wgt * y;
        used++;
    }
    if (used < 3) return 0;
    double det = sxx * s1 - sx * sx;
    if (std::fabs(det) < 1e-30) return 0;
    double aa = (sxy * s1 - sx * sy) / det, bb = (sxx * sy - sx * sxy) / det;
    *a = float(std::max(aa, 1e-9));
    *b = float(std::max(bb, 0.0));
    return used;
}
