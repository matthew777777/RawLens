// On-device proof for the small-kernel gradient + preprocessing stage
// (shaders/grad.comp ops 0-3).
//
// Usage: srp_proof_grad <grad.spv> <fixtures_dir> <gpuref_dir>
// Runs SrpGradGpu on-device over parity/fixtures/grad_in_grey (64x64,
// the gen_gpu_reference stage_grad input) for all four ops and compares
// against the port's recorded GPU bytes:
//   op 0 kerngrad  vs gpu_grad_out_kerngrad (63x63x2 pairs)
//   op 1 sobel     vs gpu_grad_out_sobel_gx/gy (split pair lanes)
//   op 2 sqrt      vs gpu_grad_out_sqrt
//   op 3 gamma     vs gpu_grad_out_gamma (exp bits = float32(1/2.2))
// Same shader, compiler, and device class as the port, so every op gates
// bit-exact; any deviation is our bug. Prints numbers; exits nonzero on
// any breach.

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

// Runs one grad op; vec op (0/1) reads back count*2 floats, scalar op
// (2/3) count floats. Returns false on any setup/dispatch failure.
bool RunGrad(srp::Context* ctx, VkShaderModule module, uint32_t width,
             uint32_t height, uint32_t count, uint32_t op, uint32_t exp_bits,
             const std::vector<float>& in, std::vector<float>* got) {
  auto* device = static_cast<VkDevice>(srp::NativeDevice(ctx));
  auto* physical =
      static_cast<VkPhysicalDevice>(srp::NativePhysicalDevice(ctx));
  const size_t out_n =
      (op <= 1) ? static_cast<size_t>(count) * 2 : count;
  VkBuffer in_buf = VK_NULL_HANDLE, out_buf = VK_NULL_HANDLE;
  VkDeviceMemory in_mem = VK_NULL_HANDLE, out_mem = VK_NULL_HANDLE;
  bool ok = false;
  if (MakeBuffer(device, physical, in.size() * sizeof(float), in.data(),
                 &in_buf, &in_mem) &&
      MakeBuffer(device, physical, out_n * sizeof(float), nullptr, &out_buf,
                 &out_mem) &&
      srp::SrpGradGpu(ctx, width, height, count, op, exp_bits, in_buf,
                      out_buf, module) == srp::Status::kOk) {
    void* mapped = nullptr;
    if (vkMapMemory(device, out_mem, 0, out_n * sizeof(float), 0, &mapped) ==
        VK_SUCCESS) {
      got->assign(static_cast<const float*>(mapped),
                  static_cast<const float*>(mapped) + out_n);
      vkUnmapMemory(device, out_mem);
      ok = true;
    }
  }
  FreeBuffer(device, in_buf, in_mem);
  FreeBuffer(device, out_buf, out_mem);
  return ok;
}

float MaxAbs(const std::vector<float>& got, const std::vector<float>& want) {
  float m = 0.0f;
  const size_t n = got.size() < want.size() ? got.size() : want.size();
  for (size_t i = 0; i < n; ++i) {
    m = std::fmax(m, std::fabs(got[i] - want[i]));
  }
  return m;
}

bool CheckExact(const char* name, const std::vector<float>& got,
                const std::vector<float>& want) {
  if (got.size() != want.size()) {
    std::fprintf(stderr, "FAIL %s: size %zu vs %zu\n", name, got.size(),
                 want.size());
    return false;
  }
  const float m = MaxAbs(got, want);
  const bool pass = m == 0.0f;
  std::printf("%s: n=%zu maxabs=%.6g (bit-exact) %s\n", name, got.size(),
              static_cast<double>(m), pass ? "PASS" : "FAIL");
  return pass;
}

bool Load(const std::string& dir, const char* name, srp_proof::NpyArray* out) {
  return srp_proof::LoadNpy((dir + "/" + name).c_str(), out);
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 4) {
    std::fprintf(stderr, "usage: %s <grad.spv> <fixtures_dir> <gpuref_dir>\n",
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
  srp_proof::NpyArray grey, w_kerngrad, w_gx, w_gy, w_sqrt, w_gamma;
  if (!Load(fix_dir, "grad_in_grey.npy", &grey) ||
      !Load(gpu_dir, "gpu_grad_out_kerngrad.npy", &w_kerngrad) ||
      !Load(gpu_dir, "gpu_grad_out_sobel_gx.npy", &w_gx) ||
      !Load(gpu_dir, "gpu_grad_out_sobel_gy.npy", &w_gy) ||
      !Load(gpu_dir, "gpu_grad_out_sqrt.npy", &w_sqrt) ||
      !Load(gpu_dir, "gpu_grad_out_gamma.npy", &w_gamma)) {
    std::fprintf(stderr, "FAIL: grad refs missing\n");
    failures = 6;
  } else {
    const uint32_t w = static_cast<uint32_t>(grey.shape[1]);
    const uint32_t h = static_cast<uint32_t>(grey.shape[0]);
    const uint32_t kcount = (w - 1) * (h - 1);
    const uint32_t n = w * h;
    std::vector<float> got;
    // op 0 kerngrad: interleaved pairs vs (H-1)x(W-1)x2.
    if (!RunGrad(ctx, module, w, h, kcount, 0, 0, grey.data, &got) ||
        !CheckExact("grad_kerngrad", got, w_kerngrad.data)) {
      ++failures;
    }
    // op 1 sobel: even lanes vs gx, odd lanes vs gy.
    if (!RunGrad(ctx, module, w, h, n, 1, 0, grey.data, &got)) {
      std::fprintf(stderr, "FAIL grad_sobel: dispatch failed\n");
      ++failures;
    } else {
      std::vector<float> got_gx(n), got_gy(n);
      for (uint32_t i = 0; i < n; ++i) {
        got_gx[i] = got[2 * i];
        got_gy[i] = got[2 * i + 1];
      }
      if (!CheckExact("grad_sobel_gx", got_gx, w_gx.data)) ++failures;
      if (!CheckExact("grad_sobel_gy", got_gy, w_gy.data)) ++failures;
    }
    // op 2 sqrt.
    if (!RunGrad(ctx, module, w, h, n, 2, 0, grey.data, &got) ||
        !CheckExact("grad_sqrt", got, w_sqrt.data)) {
      ++failures;
    }
    // op 3 gamma (exp bits = float32(1/2.2), the port's encoding).
    float exp_f = static_cast<float>(1.0 / 2.2);
    uint32_t exp_bits = 0;
    std::memcpy(&exp_bits, &exp_f, 4);
    if (!RunGrad(ctx, module, w, h, n, 3, exp_bits, grey.data, &got) ||
        !CheckExact("grad_gamma", got, w_gamma.data)) {
      ++failures;
    }
  }

  vkDestroyShaderModule(device, module, nullptr);
  srp::Destroy(ctx);
  return failures == 0 ? 0 : 1;
}
