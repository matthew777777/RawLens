// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Unified resource layer: images, pipelines, synchronous dispatch.
// Identical sources for Android (NDK) and desktop. Mirrors the GLES
// texture/program/discharge discipline: NEAREST + CLAMP_TO_EDGE sampling,
// one uniform block per pass, synchronous per-slice dispatch with the same
// 10s fence timeout the GL path uses.
#include "srvk_compute.h"

#include <stdlib.h>
#include <string.h>

#include "srvk_internal.h"

#define SRVK_FENCE_TIMEOUT_NS 10000000000ULL

struct SrvkImage {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkFormat vk_format;
    int width;
    int height;
    int format;
    size_t bytes;
    VkImageLayout layout;
};

struct SrvkPipeline {
    VkPipeline pipeline;
    VkPipelineLayout pipe_layout;
    VkDescriptorSetLayout set_layout;
    VkDescriptorSet set;
    VkBuffer ubo;
    VkDeviceMemory ubo_memory;
    void* ubo_mapped;
    size_t ubo_size;
    // Per-binding image kind (SRVK_SAMPLED/STORAGE, -1 unset), indexed by
    // descriptor binding. Bindings are small (<= 32 in every SR shader).
    int kinds[40];
};

static int format_info(int format, VkFormat* vk_format, size_t* texel) {
    switch (format) {
        case SRVK_R32F: *vk_format = VK_FORMAT_R32_SFLOAT; *texel = 4; return 0;
        case SRVK_R32UI: *vk_format = VK_FORMAT_R32_UINT; *texel = 4; return 0;
        case SRVK_RGBA32F: *vk_format = VK_FORMAT_R32G32B32A32_SFLOAT; *texel = 16; return 0;
        case SRVK_R16UI: *vk_format = VK_FORMAT_R16_UINT; *texel = 2; return 0;
        default: return -1;
    }
}

static int memory_type(SrvkContext* ctx, uint32_t bits, VkMemoryPropertyFlags props,
                       uint32_t* index) {
    VkPhysicalDeviceMemoryProperties mem;
    vkGetPhysicalDeviceMemoryProperties(ctx->physical, &mem);
    for (uint32_t i = 0; i < mem.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (mem.memoryTypes[i].propertyFlags & props) == props) {
            *index = i;
            return 0;
        }
    }
    return -1;
}

uint64_t srvk_create_image(SrvkContext* ctx, int width, int height, int format,
                           char* errmsg, size_t errmsg_len) {
    VkFormat vk_format;
    size_t texel;
    if (ctx == NULL || width <= 0 || height <= 0 || format_info(format, &vk_format, &texel) != 0) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "bad image spec");
        return 0;
    }
    VkFormatProperties props;
    vkGetPhysicalDeviceFormatProperties(ctx->physical, vk_format, &props);
    const VkFormatFeatureFlags need =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    if ((props.optimalTilingFeatures & need) != need) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "format %d lacks sampled+storage support on this device", format);
        return 0;
    }
    SrvkImage* img = (SrvkImage*)calloc(1, sizeof(SrvkImage));
    if (img == NULL) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "out of memory");
        return 0;
    }
    img->width = width;
    img->height = height;
    img->format = format;
    img->vk_format = vk_format;
    img->bytes = (size_t)width * (size_t)height * texel;
    img->layout = VK_IMAGE_LAYOUT_UNDEFINED;

    VkImageCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = vk_format;
    ci.extent.width = (uint32_t)width;
    ci.extent.height = (uint32_t)height;
    ci.extent.depth = 1;
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT |
               VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult res = vkCreateImage(ctx->device, &ci, NULL, &img->image);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkCreateImage failed", res);
        free(img);
        return 0;
    }
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(ctx->device, img->image, &req);
    uint32_t type = ctx->device_local_type;
    if (!(req.memoryTypeBits & (1u << type))) {
        // Unusual drivers may restrict optimal images; fall back to any fit.
        if (memory_type(ctx, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &type) != 0 &&
            memory_type(ctx, req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, &type) != 0) {
            if (errmsg != NULL && errmsg_len > 0)
                snprintf(errmsg, errmsg_len, "no memory type for image");
            vkDestroyImage(ctx->device, img->image, NULL);
            free(img);
            return 0;
        }
    }
    VkMemoryAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    res = vkAllocateMemory(ctx->device, &ai, NULL, &img->memory);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "image memory alloc failed", res);
        vkDestroyImage(ctx->device, img->image, NULL);
        free(img);
        return 0;
    }
    res = vkBindImageMemory(ctx->device, img->image, img->memory, 0);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkBindImageMemory failed", res);
        vkFreeMemory(ctx->device, img->memory, NULL);
        vkDestroyImage(ctx->device, img->image, NULL);
        free(img);
        return 0;
    }
    VkImageViewCreateInfo vi = {};
    vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vi.image = img->image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = vk_format;
    vi.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    vi.subresourceRange.levelCount = 1;
    vi.subresourceRange.layerCount = 1;
    res = vkCreateImageView(ctx->device, &vi, NULL, &img->view);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkCreateImageView failed", res);
        vkFreeMemory(ctx->device, img->memory, NULL);
        vkDestroyImage(ctx->device, img->image, NULL);
        free(img);
        return 0;
    }
    return (uint64_t)(uintptr_t)img;
}

