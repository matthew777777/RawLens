// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// One-shot Vulkan capability probe for GALOSH. No device is created; the
// throwaway instance is destroyed before return.
#include "galosh_host.h"

#include <new>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <vulkan/vulkan.h>

namespace {

class JsonWriter {
  public:
    JsonWriter(char* buf, size_t len) : buf_(buf), len_(len), pos_(0), ok_(len > 0) {
        if (len_ > 0) buf_[0] = '\0';
    }
    bool ok() const { return ok_; }
    void Raw(const char* s) { AppendStr(s); }
    void AppendStr(const char* s) {
        if (!ok_) return;
        const int n = snprintf(buf_ + pos_, len_ - pos_, "%s", s);
        if (n < 0 || (size_t)n >= len_ - pos_) {
            ok_ = false;
            return;
        }
        pos_ += (size_t)n;
    }
    void Bool(bool v) { Raw(v ? "true" : "false"); }
    void Version(uint32_t v) {
        Append("\"%u.%u.%u\"", (unsigned)VK_VERSION_MAJOR(v), (unsigned)VK_VERSION_MINOR(v),
               (unsigned)VK_VERSION_PATCH(v));
    }
    void Escaped(const char* s) {
        Raw("\"");
        for (const char* p = s; *p != '\0'; ++p) {
            const unsigned char c = (unsigned char)*p;
            if (c == '"' || c == '\\') {
                Append("\\%c", (int)c);
            } else if (c < 0x20) {
                Append("\\u%04x", (unsigned)c);
            } else {
                Append("%c", (int)c);
            }
        }
        Raw("\"");
    }
    template <typename... Args>
    void Append(const char* fmt, Args... args) {
        if (!ok_) return;
        const int n = snprintf(buf_ + pos_, len_ - pos_, fmt, args...);
        if (n < 0 || (size_t)n >= len_ - pos_) {
            ok_ = false;
            return;
        }
        pos_ += (size_t)n;
    }

  private:
    char* buf_;
    size_t len_;
    size_t pos_;
    bool ok_;
};

uint32_t LoaderVersion() {
    uint32_t v = VK_API_VERSION_1_0;
    if (vkEnumerateInstanceVersion(&v) != VK_SUCCESS) v = VK_API_VERSION_1_0;
    return v;
}

}  // namespace

