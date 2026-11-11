// SPDX-License-Identifier: Apache-2.0
#include "lkr_gpu.h"

#include <cstdio>
#include <cstring>

#include "srp_context.h"

namespace lkr {

namespace {
constexpr uint32_t kMaxSets = 256;
constexpr uint32_t kMaxBindings = 12;
}  // namespace

Gpu::Gpu() {
  if (srp::Create(&ctx_) != srp::Status::kOk) {
    ctx_ = nullptr;
    return;
  }
  dev_ = srp::NativeDevice(ctx_);
  phys_ = srp::NativePhysicalDevice(ctx_);
  if (dev_ == VK_NULL_HANDLE) return;
  vkGetDeviceQueue(dev_, srp::ComputeQueueFamily(ctx_), 0, &queue_);
  vkGetPhysicalDeviceMemoryProperties(phys_, &memprops_);

  VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  pci.queueFamilyIndex = srp::ComputeQueueFamily(ctx_);
  vkCreateCommandPool(dev_, &pci, nullptr, &pool_);
  VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
  cai.commandPool = pool_;
  cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  cai.commandBufferCount = 1;
  vkAllocateCommandBuffers(dev_, &cai, &cmd_);
  VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
  vkCreateFence(dev_, &fci, nullptr, &fence_);

  VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, kMaxSets * kMaxBindings};
  VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
  dpi.maxSets = kMaxSets;
  dpi.poolSizeCount = 1;
  dpi.pPoolSizes = &ps;
  vkCreateDescriptorPool(dev_, &dpi, nullptr, &dpool_);
}

Gpu::~Gpu() {
  if (dev_ != VK_NULL_HANDLE) {
    vkDeviceWaitIdle(dev_);
    if (qpool_) vkDestroyQueryPool(dev_, qpool_, nullptr);
    vkDestroyDescriptorPool(dev_, dpool_, nullptr);
    vkDestroyFence(dev_, fence_, nullptr);
    vkDestroyCommandPool(dev_, pool_, nullptr);
  }
  srp::Destroy(ctx_);
}

std::string Gpu::info() const { return srp::DeviceInfo(ctx_); }

Buffer Gpu::alloc(size_t bytes, bool host_read) {
  Buffer b;
  if (bytes == 0) bytes = 4;
  VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  bi.size = bytes;
  bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
             VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  if (vkCreateBuffer(dev_, &bi, nullptr, &b.buf) != VK_SUCCESS) return Buffer{};
  VkMemoryRequirements req;
  vkGetBufferMemoryRequirements(dev_, b.buf, &req);
  const VkMemoryPropertyFlags HV = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
  const VkMemoryPropertyFlags HC = VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
  const VkMemoryPropertyFlags CA = VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
  const VkMemoryPropertyFlags DL = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
  std::vector<VkMemoryPropertyFlags> want;
  if (host_read) want = {HV | CA | HC, HV | CA};
  want.push_back(DL | HV | HC);
  want.push_back(HV | HC);
  uint32_t type = UINT32_MAX;
  for (VkMemoryPropertyFlags f : want) {
    for (uint32_t i = 0; i < memprops_.memoryTypeCount && type == UINT32_MAX; ++i)
      if ((req.memoryTypeBits & (1u << i)) && (memprops_.memoryTypes[i].propertyFlags & f) == f) type = i;
    if (type != UINT32_MAX) break;
  }
  VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  ai.allocationSize = req.size;
  ai.memoryTypeIndex = type;
  if (type == UINT32_MAX || vkAllocateMemory(dev_, &ai, nullptr, &b.mem) != VK_SUCCESS) {
    vkDestroyBuffer(dev_, b.buf, nullptr);
    return Buffer{};
  }
  vkBindBufferMemory(dev_, b.buf, b.mem, 0);
  vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map);
  b.bytes = bytes;
  const VkMemoryPropertyFlags got = memprops_.memoryTypes[type].propertyFlags;
  b.cached = (got & CA) != 0;
  b.coherent = (got & HC) != 0;
  if (!b.coherent) noncoherent_.push_back(b);
  return b;
}

