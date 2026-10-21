#include <cstdio>
#include "srp_pipeline.h"

#include "srp_dispatch.h"

#include <vulkan/vulkan.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace srp {
namespace {

// Shared CPU-fallback body: validate, identity-copy, report.
Status CopyPassthrough(Context* ctx, const float* in, float* out, size_t count) {
  (void)ctx;  // CPU fallback needs no device; null is accepted.
  if (in == nullptr || out == nullptr || count == 0) return Status::kInitFailed;
  std::memmove(out, in, count * sizeof(float));
  return Status::kOk;
}

// Element-count wrapper over SrpDispatchGroups (srp_dispatch.h): same
// validation, groups = ceil(count / local_size).
Status DispatchStorageCompute(Context* ctx, VkBuffer* buffers,
                              uint32_t nbuffers, const void* push,
                              size_t push_size, uint32_t local_size,
                              uint32_t count, VkShaderModule shader_module) {
  if (ctx == nullptr || buffers == nullptr || nbuffers == 0 ||
      nbuffers > kMaxStorageBuffers || push == nullptr || push_size == 0 ||
      push_size > 128 || local_size == 0 || count == 0 ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  for (uint32_t i = 0; i < nbuffers; ++i) {
    if (buffers[i] == VK_NULL_HANDLE) return Status::kInitFailed;
  }
  const uint32_t groups = (count + local_size - 1) / local_size;
  return SrpDispatchGroups(ctx, buffers, nbuffers, push, push_size,
                           local_size, groups, shader_module);
}

}  // namespace

namespace {
// Fence timeout shared by the dispatch core below (file-local).
constexpr uint64_t kFenceTimeoutNs = 1000000000ULL;  // 1 s
}  // namespace

Status SrpDispatchGroups(Context* ctx, VkBuffer* buffers, uint32_t nbuffers,
                         const void* push, size_t push_size,
                         uint32_t local_size, uint32_t groups,
                         VkShaderModule shader_module) {
  if (ctx == nullptr || buffers == nullptr || nbuffers == 0 ||
      nbuffers > kMaxStorageBuffers || push == nullptr || push_size == 0 ||
      push_size > 128 || local_size == 0 || groups == 0 ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  for (uint32_t i = 0; i < nbuffers; ++i) {
    if (buffers[i] == VK_NULL_HANDLE) return Status::kInitFailed;
  }
  // Workgroup shape comes from the shader; only the group count matters.
  (void)local_size;
  VkDevice device = NativeDevice(ctx);
  if (device == VK_NULL_HANDLE) return Status::kNoDevice;
  VkQueue queue = VK_NULL_HANDLE;
  vkGetDeviceQueue(device, ComputeQueueFamily(ctx), 0, &queue);
  if (queue == VK_NULL_HANDLE) return Status::kNoDevice;

  // Cached triple: same layouts + compiled pipeline for this (module,
  // buffer count, push size) shape across all dispatches. Owned by the
  // Context — must not be destroyed here.
  SrpPipeTriple triple{};
  if (SrpPipelineFor(ctx, shader_module, nbuffers,
                     push_size, &triple) != Status::kOk ||
      triple.pipeline == VK_NULL_HANDLE ||
      triple.pipe_layout == VK_NULL_HANDLE ||
      triple.set_layout == VK_NULL_HANDLE) {
    return Status::kDispatchFailed;
  }
  VkDescriptorSetLayout set_layout = triple.set_layout;
  VkPipelineLayout pipeline_layout = triple.pipe_layout;
  VkPipeline pipeline = triple.pipeline;

  // Persistent per-dispatch objects (reset pools + fence): no
  // per-dispatch create/destroy. Same submit points, same order, same
  // fence waits — scheduling-only change, values bit-identical.
  VkCommandBuffer cmd = VK_NULL_HANDLE;
  VkDescriptorSet set = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  Status status = Status::kDispatchFailed;
  if (SrpDispatchBegin(ctx, set_layout, &cmd, &set, &fence) == Status::kOk) {
    VkDescriptorBufferInfo buffer_infos[kMaxStorageBuffers] = {};
    VkWriteDescriptorSet writes[kMaxStorageBuffers] = {};
    for (uint32_t i = 0; i < nbuffers; ++i) {
      buffer_infos[i].buffer = buffers[i];
      buffer_infos[i].range = VK_WHOLE_SIZE;
      writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[i].dstSet = set;
      writes[i].dstBinding = i;
      writes[i].descriptorCount = 1;
      writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      writes[i].pBufferInfo = &buffer_infos[i];
    }
    vkUpdateDescriptorSets(device, nbuffers, writes, 0, nullptr);

    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(cmd, &begin) == VK_SUCCESS) {
      vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
      vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1,
                              &set, 0, nullptr);
      vkCmdPushConstants(cmd, pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                         static_cast<uint32_t>(push_size), push);
      vkCmdDispatch(cmd, groups, 1, 1);
      if (vkEndCommandBuffer(cmd) == VK_SUCCESS) {
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &cmd;
        if (vkQueueSubmit(queue, 1, &submit, fence) == VK_SUCCESS) {
          // Fence wait in 1 s slices, retrying VK_TIMEOUT: a timed-out
          // wait must NEVER release the dispatch as complete — the
          // buffers return to the pool (reuse, overwrite, evict-destroy)
          // on kOk, and reusing them while the GPU is still running is
          // driver use-after-free (observed as a mali-event-thread
          // abort minutes later on slow full-res dispatches). Bound:
          // 300 slices (5 min), then fail loudly instead of reusing.
          VkResult wait = VK_TIMEOUT;
          for (int i = 0; i < 300 && wait == VK_TIMEOUT; ++i) {
            wait = vkWaitForFences(device, 1, &fence, VK_TRUE,
                                   kFenceTimeoutNs);
          }
          if (wait == VK_SUCCESS) {
            status = Status::kOk;
          }
        }
      }
    }
  }

  // Layouts + pipeline stay alive in the Context cache; dispatch pools
  // and fence stay alive in the Context (reset per dispatch).
  if (status == Status::kOk) SrpCountDispatch(ctx);
  return status;
}

static_assert(sizeof(SrpMergeParams) == 16, "SrpMergeParams must stay 16 bytes");
static_assert(sizeof(SrpMergeParams) <= 128, "params must fit push-constant limits");
static_assert(sizeof(SrpHmsrPush) == 128, "SrpHmsrPush must mirror the 128 B HmsrPush");