static int submit_oneshot(SrvkContext* ctx, VkCommandBuffer cmd, char* errmsg, size_t errmsg_len,
                          const char* what) {
    VkResult res = vkEndCommandBuffer(cmd);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, what, res);
        return -1;
    }
    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fi = {};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    res = vkCreateFence(ctx->device, &fi, NULL, &fence);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "fence alloc failed", res);
        return -1;
    }
    VkSubmitInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    res = vkQueueSubmit(ctx->queue, 1, &si, fence);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkQueueSubmit failed", res);
        vkDestroyFence(ctx->device, fence, NULL);
        return -1;
    }
    res = vkWaitForFences(ctx->device, 1, &fence, VK_TRUE, SRVK_FENCE_TIMEOUT_NS);
    // A timed-out fence may still be in flight: destroying it would be
    // undefined, so only destroy on a definitive outcome (same fail-the-merge
    // contract as the GL fence timeout; the context tears down after).
    if (res == VK_SUCCESS) {
        vkDestroyFence(ctx->device, fence, NULL);
        return 0;
    }
    if (errmsg != NULL && errmsg_len > 0)
        snprintf(errmsg, errmsg_len, "%s: GPU did not complete (VkResult %d)", what, (int)res);
    return -1;
}

static int alloc_cmd(SrvkContext* ctx, VkCommandBuffer* cmd, char* errmsg, size_t errmsg_len) {
    VkCommandBufferAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool = ctx->cmd_pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    VkResult res = vkAllocateCommandBuffers(ctx->device, &ai, cmd);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "command buffer alloc failed", res);
        return -1;
    }
    VkCommandBufferBeginInfo bi = {};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    res = vkBeginCommandBuffer(*cmd, &bi);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "vkBeginCommandBuffer failed", res);
        vkFreeCommandBuffers(ctx->device, ctx->cmd_pool, 1, cmd);
        return -1;
    }
    return 0;
}

