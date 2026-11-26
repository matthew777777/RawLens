// SPDX-License-Identifier: Apache-2.0
#include "srp_context.h"

#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <thread>
#include <vector>
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#endif

#include "srp_pipeline.h"

namespace srp {
namespace {

constexpr uint64_t kFenceTimeoutNs = 1000000000ULL;  // 1 s

// FNV-1a64 over SPIR-V bytes: module interning key (collisions across the
// ~10 distinct stage modules are not a practical concern; a miss only
// costs one extra compile).
uint64_t HashBytes(const uint8_t* bytes, size_t len) {
  uint64_t h = 14695981039346656037ULL;
  for (size_t i = 0; i < len; ++i) {
    h ^= bytes[i];
    h *= 1099511628211ULL;
  }
  return h;
}

}  // namespace

struct PipeKey {
  VkShaderModule mod = VK_NULL_HANDLE;
  uint32_t nbuffers = 0;
  uint32_t push_size = 0;
  bool operator<(const PipeKey& o) const {
    if (mod != o.mod) return mod < o.mod;
    if (nbuffers != o.nbuffers) return nbuffers < o.nbuffers;
    return push_size < o.push_size;
  }
};

struct PipeEntry {
  VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
  VkPipelineLayout pipe_layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
};

struct Context {
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physical = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  uint32_t compute_family = 0;
  uint32_t subgroup_size = 0;
  char device_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = {};
  // Dispatch caches (lifetime/placement only: same SPIR-V, same order;
  // values bit-identical): interned stage modules, compiled pipelines per
  // (module, buffer count, push size), and a driver pipeline cache.
  // Guarded for future multi-threaded use; all dispatch today is serial.
  // Persistent per-dispatch objects (SrpDispatchBegin): reset per call,
  // destroyed with the Context. Serial use only.
  VkCommandPool dispatch_cmd_pool = VK_NULL_HANDLE;
  VkDescriptorPool dispatch_desc_pool = VK_NULL_HANDLE;
  VkFence dispatch_fence = VK_NULL_HANDLE;
  struct Batch {
    VkCommandPool pool = VK_NULL_HANDLE;
    VkDescriptorPool desc = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool pending = false;
  } batches[kBatchSlots];
  int batch_open = -1;
  std::mutex mutex;
  VkPipelineCache pipeline_cache = VK_NULL_HANDLE;
  std::map<uint64_t, VkShaderModule> modules;
  std::map<PipeKey, PipeEntry> pipes;
  // Lifetime totals for SRP_PROFILE accounting.
  uint64_t dispatch_count = 0;
  // Device buffer pool keyed by float capacity: dedicated vkAllocateMemory
  // per buffer costs milliseconds on MoltenVK; reuse makes repeat shapes
  // (same frame dims across the burst, same tiles per level) free.
  // Buffers are uninitialized, exactly like fresh allocations (kernels
  // never consume unwritten lanes — outputs are bit-identical across
  // runs today, which would be impossible otherwise).
  std::map<size_t, std::vector<std::pair<VkBuffer, VkDeviceMemory>>> pool;
  // Last-use tick per size class for LRU eviction (bumped on hit + release).
  std::map<size_t, uint64_t> pool_touch;
  uint64_t pool_clock = 0;
  uint64_t pool_hits = 0;
  uint64_t pool_misses = 0;
  uint64_t pool_evicted = 0;
  // Retained (between-uses) bytes now + high-water mark. Live in-use
  // buffers are not counted; only pool-retained ones are.
  uint64_t pool_bytes = 0;
  uint64_t pool_bytes_peak = 0;
  // Resident device buffers held across JNI calls (merge num/den):
  // pool-acquired (eviction only scans the free list, so held buffers
  // are never evicted), zero-filled at creation, freed explicitly.
  // Handles are small positive integers; 0 is never valid.
  struct ResidentBuf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    size_t nfloats = 0;
    bool pooled = true;
  };
  std::map<int64_t, ResidentBuf> resident;
  // Image residents (SrpCreateImage), negative handles; layout GENERAL.
  struct ResidentImg {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
  };
  std::map<int64_t, ResidentImg> images;
  VkSampler sampler = VK_NULL_HANDLE;   // nearest, clamp to edge
  // Stage scratch kept for the context lifetime (SrpScratch), by slot.
  std::map<uint32_t, ResidentBuf> scratch;
  int64_t resident_next = 1;
};

