// SPDX-License-Identifier: GPL-3.0-or-later
// Take-constant resources, allocated/uploaded before camera streaming.
// Vulkan spec: image creation, buffer-to-image copy, shader-read barriers,
// descriptor-set compatibility and normalized 3D linear filtering.
// https://docs.vulkan.org/spec/latest/chapters/textures.html
static void destroyRecordCube(Context* ctx, bool keepLayout = false) {
    auto& c = ctx->recordCube;
    if (c.pool) vkDestroyDescriptorPool(ctx->device, c.pool, nullptr);
    if (c.sampler) vkDestroySampler(ctx->device, c.sampler, nullptr);
    if (c.view) vkDestroyImageView(ctx->device, c.view, nullptr);
    if (c.image) vkDestroyImage(ctx->device, c.image, nullptr);
    if (c.memory) vkFreeMemory(ctx->device, c.memory, nullptr);
    if (c.params) vkDestroyBuffer(ctx->device, c.params, nullptr);
    if (c.paramsMemory) vkFreeMemory(ctx->device, c.paramsMemory, nullptr);
    auto layout = c.layout;
    if (!keepLayout && layout) vkDestroyDescriptorSetLayout(ctx->device, layout, nullptr);
    c = RecordCubeState{};
    if (keepLayout) c.layout = layout;
}

