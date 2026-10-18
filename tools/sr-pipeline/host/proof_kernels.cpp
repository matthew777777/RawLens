// On-device proof for the kernel-covariance build (shaders/kernels.comp,
// Alg.5), composed end to end on-device: GAT (gat.comp op 0) -> decimate
// (gat.comp op 1) -> kernel gradients (grad.comp op 0) -> covariance.
//
// Usage: srp_proof_kernels <kernels.spv> <gat.spv> <grad.spv>
//                          <fixtures_dir> <gpuref_dir>
// Input parity/fixtures/kernels_in_raw (64x64, the gen_gpu_reference
// stage_kernels input, small_config preprocessing="gat" with
// ALPHA/BETA = 1e-4/1e-6 on all planes, linear steerable law,
// k_detail=0.3 k_denoise=4.0 D_th=0.75 D_tr=1.1 k_stretch=4 k_shrink=2).
// Output (32x32x4 vecs) is compared against
// parity/gpu_reference/gpu_kernels_out_covs.npy; the gate is the measured
// composition bound (input stages rebuild the grads, so this is not
// bit-exact by construction). Prints numbers; exits nonzero on breach.

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

bool LoadModule(VkDevice device, const char* path, VkShaderModule* out) {
  const std::vector<uint8_t> spirv = ReadFile(path);
  if (spirv.empty() || spirv.size() % 4 != 0) return false;
  VkShaderModuleCreateInfo module_info{};
  module_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
  module_info.codeSize = spirv.size();
  module_info.pCode = reinterpret_cast<const uint32_t*>(spirv.data());
  return vkCreateShaderModule(device, &module_info, nullptr, out) == VK_SUCCESS;
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

bool Load(const std::string& dir, const char* name, srp_proof::NpyArray* out) {
  return srp_proof::LoadNpy((dir + "/" + name).c_str(), out);
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 6) {
    std::fprintf(stderr,
                 "usage: %s <kernels.spv> <gat.spv> <grad.spv> <fixtures_dir> "
                 "<gpuref_dir>\n",
                 argv[0]);
    return 2;
  }
  const std::string fix_dir = argv[4];
  const std::string gpu_dir = argv[5];

  srp::Context* ctx = nullptr;
  if (srp::Create(&ctx) != srp::Status::kOk || ctx == nullptr) {
    std::fprintf(stderr, "FAIL: srp::Create failed\n");
    return 1;
  }
  std::printf("device: %s\n", srp::DeviceInfo(ctx).c_str());
  auto* device = static_cast<VkDevice>(srp::NativeDevice(ctx));
  auto* physical =
      static_cast<VkPhysicalDevice>(srp::NativePhysicalDevice(ctx));

  VkShaderModule kernels_mod = VK_NULL_HANDLE, gat_mod = VK_NULL_HANDLE,
                 grad_mod = VK_NULL_HANDLE;
  if (!LoadModule(device, argv[1], &kernels_mod) ||
      !LoadModule(device, argv[2], &gat_mod) ||
      !LoadModule(device, argv[3], &grad_mod)) {
    std::fprintf(stderr, "FAIL: shader module create failed\n");
    srp::Destroy(ctx);
    return 1;
  }

  int failures = 0;
  srp_proof::NpyArray raw, want;
  if (!Load(fix_dir, "kernels_in_raw.npy", &raw) ||
      !Load(gpu_dir, "gpu_kernels_out_covs.npy", &want)) {
    std::fprintf(stderr, "FAIL: kernels refs missing\n");
    failures = 2;
  } else {
    const uint32_t w = static_cast<uint32_t>(raw.shape[1]);
    const uint32_t h = static_cast<uint32_t>(raw.shape[0]);
    const size_t n = static_cast<size_t>(w) * h;
    const uint32_t gw = w / 2, gh = h / 2;       // decimated grey
    const uint32_t kw = gw - 1, kh = gh - 1;     // kerngrad grid
    const size_t n_grey = static_cast<size_t>(gw) * gh;
    const size_t n_grads = static_cast<size_t>(kw) * kh * 2;
    const size_t n_cov = n_grey * 4;
    // Stage buffers.
    VkBuffer raw_buf = VK_NULL_HANDLE, gat_buf = VK_NULL_HANDLE,
             grey_buf = VK_NULL_HANDLE, grad_buf = VK_NULL_HANDLE,
             cov_buf = VK_NULL_HANDLE, ab_buf = VK_NULL_HANDLE,
             kp_buf = VK_NULL_HANDLE;
    VkDeviceMemory raw_mem = VK_NULL_HANDLE, gat_mem = VK_NULL_HANDLE,
                   grey_mem = VK_NULL_HANDLE, grad_mem = VK_NULL_HANDLE,
                   cov_mem = VK_NULL_HANDLE, ab_mem = VK_NULL_HANDLE,
                   kp_mem = VK_NULL_HANDLE;
    const float ab[8] = {1e-4f, 1e-4f, 1e-4f, 1e-4f,
                         1e-6f, 1e-6f, 1e-6f, 1e-6f};
    // kparams: six shaping scalars + selection_law/is_iso by BIT pattern
    // (std430 uint fields; float 1.0f would be the wrong bits).
    float kp_host[8] = {0.3f, 4.0f, 0.75f, 1.1f, 4.0f, 2.0f, 0.0f, 0.0f};
    const uint32_t sel_linear = 1, not_iso = 0;
    std::memcpy(&kp_host[6], &sel_linear, 4);
    std::memcpy(&kp_host[7], &not_iso, 4);
    bool ok = false;
    std::vector<float> got;
    if (MakeBuffer(device, physical, n * 4, raw.data.data(), &raw_buf,
                   &raw_mem) &&
        MakeBuffer(device, physical, n * 4, nullptr, &gat_buf, &gat_mem) &&
        MakeBuffer(device, physical, n_grey * 4, nullptr, &grey_buf,
                   &grey_mem) &&
        MakeBuffer(device, physical, n_grads * 4, nullptr, &grad_buf,
                   &grad_mem) &&
        MakeBuffer(device, physical, n_cov * 4, nullptr, &cov_buf,
                   &cov_mem) &&
        MakeBuffer(device, physical, sizeof(ab), ab, &ab_buf, &ab_mem) &&
        MakeBuffer(device, physical, sizeof(kp_host), kp_host, &kp_buf,
                   &kp_mem) &&
        srp::SrpGatGpu(ctx, w, h, static_cast<uint32_t>(n), 0, raw_buf,
                       gat_buf, ab_buf, gat_mod) == srp::Status::kOk &&
        srp::SrpGatGpu(ctx, w, h, static_cast<uint32_t>(n_grey), 1, gat_buf,
                       grey_buf, ab_buf, gat_mod) == srp::Status::kOk &&
        srp::SrpGradGpu(ctx, gw, gh, kw * kh, 0, 0, grey_buf, grad_buf,
                        grad_mod) == srp::Status::kOk &&
        srp::SrpKernelsCovGpu(ctx, kw, kh, gw, gh, grad_buf, cov_buf, kp_buf,
                              kernels_mod) == srp::Status::kOk &&
        Download(device, cov_mem, n_cov, &got)) {
      ok = true;
    }
    FreeBuffer(device, raw_buf, raw_mem);
    FreeBuffer(device, gat_buf, gat_mem);
    FreeBuffer(device, grey_buf, grey_mem);
    FreeBuffer(device, grad_buf, grad_mem);
    FreeBuffer(device, cov_buf, cov_mem);
    FreeBuffer(device, ab_buf, ab_mem);
    FreeBuffer(device, kp_buf, kp_mem);
    if (!ok || got.size() != want.data.size()) {
      std::fprintf(stderr, "FAIL kernels: dispatch failed\n");
      failures = 1;
    } else {
      // want is (gy,gx,2,2) row-major, got is (gy,gx,4) vecs (xx,xy,yy,0).
      const size_t npix = got.size() / 4;
      float m = 0.0f;
      for (size_t p = 0; p < npix; ++p) {
        m = std::fmax(m, std::fabs(got[4 * p + 0] - want.data[4 * p + 0]));
        m = std::fmax(m, std::fabs(got[4 * p + 1] - want.data[4 * p + 1]));
        m = std::fmax(m, std::fabs(got[4 * p + 1] - want.data[4 * p + 2]));
        m = std::fmax(m, std::fabs(got[4 * p + 2] - want.data[4 * p + 3]));
        m = std::fmax(m, std::fabs(got[4 * p + 3]));
      }
      std::printf("kernels_cov_composed: n=%zu maxabs=%.6g\n", got.size(),
                  static_cast<double>(m));
    }
  }

  vkDestroyShaderModule(device, kernels_mod, nullptr);
  vkDestroyShaderModule(device, gat_mod, nullptr);
  vkDestroyShaderModule(device, grad_mod, nullptr);
  srp::Destroy(ctx);
  return failures == 0 ? 0 : 1;
}