static void transition(VkCommandBuffer cmd, VkImage image, VkImageLayout old_layout,
                       VkImageLayout new_layout) {
    VkAccessFlags src_access = 0;
    VkAccessFlags dst_access = 0;
    VkPipelineStageFlags src_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkPipelineStageFlags dst_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    if (old_layout == VK_IMAGE_LAYOUT_UNDEFINED && new_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        dst_access = VK_ACCESS_TRANSFER_WRITE_BIT;
        dst_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (old_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL &&
               new_layout == VK_IMAGE_LAYOUT_GENERAL) {
        src_access = VK_ACCESS_TRANSFER_WRITE_BIT;
        src_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dst_access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        dst_stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    } else if (old_layout == VK_IMAGE_LAYOUT_GENERAL &&
               new_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        src_access = VK_ACCESS_SHADER_WRITE_BIT;
        src_stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        dst_access = VK_ACCESS_TRANSFER_WRITE_BIT;
        dst_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (old_layout == VK_IMAGE_LAYOUT_GENERAL &&
               new_layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
        src_access = VK_ACCESS_SHADER_WRITE_BIT;
        src_stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        dst_access = VK_ACCESS_TRANSFER_READ_BIT;
        dst_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if ((old_layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ||
                old_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) &&
               new_layout == VK_IMAGE_LAYOUT_GENERAL) {
        src_access = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        src_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        dst_access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        dst_stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    }
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = src_access;
    barrier.dstAccessMask = dst_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, src_stage, dst_stage, 0, 0, NULL, 0, NULL, 1, &barrier);
}

static int ensure_staging(SrvkContext* ctx, size_t bytes, char* errmsg, size_t errmsg_len) {
    if (ctx->staging_size >= bytes && ctx->staging != VK_NULL_HANDLE) return 0;
    if (ctx->staging != VK_NULL_HANDLE) {
        if (ctx->staging_mapped != NULL) vkUnmapMemory(ctx->device, ctx->staging_memory);
        vkDestroyBuffer(ctx->device, ctx->staging, NULL);
        vkFreeMemory(ctx->device, ctx->staging_memory, NULL);
        ctx->staging = VK_NULL_HANDLE;
        ctx->staging_memory = VK_NULL_HANDLE;
        ctx->staging_mapped = NULL;
        ctx->staging_size = 0;
    }
    VkBufferCreateInfo ci = {};
    ci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    ci.size = bytes;
    ci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult res = vkCreateBuffer(ctx->device, &ci, NULL, &ctx->staging);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "staging buffer failed", res);
        return -1;
    }
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(ctx->device, ctx->staging, &req);
    uint32_t type = ctx->host_coherent_type;
    if (!(req.memoryTypeBits & (1u << type))) {
        if (memory_type(ctx, req.memoryTypeBits,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        &type) != 0) {
            if (errmsg != NULL && errmsg_len > 0)
                snprintf(errmsg, errmsg_len, "no host-coherent memory for staging");
            vkDestroyBuffer(ctx->device, ctx->staging, NULL);
            ctx->staging = VK_NULL_HANDLE;
            return -1;
        }
    }
    VkMemoryAllocateInfo ai = {};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    res = vkAllocateMemory(ctx->device, &ai, NULL, &ctx->staging_memory);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "staging memory failed", res);
        vkDestroyBuffer(ctx->device, ctx->staging, NULL);
        ctx->staging = VK_NULL_HANDLE;
        return -1;
    }
    res = vkBindBufferMemory(ctx->device, ctx->staging, ctx->staging_memory, 0);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "staging bind failed", res);
        vkFreeMemory(ctx->device, ctx->staging_memory, NULL);
        vkDestroyBuffer(ctx->device, ctx->staging, NULL);
        ctx->staging = VK_NULL_HANDLE;
        ctx->staging_memory = VK_NULL_HANDLE;
        return -1;
    }
    res = vkMapMemory(ctx->device, ctx->staging_memory, 0, req.size, 0, &ctx->staging_mapped);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "staging map failed", res);
        vkFreeMemory(ctx->device, ctx->staging_memory, NULL);
        vkDestroyBuffer(ctx->device, ctx->staging, NULL);
        ctx->staging = VK_NULL_HANDLE;
        ctx->staging_memory = VK_NULL_HANDLE;
        return -1;
    }
    ctx->staging_size = bytes;
    return 0;
}

