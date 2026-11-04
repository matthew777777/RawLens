// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

// FFDNet plug-and-play prior in the generalized-Anscombe domain of OUR fitted
// noise model (python lkr.Prior(domain='gat')._gat_net, exact mirror):
//   x_c = max(z_c * wb_c, 0); a_c = a wb_c, b_c = b wb_c^2
//   D = 2/a_c sqrt(a_c x + 3/8 a_c^2 + b_c)       (unit-variance noise)
//   Dn = (D - lo)/span, noise level r/span with r = sigma / sig_ref
//   FFDNet(pixel_unshuffle(Dn) ++ noise) -> pixel_shuffle -> * span + lo
//   closed-form unbiased inverse GAT (Makitalo-Foi), / wb
// The convolution core runs in ncnn (Vulkan on desktop/Android GPU); the NPU
// backend (LiteRT) plugs in behind the same interface on Android.

#include <array>
#include <functional>
#include <memory>
#include <string>

namespace lkr {

class FfdnetGatPrior {
 public:
  struct Options {
    bool vulkan = true;
    bool fp16 = false;   // fp16 storage/arith in ncnn (fast); false = fp32 parity
    int threads = 2;
  };
  FfdnetGatPrior();
  ~FfdnetGatPrior();
  bool load(const std::string& param_path, const std::string& bin_path, const Options& opt);
  void setNoise(float a, float b, float sig_ref, const std::array<float, 3>& wb);
  // z, x: 3 planes of H*W (H, W even). Thread-safe: packing runs per thread,
  // only the network call is serialised. Returns false on failure.
  bool operator()(const float* z, float* x, int H, int W, float sigma, const float* noise_scale = nullptr);
  // Only the network (packed 13 x H2 x W2 in, 12 x H2 x W2 out) for parity.
  bool runCore(const float* in, float* out, int H2, int W2);
  std::string backend() const;
  std::string timing() const;   // mean per-call breakdown since load
  // External convolution core (e.g. LiteRT NPU): CHW 13 x H2 x W2 in,
  // 12 x H2 x W2 out. When set and its fixed shape matches, it replaces ncnn.
  using Core = std::function<bool(const float* in, float* out, int H2, int W2)>;
  void setCore(Core core, int H2, int W2, const std::string& name);
  // Zero-copy NHWC core (LiteRT): pack writes straight into the locked input
  // tensor [H2][W2][13], unpack reads the locked output [H2][W2][12].
  struct NhwcCore {
    std::function<float*()> lock_in;          // lock input for writing
    std::function<bool()> run;                // unlock input + run
    std::function<const float*()> lock_out;   // lock output for reading
    std::function<void()> unlock_out;
  };
  void setNhwcCore(NhwcCore core, int H2, int W2, const std::string& name);

 private:
  struct Impl;
  std::unique_ptr<Impl> d_;
};

}  // namespace lkr
