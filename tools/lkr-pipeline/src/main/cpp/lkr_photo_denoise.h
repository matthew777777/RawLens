// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstdint>
#include <string>
namespace lkr {
// Test-only backend selection: Auto retains production fallback; forced
// backends fail explicitly rather than reporting fallback timings.
enum class PhotoBackend { Auto = 0, Gpu = 1, Npu = 2 };
struct PhotoDenoiseStats {
  int backend = 0, calls = 0;
  double noise_ms = 0, model_ms = 0, npu_open_ms = 0, range_ms = 0;
  double primary_ms = 0, chroma_ms = 0, guard_ms = 0, blend_ms = 0;
  double pack_ms = 0, inference_ms = 0, unpack_ms = 0;
};
bool DenoisePhoto(uint16_t* rgba, int w, int h, const int* cfa, std::array<float, 3> wb,
                  float strength, const std::string& param, const std::string& bin,
                  const std::string& npu_dir = "", const std::string& npu_model = "",
                  int chroma_stride = 4, float luma = 0.f, bool colour_guard = true,
                  PhotoBackend backend = PhotoBackend::Auto, bool fp16 = false,
                  PhotoDenoiseStats* stats = nullptr);
}