void Gpu::free(Buffer& b) {
  if (b.buf == VK_NULL_HANDLE) return;
  for (size_t i = 0; i < noncoherent_.size(); ++i)
    if (noncoherent_[i].buf == b.buf) { noncoherent_.erase(noncoherent_.begin() + long(i)); break; }
  vkUnmapMemory(dev_, b.mem);
  vkDestroyBuffer(dev_, b.buf, nullptr);
  vkFreeMemory(dev_, b.mem, nullptr);
  b = Buffer{};
}

Pipeline Gpu::pipeline(const uint32_t* spv, size_t spv_bytes, uint32_t nbind, uint32_t push_bytes) {
  Pipeline p;
  p.nbind = nbind;
  p.push = push_bytes;
  VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
  mi.codeSize = spv_bytes;
  mi.pCode = spv;
  vkCreateShaderModule(dev_, &mi, nullptr, &p.mod);
  std::vector<VkDescriptorSetLayoutBinding> b(nbind);
  for (uint32_t i = 0; i < nbind; ++i)
    b[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
  VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  li.bindingCount = nbind;
  li.pBindings = b.data();
  vkCreateDescriptorSetLayout(dev_, &li, nullptr, &p.dsl);
  VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, push_bytes};
  VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
  pli.setLayoutCount = 1;
  pli.pSetLayouts = &p.dsl;
  pli.pushConstantRangeCount = push_bytes ? 1 : 0;
  pli.pPushConstantRanges = &pr;
  vkCreatePipelineLayout(dev_, &pli, nullptr, &p.layout);
  VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
  ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0,
              VK_SHADER_STAGE_COMPUTE_BIT, p.mod, "main", nullptr};
  ci.layout = p.layout;
  if (vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &ci, nullptr, &p.pipe) != VK_SUCCESS)
    std::fprintf(stderr, "lkr: pipeline creation failed\n");
  return p;
}

void Gpu::destroy(Pipeline& p) {
  if (p.pipe) vkDestroyPipeline(dev_, p.pipe, nullptr);
  if (p.layout) vkDestroyPipelineLayout(dev_, p.layout, nullptr);
  if (p.dsl) vkDestroyDescriptorSetLayout(dev_, p.dsl, nullptr);
  if (p.mod) vkDestroyShaderModule(dev_, p.mod, nullptr);
  p = Pipeline{};
}

VkDescriptorSet Gpu::set(const Pipeline& p, std::initializer_list<const Buffer*> bufs) {
  VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
  ai.descriptorPool = dpool_;
  ai.descriptorSetCount = 1;
  ai.pSetLayouts = &p.dsl;
  VkDescriptorSet s = VK_NULL_HANDLE;
  if (vkAllocateDescriptorSets(dev_, &ai, &s) != VK_SUCCESS) {
    std::fprintf(stderr, "lkr: descriptor pool exhausted\n");
    return VK_NULL_HANDLE;
  }
  std::vector<VkDescriptorBufferInfo> info;
  std::vector<VkWriteDescriptorSet> w;
  info.reserve(bufs.size());
  uint32_t i = 0;
  for (const Buffer* b : bufs) info.push_back({b->buf, 0, VK_WHOLE_SIZE});
  for (i = 0; i < info.size(); ++i) {
    VkWriteDescriptorSet x{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    x.dstSet = s;
    x.dstBinding = i;
    x.descriptorCount = 1;
    x.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    x.pBufferInfo = &info[i];
    w.push_back(x);
  }
  vkUpdateDescriptorSets(dev_, static_cast<uint32_t>(w.size()), w.data(), 0, nullptr);
  return s;
}

void Gpu::resetSets() { vkResetDescriptorPool(dev_, dpool_, 0); }

void Gpu::profile(bool on) {
  if (!on || qpool_) return;
  VkPhysicalDeviceProperties pr;
  vkGetPhysicalDeviceProperties(phys_, &pr);
  ts_period_ns_ = pr.limits.timestampPeriod;
  VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
  qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
  qi.queryCount = 1024;
  vkCreateQueryPool(dev_, &qi, nullptr, &qpool_);
}

std::string Gpu::profileReport() const {
  std::string out;
  char b[96];
  for (const auto& e : prof_) {
    std::snprintf(b, sizeof(b), "%s%p:%.1f", out.empty() ? "" : " ", (const void*)e.first, e.second);
    out += b;
  }
  return out;
}

void Gpu::begin() {
  vkResetCommandBuffer(cmd_, 0);
  VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  vkBeginCommandBuffer(cmd_, &bi);
  if (qpool_) vkCmdResetQueryPool(cmd_, qpool_, 0, 1024);
  nq_ = 0;
  qwho_.clear();
  recording_ = true;
}

void Gpu::barrier() {
  VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
  mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                     VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
  vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb,
                       0, nullptr, 0, nullptr);
}

