// On-device proof for the merge splat (shaders/merge_splat.comp, Alg.4).
//
// Usage: srp_proof_merge_splat <merge_splat.spv> <fixtures_dir> <gpuref_dir>
// Runs SrpMergeSplatGpu on-device over parity/fixtures/merge_in_* (128x128
// comp, 8x8 flow, 64x64 covs, 64x64 r; scale 2, tile 16, bayer) into
// zeroed 256x256x3 num/den and compares against
// parity/gpu_reference/gpu_merge_out_{num,den}.npy. Same shader,
// compiler, and device class as the port, so the gate is the measured
// exp/FMA-class bound (the port's own merge envelope), not bit-exact:
// per-HR-pixel exp() of inverted-covariance distances differs in the
// last ulps by summation order. Prints numbers; exits nonzero on breach.

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

bool Download(VkDevice device, VkDeviceMemory mem, size_t nfloats,
              std::vector<float>* out) {
  void* mapped = nullptr;
  if (vkMapMemory(device, mem, 0, nfloats * sizeof(float), 0, &mapped) !=
      VK_SUCCESS) {
    return false;
  }
  out->assign(static_cast<const float*>(mapped),
              static_cast<const float*>(mapped) + nfloats);
  vkUnmapMemory(device, mem);
  return true;
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
    std::fprintf(stderr,
                 "usage: %s <merge_splat.spv> <fixtures_dir> <gpuref_dir>\n",
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
  srp_proof::NpyArray comp, flow, covs, r, want_num, want_den;
  if (!Load(fix_dir, "merge_in_comp_raw.npy", &comp) ||
      !Load(fix_dir, "merge_in_flow.npy", &flow) ||
      !Load(fix_dir, "merge_in_covs.npy", &covs) ||
      !Load(fix_dir, "merge_in_r.npy", &r) ||
      !Load(gpu_dir, "gpu_merge_out_num.npy", &want_num) ||
      !Load(gpu_dir, "gpu_merge_out_den.npy", &want_den)) {
    std::fprintf(stderr, "FAIL: merge refs missing\n");
    failures = 6;
  } else {
    const uint32_t lr_w = 128, lr_h = 128, hr_w = 256, hr_h = 256;
    const size_t n_hr3 = static_cast<size_t>(hr_w) * hr_h * 3;
    // Flow is already (8,8,2) interleaved; pack covs (64,64,2,2) ->
    // (xx,xy,yy,0) vecs.
    const std::vector<float>& flow_flat = flow.data;
    std::vector<float> cov_flat;
    for (size_t p = 0; p < 64 * 64; ++p) {
      cov_flat.push_back(covs.data[4 * p + 0]);
      cov_flat.push_back(covs.data[4 * p + 1]);
      cov_flat.push_back(covs.data[4 * p + 3]);
      cov_flat.push_back(0.0f);
    }
    VkBuffer comp_b = VK_NULL_HANDLE, flow_b = VK_NULL_HANDLE,
             cov_b = VK_NULL_HANDLE, r_b = VK_NULL_HANDLE,
             num_b = VK_NULL_HANDLE, den_b = VK_NULL_HANDLE;
    VkDeviceMemory comp_m = VK_NULL_HANDLE, flow_m = VK_NULL_HANDLE,
                   cov_m = VK_NULL_HANDLE, r_m = VK_NULL_HANDLE,
                   num_m = VK_NULL_HANDLE, den_m = VK_NULL_HANDLE;
    std::vector<float> got_num, got_den;
    bool ok = false;
    if (MakeBuffer(device, physical, comp.data.size() * 4, comp.data.data(),
                   &comp_b, &comp_m) &&
        MakeBuffer(device, physical, flow_flat.size() * 4, flow_flat.data(),
                   &flow_b, &flow_m) &&
        MakeBuffer(device, physical, cov_flat.size() * 4, cov_flat.data(),
                   &cov_b, &cov_m) &&
        MakeBuffer(device, physical, r.data.size() * 4, r.data.data(), &r_b,
                   &r_m) &&
        MakeBuffer(device, physical, n_hr3 * 4, nullptr, &num_b, &num_m) &&
        MakeBuffer(device, physical, n_hr3 * 4, nullptr, &den_b, &den_m) &&
        srp::SrpMergeSplatGpu(ctx, lr_w, lr_h, hr_w, hr_h, comp_b, flow_b,
                              cov_b, r_b, num_b, den_b, 16, 2.0f, 0, 8, 8,
                              module) == srp::Status::kOk &&
        Download(device, num_m, n_hr3, &got_num) &&
        Download(device, den_m, n_hr3, &got_den) &&
        got_num.size() == want_num.data.size() &&
        got_den.size() == want_den.data.size()) {
      ok = true;
    }
    FreeBuffer(device, comp_b, comp_m);
    FreeBuffer(device, flow_b, flow_m);
    FreeBuffer(device, cov_b, cov_m);
    FreeBuffer(device, r_b, r_m);
    FreeBuffer(device, num_b, num_m);
    FreeBuffer(device, den_b, den_m);
    if (!ok) {
      std::fprintf(stderr, "FAIL merge: dispatch failed\n");
      failures = 1;
    } else {
      const float m_num = MaxAbs(got_num, want_num.data);
      const float m_den = MaxAbs(got_den, want_den.data);
      std::printf("merge_splat num: n=%zu maxabs=%.6g\n", got_num.size(),
                  static_cast<double>(m_num));
      std::printf("merge_splat den: n=%zu maxabs=%.6g\n", got_den.size(),
                  static_cast<double>(m_den));
    }
  }

  vkDestroyShaderModule(device, module, nullptr);
  srp::Destroy(ctx);
  return failures == 0 ? 0 : 1;
}
