// SPDX-License-Identifier: GPL-3.0-or-later
// Resources for the separately retained Packed-ratio packed-ratio variant.
static void destroyPackedRcdImages(Context* ctx) {
    auto& s = ctx->packedRcd;
    for (int i = 0; i < 3; ++i) {
        if (s.views[i]) vkDestroyImageView(ctx->device, s.views[i], nullptr);
        if (s.images[i]) vkDestroyImage(ctx->device, s.images[i], nullptr);
        if (s.memory[i]) vkFreeMemory(ctx->device, s.memory[i], nullptr);
        s.views[i] = VK_NULL_HANDLE; s.images[i] = VK_NULL_HANDLE; s.memory[i] = VK_NULL_HANDLE;
    }
    s.width = s.height = 0;
    s.initialized = false;
}

static void destroyPackedRcd(Context* ctx) {
    auto& s = ctx->packedRcd;
    destroyPackedRcdImages(ctx);
    for (auto pipeline : s.pipelines) if (pipeline) vkDestroyPipeline(ctx->device, pipeline, nullptr);
    if (s.pool) vkDestroyDescriptorPool(ctx->device, s.pool, nullptr);
    if (s.layout) vkDestroyPipelineLayout(ctx->device, s.layout, nullptr);
    if (s.setLayout) vkDestroyDescriptorSetLayout(ctx->device, s.setLayout, nullptr);
    if (s.sampler) vkDestroySampler(ctx->device, s.sampler, nullptr);
    if (s.queries) vkDestroyQueryPool(ctx->device, s.queries, nullptr);
    s = PackedRcdState{};
}

static bool ensurePackedRcdImages(Context* ctx, int width, int height) {
    auto& s = ctx->packedRcd;
    if (s.width == width && s.height == height) return true;
    destroyPackedRcdImages(ctx);
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memoryProperties);
    for (int i = 0; i < 3; ++i) {
        const VkFormat format = i == 0 ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R16G16_SFLOAT;
        VkImageCreateInfo image{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        image.imageType = VK_IMAGE_TYPE_2D;
        image.format = format;
        image.extent = {(uint32_t)(width + 1) / 2, (uint32_t)(height + 1) / 2, 1};
        image.mipLevels = image.arrayLayers = 1;
        image.samples = VK_SAMPLE_COUNT_1_BIT;
        image.tiling = VK_IMAGE_TILING_OPTIMAL;
        image.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        image.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateImage(ctx->device, &image, nullptr, &s.images[i]) != VK_SUCCESS) {
            destroyPackedRcdImages(ctx); return false;
        }
        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(ctx->device, s.images[i], &requirements);
        uint32_t type = pickMemoryTypeWithProps(requirements.memoryTypeBits, memoryProperties,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type == UINT32_MAX) type = pickMemoryType(requirements.memoryTypeBits, memoryProperties.memoryTypeCount);
        if (type == UINT32_MAX) { destroyPackedRcdImages(ctx); return false; }
        VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = type;
        if (vkAllocateMemory(ctx->device, &allocation, nullptr, &s.memory[i]) != VK_SUCCESS ||
            vkBindImageMemory(ctx->device, s.images[i], s.memory[i], 0) != VK_SUCCESS) {
            destroyPackedRcdImages(ctx); return false;
        }
        VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        view.image = s.images[i]; view.viewType = VK_IMAGE_VIEW_TYPE_2D; view.format = format;
        view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vkCreateImageView(ctx->device, &view, nullptr, &s.views[i]) != VK_SUCCESS) {
            destroyPackedRcdImages(ctx); return false;
        }
    }
    s.width = width; s.height = height;
    return true;
}

static bool createPackedRcd(Context* ctx, const std::vector<std::vector<uint32_t>>& code, int localX, int localY) {
    auto& s = ctx->packedRcd;
    s.localX = localX; s.localY = localY;
    VkDescriptorSetLayoutBinding bindings[7]{};
    const uint32_t ids[7] = {0, 2, 6, 7, 8, 9, 10};
    for (int i = 0; i < 7; ++i) {
        bindings[i].binding = ids[i]; bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        bindings[i].descriptorType = (i == 0 || i >= 5) ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER :
            i == 1 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }
    VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    setInfo.bindingCount = 7; setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &s.setLayout) != VK_SUCCESS) return false;
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(RcdParams)};
    VkPipelineLayoutCreateInfo layout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layout.setLayoutCount = 1; layout.pSetLayouts = &s.setLayout;
    layout.pushConstantRangeCount = 1; layout.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &layout, nullptr, &s.layout) != VK_SUCCESS) return false;
    const uint32_t local[2] = {(uint32_t)localX, (uint32_t)localY};
    const VkSpecializationMapEntry entries[2] = {{12, 0, 4}, {13, 4, 4}};
    VkSpecializationInfo specialization{2, entries, sizeof(local), local};
    for (int i = 0; i < 4; ++i) {
        VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        moduleInfo.codeSize = code[i].size() * 4; moduleInfo.pCode = code[i].data();
        VkShaderModule module = VK_NULL_HANDLE;
        if (vkCreateShaderModule(ctx->device, &moduleInfo, nullptr, &module) != VK_SUCCESS) return false;
        VkComputePipelineCreateInfo pipeline{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pipeline.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipeline.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipeline.stage.module = module; pipeline.stage.pName = "main";
        pipeline.stage.pSpecializationInfo = &specialization; pipeline.layout = s.layout;
        VkResult result = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeline, nullptr, &s.pipelines[i]);
        vkDestroyShaderModule(ctx->device, module, nullptr);
        if (result != VK_SUCCESS) return false;
    }
    VkDescriptorPoolSize sizes[3] = {{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 12},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 4}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 12}};
    VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool.maxSets = 4; pool.poolSizeCount = 3; pool.pPoolSizes = sizes;
    if (vkCreateDescriptorPool(ctx->device, &pool, nullptr, &s.pool) != VK_SUCCESS) return false;
    VkDescriptorSetLayout layouts[4] = {s.setLayout, s.setLayout, s.setLayout, s.setLayout};
    VkDescriptorSetAllocateInfo allocate{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocate.descriptorPool = s.pool; allocate.descriptorSetCount = 4; allocate.pSetLayouts = layouts;
    if (vkAllocateDescriptorSets(ctx->device, &allocate, s.sets) != VK_SUCCESS) return false;
    VkSamplerCreateInfo sampler{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sampler.magFilter = sampler.minFilter = VK_FILTER_NEAREST;
    sampler.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sampler.addressModeU = sampler.addressModeV = sampler.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(ctx->device, &sampler, nullptr, &s.sampler) != VK_SUCCESS) return false;
    uint32_t queueCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->gpu, &queueCount, nullptr);
    std::vector<VkQueueFamilyProperties> queues(queueCount);
    vkGetPhysicalDeviceQueueFamilyProperties(ctx->gpu, &queueCount, queues.data());
    s.timestampBits = queues[ctx->queueFamily].timestampValidBits;
    if (s.timestampBits) {
        VkQueryPoolCreateInfo query{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
        query.queryType = VK_QUERY_TYPE_TIMESTAMP; query.queryCount = 8;
        if (vkCreateQueryPool(ctx->device, &query, nullptr, &s.queries) != VK_SUCCESS) s.queries = VK_NULL_HANDLE;
    }
    if (!ctx->rcdYuvParamMaps[0] && !createRcdYuvParams(ctx)) return false;
    savePipelineCache(ctx);
    return true;
}
