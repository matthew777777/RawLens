// Ensure slot's image+staging+upload-cmd for rows*cols (recreates on
// dims change; slot reuse is caller-fence-gated so the old image is
// idle). False on allocation failure: caller disables shading, loud.
bool ensureShadeImage(Context* ctx, int slot, int rows, int cols) {
    if (ctx->shadeImgs[slot] != VK_NULL_HANDLE && ctx->shadeImgRows[slot] == rows &&
        ctx->shadeImgCols[slot] == cols) {
        return true;
    }
    destroyShadeSlot(ctx, slot);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = kShadeFormat;
    imgInfo.extent = {(uint32_t)cols, (uint32_t)rows, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &ctx->shadeImgs[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade image %d (%dx%d) failed", slot, cols, rows);
        return false;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, ctx->shadeImgs[slot], &req);
    uint32_t memType =
        pickMemoryTypeProps(req.memoryTypeBits, memProps, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: shade image %d: no device-local type", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->shadeImgMems[slot]) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, ctx->shadeImgs[slot], ctx->shadeImgMems[slot], 0) !=
            VK_SUCCESS) {
        LOGW("vf-vk: shade image memory %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = ctx->shadeImgs[slot];
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kShadeFormat;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(ctx->device, &viewInfo, nullptr, &ctx->shadeViews[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade view %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    const VkDeviceSize bytes = (VkDeviceSize)rows * cols * 4 * sizeof(uint16_t);
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = bytes;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->shadeStagings[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade staging %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    vkGetBufferMemoryRequirements(ctx->device, ctx->shadeStagings[slot], &req);
    memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                  VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                      VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: shade staging %d: no coherent type", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->shadeStagingMems[slot]) != VK_SUCCESS ||
        vkBindBufferMemory(ctx->device, ctx->shadeStagings[slot], ctx->shadeStagingMems[slot],
                           0) != VK_SUCCESS ||
        vkMapMemory(ctx->device, ctx->shadeStagingMems[slot], 0, req.size, 0,
                    &ctx->shadeStagingMaps[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade staging memory %d failed", slot);
        ctx->shadeStagingMaps[slot] = nullptr;
        destroyShadeSlot(ctx, slot);
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->shadeUploadCmds[slot]) != VK_SUCCESS) {
        LOGW("vf-vk: shade upload cmd %d failed", slot);
        destroyShadeSlot(ctx, slot);
        return false;
    }
    ctx->shadeImgRows[slot] = rows;
    ctx->shadeImgCols[slot] = cols;
    ctx->shadeImgInit[slot] = false;
    return true;
}

// Record + submit the staging->image blit for slot (barriers inline).
// Submitted unfenced just ahead of the main submit on the same queue;
// FIFO orders it — no fence, no WaitIdle. False on record/submit
// failure (caller disables shading for the frame, loud).
bool blitShadeImage(Context* ctx, int slot) {
    VkCommandBuffer cmd = ctx->shadeUploadCmds[slot];
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) return false;
    VkImageMemoryBarrier pre{};
    pre.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    pre.oldLayout =
        ctx->shadeImgInit[slot] ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    pre.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    pre.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.image = ctx->shadeImgs[slot];
    pre.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    pre.subresourceRange.levelCount = 1;
    pre.subresourceRange.layerCount = 1;
    pre.srcAccessMask =
        ctx->shadeImgInit[slot] ? (VkAccessFlags)VK_ACCESS_SHADER_READ_BIT : (VkAccessFlags)0;
    pre.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &pre);
    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;  // tight staging rows
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {(uint32_t)ctx->shadeImgCols[slot], (uint32_t)ctx->shadeImgRows[slot], 1};
    vkCmdCopyBufferToImage(cmd, ctx->shadeStagings[slot], ctx->shadeImgs[slot],
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    VkImageMemoryBarrier post{};
    post.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    post.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    post.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    post.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.image = ctx->shadeImgs[slot];
    post.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    post.subresourceRange.levelCount = 1;
    post.subresourceRange.layerCount = 1;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    post.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &post);
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) return false;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(ctx->queue, 1, &submit, VK_NULL_HANDLE) != VK_SUCCESS) return false;
    ctx->shadeImgInit[slot] = true;
    return true;
}

// Dummy 1x1 shading image (all ones: a wayward sample is identity) for
// the viewfinder, which shades in GL but must bind the layout's image.
// One-shot upload with an immediate idle (first VF frame only); failure
// degrades gracefully (VF falls back, record disables shading).
bool ensureShadeDummy(Context* ctx) {
    if (ctx->shadeDummyView != VK_NULL_HANDLE) return true;
    if (!ensureShadeSampler(ctx)) return false;
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = kShadeFormat;
    imgInfo.extent = {1, 1, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &ctx->shadeDummyImg) != VK_SUCCESS) {
        LOGW("vf-vk: shade dummy image failed");
        return false;
    }
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, ctx->shadeDummyImg, &req);
    uint32_t memType =
        pickMemoryTypeProps(req.memoryTypeBits, memProps, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (memType == UINT32_MAX ||
        vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->shadeDummyMem) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, ctx->shadeDummyImg, ctx->shadeDummyMem, 0) != VK_SUCCESS) {
        LOGW("vf-vk: shade dummy memory failed");
        destroyShadeDummyImage(ctx);
        return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = ctx->shadeDummyImg;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kShadeFormat;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(ctx->device, &viewInfo, nullptr, &ctx->shadeDummyView) != VK_SUCCESS) {
        LOGW("vf-vk: shade dummy view failed");
        destroyShadeDummyImage(ctx);
        return false;
    }
    // Upload four 1.0f halves (0x3C00) through a one-shot staging blit.
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    void* stagingMap = nullptr;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    bool ok = false;
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = 8;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    do {
        if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &staging) != VK_SUCCESS) break;
        vkGetBufferMemoryRequirements(ctx->device, staging, &req);
        memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (memType == UINT32_MAX) break;
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = memType;
        if (vkAllocateMemory(ctx->device, &alloc, nullptr, &stagingMem) != VK_SUCCESS) break;
        if (vkBindBufferMemory(ctx->device, staging, stagingMem, 0) != VK_SUCCESS) break;
        if (vkMapMemory(ctx->device, stagingMem, 0, req.size, 0, &stagingMap) != VK_SUCCESS) {
            stagingMap = nullptr;
            break;
        }
        static const uint16_t ones[4] = {0x3C00, 0x3C00, 0x3C00, 0x3C00};
        memcpy(stagingMap, ones, sizeof(ones));
        vkUnmapMemory(ctx->device, stagingMem);
        stagingMap = nullptr;
        VkCommandBufferAllocateInfo cmdInfo{};
        cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdInfo.commandPool = ctx->commandPool;
        cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdInfo.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &cmd) != VK_SUCCESS) break;
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) break;
        VkImageMemoryBarrier bar{};
        bar.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        bar.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        bar.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bar.image = ctx->shadeDummyImg;
        bar.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        bar.subresourceRange.levelCount = 1;
        bar.subresourceRange.layerCount = 1;
        bar.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &bar);
        VkBufferImageCopy region{};
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.layerCount = 1;
        region.imageExtent = {1, 1, 1};
        vkCmdCopyBufferToImage(cmd, staging, ctx->shadeDummyImg,
                               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        bar.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        bar.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        bar.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        bar.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                             &bar);
        if (vkEndCommandBuffer(cmd) != VK_SUCCESS) break;
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &cmd;
        if (vkQueueSubmit(ctx->queue, 1, &submit, VK_NULL_HANDLE) != VK_SUCCESS) break;
        if (vkQueueWaitIdle(ctx->queue) != VK_SUCCESS) break;
        ok = true;
    } while (false);
    if (cmd != VK_NULL_HANDLE) vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &cmd);
    if (staging != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, staging, nullptr);
    if (stagingMem != VK_NULL_HANDLE) vkFreeMemory(ctx->device, stagingMem, nullptr);
    if (!ok) {
        LOGW("vf-vk: shade dummy upload failed");
        destroyShadeDummyImage(ctx);
        return false;
    }
    return true;
}

