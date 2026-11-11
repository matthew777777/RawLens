// SPDX-License-Identifier: GPL-3.0-or-later
#include "lkr_solver.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>

#include "lkr_mhc.h"
#include "lkr_spv.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define LKR_FAIL(msg) (__android_log_print(ANDROID_LOG_ERROR, "RawLensLkr", "solve: %s", msg), false)
#else
#define LKR_FAIL(msg) (std::fprintf(stderr, "solve: %s\n", msg), false)
#endif
namespace lkr {

namespace {
// Separable running-sum box blur (radius r, clamped edges), in place.
void BoxBlur(float* a, int H, int W, int r, std::vector<float>& tmp) {
  tmp.resize(size_t(std::max(H, W)));
  for (int i = 0; i < H; ++i) {
    float* row = a + size_t(i) * W;
    double s = 0;
    for (int j = -r; j <= r; ++j) s += row[std::min(std::max(j, 0), W - 1)];
    for (int j = 0; j < W; ++j) {
      tmp[j] = float(s / (2 * r + 1));
      s += row[std::min(j + r + 1, W - 1)] - row[std::max(j - r, 0)];
    }
    std::copy(tmp.begin(), tmp.begin() + W, row);
  }
  for (int j = 0; j < W; ++j) {
    double s = 0;
    for (int i = -r; i <= r; ++i) s += a[size_t(std::min(std::max(i, 0), H - 1)) * W + j];
    for (int i = 0; i < H; ++i) {
      tmp[i] = float(s / (2 * r + 1));
      s += a[size_t(std::min(i + r + 1, H - 1)) * W + j] - a[size_t(std::max(i - r, 0)) * W + j];
    }
    for (int i = 0; i < H; ++i) a[size_t(i) * W + j] = tmp[i];
  }
}
// x += lowpass(z - x), per plane (two box passes ~ Gaussian).
void MeanPreserve(const float* z, float* x, int H, int W, int r) {
  const size_t HW = size_t(H) * W;
  std::vector<float> d(HW), tmp;
  for (int c = 0; c < 3; ++c) {
    for (size_t i = 0; i < HW; ++i) d[i] = z[c * HW + i] - x[c * HW + i];
    BoxBlur(d.data(), H, W, r, tmp);
    BoxBlur(d.data(), H, W, r, tmp);
    for (size_t i = 0; i < HW; ++i) x[c * HW + i] += d[i];
  }
}
}  // namespace

namespace {
// 0..1 shadow weight per HR pixel from the local luma of z (box r=4) and our
// noise model: ratio of relative noise at L to that at mid-grey 0.18,
// ramped over 1.5..5 (day: ~L 0.07 .. 0.015).
void ShadowWeight(const float* z, int H, int W, const SolveParams& prm, std::vector<float>* out) {
  const size_t HW = size_t(H) * W;
  const float q[3] = {0.25f * prm.wb[0], 0.5f * prm.wb[1], 0.25f * prm.wb[2]};
  std::vector<float> L(HW), tmp;
  for (size_t i = 0; i < HW; ++i) L[i] = q[0] * z[i] + q[1] * z[HW + i] + q[2] * z[2 * HW + i];
  BoxBlur(L.data(), H, W, 4, tmp);
  const float a = prm.noise_a, b = std::max(prm.noise_b, 1e-12f);
  const float rel_mid = std::sqrt(a * 0.18f + b) / 0.18f;
  out->resize(HW);
  for (size_t i = 0; i < HW; ++i) {
    const float l = std::max(L[i], 1e-5f);
    const float rho = std::sqrt(a * l + b) / l / rel_mid;
    (*out)[i] = std::min(std::max((rho - 1.5f) / 3.5f, 0.0f), 1.0f);
  }
}
}  // namespace

namespace {

struct DctPush {
  int32_t H, W, ny, nx, py, px, ixo, has_ns;
  float sigma2, lv_a, lv_b, k_y2, k_c2, wb_r, wb_g, wb_b;
  float dark_level, dark_cap, dark_luma;
};

struct Push {
  int32_t h, w, s, K;
  int32_t H, W, kOff, mode;
  float eta, mu, scale, invScale;
  float f0, f1, f2, f3;
  float rob_a, rob_b, rob_k, rob_on;
};
static_assert(sizeof(Push) == 80, "push layout");

// U^T r accumulates per HR pixel at most ~K * max|r|; 2^25 leaves +-64 of
// headroom in int32 at ~3e-8 resolution.
constexpr float kFixed = 33554432.0f;

uint32_t div_up(uint32_t a, uint32_t b) { return (a + b - 1) / b; }

double now_ms() {
  using namespace std::chrono;
  return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

}  // namespace

Solver::Solver(Gpu* gpu) : gpu_(gpu) {
  if (!gpu_ || !gpu_->ok()) return;
  dct_y_ = gpu_->pipeline(spv::dct_x_y, sizeof(spv::dct_x_y), 5, sizeof(DctPush));
  dct_u_ = gpu_->pipeline(spv::dct_x_u, sizeof(spv::dct_x_u), 5, sizeof(DctPush));
  dct_v_ = gpu_->pipeline(spv::dct_x_v, sizeof(spv::dct_x_v), 5, sizeof(DctPush));
  dct_yuv_ = gpu_->pipeline(spv::dct_yuv, sizeof(spv::dct_yuv), 1, sizeof(DctPush));
  resid_ = gpu_->pipeline(spv::resid_adj, sizeof(spv::resid_adj), 5, sizeof(Push));
  zstep_ = gpu_->pipeline(spv::zstep, sizeof(spv::zstep), 3, sizeof(Push));
  grad_ = gpu_->pipeline(spv::grad, sizeof(spv::grad), 2, sizeof(Push));
  jac_ = gpu_->pipeline(spv::jac, sizeof(spv::jac), 7, sizeof(Push));
  solve_ = gpu_->pipeline(spv::solve, sizeof(spv::solve), 2, sizeof(Push));
  residg_ = gpu_->pipeline(spv::resid, sizeof(spv::resid), 5, sizeof(Push));
  zgather_ = gpu_->pipeline(spv::zstep_gather, sizeof(spv::zstep_gather), 4, sizeof(Push));
  weight_ = gpu_->pipeline(spv::weight, sizeof(spv::weight), 5, sizeof(Push));
  scatter_ = gpu_->pipeline(spv::scatter, sizeof(spv::scatter), 4, sizeof(Push));
  jacblk_ = gpu_->pipeline(spv::jacblk, sizeof(spv::jacblk), 7, sizeof(Push));
  solveblk_ = gpu_->pipeline(spv::solveblk, sizeof(spv::solveblk), 3, sizeof(Push));
  wmin_ = gpu_->pipeline(spv::weight_min, sizeof(spv::weight_min), 5, sizeof(Push));
  wminh_ = gpu_->pipeline(spv::weight_minh, sizeof(spv::weight_minh), 3, sizeof(Push));
  radjw_ = gpu_->pipeline(spv::resid_adj_w, sizeof(spv::resid_adj_w), 6, sizeof(Push));
  wexp_ = gpu_->pipeline(spv::weight_e, sizeof(spv::weight_e), 3, sizeof(Push));
  wth_ = gpu_->pipeline(spv::weight_th, sizeof(spv::weight_th), 4, sizeof(Push));
  zgw_ = gpu_->pipeline(spv::zstep_gw, sizeof(spv::zstep_gw), 6, sizeof(Push));
  residq_ = gpu_->pipeline(spv::resid_q, sizeof(spv::resid_q), 5, sizeof(Push));
  radjwq_ = gpu_->pipeline(spv::resid_adj_w_q, sizeof(spv::resid_adj_w_q), 6, sizeof(Push));
  scatq_ = gpu_->pipeline(spv::scatter_q, sizeof(spv::scatter_q), 4, sizeof(Push));
  zpack_ = gpu_->pipeline(spv::zpack, sizeof(spv::zpack), 2, sizeof(Push));
  residzq_ = gpu_->pipeline(spv::resid_zq, sizeof(spv::resid_zq), 5, sizeof(Push));
  radjwzq_ = gpu_->pipeline(spv::resid_adj_w_zq, sizeof(spv::resid_adj_w_zq), 6, sizeof(Push));
  wtemp_ = gpu_->pipeline(spv::weight_t, sizeof(spv::weight_t), 5, sizeof(Push));
  ok_ = dct_y_.pipe && dct_u_.pipe && dct_v_.pipe && dct_yuv_.pipe && resid_.pipe && zstep_.pipe && grad_.pipe && jac_.pipe && solve_.pipe && residg_.pipe && zgather_.pipe &&
        weight_.pipe && scatter_.pipe && jacblk_.pipe && solveblk_.pipe && wmin_.pipe && wtemp_.pipe && wminh_.pipe && radjw_.pipe && wexp_.pipe && wth_.pipe && zgw_.pipe && residq_.pipe && radjwq_.pipe && scatq_.pipe && zpack_.pipe && residzq_.pipe && radjwzq_.pipe;
}

std::string Solver::profileNames() const {
  char b[512];
  std::snprintf(b, sizeof(b), "resid_adj=%p zstep=%p resid=%p zstep_gather=%p grad=%p jac=%p solve=%p",
                (const void*)&resid_, (const void*)&zstep_, (const void*)&residg_, (const void*)&zgather_,
                (const void*)&grad_, (const void*)&jac_, (const void*)&solve_);
  return b;
}

Buffer& Solver::Cached(int slot, size_t bytes, bool host_read) {
  if (int(cache_.size()) <= slot) cache_.resize(size_t(slot) + 1);
  Entry& e = cache_[size_t(slot)];
  if (!e.buf.map || e.bytes != bytes || e.host_read != host_read) {
    gpu_->free(e.buf);
    e.buf = gpu_->alloc(bytes, host_read);
    e.bytes = bytes;
    e.host_read = host_read;
  }
  return e.buf;
}

Solver::~Solver() {
  if (gpu_) for (Entry& e : cache_) gpu_->free(e.buf);
  if (!gpu_ || !gpu_->ok()) return;
  for (Pipeline* p : {&dct_y_, &dct_u_, &dct_v_, &dct_yuv_, &resid_, &zstep_, &grad_, &jac_, &solve_, &residg_, &zgather_, &weight_, &scatter_, &jacblk_, &solveblk_, &wmin_, &wtemp_, &wminh_, &radjw_, &wexp_, &wth_, &zgw_, &residq_, &radjwq_, &scatq_, &zpack_, &residzq_, &radjwzq_})
    gpu_->destroy(*p);
}

bool Solver::solve(const float* frames, int K, int h, int w, float* p, const SolveParams& prm,
                   const PriorFn& prior, std::vector<float>* x_out, SolveStats* st) {
  const double t_start = now_ms();
  SolveStats local;
  SolveStats& S = st ? *st : local;
  S = SolveStats{};
  const int s = prm.s, H = s * h, W = s * w;
  const size_t nLR = size_t(K) * h * w, nHR = size_t(3) * H * W;

  // Buffers come from a per-solver cache (same tile size -> allocated once per
  // worker instead of ~32 allocations per tile); slots in declaration order.
  int slot = 0;
  auto cached = [&](size_t bytes, bool host_read = false) -> Buffer& { return Cached(slot++, bytes, host_read); };
  Buffer& y = cached(nLR * 4), &pb = cached(size_t(K) * 6 * 4);
  // z, x live in GPU-friendly memory (host-cached memory is slow for GPU reads
  // on Mali); zc / xc are host-cached staging copies for the CPU side (prior
  // input/output, final result), moved by GPU buffer copies.
  Buffer& z = cached(nHR * 4), &x = cached(nHR * 4), &acc = cached(nHR * 4);
  Buffer& zc = cached(nHR * 4, true), &xc = cached(nHR * 4, true);
  Buffer& g = cached(nHR * 2 * 4);
  // jac.comp: 64 lanes x 8 pixels per group, 16 subgroup slots per group.
  const uint32_t G = div_up(uint32_t(h * w), 64 * 8), kSlots = 16;
  Buffer& part = cached(size_t(std::max(K - 1, 1)) * G * kSlots * 27 * 4);
  Buffer& v = cached(nHR * 4);
  Buffer& rr = cached(nLR * 4);   // residual (gather / robust paths)
  Buffer& rwb = cached(nLR * 4), &wtb = cached(nLR * 4);   // weighted residual, robust weight
  Buffer& wvb = cached(nLR * 4);                                 // valid * weight
  Buffer& wrawb = cached(nLR * 4), &rwtmp = cached(nLR * 4), &wvtmp = cached(nLR * 4);   // pre-spread weights
  // Temporal weights: z0 = reference-only start image, rrt its residuals.
  Buffer& z0 = cached(nHR * 4), &rrt = cached(nLR * 4), &wtr = cached(nLR * 4);
  // rwt2: vec4 row sums of the temporal test (weight_th); ebuf: per-sample robust terms (weight_e)
  Buffer& rwt2 = cached(nLR * 16), &wvt2 = cached(nLR * 4), &wmtmp = cached(nLR * 4);
  Buffer& ebuf = cached(nLR * 4);
  // Packed 2x2 taps of z / z0 (zpack.comp); only the off-by-default pack_taps path reads them.
  const size_t zq_bytes = prm.pack_taps ? nHR * 16 : 16;
  Buffer& zq = cached(zq_bytes), &z0q = cached(zq_bytes);
  { float* f = wtr.as<float>(); for (size_t i = 0; i < nLR; ++i) f[i] = 1.0f; }
  // Local motion field: K x nby x nbx vec2 (+ Jacobi scratch, block normal eqs).
  const int LB = prm.local_block > 0 ? prm.local_block : 16;
  const int nbx = (w + LB - 1) / LB, nby = (h + LB - 1) / LB, nb = nbx * nby;
  Buffer& dloc = cached(size_t(K) * nb * 8), &dtmp = cached(size_t(K) * nb * 8);
  Buffer& blk = cached(size_t(std::max(K - 1, 1)) * nb * 6 * 4);
  std::memset(dloc.map, 0, dloc.bytes);
  if (prm.local_init && prm.local_block > 0) std::memcpy(dloc.map, prm.local_init, size_t(K) * nb * 8);
  // Weighted coverage sum_k U_k^T (valid w) (acc2 -> cw) and plain coverage
  // U^T 1 (cov, = v after the L bound), host-cached copies for the prior map.
  Buffer& acc2 = cached(nHR * 4), &cw = cached(nHR * 4), &cov = cached(nHR * 4);
  Buffer& cwc = cached(nHR * 4, true), &covc = cached(nHR * 4, true);
  std::memset(acc2.map, 0, nHR * 4);
  if (!y.map || !pb.map || !z.map || !x.map || !acc.map || !g.map || !part.map || !v.map || !z0.map || !wtr.map ||
      !rrt.map || !acc2.map || !cwc.map || !covc.map)
    return LKR_FAIL("buffer allocation");

  std::memcpy(y.map, frames, nLR * 4);
  std::memcpy(pb.map, p, size_t(K) * 24);
  std::memset(acc.map, 0, nHR * 4);
  std::memset(part.map, 0, part.bytes);   // unused subgroup slots must read 0
  {  // z0 = x0 = bilinear upsample of the bilinear-demosaiced reference
    std::vector<float> rgb(size_t(3) * h * w);
    if (prm.mhc_init) MhcCoherentRggb(frames, h, w, rgb.data());   // = encoded-video MHC (MhcReference)
    else DemosaicBilinear(frames, h, w, rgb.data());
    std::vector<float> up(nHR);   // CPU-side copy: never read back from mapped GPU memory
    UpsampleBilinear(rgb.data(), 3, h, w, s, up.data());
    std::memcpy(z.map, up.data(), nHR * 4);
    std::memcpy(x.map, up.data(), nHR * 4);
    std::memcpy(z0.map, up.data(), nHR * 4);
  }

  const bool dct = prm.dct_prior && !prior;
  if (dct && ((H & 3) || (W & 3))) return LKR_FAIL("dct x-step needs H, W % 4 == 0");
  const int dct_ny = dct ? (H - 8) / 4 + 1 : 0, dct_nx = dct ? (W - 8) / 4 + 1 : 0;
  Buffer& dct_ns = cached(dct ? size_t(H) * W * 4 : 16), &dct_iw = cached(dct ? size_t(H + W + 8) * 4 : 16),
         &dct_nv = cached(dct ? size_t(dct_ny) * dct_nx * 4 : 16);
  if (dct) {   // inverse Hann overlap sum, per row then per column (vec4 slots)
    static const float win[8] = {0.03806023f, 0.30865828f, 0.69134172f, 0.96193977f,
                                 0.96193977f, 0.69134172f, 0.30865828f, 0.03806023f};
    float* iw = dct_iw.as<float>();
    auto axis = [&](int n, int blocks, float* out) {
      std::vector<float> sum(size_t(n), 0.0f);
      for (int b = 0; b < blocks; ++b)
        for (int i = 0; i < 8; ++i) sum[size_t(b * 4 + i)] += win[i];
      for (int i = 0; i < n; ++i) out[i] = 1.0f / sum[size_t(i)];
    };
    axis(H, dct_ny, iw);
    axis(W, dct_nx, iw + H);
  }

  gpu_->resetSets();
  VkDescriptorSet sDctY = VK_NULL_HANDLE, sDctU = VK_NULL_HANDLE, sDctV = VK_NULL_HANDLE, sDctC = VK_NULL_HANDLE;
  if (dct) {
    sDctY = gpu_->set(dct_y_, {&z, &x, &dct_ns, &dct_iw, &dct_nv});
    sDctU = gpu_->set(dct_u_, {&z, &x, &dct_ns, &dct_iw, &dct_nv});
    sDctV = gpu_->set(dct_v_, {&z, &x, &dct_ns, &dct_iw, &dct_nv});
    sDctC = gpu_->set(dct_yuv_, {&x});
  }
  VkDescriptorSet sResid = gpu_->set(resid_, {&z, &y, &pb, &acc, &dloc});
  VkDescriptorSet sZ = gpu_->set(zstep_, {&z, &x, &acc});
  VkDescriptorSet sGrad = gpu_->set(grad_, {&z, &g});
  VkDescriptorSet sJac = gpu_->set(jac_, {&z, &g, &y, &pb, &part, &wtb, &dloc});
  VkDescriptorSet sJB = gpu_->set(jacblk_, {&z, &g, &y, &pb, &blk, &wtb, &dloc});
  VkDescriptorSet sSB = gpu_->set(solveblk_, {&blk, &dloc, &dtmp});
  VkDescriptorSet sSolve = gpu_->set(solve_, {&part, &pb});
  VkDescriptorSet sVResid = gpu_->set(resid_, {&v, &y, &pb, &acc, &dloc});
  VkDescriptorSet sVZ = gpu_->set(zstep_, {&v, &x, &acc});

  Push pc{};
  pc.h = h; pc.w = w; pc.s = s; pc.K = K; pc.H = H; pc.W = W;
  pc.scale = kFixed; pc.invScale = 1.0f / kFixed;
  pc.kOff = prm.local_block;   // local motion block size (0 = off), see localD()
  const uint32_t gxLR = div_up(w, 16), gyLR = div_up(h, 16);
  const uint32_t gHR1 = div_up(uint32_t(nHR), 256);
  VkDescriptorSet sR = gpu_->set(residg_, {&z, &y, &pb, &rr, &dloc});
  VkDescriptorSet sZG = gpu_->set(zgather_, {&z, &x, &rr, &pb});
  VkDescriptorSet sVR = gpu_->set(residg_, {&v, &y, &pb, &rr, &dloc});
  VkDescriptorSet sVZG = gpu_->set(zgather_, {&v, &x, &rr, &pb});
  VkDescriptorSet sE = gpu_->set(wexp_, {&rr, &y, &ebuf});
  VkDescriptorSet sW = gpu_->set(weight_, {&rr, &ebuf, &rwb, &wtb, &wvb});
  VkDescriptorSet sW0 = gpu_->set(weight_, {&rr, &ebuf, &rwtmp, &wrawb, &wvtmp});      // raw weights for the spread
  VkDescriptorSet sWH = gpu_->set(wminh_, {&wrawb, &wtr, &wmtmp});
  VkDescriptorSet sWM = gpu_->set(wmin_, {&rr, &wmtmp, &rwb, &wtb, &wvb});
  VkDescriptorSet sRW = gpu_->set(radjw_, {&z, &y, &pb, &acc, &dloc, &wtb});
  VkDescriptorSet sZGW = gpu_->set(zgw_, {&z, &x, &rr, &pb, &dloc, &wtb});
  VkDescriptorSet sRT = gpu_->set(residg_, {&z0, &y, &pb, &rrt, &dloc});
  VkDescriptorSet sTH = gpu_->set(wth_, {&rrt, &y, &rwt2, &wvt2});
  VkDescriptorSet sWT = gpu_->set(wtemp_, {&rrt, &y, &rwt2, &wtr, &wvt2});
  const bool temporal = prm.temporal_k > 0.0f && prm.robust_spread > 0;
  VkDescriptorSet sS = gpu_->set(scatter_, {&rwb, &pb, &acc, &dloc});
  VkDescriptorSet sSW = gpu_->set(scatter_, {&wvb, &pb, &acc2, &dloc});       // weighted coverage
  // Quad variants (prm.quad): same buffers, one invocation per 2x2 quad.
  const bool quad = prm.quad && (w % 2 == 0) && (h % 2 == 0);
  const uint32_t gxQ = div_up(uint32_t(w / 2), 16), gyQ = div_up(uint32_t(h / 2), 16);
  VkDescriptorSet sRq = gpu_->set(residq_, {&z, &y, &pb, &rr, &dloc});
  VkDescriptorSet sRTq = gpu_->set(residq_, {&z0, &y, &pb, &rrt, &dloc});
  VkDescriptorSet sRWq = gpu_->set(radjwq_, {&z, &y, &pb, &acc, &dloc, &wtb});
  VkDescriptorSet sSq = gpu_->set(scatq_, {&rwb, &pb, &acc, &dloc});
  VkDescriptorSet sSWq = gpu_->set(scatq_, {&wvb, &pb, &acc2, &dloc});
  const bool packz = prm.pack_taps && !quad;
  VkDescriptorSet sZP = gpu_->set(zpack_, {&z, &zq}), sZ0P = gpu_->set(zpack_, {&z0, &z0q});
  VkDescriptorSet sRzq = gpu_->set(residzq_, {&zq, &y, &pb, &rr, &dloc});
  VkDescriptorSet sRTzq = gpu_->set(residzq_, {&z0q, &y, &pb, &rrt, &dloc});
  VkDescriptorSet sRWzq = gpu_->set(radjwzq_, {&zq, &y, &pb, &acc, &dloc, &wtb});
  const uint32_t gxP = div_up(uint32_t(W), 16), gyP = div_up(uint32_t(H), 16);
  auto d_pack = [&](VkDescriptorSet s_, const Push& qq) { gpu_->dispatch(zpack_, s_, &qq, gxP, gyP, 3); };
  auto d_resid = [&](VkDescriptorSet sp, VkDescriptorSet sq, const Push& qq, VkDescriptorSet szq) {
    if (quad) gpu_->dispatch(residq_, sq, &qq, gxQ, gyQ, K);
    else if (packz) gpu_->dispatch(residzq_, szq, &qq, gxLR, gyLR, K);
    else gpu_->dispatch(residg_, sp, &qq, gxLR, gyLR, K);
  };
  auto d_scatter = [&](VkDescriptorSet sp, VkDescriptorSet sq, const Push& qq) {
    if (quad) gpu_->dispatch(scatq_, sq, &qq, gxQ, gyQ, K); else gpu_->dispatch(scatter_, sp, &qq, gxLR, gyLR, K);
  };
  VkDescriptorSet sZW = gpu_->set(zstep_, {&cw, &x, &acc2});           // acc2 -> cw (mode 1)
  const uint32_t gxHR = div_up(W, 16), gyHR = div_up(H, 16);
  // One "data step" = residual + adjoint (+ z update): scatter or gather.
  auto data_step = [&](const Push& q, bool on_v, bool first = true) {
    if (!on_v && q.rob_on > 0.5f && prm.robust_spread > 0 && prm.robust_gather) {
      // gather: residual (first step: + weights), then the fused gather adjoint + z-update
      if (packz) d_pack(sZP, q);
      d_resid(sR, sRq, q, sRzq);
      if (first) {
        gpu_->dispatch(wexp_, sE, &q, gxLR, gyLR, K);
        gpu_->dispatch(weight_, sW0, &q, gxLR, gyLR, K);
        Push qm = q; qm.f3 = float(prm.robust_spread);
        gpu_->dispatch(wminh_, sWH, &qm, gxLR, gyLR, K);
        gpu_->dispatch(wmin_, sWM, &qm, gxLR, gyLR, K);
      }
      gpu_->dispatch(zgw_, sZGW, &q, gxHR, gyHR);
    } else if (!on_v && q.rob_on > 0.5f && prm.robust_spread > 0 && !first) {
      // z-steps 2..n: this iteration's weights, fused residual + weighted adjoint
      if (quad) gpu_->dispatch(radjwq_, sRWq, &q, gxQ, gyQ, K);
      else if (packz) { d_pack(sZP, q); gpu_->dispatch(radjwzq_, sRWzq, &q, gxLR, gyLR, K); }
      else gpu_->dispatch(radjw_, sRW, &q, gxLR, gyLR, K);
      gpu_->dispatch(zstep_, sZ, &q, gHR1);
    } else if (!on_v && q.rob_on > 0.5f) {      // robust: resid -> weight -> scatter -> zstep
      if (packz) d_pack(sZP, q);
      d_resid(sR, sRq, q, sRzq);
      if (prm.robust_spread > 0) {
        gpu_->dispatch(wexp_, sE, &q, gxLR, gyLR, K);
        gpu_->dispatch(weight_, sW0, &q, gxLR, gyLR, K);
        Push qm = q; qm.f3 = float(prm.robust_spread);
        gpu_->dispatch(wminh_, sWH, &qm, gxLR, gyLR, K);
        gpu_->dispatch(wmin_, sWM, &qm, gxLR, gyLR, K);
      } else {
        gpu_->dispatch(wexp_, sE, &q, gxLR, gyLR, K);
        gpu_->dispatch(weight_, sW, &q, gxLR, gyLR, K);
      }
      d_scatter(sS, sSq, q);
      gpu_->dispatch(zstep_, sZ, &q, gHR1);
    } else if (prm.gather) {
      gpu_->dispatch(residg_, on_v ? sVR : sR, &q, gxLR, gyLR, K);
      gpu_->dispatch(zgather_, on_v ? sVZG : sZG, &q, gxHR, gyHR);
    } else {
      gpu_->dispatch(resid_, on_v ? sVResid : sResid, &q, gxLR, gyLR, K);
      gpu_->dispatch(zstep_, on_v ? sVZ : sZ, &q, gHR1);
    }
  };

  // Operator norm L >= ||U^T U|| (masked).
  //  prm.L > 0: given.  prm.L == 0 (default): Schur bound
  //  ||U^T U|| <= ||U||_1 ||U||_inf = max(U^T 1), since each LR row of U sums
  //  to 1 -- one pass, deterministic, never below the true L, so
  //  eta = c/(L+mu) is stable for any c < 2.  prm.L < 0: power iteration
  //  started from U^T 1 (reference/diagnostics only: slow to converge).
  float L = prm.L;
  if (L <= 0.0f) {
    float* vv = v.as<float>();
    {
      gpu_->begin();
      Push q = pc; q.mode = 2;
      if (prm.gather) {
        gpu_->dispatch(residg_, sVR, &q, gxLR, gyLR, K);
        gpu_->dispatch(zgather_, sVZG, &q, gxHR, gyHR);
      } else {
        gpu_->dispatch(resid_, sVResid, &q, gxLR, gyLR, K);
        q.mode = 1;
        gpu_->dispatch(zstep_, sVZ, &q, gHR1);
      }
      gpu_->submit();
      ++S.submits;
    }
    if (prm.L == 0.0f) {
      float mx = 0.0f;
      for (size_t i = 0; i < nHR; ++i) mx = std::max(mx, vv[i]);
      L = mx;
    }
    const double target = std::sqrt(double(nHR));   // RMS 1 keeps fixed point accurate
    float prev = 0.0f;
    for (int it = 0; prm.L < 0.0f && it < prm.power_iters; ++it) {
      double n2 = 0;
      for (size_t i = 0; i < nHR; ++i) n2 += double(vv[i]) * vv[i];
      const float sc = float(target / std::sqrt(n2));
      for (size_t i = 0; i < nHR; ++i) vv[i] *= sc;
      gpu_->begin();
      Push q = pc; q.mode = 1;
      data_step(q, true);
      gpu_->submit();
      ++S.submits;
      double w2 = 0;
      for (size_t i = 0; i < nHR; ++i) w2 += double(vv[i]) * vv[i];
      L = float(std::sqrt(w2) / target);
      S.power_iters_used = it + 1;
      if (prm.power_tol > 0 && it > 0 && std::fabs(L - prev) <= prm.power_tol * L) break;
      prev = L;
    }
  }
  S.L = L;
  const bool eq = prm.equalize_noise && prm.robust && prm.noise_b > 0;
  if (eq) {   // plain coverage U^T 1 (v after the bound) for the contributing-frame ratio
    if (prm.L != 0.0f) {  // v not computed by the bound: compute U^T 1 now
      gpu_->begin();
      Push q = pc; q.mode = 2;
      if (prm.gather) {
        gpu_->dispatch(residg_, sVR, &q, gxLR, gyLR, K);
        gpu_->dispatch(zgather_, sVZG, &q, gxHR, gyHR);
      } else {
        gpu_->dispatch(resid_, sVResid, &q, gxLR, gyLR, K);
        q.mode = 1;
        gpu_->dispatch(zstep_, sVZ, &q, gHR1);
      }
      gpu_->submit();
    }
    gpu_->begin();
    gpu_->copy(v, covc, nHR * 4);
    gpu_->submit();
  }
  std::vector<float> nscale;   // per-HR-pixel noise factor for the prior / grain
  std::vector<float> shadow;   // 0..1 shadow weight of the last prior call (shadow cleanup)

  auto trace = [&](const std::string& stage, const float* data, size_t count) {
    if (prm.debug_prefix.empty()) return;
    FILE* f = std::fopen((prm.debug_prefix + "_" + stage + ".f32").c_str(), "wb");
    if (f) { std::fwrite(data, sizeof(float), count, f); std::fclose(f); }
  };
  if (!prm.debug_prefix.empty()) {
    trace("frames", frames, nLR);
    trace("reference_rgb", z0.as<float>(), nHR);
    trace("local_initial", dloc.as<float>(), size_t(K) * nb * 2);
    trace("motion_initial", p, size_t(K) * 6);
    FILE* f = std::fopen((prm.debug_prefix + "_meta.txt").c_str(), "w");
    if (f) {
      std::fprintf(f, "H=%d W=%d h=%d w=%d K=%d a=%.9g b=%.9g sigma=%.9g gain=%.9g grain=%.9g wb=%.9g,%.9g,%.9g\n",
          H, W, h, w, K, prm.noise_a, prm.noise_b, prm.sigma_noise, prm.prior_gain, prm.grain,
          prm.wb[0], prm.wb[1], prm.wb[2]);
      std::fclose(f);
    }
  }

  // Schedule (DPIR-style): sigma_t geometric from sigma_max to sigma_end.
  std::vector<float> sig(prm.T);
  for (int t = 0; t < prm.T; ++t)
    sig[t] = prm.T == 1 ? prm.sigma_max
                        : float(prm.sigma_max * std::pow(double(prm.sigma_end) / prm.sigma_max,
                                                          double(t) / (prm.T - 1)));
  auto prior_here = [&](int t) {
    return prm.prior_at.empty() ||
           std::find(prm.prior_at.begin(), prm.prior_at.end(), t) != prm.prior_at.end();
  };

  bool x_from_staging = false;
  bool z0_packed = false;
  for (int t = 0; t < prm.T; ++t) {
    const float mu = float(prm.lam * double(prm.sigma_noise) * prm.sigma_noise / (double(sig[t]) * sig[t]));
    Push q = pc;
    q.mode = 0;
    q.mu = mu;
    q.eta = prm.step_scale / (L + mu);
    q.f0 = 1e-9f;
    q.f1 = float(G * kSlots);
    q.f2 = prm.clip;
    q.rob_a = prm.noise_a;
    q.rob_b = prm.noise_b;
    q.rob_k = prm.robust_k;
    q.f3 = prm.robust_tau;
    q.rob_on = (prm.robust && prm.noise_b > 0 && t >= prm.robust_start) ? std::max(1.0f, prm.robust_pow) : 0.0f;
    const double tg = now_ms();
    gpu_->begin();
    if (x_from_staging) { gpu_->copy(xc, x, nHR * 4); x_from_staging = false; }   // previous prior's output
    if (temporal && q.rob_on > 0.5f) {                             // temporal weights, current motion
      Push qt = q; qt.rob_k = prm.temporal_k; qt.f3 = prm.temporal_tau;
      if (packz && !z0_packed) { d_pack(sZ0P, qt); z0_packed = true; }   // z0 is fixed: pack once
      d_resid(sRT, sRTq, qt, sRTzq);
      Push qd = qt; qd.f0 = prm.temporal_detail;
      qd.f1 = prm.temporal_dark_wide ? 1.0f : 0.0f;
      qd.f2 = prm.temporal_dark_level;
      gpu_->dispatch(wth_, sTH, &qd, gxLR, gyLR, K);
      gpu_->dispatch(wtemp_, sWT, &qd, gxLR, gyLR, K);
    }
    for (int zs = 0; zs < prm.z_steps; ++zs) data_step(q, false, zs == 0);  // Eq. (5)
    const bool refine_now = prm.motion_every <= 1 || t < prm.motion_warmup ||
                            ((t - prm.motion_warmup) % prm.motion_every) == prm.motion_every - 1;
    if (prm.refine_motion && K > 1 && refine_now) {                // Eq. (7)
      gpu_->dispatch(grad_, sGrad, &q, div_up(W, 16), div_up(H, 16), 3);
      gpu_->dispatch(jac_, sJac, &q, G, K - 1);
      gpu_->dispatch(solve_, sSolve, &q, K - 1);
      if (prm.local_block > 0) {                                   // local block translations
        gpu_->dispatch(jacblk_, sJB, &q, nbx, nby, K - 1);
        Push qb = q;
        qb.f0 = prm.local_lambda;
        qb.f1 = prm.local_floor;
        gpu_->dispatch(solveblk_, sSB, &qb, div_up(uint32_t(K * nb), 64));
        gpu_->copy(dtmp, dloc, dloc.bytes);
      }
    }
    const bool use_prior = prior_here(t);
    if (use_prior && !prior && !dct) gpu_->copy(z, x, nHR * 4);    // 'none': x = z
    if (use_prior && (prior || dct)) gpu_->copy(z, zc, nHR * 4);            // prior input to the CPU side
    if (use_prior && eq && q.rob_on > 0.5f) {             // weighted coverage of this iteration
      d_scatter(sSW, sSWq, q);
      Push q1 = q; q1.mode = 1;
      gpu_->dispatch(zstep_, sZW, &q1, gHR1);
      gpu_->copy(cw, cwc, nHR * 4);
    }
    if (!gpu_->submit()) {
      char m[96];
      std::snprintf(m, sizeof(m), "iteration %d submit, vk %d, wtemp pipe %p", t, gpu_->last_error, (void*)wtemp_.pipe);
      return LKR_FAIL(m);
    }
    ++S.submits;
    S.gpu_ms += now_ms() - tg;
    // Coverage is also needed by no-prior motion-region Bayer cleanup.
    if (use_prior) {
      if (eq && q.rob_on > 0.5f) {
        // f = sqrt(cov / cov_w) over the three planes: 1 where every frame
        // contributed, sqrt(K) where only the reference did.
        nscale.resize(size_t(H) * W);
        const float* c1 = covc.as<float>();
        const float* cwp = cwc.as<float>();
        const size_t HW = size_t(H) * W;
        for (size_t i = 0; i < HW; ++i) {
          const float a = c1[i] + c1[HW + i] + c1[2 * HW + i];
          const float b = cwp[i] + cwp[HW + i] + cwp[2 * HW + i];
          // Only where clearly fewer frames contributed (rho < 0.8), capped at
          // sqrt(K) (reference only): static areas stay exactly at 1.
          const float rho = a > 0 ? std::min(b / a, 1.0f) : 1.0f;
          nscale[i] = std::min(std::sqrt(std::max(0.8f / std::max(rho, 1e-3f), 1.0f)), std::sqrt(float(K)));
        }
        if (const char* dump = std::getenv("LKR_DUMP_NS")) {
          FILE* f = std::fopen(dump, "wb");
          if (f) { std::fwrite(nscale.data(), 4, nscale.size(), f); std::fclose(f); }
        }
        if (std::getenv("LKR_DEBUG_NS")) {
          float mx = 1, mean = 0;
          size_t over = 0;
          for (float f : nscale) { mx = std::max(mx, f); mean += f; over += f > 1.2f; }
          std::fprintf(stderr, "t=%d noise-scale mean %.3f max %.2f  >1.2: %.2f%%\n", t, mean / nscale.size(), mx,
                       100.0 * over / nscale.size());
        }
      }
    }
    if (use_prior && (prior || dct)) {                             // PnP x-step
      const double tp = now_ms();
      const float* ns = nullptr;
      if (!nscale.empty()) ns = nscale.data();
      std::vector<float> nse;
      if (ns && prm.equalize_strength < 1.0f) {   // noise-level-aware equalisation strength
        nse.resize(size_t(H) * W);
        for (size_t i = 0; i < nse.size(); ++i) nse[i] = 1.0f + prm.equalize_strength * (ns[i] - 1.0f);
        ns = nse.data();
      }
      std::vector<float> nsh;
      // Chroma cleanup needs its mask even when the independent luma boost
      // is disabled (the default, to preserve grass / foliage texture).
      if ((prm.shadow_boost > 0.0f || prm.shadow_chroma > 0.0f) &&
          (prm.noise_a > 0.0f || prm.noise_b > 0.0f)) {
        ShadowWeight(zc.as<float>(), H, W, prm, &shadow);
        if (prm.shadow_boost > 0.0f) {
          nsh.resize(shadow.size());
          for (size_t i = 0; i < nsh.size(); ++i) nsh[i] = (ns ? ns[i] : 1.0f) * (1.0f + prm.shadow_boost * shadow[i]);
          ns = nsh.data();
        }
      }
      trace("iter" + std::to_string(t) + "_z", zc.as<float>(), nHR);
      if (dct) {
        if (ns) std::memcpy(dct_ns.map, ns, size_t(H) * W * 4);
        const float sg = sig[t] * prm.prior_gain;
        const float denom = prm.noise_a * 0.18f + prm.noise_b;
        DctPush d{H, W, dct_ny, dct_nx, 0, 0, H / 4, ns ? 1 : 0, sg * sg,
                  denom > 0 ? prm.noise_a / denom : 0.0f, denom > 0 ? prm.noise_b / denom : 1.0f,
                  prm.dct_luma * prm.dct_luma, prm.dct_chroma * prm.dct_chroma,
                  prm.wb[0], prm.wb[1], prm.wb[2], prm.dct_dark_level, prm.dct_dark_cap, prm.dct_dark_luma};
        gpu_->begin();
        gpu_->fill(x, 0);
        const std::pair<const Pipeline*, VkDescriptorSet> chans[3] = {{&dct_y_, sDctY}, {&dct_u_, sDctU}, {&dct_v_, sDctV}};
        for (const auto& c : chans)
          for (d.py = 0; d.py < 2; ++d.py)
            for (d.px = 0; d.px < 2; ++d.px)
              gpu_->dispatch(*c.first, c.second, &d, uint32_t((dct_nx + 1) / 2 + 3) / 4, uint32_t((dct_ny + 1) / 2 + 3) / 4);
        gpu_->dispatch(dct_yuv_, sDctC, &d, uint32_t(W / 4 + 15) / 16, uint32_t(H + 15) / 16);
        gpu_->copy(x, xc, nHR * 4);
        if (!gpu_->submit()) return LKR_FAIL("dct x-step");
        ++S.submits;
      } else if (!prior(zc.as<float>(), xc.as<float>(), H, W, sig[t] * prm.prior_gain, ns)) {
        return LKR_FAIL("prior");
      }
      trace("iter" + std::to_string(t) + "_network", xc.as<float>(), nHR);
      if (prm.mean_preserve > 0) MeanPreserve(zc.as<float>(), xc.as<float>(), H, W, prm.mean_preserve);
      trace("iter" + std::to_string(t) + "_x", xc.as<float>(), nHR);
      trace("iter" + std::to_string(t) + "_weights", wtb.as<float>(), nLR);
      trace("iter" + std::to_string(t) + "_temporal", wtr.as<float>(), nLR);
      trace("iter" + std::to_string(t) + "_temporal_residual", rrt.as<float>(), nLR);
      trace("iter" + std::to_string(t) + "_temporal_rows", rwt2.as<float>(), nLR * 4);
      trace("iter" + std::to_string(t) + "_motion", pb.as<float>(), size_t(K) * 6);
      x_from_staging = true;
      S.prior_ms += now_ms() - tp;
      ++S.prior_calls;
    }
  }

  // Final z, x to the CPU (x is already in xc when the last step was a prior).
  gpu_->begin();
  gpu_->copy(z, zc, nHR * 4);
  if (!x_from_staging) gpu_->copy(x, xc, nHR * 4);
  if (!gpu_->submit()) return LKR_FAIL("final submit");
  x_out->assign(xc.as<float>(), xc.as<float>() + nHR);
  if (const char* dd = std::getenv("LKR_DUMP_WT")) {   // diagnostics: temporal + final robust weights
    gpu_->begin(); gpu_->submit();
    FILE* f = std::fopen((std::string(dd) + "_wtr.f32").c_str(), "wb");
    if (f) { std::fwrite(wtr.as<float>(), 4, nLR, f); std::fclose(f); }
    f = std::fopen((std::string(dd) + "_wt.f32").c_str(), "wb");
    if (f) { std::fwrite(wtb.as<float>(), 4, nLR, f); std::fclose(f); }
  }
  if (const char* dd = std::getenv("LKR_DUMP_ZX")) {   // diagnostics: data fit z and prior output x (planar)
    FILE* f = std::fopen((std::string(dd) + "_z.f32").c_str(), "wb");
    if (f) { std::fwrite(zc.as<float>(), 4, nHR, f); std::fclose(f); }
    f = std::fopen((std::string(dd) + "_x.f32").c_str(), "wb");
    if (f) { std::fwrite(xc.as<float>(), 4, nHR, f); std::fclose(f); }
  }
  if (prm.debug_maps) {   // diagnostics, HR planes (LR maps by nearest sample)
    gpu_->begin(); gpu_->submit();
    const size_t HWh = size_t(H) * W;
    S.dbg.assign(size_t(kDebugMaps) * HWh, 0.0f);
    const float* wt = wtb.as<float>();
    const float* tr = wtr.as<float>();
    const float* dl = dloc.as<float>();
    const int LBd = prm.local_block > 0 ? prm.local_block : 16;
    const int nbxd = (w + LBd - 1) / LBd;
    for (int Y = 0; Y < H; ++Y)
      for (int X = 0; X < W; ++X) {
        const int i = std::min(Y / s, h - 1), j = std::min(X / s, w - 1);
        float eff = 1.0f, tm = 0.0f, dm = 0.0f;
        for (int k = 1; k < K; ++k) {
          const size_t o = (size_t(k) * h + i) * w + j;
          eff += prm.robust ? wt[o] : 1.0f;
          tm += tr[o];
          if (prm.local_block > 0) {
            const size_t b = (size_t(k) * ((h + LBd - 1) / LBd) + i / LBd) * nbxd + j / LBd;
            dm = std::max(dm, std::hypot(dl[2 * b], dl[2 * b + 1]));
          }
        }
        const size_t p = size_t(Y) * W + X;
        S.dbg[p] = eff;
        S.dbg[HWh + p] = K > 1 ? tm / float(K - 1) : 1.0f;
        S.dbg[2 * HWh + p] = nscale.empty() ? 1.0f : nscale[p];
        S.dbg[3 * HWh + p] = shadow.empty() ? 0.0f : shadow[p];
        S.dbg[4 * HWh + p] = dm;
      }
  }
  trace("local_final", dloc.as<float>(), size_t(K) * nb * 2);
  trace("final_z", zc.as<float>(), nHR);
  trace("final_x", x_out->data(), nHR);
  FinishLook(zc.as<float>(), x_out->data(), H, W, prm, nscale.empty() ? nullptr : nscale.data(),
             shadow.empty() ? nullptr : shadow.data());
  trace("final_look", x_out->data(), nHR);
  std::memcpy(p, pb.map, size_t(K) * 24);
  S.dispatches = gpu_->dispatches();
  if (prm.local_out && prm.local_block > 0) std::memcpy(prm.local_out, dloc.map, size_t(K) * nb * 8);
  S.total_ms = now_ms() - t_start;
  return true;
}

// Removes the three Bayer-period components (-1)^x, (-1)^y, (-1)^(x+y) of a
// plane: demodulate, B3 low-pass (local amplitude), remodulate, subtract.
// Random grain and real texture are barely touched; periodic zipper/maze
// structure left by the CFA sampling is removed.
static void NotchBayerPeriod(float* P, int H, int W) {
  std::vector<float> m(size_t(H) * W), t(size_t(H) * W);
  // B3 spline [1 4 6 4 1]/16: symmetric (no shift) and exactly zero at the
  // Bayer period, so the demodulated amplitude carries no image DC.
  static const float k5[5] = {1 / 16.f, 4 / 16.f, 6 / 16.f, 4 / 16.f, 1 / 16.f};
  auto refl = [](int i, int n) { return i < 0 ? -i : (i >= n ? 2 * n - 2 - i : i); };
  auto box5 = [&](std::vector<float>& a) {
    for (int y = 0; y < H; ++y) {
      const float* r = a.data() + size_t(y) * W;
      for (int x = 0; x < W; ++x) {
        float s = 0;
        for (int d = -2; d <= 2; ++d) s += k5[d + 2] * r[refl(x + d, W)];
        t[size_t(y) * W + x] = s;
      }
    }
    for (int y = 0; y < H; ++y)
      for (int x = 0; x < W; ++x) {
        float s = 0;
        for (int d = -2; d <= 2; ++d) s += k5[d + 2] * t[size_t(refl(y + d, H)) * W + x];
        a[size_t(y) * W + x] = s;
      }
  };
  for (int mode = 0; mode < 3; ++mode) {
    auto sg = [mode](int x, int y) { return ((mode == 0 ? x : mode == 1 ? y : x + y) & 1) ? -1.0f : 1.0f; };
    for (int y = 0; y < H; ++y)
      for (int x = 0; x < W; ++x) m[size_t(y) * W + x] = P[size_t(y) * W + x] * sg(x, y);
    box5(m);
    for (int y = 0; y < H; ++y)
      for (int x = 0; x < W; ++x) P[size_t(y) * W + x] -= m[size_t(y) * W + x] * sg(x, y);
  }
}

// DSLR-look finish (not in the paper): colour of x without Bayer-period
// structure, plus luminance-only grain = grain * notch(L(z - x)).
void FinishLook(const float* z, float* x, int H, int W, const SolveParams& prm, const float* noise_scale,
                const float* shadow) {
  if (!prm.notch && prm.grain <= 0.0f && !shadow && !prm.highlight_desat) return;
  const size_t HW = size_t(H) * W;
  const float q[3] = {0.25f * prm.wb[0], 0.5f * prm.wb[1], 0.25f * prm.wb[2]};
  std::vector<float> L(HW), C(HW);
  // Clip halo (IMG_20261107_132932 chrome glints): the Bayer-period notches
  // ring around a clipped point -- negative (black) pixels 1-3 px away -- and
  // pull the clipped core below the clip level, so the neutraliser below
  // missed it and it stayed magenta (raw-clipped R,B times wb). Keep the
  // solved values within 3 px of any near-clipped pixel.
  std::vector<uint8_t> halo;
  std::vector<float> keep;
  if (prm.notch) {
    std::vector<uint8_t> hot(HW, 0);
    bool any = false;
    for (size_t i = 0; i < HW; ++i)
      if (std::max(x[i], std::max(x[HW + i], x[2 * HW + i])) >= 0.92f * prm.clip) { hot[i] = 1; any = true; }
    if (any) {
      halo.assign(HW, 0);
      constexpr int r = 3;
      for (int Y = 0; Y < H; ++Y)
        for (int X = 0; X < W; ++X) {
          if (!hot[size_t(Y) * W + X]) continue;
          for (int dy = -r; dy <= r; ++dy)
            for (int dx = -r; dx <= r; ++dx) {
              const int yy = Y + dy, xx = X + dx;
              if (yy >= 0 && yy < H && xx >= 0 && xx < W) halo[size_t(yy) * W + xx] = 1;
            }
        }
      keep.assign(x, x + 3 * HW);
    }
  }
  if (prm.notch) {
    for (size_t i = 0; i < HW; ++i) L[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    for (int c = 0; c < 3; ++c) {
      for (size_t i = 0; i < HW; ++i) C[i] = prm.wb[c] * x[c * HW + i] - L[i];
      NotchBayerPeriod(C.data(), H, W);
      for (size_t i = 0; i < HW; ++i) x[c * HW + i] = (L[i] + C[i]) / prm.wb[c];
    }
  }
  // Luma Bayer-period notch ONLY where frames were rejected: there each
  // Bayer site is measured by a different subset of frames, which leaves a
  // regular site-to-site brightness pattern (dot grid on moving grass/cars,
  // measured in the data fit itself). Strength follows the contributing-frame
  // factor: 0 where every frame contributed (static detail untouched).
  if (prm.notch && noise_scale) {
    std::vector<float> Lx(HW), Ln;
    for (size_t i = 0; i < HW; ++i) Lx[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    Ln = Lx;
    NotchBayerPeriod(Ln.data(), H, W);
    for (size_t i = 0; i < HW; ++i) {
      const float t = std::min(std::max((noise_scale[i] - 1.0f) / 0.4142f, 0.0f), 1.0f);   // full at f >= sqrt(2)
      if (t <= 0.0f) continue;
      const float d = t * (Ln[i] - Lx[i]);
      for (int c = 0; c < 3; ++c) x[c * HW + i] += d / prm.wb[c];
    }
  }
  if (!halo.empty())
    for (size_t i = 0; i < HW; ++i)
      if (halo[i]) for (int c = 0; c < 3; ++c) x[c * HW + i] = keep[c * HW + i];
  if (prm.grain > 0.0f) {
    for (size_t i = 0; i < HW; ++i)
      L[i] = q[0] * (z[i] - x[i]) + q[1] * (z[HW + i] - x[HW + i]) + q[2] * (z[2 * HW + i] - x[2 * HW + i]);
    if (prm.notch) NotchBayerPeriod(L.data(), H, W);
    // Grain where fewer frames contributed is noisier by noise_scale: return
    // proportionally less, so the grain level matches across the image.
    for (int c = 0; c < 3; ++c)
      for (size_t i = 0; i < HW; ++i)
        x[c * HW + i] += prm.grain * L[i] / (noise_scale ? 1.0f + prm.equalize_strength * (noise_scale[i] - 1.0f) : 1.0f) /
                         (shadow ? 1.0f + prm.shadow_boost * shadow[i] : 1.0f) / prm.wb[c];
  }
  // Shadow chroma: blend chroma (wb-space, minus luma) toward its local mean
  // (two box passes r=3) by shadow_chroma * shadow weight. Luma untouched.
  if (shadow && prm.shadow_chroma > 0.0f) {
    for (size_t i = 0; i < HW; ++i) L[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    std::vector<float> tmp;
    std::vector<float> Cc[3];
    for (int c = 0; c < 3; ++c) {
      Cc[c].resize(HW);
      for (size_t i = 0; i < HW; ++i) Cc[c][i] = prm.wb[c] * x[c * HW + i] - L[i];
      C = Cc[c];
      BoxBlur(C.data(), H, W, 3, tmp);
      BoxBlur(C.data(), H, W, 3, tmp);
      for (size_t i = 0; i < HW; ++i) {
        const float t = prm.shadow_chroma * shadow[i];
        x[c * HW + i] = (L[i] + Cc[c][i] + t * (C[i] - Cc[c][i])) / prm.wb[c];
      }
    }
  }
  // Neutralise only pixels with no remaining unsaturated colour channel.
  // One-channel clipping is common in sunsets: the other channels still
  // contain valid colour. Do not expand this mask into coloured neighbours.
  if (prm.highlight_desat) {
    const float lo = 0.92f * prm.clip, hi = prm.clip;
    std::vector<float> m(HW);
    for (size_t i = 0; i < HW; ++i) {
      const float mn = std::min(x[i], std::min(x[HW + i], x[2 * HW + i]));
      m[i] = std::clamp((mn - lo) / std::max(hi - lo, 1e-6f), 0.0f, 1.0f);
    }
    for (size_t i = 0; i < HW; ++i) {
      if (m[i] <= 0.0f) continue;
      const float L = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];   // wb-space luma
      for (int c = 0; c < 3; ++c) {
        const float neutral = L / prm.wb[c];
        x[c * HW + i] += m[i] * (neutral - x[c * HW + i]);
      }
    }
  }
}

// scipy.ndimage.convolve(raw*mask, k, mode='mirror') / convolve(mask, k):
// 'mirror' reflects about the edge sample (d c b | a b c d | c b a).
void DemosaicBilinear(const float* raw, int h, int w, float* rgb) {
  static const float k[3][3] = {{0.25f, 0.5f, 0.25f}, {0.5f, 1.0f, 0.5f}, {0.25f, 0.5f, 0.25f}};
  auto mir = [](int i, int n) { return i < 0 ? -i : (i >= n ? 2 * n - 2 - i : i); };
  auto chan = [](int i, int j) {
    bool ei = (i & 1) == 0, ej = (j & 1) == 0;
    return (ei && ej) ? 0 : ((!ei && !ej) ? 2 : 1);
  };
  for (int i = 0; i < h; ++i)
    for (int j = 0; j < w; ++j) {
      double num[3] = {0, 0, 0}, den[3] = {0, 0, 0};
      for (int di = -1; di <= 1; ++di)
        for (int dj = -1; dj <= 1; ++dj) {
          const int ii = mir(i + di, h), jj = mir(j + dj, w);
          const int c = chan(ii, jj);
          const float kv = k[di + 1][dj + 1];
          num[c] += double(kv) * raw[size_t(ii) * w + jj];
          den[c] += kv;
        }
      for (int c = 0; c < 3; ++c) rgb[(size_t(c) * h + i) * w + j] = float(num[c] / den[c]);
    }
}

// torch.nn.functional.interpolate(mode='bilinear', align_corners=False):
// src = (dst + 0.5) / s - 0.5, clamped at 0; upper neighbour clamped to n-1.
void UpsampleBilinear(const float* src, int C, int h, int w, int s, float* dst) {
  const int H = s * h, W = s * w;
  for (int c = 0; c < C; ++c)
    for (int Y = 0; Y < H; ++Y) {
      float sy = std::max((Y + 0.5f) / s - 0.5f, 0.0f);
      int y0 = int(sy), y1 = std::min(y0 + 1, h - 1);
      float ay = sy - y0;
      for (int X = 0; X < W; ++X) {
        float sx = std::max((X + 0.5f) / s - 0.5f, 0.0f);
        int x0 = int(sx), x1 = std::min(x0 + 1, w - 1);
        float ax = sx - x0;
        const float* P = src + size_t(c) * h * w;
        float top = P[y0 * w + x0] * (1 - ax) + P[y0 * w + x1] * ax;
        float bot = P[y1 * w + x0] * (1 - ax) + P[y1 * w + x1] * ax;
        dst[(size_t(c) * H + Y) * W + X] = top * (1 - ay) + bot * ay;
      }
    }
}

}  // namespace lkr