int galosh_probe_caps(char* json, size_t json_len) {
    if (json == nullptr || json_len == 0) return -1;
    JsonWriter w(json, json_len);

    const uint32_t loader = LoaderVersion();
    const uint32_t req = loader >= VK_API_VERSION_1_2 ? VK_API_VERSION_1_2 : loader;

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "rawlens-galosh-probe";
    app.apiVersion = req;
    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&ici, nullptr, &inst) != VK_SUCCESS) {
        snprintf(json, json_len, "{\"error\":\"vkCreateInstance failed\"}");
        return -1;
    }

    uint32_t ndev = 0;
    vkEnumeratePhysicalDevices(inst, &ndev, nullptr);
    VkPhysicalDevice devs[8];
    if (ndev > 8) ndev = 8;
    if (ndev > 0) vkEnumeratePhysicalDevices(inst, &ndev, devs);

    w.Raw("{\"loaderVersion\":");
    w.Version(loader);
    w.Raw(",\"instanceVersion\":");
    w.Version(req);
    w.Append(",\"deviceCount\":%u,\"devices\":[", (unsigned)ndev);
    for (uint32_t d = 0; d < ndev; ++d) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(devs[d], &props);

        VkPhysicalDeviceSubgroupProperties sgProps{};
        sgProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
        VkPhysicalDeviceSubgroupSizeControlPropertiesEXT sgSizeProps{};
        sgSizeProps.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES_EXT;
        sgSizeProps.pNext = nullptr;
        sgProps.pNext = &sgSizeProps;
        VkPhysicalDeviceProperties2 props2{};
        props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
        props2.pNext = &sgProps;
        vkGetPhysicalDeviceProperties2(devs[d], &props2);

        VkPhysicalDeviceShaderFloat16Int8Features f16{};
        f16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
        VkPhysicalDevice16BitStorageFeatures stor16{};
        stor16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
        VkPhysicalDeviceSubgroupSizeControlFeaturesEXT sgFeat{};
        sgFeat.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_FEATURES_EXT;
        f16.pNext = &stor16;
        stor16.pNext = &sgFeat;
        sgFeat.pNext = nullptr;
        VkPhysicalDeviceFeatures2 feats2{};
        feats2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        feats2.pNext = &f16;
        vkGetPhysicalDeviceFeatures2(devs[d], &feats2);

        bool sgControlExt = false;
        uint32_t next = 0;
        vkEnumerateDeviceExtensionProperties(devs[d], nullptr, &next, nullptr);
        if (next > 0) {
            VkExtensionProperties* exts = new (std::nothrow) VkExtensionProperties[next];
            if (exts != nullptr) {
                if (vkEnumerateDeviceExtensionProperties(devs[d], nullptr, &next, exts) ==
                    VK_SUCCESS) {
                    for (uint32_t i = 0; i < next; ++i) {
                        if (strcmp(exts[i].extensionName,
                                   VK_EXT_SUBGROUP_SIZE_CONTROL_EXTENSION_NAME) == 0) {
                            sgControlExt = true;
                        }
                    }
                }
                delete[] exts;
            }
        }

        uint32_t nq = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devs[d], &nq, nullptr);
        VkQueueFamilyProperties qprops[16];
        if (nq > 16) nq = 16;
        if (nq > 0) vkGetPhysicalDeviceQueueFamilyProperties(devs[d], &nq, qprops);

        VkPhysicalDeviceMemoryProperties mem{};
        vkGetPhysicalDeviceMemoryProperties(devs[d], &mem);

        if (d > 0) w.Raw(",");
        w.Raw("{\"name\":");
        w.Escaped(props.deviceName);
        w.Append(",\"vendorId\":%u,\"deviceId\":%u,\"deviceType\":%d,\"driverVersion\":%u",
                 (unsigned)props.vendorID, (unsigned)props.deviceID, (int)props.deviceType,
                 (unsigned)props.driverVersion);
        w.Raw(",\"apiVersion\":");
        w.Version(props.apiVersion);
        const VkPhysicalDeviceLimits& lim = props.limits;
        w.Append(",\"limits\":{\"maxComputeWorkGroupSize\":[%u,%u,%u]"
                 ",\"maxComputeWorkGroupInvocations\":%u,\"maxPushConstantsSize\":%u"
                 ",\"maxStorageBufferRange\":%u,\"maxComputeSharedMemorySize\":%u"
                 ",\"maxMemoryAllocationCount\":%u}",
                 (unsigned)lim.maxComputeWorkGroupSize[0], (unsigned)lim.maxComputeWorkGroupSize[1],
                 (unsigned)lim.maxComputeWorkGroupSize[2],
                 (unsigned)lim.maxComputeWorkGroupInvocations, (unsigned)lim.maxPushConstantsSize,
                 (unsigned)lim.maxStorageBufferRange, (unsigned)lim.maxComputeSharedMemorySize,
                 (unsigned)lim.maxMemoryAllocationCount);
        w.Append(",\"subgroup\":{\"size\":%u,\"supportedStages\":%u,\"supportedOperations\":%u}",
                 (unsigned)sgProps.subgroupSize, (unsigned)sgProps.supportedStages,
                 (unsigned)sgProps.supportedOperations);
        w.Raw(",\"features\":{\"shaderFloat16\":");
        w.Bool(f16.shaderFloat16 != 0u);
        w.Raw(",\"storageBuffer16BitAccess\":");
        w.Bool(stor16.storageBuffer16BitAccess != 0u);
        w.Raw(",\"subgroupSizeControlExt\":");
        w.Bool(sgControlExt);
        w.Raw(",\"subgroupSizeControl\":");
        w.Bool(sgFeat.subgroupSizeControl != 0u);
        w.Raw(",\"computeFullSubgroups\":");
        w.Bool(sgFeat.computeFullSubgroups != 0u);
        w.Append(",\"minSubgroupSize\":%u,\"maxSubgroupSize\":%u}",
                 (unsigned)sgSizeProps.minSubgroupSize, (unsigned)sgSizeProps.maxSubgroupSize);
        w.Raw(",\"queues\":[");
        for (uint32_t i = 0; i < nq; ++i) {
            if (i > 0) w.Raw(",");
            w.Append("{\"flags\":%u,\"count\":%u}", (unsigned)qprops[i].queueFlags,
                     (unsigned)qprops[i].queueCount);
        }
        w.Append("],\"memory\":{\"heapCount\":%u,\"heaps\":[", (unsigned)mem.memoryHeapCount);
        for (uint32_t i = 0; i < mem.memoryHeapCount; ++i) {
            if (i > 0) w.Raw(",");
            w.Append("{\"size\":%llu,\"flags\":%u}", (unsigned long long)mem.memoryHeaps[i].size,
                     (unsigned)mem.memoryHeaps[i].flags);
        }
        w.Append("],\"typeCount\":%u}", (unsigned)mem.memoryTypeCount);
        w.Raw("}");
    }
    w.Raw("]}");
    vkDestroyInstance(inst, nullptr);
    return w.ok() ? 0 : -1;
}