void destroyFused(Context* ctx) {
    for (int i = 0; i < 3; ++i) {
        if (ctx->fusedParamMaps[i] != nullptr && ctx->fusedParamMems[i] != VK_NULL_HANDLE) {
            vkUnmapMemory(ctx->device, ctx->fusedParamMems[i]);
        }
        ctx->fusedParamMaps[i] = nullptr;
        if (ctx->fusedParamBufs[i] != VK_NULL_HANDLE)
            vkDestroyBuffer(ctx->device, ctx->fusedParamBufs[i], nullptr);
        ctx->fusedParamBufs[i] = VK_NULL_HANDLE;
        if (ctx->fusedParamMems[i] != VK_NULL_HANDLE)
            vkFreeMemory(ctx->device, ctx->fusedParamMems[i], nullptr);
        ctx->fusedParamMems[i] = VK_NULL_HANDLE;
    }
    if (ctx->fusedPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->fusedPipeline, nullptr);
    if (ctx->fusedPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->fusedPipelineLayout, nullptr);
    if (ctx->fusedSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->fusedSetLayout, nullptr);
    if (ctx->fusedPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->fusedPool, nullptr);
    ctx->fusedPipeline = VK_NULL_HANDLE;
    ctx->fusedPipelineLayout = VK_NULL_HANDLE;
    ctx->fusedSetLayout = VK_NULL_HANDLE;
    ctx->fusedPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->fusedSets[i] = VK_NULL_HANDLE;
}

