// On-device proof for robustness estimation (shaders/robustness.comp,
// Alg.6-9, bayer): init (ops 0+1) -> smap (op 6) -> compute (ops
// 0+1+2+3+4+5), composed end to end on-device.
//
// Usage: srp_proof_robust <robustness.spv> <fixtures_dir> <gpuref_dir>
// Input parity/fixtures/robustness_in_{ref,comp}_raw (128x128) +
// robustness_in_flow (8x8x2), small_config(tile 16, linear steerable,
// k_detail=0.3 k_denoise=4.0 D_th=0.75 D_tr=1.1, t=0.12, Mt=0.8 s1=2
// s2=12, noise_correction off, zero curves). Output R (64x64) is compared
// against parity/gpu_reference/gpu_robustness_out_r.npy (gate: measured
// composition bound, far inside the certified envelope) and the run also
// checks smap_in_flow -> S against gpu_smap_out_s.npy (bit-exact class).
// Prints numbers; exits nonzero on any breach.

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

uint32_t F32Bits(float v) {
  uint32_t b = 0;
  std::memcpy(&b, &v, 4);
  return b;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 4) {
    std::fprintf(stderr, "usage: %s <robustness.spv> <fixtures_dir> <gpuref_dir>\n",
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
  // Standalone smap leg first (own fixtures).
  srp_proof::NpyArray sflow, want_s;
  if (!Load(fix_dir, "smap_in_flow.npy", &sflow) ||
      !Load(gpu_dir, "gpu_smap_out_s.npy", &want_s)) {
    std::fprintf(stderr, "FAIL: smap refs missing\n");
    failures = 2;
  } else {
    const uint32_t ntx = static_cast<uint32_t>(sflow.shape[1]);
    const uint32_t nty = static_cast<uint32_t>(sflow.shape[0]);
    const size_t n = static_cast<size_t>(ntx) * nty;
    VkBuffer f_buf = VK_NULL_HANDLE, s_buf = VK_NULL_HANDLE;
    VkDeviceMemory f_mem = VK_NULL_HANDLE, s_mem = VK_NULL_HANDLE;
    std::vector<float> got;
    if (MakeBuffer(device, physical, n * 2 * 4, sflow.data.data(), &f_buf,
                   &f_mem) &&
        MakeBuffer(device, physical, n * 4, nullptr, &s_buf, &s_mem) &&
        srp::SrpSmapGpu(ctx, ntx, nty, f_buf, s_buf, F32Bits(0.8f),
                        F32Bits(2.0f), F32Bits(12.0f),
                        module) == srp::Status::kOk &&
        Download(device, s_mem, n, &got) && got.size() == want_s.data.size()) {
      const float m = MaxAbs(got, want_s.data);
      const bool pass = m == 0.0f;
      std::printf("smap_flow44_vs_port_gpu: n=%zu maxabs=%.6g (bit-exact) %s\n",
                  n, static_cast<double>(m), pass ? "PASS" : "FAIL");
      if (!pass) ++failures;
    } else {
      std::fprintf(stderr, "FAIL smap: dispatch failed\n");
      ++failures;
    }
    FreeBuffer(device, f_buf, f_mem);
    FreeBuffer(device, s_buf, s_mem);
  }

  // Composed init -> compute chain on the 128 fixtures.
  srp_proof::NpyArray ref, comp, flow, want_r;
  if (!Load(fix_dir, "robustness_in_ref_raw.npy", &ref) ||
      !Load(fix_dir, "robustness_in_comp_raw.npy", &comp) ||
      !Load(fix_dir, "robustness_in_flow.npy", &flow) ||
      !Load(gpu_dir, "gpu_robustness_out_r.npy", &want_r)) {
    std::fprintf(stderr, "FAIL: robustness refs missing\n");
    failures += 4;
  } else {
    const uint32_t w = static_cast<uint32_t>(ref.shape[1]);
    const uint32_t h = static_cast<uint32_t>(ref.shape[0]);
    const uint32_t gw = w / 2, gh = h / 2;
    const size_t n = static_cast<size_t>(gw) * gh;
    const uint32_t ftx = static_cast<uint32_t>(flow.shape[1]);
    const uint32_t fty = static_cast<uint32_t>(flow.shape[0]);
    const size_t nf = static_cast<size_t>(ftx) * fty;
    const float lut_host[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    VkBuffer ref_b = VK_NULL_HANDLE, comp_b = VK_NULL_HANDLE,
             means_b = VK_NULL_HANDLE, vars_b = VK_NULL_HANDLE,
             flow_b = VK_NULL_HANDLE, lut_b = VK_NULL_HANDLE,
             s_b = VK_NULL_HANDLE, r_b = VK_NULL_HANDLE;
    VkDeviceMemory ref_m = VK_NULL_HANDLE, comp_m = VK_NULL_HANDLE,
                   means_m = VK_NULL_HANDLE, vars_m = VK_NULL_HANDLE,
                   flow_m = VK_NULL_HANDLE, lut_m = VK_NULL_HANDLE,
                   s_m = VK_NULL_HANDLE, r_m = VK_NULL_HANDLE;
    std::vector<float> got;
    bool ok = false;
    if (MakeBuffer(device, physical, w * h * 4, ref.data.data(), &ref_b,
                   &ref_m) &&
        MakeBuffer(device, physical, w * h * 4, comp.data.data(), &comp_b,
                   &comp_m) &&
        MakeBuffer(device, physical, 3 * n * 4, nullptr, &means_b, &means_m) &&
        MakeBuffer(device, physical, 3 * n * 4, nullptr, &vars_b, &vars_m) &&
        MakeBuffer(device, physical, nf * 2 * 4, flow.data.data(), &flow_b,
                   &flow_m) &&
        MakeBuffer(device, physical, sizeof(lut_host), lut_host, &lut_b,
                   &lut_m) &&
        MakeBuffer(device, physical, nf * 4, nullptr, &s_b, &s_m) &&
        MakeBuffer(device, physical, n * 4, nullptr, &r_b, &r_m) &&
        srp::SrpRobustInitGpu(ctx, w, h, ref_b, means_b, vars_b, module) ==
            srp::Status::kOk &&
        srp::SrpSmapGpu(ctx, ftx, fty, flow_b, s_b, F32Bits(0.8f),
                        F32Bits(2.0f), F32Bits(12.0f),
                        module) == srp::Status::kOk &&
        srp::SrpRobustComputeGpu(
            ctx, w, h, comp_b, means_b, vars_b, flow_b, lut_b, s_b, r_b, 16,
            2, 0, F32Bits(0.12f), ftx, fty,
            module) == srp::Status::kOk &&
        Download(device, r_m, n, &got) && got.size() == want_r.data.size()) {
      ok = true;
    }
    FreeBuffer(device, ref_b, ref_m);
    FreeBuffer(device, comp_b, comp_m);
    FreeBuffer(device, means_b, means_m);
    FreeBuffer(device, vars_b, vars_m);
    FreeBuffer(device, flow_b, flow_m);
    FreeBuffer(device, lut_b, lut_m);
    FreeBuffer(device, s_b, s_m);
    FreeBuffer(device, r_b, r_m);
    if (!ok) {
      std::fprintf(stderr, "FAIL robustness: dispatch failed\n");
      failures = 1;
    } else {
      const float m = MaxAbs(got, want_r.data);
      std::printf("robustness_composed128_vs_port_gpu: n=%zu maxabs=%.6g\n",
                  got.size(), static_cast<double>(m));
    }
  }

  vkDestroyShaderModule(device, module, nullptr);
  srp::Destroy(ctx);
  return failures == 0 ? 0 : 1;
}