Status Create(Context** out_ctx) {
  if (out_ctx == nullptr) return Status::kInitFailed;
  *out_ctx = nullptr;

  VkApplicationInfo app{};
  app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  app.pApplicationName = "srpipeline";
  app.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
  app.apiVersion = VK_API_VERSION_1_2;

  // Instance creation attempts: with portability enumeration first (required
  // by MoltenVK-bearing loaders, which fail plain creation with
  // VK_ERROR_INCOMPATIBLE_DRIVER), then plain. Some loaders no longer list
  // VK_KHR_portability_enumeration in their extension enumeration even
  // though they honor the flag, so "listed" cannot gate the attempt: try it
  // and fall back on VK_ERROR_EXTENSION_NOT_PRESENT. The NDK loader lacks
  // the macros entirely, so this compiles down to the single plain attempt
  // there (zero behavior change on Android).
  auto try_create = [&](bool with_portability, VkInstance* out) {
    std::vector<const char*> extensions;
    VkInstanceCreateFlags instance_flags = 0;
    if (with_portability) {
#ifdef VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
      extensions.push_back(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
      // NOTE: intentionally NOT #ifdef-guarded. This enumerator is an enum
      // constant, never a macro, so #ifdef on it is always false (that
      // mistake silently dropped the flag and MoltenVK loaders answered
      // VK_ERROR_INCOMPATIBLE_DRIVER). Present in all current headers
      // including the NDK's; ancient-header risk is covered by the
      // EXTENSION_NOT_PRESENT fallback below.
      instance_flags |= VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
#else
      return VK_ERROR_EXTENSION_NOT_PRESENT;
#endif
    }
    VkInstanceCreateInfo instance_info{};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.flags = instance_flags;
    instance_info.pApplicationInfo = &app;
    instance_info.enabledExtensionCount =
        static_cast<uint32_t>(extensions.size());
    instance_info.ppEnabledExtensionNames = extensions.data();
    return vkCreateInstance(&instance_info, nullptr, out);
  };

  VkInstance instance = VK_NULL_HANDLE;
  VkResult created = try_create(true, &instance);
  if (created == VK_ERROR_EXTENSION_NOT_PRESENT) {
    created = try_create(false, &instance);
  }
  if (created != VK_SUCCESS) {
    return Status::kNoVulkan;
  }

  uint32_t phys_count = 0;
  if (vkEnumeratePhysicalDevices(instance, &phys_count, nullptr) != VK_SUCCESS || phys_count == 0) {
    vkDestroyInstance(instance, nullptr);
    return Status::kNoDevice;
  }
  std::vector<VkPhysicalDevice> devices(phys_count);
  if (vkEnumeratePhysicalDevices(instance, &phys_count, devices.data()) != VK_SUCCESS) {
    vkDestroyInstance(instance, nullptr);
    return Status::kNoDevice;
  }

  VkPhysicalDevice chosen = VK_NULL_HANDLE;
  uint32_t compute_family = 0;
  for (VkPhysicalDevice dev : devices) {
    uint32_t family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(dev, &family_count, nullptr);
    std::vector<VkQueueFamilyProperties> families(family_count);
    vkGetPhysicalDeviceQueueFamilyProperties(dev, &family_count, families.data());
    for (uint32_t i = 0; i < family_count; ++i) {
      if ((families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0 && families[i].queueCount > 0) {
        chosen = dev;
        compute_family = i;
        break;
      }
    }
    if (chosen != VK_NULL_HANDLE) break;
  }
  if (chosen == VK_NULL_HANDLE) {
    vkDestroyInstance(instance, nullptr);
    return Status::kNoDevice;
  }

  float priority = 1.0f;
  VkDeviceQueueCreateInfo queue_info{};
  queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
  queue_info.queueFamilyIndex = compute_family;
  queue_info.queueCount = 1;
  queue_info.pQueuePriorities = &priority;

  VkDeviceCreateInfo device_info{};
  device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
  device_info.queueCreateInfoCount = 1;
  device_info.pQueueCreateInfos = &queue_info;

  VkDevice device = VK_NULL_HANDLE;
  if (vkCreateDevice(chosen, &device_info, nullptr, &device) != VK_SUCCESS) {
    vkDestroyInstance(instance, nullptr);
    return Status::kInitFailed;
  }

  VkPhysicalDeviceSubgroupProperties subgroup{};
  subgroup.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES;
  VkPhysicalDeviceProperties2 props2{};
  props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
  props2.pNext = &subgroup;
  vkGetPhysicalDeviceProperties2(chosen, &props2);

#ifdef __ANDROID__
  {
    VkPhysicalDeviceMemoryProperties mp{};
    vkGetPhysicalDeviceMemoryProperties(chosen, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
      __android_log_print(ANDROID_LOG_DEBUG, "srp-native", "memory type %u flags 0x%x heap %u", i,
                          mp.memoryTypes[i].propertyFlags, mp.memoryTypes[i].heapIndex);
  }
#endif
  auto* ctx = new Context();
  ctx->instance = instance;
  ctx->physical = chosen;
  ctx->device = device;
  ctx->compute_family = compute_family;
  ctx->subgroup_size = static_cast<uint32_t>(subgroup.subgroupSize);
  for (size_t i = 0; i < sizeof(ctx->device_name); ++i) {
    ctx->device_name[i] = props2.properties.deviceName[i];
    if (props2.properties.deviceName[i] == '\0') break;
  }
  ctx->device_name[sizeof(ctx->device_name) - 1] = '\0';

  VkPipelineCacheCreateInfo cache_info{};
  cache_info.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
  if (vkCreatePipelineCache(ctx->device, &cache_info, nullptr,
                            &ctx->pipeline_cache) != VK_SUCCESS) {
    ctx->pipeline_cache = VK_NULL_HANDLE;  // advisory only; continue
  }

  *out_ctx = ctx;
  return Status::kOk;
}

void SrpCountDispatch(Context* ctx) {
  if (ctx == nullptr) return;
  std::lock_guard<std::mutex> lock(ctx->mutex);
  ++ctx->dispatch_count;
}

Status SrpDispatchBegin(Context* ctx, VkDescriptorSetLayout set_layout,
                        VkCommandBuffer* cmd, VkDescriptorSet* set,
                        VkFence* fence) {
  if (ctx == nullptr || set_layout == VK_NULL_HANDLE || cmd == nullptr ||
      set == nullptr || fence == nullptr ||
      ctx->device == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  *cmd = VK_NULL_HANDLE;
  *set = VK_NULL_HANDLE;
  *fence = VK_NULL_HANDLE;
  VkDevice device = ctx->device;
  if (ctx->dispatch_cmd_pool == VK_NULL_HANDLE) {
    VkCommandPoolCreateInfo cmd_pool_info{};
    cmd_pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cmd_pool_info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    cmd_pool_info.queueFamilyIndex = ctx->compute_family;
    if (vkCreateCommandPool(device, &cmd_pool_info, nullptr,
                            &ctx->dispatch_cmd_pool) != VK_SUCCESS) {
      return Status::kDispatchFailed;
    }
  }
  if (ctx->dispatch_desc_pool == VK_NULL_HANDLE) {
    // One live set at a time (serial discipline); sized for the widest
    // entry (kMaxStorageBuffers storage buffers).
    const VkDescriptorPoolSize pool_sizes[3] = {
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kMaxStorageBuffers},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kMaxStorageBuffers},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kMaxStorageBuffers}};
    VkDescriptorPoolCreateInfo pool_info{};
    pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pool_info.maxSets = 1;
    pool_info.poolSizeCount = 3;
    pool_info.pPoolSizes = pool_sizes;
    if (vkCreateDescriptorPool(device, &pool_info, nullptr,
                               &ctx->dispatch_desc_pool) != VK_SUCCESS) {
      return Status::kDispatchFailed;
    }
  }
  if (ctx->dispatch_fence == VK_NULL_HANDLE) {
    VkFenceCreateInfo fence_info{};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fence_info, nullptr, &ctx->dispatch_fence) !=
        VK_SUCCESS) {
      return Status::kDispatchFailed;
    }
  }
  if (vkResetCommandPool(device, ctx->dispatch_cmd_pool, 0) != VK_SUCCESS ||
      vkResetDescriptorPool(device, ctx->dispatch_desc_pool, 0) !=
          VK_SUCCESS ||
      vkResetFences(device, 1, &ctx->dispatch_fence) != VK_SUCCESS) {
    return Status::kDispatchFailed;
  }
  VkCommandBufferAllocateInfo cmd_alloc{};
  cmd_alloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
  cmd_alloc.commandPool = ctx->dispatch_cmd_pool;
  cmd_alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  cmd_alloc.commandBufferCount = 1;
  VkDescriptorSetAllocateInfo alloc_info{};
  alloc_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
  alloc_info.descriptorPool = ctx->dispatch_desc_pool;
  alloc_info.descriptorSetCount = 1;
  alloc_info.pSetLayouts = &set_layout;
  if (vkAllocateCommandBuffers(device, &cmd_alloc, cmd) != VK_SUCCESS ||
      vkAllocateDescriptorSets(device, &alloc_info, set) != VK_SUCCESS) {
    return Status::kDispatchFailed;
  }
  *fence = ctx->dispatch_fence;
  return Status::kOk;
}

