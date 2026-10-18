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

}  // namespace srp