int srvk_write_image(SrvkContext* ctx, uint64_t handle, const void* bytes, size_t len,
                     char* errmsg, size_t errmsg_len) {
    SrvkImage* img = (SrvkImage*)(uintptr_t)handle;
    if (ctx == NULL || img == NULL || bytes == NULL || len != img->bytes) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "write_image: size mismatch");
        return -1;
    }
    if (ensure_staging(ctx, len, errmsg, errmsg_len) != 0) return -1;
    memcpy(ctx->staging_mapped, bytes, len);
    VkCommandBuffer cmd;
    if (alloc_cmd(ctx, &cmd, errmsg, errmsg_len) != 0) return -1;
    transition(cmd, img->image, img->layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkBufferImageCopy copy = {};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent.width = (uint32_t)img->width;
    copy.imageExtent.height = (uint32_t)img->height;
    copy.imageExtent.depth = 1;
    vkCmdCopyBufferToImage(cmd, ctx->staging, img->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                           1, &copy);
    transition(cmd, img->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
    int rc = submit_oneshot(ctx, cmd, errmsg, errmsg_len, "write_image");
    vkFreeCommandBuffers(ctx->device, ctx->cmd_pool, 1, &cmd);
    if (rc == 0) img->layout = VK_IMAGE_LAYOUT_GENERAL;
    return rc;
}

int srvk_read_image(SrvkContext* ctx, uint64_t handle, void* out, size_t len,
                    char* errmsg, size_t errmsg_len) {
    SrvkImage* img = (SrvkImage*)(uintptr_t)handle;
    if (ctx == NULL || img == NULL || out == NULL || len != img->bytes) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "read_image: size mismatch");
        return -1;
    }
    return srvk_read_image_region(ctx, handle, 0, 0, img->width, img->height,
                                  out, len, errmsg, errmsg_len);
}

