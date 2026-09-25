// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Unified Vulkan compute core: identical sources for Android (NDK) and
// desktop (Linux/macOS). Straight Vulkan C, no loader-specific calls, no
// surface/swapchain (headless compute only), no validation layers.
#include "srvk_compute.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include "srvk_internal.h"

static int has_instance_ext(const char* name) {
    uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(NULL, &count, NULL) != VK_SUCCESS) return 0;
    VkExtensionProperties* exts =
        (VkExtensionProperties*)malloc(sizeof(VkExtensionProperties) * (count ? count : 1));
    if (exts == NULL) return 0;
    int found = 0;
    if (vkEnumerateInstanceExtensionProperties(NULL, &count, exts) == VK_SUCCESS) {
        for (uint32_t i = 0; i < count; i++) {
            if (strcmp(exts[i].extensionName, name) == 0) { found = 1; break; }
        }
    }
    free(exts);
    return found;
}

SrvkContext* srvk_create(char* errmsg, size_t errmsg_len) {
    SrvkContext* ctx = (SrvkContext*)calloc(1, sizeof(SrvkContext));
    if (ctx == NULL) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "out of memory");
        return NULL;
    }

    VkApplicationInfo app = {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "sr-vulkan";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "RawLens";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = VK_API_VERSION_1_2;

    // Portability enumeration (MoltenVK and other conformant-but-portable
    // drivers) when the loader advertises it; harmless when absent.
    const char* exts[1];
    uint32_t ext_count = 0;
    VkInstanceCreateFlags flags = 0;
    if (has_instance_ext(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
        exts[ext_count++] = VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
        flags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
    }

    VkInstanceCreateInfo ici = {};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.flags = flags;
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = ext_count;
    ici.ppEnabledExtensionNames = ext_count ? exts : NULL;

    VkResult res = vkCreateInstance(&ici, NULL, &ctx->instance);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkCreateInstance failed", res);
        free(ctx);
        return NULL;
    }

    uint32_t count = 0;
    res = vkEnumeratePhysicalDevices(ctx->instance, &count, NULL);
    if (res != VK_SUCCESS || count == 0) {
        srvk_set_err(errmsg, errmsg_len, "no Vulkan physical devices", res);
        vkDestroyInstance(ctx->instance, NULL);
        free(ctx);
        return NULL;
    }
    ctx->devices = (VkPhysicalDevice*)malloc(sizeof(VkPhysicalDevice) * count);
    if (ctx->devices == NULL) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "out of memory");
        vkDestroyInstance(ctx->instance, NULL);
        free(ctx);
        return NULL;
    }
    res = vkEnumeratePhysicalDevices(ctx->instance, &count, ctx->devices);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkEnumeratePhysicalDevices failed", res);
        free(ctx->devices);
        vkDestroyInstance(ctx->instance, NULL);
        free(ctx);
        return NULL;
    }
    ctx->device_count = count;

    // First device with a compute queue; prefer compute-only families so the
    // merge never contends with a graphics queue, accept combined ones.
    int found = -1;
    uint32_t found_family = 0;
    for (uint32_t d = 0; d < count && found < 0; d++) {
        uint32_t qcount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(ctx->devices[d], &qcount, NULL);
        VkQueueFamilyProperties* qprops =
            (VkQueueFamilyProperties*)malloc(sizeof(VkQueueFamilyProperties) * (qcount ? qcount : 1));
        if (qprops == NULL) continue;
        vkGetPhysicalDeviceQueueFamilyProperties(ctx->devices[d], &qcount, qprops);
        int combined = -1;
        for (uint32_t q = 0; q < qcount; q++) {
            if (!(qprops[q].queueFlags & VK_QUEUE_COMPUTE_BIT)) continue;
            if (!(qprops[q].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
                found = (int)d;
                found_family = q;
                break;
            }
            if (combined < 0) combined = (int)q;
        }
        if (found < 0 && combined >= 0) {
            found = (int)d;
            found_family = (uint32_t)combined;
        }
        free(qprops);
    }
    if (found < 0) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "no compute queue on %u device(s)", count);
        free(ctx->devices);
        vkDestroyInstance(ctx->instance, NULL);
        free(ctx);
        return NULL;
    }
    ctx->physical = ctx->devices[found];
    ctx->physical_index = (uint32_t)found;
    ctx->queue_family = found_family;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo qci = {};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = found_family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    VkDeviceCreateInfo dci = {};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    res = vkCreateDevice(ctx->physical, &dci, NULL, &ctx->device);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkCreateDevice failed", res);
        free(ctx->devices);
        vkDestroyInstance(ctx->instance, NULL);
        free(ctx);
        return NULL;
    }
    vkGetDeviceQueue(ctx->device, found_family, 0, &ctx->queue);
    if (srvk_resources_init(ctx, errmsg, errmsg_len) != 0) {
        srvk_resources_teardown(ctx);
        free(ctx->devices);
        vkDestroyDevice(ctx->device, NULL);
        vkDestroyInstance(ctx->instance, NULL);
        free(ctx);
        return NULL;
    }
    return ctx;
}