namespace {
bool CreatePooled(Context* ctx, size_t nfloats, VkBuffer* out_buf, VkDeviceMemory* out_mem);
}

bool SrpFindHostMemory(VkPhysicalDevice physical, uint32_t type_bits, uint32_t* out_index) {
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(physical, &props);
  const VkMemoryPropertyFlags base =
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
  // Cached first: host reads of write-combined memory run at a fraction of
  // the copy speed (every download and readback).
  static const bool prefer_cached = [] {
#ifdef __ANDROID__
    char v[8] = {};
    if (__system_property_get("debug.srp.cached", v) > 0) return v[0] != '0';
#endif
    return true;
  }();
  for (int pass = prefer_cached ? 0 : 1; pass < 2; ++pass) {
    const VkMemoryPropertyFlags want =
        pass == 0 ? (base | VK_MEMORY_PROPERTY_HOST_CACHED_BIT) : base;
    for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
      if ((type_bits & (1u << i)) != 0 &&
          (props.memoryTypes[i].propertyFlags & want) == want) {
        *out_index = i;
        return true;
      }
    }
  }
  return false;
}

Status SrpBatchWait(Context* ctx, uint32_t slot) {
  if (ctx == nullptr || slot >= kBatchSlots) return Status::kInitFailed;
  Context::Batch& b = ctx->batches[slot];
  if (!b.pending) return Status::kOk;
  VkResult wait = VK_TIMEOUT;
  for (int i = 0; i < 300 && wait == VK_TIMEOUT; ++i) {
    wait = vkWaitForFences(ctx->device, 1, &b.fence, VK_TRUE, kFenceTimeoutNs);
  }
  if (wait != VK_SUCCESS) return Status::kDispatchFailed;
  b.pending = false;
  return Status::kOk;
}

Status SrpBatchBegin(Context* ctx, uint32_t slot) {
  if (ctx == nullptr || slot >= kBatchSlots || ctx->batch_open >= 0) return Status::kInitFailed;
  const Status waited = SrpBatchWait(ctx, slot);
  if (waited != Status::kOk) return waited;
  Context::Batch& b = ctx->batches[slot];
  VkDevice device = ctx->device;
  constexpr uint32_t kSets = 64;
  if (b.pool == VK_NULL_HANDLE) {
    VkCommandPoolCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    ci.queueFamilyIndex = ctx->compute_family;
    const VkDescriptorPoolSize sizes[3] = {
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kSets * kMaxStorageBuffers},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kSets * kMaxStorageBuffers},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, kSets * kMaxStorageBuffers}};
    VkDescriptorPoolCreateInfo di{};
    di.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    di.maxSets = kSets;
    di.poolSizeCount = 3;
    di.pPoolSizes = sizes;
    VkFenceCreateInfo fi{};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateCommandPool(device, &ci, nullptr, &b.pool) != VK_SUCCESS ||
        vkCreateDescriptorPool(device, &di, nullptr, &b.desc) != VK_SUCCESS ||
        vkCreateFence(device, &fi, nullptr, &b.fence) != VK_SUCCESS) {
      return Status::kDispatchFailed;
    }
    VkCommandBufferAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ai.commandPool = b.pool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &ai, &b.cmd) != VK_SUCCESS) return Status::kDispatchFailed;
  }
  VkCommandBufferBeginInfo begin{};
  begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
  begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  if (vkResetCommandPool(device, b.pool, 0) != VK_SUCCESS ||
      vkResetDescriptorPool(device, b.desc, 0) != VK_SUCCESS ||
      vkResetFences(device, 1, &b.fence) != VK_SUCCESS ||
      vkBeginCommandBuffer(b.cmd, &begin) != VK_SUCCESS) {
    return Status::kDispatchFailed;
  }
  ctx->batch_open = static_cast<int>(slot);
  return Status::kOk;
}

Status SrpBatchSubmit(Context* ctx) {
  if (ctx == nullptr || ctx->batch_open < 0) return Status::kInitFailed;
  Context::Batch& b = ctx->batches[ctx->batch_open];
  ctx->batch_open = -1;
  VkMemoryBarrier host{};
  host.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
  host.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
  host.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  vkCmdPipelineBarrier(b.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                       1, &host, 0, nullptr, 0, nullptr);
  if (vkEndCommandBuffer(b.cmd) != VK_SUCCESS) return Status::kDispatchFailed;
  VkQueue queue = VK_NULL_HANDLE;
  vkGetDeviceQueue(ctx->device, ctx->compute_family, 0, &queue);
  VkSubmitInfo submit{};
  submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
  submit.commandBufferCount = 1;
  submit.pCommandBuffers = &b.cmd;
  if (vkQueueSubmit(queue, 1, &submit, b.fence) != VK_SUCCESS) return Status::kDispatchFailed;
  b.pending = true;
  return Status::kOk;
}

VkCommandBuffer SrpBatchCommand(Context* ctx) {
  return ctx == nullptr || ctx->batch_open < 0 ? VK_NULL_HANDLE : ctx->batches[ctx->batch_open].cmd;
}

Status SrpBatchSet(Context* ctx, VkDescriptorSetLayout layout, VkDescriptorSet* set) {
  if (ctx == nullptr || ctx->batch_open < 0) return Status::kInitFailed;
  VkDescriptorSetAllocateInfo ai{};
  ai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
  ai.descriptorPool = ctx->batches[ctx->batch_open].desc;
  ai.descriptorSetCount = 1;
  ai.pSetLayouts = &layout;
  return vkAllocateDescriptorSets(ctx->device, &ai, set) == VK_SUCCESS ? Status::kOk
                                                                      : Status::kDispatchFailed;
}

