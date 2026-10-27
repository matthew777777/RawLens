// Shared device bring-up for initNative (first use) and reinitNative (recovery
// after the queue wedges or the device is lost mid-session). Requires g == nullptr.
static int initContext(JNIEnv* env, jbyteArray spv);

// Wait (bounded) for an in-flight viewfinder dispatch. computeNative
// waits UNLOCKED, so teardown/reset must join it before destroying the
// imports/output it reads/writes. Bounded at ~2s: the wait itself is
// capped at 500ms, so expiry needs a driver ignoring timeouts —
// teardown then proceeds loudly (the worker re-validates g after its
// wait and bails). Requires vkMutex (released while spinning).
// Returns false when the worker is still inside its fence wait (B1:
// callers must then not destroy the fence/device it is waiting on).
static bool joinVfCompute(std::unique_lock<std::mutex>& lock) {
    int spins = 0;
    while (vfComputeInFlight.load() > 0 && spins < 400) {
        lock.unlock();
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        lock.lock();
        ++spins;
    }
    if (vfComputeInFlight.load() > 0) {
        LOGW("vf-vk: teardown with VF compute in flight (wedged worker?)");
        return false;
    }
    return true;
}

static void destroyContext(Context* ctx);

// B1: a context retired while a viewfinder worker was still inside its
// unlocked vkWaitForFences on ctx->fence/ctx->device. Destroying it then
// would free the objects under the wait; the worker destroys it instead
// once it returns (reapOrphanContext, under vkMutex).
static Context* orphanCtx = nullptr;

static void reapOrphanContext() {
    if (orphanCtx != nullptr && vfComputeInFlight.load() == 0) {
        Context* o = orphanCtx;
        orphanCtx = nullptr;
        LOGI("vf-vk: destroying context retired during a stuck VF wait");
        destroyContext(o);
    }
}

// [joined] = joinVfCompute's result: false parks the context as the orphan.
static void teardownContext(bool joined = true) {
    if (g == nullptr) return;
    Context* ctx = g;
    g = nullptr;
    vkGeneration.fetch_add(1);
    if (!joined) {
        if (orphanCtx == nullptr) {
            orphanCtx = ctx;
        } else {
            LOGW("vf-vk: second stuck teardown; leaking a context rather than freeing it under a wait");
        }
        return;
    }
    destroyContext(ctx);
}

static void destroyContext(Context* ctx) {
    // Best-effort idle first: returns promptly (with an error) on a lost device.
    vkDeviceWaitIdle(ctx->device);
    destroyGrade(ctx);
    destroyImagePipeline(ctx);
    destroyBridge(ctx);
    destroyShadeDummyImage(ctx);
    destroyShadeSampler(ctx);
    destroyGradeLut(ctx);
    for (auto& entry : ctx->inputs) destroyImportedBuffer(ctx, &entry.second);
    for (AHardwareBuffer* b : ctx->inputOrder) AHardwareBuffer_release(b);
    ctx->inputs.clear();
    ctx->inputOrder.clear();
    for (auto& entry : ctx->outputs) destroyImported(ctx, &entry.second);
    for (AHardwareBuffer* b : ctx->outputOrder) AHardwareBuffer_release(b);
    ctx->outputs.clear();
    ctx->outputOrder.clear();
    ctx->output = ImportedImage{};
    ctx->outputBuffer = nullptr;
    destroyStaging(ctx);
    destroyLut(ctx);
    if (ctx->fence != VK_NULL_HANDLE) vkDestroyFence(ctx->device, ctx->fence, nullptr);
    if (ctx->commandPool != VK_NULL_HANDLE)
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
    if (ctx->pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->pipeline, nullptr);
    if (ctx->f16Pipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->f16Pipeline, nullptr);
    if (ctx->pipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->pipelineLayout, nullptr);
    if (ctx->setLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->setLayout, nullptr);
    if (ctx->descriptorPool != VK_NULL_HANDLE)
        vkDestroyDescriptorPool(ctx->device, ctx->descriptorPool, nullptr);
    savePipelineCache(ctx);
    if (ctx->pipelineCache != VK_NULL_HANDLE)
        vkDestroyPipelineCache(ctx->device, ctx->pipelineCache, nullptr);
    VkDevice device = ctx->device;
    VkInstance instance = ctx->instance;
    delete ctx;
    if (device != VK_NULL_HANDLE) vkDestroyDevice(device, nullptr);
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_initNative(
    JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g != nullptr) return VFVK_OK;
    return initContext(env, spv);
}

// Persistence file for the process pipeline cache. Takes effect for the
// live device (subsequently created pipelines) and every later (re)init.
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfVulkan_setPipelineCachePathNative(
    JNIEnv* env, jobject, jstring path) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    const char* chars = (env != nullptr && path != nullptr)
                            ? env->GetStringUTFChars(path, nullptr)
                            : nullptr;
    gPipelineCachePath = (chars != nullptr) ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    if (g != nullptr) g->pipelineCachePath = gPipelineCachePath;
}

// Full teardown + bring-up after persistent submit failures (wedged queue or
// lost device): imports are dropped and re-created on demand, so the next
// frame re-probes a fresh device instead of failing on a dead one forever.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_reinitNative(
    JNIEnv* env, jobject, jbyteArray spv) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    LOGW("vf-vk: reinitializing device after submit failures");
    teardownContext(joinVfCompute(vkGuard));
    int r = initContext(env, spv);
    if (r == VFVK_OK) {
        LOGI("vf-vk: device reinit ok");
    } else {
        LOGW("vf-vk: device reinit failed %d", r);
    }
    return r;
}

