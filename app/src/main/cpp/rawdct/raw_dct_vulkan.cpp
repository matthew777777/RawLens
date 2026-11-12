// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// RAW Video (.craw) temporal DCT denoiser host (shader: vf_rawdct.comp).
// Own small Vulkan device (independent of the viewfinder context, so VF
// resets never invalidate it). Per slot: a host-coherent input buffer the
// camera thread memcpys the crop rows into, a host-cached output buffer the
// encode worker reads after the slot fence, a stats buffer, a CB + fence.
// The previous submit's output is the recursive "prev" frame.
//
// Threading: submit (camera thread) and wait/output (encode workers) run on
// different threads. Kotlin guarantees a slot is never resubmitted before
// its worker finished and while it is still the prev frame, so per-slot
// state needs no lock; the queue + model are under `mu`.

#include <jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "RawDct"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

struct Params {
    int32_t rOrigin[2];
    int32_t rSize[2];
    int32_t pitch;
    int32_t flags;
    int32_t grQuad;
    int32_t pad0;
    float normBias[4];
    float invRange[4];
    float a, b, kmax, spatial, motion, statClamp, pad1, pad2;
};
static_assert(sizeof(Params) == 96, "rawdct push layout drift");

constexpr int kBins = 16;
constexpr int kStatWords = kBins * 3;

struct Buf {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void* map = nullptr;
    bool coherent = true;
};

struct Slot {
    Buf in, out, stats, mv;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkDescriptorSet set = VK_NULL_HANDLE;
    bool armed = false;
    bool hasStats = false;
};

struct Engine {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkPhysicalDeviceMemoryProperties memProps{};
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkPipeline alignPipeline = VK_NULL_HANDLE;  // vf_rawdct_align.comp
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    int width = 0, height = 0;  // compact frame (crop rows), stride = width
    size_t bytes = 0;
    std::vector<Slot> slots;
    int prevSlot = -1;
    // Noise model var = a*mean + b (normalized units), fitted from stats.
    bool modelValid = false;
    double a = 0, b = 0;
    std::mutex mu;
};

