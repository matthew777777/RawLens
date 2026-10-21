// Create (once) + bind (once) the LUT buffer. Host-visible + coherent
// preferred, plain host-visible with explicit flush as fallback (staging
// pattern). Default fill is 0x00: a dispatch before the first upload
// reads black, which the tier probe catches. Creation runs under vkMutex
// before any in-flight work can reference binding 3 (reap gate + fence
// wait serialize dispatches; see dispatchSuperpixel).
int ensureLut(Context* ctx) {
    if (ctx->lutBuffer == VK_NULL_HANDLE) {
        VkBufferCreateInfo bufInfo{};
        bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufInfo.size = kLutBytes;
        bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->lutBuffer) != VK_SUCCESS) {
            LOGW("vf-vk: lut: create failed");
            return VFVK_SUBMIT_FAILED;
        }
        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(ctx->device, ctx->lutBuffer, &req);
        VkPhysicalDeviceMemoryProperties memProps{};
        vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
        uint32_t memType = pickMemoryTypeWithProps(
            req.memoryTypeBits, memProps,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        ctx->lutCoherent = true;
        if (memType == UINT32_MAX) {
            memType = pickMemoryTypeWithProps(req.memoryTypeBits, memProps,
                                              VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            ctx->lutCoherent = false;
        }
        if (memType == UINT32_MAX) {
            LOGW("vf-vk: lut: no host-visible memory type");
            destroyLut(ctx);
            return VFVK_SUBMIT_FAILED;
        }
        VkMemoryAllocateInfo alloc{};
        alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = memType;
        if (vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->lutMemory) != VK_SUCCESS ||
            vkBindBufferMemory(ctx->device, ctx->lutBuffer, ctx->lutMemory, 0) != VK_SUCCESS ||
            vkMapMemory(ctx->device, ctx->lutMemory, 0, VK_WHOLE_SIZE, 0, &ctx->lutMapped) !=
                VK_SUCCESS ||
            !ctx->lutMapped) {
            LOGW("vf-vk: lut: alloc/bind/map failed");
            destroyLut(ctx);
            return VFVK_SUBMIT_FAILED;
        }
        std::memset(ctx->lutMapped, 0, (size_t)kLutBytes);
        if (!ctx->lutCoherent) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = ctx->lutMemory;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            vkFlushMappedMemoryRanges(ctx->device, 1, &range);
        }
        LOGI("vf-vk: lut ready size=%llu coherent=%d",
             (unsigned long long)kLutBytes, ctx->lutCoherent ? 1 : 0);
    }
    if (!ctx->lutBound) {
        VkDescriptorBufferInfo lutInfo{};
        lutInfo.buffer = ctx->lutBuffer;
        lutInfo.offset = 0;
        lutInfo.range = VK_WHOLE_SIZE;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = ctx->descriptorSet;
        write.dstBinding = 3;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &lutInfo;
        vkUpdateDescriptorSets(ctx->device, 1, &write, 0, nullptr);
        ctx->lutBound = true;
    }
    if (ctx->imgSet != VK_NULL_HANDLE && !ctx->imgLutBound) {
        VkDescriptorBufferInfo lutInfo{};
        lutInfo.buffer = ctx->lutBuffer;
        lutInfo.offset = 0;
        lutInfo.range = VK_WHOLE_SIZE;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = ctx->imgSet;
        write.dstBinding = 3;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &lutInfo;
        vkUpdateDescriptorSets(ctx->device, 1, &write, 0, nullptr);
        ctx->imgLutBound = true;
    }
    return VFVK_OK;
}

