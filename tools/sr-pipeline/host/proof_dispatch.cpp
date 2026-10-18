// First on-device dispatch run of the sr-pipeline native backend.
//
// Usage: srp_proof_dispatch <merge_accumulate.spv>
// Creates a context, stages two caller-owned host-visible buffers, runs
// SrpMergeAccumulateGpu twice (gain=2 then gain=1, proving in-place
// accumulation across dispatches), fence-waits, reads back, and compares
// against a CPU oracle. Prints device info + maxabs and exits nonzero on
// any mismatch. The element count is deliberately not a multiple of the
// shader's 64-wide workgroup to exercise the tail guard.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include <vulkan/vulkan.h>

#include "srp_context.h"
#include "srp_pipeline.h"

namespace {

bool FindMemoryType(VkPhysicalDevice physical, uint32_t type_bits,
                    VkMemoryPropertyFlags flags, uint32_t* out_index) {
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

bool MakeBuffer(VkDevice device, VkPhysicalDevice physical, VkDeviceSize bytes,
                const void* src, VkBuffer* out_buffer,
                VkDeviceMemory* out_memory) {
  VkBufferCreateInfo buffer_info{};
  buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
  buffer_info.size = bytes;
  buffer_info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
  buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  if (vkCreateBuffer(device, &buffer_info, nullptr, out_buffer) != VK_SUCCESS) {
    return false;
  }
  VkMemoryRequirements req{};
  vkGetBufferMemoryRequirements(device, *out_buffer, &req);
  uint32_t index = 0;
  if (!FindMemoryType(physical, req.memoryTypeBits,
                      VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                      &index)) {
    vkDestroyBuffer(device, *out_buffer, nullptr);
    return false;
  }
  VkMemoryAllocateInfo alloc{};
  alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
  alloc.allocationSize = req.size;
  alloc.memoryTypeIndex = index;
  if (vkAllocateMemory(device, &alloc, nullptr, out_memory) != VK_SUCCESS) {
    vkDestroyBuffer(device, *out_buffer, nullptr);
    return false;
  }
  if (vkBindBufferMemory(device, *out_buffer, *out_memory, 0) != VK_SUCCESS) {
    vkFreeMemory(device, *out_memory, nullptr);
    vkDestroyBuffer(device, *out_buffer, nullptr);
    return false;
  }
  if (src != nullptr) {
    void* mapped = nullptr;
    if (vkMapMemory(device, *out_memory, 0, bytes, 0, &mapped) != VK_SUCCESS) {
      return false;
    }
    std::memcpy(mapped, src, static_cast<size_t>(bytes));
    vkUnmapMemory(device, *out_memory);
  }
  return true;
}

std::vector<uint8_t> ReadFile(const char* path) {
  std::vector<uint8_t> bytes;
  if (FILE* f = std::fopen(path, "rb")) {
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (n > 0) {
      bytes.resize(static_cast<size_t>(n));
      bytes.resize(std::fread(bytes.data(), 1, bytes.size(), f));
    }
    std::fclose(f);
  }
  return bytes;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 2) {
    std::fprintf(stderr, "usage: %s <merge_accumulate.spv>\n", argv[0]);
    return 2;
  }

  srp::Context* ctx = nullptr;
  const srp::Status create_status = srp::Create(&ctx);
  if (create_status != srp::Status::kOk || ctx == nullptr) {
    std::fprintf(stderr, "FAIL: srp::Create failed status=%d\n",
                 static_cast<int>(create_status));
    return 1;
  }
  std::printf("device: %s\n", srp::DeviceInfo(ctx).c_str());

  auto* device = static_cast<VkDevice>(srp::NativeDevice(ctx));
  auto* physical = static_cast<VkPhysicalDevice>(srp::NativePhysicalDevice(ctx));

  constexpr uint32_t kCount = 4096 + 13;  // tail guard: not a multiple of 64
  constexpr uint32_t kWidth = 64, kHeight = 65;  // documentation only
  const VkDeviceSize bytes = kCount * sizeof(float);
  std::vector<float> in(kCount), acc(kCount, 0.0f);
  for (uint32_t i = 0; i < kCount; ++i) {
    in[i] = static_cast<float>(i % 97) * 0.25f - 3.0f;
  }

  VkBuffer in_buf = VK_NULL_HANDLE, acc_buf = VK_NULL_HANDLE;
  VkDeviceMemory in_mem = VK_NULL_HANDLE, acc_mem = VK_NULL_HANDLE;
  if (!MakeBuffer(device, physical, bytes, in.data(), &in_buf, &in_mem) ||
      !MakeBuffer(device, physical, bytes, acc.data(), &acc_buf, &acc_mem)) {
    std::fprintf(stderr, "FAIL: staging buffer create failed\n");
    srp::Destroy(ctx);
    return 1;
  }

  const std::vector<uint8_t> spirv = ReadFile(argv[1]);
  if (spirv.empty() || spirv.size() % 4 != 0) {
    std::fprintf(stderr, "FAIL: cannot read spv %s\n", argv[1]);
    srp::Destroy(ctx);
    return 1;
  }
  VkShaderModuleCreateInfo module_info{};
  module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
  module_info.codeSize = spirv.size();
  module_info.pCode = reinterpret_cast<const uint32_t*>(spirv.data());
  VkShaderModule module = VK_NULL_HANDLE;
  if (vkCreateShaderModule(device, &module_info, nullptr, &module) != VK_SUCCESS) {
    std::fprintf(stderr, "FAIL: shader module create failed\n");
    srp::Destroy(ctx);
    return 1;
  }

  srp::SrpMergeParams params{};
  params.count = kCount;
  params.width = kWidth;
  params.height = kHeight;
  params.gain = 2.0f;
  int rc = 1;
  if (srp::SrpMergeAccumulateGpu(ctx, &params, in_buf, acc_buf, module) !=
      srp::Status::kOk) {
    std::fprintf(stderr, "FAIL: first dispatch failed\n");
  } else {
    params.gain = 1.0f;
    if (srp::SrpMergeAccumulateGpu(ctx, &params, in_buf, acc_buf, module) !=
        srp::Status::kOk) {
      std::fprintf(stderr, "FAIL: second dispatch failed\n");
    } else {
      void* mapped = nullptr;
      if (vkMapMemory(device, acc_mem, 0, bytes, 0, &mapped) != VK_SUCCESS) {
        std::fprintf(stderr, "FAIL: readback map failed\n");
      } else {
        std::memcpy(acc.data(), mapped, static_cast<size_t>(bytes));
        vkUnmapMemory(device, acc_mem);
        float maxabs = 0.0f;
        for (uint32_t i = 0; i < kCount; ++i) {
          // CPU oracle: acc = 0 + 2*in + 1*in, same order as the shader.
          const float expect = 2.0f * in[i] + 1.0f * in[i];
          maxabs = std::fmax(maxabs, std::fabs(acc[i] - expect));
        }
        std::printf("count=%u groups=%u maxabs_vs_cpu=%.3g\n", kCount,
                    (kCount + srp::kMergeLocalSize - 1) / srp::kMergeLocalSize,
                    static_cast<double>(maxabs));
        if (maxabs <= 1e-6f) {
          std::printf("PASS: on-device merge-accumulate matches CPU\n");
          rc = 0;
        } else {
          std::fprintf(stderr, "FAIL: maxabs %.3g exceeds 1e-6\n",
                       static_cast<double>(maxabs));
        }
      }
    }
  }

  vkDestroyShaderModule(device, module, nullptr);
  vkDestroyBuffer(device, in_buf, nullptr);
  vkFreeMemory(device, in_mem, nullptr);
  vkDestroyBuffer(device, acc_buf, nullptr);
  vkFreeMemory(device, acc_mem, nullptr);
  srp::Destroy(ctx);
  return rc;
}
