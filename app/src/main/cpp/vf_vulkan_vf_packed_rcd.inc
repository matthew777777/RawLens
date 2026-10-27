// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted execution for third_party/rcd-packed-upstream. The old RCD backend
// remains independent. All four stages share one command buffer; internal
// quarter-resolution images stay owned by this Vulkan queue throughout.
struct PackedRcdGradeParams {
    RcdYuvParams grade{};
    int32_t shadeDims[4]{};
    float shadeActive[4]{};
};
static_assert(sizeof(PackedRcdGradeParams) == 192, "Packed RCD shade SSBO layout");
static_assert(sizeof(PackedRcdGradeParams) <= 256, "Fits each RCD grade parameter allocation");

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfPackedRcd_initNative(
    JNIEnv* env, jobject, jobjectArray stages, jint localX, jint localY, jboolean fused, jboolean tuned) {
    const std::lock_guard<std::mutex> guard(vkMutex);
    if (!g) return VFVK_NOT_INITIALIZED;
    if (!g->hasF16Math || !g->hasExtendedStorageFormats || !stages || env->GetArrayLength(stages) != 4 ||
        localX < 1 || localX > 32 || localY < 1 || localY > 32 ||
        (fused && (localY < 2 || localY % 2))) return VFVK_BAD_ARGUMENT;
    auto& s = g->packedRcd;
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(g->gpu, &properties);
    if ((uint32_t)(localX * localY) > properties.limits.maxComputeWorkGroupInvocations ||
        (uint32_t)localX > properties.limits.maxComputeWorkGroupSize[0] ||
        (uint32_t)localY > properties.limits.maxComputeWorkGroupSize[1]) return VFVK_BAD_ARGUMENT;
    std::vector<std::vector<uint32_t>> code(4);
    for (int i = 0; i < 4; ++i) {
        auto bytes = (jbyteArray)env->GetObjectArrayElement(stages, i);
        if (!bytes) return VFVK_BAD_ARGUMENT;
        const jsize size = env->GetArrayLength(bytes);
        if (size <= 0 || size % 4) { env->DeleteLocalRef(bytes); return VFVK_BAD_ARGUMENT; }
        code[i].resize(size / 4);
        env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(code[i].data()));
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    }
    uint64_t shaderHash = 1469598103934665603ull;
    for (const auto& stage : code) for (uint32_t word : stage) { shaderHash ^= word; shaderHash *= 1099511628211ull; }
    if (s.pipelines[3] && s.localX == localX && s.localY == localY && s.shaderHash == shaderHash && s.yuv == (fused == JNI_TRUE) && s.tuned == (tuned == JNI_TRUE)) return VFVK_OK;
    if (vkDeviceWaitIdle(g->device) != VK_SUCCESS) return VFVK_DEVICE_LOST;
    destroyPackedRcd(g);
    if (!createPackedRcd(g, code, localX, localY, tuned == JNI_TRUE)) {
        destroyPackedRcd(g); return VFVK_PIPELINE_FAILED;
    }
    s.shaderHash = shaderHash;
    s.yuv = fused == JNI_TRUE;
    LOGI("vf-vk: Packed-ratio RCD ready, workgroup %dx%d", localX, localY);
    return VFVK_OK;
}

