// ---- RCD demosaic (Direct-Log record path): one pipeline, u_mode
// selects dirs / green / R-B-at-opposite / R-B-at-green. Same
// fd-exporting convention as the grade submits (the recorder closes
// the intermediate fds; probes poll them for per-mode GPU timings). ----

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRcd_initRcdNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->rcdPipelines[0] != VK_NULL_HANDLE) return VFVK_OK;
    // Twin command buffers live with the grade init (as for grade-YUV).
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createRcdPipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: rcd demosaic pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRcd_rcdSubmitNative(
    JNIEnv* env, jobject, jobject cfaBuffer, jobject rgbBuffer, jobject scratchBuffer,
    jintArray iparams, jfloatArray fparams, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    if (g->rcdPipelines[0] == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!cfaBuffer || !rgbBuffer || !scratchBuffer || !iparams || !fparams) {
        return -VFVK_BAD_ARGUMENT;
    }
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* cfaBuf = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, rgbBuffer);
    AHardwareBuffer* scrBuf = AHardwareBuffer_fromHardwareBuffer(env, scratchBuffer);
    if (!cfaBuf || !outBuf || !scrBuf) return -VFVK_BAD_ARGUMENT;

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
        params.mode < 0 || (params.mode & ~19) != 0 ||
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
    // Full-res rgb out (written mode 1, refined modes 2-3).
    ImportedImage rgbImg;
    int r = gradeImage(g, outBuf, "rcd-rgb", &g->rgbOuts, &rgbImg);
    if (r != VFVK_OK) return -r;
    // Direction scratch (written mode 0, read modes 1-3).
    ImportedImage scrImg;
    r = gradeImage(g, scrBuf, "rcd-scratch", &g->scratchOuts, &scrImg);
    if (r != VFVK_OK) return -r;

    // Separate immutable bindings for each mode allow back-to-back stage submits.
    const VkDescriptorSet modeSet = g->rcdSets[slot * 4 + (params.mode & 3)];
    // Dynamic per-submit CB (see acquireRcdCmd): never a twin reset.
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence submitFence = VK_NULL_HANDLE;
    {
        // A4: the slot's set (and fused params) may still be in flight.
        if (rcdSetInUse(g, modeSet)) return -VFVK_BUSY;
        int cr = acquireRcdCmd(g, &cmd, &submitFence);
        if (cr != VFVK_OK) return -cr;
    }
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = input.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = rgbImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo scrInfo{};
    scrInfo.imageView = scrImg.view;
    scrInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = modeSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = modeSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = modeSet;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[2].pImageInfo = &scrInfo;
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
    // No reset: acquireRcdCmd hands out fresh-or-retired CBs.
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
    // Mode 0 writes scratch fresh (rgb untouched); mode 1 writes rgb
    // fresh and reads scratch; modes 2-3 read-modify-write rgb texels
    // (same-invocation RMW only — no intra-dispatch hazard by the
    // shader's mosaic-from-CFA discipline) and read scratch.
    const int mode = params.mode & 3;
    const bool scrWrite = mode == 0;
    const bool rgbWrite = mode >= 1;
    const bool rgbRead = mode >= 2;
    const bool scrRead = mode >= 1;
    // A8 (sync validation, SYNC-HAZARD-WRITE-AFTER-WRITE): each mode's
    // acquire waits on prior compute writes. TOP_OF_PIPE ordered nothing,
    // and the Direct-Log path submits modes 0-2 back to back with no CPU
    // wait, so mode N could read (or re-layout) mode N-1's images mid-write.
    if (scrWrite) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                      VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    } else if (scrRead) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    }
    if (rgbWrite) {
        layoutBarrier(cmd, rgbImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                      rgbRead ? (VkAccessFlags)(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                              : (VkAccessFlags)VK_ACCESS_SHADER_WRITE_BIT,
                      rgbRead ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
                      VK_IMAGE_LAYOUT_GENERAL,
                      VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->rcdPipelines[params.mode & 3]);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->rcdPipelineLayout, 0, 1, &modeSet, 0, nullptr);
    vkCmdPushConstants(cmd, g->rcdPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t blockWidth = (params.mode & 3) == 0 ? 8u : 16u;
    const uint32_t gx = (uint32_t)(params.dims[0] + blockWidth - 1) / blockWidth;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(cmd, gx, gy, 1);
    if (scrWrite) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    } else if (scrRead) {
        layoutBarrier(cmd, scrImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    }
    if (rgbWrite) {
        layoutBarrier(cmd, rgbImg.image,
                      VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                      VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                      VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                      g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    }
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
    vr = vkQueueSubmit(g->queue, 1, &submit, submitFence);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        vkDestroyFence(g->device, submitFence, nullptr);
        g->rcdFreeCbs.push_back(cmd);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    // A5: semaphore and set stay owned by the submission until its fence signals.
    g->rcdPending.push_back({cmd, submitFence, sem, modeSet});
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

// ---- Fused RCD finalize + grade (Direct-Log RCD path): modes 0-2 run
// first via rcdSubmitNative, then this replaces mode 3 + gradeYuv with
// one dispatch (RCD finalize at green sites + direct P010 encode, no
// RGBA16F round trip). Same fd-exporting convention as the other
// submits; the recorder closes superseded mode fds, this fd gates the
// copy. Falls back to the split path when init fails. ----

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRcd_initRcdYuvNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->rcdYuvPipeline != VK_NULL_HANDLE && g->rcdYuvParamMaps[0] != nullptr) return VFVK_OK;
    // Twin command buffers live with the grade init (as for RCD/YUV).
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!ensureTwinResources(g)) return VFVK_DEVICE_FAILED;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (g->rcdYuvPipeline == VK_NULL_HANDLE &&
        !createRcdYuvPipeline(g, code.data(), code.size())) {
        return VFVK_PIPELINE_FAILED;
    }
    if (g->rcdYuvParamMaps[0] == nullptr && !createRcdYuvParams(g)) {
        return VFVK_DEVICE_FAILED;
    }
    LOGI("vf-vk: rcd-yuv fused pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfRcd_rcdFusedYuvSubmitNative(
    JNIEnv* env, jobject, jobject cfaBuffer, jobject rgbBuffer, jobject scratchBuffer,
    jobject p010Buffer, jintArray rcdIparams, jfloatArray rcdFparams,
    jintArray gradeIdims, jintArray gradeIsrc, jfloatArray gradeFparams,
    jintArray istrides, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    if (g->rcdYuvPipeline == VK_NULL_HANDLE || g->rcdYuvParamMaps[slot] == nullptr) {
        return -VFVK_PIPELINE_FAILED;
    }
    if (!g->hasSemaphoreFd) return -1;
    if (!cfaBuffer || !rgbBuffer || !scratchBuffer || !p010Buffer ||
        !rcdIparams || !rcdFparams || !gradeIdims || !gradeIsrc || !gradeFparams || !istrides) {
        return -VFVK_BAD_ARGUMENT;
    }
    const jsize gLen = env->GetArrayLength(gradeFparams);
    if (env->GetArrayLength(rcdIparams) < 10 || env->GetArrayLength(rcdFparams) < 8 ||
        env->GetArrayLength(gradeIdims) < 2 || env->GetArrayLength(gradeIsrc) < 2 ||
        gLen < 17 || env->GetArrayLength(istrides) < 4) {
        return -VFVK_BAD_ARGUMENT;
    }
    AHardwareBuffer* cfaBuf = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* rgbBuf = AHardwareBuffer_fromHardwareBuffer(env, rgbBuffer);
    AHardwareBuffer* scrBuf = AHardwareBuffer_fromHardwareBuffer(env, scratchBuffer);
    AHardwareBuffer* p010Buf = AHardwareBuffer_fromHardwareBuffer(env, p010Buffer);
    if (!cfaBuf || !rgbBuf || !scrBuf || !p010Buf) return -VFVK_BAD_ARGUMENT;

    RcdYuvParams params{};
    jint ri[10];
    jfloat rf[8];
    env->GetIntArrayRegion(rcdIparams, 0, 10, ri);
    env->GetFloatArrayRegion(rcdFparams, 0, 8, rf);
    jint gi[2], gs[2], st[4];
    jfloat gf[17];
    env->GetIntArrayRegion(gradeIdims, 0, 2, gi);
    env->GetIntArrayRegion(gradeIsrc, 0, 2, gs);
    env->GetFloatArrayRegion(gradeFparams, 0, 17, gf);
    env->GetIntArrayRegion(istrides, 0, 4, st);
    // gf[17] selects the output profile (0 = BT.709, 1 = S-Log3,
    // 2 = HLG); short arrays heal to BT.709 (gradeYuv convention).
    int outMode = 0;
    if (gLen >= 18) {
        jfloat m = 0;
        env->GetFloatArrayRegion(gradeFparams, 17, 1, &m);
        outMode = (int)(m + 0.5f);
    }
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    if (outMode < 0 || outMode > 3) return -VFVK_BAD_ARGUMENT;
    params.cDims[0] = ri[0];
    params.cDims[1] = ri[1];
    params.cOrigin[0] = ri[2];
    params.cOrigin[1] = ri[3];
    for (int i = 0; i < 4; ++i) params.cChans[i] = ri[4 + i];
    params.cPitch = ri[8];
    // ri[9] (RCD mode) is ignored: the fused stage always finalizes.
    for (int i = 0; i < 4; ++i) params.cBlack[i] = rf[i];
    for (int i = 0; i < 4; ++i) params.cInvRange[i] = rf[4 + i];
    if (params.cDims[0] <= 0 || params.cDims[1] <= 0 ||
        params.cDims[0] > VFVK_MAX_EDGE || params.cDims[1] > VFVK_MAX_EDGE ||
        params.cPitch <= 0 || params.cPitch > 16384 ||
        params.cOrigin[0] < 0 || params.cOrigin[1] < 0) {
        return -VFVK_BAD_ARGUMENT;
    }
    params.oDims[0] = gi[0];
    params.oDims[1] = gi[1];
    // Grade source dims are the crop by construction (the fused shader
    // maps export->crop itself); reject a mismatched caller outright.
    if (gs[0] != params.cDims[0] || gs[1] != params.cDims[1]) return -VFVK_BAD_ARGUMENT;
    if (params.oDims[0] <= 0 || params.oDims[1] <= 0 ||
        params.oDims[0] > VFVK_MAX_EDGE * 2 || params.oDims[1] > VFVK_MAX_EDGE * 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    foldGradeCcm(gf, params.gCcm);
    const int yStrideB = st[0], uvStrideB = st[1];
    const int fw = st[2], fh = st[3];
    if (yStrideB < fw * 2 || uvStrideB < fw * 2 || fw <= 0 || fh <= 0 || (fh % 2) != 0 ||
        (yStrideB % 2) != 0 || (uvStrideB % 4) != 0) {
        LOGW("vf-vk: rcd-yuv strides implausible y=%d uv=%d %dx%d", yStrideB, uvStrideB, fw, fh);
        return -VFVK_BAD_ARGUMENT;
    }
    params.gMisc[0] = 0.0f;  // no bypass on the record path
    params.gMisc[1] = (float)(yStrideB / 2);
    params.gMisc[2] = (float)(uvStrideB / 2);
    params.gMisc[3] = (float)((int64_t)yStrideB * fh);
    params.gMisc2[0] = 1.0f;  // fullRgb: demosaiced RGB in
    params.gMisc2[1] = (float)outMode;
    params.gMisc2[2] = gf[16];
    params.gMisc2[3] = 0.0f;

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
    // Modes 1-2 rgb + mode-0 scratch in (same caches as the RCD modes,
    // so these imports hit).
    ImportedImage rgbImg;
    int r = gradeImage(g, rgbBuf, "rcd-rgb", &g->rgbOuts, &rgbImg);
    if (r != VFVK_OK) return -r;
    ImportedImage scrImg;
    r = gradeImage(g, scrBuf, "rcd-scratch", &g->scratchOuts, &scrImg);
    if (r != VFVK_OK) return -r;
    // Codec P010 bytes out (storage buffer, byte-exact). Import first so
    // a layout failure aborts before any dispatch is recorded.
    ImportedBuffer dstImg;
    {
        int rr = importP010Out(g, p010Buf, yStrideB, uvStrideB, fw, fh, &dstImg);
        if (rr != VFVK_OK) return (rr == -20) ? -20 : -rr;
    }
    // A4: the slot's set and params buffer may still be in flight.
    if (rcdSetInUse(g, g->rcdYuvSets[slot])) return -VFVK_BUSY;
    memcpy(g->rcdYuvParamMaps[slot], &params, sizeof(params));

    // Dynamic per-submit CB (see acquireRcdCmd): never a twin reset.
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence submitFence = VK_NULL_HANDLE;
    {
        int cr = acquireRcdCmd(g, &cmd, &submitFence);
        if (cr != VFVK_OK) return -cr;
    }
    VkDescriptorBufferInfo inInfo{};
    inInfo.buffer = input.buffer;
    inInfo.offset = 0;
    inInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo rgbInfo{};
    rgbInfo.imageView = rgbImg.view;
    rgbInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo scrInfo{};
    scrInfo.imageView = scrImg.view;
    scrInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo paramInfo{};
    paramInfo.buffer = g->rcdYuvParamBufs[slot];
    paramInfo.offset = 0;
    paramInfo.range = sizeof(params);
    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = dstImg.buffer;
    outInfo.offset = 0;
    outInfo.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[5]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->rcdYuvSets[slot];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->rcdYuvSets[slot];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &rgbInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->rcdYuvSets[slot];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[2].pImageInfo = &scrInfo;
    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = g->rcdYuvSets[slot];
    writes[3].dstBinding = 3;
    writes[3].descriptorCount = 1;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[3].pBufferInfo = &paramInfo;
    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[4].dstSet = g->rcdYuvSets[slot];
    writes[4].dstBinding = 4;
    writes[4].descriptorCount = 1;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[4].pBufferInfo = &outInfo;
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
    // No reset: acquireRcdCmd hands out fresh-or-retired CBs.
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
    VkBufferMemoryBarrier paramBarrier{};
    paramBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    paramBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    paramBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    paramBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    paramBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    paramBarrier.buffer = g->rcdYuvParamBufs[slot];
    paramBarrier.offset = 0;
    paramBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &paramBarrier, 0, nullptr);
    // rgb + scratch arrive GENERAL from the mode submits (same-queue
    // FIFO ordered ahead of this dispatch); re-assert read access.
    layoutBarrier(cmd, rgbImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,  // A8: after modes 0-2
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    layoutBarrier(cmd, scrImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,  // A8: after modes 0-2
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
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, g->rcdYuvPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->rcdYuvPipelineLayout, 0, 1, &g->rcdYuvSets[slot], 0, nullptr);
    // Crop-domain dispatch (the shared CFA tile needs crop locality);
    // only canonical export samplers pack (see vf_rcdyuv.comp).
    const uint32_t gx = (uint32_t)(params.cDims[0] + 15) / 16;
    const uint32_t gy = (uint32_t)(params.cDims[1] + 7) / 8;
    vkCmdDispatch(cmd, gx, gy, 1);
    layoutBarrier(cmd, rgbImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    layoutBarrier(cmd, scrImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.dstAccessMask = 0;
    bufBarrier.srcQueueFamilyIndex = g->queueFamily;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
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
    vr = vkQueueSubmit(g->queue, 1, &submit, submitFence);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        vkDestroyFence(g->device, submitFence, nullptr);
        g->rcdFreeCbs.push_back(cmd);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    // A5: semaphore and set stay owned by the submission until its fence signals.
    g->rcdPending.push_back({cmd, submitFence, sem, g->rcdYuvSets[slot]});
    if (vr != VK_SUCCESS || fd < 0) {
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

