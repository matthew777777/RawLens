#pragma once
#include <vulkan/vulkan.h>

#include <cstdint>
namespace rawr::vk {
struct OwnedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t width = 0, height = 0;
};
OwnedImage createOwnedImage(VkPhysicalDevice physical, VkDevice device, uint32_t width, uint32_t height,
                            VkFormat format, VkImageUsageFlags usage);
void destroyOwnedImage(VkDevice device, OwnedImage& image) noexcept;
void recordImageCopy(VkCommandBuffer command, VkImage source, VkImage destination, uint32_t width, uint32_t height);
}  // namespace rawr::vk
