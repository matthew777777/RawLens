// Sampled-image twin of createPipeline: binding 0 is the R16 raw sampler,
// bindings 1-3 (storage output, shading sampler, LUT) match the buffer
// pipeline so push packing and the export path are shared.
bool createImagePipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: image shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[4]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 4;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->imgSetLayout) !=
        VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(SuperpixelParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->imgSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->imgPipelineLayout) !=
        VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->imgPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo,
                                          nullptr, &ctx->imgPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: image compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_NEAREST;
    samplerInfo.minFilter = VK_FILTER_NEAREST;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(ctx->device, &samplerInfo, nullptr, &ctx->rawSampler) != VK_SUCCESS) {
        LOGW("vf-vk: image raw sampler failed");
        return false;
    }
    VkDescriptorPoolSize poolSizes[3]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = 2;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[2].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->imgPool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->imgPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->imgSetLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->imgSet) != VK_SUCCESS) {
        return false;
    }
    return true;
}

void layoutBarrier(VkCommandBuffer cmd, VkImage image,
                   VkPipelineStageFlags srcStage, VkAccessFlags srcAccess,
                   VkPipelineStageFlags dstStage, VkAccessFlags dstAccess,
                   VkImageLayout oldLayout, VkImageLayout newLayout,
                   uint32_t srcFamily, uint32_t dstFamily) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = srcFamily;
    barrier.dstQueueFamilyIndex = dstFamily;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

// Linked pipeline sharing the caller's layout (f16 superpixel variant:
// identical bindings + push block, different store format).
static bool createLinkedPipeline(Context* ctx, const uint32_t* code, size_t words, VkPipeline* out) {
    *out = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: linked shader module failed");
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->pipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, out);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: linked compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    return true;
}

bool createPipeline(Context* ctx, const uint32_t* code, size_t words) {    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[4]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 4;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->setLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(SuperpixelParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->setLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->pipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->setLayout, nullptr);
        ctx->setLayout = VK_NULL_HANDLE;  // P1: teardown must not destroy it twice
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->pipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->pipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[3]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 2;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->descriptorPool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->setLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->descriptorSet) != VK_SUCCESS) {
        return false;
    }
    return true;
}

// Phase-B grade pipeline: STORAGE_IMAGE in + STORAGE_IMAGE out (the
// superpixel stage is STORAGE_BUFFER in + STORAGE_IMAGE out, so it needs
// its own set layout). Push block is GradeParams (96 bytes).
bool createGradePipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: grade shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->gradeSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(GradeParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->gradeSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->gradePipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->gradeSetLayout, nullptr);
        ctx->gradeSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->gradePipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->gradePipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: grade compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = 1;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->gradePool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->gradePool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->gradeSetLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->gradeSet) != VK_SUCCESS) {
        return false;
    }
    return true;
}

// True-10-bit pipeline: STORAGE_IMAGE (FP16 superpixel export) in +
// STORAGE_BUFFER (P010 codec bytes) out. Own set layout; push block is
// GradeYuvParams (112 bytes).
bool createYuvPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: yuv shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 3;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->yuvSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(GradeYuvParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->yuvSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->yuvPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->yuvSetLayout, nullptr);
        ctx->yuvSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->yuvPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->yuvPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: yuv compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[3]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[1].descriptorCount = 3;
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = 3;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 3;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->yuvPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->yuvPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->yuvSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->yuvSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// Record-preview pipeline: STORAGE_BUFFER P010 in + STORAGE_IMAGE RGBA8
// out + PreviewParams push (32 bytes).
bool createPreviewPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: preview shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->previewSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(PreviewParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->previewSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->previewPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->previewSetLayout, nullptr);
        ctx->previewSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->previewPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->previewPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: preview compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 3;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->previewPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->previewPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->previewSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->previewSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// 8-bit SDR fallback pipeline: STORAGE_BUFFER P010 in + STORAGE_IMAGE
// full-res RGBA8 out + Rgb8Params push (28 bytes). Layout mirrors the
// preview pipeline (same bindings); only the push block is smaller.
bool createRgb8Pipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: rgb8 shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->rgb8SetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(Rgb8Params);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->rgb8SetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->rgb8PipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rgb8SetLayout, nullptr);
        ctx->rgb8SetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->rgb8PipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->rgb8Pipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: rgb8 compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 3;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->rgb8Pool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->rgb8Pool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->rgb8SetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->rgb8Sets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// RCD demosaic pipeline: STORAGE_BUFFER CFA in + STORAGE_IMAGE rgb
