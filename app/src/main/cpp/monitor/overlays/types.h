#pragma once
#include <vulkan/vulkan.h>

#include <cstddef>
#include <cstdint>

#include "monitoring_overlays/config_types.h"

namespace monitoring_overlays {

struct VulkanContext {
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    const VkAllocationCallbacks* allocator = nullptr;
};

struct RawStateImageView {
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_R16_UINT;
    VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct DisplayImageView {
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
    VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct OverlayImageView {
    VkImageView view = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
    VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
    uint32_t width = 0;
    uint32_t height = 0;
};

struct ShaderBinary {
    const uint32_t* words = nullptr;
    size_t byteSize = 0;
};

struct MonitoringOverlaysCreateInfo {
    VulkanContext context{};
    ShaderBinary rawStateShader{};
    ShaderBinary focusPeakingShader{};
    ShaderBinary falseColorShader{};
    ShaderBinary tonemapShadowShader{};
    ShaderBinary combinedShader{};
    uint32_t maxFramesInFlight = 3;
    // These values are part of the compiled shader/host ABI and should normally
    // be left at defaults.
    uint32_t rawWorkgroupSizeX = 16;
    uint32_t rawWorkgroupSizeY = 16;
    uint32_t focusWorkgroupSizeX = 8;
    uint32_t focusWorkgroupSizeY = 8;
    uint32_t falseColorWorkgroupSizeX = 16;
    uint32_t falseColorWorkgroupSizeY = 16;
    uint32_t tonemapShadowWorkgroupSizeX = 16;
    uint32_t tonemapShadowWorkgroupSizeY = 16;
    uint32_t combinedWorkgroupSizeX = 8;
    uint32_t combinedWorkgroupSizeY = 8;
};

struct RawStateRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    RawStateImageView input{};
    OverlayImageView output{};
    RawStateOverlayParams params{};
    uint32_t frameSlot = 0;
    bool composeOverExisting = false;
};

struct FocusPeakingRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    // Display-referred (tonemapped RGBA8) input. Scene-linear was the 1.0.0
    // contract and is no longer supported: linear gradients are not
    // perceptually uniform and produced faint single-pixel overlays.
    DisplayImageView input{};
    OverlayImageView output{};
    FocusPeakingParams params{};
    uint32_t frameSlot = 0;
    bool composeOverExisting = false;
};

struct FalseColorRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    DisplayImageView input{};
    OverlayImageView output{};
    FalseColorParams params = FalseColorParams::defaultPreset();
    uint32_t frameSlot = 0;
    bool composeOverExisting = false;
};

struct TonemapShadowRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    DisplayImageView input{};
    OverlayImageView output{};
    TonemapShadowParams params{};
    uint32_t frameSlot = 0;
    bool composeOverExisting = false;
};

struct CombinedRecordInfo {
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    RawStateImageView rawState{};
    DisplayImageView display{};
    OverlayImageView output{};
    RawStateOverlayParams rawParams{};
    FocusPeakingParams focusParams{};
    FalseColorParams falseColorParams = FalseColorParams::defaultPreset();
    TonemapShadowParams tonemapShadowParams{};
    uint32_t frameSlot = 0;
};

}  // namespace monitoring_overlays
