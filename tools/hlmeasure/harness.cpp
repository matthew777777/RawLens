// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Standalone measurement harness: executes the HlVulkanRecon plan bundle
// (tools/hlmeasure flow: JVM writeMeasureBundle -> plan.json + .bin files)
// through the shipped hlrecon.spv on any local Vulkan compute device and
// writes the device outputs for the ULP diff against the JVM twin.
//
// Usage:
//   harness <bundle-dir> <hlrecon.spv> <out-dir>
// Reads plan.json + in_*.bin, writes out_merged_{0,1,2}.bin (planar R,G,B),
// out_mask.bin, out_guide.bin (half-res planar) and timings to stdout.
// No Android/JNI needed: plain Vulkan 1.1 compute + one full memory
// barrier between dispatches (same queue, GENERAL layout throughout).

#include <vulkan/vulkan.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <string>
#include <vector>

#define VK_CHECK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { \
    fprintf(stderr, "Vulkan error %d at %s:%d\n", r_, __FILE__, __LINE__); exit(1); } } while (0)

// ---- minimal JSON for the known plan schema ----
struct Json {
    enum T { NUL, NUM, STR, ARR, OBJ } t = NUL;
    double num = 0;
    std::string str;
    std::vector<Json> arr;
    std::map<std::string, Json> obj;
    const Json& operator[](const std::string& k) const {
        auto it = obj.find(k);
        if (it == obj.end()) { fprintf(stderr, "missing key %s\n", k.c_str()); exit(1); }
        return it->second;
    }
};
struct P { const char* s; };
static void ws(P& p) { while (*p.s == ' ' || *p.s == '\n' || *p.s == '\r' || *p.s == '\t' || *p.s == ',') p.s++; }
static Json val(P& p);
static Json val(P& p) {
    ws(p);
    Json j;
    if (*p.s == '{') {
        j.t = Json::OBJ; p.s++;
        ws(p);
        while (*p.s != '}') {
            ws(p);
            if (*p.s != '"') { fprintf(stderr, "key expected\n"); exit(1); }
            p.s++;
            std::string k;
            while (*p.s != '"') k += *p.s++;
            p.s++;
            ws(p);
            if (*p.s != ':') { fprintf(stderr, "colon expected\n"); exit(1); }
            p.s++;
            j.obj[k] = val(p);
            ws(p);
        }
        p.s++;
    } else if (*p.s == '[') {
        j.t = Json::ARR; p.s++;
        ws(p);
        while (*p.s != ']') { j.arr.push_back(val(p)); ws(p); }
        p.s++;
    } else if (*p.s == '"') {
        j.t = Json::STR; p.s++;
        while (*p.s != '"') {
            if (*p.s == '\\') p.s++;
            j.str += *p.s++;
        }
        p.s++;
    } else {
        j.t = Json::NUM;
        char* e = nullptr;
        j.num = strtod(p.s, &e);
        p.s = e;
    }
    return j;
}

static std::vector<char> readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { fprintf(stderr, "cannot open %s\n", path.c_str()); exit(1); }
    return std::vector<char>(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
}

struct Image {
    VkImage img = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    int w = 0, h = 0, ch = 0;
    VkFormat fmt = VK_FORMAT_UNDEFINED;
};

static uint32_t memType(VkPhysicalDevice pd, uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    fprintf(stderr, "no memory type\n"); exit(1);
}

