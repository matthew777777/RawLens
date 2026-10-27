// ---- True-10-bit stage (Direct-Log P010): superpixel FP16 in, codec P010
// bytes out. Returns the completion sync fd (>= 0) or a negative -VFVK_*
// code; -20 means the codec buffer layout is not tight P010 (loud abort,
// never silent corruption). Shares the twin command buffers (per slot).
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_initGradeYuvNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->yuvPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin resources (command buffers, isolated sets) live with the grade
    // init; the yuv submit reuses them, so grade must be initialized first.
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createYuvPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: grade-yuv pipeline ready");
    return VFVK_OK;
}

// Codec P010 bytes out (storage buffer, byte-exact), cached by pointer
// in yuvOuts. Tight semi-planar validation: Y + interleaved UV must fit
// with less than a page of slack (allocators round total size up;
// strides themselves come from describe, so addressing is exact).
// Anything structurally divergent aborts loudly (-20) rather than
// misplacing UV. Shared by grade-YUV and fused.
static int importP010Out(Context* ctx, AHardwareBuffer* dstBuf, int yStrideB, int uvStrideB,
                         int fw, int fh, ImportedBuffer* out) {
    auto it = ctx->yuvOuts.find(dstBuf);
    if (it != ctx->yuvOuts.end()) {
        *out = it->second;
        return VFVK_OK;
    }
    ImportedBuffer imported;
    int rr = importInputBuffer(ctx, dstBuf, &imported);
    if (rr != VFVK_OK) {
        LOGW("vf-vk: yuv-out storage import failed %d", rr);
        return rr;
    }
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    if (ctx->getAhbProps(ctx->device, dstBuf, &ahbProps) != VK_SUCCESS) {
        destroyImportedBuffer(ctx, &imported);
        return VFVK_SUBMIT_FAILED;
    }
    const uint64_t expect =
        (uint64_t)yStrideB * (uint64_t)fh + (uint64_t)uvStrideB * (uint64_t)(fh / 2);
    if (ahbProps.allocationSize < expect ||
        ahbProps.allocationSize - expect > 4096) {
        LOGW("vf-vk: yuv-out not tight P010: alloc=%llu expect=%llu (y=%d uv=%d %dx%d)",
             (unsigned long long)ahbProps.allocationSize, (unsigned long long)expect,
             yStrideB, uvStrideB, fw, fh);
        destroyImportedBuffer(ctx, &imported);
        return -20;
    }
    AHardwareBuffer_acquire(dstBuf);
    ctx->yuvOuts[dstBuf] = imported;
    *out = imported;
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeYuvSubmitNative(
    JNIEnv* env, jobject, jobject expBuffer, jobject codecBuffer,
    jintArray idims, jintArray isrc, jfloatArray fparams, jintArray istrides, jint slot,
    jint fullRgb) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinGradeCmd[slot]), gateKey(g->yuvSets[slot])})) return -VFVK_BUSY;
    if (fullRgb != 0 && fullRgb != 1) return -VFVK_BAD_ARGUMENT;
    if (g->yuvPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!expBuffer || !codecBuffer || !idims || !isrc || !fparams || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    const jsize fLen = env->GetArrayLength(fparams);
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(isrc) < 2 ||
        fLen < 17 || env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* expBuf = AHardwareBuffer_fromHardwareBuffer(env, expBuffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, codecBuffer);
    if (!expBuf || !dstBuf) return -VFVK_BAD_ARGUMENT;

    GradeYuvParams params{};
    jint ii[2], si[2], st[4];
    jfloat ff[17];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetIntArrayRegion(isrc, 0, 2, si);
    env->GetFloatArrayRegion(fparams, 0, 17, ff);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    // ff[17] selects the output profile (0 = BT.709, 1 = S-Log3,
    // 2 = HLG); short arrays heal to BT.709.
    int outMode = 0;
    if (fLen >= 18) {
        jfloat m = 0;
        env->GetFloatArrayRegion(fparams, 17, 1, &m);
        outMode = (int)(m + 0.5f);
    }
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    if (outMode < 0 || outMode > 3) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.srcDims[0] = si[0];
    params.srcDims[1] = si[1];
    foldGradeCcm(ff, params.ccm);
    params.misc[0] = ff[14];
    params.misc[1] = params.misc[2] = params.misc[3] = 0.0f;
    // ff[15]/ff[16] (contrast/saturation) are reserved: the record path
    // is direct-encode, no look.
    params.misc2[1] = (float)outMode;
    params.misc2[2] = ff[16];
    params.misc2[3] = 0.0f;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE * 2 || params.dims[1] > VFVK_MAX_EDGE * 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    // OUR OWN semi-planar P010: Y rows + interleaved UV rows, tight.
    // Anything else aborts loudly (-20) rather than misplacing chroma.
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0) {
        LOGW("vf-vk: yuv strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.misc[1] = (float)(yStrideB / 2);
    params.misc[2] = (float)(uvStrideB / 2);
    params.misc[3] = (float)((int64_t)yStrideB * fh);
    params.misc2[0] = (fullRgb != 0) ? 1.0f : 0.0f;

    // FP16 source in (superpixel export or full-res MHC rgb; shared
    // grade-in import, same image layout either way).
    ImportedImage inImg;
    int r = gradeImage(g, expBuf, "yuv-in", &g->gradeIns, &inImg);
    if (r != VFVK_OK) return -r;
    // Codec P010 bytes out (storage buffer, byte-exact). Import first so a
    // layout failure aborts before any dispatch is recorded.
    ImportedBuffer dstImg;
    {
        int rr = importP010Out(g, dstBuf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : -rr;
    }

    VkCommandBuffer cmd = g->twinGradeCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorImageInfo inInfo{};
    inInfo.imageView = inImg.view;
    inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    if (!g->gradeLutReady) return -VFVK_NOT_INITIALIZED;
    VkDescriptorImageInfo lutInfo{};
    lutInfo.sampler = g->gradeLutSampler;
    lutInfo.imageView = g->gradeLutView;
    lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->yuvSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->yuvSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->yuvSets[slot];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].pImageInfo = &lutInfo;
    vkUpdateDescriptorSets(g->device, 3, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        return -VFVK_SUBMIT_FAILED;
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    layoutBarrier(cmd, inImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    // Fresh output allocation: acquire ownership before the first write
    // (without this the store lands nowhere observable).
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = dstImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->yuvPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->yuvPipelineLayout, 0, 1, &g->yuvSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->yuvPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(cmd, gx, gy, 1);
    // Release for the codec (GL/EGL-free path): ownership back to FOREIGN;
    // completion in wall time rides the exported sync fd, which the caller
    // polls before queueInputBuffer (the codec has no fd channel).
    layoutBarrier(cmd, inImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    VkBufferMemoryBarrier outBarrier{};
    outBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outBarrier.dstAccessMask = 0;
    outBarrier.srcQueueFamilyIndex = g->queueFamily;
    outBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    outBarrier.buffer = dstImg.buffer;
    outBarrier.offset = 0;
    outBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &outBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    VkFence gateF = gateFence(g);
    if (gateF == VK_NULL_HANDLE) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return -VFVK_SUBMIT_FAILED;
    }
    vr = vkQueueSubmit(g->queue, 1, &submit, gateF);
    if (vr != VK_SUCCESS) {
        g->gateFencePool.push_back(gateF);
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    // A5: the semaphore lives until the submit's fence signals.
    slotSubmitted(g, {gateKey(g->twinGradeCmd[slot]), gateKey(g->yuvSets[slot])}, gateF, sem);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// Blocking twin of gradeYuvSubmitNative: identical validation, imports,
// descriptors, barriers and dispatch, but completion via the shared fence
// (gradeNative convention) instead of an exported sync fd. For drivers
// without semaphore-fd export (software/validation Vulkan) and as a
// documented fallback; the record path keeps the fd twin. Same-queue FIFO
// still orders this behind a preceding computeSubmitNative.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeYuvBlockingNative(
    JNIEnv* env, jobject, jobject expBuffer, jobject codecBuffer,
    jintArray idims, jintArray isrc, jfloatArray fparams, jintArray istrides, jint slot,
    jint fullRgb) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinGradeCmd[slot]), gateKey(g->yuvSets[slot])})) return VFVK_BUSY;
    if (fullRgb != 0 && fullRgb != 1) return VFVK_BAD_ARGUMENT;
    if (g->yuvPipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!expBuffer || !codecBuffer || !idims || !isrc || !fparams || !istrides) {
        return VFVK_BAD_ARGUMENT;
    }
    const jsize fLen = env->GetArrayLength(fparams);
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(isrc) < 2 ||
        fLen < 17 || env->GetArrayLength(istrides) < 4) {
        return VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* expBuf = AHardwareBuffer_fromHardwareBuffer(env, expBuffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, codecBuffer);
    if (!expBuf || !dstBuf) return VFVK_BAD_ARGUMENT;

    GradeYuvParams params{};
    jint ii[2], si[2], st[4];
    jfloat ff[17];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetIntArrayRegion(isrc, 0, 2, si);
    env->GetFloatArrayRegion(fparams, 0, 17, ff);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    int outMode = 0;
    if (fLen >= 18) {
        jfloat m = 0;
        env->GetFloatArrayRegion(fparams, 17, 1, &m);
        outMode = (int)(m + 0.5f);
    }
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (outMode < 0 || outMode > 3) return VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.srcDims[0] = si[0];
    params.srcDims[1] = si[1];
    foldGradeCcm(ff, params.ccm);
    params.misc[0] = ff[14];
    params.misc[1] = params.misc[2] = params.misc[3] = 0.0f;
    params.misc2[1] = (float)outMode;
    params.misc2[2] = ff[16];
    params.misc2[3] = 0.0f;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE * 2 || params.dims[1] > VFVK_MAX_EDGE * 2) {
        return VFVK_BAD_ARGUMENT;
    }
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0) {
        LOGW("vf-vk: yuv strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return VFVK_BAD_ARGUMENT;
    }
    params.misc[1] = (float)(yStrideB / 2);
    params.misc[2] = (float)(uvStrideB / 2);
    params.misc[3] = (float)((int64_t)yStrideB * fh);
    params.misc2[0] = (fullRgb != 0) ? 1.0f : 0.0f;

    ImportedImage inImg;
    int r = gradeImage(g, expBuf, "yuv-in", &g->gradeIns, &inImg);
    if (r != VFVK_OK) return r;
    ImportedBuffer dstImg;
    {
        int rr = importP010Out(g, dstBuf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : rr;
    }

    VkCommandBuffer cmd = g->twinGradeCmd[slot];
    if (cmd == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    VkDescriptorImageInfo inInfo{};
    inInfo.imageView = inImg.view;
    inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    if (!g->gradeLutReady) return VFVK_NOT_INITIALIZED;
    VkDescriptorImageInfo lutInfo{};
    lutInfo.sampler = g->gradeLutSampler;
    lutInfo.imageView = g->gradeLutView;
    lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->yuvSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->yuvSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->yuvSets[slot];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].pImageInfo = &lutInfo;
    vkUpdateDescriptorSets(g->device, 3, writes, 0, nullptr);

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: yuv blocking begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    layoutBarrier(cmd, inImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = dstImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->yuvPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->yuvPipelineLayout, 0, 1, &g->yuvSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->yuvPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(cmd, gx, gy, 1);
    layoutBarrier(cmd, inImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    VkBufferMemoryBarrier outBarrier{};
    outBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outBarrier.dstAccessMask = 0;
    outBarrier.srcQueueFamilyIndex = g->queueFamily;
    outBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    outBarrier.buffer = dstImg.buffer;
    outBarrier.offset = 0;
    outBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &outBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: yuv blocking end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: yuv blocking submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    vr = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, 2000000000ull);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: yuv blocking fence wait failed %d", vr);
        const VkResult retired = vkQueueWaitIdle(g->queue);
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

// ---- Record preview (Direct-Log monitor): staged P010 in, RGBA8 out.
// Same-queue ordered after the encode submit, own exported sync fd for
// the EGL present path. Best-effort: any failure skips the preview for
// that frame, the encode is unaffected.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRecordPreview_initPreviewNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->previewPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin resources (command buffers, isolated sets) live with the grade
    // init; the preview submit reuses them, so grade must be up first.
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createPreviewPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: record-preview pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRecordPreview_previewSubmitNative(
    JNIEnv* env, jobject, jobject p010Buffer, jobject previewBuffer,
    jintArray idims, jintArray isrc, jintArray istrides, jint box, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinPreviewCmd[slot]), gateKey(g->previewSets[slot])})) return -VFVK_BUSY;
    if (g->previewPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!p010Buffer || !previewBuffer || !idims || !isrc || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(isrc) < 2 ||
        env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* srcBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, previewBuffer);
    if (!srcBuf || !dstBuf) return -VFVK_BAD_ARGUMENT;

    PreviewParams params{};
    jint ii[2], si[2], st[4];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetIntArrayRegion(isrc, 0, 2, si);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.srcDims[0] = si[0];
    params.srcDims[1] = si[1];
    params.strides[3] = box;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE ||
        params.srcDims[0] <= 0 || params.srcDims[1] <= 0 ||
        params.srcDims[0] > VFVK_MAX_EDGE * 2 || params.srcDims[1] > VFVK_MAX_EDGE * 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    // Exact integer downsample on both axes, even box (the chroma grid is
    // half-res; an odd box would skew the UV taps). A wrong-scale preview
    // is worse than none: reject loudly, the recorder skips the frame.
    if (box < 2 || (box % 2) != 0 ||
        params.srcDims[0] != params.dims[0] * box ||
        params.srcDims[1] != params.dims[1] * box) {
        LOGW("vf-vk: preview box mismatch %dx%d -> %dx%d box=%d",
             params.srcDims[0], params.srcDims[1], params.dims[0], params.dims[1], box);
        return -VFVK_BAD_ARGUMENT;
    }
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0 ||
        fw != params.srcDims[0] || fh != params.srcDims[1]) {
        LOGW("vf-vk: preview strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.strides[0] = yStrideB / 2;
    params.strides[1] = uvStrideB / 2;
    params.strides[2] = yStrideB * fh;

    // P010 in (the encode submit just validated + wrote it; cache hit).
    ImportedBuffer srcImg;
    {
        int rr = importP010Out(g, srcBuf, yStrideB, uvStrideB, fw, fh, &srcImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : -rr;
    }
    // RGBA8 preview out (STORAGE_IMAGE, byte-exact for EGL re-import).
    ImportedImage dstImg;
    {
        int r = gradeImage(g, dstBuf, "preview-out", &g->previewOuts, &dstImg);
        if (r != VFVK_OK) return -r;
    }

    VkCommandBuffer cmd = g->twinPreviewCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = srcImg.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = dstImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->previewSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->previewSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    VkExportSemaphoreCreateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semInfo.pNext = &exportInfo;
    VkSemaphore sem = VK_NULL_HANDLE;
    if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
        return -VFVK_SUBMIT_FAILED;
    }
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    // Same-queue ordered after the encode submit (FIFO): re-acquire both
    // buffers from FOREIGN (the encode submit just released them).
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = srcImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->previewPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->previewPipelineLayout, 0, 1, &g->previewSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->previewPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.dims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    // Release for the EGL present path: ownership back to FOREIGN;
    // completion in wall time rides the exported sync fd, which the
    // presenter adopts before sampling (GL waits on the GPU timeline).
    {
        VkBufferMemoryBarrier release{};
        release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        release.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        release.dstAccessMask = 0;
        release.srcQueueFamilyIndex = g->queueFamily;
        release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        release.buffer = srcImg.buffer;
        release.offset = 0;
        release.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &release, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    VkFence gateF = gateFence(g);
    if (gateF == VK_NULL_HANDLE) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return -VFVK_SUBMIT_FAILED;
    }
    vr = vkQueueSubmit(g->queue, 1, &submit, gateF);
    if (vr != VK_SUCCESS) {
        g->gateFencePool.push_back(gateF);
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    // A5: the semaphore lives until the submit's fence signals.
    slotSubmitted(g, {gateKey(g->twinPreviewCmd[slot]), gateKey(g->previewSets[slot])}, gateF, sem);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRgb8Convert_initRgb8Native(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->rgb8Pipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin command buffers are standalone allocations (same pool, own
    // slots): no grade dependency — the warp path never grades.
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createRgb8Pipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: rgb8 convert pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRgb8Convert_rgb8SubmitNative(
    JNIEnv* env, jobject, jobject p010Buffer, jobject rgb8Buffer,
    jintArray idims, jintArray isrc, jintArray istrides, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinRgb8Cmd[slot]), gateKey(g->rgb8Sets[slot])})) return -VFVK_BUSY;
    if (g->rgb8Pipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!p010Buffer || !rgb8Buffer || !idims || !isrc || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(isrc) < 2 ||
        env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* srcBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, rgb8Buffer);
    if (!srcBuf || !dstBuf) return -VFVK_BAD_ARGUMENT;

    Rgb8Params params{};
    jint ii[2], si[2], st[4];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetIntArrayRegion(isrc, 0, 2, si);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.srcDims[0] = si[0];
    params.srcDims[1] = si[1];
    // Full-res 1:1 convert (the 8-bit SDR fallback): out dims must equal
    // the P010 dims exactly, both even (YUV420 chroma grid).
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE ||
        params.dims[0] != params.srcDims[0] || params.dims[1] != params.srcDims[1] ||
        (params.dims[0] % 2) != 0 || (params.dims[1] % 2) != 0) {
        LOGW("vf-vk: rgb8 dims mismatch %dx%d vs %dx%d",
             params.srcDims[0], params.srcDims[1], params.dims[0], params.dims[1]);
        return -VFVK_BAD_ARGUMENT;
    }
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0 ||
        fw != params.srcDims[0] || fh != params.srcDims[1]) {
        LOGW("vf-vk: rgb8 strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.strides[0] = yStrideB / 2;
    params.strides[1] = uvStrideB / 2;
    params.strides[2] = yStrideB * fh;

    // P010 in (the encode submit just validated + wrote it; cache hit).
    ImportedBuffer srcImg;
    {
        int rr = importP010Out(g, srcBuf, yStrideB, uvStrideB, fw, fh, &srcImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : -rr;
    }
    // RGBA8 convert out (STORAGE_IMAGE, byte-exact for EGL re-import).
    ImportedImage dstImg;
    {
        int r = gradeImage(g, dstBuf, "rgb8-out", &g->rgb8Outs, &dstImg);
        if (r != VFVK_OK) return -r;
    }

    VkSemaphore sem = VK_NULL_HANDLE;
    {
        VkExportSemaphoreCreateInfo exportInfo{};
        exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
        exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        VkSemaphoreCreateInfo semInfo{};
        semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        semInfo.pNext = &exportInfo;
        if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
            return -VFVK_SUBMIT_FAILED;
        }
    }
    VkCommandBuffer cmd = g->twinRgb8Cmd[slot];
    if (cmd == VK_NULL_HANDLE) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return -VFVK_PIPELINE_FAILED;
    }
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = srcImg.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = dstImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->rgb8Sets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->rgb8Sets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    // Same-queue ordered after the encode submit (FIFO): re-acquire both
    // buffers from FOREIGN (the encode submit just released them).
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = srcImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->rgb8Pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->rgb8PipelineLayout, 0, 1, &g->rgb8Sets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->rgb8PipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.dims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    // Release for the EGL present path: ownership back to FOREIGN;
    // completion in wall time rides the exported sync fd, which the
    // presenter adopts before sampling (GL waits on the GPU timeline).
    {
        VkBufferMemoryBarrier release{};
        release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        release.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        release.dstAccessMask = 0;
        release.srcQueueFamilyIndex = g->queueFamily;
        release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        release.buffer = srcImg.buffer;
        release.offset = 0;
        release.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &release, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &sem;
    VkFence gateF = gateFence(g);
    if (gateF == VK_NULL_HANDLE) {
        vkDestroySemaphore(g->device, sem, nullptr);
        return -VFVK_SUBMIT_FAILED;
    }
    vr = vkQueueSubmit(g->queue, 1, &submit, gateF);
    if (vr != VK_SUCCESS) {
        g->gateFencePool.push_back(gateF);
        vkDestroySemaphore(g->device, sem, nullptr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    // A5: the semaphore lives until the submit's fence signals.
    slotSubmitted(g, {gateKey(g->twinRgb8Cmd[slot]), gateKey(g->rgb8Sets[slot])}, gateF, sem);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// Blocking twin of previewSubmitNative: identical validation, imports,
// descriptors, barriers and dispatch, but completion via the shared fence
// (gradeNative convention) instead of an exported sync fd. For drivers
// without semaphore-fd export (software/validation Vulkan) and as a
// documented fallback; the record path keeps the fd twin. Same-queue FIFO
// still orders this behind a preceding encode submit.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRecordPreview_previewBlockingNative(
    JNIEnv* env, jobject, jobject p010Buffer, jobject previewBuffer,
    jintArray idims, jintArray isrc, jintArray istrides, jint box, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinPreviewCmd[slot]), gateKey(g->previewSets[slot])})) return VFVK_BUSY;
    if (g->previewPipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!p010Buffer || !previewBuffer || !idims || !isrc || !istrides) {
        return VFVK_BAD_ARGUMENT;
    }
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(isrc) < 2 ||
        env->GetArrayLength(istrides) < 4) {
        return VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* srcBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, previewBuffer);
    if (!srcBuf || !dstBuf) return VFVK_BAD_ARGUMENT;

    PreviewParams params{};
    jint ii[2], si[2], st[4];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetIntArrayRegion(isrc, 0, 2, si);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.srcDims[0] = si[0];
    params.srcDims[1] = si[1];
    params.strides[3] = box;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE ||
        params.srcDims[0] <= 0 || params.srcDims[1] <= 0 ||
        params.srcDims[0] > VFVK_MAX_EDGE * 2 || params.srcDims[1] > VFVK_MAX_EDGE * 2) {
        return VFVK_BAD_ARGUMENT;
    }
    if (box < 2 || (box % 2) != 0 ||
        params.srcDims[0] != params.dims[0] * box ||
        params.srcDims[1] != params.dims[1] * box) {
        LOGW("vf-vk: preview box mismatch %dx%d -> %dx%d box=%d",
             params.srcDims[0], params.srcDims[1], params.dims[0], params.dims[1], box);
        return VFVK_BAD_ARGUMENT;
    }
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0 ||
        fw != params.srcDims[0] || fh != params.srcDims[1]) {
        LOGW("vf-vk: preview strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return VFVK_BAD_ARGUMENT;
    }
    params.strides[0] = yStrideB / 2;
    params.strides[1] = uvStrideB / 2;
    params.strides[2] = yStrideB * fh;

    ImportedBuffer srcImg;
    {
        int rr = importP010Out(g, srcBuf, yStrideB, uvStrideB, fw, fh, &srcImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : rr;
    }
    ImportedImage dstImg;
    {
        int r = gradeImage(g, dstBuf, "preview-out", &g->previewOuts, &dstImg);
        if (r != VFVK_OK) return r;
    }

    VkCommandBuffer cmd = g->twinPreviewCmd[slot];
    if (cmd == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = srcImg.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = dstImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->previewSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->previewSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: preview blocking begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    {
        VkBufferMemoryBarrier acquire{};
        acquire.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        acquire.srcAccessMask = 0;
        acquire.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        acquire.dstQueueFamilyIndex = g->queueFamily;
        acquire.buffer = srcImg.buffer;
        acquire.offset = 0;
        acquire.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->previewPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->previewPipelineLayout, 0, 1, &g->previewSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->previewPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.dims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    {
        VkBufferMemoryBarrier release{};
        release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        release.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        release.dstAccessMask = 0;
        release.srcQueueFamilyIndex = g->queueFamily;
        release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        release.buffer = srcImg.buffer;
        release.offset = 0;
        release.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &release, 0, nullptr);
    }
    layoutBarrier(cmd, dstImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: preview blocking end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: preview blocking submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    vr = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, 2000000000ull);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: preview blocking fence wait failed %d", vr);
        const VkResult retired = vkQueueWaitIdle(g->queue);
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

