// SPDX-License-Identifier: Apache-2.0
#pragma once
// Batched LkAffine on the GPU: many fits (frames, sub-tiles) iterate together, one GPU pass per
// iteration computes every active fit's normal equations (shaders/lkfit.comp, float sums per
// group); the host adds them in double and runs LkAffine's own control flow per fit (6x6 solve with
// the 1e-6 diagonal, p += dp, stop at |dt| < 1e-3, level-to-level scaling). On Mali the CPU
// version was 61 % of all CPU time of a Lite merge.

#include <array>
#include <vector>

#include "lkrl_gpu.h"
#include "lkrl_host.h"

namespace lkrl {

struct LkJob {
  const LkRef* ref = nullptr;     // all jobs of one batch: same pyramid sizes
  const float* mov = nullptr;     // level-0 moving image, ref->hs[0] x ref->ws[0]
  std::array<double, 6> p{1, 0, 0, 0, 1, 0};   // in: start (LkAffine's p0); out: result
};

class LkBatchGpu {
 public:
  explicit LkBatchGpu(Gpu* gpu);
  ~LkBatchGpu();
  bool ok() const { return ok_; }
  // Same result contract as LkAffine(*job.ref, job.mov, iters, &job.p) for every job.
  bool run(std::vector<LkJob>& jobs, int iters);

 private:
  Buffer& buf(int slot, size_t bytes, bool host_read);
  Gpu* gpu_;
  Pipeline pipe_;
  bool ok_ = false;
  Buffer bufs_[5];
  size_t sizes_[5] = {0, 0, 0, 0, 0};
};

}  // namespace lkrl
