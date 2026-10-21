// ---- Phase-B grade stage (probe-only) ----

// Get-or-import a grade image (superpixel export as input, graded export as
// output). The probe owns a fixed pair; maps are cleared by reset/teardown.
static int gradeImage(Context* ctx, AHardwareBuffer* buf, const char* tag,
                      std::map<AHardwareBuffer*, ImportedImage>* cache, ImportedImage* out) {
    auto it = cache->find(buf);
    if (it != cache->end()) {
        *out = it->second;
        return VFVK_OK;
    }
    ImportedImage imported;
    int r = importAhb(ctx, buf, VK_IMAGE_USAGE_STORAGE_BIT, tag, false, &imported);
    if (r != VFVK_OK) return VFVK_OUTPUT_IMPORT_FAILED;
    AHardwareBuffer_acquire(buf);
    (*cache)[buf] = imported;
    *out = imported;
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_initF16Native(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->f16Pipeline != VK_NULL_HANDLE) return VFVK_OK;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createLinkedPipeline(g, code.data(), code.size(), &g->f16Pipeline)) {
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: f16 superpixel pipeline ready");
    return VFVK_OK;
}

// Wait-free twin resources: per-slot command buffers + isolated descriptor
// pools/sets (3 slots). Idempotent; shared by the grade and grade-YUV inits
// (whichever runs first brings them up).
static bool ensureTwinResources(Context* ctx) {
    if (ctx->twinComputeCmd[0] != VK_NULL_HANDLE) return true;
    // The recorder sets below are allocated from the grade layouts; a
    // null layout here means grade init has not run. Fail cleanly —
    // several Mali drivers segfault on null-layout allocation instead
    // of returning an error.
    if (ctx->setLayout == VK_NULL_HANDLE || ctx->gradeSetLayout == VK_NULL_HANDLE) {
        LOGW("vf-vk: twin resources need grade layouts first");
        return false;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 21;
    VkCommandBuffer twins[21];
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, twins) != VK_SUCCESS) {
        LOGW("vf-vk: twin command buffers failed");
        return false;
    }
    ctx->twinComputeCmd[0] = twins[0];
    ctx->twinComputeCmd[1] = twins[1];
    ctx->twinComputeCmd[2] = twins[2];
    ctx->twinGradeCmd[0] = twins[3];
    ctx->twinGradeCmd[1] = twins[4];
    ctx->twinGradeCmd[2] = twins[5];
    ctx->twinRcdCmd[0] = twins[6];
    ctx->twinRcdCmd[1] = twins[7];
    ctx->twinRcdCmd[2] = twins[8];
    ctx->twinMhcCmd[0] = twins[9];
    ctx->twinMhcCmd[1] = twins[10];
    ctx->twinMhcCmd[2] = twins[11];
    ctx->twinFusedCmd[0] = twins[12];
    ctx->twinFusedCmd[1] = twins[13];
    ctx->twinFusedCmd[2] = twins[14];
    ctx->twinPreviewCmd[0] = twins[15];
    ctx->twinPreviewCmd[1] = twins[16];
    ctx->twinPreviewCmd[2] = twins[17];
    ctx->twinRgb8Cmd[0] = twins[18];
    ctx->twinRgb8Cmd[1] = twins[19];
    ctx->twinRgb8Cmd[2] = twins[20];
    // setLayout needs 2 STORAGE_BUFFER + 1 STORAGE_IMAGE + 1 sampler
    // per set (bindings 0/3, 1, 2), so 3 sets need 6/3/3.
    VkDescriptorPoolSize recComputeSizes[3]{};
    recComputeSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    recComputeSizes[0].descriptorCount = 6;
    recComputeSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    recComputeSizes[1].descriptorCount = 3;
    recComputeSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    recComputeSizes[2].descriptorCount = 3;
    VkDescriptorPoolCreateInfo recComputePoolInfo{};
    recComputePoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    recComputePoolInfo.maxSets = 3;
    recComputePoolInfo.poolSizeCount = 3;
    recComputePoolInfo.pPoolSizes = recComputeSizes;
    VkDescriptorPoolSize recGradeSizes[1]{};
    recGradeSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    recGradeSizes[0].descriptorCount = 6;
    VkDescriptorPoolCreateInfo recGradePoolInfo{};
    recGradePoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    recGradePoolInfo.maxSets = 3;
    recGradePoolInfo.poolSizeCount = 1;
    recGradePoolInfo.pPoolSizes = recGradeSizes;
    if (vkCreateDescriptorPool(ctx->device, &recComputePoolInfo, nullptr, &ctx->recComputePool) != VK_SUCCESS ||
        vkCreateDescriptorPool(ctx->device, &recGradePoolInfo, nullptr, &ctx->recGradePool) != VK_SUCCESS) {
        LOGW("vf-vk: recorder descriptor pools failed");
        destroyGrade(ctx);
        return false;
    }
    for (int i = 0; i < 3; ++i) {
        VkDescriptorSetAllocateInfo recAlloc{};
        recAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        recAlloc.descriptorPool = ctx->recComputePool;
        recAlloc.descriptorSetCount = 1;
        recAlloc.pSetLayouts = &ctx->setLayout;
        VkResult recComputeRes =
            vkAllocateDescriptorSets(ctx->device, &recAlloc, &ctx->recComputeSets[i]);
        if (recComputeRes != VK_SUCCESS) {
            LOGW("vf-vk: recorder compute set %d failed %d", i, recComputeRes);
            destroyGrade(ctx);
            return false;
        }
        recAlloc.descriptorPool = ctx->recGradePool;
        recAlloc.pSetLayouts = &ctx->gradeSetLayout;
        VkResult recGradeRes =
            vkAllocateDescriptorSets(ctx->device, &recAlloc, &ctx->recGradeSets[i]);
        if (recGradeRes != VK_SUCCESS) {
            LOGW("vf-vk: recorder grade set %d failed %d", i, recGradeRes);
            destroyGrade(ctx);
            return false;
        }
    }
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_initGradeNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->gradePipeline != VK_NULL_HANDLE) return VFVK_OK;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createGradePipeline(g, code.data(), code.size())) {
        destroyGrade(g);
        return VFVK_PIPELINE_FAILED;
    }
    if (!ensureTwinResources(g)) {
        destroyGrade(g);
        return VFVK_DEVICE_FAILED;
    }
    LOGI("vf-vk: grade pipeline ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeNative(
    JNIEnv* env, jobject, jobject inBuffer, jobject outBuffer,
    jintArray idims, jfloatArray fparams) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->gradePipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (!inBuffer || !outBuffer || !idims || !fparams) return VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(fparams) < 15) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* inBuf = AHardwareBuffer_fromHardwareBuffer(env, inBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, outBuffer);
    if (!inBuf || !outBuf) return VFVK_BAD_ARGUMENT;
    ImportedImage inImg, outImg;
    int r = gradeImage(g, inBuf, "grade-in", &g->gradeIns, &inImg);
    if (r != VFVK_OK) return r;
    r = gradeImage(g, outBuf, "grade-out", &g->gradeOuts, &outImg);
    if (r != VFVK_OK) return r;

    GradeParams params{};
    jint ii[2];
    jfloat ff[15];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetFloatArrayRegion(fparams, 0, 15, ff);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.pad[0] = params.pad[1] = 0;
    for (int i = 0; i < 4; ++i) params.gains[i] = ff[i];
    for (int i = 0; i < 9; ++i) params.ccm[i / 3][i % 3] = ff[4 + i];
    params.misc[0] = ff[13];
    params.misc[1] = ff[14];
    params.misc[2] = params.misc[3] = 0.0f;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE) {
        return VFVK_BAD_ARGUMENT;
    }

    VkDescriptorImageInfo inInfo{};
    inInfo.imageView = inImg.view;
    inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = outImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->gradeSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->gradeSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    // Acquire the superpixel output (owned by FOREIGN after its dispatch).
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    // Fresh graded output.
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->gradePipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->gradePipelineLayout, 0, 1, &g->gradeSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->gradePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    // Release both images for the GL consumer (same pattern as superpixel).
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    vr = vkWaitForFences(g->device, 1, &g->fence, VK_TRUE, 2000000000ull);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: grade fence wait failed %d", vr);
        const VkResult retired = vkQueueWaitIdle(g->queue);
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    return VFVK_OK;
}