VkBuffer SrpScratch(Context* ctx, uint32_t slot, size_t nfloats) {
  if (ctx == nullptr || nfloats == 0) return VK_NULL_HANDLE;
  auto it = ctx->scratch.find(slot);
  if (it != ctx->scratch.end()) {
    if (it->second.nfloats == nfloats) return it->second.buf;
    vkDestroyBuffer(ctx->device, it->second.buf, nullptr);
    vkFreeMemory(ctx->device, it->second.mem, nullptr);
    ctx->scratch.erase(it);
  }
  Context::ResidentBuf b;
  if (!CreatePooled(ctx, nfloats, &b.buf, &b.mem)) return VK_NULL_HANDLE;
  b.nfloats = nfloats;
  ctx->scratch[slot] = b;
  return b.buf;
}

namespace {

// Shared buffer+memory creation (HOST_VISIBLE|COHERENT staging pattern,
// same requirements the per-call allocator used).
bool CreatePooled(Context* ctx, size_t nfloats, VkBuffer* out_buf,
                  VkDeviceMemory* out_mem) {
  VkBufferCreateInfo info{};
  info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
  info.size = static_cast<VkDeviceSize>(nfloats) * 4;
  info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  VkBuffer buf = VK_NULL_HANDLE;
  if (vkCreateBuffer(ctx->device, &info, nullptr, &buf) != VK_SUCCESS) {
    return false;
  }
  VkMemoryRequirements req{};
  vkGetBufferMemoryRequirements(ctx->device, buf, &req);
  VkPhysicalDevice physical = NativePhysicalDevice(ctx);
  uint32_t index = 0;
  if (!SrpFindHostMemory(physical, req.memoryTypeBits, &index)) {
    vkDestroyBuffer(ctx->device, buf, nullptr);
    return false;
  }
  VkMemoryAllocateInfo ai{};
  ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  ai.allocationSize = req.size;
  ai.memoryTypeIndex = index;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  if (vkAllocateMemory(ctx->device, &ai, nullptr, &mem) != VK_SUCCESS ||
      vkBindBufferMemory(ctx->device, buf, mem, 0) != VK_SUCCESS) {
    if (mem != VK_NULL_HANDLE) vkFreeMemory(ctx->device, mem, nullptr);
    vkDestroyBuffer(ctx->device, buf, nullptr);
    return false;
  }
  *out_buf = buf;
  *out_mem = mem;
  return true;
}

// Retained-pool cap in bytes: default 512MB, SRP_POOL_CAP_MB overrides
// (0 disables the cap). Bounds between-uses retention so full-res FFT
// sizes (hundreds of MB per buffer, dozens retained per size) cannot
// OOM the host; live in-use buffers are unaffected. Eviction destroys
// (values are bit-identical either way: pooled buffers are fully
// overwritten, like fresh allocations).
uint64_t PoolCapBytes() {
  uint64_t cap_mb = 512;
  if (const char* env = std::getenv("SRP_POOL_CAP_MB")) {
    char* end = nullptr;
    const unsigned long v = std::strtoul(env, &end, 10);
    if (end != env) cap_mb = v;
  }
#ifdef __ANDROID__
  // Android has no process env passthrough for instrumented runs, so the
  // same knob is also readable as a debug system property
  // (`adb shell setprop debug.srp.pool_cap_mb 64`). Bounds
  // between-uses device retention on RAM-shared Mali parts, where the
  // 512MB desktop default plus ~400MB pinned residents trips LMK.
  // Values-identical either way (pooled buffers are fully overwritten).
  else {
    char prop[32] = {};
    if (__system_property_get("debug.srp.pool_cap_mb", prop) > 0) {
      char* end = nullptr;
      const unsigned long v = std::strtoul(prop, &end, 10);
      if (end != prop) cap_mb = v;
    } else {
      // No prop (e.g. after a reboot clears it): RAM-shared Mali parts
      // cannot take the 512MB desktop default next to the ~400MB pinned
      // residents plus the live set (observed device OOM). 64MB is the
      // proven full-res operating point (desktop keeps 512MB).
      cap_mb = 64;
    }
  }
#endif
  if (cap_mb == 0) return UINT64_MAX;
  return cap_mb * 1024ull * 1024ull;
}

// Destroys stalest size classes until `need` more bytes fit under the
// cap. Call with ctx->mutex held.
void EvictForLocked(Context* ctx, size_t need) {
  const uint64_t cap = PoolCapBytes();
  while (!ctx->pool.empty() &&
         ctx->pool_bytes + static_cast<uint64_t>(need) * 4 > cap) {
    size_t victim = 0;
    uint64_t oldest = UINT64_MAX;
    bool found = false;
    for (const auto& kv : ctx->pool) {
      if (kv.second.empty()) continue;
      uint64_t t = 0;
      const auto it = ctx->pool_touch.find(kv.first);
      if (it != ctx->pool_touch.end()) t = it->second;
      if (!found || t < oldest) {
        oldest = t;
        victim = kv.first;
        found = true;
      }
    }
    if (!found) break;
    auto& vec = ctx->pool[victim];
    const auto slot = vec.back();
    vec.pop_back();
    if (vec.empty()) {
      ctx->pool.erase(victim);
      ctx->pool_touch.erase(victim);
    }
    ctx->pool_bytes -= static_cast<uint64_t>(victim) * 4;
    ++ctx->pool_evicted;
    vkDestroyBuffer(ctx->device, slot.first, nullptr);
    vkFreeMemory(ctx->device, slot.second, nullptr);
  }
}

}  // namespace

bool SrpAcquireBuffer(Context* ctx, size_t nfloats, VkBuffer* out_buf,
                      VkDeviceMemory* out_mem) {
  if (out_buf == nullptr || out_mem == nullptr) return false;
  *out_buf = VK_NULL_HANDLE;
  *out_mem = VK_NULL_HANDLE;
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE || nfloats == 0) {
    return false;
  }
  std::lock_guard<std::mutex> lock(ctx->mutex);
  auto it = ctx->pool.find(nfloats);
  if (it != ctx->pool.end() && !it->second.empty()) {
    const auto slot = it->second.back();
    it->second.pop_back();
    if (it->second.empty()) ctx->pool.erase(it);
    ++ctx->pool_hits;
    *out_buf = slot.first;
    *out_mem = slot.second;
    ctx->pool_bytes -= static_cast<uint64_t>(nfloats) * 4;
    ctx->pool_touch[nfloats] = ++ctx->pool_clock;
    return true;
  }
  ++ctx->pool_misses;
  VkBuffer buf = VK_NULL_HANDLE;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  // Cold path only; steady state reuses pooled buffers without allocating.
  if (!CreatePooled(ctx, nfloats, &buf, &mem)) return false;
  *out_buf = buf;
  *out_mem = mem;
  return true;
}