Status SrpGatCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpDecimateCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpAlignCropCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpIcaCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpKernelsCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpRobustnessCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpMergeCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpGreyFftCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpL2Cpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpSmapCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpDivideCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpHessianCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpGradCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpResampleCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpDemosaicCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}
Status SrpEndToEndCpu(Context* ctx, const float* in, float* out, size_t count) {
  return CopyPassthrough(ctx, in, out, count);
}

Status SrpMergeAccumulateGpu(Context* ctx, const SrpMergeParams* params,
                             VkBuffer in_buffer, VkBuffer acc_buffer,
                             VkShaderModule shader_module) {
  if (params == nullptr) return Status::kInitFailed;
  VkBuffer buffers[2] = {in_buffer, acc_buffer};
  return DispatchStorageCompute(ctx, buffers, 2, params, sizeof(*params),
                                kMergeLocalSize, params->count, shader_module);
}

Status SrpAlignL1Gpu(Context* ctx, uint32_t width, uint32_t height,
                     uint32_t tile_count, uint32_t tile_size,
                     uint32_t search_radius, uint32_t ntx, VkBuffer ref_buffer,
                     VkBuffer mov_buffer, VkBuffer flow_buffer,
                     VkShaderModule shader_module) {
  if (width == 0 || height == 0 || tile_count == 0 || tile_size == 0 ||
      ntx == 0 || tile_count % ntx != 0) {
    return Status::kInitFailed;
  }
  // Push overlay mirrors make_push(w, h, tc, tile_count=tc, tile_size,
  // pad_extra={0: 0, 1: search_radius, 2: ntx}): scale 1, rest 0.
  SrpHmsrPush push{};
  push.width = width;
  push.height = height;
  push.pixel_count = tile_count;
  push.tile_count = tile_count;
  push.tile_size = tile_size;
  push.scale = 1.0f;
  push.pad[1] = search_radius;
  push.pad[2] = ntx;
  VkBuffer buffers[3] = {ref_buffer, mov_buffer, flow_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kAlignLocalSize, tile_count, shader_module);
}

Status SrpCorrExtractGpu(Context* ctx, uint32_t height, uint32_t width,
                         uint32_t tile_count, uint32_t tile_size,
                         uint32_t radius, uint32_t ntx, uint32_t tile_offset,
                         uint32_t chunk_tiles, VkBuffer mov_buffer,
                         VkBuffer flow_buffer, VkBuffer patch_buffer,
                         VkShaderModule shader_module) {
  if (height == 0 || width == 0 || tile_count == 0 || tile_size == 0 ||
      ntx == 0 || tile_count % ntx != 0 || chunk_tiles == 0 ||
      tile_offset >= tile_count ||
      static_cast<uint64_t>(tile_offset) + chunk_tiles > tile_count) {
    return Status::kInitFailed;
  }
  const uint32_t s = tile_size + 2 * radius;
  const uint64_t out_n =
      static_cast<uint64_t>(chunk_tiles) * s * s;
  if (out_n > static_cast<uint64_t>(UINT32_MAX)) return Status::kInitFailed;
  // Push overlay mirrors make_push(0, 0, n*S*S, tile_count=n, tile_size,
  // pad_extra={0:0, 1:H, 2:W, 3:ntx, 4:S, 5:radius, 6:tile_offset}).
  // tile_count stays FULL (the shader's whole-row guard); the grid
  // covers only the chunk window.
  SrpHmsrPush push{};
  push.pixel_count = static_cast<uint32_t>(out_n);
  push.tile_count = tile_count;
  push.tile_size = tile_size;
  push.scale = 1.0f;
  push.pad[1] = height;
  push.pad[2] = width;
  push.pad[3] = ntx;
  push.pad[4] = s;
  push.pad[5] = radius;
  push.pad[6] = tile_offset;
  VkBuffer buffers[3] = {mov_buffer, flow_buffer, patch_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kCorrLocalSize, static_cast<uint32_t>(out_n),
                                shader_module);
}

Status SrpCorrArgminGpu(Context* ctx, uint32_t tile_count, uint32_t tile_size,
                        uint32_t radius, uint32_t ntx, uint32_t tile_offset,
                        uint32_t chunk_tiles, uint32_t patch_count,
                        uint32_t corr_count, VkBuffer patch_buffer,
                        VkBuffer corr_buffer, VkBuffer dflow_buffer,
                        VkShaderModule shader_module) {
  if (tile_count == 0 || tile_size == 0 || ntx == 0 ||
      tile_count % ntx != 0 || chunk_tiles == 0 ||
      tile_offset >= tile_count ||
      static_cast<uint64_t>(tile_offset) + chunk_tiles > tile_count) {
    return Status::kInitFailed;
  }
  const uint32_t s = tile_size + 2 * radius;
  const uint32_t c = 2 * radius + 1;
  if (patch_count != chunk_tiles * s * s ||
      corr_count != chunk_tiles * c * c) {
    return Status::kInitFailed;
  }
  // Push overlay mirrors make_push(0, 0, n*2, tile_count=n, tile_size,
  // pad_extra={0:1, 1:0, 2:0, 3:ntx, 4:S, 5:radius, 6:tile_offset}).
  // tile_count here is the CHUNK (the shader's per-tile bound);
  // the window selects the global tiles, cf. op 0.
  SrpHmsrPush push{};
  push.pixel_count = chunk_tiles * 2;
  push.tile_count = chunk_tiles;
  push.tile_size = tile_size;
  push.scale = 1.0f;
  push.pad[0] = 1;
  push.pad[3] = ntx;
  push.pad[4] = s;
  push.pad[5] = radius;
  push.pad[6] = tile_offset;
  VkBuffer buffers[3] = {patch_buffer, corr_buffer, dflow_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kCorrLocalSize, chunk_tiles, shader_module);
}

