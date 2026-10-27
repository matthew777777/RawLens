// ---- Malvar-He-Cutler fast demosaic (Direct-Log record path): single
// dispatch, CFA in + write-only rgb out. Same fd-exporting convention
// as the other submits (the recorder closes the fd when the grade's fd
// supersedes it... in practice MHC runs right before the grade, whose
// fd carries completion; probes poll the MHC fd for GPU timings). ----

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfMhc_initMhcNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->mhcPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin command buffers live with the grade init (as for RCD/YUV).
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createMhcPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: mhc demosaic pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfMhc_mhcSubmitNative(
    JNIEnv* env, jobject, jobject cfaBuffer, jobject rgbBuffer,
    jintArray iparams, jfloatArray fparams, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinMhcCmd[slot]), gateKey(g->mhcSets[slot])})) return -VFVK_BUSY;
    if (g->mhcPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!cfaBuffer || !rgbBuffer || !iparams || !fparams) return -VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* cfaBuf = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, rgbBuffer);
    if (!cfaBuf || !outBuf) return -VFVK_BAD_ARGUMENT;

    RcdParams params{};
    jint ii[10];
    jfloat ff[8];
    env->GetIntArrayRegion(iparams, 0, 10, ii);
    env->GetFloatArrayRegion(fparams, 0, 8, ff);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.origin[0] = ii[2];
    params.origin[1] = ii[3];
    for (int i = 0; i < 4; ++i) params.chans[i] = ii[4 + i];
    params.pitch = ii[8];
    params.mode = ii[9];
    for (int i = 0; i < 4; ++i) params.black[i] = ff[i];
    for (int i = 0; i < 4; ++i) params.invRange[i] = ff[4 + i];
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE ||
        params.pitch <= 0 || params.pitch > 16384 ||
        params.mode != 0 ||
        params.origin[0] < 0 || params.origin[1] < 0) {
        return -VFVK_BAD_ARGUMENT;
    }

    // CFA bytes in (shared input-buffer import, byte-exact).
    auto it = g->inputs.find(cfaBuf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, cfaBuf, &imported);
        if (r != VFVK_OK) return -VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(cfaBuf);
        g->inputs[cfaBuf] = imported;
        g->inputOrder.push_back(cfaBuf);
        it = g->inputs.find(cfaBuf);
    }
    const ImportedBuffer& input = it->second;
    // Full-res rgb out (fresh overwrite every dispatch; imports share
    // the rgbOuts map with RCD — same buffers, same usage).
    ImportedImage rgbImg;
    int r = gradeImage(g, outBuf, "mhc-rgb", &g->rgbOuts, &rgbImg);
    if (r != VFVK_OK) return -r;

    VkCommandBuffer cmd = g->twinMhcCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = input.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = rgbImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->mhcSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->mhcSets[slot];
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
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = 0;
    bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    bufBarrier.dstQueueFamilyIndex = g->queueFamily;
    bufBarrier.buffer = input.buffer;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    layoutBarrier(cmd, rgbImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->mhcPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->mhcPipelineLayout, 0, 1, &g->mhcSets[slot], 0, nullptr);
    vkCmdPushConstants(cmd, g->mhcPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    // MHC uses 16x16 workgroups (vf_mhc.comp; all other vf stages 8x8).
    const uint32_t gx = (uint32_t)(params.dims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.dims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    layoutBarrier(cmd, rgbImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.dstAccessMask = 0;
    bufBarrier.srcQueueFamilyIndex = g->queueFamily;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
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
    slotSubmitted(g, {gateKey(g->twinMhcCmd[slot]), gateKey(g->mhcSets[slot])}, gateF, sem);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- Fused MHC+grade record stage (vf_mhcyuv.comp) ---------------------
// Single dispatch: CFA mosaic -> demosaic -> grade -> P010. The split
// stages serialize on one queue (measured 38.6ms > the 33.3ms frame)
// and round-trip 150MB of RGB; fusing removes both. Params reuse the
// packMhc + packGrade arrays (same semantics) via a per-slot SSBO.

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_initFusedYuvNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->fusedPipeline != VK_NULL_HANDLE) return VFVK_OK;
    // Twin command buffers live with the grade init (as for RCD/MHC/YUV).
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createFusedPipeline(g, code.data(), code.size())) {
        destroyFused(g);
        return VFVK_PIPELINE_FAILED;
    }
    if (!createFusedParams(g)) {
        destroyFused(g);
        return VFVK_DEVICE_FAILED;
    }
    LOGI("vf-vk: fused mhc+yuv pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeLutUploadNative(JNIEnv* env, jobject, jshortArray halfBits) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    // 129 RGBA half words; must match kGradeLutBytes above.
    constexpr jsize kHalfWords = 129 * 4;
    if (!halfBits || env->GetArrayLength(halfBits) != kHalfWords) {
        return VFVK_BAD_ARGUMENT;
    }
    std::vector<uint16_t> bits((size_t)kHalfWords);
    env->GetShortArrayRegion(halfBits, 0, (jsize)bits.size(),
                             reinterpret_cast<jshort*>(bits.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    return uploadGradeLut(g, bits.data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_fusedYuvSubmitNative(
    JNIEnv* env, jobject, jobject cfaBuffer, jobject p010Buffer,
    jintArray mhcIparams, jfloatArray mhcFparams,
    jintArray gradeIdims, jfloatArray gradeFparams, jintArray istrides, jint slot,
    jintArray shadeDims, jshortArray shadeGains) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinFusedCmd[slot]), gateKey(g->fusedSets[slot]), gateKey(g->fusedParamBufs[slot])})) return -VFVK_BUSY;
    if (g->fusedPipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!cfaBuffer || !p010Buffer || !mhcIparams || !mhcFparams ||
        !gradeIdims || !gradeFparams || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    const jsize gfLen = env->GetArrayLength(gradeFparams);
    if (env->GetArrayLength(mhcIparams) < 10 || env->GetArrayLength(mhcFparams) < 8 ||
        env->GetArrayLength(gradeIdims) < 2 || gfLen < 17 ||
        env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    if (g->fusedParamMaps[slot] == nullptr) return -VFVK_DEVICE_FAILED;
    AHardwareBuffer* cfaBuf = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    if (!cfaBuf || !dstBuf) return -VFVK_BAD_ARGUMENT;

    FusedParams params{};
    jint mi[10], gi[2], st[4];
    jfloat mf[8], gf[17];
    env->GetIntArrayRegion(mhcIparams, 0, 10, mi);
    env->GetFloatArrayRegion(mhcFparams, 0, 8, mf);
    env->GetIntArrayRegion(gradeIdims, 0, 2, gi);
    env->GetFloatArrayRegion(gradeFparams, 0, 17, gf);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    // gf[17] selects the output profile (0 = BT.709, 1 = S-Log3,
    // 2 = HLG); short arrays heal to BT.709.
    int outMode = 0;
    if (gfLen >= 18) {
        jfloat m = 0;
        env->GetFloatArrayRegion(gradeFparams, 17, 1, &m);
        outMode = (int)(m + 0.5f);
    }
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    if (outMode < 0 || outMode > 3) return -VFVK_BAD_ARGUMENT;
    params.cDims[0] = mi[0];
    params.cDims[1] = mi[1];
    params.cOrigin[0] = mi[2];
    params.cOrigin[1] = mi[3];
    for (int i = 0; i < 4; ++i) params.cChans[i] = mi[4 + i];
    params.cPitch = mi[8];
    const int mhcMode = mi[9];
    for (int i = 0; i < 4; ++i) params.cNormBias[i] = mf[i];
    for (int i = 0; i < 4; ++i) params.cInvRange[i] = mf[4 + i];
    params.oDims[0] = gi[0];
    params.oDims[1] = gi[1];
    for (int i = 0; i < 4; ++i) params.gGains[i] = gf[i];
    foldGradeCcm(gf, params.gCcm);
    params.gMisc[0] = gf[13];
    // gf[15]/gf[16] (contrast/saturation) are reserved: the record path
    // is direct-encode, no look. gGains.w still carries gf[16] (the
    // shader ignores it) so the SSBO layout never drifts.
    params.gGains[3] = gf[16];
    const float bypass = gf[14];
    if (params.cDims[0] <= 0 || params.cDims[1] <= 0 ||
        params.cDims[0] > VFVK_MAX_EDGE || params.cDims[1] > VFVK_MAX_EDGE ||
        params.cPitch <= 0 || params.cPitch > 16384 ||
        mhcMode != 0 || bypass != 0.0f ||
        params.cOrigin[0] < 0 || params.cOrigin[1] < 0 ||
        params.oDims[0] <= 0 || params.oDims[1] <= 0 ||
        params.oDims[0] > VFVK_MAX_EDGE * 2 || params.oDims[1] > VFVK_MAX_EDGE * 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0) {
        LOGW("vf-vk: fused strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.gMisc[1] = (float)(yStrideB / 2);
    params.gMisc[2] = (float)(uvStrideB / 2);
    params.gMisc[3] = (float)((int64_t)yStrideB * fh);
    VkImageView shadeView = VK_NULL_HANDLE;
    uploadShadeMap(env, shadeDims, shadeGains, slot,
                   params.sDims, params.sActive, &shadeView);
    if (shadeView == VK_NULL_HANDLE) return -VFVK_SUBMIT_FAILED;
    // After the uploader (it zeroes sDims.w): the output profile word.
    params.sDims[3] = outMode;

    // CFA bytes in (shared input-buffer import, byte-exact).
    auto it = g->inputs.find(cfaBuf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, cfaBuf, &imported);
        if (r != VFVK_OK) return -VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(cfaBuf);
        g->inputs[cfaBuf] = imported;
        g->inputOrder.push_back(cfaBuf);
        it = g->inputs.find(cfaBuf);
    }
    const ImportedBuffer& input = it->second;
    // P010 bytes out (shared tight validation; -20 aborts loudly).
    ImportedBuffer dstImg;
    {
        int r = importP010Out(g, dstBuf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (r != VFVK_OK) return (r == -20) ? -20 : -r;
    }
    // Params in (per-slot coherent SSBO; flush for good measure).
    memcpy(g->fusedParamMaps[slot], &params, sizeof(params));
    {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = g->fusedParamMems[slot];
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        vkFlushMappedMemoryRanges(g->device, 1, &range);
    }

    VkCommandBuffer cmd = g->twinFusedCmd[slot];
    if (cmd == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = input.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo paramInfo{};
    paramInfo.buffer = g->fusedParamBufs[slot];
    paramInfo.offset = 0;
    paramInfo.range = sizeof(FusedParams);
    if (!g->gradeLutReady) return -VFVK_NOT_INITIALIZED;
    VkDescriptorImageInfo lutInfo{};
    lutInfo.sampler = g->gradeLutSampler;
    lutInfo.imageView = g->gradeLutView;
    lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = shadeView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[5]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->fusedSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->fusedSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->fusedSets[slot];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[2].pBufferInfo = &paramInfo;
    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = g->fusedSets[slot];
    writes[3].dstBinding = 3;
    writes[3].descriptorCount = 1;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[3].pImageInfo = &lutInfo;
    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[4].dstSet = g->fusedSets[slot];
    writes[4].dstBinding = 4;
    writes[4].descriptorCount = 1;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[4].pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 5, writes, 0, nullptr);

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
    VkBufferMemoryBarrier inBarrier{};
    inBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inBarrier.srcAccessMask = 0;
    inBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    inBarrier.dstQueueFamilyIndex = g->queueFamily;
    inBarrier.buffer = input.buffer;
    inBarrier.offset = 0;
    inBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &inBarrier, 0, nullptr);
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
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->fusedPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->fusedPipelineLayout, 0, 1, &g->fusedSets[slot], 0, nullptr);
    // vf_mhcyuv: 16x16 invocations, each owning two horizontal pixels.
    // https://docs.vulkan.org/spec/latest/chapters/dispatch.html
    const uint32_t pairs = (uint32_t)(params.oDims[0] + 1) / 2;
    const uint32_t gx = (pairs + 15) / 16;
    const uint32_t gy = (uint32_t)(params.oDims[1] + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    inBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inBarrier.dstAccessMask = 0;
    inBarrier.srcQueueFamilyIndex = g->queueFamily;
    inBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &inBarrier, 0, nullptr);
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
    slotSubmitted(g, {gateKey(g->twinFusedCmd[slot]), gateKey(g->fusedSets[slot]), gateKey(g->fusedParamBufs[slot])}, gateF, sem);
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- Post-record stabilization warp (vf_stabwarp.comp) -------------------
// Tight-P010 storage buffer in (one decoded frame, host-uploaded through
// a persistently-mapped staging buffer) + OUR P010 staging storage buffer
// out (importP010Out validation, shared yuvOuts cache). Single command
// buffer + blocking fence submit: the pass runs on a background thread
// after the take, so no twin ping-pong and no fd export. Plain VFVK_*
// return codes (non-negative, unlike the fd-exporting submits).
bool createStabPipeline(Context* ctx, const uint32_t* code, size_t words) {
    VkShaderModuleCreateInfo modInfo{};
    modInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    modInfo.codeSize = words * sizeof(uint32_t);
    modInfo.pCode = code;
    VkShaderModule module = VK_NULL_HANDLE;
    if (vkCreateShaderModule(ctx->device, &modInfo, nullptr, &module) != VK_SUCCESS) {
        LOGW("vf-vk: stab shader module failed");
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    bindings[0].binding = 0;
    bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[0].descriptorCount = 1;
    bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    bindings[1].binding = 1;
    bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    bindings[1].descriptorCount = 1;
    bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    VkDescriptorSetLayoutCreateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setInfo.bindingCount = 2;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(ctx->device, &setInfo, nullptr, &ctx->stabSetLayout) != VK_SUCCESS) {
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = sizeof(StabWarpParams);
    VkPipelineLayoutCreateInfo pipeLayoutInfo{};
    pipeLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipeLayoutInfo.setLayoutCount = 1;
    pipeLayoutInfo.pSetLayouts = &ctx->stabSetLayout;
    pipeLayoutInfo.pushConstantRangeCount = 1;
    pipeLayoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(ctx->device, &pipeLayoutInfo, nullptr,
                               &ctx->stabPipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(ctx->device, ctx->stabSetLayout, nullptr);
        ctx->stabSetLayout = VK_NULL_HANDLE;
        vkDestroyShaderModule(ctx->device, module, nullptr);
        return false;
    }
    VkComputePipelineCreateInfo pipeInfo{};
    pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipeInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipeInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipeInfo.stage.module = module;
    pipeInfo.stage.pName = "main";
    pipeInfo.layout = ctx->stabPipelineLayout;
    VkResult r = vkCreateComputePipelines(ctx->device, pipelineCacheFor(ctx), 1, &pipeInfo, nullptr,
                                          &ctx->stabPipeline);
    vkDestroyShaderModule(ctx->device, module, nullptr);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: stab compute pipeline failed %d", r);
        return false;
    }
    savePipelineCache(ctx);
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(ctx->device, &poolInfo, nullptr, &ctx->stabPool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = ctx->stabPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &ctx->stabSetLayout;
    if (vkAllocateDescriptorSets(ctx->device, &allocInfo, &ctx->stabSet) != VK_SUCCESS) {
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->stabCmd) != VK_SUCCESS) {
        LOGW("vf-vk: stab command buffer failed");
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(ctx->device, &fenceInfo, nullptr, &ctx->stabFence) != VK_SUCCESS) {
        LOGW("vf-vk: stab fence failed");
        return false;
    }
    return true;
}

// Tight-P010 upload staging for one WxH frame (host-visible + coherent,
// mapped persistently): Y rows of W u16 + H/2 chroma rows of W u16.
bool createStabStaging(Context* ctx, int w, int h) {
    const uint64_t bytes = (uint64_t)w * (uint64_t)h * 3;
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = bytes;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->stabStaging) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging buffer failed");
        return false;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx->device, ctx->stabStaging, &req);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryTypeProps(
        req.memoryTypeBits, memProps,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: stab staging: no coherent type");
        return false;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->stabStagingMem) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging alloc failed");
        return false;
    }
    if (vkBindBufferMemory(ctx->device, ctx->stabStaging, ctx->stabStagingMem, 0) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging bind failed");
        return false;
    }
    if (vkMapMemory(ctx->device, ctx->stabStagingMem, 0, req.size, 0,
                    &ctx->stabStagingMap) != VK_SUCCESS) {
        LOGW("vf-vk: stab staging map failed");
        ctx->stabStagingMap = nullptr;
        return false;
    }
    ctx->stabW = w;
    ctx->stabH = h;
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfStab_initStabNative(JNIEnv* env, jobject, jbyteArray spv,
                                              jint width, jint height) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (width <= 0 || height <= 0 || width > VFVK_MAX_EDGE || height > VFVK_MAX_EDGE ||
        (width % 2) != 0 || (height % 2) != 0) {
        return VFVK_BAD_ARGUMENT;
    }
    if (g->stabPipeline != VK_NULL_HANDLE) {
        // Idempotent per dims: a same-size re-init is a no-op, a new size
        // reallocates the staging (the pipeline itself is size-agnostic).
        if (g->stabW == width && g->stabH == height) return VFVK_OK;
        if (g->stabStagingMap != nullptr && g->stabStagingMem != VK_NULL_HANDLE)
            vkUnmapMemory(g->device, g->stabStagingMem);
        g->stabStagingMap = nullptr;
        if (g->stabStaging != VK_NULL_HANDLE)
            vkDestroyBuffer(g->device, g->stabStaging, nullptr);
        g->stabStaging = VK_NULL_HANDLE;
        if (g->stabStagingMem != VK_NULL_HANDLE)
            vkFreeMemory(g->device, g->stabStagingMem, nullptr);
        g->stabStagingMem = VK_NULL_HANDLE;
        g->stabW = 0;
        g->stabH = 0;
        if (!createStabStaging(g, width, height)) {
            destroyStab(g);
            return VFVK_DEVICE_FAILED;
        }
        return VFVK_OK;
    }
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createStabPipeline(g, code.data(), code.size()) ||
        !createStabStaging(g, width, height)) {
        destroyStab(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: stab warp pipeline ready %dx%d", width, height);
    return VFVK_OK;
}

// One decoded frame into the tight-P010 staging: Y rows then interleaved
// UV rows, u16 units, 10-bit codes in the upper bits (grade convention).
// 8-bit decoder output upscales video-range (v<<2); 10-bit copies as-is
// (decoder P010 matches the codec P010 convention by construction).
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfStab_stabUploadNative(
    JNIEnv* env, jobject, jobject yBuf, jobject uBuf, jobject vBuf,
    jint yStrideB, jint uStrideB, jint vStrideB,
    jint uPxB, jint vPxB, jint width, jint height, jboolean is10Bit) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->stabStagingMap == nullptr || g->stabW != width || g->stabH != height) {
        return VFVK_BAD_ARGUMENT;
    }
    // S1: the staging buffer is the previous warp's input; never overwrite
    // it while that warp may still be reading (see A1 / stabPending).
    if (g->stabPending) {
        if (vkGetFenceStatus(g->device, g->stabFence) == VK_NOT_READY) return VFVK_BUSY;
        vkResetFences(g->device, 1, &g->stabFence);
        g->stabPending = false;
    }
    if (!yBuf || !uBuf || !vBuf) return VFVK_BAD_ARGUMENT;
    if (width <= 0 || height <= 0 || (width % 2) != 0 || (height % 2) != 0) {
        return VFVK_BAD_ARGUMENT;
    }
    const int pxB = is10Bit ? 2 : 1;
    // S3: the 10-bit path loads 2 bytes per chroma sample, so a 1-byte pixel
    // stride would read one byte past the plane the capacity check allows.
    if (yStrideB < width * pxB || uStrideB < (width / 2) * uPxB ||
        vStrideB < (width / 2) * vPxB || uPxB < pxB || vPxB < pxB) {
        LOGW("vf-vk: stab upload strides implausible");
        return VFVK_BAD_ARGUMENT;
    }
    uint8_t* yPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    uint8_t* uPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
    uint8_t* vPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!yPtr || !uPtr || !vPtr) return VFVK_BAD_ARGUMENT;
    const jlong yCap = env->GetDirectBufferCapacity(yBuf);
    const jlong uCap = env->GetDirectBufferCapacity(uBuf);
    const jlong vCap = env->GetDirectBufferCapacity(vBuf);
    const int64_t yNeed = (int64_t)yStrideB * (height - 1) + (int64_t)width * pxB;
    const int64_t uNeed = (int64_t)uStrideB * (height / 2 - 1) + (int64_t)(width / 2) * uPxB;
    const int64_t vNeed = (int64_t)vStrideB * (height / 2 - 1) + (int64_t)(width / 2) * vPxB;
    if (yCap < yNeed || uCap < uNeed || vCap < vNeed) {
        LOGW("vf-vk: stab upload planes too small");
        return VFVK_BAD_ARGUMENT;
    }
    uint16_t* dst = static_cast<uint16_t*>(g->stabStagingMap);
    uint16_t* dstUv = dst + (int64_t)width * height;
    if (is10Bit) {
        for (int y = 0; y < height; ++y) {
            const uint8_t* row = yPtr + (int64_t)y * yStrideB;
            uint16_t* out = dst + (int64_t)y * width;
            for (int x = 0; x < width; ++x) {
                // Little-endian u16 load (ByteBuffer order is irrelevant).
                out[x] = (uint16_t)(row[x * 2] | (row[x * 2 + 1] << 8));
            }
        }
        for (int y = 0; y < height / 2; ++y) {
            const uint8_t* uRow = uPtr + (int64_t)y * uStrideB;
            const uint8_t* vRow = vPtr + (int64_t)y * vStrideB;
            uint16_t* out = dstUv + (int64_t)y * width;
            for (int x = 0; x < width / 2; ++x) {
                const uint8_t* us = uRow + (int64_t)x * uPxB;
                const uint8_t* vs = vRow + (int64_t)x * vPxB;
                out[x * 2] = (uint16_t)(us[0] | (us[1] << 8));
                out[x * 2 + 1] = (uint16_t)(vs[0] | (vs[1] << 8));
            }
        }
    } else {
        for (int y = 0; y < height; ++y) {
            const uint8_t* row = yPtr + (int64_t)y * yStrideB;
            uint16_t* out = dst + (int64_t)y * width;
            for (int x = 0; x < width; ++x) {
                out[x] = (uint16_t)(row[x] << 8);  // v<<2 code, stored <<6
            }
        }
        for (int y = 0; y < height / 2; ++y) {
            const uint8_t* uRow = uPtr + (int64_t)y * uStrideB;
            const uint8_t* vRow = vPtr + (int64_t)y * vStrideB;
            uint16_t* out = dstUv + (int64_t)y * width;
            for (int x = 0; x < width / 2; ++x) {
                out[x * 2] = (uint16_t)(uRow[(int64_t)x * uPxB] << 8);
                out[x * 2 + 1] = (uint16_t)(vRow[(int64_t)x * vPxB] << 8);
            }
        }
    }
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfStab_stabSubmitNative(
    JNIEnv* env, jobject, jobject p010Buffer, jintArray iparams, jfloatArray fparams) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->stabPipeline == VK_NULL_HANDLE || g->stabCmd == VK_NULL_HANDLE ||
        g->stabFence == VK_NULL_HANDLE || g->stabStaging == VK_NULL_HANDLE) {
        return VFVK_PIPELINE_FAILED;
    }
    if (g->stabPending) {
        // A1: the previous warp outlived its bounded wait; never reset or
        // re-record under it.
        if (vkGetFenceStatus(g->device, g->stabFence) == VK_NOT_READY) return VFVK_BUSY;
        vkResetFences(g->device, 1, &g->stabFence);
        g->stabPending = false;
    }
    if (!p010Buffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(iparams) < 4 || env->GetArrayLength(fparams) < 9) {
        return VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* dstBuf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    if (!dstBuf) return VFVK_BAD_ARGUMENT;
    StabWarpParams params{};
    jint ii[4];
    jfloat ff[9];
    env->GetIntArrayRegion(iparams, 0, 4, ii);
    env->GetFloatArrayRegion(fparams, 0, 9, ff);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    const int w = ii[0], h = ii[1];
    const int yStrideB = ii[2], uvStrideB = ii[3];
    const int fw = w, fh = h;
    if (w != g->stabW || h != g->stabH || w <= 0 || h <= 0 ||
        w > VFVK_MAX_EDGE || h > VFVK_MAX_EDGE || (w % 2) != 0 || (h % 2) != 0) {
        return VFVK_BAD_ARGUMENT;
    }
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || (yStrideB % 2) != 0 ||
        (uvStrideB % 4) != 0) {
        LOGW("vf-vk: stab out strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return VFVK_BAD_ARGUMENT;
    }
    params.dims[0] = w;
    params.dims[1] = h;
    params.srcStrides[0] = w;
    params.srcStrides[1] = w;
    params.outStrides[0] = yStrideB / 2;
    params.outStrides[1] = uvStrideB / 2;
    params.uvBase[0] = w * h * 2;
    params.uvBase[1] = yStrideB * fh;
    for (int i = 0; i < 9; ++i) {
        if (!std::isfinite(ff[i])) return VFVK_BAD_ARGUMENT;
        params.mat[i] = ff[i];
    }

    ImportedBuffer dstImg;
    {
        int rr = importP010Out(g, dstBuf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (rr != VFVK_OK) return rr;
    }
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = g->stabStaging;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->stabSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->stabSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    VkCommandBuffer cmd = g->stabCmd;
    vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(cmd, &begin);
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    // Host upload above happened-before this call (same thread): make it
    // visible to the shader read.
    {
        VkBufferMemoryBarrier upload{};
        upload.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        upload.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        upload.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        upload.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        upload.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        upload.buffer = g->stabStaging;
        upload.offset = 0;
        upload.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &upload,
                             0, nullptr);
    }
    // Re-acquire the staging output from FOREIGN (the CPU copy released it).
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
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &acquire,
                             0, nullptr);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->stabPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->stabPipelineLayout, 0, 1, &g->stabSet, 0, nullptr);
    vkCmdPushConstants(cmd, g->stabPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(w + 15) / 16;
    const uint32_t gy = (uint32_t)(h + 15) / 16;
    vkCmdDispatch(cmd, gx, gy, 1);
    // Release for the CPU copy path: ownership back to FOREIGN; the fence
    // below carries wall-time completion (no fd export on this stage).
    {
        VkBufferMemoryBarrier release{};
        release.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        release.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        release.dstAccessMask = 0;
        release.srcQueueFamilyIndex = g->queueFamily;
        release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        release.buffer = dstImg.buffer;
        release.offset = 0;
        release.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &release,
                             0, nullptr);
    }
    vr = vkEndCommandBuffer(cmd);
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->stabFence);
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->stabPending = true;
    vr = vkWaitForFences(g->device, 1, &g->stabFence, VK_TRUE, 5000000000ULL);
    if (vr == VK_SUCCESS) {
        vkResetFences(g->device, 1, &g->stabFence);
        g->stabPending = false;
    }
    if (vr != VK_SUCCESS) {
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfStab_resetStabNative(JNIEnv*, jobject) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return;
    vkDeviceWaitIdle(g->device);
    destroyStab(g);
}