void SrpReleaseBuffer(Context* ctx, VkBuffer buf, VkDeviceMemory mem,
                      size_t nfloats) {
  if (ctx == nullptr || buf == VK_NULL_HANDLE || mem == VK_NULL_HANDLE ||
      nfloats == 0) {
    return;
  }
  std::lock_guard<std::mutex> lock(ctx->mutex);
  ctx->pool_touch[nfloats] = ++ctx->pool_clock;
  EvictForLocked(ctx, nfloats);
  ctx->pool[nfloats].push_back({buf, mem});
  ctx->pool_bytes += static_cast<uint64_t>(nfloats) * 4;
  if (ctx->pool_bytes > ctx->pool_bytes_peak) {
    ctx->pool_bytes_peak = ctx->pool_bytes;
  }
}

int64_t SrpCreateResident(Context* ctx, size_t nfloats) {
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE || nfloats == 0) {
    return 0;
  }
  VkBuffer buf = VK_NULL_HANDLE;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  if (!SrpAcquireBuffer(ctx, nfloats, &buf, &mem)) return 0;
  // Exact zeros (replaces the host-zeroed accumulator ByteBuffers).
  // Host-coherent, like the staging buffers: no flush needed.
  void* mapped = nullptr;
  if (vkMapMemory(ctx->device, mem, 0, static_cast<VkDeviceSize>(nfloats) * 4,
                  0, &mapped) != VK_SUCCESS) {
    SrpReleaseBuffer(ctx, buf, mem, nfloats);
    return 0;
  }
  std::memset(mapped, 0, nfloats * 4);
  vkUnmapMemory(ctx->device, mem);
  std::lock_guard<std::mutex> lock(ctx->mutex);
  const int64_t handle = ctx->resident_next++;
  ctx->resident[handle] = Context::ResidentBuf{buf, mem, nfloats, true};
  return handle;
}

// Device-local float image (1, 2 or 4 channels, 32-bit each), GENERAL layout,
// sampled (nearest) and storage. Returns a negative handle, 0 on failure.
int64_t SrpCreateImage(Context* ctx, uint32_t w, uint32_t h, uint32_t channels) {
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE || w == 0 || h == 0) return 0;
  VkDevice device = ctx->device;
  Context::ResidentImg r;
  VkImageCreateInfo ii{};
  ii.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
  ii.imageType = VK_IMAGE_TYPE_2D;
  ii.format = channels == 1 ? VK_FORMAT_R32_SFLOAT
      : channels == 2 ? VK_FORMAT_R32G32_SFLOAT : VK_FORMAT_R32G32B32A32_SFLOAT;
  ii.extent = {w, h, 1};
  ii.mipLevels = ii.arrayLayers = 1;
  ii.samples = VK_SAMPLE_COUNT_1_BIT;
  ii.tiling = VK_IMAGE_TILING_OPTIMAL;
  ii.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
  if (vkCreateImage(device, &ii, nullptr, &r.image) != VK_SUCCESS) return 0;
  VkMemoryRequirements req{};
  vkGetImageMemoryRequirements(device, r.image, &req);
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(ctx->physical, &props);
  uint32_t index = UINT32_MAX;
  for (uint32_t i = 0; i < props.memoryTypeCount && index == UINT32_MAX; ++i) {
    if ((req.memoryTypeBits & (1u << i)) != 0 &&
        (props.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) index = i;
  }
  VkMemoryAllocateInfo ai{};
  ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  ai.allocationSize = req.size;
  ai.memoryTypeIndex = index;
  VkImageViewCreateInfo vi{};
  vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
  vi.image = r.image;
  vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
  vi.format = ii.format;
  vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  bool ok = index != UINT32_MAX && vkAllocateMemory(device, &ai, nullptr, &r.mem) == VK_SUCCESS &&
      vkBindImageMemory(device, r.image, r.mem, 0) == VK_SUCCESS &&
      vkCreateImageView(device, &vi, nullptr, &r.view) == VK_SUCCESS;
  if (ok && ctx->sampler == VK_NULL_HANDLE) {
    VkSamplerCreateInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ok = vkCreateSampler(device, &si, nullptr, &ctx->sampler) == VK_SUCCESS;
  }
  if (ok) {
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandPoolCreateInfo pi{};
    pi.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pi.queueFamilyIndex = ctx->compute_family;
    ok = vkCreateCommandPool(device, &pi, nullptr, &pool) == VK_SUCCESS;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo ca{};
    ca.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    ca.commandPool = pool;
    ca.commandBufferCount = 1;
    ok = ok && vkAllocateCommandBuffers(device, &ca, &cmd) == VK_SUCCESS;
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (ok && vkBeginCommandBuffer(cmd, &bi) == VK_SUCCESS) {
      VkImageMemoryBarrier b{};
      b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
      b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
      b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
      b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      b.image = r.image;
      b.subresourceRange = vi.subresourceRange;
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           0, 0, nullptr, 0, nullptr, 1, &b);
      VkSubmitInfo submit{};
      submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
      submit.commandBufferCount = 1;
      submit.pCommandBuffers = &cmd;
      VkQueue queue = VK_NULL_HANDLE;
      vkGetDeviceQueue(device, ctx->compute_family, 0, &queue);
      ok = vkEndCommandBuffer(cmd) == VK_SUCCESS &&
          vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS && vkQueueWaitIdle(queue) == VK_SUCCESS;
    } else {
      ok = false;
    }
    if (pool != VK_NULL_HANDLE) vkDestroyCommandPool(device, pool, nullptr);
  }
  if (!ok) {
    if (r.view != VK_NULL_HANDLE) vkDestroyImageView(device, r.view, nullptr);
    vkDestroyImage(device, r.image, nullptr);
    if (r.mem != VK_NULL_HANDLE) vkFreeMemory(device, r.mem, nullptr);
    return 0;
  }
  std::lock_guard<std::mutex> lock(ctx->mutex);
  const int64_t handle = -ctx->resident_next++;
  ctx->images[handle] = r;
  return handle;
}

VkImageView SrpImageView(Context* ctx, int64_t handle) {
  std::lock_guard<std::mutex> lock(ctx->mutex);
  const auto it = ctx->images.find(handle);
  return it == ctx->images.end() ? VK_NULL_HANDLE : it->second.view;
}

