// On-device proof for the FFT correlation chain (shaders/fft.comp).
//
// Usage: srp_proof_fft <fft.spv> <corr.spv> <ref_dir>
// Three layers, all on real-burst level-1 data (<ref_dir>, see
// /tmp/gen_corr_refs.py):
//   1. host tables bit-exact vs the port's dumps (twiddles 16/64,
//      Bluestein chirp + device-computed B_fft for n=24);
//   2. SrpCorrelateGpu on (ref_tiles, mov_patches_off) vs the port's own
//      device_correlate output — reported (summation-order class), not
//      gated: no certified corrs-alone envelope exists;
//   3. GATED: entry corrs → host ±R crop (mirroring gpu_align_lvl_l2) →
//      SrpCorrArgminGpu → winning integers bit-exact vs the CPU argmin
//      (the gpu_l2_levels gate class: flow maxabs 0.0, int agreement).
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
#include "srp_fft.h"
#include "srp_pipeline.h"

namespace {

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

VkShaderModule LoadModule(VkDevice device, const char* path) {
  const std::vector<uint8_t> spirv = ReadFile(path);
  if (spirv.empty() || spirv.size() % 4 != 0) return VK_NULL_HANDLE;
  VkShaderModuleCreateInfo info{};
  info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
  info.codeSize = spirv.size();
  info.pCode = reinterpret_cast<const uint32_t*>(spirv.data());
  VkShaderModule mod = VK_NULL_HANDLE;
  if (vkCreateShaderModule(device, &info, nullptr, &mod) != VK_SUCCESS) {
    return VK_NULL_HANDLE;
  }
  return mod;
}

bool CheckExactVec(const char* name, const std::vector<float>& got,
                   const std::vector<float>& want) {
  if (got.size() != want.size()) {
    std::fprintf(stderr, "FAIL %s: size %zu vs %zu\n", name, got.size(),
                 want.size());
    return false;
  }
  uint64_t ndiff = 0;
  float maxabs = 0.0f;
  for (size_t i = 0; i < got.size(); ++i) {
    const float d = std::fabs(got[i] - want[i]);
    if (d != 0.0f) {
      ++ndiff;
      maxabs = std::fmax(maxabs, d);
    }
  }
  const bool pass = ndiff == 0;
  std::printf("%s: n=%zu ndiff=%llu maxabs=%.6g (bit-exact) %s\n", name,
              got.size(), static_cast<unsigned long long>(ndiff),
              static_cast<double>(maxabs), pass ? "PASS" : "FAIL");
  return pass;
}

float MaxAbs(const std::vector<float>& got, const std::vector<float>& want) {
  float m = 0.0f;
  for (size_t i = 0; i < got.size() && i < want.size(); ++i) {
    m = std::fmax(m, std::fabs(got[i] - want[i]));
  }
  return m;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 4) {
    std::fprintf(stderr, "usage: %s <fft.spv> <corr.spv> <ref_dir>\n",
                 argv[0]);
    return 2;
  }
  const std::string ref_dir = argv[3];
  auto load = [&](const char* name, srp_proof::NpyArray* out) {
    return srp_proof::LoadNpy((ref_dir + "/" + name).c_str(), out);
  };

  srp::Context* ctx = nullptr;
  if (srp::Create(&ctx) != srp::Status::kOk || ctx == nullptr) {
    std::fprintf(stderr, "FAIL: srp::Create failed\n");
    return 1;
  }
  std::printf("device: %s\n", srp::DeviceInfo(ctx).c_str());
  auto* device = static_cast<VkDevice>(srp::NativeDevice(ctx));
  VkShaderModule fft_mod = LoadModule(device, argv[1]);
  VkShaderModule corr_mod = LoadModule(device, argv[2]);
  if (fft_mod == VK_NULL_HANDLE || corr_mod == VK_NULL_HANDLE) {
    std::fprintf(stderr, "FAIL: cannot load modules\n");
    srp::Destroy(ctx);
    return 1;
  }