Status SrpCorrCropGpu(Context* ctx, uint32_t tile_count, uint32_t tile_size,
                      uint32_t radius, uint32_t ntx, VkBuffer full_buffer,
                      VkBuffer crop_buffer, VkShaderModule shader_module) {
  if (tile_count == 0 || tile_size == 0 || ntx == 0 ||
      tile_count % ntx != 0) {
    return Status::kInitFailed;
  }
  const uint32_t s = tile_size + 2 * radius;
  const uint32_t c = 2 * radius + 1;
  const uint64_t out_n = static_cast<uint64_t>(tile_count) * c * c;
  if (out_n == 0 || out_n > static_cast<uint64_t>(UINT32_MAX)) {
    return Status::kInitFailed;
  }
  // Push overlay mirrors the extract/argmin family: pad[0]=2 selects the
  // crop op, pad[4]=S, pad[5]=radius (C derives as 2r+1).
  SrpHmsrPush push{};
  push.pixel_count = static_cast<uint32_t>(out_n);
  push.tile_count = tile_count;
  push.tile_size = tile_size;
  push.scale = 1.0f;
  push.pad[0] = 2;
  push.pad[3] = ntx;
  push.pad[4] = s;
  push.pad[5] = radius;
  // Binding 1 is unread by op 2: alias the full input (cf. Hessian).
  VkBuffer buffers[3] = {full_buffer, full_buffer, crop_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kCorrLocalSize, static_cast<uint32_t>(out_n),
                                shader_module);
}

Status SrpReal2PairsGpu(Context* ctx, uint32_t nfloats, VkBuffer real_buffer,
                        VkBuffer pairs_buffer, VkShaderModule shader_module) {
  if (nfloats == 0) {
    return Status::kInitFailed;
  }
  // Push overlay: pad[0]=3 selects real2pairs; no shape pads needed.
  SrpHmsrPush push{};
  push.pixel_count = nfloats;
  push.scale = 1.0f;
  push.pad[0] = 3;
  // Binding 1 is unread by op 3: alias the real input (cf. Hessian).
  VkBuffer buffers[3] = {real_buffer, real_buffer, pairs_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kCorrLocalSize, nfloats, shader_module);
}

Status SrpIcaHessianGpu(Context* ctx, uint32_t width, uint32_t height,
                        uint32_t tile_count, uint32_t tile_size, uint32_t ntx,
                        VkBuffer gradx_buffer, VkBuffer grady_buffer,
                        VkBuffer hess_buffer, VkShaderModule shader_module) {
  if (width == 0 || height == 0 || tile_count == 0 || tile_size == 0 ||
      ntx == 0 || tile_count % ntx != 0) {
    return Status::kInitFailed;
  }
  // Push overlay mirrors make_push(w, h, tc, tile_count=tc, tile_size,
  // pad_extra={0:0, 1:ntx, 2:0, 3:3}).
  SrpHmsrPush push{};
  push.width = width;
  push.height = height;
  push.pixel_count = tile_count;
  push.tile_count = tile_count;
  push.tile_size = tile_size;
  push.scale = 1.0f;
  push.pad[1] = ntx;
  push.pad[3] = 3;
  // Op 0 reads bindings 2 (gradx) and 3 (grady) and writes binding 4.
  // Bindings 0/1 (ref/mov) alias gradx like the port's duplicate bindings;
  // binding 5 (flow) aliases the hess output — op 0 never touches it, so
  // no hazard, and no transient dummy buffer is needed.
  VkBuffer buffers[6] = {gradx_buffer, gradx_buffer, gradx_buffer,
                         grady_buffer, hess_buffer, hess_buffer};
  return DispatchStorageCompute(ctx, buffers, 6, &push, sizeof(push),
                                kIcaLocalSize, tile_count, shader_module);
}

Status SrpIcaRefineGpu(Context* ctx, uint32_t width, uint32_t height,
                       uint32_t tile_count, uint32_t tile_size, uint32_t niter,
                       float clip_radius, uint32_t ntx, VkBuffer ref_buffer,
                       VkBuffer mov_buffer, VkBuffer gradx_buffer,
                       VkBuffer grady_buffer, VkBuffer hess_buffer,
                       VkBuffer flow_buffer, VkShaderModule shader_module) {
  if (width == 0 || height == 0 || tile_count == 0 || tile_size == 0 ||
      niter == 0 || ntx == 0 || tile_count % ntx != 0) {
    return Status::kInitFailed;
  }
  // Push overlay mirrors make_push(w, h, tc, tile_count=tc, tile_size,
  // pad_extra={0:1, 1:ntx, 2:clipbits, 3:niter}); clip uploads by bit
  // pattern, exactly like the port's struct pack/unpack.
  uint32_t clipbits = 0;
  static_assert(sizeof(clipbits) == sizeof(clip_radius), "float bits");
  std::memcpy(&clipbits, &clip_radius, sizeof(clipbits));
  SrpHmsrPush push{};
  push.width = width;
  push.height = height;
  push.pixel_count = tile_count;
  push.tile_count = tile_count;
  push.tile_size = tile_size;
  push.scale = 1.0f;
  push.pad[0] = 1;
  push.pad[1] = ntx;
  push.pad[2] = clipbits;
  push.pad[3] = niter;
  VkBuffer buffers[6] = {ref_buffer, mov_buffer, gradx_buffer, grady_buffer,
                         hess_buffer, flow_buffer};
  return DispatchStorageCompute(ctx, buffers, 6, &push, sizeof(push),
                                kIcaLocalSize, tile_count, shader_module);
}

// ica_wg.comp: one workgroup per tile (generic tile sizes).
Status SrpIcaRefineWgGpu(Context* ctx, uint32_t width, uint32_t height,
                         uint32_t tile_count, uint32_t tile_size, uint32_t niter,
                         float clip_radius, uint32_t ntx, VkBuffer ref_buffer,
                         VkBuffer mov_buffer, VkBuffer gradx_buffer,
                         VkBuffer grady_buffer, VkBuffer hess_buffer,
                         VkBuffer flow_buffer, VkShaderModule shader_module) {
  if (width == 0 || height == 0 || tile_count == 0 || tile_size == 0 ||
      niter == 0 || ntx == 0 || tile_count % ntx != 0) {
    return Status::kInitFailed;
  }
  uint32_t clipbits = 0;
  std::memcpy(&clipbits, &clip_radius, sizeof(clipbits));
  SrpHmsrPush push{};
  push.width = width; push.height = height; push.pixel_count = tile_count;
  push.tile_count = tile_count; push.tile_size = tile_size; push.scale = 1.0f;
  push.pad[0] = 1; push.pad[1] = ntx; push.pad[2] = clipbits; push.pad[3] = niter;
  VkBuffer buffers[6] = {ref_buffer, mov_buffer, gradx_buffer, grady_buffer,
                         hess_buffer, flow_buffer};
  return SrpDispatchGroups(ctx, buffers, 6, &push, sizeof(push), 256,
                           tile_count > 65535 ? 65535 : tile_count, shader_module);
}

