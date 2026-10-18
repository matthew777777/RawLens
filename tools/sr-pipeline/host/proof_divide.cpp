// On-device proof for the elementwise divide (shaders/divide.comp,
// utils.divide: num /= den, IEEE semantics).
//
// Usage: srp_proof_divide <divide.spv> <fixtures_dir> <gpuref_dir>
// Runs SrpDivideGpu on-device over parity/fixtures/divide_in_num +
// divide_in_den (16x16x3, the gen_gpu_reference stage_divide input) and
// compares against parity/gpu_reference/gpu_divide_out_ratio.npy.
// Same shader, compiler, and device class as the port, so the gate is
// bit-exact; any deviation is our bug. Also prints the drift against
// parity/gpu_reference/cpu_divide_out_ratio.npy (1-ulp class, info only).
// Prints numbers; exits nonzero on any breach of the bit-exact gate.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <vulkan/vulkan.h>

#include "npy.h"
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
                const float* src, VkBuffer* out_buffer,
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
  void* mapped = nullptr;
  if (vkMapMemory(device, *out_memory, 0, bytes, 0, &mapped) != VK_SUCCESS) {
    return false;
  }
  if (src != nullptr) {
    std::memcpy(mapped, src, static_cast<size_t>(bytes));
  } else {
    std::memset(mapped, 0, static_cast<size_t>(bytes));
  }
  vkUnmapMemory(device, *out_memory);
  return true;
}

void FreeBuffer(VkDevice device, VkBuffer buffer, VkDeviceMemory memory) {
  if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(device, buffer, nullptr);
  if (memory != VK_NULL_HANDLE) vkFreeMemory(device, memory, nullptr);
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

float MaxAbs(const std::vector<float>& got, const std::vector<float>& want) {
  float m = 0.0f;
  const size_t n = got.size() < want.size() ? got.size() : want.size();
  for (size_t i = 0; i < n; ++i) {
    m = std::fmax(m, std::fabs(got[i] - want[i]));
  }
  return m;
}

bool Load(const std::string& dir, const char* name, srp_proof::NpyArray* out) {
  return srp_proof::LoadNpy((dir + "/" + name).c_str(), out);
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 4) {
    std::fprintf(stderr, "usage: %s <divide.spv> <fixtures_dir> <gpuref_dir>\n",
                 argv[0]);
    return 2;
  }
  const std::string fix_dir = argv[2];
  const std::string gpu_dir = argv[3];

  srp::Context* ctx = nullptr;
  if (srp::Create(&ctx) != srp::Status::kOk || ctx == nullptr) {
    std::fprintf(stderr, "FAIL: srp::Create failed\n");
    return 1;
  }
  std::printf("device: %s\n", srp::DeviceInfo(ctx).c_str());
  auto* device = static_cast<VkDevice>(srp::NativeDevice(ctx));
  auto* physical =
      static_cast<VkPhysicalDevice>(srp::NativePhysicalDevice(ctx));

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

  int failures = 0;
  srp_proof::NpyArray num, den, want_gpu, want_cpu;
  if (!Load(fix_dir, "divide_in_num.npy", &num) ||
      !Load(fix_dir, "divide_in_den.npy", &den) ||
      !Load(gpu_dir, "gpu_divide_out_ratio.npy", &want_gpu) ||
      !Load(gpu_dir, "cpu_divide_out_ratio.npy", &want_cpu)) {
    std::fprintf(stderr, "FAIL: divide refs missing\n");
    failures = 4;
  } else {
    const size_t n = num.data.size();
    std::vector<float> got;
    VkBuffer num_buf = VK_NULL_HANDLE, den_buf = VK_NULL_HANDLE,
             out_buf = VK_NULL_HANDLE;
    VkDeviceMemory num_mem = VK_NULL_HANDLE, den_mem = VK_NULL_HANDLE,
                   out_mem = VK_NULL_HANDLE;
    bool ok = false;
    if (n == den.data.size() && n > 0 &&
        MakeBuffer(device, physical, n * sizeof(float), num.data.data(),
                   &num_buf, &num_mem) &&
        MakeBuffer(device, physical, n * sizeof(float), den.data.data(),
                   &den_buf, &den_mem) &&
        MakeBuffer(device, physical, n * sizeof(float), nullptr, &out_buf,
                   &out_mem) &&
        srp::SrpDivideGpu(ctx, static_cast<uint32_t>(n), num_buf, den_buf,
                          out_buf, module) == srp::Status::kOk) {
      void* mapped = nullptr;
      if (vkMapMemory(device, out_mem, 0, n * sizeof(float), 0, &mapped) ==
          VK_SUCCESS) {
        got.assign(static_cast<const float*>(mapped),
                   static_cast<const float*>(mapped) + n);
        vkUnmapMemory(device, out_mem);
        ok = true;
      }
    }
    FreeBuffer(device, num_buf, num_mem);
    FreeBuffer(device, den_buf, den_mem);
    FreeBuffer(device, out_buf, out_mem);
    if (!ok || got.size() != want_gpu.data.size()) {
      std::fprintf(stderr, "FAIL divide: dispatch failed\n");
      failures = 1;
    } else {
      const float d_gpu = MaxAbs(got, want_gpu.data);
      const bool pass_gpu = d_gpu == 0.0f;
      std::printf("divide_fixture_vs_port_gpu: n=%zu maxabs=%.6g (bit-exact) %s\n",
                  n, static_cast<double>(d_gpu), pass_gpu ? "PASS" : "FAIL");
      if (!pass_gpu) ++failures;
      const float d_cpu = MaxAbs(got, want_cpu.data);
      std::printf("divide_fixture_vs_cpu: n=%zu maxabs=%.6g (info only)\n",
                  n, static_cast<double>(d_cpu));
    }
  }

  vkDestroyShaderModule(device, module, nullptr);
  srp::Destroy(ctx);
  return failures == 0 ? 0 : 1;
}
