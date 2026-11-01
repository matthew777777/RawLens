#pragma once
#include <vulkan/vulkan.h>

#include <utility>
#include <vector>

#include "rawr/vk/OwnedImage.h"
namespace rawr::vk {
// Owns Vulkan objects created on one device and destroys them in reverse
// creation order. Build a partial resource in a local instance and append()
// it once complete; failure paths then just return or throw. Freeing mapped
// memory unmaps it, so mapped allocations need no separate unmap.
class DeviceObjects final {
   public:
    explicit DeviceObjects(VkDevice device = VK_NULL_HANDLE) noexcept : device_(device) {}
    ~DeviceObjects() { reset(); }
    DeviceObjects(const DeviceObjects&) = delete;
    DeviceObjects& operator=(const DeviceObjects&) = delete;
    DeviceObjects(DeviceObjects&& other) noexcept
        : device_(std::exchange(other.device_, VkDevice{})), objects_(std::move(other.objects_)) {
        other.objects_.clear();
    }
    DeviceObjects& operator=(DeviceObjects&& other) noexcept {
        if (this != &other) {
            reset();
            device_ = std::exchange(other.device_, VkDevice{});
            objects_ = std::move(other.objects_);
            other.objects_.clear();
        }
        return *this;
    }

    VkDevice device() const noexcept { return device_; }
    bool empty() const noexcept { return objects_.empty(); }
    // Each adopt takes ownership of a handle (null is ignored) and returns it.
    VkImage adoptImage(VkImage h) { return adopt(Kind::Image, h); }
    VkImageView adoptView(VkImageView h) { return adopt(Kind::View, h); }
    VkBuffer adoptBuffer(VkBuffer h) { return adopt(Kind::Buffer, h); }
    VkDeviceMemory adoptMemory(VkDeviceMemory h) { return adopt(Kind::Memory, h); }
    VkPipeline adoptPipeline(VkPipeline h) { return adopt(Kind::Pipeline, h); }
    VkPipelineLayout adoptPipelineLayout(VkPipelineLayout h) { return adopt(Kind::PipelineLayout, h); }
    VkDescriptorSetLayout adoptDescriptorSetLayout(VkDescriptorSetLayout h) {
        return adopt(Kind::DescriptorSetLayout, h);
    }
    VkDescriptorPool adoptDescriptorPool(VkDescriptorPool h) { return adopt(Kind::DescriptorPool, h); }
    VkShaderModule adoptShaderModule(VkShaderModule h) { return adopt(Kind::ShaderModule, h); }
    VkSampler adoptSampler(VkSampler h) { return adopt(Kind::Sampler, h); }
    // Takes an OwnedImage's memory, image and view (destroyed view first).
    const OwnedImage& adoptOwnedImage(const OwnedImage& image) {
        adoptMemory(image.memory);
        adoptImage(image.image);
        adoptView(image.view);
        return image;
    }
    // Takes every object of other (same device); they are destroyed before this one's.
    void append(DeviceObjects&& other) {
        objects_.insert(objects_.end(), other.objects_.begin(), other.objects_.end());
        other.objects_.clear();
    }
    void reset() noexcept {
        for (auto it = objects_.rbegin(); it != objects_.rend(); ++it) destroy(*it);
        objects_.clear();
    }

   private:
    // Non-dispatchable handles are pointers on the 64-bit builds.
    enum class Kind {
        Image,
        View,
        Buffer,
        Memory,
        Pipeline,
        PipelineLayout,
        DescriptorSetLayout,
        DescriptorPool,
        ShaderModule,
        Sampler
    };
    struct Object {
        Kind kind;
        void* handle;
    };
    template <typename Handle>
    Handle adopt(Kind kind, Handle handle) {
        if (handle != VK_NULL_HANDLE) objects_.push_back({kind, reinterpret_cast<void*>(handle)});
        return handle;
    }
    template <typename Handle>
    static Handle as(const Object& o) noexcept {
        return reinterpret_cast<Handle>(o.handle);
    }
    void destroy(const Object& o) noexcept {
        switch (o.kind) {
            case Kind::Image: vkDestroyImage(device_, as<VkImage>(o), nullptr); break;
            case Kind::View: vkDestroyImageView(device_, as<VkImageView>(o), nullptr); break;
            case Kind::Buffer: vkDestroyBuffer(device_, as<VkBuffer>(o), nullptr); break;
            case Kind::Memory: vkFreeMemory(device_, as<VkDeviceMemory>(o), nullptr); break;
            case Kind::Pipeline: vkDestroyPipeline(device_, as<VkPipeline>(o), nullptr); break;
            case Kind::PipelineLayout: vkDestroyPipelineLayout(device_, as<VkPipelineLayout>(o), nullptr); break;
            case Kind::DescriptorSetLayout:
                vkDestroyDescriptorSetLayout(device_, as<VkDescriptorSetLayout>(o), nullptr);
                break;
            case Kind::DescriptorPool: vkDestroyDescriptorPool(device_, as<VkDescriptorPool>(o), nullptr); break;
            case Kind::ShaderModule: vkDestroyShaderModule(device_, as<VkShaderModule>(o), nullptr); break;
            case Kind::Sampler: vkDestroySampler(device_, as<VkSampler>(o), nullptr); break;
        }
    }
    VkDevice device_;
    std::vector<Object> objects_;
};
}  // namespace rawr::vk