VkSampler SrpSampler(Context* ctx) { return ctx->sampler; }

void SrpFreeImage(Context* ctx, int64_t handle) {
  if (ctx == nullptr) return;
  Context::ResidentImg r;
  {
    std::lock_guard<std::mutex> lock(ctx->mutex);
    const auto it = ctx->images.find(handle);
    if (it == ctx->images.end()) return;
    r = it->second;
    ctx->images.erase(it);
  }
  vkDestroyImageView(ctx->device, r.view, nullptr);
  vkDestroyImage(ctx->device, r.image, nullptr);
  vkFreeMemory(ctx->device, r.mem, nullptr);
}

// Resident for large host readbacks: HOST_CACHED memory when the device has it
// (needs no coherence: every download invalidates). Uninitialised contents.
int64_t SrpCreateHostResident(Context* ctx, size_t nfloats) {
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE || nfloats == 0) return 0;
  VkBufferCreateInfo info{};
  info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
  info.size = static_cast<VkDeviceSize>(nfloats) * 4;
  info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  VkBuffer buf = VK_NULL_HANDLE;
  if (vkCreateBuffer(ctx->device, &info, nullptr, &buf) != VK_SUCCESS) return 0;
  VkMemoryRequirements req{};
  vkGetBufferMemoryRequirements(ctx->device, buf, &req);
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(ctx->physical, &props);
  const VkMemoryPropertyFlags want =
      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
  uint32_t index = UINT32_MAX;
  for (uint32_t i = 0; i < props.memoryTypeCount && index == UINT32_MAX; ++i)
    if ((req.memoryTypeBits & (1u << i)) != 0 && (props.memoryTypes[i].propertyFlags & want) == want)
      index = i;
  if (index == UINT32_MAX && !SrpFindHostMemory(ctx->physical, req.memoryTypeBits, &index)) {
    vkDestroyBuffer(ctx->device, buf, nullptr);
    return 0;
  }
  VkMemoryAllocateInfo ai{};
  ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  ai.allocationSize = req.size;
  ai.memoryTypeIndex = index;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  if (vkAllocateMemory(ctx->device, &ai, nullptr, &mem) != VK_SUCCESS ||
      vkBindBufferMemory(ctx->device, buf, mem, 0) != VK_SUCCESS) {
    if (mem != VK_NULL_HANDLE) vkFreeMemory(ctx->device, mem, nullptr);
    vkDestroyBuffer(ctx->device, buf, nullptr);
    return 0;
  }
  std::lock_guard<std::mutex> lock(ctx->mutex);
  const int64_t handle = ctx->resident_next++;
  ctx->resident[handle] = Context::ResidentBuf{buf, mem, nfloats, false};
  return handle;
}

// Host view of a resident (invalidated): valid until SrpUnmapResident.
const float* SrpMapResident(Context* ctx, int64_t handle, size_t nfloats) {
  if (ctx == nullptr) return nullptr;
  std::lock_guard<std::mutex> lock(ctx->mutex);
  auto it = ctx->resident.find(handle);
  if (it == ctx->resident.end() || it->second.nfloats < nfloats) return nullptr;
  void* p = nullptr;
  if (vkMapMemory(ctx->device, it->second.mem, 0, VK_WHOLE_SIZE, 0, &p) != VK_SUCCESS) return nullptr;
  VkMappedMemoryRange range{};
  range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
  range.memory = it->second.mem;
  range.size = VK_WHOLE_SIZE;
  vkInvalidateMappedMemoryRanges(ctx->device, 1, &range);
  return static_cast<const float*>(p);
}

void SrpUnmapResident(Context* ctx, int64_t handle) {
  if (ctx == nullptr) return;
  std::lock_guard<std::mutex> lock(ctx->mutex);
  auto it = ctx->resident.find(handle);
  if (it != ctx->resident.end()) vkUnmapMemory(ctx->device, it->second.mem);
}

Status SrpFreeResident(Context* ctx, int64_t handle) {
  if (ctx == nullptr || handle <= 0) return Status::kInitFailed;
  VkBuffer buf = VK_NULL_HANDLE;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  size_t nfloats = 0;
  bool pooled = true;
  {
    std::lock_guard<std::mutex> lock(ctx->mutex);
    auto it = ctx->resident.find(handle);
    if (it == ctx->resident.end()) return Status::kInitFailed;
    pooled = it->second.pooled;
    buf = it->second.buf;
    mem = it->second.mem;
    nfloats = it->second.nfloats;
    ctx->resident.erase(it);
  }
  if (!pooled) {
    vkDestroyBuffer(ctx->device, buf, nullptr);
    vkFreeMemory(ctx->device, mem, nullptr);
    return Status::kOk;
  }
  // Outside the lock: SrpReleaseBuffer takes it itself.
  SrpReleaseBuffer(ctx, buf, mem, nfloats);
  return Status::kOk;
}

Status SrpDownloadResident(Context* ctx, int64_t handle, float* out,
                           size_t count) {
  if (ctx == nullptr || out == nullptr || count == 0) {
    return Status::kInitFailed;
  }
  VkDeviceMemory mem = VK_NULL_HANDLE;
  {
    std::lock_guard<std::mutex> lock(ctx->mutex);
    auto it = ctx->resident.find(handle);
    if (it == ctx->resident.end() || it->second.nfloats < count) {
      return Status::kInitFailed;
    }
    mem = it->second.mem;
  }
  void* mapped = nullptr;
  if (vkMapMemory(ctx->device, mem, 0, static_cast<VkDeviceSize>(count) * 4,
                  0, &mapped) != VK_SUCCESS) {
    return Status::kDispatchFailed;
  }
  // Same MoltenVK invalidate the pooled downloads need (stale,
  // pre-dispatch contents otherwise).
  VkMappedMemoryRange range{};
  range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
  range.memory = mem;
  range.offset = 0;
  range.size = VK_WHOLE_SIZE;  // any nonCoherentAtomSize
  vkInvalidateMappedMemoryRanges(ctx->device, 1, &range);
  const size_t parts = count > (1u << 20) ? 4 : 1;
  std::vector<std::thread> pool;
  auto copy = [&](size_t i) {
    const size_t b = count * i / parts, e = count * (i + 1) / parts;
    std::memcpy(out + b, static_cast<const float*>(mapped) + b, (e - b) * 4);
  };
  for (size_t i = 1; i < parts; ++i) pool.emplace_back(copy, i);
  copy(0);
  for (auto& t : pool) t.join();
  vkUnmapMemory(ctx->device, mem);
  return Status::kOk;
}