int srvk_read_image_region(SrvkContext* ctx, uint64_t handle, int x, int y, int w, int h,
                           void* out, size_t len, char* errmsg, size_t errmsg_len) {
    SrvkImage* img = (SrvkImage*)(uintptr_t)handle;
    if (ctx == NULL || img == NULL || out == NULL) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "read_image_region: null argument");
        return -1;
    }
    if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > img->width || y + h > img->height) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "read_image_region: region outside %dx%d",
                     img->width, img->height);
        return -1;
    }
    size_t texel = (size_t)img->bytes / ((size_t)img->width * (size_t)img->height);
    if (len != (size_t)w * (size_t)h * texel) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "read_image_region: size mismatch");
        return -1;
    }
    if (ensure_staging(ctx, len, errmsg, errmsg_len) != 0) return -1;
    VkCommandBuffer cmd;
    if (alloc_cmd(ctx, &cmd, errmsg, errmsg_len) != 0) return -1;
    transition(cmd, img->image, img->layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    VkBufferImageCopy copy = {};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageOffset.x = x;
    copy.imageOffset.y = y;
    copy.imageExtent.width = (uint32_t)w;
    copy.imageExtent.height = (uint32_t)h;
    copy.imageExtent.depth = 1;
    vkCmdCopyImageToBuffer(cmd, img->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, ctx->staging,
                           1, &copy);
    transition(cmd, img->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
    int rc = submit_oneshot(ctx, cmd, errmsg, errmsg_len, "read_image_region");
    vkFreeCommandBuffers(ctx->device, ctx->cmd_pool, 1, &cmd);
    if (rc == 0) {
        img->layout = VK_IMAGE_LAYOUT_GENERAL;
        memcpy(out, ctx->staging_mapped, len);
    }
    return rc;
}

void srvk_destroy_image(SrvkContext* ctx, uint64_t handle) {
    SrvkImage* img = (SrvkImage*)(uintptr_t)handle;
    if (ctx == NULL || img == NULL) return;
    // Images are idle here: every submit that touched them fence-waited.
    if (img->view != VK_NULL_HANDLE) vkDestroyImageView(ctx->device, img->view, NULL);
    if (img->image != VK_NULL_HANDLE) vkDestroyImage(ctx->device, img->image, NULL);
    if (img->memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, img->memory, NULL);
    free(img);
}

uint64_t srvk_create_pipeline(SrvkContext* ctx, uint64_t module, int ubo_binding, size_t ubo_size,
                              int image_count, const int* image_bindings, const int* image_kinds,
                              char* errmsg, size_t errmsg_len) {
    if (ctx == NULL || module == 0 || image_count < 0 || ubo_size == 0) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "bad pipeline spec");
        return 0;
    }
    SrvkPipeline* pipe = (SrvkPipeline*)calloc(1, sizeof(SrvkPipeline));
    if (pipe == NULL) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "out of memory");
        return 0;
    }
    pipe->ubo_size = ubo_size;
    for (int i = 0; i < 40; i++) pipe->kinds[i] = -1;
    for (int i = 0; i < image_count; i++) {
        int b = image_bindings[i];
        if (b < 0 || b >= 40) {
            if (errmsg != NULL && errmsg_len > 0)
                snprintf(errmsg, errmsg_len, "binding %d out of range", b);
            free(pipe);
            return 0;
        }
        pipe->kinds[b] = image_kinds[i];
    }
    int total = image_count + 1;
    VkDescriptorSetLayoutBinding* bindings =
        (VkDescriptorSetLayoutBinding*)calloc((size_t)total, sizeof(VkDescriptorSetLayoutBinding));
    if (bindings == NULL) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "out of memory");
        free(pipe);
        return 0;
    }
    for (int i = 0; i < image_count; i++) {
        bindings[i].binding = (uint32_t)image_bindings[i];
        bindings[i].descriptorType = image_kinds[i] == SRVK_STORAGE
                                         ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                         : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[image_count].binding = (uint32_t)ubo_binding;
    bindings[image_count].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[image_count].descriptorCount = 1;
    bindings[image_count].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayout layout = VK_NULL_HANDLE;
    VkPipelineLayout pipe_layout = VK_NULL_HANDLE;
    VkResult res;
    do {
        VkDescriptorSetLayoutCreateInfo li = {};
        li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        li.bindingCount = (uint32_t)total;
        li.pBindings = bindings;
        res = vkCreateDescriptorSetLayout(ctx->device, &li, NULL, &layout);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "descriptor layout failed", res);
            break;
        }
        VkPipelineLayoutCreateInfo pi = {};
        pi.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        pi.setLayoutCount = 1;
        pi.pSetLayouts = &layout;
        res = vkCreatePipelineLayout(ctx->device, &pi, NULL, &pipe_layout);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "pipeline layout failed", res);
            break;
        }
        VkDescriptorSetAllocateInfo ai = {};
        ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        ai.descriptorPool = ctx->desc_pool;
        ai.descriptorSetCount = 1;
        ai.pSetLayouts = &layout;
        res = vkAllocateDescriptorSets(ctx->device, &ai, &pipe->set);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "descriptor set alloc failed", res);
            break;
        }
        VkComputePipelineCreateInfo ci = {};
        ci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        ci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        ci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        ci.stage.module = (VkShaderModule)(uintptr_t)module;
        ci.stage.pName = "main";
        ci.layout = pipe_layout;
        res = vkCreateComputePipelines(ctx->device, VK_NULL_HANDLE, 1, &ci, NULL, &pipe->pipeline);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "vkCreateComputePipelines failed", res);
            break;
        }
        VkBufferCreateInfo bi = {};
        bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bi.size = ubo_size;
        bi.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        res = vkCreateBuffer(ctx->device, &bi, NULL, &pipe->ubo);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "UBO create failed", res);
            break;
        }
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(ctx->device, pipe->ubo, &req);
        uint32_t type = ctx->host_coherent_type;
        if (!(req.memoryTypeBits & (1u << type))) {
            if (memory_type(ctx, req.memoryTypeBits,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                            &type) != 0) {
                if (errmsg != NULL && errmsg_len > 0)
                    snprintf(errmsg, errmsg_len, "no host-coherent memory for UBO");
                res = VK_ERROR_OUT_OF_DEVICE_MEMORY;
                break;
            }
        }
        VkMemoryAllocateInfo mi = {};
        mi.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        mi.allocationSize = req.size;
        mi.memoryTypeIndex = type;
        res = vkAllocateMemory(ctx->device, &mi, NULL, &pipe->ubo_memory);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "UBO alloc failed", res);
            break;
        }
        res = vkBindBufferMemory(ctx->device, pipe->ubo, pipe->ubo_memory, 0);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "UBO bind failed", res);
            break;
        }
        res = vkMapMemory(ctx->device, pipe->ubo_memory, 0, req.size, 0, &pipe->ubo_mapped);
        if (res != VK_SUCCESS) {
            srvk_set_err(errmsg, errmsg_len, "UBO map failed", res);
            break;
        }
        VkDescriptorBufferInfo buf_info = {};
        buf_info.buffer = pipe->ubo;
        buf_info.range = ubo_size;
        VkWriteDescriptorSet write = {};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = pipe->set;
        write.dstBinding = (uint32_t)ubo_binding;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        write.pBufferInfo = &buf_info;
        vkUpdateDescriptorSets(ctx->device, 1, &write, 0, NULL);
    } while (0);

    free(bindings);
    if (res != VK_SUCCESS) {
        if (pipe->ubo_mapped != NULL) vkUnmapMemory(ctx->device, pipe->ubo_memory);
        if (pipe->ubo != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, pipe->ubo, NULL);
        if (pipe->ubo_memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, pipe->ubo_memory, NULL);
        if (pipe->pipeline != VK_NULL_HANDLE)
            vkDestroyPipeline(ctx->device, pipe->pipeline, NULL);
        if (pipe->set != VK_NULL_HANDLE)
            vkFreeDescriptorSets(ctx->device, ctx->desc_pool, 1, &pipe->set);
        if (pipe_layout != VK_NULL_HANDLE)
            vkDestroyPipelineLayout(ctx->device, pipe_layout, NULL);
        if (layout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(ctx->device, layout, NULL);
        free(pipe);
        return 0;
    }
    pipe->pipe_layout = pipe_layout;
    pipe->set_layout = layout;
    return (uint64_t)(uintptr_t)pipe;
}