int main(int argc, char** argv) {
    // Unbuffered so adb shell shows progress even on a native crash.
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (argc != 4) { fprintf(stderr, "usage: harness <bundle> <spv> <out>\n"); return 1; }
    std::string bundle = argv[1], spvPath = argv[2], outDir = argv[3];

    std::vector<char> planBytes = readFile(bundle + "/plan.json");
    std::string planText(planBytes.begin(), planBytes.end());
    P p{ planText.c_str() };
    Json plan = val(p);

    VkApplicationInfo ai{ VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "hlmeasure", 0, "", 0,
        VK_API_VERSION_1_1 };
    // MoltenVK/macOS needs portability enumeration; Android uses the system
    // driver, where requesting it would fail instance creation.
    std::vector<const char*> exts;
    VkInstanceCreateFlags iciFlags = 0;
#ifndef __ANDROID__
    exts.push_back(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
    iciFlags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
#endif
    VkInstanceCreateInfo ici{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, nullptr, iciFlags, &ai, 0,
        nullptr, (uint32_t)exts.size(), exts.data() };
    VkInstance inst;
    VK_CHECK(vkCreateInstance(&ici, nullptr, &inst));
    uint32_t nd = 0;
    VK_CHECK(vkEnumeratePhysicalDevices(inst, &nd, nullptr));
    if (!nd) { fprintf(stderr, "no devices\n"); return 1; }
    std::vector<VkPhysicalDevice> pds(nd);
    VK_CHECK(vkEnumeratePhysicalDevices(inst, &nd, pds.data()));
    VkPhysicalDevice pd = pds[0];
    printf("physical-devices=%u\n", nd);
    VkPhysicalDeviceProperties pr;
    vkGetPhysicalDeviceProperties(pd, &pr);
    printf("device: %s\n", pr.deviceName);

    for (VkFormat f : { VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT }) {
        VkFormatProperties fp;
        vkGetPhysicalDeviceFormatProperties(pd, f, &fp);
        VkFormatFeatureFlags need = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
            VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT |
            VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
        if ((fp.optimalTilingFeatures & need) != need) {
            fprintf(stderr, "format %d lacks features\n", f);
            return 1;
        }
    }

    uint32_t qf = 0;
    {
        uint32_t nq = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, nullptr);
        std::vector<VkQueueFamilyProperties> qp(nq);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qp.data());
        for (uint32_t i = 0; i < nq; i++) {
            if (qp[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { qf = i; break; }
        }
    }
    float qp0 = 1.0f;
    VkDeviceQueueCreateInfo dq{ VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, nullptr, 0, qf, 1, &qp0 };
    VkDeviceCreateInfo dc{ VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, nullptr, 0, 1, &dq,
        0, nullptr, 0, nullptr, nullptr };
    VkDevice dev;
    VK_CHECK(vkCreateDevice(pd, &dc, nullptr, &dev));
    VkQueue queue;
    vkGetDeviceQueue(dev, qf, 0, &queue);

    // Images.
    std::map<std::string, Image> images;
    auto fmtFor = [](int ch) { return ch == 4 ? VK_FORMAT_R32G32B32A32_SFLOAT : VK_FORMAT_R32_SFLOAT; };
    for (const auto& kv : plan["images"].obj) {
        const std::string& key = kv.first;
        int w = (int)kv.second.arr[0].num, h = (int)kv.second.arr[1].num, ch = (int)kv.second.arr[2].num;
        Image im;
        im.w = w; im.h = h; im.ch = ch; im.fmt = fmtFor(ch);
        VkImageCreateInfo ii{ VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, nullptr, 0,
            VK_IMAGE_TYPE_2D, im.fmt, { (uint32_t)w, (uint32_t)h, 1 }, 1, 1,
            VK_SAMPLE_COUNT_1_BIT, VK_IMAGE_TILING_OPTIMAL,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            VK_SHARING_MODE_EXCLUSIVE, 0, nullptr, VK_IMAGE_LAYOUT_UNDEFINED };
        VK_CHECK(vkCreateImage(dev, &ii, nullptr, &im.img));
        VkMemoryRequirements mr;
        vkGetImageMemoryRequirements(dev, im.img, &mr);
        VkMemoryAllocateInfo ma{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size,
            memType(pd, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) };
        VK_CHECK(vkAllocateMemory(dev, &ma, nullptr, &im.mem));
        VK_CHECK(vkBindImageMemory(dev, im.img, im.mem, 0));
        VkImageViewCreateInfo vi{ VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, nullptr, 0, im.img,
            VK_IMAGE_VIEW_TYPE_2D, im.fmt, {}, { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        VK_CHECK(vkCreateImageView(dev, &vi, nullptr, &im.view));
        images[key] = im;
    }

    // Staging buffer (big enough for the largest image).
    size_t stageSize = 0;
    for (const auto& kv : images) {
        stageSize = std::max(stageSize, (size_t)kv.second.w * kv.second.h * kv.second.ch * 4);
    }
    VkBuffer stage;
    VkDeviceMemory stageMem;
    {
        VkBufferCreateInfo bi{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, stageSize,
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_SHARING_MODE_EXCLUSIVE, 0, nullptr };
        VK_CHECK(vkCreateBuffer(dev, &bi, nullptr, &stage));
        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(dev, stage, &mr);
        VkMemoryAllocateInfo ma{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size,
            memType(pd, mr.memoryTypeBits,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) };
        VK_CHECK(vkAllocateMemory(dev, &ma, nullptr, &stageMem));
        VK_CHECK(vkBindBufferMemory(dev, stage, stageMem, 0));
    }
    void* stagePtr = nullptr;
    VK_CHECK(vkMapMemory(dev, stageMem, 0, stageSize, 0, &stagePtr));

    // Uploads (every table image has a staged .bin, zeros for scratch).
    const bool noUpload = getenv("HLT_NO_UPLOAD") != nullptr;
    for (auto& kv : images) {
        if (noUpload) continue;
        std::string path = bundle + "/in_" + kv.first + ".bin";
        std::ifstream f(path, std::ios::binary);
        size_t bytes = (size_t)kv.second.w * kv.second.h * kv.second.ch * 4;
        if (f) {
            std::vector<char> data((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
            if (data.size() != bytes) {
                fprintf(stderr, "size mismatch %s: %zu vs %zu\n", path.c_str(), data.size(), bytes);
                return 1;
            }
            // Transition UNDEFINED -> GENERAL with the upload barrier, then copy.
            VkCommandPool pool;
            VkCommandPoolCreateInfo pci{ VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr,
                VK_COMMAND_POOL_CREATE_TRANSIENT_BIT, qf };
            VK_CHECK(vkCreateCommandPool(dev, &pci, nullptr, &pool));
            VkCommandBuffer cb;
            VkCommandBufferAllocateInfo ai2{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                nullptr, pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1 };
            VK_CHECK(vkAllocateCommandBuffers(dev, &ai2, &cb));
            VkCommandBufferBeginInfo bi{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr,
                VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr };
            VK_CHECK(vkBeginCommandBuffer(cb, &bi));
            VkImageMemoryBarrier bar{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER, nullptr, 0,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                qf, qf, kv.second.img,
                { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &bar);
            memcpy(stagePtr, data.data(), bytes);
            VkBufferImageCopy cp{};
            cp.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
            cp.imageExtent = { (uint32_t)kv.second.w, (uint32_t)kv.second.h, 1 };
            vkCmdCopyBufferToImage(cb, stage, kv.second.img, VK_IMAGE_LAYOUT_GENERAL, 1, &cp);
            VK_CHECK(vkEndCommandBuffer(cb));
            VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &cb,
                0, nullptr };
            VkFence fence;
            VkFenceCreateInfo fi{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0 };
            VK_CHECK(vkCreateFence(dev, &fi, nullptr, &fence));
            VK_CHECK(vkQueueSubmit(queue, 1, &si, fence));
            VK_CHECK(vkWaitForFences(dev, 1, &fence, VK_TRUE, 10000000000ull));
            vkDestroyFence(dev, fence, nullptr);
            vkFreeCommandBuffers(dev, pool, 1, &cb);
            vkDestroyCommandPool(dev, pool, nullptr);
        } else {
            fprintf(stderr, "missing input %s\n", path.c_str());
            return 1;
        }
    }

    // Pipeline.
    std::vector<char> spv = readFile(spvPath);
    VkShaderModuleCreateInfo mi{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, nullptr, 0, spv.size(),
        reinterpret_cast<const uint32_t*>(spv.data()) };
    VkShaderModule mod;
    VK_CHECK(vkCreateShaderModule(dev, &mi, nullptr, &mod));
    std::vector<VkDescriptorSetLayoutBinding> bindings;
    {
        VkDescriptorSetLayoutBinding b{};
        b.binding = 0;
        b.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        b.descriptorCount = 1;
        b.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        bindings.push_back(b);
        for (int i = 1; i <= 17; i++) {
            VkDescriptorSetLayoutBinding s{};
            s.binding = (uint32_t)i;
            s.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            s.descriptorCount = 1;
            s.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            bindings.push_back(s);
        }
        VkDescriptorSetLayoutBinding u{};
        u.binding = 18;
        u.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        u.descriptorCount = 1;
        u.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        bindings.push_back(u);
    }
    VkDescriptorSetLayout layout;
    VkDescriptorSetLayoutCreateInfo li{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, nullptr,
        0, (uint32_t)bindings.size(), bindings.data() };
    VK_CHECK(vkCreateDescriptorSetLayout(dev, &li, nullptr, &layout));
    VkPipelineLayout pipeLayout;
    VkPipelineLayoutCreateInfo pli{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, nullptr, 0, 1,
        &layout, 0, nullptr };
    VK_CHECK(vkCreatePipelineLayout(dev, &pli, nullptr, &pipeLayout));
    VkPipeline pipeline;
    VkComputePipelineCreateInfo pi{ VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO, nullptr, 0,
        { VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0,
            VK_SHADER_STAGE_COMPUTE_BIT, mod, "main", nullptr },
        pipeLayout, VK_NULL_HANDLE, 0 };
    VK_CHECK(vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &pi, nullptr, &pipeline));

    // Descriptor pool + set.
    VkDescriptorPool pool;
    {
        VkDescriptorPoolSize sizes[3] = { { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1 },
            { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 17 }, { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1 } };
        VkDescriptorPoolCreateInfo pci{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, nullptr, 0,
            1, 3, sizes };
        VK_CHECK(vkCreateDescriptorPool(dev, &pci, nullptr, &pool));
    }
    VkDescriptorSet set;
    VkDescriptorSetAllocateInfo ai3{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, nullptr, pool,
        1, &layout };
    VK_CHECK(vkAllocateDescriptorSets(dev, &ai3, &set));

    VkSampler sampler;
    {
        VkSamplerCreateInfo sci{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO, nullptr, 0, VK_FILTER_NEAREST,
            VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, 0.0f,
            VK_FALSE, 0.0f, VK_FALSE, VK_COMPARE_OP_NEVER, 0.0f, 1.0f,
            VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK, VK_FALSE };
        VK_CHECK(vkCreateSampler(dev, &sci, nullptr, &sampler));
    }
    // UBO 112 bytes host-visible.
    VkBuffer ubo;
    VkDeviceMemory uboMem;
    void* uboPtr = nullptr;
    {
        VkBufferCreateInfo bi{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, nullptr, 0, 112,
            VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, VK_SHARING_MODE_EXCLUSIVE, 0, nullptr };
        VK_CHECK(vkCreateBuffer(dev, &bi, nullptr, &ubo));
        VkMemoryRequirements mr;
        vkGetBufferMemoryRequirements(dev, ubo, &mr);
        VkMemoryAllocateInfo ma{ VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size,
            memType(pd, mr.memoryTypeBits,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) };
        VK_CHECK(vkAllocateMemory(dev, &ma, nullptr, &uboMem));
        VK_CHECK(vkBindBufferMemory(dev, ubo, uboMem, 0));
        VK_CHECK(vkMapMemory(dev, uboMem, 0, 112, 0, &uboPtr));
    }
    std::vector<VkWriteDescriptorSet> writes;
    std::vector<VkDescriptorImageInfo> imgInfos;
    imgInfos.reserve(32);
    {
        VkDescriptorImageInfo gi{};
        gi.sampler = sampler;
        gi.imageView = images["gamma"].view;
        gi.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        imgInfos.push_back(gi);
        VkWriteDescriptorSet w0{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, set, 0, 0, 1,
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, &imgInfos.back(), nullptr, nullptr };
        writes.push_back(w0);
    }
    // Storage slots 1..17 bound per-op below (images change between ops).
    // Pre-create the write structs; patch imageView each op.
    for (int i = 1; i <= 17; i++) {
        VkDescriptorImageInfo ii{};
        ii.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        imgInfos.push_back(ii);
        VkWriteDescriptorSet w{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, set,
            (uint32_t)i, 0, 1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, &imgInfos.back(), nullptr,
            nullptr };
        writes.push_back(w);
    }
    {
        VkDescriptorBufferInfo bi{ ubo, 0, 112 };
        static VkDescriptorBufferInfo held;
        held = bi;
        VkWriteDescriptorSet w{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, nullptr, set, 18, 0, 1,
            VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, nullptr, &held, nullptr };
        writes.push_back(w);
    }

    auto setStorage = [&](int vulkanBinding, const std::string& key) {
        for (auto& wr : writes) {
            if (wr.dstBinding == (uint32_t)vulkanBinding) {
                VkDescriptorImageInfo* ii = const_cast<VkDescriptorImageInfo*>(
                    wr.pImageInfo);
                ii->imageView = images[key].view;
                ii->sampler = VK_NULL_HANDLE;
                ii->imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            }
        }
    };

    // Record all dispatches into one command buffer with full barriers.
    VkCommandPool cpool;
    {
        // RESET_COMMAND_BUFFER_BIT: one buffer is re-recorded per op; some
        // drivers (Mali, unlike MoltenVK) fault on reset without it.
        VkCommandPoolCreateInfo pci{ VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr,
            VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, qf };
        VK_CHECK(vkCreateCommandPool(dev, &pci, nullptr, &cpool));
    }
    VkCommandBuffer cb;
    {
        VkCommandBufferAllocateInfo ai2{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr,
            cpool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1 };
        VK_CHECK(vkAllocateCommandBuffers(dev, &ai2, &cb));
    }

    float maxF[3] = { (float)plan["maxF"].arr[0].num, (float)plan["maxF"].arr[1].num,
        (float)plan["maxF"].arr[2].num };
    float medF[3] = { (float)plan["medF"].arr[0].num, (float)plan["medF"].arr[1].num,
        (float)plan["medF"].arr[2].num };
    float xyz[3] = { (float)plan["xyzRow"].arr[0].num, (float)plan["xyzRow"].arr[1].num,
        (float)plan["xyzRow"].arr[2].num };
    int hfw = (int)plan["hfw"].num, hfh = (int)plan["hfh"].num;
    float whitePt = (float)plan["whitePt"].num, clipPt = (float)plan["clipPt"].num,
          blendPt = (float)plan["blendPt"].num;

    // NOTE: no descriptor update here — storage views are patched per-op
    // below, and updating with the still-NULL views faults some drivers
    // (Mali reads view+0xa8; MoltenVK tolerates NULL). Each op updates the
    // full set after patching, before its bind.

    // One submit per op: the UBO is rewritten on the host between ops,
    // so sharing a single command buffer would expose every dispatch to
    // the LAST op's uniforms. Per-op submit+fence (like srvk's synchronous
    // dispatch) keeps each dispatch's uniforms exact.
    int opIndex = 0;
    VkFence fence;
    {
        VkFenceCreateInfo fi{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0 };
        VK_CHECK(vkCreateFence(dev, &fi, nullptr, &fence));
    }
    // GPU timestamps around the whole op sequence (same queue clock).
    // NOTE: an earlier revision passed queryCount=0 here (six initializers
    // for five members); MoltenVK tolerated it, Mali faulted. Fixed.
    VkQueryPool queryPool = VK_NULL_HANDLE;
    double timestampPeriod = 1.0;
    {
        VkQueueFamilyProperties qfp;
        uint32_t nq = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, nullptr);
        std::vector<VkQueueFamilyProperties> qps(nq);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qps.data());
        qfp = qps[qf];
        if (qfp.timestampValidBits > 0) {
            VkQueryPoolCreateInfo qi{ VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO, nullptr, 0,
                VK_QUERY_TYPE_TIMESTAMP, 2, 0 };
            VK_CHECK(vkCreateQueryPool(dev, &qi, nullptr, &queryPool));
            timestampPeriod = pr.limits.timestampPeriod;
        }
    }
    auto hostT0 = std::chrono::steady_clock::now();
    for (const Json& op : plan["ops"].arr) {
        VK_CHECK(vkResetCommandBuffer(cb, 0));
        VkCommandBufferBeginInfo bbi{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr,
            VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr };
        VK_CHECK(vkBeginCommandBuffer(cb, &bbi));
        if (opIndex > 0) {
            // Cross-submit visibility: prior submits' shader writes must be
            // available/visible to this dispatch (queue-wide backward scope).
            VkMemoryBarrier mb{ VK_STRUCTURE_TYPE_MEMORY_BARRIER, nullptr,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT };
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0,
                                 nullptr);
        }
        if (queryPool != VK_NULL_HANDLE && opIndex == 0) {
            vkCmdResetQueryPool(cb, queryPool, 0, 2);
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, 0);
        }
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        int mode = (int)op["mode"].num, w = (int)op["w"].num, h = (int)op["h"].num;
        int radius = (int)op["radius"].num, combineOp = (int)op["combineOp"].num;
        float eps = (float)op["eps"].num;
        // Patch storage bindings for this op (ESSL slot s -> vulkan s+1).
        for (int s = 0; s <= 16; s++) {
            char sb[8];
            snprintf(sb, sizeof(sb), "%d", s);
            auto it = op["roles"].obj.find(sb);
            std::string key = (it == op["roles"].obj.end()) ? "" : it->second.str;
            if (key.empty()) {
                // Format-matched dummy.
                bool rgba = (s == 4 || (s >= 5 && s <= 9) || s == 16);
                key = rgba ? "dummyRgba" : "dummyR32";
            }
            setStorage(s + 1, key);
        }
        for (const auto& wr : writes) {
            // A NULL view here faults drivers (Mali reads view+0xa8) with
            // no useful error — fail loudly instead. Missing table images
            // (e.g. no "gamma") are the usual cause.
            if ((wr.descriptorType == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER ||
                 wr.descriptorType == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) &&
                wr.pImageInfo->imageView == VK_NULL_HANDLE) {
                fprintf(stderr, "NULL imageView for binding %u\n", wr.dstBinding);
                exit(1);
            }
        }
        vkUpdateDescriptorSets(dev, (uint32_t)writes.size(), writes.data(), 0, nullptr);
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeLayout, 0, 1, &set, 0,
                                nullptr);
        // UBO per manifest offsets (true std140).
        memset(uboPtr, 0, 112);
        auto putI = [&](int off, int v) { memcpy((char*)uboPtr + off, &v, 4); };
        auto putF = [&](int off, float v) { memcpy((char*)uboPtr + off, &v, 4); };
        putI(12, mode);
        putI(16, w);
        putI(20, h);
        putI(24, radius);
        putI(28, combineOp);
        putI(32, 4);
        putI(36, hfw);
        putI(40, hfh);
        putF(44, eps);
        putF(48, whitePt);
        putF(52, clipPt);
        putF(56, blendPt);
        memcpy((char*)uboPtr + 64, maxF, 12);
        memcpy((char*)uboPtr + 80, medF, 12);
        memcpy((char*)uboPtr + 96, xyz, 12);
        vkCmdDispatch(cb, (w + 7) / 8, (h + 7) / 8, 1);
        if (queryPool != VK_NULL_HANDLE && opIndex + 1 == (int)plan["ops"].arr.size()) {
            vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool, 1);
        }
        VK_CHECK(vkEndCommandBuffer(cb));
        {
            VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &cb,
                0, nullptr };
            VK_CHECK(vkResetFences(dev, 1, &fence));
            VK_CHECK(vkQueueSubmit(queue, 1, &si, fence));
            VK_CHECK(vkWaitForFences(dev, 1, &fence, VK_TRUE, 60000000000ull));
        }
        opIndex++;
    }
    printf("dispatches=%d\n", opIndex);
    {
        auto hostT1 = std::chrono::steady_clock::now();
        double hostMs =
            std::chrono::duration<double, std::milli>(hostT1 - hostT0).count();
        printf("host_ms=%.3f\n", hostMs);
    }
    if (queryPool != VK_NULL_HANDLE) {
        uint64_t stamps[2] = { 0, 0 };
        VK_CHECK(vkGetQueryPoolResults(dev, queryPool, 0, 2, sizeof(stamps), stamps, 8,
                                       VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT));
        printf("gpu_ms=%.3f\n", (stamps[1] - stamps[0]) * timestampPeriod / 1e6);
    }

    // Readback helper.
    auto download = [&](const std::string& key, const std::string& outName) {
        Image& im = images[key];
        VkCommandPool pool;
        VkCommandPoolCreateInfo pci{ VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, nullptr,
            VK_COMMAND_POOL_CREATE_TRANSIENT_BIT, qf };
        VK_CHECK(vkCreateCommandPool(dev, &pci, nullptr, &pool));
        VkCommandBuffer c2;
        VkCommandBufferAllocateInfo ai2{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr,
            pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1 };
        VK_CHECK(vkAllocateCommandBuffers(dev, &ai2, &c2));
        VkCommandBufferBeginInfo bi{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, nullptr,
            VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, nullptr };
        VK_CHECK(vkBeginCommandBuffer(c2, &bi));
        VkBufferImageCopy cp{};
        cp.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
        cp.imageExtent = { (uint32_t)im.w, (uint32_t)im.h, 1 };
        vkCmdCopyImageToBuffer(c2, im.img, VK_IMAGE_LAYOUT_GENERAL, stage, 1, &cp);
        VK_CHECK(vkEndCommandBuffer(c2));
        VkSubmitInfo si{ VK_STRUCTURE_TYPE_SUBMIT_INFO, nullptr, 0, nullptr, nullptr, 1, &c2, 0,
            nullptr };
        VkFence f2;
        VkFenceCreateInfo fi{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, nullptr, 0 };
        VK_CHECK(vkCreateFence(dev, &fi, nullptr, &f2));
        VK_CHECK(vkQueueSubmit(queue, 1, &si, f2));
        VK_CHECK(vkWaitForFences(dev, 1, &f2, VK_TRUE, 10000000000ull));
        vkDestroyFence(dev, f2, nullptr);
        vkFreeCommandBuffers(dev, pool, 1, &c2);
        vkDestroyCommandPool(dev, pool, nullptr);
        size_t bytes = (size_t)im.w * im.h * im.ch * 4;
        if (im.ch == 4) {
            // Deinterleave RGBA -> planar R,G,B to match the twin layout.
            int n = im.w * im.h;
            const float* src = (const float*)stagePtr;
            for (int c = 0; c < 3; c++) {
                std::string name = outDir + "/out_" + outName + "_" + std::to_string(c) + ".bin";
                std::ofstream o(name, std::ios::binary);
                std::vector<float> plane(n);
                for (int i = 0; i < n; i++) plane[i] = src[i * 4 + c];
                o.write((const char*)plane.data(), n * 4);
            }
        } else {
            std::string name = outDir + "/out_" + outName + ".bin";
            std::ofstream o(name, std::ios::binary);
            o.write((const char*)stagePtr, bytes);
        }
    };
    {
        auto it = plan.obj.find("readback");
        if (it == plan.obj.end()) {
            download("merged", "merged");
            download("hM", "mask");
            download("g", "guide");
        } else {
            for (const Json& rb : it->second.arr) download(rb.str, rb.str);
        }
    }

    vkUnmapMemory(dev, stageMem);
    return 0;
}