Status SrpGatGpu(Context* ctx, uint32_t width, uint32_t height,
                 uint32_t count, uint32_t op, VkBuffer in_buffer,
                 VkBuffer out_buffer, VkBuffer ab_buffer,
                 VkShaderModule shader_module) {
  if (width == 0 || height == 0 || count == 0 || (op != 0 && op != 1)) {
    return Status::kInitFailed;
  }
  if (op == 0 && count != width * height) return Status::kInitFailed;
  if (op == 1 && (width % 2 != 0 || height % 2 != 0 ||
                  count != (width / 2) * (height / 2))) {
    return Status::kInitFailed;
  }
  SrpHmsrPush push{};
  push.width = width;
  push.height = height;
  push.pixel_count = count;
  push.scale = 1.0f;
  push.pad[0] = op;
  VkBuffer buffers[3] = {in_buffer, out_buffer, ab_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kGatLocalSize, count, shader_module);
}

namespace {

bool FindMemoryType(VkPhysicalDevice physical, uint32_t type_bits,
                    VkMemoryPropertyFlags flags, uint32_t* out_index) {
  if (physical == VK_NULL_HANDLE || out_index == nullptr) return false;
  VkPhysicalDeviceMemoryProperties props{};
  vkGetPhysicalDeviceMemoryProperties(physical, &props);
  for (uint32_t i = 0; i < props.memoryTypeCount; ++i) {
    if ((type_bits & (1u << i)) != 0u &&
        (props.memoryTypes[i].propertyFlags & flags) == flags) {
      *out_index = i;
      return true;
    }
  }
  return false;
}

// Host-visible transient arena for the demosaic chain (mirrors the
// FftChain/srp_fft.cpp pattern; file-local, freed before return).
struct DemoArena {
  Context* ctx = nullptr;
  VkDevice device = VK_NULL_HANDLE;
  struct Item {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
  };
  std::vector<Item> items;
  bool ok = true;

  VkBuffer alloc(uint32_t nfloats, const float* src) {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    if (!ok || device == VK_NULL_HANDLE || nfloats == 0) {
      ok = false;
      return VK_NULL_HANDLE;
    }
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = static_cast<VkDeviceSize>(nfloats) * 4;
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device, &info, nullptr, &buf) != VK_SUCCESS) {
      ok = false;
      return VK_NULL_HANDLE;
    }
    VkPhysicalDevice physical = NativePhysicalDevice(ctx);
    VkMemoryRequirements req{};
    vkGetBufferMemoryRequirements(device, buf, &req);
    uint32_t index = 0;
    if (!FindMemoryType(physical, req.memoryTypeBits,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        &index)) {
      vkDestroyBuffer(device, buf, nullptr);
      ok = false;
      return VK_NULL_HANDLE;
    }
    VkMemoryAllocateInfo ai{};
    ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = index;
    if (vkAllocateMemory(device, &ai, nullptr, &mem) != VK_SUCCESS ||
        vkBindBufferMemory(device, buf, mem, 0) != VK_SUCCESS) {
      if (mem != VK_NULL_HANDLE) vkFreeMemory(device, mem, nullptr);
      vkDestroyBuffer(device, buf, nullptr);
      ok = false;
      return VK_NULL_HANDLE;
    }
    void* mapped = nullptr;
    if (vkMapMemory(device, mem, 0, static_cast<VkDeviceSize>(nfloats) * 4, 0,
                    &mapped) != VK_SUCCESS) {
      vkFreeMemory(device, mem, nullptr);
      vkDestroyBuffer(device, buf, nullptr);
      ok = false;
      return VK_NULL_HANDLE;
    }
    if (src != nullptr) {
      std::memcpy(mapped, src, static_cast<size_t>(nfloats) * 4);
    } else {
      std::memset(mapped, 0, static_cast<size_t>(nfloats) * 4);
    }
    vkUnmapMemory(device, mem);
    items.push_back({buf, mem});
    return buf;
  }

  void freeAll() {
    if (device != VK_NULL_HANDLE) {
      for (const auto& it : items) {
        vkDestroyBuffer(device, it.buf, nullptr);
        vkFreeMemory(device, it.mem, nullptr);
      }
    }
    items.clear();
  }
};

// Decision kernel K (colour_demosaicing menon2007.py literal, float32).
constexpr float kDemoK[25] = {
    0, 0, 1, 0, 1,  //
    0, 0, 0, 1, 0,  //
    0, 0, 3, 0, 3,  //
    0, 0, 0, 1, 0,  //
    0, 0, 1, 0, 1,  //
};

// 50 pre-flipped sparse taps: k flipped then transposed-k flipped, exactly
// like the port's sparse_taps() (scipy convolve flips the kernel; the
// shader correlates, taps arrive row-major l2r in C_).
void BuildDemoTaps(float out_taps[50]) {
  for (int y = 0; y < 5; ++y) {
    for (int x = 0; x < 5; ++x) {
      out_taps[y * 5 + x] = kDemoK[(4 - y) * 5 + (4 - x)];
      // Transpose then flip both axes: KT[y][x] = K[x][y].
      out_taps[25 + y * 5 + x] = kDemoK[(4 - x) * 5 + (4 - y)];
    }
  }
}

}  // namespace