Status SrpUploadResident(Context* ctx, int64_t handle, const float* in,
                         size_t count) {
  if (ctx == nullptr || in == nullptr || count == 0) {
    return Status::kInitFailed;
  }
  VkDeviceMemory mem = VK_NULL_HANDLE;
  {
    std::lock_guard<std::mutex> lock(ctx->mutex);
    auto it = ctx->resident.find(handle);
    if (it == ctx->resident.end() || it->second.nfloats < count) {
      return Status::kInitFailed;
    }
    mem = it->second.mem;
  }
  void* mapped = nullptr;
  if (vkMapMemory(ctx->device, mem, 0, static_cast<VkDeviceSize>(count) * 4,
                  0, &mapped) != VK_SUCCESS) {
    return Status::kDispatchFailed;
  }
  std::memcpy(mapped, in, count * 4);
  // Host-write availability, cf. FftChain::flushWritten (unconditional).
  VkMappedMemoryRange range{};
  range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
  range.memory = mem;
  range.offset = 0;
  range.size = VK_WHOLE_SIZE;  // any nonCoherentAtomSize
  vkFlushMappedMemoryRanges(ctx->device, 1, &range);
  vkUnmapMemory(ctx->device, mem);
  return Status::kOk;
}

Status SrpResidentBuffer(Context* ctx, int64_t handle, size_t min_floats,
                         VkBuffer* out) {
  if (ctx == nullptr || handle <= 0 || out == nullptr) {
    return Status::kInitFailed;
  }
  std::lock_guard<std::mutex> lock(ctx->mutex);
  auto it = ctx->resident.find(handle);
  if (it == ctx->resident.end() || it->second.nfloats < min_floats) {
    return Status::kInitFailed;
  }
  *out = it->second.buf;
  return Status::kOk;
}

void Destroy(Context* ctx) {
  if (ctx == nullptr) return;
  for (uint32_t i = 0; i < kBatchSlots; ++i) {
    SrpBatchWait(ctx, i);
    const Context::Batch& b = ctx->batches[i];
    if (b.fence != VK_NULL_HANDLE) vkDestroyFence(ctx->device, b.fence, nullptr);
    if (b.desc != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx->device, b.desc, nullptr);
    if (b.pool != VK_NULL_HANDLE) vkDestroyCommandPool(ctx->device, b.pool, nullptr);
  }
  for (const auto& kv : ctx->images) {
    vkDestroyImageView(ctx->device, kv.second.view, nullptr);
    vkDestroyImage(ctx->device, kv.second.image, nullptr);
    vkFreeMemory(ctx->device, kv.second.mem, nullptr);
  }
  if (ctx->sampler != VK_NULL_HANDLE) vkDestroySampler(ctx->device, ctx->sampler, nullptr);
  // Forgotten residents free with the context (same as pooled buffers).
  for (const auto& kv : ctx->resident) {
    if (kv.second.buf != VK_NULL_HANDLE) {
      vkDestroyBuffer(ctx->device, kv.second.buf, nullptr);
    }
    if (kv.second.mem != VK_NULL_HANDLE) {
      vkFreeMemory(ctx->device, kv.second.mem, nullptr);
    }
  }
  ctx->resident.clear();
  for (const auto& kv : ctx->scratch) {
    vkDestroyBuffer(ctx->device, kv.second.buf, nullptr);
    vkFreeMemory(ctx->device, kv.second.mem, nullptr);
  }
  ctx->scratch.clear();
  if (const char* prof = std::getenv("SRP_PROFILE")) {
    if (prof[0] != '\0' && prof[0] != '0') {
      std::fprintf(stderr,
                   "sr-pipeline dispatches=%llu modules=%zu pipelines=%zu "
                   "pool_hits=%llu pool_misses=%llu pool_cached=%zu "
                   "pool_mb=%.0f pool_peak_mb=%.0f evicted=%llu\n",
                   static_cast<unsigned long long>(ctx->dispatch_count),
                   ctx->modules.size(), ctx->pipes.size(),
                   static_cast<unsigned long long>(ctx->pool_hits),
                   static_cast<unsigned long long>(ctx->pool_misses),
                   ctx->pool.size(),
                   static_cast<double>(ctx->pool_bytes) / 1048576.0,
                   static_cast<double>(ctx->pool_bytes_peak) / 1048576.0,
                   static_cast<unsigned long long>(ctx->pool_evicted));
    }
  }
  if (ctx->device != VK_NULL_HANDLE) {
    for (const auto& kv : ctx->pool) {
      for (const auto& slot : kv.second) {
        if (slot.first != VK_NULL_HANDLE) {
          vkDestroyBuffer(ctx->device, slot.first, nullptr);
        }
        if (slot.second != VK_NULL_HANDLE) {
          vkFreeMemory(ctx->device, slot.second, nullptr);
        }
      }
    }
    for (const auto& kv : ctx->pipes) {
      if (kv.second.pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(ctx->device, kv.second.pipeline, nullptr);
      }
      if (kv.second.pipe_layout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(ctx->device, kv.second.pipe_layout, nullptr);
      }
      if (kv.second.set_layout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(ctx->device, kv.second.set_layout,
                                     nullptr);
      }
    }
    for (const auto& kv : ctx->modules) {
      if (kv.second != VK_NULL_HANDLE) {
        vkDestroyShaderModule(ctx->device, kv.second, nullptr);
      }
    }
    if (ctx->pipeline_cache != VK_NULL_HANDLE) {
      vkDestroyPipelineCache(ctx->device, ctx->pipeline_cache, nullptr);
    }
    if (ctx->dispatch_fence != VK_NULL_HANDLE) {
      vkDestroyFence(ctx->device, ctx->dispatch_fence, nullptr);
    }
    if (ctx->dispatch_desc_pool != VK_NULL_HANDLE) {
      vkDestroyDescriptorPool(ctx->device, ctx->dispatch_desc_pool, nullptr);
    }
    if (ctx->dispatch_cmd_pool != VK_NULL_HANDLE) {
      vkDestroyCommandPool(ctx->device, ctx->dispatch_cmd_pool, nullptr);
    }
    vkDestroyDevice(ctx->device, nullptr);
  }
  if (ctx->instance != VK_NULL_HANDLE) vkDestroyInstance(ctx->instance, nullptr);
  delete ctx;
}