void Gpu::dispatch(const Pipeline& p, VkDescriptorSet s, const void* push, uint32_t gx, uint32_t gy,
                   uint32_t gz) {
  vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, p.pipe);
  vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, p.layout, 0, 1, &s, 0, nullptr);
  if (p.push) vkCmdPushConstants(cmd_, p.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, p.push, push);
  const bool prof = qpool_ && nq_ + 2 <= 1024;
  if (prof) vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, qpool_, nq_++);
  vkCmdDispatch(cmd_, gx, gy, gz);
  if (prof) {
    vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, qpool_, nq_++);
    qwho_.push_back(&p);
  }
  barrier();
  ++ndispatch_;
}

void Gpu::copy(const Buffer& src, const Buffer& dst, size_t bytes) {
  VkBufferCopy r{0, 0, bytes};
  vkCmdCopyBuffer(cmd_, src.buf, dst.buf, 1, &r);
  barrier();
}

void Gpu::fill(const Buffer& dst, uint32_t value) {
  vkCmdFillBuffer(cmd_, dst.buf, 0, VK_WHOLE_SIZE, value);
  barrier();
}

bool Gpu::submit() {
  if (!recording_) return true;
  // Host writes before submit are visible (coherent memory + submit is a
  // host->device domain operation); add the device->host barrier for reads.
  VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
  mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
  mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
  vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
  vkEndCommandBuffer(cmd_);
  recording_ = false;
  VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  si.commandBufferCount = 1;
  si.pCommandBuffers = &cmd_;
  std::vector<VkMappedMemoryRange> ranges;
  for (const Buffer& b : noncoherent_)
    ranges.push_back({VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, nullptr, b.mem, 0, VK_WHOLE_SIZE});
  if (!ranges.empty()) vkFlushMappedMemoryRanges(dev_, uint32_t(ranges.size()), ranges.data());   // CPU writes -> GPU
  vkResetFences(dev_, 1, &fence_);
  const VkResult rs = vkQueueSubmit(queue_, 1, &si, fence_);
  if (rs != VK_SUCCESS) { last_error = int(rs); return false; }
  const VkResult rw = vkWaitForFences(dev_, 1, &fence_, VK_TRUE, UINT64_MAX);
  if (rw != VK_SUCCESS) last_error = 1000 + int(rw);
  const bool ok = rw == VK_SUCCESS;
  if (ok && !ranges.empty()) vkInvalidateMappedMemoryRanges(dev_, uint32_t(ranges.size()), ranges.data());   // GPU writes -> CPU
  if (ok && qpool_ && nq_) {
    std::vector<uint64_t> ts(nq_);
    vkGetQueryPoolResults(dev_, qpool_, 0, nq_, nq_ * 8, ts.data(), 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
    for (size_t i = 0; i < qwho_.size(); ++i) {
      const double ms = double(ts[2 * i + 1] - ts[2 * i]) * ts_period_ns_ * 1e-6;
      bool found = false;
      for (auto& e : prof_) if (e.first == qwho_[i]) { e.second += ms; found = true; }
      if (!found) prof_.push_back({qwho_[i], ms});
    }
  }
  return ok;
}

}  // namespace lkr