Status SrpDemosaicMenonGpu(Context* ctx, uint32_t width, uint32_t height,
                           VkBuffer cfa_buffer, VkBuffer grey_buffer,
                           VkShaderModule shader_module) {
  if (ctx == nullptr || width == 0 || height == 0 ||
      cfa_buffer == VK_NULL_HANDLE || grey_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint64_t n64 =
      static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
  if (n64 == 0 || n64 > 0xFFFFFFFFULL) return Status::kInitFailed;
  const uint32_t n = static_cast<uint32_t>(n64);
  if (n / width != height) return Status::kInitFailed;
  // The vec2 banks hold 2*n floats; stay far from 32-bit overflow.
  if (n > 0x0FFFFFFFu) return Status::kInitFailed;

  DemoArena arena;
  arena.ctx = ctx;
  arena.device = NativeDevice(ctx);
  if (arena.device == VK_NULL_HANDLE) return Status::kNoDevice;

  // Intermediates, mirroring the port's resident session one for one.
  VkBuffer gh = arena.alloc(n, nullptr);
  VkBuffer gv = arena.alloc(n, nullptr);
  VkBuffer dh = arena.alloc(n, nullptr);
  VkBuffer dv = arena.alloc(n, nullptr);
  VkBuffer m = arena.alloc(n, nullptr);
  VkBuffer g = arena.alloc(n, nullptr);
  VkBuffer r1a = arena.alloc(n, nullptr);
  VkBuffer b1a = arena.alloc(n, nullptr);
  VkBuffer r1 = arena.alloc(n, nullptr);
  VkBuffer b1 = arena.alloc(n, nullptr);
  VkBuffer rgm = arena.alloc(n, nullptr);
  VkBuffer bgm = arena.alloc(n, nullptr);
  VkBuffer r2 = arena.alloc(n, nullptr);
  VkBuffer b2 = arena.alloc(n, nullptr);
  VkBuffer g2 = arena.alloc(n, nullptr);
  VkBuffer r3 = arena.alloc(n, nullptr);
  VkBuffer b3 = arena.alloc(n, nullptr);
  VkBuffer rc = arena.alloc(2 * n, nullptr);
  VkBuffer gc = arena.alloc(2 * n, nullptr);
  VkBuffer bc = arena.alloc(2 * n, nullptr);
  float taps_host[50];
  BuildDemoTaps(taps_host);
  VkBuffer taps = arena.alloc(50, taps_host);
  VkBuffer dum1 = arena.alloc(1, nullptr);
  VkBuffer dum2 = arena.alloc(2, nullptr);
  if (!arena.ok) {
    arena.freeAll();
    return Status::kDispatchFailed;
  }

  SrpHmsrPush push{};
  push.width = width;
  push.height = height;
  push.pixel_count = n;
  push.scale = 1.0f;
  push.pad[1] = width;
  push.pad[2] = height;

  // Per-op binding tables (11 slots each), mirroring the port's _run order.
  const VkBuffer table[12][11] = {
      {cfa_buffer, gh, gv, dum1, dum1, dum1, dum1, dum1, dum2, dum2, dum2},
      {cfa_buffer, gh, gv, dh, dv, dum1, dum1, dum1, dum2, dum2, dum2},
      {dh, dv, taps, m, dum1, dum1, dum1, dum1, dum2, dum2, dum2},
      {gh, gv, m, g, dum1, dum1, dum1, dum1, dum2, dum2, dum2},
      {cfa_buffer, g, dum1, dum1, dum1, dum1, dum1, dum1, rc, gc, bc},
      {cfa_buffer, g, dum1, dum1, dum1, dum1, r1a, b1a, rc, gc, bc},
      {r1a, b1a, dum1, dum1, dum1, dum1, dum1, dum1, rc, dum2, bc},
      {r1a, b1a, m, dum1, dum1, dum1, r1, b1, rc, gc, bc},
      {r1, g, b1, m, rgm, bgm, dum1, dum1, dum2, dum2, dum2},
      {r1, g, b1, rgm, bgm, r2, b2, g2, dum2, dum2, dum2},
      {r2, b2, g2, m, r3, b3, dum1, dum1, dum2, dum2, dum2},
      {r3, b3, g2, grey_buffer, dum1, dum1, dum1, dum1, dum2, dum2, dum2},
  };
  Status status = Status::kOk;
  for (uint32_t op = 0; op < 12; ++op) {
    push.pad[0] = op;
    VkBuffer buffers[kMaxStorageBuffers];
    for (uint32_t b = 0; b < 11; ++b) buffers[b] = table[op][b];
    status = DispatchStorageCompute(ctx, buffers, 11, &push, sizeof(push),
                                    kDemosaicLocalSize, n, shader_module);
    if (status != Status::kOk) break;
  }
  arena.freeAll();
  return status;
}

Status SrpGradGpu(Context* ctx, uint32_t width, uint32_t height,
                  uint32_t count, uint32_t op, uint32_t exp_bits,
                  VkBuffer in_buffer, VkBuffer out_buffer,
                  VkShaderModule shader_module) {
  if (ctx == nullptr || width == 0 || height == 0 || count == 0 || op > 3 ||
      in_buffer == VK_NULL_HANDLE || out_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint64_t n64 =
      static_cast<uint64_t>(width) * static_cast<uint64_t>(height);
  if (op == 0) {
    if (width < 2 || height < 2 ||
        count != (width - 1) * (height - 1)) {  // (w-1)*(h-1) fits: w,h <= n
      return Status::kInitFailed;
    }
  } else {
    if (n64 != count) return Status::kInitFailed;
  }

  DemoArena arena;
  arena.ctx = ctx;
  arena.device = NativeDevice(ctx);
  if (arena.device == VK_NULL_HANDLE) return Status::kNoDevice;
  VkBuffer dummy = arena.alloc(1, nullptr);
  if (!arena.ok) {
    arena.freeAll();
    return Status::kDispatchFailed;
  }

  SrpHmsrPush push{};
  push.width = width;
  push.height = height;
  push.pixel_count = count;
  push.scale = 1.0f;
  push.pad[0] = op;
  push.pad[1] = width;
  push.pad[2] = height;
  push.pad[3] = exp_bits;
  // Vec2 pair ops read C as dummy and write CV; scalar ops write C.
  VkBuffer buffers[3];
  if (op <= 1) {
    buffers[0] = in_buffer;
    buffers[1] = dummy;
    buffers[2] = out_buffer;
  } else {
    buffers[0] = in_buffer;
    buffers[1] = out_buffer;
    buffers[2] = dummy;
  }
  const Status status =
      DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                             kGradLocalSize, count, shader_module);
  arena.freeAll();
  return status;
}

Status SrpKernelsCovGpu(Context* ctx, uint32_t grad_w, uint32_t grad_h,
                        uint32_t cov_w, uint32_t cov_h, VkBuffer grads_buffer,
                        VkBuffer cov_buffer, VkBuffer kparams_buffer,
                        VkShaderModule shader_module) {
  if (ctx == nullptr || grad_w == 0 || grad_h == 0 || cov_w == 0 ||
      cov_h == 0 || grads_buffer == VK_NULL_HANDLE ||
      cov_buffer == VK_NULL_HANDLE || kparams_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint64_t n64 =
      static_cast<uint64_t>(cov_w) * static_cast<uint64_t>(cov_h);
  if (n64 == 0 || n64 > 0x0FFFFFFFULL) return Status::kInitFailed;
  const uint32_t n = static_cast<uint32_t>(n64);

  SrpHmsrPush push{};
  push.width = cov_w;
  push.height = cov_h;
  push.pixel_count = n;
  push.scale = 1.0f;
  push.pad[1] = grad_w;
  push.pad[2] = grad_h;
  push.pad[3] = cov_w;
  VkBuffer buffers[3] = {grads_buffer, cov_buffer, kparams_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kKernelsLocalSize, n, shader_module);
}

Status SrpDivideGpu(Context* ctx, uint32_t count, VkBuffer num_buffer,
                    VkBuffer den_buffer, VkBuffer out_buffer,
                    VkShaderModule shader_module) {
  if (ctx == nullptr || count == 0 || num_buffer == VK_NULL_HANDLE ||
      den_buffer == VK_NULL_HANDLE || out_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  SrpHmsrPush push{};
  push.pixel_count = count;
  push.scale = 1.0f;
  VkBuffer buffers[3] = {num_buffer, den_buffer, out_buffer};
  return DispatchStorageCompute(ctx, buffers, 3, &push, sizeof(push),
                                kDivideLocalSize, count, shader_module);
}

Status SrpMergeSplatGpu(Context* ctx, uint32_t lr_w, uint32_t lr_h,
                        uint32_t hr_w, uint32_t hr_h, VkBuffer comp_buffer,
                        VkBuffer flow_buffer, VkBuffer cov_buffer,
                        VkBuffer r_buffer, VkBuffer num_buffer,
                        VkBuffer den_buffer, uint32_t tile_size, float scale,
                        uint32_t mode, uint32_t ftx, uint32_t fty,
                        VkShaderModule shader_module, uint32_t ref_kernels) {
  if (ctx == nullptr || lr_w == 0 || lr_h == 0 || hr_w == 0 || hr_h == 0 ||
      tile_size == 0 || scale <= 0.0f || mode > 1 || ftx == 0 || fty == 0 ||
      comp_buffer == VK_NULL_HANDLE || flow_buffer == VK_NULL_HANDLE ||
      cov_buffer == VK_NULL_HANDLE || r_buffer == VK_NULL_HANDLE ||
      num_buffer == VK_NULL_HANDLE || den_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  // The flow grid must cover the frame (the shader indexes it without a
  // bounds check, like the port).
  if (static_cast<uint64_t>(ftx) * tile_size < lr_w ||
      static_cast<uint64_t>(fty) * tile_size < lr_h) {
    return Status::kInitFailed;
  }
  const uint64_t hr64 = static_cast<uint64_t>(hr_w) * hr_h;
  if (hr64 == 0 || hr64 > 0x0FFFFFFFULL) return Status::kInitFailed;
  const uint32_t hr = static_cast<uint32_t>(hr64);

  SrpHmsrPush push{};
  push.width = lr_w;
  push.height = lr_h;
  push.pixel_count = hr;
  push.tile_size = tile_size;
  push.scale = scale;
  push.mode = mode;
  push.pad[1] = ftx;
  push.pad[2] = hr_w;
  push.pad[3] = ref_kernels;  // 1: covariances on the reference grid
  VkBuffer buffers[6] = {comp_buffer, flow_buffer, cov_buffer, r_buffer,
                         num_buffer, den_buffer};
  return DispatchStorageCompute(ctx, buffers, 6, &push, sizeof(push),
                                kMergeSplatLocalSize, hr, shader_module);
}

Status SrpCorrDirectGpu(Context* ctx, uint32_t ref_w, uint32_t tile_size,
                        uint32_t radius, uint32_t ntx, uint32_t tile_offset,
                        uint32_t chunk_tiles, uint32_t flip, VkBuffer ref_buffer,
                        VkBuffer patch_buffer, VkBuffer out_buffer,
                        VkShaderModule shader_module) {
  if (ctx == nullptr || ref_w == 0 || tile_size == 0 || ntx == 0 ||
      chunk_tiles == 0 || ref_buffer == VK_NULL_HANDLE ||
      patch_buffer == VK_NULL_HANDLE || out_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint32_t c = 2 * radius + 1;
  SrpHmsrPush push{};
  push.scale = 1.0f;
  push.mode = flip;
  push.pixel_count = chunk_tiles * c * c;
  push.pad[0] = ref_w; push.pad[1] = tile_size; push.pad[2] = radius;
  push.pad[3] = ntx; push.pad[4] = tile_offset;
  VkBuffer buffers[3] = {ref_buffer, patch_buffer, out_buffer};
  uint64_t groups = (static_cast<uint64_t>(push.pixel_count) + 255) / 256;
  if (groups > 65535) groups = 65535;
  return SrpDispatchGroups(ctx, buffers, 3, &push, sizeof(push), 256,
                           static_cast<uint32_t>(groups), shader_module);
}

namespace {
// Grid-stride dispatch: kernelnet.comp loops over p.pixelCount, so the group
// count can stay under the 65535 x-limit for any sensor size.
Status KnDispatch(Context* ctx, VkBuffer a, VkBuffer b, VkBuffer w,
                  SrpHmsrPush* push, VkShaderModule mod) {
  VkBuffer buffers[3] = {a, b, w};
  uint64_t groups = (static_cast<uint64_t>(push->pixel_count) + 255) / 256;
  if (groups > 65535) groups = 65535;
  return SrpDispatchGroups(ctx, buffers, 3, push, sizeof(*push), 256,
                           static_cast<uint32_t>(groups), mod);
}
}  // namespace

Status SrpKernelNetCovGpu(Context* ctx, uint32_t raw_w, uint32_t raw_h,
                          float sigma, float sigma_scale, float sigma_cap,
                          uint32_t crop_x, uint32_t crop_y, uint32_t cov_w,
                          uint32_t cov_h, VkBuffer raw_buffer,
                          VkBuffer weights_buffer, VkBuffer luma_buffer,
                          VkBuffer act0_buffer, VkBuffer act1_buffer,
                          VkBuffer map_buffer, VkBuffer cov_buffer,
                          VkShaderModule shader_module, uint32_t flip_x,
                          uint32_t flip_y) {
  if (ctx == nullptr || raw_w < 4 || raw_h < 4 || cov_w == 0 || cov_h == 0 ||
      raw_buffer == VK_NULL_HANDLE || weights_buffer == VK_NULL_HANDLE ||
      luma_buffer == VK_NULL_HANDLE || act0_buffer == VK_NULL_HANDLE ||
      act1_buffer == VK_NULL_HANDLE || map_buffer == VK_NULL_HANDLE ||
      cov_buffer == VK_NULL_HANDLE || shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint32_t lw = raw_w / 2, lh = raw_h / 2;           // luma grid
  const uint32_t mw = (lw - 1) / 2 + 1, mh = (lh - 1) / 2 + 1;  // stride 2
  SrpHmsrPush push{};
  push.scale = 1.0f;
  // 0 PREP
  push.mode = 0;
  push.pixel_count = lw * lh;
  push.pad[0] = raw_w; push.pad[1] = raw_h; push.pad[2] = lw; push.pad[3] = lh;
  push.pad[4] = flip_x; push.pad[5] = flip_y;
  Status st = KnDispatch(ctx, raw_buffer, luma_buffer, weights_buffer, &push, shader_module);
  if (st != Status::kOk) { fprintf(stderr, "kernelnet: prep failed %d\n", (int)st); return st; }
  // Layer table from the .param: (cin, cout, k, stride, relu); weights
  // packed [w0 b0 w1 b1 ... w5 b5] in that order.
  struct L { uint32_t cin, cout, k, stride, relu; };
  const L layers[6] = {{2, 16, 3, 2, 1}, {16, 16, 3, 1, 1}, {16, 16, 3, 1, 1},
                       {16, 16, 3, 1, 1}, {16, 16, 3, 1, 1}, {16, 3, 1, 1, 0}};
  uint32_t off = 0;
  VkBuffer in = luma_buffer;
  uint32_t inW = lw, inH = lh;
  VkBuffer acts[2] = {act0_buffer, act1_buffer};
  for (int i = 0; i < 6; ++i) {
    const L& l = layers[i];
    const uint32_t wOff = off;
    off += l.cout * l.cin * l.k * l.k;
    const uint32_t bOff = off;
    off += l.cout;
    SrpHmsrPush lp{};
    lp.scale = 1.0f;
    lp.mode = (i == 5) ? 2 : 1;
    lp.alpha = sigma;
    lp.pixel_count = l.cout * mw * mh;
    lp.pad[0] = l.cin; lp.pad[1] = l.cout; lp.pad[2] = inW; lp.pad[3] = inH;
    lp.pad[4] = mw; lp.pad[5] = mh; lp.pad[6] = l.stride; lp.pad[7] = l.k;
    lp.pad[8] = l.relu; lp.pad[9] = wOff; lp.pad[10] = bOff;
    lp.pad[11] = (i == 0) ? 1 : 0;
    VkBuffer out = (i == 5) ? map_buffer : acts[i % 2];
    st = KnDispatch(ctx, in, out, weights_buffer, &lp, shader_module);
    if (st != Status::kOk) { fprintf(stderr, "kernelnet: layer %d failed %d\n", i, (int)st); return st; }
    in = out; inW = mw; inH = mh;
  }
  // 3 COV
  SrpHmsrPush cp{};
  cp.scale = 1.0f;
  cp.mode = 3;
  cp.alpha = sigma_scale;
  cp.beta = sigma_cap;
  cp.pixel_count = cov_w * cov_h;
  cp.pad[0] = mw; cp.pad[1] = mh; cp.pad[2] = raw_w; cp.pad[3] = raw_h;
  cp.pad[4] = crop_x; cp.pad[5] = crop_y; cp.pad[6] = cov_w; cp.pad[7] = cov_h;
  cp.pad[8] = flip_x; cp.pad[9] = flip_y;
  return KnDispatch(ctx, map_buffer, cov_buffer, weights_buffer, &cp, shader_module);
}

namespace {

// One robustness.comp dispatch over the fixed 7-slot binding order
// (A,B,C,D,E,S,LUT). `ex[0]` selects the op, `ex[3..12]` carry the
// op's pad extras (W/H ride pad[1..2); unused slots take `dummy.
Status RobDispatch(Context* ctx, const uint32_t ex[13], uint32_t W,
                   uint32_t H, uint32_t grid, VkBuffer a, VkBuffer b,
                   VkBuffer c, VkBuffer d, VkBuffer e, VkBuffer s,
                   VkBuffer lut, VkShaderModule mod) {
  SrpHmsrPush push{};
  push.pixel_count = grid;
  push.scale = 1.0f;
  push.pad[0] = ex[0];
  push.pad[1] = W;
  push.pad[2] = H;
  for (int k = 3; k < 13; ++k) push.pad[k] = ex[k];
  VkBuffer buffers[7] = {a, b, c, d, e, s, lut};
  return DispatchStorageCompute(ctx, buffers, 7, &push, sizeof(push),
                                kRobustLocalSize, grid, mod);
}

}  // namespace

Status SrpRobustInitGpu(Context* ctx, uint32_t width, uint32_t height,
                        VkBuffer ref_buffer, VkBuffer means_buffer,
                        VkBuffer vars_buffer, VkShaderModule shader_module) {
  if (ctx == nullptr || width < 2 || height < 2 ||
      ref_buffer == VK_NULL_HANDLE || means_buffer == VK_NULL_HANDLE ||
      vars_buffer == VK_NULL_HANDLE || shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint32_t gw = width / 2, gh = height / 2;
  const uint64_t n64 = static_cast<uint64_t>(gw) * gh;
  if (n64 == 0 || n64 > 0x0FFFFFFFULL) return Status::kInitFailed;
  const uint32_t n = static_cast<uint32_t>(n64);

  DemoArena arena;
  arena.ctx = ctx;
  arena.device = NativeDevice(ctx);
  if (arena.device == VK_NULL_HANDLE) return Status::kNoDevice;
  VkBuffer guide = arena.alloc(3 * n, nullptr);
  VkBuffer dummy = arena.alloc(1, nullptr);
  if (!arena.ok) {
    arena.freeAll();
    return Status::kDispatchFailed;
  }

  uint32_t ex[13] = {};
  Status status;
  // op 0 guide: A=frame -> D=guide(3n).
  ex[0] = 0;
  ex[12] = width;
  status = RobDispatch(ctx, ex, gw, gh, n, ref_buffer, dummy, dummy, guide,
                       dummy, dummy, dummy, shader_module);
  if (status != Status::kOk) {
    arena.freeAll();
    return status;
  }
  // op 1 stats: A=guide -> D=means, E=vars.
  ex[0] = 1;
  ex[3] = 3;
  ex[12] = 0;
  status = RobDispatch(ctx, ex, gw, gh, 3 * n, guide, dummy, dummy,
                       means_buffer, vars_buffer, dummy, dummy,
                       shader_module);
  arena.freeAll();
  return status;
}

Status SrpRobustComputeGpu(Context* ctx, uint32_t width, uint32_t height,
                           VkBuffer comp_buffer, VkBuffer means_buffer,
                           VkBuffer vars_buffer, VkBuffer flow_buffer,
                           VkBuffer lut_buffer, VkBuffer s_buffer,
                           VkBuffer out_buffer, uint32_t tile_size,
                           uint32_t lut_n, uint32_t noise_correction,
                           uint32_t t_bits, uint32_t ftx, uint32_t fty,
                           VkShaderModule shader_module) {
  if (ctx == nullptr || width < 2 || height < 2 || tile_size < 2 ||
      lut_n == 0 || ftx == 0 || fty == 0 || comp_buffer == VK_NULL_HANDLE ||
      means_buffer == VK_NULL_HANDLE || vars_buffer == VK_NULL_HANDLE ||
      flow_buffer == VK_NULL_HANDLE || lut_buffer == VK_NULL_HANDLE ||
      s_buffer == VK_NULL_HANDLE || out_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint32_t gw = width / 2, gh = height / 2;
  const uint64_t n64 = static_cast<uint64_t>(gw) * gh;
  if (n64 == 0 || n64 > 0x0FFFFFFFULL) return Status::kInitFailed;
  const uint32_t n = static_cast<uint32_t>(n64);
  const uint32_t tile_half = tile_size / 2;

  DemoArena arena;
  arena.ctx = ctx;
  arena.device = NativeDevice(ctx);
  if (arena.device == VK_NULL_HANDLE) return Status::kNoDevice;
  VkBuffer guide = arena.alloc(3 * n, nullptr);
  VkBuffer cmeans = arena.alloc(3 * n, nullptr);
  VkBuffer cvars = arena.alloc(3 * n, nullptr);
  VkBuffer warped = arena.alloc(3 * n, nullptr);
  VkBuffer d = arena.alloc(n, nullptr);
  VkBuffer sg = arena.alloc(n, nullptr);
  VkBuffer r = arena.alloc(n, nullptr);
  VkBuffer dummy = arena.alloc(1, nullptr);
  if (!arena.ok) {
    arena.freeAll();
    return Status::kDispatchFailed;
  }

  uint32_t ex[13] = {};
  Status status = Status::kOk;
  // op 0 guide on the compared frame.
  ex[0] = 0;
  ex[12] = width;
  status = RobDispatch(ctx, ex, gw, gh, n, comp_buffer, dummy, dummy, guide,
                       dummy, dummy, dummy, shader_module);
  // op 1 stats of the compared guide.
  if (status == Status::kOk) {
    ex[0] = 1;
    ex[3] = 3;
    ex[12] = 0;
    status = RobDispatch(ctx, ex, gw, gh, 3 * n, guide, dummy, dummy, cmeans,
                         cvars, dummy, dummy, shader_module);
  }
  // op 2 warp vs the raw-basis flow.
  if (status == Status::kOk) {
    ex[0] = 2;
    ex[3] = 3;
    ex[4] = tile_half;
    ex[11] = ftx;
    status = RobDispatch(ctx, ex, gw, gh, n, cmeans, flow_buffer, dummy,
                         warped, dummy, dummy, dummy, shader_module);
  }
  // op 3 d/sigma (ref means vs warped means, ref vars, LUT).
  if (status == Status::kOk) {
    ex[0] = 3;
    ex[3] = 3;
    ex[4] = 0;
    ex[5] = lut_n;
    ex[10] = noise_correction;
    ex[11] = 0;
    status = RobDispatch(ctx, ex, gw, gh, n, means_buffer, warped,
                         vars_buffer, d, sg, dummy, lut_buffer,
                         shader_module);
  }
  // op 4 threshold (S gathered per tileHalf block).
  if (status == Status::kOk) {
    ex[0] = 4;
    ex[3] = 0;
    ex[4] = tile_half;
    ex[5] = 0;
    ex[6] = t_bits;
    ex[10] = 0;
    ex[11] = ftx;
    status = RobDispatch(ctx, ex, gw, gh, n, d, sg, dummy, r, dummy, s_buffer,
                         dummy, shader_module);
  }
  // op 5 local min into the caller buffer.
  if (status == Status::kOk) {
    ex[0] = 5;
    ex[4] = 0;
    ex[6] = 0;
    ex[11] = 0;
    status = RobDispatch(ctx, ex, gw, gh, n, r, dummy, dummy, out_buffer,
                         dummy, dummy, dummy, shader_module);
  }
  arena.freeAll();
  return status;
}

Status SrpSmapGpu(Context* ctx, uint32_t ntx, uint32_t nty,
                  VkBuffer flow_buffer, VkBuffer s_buffer, uint32_t mt_bits,
                  uint32_t s1_bits, uint32_t s2_bits,
                  VkShaderModule shader_module) {
  if (ctx == nullptr || ntx == 0 || nty == 0 ||
      flow_buffer == VK_NULL_HANDLE || s_buffer == VK_NULL_HANDLE ||
      shader_module == VK_NULL_HANDLE) {
    return Status::kInitFailed;
  }
  const uint64_t n64 = static_cast<uint64_t>(ntx) * nty;
  if (n64 == 0 || n64 > 0x0FFFFFFFULL) return Status::kInitFailed;
  const uint32_t n = static_cast<uint32_t>(n64);

  DemoArena arena;
  arena.ctx = ctx;
  arena.device = NativeDevice(ctx);
  if (arena.device == VK_NULL_HANDLE) return Status::kNoDevice;
  VkBuffer dummy = arena.alloc(1, nullptr);
  if (!arena.ok) {
    arena.freeAll();
    return Status::kDispatchFailed;
  }
  uint32_t ex[13] = {};
  ex[0] = 6;
  ex[7] = mt_bits;
  ex[8] = s1_bits;
  ex[9] = s2_bits;
  const Status status =
      RobDispatch(ctx, ex, ntx, nty, n, flow_buffer, dummy, dummy, s_buffer,
                  dummy, dummy, dummy, shader_module);
  arena.freeAll();
  return status;
}

}  // namespace srp