// (read-write across the 3 mode dispatches) + RcdParams push (72 bytes).
bool createRcdPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: rcd shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[3]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 3;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->rcdSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(RcdParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->rcdSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->rcdPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rcdSetLayout, nullptr);
        ctx->rcdSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->rcdPipelineLayout;
    VkSpecializationMapEntry stageEntry{0, 0, sizeof(int32_t)};
    VkSpecializationInfo specialization{};
    specialization.mapEntryCount = 1;
    specialization.pMapEntries = &stageEntry;
    specialization.dataSize = sizeof(int32_t);
    pipeInfo.stage.pSpecializationInfo = &specialization;
    VkResult r = VK_SUCCESS;
    for (int32_t mode = 0; mode < 4; ++mode) {
        specialization.pData = &mode;
        r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1,
                                    &pipeInfo, nullptr, &ctx->rcdPipelines[mode]);
        if (r != VK_SUCCESS) break;
    }
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: rcd compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 12;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 24;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 12;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->rcdPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 12; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->rcdPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->rcdSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->rcdSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// RCD dynamic command buffers: the twin discipline (one CB per slot,
// reset + re-record on every submit) is invalid when submit gaps
// (sub-ms, back-to-back modes) are shorter than exec (55ms+):
// resetting a pending CB destroyed RCD takes (device lost after a
// few frames, then PIPELINE_FAILED after the viewfinder's recovery
// teardown). RCD submits (modes + fused) instead take a CB from a
// free list (reset only after its fence signaled, so never pending)
// or allocate, submit it WITH a fence, and retire (fence, CB) pairs
// when signaled. Capped: past the cap the submit returns BUSY and
// the caller drops the frame (VfVulkan.BUSY precedent) — it never
// blocks the camera thread and never resets a pending CB.
#define RCD_INFLIGHT_CAP 16
// A4: true while a pending RCD submit still references [set] (the
// per-slot descriptor set must not be rewritten under it).
static bool rcdSetInUse(Context* ctx, VkDescriptorSet set) {
    for (const auto& p : ctx->rcdPending) {
        // Same short grace as slotIdle (fd and fence are not ordered).
        if (p.set == set &&
            vkWaitForFences(ctx->device, 1, &p.fence, VK_TRUE, 2000000ull) == VK_TIMEOUT) return true;
    }
    return false;
}

static int acquireRcdCmd(Context* ctx, VkCommandBuffer* cmd, VkFence* fence) {
    for (size_t i = 0; i < ctx->rcdPending.size();) {
        VkResult st = vkGetFenceStatus(ctx->device, ctx->rcdPending[i].fence);
        if (st == VK_SUCCESS) {
            vkDestroyFence(ctx->device, ctx->rcdPending[i].fence, nullptr);
            if (ctx->rcdPending[i].sem != VK_NULL_HANDLE)
                vkDestroySemaphore(ctx->device, ctx->rcdPending[i].sem, nullptr);
            ctx->rcdFreeCbs.push_back(ctx->rcdPending[i].cmd);
            ctx->rcdPending.erase(ctx->rcdPending.begin() + i);
        } else {
            ++i;
        }
    }
    if (ctx->rcdPending.size() >= RCD_INFLIGHT_CAP) return VFVK_BUSY;
    VkCommandBuffer cb = VK_NULL_HANDLE;
    if (!ctx->rcdFreeCbs.empty()) {
        cb = ctx->rcdFreeCbs.back();
        ctx->rcdFreeCbs.pop_back();
        // Retired only after its fence signaled: reset is safe here.
        vkResetCommandBuffer(cb, 0);
    } else {
        VkCommandBufferAllocateInfo info{};
        info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        info.commandPool = ctx->commandPool;
        info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        info.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(ctx->device, &info, &cb) != VK_SUCCESS) {
            return VFVK_SUBMIT_FAILED;
        }
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkFence f = VK_NULL_HANDLE;
    if (vkCreateFence(ctx->device, &fenceInfo, nullptr, &f) != VK_SUCCESS) {
        ctx->rcdFreeCbs.push_back(cb);
        return VFVK_SUBMIT_FAILED;
    }
    *cmd = cb;
    *fence = f;
    return VFVK_OK;
}

// Fused RCD finalize + grade pipeline: STORAGE_BUFFER CFA in +
// STORAGE_IMAGE rgb/scratch in (modes 1-2/0 results) + RcdYuvParams
// SSBO (160 bytes) + STORAGE_BUFFER P010 out. No push constants.
bool createRcdYuvPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: rcd-yuv shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[5]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[2].binding = 2;
    bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[2].descriptorCount = 1;
    bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[4].binding = 4;
    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[4].descriptorCount = 1;
    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 5;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->rcdYuvSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->rcdYuvSetLayout;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->rcdYuvPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rcdYuvSetLayout, nullptr);
        ctx->rcdYuvSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->rcdYuvPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->rcdYuvPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: rcd-yuv compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 9;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 6;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->rcdYuvPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->rcdYuvPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->rcdYuvSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->rcdYuvSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// Malvar-He-Cutler pipeline: STORAGE_BUFFER CFA in + write-only