static int initContext(JNIEnv* env, jbyteArray spv) {
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;

    Context* ctx = new Context();
    ctx->pipelineCachePath = gPipelineCachePath;
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "RawLensVf";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &app;
    if (vkCreateInstance(&instInfo, nullptr, &ctx->instance) != VK_SUCCESS) {
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    uint32_t gpuCount = 0;
    vkEnumeratePhysicalDevices(ctx->instance, &gpuCount, nullptr);
    VkPhysicalDevice gpus[8];
    uint32_t n = gpuCount > 8 ? 8 : gpuCount;
    if (n == 0 || vkEnumeratePhysicalDevices(ctx->instance, &n, gpus) != VK_SUCCESS) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    // Device scan: try every physical device, requiring the
    // mandatory external-memory extensions and a queue with BOTH graphics
    // and compute on that same device. Picking the first enumerated GPU
    // unconditionally stranded Adreno devices whose first GPU lacks AHB.
    bool hasExternalMem = false, hasAhb = false, hasForeign = false;
    bool hasSem = false, hasSemFd = false, hasF16 = false;
    bool anyQueue = false;
    for (uint32_t i = 0; i < n && ctx->gpu == VK_NULL_HANDLE; ++i) {
        VkPhysicalDeviceProperties devProps{};
        vkGetPhysicalDeviceProperties(gpus[i], &devProps);
        if (devProps.apiVersion < VK_API_VERSION_1_1) continue;
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &qCount, nullptr);
        VkQueueFamilyProperties props[16];
        uint32_t m = qCount > 16 ? 16 : qCount;
        vkGetPhysicalDeviceQueueFamilyProperties(gpus[i], &m, props);
        uint32_t family = UINT32_MAX;
        for (uint32_t q = 0; q < m; ++q) {
            if ((props[q].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
                (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) {
                family = q;
                break;
            }
        }
        if (family == UINT32_MAX) continue;
        anyQueue = true;
        uint32_t extCount = 0;
        if (vkEnumerateDeviceExtensionProperties(gpus[i], nullptr, &extCount, nullptr) !=
            VK_SUCCESS)
            continue;
        bool extMem = false, ahb = false, foreign = false, sem = false, semFd = false,
             f16 = false;
        uint32_t cap = extCount > 256 ? 256 : extCount;
        std::vector<VkExtensionProperties> exts(cap);
        if (vkEnumerateDeviceExtensionProperties(gpus[i], nullptr, &cap, exts.data()) ==
            VK_SUCCESS) {
            for (uint32_t e = 0; e < cap; ++e) {
                if (!std::strcmp(exts[e].extensionName,
                                 VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME))
                    foreign = true;
                if (!std::strcmp(exts[e].extensionName, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME))
                    extMem = true;
                if (!std::strcmp(exts[e].extensionName,
                                 VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME))
                    ahb = true;
                if (!std::strcmp(exts[e].extensionName, VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME))
                    sem = true;
                if (!std::strcmp(exts[e].extensionName,
                                 VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME))
                    semFd = true;
                if (!std::strcmp(exts[e].extensionName,
                                 VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME))
                    f16 = true;
            }
        }
        if (!extMem || !ahb || !foreign) continue;
        ctx->gpu = gpus[i];
        ctx->queueFamily = family;
        hasExternalMem = extMem;
        hasAhb = ahb;
        hasForeign = foreign;
        hasSem = sem;
        hasSemFd = semFd;
        hasF16 = f16;
        LOGI("vf-vk: selected device %s (gpu %u family %u)", devProps.deviceName, i, family);
    }
    if (ctx->gpu == VK_NULL_HANDLE) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return anyQueue ? VFVK_NO_EXTENSION : VFVK_DEVICE_FAILED;
    }
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = ctx->queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    // Optional pairs append independently: semaphore-fd (wait-free fast
    // path) and float16 math (fused record kernel; fp32 fallback otherwise).
    const bool wantSemFd = hasSem && hasSemFd;
    std::vector<const char*> devExt;
    devExt.push_back(VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME);
    devExt.push_back(VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
    devExt.push_back(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
    if (wantSemFd) {
        devExt.push_back(VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME);
        devExt.push_back(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    }
    VkPhysicalDeviceShaderFloat16Int8Features f16Features{};
    bool wantF16 = hasF16;
    if (wantF16) {
        // Presence is not enough: shaderFloat16 is a queryable feature.
        f16Features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES;
        VkPhysicalDeviceFeatures2 f2{};
        f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        f2.pNext = &f16Features;
        vkGetPhysicalDeviceFeatures2(ctx->gpu, &f2);
        wantF16 = f16Features.shaderFloat16 == VK_TRUE;
    }
    if (wantF16) devExt.push_back(VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);
    VkPhysicalDeviceFeatures supportedCore{};
    vkGetPhysicalDeviceFeatures(ctx->gpu, &supportedCore);
    VkPhysicalDeviceFeatures enabledCore{};
    enabledCore.shaderStorageImageExtendedFormats = supportedCore.shaderStorageImageExtendedFormats;
    ctx->hasExtendedStorageFormats = enabledCore.shaderStorageImageExtendedFormats == VK_TRUE;
    VkDeviceCreateInfo devInfo{};
    devInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devInfo.queueCreateInfoCount = 1;
    devInfo.pQueueCreateInfos = &queueInfo;
    devInfo.enabledExtensionCount = static_cast<uint32_t>(devExt.size());
    devInfo.ppEnabledExtensionNames = devExt.data();
    devInfo.pEnabledFeatures = &enabledCore;
    if (wantF16) {
        f16Features.shaderFloat16 = VK_TRUE;
        f16Features.shaderInt8 = VK_FALSE;
        f16Features.pNext = nullptr;
        devInfo.pNext = &f16Features;
    }
    if (vkCreateDevice(ctx->gpu, &devInfo, nullptr, &ctx->device) != VK_SUCCESS) {
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    vkGetDeviceQueue(ctx->device, ctx->queueFamily, 0, &ctx->queue);
    ctx->getAhbProps = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(ctx->device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!ctx->getAhbProps) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_NO_EXTENSION;
    }
    if (wantSemFd) {
        ctx->getSemFd = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
            vkGetDeviceProcAddr(ctx->device, "vkGetSemaphoreFdKHR"));
        ctx->hasSemaphoreFd = (ctx->getSemFd != nullptr);
        LOGI("vf-vk: semaphore-fd export %s", ctx->hasSemaphoreFd ? "ready" : "unavailable");
    }
    ctx->hasF16Math = wantF16;
    LOGI("vf-vk: float16 math %s", wantF16 ? "ready" : "unavailable (fp32 fallback)");
    if (!createPipeline(ctx, code.data(), code.size())) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_PIPELINE_FAILED;
    }
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = ctx->queueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    if (vkCreateCommandPool(ctx->device, &poolInfo, nullptr, &ctx->commandPool) != VK_SUCCESS) {
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkCommandBufferAllocateInfo cmdInfo{};
    cmdInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdInfo.commandPool = ctx->commandPool;
    cmdInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(ctx->device, &cmdInfo, &ctx->commandBuffer) != VK_SUCCESS) {
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(ctx->device, &fenceInfo, nullptr, &ctx->fence) != VK_SUCCESS) {
        vkDestroyCommandPool(ctx->device, ctx->commandPool, nullptr);
        vkDestroyDevice(ctx->device, nullptr);
        vkDestroyInstance(ctx->instance, nullptr);
        delete ctx;
        return VFVK_DEVICE_FAILED;
    }
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(ctx->gpu, &props);
    LOGI("vf-vk: device ready: %s", props.deviceName);
    g = ctx;
    return VFVK_OK;
}

static bool readSuperpixelParams(JNIEnv* env, jintArray iparams, jfloatArray fparams,
                                   SuperpixelParams* out) {
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return false;
    jint ipairs[10];
    jfloat fppairs[8];
    env->GetIntArrayRegion(iparams, 0, 10, ipairs);
    env->GetFloatArrayRegion(fparams, 0, 8, fppairs);
    if (env->ExceptionCheck()) return false;
    for (int i = 0; i < 4; ++i) out->chans[i] = ipairs[i];
    for (int i = 0; i < 4; ++i) out->black[i] = fppairs[i];
    for (int i = 0; i < 4; ++i) out->invRange[i] = fppairs[4 + i];
    out->quadBase[0] = ipairs[4];
    out->quadBase[1] = ipairs[5];
    out->frameSize[0] = ipairs[6];
    out->frameSize[1] = ipairs[7];
    out->step = ipairs[8];
    out->pitch = ipairs[9];
    if (out->frameSize[0] <= 0 || out->frameSize[1] <= 0 ||
        out->frameSize[0] > VFVK_MAX_EDGE || out->frameSize[1] > VFVK_MAX_EDGE ||
        out->pitch <= 0 || out->pitch > 16384) {
        return false;
    }
    return true;
}

// Shared superpixel dispatch tail (zero-copy import + staging copy):
// descriptor write, record, submit, bounded fence wait. `foreignInput`
// selects the external-memory acquire/release barriers (imported HAL
// buffer) vs host-write visibility (staging upload, no foreign owner).
// The fence wait runs UNLOCKED (see computeNative); the generation check
// after it catches a teardown that gave up the join.
static int dispatchSuperpixel(std::unique_lock<std::mutex>& vkGuard, const SuperpixelParams& params,
                              VkBuffer input, bool foreignInput) {
    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = input;
    inputInfo.offset = 0;
    inputInfo.range = VK_WHOLE_SIZE;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = g->output.view;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
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
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);

    // Shading binding (viewfinder shades in GL: dummy image, enable
    // stays 0).
    if (!ensureShadeSampler(g) || !ensureShadeDummy(g)) return VFVK_SUBMIT_FAILED;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = g->shadeDummyView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet shadeWrite{};
    shadeWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    shadeWrite.dstSet = g->descriptorSet;
    shadeWrite.dstBinding = 2;
    shadeWrite.descriptorCount = 1;
    shadeWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    shadeWrite.pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 1, &shadeWrite, 0, nullptr);

    // Normalize LUT (binding 3): created + bound once; per-frame bytes
    // land via uploadLutNative before compute.
    if (ensureLut(g) != VFVK_OK) return VFVK_SUBMIT_FAILED;

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.buffer = input;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    if (foreignInput) {
        // Adopt the HAL's fresh contents (buffer barrier + cache invalidate).
        bufBarrier.srcAccessMask = 0;
        bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        bufBarrier.dstQueueFamilyIndex = g->queueFamily;
        vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    } else {
        // Make the CPU memcpy visible to the shader (staging upload).
        bufBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        bufBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    }
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
    // Make writes available for the cross-API (GL) consumer before queue idle.
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                  g->queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT);
    if (foreignInput) {
        // Return the borrowed RAW allocation to Camera2/CPU as well. A fence alone
        // orders execution; it does not transfer external-memory ownership.
        bufBarrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        bufBarrier.dstAccessMask = 0;
        bufBarrier.srcQueueFamilyIndex = g->queueFamily;
        bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
        vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0, nullptr);
    }
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: submit failed %d", vr);
        // Fence was reset but nothing is in flight: disarm so the next
        // call does not reap-check a fence that will never signal.
        g->computeArmed = false;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = true;
    // Unlocked fence wait: the GL import below must see finished writes,
    // but this thread holds no mutex while record dispatches queued ahead
    // drain (~30ms in record mode), so record submits proceed freely.
    // Teardown/reset join vfComputeInFlight before destroying anything
    // this dispatch touches; the generation check below catches a
    // teardown that gave up the join (wedged worker) without touching
    // freed memory. A timeline-semaphore export is the follow-up.
    vfComputeInFlight.fetch_add(1);
    const uint64_t gen = vkGeneration.load();
    VkFence fence = g->fence;
    VkDevice device = g->device;
    vkGuard.unlock();
    vr = vkWaitForFences(device, 1, &fence, VK_TRUE, VFVK_VF_WAIT_NS);
    vkGuard.lock();
    vfComputeInFlight.fetch_sub(1);
    if (g == nullptr || vkGeneration.load() != gen) {
        reapOrphanContext();  // B1: free a context retired during this wait
        return VFVK_NOT_INITIALIZED;
    }
    // Timeout: NO retire (a WaitIdle here would reintroduce the convoy
    // on the failure path). The dispatch may still be reading its input
    // when the caller releases the Image lease, but its output is never
    // presented (BUSY skips; the next submit overwrites the export), so
    // the worst case is garbage pixels nobody samples. Memory stays
    // mapped (import holds the buffer), so no fault is possible.
    if (vr == VK_TIMEOUT) return VFVK_BUSY;
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: fence wait failed %d; retiring submitted RAW reads before release", vr);
        // The caller releases its camera Image lease on return. A failed fence
        // alone is not permission to let the HAL overwrite that allocation.
        const VkResult retired = vkQueueWaitIdle(g->queue);
        // Queue idle => nothing in flight (armed cleared).
        g->computeArmed = false;
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = false;
    return VFVK_OK;
}

