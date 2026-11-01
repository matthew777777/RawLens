#pragma once
#include <vulkan/vulkan.h>

#include <cstdint>
namespace rawr::vk {
// Borrowed device handles a native library needs to create and record its own
// resources. The caller owns the device and all queue submission.
struct GpuContext {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
};
}  // namespace rawr::vk