int srvk_bind_image(SrvkContext* ctx, uint64_t pipe_handle, int binding, uint64_t img_handle,
                    char* errmsg, size_t errmsg_len) {
    SrvkPipeline* pipe = (SrvkPipeline*)(uintptr_t)pipe_handle;
    SrvkImage* img = (SrvkImage*)(uintptr_t)img_handle;
    if (ctx == NULL || pipe == NULL || img == NULL || binding < 0) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "bad bind_image args");
        return -1;
    }
    if (binding >= 40 || pipe->kinds[binding] < 0) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "binding %d is not an image binding", binding);
        return -1;
    }
    VkDescriptorImageInfo info = {};
    info.imageView = img->view;
    info.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    info.sampler = ctx->sampler;
    VkWriteDescriptorSet write = {};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = pipe->set;
    write.dstBinding = (uint32_t)binding;
    write.descriptorCount = 1;
    write.descriptorType = pipe->kinds[binding] == SRVK_STORAGE
                               ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                               : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    write.pImageInfo = &info;
    vkUpdateDescriptorSets(ctx->device, 1, &write, 0, NULL);
    return 0;
}

int srvk_write_uniforms(SrvkContext* ctx, uint64_t pipe_handle, const void* bytes, size_t len,
                        char* errmsg, size_t errmsg_len) {
    SrvkPipeline* pipe = (SrvkPipeline*)(uintptr_t)pipe_handle;
    if (ctx == NULL || pipe == NULL || bytes == NULL || len != pipe->ubo_size) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "write_uniforms: size mismatch");
        return -1;
    }
    memcpy(pipe->ubo_mapped, bytes, len);
    return 0;
}

int srvk_dispatch(SrvkContext* ctx, uint64_t pipe_handle, int gx, int gy, int gz,
                  char* errmsg, size_t errmsg_len) {
    SrvkPipeline* pipe = (SrvkPipeline*)(uintptr_t)pipe_handle;
    if (ctx == NULL || pipe == NULL || gx <= 0 || gy <= 0 || gz <= 0) {
        if (errmsg != NULL && errmsg_len > 0) snprintf(errmsg, errmsg_len, "bad dispatch args");
        return -1;
    }
    VkCommandBuffer cmd;
    if (alloc_cmd(ctx, &cmd, errmsg, errmsg_len) != 0) return -1;
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe->pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe->pipe_layout, 0, 1,
                            &pipe->set, 0, NULL);
    vkCmdDispatch(cmd, (uint32_t)gx, (uint32_t)gy, (uint32_t)gz);
    // Mirror the GL shader-image + texture-fetch barrier: orders this
    // dispatch against any later command in submission order.
    VkMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 1, &barrier, 0, NULL, 0, NULL);
    int rc = submit_oneshot(ctx, cmd, errmsg, errmsg_len, "dispatch");
    vkFreeCommandBuffers(ctx->device, ctx->cmd_pool, 1, &cmd);
    return rc;
}