void destroyStab(Context* ctx);
void destroyGrade(Context* ctx) {
    destroyPackedRcd(ctx);
    VkCommandBuffer twins[21] = {
        ctx->twinComputeCmd[0], ctx->twinComputeCmd[1], ctx->twinComputeCmd[2],
        ctx->twinGradeCmd[0], ctx->twinGradeCmd[1], ctx->twinGradeCmd[2],
        ctx->twinRcdCmd[0], ctx->twinRcdCmd[1], ctx->twinRcdCmd[2],
        ctx->twinMhcCmd[0], ctx->twinMhcCmd[1], ctx->twinMhcCmd[2],
        ctx->twinFusedCmd[0], ctx->twinFusedCmd[1], ctx->twinFusedCmd[2],
        ctx->twinPreviewCmd[0], ctx->twinPreviewCmd[1], ctx->twinPreviewCmd[2],
        ctx->twinRgb8Cmd[0], ctx->twinRgb8Cmd[1], ctx->twinRgb8Cmd[2]};
    bool anyTwin = false;
    for (int i = 0; i < 21; ++i) anyTwin = anyTwin || (twins[i] != VK_NULL_HANDLE);
    if (anyTwin) vkFreeCommandBuffers(ctx->device, ctx->commandPool, 21, twins);
    for (int i = 0; i < 3; ++i) {
        ctx->twinComputeCmd[i] = VK_NULL_HANDLE;
        ctx->twinGradeCmd[i] = VK_NULL_HANDLE;
        ctx->twinRcdCmd[i] = VK_NULL_HANDLE;
        ctx->twinMhcCmd[i] = VK_NULL_HANDLE;
        ctx->twinFusedCmd[i] = VK_NULL_HANDLE;
        ctx->twinPreviewCmd[i] = VK_NULL_HANDLE;
        ctx->twinRgb8Cmd[i] = VK_NULL_HANDLE;
        ctx->recComputeSets[i] = VK_NULL_HANDLE;
        ctx->recGradeSets[i] = VK_NULL_HANDLE;
    }
    // RCD dynamic CBs (same teardown safety as the twins above: this
    // runs after the queue is idle).
    for (size_t i = 0; i < ctx->rcdPending.size(); ++i) {
        if (ctx->rcdPending[i].fence != VK_NULL_HANDLE)
            vkDestroyFence(ctx->device, ctx->rcdPending[i].fence, nullptr);
        if (ctx->rcdPending[i].sem != VK_NULL_HANDLE)
            vkDestroySemaphore(ctx->device, ctx->rcdPending[i].sem, nullptr);
        // A6: still-pending CBs used to leak here (fence destroyed, CB never freed).
        if (ctx->rcdPending[i].cmd != VK_NULL_HANDLE) ctx->rcdFreeCbs.push_back(ctx->rcdPending[i].cmd);
    }
    ctx->rcdPending.clear();
    drainSlotGate(ctx);
    if (!ctx->rcdFreeCbs.empty()) {
        vkFreeCommandBuffers(ctx->device, ctx->commandPool,
                             (uint32_t)ctx->rcdFreeCbs.size(), ctx->rcdFreeCbs.data());
        ctx->rcdFreeCbs.clear();
    }
    destroyFused(ctx);
    destroyShade(ctx);
    if (ctx->recComputePool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->recComputePool, nullptr);
    if (ctx->recGradePool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->recGradePool, nullptr);
    ctx->recComputePool = VK_NULL_HANDLE;
    ctx->recGradePool = VK_NULL_HANDLE;
    for (auto& entry : ctx->recOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->recOuts.clear();
    for (auto& entry : ctx->yuvOuts) {
        destroyImportedBuffer(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->yuvOuts.clear();
    if (ctx->yuvPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->yuvPipeline, nullptr);
    if (ctx->yuvPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->yuvPipelineLayout, nullptr);
    if (ctx->yuvSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->yuvSetLayout, nullptr);
    if (ctx->yuvPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->yuvPool, nullptr);
    ctx->yuvPipeline = VK_NULL_HANDLE;
    ctx->yuvPipelineLayout = VK_NULL_HANDLE;
    ctx->yuvSetLayout = VK_NULL_HANDLE;
    ctx->yuvPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->yuvSets[i] = VK_NULL_HANDLE;
    for (auto& entry : ctx->previewOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->previewOuts.clear();
    if (ctx->previewPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->previewPipeline, nullptr);
    if (ctx->previewPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->previewPipelineLayout, nullptr);
    if (ctx->previewSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->previewSetLayout, nullptr);
    if (ctx->previewPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->previewPool, nullptr);
    ctx->previewPipeline = VK_NULL_HANDLE;
    ctx->previewPipelineLayout = VK_NULL_HANDLE;
    ctx->previewSetLayout = VK_NULL_HANDLE;
    ctx->previewPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->previewSets[i] = VK_NULL_HANDLE;
    for (auto& entry : ctx->rgb8Outs) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->rgb8Outs.clear();
    if (ctx->rgb8Pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->rgb8Pipeline, nullptr);
    if (ctx->rgb8PipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->rgb8PipelineLayout, nullptr);
    if (ctx->rgb8SetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rgb8SetLayout, nullptr);
    if (ctx->rgb8Pool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->rgb8Pool, nullptr);
    ctx->rgb8Pipeline = VK_NULL_HANDLE;
    ctx->rgb8PipelineLayout = VK_NULL_HANDLE;
    ctx->rgb8SetLayout = VK_NULL_HANDLE;
    ctx->rgb8Pool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->rgb8Sets[i] = VK_NULL_HANDLE;
    for (auto& entry : ctx->rgbOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->rgbOuts.clear();
    for (auto& entry : ctx->scratchOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->scratchOuts.clear();
    for (auto pipeline : ctx->rcdPipelines)
        if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, pipeline, nullptr);
    if (ctx->rcdPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->rcdPipelineLayout, nullptr);
    if (ctx->rcdSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rcdSetLayout, nullptr);
    if (ctx->rcdPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->rcdPool, nullptr);
    for (auto& pipeline : ctx->rcdPipelines) pipeline = VK_NULL_HANDLE;
    ctx->rcdPipelineLayout = VK_NULL_HANDLE;
    ctx->rcdSetLayout = VK_NULL_HANDLE;
    ctx->rcdPool = VK_NULL_HANDLE;
    for (auto& set : ctx->rcdSets) set = VK_NULL_HANDLE;
    if (ctx->rcdYuvPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->rcdYuvPipeline, nullptr);
    if (ctx->rcdYuvPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->rcdYuvPipelineLayout, nullptr);
    if (ctx->rcdYuvSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->rcdYuvSetLayout, nullptr);
    if (ctx->rcdYuvPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->rcdYuvPool, nullptr);
    ctx->rcdYuvPipeline = VK_NULL_HANDLE;
    ctx->rcdYuvPipelineLayout = VK_NULL_HANDLE;
    ctx->rcdYuvSetLayout = VK_NULL_HANDLE;
    ctx->rcdYuvPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->rcdYuvSets[i] = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) {
        if (ctx->rcdYuvParamMaps[i] != nullptr && ctx->rcdYuvParamMems[i] != VK_NULL_HANDLE) {
            vkUnmapMemory(ctx->device, ctx->rcdYuvParamMems[i]);
        }
        ctx->rcdYuvParamMaps[i] = nullptr;
        if (ctx->rcdYuvParamBufs[i] != VK_NULL_HANDLE)
            vkDestroyBuffer(ctx->device, ctx->rcdYuvParamBufs[i], nullptr);
        ctx->rcdYuvParamBufs[i] = VK_NULL_HANDLE;
        if (ctx->rcdYuvParamMems[i] != VK_NULL_HANDLE)
            vkFreeMemory(ctx->device, ctx->rcdYuvParamMems[i], nullptr);
        ctx->rcdYuvParamMems[i] = VK_NULL_HANDLE;
    }
    if (ctx->mhcPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->mhcPipeline, nullptr);
    if (ctx->mhcPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->mhcPipelineLayout, nullptr);
    if (ctx->mhcSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->mhcSetLayout, nullptr);
    if (ctx->mhcPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->mhcPool, nullptr);
    ctx->mhcPipeline = VK_NULL_HANDLE;
    ctx->mhcPipelineLayout = VK_NULL_HANDLE;
    ctx->mhcSetLayout = VK_NULL_HANDLE;
    ctx->mhcPool = VK_NULL_HANDLE;
    for (int i = 0; i < 3; ++i) ctx->mhcSets[i] = VK_NULL_HANDLE;
    for (auto& entry : ctx->gradeIns) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->gradeIns.clear();
    for (auto& entry : ctx->gradeOuts) {
        destroyImported(ctx, &entry.second);
        AHardwareBuffer_release(entry.first);
    }
    ctx->gradeOuts.clear();
    if (ctx->gradePipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->gradePipeline, nullptr);
    if (ctx->gradePipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->gradePipelineLayout, nullptr);
    if (ctx->gradeSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->gradeSetLayout, nullptr);
    if (ctx->gradePool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->gradePool, nullptr);
    ctx->gradePipeline = VK_NULL_HANDLE;
    ctx->gradePipelineLayout = VK_NULL_HANDLE;
    ctx->gradeSetLayout = VK_NULL_HANDLE;
    ctx->gradePool = VK_NULL_HANDLE;
    ctx->gradeSet = VK_NULL_HANDLE;
    destroyStab(ctx);
}

// Post-record stab warp teardown (null-safe): fence, command buffer,
// staging upload buffer, descriptor pool, pipeline. The P010 output
// imports stay cached in yuvOuts (shared with the record stages).
void destroyStab(Context* ctx) {
    ctx->stabPending = false;  // A1: queue is idle at every destroyStab call site
    if (ctx->stabFence != VK_NULL_HANDLE)
        vkDestroyFence(ctx->device, ctx->stabFence, nullptr);
    ctx->stabFence = VK_NULL_HANDLE;
    if (ctx->stabCmd != VK_NULL_HANDLE && ctx->commandPool != VK_NULL_HANDLE)
        vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &ctx->stabCmd);
    ctx->stabCmd = VK_NULL_HANDLE;
    if (ctx->stabStagingMap != nullptr && ctx->stabStagingMem != VK_NULL_HANDLE)
        vkUnmapMemory(ctx->device, ctx->stabStagingMem);
    ctx->stabStagingMap = nullptr;
    if (ctx->stabStaging != VK_NULL_HANDLE)
        vkDestroyBuffer(ctx->device, ctx->stabStaging, nullptr);
    ctx->stabStaging = VK_NULL_HANDLE;
    if (ctx->stabStagingMem != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->stabStagingMem, nullptr);
    ctx->stabStagingMem = VK_NULL_HANDLE;
    if (ctx->stabPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->stabPool, nullptr);
    ctx->stabPool = VK_NULL_HANDLE;
    ctx->stabSet = VK_NULL_HANDLE;
    if (ctx->stabPipeline != VK_NULL_HANDLE)
        vkDestroyPipeline(ctx->device, ctx->stabPipeline, nullptr);
    ctx->stabPipeline = VK_NULL_HANDLE;
    if (ctx->stabPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->stabPipelineLayout, nullptr);
    ctx->stabPipelineLayout = VK_NULL_HANDLE;
    if (ctx->stabSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->stabSetLayout, nullptr);
    ctx->stabSetLayout = VK_NULL_HANDLE;
    ctx->stabW = 0;
    ctx->stabH = 0;
}

// Baked grade warp LUT: 129-entry 1D RGBA16F over a log2 lattice
// ([2^-10, 2^4]; see VfLogGrade.bakeLut), stored as a 129x1x1 3D image
// so the existing sampler path just works (the v/w fetch at 0.5
// degrades trilinear to a 1D linear interp, ~0.07% error). Created
// lazily on first upload (optimal-tiled, device-local, staging
// upload); re-uploaded when contrast changes (129 evals ≈ 0.1ms, so
// slider moves never jank). Shared by the fused + grade-YUV stages.
constexpr int kGradeLutSize = 129;
constexpr VkDeviceSize kGradeLutBytes =
    (VkDeviceSize)kGradeLutSize * 4 * sizeof(uint16_t);

bool createGradeLut(Context* ctx) {
    VkFormatProperties fmtProps{};
    vkGetPhysicalDeviceFormatProperties(ctx->gpu, VK_FORMAT_R16G16B16A16_SFLOAT, &fmtProps);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                     VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((fmtProps.optimalTilingFeatures & need) != need) {
        LOGW("vf-vk: grade LUT: RGBA16F linear filtering unsupported");
        return false;
    }
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_3D;
    imgInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    imgInfo.extent = {(uint32_t)kGradeLutSize, 1, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &ctx->gradeLutImage) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT image failed");
        return false;
    }
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, ctx->gradeLutImage, &req);
    uint32_t memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                           VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: grade LUT: no device-local type");
        return false;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->gradeLutMemory) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, ctx->gradeLutImage, ctx->gradeLutMemory, 0) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT memory failed");
        return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = ctx->gradeLutImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_3D;
    viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(ctx->device, &viewInfo, nullptr, &ctx->gradeLutView) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT view failed");
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
    if (vkCreateSampler(ctx->device, &samplerInfo, nullptr, &ctx->gradeLutSampler) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT sampler failed");
        return false;
    }
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = kGradeLutBytes;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->gradeLutStaging) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT staging failed");
        return false;
    }
    vkGetBufferMemoryRequirements(ctx->device, ctx->gradeLutStaging, &req);
    memType = pickMemoryTypeProps(req.memoryTypeBits, memProps,
                                  VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                      VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: grade LUT staging: no coherent type");
        return false;
    }
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->gradeLutStagingMem) != VK_SUCCESS ||
        vkBindBufferMemory(ctx->device, ctx->gradeLutStaging, ctx->gradeLutStagingMem, 0) != VK_SUCCESS ||
        vkMapMemory(ctx->device, ctx->gradeLutStagingMem, 0, req.size, 0,
                    &ctx->gradeLutStagingMap) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT staging memory failed");
        ctx->gradeLutStagingMap = nullptr;
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->gradeLutUploadCmd) != VK_SUCCESS) {
        LOGW("vf-vk: grade LUT upload cmd failed");
        return false;
    }
    return true;
}