// ---- Wait-free submit twins (Direct-Log): same queue, ordered submits,
// no vkQueueWaitIdle. The consumer synchronizes via an exported sync fd
// (grade) or queue order (compute feeding grade). Blocking originals above
// are untouched (viewfinder + fallback path). ----

// Parse + upload one frame's lens-shading map (fp16 bits) into the
// per-slot RGBA16F image, via staging + an inline barriered blit
// submitted just ahead on the same queue. Null dims/gains (or any
// shape violation) disables shading for the frame; content validity
// (finite, >= 1) is enforced Kotlin-side by LensShadingModel. Always
// binds a valid view (slot or dummy), so a bad/missing map degrades
// the look, never the take.
void uploadShadeMap(JNIEnv* env, jintArray shadeDims, jshortArray shadeGains,
                    int slot, int32_t outDims[4], float outActive[4], VkImageView* outView) {
    outDims[0] = outDims[1] = 1;
    outDims[2] = 0;
    outDims[3] = 0;
    outActive[0] = outActive[1] = 0.0f;
    outActive[2] = outActive[3] = 1.0f;
    *outView = VK_NULL_HANDLE;
    bool usable = false;
    jint dd[6] = {0, 0, 0, 0, 1, 1};
    jsize gainLen = 0;
    if (shadeDims != nullptr && shadeGains != nullptr &&
        env->GetArrayLength(shadeDims) >= 6) {
        env->GetIntArrayRegion(shadeDims, 0, 6, dd);
        if (!env->ExceptionCheck()) {
            gainLen = env->GetArrayLength(shadeGains);
            const int rows = dd[0], cols = dd[1];
            if (rows >= 1 && rows <= kShadeMaxCells && cols >= 1 && cols <= kShadeMaxCells &&
                dd[4] > dd[2] && dd[5] > dd[3] &&
                gainLen == (jsize)rows * cols * 4) {
                usable = true;
            } else {
                LOGW("vf-vk: shade map shape %dx%d+%d,%d len=%d rejected",
                     cols, rows, (int)dd[2], (int)dd[3], (int)gainLen);
            }
        } else {
            env->ExceptionClear();
        }
    }
    if (usable && ensureShadeSampler(g) &&
        ensureShadeImage(g, slot, (int)dd[0], (int)dd[1])) {
        env->GetShortArrayRegion(shadeGains, 0, gainLen,
                                 reinterpret_cast<jshort*>(g->shadeStagingMaps[slot]));
        if (!env->ExceptionCheck()) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = g->shadeStagingMems[slot];
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            vkFlushMappedMemoryRanges(g->device, 1, &range);
            if (blitShadeImage(g, slot)) {
                outDims[0] = dd[0];
                outDims[1] = dd[1];
                outDims[2] = 1;
                // gainAt normalization, reciprocal form (the shader
                // multiplies): 1/max(w-1, 1) mirrors its clamp exactly.
                const int aw = dd[4] - dd[2] - 1;
                const int ah = dd[5] - dd[3] - 1;
                outActive[0] = (float)dd[2];
                outActive[1] = (float)dd[3];
                outActive[2] = 1.0f / (float)(aw > 1 ? aw : 1);
                outActive[3] = 1.0f / (float)(ah > 1 ? ah : 1);
                *outView = g->shadeViews[slot];
                return;
            }
        } else {
            env->ExceptionClear();
        }
    }
    // Disabled: bind the dummy so the layout stays valid.
    if (ensureShadeSampler(g) && ensureShadeDummy(g)) *outView = g->shadeDummyView;
}

