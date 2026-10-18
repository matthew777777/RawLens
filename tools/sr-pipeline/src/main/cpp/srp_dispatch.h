#pragma once

#include <cstddef>
#include <cstdint>

#include <vulkan/vulkan.h>

#include "srp_context.h"

// Internal dispatch core shared by the srp_pipeline entries and the srp_fft
// resident chains (NOT public API; no stability promise).
//
// Binds `buffers[0..nbuffers)` as storage buffers, uploads `push_size` bytes
// as push constants, dispatches exactly `groups` groups of `local_size`,
// and fence-waits completion. All transient Vulkan objects are
// created/destroyed inside; context, buffers, and module stay caller-owned.
namespace srp {

Status SrpDispatchGroups(Context* ctx, VkBuffer* buffers, uint32_t nbuffers,
                         const void* push, size_t push_size,
                         uint32_t local_size, uint32_t groups,
                         VkShaderModule shader_module);

}  // namespace srp