void srvk_destroy_pipeline(SrvkContext* ctx, uint64_t pipe_handle) {
    SrvkPipeline* pipe = (SrvkPipeline*)(uintptr_t)pipe_handle;
    if (ctx == NULL || pipe == NULL) return;
    if (pipe->ubo_mapped != NULL) vkUnmapMemory(ctx->device, pipe->ubo_memory);
    if (pipe->ubo != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, pipe->ubo, NULL);
    if (pipe->ubo_memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, pipe->ubo_memory, NULL);
    if (pipe->pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, pipe->pipeline, NULL);
    if (pipe->set != VK_NULL_HANDLE) vkFreeDescriptorSets(ctx->device, ctx->desc_pool, 1, &pipe->set);
    if (pipe->pipe_layout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, pipe->pipe_layout, NULL);
    if (pipe->set_layout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, pipe->set_layout, NULL);
    free(pipe);
}

int srvk_resources_init(SrvkContext* ctx, char* errmsg, size_t errmsg_len) {
    VkPhysicalDeviceMemoryProperties mem;
    vkGetPhysicalDeviceMemoryProperties(ctx->physical, &mem);
    uint32_t all = (mem.memoryTypeCount >= 32) ? 0xFFFFFFFFu : ((1u << mem.memoryTypeCount) - 1);
    if (memory_type(ctx, all,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    &ctx->host_coherent_type) != 0) {
        if (errmsg != NULL && errmsg_len > 0)
            snprintf(errmsg, errmsg_len, "no host-coherent memory type");
        return -1;
    }
    if (memory_type(ctx, all, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &ctx->device_local_type) != 0) {
        ctx->device_local_type = ctx->host_coherent_type;
    }
    VkSamplerCreateInfo si = {};
    si.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    si.magFilter = VK_FILTER_NEAREST;
    si.minFilter = VK_FILTER_NEAREST;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    si.maxLod = 0.0f;
    VkResult res = vkCreateSampler(ctx->device, &si, NULL, &ctx->sampler);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "sampler create failed", res);
        return -1;
    }
    VkCommandPoolCreateInfo pi = {};
    pi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pi.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pi.queueFamilyIndex = ctx->queue_family;
    res = vkCreateCommandPool(ctx->device, &pi, NULL, &ctx->cmd_pool);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "command pool failed", res);
        return -1;
    }
    VkDescriptorPoolSize sizes[3] = {};
    sizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    sizes[0].descriptorCount = 512;
    sizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    sizes[1].descriptorCount = 512;
    sizes[2].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    sizes[2].descriptorCount = 64;
    VkDescriptorPoolCreateInfo di = {};
    di.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    di.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    di.maxSets = 64;
    di.poolSizeCount = 3;
    di.pPoolSizes = sizes;
    res = vkCreateDescriptorPool(ctx->device, &di, NULL, &ctx->desc_pool);
    if (res != VK_SUCCESS) {
        srvk_set_err(errmsg, errmsg_len, "descriptor pool failed", res);
        return -1;
    }
    return 0;
}

void srvk_resources_teardown(SrvkContext* ctx) {
    if (ctx == NULL) return;
    if (ctx->staging_mapped != NULL) vkUnmapMemory(ctx->device, ctx->staging_memory);
    if (ctx->staging != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, ctx->staging, NULL);
    if (ctx->staging_memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, ctx->staging_memory, NULL);
    if (ctx->desc_pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx->device, ctx->desc_pool, NULL);
    if (ctx->cmd_pool != VK_NULL_HANDLE) vkDestroyCommandPool(ctx->device, ctx->cmd_pool, NULL);
    if (ctx->sampler != VK_NULL_HANDLE) vkDestroySampler(ctx->device, ctx->sampler, NULL);
}
