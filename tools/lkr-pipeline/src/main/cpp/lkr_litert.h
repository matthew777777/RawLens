// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

// FFDNet convolution core on the NPU through the LiteRT C API (CompiledModel,
// NPU only, rejected unless fully non-CPU accelerated -- the
// tools/ffdnet-probe rule). libLiteRt.so is dlopen'ed, so the library loads
// and falls back to ncnn on devices without the LiteRT / vendor runtime.
// Fixed-shape models: one compiled model per packed tile size (H2 x W2).

#include <memory>
#include <string>

namespace lkr {

class LiteRtCore {
 public:
  LiteRtCore();
  ~LiteRtCore();
  // lib_dir: where libLiteRt.so and the vendor dispatch/compiler plugins live
  // (the APK's nativeLibraryDir); model: .tflite with input [1,H2,W2,13].
  bool open(const std::string& lib_dir, const std::string& model, int H2, int W2, std::string* err);
  // CHW packed input (13 x H2 x W2) -> CHW output (12 x H2 x W2).
  bool run(const float* in_chw, float* out_chw);
  // Zero-copy NHWC access (see FfdnetGatPrior::NhwcCore).
  float* lockInput();
  bool unlockAndRun();
  const float* lockOutput();
  void unlockOutput();
  int h2() const;
  int w2() const;

 private:
  struct Impl;
  std::unique_ptr<Impl> d_;
};

}  // namespace lkr
