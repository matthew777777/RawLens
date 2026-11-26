// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <cstdint>
#include <string>

#include <vulkan/vulkan.h>

// Fresh Phase-1 scaffold for the sr-pipeline Vulkan backend.
// Opaque context: no Vulkan types leak into the header, so NDK and
// host callers only depend on the C++ standard library here.
namespace srp {

enum class Status : int32_t {
  kOk = 0,
  kNoVulkan = 1,
  kNoDevice = 2,
  kInitFailed = 3,
  kDispatchFailed = 4,
};

struct Context;

// Creates a Vulkan 1.2 instance, picks a compute-capable physical device,
// and creates a logical device with one compute queue.
// Returns kNoDevice when no physical device has a compute queue.
// The caller owns *out_ctx and must release it with Destroy.
Status Create(Context** out_ctx);

// Releases a context created by Create; no-op on nullptr.
void Destroy(Context* ctx);

// Human-readable device summary: name, subgroup size, compute family.
// Returns "(no device)" for nullptr or a context without a device.
std::string DeviceInfo(const Context* ctx);

// Native handles for sibling translation units (pipeline, JNI), typed as
// the Vulkan handles they are: on 64-bit these are pointer types, on
// 32-bit (armeabi-v7a) non-dispatchable handles are uint64_t, so void*
// type-erasure plus static_cast does NOT compile there. Both are
// VK_NULL_HANDLE for a null context or a context without a device.
// ComputeQueueFamily is 0 in the same cases.
VkDevice NativeDevice(const Context* ctx);
VkPhysicalDevice NativePhysicalDevice(const Context* ctx);
uint32_t ComputeQueueFamily(const Context* ctx);

// Trivial fence-waited dispatch stub: signals/watches a fence and returns
// a status code. No real work is recorded yet (Phase 1 placeholder).
Status DispatchPassthrough(Context* ctx);

// Resident device buffers held across JNI calls (merge num/den): create
// returns a positive handle (0 on failure) to a zero-filled buffer;
// download copies it to host (with the MoltenVK invalidate the pooled
// downloads need); free returns it to the pool (kInitFailed on unknown
// handles); residentBuffer validates and returns the VkBuffer for
// entries (which bind it like any other buffer). Held buffers are never
// evicted (eviction only scans the free list).
int64_t SrpCreateResident(Context* ctx, size_t nfloats);
Status SrpFreeResident(Context* ctx, int64_t handle);
Status SrpDownloadResident(Context* ctx, int64_t handle, float* out,
                           size_t count);
// Copies `count` host floats into a resident (level-static uploads: the
// entry fills pre-created residents once per run). Same validation as
// the download path; flush mirrors FftChain::flushWritten (unconditional:
// spec no-op for coherent memory, real sync where the driver emulates
// coherence).
Status SrpUploadResident(Context* ctx, int64_t handle, const float* in,
                         size_t count);
Status SrpResidentBuffer(Context* ctx, int64_t handle, size_t min_floats,
                         VkBuffer* out);

// Host-visible + coherent memory type, HOST_CACHED preferred (srp_context.cpp).
bool SrpFindHostMemory(VkPhysicalDevice physical, uint32_t type_bits, uint32_t* out_index);

// Context-lifetime stage scratch for `slot` (uninitialised, exactly `nfloats`;
// reallocated when the size changes). Serial use, like every dispatch.
VkBuffer SrpScratch(Context* ctx, uint32_t slot, size_t nfloats);

// Batched submission (a few slots): between Begin and Submit every
// SrpDispatchGroups call is recorded into one command buffer (a memory barrier
// before each dispatch) instead of being submitted and waited for. Submit
// returns at once; BatchWait(slot), or a new Begin on that slot, waits. The
// caller must not touch or free buffers a pending batch uses.
constexpr uint32_t kBatchSlots = 4;
Status SrpBatchBegin(Context* ctx, uint32_t slot);
Status SrpBatchSubmit(Context* ctx);
Status SrpBatchWait(Context* ctx, uint32_t slot);
// Open command buffer (null outside Begin/Submit) and a set from its pool.
VkCommandBuffer SrpBatchCommand(Context* ctx);
Status SrpBatchSet(Context* ctx, VkDescriptorSetLayout layout, VkDescriptorSet* set);

int64_t SrpCreateHostResident(Context* ctx, size_t nfloats);
const float* SrpMapResident(Context* ctx, int64_t handle, size_t nfloats);
void SrpUnmapResident(Context* ctx, int64_t handle);

// Float images (texture-unit reads; negative handles). Binding kinds pack two
// bits per binding: 0 storage buffer, 1 sampled image, 2 storage image.
int64_t SrpCreateImage(Context* ctx, uint32_t w, uint32_t h, uint32_t channels);
VkImageView SrpImageView(Context* ctx, int64_t handle);
VkSampler SrpSampler(Context* ctx);
void SrpFreeImage(Context* ctx, int64_t handle);
inline VkDescriptorType SrpBindType(uint32_t kinds, uint32_t i) {
  const uint32_t k = (kinds >> (2 * i)) & 3u;
  return k == 0 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
      : k == 1 ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
}

}  // namespace srp
