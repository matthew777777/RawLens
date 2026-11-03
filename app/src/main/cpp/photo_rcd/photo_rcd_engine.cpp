// SPDX-License-Identifier: GPL-3.0-or-later
// New shared Android/host resource implementation, RawLens/Codex, 2026-11-01.
// Reconstruction is the separately attributed pinned RawTherapee shader.
#include "photo_rcd_engine.h"
#include "photo_rcd_input.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <mutex>
#include <stdexcept>
#ifdef __ANDROID__
#define VK_USE_PLATFORM_ANDROID_KHR
#include <android/hardware_buffer.h>
#endif
#include <map>
#include <vulkan/vulkan.h>

namespace photo_rcd {
// Frame-global sample masks after the 64 metadata words: one bit per source
// column, then per source row; set where the output reads that coordinate.
// exact: P010 frames, integer srcOf(o) = ((2o+1)*src)/(2*out). Otherwise the
// separate vf_gradeyuv grade samples int((o+0.5)*src/out) with GPU float
// division, so a 1e-3 margin marks both candidates (superset).
static size_t sampleMaskWords(int w, int h) { return size_t(w + 31) / 32 + size_t(h + 31) / 32; }
static void writeSampleMask(int32_t *m, int w, int h, int outW, int outH, bool exact,
                            int shiftX = 0, int shiftY = 0) {
  auto *bits = reinterpret_cast<uint32_t *>(m + 64);
  const size_t colWords = size_t(w + 31) / 32;
  std::memset(bits, 0, sampleMaskWords(w, h) * 4);
  auto axis = [](uint32_t *b, int src, int out, bool exactAxis, int shift) {
    if (out <= 0 || out >= src) {
      for (int c = 0; c < src; ++c)
        b[c >> 5] |= 1u << (c & 31);
      return;
    }
    for (int o = 0; o < out; ++o) {
      if (exactAxis) {
        const int c = int((int64_t(2 * o + 1) * src) / (2 * int64_t(out))) + shift;
        b[c >> 5] |= 1u << (c & 31);
        continue;
      }
      const double u = (o + 0.5) * src / out;
      for (int c = std::max(0, int(std::ceil(u - 1.0 - 1e-3)));
           c <= std::min(src - 1, int(std::floor(u + 1e-3))); ++c)
        b[c >> 5] |= 1u << (c & 31);
    }
  };
  axis(bits, w, outW, exact, shiftX);
  axis(bits + colWords, h, outH, exact, shiftY);
  // Words 16/17: every row / every column sampled (skip the mask loads).
  m[16] = outH <= 0 || outH >= h;
  m[17] = outW <= 0 || outW >= w;
}
static void ok(VkResult result, const char *what) {
  if (result != VK_SUCCESS)
    throw std::runtime_error(std::string(what) + ": Vulkan " +
                             std::to_string(result));
}
struct Engine::State {
  struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    void *mapped = nullptr;
    VkDeviceSize bytes = 0;
    bool coherent = true;
  };
  struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
  };
  std::array<Image, 5> images{};
  bool imageIntermediates = false;
  bool recordingCandidate = false;
  bool packedCells = false;
  bool rawInput = false;
  bool normalizeOnce = false;
  bool tileLocal = false;
  // Frame-global subgroup walker (recording option A); requires tileLocal.
  bool frameGlobal = false;
  int sampleWidth = 0, sampleHeight = 0;
  // Fused final+grade frames: P010 written by stage 4 into an imported
  // encoder buffer (binding 11); completion exported as a sync fd.
  bool p010Frame = false;
  int p010W = 0, p010H = 0, recordedP010W = 0, recordedP010H = 0;
  // P010 frames double-buffer the RAW staging (binding 9): the next frame's
  // rows are copied into the idle half while the GPU still reads the other.
  int stagingHalf = 0, recordedStagingHalf = 0;
  size_t stagingSlot() const { return (rawBytes + 255) & ~size_t(255); }
  bool frameMatches(int w, int h, uint32_t cfaPhase, bool half, size_t rawCapacity) const {
    return width == w && height == h && phase == cfaPhase && halfFrame == half &&
           rawBytes == rawCapacity && builtP010 == p010Frame;
  }
  std::map<void *, Buffer> p010Imports;
  Buffer *selectedP010 = nullptr, *recordedP010 = nullptr;
  VkSemaphore completion = VK_NULL_HANDLE;
  bool statusPending = false;
#ifdef __ANDROID__
  PFN_vkGetSemaphoreFdKHR getSemaphoreFd = nullptr;
#endif
  uint32_t coreX = 16, coreY = 16;
  bool imageOutput = false;
  std::map<void *, Image> outputImages;
  Image *selectedOutput = nullptr;
  Image *recordedOutput = nullptr;
#ifdef __ANDROID__
  PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties = nullptr;
