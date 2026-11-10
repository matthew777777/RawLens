// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
#include "rawdn.h"

#include <vulkan/vulkan.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Buf {
    VkBuffer b{};
    VkDeviceMemory m{};
    VkDeviceSize size{};
};

void ck(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(std::string(what) + " failed: VkResult " + std::to_string(int(r)));
}

struct Level {
    int qw{}, qh{}, chans{};
    Buf z, p, num, den;
    VkDescriptorSet dct{}, down{}, resid{}, fin{};
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
    VkPipeline pipe[RAWDN_SHADER_COUNT]{};
    VkShaderModule mod[RAWDN_SHADER_COUNT]{};
    Buf io;
    std::vector<Level> lv;
    VkDescriptorSet packSet{}, unpackSet{};
    int w{}, h{}, levels{};
    double gpuMs{-1};

    uint32_t memType(uint32_t bits, VkMemoryPropertyFlags want) const {
        VkPhysicalDeviceMemoryProperties mp{};
        vkGetPhysicalDeviceMemoryProperties(pd, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
        throw std::runtime_error("no suitable Vulkan memory type");
    }
    Buf mkBuf(VkDeviceSize n, bool host) {
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
        ai.memoryTypeIndex = memType(mr.memoryTypeBits, host ? (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                                                             : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
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
        for (auto& l : lv) {
            delBuf(l.z);
            delBuf(l.p);
            delBuf(l.num);
            delBuf(l.den);
        }
        lv.clear();
        w = h = levels = 0;
    }
    VkDescriptorSet mkSet(const Buf* b0, const Buf* b1, const Buf* b2, const Buf* b3) {
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
            bi[i] = {bs[i]->b, 0, VK_WHOLE_SIZE};
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
            lv.resize(size_t(n));
            for (int l = 0; l < n; l++) {
                Level& L = lv[size_t(l)];
                L.qw = q1w >> l;
                L.qh = q1h >> l;
                L.chans = l == 0 ? 4 : 3;
                VkDeviceSize q = VkDeviceSize(L.qw) * L.qh;
                L.z = mkBuf(q * 16 * L.chans, false);
                L.p = mkBuf(q * 16 * L.chans, false);
                L.num = mkBuf(q * 16, false);
                L.den = mkBuf(q * 4, false);
            }
            VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, uint32_t(4 * (4 * n + 2))};
            VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
            pi.maxSets = uint32_t(4 * n + 2);
            pi.poolSizeCount = 1;
            pi.pPoolSizes = &ps;
            ck(vkCreateDescriptorPool(dev, &pi, nullptr, &dpool), "vkCreateDescriptorPool");
            packSet = mkSet(&io, &lv[0].z, &lv[0].z, &lv[0].z);
            unpackSet = mkSet(&lv[0].p, &lv[0].z, &io, &io);
            for (int l = 0; l < n; l++) {
                Level& L = lv[size_t(l)];
                Level& C = lv[size_t(std::min(l + 1, n - 1))];
                L.dct = mkSet(&L.z, &L.p, &L.num, &L.den);
                L.down = mkSet(&L.z, &C.z, &C.z, &C.z);
                L.resid = mkSet(&C.p, &L.num, &L.den, &C.num);
                L.fin = mkSet(&L.num, &L.den, &C.num, &L.p);
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

struct PackPc {
    int32_t qw, qh, cw, ch, diagGreen, pad[3];
    float a, b, scale, grain;
    float black[4];
    float gain[4];
    float white;
};
struct DctPc {
    int32_t qw, qh, base, mode, py, px, pad[2];
    float thr;
};
struct FoldPc {
    int32_t fqw, fqh, cqw, cqh, base, fold;
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

uint32_t groups(int n) { return uint32_t((n + 7) / 8); }

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
    p->chroma = 3.f;
    p->chromaFine = 1.f;
    p->grain = 0.35f;
    p->levels = 4;
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
        }
        return 0;
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        return -1;
    }
}

extern "C" int rawdn_process(RawdnContext* c, const uint16_t* in, uint16_t* out, int w, int h,
                             const RawdnParams* prm, char* errmsg, int errlen) {
    VkCommandBuffer cb{};
    try {
        if (w < 64 || h < 64 || (w & 1) || (h & 1)) throw std::runtime_error("unsupported frame size");
        if (!(prm->noiseA > 0.f) || prm->noiseB < 0.f || !(prm->white > 0.f)) throw std::runtime_error("invalid noise model");
        for (int i = 0; i < RAWDN_SHADER_COUNT; i++)
            if (!c->pipe[i]) throw std::runtime_error("shaders not loaded");
        c->prepare(w, h, prm->levels > 0 ? prm->levels : 4);
        void* map = nullptr;
        ck(vkMapMemory(c->dev, c->io.m, 0, VK_WHOLE_SIZE, 0, &map), "vkMapMemory");
        std::memcpy(map, in, size_t(w) * size_t(h) * 2);

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
        pk.white = prm->white;
        for (int i = 0; i < 4; i++) {
            pk.black[i] = prm->black[i];
            pk.gain[i] = std::sqrt(std::max(prm->wb[kColour[cfa][i]], 1e-3f) / std::max(prm->wb[1], 1e-3f));
        }
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
        auto run = [&](int pipe, VkDescriptorSet set, const void* pc, uint32_t pcBytes, int nx, int ny) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, c->pipe[pipe]);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, c->layout, 0, 1, &set, 0, nullptr);
            vkCmdPushConstants(cb, c->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pcBytes, pc);
            vkCmdDispatch(cb, groups(nx), groups(ny), 1);
            barrier(cb);
        };
        run(RAWDN_PACK, c->packSet, &pk, sizeof(pk), pk.qw, pk.qh);
        const int n = c->levels;
        for (int l = 0; l + 1 < n; l++) {
            int32_t d[4] = {c->lv[size_t(l)].qw, c->lv[size_t(l)].qh, c->lv[size_t(l) + 1].qw, c->lv[size_t(l) + 1].qh};
            run(RAWDN_DOWN, c->lv[size_t(l)].down, d, sizeof(d), d[2], d[3]);
        }
        const float lam2 = prm->lambda * prm->lambda;
        for (int l = n - 1; l >= 0; l--) {
            Level& L = c->lv[size_t(l)];
            const int q = L.qw * L.qh;
            const float lscale = 1.f / float(1 << (2 * l));
            for (int stage = 0; stage < 2; stage++) {
                for (int ch = 0; ch < L.chans; ch++) {
                    vkCmdFillBuffer(cb, L.num.b, 0, VK_WHOLE_SIZE, 0);
                    vkCmdFillBuffer(cb, L.den.b, 0, VK_WHOLE_SIZE, 0);
                    barrier(cb);
                    const float var = var1[ch] * lscale;
                    const float strength = ch == 0 ? prm->luma : (l == 0 ? prm->chromaFine : prm->chroma);
                    DctPc dp{};
                    dp.qw = L.qw;
                    dp.qh = L.qh;
                    dp.base = ch * q;
                    dp.mode = stage;
                    dp.thr = stage == 0 ? lam2 * var : strength * var;
                    for (int py = 0; py < 4; py++)
                        for (int px = 0; px < 4; px++) {
                            dp.py = py;
                            dp.px = px;
                            run(RAWDN_DCT, L.dct, &dp, sizeof(dp), (L.qw + 3 - px + 3) / 4, (L.qh + 3 - py + 3) / 4);
                        }
                    const bool fold = l + 1 < n && ch < 3;
                    FoldPc fp{L.qw, L.qh, L.qw / 2, L.qh / 2, 0, fold ? 1 : 0};
                    if (fold) {
                        fp.base = ch * (q / 4);
                        run(RAWDN_RESID, L.resid, &fp, sizeof(fp), fp.cqw, fp.cqh);
                    }
                    fp.base = ch * q;
                    run(RAWDN_FIN, L.fin, &fp, sizeof(fp), fp.cqw, fp.cqh);
                }
            }
        }
        run(RAWDN_UNPACK, c->unpackSet, &pk, sizeof(pk), pk.qw, pk.qh);
        ck(vkEndCommandBuffer(cb), "vkEndCommandBuffer");
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cb;
        auto t0 = std::chrono::steady_clock::now();
        ck(vkQueueSubmit(c->queue, 1, &si, VK_NULL_HANDLE), "vkQueueSubmit");
        ck(vkQueueWaitIdle(c->queue), "vkQueueWaitIdle");
        c->gpuMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        std::memcpy(out, map, size_t(w) * size_t(h) * 2);
        vkUnmapMemory(c->dev, c->io.m);
        vkFreeCommandBuffers(c->dev, c->pool, 1, &cb);
        return 0;
    } catch (const std::exception& e) {
        fail(errmsg, errlen, e.what());
        if (c->dev) {
            vkDeviceWaitIdle(c->dev);
            if (cb) vkFreeCommandBuffers(c->dev, c->pool, 1, &cb);
            c->freeFrame();
        }
        return -1;
    }
}