// STORAGE_IMAGE rgb out + RcdParams push (72 bytes, u_mode 0).
bool createMhcPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: mhc shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->mhcSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(RcdParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->mhcSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->mhcPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->mhcSetLayout, nullptr);
        ctx->mhcSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->mhcPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->mhcPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: mhc compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = 3;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->mhcPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->mhcPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->mhcSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->mhcSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

uint32_t pickMemoryTypeProps(uint32_t bits, const VkPhysicalDeviceMemoryProperties& props,
                             VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    return UINT32_MAX;
}

// Fused MHC+grade pipeline: CFA STORAGE_BUFFER in + P010 STORAGE_BUFFER
// out + params STORAGE_BUFFER (FusedParams SSBO) + shading sampler +
// grade-LUT sampler; no push constants.
bool createFusedPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: fused shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[5]{};
    for (int i = 0; i < 3; ++i) {
        bindings[i].binding = (uint32_t)i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[4].binding = 4;
    bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    bindings[4].descriptorCount = 1;
    bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 5;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->fusedSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->fusedSetLayout;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr, &ctx->fusedPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->fusedSetLayout, nullptr);
        ctx->fusedSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->fusedPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr, &ctx->fusedPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: fused compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 9;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[1].descriptorCount = 6;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->fusedPool) != VK_SUCCESS) {
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        allocInfo.descriptorPool = ctx->fusedPool;
        allocInfo.descriptorSetCount = 1;
        allocInfo.pSetLayouts = &ctx->fusedSetLayout;
        if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->fusedSets[i]) != VK_SUCCESS) {
            return false;
        }
    }
    return true;
}

// Per-slot fused param buffers: host-visible + coherent, mapped
// persistently (192B params in 256B of allocation comfort).
bool createFusedParams(Context* ctx) {
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    for (int i = 0; i < 3; ++i) {
        VkBufferCreateInfo bufInfo{};
        bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufInfo.size = 256;
        bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->fusedParamBufs[i]) != VK_SUCCESS) {
            LOGW("vf-vk: fused param buffer %d failed", i);
            return false;
        }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(ctx->device, ctx->fusedParamBufs[i], &req);
        uint32_t memType = pickMemoryTypeProps(
            req.memoryTypeBits, memProps,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memType == UINT32_MAX) {
            LOGW("vf-vk: fused param memory %d: no coherent type", i);
            return false;
        }
        VkMemoryAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = memType;
        if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->fusedParamMems[i]) != VK_SUCCESS) {
            LOGW("vf-vk: fused param alloc %d failed", i);
            return false;
        }
        if (vkBindBufferMemory(ctx->device, ctx->fusedParamBufs[i],
                               ctx->fusedParamMems[i], 0) != VK_SUCCESS) {
            LOGW("vf-vk: fused param bind %d failed", i);
            return false;
        }
        if (vkMapMemory(ctx->device, ctx->fusedParamMems[i], 0, req.size, 0,
                        &ctx->fusedParamMaps[i]) != VK_SUCCESS) {
            LOGW("vf-vk: fused param map %d failed", i);
            ctx->fusedParamMaps[i] = nullptr;
            return false;
        }
    }
    return true;
}

// Per-slot RCD-fused param buffers: host-visible + coherent, mapped
// persistently (160B params in 256B of allocation comfort; mirrors
// createFusedParams).
bool createRcdYuvParams(Context* ctx) {
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    for (int i = 0; i < 3; ++i) {
        VkBufferCreateInfo bufInfo{};
        bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufInfo.size = 256;
        bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->rcdYuvParamBufs[i]) != VK_SUCCESS) {
            LOGW("vf-vk: rcd-yuv param buffer %d failed", i);
            return false;
        }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(ctx->device, ctx->rcdYuvParamBufs[i], &req);
        uint32_t memType = pickMemoryTypeProps(
            req.memoryTypeBits, memProps,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memType == UINT32_MAX) {
            LOGW("vf-vk: rcd-yuv param memory %d: no coherent type", i);
            return false;
        }
        VkMemoryAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = memType;
        if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->rcdYuvParamMems[i]) != VK_SUCCESS) {
            LOGW("vf-vk: rcd-yuv param alloc %d failed", i);
            return false;
        }
        if (vkBindBufferMemory(ctx->device, ctx->rcdYuvParamBufs[i],
                               ctx->rcdYuvParamMems[i], 0) != VK_SUCCESS) {
            LOGW("vf-vk: rcd-yuv param bind %d failed", i);
            return false;
        }
        if (vkMapMemory(ctx->device, ctx->rcdYuvParamMems[i], 0, req.size, 0,
                        &ctx->rcdYuvParamMaps[i]) != VK_SUCCESS) {
            LOGW("vf-vk: rcd-yuv param map %d failed", i);
            ctx->rcdYuvParamMaps[i] = nullptr;
            return false;
        }
    }
    return true;
}