  int failures = 0;
  // NOTE: the layer-1 chain must die before srp::Destroy below (its arena
  // frees device buffers; destroying after the device is use-after-free).
  // SrpCorrelateGpu scopes its chain per call, so the entry is unaffected.
  {
  srp::FftChain chain(ctx, fft_mod);

  // Layer 1: tables.
  srp_proof::NpyArray tw16, tw64, chirp24, bfft24;
  if (!load("fft_tw16.npy", &tw16) || !load("fft_tw64.npy", &tw64) ||
      !load("fft_chirp24.npy", &chirp24) ||
      !load("fft_bfft24.npy", &bfft24)) {
    std::fprintf(stderr, "FAIL: table refs missing\n");
    return 1;
  }
  if (!CheckExactVec("twiddles16", srp::FftChain::BuildTwiddles(16),
                     tw16.data) ||
      !CheckExactVec("twiddles64", srp::FftChain::BuildTwiddles(64),
                     tw64.data)) {
    ++failures;
  }
  srp::FftChain::ChirpPlan plan = chain.bluestein(24);
  bool plan_ok = plan.m == 64 &&
                 CheckExactVec("chirp24", plan.w, chirp24.data) &&
                 CheckExactVec("bfft24", plan.bfft, bfft24.data);
  std::printf("bluestein24: m=%u %s\n", plan.m, plan_ok ? "PASS" : "FAIL");
  if (!plan_ok) ++failures;
  }  // layer-1 chain dies here (see NOTE above)

