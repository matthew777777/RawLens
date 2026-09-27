// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// GALOSH Vulkan host scaffold: instance/device bootstrap plus SPIR-V
// shader-module loading from assets. Device requirements mirror upstream
// galosh_vk.c (Vulkan 1.2, float16 arithmetic, 16-bit storage) while
// degrading gracefully so the probe test can report instead of crash.
#include "galosh_host.h"

#include <new>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include "galosh_internal.h"

uint32_t galosh_find_mem_type(VkPhysicalDevice pd, uint32_t bits, VkMemoryPropertyFlags want,
                              bool* ok) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) {
            if (ok != nullptr) *ok = true;
            return i;
        }
    }
    if (ok != nullptr) *ok = false;
    return 0;
}

bool galosh_make_buf(VkDevice dev, VkPhysicalDevice pd, VkDeviceSize size,
                     VkBufferUsageFlags use, VkMemoryPropertyFlags props, GBuf* out) {
    if (out == nullptr || size == 0) return false;
    *out = GBuf{};
    VkBufferCreateInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bi.size = size;
    bi.usage = use;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(dev, &bi, nullptr, &out->buf) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(dev, out->buf, &mr);
    bool memOk = false;
    const uint32_t index = galosh_find_mem_type(pd, mr.memoryTypeBits, props, &memOk);
    if (!memOk) {
        vkDestroyBuffer(dev, out->buf, nullptr);
        *out = GBuf{};
        return false;
    }
    VkMemoryAllocateInfo mai{};
    mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    mai.allocationSize = mr.size;
    mai.memoryTypeIndex = index;
    if (vkAllocateMemory(dev, &mai, nullptr, &out->mem) != VK_SUCCESS) {
        vkDestroyBuffer(dev, out->buf, nullptr);
        *out = GBuf{};
        return false;
    }
    if (vkBindBufferMemory(dev, out->buf, out->mem, 0) != VK_SUCCESS) {
        vkFreeMemory(dev, out->mem, nullptr);
        vkDestroyBuffer(dev, out->buf, nullptr);
        *out = GBuf{};
        return false;
    }
    out->size = size;
    return true;
}

void galosh_free_buf(VkDevice dev, GBuf* b) {
    if (b == nullptr) return;
    if (b->buf != VK_NULL_HANDLE) vkDestroyBuffer(dev, b->buf, nullptr);
    if (b->mem != VK_NULL_HANDLE) vkFreeMemory(dev, b->mem, nullptr);
    *b = GBuf{};
}

void galosh_destroy(GaloshContext* ctx) {
    if (ctx == nullptr) return;
    galosh_destroy_pipelines(ctx);
    if (ctx->pool != VK_NULL_HANDLE) vkDestroyCommandPool(ctx->device, ctx->pool, nullptr);
    if (ctx->device != VK_NULL_HANDLE) vkDestroyDevice(ctx->device, nullptr);
    if (ctx->instance != VK_NULL_HANDLE) vkDestroyInstance(ctx->instance, nullptr);
    delete ctx;
}

// Upstream [B7g] probe: the subgroup-cooperative pass12 needs subgroup size
// pinnable to exactly 32 on the compute stage.
static bool probe_sg32(VkPhysicalDevice pd) {
    uint32_t nx = 0;
    vkEnumerateDeviceExtensionProperties(pd, nullptr, &nx, nullptr);
    if (nx == 0) return false;
    VkExtensionProperties* xp = new (std::nothrow) VkExtensionProperties[nx];
    if (xp == nullptr) return false;
    bool has = false;
    if (vkEnumerateDeviceExtensionProperties(pd, nullptr, &nx, xp) == VK_SUCCESS) {
        for (uint32_t i = 0; i < nx; i++) {
            if (strcmp(xp[i].extensionName, VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME) == 0) {
                has = true;
                break;
            }
        }
    }
    delete[] xp;
    if (!has) return false;
    VkPhysicalDeviceSubgroupSizeControlFeaturesEXT sf{};
    sf.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_FEATURES_EXT;
    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    f2.pNext = &sf;
    vkGetPhysicalDeviceFeatures2(pd, &f2);
    VkPhysicalDeviceSubgroupSizeControlPropertiesEXT sp{};
    sp.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES_EXT;
    VkPhysicalDeviceProperties2 p2{};
    p2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    p2.pNext = &sp;
    vkGetPhysicalDeviceProperties2(pd, &p2);
    return sf.subgroupSizeControl != 0u && sf.computeFullSubgroups != 0u &&
           sp.minSubgroupSize <= 32 && 32 <= sp.maxSubgroupSize &&
           (sp.requiredSubgroupSizeStages & VK_SHADER_STAGE_COMPUTE_BIT) != 0;
}