extern "C" double rawdn_last_gpu_ms(RawdnContext* c) { return c ? c->gpuMs : -1; }

extern "C" void rawdn_destroy(RawdnContext* c) {
    if (!c) return;
    if (c->dev) {
        c->freeFrame();
        for (int i = 0; i < RAWDN_SHADER_COUNT; i++) {
            if (c->pipe[i]) vkDestroyPipeline(c->dev, c->pipe[i], nullptr);
            if (c->mod[i]) vkDestroyShaderModule(c->dev, c->mod[i], nullptr);
        }
        if (c->layout) vkDestroyPipelineLayout(c->dev, c->layout, nullptr);
        if (c->dsl) vkDestroyDescriptorSetLayout(c->dev, c->dsl, nullptr);
        if (c->pool) vkDestroyCommandPool(c->dev, c->pool, nullptr);
        vkDestroyDevice(c->dev, nullptr);
    }
    if (c->inst) vkDestroyInstance(c->inst, nullptr);
    delete c;
}

extern "C" int rawdn_estimate_noise(const uint16_t* in, int w, int h, int cfa, const float black[4], float white,
                                    float* a, float* b) {
    // Green pair of each 2x2 cell: difference cancels the signal, so its
    // variance over a 16x16 px block is twice the noise variance at the block mean.
    const bool diag = (cfa & 3) == 1 || (cfa & 3) == 2;
    const int o1 = diag ? 0 : 1, o2 = diag ? w + 1 : w;
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
                const uint16_t* row = in + size_t(2 * (y * B + j)) * size_t(w) + size_t(2 * x * B);
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
