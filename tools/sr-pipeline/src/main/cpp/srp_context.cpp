#include "srp_context.h"

#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <vector>
#ifdef __ANDROID__
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
  };
  std::map<int64_t, ResidentBuf> resident;
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
    VkDescriptorPoolSize pool_size{};
    pool_size.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    pool_size.descriptorCount = kMaxStorageBuffers;
    VkDescriptorPoolCreateInfo pool_info{};
    pool_info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    pool_info.maxSets = 1;
    pool_info.poolSizeCount = 1;
    pool_info.pPoolSizes = &pool_size;
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
  // FindMemoryType lives in srp_pipeline.cpp; repeat the same scan here
  // to keep the pool self-contained (host-visible + coherent).
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(physical, &props);
  bool found = false;
  for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
    if ((req.memoryTypeBits & (1u << i)) != 0 &&
        (props.memoryTypes[i].propertyFlags &
         (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
            (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
      index = i;
      found = true;
      break;
    }
  }
  if (!found) {
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
  ctx->resident[handle] = Context::ResidentBuf{buf, mem, nfloats};
  return handle;
}

Status SrpFreeResident(Context* ctx, int64_t handle) {
  if (ctx == nullptr || handle <= 0) return Status::kInitFailed;
  VkBuffer buf = VK_NULL_HANDLE;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  size_t nfloats = 0;
  {
    std::lock_guard<std::mutex> lock(ctx->mutex);
    auto it = ctx->resident.find(handle);
    if (it == ctx->resident.end()) return Status::kInitFailed;
    buf = it->second.buf;
    mem = it->second.mem;
    nfloats = it->second.nfloats;
    ctx->resident.erase(it);
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
  range.size = static_cast<VkDeviceSize>(count) * 4;
  vkInvalidateMappedMemoryRanges(ctx->device, 1, &range);
  std::memcpy(out, mapped, count * 4);
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
  range.size = static_cast<VkDeviceSize>(count) * 4;
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
                      size_t push_size, SrpPipeTriple* out) {
  if (out == nullptr) return Status::kInitFailed;
  *out = SrpPipeTriple{};
  if (ctx == nullptr || ctx->device == VK_NULL_HANDLE ||
      module == VK_NULL_HANDLE || nbuffers == 0 ||
      nbuffers > kMaxStorageBuffers || push_size == 0 || push_size > 128) {
    return Status::kInitFailed;
  }
  VkShaderModule mod = module;
  std::lock_guard<std::mutex> lock(ctx->mutex);
  PipeKey key{mod, nbuffers, static_cast<uint32_t>(push_size)};
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
    bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
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
