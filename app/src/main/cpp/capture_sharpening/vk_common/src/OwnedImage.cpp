#include "rawr/vk/OwnedImage.h"

#include <stdexcept>
namespace rawr::vk {
namespace {
uint32_t findMemoryType(VkPhysicalDevice physical, uint32_t bits, VkMemoryPropertyFlags wanted) {
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(physical, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & wanted) == wanted) return i;
    for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
        if (bits & (1u << i)) return i;
    throw std::runtime_error("No compatible Vulkan memory type");
}
void check(VkResult r, const char* what) {
    if (r != VK_SUCCESS) throw std::runtime_error(what);
}
}  // namespace
OwnedImage createOwnedImage(VkPhysicalDevice p, VkDevice d, uint32_t w, uint32_t h, VkFormat f,
                            VkImageUsageFlags usage) {
    OwnedImage o{};
    o.format = f;
    o.width = w;
    o.height = h;
    VkImageCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = f;
    ci.extent = {w, h, 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    check(vkCreateImage(d, &ci, nullptr, &o.image), "create image");
    try {
        VkMemoryRequirements mr{};
        vkGetImageMemoryRequirements(d, o.image, &mr);
        VkMemoryAllocateInfo ma{};
        ma.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ma.allocationSize = mr.size;
        ma.memoryTypeIndex = findMemoryType(p, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        check(vkAllocateMemory(d, &ma, nullptr, &o.memory), "alloc image");
        check(vkBindImageMemory(d, o.image, o.memory, 0), "bind image");
        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = o.image;
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = f;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        check(vkCreateImageView(d, &vi, nullptr, &o.view), "image view");
    } catch (...) {
        destroyOwnedImage(d, o);
        throw;
    }
    return o;
}
void destroyOwnedImage(VkDevice d, OwnedImage& o) noexcept {
    if (o.view) vkDestroyImageView(d, o.view, nullptr);
    if (o.image) vkDestroyImage(d, o.image, nullptr);
    if (o.memory) vkFreeMemory(d, o.memory, nullptr);
    o = {};
}
void recordImageCopy(VkCommandBuffer command, VkImage source, VkImage destination, uint32_t width, uint32_t height) {
    VkImageCopy region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.extent = {width, height, 1};
    vkCmdCopyImage(command, source, VK_IMAGE_LAYOUT_GENERAL, destination, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
}
}  // namespace rawr::vk