void destroyGradeLut(Context* ctx) {
    if (ctx->gradeLutUploadCmd != VK_NULL_HANDLE && ctx->commandPool != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(ctx->device, ctx->commandPool, 1, &ctx->gradeLutUploadCmd);
    }
    ctx->gradeLutUploadCmd = VK_NULL_HANDLE;
    if (ctx->gradeLutStagingMap != nullptr && ctx->gradeLutStagingMem != VK_NULL_HANDLE) {
        vkUnmapMemory(ctx->device, ctx->gradeLutStagingMem);
    }
    ctx->gradeLutStagingMap = nullptr;
    if (ctx->gradeLutStaging != VK_NULL_HANDLE)
        vkDestroyBuffer(ctx->device, ctx->gradeLutStaging, nullptr);
    ctx->gradeLutStaging = VK_NULL_HANDLE;
    if (ctx->gradeLutStagingMem != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->gradeLutStagingMem, nullptr);
    ctx->gradeLutStagingMem = VK_NULL_HANDLE;
    if (ctx->gradeLutSampler != VK_NULL_HANDLE)
        vkDestroySampler(ctx->device, ctx->gradeLutSampler, nullptr);
    ctx->gradeLutSampler = VK_NULL_HANDLE;
    if (ctx->gradeLutView != VK_NULL_HANDLE)
        vkDestroyImageView(ctx->device, ctx->gradeLutView, nullptr);
    ctx->gradeLutView = VK_NULL_HANDLE;
    if (ctx->gradeLutImage != VK_NULL_HANDLE)
        vkDestroyImage(ctx->device, ctx->gradeLutImage, nullptr);
    ctx->gradeLutImage = VK_NULL_HANDLE;
    if (ctx->gradeLutMemory != VK_NULL_HANDLE)
        vkFreeMemory(ctx->device, ctx->gradeLutMemory, nullptr);
    ctx->gradeLutMemory = VK_NULL_HANDLE;
    ctx->gradeLutReady = false;
}