// motioncam ensureRawBuffer pattern: keep the buffer while it fits, else
// idle + recreate. Host-visible + coherent preferred, plain host-visible
// with an explicit per-frame flush as fallback. Mapped persistently.
int ensureStaging(Context* ctx, VkDeviceSize size) {
    const VkDeviceSize aligned = (size + 3u) & ~VkDeviceSize(3u);
    if (ctx->stagingBuffer != VK_NULL_HANDLE && ctx->stagingSize >= aligned) return VFVK_OK;
    vkDeviceWaitIdle(ctx->device);
    destroyStaging(ctx);
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = aligned;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VkResult r = vkCreateBuffer(ctx->device, &bufInfo, nullptr, &ctx->stagingBuffer);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: staging create failed %d", r);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx->device, ctx->stagingBuffer, &req);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryTypeWithProps(
        req.memoryTypeBits, memProps,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    ctx->stagingCoherent = true;
    if (memType == UINT32_MAX) {
        memType = pickMemoryTypeWithProps(req.memoryTypeBits, memProps,
                                          VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        ctx->stagingCoherent = false;
    }
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: copy: no host-visible memory type");
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->stagingMemory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: staging alloc failed %d", r);
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    r = vkBindBufferMemory(ctx->device, ctx->stagingBuffer, ctx->stagingMemory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: copy: staging bind failed %d", r);
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    r = vkMapMemory(ctx->device, ctx->stagingMemory, 0, VK_WHOLE_SIZE, 0, &ctx->stagingMapped);
    if (r != VK_SUCCESS || !ctx->stagingMapped) {
        LOGW("vf-vk: copy: staging map failed %d", r);
        destroyStaging(ctx);
        return VFVK_COPY_UPLOAD_FAILED;
    }
    ctx->stagingSize = aligned;
    LOGI("vf-vk: copy: staging ready size=%llu coherent=%d",
         (unsigned long long)aligned, ctx->stagingCoherent ? 1 : 0);
    return VFVK_OK;
}

void destroyImported(Context* ctx, ImportedImage* img) {
    if (img->view != VK_NULL_HANDLE) vkDestroyImageView(ctx->device, img->view, nullptr);
    if (img->image != VK_NULL_HANDLE) vkDestroyImage(ctx->device, img->image, nullptr);
    if (img->memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, img->memory, nullptr);
    *img = ImportedImage{};
}

void destroyImportedBuffer(Context* ctx, ImportedBuffer* buf) {
    if (buf->buffer != VK_NULL_HANDLE) vkDestroyBuffer(ctx->device, buf->buffer, nullptr);
    if (buf->memory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, buf->memory, nullptr);
    *buf = ImportedBuffer{};
}

void destroyBridge(Context* ctx) {
    if (ctx->bridgeView != VK_NULL_HANDLE) vkDestroyImageView(ctx->device, ctx->bridgeView, nullptr);
    if (ctx->bridgeImage != VK_NULL_HANDLE) vkDestroyImage(ctx->device, ctx->bridgeImage, nullptr);
    if (ctx->bridgeMemory != VK_NULL_HANDLE) vkFreeMemory(ctx->device, ctx->bridgeMemory, nullptr);
    ctx->bridgeView = VK_NULL_HANDLE;
    ctx->bridgeImage = VK_NULL_HANDLE;
    ctx->bridgeMemory = VK_NULL_HANDLE;
    ctx->bridgeW = 0;
    ctx->bridgeH = 0;
}

// Owned R16 transfer target for the bridge rung. Driver-created OPTIMAL, so
// the img shader reads exactly what the copy engine wrote: no sampling
// granularity, no stride ambiguity, stripe-impossible by construction.
int ensureBridge(Context* ctx, uint32_t w, uint32_t h) {
    if (ctx->bridgeImage != VK_NULL_HANDLE && ctx->bridgeW == w && ctx->bridgeH == h) {
        return VFVK_OK;
    }
    // Size change only (lens switch): the reap gate has already drained the
    // in-flight dispatch; idle makes the realloc safe.
    vkDeviceWaitIdle(ctx->device);
    destroyBridge(ctx);
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R16_UINT;
    imgInfo.extent.width = w;
    imgInfo.extent.height = h;
    imgInfo.extent.depth = 1;
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkResult r = vkCreateImage(ctx->device, &imgInfo, nullptr, &ctx->bridgeImage);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: bridge: image create %ux%u failed %d", w, h, r);
        destroyBridge(ctx);
        return VFVK_SUBMIT_FAILED;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, ctx->bridgeImage, &req);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryTypeWithProps(req.memoryTypeBits, memProps,
                                               VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (memType == UINT32_MAX) memType = pickMemoryType(req.memoryTypeBits, memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: bridge: no memory type");
        destroyBridge(ctx);
        return VFVK_SUBMIT_FAILED;
    }
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &ctx->bridgeMemory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: bridge: alloc failed %d", r);
        destroyBridge(ctx);
        return VFVK_SUBMIT_FAILED;
    }
    r = vkBindImageMemory(ctx->device, ctx->bridgeImage, ctx->bridgeMemory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: bridge: bind failed %d", r);
        destroyBridge(ctx);
        return VFVK_SUBMIT_FAILED;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = ctx->bridgeImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16_UINT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    r = vkCreateImageView(ctx->device, &viewInfo, nullptr, &ctx->bridgeView);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: bridge: view failed %d", r);
        destroyBridge(ctx);
        return VFVK_SUBMIT_FAILED;
    }
    ctx->bridgeW = w;
    ctx->bridgeH = h;
    LOGI("vf-vk: bridge: owned R16 %ux%u ready", w, h);
    return VFVK_OK;
}

// FIFO eviction for the pointer-keyed input import cache, shared by all
// get-or-import sites. The cap (24) covers the largest reader pool (22),
// so steady-state viewfinder frames never evict: any eviction means the
// live distinct-buffer set overflowed the cache and the next use of the
// evicted buffer pays a full 25MB re-import. The counter + rate-limited
// warning make that regime visible in logcat instead of silent churn.
void evictOldestInput(Context* ctx) {
    static uint64_t evictions = 0;
    AHardwareBuffer* oldest = ctx->inputOrder.front();
    ctx->inputOrder.erase(ctx->inputOrder.begin());
    auto oit = ctx->inputs.find(oldest);
    if (oit != ctx->inputs.end()) {
        // B3: a pending submit may still read this import; eviction is rare
        // (the cap covers the live pools), so idle the queue first.
        vkQueueWaitIdle(ctx->queue);
        destroyImportedBuffer(ctx, &oit->second);
        ctx->inputs.erase(oit);
    }
    AHardwareBuffer_release(oldest);
    evictions++;
    if (evictions == 1 || evictions % 64 == 0) {
        LOGW("vf-vk: input import cache evicted %llu buffer(s) (cap %d); live pool exceeds cache",
             (unsigned long long)evictions, INPUT_CACHE_CAP);
    }
}

// FIFO eviction for the output import cache. Unlike inputs, the current
// buffer is always skipped: the single in-flight dispatch (if any) writes
// through the current import, and destroying it mid-flight lost the device.
// The cap covers every live export, so any eviction is already pathological.
void evictOldestOutput(Context* ctx) {
    static uint64_t evictions = 0;
    for (auto oit = ctx->outputOrder.begin(); oit != ctx->outputOrder.end(); ++oit) {
        if (*oit == ctx->outputBuffer) continue;
        AHardwareBuffer* oldest = *oit;
        ctx->outputOrder.erase(oit);
        auto mit = ctx->outputs.find(oldest);
        if (mit != ctx->outputs.end()) {
            vkQueueWaitIdle(ctx->queue);  // B3: never destroy an import in flight
            destroyImported(ctx, &mit->second);
            ctx->outputs.erase(mit);
        }
        AHardwareBuffer_release(oldest);
        evictions++;
        if (evictions == 1 || evictions % 64 == 0) {
            LOGW("vf-vk: output import cache evicted %llu buffer(s) (cap %d); live pool exceeds cache",
                 (unsigned long long)evictions, OUTPUT_CACHE_CAP);
        }
        return;
    }
}

// Import an AHB as a Vulkan image per the reference flow: query format
// properties, create with the reported VkFormat (or externalFormat), dedicated
// import-allocate, bind, view. Does NOT acquire the caller's buffer.
// Tiling: HAL camera buffers are linear (CPU-visible, rowStride-pitched), so the
// input tries LINEAR first — importing them as OPTIMAL lets the driver
// misinterpret the layout (observed: striped mis-samples). The app-allocated
// export buffer stays OPTIMAL (proven correct end-to-end by the grid test).
int importAhb(Context* ctx, AHardwareBuffer* buf, VkImageUsageFlags usage,
              const char* tag, bool linearFirst, ImportedImage* out) {
    *out = ImportedImage{};
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
    fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ahbProps.pNext = &fmtProps;
    VkResult r = ctx->getAhbProps(ctx->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: ahb-props failed %d", tag, r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    LOGI("vf-vk: %s: %ux%u ahbFormat=%u vkFormat=%d external=%llu size=%llu types=%#x", tag,
         desc.width, desc.height, desc.format, fmtProps.format,
         (unsigned long long)fmtProps.externalFormat,
         (unsigned long long)ahbProps.allocationSize, ahbProps.memoryTypeBits);
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryType(ahbProps.memoryTypeBits, memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: %s: no memory type", tag);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    const bool useExternal = fmtProps.format == VK_FORMAT_UNDEFINED;
    if (useExternal && fmtProps.externalFormat == 0) {
        LOGW("vf-vk: %s: no usable format", tag);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkExternalFormatANDROID vkExtFmt{};
    vkExtFmt.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    vkExtFmt.externalFormat = fmtProps.externalFormat;
    VkExternalMemoryImageCreateInfo extImg{};
    extImg.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImg.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    if (useExternal) extImg.pNext = &vkExtFmt;
    const VkImageTiling tilings[2] = {
        linearFirst ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_OPTIMAL,
        linearFirst ? VK_IMAGE_TILING_OPTIMAL : VK_IMAGE_TILING_LINEAR,
    };
    const char* tilingNames[2] = {linearFirst ? "LINEAR" : "OPTIMAL", linearFirst ? "OPTIMAL" : "LINEAR"};
    for (int t = 0; t < 2; ++t) {
        VkImageCreateInfo imgInfo{};
        imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        imgInfo.pNext = &extImg;
        imgInfo.imageType = VK_IMAGE_TYPE_2D;
        imgInfo.format = useExternal ? VK_FORMAT_UNDEFINED : fmtProps.format;
        imgInfo.extent.width = desc.width;
        imgInfo.extent.height = desc.height;
        imgInfo.extent.depth = 1;
        imgInfo.mipLevels = 1;
        imgInfo.arrayLayers = 1;
        imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imgInfo.tiling = tilings[t];
        imgInfo.usage = usage;
        imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        r = vkCreateImage(ctx->device, &imgInfo, nullptr, &out->image);
        if (r != VK_SUCCESS) {
            LOGW("vf-vk: %s: image create (%s) failed %d (usage=%#x)", tag, tilingNames[t], r, usage);
            continue;
        }
        LOGI("vf-vk: %s: image tiling=%s", tag, tilingNames[t]);
        break;
    }
    if (out->image == VK_NULL_HANDLE) return VFVK_INPUT_IMPORT_FAILED;
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = out->image;
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buf;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = &import;
    alloc.allocationSize = ahbProps.allocationSize;
    alloc.memoryTypeIndex = memType;
    r = vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: import alloc failed %d", tag, r);
        vkDestroyImage(ctx->device, out->image, nullptr);
        *out = ImportedImage{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    r = vkBindImageMemory(ctx->device, out->image, out->memory, 0);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: bind failed %d", tag, r);
        vkFreeMemory(ctx->device, out->memory, nullptr);
        vkDestroyImage(ctx->device, out->image, nullptr);
        *out = ImportedImage{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = out->image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = useExternal ? VK_FORMAT_UNDEFINED : fmtProps.format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    r = vkCreateImageView(ctx->device, &viewInfo, nullptr, &out->view);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: %s: view failed %d", tag, r);
        destroyImported(ctx, out);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    return VFVK_OK;
}

// Import an AHB as a storage buffer: byte-exact, no pitch/tiling/format risk.
// The shader does explicit (y * pitch + x) addressing with the Image plane's
// stride, the same value the correct CPU path uses.
int importInputBuffer(Context* ctx, AHardwareBuffer* buf, ImportedBuffer* out) {
    *out = ImportedBuffer{};
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    VkResult r = ctx->getAhbProps(ctx->device, buf, &ahbProps);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: ahb-props failed %d", r);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    if (ahbProps.allocationSize == 0 || ahbProps.memoryTypeBits == 0) {
        LOGW("vf-vk: input: empty AHB properties");
        return VFVK_INPUT_IMPORT_FAILED;
    }
    // Plain buffer first (Adreno 840 path): no external-memory buffer
    // create-info chained, and the memory type comes from the AHB's bits
    // ALONE. Chaining VkExternalMemoryBufferCreateInfo makes some Adreno
    // drivers reject or zero the import — and the plain buffer's own
    // memoryTypeBits exclude the AHB's type, so requiring a type in the
    // intersection rejects every import there. The AHB import lives only
    // in the allocate call (VkImportAndroidHardwareBufferInfoANDROID +
    // dedicated). Deliberately not spec-shaped at bind time; the chained
    // retry below stays spec-shaped for drivers that require it (Mali).
    VkExternalMemoryBufferCreateInfo extBuf{};
    extBuf.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO;
    extBuf.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    bool plainBuffer = true;
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = ahbProps.allocationSize;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    r = vkCreateBuffer(ctx->device, &bufInfo, nullptr, &out->buffer);
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: plain buffer create failed %d; retrying external", r);
        plainBuffer = false;
        bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufInfo.pNext = &extBuf;
        r = vkCreateBuffer(ctx->device, &bufInfo, nullptr, &out->buffer);
        if (r != VK_SUCCESS) {
            LOGW("vf-vk: input: buffer create failed %d", r);
            return VFVK_INPUT_IMPORT_FAILED;
        }
    }
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(ctx->device, out->buffer, &req);
    if (req.size > ahbProps.allocationSize) {
        LOGW("vf-vk: input: requirements %llu exceed allocation %llu",
             (unsigned long long)req.size, (unsigned long long)ahbProps.allocationSize);
        vkDestroyBuffer(ctx->device, out->buffer, nullptr);
        *out = ImportedBuffer{};
        return VFVK_INPUT_IMPORT_FAILED;
    }
    uint32_t memType;
    if (plainBuffer) {
        memType = pickMemoryType(ahbProps.memoryTypeBits, memProps.memoryTypeCount);
    } else {
        // Chained form is a real external-memory buffer: the type must
        // satisfy both sides (proven Mali shape).
        memType = pickMemoryType(ahbProps.memoryTypeBits & req.memoryTypeBits,
                                 memProps.memoryTypeCount);
        if (memType == UINT32_MAX) {
            LOGW("vf-vk: input: no compatible buffer memory type");
            destroyImportedBuffer(ctx, out);
            return VFVK_INPUT_IMPORT_FAILED;
        }
    }
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.buffer = out->buffer;
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buf;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = &import;
    alloc.allocationSize = ahbProps.allocationSize;
    alloc.memoryTypeIndex = memType;
    if (memType == UINT32_MAX) {
        // Plain path with pathological AHB bits (nonzero bits outside the
        // type table): skip straight to the chained retry below.
        r = VK_ERROR_INITIALIZATION_FAILED;
    } else {
        r = vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory);
        if (r == VK_SUCCESS) {
            r = vkBindBufferMemory(ctx->device, out->buffer, out->memory, 0);
        }
    }
    if (r != VK_SUCCESS && plainBuffer) {
        // Plain import rejected at alloc/bind: retry once with the chained
        // external-memory buffer form (STORAGE-only, proven shape) before
        // giving up on the tier.
        LOGW("vf-vk: input: plain import failed %d; retrying external", r);
        destroyImportedBuffer(ctx, out);
        bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bufInfo.pNext = &extBuf;
        if (vkCreateBuffer(ctx->device, &bufInfo, nullptr, &out->buffer) != VK_SUCCESS) {
            *out = ImportedBuffer{};
            return VFVK_INPUT_IMPORT_FAILED;
        }
        vkGetBufferMemoryRequirements(ctx->device, out->buffer, &req);
        memType = pickMemoryType(ahbProps.memoryTypeBits & req.memoryTypeBits,
                                 memProps.memoryTypeCount);
        if (memType == UINT32_MAX || req.size > ahbProps.allocationSize) {
            vkDestroyBuffer(ctx->device, out->buffer, nullptr);
            *out = ImportedBuffer{};
            return VFVK_INPUT_IMPORT_FAILED;
        }
        dedicated.buffer = out->buffer;
        alloc.memoryTypeIndex = memType;
        r = vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory);
        if (r == VK_SUCCESS) {
            r = vkBindBufferMemory(ctx->device, out->buffer, out->memory, 0);
        }
        plainBuffer = false;
    }
    if (r != VK_SUCCESS) {
        LOGW("vf-vk: input: import alloc/bind failed %d", r);
        destroyImportedBuffer(ctx, out);
        return VFVK_INPUT_IMPORT_FAILED;
    }
    // Field diagnostics for HALs whose import succeeds but reads back zeros
    // (Adreno 750): allocation identity + chosen memory type, one line per
    // pool buffer (the import cache makes repeats cheap and quiet).
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    out->stridePixels = desc.stride;
    out->width = desc.width;
    out->height = desc.height;
    LOGI("vf-vk: input: buffer import ok %ux%u ahbFormat=%u layers=%u usage=%llu size=%llu types=%#x memType=%u plain=%d stride=%u",
         desc.width, desc.height, desc.format, desc.layers, (unsigned long long)desc.usage,
         (unsigned long long)ahbProps.allocationSize, ahbProps.memoryTypeBits, memType,
         plainBuffer ? 1 : 0, desc.stride);
    return VFVK_OK;
}

// Sampled-image import: only a 2D, non-BLOB buffer that the
// driver exposes as VK_FORMAT_R16_UINT with SAMPLED_IMAGE support is
// zero-copyable. Anything else returns VFVK_IMAGE_UNSUPPORTED so the caller
// latches this tier and uploads instead — never a silent black import.
// OPTIMAL tiling only: the driver owns the layout, texelFetch is exact.
int importInputImage(Context* ctx, AHardwareBuffer* buf, ImportedImage* out) {
    *out = ImportedImage{};
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buf, &desc);
    if (desc.format == AHARDWAREBUFFER_FORMAT_BLOB || desc.height <= 1 ||
        desc.layers != 1 || desc.width == 0) {
        LOGW("vf-vk: image: not a 2D R16 image %ux%u ahbFormat=%u layers=%u",
             desc.width, desc.height, desc.format, desc.layers);
        return VFVK_IMAGE_UNSUPPORTED;
    }
    // Alignment quirk (MT6878/Mali): an OPTIMAL R16 import whose row bytes
    // are not a multiple of 64, or whose height is not a multiple of 16,
    // samples back spatially modulated (smooth magenta/grey bands over the
    // scene) instead of the sensor grid — the texture unit walks rows at
    // 64B granularity and tiles at 16px, and 4080x3060 satisfies neither
    // (8160 = 64x127.5, 3060 = 16x191.25) while 3264x2448 satisfies both.
    // The black-output probe cannot see the bands (lit output), so reject
    // up front: the buffer tier is proven zero-copy on these streams.
    // Conservative by design — worst case a working config renders one
    // tier down, never striped. Both engines share this choke point.
    if (((uint64_t)desc.width * 2u % 64u) != 0 || (desc.height % 16u) != 0) {
        static bool quirkLogged = false;
        if (!quirkLogged) {
            quirkLogged = true;
            LOGW("vf-vk: image: unaligned geometry %ux%u rejected (row %lluB); buffer tier covers",
                 desc.width, desc.height, (unsigned long long)desc.width * 2u);
        }
        return VFVK_IMAGE_UNSUPPORTED;
    }
    VkAndroidHardwareBufferFormatPropertiesANDROID fmtProps{};
    fmtProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID ahbProps{};
    ahbProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    ahbProps.pNext = &fmtProps;
    if (ctx->getAhbProps(ctx->device, buf, &ahbProps) != VK_SUCCESS) {
        LOGW("vf-vk: image: ahb-props failed");
        return VFVK_IMAGE_UNSUPPORTED;
    }
    if (fmtProps.format != VK_FORMAT_R16_UINT ||
        (fmtProps.formatFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0) {
        LOGW("vf-vk: image: vkFormat=%d features=%#x external=%llu not sampled R16_UINT",
             fmtProps.format, fmtProps.formatFeatures,
             (unsigned long long)fmtProps.externalFormat);
        return VFVK_IMAGE_UNSUPPORTED;
    }
    VkExternalMemoryImageCreateInfo extImg{};
    extImg.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    extImg.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imgInfo{};
    imgInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imgInfo.pNext = &extImg;
    imgInfo.imageType = VK_IMAGE_TYPE_2D;
    imgInfo.format = VK_FORMAT_R16_UINT;
    imgInfo.extent = {desc.width, desc.height, 1};
    imgInfo.mipLevels = 1;
    imgInfo.arrayLayers = 1;
    imgInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imgInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imgInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    imgInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imgInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(ctx->device, &imgInfo, nullptr, &out->image) != VK_SUCCESS) {
        LOGW("vf-vk: image: create failed");
        *out = ImportedImage{};
        return VFVK_IMAGE_UNSUPPORTED;
    }
    VkMemoryRequirements req{};
    vkGetImageMemoryRequirements(ctx->device, out->image, &req);
    uint32_t typeBits = req.memoryTypeBits & ahbProps.memoryTypeBits;
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(ctx->gpu, &memProps);
    uint32_t memType = pickMemoryType(typeBits, memProps.memoryTypeCount);
    if (memType == UINT32_MAX) {
        LOGW("vf-vk: image: no compatible memory type");
        vkDestroyImage(ctx->device, out->image, nullptr);
        *out = ImportedImage{};
        return VFVK_IMAGE_UNSUPPORTED;
    }
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = out->image;
    VkImportAndroidHardwareBufferInfoANDROID import{};
    import.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    import.pNext = &dedicated;
    import.buffer = buf;
    VkMemoryAllocateInfo alloc{};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.pNext = &import;
    alloc.allocationSize = ahbProps.allocationSize;
    alloc.memoryTypeIndex = memType;
    if (vkAllocateMemory(ctx->device, &alloc, nullptr, &out->memory) != VK_SUCCESS ||
        vkBindImageMemory(ctx->device, out->image, out->memory, 0) != VK_SUCCESS) {
        LOGW("vf-vk: image: import alloc/bind failed");
        destroyImported(ctx, out);
        return VFVK_IMAGE_UNSUPPORTED;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = out->image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16_UINT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (vkCreateImageView(ctx->device, &viewInfo, nullptr, &out->view) != VK_SUCCESS) {
        LOGW("vf-vk: image: view failed");
        destroyImported(ctx, out);
        return VFVK_IMAGE_UNSUPPORTED;
    }
    LOGI("vf-vk: image: R16_UINT sampled import ok %ux%u memType=%u", desc.width,
         desc.height, memType);
    return VFVK_OK;
}

void evictOldestImgInput(Context* ctx) {
    AHardwareBuffer* oldest = ctx->imgInputOrder.front();
    ctx->imgInputOrder.erase(ctx->imgInputOrder.begin());
    auto it = ctx->imgInputs.find(oldest);
    if (it != ctx->imgInputs.end()) {
        destroyImported(ctx, &it->second);
        ctx->imgInputs.erase(it);
    }
    AHardwareBuffer_release(oldest);
}

void destroyImagePipeline(Context* ctx) {
    for (auto& entry : ctx->imgInputs) destroyImported(ctx, &entry.second);
    for (AHardwareBuffer* b : ctx->imgInputOrder) AHardwareBuffer_release(b);
    ctx->imgInputs.clear();
    ctx->imgInputOrder.clear();
    if (ctx->rawSampler != VK_NULL_HANDLE) vkDestroySampler(ctx->device, ctx->rawSampler, nullptr);
    ctx->rawSampler = VK_NULL_HANDLE;
    if (ctx->imgPipeline != VK_NULL_HANDLE) vkDestroyPipeline(ctx->device, ctx->imgPipeline, nullptr);
    ctx->imgPipeline = VK_NULL_HANDLE;
    if (ctx->imgPipelineLayout != VK_NULL_HANDLE)
        vkDestroyPipelineLayout(ctx->device, ctx->imgPipelineLayout, nullptr);
    ctx->imgPipelineLayout = VK_NULL_HANDLE;
    if (ctx->imgSetLayout != VK_NULL_HANDLE)
        vkDestroyDescriptorSetLayout(ctx->device, ctx->imgSetLayout, nullptr);
    ctx->imgSetLayout = VK_NULL_HANDLE;
    if (ctx->imgPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx->device, ctx->imgPool, nullptr);
    ctx->imgPool = VK_NULL_HANDLE;
    ctx->imgSet = VK_NULL_HANDLE;
    ctx->imgLutBound = false;
}

// ---- Slot gate helpers (see Context::slotGate) ----
// Bounded grace for a fence whose batch already signalled its sync fd.
constexpr uint64_t SLOT_GATE_GRACE_NS = 2000000ull;  // 2 ms
template <class H>
uint64_t gateKey(H handle) {
    return (uint64_t)(uintptr_t)handle;
}

void retireSlot(Context* ctx, const std::shared_ptr<Context::SlotInFlight>& f) {
    if (f->sem != VK_NULL_HANDLE) vkDestroySemaphore(ctx->device, f->sem, nullptr);
    f->sem = VK_NULL_HANDLE;
    if (f->fence != VK_NULL_HANDLE) {
        vkResetFences(ctx->device, 1, &f->fence);
        ctx->gateFencePool.push_back(f->fence);
    }
    f->fence = VK_NULL_HANDLE;
}

// Non-blocking. True when no listed resource is still referenced by a
// pending submit; retires (semaphore destroyed, fence pooled) every
// submission that has completed.
bool slotIdle(Context* ctx, std::initializer_list<uint64_t> keys) {
    for (uint64_t k : keys) {
        auto it = ctx->slotGate.find(k);
        if (it == ctx->slotGate.end()) continue;
        std::shared_ptr<Context::SlotInFlight> f = it->second;
        // The caller usually reuses a slot only after its exported sync
        // fd fired; the fence and the fd signal at the end of the same
        // batch but are not ordered, so give the fence a short grace
        // (SLOT_GATE_GRACE_NS) before calling the slot busy.
        if (f->fence != VK_NULL_HANDLE &&
            vkWaitForFences(ctx->device, 1, &f->fence, VK_TRUE, SLOT_GATE_GRACE_NS) == VK_TIMEOUT) {
            return false;
        }
        retireSlot(ctx, f);
        ctx->slotGate.erase(it);
    }
    return true;
}

VkFence gateFence(Context* ctx) {
    if (!ctx->gateFencePool.empty()) {
        VkFence f = ctx->gateFencePool.back();
        ctx->gateFencePool.pop_back();
        return f;
    }
    VkFenceCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkFence f = VK_NULL_HANDLE;
    if (vkCreateFence(ctx->device, &info, nullptr, &f) != VK_SUCCESS) return VK_NULL_HANDLE;
    return f;
}

// After a successful submit signalling [fence] (+ [sem] when an fd was
// exported): the listed resources stay owned until the fence signals.
void slotSubmitted(Context* ctx, std::initializer_list<uint64_t> keys, VkFence fence, VkSemaphore sem) {
    auto f = std::make_shared<Context::SlotInFlight>();
    f->fence = fence;
    f->sem = sem;
    for (uint64_t k : keys) ctx->slotGate[k] = f;
}

// Teardown only (queue idle): drop every gate entry and pooled fence.
void drainSlotGate(Context* ctx) {
    for (auto& kv : ctx->slotGate) {
        if (kv.second->sem != VK_NULL_HANDLE) vkDestroySemaphore(ctx->device, kv.second->sem, nullptr);
        if (kv.second->fence != VK_NULL_HANDLE) vkDestroyFence(ctx->device, kv.second->fence, nullptr);
        kv.second->sem = VK_NULL_HANDLE;
        kv.second->fence = VK_NULL_HANDLE;
    }
    ctx->slotGate.clear();
    for (VkFence f : ctx->gateFencePool) vkDestroyFence(ctx->device, f, nullptr);
    ctx->gateFencePool.clear();
}