GaloshContext* galosh_create(char* errmsg, size_t errmsg_len) {
    GaloshContext* ctx = new (std::nothrow) GaloshContext();
    if (ctx == nullptr) {
        if (errmsg != nullptr && errmsg_len > 0) snprintf(errmsg, errmsg_len, "out of memory");
        return nullptr;
    }
    uint32_t loader = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion(&loader) != VK_SUCCESS) loader = VK_API_VERSION_1_0;

    // Try 1.2 first (GALOSH requirement), fall back to 1.1 so older devices
    // still produce a usable context for the caps-gated classic path.
    const uint32_t candidates[2] = {VK_API_VERSION_1_2, VK_API_VERSION_1_1};
    VkResult created = VK_ERROR_INCOMPATIBLE_DRIVER;
    for (uint32_t want : candidates) {
        if (loader < want) continue;
        VkApplicationInfo app{};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.pApplicationName = "rawlens-galosh";
        app.apiVersion = want;
        VkInstanceCreateInfo ici{};
        ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        ici.pApplicationInfo = &app;
        created = vkCreateInstance(&ici, nullptr, &ctx->instance);
        if (created == VK_SUCCESS) {
            ctx->instanceVersion = want;
            break;
        }
        ctx->instance = VK_NULL_HANDLE;
    }
    if (created != VK_SUCCESS) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "vkCreateInstance failed: %d", (int)created);
        galosh_destroy(ctx);
        return nullptr;
    }

    uint32_t ndev = 0;
    vkEnumeratePhysicalDevices(ctx->instance, &ndev, nullptr);
    if (ndev == 0) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "no Vulkan physical devices");
        galosh_destroy(ctx);
        return nullptr;
    }
    VkPhysicalDevice* devs = new (std::nothrow) VkPhysicalDevice[ndev];
    if (devs == nullptr) {
        if (errmsg != nullptr && errmsg_len > 0) snprintf(errmsg, errmsg_len, "out of memory");
        galosh_destroy(ctx);
        return nullptr;
    }
    vkEnumeratePhysicalDevices(ctx->instance, &ndev, devs);
    ctx->physical = devs[0];
    delete[] devs;
    vkGetPhysicalDeviceProperties(ctx->physical, &ctx->props);
    ctx->sg_ok = probe_sg32(ctx->physical);

    uint32_t nq = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physical, &nq, nullptr);
    VkQueueFamilyProperties* qprops = new (std::nothrow) VkQueueFamilyProperties[nq > 0 ? nq : 1];
    if (nq == 0 || qprops == nullptr) {
        delete[] qprops;
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "no queue families / out of memory");
        galosh_destroy(ctx);
        return nullptr;
    }
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->physical, &nq, qprops);
    bool haveCompute = false;
    for (uint32_t i = 0; i < nq; ++i) {
        if ((qprops[i].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
            ctx->queueFamily = i;
            haveCompute = true;
            break;
        }
    }
    delete[] qprops;
    if (!haveCompute) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "no compute queue family");
        galosh_destroy(ctx);
        return nullptr;
    }

    VkPhysicalDeviceShaderFloat16Int8Features f16{};
    f16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
    VkPhysicalDevice16BitStorageFeatures stor16{};
    stor16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
    f16.pNext = &stor16;
    VkPhysicalDeviceFeatures2 feats2{};
    feats2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    feats2.pNext = &f16;
    vkGetPhysicalDeviceFeatures2(ctx->physical, &feats2);
    ctx->shaderF16 = f16.shaderFloat16 != 0u;
    ctx->storage16 = stor16.storageBuffer16BitAccess != 0u;

    // Enable float16/16-bit-storage only where supported; the Phase 2
    // pipeline reads these flags to pick the FP16 vs FP32 contract.
    VkPhysicalDeviceShaderFloat16Int8Features wantF16{};
    wantF16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
    VkPhysicalDevice16BitStorageFeatures wantStor{};
    wantStor.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
    void* tail = nullptr;
    if (ctx->storage16) {
        wantStor.storageBuffer16BitAccess = VK_TRUE;
        tail = &wantStor;
    }
    if (ctx->shaderF16) {
        wantF16.shaderFloat16 = VK_TRUE;
        wantF16.pNext = tail;
        tail = &wantF16;
    }
    const float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = ctx->queueFamily;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.pNext = tail;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    if (vkCreateDevice(ctx->physical, &dci, nullptr, &ctx->device) != VK_SUCCESS) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "vkCreateDevice failed");
        galosh_destroy(ctx);
        return nullptr;
    }
    vkGetDeviceQueue(ctx->device, ctx->queueFamily, 0, &ctx->queue);
    VkCommandPoolCreateInfo pci{};
    pci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pci.queueFamilyIndex = ctx->queueFamily;
    if (vkCreateCommandPool(ctx->device, &pci, nullptr, &ctx->pool) != VK_SUCCESS) {
        if (errmsg != nullptr && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "vkCreateCommandPool failed");
        galosh_destroy(ctx);
        return nullptr;
    }
    return ctx;
}

int galosh_load_shaders(GaloshContext* ctx, void* assetMgr, char* errmsg, size_t errmsg_len) {
    if (ctx == nullptr) {
        if (errmsg != nullptr && errmsg_len > 0) snprintf(errmsg, errmsg_len, "null context");
        return -1;
    }
    // Per-kernel module + pipeline creation (see galosh_init_pipelines).
    if (galosh_init_pipelines(ctx, assetMgr, errmsg, errmsg_len) != 0) return -1;
    return galosh_loaded_shader_count(ctx);
}

int galosh_loaded_shader_count(const GaloshContext* ctx) {
    if (ctx == nullptr) return -1;
    int n = 0;
    for (int i = 0; i < GALOSH_K_COUNT; i++) {
        if (ctx->kerns[i].pipe != VK_NULL_HANDLE) n++;
    }
    return n;
}