// Mirror of computeNative without the fence wait: the following grade submit
// on the same queue is ordered after this one, so no CPU stall is needed.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeSubmitNative(
    JNIEnv* env, jobject, jobject inputBuffer, jobject expBuffer,
    jintArray iparams, jfloatArray fparams, jint slot,
    jintArray shadeDims, jshortArray shadeGains) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinComputeCmd[slot]), gateKey(g->recComputeSets[slot])})) return VFVK_BUSY;
    if (!inputBuffer || !expBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->twinComputeCmd[slot] == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    if (g->recComputeSets[slot] == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    AHardwareBuffer* expBuf = AHardwareBuffer_fromHardwareBuffer(env, expBuffer);
    if (!expBuf) return VFVK_BAD_ARGUMENT;
    // Recorder-private output import (never evicts the viewfinder's):
    // ping-pong pair cached by pointer, cleared by reset/teardown.
    ImportedImage recOut;
    auto roit = g->recOuts.find(expBuf);
    if (roit == g->recOuts.end()) {
        ImportedImage imported;
        int rout = importAhb(g, expBuf, VK_IMAGE_USAGE_STORAGE_BIT, "rec-out", false, &imported);
        if (rout != VFVK_OK) return VFVK_OUTPUT_IMPORT_FAILED;
        AHardwareBuffer_acquire(expBuf);
        g->recOuts[expBuf] = imported;
        recOut = imported;
    } else {
        recOut = roit->second;
    }
    Context::CmdSwap cmdSwap(g, g->twinComputeCmd[slot]);
    Context::SetSwap setSwap(&g->descriptorSet, g->recComputeSets[slot]);
    if (g->f16Pipeline == VK_NULL_HANDLE) return VFVK_PIPELINE_FAILED;
    Context::PipeSwap pipeSwap(g, g->f16Pipeline);
    Context::OutSwap outSwap(g, expBuf, recOut);
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;

    auto it = g->inputs.find(buf);
    if (it == g->inputs.end()) {
        ImportedBuffer imported;
        int r = importInputBuffer(g, buf, &imported);
        if (r != VFVK_OK) return VFVK_INPUT_IMPORT_FAILED;
        if (g->inputOrder.size() >= INPUT_CACHE_CAP) {
            evictOldestInput(g);
        }
        AHardwareBuffer_acquire(buf);
        g->inputs[buf] = imported;
        g->inputOrder.push_back(buf);
        it = g->inputs.find(buf);
    }
    const ImportedBuffer& input = it->second;

    SuperpixelParams params{};
    jint ipairs[10];
    jfloat fppairs[8];
    env->GetIntArrayRegion(iparams, 0, 10, ipairs);
    env->GetFloatArrayRegion(fparams, 0, 8, fppairs);
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    for (int i = 0; i < 4; ++i) params.chans[i] = ipairs[i];
    for (int i = 0; i < 4; ++i) params.black[i] = fppairs[i];
    for (int i = 0; i < 4; ++i) params.invRange[i] = fppairs[4 + i];
    params.quadBase[0] = ipairs[4];
    params.quadBase[1] = ipairs[5];
    params.frameSize[0] = ipairs[6];
    params.frameSize[1] = ipairs[7];
    params.step = ipairs[8];
    params.pitch = ipairs[9];
    if (params.frameSize[0] <= 0 || params.frameSize[1] <= 0 ||
        params.frameSize[0] > VFVK_MAX_EDGE || params.frameSize[1] > VFVK_MAX_EDGE ||
        params.pitch <= 0 || params.pitch > 16384) {
        return VFVK_BAD_ARGUMENT;
    }
    VkImageView shadeView = VK_NULL_HANDLE;
    uploadShadeMap(env, shadeDims, shadeGains, slot,
                   params.shadeDims, params.shadeActive, &shadeView);
    if (shadeView == VK_NULL_HANDLE) return VFVK_SUBMIT_FAILED;

    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = input.buffer;
    inputInfo.offset = 0;
    inputInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = g->output.view;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = shadeView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[3]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inputInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outputInfo;
    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = g->descriptorSet;
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 3, writes, 0, nullptr);

    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: submit-twin begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
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
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->pipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->pipelineLayout, 0, 1, &g->descriptorSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.frameSize[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.frameSize[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    bufBarrier.dstAccessMask = 0;
    bufBarrier.srcQueueFamilyIndex = g->queueFamily;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: submit-twin end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    // No fence, no wait: the grade submit below is ordered after this one on
    // the same queue, and the fd handoff carries completion to GL.
    VkFence gateF = gateFence(g);
    if (gateF == VK_NULL_HANDLE) {
        return -VFVK_SUBMIT_FAILED;
    }
    vr = vkQueueSubmit(g->queue, 1, &submit, gateF);
    if (vr != VK_SUCCESS) {
        g->gateFencePool.push_back(gateF);
        LOGW("vf-vk: submit-twin submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    slotSubmitted(g, {gateKey(g->twinComputeCmd[slot]), gateKey(g->recComputeSets[slot])}, gateF, VK_NULL_HANDLE);
    return VFVK_OK;
}

// Mirror of gradeNative that signals an exportable binary semaphore instead
// of waiting, then returns the sync fd for the EGL native-fence handoff.
// Returns the fd (>= 0) or a negative -VFVK_* code (caller falls back to the
// blocking gradeNative). The exported fd owns the signal; the VkSemaphore is
// destroyed after export.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfLogGrade_gradeSubmitNative(
    JNIEnv* env, jobject, jobject inBuffer, jobject outBuffer,
    jintArray idims, jfloatArray fparams, jint slot) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return -VFVK_NOT_INITIALIZED;
    if (slot < 0 || slot > 2) return -VFVK_BAD_ARGUMENT;
    // A3/A4: never rewrite or reset slot state the GPU still executes.
    if (!slotIdle(g, {gateKey(g->twinGradeCmd[slot]), gateKey(g->recGradeSets[slot])})) return -VFVK_BUSY;
    Context::CmdSwap cmdSwap(g, g->twinGradeCmd[slot]);
    if (g->recGradeSets[slot] == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    Context::SetSwap setSwap(&g->gradeSet, g->recGradeSets[slot]);
    if (g->gradePipeline == VK_NULL_HANDLE) return -VFVK_PIPELINE_FAILED;
    if (!g->hasSemaphoreFd) return -1;
    if (!inBuffer || !outBuffer || !idims || !fparams) return -VFVK_BAD_ARGUMENT;
    if (env->GetArrayLength(idims) < 2 || env->GetArrayLength(fparams) < 15) return -VFVK_BAD_ARGUMENT;
    AHardwareBuffer* inBuf = AHardwareBuffer_fromHardwareBuffer(env, inBuffer);
    AHardwareBuffer* outBuf = AHardwareBuffer_fromHardwareBuffer(env, outBuffer);
    if (!inBuf || !outBuf) return -VFVK_BAD_ARGUMENT;
    ImportedImage inImg, outImg;
    int r = gradeImage(g, inBuf, "grade-in", &g->gradeIns, &inImg);
    if (r != VFVK_OK) return -r;
    r = gradeImage(g, outBuf, "grade-out", &g->gradeOuts, &outImg);
    if (r != VFVK_OK) return -r;

    GradeParams params{};
    jint ii[2];
    jfloat ff[15];
    env->GetIntArrayRegion(idims, 0, 2, ii);
    env->GetFloatArrayRegion(fparams, 0, 15, ff);
    if (env->ExceptionCheck()) return -VFVK_BAD_ARGUMENT;
    params.dims[0] = ii[0];
    params.dims[1] = ii[1];
    params.pad[0] = params.pad[1] = 0;
    for (int i = 0; i < 4; ++i) params.gains[i] = ff[i];
    for (int i = 0; i < 9; ++i) params.ccm[i / 3][i % 3] = ff[4 + i];
    params.misc[0] = ff[13];
    params.misc[1] = ff[14];
    params.misc[2] = params.misc[3] = 0.0f;
    if (params.dims[0] <= 0 || params.dims[1] <= 0 ||
        params.dims[0] > VFVK_MAX_EDGE || params.dims[1] > VFVK_MAX_EDGE) {
        return -VFVK_BAD_ARGUMENT;
    }

    VkDescriptorImageInfo inInfo{};
    inInfo.imageView = inImg.view;
    inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outInfo{};
    outInfo.imageView = outImg.view;
    outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->gradeSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[0].pImageInfo = &inInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->gradeSet;
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
        LOGW("vf-vk: grade-submit semaphore create failed");
        return -VFVK_SUBMIT_FAILED;
    }

    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        LOGW("vf-vk: grade-submit begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->gradePipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->gradePipelineLayout, 0, 1, &g->gradeSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->gradePipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                       0, sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.dims[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.dims[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    layoutBarrier(g->commandBuffer, inImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    layoutBarrier(g->commandBuffer, outImg.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        vkDestroySemaphore(g->device, sem, nullptr);
        LOGW("vf-vk: grade-submit end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
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
        LOGW("vf-vk: grade-submit submit failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? -VFVK_DEVICE_LOST : -VFVK_SUBMIT_FAILED;
    }
    VkSemaphoreGetFdInfoKHR fdInfo{};
    fdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    fdInfo.semaphore = sem;
    fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    int fd = -1;
    vr = g->getSemFd(g->device, &fdInfo, &fd);
    // A5: the semaphore lives until the submit's fence signals.
    slotSubmitted(g, {gateKey(g->twinGradeCmd[slot]), gateKey(g->recGradeSets[slot])}, gateF, sem);
    if (vr != VK_SUCCESS || fd < 0) {
        LOGW("vf-vk: grade-submit fd export failed %d", vr);
        return -VFVK_SUBMIT_FAILED;
    }
    return fd;
}