uint32_t pickType(const Engine* e, uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < e->memProps.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (e->memProps.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
}

bool makeBuf(Engine* e, VkDeviceSize size, bool preferCached, Buf* out) {
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = size;
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(e->device, &bi, nullptr, &out->buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(e->device, out->buffer, &req);
    uint32_t type = UINT32_MAX;
    if (preferCached) {
        type = pickType(e, req.memoryTypeBits,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
    }
    if (type == UINT32_MAX) {
        type = pickType(e, req.memoryTypeBits,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    }
    if (type == UINT32_MAX) return false;
    out->coherent = (e->memProps.memoryTypes[type].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    if (vkAllocateMemory(e->device, &ai, nullptr, &out->memory) != VK_SUCCESS) return false;
    if (vkBindBufferMemory(e->device, out->buffer, out->memory, 0) != VK_SUCCESS) return false;
    return vkMapMemory(e->device, out->memory, 0, VK_WHOLE_SIZE, 0, &out->map) == VK_SUCCESS;
}

void freeBuf(Engine* e, Buf* b) {
    if (b->map) vkUnmapMemory(e->device, b->memory);
    if (b->buffer) vkDestroyBuffer(e->device, b->buffer, nullptr);
    if (b->memory) vkFreeMemory(e->device, b->memory, nullptr);
    *b = Buf{};
}

void destroy(Engine* e) {
    if (e->device) {
        vkDeviceWaitIdle(e->device);
        for (auto& s : e->slots) {
            freeBuf(e, &s.in);
            freeBuf(e, &s.out);
            freeBuf(e, &s.stats);
            freeBuf(e, &s.mv);
            if (s.fence) vkDestroyFence(e->device, s.fence, nullptr);
        }
        if (e->cmdPool) vkDestroyCommandPool(e->device, e->cmdPool, nullptr);
        if (e->pool) vkDestroyDescriptorPool(e->device, e->pool, nullptr);
        if (e->pipeline) vkDestroyPipeline(e->device, e->pipeline, nullptr);
        if (e->alignPipeline) vkDestroyPipeline(e->device, e->alignPipeline, nullptr);
        if (e->layout) vkDestroyPipelineLayout(e->device, e->layout, nullptr);
        if (e->setLayout) vkDestroyDescriptorSetLayout(e->device, e->setLayout, nullptr);
        vkDestroyDevice(e->device, nullptr);
    }
    if (e->instance) vkDestroyInstance(e->instance, nullptr);
    delete e;
}

bool makePipeline(Engine* e, const std::vector<uint32_t>& code, VkPipeline* out) {
    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize = code.size() * 4;
    mi.pCode = code.data();
    VkShaderModule mod = VK_NULL_HANDLE;
    if (vkCreateShaderModule(e->device, &mi, nullptr, &mod) != VK_SUCCESS) return false;
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    ci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    ci.stage.module = mod;
    ci.stage.pName = "main";
    ci.layout = e->layout;
    VkResult r = vkCreateComputePipelines(e->device, VK_NULL_HANDLE, 1, &ci, nullptr, out);
    vkDestroyShaderModule(e->device, mod, nullptr);
    return r == VK_SUCCESS;
}

uint32_t tilesX(const Engine* e) { return (uint32_t(e->width / 2) + 15) / 16; }
uint32_t tilesY(const Engine* e) { return (uint32_t(e->height / 2) + 7) / 8; }

bool init(Engine* e, const std::vector<uint32_t>& code, const std::vector<uint32_t>& alignCode, int nSlots) {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "rawlens-rawdct";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ii{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ii.pApplicationInfo = &app;
    if (vkCreateInstance(&ii, nullptr, &e->instance) != VK_SUCCESS) return false;
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(e->instance, &n, nullptr);
    if (n == 0) return false;
    std::vector<VkPhysicalDevice> gpus(n);
    vkEnumeratePhysicalDevices(e->instance, &n, gpus.data());
    e->gpu = gpus[0];
    vkGetPhysicalDeviceMemoryProperties(e->gpu, &e->memProps);
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(e->gpu, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(e->gpu, &qn, qf.data());
    e->family = UINT32_MAX;
    for (uint32_t i = 0; i < qn; ++i)
        if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { e->family = i; break; }
    if (e->family == UINT32_MAX) return false;
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qi.queueFamilyIndex = e->family;
    qi.queueCount = 1;
    qi.pQueuePriorities = &prio;
    VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    di.queueCreateInfoCount = 1;
    di.pQueueCreateInfos = &qi;
    if (vkCreateDevice(e->gpu, &di, nullptr, &e->device) != VK_SUCCESS) return false;
    vkGetDeviceQueue(e->device, e->family, 0, &e->queue);

    // 0 cur, 1 prev, 2 out, 3 stats, 4 motion vectors (both shaders share it).
    VkDescriptorSetLayoutBinding binds[5]{};
    for (int i = 0; i < 5; ++i) {
        binds[i].binding = i;
        binds[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        binds[i].descriptorCount = 1;
        binds[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo sl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    sl.bindingCount = 5;
    sl.pBindings = binds;
    if (vkCreateDescriptorSetLayout(e->device, &sl, nullptr, &e->setLayout) != VK_SUCCESS) return false;
    VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(Params)};
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &e->setLayout;
    pl.pushConstantRangeCount = 1;
    pl.pPushConstantRanges = &pr;
    if (vkCreatePipelineLayout(e->device, &pl, nullptr, &e->layout) != VK_SUCCESS) return false;
    if (!makePipeline(e, code, &e->pipeline)) return false;
    if (!makePipeline(e, alignCode, &e->alignPipeline)) return false;

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, uint32_t(5 * nSlots)};
    VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dp.maxSets = nSlots;
    dp.poolSizeCount = 1;
    dp.pPoolSizes = &ps;
    if (vkCreateDescriptorPool(e->device, &dp, nullptr, &e->pool) != VK_SUCCESS) return false;
    VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cp.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cp.queueFamilyIndex = e->family;
    if (vkCreateCommandPool(e->device, &cp, nullptr, &e->cmdPool) != VK_SUCCESS) return false;

    e->slots.resize(nSlots);
    for (auto& s : e->slots) {
        if (!makeBuf(e, e->bytes, false, &s.in)) return false;
        if (!makeBuf(e, e->bytes, true, &s.out)) return false;
        if (!makeBuf(e, kStatWords * 4, false, &s.stats)) return false;
        if (!makeBuf(e, VkDeviceSize(tilesX(e)) * tilesY(e) * 8, false, &s.mv)) return false;
        memset(s.mv.map, 0, size_t(tilesX(e)) * tilesY(e) * 8);
        memset(s.stats.map, 0, kStatWords * 4);
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool = e->cmdPool;
        ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ca.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(e->device, &ca, &s.cmd) != VK_SUCCESS) return false;
        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (vkCreateFence(e->device, &fi, nullptr, &s.fence) != VK_SUCCESS) return false;
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool = e->pool;
        da.descriptorSetCount = 1;
        da.pSetLayouts = &e->setLayout;
        if (vkAllocateDescriptorSets(e->device, &da, &s.set) != VK_SUCCESS) return false;
    }
    return true;
}

// Fold one completed slot's stats into the model (weighted LS on bins, EMA).
void foldStats(Engine* e, Slot& s) {
    if (!s.hasStats) return;
    s.hasStats = false;
    if (!s.stats.coherent) {
        VkMappedMemoryRange rg{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        rg.memory = s.stats.memory;
        rg.size = VK_WHOLE_SIZE;
        vkInvalidateMappedMemoryRanges(e->device, 1, &rg);
    }
    const uint32_t* st = static_cast<const uint32_t*>(s.stats.map);
    double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
    int bins = 0;
    for (int i = 0; i < kBins; ++i) {
        uint32_t cnt = st[i * 3 + 2];
        if (cnt < 24) continue;
        double sum = (double(st[i * 3 + 1]) * 4294967296.0 + double(st[i * 3])) / 1073741824.0;
        // 1.05: mean of the 3x-truncated chi-square (4 dof) is ~0.95 of the true one.
        double y = 1.05 * sum / cnt;
        double c = (i + 0.5) / kBins;
        double x = c * c;
        double w = double(cnt);
        sw += w; sx += w * x; sy += w * y; sxx += w * x * x; sxy += w * x * y;
        ++bins;
    }
    memset(s.stats.map, 0, kStatWords * 4);
    if (bins == 0) return;
    double a, b;
    double det = sw * sxx - sx * sx;
    if (bins >= 2 && det > 1e-12) {
        a = (sw * sxy - sx * sy) / det;
        b = (sy - a * sx) / sw;
    } else {
        a = 0; b = sy / sw;
    }
    if (a < 0) { a = 0; b = sy / sw; }
    if (b < 1e-9) {
        b = 1e-9;
        if (sxx > 0) a = std::max(0.0, (sxy - b * sx) / sxx);
    }
    if (!e->modelValid) { e->a = a; e->b = b; e->modelValid = true; }
    else { e->a += 0.3 * (a - e->a); e->b += 0.3 * (b - e->b); }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_RawDct_nativeCreate(JNIEnv* env, jobject, jbyteArray spv, jbyteArray alignSpv,
                                             jint width, jint height, jint slots) {
    if (!spv || !alignSpv || width < 8 || height < 8 || (width & 1) || (height & 1) || slots < 2 || slots > 8) return 0;
    auto words = [env](jbyteArray a, std::vector<uint32_t>* out) {
        jsize n = env->GetArrayLength(a);
        if (n % 4) return false;
        out->resize(n / 4);
        env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(out->data()));
        return !env->ExceptionCheck();
    };
    std::vector<uint32_t> code, alignCode;
    if (!words(spv, &code) || !words(alignSpv, &alignCode)) return 0;
    auto* e = new Engine();
    e->width = width;
    e->height = height;
    e->bytes = size_t(width) * height * 2;
    if (!init(e, code, alignCode, slots)) {
        LOGW("init failed %dx%d slots=%d", width, height, slots);
        destroy(e);
        return 0;
    }
    LOGI("ready %dx%d slots=%d", width, height, slots);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_matthew_rawlens_RawDct_nativeDestroy(JNIEnv*, jobject, jlong h) {
    if (h) destroy(reinterpret_cast<Engine*>(h));
}

// src: direct buffer of the camera plane; copies rows [rowTop, rowTop+height)
// of `srcStride` bytes into the slot and dispatches. levels[0..3] black and
// white per COMPACT-frame quad (phase of row rowTop). tune: kmax, spatial,
// motion. Returns 0, or negative on error.
JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_RawDct_nativeSubmit(JNIEnv* env, jobject, jlong h, jobject src,
                                             jint srcStride, jint rowTop, jint slot,
                                             jfloatArray levels, jfloat white, jint grQuad,
                                             jfloatArray tune) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (!e || slot < 0 || slot >= int(e->slots.size())) return -1;
    auto* base = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    jlong cap = env->GetDirectBufferCapacity(src);
    const size_t rowBytes = size_t(e->width) * 2;
    if (!base || srcStride < int(rowBytes) || rowTop < 0) return -2;
    if ((int64_t(rowTop) + e->height - 1) * srcStride + int64_t(rowBytes) > cap) return -3;
    float lv[4], tn[3];
    env->GetFloatArrayRegion(levels, 0, 4, lv);
    env->GetFloatArrayRegion(tune, 0, 3, tn);
    if (env->ExceptionCheck()) return -4;

    std::lock_guard<std::mutex> lock(e->mu);
    Slot& s = e->slots[slot];
    if (slot == e->prevSlot) return -5;
    if (s.armed) {
        // Kotlin hands the slot back only after its worker waited; never blocks long.
        if (vkWaitForFences(e->device, 1, &s.fence, VK_TRUE, 100000000ull) != VK_SUCCESS) return -6;
        s.armed = false;
    }
    vkResetFences(e->device, 1, &s.fence);
    foldStats(e, s);

    uint8_t* dst = static_cast<uint8_t*>(s.in.map);
    const uint8_t* from = base + size_t(rowTop) * srcStride;
    if (size_t(srcStride) == rowBytes) memcpy(dst, from, rowBytes * e->height);
    else for (int y = 0; y < e->height; ++y) memcpy(dst + y * rowBytes, from + size_t(y) * srcStride, rowBytes);
    if (!s.in.coherent) {
        VkMappedMemoryRange rg{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        rg.memory = s.in.memory;
        rg.size = VK_WHOLE_SIZE;
        vkFlushMappedMemoryRanges(e->device, 1, &rg);
    }

    const bool hasPrev = e->prevSlot >= 0;
    Slot& prev = hasPrev ? e->slots[e->prevSlot] : s;
    VkDescriptorBufferInfo infos[5] = {
        {s.in.buffer, 0, VK_WHOLE_SIZE},
        {hasPrev ? prev.out.buffer : s.in.buffer, 0, VK_WHOLE_SIZE},
        {s.out.buffer, 0, VK_WHOLE_SIZE},
        {s.stats.buffer, 0, VK_WHOLE_SIZE},
        {s.mv.buffer, 0, VK_WHOLE_SIZE},
    };
    VkWriteDescriptorSet w[5]{};
    for (int i = 0; i < 5; ++i) {
        w[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        w[i].dstSet = s.set;
        w[i].dstBinding = i;
        w[i].descriptorCount = 1;
        w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        w[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(e->device, 5, w, 0, nullptr);

    Params p{};
    p.rOrigin[0] = 0; p.rOrigin[1] = 0;
    p.rSize[0] = e->width; p.rSize[1] = e->height;
    p.pitch = e->width;
    p.flags = (hasPrev ? 9 : 0) | (e->modelValid ? 2 : 0) | 4;
    p.grQuad = grQuad & 3;
    for (int i = 0; i < 4; ++i) {
        float inv = 1.0f / std::max(white - lv[i], 1.0f);
        p.invRange[i] = inv;
        p.normBias[i] = -lv[i] * inv;
    }
    p.a = float(e->a);
    p.b = float(e->b);
    p.kmax = std::min(std::max(tn[0], 0.0f), 0.95f);
    p.spatial = std::max(tn[1], 0.0f);
    p.motion = std::max(tn[2], 0.5f);
    p.statClamp = 0.004f;

    vkResetCommandBuffer(s.cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(s.cmd, &bi);
    // Prior submits' writes (prev frame output) -> this dispatch's reads.
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_HOST_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
    vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, e->layout, 0, 1, &s.set, 0, nullptr);
    vkCmdPushConstants(s.cmd, e->layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(p), &p);
    const uint32_t gx = tilesX(e), gy = tilesY(e);
    if (hasPrev) {
        // Motion search (one workgroup per denoise tile), then the merge reads it.
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, e->alignPipeline);
        vkCmdDispatch(s.cmd, gx, gy, 1);
        VkMemoryBarrier ab{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        ab.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        ab.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 1, &ab, 0, nullptr, 0, nullptr);
    }
    vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, e->pipeline);
    vkCmdDispatch(s.cmd, gx, gy, 1);
    VkMemoryBarrier hb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    hb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    hb.dstAccessMask = VK_ACCESS_HOST_READ_BIT | VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 1, &hb, 0, nullptr, 0, nullptr);
    if (vkEndCommandBuffer(s.cmd) != VK_SUCCESS) return -7;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &s.cmd;
    VkResult r = vkQueueSubmit(e->queue, 1, &si, s.fence);
    if (r != VK_SUCCESS) {
        LOGW("submit failed %d", r);
        return r == VK_ERROR_DEVICE_LOST ? -9 : -8;
    }
    s.armed = true;
    s.hasStats = true;
    e->prevSlot = slot;
    return 0;
}

// Encode worker: wait for the slot, make the output visible to the CPU.
JNIEXPORT jboolean JNICALL
Java_com_matthew_rawlens_RawDct_nativeWait(JNIEnv*, jobject, jlong h, jint slot, jint timeoutMs) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (!e || slot < 0 || slot >= int(e->slots.size())) return JNI_FALSE;
    Slot& s = e->slots[slot];
    if (vkWaitForFences(e->device, 1, &s.fence, VK_TRUE, uint64_t(timeoutMs) * 1000000ull) != VK_SUCCESS)
        return JNI_FALSE;
    if (!s.out.coherent) {
        VkMappedMemoryRange rg{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        rg.memory = s.out.memory;
        rg.size = VK_WHOLE_SIZE;
        vkInvalidateMappedMemoryRanges(e->device, 1, &rg);
    }
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_matthew_rawlens_RawDct_nativeOutput(JNIEnv* env, jobject, jlong h, jint slot) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (!e || slot < 0 || slot >= int(e->slots.size())) return nullptr;
    return env->NewDirectByteBuffer(e->slots[slot].out.map, jlong(e->bytes));
}

// Drop temporal history (e.g. after a dropped frame burst or exposure jump).
JNIEXPORT void JNICALL
Java_com_matthew_rawlens_RawDct_nativeResetHistory(JNIEnv*, jobject, jlong h) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (!e) return;
    std::lock_guard<std::mutex> lock(e->mu);
    e->prevSlot = -1;
}

JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_RawDct_nativeNoise(JNIEnv* env, jobject, jlong h) {
    auto* e = reinterpret_cast<Engine*>(h);
    if (!e) return nullptr;
    float v[3];
    {
        std::lock_guard<std::mutex> lock(e->mu);
        v[0] = float(e->a); v[1] = float(e->b); v[2] = e->modelValid ? 1.f : 0.f;
    }
    jfloatArray out = env->NewFloatArray(3);
    env->SetFloatArrayRegion(out, 0, 3, v);
    return out;
}

}  // extern "C"
