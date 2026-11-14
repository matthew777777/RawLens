// SPDX-License-Identifier: Apache-2.0
#pragma once
// Local-motion initialisation for LK-SR: coarse-to-fine block search with
// sr-pipeline's MFSR align.comp (exhaustive L1 per tile, same SPIR-V source)
// on half-resolution grey images, each frame pre-warped by its tile affine so
// only the LOCAL residual motion is searched. Output: the solver's local field
// (K x nby x nbx vec2, LR px, 16-px blocks), frame 0 = 0.
#include <vector>
#include "lkrl_gpu.h"

namespace lkrl {

class BlockSearch {
 public:
  explicit BlockSearch(Gpu* gpu);
  ~BlockSearch();
  bool ok() const { return pipe_.pipe != VK_NULL_HANDLE; }
  // frames: K x n x n RGGB tile; p: K x 6 affine (frame -> ref LR coords).
  // sigma: OUR raw noise sigma (gates matches: zero-shift mismatch must exceed noise).
  bool run(const float* frames, int K, int n, const float* p, int levels, int radius, float sigma,
           std::vector<float>* field);

 private:
  Gpu* gpu_;
  Pipeline pipe_;
};

}  // namespace lkrl