static jint runPackedRcd(JNIEnv* env, jobject cfaBuffer, jobject rgbBuffer,
    jintArray iparams, jfloatArray fparams, jobject p010Buffer,
    const RcdYuvParams* gradeParams, const jint* strides, int asyncSlot = -1, jintArray shadeDims = nullptr, jshortArray shadeGains = nullptr) {
    const std::lock_guard<std::mutex> guard(vkMutex);
    if (!g) return -VFVK_NOT_INITIALIZED;
    auto& s = g->packedRcd;
    if (!s.pipelines[3]) return -VFVK_PIPELINE_FAILED;
    const bool fused = p010Buffer != nullptr;
    const bool async = asyncSlot >= 0;
    if (asyncSlot > 2 || (async && (!fused || !g->hasSemaphoreFd))) return -VFVK_BAD_ARGUMENT;
    const int slot = async ? asyncSlot : 0;
    const bool timed = s.queries && !async;
    if (s.yuv != fused) return -VFVK_BAD_ARGUMENT;
    if (!cfaBuffer || (!fused && !rgbBuffer) || !iparams || !fparams ||
        env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return -VFVK_BAD_ARGUMENT;
    jint ii[10]; jfloat ff[8];
    env->GetIntArrayRegion(iparams, 0, 10, ii);
    env->GetFloatArrayRegion(fparams, 0, 8, ff);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    RcdParams params{};
    params.dims[0] = ii[0]; params.dims[1] = ii[1];
    params.origin[0] = ii[2]; params.origin[1] = ii[3];
    for (int i = 0; i < 4; ++i) {
        params.chans[i] = ii[4 + i]; params.black[i] = ff[i]; params.invRange[i] = ff[4 + i];
    }
    params.pitch = ii[8]; params.mode = 0;
    if (ii[0] < 2 || ii[1] < 2 || ii[0] > VFVK_MAX_EDGE || ii[1] > VFVK_MAX_EDGE ||
        ii[2] < 0 || ii[3] < 0 || ii[8] < ii[0] + ii[2] || ii[8] > 16384) return -VFVK_BAD_ARGUMENT;
    AHardwareBuffer* cfa = AHardwareBuffer_fromHardwareBuffer(env, cfaBuffer);
    AHardwareBuffer* rgb = rgbBuffer ? AHardwareBuffer_fromHardwareBuffer(env, rgbBuffer) : nullptr;
    AHardwareBuffer* p010 = fused ? AHardwareBuffer_fromHardwareBuffer(env, p010Buffer) : nullptr;
    if (!cfa || (fused ? !p010 : !rgb)) return -VFVK_BAD_ARGUMENT;
    // The exported sync fd can signal slightly before the submission fence.
    // Reuse is gated by the actual fence, rather than dropping an otherwise
    // complete frame after the generic 2 ms grace period.
    for (const auto& pending : g->rcdPending) if (pending.set == s.sets[slot * 4]) {
        VkResult retired = vkWaitForFences(g->device, 1, &pending.fence, VK_TRUE, 100'000'000ull);
        if (retired != VK_SUCCESS) return retired == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_BUSY;
    }
    if (!fused) {
        AHardwareBuffer_Desc description{};
        AHardwareBuffer_describe(rgb, &description);
        if (description.width != (uint32_t)ii[0] || description.height != (uint32_t)ii[1] ||
            description.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) return -VFVK_BAD_ARGUMENT;
    } else if (!gradeParams || !strides || gradeParams->oDims[0] < 2 || gradeParams->oDims[1] < 2) {
        return -VFVK_BAD_ARGUMENT;
    }
    auto input = g->inputs.find(cfa);
    if (input == g->inputs.end()) {
        ImportedBuffer imported;
        if (importInputBuffer(g, cfa, &imported) != VFVK_OK) return -VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) evictOldestInput(g);
        AHardwareBuffer_acquire(cfa); g->inputs[cfa] = imported; g->inputOrder.push_back(cfa);
        input = g->inputs.find(cfa);
    }
    ImportedImage output{};
    int result = VFVK_OK;
    if (!fused) {
        result = gradeImage(g, rgb, "packed-rcd-rgb", &g->rgbOuts, &output);
        if (result != VFVK_OK) return -result;
    }
    if (!ensurePackedRcdImages(g, ii[0], ii[1])) return -VFVK_DEVICE_FAILED;
    ImportedBuffer p010Output{};
    if (fused) {
        result = importP010Out(g, p010, strides[0], strides[1], strides[2], strides[3], &p010Output);
        if (result != VFVK_OK) return result == -20 ? -20 : -result;
    }
    PackedRcdGradeParams packedGrade{};
    if (gradeParams) packedGrade.grade = *gradeParams;
    VkImageView shadeView = VK_NULL_HANDLE;
    uploadShadeMap(env, shadeDims, shadeGains, slot,
        packedGrade.shadeDims, packedGrade.shadeActive, &shadeView);
    if (!shadeView || ((shadeDims || shadeGains) && !packedGrade.shadeDims[2])) return -VFVK_COPY_UPLOAD_FAILED;
    memcpy(g->rcdYuvParamMaps[slot], &packedGrade, sizeof(packedGrade));
    VkDescriptorBufferInfo raw{input->second.buffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo packedOutput{fused ? p010Output.buffer : raw.buffer, 0, VK_WHOLE_SIZE};
    VkDescriptorBufferInfo gradeInfo{g->rcdYuvParamBufs[slot], 0, sizeof(PackedRcdGradeParams)};
    VkDescriptorImageInfo shadeInfo{g->shadeSampler, shadeView, VK_IMAGE_LAYOUT_GENERAL};
    VkDescriptorImageInfo sampled[3]{};
    for (int i = 0; i < 3; ++i) sampled[i] = {s.sampler, s.views[i], VK_IMAGE_LAYOUT_GENERAL};
    for (int stage = 0; stage < 4; ++stage) {
        VkDescriptorImageInfo destination{VK_NULL_HANDLE,
            stage < 3 ? s.views[stage] : fused ? s.views[0] : output.view, VK_IMAGE_LAYOUT_GENERAL};
        VkWriteDescriptorSet writes[8]{};
        const uint32_t bindings[8] = {0, 2, 6, 7, 8, 9, 10, 4};
        for (int i = 0; i < 8; ++i) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = s.sets[slot * 4 + stage]; writes[i].dstBinding = bindings[i];
            writes[i].descriptorCount = 1;
            writes[i].descriptorType = (i == 0 || i == 5 || i == 6) ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER :
                i == 1 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            if (i == 0) writes[i].pBufferInfo = &raw;
            else if (i == 5 || i == 6) writes[i].pBufferInfo = i == 5 ? &packedOutput : &gradeInfo;
            else writes[i].pImageInfo = i == 7 ? &shadeInfo : i == 1 ? &destination : &sampled[i - 2];
        }
        vkUpdateDescriptorSets(g->device, 8, writes, 0, nullptr);
    }
    VkCommandBuffer cmd = VK_NULL_HANDLE; VkFence fence = VK_NULL_HANDLE;
    result = acquireRcdCmd(g, &cmd, &fence);
    if (result != VFVK_OK) return -result;
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &begin) != VK_SUCCESS) {
        vkDestroyFence(g->device, fence, nullptr);
        g->rcdFreeCbs.push_back(cmd);
        return -VFVK_SUBMIT_FAILED;
    }
    if (timed) vkCmdResetQueryPool(cmd, s.queries, 0, 8);
    VkBufferMemoryBarrier acquire{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    acquire.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    acquire.dstQueueFamilyIndex = g->queueFamily;
    acquire.buffer = raw.buffer; acquire.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 1, &acquire, 0, nullptr);
    for (int i = 0; i < 3; ++i) {
        layoutBarrier(cmd, s.images[i], s.initialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            s.initialized ? (VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT) : 0,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            s.initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    }
    VkBufferMemoryBarrier outputBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    if (fused) {
        outputBarrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        outputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        outputBarrier.dstQueueFamilyIndex = g->queueFamily;
        outputBarrier.buffer = p010Output.buffer; outputBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0, 0, nullptr, 1, &outputBarrier, 0, nullptr);
    } else {
        layoutBarrier(cmd, output.image, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    }
    VkBufferMemoryBarrier parameters{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    parameters.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    parameters.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    parameters.srcQueueFamilyIndex = parameters.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    parameters.buffer = gradeInfo.buffer; parameters.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, nullptr, 1, &parameters, 0, nullptr);
    for (int stage = 0; stage < 4; ++stage) {
        if (timed) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, s.queries, stage * 2);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s.pipelines[stage == 3 && fused ? 3 + int(gradeParams->gMisc2[1]) +
            (ii[0] != gradeParams->oDims[0] || ii[1] != gradeParams->oDims[1] ? 4 : 0) : stage]);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s.layout, 0, 1, &s.sets[slot * 4 + stage], 0, nullptr);
        vkCmdPushConstants(cmd, s.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(params), &params);
        const uint32_t lx = s.stageLocalX[stage], ly = s.stageLocalY[stage];
        const uint32_t dispatchWidth = stage == 3 && fused ? (gradeParams->oDims[0] + 1) / 2 : (ii[0] + 1) / 2;
        const uint32_t dispatchHeight = stage == 3 && fused ? gradeParams->oDims[1] : (ii[1] + 1) / 2;
        vkCmdDispatch(cmd, (dispatchWidth + lx - 1) / lx, (dispatchHeight + ly - 1) / ly, 1);
        if (timed) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, s.queries, stage * 2 + 1);
        if (stage < 3) layoutBarrier(cmd, s.images[stage], VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    }
    if (fused) {
        outputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; outputBarrier.dstAccessMask = 0;
        outputBarrier.srcQueueFamilyIndex = g->queueFamily;
        outputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, nullptr, 1, &outputBarrier, 0, nullptr);
    } else {
        layoutBarrier(cmd, output.image, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    }
    acquire.srcAccessMask = VK_ACCESS_SHADER_READ_BIT; acquire.dstAccessMask = 0;
    acquire.srcQueueFamilyIndex = g->queueFamily; acquire.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                         0, 0, nullptr, 1, &acquire, 0, nullptr);
    if (vkEndCommandBuffer(cmd) != VK_SUCCESS) {
        vkDestroyFence(g->device, fence, nullptr);
        g->rcdFreeCbs.push_back(cmd);
        return -VFVK_SUBMIT_FAILED;
    }
    VkSemaphore sem = VK_NULL_HANDLE;
    if (async) {
        VkExportSemaphoreCreateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
        exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        VkSemaphoreCreateInfo semInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        semInfo.pNext = &exportInfo;
        if (vkCreateSemaphore(g->device, &semInfo, nullptr, &sem) != VK_SUCCESS) {
            vkDestroyFence(g->device, fence, nullptr);
            g->rcdFreeCbs.push_back(cmd);
            return -VFVK_SUBMIT_FAILED;
        }
    }
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1; submit.pCommandBuffers = &cmd;
    if (async) { submit.signalSemaphoreCount = 1; submit.pSignalSemaphores = &sem; }
    VkResult vr = vkQueueSubmit(g->queue, 1, &submit, fence);
    if (vr != VK_SUCCESS) {
        if (sem) vkDestroySemaphore(g->device, sem, nullptr);
        vkDestroyFence(g->device, fence, nullptr);
        g->rcdFreeCbs.push_back(cmd);
        return -VFVK_SUBMIT_FAILED;
    }
    s.initialized = true;
    g->rcdPending.push_back({cmd, fence, sem, s.sets[slot * 4]});
    if (async) {
        VkSemaphoreGetFdInfoKHR info{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
        info.semaphore = sem;
        info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
        int fd = -1;
        vr = g->getSemFd(g->device, &info, &fd);
        if (vr == VK_SUCCESS && fd >= 0) return fd;
        // Export may report an already-signaled payload (-1). Prove completion
        // before returning the recorder's completed sentinel; never release RAW early.
        vr = vkWaitForFences(g->device, 1, &fence, VK_TRUE, 5'000'000'000ull);
        return vr == VK_SUCCESS ? -1 : -VFVK_BUSY;
    }
    // Serial ownership is deliberate: descriptors and the internal images may
    // be reused only after all stages finish, and RAW stays leased until here.
    vr = vkWaitForFences(g->device, 1, &fence, VK_TRUE, 5'000'000'000ull);
    if (vr != VK_SUCCESS) return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_BUSY;
    if (timed) {
        uint64_t ticks[8]{};
        if (vkGetQueryPoolResults(g->device, s.queries, 0, 8, sizeof(ticks), ticks,
                                  sizeof(uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(g->gpu, &properties);
            for (int i = 0; i < 4; ++i) s.stageMs[i] =
                float(double((ticks[2 * i + 1] - ticks[2 * i]) & (s.timestampBits >= 64 ? UINT64_MAX : ((1ull << s.timestampBits) - 1))) * properties.limits.timestampPeriod / 1e6);
        }
    }
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfPackedRcd_processNative(JNIEnv* env, jobject, jobject cfa, jobject rgb,
                                               jintArray ip, jfloatArray fp, jintArray shadeDims, jshortArray shadeGains) {
    return runPackedRcd(env, cfa, rgb, ip, fp, nullptr, nullptr, nullptr, -1, shadeDims, shadeGains);
}

static jint packedRcdYuv(JNIEnv* env, jobject cfa, jobject p010,
    jintArray ip, jfloatArray fp, jfloatArray grade, jintArray strides, int slot, jintArray shadeDims, jshortArray shadeGains) {
    if (!grade || !strides || env->GetArrayLength(grade) < 18 || env->GetArrayLength(strides) < 4) return -VFVK_BAD_ARGUMENT;
    jfloat gf[18]; jint st[4];
    env->GetFloatArrayRegion(grade, 0, 18, gf);
    env->GetIntArrayRegion(strides, 0, 4, st);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    if (st[2] < 2 || st[3] < 2 || st[2] > VFVK_MAX_EDGE || st[3] > VFVK_MAX_EDGE ||
        (st[2] & 1) || (st[3] & 1) || st[0] < st[2] * 2 || st[1] < st[2] * 2 ||
        (st[0] % 4) || (st[1] % 4)) return -VFVK_BAD_ARGUMENT;
    const int mode = int(gf[17] + 0.5f);
    if (mode < 0 || mode > 3) return -VFVK_BAD_ARGUMENT;
    RcdYuvParams params{};
    params.cDims[0] = params.oDims[0] = st[2];
    params.cDims[1] = params.oDims[1] = st[3];
    foldGradeCcm(gf, params.gCcm);
    params.gMisc[1] = float(st[0] / 2); params.gMisc[2] = float(st[1] / 2);
    params.gMisc[3] = float(int64_t(st[0]) * st[3]);
    params.gMisc2[0] = 1.0f; params.gMisc2[1] = float(mode); params.gMisc2[2] = gf[16];
    return runPackedRcd(env, cfa, nullptr, ip, fp, p010, &params, st, slot, shadeDims, shadeGains);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfPackedRcd_processYuvNative(JNIEnv* env, jobject, jobject cfa, jobject p010,
    jintArray ip, jfloatArray fp, jfloatArray grade, jintArray strides, jintArray shadeDims, jshortArray shadeGains) {
    return packedRcdYuv(env, cfa, p010, ip, fp, grade, strides, -1, shadeDims, shadeGains);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfPackedRcd_submitYuvNative(JNIEnv* env, jobject, jobject cfa, jobject p010,
    jintArray ip, jfloatArray fp, jfloatArray grade, jintArray strides, jint slot, jintArray shadeDims, jshortArray shadeGains) {
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    return packedRcdYuv(env, cfa, p010, ip, fp, grade, strides, slot, shadeDims, shadeGains);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_matthew_rawlens_VfPackedRcd_timingsNative(JNIEnv* env, jobject) {
    const std::lock_guard<std::mutex> guard(vkMutex);
    if (!g) return nullptr;
    jfloatArray out = env->NewFloatArray(4);
    if (out) env->SetFloatArrayRegion(out, 0, 4, g->packedRcd.stageMs);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfPackedRcd_validateP010Native(JNIEnv* env, jobject, jobject buffer, jint width, jint height) {
    const std::lock_guard<std::mutex> guard(vkMutex);
    if (!g || !buffer || width < 2 || height < 2 || width % 2 || height % 2) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    if (!ahb) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_YCbCr_P010 || desc.width != (uint32_t)width ||
        desc.height != (uint32_t)height || !(desc.usage & AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER)) return VFVK_BAD_ARGUMENT;
    ImportedBuffer imported{};
    return importP010Out(g, ahb, desc.stride * 2, desc.stride * 2, width, height, &imported);
}