// Context-owned module for these SPIR-V bytes (interned by content hash;
// created on first use). VK_NULL_HANDLE on failure. Declared in
// srp_pipeline.h next to the dispatch entry points.
VkShaderModule SrpInternModule(Context* ctx, const uint8_t* bytes,
                               size_t len) {
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE || bytes == nullptr ||
      len == 0 || len % 4 != 0) {
    return VK_NULL_HANDLE;
  }
  const uint64_t key = HashBytes(bytes, len);
  std::lock_guard<std::mutex> lock(ctx->mutex);
  const auto hit = ctx->modules.find(key);
  if (hit != ctx->modules.end()) return hit->second;
  VkShaderModuleCreateInfo module_info{};
  module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
  module_info.codeSize = len;
  module_info.pCode = reinterpret_cast<const uint32_t*>(bytes);
  VkShaderModule module = VK_NULL_HANDLE;
  if (vkCreateShaderModule(ctx->device, &module_info, nullptr, &module) !=
      VK_SUCCESS) {
    return VK_NULL_HANDLE;
  }
  ctx->modules[key] = module;
  return module;
}

Status SrpPipelineFor(Context* ctx, VkShaderModule module, uint32_t nbuffers,
                      size_t push_size, SrpPipeTriple* out, uint32_t kinds) {
  if (out == nullptr) return Status::kInitFailed;
  *out = SrpPipeTriple{};
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE ||
      module == VK_NULL_HANDLE || nbuffers == 0 ||
      nbuffers > kMaxStorageBuffers || push_size == 0 || push_size > 128) {
    return Status::kInitFailed;
  }
  VkShaderModule mod = module;
  std::lock_guard<std::mutex> lock(ctx->mutex);
  PipeKey key{mod, nbuffers, static_cast<uint32_t>(push_size) | kinds << 8};
  const auto hit = ctx->pipes.find(key);
  if (hit != ctx->pipes.end()) {
    out->set_layout = hit->second.set_layout;
    out->pipe_layout = hit->second.pipe_layout;
    out->pipeline = hit->second.pipeline;
    return Status::kOk;
  }
  // Miss: same creation sequence the per-dispatch path ran, once.
  VkDescriptorSetLayoutBinding bindings[kMaxStorageBuffers] = {};
  for (uint32_t i = 0; i < nbuffers; ++i) {
    bindings[i].binding = i;
    bindings[i].descriptorType = SrpBindType(kinds, i);
    bindings[i].descriptorCount = 1;
    bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
  }
  VkDescriptorSetLayoutCreateInfo layout_info{};
  layout_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
  layout_info.bindingCount = nbuffers;
  layout_info.pBindings = bindings;
  VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
  if (vkCreateDescriptorSetLayout(ctx->device, &layout_info, nullptr,
                                  &set_layout) != VK_SUCCESS) {
    return Status::kDispatchFailed;
  }
  VkPushConstantRange push_range{};
  push_range.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
  push_range.offset = 0;
  push_range.size = static_cast<uint32_t>(push_size);
  VkPipelineLayoutCreateInfo pipeline_layout_info{};
  pipeline_layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
  pipeline_layout_info.setLayoutCount = 1;
  pipeline_layout_info.pSetLayouts = &set_layout;
  pipeline_layout_info.pushConstantRangeCount = 1;
  pipeline_layout_info.pPushConstantRanges = &push_range;
  VkPipelineLayout pipe_layout = VK_NULL_HANDLE;
  if (vkCreatePipelineLayout(ctx->device, &pipeline_layout_info, nullptr,
                             &pipe_layout) != VK_SUCCESS) {
    vkDestroyDescriptorSetLayout(ctx->device, set_layout, nullptr);
    return Status::kDispatchFailed;
  }
  VkPipelineShaderStageCreateInfo stage{};
  stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
  stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
  stage.module = mod;
  stage.pName = "main";
  VkComputePipelineCreateInfo pipeline_info{};
  pipeline_info.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
  pipeline_info.stage = stage;
  pipeline_info.layout = pipe_layout;
  VkPipeline pipeline = VK_NULL_HANDLE;
  if (vkCreateComputePipelines(ctx->device, ctx->pipeline_cache, 1,
                               &pipeline_info, nullptr,
                               &pipeline) != VK_SUCCESS) {
    vkDestroyPipelineLayout(ctx->device, pipe_layout, nullptr);
    vkDestroyDescriptorSetLayout(ctx->device, set_layout, nullptr);
    return Status::kDispatchFailed;
  }
  ctx->pipes[key] = PipeEntry{set_layout, pipe_layout, pipeline};
  out->set_layout = set_layout;
  out->pipe_layout = pipe_layout;
  out->pipeline = pipeline;
  return Status::kOk;
}

std::string DeviceInfo(const Context* ctx) {
  if (ctx == nullptr || ctx->physical == VK_NULL_HANDLE) return "(no device)";
  char buf[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE + 96];
  std::snprintf(buf, sizeof(buf), "name=%s subgroupSize=%u computeFamily=%u", ctx->device_name,
                ctx->subgroup_size, ctx->compute_family);
  buf[sizeof(buf) - 1] = '\0';
  return std::string(buf);
}

VkDevice NativeDevice(const Context* ctx) {
  if (ctx == nullptr) return VK_NULL_HANDLE;
  return ctx->device;
}

VkPhysicalDevice NativePhysicalDevice(const Context* ctx) {
  if (ctx == nullptr) return VK_NULL_HANDLE;
  return ctx->physical;
}

uint32_t ComputeQueueFamily(const Context* ctx) {
  if (ctx == nullptr) return 0;
  return ctx->compute_family;
}

Status DispatchPassthrough(Context* ctx) {
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE) return Status::kNoDevice;

  // Phase-1 stub: no command buffer is recorded yet. A signaled fence
  // exercises the fence wait path and returns immediately.
  VkFenceCreateInfo fence_info{};
  fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
  fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;

  VkFence fence = VK_NULL_HANDLE;
  if (vkCreateFence(ctx->device, &fence_info, nullptr, &fence) != VK_SUCCESS) {
    return Status::kDispatchFailed;
  }
  VkResult wait = vkWaitForFences(ctx->device, 1, &fence, VK_TRUE, kFenceTimeoutNs);
  vkDestroyFence(ctx->device, fence, nullptr);
  return wait == VK_SUCCESS ? Status::kOk : Status::kDispatchFailed;
}

}  // namespace srp
