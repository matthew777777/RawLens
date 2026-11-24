// SPDX-License-Identifier: Apache-2.0
#include "lkrl_full.h"

#include <ATen/Parallel.h>
#include <c10/core/InferenceMode.h>
#include <torch/script.h>

#include <algorithm>
#include <cmath>
#include <exception>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>

namespace {
struct PriorRuntime {
  explicit PriorRuntime(const std::string& checkpoint)
      : module(torch::jit::load(checkpoint, torch::kCPU)) {
    module.eval();
  }

  bool predict(const float* z, float* x, int h, int w, float sigma) {
    try {
      const c10::InferenceMode inference;
      auto input = torch::empty({1, 4, h, w}, torch::kFloat32);
      const size_t pixels = size_t(h) * w;
      std::memcpy(input.data_ptr<float>(), z, 3 * pixels * sizeof(float));
      input.select(1, 3).fill_(sigma);
      torch::Tensor output;
      {
        std::lock_guard<std::mutex> lock(mutex);
        output = module.forward({input}).toTensor().contiguous();
      }
      if (output.sizes() != at::IntArrayRef({1, 3, h, w}) ||
          output.scalar_type() != torch::kFloat32 || !torch::isfinite(output).all().item<bool>()) {
        std::fprintf(stderr, "learned prior returned invalid shape, type or values\n");
        return false;
      }
      std::memcpy(x, output.data_ptr<float>(), 3 * pixels * sizeof(float));
      return true;
    } catch (const std::exception& error) {
      std::fprintf(stderr, "learned prior inference: %s\n", error.what());
      return false;
    }
  }

  torch::jit::Module module;
  std::mutex mutex;
};
}  // namespace

lkrl::PriorFactory LoadTorchPrior(const std::string& checkpoint) {
  at::set_num_threads(2);
  auto runtime = std::make_shared<PriorRuntime>(checkpoint);
  return [runtime](float a, float b, float, const std::array<float, 3>&) {
    // Training conditions the network on the sensor sigma at mid-grey.
    const float sigma = std::sqrt(std::max(0.18f * a + b, 0.0f));
    return [runtime, sigma](const float* z, float* x, int h, int w, float, const float*) {
      return runtime->predict(z, x, h, w, sigma);
    };
  };
}