// HAL maps are tiny (17x17 typical); 64x64 cells is a generous cap
// against a corrupt HAL advertising gigabytes.
constexpr int kShadeMaxCells = 64;
constexpr VkFormat kShadeFormat = VK_FORMAT_R16G16B16A16_SFLOAT;

void destroyShadeSlot(Context* ctx, int slot) {
    if (ctx->shadeUploadCmds[slot] != VK_NULL_HANDLE && ctx->commandPool != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &ctx->shadeUploadCmds[slot]);
    }
    ctx->shadeUploadCmds[slot] = VK_NULL_HANDLE;
    if (ctx->shadeViews[slot] != VK_NULL_HANDLE)
        vkDestroyImageView(ctx->device, ctx->shadeViews[slot], nullptr);
    ctx->shadeViews[slot] = VK_NULL_HANDLE;
    if (ctx->shadeImgs[slot] != VK_NULL_HANDLE)
        vkDestroyImage(ctx->device, ctx->shadeImgs[slot], nullptr);
    ctx->shadeImgs[slot] = VK_NULL_HANDLE;
    if (ctx->shadeImgMems[slot] != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->shadeImgMems[slot], nullptr);
    ctx->shadeImgMems[slot] = VK_NULL_HANDLE;
    if (ctx->shadeStagingMaps[slot] != nullptr && ctx->shadeStagingMems[slot] != VK_NULL_HANDLE) {
        vkUnmapMemory(ctx->device, ctx->shadeStagingMems[slot]);
    }
    ctx->shadeStagingMaps[slot] = nullptr;
    if (ctx->shadeStagings[slot] != VK_NULL_HANDLE)
        vkDestroyBuffer(ctx->device, ctx->shadeStagings[slot], nullptr);
    ctx->shadeStagings[slot] = VK_NULL_HANDLE;
    if (ctx->shadeStagingMems[slot] != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->shadeStagingMems[slot], nullptr);
    ctx->shadeStagingMems[slot] = VK_NULL_HANDLE;
    ctx->shadeImgRows[slot] = 0;
    ctx->shadeImgCols[slot] = 0;
    ctx->shadeImgInit[slot] = false;
}

void destroyShade(Context* ctx) {
    for (int i = 0; i < 3; ++i) destroyShadeSlot(ctx, i);
}

void destroyShadeDummyImage(Context* ctx) {
    if (ctx->shadeDummyView != VK_NULL_HANDLE)
        vkDestroyImageView(ctx->device, ctx->shadeDummyView, nullptr);
    ctx->shadeDummyView = VK_NULL_HANDLE;
    if (ctx->shadeDummyImg != VK_NULL_HANDLE)
        vkDestroyImage(ctx->device, ctx->shadeDummyImg, nullptr);
    ctx->shadeDummyImg = VK_NULL_HANDLE;
    if (ctx->shadeDummyMem != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->shadeDummyMem, nullptr);
    ctx->shadeDummyMem = VK_NULL_HANDLE;
}

void destroyShadeSampler(Context* ctx) {
    if (ctx->shadeSampler != VK_NULL_HANDLE)
        vkDestroySampler(ctx->device, ctx->shadeSampler, nullptr);
    ctx->shadeSampler = VK_NULL_HANDLE;
}

// Shared linear sampler (mirrors the grade-LUT sampler, 2D). The
// format check doubles as the shading-availability probe: RGBA16F
// optimal linear filtering is what the grade LUT already proved.
bool ensureShadeSampler(Context* ctx) {
    if (ctx->shadeSampler != VK_NULL_HANDLE) return true;
    VkFormatProperties fmtProps{};
    vkGetPhysicalDeviceFormatProperties(ctx->gpu, kShadeFormat, &fmtProps);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                     VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((fmtProps.optimalTilingFeatures & need) != need) {
        LOGW("vf-vk: shade: RGBA16F linear filtering unsupported");
        return false;
    }
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    if (vkCreateSampler(ctx->device, &samplerInfo, nullptr, &ctx->shadeSampler) != VK_SUCCESS) {
        LOGW("vf-vk: shade sampler failed");
        return false;
    }
    return true;
}