// Sampled-image dispatch: same fence/export discipline as dispatchSuperpixel,
// but the input is the imported R16 image. Foreign acquire/release follows
// Acquire: GENERAL -> SHADER_READ_ONLY on FOREIGN_EXT -> queue, then back.
static int dispatchSuperpixelImage(std::unique_lock<std::mutex>& vkGuard,
                                   const SuperpixelParams& params,
                                   const ImportedImage& input) {
    VkDescriptorImageInfo inputInfo{};
    inputInfo.sampler = g->rawSampler;
    inputInfo.imageView = input.view;
    inputInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = g->output.view;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->imgSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &inputInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->imgSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outputInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);
    if (!ensureShadeSampler(g) || !ensureShadeDummy(g)) return VFVK_SUBMIT_FAILED;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = g->shadeDummyView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet shadeWrite{};
    shadeWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    shadeWrite.dstSet = g->imgSet;
    shadeWrite.dstBinding = 2;
    shadeWrite.descriptorCount = 1;
    shadeWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    shadeWrite.pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 1, &shadeWrite, 0, nullptr);
    if (ensureLut(g) != VFVK_OK) return VFVK_SUBMIT_FAILED;

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: image begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkImageMemoryBarrier acquire{};
    acquire.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    acquire.srcAccessMask = 0;
    acquire.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    acquire.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    acquire.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    acquire.dstQueueFamilyIndex = g->queueFamily;
    acquire.image = input.image;
    acquire.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    acquire.subresourceRange.levelCount = 1;
    acquire.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &acquire);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->imgPipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->imgPipelineLayout, 0, 1, &g->imgSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->imgPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                       sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.frameSize[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.frameSize[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, g->queueFamily,
                  VK_QUEUE_FAMILY_FOREIGN_EXT);
    VkImageMemoryBarrier release{};
    release.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    release.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    release.dstAccessMask = 0;
    release.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    release.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    release.srcQueueFamilyIndex = g->queueFamily;
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    release.image = input.image;
    release.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    release.subresourceRange.levelCount = 1;
    release.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &release);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: image end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: image submit failed %d", vr);
        g->computeArmed = false;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = true;
    vfComputeInFlight.fetch_add(1);
    const uint64_t gen = vkGeneration.load();
    VkFence fence = g->fence;
    VkDevice device = g->device;
    vkGuard.unlock();
    vr = vkWaitForFences(device, 1, &fence, VK_TRUE, VFVK_VF_WAIT_NS);
    vkGuard.lock();
    vfComputeInFlight.fetch_sub(1);
    if (g == nullptr || vkGeneration.load() != gen) {
        reapOrphanContext();  // B1: free a context retired during this wait
        return VFVK_NOT_INITIALIZED;
    }
    if (vr == VK_TIMEOUT) return VFVK_BUSY;
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: image fence wait failed %d; retiring before release", vr);
        const VkResult retired = vkQueueWaitIdle(g->queue);
        g->computeArmed = false;
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = false;
    return VFVK_OK;
}