#endif
  int reconstructionScale = 1;
  size_t rawBytes = 0;
  size_t bindingCount() const { return frameGlobal ? 12 : rawInput ? 11 : 9; }
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue queue = VK_NULL_HANDLE;
  uint32_t family = 0;
  VkPhysicalDeviceProperties properties{};
  VkPhysicalDeviceMemoryProperties memoryProperties{};
  VkDescriptorSetLayout descriptors = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  std::array<VkPipeline, 8> pipelines{};
  bool cacheResponses = false;
  bool tiledReconstruction = false;
  VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
  VkDescriptorSet set = VK_NULL_HANDLE;
  VkCommandPool commandPool = VK_NULL_HANDLE;
  VkCommandBuffer command = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  VkQueryPool queryPool = VK_NULL_HANDLE;
  uint32_t timestampBits = 0;
  std::vector<int> queryStages;
  std::vector<double> lastGpuCosts = std::vector<double>(8, -1.0);
  std::array<Buffer, 12> buffers;
  int width = 0, height = 0;
  uint32_t phase = 0;
  bool submitted = false;
  bool halfFrame = false;
  bool builtP010 = false;
  std::string name;
  std::mutex mutex;
  // P010 frames keep two frames in flight: everything a submitted frame
  // reads from the host or records (descriptor set, command buffer, fence,
  // semaphore, timestamps, config and metadata) exists twice; the idle copy
  // lives here and is swapped in for alternate frames. GPU-only buffers are
  // shared and ordered by a compute barrier at the start of each frame.
  struct Slot {
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkSemaphore completion = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;
    std::vector<int> queryStages;
    bool submitted = false, statusPending = false;
    Buffer *recordedP010 = nullptr;
    int recordedP010W = 0, recordedP010H = 0, recordedStagingHalf = -1;
    Buffer config, meta;
  };
  Slot spare;
  bool spareReady = false;
  void swapSlot() {
    std::swap(set, spare.set);
    std::swap(command, spare.command);
    std::swap(fence, spare.fence);
    std::swap(completion, spare.completion);
    std::swap(queryPool, spare.queryPool);
    std::swap(queryStages, spare.queryStages);
    std::swap(submitted, spare.submitted);
    std::swap(statusPending, spare.statusPending);
    std::swap(recordedP010, spare.recordedP010);
    std::swap(recordedP010W, spare.recordedP010W);
    std::swap(recordedP010H, spare.recordedP010H);
    std::swap(recordedStagingHalf, spare.recordedStagingHalf);
    std::swap(buffers[7], spare.config);
    std::swap(buffers[10], spare.meta);
  }
  void prepareSpare();
  void releaseSpare() {
    if (!spareReady)
      return;
    swapSlot();
    await();
    swapSlot();
    if (spare.command)
      vkFreeCommandBuffers(device, commandPool, 1, &spare.command);
    if (spare.fence)
      vkDestroyFence(device, spare.fence, nullptr);
    if (spare.completion)
      vkDestroySemaphore(device, spare.completion, nullptr);
    if (spare.queryPool)
      vkDestroyQueryPool(device, spare.queryPool, nullptr);
    for (Buffer *b : {&spare.config, &spare.meta}) {
      if (b->mapped)
        vkUnmapMemory(device, b->memory);
      if (b->buffer)
        vkDestroyBuffer(device, b->buffer, nullptr);
      if (b->memory)
        vkFreeMemory(device, b->memory, nullptr);
    }
    spare = Slot{};  // its descriptor set is freed with the pool
    spareReady = false;
  }

  void await() {
    if (!submitted)
      return;
    VkResult r = vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ull);
    if (r != VK_SUCCESS) {
      // Timeout is not proof of completion. Drain before releasing or reusing.
      VkResult drained = vkDeviceWaitIdle(device);
      submitted = false;
      ok(drained, "drain after incomplete frame");
      ok(r, "frame completion");
    }
    submitted = false;
  }
  void releaseFrame() {
    if (!device)
      return;
    releaseSpare();
    await();
    if (command)
      vkFreeCommandBuffers(device, commandPool, 1, &command);
    command = VK_NULL_HANDLE;
    if (descriptorPool)
      vkDestroyDescriptorPool(device, descriptorPool, nullptr);
    descriptorPool = VK_NULL_HANDLE;
    set = VK_NULL_HANDLE;
    for (auto &b : buffers) {
      if (b.mapped)
        vkUnmapMemory(device, b.memory);
      if (b.buffer)
        vkDestroyBuffer(device, b.buffer, nullptr);
      if (b.memory)
        vkFreeMemory(device, b.memory, nullptr);
      b = Buffer{};
    }
    for (auto &i : images) {
      if (i.view)
        vkDestroyImageView(device, i.view, nullptr);
      if (i.image)
        vkDestroyImage(device, i.image, nullptr);
      if (i.memory)
        vkFreeMemory(device, i.memory, nullptr);
      i = Image{};
    }
    width = height = 0;
    recordedOutput = nullptr;
  }
  ~State() {
    if (device)
      vkDeviceWaitIdle(device);
    submitted = false;
    releaseFrame();
#ifdef __ANDROID__
    for (auto &entry : p010Imports) {
      if (entry.second.buffer)
        vkDestroyBuffer(device, entry.second.buffer, nullptr);
      if (entry.second.memory)
        vkFreeMemory(device, entry.second.memory, nullptr);
      AHardwareBuffer_release(static_cast<AHardwareBuffer *>(entry.first));
    }
#endif
    if (completion)
      vkDestroySemaphore(device, completion, nullptr);
    for (auto &entry : outputImages) {
      auto &i = entry.second;
      if (i.view)
        vkDestroyImageView(device, i.view, nullptr);
      if (i.image)
        vkDestroyImage(device, i.image, nullptr);
      if (i.memory)
        vkFreeMemory(device, i.memory, nullptr);
    }
    if (fence)
      vkDestroyFence(device, fence, nullptr);
    if (queryPool)
      vkDestroyQueryPool(device, queryPool, nullptr);
    for (auto pipeline : pipelines)
      if (pipeline)
        vkDestroyPipeline(device, pipeline, nullptr);
    if (layout)
      vkDestroyPipelineLayout(device, layout, nullptr);
    if (descriptors)
      vkDestroyDescriptorSetLayout(device, descriptors, nullptr);
    if (commandPool)
      vkDestroyCommandPool(device, commandPool, nullptr);
    if (device)
      vkDestroyDevice(device, nullptr);
    if (instance)
      vkDestroyInstance(instance, nullptr);
  }
  // deviceUpload: CPU-written/GPU-read buffers in device-local, host-visible,
  // uncached memory. Mali's host-cached type is I/O-coherent and makes GPU
  // reads of frequently sampled data (CFA, product map) markedly slower.
  void allocate(Buffer &b, VkDeviceSize bytes, bool gpuOnly = false,
                bool deviceUpload = false) {
    b.bytes = bytes;
    if (bytes > properties.limits.maxStorageBufferRange)
      throw std::runtime_error("unsupported SSBO range");
    VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    ci.size = bytes;
    ci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    ok(vkCreateBuffer(device, &ci, nullptr, &b.buffer), "create frame buffer");
    VkMemoryRequirements requirement;
    vkGetBufferMemoryRequirements(device, b.buffer, &requirement);
    uint32_t type = memoryProperties.memoryTypeCount;
    // Prefer CPU-cached unified memory, with explicit flush/invalidate. Mali's
    // first coherent type is uncached on the host and makes frame readback
    // costly.
    if (deviceUpload && !gpuOnly)
      for (uint32_t candidate = 0; candidate < memoryProperties.memoryTypeCount;
           ++candidate) {
        auto flags = memoryProperties.memoryTypes[candidate].propertyFlags;
        if ((requirement.memoryTypeBits & (1u << candidate)) &&
            (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) &&
            (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            !(flags & (VK_MEMORY_PROPERTY_HOST_CACHED_BIT |
                       VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT |
                       VK_MEMORY_PROPERTY_PROTECTED_BIT))) {
          type = candidate;
          break;
        }
      }
    if (gpuOnly) {
      for (uint32_t candidate = 0; candidate < memoryProperties.memoryTypeCount;
           ++candidate) {
        auto flags = memoryProperties.memoryTypes[candidate].propertyFlags;
        if ((requirement.memoryTypeBits & (1u << candidate)) &&
            (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) &&
            !(flags & (VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT |
                       VK_MEMORY_PROPERTY_PROTECTED_BIT))) {
          type = candidate;
          break;
        }
      }
    }
    for (int preference = 0;
         preference < 2 && type == memoryProperties.memoryTypeCount;
         ++preference)
      for (uint32_t candidate = 0; candidate < memoryProperties.memoryTypeCount;
           ++candidate) {
        auto flags = memoryProperties.memoryTypes[candidate].propertyFlags;
        if ((requirement.memoryTypeBits & (1u << candidate)) &&
            (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) &&
            (preference || (flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT))) {
          type = candidate;
          break;
        }
      }
    if (type == memoryProperties.memoryTypeCount)
      throw std::runtime_error("unsupported coherent upload memory");
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = requirement.size;
    ai.memoryTypeIndex = type;
    ok(vkAllocateMemory(device, &ai, nullptr, &b.memory),
       "allocate frame memory");
    ok(vkBindBufferMemory(device, b.buffer, b.memory, 0), "bind frame memory");
    b.coherent = (memoryProperties.memoryTypes[type].propertyFlags &
                  VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    if (!gpuOnly)
      ok(vkMapMemory(device, b.memory, 0, VK_WHOLE_SIZE, 0, &b.mapped),
         "map frame memory");
  }
  void flush(size_t index) {
    if (buffers[index].coherent)
      return;
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    range.memory = buffers[index].memory;
    range.size = VK_WHOLE_SIZE;
    ok(vkFlushMappedMemoryRanges(device, 1, &range), "flush RCD host writes");
  }
  void allocateImage(Image &image, VkFormat format, uint32_t layers) {
    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(physical, format, &fp);
    if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) ||
        layers > properties.limits.maxImageArrayLayers)
      throw std::runtime_error("Unsupported RCD storage image representation");
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = format;
    info.extent = {194, 194, 1};
    info.mipLevels = 1;
    info.arrayLayers = layers;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_STORAGE_BIT;
    ok(vkCreateImage(device, &info, nullptr, &image.image),
       "create RCD intermediate image");
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(device, image.image, &req);
    uint32_t type = memoryProperties.memoryTypeCount;
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
      auto flags = memoryProperties.memoryTypes[i].propertyFlags;
      if ((req.memoryTypeBits & (1u << i)) &&
          (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) &&
          !(flags & (VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT |
                     VK_MEMORY_PROPERTY_PROTECTED_BIT))) {
        type = i;
        break;
      }
    }
    if (type == memoryProperties.memoryTypeCount)
      throw std::runtime_error("No device-local storage image memory");
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = type;
    ok(vkAllocateMemory(device, &ai, nullptr, &image.memory),
       "allocate RCD image memory");
    ok(vkBindImageMemory(device, image.image, image.memory, 0),
       "bind RCD image memory");
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = image.image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    vi.format = format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, layers};
    ok(vkCreateImageView(device, &vi, nullptr, &image.view),
       "create RCD image view");
  }
  void invalidate(size_t index) {
    if (buffers[index].coherent)
      return;
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    range.memory = buffers[index].memory;
    range.size = VK_WHOLE_SIZE;
    ok(vkInvalidateMappedMemoryRanges(device, 1, &range),
       "invalidate RCD host readback");
  }
  void frame(int w, int h, uint32_t cfaPhase, bool half = false,
             size_t rawCapacity = 0) {
    if (frameMatches(w, h, cfaPhase, half, rawCapacity)) {
      if (p010Frame && (recordedP010 != selectedP010 || recordedP010H != p010H ||
                        recordedP010W != p010W || recordedStagingHalf != stagingHalf)) {
        updateP010Descriptor();
        if (command)
          vkFreeCommandBuffers(device, commandPool, 1, &command);
        command = VK_NULL_HANDLE;
        recordCommands(w, h, cfaPhase, half);
      }
      if (imageOutput && recordedOutput != selectedOutput) {
        updateOutputDescriptor();
        if (command)
          vkFreeCommandBuffers(device, commandPool, 1, &command);
        command = VK_NULL_HANDLE;
        recordCommands(w, h, cfaPhase, half);
      }
      return;
    }
    releaseFrame();
    if (frameGlobal && (w % 4 || w < 64 || h < 64))
      throw std::invalid_argument("frame-global RCD requires width%4==0, 64x64 minimum");
    const size_t n = size_t(w) * h;
    const uint32_t tiles = ((w + 175) / 176) * ((h + 175) / 176);
    if (tiles > properties.limits.maxComputeWorkGroupCount[2])
      throw std::runtime_error("unsupported tile dispatch count");
    const size_t atlas = size_t(tiles) * 194 * 194;
    for (size_t i = 0; i < 7; ++i) {
      VkDeviceSize bytes;
      if (recordingCandidate)
        bytes =
            (i == 0                                                ? n * 4
             : i == 6                                              ? n * 16
             : (i == 1 || i == 4 || i == 5) && !imageIntermediates ? atlas * 16
                                                                   : 16);
      else
        bytes = imageIntermediates && i >= 1 && i <= 5
                    ? 16
                    : (i == 0 || i == 6 ? n : atlas) * (i == 6   ? 16
                                                        : i == 5 ? 8
                                                                 : 4);
      if (recordingCandidate && (cacheResponses || packedCells)) {
        if (i == 2 && cacheResponses)
          bytes = atlas * 16;
        if (i == 1 && packedCells)
          bytes = atlas * 8;
        if (i == 4)
          bytes = atlas * 4;
        if (i == 5)
          bytes = atlas * 8;
      }
      if (rawInput)
        bytes = i == 0   ? size_t((w + 1) / 2) * ((h + 1) / 2) * 8
                : i == 6 ? n * 16
                         : atlas * ((i == 2 || i == 3 || i == 5) ? 2 : 4);
      if (tileLocal && half && i == 6)
        bytes = 16;
      if (tileLocal && i >= 1 && i <= 5)
        bytes = 16;
      // Five FP16 planes (VH, PQ, LP, green, missing), four pixels per uvec2.
      if (frameGlobal && i == 1)
        bytes = n * 10 + size_t(2 * 9 * w + 18 * (h - 18)) * 8;
      if (rawInput && normalizeOnce && i == 0)
        bytes = n * 4;
      allocate(buffers[i], bytes,
               (i >= 1 && i <= 5) || (i == 6 && half) || (frameGlobal && i == 0));
    }
    if (imageIntermediates)
      for (size_t i = 0; i < 5; ++i) {
        if (recordingCandidate && (i == 1 || i == 2))
          continue;
        allocateImage(images[i],
                      recordingCandidate || i == 4
                          ? VK_FORMAT_R32G32B32A32_SFLOAT
                          : VK_FORMAT_R32_SFLOAT,
                      tiles);
      }
    allocate(buffers[7], (16 + 65536 * 4) * 4, false, frameGlobal);
    // P010 frames: output-resolution product RGB, GPU-only (stage 4 -> 5).
    allocate(buffers[8], imageOutput ? 16 : n * 8, p010Frame);
    if (frameGlobal)
      allocate(buffers[11], 16, true);
    if (rawInput) {
      allocate(buffers[9],
               p010Frame ? 2 * ((rawCapacity + 255) & ~size_t(255)) : rawCapacity,
               false, frameGlobal);
      allocate(buffers[10], 256 + (frameGlobal ? sampleMaskWords(w, h) * 4 : 0), false, frameGlobal);
      // Unwritten metadata words must read as "off", never stale memory.
      std::memset(buffers[10].mapped, 0, buffers[10].bytes);
    }
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, uint32_t(2 * bindingCount())},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 10}};
    VkDescriptorPoolCreateInfo pi{
        VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = 2;
    pi.poolSizeCount = (imageIntermediates || imageOutput) ? 2 : 1;
    pi.pPoolSizes = sizes;
    ok(vkCreateDescriptorPool(device, &pi, nullptr, &descriptorPool),
       "create descriptor pool");
    VkDescriptorSetAllocateInfo di{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    di.descriptorPool = descriptorPool;
    di.descriptorSetCount = 1;
    di.pSetLayouts = &descriptors;
    ok(vkAllocateDescriptorSets(device, &di, &set), "allocate descriptor set");
    std::vector<VkDescriptorBufferInfo> infos(bindingCount());
    std::vector<VkWriteDescriptorSet> writes(bindingCount());
    std::array<VkDescriptorImageInfo, 5> imageInfos{};
    for (size_t i = 0; i < bindingCount(); ++i) {
      infos[i] = {buffers[i].buffer, 0, buffers[i].bytes};
      auto &write = writes[i];
      write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      write.dstSet = set;
      write.dstBinding = i;
      write.descriptorCount = 1;
      write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      write.pBufferInfo = &infos[i];
      if (imageIntermediates && i >= 1 && i <= 5 &&
          (!recordingCandidate || i == 1 || i >= 4)) {
        imageInfos[i - 1] = {VK_NULL_HANDLE, images[i - 1].view,
                             VK_IMAGE_LAYOUT_GENERAL};
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        write.pBufferInfo = nullptr;
        write.pImageInfo = &imageInfos[i - 1];
      }
    }
    VkDescriptorImageInfo outputInfo{};
    if (imageOutput) {
      if (!selectedOutput)
        throw std::runtime_error("GPU output image not selected");
      outputInfo = {VK_NULL_HANDLE, selectedOutput->view,
                    VK_IMAGE_LAYOUT_GENERAL};
      writes[8].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
      writes[8].pBufferInfo = nullptr;
      writes[8].pImageInfo = &outputInfo;
    }
    if (p010Frame && selectedP010)
      infos[11] = {selectedP010->buffer, 0, VK_WHOLE_SIZE};
    if (p010Frame) {
      infos[9] = {buffers[9].buffer,
                  VkDeviceSize(stagingHalf) * ((rawCapacity + 255) & ~size_t(255)),
                  rawCapacity};
    }
    vkUpdateDescriptorSets(device, writes.size(), writes.data(), 0, nullptr);
    builtP010 = p010Frame;
    recordCommands(w, h, cfaPhase, half);
    width = w;
    height = h;
    phase = cfaPhase;
    halfFrame = half;
    rawBytes = rawCapacity;
  }
  void updateP010Descriptor() {
    VkDescriptorBufferInfo info[2] = {
        {selectedP010->buffer, 0, VK_WHOLE_SIZE},
        {buffers[9].buffer, VkDeviceSize(stagingHalf) * stagingSlot(), rawBytes}};
    VkWriteDescriptorSet write[2]{};
    for (int i = 0; i < 2; ++i) {
      write[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      write[i].dstSet = set;
      write[i].dstBinding = i == 0 ? 11 : 9;
      write[i].descriptorCount = 1;
      write[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      write[i].pBufferInfo = &info[i];
    }
    vkUpdateDescriptorSets(device, 2, write, 0, nullptr);
  }
#ifdef __ANDROID__
  // Import an encoder P010 buffer (BLOB or YCbCr P010 AHB) as a storage
  // buffer; cached and retained for the engine lifetime like output images.
  void selectP010(AHardwareBuffer *ahb, VkDeviceSize required) {
    auto found = p010Imports.find(ahb);
    if (found != p010Imports.end()) {
      if (found->second.bytes < required)
        throw std::invalid_argument("P010 buffer smaller than layout");
      selectedP010 = &found->second;
      return;
    }
    VkAndroidHardwareBufferPropertiesANDROID props{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    ok(getAhbProperties(device, ahb, &props), "query P010 Android buffer");
    if (props.allocationSize < required)
      throw std::invalid_argument("P010 allocation smaller than layout");
    Buffer b;
    VkExternalMemoryBufferCreateInfo ext{
        VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO};
    ext.handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.pNext = &ext;
    bi.size = props.allocationSize;
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    ok(vkCreateBuffer(device, &bi, nullptr, &b.buffer), "create P010 buffer");
    VkMemoryDedicatedAllocateInfo dedicated{
        VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.buffer = b.buffer;
    VkImportAndroidHardwareBufferInfoANDROID import{
        VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    import.pNext = &dedicated;
    import.buffer = ahb;
    uint32_t type = 0;
    while (type < 32 && !(props.memoryTypeBits & (1u << type)))
      ++type;
    if (type == 32)
      throw std::runtime_error("No memory type for P010 import");
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.pNext = &import;
    ai.allocationSize = props.allocationSize;
    ai.memoryTypeIndex = type;
    VkResult r = vkAllocateMemory(device, &ai, nullptr, &b.memory);
    if (r != VK_SUCCESS) {
      vkDestroyBuffer(device, b.buffer, nullptr);
      ok(r, "import P010 memory");
    }
    ok(vkBindBufferMemory(device, b.buffer, b.memory, 0), "bind P010 memory");
    b.bytes = props.allocationSize;
    AHardwareBuffer_acquire(ahb);
    selectedP010 = &(p010Imports[ahb] = b);
  }
#endif
  void updateOutputDescriptor() {
    VkDescriptorImageInfo info{VK_NULL_HANDLE, selectedOutput->view,
                               VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    write.dstSet = set;
    write.dstBinding = 8;
    write.descriptorCount = 1;
    write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    write.pImageInfo = &info;
    vkUpdateDescriptorSets(device, 1, &write, 0, nullptr);
  }
  void recordCommands(int w, int h, uint32_t cfaPhase, bool half) {
    const uint32_t tiles = ((w + 175) / 176) * ((h + 175) / 176);
    VkCommandBufferAllocateInfo ai{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = commandPool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    ok(vkAllocateCommandBuffers(device, &ai, &command),
       "allocate command buffer");
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    ok(vkBeginCommandBuffer(command, &bi), "begin frame");
    if (imageIntermediates) {
      std::vector<VkImageMemoryBarrier> transitions;
      for (size_t i = 0; i < 5; ++i) {
        if (!images[i].image)
          continue;
        transitions.emplace_back();
        auto &b = transitions.back();
        b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = images[i].image;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, tiles};
        b.dstAccessMask =
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      }
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr,
                           0, nullptr, transitions.size(), transitions.data());
    }
    if (imageOutput) {
      VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
      // Every pixel is overwritten; discarding old contents permits UNDEFINED
      // on every acquisition. The caller has proved prior grade completion.
      b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
      b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
      b.dstQueueFamilyIndex = family;
      b.image = selectedOutput->image;
      b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      b.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr,
                           0, nullptr, 1, &b);
    }
    if (queryPool)
      vkCmdResetQueryPool(command, queryPool, 0, 16);
    queryStages.clear();
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    // Host inputs, and (two frames in flight) the previous frame's use of the
    // shared GPU-only planes: both precede this frame's first dispatch.
    barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(command,
                         VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                         0, nullptr, 0, nullptr);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0,
                            1, &set, 0, nullptr);
    std::vector<int> stages;
    if (rawInput)
      stages.push_back(7);
    if (cacheResponses)
      stages.push_back(rawInput ? 5 : 7);
    for (int pass = 0; pass < 5; ++pass)
      if (!tileLocal || pass == 0 || pass == 4)
        stages.push_back(pass);
    if (half)
      stages.push_back(6);
    if (tileLocal && half)
      stages = frameGlobal ? (p010Frame ? std::vector<int>{0, 1, 3, 2, 5}
                                        : std::vector<int>{0, 1, 2, 3})
                           : std::vector<int>{7, 3, 5};
    if (frameGlobal && !half)
      throw std::invalid_argument("frame-global RCD has no FP32 readback mode");
    for (const int stage : stages) {
      if (tiledReconstruction && (stage == 2 || stage == 3))
        continue;
      if (queryPool)
        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            queryPool, queryStages.size() * 2);
      vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipelines[stage] ? pipelines[stage] : pipelines[0]);
      int32_t parameters[] = {w, h, int32_t(cfaPhase), stage};
      vkCmdPushConstants(command, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16,
                         parameters);
      const int edge = tiledReconstruction ? 16 : 8;
      const int dispatchX = tileLocal ? int(coreX) : edge,
                dispatchY = tileLocal ? int(coreY) : edge;
      if (stage == 7 && rawInput)
        vkCmdDispatch(command, ((w + 1) / 2 + 7) / 8, ((h + 1) / 2 + 7) / 8, 1);
      else if (frameGlobal && stage == 4)
        // Sampled final pass: 56-pixel bands, 8 output rows per group.
        vkCmdDispatch(command, (w + 55) / 56, (p010H + 7) / 8, 1);
      else if (frameGlobal && stage == 5)
        // Grade + P010 pack: one invocation per 2x2 output cell.
        vkCmdDispatch(command, (p010W / 2 + 15) / 16, p010H / 2, 1);
      else if (frameGlobal && stage == 3)
        vkCmdDispatch(command, (2 * 9 * w + 18 * (h - 18) + 15) / 16, 1, 1);
      else if (frameGlobal)
        // Bands: 64 pixels per subgroup, overlapped by the stencil erosion
        // (8 pixels for guides/green-missing, 4 for the final pass).
        // Guides and green/missing walk 64-row blocks; the final pass is one
        // subgroup per output row.
        vkCmdDispatch(command, (w + (stage == 2 ? 55 : 47)) / (stage == 2 ? 56 : 48),
                      stage == 2 ? (h + 7) / 8 : (h + 127) / 128, 1);
      else if (stage == 4 || stage == 6 ||
               (stage == 5 && rawInput && (cacheResponses || tileLocal)) ||
               (stage == 7 && !recordingCandidate))
        vkCmdDispatch(command, (w + dispatchX - 1) / dispatchX,
                      (h + dispatchY - 1) / dispatchY, 1);
      else if (recordingCandidate &&
               (((cacheResponses || packedCells) && stage == 1) || stage == 2 ||
                stage == 3))
        vkCmdDispatch(command, 13, 25, tiles);
      else if (tileLocal && (stage == 0 || stage == 3))
        vkCmdDispatch(command, (176 + coreX - 1) / coreX,
                      (176 + coreY - 1) / coreY, tiles);
      else if (tiledReconstruction && stage == 1)
        vkCmdDispatch(command, 11, 11, tiles);
      else
        vkCmdDispatch(command, (194 + edge - 1) / edge, (194 + edge - 1) / edge,
                      tiles);
      if (queryPool)
        vkCmdWriteTimestamp(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            queryPool, queryStages.size() * 2 + 1);
      queryStages.push_back(stage);
      barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                           0, nullptr, 0, nullptr);
    }
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, nullptr,
                         0, nullptr);
    if (imageOutput) {
      VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
      b.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
      b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
      b.srcQueueFamilyIndex = family;
      b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
      b.image = selectedOutput->image;
      b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr,
                           0, nullptr, 1, &b);
    }
    ok(vkEndCommandBuffer(command), "end frame");
    recordedOutput = selectedOutput;
    recordedP010 = selectedP010;
    recordedP010H = p010H;
    recordedP010W = p010W;
    recordedStagingHalf = stagingHalf;
  }
  uint64_t outputUsage() const {
#ifndef __ANDROID__
    throw std::runtime_error("Android output image usage unavailable");
#else
    VkPhysicalDeviceExternalImageFormatInfo external{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};
    external.handleType =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkPhysicalDeviceImageFormatInfo2 info{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};
    info.pNext = &external;
    info.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    info.type = VK_IMAGE_TYPE_2D;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    VkAndroidHardwareBufferUsageANDROID usage{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_USAGE_ANDROID};
    VkExternalImageFormatProperties externalProps{
        VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    externalProps.pNext = &usage;
    VkImageFormatProperties2 properties{
        VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};
    properties.pNext = &externalProps;
    ok(vkGetPhysicalDeviceImageFormatProperties2(physical, &info, &properties),
       "query GPU output allocation usage");
    if (!(externalProps.externalMemoryProperties.externalMemoryFeatures &
          VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT))
      throw std::runtime_error("GPU image import unsupported");
    return usage.androidHardwareBufferUsage;
#endif
  }
  void selectOutput(void *opaque, int w, int h) {
#ifndef __ANDROID__
    throw std::runtime_error("Android output images unavailable");
#else
    auto *buffer = static_cast<AHardwareBuffer *>(opaque);
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.width != uint32_t(w) || desc.height != uint32_t(h) ||
        desc.layers != 1 ||
        desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT)
      throw std::invalid_argument("Invalid GPU RGBA16F output layout");
    auto existing = outputImages.find(opaque);
    if (existing != outputImages.end()) {
      if (!existing->second.view)
        throw std::runtime_error("Incomplete output import");
      selectedOutput = &existing->second;
      return;
    }
    VkPhysicalDeviceExternalImageFormatInfo external{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};
    external.handleType =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkPhysicalDeviceImageFormatInfo2 info{
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2};
    info.pNext = &external;
    info.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    info.type = VK_IMAGE_TYPE_2D;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    VkAndroidHardwareBufferUsageANDROID usage{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_USAGE_ANDROID};
    VkExternalImageFormatProperties externalProps{
        VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    externalProps.pNext = &usage;
    VkImageFormatProperties2 formatProps{
        VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2};
    formatProps.pNext = &externalProps;
    ok(vkGetPhysicalDeviceImageFormatProperties2(physical, &info, &formatProps),
       "query external RGBA16F storage support");
    if (!(externalProps.externalMemoryProperties.externalMemoryFeatures &
          VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) ||
        (desc.usage & usage.androidHardwareBufferUsage) !=
            usage.androidHardwareBufferUsage)
      throw std::runtime_error(
          "Unsupported external storage image usage/import: features=" +
          std::to_string(
              externalProps.externalMemoryProperties.externalMemoryFeatures) +
          " actual_usage=" + std::to_string(desc.usage) + " required_usage=" +
          std::to_string(usage.androidHardwareBufferUsage));
    VkAndroidHardwareBufferFormatPropertiesANDROID format{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{
        VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
    props.pNext = &format;
    ok(getAhbProperties(device, buffer, &props), "query output Android buffer");
    if (format.format != VK_FORMAT_R16G16B16A16_SFLOAT)
      throw std::runtime_error("Unsupported external output format");
    auto &out = outputImages[opaque];
    VkExternalMemoryImageCreateInfo ext{
        VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    ext.handleTypes = external.handleType;
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.pNext = &ext;
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = info.format;
    ci.extent = {uint32_t(w), uint32_t(h), 1};
    ci.mipLevels = 1;
    ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = info.tiling;
    ci.usage = info.usage;
    ok(vkCreateImage(device, &ci, nullptr, &out.image),
       "create external output image");
    uint32_t type = memoryProperties.memoryTypeCount;
    for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i)
      if ((props.memoryTypeBits & (1u << i)) &&
          !(memoryProperties.memoryTypes[i].propertyFlags &
            (VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT |
             VK_MEMORY_PROPERTY_PROTECTED_BIT))) {
        type = i;
        break;
      }
    if (type == memoryProperties.memoryTypeCount)
      throw std::runtime_error("No external output memory type");
    VkImportAndroidHardwareBufferInfoANDROID import{
        VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    import.buffer = buffer;
    VkMemoryDedicatedAllocateInfo dedicated{
        VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    dedicated.pNext = &import;
    dedicated.image = out.image;
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.pNext = &dedicated;
    allocation.allocationSize = props.allocationSize;
    allocation.memoryTypeIndex = type;
    ok(vkAllocateMemory(device, &allocation, nullptr, &out.memory),
       "import output memory");
    ok(vkBindImageMemory(device, out.image, out.memory, 0),
       "bind external output");
    VkImageViewCreateInfo view{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    view.image = out.image;
    view.viewType = VK_IMAGE_VIEW_TYPE_2D;
    view.format = info.format;
    view.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    ok(vkCreateImageView(device, &view, nullptr, &out.view),
       "create external output view");
    selectedOutput = &out;
#endif
  }
  void collectCosts() {
    if (!queryPool)
      return;
    std::vector<uint64_t> times(queryStages.size() * 2);
    ok(vkGetQueryPoolResults(device, queryPool, 0, times.size(),
                             times.size() * 8, times.data(), 8,
                             VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
       "read RCD timestamps");
    uint64_t mask =
        timestampBits == 64 ? ~uint64_t(0) : (uint64_t(1) << timestampBits) - 1;
    std::fill(lastGpuCosts.begin(), lastGpuCosts.end(), -1.0);
    for (size_t i = 0; i < queryStages.size(); ++i)
      lastGpuCosts[queryStages[i]] =
          double((times[i * 2 + 1] - times[i * 2]) & mask) *
          properties.limits.timestampPeriod / 1e6;
  }
};

void Engine::State::prepareSpare() {
  if (spareReady)
    return;
  VkDescriptorSetAllocateInfo di{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  di.descriptorPool = descriptorPool;
  di.descriptorSetCount = 1;
  di.pSetLayouts = &descriptors;
  ok(vkAllocateDescriptorSets(device, &di, &spare.set), "allocate spare descriptor set");
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
  ok(vkCreateFence(device, &fi, nullptr, &spare.fence), "create spare fence");
  VkExportSemaphoreCreateInfo exportInfo{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
  exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
  VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  semaphoreInfo.pNext = &exportInfo;
  ok(vkCreateSemaphore(device, &semaphoreInfo, nullptr, &spare.completion),
     "create spare completion semaphore");
  if (timestampBits) {
    VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qi.queryCount = 16;
    ok(vkCreateQueryPool(device, &qi, nullptr, &spare.queryPool), "create spare timestamps");
  }
  allocate(spare.config, buffers[7].bytes, false, frameGlobal);
  allocate(spare.meta, buffers[10].bytes, false, frameGlobal);
  std::memcpy(spare.config.mapped, buffers[7].mapped, buffers[7].bytes);
  std::memcpy(spare.meta.mapped, buffers[10].mapped, buffers[10].bytes);
  spareReady = true;
  // Write every binding of the spare set from the swapped-in view.
  swapSlot();
  std::vector<VkDescriptorBufferInfo> infos(bindingCount());
  std::vector<VkWriteDescriptorSet> writes(bindingCount());
  for (size_t i = 0; i < bindingCount(); ++i) {
    infos[i] = {buffers[i].buffer, 0, buffers[i].bytes};
    if (i == 9)
      infos[i] = {buffers[9].buffer, 0, rawBytes};
    if (i == 11)
      infos[i] = {selectedP010->buffer, 0, VK_WHOLE_SIZE};
    writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    writes[i].dstSet = set;
    writes[i].dstBinding = uint32_t(i);
    writes[i].descriptorCount = 1;
    writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[i].pBufferInfo = &infos[i];
  }
  vkUpdateDescriptorSets(device, writes.size(), writes.data(), 0, nullptr);
  swapSlot();
}

Engine::Engine(const std::vector<uint32_t> &shader, bool specializePasses,
               bool cacheResponses, bool tiledReconstruction,
               bool imageIntermediates, bool recordingCandidate,
               const std::vector<std::vector<uint32_t>> &stageShaders,
               bool packedCells, const std::vector<uint32_t> &rawPackShader,
               int reconstructionScale, bool normalizeOnce, bool tileLocal,
               bool imageOutput, std::array<uint32_t, 2> tileShape,
               bool frameGlobal)
    : state(new State) {
  auto &s = *state;
  s.rawInput = !rawPackShader.empty();
  if (normalizeOnce && (!s.rawInput || reconstructionScale != 1))
    throw std::invalid_argument(
        "normalization cache requires full-resolution RAW route");
  if (tileLocal &&
      (!normalizeOnce || !s.rawInput || cacheResponses || tiledReconstruction ||
       imageIntermediates || recordingCandidate))
    throw std::invalid_argument(
        "tile-local route requires full-source normalized RAW only");
  if (imageOutput && !tileLocal)
    throw std::invalid_argument("GPU image output requires tile-local RCD");
  if (frameGlobal && !tileLocal)
    throw std::invalid_argument("frame-global RCD uses the tile-local route");
#ifndef __ANDROID__
  if (imageOutput)
    throw std::runtime_error(
        "Android output images unavailable on host engine");
#endif
  for (auto edge : tileShape)
    if (edge != 8 && edge != 16 && edge != 32)
      throw std::invalid_argument("Unsupported tile shape");
  if (tileShape[0] * tileShape[1] > 512)
    throw std::invalid_argument("Unsupported tile invocation count");
  s.coreX = tileShape[0];
  s.coreY = tileShape[1];
  uint32_t localBytes = 0;
  for (uint32_t radius : {11, 6, 7, 4, 5, 3})
    localBytes += (s.coreX + 2 * radius) * (s.coreY + 2 * radius) * 4;
  s.frameGlobal = frameGlobal;
  if (frameGlobal) {
    // One 16-lane subgroup per group; no shared memory.
    s.coreX = 16;
    s.coreY = 1;
    localBytes = 0;
  }
  s.imageOutput = imageOutput;
  s.tileLocal = tileLocal;
  s.normalizeOnce = normalizeOnce;
  if (reconstructionScale < 1 || reconstructionScale > 8 ||
      (!s.rawInput && reconstructionScale != 1))
    throw std::invalid_argument("unsupported reconstruction scale");
  s.reconstructionScale = reconstructionScale;
  if (s.rawInput &&
      (!specializePasses || recordingCandidate ||
       (cacheResponses && !normalizeOnce) || tiledReconstruction ||
       imageIntermediates || packedCells || !stageShaders.empty() ||
       rawPackShader.size() < 5 || rawPackShader[0] != 0x07230203))
    throw std::invalid_argument(
        "incompatible requirements input configuration");
  s.recordingCandidate = recordingCandidate;
  s.packedCells = packedCells;
  if (packedCells && (!recordingCandidate || cacheResponses ||
                      imageIntermediates || !stageShaders.empty()))
    throw std::invalid_argument("unsupported packed-cell combination");
  if (recordingCandidate &&
      (tiledReconstruction || (cacheResponses && imageIntermediates)))
    throw std::invalid_argument("candidate cannot combine atlas variants");
  s.cacheResponses = cacheResponses;
  s.tiledReconstruction = tiledReconstruction;
  s.imageIntermediates = imageIntermediates;
  if (!stageShaders.empty()) {
    if (!specializePasses || !recordingCandidate || !cacheResponses ||
        imageIntermediates || stageShaders.size() != 8)
      throw std::invalid_argument("invalid stage-program configuration");
    for (int pass : {0, 1, 2, 3, 4, 6, 7})
      if (stageShaders[pass].size() < 5 || stageShaders[pass][0] != 0x07230203)
        throw std::invalid_argument("invalid stage-program SPIR-V");
  }
  if (shader.empty() || shader[0] != 0x07230203)
    throw std::invalid_argument("invalid RCD SPIR-V");
  VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  application.pApplicationName = "RawLens public-source photo RCD";
  application.apiVersion = VK_API_VERSION_1_1;
  uint32_t count = 0;
  ok(vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr),
     "instance extensions");
  std::vector<VkExtensionProperties> extensions(count);
  ok(vkEnumerateInstanceExtensionProperties(nullptr, &count, extensions.data()),
     "instance extensions");
  std::vector<const char *> enabled;
  for (auto &e : extensions)
    if (std::strcmp(e.extensionName, "VK_KHR_portability_enumeration") == 0)
      enabled.push_back("VK_KHR_portability_enumeration");
  VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  ci.pApplicationInfo = &application;
  ci.enabledExtensionCount = enabled.size();
  ci.ppEnabledExtensionNames = enabled.data();
  if (!enabled.empty())
    ci.flags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
  ok(vkCreateInstance(&ci, nullptr, &s.instance), "create instance");
  ok(vkEnumeratePhysicalDevices(s.instance, &count, nullptr),
     "enumerate devices");
  if (!count)
    throw std::runtime_error("no Vulkan device");
  std::vector<VkPhysicalDevice> devices(count);
  ok(vkEnumeratePhysicalDevices(s.instance, &count, devices.data()),
     "enumerate devices");
  for (auto physical : devices) {
    VkPhysicalDeviceProperties p;
    vkGetPhysicalDeviceProperties(physical, &p);
    if (p.apiVersion < VK_API_VERSION_1_1 ||
        (tileLocal && p.limits.maxComputeSharedMemorySize < localBytes) ||
        p.limits.maxPerStageDescriptorStorageBuffers < s.bindingCount() ||
        p.limits.maxDescriptorSetStorageBuffers < s.bindingCount() ||
        (imageIntermediates && (p.limits.maxPerStageDescriptorStorageImages <
                                    (recordingCandidate ? 3u : 5u) ||
                                p.limits.maxDescriptorSetStorageImages <
                                    (recordingCandidate ? 3u : 5u))) ||
        p.limits.maxComputeWorkGroupInvocations < (tileLocal ? s.coreX * s.coreY
                                                   : tiledReconstruction
                                                       ? 256u
                                                       : 64u) ||
        p.limits.maxComputeWorkGroupSize[0] < (tileLocal             ? s.coreX
                                               : tiledReconstruction ? 16u
                                                                     : 8u) ||
        p.limits.maxComputeWorkGroupSize[1] < (tileLocal             ? s.coreY
                                               : tiledReconstruction ? 16u
                                                                     : 8u))
      continue;
    uint32_t qc = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &qc, nullptr);
    std::vector<VkQueueFamilyProperties> qs(qc);
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &qc, qs.data());
    for (uint32_t q = 0; q < qc; ++q)
      if (qs[q].queueFlags & VK_QUEUE_COMPUTE_BIT) {
        s.physical = physical;
        s.family = q;
        s.timestampBits = qs[q].timestampValidBits;
        s.properties = p;
        break;
      }
    if (s.physical)
      break;
  }
  if (!s.physical)
    throw std::runtime_error("unsupported RCD FP32 buffer/compute limits");
  s.name = s.properties.deviceName;
  vkGetPhysicalDeviceMemoryProperties(s.physical, &s.memoryProperties);
  ok(vkEnumerateDeviceExtensionProperties(s.physical, nullptr, &count, nullptr),
     "device extensions");
  extensions.resize(count);
  ok(vkEnumerateDeviceExtensionProperties(s.physical, nullptr, &count,
                                          extensions.data()),
     "device extensions");
  enabled.clear();
  for (auto &e : extensions)
    if (std::strcmp(e.extensionName, "VK_KHR_portability_subset") == 0)
      enabled.push_back("VK_KHR_portability_subset");
  if (imageOutput || frameGlobal) {
    std::vector<const char *> required = {
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "VK_EXT_queue_family_foreign"};
    if (frameGlobal)
      required.push_back("VK_KHR_external_semaphore_fd");
    for (const char *required : required) {
      bool found = false;
      for (const auto &e : extensions)
        if (std::strcmp(e.extensionName, required) == 0)
          found = true;
      if (!found)
        throw std::runtime_error(
            std::string("Unsupported GPU image output extension ") + required);
      enabled.push_back(required);
    }
  }
  float priority = 1;
  VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  qi.queueFamilyIndex = s.family;
  qi.queueCount = 1;
  qi.pQueuePriorities = &priority;
  VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  di.queueCreateInfoCount = 1;
  di.pQueueCreateInfos = &qi;
  di.enabledExtensionCount = enabled.size();
  di.ppEnabledExtensionNames = enabled.data();
  ok(vkCreateDevice(s.physical, &di, nullptr, &s.device), "create device");
  vkGetDeviceQueue(s.device, s.family, 0, &s.queue);
#ifdef __ANDROID__
  if (frameGlobal) {
    s.getSemaphoreFd = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
        vkGetDeviceProcAddr(s.device, "vkGetSemaphoreFdKHR"));
    if (!s.getSemaphoreFd)
      throw std::runtime_error("Missing semaphore fd export");
    VkExportSemaphoreCreateInfo exportInfo{
        VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    semaphoreInfo.pNext = &exportInfo;
    ok(vkCreateSemaphore(s.device, &semaphoreInfo, nullptr, &s.completion),
       "create exportable completion semaphore");
  }
  if (imageOutput || frameGlobal) {
    s.getAhbProperties =
        reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(s.device,
                                "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (!s.getAhbProperties)
      throw std::runtime_error("Missing Android buffer properties function");
  }
#endif
  std::vector<VkDescriptorSetLayoutBinding> bindings(s.bindingCount());
  for (uint32_t i = 0; i < s.bindingCount(); ++i)
    bindings[i] = {i,
                   (imageOutput && i == 8) ||
                           (imageIntermediates && i >= 1 && i <= 5 &&
                            (!recordingCandidate || i == 1 || i >= 4))
                       ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                       : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                   1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
  VkDescriptorSetLayoutCreateInfo li{
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  li.bindingCount = bindings.size();
  li.pBindings = bindings.data();
  ok(vkCreateDescriptorSetLayout(s.device, &li, nullptr, &s.descriptors),
     "create descriptor layout");
  VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, 16};
  VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
  pl.setLayoutCount = 1;
  pl.pSetLayouts = &s.descriptors;
  pl.pushConstantRangeCount = 1;
  pl.pPushConstantRanges = &range;
  ok(vkCreatePipelineLayout(s.device, &pl, nullptr, &s.layout),
     "create pipeline layout");
  VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
  mi.codeSize = shader.size() * 4;
  mi.pCode = shader.data();
  VkShaderModule module = VK_NULL_HANDLE;
  ok(vkCreateShaderModule(s.device, &mi, nullptr, &module),
     "create shader module");
  VkComputePipelineCreateInfo pi{
      VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
  pi.layout = s.layout;
  pi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
              nullptr,
              0,
              VK_SHADER_STAGE_COMPUTE_BIT,
              module,
              "main",
              nullptr};
  VkResult result = VK_SUCCESS;
  for (int pass = 0;
       pass < (specializePasses ? ((cacheResponses || s.rawInput) ? 8 : 7) : 1);
       ++pass) {
    if (pass == 5 && !s.frameGlobal && !(s.rawInput && (cacheResponses || tileLocal)))
      continue;
    VkSpecializationMapEntry entry{9, 0, sizeof(pass)};
    VkSpecializationInfo specialization{1, &entry, sizeof(pass), &pass};
    pi.stage.pSpecializationInfo = specializePasses ? &specialization : nullptr;
    VkShaderModule baked = VK_NULL_HANDLE;
    if (!stageShaders.empty() || (s.rawInput && pass == 7)) {
      const auto &program =
          (s.rawInput && pass == 7) ? rawPackShader : stageShaders[pass];
      VkShaderModuleCreateInfo info{
          VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
      info.codeSize = program.size() * 4;
      info.pCode = program.data();
      result = vkCreateShaderModule(s.device, &info, nullptr, &baked);
      if (result != VK_SUCCESS)
        break;
      pi.stage.module = baked;
      pi.stage.pSpecializationInfo = nullptr;
    }
    result = vkCreateComputePipelines(s.device, VK_NULL_HANDLE, 1, &pi, nullptr,
                                      &s.pipelines[pass]);
    if (baked)
      vkDestroyShaderModule(s.device, baked, nullptr);
    if (result != VK_SUCCESS)
      break;
  }
  vkDestroyShaderModule(s.device, module, nullptr);
  ok(result, "create RCD pipeline");
  VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  pc.queueFamilyIndex = s.family;
  ok(vkCreateCommandPool(s.device, &pc, nullptr, &s.commandPool),
     "create command pool");
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
  ok(vkCreateFence(s.device, &fi, nullptr, &s.fence),
     "create completion fence");
  if (s.timestampBits) {
    VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qi.queryCount = 16;
    ok(vkCreateQueryPool(s.device, &qi, nullptr, &s.queryPool),
       "create RCD timestamp pool");
  }
}
Engine::~Engine() = default;
const std::string &Engine::deviceName() const { return state->name; }
std::vector<double> Engine::gpuStageCosts() const {
  std::lock_guard<std::mutex> guard(state->mutex);
  return state->lastGpuCosts;
}
void Engine::render(int width, int height, uint32_t phase, const float *cfa,
                    size_t samples, float *rgba, size_t outputSamples) {
  std::lock_guard<std::mutex> guard(state->mutex);
  if (width < 19 || height < 19 || width > 16384 || height > 16384 ||
      size_t(width) * height > 16000000 || samples != size_t(width) * height ||
      outputSamples != samples * 4 || !cfa || !rgba)
    throw std::invalid_argument("invalid canonical RCD geometry/buffers");
  std::array<int, 3> colors{};
  for (int q = 0; q < 4; ++q) {
    uint32_t c = (phase >> (q * 2)) & 3;
    if (c > 2)
      throw std::invalid_argument("invalid CFA phase");
    colors[c]++;
  }
  if (phase != 148 && phase != 97 && phase != 73 && phase != 22)
    throw std::invalid_argument("invalid Bayer topology");
  for (size_t i = 0; i < samples; ++i)
    if (!std::isfinite(cfa[i]) || cfa[i] < 0 || cfa[i] > 1)
      throw std::invalid_argument("invalid canonical CFA value");
  auto &s = *state;
  if (s.rawInput)
    throw std::invalid_argument("RAW input engine requires integer input");
  s.await();
  s.frame(width, height, phase);
  std::memcpy(s.buffers[0].mapped, cfa, samples * 4);
  s.flush(0);
  ok(vkResetFences(s.device, 1, &s.fence), "reset frame fence");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  submit.commandBufferCount = 1;
  submit.pCommandBuffers = &s.command;
  ok(vkQueueSubmit(s.queue, 1, &submit, s.fence), "submit RCD frame");
  s.submitted = true;
  s.await();
  s.collectCosts();
  s.invalidate(6);
  const float *result = static_cast<const float *>(s.buffers[6].mapped);
  for (size_t i = 0; i < outputSamples; ++i)
    if (!std::isfinite(result[i]))
      throw std::runtime_error("nonfinite RCD output");
  std::memcpy(rgba, result, outputSamples * 4);
}

void Engine::renderHalf(int width, int height, uint32_t phase, const float *cfa,
                        size_t samples, const std::vector<uint32_t> &correction,
                        std::vector<uint16_t> &half) {
  std::lock_guard<std::mutex> guard(state->mutex);
  if (width < 19 || height < 19 || width > 16384 || height > 16384 ||
      size_t(width) * height > 16000000 || samples != size_t(width) * height ||
      !cfa || (phase != 148 && phase != 97 && phase != 73 && phase != 22) ||
      correction.size() < 16 || correction.size() > (16 + 65536 * 4))
    throw std::invalid_argument("invalid product RCD frame");
  for (size_t i = 0; i < samples; ++i)
    if (!std::isfinite(cfa[i]) || cfa[i] < 0 || cfa[i] > 1)
      throw std::invalid_argument("invalid CFA value");
  auto &s = *state;
  if (s.rawInput)
    throw std::invalid_argument("RAW input engine requires integer input");
  s.await();
  s.frame(width, height, phase, true);
  std::memcpy(s.buffers[0].mapped, cfa, samples * 4);
  std::memcpy(s.buffers[7].mapped, correction.data(), correction.size() * 4);
  static_cast<uint32_t *>(s.buffers[7].mapped)[12] = 0;
  s.flush(0);
  s.flush(7);
  ok(vkResetFences(s.device, 1, &s.fence), "reset product fence");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  submit.commandBufferCount = 1;
  submit.pCommandBuffers = &s.command;
  ok(vkQueueSubmit(s.queue, 1, &submit, s.fence), "submit RCD product frame");
  s.submitted = true;
  s.await();
  s.collectCosts();
  s.invalidate(7);
  s.invalidate(8);
  if (static_cast<uint32_t *>(s.buffers[7].mapped)[12])
    throw std::runtime_error("GPU product output outside finite FP16 domain");
  half.resize(samples * 4);
  std::memcpy(half.data(), s.buffers[8].mapped, samples * 8);
}

bool Engine::usesRawInput() const { return state->rawInput; }
void Engine::setOutputSampling(int width, int height) {
  std::lock_guard<std::mutex> lock(state->mutex);
  if (!state->frameGlobal || width < 0 || height < 0)
    throw std::invalid_argument("output sampling requires frame-global RCD");
  state->sampleWidth = width;
  state->sampleHeight = height;
}
bool Engine::usesImageOutput() const { return state->imageOutput; }
uint64_t Engine::outputUsage() const {
  if (!state->imageOutput)
    throw std::invalid_argument("No GPU image output configured");
  return state->outputUsage();
}
int Engine::reconstructionScale() const { return state->reconstructionScale; }
void Engine::renderRawFrame(const uint8_t *data, size_t bytes,
                            const std::array<int, 9> &g,
                            const std::array<float, 4> &black, float white,
                            const std::vector<uint32_t> &correction,
                            std::vector<uint16_t> *half,
                            std::vector<float> *rgba, void *hardwareBuffer) {
  std::lock_guard<std::mutex> lock(state->mutex);
  validateRaw16(data, bytes, g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7],
                black, white);
  // Frame-global shaders read four crop pixels as one aligned 8-byte word.
  if (state->frameGlobal && (g[3] != 2 || g[4] % 4 || g[2] % 8))
    throw std::invalid_argument(
        "frame-global RCD requires 2-byte pixels, left%4==0, stride%8==0");
  if (!state->rawInput || correction.size() < 16 ||
      correction.size() > 16 + 65536 * 4 ||
      (g[8] != 0x94 && g[8] != 0x16 && g[8] != 0x61 && g[8] != 0x49))
    throw std::invalid_argument("invalid RAW recording arguments");
  const size_t used = size_t(g[1] - 1) * g[2] + size_t(g[0] - 1) * g[3] + 2;
  if (used > 0x7ffffffc)
    throw std::invalid_argument("RAW storage exceeds address contract");
  const int scale = state->reconstructionScale;
  const int w = scale == 1 ? g[6] : 2 * ((g[6] + 2 * scale - 1) / (2 * scale));
  const int h = scale == 1 ? g[7] : 2 * ((g[7] + 2 * scale - 1) / (2 * scale));
  if (w < 19 || h < 19)
    throw std::invalid_argument("reduced reconstruction too small");
  const size_t padded = (used + 3) & ~size_t(3), samples = size_t(w) * h;
  auto &s = *state;
  s.await();
  if (s.imageOutput) {
    if (hardwareBuffer)
      s.selectOutput(hardwareBuffer, w, h);
    else if (!s.selectedOutput)
      throw std::invalid_argument("GPU output requires a HardwareBuffer");
  } else if (hardwareBuffer)
    throw std::invalid_argument("Engine has no GPU image output");
  s.p010Frame = false;
  s.frame(w, h, uint32_t(g[8]), half != nullptr || hardwareBuffer != nullptr,
          padded);
  if (s.frameGlobal) {
    // Frame-global loads are bounds-guarded to the crop rows, so only those
    // rows are staged (same offsets; ~30% of a 4:3 sensor is outside a 16:9
    // crop).
    const size_t begin = size_t(g[5]) * g[2];
    const size_t end = std::min(used, size_t(g[5] + g[7]) * g[2]);
    std::memcpy(static_cast<uint8_t *>(s.buffers[9].mapped) + begin,
                data + begin, end - begin);
  } else {
    std::memcpy(s.buffers[9].mapped, data, used);
    std::memset(static_cast<uint8_t *>(s.buffers[9].mapped) + used, 0,
                padded - used);
  }
  std::memcpy(s.buffers[10].mapped, g.data(), 32);
  std::memcpy(static_cast<uint8_t *>(s.buffers[10].mapped) + 32, black.data(),
              16);
  std::memcpy(static_cast<uint8_t *>(s.buffers[10].mapped) + 48, &white, 4);
  std::memcpy(s.buffers[7].mapped, correction.data(), correction.size() * 4);
  static_cast<uint32_t *>(s.buffers[7].mapped)[12] = 0;
  auto *metadata = static_cast<int32_t *>(s.buffers[10].mapped);
  metadata[6] = w;
  metadata[7] = h;
  metadata[13] = g[6];
  metadata[14] = g[7];
  metadata[15] = scale;
  if (s.frameGlobal) {
    writeSampleMask(metadata, w, h, s.sampleWidth, s.sampleHeight, false);
    metadata[18] = 0;
    metadata[38] = 0;
  }
  s.flush(7);
  s.flush(9);
  s.flush(10);
  ok(vkResetFences(s.device, 1, &s.fence), "reset RAW product fence");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  submit.commandBufferCount = 1;
  submit.pCommandBuffers = &s.command;
  ok(vkQueueSubmit(s.queue, 1, &submit, s.fence), "submit RAW product frame");
  s.submitted = true;
  s.await();
  s.collectCosts();
  if (hardwareBuffer) {
    s.invalidate(7);
    if (static_cast<uint32_t *>(s.buffers[7].mapped)[12])
      throw std::runtime_error("GPU product output outside finite FP16 domain");
  } else if (half) {
    s.invalidate(7);
    s.invalidate(8);
    if (static_cast<uint32_t *>(s.buffers[7].mapped)[12])
      throw std::runtime_error("GPU product output outside finite FP16 domain");
    half->resize(samples * 4);
    std::memcpy(half->data(), s.buffers[8].mapped, samples * 8);
  } else {
    s.invalidate(6);
    rgba->resize(samples * 4);
    std::memcpy(rgba->data(), s.buffers[6].mapped, samples * 16);
  }
}
int Engine::renderRawP010(const uint8_t *data, size_t bytes,
                          const std::array<int, 9> &g,
                          const std::array<float, 4> &black, float white,
                          const std::vector<uint32_t> &correction,
                          const std::array<float, 14> &grade,
                          const std::array<int, 4> &layout, void *p010Buffer) {
#ifndef __ANDROID__
  throw std::runtime_error("P010 output requires Android");
#else
  std::lock_guard<std::mutex> lock(state->mutex);
  auto &s = *state;
  if (!s.frameGlobal || s.imageOutput || !p010Buffer)
    throw std::invalid_argument("fused P010 requires a buffer frame-global engine");
  validateRaw16(data, bytes, g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7],
                black, white);
  if (g[3] != 2 || g[4] % 4 || g[2] % 8 || correction.size() < 16 ||
      correction.size() > 16 + 65536 * 4 ||
      (g[8] != 0x94 && g[8] != 0x16 && g[8] != 0x61 && g[8] != 0x49))
    throw std::invalid_argument("invalid fused RAW arguments");
  const int outW = layout[0], outH = layout[1], yStrideB = layout[2],
            uvStrideB = layout[3];
  if (outW <= 0 || outH <= 0 || outW % 2 || outH % 2 || outW > g[6] ||
      outH > g[7] || yStrideB < outW * 2 || uvStrideB < outW * 2 ||
      yStrideB % 2 || uvStrideB % 4 || grade[12] < 0 || grade[12] > 3)
    throw std::invalid_argument("invalid P010 layout");
  const size_t used = size_t(g[1] - 1) * g[2] + size_t(g[0] - 1) * g[3] + 2;
  const size_t padded = (used + 3) & ~size_t(3);
  const int w = g[6], h = g[7];
  const size_t begin = size_t(g[5]) * g[2];
  const size_t end = std::min(used, size_t(g[5] + g[7]) * g[2]);
  // Same layout as the frame in flight: queue this frame behind it on the
  // other slot (two frames in flight), waiting only for the frame before it,
  // which used this slot and this staging half.
  s.p010Frame = true;
  const bool overlap = s.frameMatches(w, h, uint32_t(g[8]), true, padded) && s.submitted &&
                       !s.imageIntermediates && s.selectedP010;
  const int half = overlap ? 1 - s.stagingHalf : 0;
  if (overlap) {
    s.prepareSpare();
    s.swapSlot();
  }
  s.await();
  if (s.statusPending) {
    s.statusPending = false;
    s.collectCosts();
    s.invalidate(7);
    if (static_cast<uint32_t *>(s.buffers[7].mapped)[12])
      throw std::runtime_error("GPU product output outside finite FP16 domain");
  }
  s.p010W = outW;
  s.p010H = outH;

  s.selectP010(static_cast<AHardwareBuffer *>(p010Buffer),
               VkDeviceSize(yStrideB) * outH + VkDeviceSize(uvStrideB) * (outH / 2));
  s.stagingHalf = half;
  s.frame(w, h, uint32_t(g[8]), true, padded);
  // The GPU may still run the other slot's frame (other staging half).
  std::memcpy(static_cast<uint8_t *>(s.buffers[9].mapped) + half * s.stagingSlot() + begin,
              data + begin, end - begin);
  std::memcpy(s.buffers[10].mapped, g.data(), 32);
  std::memcpy(static_cast<uint8_t *>(s.buffers[10].mapped) + 32, black.data(), 16);
  std::memcpy(static_cast<uint8_t *>(s.buffers[10].mapped) + 48, &white, 4);
  std::memcpy(s.buffers[7].mapped, correction.data(), correction.size() * 4);
  static_cast<uint32_t *>(s.buffers[7].mapped)[12] = 0;
  auto *m = static_cast<int32_t *>(s.buffers[10].mapped);
  m[6] = w;
  m[7] = h;
  m[13] = w;
  m[14] = h;
  m[15] = 1;
  // The final pass reconstructs only sampled pixels (same predicate as the
  // separate grade path); stage 5 reads them at the exact integer positions.
  {
    // Exact 2:1 axes sample one CFA parity. When that site is green, shift
    // the sampling by one source pixel (half an output pixel, the whole
    // frame) onto a red/blue site, where RCD's final step needs no gradients.
    int shiftX = 0, shiftY = 0;
    const auto site = [&](int px, int py) {
      return (uint32_t(g[8]) >> (2 * (py * 2 + px))) & 3u;
    };
    if (2 * outW == w && 2 * outH == h && site(1, 1) == 1u)
      shiftX = -1;
    m[39] = shiftX;
    m[40] = shiftY;
    writeSampleMask(m, w, h, outW, outH, true, shiftX, shiftY);
  }
  m[18] = 0;
  {
    // Word 38: all sampled pixels share one non-green CFA site.
    auto parity = [](int src, int out, int shift) {
      int seen = 0;
      for (int o = 0; o < out; ++o)
        seen |= 1 << ((int((int64_t(2 * o + 1) * src) / (2 * int64_t(out))) + shift) & 1);
      return seen == 1 ? 0 : seen == 2 ? 1 : -1;
    };
    const int px = parity(w, outW, m[39]), py = parity(h, outH, m[40]);
    m[38] = px >= 0 && py >= 0 &&
            ((uint32_t(g[8]) >> (2 * (py * 2 + px))) & 3u) != 1u;
  }
  m[19] = int(grade[12] + 0.5f);
  m[20] = grade[13] > 0.5f ? 1 : 0;
  m[21] = yStrideB / 2;
  m[22] = uvStrideB / 2;
  m[23] = yStrideB * outH;
  std::memcpy(m + 24, grade.data(), 12 * 4);
  m[36] = outW;
  m[37] = outH;
  s.flush(7);
  s.flush(9);
  s.flush(10);
  ok(vkResetFences(s.device, 1, &s.fence), "reset fused fence");
  VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  submit.commandBufferCount = 1;
  submit.pCommandBuffers = &s.command;
  submit.signalSemaphoreCount = 1;
  submit.pSignalSemaphores = &s.completion;
  ok(vkQueueSubmit(s.queue, 1, &submit, s.fence), "submit fused P010 frame");
  s.submitted = true;
  s.statusPending = true;
  VkSemaphoreGetFdInfoKHR fdInfo{VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR};
  fdInfo.semaphore = s.completion;
  fdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
  int fd = -1;
  VkResult r = s.getSemaphoreFd(s.device, &fdInfo, &fd);
  if (r != VK_SUCCESS || fd < 0) {
    // No completion handle to give away: drain so the caller may reuse P010.
    s.await();
    ok(r == VK_SUCCESS ? VK_ERROR_UNKNOWN : r, "export fused completion fd");
  }
  return fd;
#endif
}
void Engine::renderRawHalf(const uint8_t *data, size_t bytes,
                           const std::array<int, 9> &geometry,
                           const std::array<float, 4> &black, float white,
                           const std::vector<uint32_t> &correction,
                           std::vector<uint16_t> &half) {
  if (state->imageOutput)
    throw std::invalid_argument("GPU image engine requires renderRawImage");
  renderRawFrame(data, bytes, geometry, black, white, correction, &half,
                 nullptr);
}
void Engine::renderRaw(const uint8_t *data, size_t bytes,
                       const std::array<int, 9> &geometry,
                       const std::array<float, 4> &black, float white,
                       std::vector<float> &rgba) {
  renderRawFrame(data, bytes, geometry, black, white, std::vector<uint32_t>(16),
                 nullptr, &rgba);
}
void Engine::renderRawImage(const uint8_t *data, size_t bytes,
                            const std::array<int, 9> &geometry,
                            const std::array<float, 4> &black, float white,
                            const std::vector<uint32_t> &correction,
                            void *hardwareBuffer) {
  if (!hardwareBuffer)
    throw std::invalid_argument("Null GPU output buffer");
  renderRawFrame(data, bytes, geometry, black, white, correction, nullptr,
                 nullptr, hardwareBuffer);
}
} // namespace photo_rcd
