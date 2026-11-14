// SPDX-License-Identifier: Apache-2.0
#include "lkrl_gpulk.h"

#include <algorithm>
#include <cmath>
#include <map>

namespace lkrl {
namespace spv {
static const uint32_t lkfit[] = {
#include "lkfit.inc"
};
}  // namespace spv

namespace {
struct Push { int32_t H, W, nFit, groupsPerFit; };
}  // namespace

LkBatchGpu::LkBatchGpu(Gpu* gpu) : gpu_(gpu) {
  if (!gpu_ || !gpu_->ok()) return;
  pipe_ = gpu_->pipeline(spv::lkfit, sizeof(spv::lkfit), 5, sizeof(Push));
  ok_ = pipe_.pipe != VK_NULL_HANDLE;
}

LkBatchGpu::~LkBatchGpu() {
  if (!gpu_ || !gpu_->ok()) return;
  for (Buffer& b : bufs_) gpu_->free(b);
  gpu_->destroy(pipe_);
}

Buffer& LkBatchGpu::buf(int slot, size_t bytes, bool host_read) {   // grow-only, reused across tiles
  if (sizes_[slot] < bytes || !bufs_[slot].map) {
    gpu_->free(bufs_[slot]);
    bufs_[slot] = gpu_->alloc(bytes, host_read);
    sizes_[slot] = bytes;
  }
  return bufs_[slot];
}

bool LkBatchGpu::run(std::vector<LkJob>& jobs, int iters) {
  if (!ok_) return false;
  if (jobs.empty()) return true;
  const LkRef& r0 = *jobs[0].ref;
  const int levels = int(r0.r.size()), nFit = int(jobs.size());
  for (const LkJob& j : jobs)
    if (int(j.ref->r.size()) != levels || j.ref->hs[0] != r0.hs[0] || j.ref->ws[0] != r0.ws[0]) return false;
  // Distinct references (sub-tiles share one per quadrant across frames).
  std::map<const LkRef*, int> refIndex;
  std::vector<const LkRef*> refs;
  std::vector<int> fitRefAll(static_cast<size_t>(nFit));
  for (int f = 0; f < nFit; ++f) {
    auto it = refIndex.find(jobs[size_t(f)].ref);
    if (it == refIndex.end()) { it = refIndex.emplace(jobs[size_t(f)].ref, int(refs.size())).first; refs.push_back(jobs[size_t(f)].ref); }
    fitRefAll[size_t(f)] = it->second;
  }
  std::vector<std::vector<std::vector<double>>> movPyr(static_cast<size_t>(nFit));
  for (int f = 0; f < nFit; ++f) movPyr[size_t(f)] = LkMovPyramid(*jobs[size_t(f)].ref, jobs[size_t(f)].mov);

  std::vector<std::array<double, 6>> p(static_cast<size_t>(nFit));
  std::vector<char> active(static_cast<size_t>(nFit), 1);
  for (int f = 0; f < nFit; ++f) {
    p[size_t(f)] = jobs[size_t(f)].p;
    p[size_t(f)][2] /= std::pow(2.0, levels - 1);
    p[size_t(f)][5] /= std::pow(2.0, levels - 1);
  }
  for (int lv = levels - 1; lv >= 0; --lv) {
    const int H = r0.hs[size_t(lv)], W = r0.ws[size_t(lv)];
    const size_t n = size_t(H) * W;
    const int gpf = int((n + 255) / 256);
    Buffer& rb = buf(0, refs.size() * n * 16, false);
    Buffer& mb = buf(1, size_t(nFit) * n * 4, false);
    Buffer& fb = buf(2, size_t(nFit) * 4, false);
    Buffer& pb = buf(3, size_t(nFit) * 24, false);
    Buffer& part = buf(4, size_t(nFit) * gpf * 27 * 4, true);
    if (!rb.map || !mb.map || !fb.map || !pb.map || !part.map) return false;
    float* rf = rb.as<float>();
    for (size_t q = 0; q < refs.size(); ++q) {
      const LkRef& R = *refs[q];
      for (size_t o = 0; o < n; ++o) {
        float* d = rf + (q * n + o) * 4;
        d[0] = float(R.r[size_t(lv)][o]); d[1] = float(R.gx[size_t(lv)][o]); d[2] = float(R.gy[size_t(lv)][o]); d[3] = 0.0f;
      }
    }
    float* mf = mb.as<float>();
    for (int f = 0; f < nFit; ++f) {
      const std::vector<double>& m = movPyr[size_t(f)][size_t(lv)];
      for (size_t o = 0; o < n; ++o) mf[size_t(f) * n + o] = float(m[o]);
    }
    for (int f = 0; f < nFit; ++f) active[size_t(f)] = 1;   // LkAffine iterates every level
    VkDescriptorSet set = gpu_->set(pipe_, {&rb, &mb, &fb, &pb, &part});
    const Push pc{H, W, nFit, gpf};
    for (int it = 0; it < iters; ++it) {
      int* fr = fb.as<int>();
      float* pf = pb.as<float>();
      int nact = 0;
      for (int f = 0; f < nFit; ++f) {
        fr[f] = active[size_t(f)] ? fitRefAll[size_t(f)] : -1;
        nact += active[size_t(f)];
        for (int c = 0; c < 6; ++c) pf[f * 6 + c] = float(p[size_t(f)][size_t(c)]);
      }
      if (!nact) break;
      gpu_->begin();
      gpu_->dispatch(pipe_, set, &pc, uint32_t(gpf), uint32_t(nFit));
      if (!gpu_->submit()) return false;
      const float* P = part.as<float>();
      for (int f = 0; f < nFit; ++f) {
        if (!active[size_t(f)]) continue;
        double S[27] = {0};
        for (int g = 0; g < gpf; ++g)
          for (int c = 0; c < 27; ++c) S[c] += P[(size_t(f) * gpf + g) * 27 + c];
        double A[6][6], b[6], dp[6];
        int c = 0;
        for (int a = 0; a < 6; ++a)
          for (int bb = a; bb < 6; ++bb) A[a][bb] = A[bb][a] = S[c++];
        for (int a = 0; a < 6; ++a) { b[a] = S[21 + a]; A[a][a] += 1e-6; }
        if (!LkSolve6(A, b, dp)) { active[size_t(f)] = 0; continue; }
        for (int a = 0; a < 6; ++a) p[size_t(f)][size_t(a)] += dp[a];
        if (std::max(std::fabs(dp[2]), std::fabs(dp[5])) < 1e-3) active[size_t(f)] = 0;
      }
    }
    gpu_->resetSets();
    if (lv)
      for (int f = 0; f < nFit; ++f) { p[size_t(f)][2] *= 2; p[size_t(f)][5] *= 2; }
  }
  for (int f = 0; f < nFit; ++f) jobs[size_t(f)].p = p[size_t(f)];
  return true;
}

}  // namespace lkrl