static bool ensureRecordCubeLayout(Context* ctx) {
    if (ctx->recordCube.layout) return true;
    VkDescriptorSetLayoutBinding b[2]{};
    b[0] = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    b[1] = {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo info{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    info.bindingCount = 2; info.pBindings = b;
    return vkCreateDescriptorSetLayout(ctx->device, &info, nullptr, &ctx->recordCube.layout) == VK_SUCCESS;
}

static void bindRecordCube(Context* ctx, VkCommandBuffer cmd, VkPipelineLayout layout) {
    if (ctx->recordCube.enabled)
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 1, 1,
            &ctx->recordCube.set, 0, nullptr);
}

static bool cubeHostBuffer(Context* ctx, VkDeviceSize bytes, VkBufferUsageFlags usage,
                           const void* data, VkBuffer* buffer, VkDeviceMemory* memory) {
    VkBufferCreateInfo b{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    b.size = bytes; b.usage = usage; b.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(ctx->device, &b, nullptr, buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements r{};
    vkGetBufferMemoryRequirements(ctx->device, *buffer, &r);
    VkPhysicalDeviceMemoryProperties properties{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &properties);
    auto type = pickMemoryTypeWithProps(r.memoryTypeBits, properties,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo a{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    a.allocationSize = r.size; a.memoryTypeIndex = type;
    if (vkAllocateMemory(ctx->device, &a, nullptr, memory) != VK_SUCCESS ||
        vkBindBufferMemory(ctx->device, *buffer, *memory, 0) != VK_SUCCESS) return false;
    void* mapped = nullptr;
    if (vkMapMemory(ctx->device, *memory, 0, bytes, 0, &mapped) != VK_SUCCESS) return false;
    memcpy(mapped, data, (size_t)bytes);
    vkUnmapMemory(ctx->device, *memory);
    return true;
}

static int uploadRecordCube(Context* ctx, const uint16_t* rgba, int size, const float control[12]) {
    if (vkDeviceWaitIdle(ctx->device) != VK_SUCCESS) return VFVK_DEVICE_FAILED;
    destroyRecordCube(ctx, true);
    if (!rgba) return VFVK_OK;
    auto& c = ctx->recordCube;
    if (!ensureRecordCubeLayout(ctx)) return VFVK_PIPELINE_FAILED;
    VkFormatProperties format{};
    vkGetPhysicalDeviceFormatProperties(ctx->gpu, VK_FORMAT_R16G16B16A16_SFLOAT, &format);
    auto features = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((format.optimalTilingFeatures & features) != features) return VFVK_BAD_ARGUMENT;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    int result = VFVK_SUBMIT_FAILED;
    do {
        VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        image.imageType = VK_IMAGE_TYPE_3D; image.format = VK_FORMAT_R16G16B16A16_SFLOAT;
        image.extent = {(uint32_t)size, (uint32_t)size, (uint32_t)size};
        image.mipLevels = image.arrayLayers = 1; image.samples = VK_SAMPLE_COUNT_1_BIT;
        image.tiling = VK_IMAGE_TILING_OPTIMAL;
        image.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateImage(ctx->device, &image, nullptr, &c.image) != VK_SUCCESS) break;
        VkMemoryRequirements r{};
        vkGetImageMemoryRequirements(ctx->device, c.image, &r);
        VkPhysicalDeviceMemoryProperties properties{};
        vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &properties);
        auto type = pickMemoryTypeWithProps(r.memoryTypeBits, properties, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type == UINT32_MAX) break;
        VkMemoryAllocateInfo a{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        a.allocationSize = r.size; a.memoryTypeIndex = type;
        if (vkAllocateMemory(ctx->device, &a, nullptr, &c.memory) != VK_SUCCESS ||
            vkBindImageMemory(ctx->device, c.image, c.memory, 0) != VK_SUCCESS) break;
        VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        view.image = c.image; view.viewType = VK_IMAGE_VIEW_TYPE_3D; view.format = image.format;
        view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        if (vkCreateImageView(ctx->device, &view, nullptr, &c.view) != VK_SUCCESS) break;
        VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
        sampler.magFilter = sampler.minFilter = VK_FILTER_LINEAR;
        sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
        sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        if (vkCreateSampler(ctx->device, &sampler, nullptr, &c.sampler) != VK_SUCCESS) break;
        if (!cubeHostBuffer(ctx, sizeof(float)*12, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            control, &c.params, &c.paramsMemory)) break;
        if (!cubeHostBuffer(ctx, (VkDeviceSize)size*size*size*8, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            rgba, &staging, &stagingMemory)) break;
        VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        allocation.commandPool = ctx->commandPool; allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocation.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(ctx->device, &allocation, &cmd) != VK_SUCCESS) break;
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) break;
        VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        barrier.image = c.image; barrier.subresourceRange = view.subresourceRange;
        barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,0,nullptr,0,nullptr,1,&barrier);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT,0,0,1}; region.imageExtent = image.extent;
        vkCmdCopyBufferToImage(cmd,staging,c.image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&region);
        barrier.oldLayout = barrier.newLayout; barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,0,nullptr,0,nullptr,1,&barrier);
        if (vkEndCommandBuffer(cmd) != VK_SUCCESS) break;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount=1; submit.pCommandBuffers=&cmd;
        if (vkQueueSubmit(ctx->queue,1,&submit,VK_NULL_HANDLE) != VK_SUCCESS ||
            vkQueueWaitIdle(ctx->queue) != VK_SUCCESS) break;
        VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1},{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,1}};
        VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        pool.maxSets=1; pool.poolSizeCount=2; pool.pPoolSizes=sizes;
        if (vkCreateDescriptorPool(ctx->device,&pool,nullptr,&c.pool) != VK_SUCCESS) break;
        VkDescriptorSetAllocateInfo sets{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        sets.descriptorPool=c.pool; sets.descriptorSetCount=1; sets.pSetLayouts=&c.layout;
        if (vkAllocateDescriptorSets(ctx->device,&sets,&c.set) != VK_SUCCESS) break;
        VkDescriptorImageInfo ii{c.sampler,c.view,VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkDescriptorBufferInfo bi{c.params,0,sizeof(float)*12};
        VkWriteDescriptorSet writes[2]{};
        for (int i=0;i<2;++i) { writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet=c.set; writes[i].dstBinding=i; writes[i].descriptorCount=1; }
        writes[0].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; writes[0].pImageInfo=&ii;
        writes[1].descriptorType=VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; writes[1].pBufferInfo=&bi;
        vkUpdateDescriptorSets(ctx->device,2,writes,0,nullptr);
        c.enabled=true; result=VFVK_OK;
    } while (false);
    if (cmd) vkFreeCommandBuffers(ctx->device,ctx->commandPool,1,&cmd);
    if (staging) vkDestroyBuffer(ctx->device,staging,nullptr);
    if (stagingMemory) vkFreeMemory(ctx->device,stagingMemory,nullptr);
    if (result != VFVK_OK) destroyRecordCube(ctx,true);
    return result;
}