static const char* device_type_name(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
        default: return "other";
    }
}

void srvk_device_info(SrvkContext* ctx, char* buf, size_t len) {
    if (ctx == NULL || buf == NULL || len == 0) return;
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(ctx->physical, &props);
    VkPhysicalDeviceSubgroupProperties subgroup = {};
    subgroup.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
    VkPhysicalDeviceProperties2 props2 = {};
    props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    props2.pNext = &subgroup;
    vkGetPhysicalDeviceProperties2(ctx->physical, &props2);
    char name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    snprintf(name, sizeof(name), "%s", props.deviceName);
    // Flatten whitespace so the report stays one line.
    for (char* p = name; *p; p++) {
        if (*p == '\n' || *p == '\r' || *p == ';') *p = ' ';
    }
    snprintf(buf, len,
             "device=%s;type=%s;api=%u.%u.%u;driver=0x%08x;vendor=0x%04x;"
             "computeFamily=%u;maxWorkgroup=%u,%u,%u;maxInvocations=%u;subgroup=%u;",
             name, device_type_name(props.deviceType),
             VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion),
             VK_VERSION_PATCH(props.apiVersion), props.driverVersion, props.vendorID,
             ctx->queue_family, props.limits.maxComputeWorkGroupCount[0],
             props.limits.maxComputeWorkGroupCount[1],
             props.limits.maxComputeWorkGroupCount[2],
             props.limits.maxComputeWorkGroupInvocations, subgroup.subgroupSize);
}

int srvk_device_count(SrvkContext* ctx) {
    return ctx == NULL ? 0 : (int)ctx->device_count;
}

void srvk_device_name(SrvkContext* ctx, int index, char* buf, size_t len) {
    if (ctx == NULL || buf == NULL || len == 0) return;
    buf[0] = '\0';
    if (index < 0 || (uint32_t)index >= ctx->device_count) return;
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(ctx->devices[index], &props);
    snprintf(buf, len, "%s", props.deviceName);
}

uint64_t srvk_load_module(SrvkContext* ctx, const uint32_t* words, size_t word_count,
                          char* errmsg, size_t errmsg_len) {
    if (ctx == NULL || words == NULL || word_count == 0) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "empty SPIR-V");
        return 0;
    }
    if (words[0] != 0x07230203u) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "bad SPIR-V magic 0x%08x", words[0]);
        return 0;
    }
    VkShaderModuleCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = word_count * 4;
    ci.pCode = words;
    VkShaderModule module = VK_NULL_HANDLE;
    VkResult res = vkCreateShaderModule(ctx->device, &ci, NULL, &module);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkCreateShaderModule failed", res);
        return 0;
    }
    return (uint64_t)(uintptr_t)module;
}

void srvk_destroy_module(SrvkContext* ctx, uint64_t module) {
    if (ctx == NULL || module == 0) return;
    vkDestroyShaderModule(ctx->device, (VkShaderModule)(uintptr_t)module, NULL);
}

void srvk_destroy(SrvkContext* ctx) {
    if (ctx == NULL) return;
    srvk_resources_teardown(ctx);
    if (ctx->device != VK_NULL_HANDLE) vkDestroyDevice(ctx->device, NULL);
    if (ctx->instance != VK_NULL_HANDLE) vkDestroyInstance(ctx->instance, NULL);
    free(ctx->devices);
    free(ctx);
}