  // Layer 2+3: entry + downstream argmin gate.
  srp_proof::NpyArray reftiles, patches_off, port_corrs, patches, dflow_off,
      dflow;
  if (!load("corr_reftiles.npy", &reftiles) ||
      !load("corr_patches_cpu_off.npy", &patches_off) ||
      !load("corr_corrs_entry_cpu.npy", &port_corrs) ||
      !load("corr_patches_cpu.npy", &patches) ||
      !load("corr_dflow_cpu_off.npy", &dflow_off) ||
      !load("corr_dflow_cpu.npy", &dflow)) {
    std::fprintf(stderr, "FAIL: corr refs missing\n");
    return 1;
  }
  const uint32_t tc =
      static_cast<uint32_t>(reftiles.shape[0]);  // flat (n,s,s)
  const uint32_t s = static_cast<uint32_t>(reftiles.shape[1]);
  const uint32_t c = 9;  // radius 4 → C=9 (corr_params.txt)
  std::vector<float> got(static_cast<size_t>(tc) * s * s);
  srp::Status st = srp::SrpCorrelateGpu(
      ctx, tc, s, reftiles.data.data(), patches_off.data.data(), got.data(),
      fft_mod);
  if (st != srp::Status::kOk) {
    std::fprintf(stderr, "FAIL: SrpCorrelateGpu status %d\n",
                 static_cast<int>(st));
    ++failures;
  } else {
    std::printf("correlate_off: n=%zu maxabs_vs_port=%.6g (reported)\n",
                got.size(),
                static_cast<double>(MaxAbs(got, port_corrs.data)));
    // Host ±R crop (mirrors gpu_align_lvl_l2) then the proven argmin entry.
    const uint32_t pre = s;
    const uint32_t crop = (pre - 1 - c) / 2;
    std::vector<float> cropped(static_cast<size_t>(tc) * c * c);
    for (uint32_t t = 0; t < tc; ++t) {
      for (uint32_t y = 0; y < c; ++y) {
        for (uint32_t x = 0; x < c; ++x) {
          cropped[(static_cast<size_t>(t) * c + y) * c + x] =
              got[(static_cast<size_t>(t) * s + (crop + 1 + y)) * s +
                  (crop + 1 + x)];
        }
      }
    }
    std::vector<float> df(static_cast<size_t>(tc) * 2);
    // Stage through device buffers for the argmin entry (caller-owned).
    auto stage = [&](const std::vector<float>& v, VkBuffer* b,
                     VkDeviceMemory* m) -> bool {
      VkBufferCreateInfo bi{};
      bi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
      bi.size = static_cast<VkDeviceSize>(v.size()) * 4;
      bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
      bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
      if (vkCreateBuffer(device, &bi, nullptr, b) != VK_SUCCESS) return false;
      VkMemoryRequirements rq{};
      vkGetBufferMemoryRequirements(device, *b, &rq);
      VkPhysicalDeviceMemoryProperties mp{};
      auto* phys =
          static_cast<VkPhysicalDevice>(srp::NativePhysicalDevice(ctx));
      vkGetPhysicalDeviceMemoryProperties(phys, &mp);
      uint32_t idx = 0;
      bool found = false;
      for (uint32_t i = 0; i < mp.memoryTypeCount; ++i) {
        if ((rq.memoryTypeBits & (1u << i)) != 0u &&
            (mp.memoryTypes[i].propertyFlags &
             (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                 VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
          idx = i;
          found = true;
          break;
        }
      }
      if (!found) return false;
      VkMemoryAllocateInfo ai{};
      ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
      ai.allocationSize = rq.size;
      ai.memoryTypeIndex = idx;
      if (vkAllocateMemory(device, &ai, nullptr, m) != VK_SUCCESS ||
          vkBindBufferMemory(device, *b, *m, 0) != VK_SUCCESS) {
        return false;
      }
      void* mapped = nullptr;
      if (vkMapMemory(device, *m, 0, bi.size, 0, &mapped) != VK_SUCCESS) {
        return false;
      }
      std::memcpy(mapped, v.data(), v.size() * 4);
      vkUnmapMemory(device, *m);
      return true;
    };
    // Composed production path: patches_off -> correlate(entry) ->
    // crop -> argmin(entry). Energies come from the CPU patches (the
    // extract op is proven separately); corrs come from the entry.
    VkBuffer cb = VK_NULL_HANDLE, db = VK_NULL_HANDLE;
    VkDeviceMemory cm = VK_NULL_HANDLE, dm = VK_NULL_HANDLE;
    VkBuffer p2b = VK_NULL_HANDLE;
    VkDeviceMemory p2m = VK_NULL_HANDLE;
    if (!stage(patches_off.data, &p2b, &p2m) ||
        !stage(cropped, &cb, &cm)) {
      std::fprintf(stderr, "FAIL: argmin staging failed\n");
      ++failures;
    } else {
      VkBufferCreateInfo dbi{};
      dbi.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
      dbi.size = static_cast<VkDeviceSize>(tc) * 2 * 4;
      dbi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
      dbi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
      if (vkCreateBuffer(device, &dbi, nullptr, &db) != VK_SUCCESS) {
        std::fprintf(stderr, "FAIL: dflow buffer create\n");
        ++failures;
      } else {
        VkMemoryRequirements rq{};
        vkGetBufferMemoryRequirements(device, db, &rq);
        VkPhysicalDeviceMemoryProperties mp{};
        auto* phys =
            static_cast<VkPhysicalDevice>(srp::NativePhysicalDevice(ctx));
        vkGetPhysicalDeviceMemoryProperties(phys, &mp);
        uint32_t idx = 0;
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i) {
          if ((rq.memoryTypeBits & (1u << i)) != 0u &&
              (mp.memoryTypes[i].propertyFlags &
               (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                  (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                   VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            idx = i;
            break;
          }
        }
        VkMemoryAllocateInfo ai{};
        ai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        ai.allocationSize = rq.size;
        ai.memoryTypeIndex = idx;
        if (vkAllocateMemory(device, &ai, nullptr, &dm) != VK_SUCCESS ||
            vkBindBufferMemory(device, db, dm, 0) != VK_SUCCESS) {
          std::fprintf(stderr, "FAIL: dflow alloc/bind\n");
          ++failures;
        } else {
          const uint32_t pc = tc * s * s;
          const uint32_t cc = tc * c * c;
          if (srp::SrpCorrArgminGpu(ctx, tc, 16, 4, 15, 0, tc, pc, cc, p2b, cb, db,
                                    corr_mod) != srp::Status::kOk) {
            std::fprintf(stderr, "FAIL: downstream argmin dispatch\n");
            ++failures;
          } else {
            void* mapped = nullptr;
            if (vkMapMemory(device, dm, 0, dbi.size, 0, &mapped) !=
                VK_SUCCESS) {
              std::fprintf(stderr, "FAIL: dflow readback\n");
              ++failures;
            } else {
              df.assign(static_cast<const float*>(mapped),
                        static_cast<const float*>(mapped) + tc * 2);
              vkUnmapMemory(device, dm);
              if (!CheckExactVec("correlateArgmin_off", df,
                                 dflow_off.data)) {
                ++failures;
              }
            }
          }
        }
      }
    }
    for (auto b : {cb, db, p2b}) {
      if (b != VK_NULL_HANDLE) vkDestroyBuffer(device, b, nullptr);
    }
    for (auto m : {cm, dm, p2m}) {
      if (m != VK_NULL_HANDLE) vkFreeMemory(device, m, nullptr);
    }
  }

  vkDestroyShaderModule(device, fft_mod, nullptr);
  vkDestroyShaderModule(device, corr_mod, nullptr);
  srp::Destroy(ctx);
  if (failures == 0) std::printf("PASS: fft correlate chain on-device\n");
  return failures == 0 ? 0 : 1;
}