// Upload baked half bits (kGradeLutBytes) to the LUT image. Settings-change
// path (~8ms bake + one staging upload): submits on the record queue and
// waits idle, so no fence plumbing and trivially ordered vs grade submits.
int uploadGradeLut(Context* ctx, const uint16_t* halfBits) {
    if (ctx->gradeLutImage == VK_NULL_HANDLE && !createGradeLut(ctx)) {
        return VFVK_SUBMIT_FAILED;
    }
    memcpy(ctx->gradeLutStagingMap, halfBits, (size_t)kGradeLutBytes);
    VkCommandBuffer cmd = ctx->gradeLutUploadCmd;
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    VkImageMemoryBarrier pre{};
    pre.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    pre.oldLayout = ctx->gradeLutReady ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                     : VK_IMAGE_LAYOUT_UNDEFINED;
    pre.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    pre.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    pre.image = ctx->gradeLutImage;
    pre.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    pre.subresourceRange.levelCount = 1;
    pre.subresourceRange.layerCount = 1;
    pre.srcAccessMask = ctx->gradeLutReady ? VK_ACCESS_SHADER_READ_BIT : 0;
    pre.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &pre);
    VkBufferImageCopy region{};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {(uint32_t)kGradeLutSize, 1, 1};
    vkCmdCopyBufferToImage(cmd, ctx->gradeLutStaging, ctx->gradeLutImage,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    VkImageMemoryBarrier post{};
    post.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    post.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    post.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    post.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    post.image = ctx->gradeLutImage;
    post.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    post.subresourceRange.levelCount = 1;
    post.subresourceRange.layerCount = 1;
    post.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    post.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &post);
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    if (vkQueueSubmit(ctx->queue, 1, &submit, VK_NULL_HANDLE) != VK_SUCCESS) {
        return VFVK_SUBMIT_FAILED;
    }
    if (vkQueueWaitIdle(ctx->queue) != VK_SUCCESS) return VFVK_SUBMIT_FAILED;
    ctx->gradeLutReady = true;
    return VFVK_OK;
}

}  // namespace

