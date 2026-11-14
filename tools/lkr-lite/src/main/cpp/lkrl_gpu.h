// SPDX-License-Identifier: Apache-2.0
#pragma once

// Minimal Vulkan compute layer for the LK-Reloaded solver.
//
// Device/queue come from sr-pipeline's srp::Context (shared, linked from
// libsrpipeline). Unlike srp::SrpDispatchGroups (fence per dispatch), work
// here is RECORDED: pipelines and descriptor sets are created once per tile
// plan, many dispatches go into one command buffer with compute->compute
// barriers, and the host waits once per submit. Buffers are persistently
// mapped (device-local + host-visible when the heap offers it: unified
// memory on Apple and Mali).

#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

namespace srp {
struct Context;
}

namespace lkrl {

struct Buffer {
  VkBuffer buf = VK_NULL_HANDLE;
  VkDeviceMemory mem = VK_NULL_HANDLE;
  void* map = nullptr;
  size_t bytes = 0;
  bool cached = false;      // host-cached memory (CPU reads fast); may be non-coherent
  bool coherent = true;
  template <class T> T* as() const { return static_cast<T*>(map); }
};

struct Pipeline {
  VkShaderModule mod = VK_NULL_HANDLE;
  VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
  VkPipelineLayout layout = VK_NULL_HANDLE;
  VkPipeline pipe = VK_NULL_HANDLE;
  uint32_t nbind = 0;
  uint32_t push = 0;
};

// CPU wall times: fence wait includes GPU execution and must not be added to
// timestamp shader time. Totals across independent workers overlap in wall time.
struct GpuStats {
  uint64_t allocations = 0, allocated_bytes = 0, live_bytes = 0, peak_bytes = 0;
  uint64_t pipelines = 0, sets = 0, submissions = 0, dispatches = 0, copies = 0;
  double alloc_ms = 0, pipeline_ms = 0, sets_ms = 0, record_ms = 0;
  double queue_ms = 0, wait_ms = 0, coherency_ms = 0, query_ms = 0;
};

class Gpu {
 public:
  Gpu();
  ~Gpu();
  bool ok() const { return dev_ != VK_NULL_HANDLE; }
  // Compiled pipelines are shared by every Gpu of the process; with a file they also survive it
  // (the app passes one in its code cache directory). Call before creating a Gpu.
  static void SetPipelineCacheFile(const std::string& path);
  std::string info() const;

  // host_read: the CPU reads this buffer (prior input, results): prefer a
  // HOST_CACHED memory type. Device-local host-visible memory is uncached for
  // the CPU on Mali, where CPU reads are an order of magnitude slower.
  Buffer alloc(size_t bytes, bool host_read = false);
  void free(Buffer& b);

  Pipeline pipeline(const uint32_t* spv, size_t spv_bytes, uint32_t nbind, uint32_t push_bytes);
  void destroy(Pipeline& p);
  // One descriptor set per (pipeline, buffer tuple); lives until resetSets().
  VkDescriptorSet set(const Pipeline& p, std::initializer_list<const Buffer*> bufs);
  void resetSets();

  // Recording. begin() opens the command buffer; dispatch() appends a
  // dispatch followed by a full compute barrier; submit() waits.
  void begin();
  void dispatch(const Pipeline& p, VkDescriptorSet s, const void* push,
                uint32_t gx, uint32_t gy = 1, uint32_t gz = 1);
  void copy(const Buffer& src, const Buffer& dst, size_t bytes);
  void fill(const Buffer& dst, uint32_t value);
  bool submit();
  int last_error = 0;   // VkResult of the last failed submit (wait: 1000 + result)          // end + submit + wait; false on device error
  uint64_t dispatches() const { return ndispatch_; }
  // GPU timestamp profiling per pipeline (ms accumulated over submits).
  void profile(bool on);
  bool profiling() const { return qpool_ != VK_NULL_HANDLE; }
  const GpuStats& stats() const { return stats_; }
  std::string profileReport() const;

 private:
  void barrier();
  srp::Context* ctx_ = nullptr;
  VkDevice dev_ = VK_NULL_HANDLE;
  VkPhysicalDevice phys_ = VK_NULL_HANDLE;
  VkQueue queue_ = VK_NULL_HANDLE;
  VkCommandPool pool_ = VK_NULL_HANDLE;
  VkCommandBuffer cmd_ = VK_NULL_HANDLE;
  VkFence fence_ = VK_NULL_HANDLE;
  VkDescriptorPool dpool_ = VK_NULL_HANDLE;
  VkPipelineCache pcache_ = VK_NULL_HANDLE;   // seeded from / saved to a process-wide blob
  VkPhysicalDeviceMemoryProperties memprops_{};
  bool recording_ = false;
  double record_start_ms_ = 0;
  GpuStats stats_;
  std::vector<Buffer> noncoherent_;   // flushed before / invalidated after each submit
  uint64_t ndispatch_ = 0;
  VkQueryPool qpool_ = VK_NULL_HANDLE;
  float ts_period_ns_ = 1.0f;
  uint32_t nq_ = 0;
  std::vector<const Pipeline*> qwho_;
  std::vector<std::pair<const Pipeline*, double>> prof_;
};

}  // namespace lkrl