// TRANSFER bridge-copy dispatch: the camera allocation (imported once,
// shared with the buffer tier) is copied by the transfer engine into the
// owned R16 image, then the proven img pipeline reads the owned image. Same
// fence/export discipline as the other dispatches; the only new work is the
// acquire -> copy -> shader-read chain on the owned image. The copy covers
// the FULL import dims (the push block's frameSize is the stepped output
// geometry; the img shader steps through full-res like the image tier).
static int dispatchSuperpixelBridge(std::unique_lock<std::mutex>& vkGuard,
                                    const SuperpixelParams& params,
                                    const ImportedBuffer& input) {
    VkDescriptorImageInfo inputInfo{};
    inputInfo.sampler = g->rawSampler;
    inputInfo.imageView = g->bridgeView;
    inputInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = g->output.view;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = g->imgSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &inputInfo;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = g->imgSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outputInfo;
    vkUpdateDescriptorSets(g->device, 2, writes, 0, nullptr);
    if (!ensureShadeSampler(g) || !ensureShadeDummy(g)) return VFVK_SUBMIT_FAILED;
    VkDescriptorImageInfo shadeInfo{};
    shadeInfo.sampler = g->shadeSampler;
    shadeInfo.imageView = g->shadeDummyView;
    shadeInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet shadeWrite{};
    shadeWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    shadeWrite.dstSet = g->imgSet;
    shadeWrite.dstBinding = 2;
    shadeWrite.descriptorCount = 1;
    shadeWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    shadeWrite.pImageInfo = &shadeInfo;
    vkUpdateDescriptorSets(g->device, 1, &shadeWrite, 0, nullptr);
    if (ensureLut(g) != VFVK_OK) return VFVK_SUBMIT_FAILED;

    vkResetFences(g->device, 1, &g->fence);
    vkResetCommandBuffer(g->commandBuffer, 0);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkResult vr = vkBeginCommandBuffer(g->commandBuffer, &begin);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: bridge begin failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    // Adopt the HAL's fresh contents for the copy engine (not the shader).
    VkBufferMemoryBarrier bufBarrier{};
    bufBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    bufBarrier.srcAccessMask = 0;
    bufBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    bufBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    bufBarrier.dstQueueFamilyIndex = g->queueFamily;
    bufBarrier.buffer = input.buffer;
    bufBarrier.offset = 0;
    bufBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 1, &bufBarrier, 0,
                         nullptr);
    // Owned image: previous frame's shader read -> transfer write. The image
    // is app-owned (no queue transfer), but the execution dependency is real.
    layoutBarrier(g->commandBuffer, g->bridgeImage,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                  VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    VkBufferImageCopy region{};
    region.bufferOffset = 0;
    region.bufferRowLength = input.stridePixels;
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.mipLevel = 0;
    region.imageSubresource.baseArrayLayer = 0;
    region.imageSubresource.layerCount = 1;
    region.imageOffset = {0, 0, 0};
    region.imageExtent.width = input.width;
    region.imageExtent.height = input.height;
    region.imageExtent.depth = 1;
    vkCmdCopyBufferToImage(g->commandBuffer, input.buffer, g->bridgeImage,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    layoutBarrier(g->commandBuffer, g->bridgeImage,
                  VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                  VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                  VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_FOREIGN_EXT, g->queueFamily);
    vkCmdBindPipeline(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g->imgPipeline);
    vkCmdBindDescriptorSets(g->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                            g->imgPipelineLayout, 0, 1, &g->imgSet, 0, nullptr);
    vkCmdPushConstants(g->commandBuffer, g->imgPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                       sizeof(params), &params);
    const uint32_t gx = (uint32_t)(params.frameSize[0] + 7) / 8;
    const uint32_t gy = (uint32_t)(params.frameSize[1] + 7) / 8;
    vkCmdDispatch(g->commandBuffer, gx, gy, 1);
    layoutBarrier(g->commandBuffer, g->output.image,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL, g->queueFamily,
                  VK_QUEUE_FAMILY_FOREIGN_EXT);
    // Return the borrowed RAW allocation to Camera2/CPU.
    bufBarrier.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    bufBarrier.dstAccessMask = 0;
    bufBarrier.srcQueueFamilyIndex = g->queueFamily;
    bufBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(g->commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 1, &bufBarrier, 0,
                         nullptr);
    // Park the owned image back in GENERAL for the next frame's chain.
    layoutBarrier(g->commandBuffer, g->bridgeImage,
                  VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT,
                  VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, (VkAccessFlags)0,
                  VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                  VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED);
    vr = vkEndCommandBuffer(g->commandBuffer);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: bridge end failed %d", vr);
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &g->commandBuffer;
    vr = vkQueueSubmit(g->queue, 1, &submit, g->fence);
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: bridge submit failed %d", vr);
        g->computeArmed = false;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = true;
    vfComputeInFlight.fetch_add(1);
    const uint64_t gen = vkGeneration.load();
    VkFence fence = g->fence;
    VkDevice device = g->device;
    vkGuard.unlock();
    vr = vkWaitForFences(device, 1, &fence, VK_TRUE, VFVK_VF_WAIT_NS);
    vkGuard.lock();
    vfComputeInFlight.fetch_sub(1);
    if (g == nullptr || vkGeneration.load() != gen) {
        reapOrphanContext();  // B1: free a context retired during this wait
        return VFVK_NOT_INITIALIZED;
    }
    if (vr == VK_TIMEOUT) return VFVK_BUSY;
    if (vr != VK_SUCCESS) {
        LOGW("vf-vk: bridge fence wait failed %d; retiring before release", vr);
        const VkResult retired = vkQueueWaitIdle(g->queue);
        g->computeArmed = false;
        if (retired == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        return vr == VK_ERROR_DEVICE_LOST ? VFVK_DEVICE_LOST : VFVK_SUBMIT_FAILED;
    }
    g->computeArmed = false;
    return VFVK_OK;
}

// Normalize-LUT upload (VfLevels.lutFor bytes): scatter the 4 packed
// site rows (codes 0..lutMax each) into the full-range segments. The
// tail past lutMax stays 0xFF on first upload and when the bound
// shrinks (newly exposed strips held stale prefix bytes); a growing
// bound only overwrites. Runs under vkMutex like every other upload;
// a torn read only affects a BUSY-skipped (unpresented) dispatch.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_uploadLutNative(
    JNIEnv* env, jobject, jint lutMax, jbyteArray lut) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!lut || lutMax < 0 || lutMax >= (jint)kLutCodes) return VFVK_BAD_ARGUMENT;
    const uint32_t row = (uint32_t)lutMax + 1u;
    const jsize want = (jsize)row * (jsize)kLutSites;
    if (env->GetArrayLength(lut) != want) return VFVK_BAD_ARGUMENT;
    if (ensureLut(g) != VFVK_OK) return VFVK_SUBMIT_FAILED;
    std::vector<jbyte> tmp((size_t)want);
    env->GetByteArrayRegion(lut, 0, want, tmp.data());
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    uint8_t* dst = static_cast<uint8_t*>(g->lutMapped);
    if (g->lutRow == 0) {
        for (uint32_t s = 0; s < kLutSites; ++s) {
            std::memset(dst + (size_t)s * kLutCodes + row, 0xFF, (size_t)(kLutCodes - row));
        }
    } else if (row < g->lutRow) {
        for (uint32_t s = 0; s < kLutSites; ++s) {
            std::memset(dst + (size_t)s * kLutCodes + row, 0xFF, (size_t)(g->lutRow - row));
        }
    }
    for (uint32_t s = 0; s < kLutSites; ++s) {
        std::memcpy(dst + (size_t)s * kLutCodes, tmp.data() + (size_t)s * row, row);
    }
    g->lutRow = row;
    if (!g->lutCoherent) {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = g->lutMemory;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        if (vkFlushMappedMemoryRanges(g->device, 1, &range) != VK_SUCCESS) {
            LOGW("vf-vk: lut: flush failed");
            return VFVK_SUBMIT_FAILED;
        }
    }
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_ensureOutputNative(
    JNIEnv* env, jobject, jobject outputBuffer) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!outputBuffer) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, outputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == buf) return VFVK_OK;
    // Get-or-import the output (BGU rotates 3 export slots, so cache by
    // pointer like the inputs; the single-slot version re-imported here).
    auto it = g->outputs.find(buf);
    if (it != g->outputs.end()) {
        g->outputBuffer = buf;
        g->output = it->second;
        return VFVK_OK;
    }
    ImportedImage imported;
    int r = importAhb(g, buf, VK_IMAGE_USAGE_STORAGE_BIT, "output", false, &imported);
    if (r != VFVK_OK) {
        LOGW("vf-vk: output storage import failed");
        return VFVK_OUTPUT_IMPORT_FAILED;
    }
    if (g->outputOrder.size() >= OUTPUT_CACHE_CAP) {
        evictOldestOutput(g);
    }
    AHardwareBuffer_acquire(buf);
    g->outputs[buf] = imported;
    g->outputOrder.push_back(buf);
    g->outputBuffer = buf;
    g->output = imported;
    LOGI("vf-vk: output import ready");
    return VFVK_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeNative(
    JNIEnv* env, jobject, jobject inputBuffer, jintArray iparams, jfloatArray fparams) {
    // unique_lock: the fence wait below runs UNLOCKED so record submits
    // never queue behind a viewfinder completion wait (that convoy cost
    // ~30-50ms per collision = dropped record frames). Everything else
    // stays serialized: imports, descriptor writes, record, submit.
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!inputBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    if (env->GetArrayLength(iparams) < 10 || env->GetArrayLength(fparams) < 8) return VFVK_BAD_ARGUMENT;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;

    // Reap check: a previous dispatch still in flight (bounded-wait
    // BUSY last call) must complete before its command buffer is reset.
    // No work is done on this path — the caller just skips the frame.
    if (g->computeArmed) {
        const VkResult fs = vkGetFenceStatus(g->device, g->fence);
        if (fs == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        if (fs != VK_SUCCESS) return VFVK_BUSY;
        g->computeArmed = false;
    }

    // Get-or-import the input (AHB pool cycles, so cache by pointer with FIFO evict).
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
    if (!readSuperpixelParams(env, iparams, fparams, &params)) return VFVK_BAD_ARGUMENT;
    // Buffer-tier row math follows the imported allocation's stride, which
    // is authoritative for this layout (the Camera2 plane stride can
    // disagree on padded grallocs). Copy/plane tiers keep the plane pitch:
    // their staging uploads are linear by construction.
    if (input.stridePixels > 0 && params.frameSize[0] > 0 &&
        input.stridePixels >= (uint32_t)params.frameSize[0]) {
        params.pitch = (int32_t)input.stridePixels;
    } else {
        static bool strideWarned = false;
        if (!strideWarned) {
            strideWarned = true;
            LOGW("vf-vk: input: bad import stride %u for width %d; keeping plane pitch %d",
                 input.stridePixels, params.frameSize[0], params.pitch);
        }
    }

    return dispatchSuperpixel(vkGuard, params, input.buffer, true);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_initImageNative(JNIEnv* env, jobject, jbyteArray spv) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->imgPipeline != VK_NULL_HANDLE) return VFVK_OK;
    if (!spv || env->GetArrayLength(spv) % 4 != 0) return VFVK_BAD_ARGUMENT;
    jsize bytes = env->GetArrayLength(spv);
    std::vector<uint32_t> code(bytes / 4);
    env->GetByteArrayRegion(spv, 0, bytes, reinterpret_cast<jbyte*>(code.data()));
    if (env->ExceptionCheck()) return VFVK_BAD_ARGUMENT;
    if (!createImagePipeline(g, code.data(), code.size())) {
        destroyImagePipeline(g);
        return VFVK_PIPELINE_FAILED;
    }
    LOGI("vf-vk: image pipeline ready");
    return VFVK_OK;
}

// Sampled-image primary tier: sampled R16_UINT image import + superpixel.
// Rejection (VFVK_IMAGE_UNSUPPORTED) means this stream is not zero-copyable;
// the caller latches the tier and uploads via computeCopyNative instead.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeImageNative(
    JNIEnv* env, jobject, jobject inputBuffer, jintArray iparams, jfloatArray fparams) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->imgPipeline == VK_NULL_HANDLE) return VFVK_IMAGE_UNSUPPORTED;
    if (!inputBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;
    if (g->computeArmed) {
        const VkResult fs = vkGetFenceStatus(g->device, g->fence);
        if (fs == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        if (fs != VK_SUCCESS) return VFVK_BUSY;
        g->computeArmed = false;
    }
    auto it = g->imgInputs.find(buf);
    if (it == g->imgInputs.end()) {
        ImportedImage imported;
        int r = importInputImage(g, buf, &imported);
        if (r != VFVK_OK) return r;
        if (g->imgInputOrder.size() >= INPUT_CACHE_CAP) evictOldestImgInput(g);
        AHardwareBuffer_acquire(buf);
        g->imgInputs[buf] = imported;
        g->imgInputOrder.push_back(buf);
        it = g->imgInputs.find(buf);
    }
    const ImportedImage& input = it->second;
    SuperpixelParams params{};
    if (!readSuperpixelParams(env, iparams, fparams, &params)) return VFVK_BAD_ARGUMENT;
    return dispatchSuperpixelImage(vkGuard, params, input);
}

// TRANSFER bridge-copy tier: buffer import (shared cache) + copy engine +
// owned R16 + the img pipeline. Sits between the direct-read tiers and the
// CPU-touching uploads: zero CPU copies even where direct reads misbehave.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeBridgeNative(
    JNIEnv* env, jobject, jobject inputBuffer, jintArray iparams, jfloatArray fparams) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (g->imgPipeline == VK_NULL_HANDLE) return VFVK_IMAGE_UNSUPPORTED;
    if (!inputBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;
    if (g->computeArmed) {
        const VkResult fs = vkGetFenceStatus(g->device, g->fence);
        if (fs == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        if (fs != VK_SUCCESS) return VFVK_BUSY;
        g->computeArmed = false;
    }
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
    if (!readSuperpixelParams(env, iparams, fparams, &params)) return VFVK_BAD_ARGUMENT;
    // The copy region is exact or the tier refuses: a wrong rowLength would
    // skew every row silently (the black-output probe cannot see skew). The
    // owned image spans the FULL import dims — frameSize is the stepped
    // output geometry, and the img shader steps through full-res.
    if (input.width == 0 || input.height == 0 || input.stridePixels < input.width) {
        static bool bridgeStrideWarned = false;
        if (!bridgeStrideWarned) {
            bridgeStrideWarned = true;
            LOGW("vf-vk: bridge: bad import geometry %ux%u stride %u; refusing",
                 input.width, input.height, input.stridePixels);
        }
        return VFVK_SUBMIT_FAILED;
    }
    if (params.frameSize[0] <= 0 || params.frameSize[1] <= 0) return VFVK_BAD_ARGUMENT;
    int br = ensureBridge(g, input.width, input.height);
    if (br != VFVK_OK) return br;
    int r = dispatchSuperpixelBridge(vkGuard, params, input);
    if (r == VFVK_OK) {
        static bool bridgeLogged = false;
        if (!bridgeLogged) {
            bridgeLogged = true;
            LOGI("vf-vk: bridge: dispatch ok (import+transfer path)");
        }
    }
    return r;
}

// GPU-copy tier (motioncam pattern): lock the input AHB, memcpy into the
// host-visible staging buffer, flush when not coherent, then the shared
// superpixel dispatch with host-write visibility instead of the foreign
// acquire/release barriers. Same fence, command buffer, export and
// reap/BUSY semantics as computeNative; only the input mechanism differs.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computeCopyNative(
    JNIEnv* env, jobject, jobject inputBuffer, jintArray iparams, jfloatArray fparams) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!inputBuffer || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    AHardwareBuffer* buf = AHardwareBuffer_fromHardwareBuffer(env, inputBuffer);
    if (!buf) return VFVK_BAD_ARGUMENT;

    // Same reap gate as computeNative: shared fence/command buffer. Runs
    // before the upload so BUSY frames skip the memcpy too.
    if (g->computeArmed) {
        const VkResult fs = vkGetFenceStatus(g->device, g->fence);
        if (fs == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        if (fs != VK_SUCCESS) return VFVK_BUSY;
        g->computeArmed = false;
    }

    // Size the staging upload from the allocation (layout-preserving whole
    // copy; pitch still comes from the Image planes via push constants).
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    VkResult r = g->getAhbProps(g->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: ahb-props failed %d", r);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    int sr = ensureStaging(g, ahbProps.allocationSize);
    if (sr != VFVK_OK) return sr;
    void* src = nullptr;
    // Rect null = whole buffer. Fence -1: the caller holds the camera Image
    // lease, so the HAL is done writing (same guarantee as zero-copy).
    if (AHardwareBuffer_lock(buf, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1, nullptr, &src) != 0 ||
        !src) {
        LOGW("vf-vk: copy: input lock failed");
        return VFVK_COPY_UPLOAD_FAILED;
    }
    std::memcpy(g->stagingMapped, src, (size_t)ahbProps.allocationSize);
    AHardwareBuffer_unlock(buf, nullptr);
    if (!g->stagingCoherent) {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = g->stagingMemory;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        r = vkFlushMappedMemoryRanges(g->device, 1, &range);
        if (r != VK_SUCCESS) {
            LOGW("vf-vk: copy: flush failed %d", r);
            return VFVK_COPY_UPLOAD_FAILED;
        }
    }

    SuperpixelParams params{};
    if (!readSuperpixelParams(env, iparams, fparams, &params)) return VFVK_BAD_ARGUMENT;
    return dispatchSuperpixel(vkGuard, params, g->stagingBuffer, false);
}

// CPU-plane upload tier: copy the Camera2 plane's direct ByteBuffer into
// the same staging buffer, then dispatch. This is the fallback for streams
// whose hardware buffer cannot be imported at all (packed formats, BLOB
// allocations): the plane is always CPU-readable, so no AHB lock or
// external-memory import is involved. byteCount is rowStride * height.
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_computePlaneNative(
    JNIEnv* env, jobject, jobject planeBuffer, jint byteCount,
    jintArray iparams, jfloatArray fparams) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return VFVK_NOT_INITIALIZED;
    if (!planeBuffer || byteCount <= 0 || !iparams || !fparams) return VFVK_BAD_ARGUMENT;
    if (g->outputBuffer == nullptr) return VFVK_OUTPUT_IMPORT_FAILED;
    void* src = env->GetDirectBufferAddress(planeBuffer);
    jlong capacity = env->GetDirectBufferCapacity(planeBuffer);
    if (src == nullptr || capacity < byteCount) {
        LOGW("vf-vk: plane: not a direct buffer holding %d bytes", byteCount);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    if (g->computeArmed) {
        const VkResult fs = vkGetFenceStatus(g->device, g->fence);
        if (fs == VK_ERROR_DEVICE_LOST) return VFVK_DEVICE_LOST;
        if (fs != VK_SUCCESS) return VFVK_BUSY;
        g->computeArmed = false;
    }
    int sr = ensureStaging(g, (VkDeviceSize)byteCount);
    if (sr != VFVK_OK) return sr;
    std::memcpy(g->stagingMapped, src, (size_t)byteCount);
    if (!g->stagingCoherent) {
        VkMappedMemoryRange range{};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = g->stagingMemory;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        if (vkFlushMappedMemoryRanges(g->device, 1, &range) != VK_SUCCESS) {
            LOGW("vf-vk: plane: flush failed");
            return VFVK_COPY_UPLOAD_FAILED;
        }
    }
    SuperpixelParams params{};
    if (!readSuperpixelParams(env, iparams, fparams, &params)) return VFVK_BAD_ARGUMENT;
    return dispatchSuperpixel(vkGuard, params, g->stagingBuffer, false);
}

// Tier black-output probe: stride-aware max byte over a coarse grid of the
// export buffer (RGBA8 linear superpixel, pre-tonemap). The caller samples
// after the compute fence, with GL drained, so the read is deterministic.
// Returns 0..255, or -1 when the export cannot be locked (no CPU_READ
// usage: the tier is assumed healthy and probation stays pending).
extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_VfVulkan_sampleOutputNative(JNIEnv*, jobject) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    if (g == nullptr || g->outputBuffer == nullptr) return -1;
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(g->outputBuffer, &desc);
    if (desc.width <= 0 || desc.height <= 0) return -1;
    AHardwareBuffer_Planes planes{};
    if (AHardwareBuffer_lockPlanes(g->outputBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_RARELY, -1,
                                   nullptr, &planes) != 0) {
        return -1;
    }
    int maxByte = 0;
    if (planes.planeCount >= 1 && planes.planes[0].data) {
        const uint8_t* base = static_cast<const uint8_t*>(planes.planes[0].data);
        const int32_t rowStride = planes.planes[0].rowStride;
        const int stepX = (desc.width / 16 > 0) ? (int)(desc.width / 16) : 1;
        const int stepY = (desc.height / 16 > 0) ? (int)(desc.height / 16) : 1;
        for (uint32_t y = 0; y < desc.height && maxByte < 255; y += (uint32_t)stepY) {
            const uint8_t* row = base + (size_t)y * (size_t)rowStride;
            for (uint32_t x = 0; x < desc.width && maxByte < 255; x += (uint32_t)stepX) {
                const uint8_t* px = row + (size_t)x * 4u;
                for (int c = 0; c < 4; ++c) {
                    if (px[c] > maxByte) maxByte = px[c];
                }
            }
        }
    }
    AHardwareBuffer_unlock(g->outputBuffer, nullptr);
    return maxByte;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_matthew_rawlens_VfVulkan_supportsF16MathNative(JNIEnv*, jobject) {
    const std::lock_guard<std::mutex> vkGuard(vkMutex);
    return (g != nullptr && g->hasF16Math) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_VfVulkan_resetNative(JNIEnv*, jobject) {
    std::unique_lock<std::mutex> vkGuard(vkMutex);
    if (g == nullptr) return;
    // Called from non-worker threads (recorder stop): join an in-flight
    // viewfinder wait before destroying the imports/output it uses.
    if (!joinVfCompute(vkGuard)) {
        // B1: the worker still waits on this context; resetting now would
        // free its imports/output under it. The next reset does the work.
        LOGW("vf-vk: reset skipped, VF wait still in flight");
        return;
    }
    if (g == nullptr) return;
    vkDeviceWaitIdle(g->device);
    destroyGrade(g);
    // Session boundary: drop sampled-image imports only (pipeline stays).
    for (auto& entry : g->imgInputs) destroyImported(g, &entry.second);
    for (AHardwareBuffer* b : g->imgInputOrder) AHardwareBuffer_release(b);
    g->imgInputs.clear();
    g->imgInputOrder.clear();
    for (auto& entry : g->inputs) destroyImportedBuffer(g, &entry.second);
    for (AHardwareBuffer* b : g->inputOrder) AHardwareBuffer_release(b);
    g->inputs.clear();
    g->inputOrder.clear();
    for (auto& entry : g->outputs) destroyImported(g, &entry.second);
    for (AHardwareBuffer* b : g->outputOrder) AHardwareBuffer_release(b);
    g->outputs.clear();
    g->outputOrder.clear();
    g->output = ImportedImage{};
    g->outputBuffer = nullptr;
}

