// SPDX-License-Identifier: Apache-2.0
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
  // Only what every solve uses. The x-step and the A/B variants are created on first use
  // (Optional()): compiling all 30 shaders cost 2-3 s per worker on a Mali-G615, every merge.
  resid_ = gpu_->pipeline(spv::resid_adj, sizeof(spv::resid_adj), 5, sizeof(Push));
  zstep_ = gpu_->pipeline(spv::zstep, sizeof(spv::zstep), 3, sizeof(Push));
  jac_ = gpu_->pipeline(spv::jac, sizeof(spv::jac), 8, sizeof(Push));
  solve_ = gpu_->pipeline(spv::solve, sizeof(spv::solve), 2, sizeof(Push));
  residg_ = gpu_->pipeline(spv::resid, sizeof(spv::resid), 5, sizeof(Push));
  weight_ = gpu_->pipeline(spv::weight, sizeof(spv::weight), 5, sizeof(Push));
  scatter_ = gpu_->pipeline(spv::scatter, sizeof(spv::scatter), 4, sizeof(Push));
  jacblk_ = gpu_->pipeline(spv::jacblk, sizeof(spv::jacblk), 8, sizeof(Push));
  solveblk_ = gpu_->pipeline(spv::solveblk, sizeof(spv::solveblk), 3, sizeof(Push));
  wmin_ = gpu_->pipeline(spv::weight_min, sizeof(spv::weight_min), 5, sizeof(Push));
  wminh_ = gpu_->pipeline(spv::weight_minh, sizeof(spv::weight_minh), 3, sizeof(Push));
  radjw_ = gpu_->pipeline(spv::resid_adj_w, sizeof(spv::resid_adj_w), 6, sizeof(Push));
  wexp_ = gpu_->pipeline(spv::weight_e, sizeof(spv::weight_e), 3, sizeof(Push));
  wth_ = gpu_->pipeline(spv::weight_th, sizeof(spv::weight_th), 5, sizeof(Push));
  wtdet_ = gpu_->pipeline(spv::weight_tdet, sizeof(spv::weight_tdet), 3, sizeof(Push));
  wtemp_ = gpu_->pipeline(spv::weight_t, sizeof(spv::weight_t), 6, sizeof(Push));
  wdarkv_ = gpu_->pipeline(spv::weight_tv, sizeof(spv::weight_tv), 8, sizeof(Push));
  wdark_ = gpu_->pipeline(spv::weight_td, sizeof(spv::weight_td), 8, sizeof(Push));
  ok_ = resid_.pipe && zstep_.pipe && jac_.pipe && solve_.pipe && residg_.pipe && weight_.pipe && scatter_.pipe &&
        jacblk_.pipe && solveblk_.pipe && wmin_.pipe && wminh_.pipe && radjw_.pipe && wexp_.pipe && wth_.pipe &&
        wtemp_.pipe && wdark_.pipe && wdarkv_.pipe && wtdet_.pipe;
}

bool Solver::Optional(const SolveParams& prm, bool dct) {
  auto need = [&](Pipeline& p, const uint32_t* code, size_t bytes, uint32_t nbind, uint32_t push) {
    if (!p.pipe) p = gpu_->pipeline(code, bytes, nbind, push);
    return p.pipe != VK_NULL_HANDLE;
  };
  bool ok = true;
  if (prm.guide_confidence > 0.0f || prm.trusted_static[2] > 0.0f)
    ok = need(guide_stats_, spv::guide_stats, sizeof(spv::guide_stats), 3, sizeof(Push)) &&
         need(weight_guide_, spv::weight_guide, sizeof(spv::weight_guide), 6, sizeof(Push));
  if (dct)
    ok = need(dct_y_, spv::dct_x_y, sizeof(spv::dct_x_y), 5, sizeof(DctPush)) &&
         need(dct_u_, spv::dct_x_u, sizeof(spv::dct_x_u), 5, sizeof(DctPush)) &&
         need(dct_v_, spv::dct_x_v, sizeof(spv::dct_x_v), 5, sizeof(DctPush)) &&
         need(dct_yuv_, spv::dct_yuv, sizeof(spv::dct_yuv), 1, sizeof(DctPush));
  if (prm.gather) ok = ok && need(zgather_, spv::zstep_gather, sizeof(spv::zstep_gather), 4, sizeof(Push));
  if (prm.robust_gather) ok = ok && need(zgw_, spv::zstep_gw, sizeof(spv::zstep_gw), 6, sizeof(Push));
  if (prm.quad)
    ok = ok && need(residq_, spv::resid_q, sizeof(spv::resid_q), 5, sizeof(Push)) &&
         need(radjwq_, spv::resid_adj_w_q, sizeof(spv::resid_adj_w_q), 6, sizeof(Push)) &&
         need(scatq_, spv::scatter_q, sizeof(spv::scatter_q), 4, sizeof(Push));
  if (prm.half_taps)   // same slots and bindings as the packed-tap variant
    ok = ok && need(zpack_, spv::zhalf, sizeof(spv::zhalf), 2, sizeof(Push)) &&
         need(residzq_, spv::resid_zh, sizeof(spv::resid_zh), 5, sizeof(Push)) &&
         need(radjwzq_, spv::resid_adj_w_zh, sizeof(spv::resid_adj_w_zh), 6, sizeof(Push));
  else if (prm.pack_taps)
    ok = ok && need(zpack_, spv::zpack, sizeof(spv::zpack), 2, sizeof(Push)) &&
         need(residzq_, spv::resid_zq, sizeof(spv::resid_zq), 5, sizeof(Push)) &&
         need(radjwzq_, spv::resid_adj_w_zq, sizeof(spv::resid_adj_w_zq), 6, sizeof(Push));
  return ok;
}

std::string Solver::profileNames() const {
  const std::pair<const char*, const Pipeline*> all[] = {
      {"resid_adj", &resid_}, {"zstep", &zstep_}, {"resid", &residg_}, {"zstep_gather", &zgather_},
      {"jac", &jac_}, {"solve", &solve_}, {"weight", &weight_}, {"scatter", &scatter_}, {"jacblk", &jacblk_},
      {"solveblk", &solveblk_}, {"weight_min", &wmin_}, {"weight_minh", &wminh_}, {"resid_adj_w", &radjw_},
      {"weight_e", &wexp_}, {"weight_th", &wth_}, {"weight_tdet", &wtdet_}, {"weight_t", &wtemp_}, {"weight_td", &wdark_}, {"weight_tv", &wdarkv_}, {"zstep_gw", &zgw_}, {"dct_y", &dct_y_},
      {"dct_u", &dct_u_}, {"dct_v", &dct_v_}, {"dct_yuv", &dct_yuv_}};
  std::string out;
  char b[64];
  for (const auto& e : all) {
    std::snprintf(b, sizeof(b), "%s%s=%p", out.empty() ? "" : " ", e.first, (const void*)e.second);
    out += b;
  }
  return out;
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
  for (Pipeline* p : {&dct_y_, &dct_u_, &dct_v_, &dct_yuv_, &resid_, &zstep_, &jac_, &solve_, &residg_, &zgather_, &weight_, &scatter_, &jacblk_, &solveblk_, &wmin_, &wtemp_, &wdark_, &wdarkv_, &wminh_, &radjw_, &wexp_, &wth_, &wtdet_, &zgw_, &residq_, &radjwq_, &scatq_, &zpack_, &residzq_, &radjwzq_, &guide_stats_, &weight_guide_})
    gpu_->destroy(*p);
}

bool Solver::solve(const float* frames, int Kall, int h, int w, float* p, const SolveParams& prm,
                   const PriorFn& prior, std::vector<float>* x_out, SolveStats* st) {
  const double t_start = now_ms();
  // Held-out validation (prm.holdout): the last frame gets buffers and a motion start like the
  // others but never enters the merge; K is the number of frames that do.
  const int K = prm.holdout ? Kall - 1 : Kall;
  if (K < 1) return LKR_FAIL("holdout needs a second frame");
  SolveStats local;
  SolveStats& S = st ? *st : local;
  S = SolveStats{};
  const int s = prm.s, H = s * h, W = s * w;
  const size_t nLR = size_t(Kall) * h * w, nHR = size_t(3) * H * W;

  // Buffers come from a per-solver cache (same tile size -> allocated once per
  // worker instead of ~32 allocations per tile); slots in declaration order.
  int slot = 0;
  auto cached = [&](size_t bytes, bool host_read = false) -> Buffer& { return Cached(slot++, bytes, host_read); };
  Buffer& y = cached(nLR * 4), &pb = cached(size_t(Kall) * 6 * 4);
  // z, x live in GPU-friendly memory (host-cached memory is slow for GPU reads
  // on Mali); zc / xc are host-cached staging copies for the CPU side (prior
  // input/output, final result), moved by GPU buffer copies.
  Buffer& z = cached(nHR * 4), &x = cached(nHR * 4), &acc = cached(nHR * 4);
  Buffer& zc = cached(nHR * 4, true), &xc = cached(nHR * 4, true);
  Buffer& g = cached(16);   // bound by jac / jacblk, which take the gradient from the bilinear taps themselves
  // jac.comp: 64 lanes x 8 pixels per group, 16 subgroup slots per group.
  const uint32_t G = div_up(uint32_t(h * w), 64 * 8), kSlots = 16;
  Buffer& part = cached(size_t(std::max(Kall - 1, 1)) * G * kSlots * 27 * 4);
  Buffer& v = cached(nHR * 4);
  Buffer& rr = cached(nLR * 4);   // residual (gather / robust paths)
  Buffer& rwb = cached(nLR * 4), &wtb = cached(nLR * 4);   // weighted residual, robust weight
  Buffer& wvb = cached(nLR * 4);                                 // valid * weight
  Buffer& wrawb = cached(nLR * 4);   // pre-spread weights
  // Temporal weights: z0 = reference-only start image, rrt its residuals. Every pass runs behind a
  // barrier, so scratch that is dead before its next writer shares memory: rrt is consumed by the
  // temporal test before the robust residual overwrites rr (own buffer only for the trace dumps).
  Buffer& z0 = cached(nHR * 4), &wtr = cached(nLR * 4);
  Buffer& rrt = prm.debug_prefix.empty() ? rr : cached(nLR * 4);
  // rwt2: vec4 row sums of the temporal test (weight_th); ebuf: per-sample robust terms (weight_e),
  // read by weight.comp only, then reused for the row minima of the spread (wmtmp).
  Buffer& rwt2 = cached(nLR * 16), &wvt2 = cached(nLR * 4);
  Buffer& ebuf = cached(nLR * 4);
  Buffer& wmtmp = ebuf;
  // Per-sample temporal model-error term (weight_tdet.comp), read only by weight_th.
  // Per-pixel (Jx, Jy, r, valid) from jac.comp for jacblk.comp (local blocks only).
  Buffer& jbb = cached(prm.local_block > 0 && prm.jac_fuse ? nLR * 16 : 16);
  Buffer& tdetb = cached(prm.temporal_detail > 0.0f ? nLR * 4 : 16);
  // Packed 2x2 taps of z / z0 (zpack.comp); only the off-by-default pack_taps path reads them.
  const size_t zq_bytes = prm.half_taps ? nHR * 2 : prm.pack_taps ? nHR * 16 : 16;
  Buffer& zq = cached(zq_bytes), &z0q = cached(zq_bytes);
  { float* f = wtr.as<float>(); for (size_t i = 0; i < nLR; ++i) f[i] = 1.0f; }
  // Local motion field: K x nby x nbx vec2 (+ Jacobi scratch, block normal eqs).
  const int LB = prm.local_block > 0 ? prm.local_block : 16;
  const int nbx = (w + LB - 1) / LB, nby = (h + LB - 1) / LB, nb = nbx * nby;
  Buffer& dloc = cached(size_t(Kall) * nb * 8), &dtmp = cached(size_t(Kall) * nb * 8);
  Buffer& blk = cached(size_t(std::max(Kall - 1, 1)) * nb * 6 * 4);
  std::memset(dloc.map, 0, dloc.bytes);
  if (prm.local_init && prm.local_block > 0) std::memcpy(dloc.map, prm.local_init, size_t(Kall) * nb * 8);
  // Weighted coverage sum_k U_k^T (valid w) (acc2 -> cw) and plain coverage
  // U^T 1 (cov, = v after the L bound), host-cached copies for the prior map.
  Buffer& acc2 = cached(nHR * 4), &cw = cached(nHR * 4);
  // Without an x-step, zc / xc are first written by the final readback,
  // after the last coverage map has been consumed. Reuse them for coverage
  // until then: two fewer HR RGB allocations per worker (24 MiB at x2).
  // Keep the cache slots stable when switching prior modes on one Solver.
  const bool separate_coverage = bool(prior) || prm.dct_prior || prm.dct_fallback;
  Buffer& cw_stage = cached(separate_coverage ? nHR * 4 : 16, true);
  Buffer& cov_stage = cached(separate_coverage ? nHR * 4 : 16, true);
  Buffer& cwc = separate_coverage ? cw_stage : xc;
  Buffer& covc = separate_coverage ? cov_stage : zc;
  std::memset(acc2.map, 0, nHR * 4);
  if (!y.map || !pb.map || !z.map || !x.map || !acc.map || !g.map || !part.map || !v.map || !z0.map || !wtr.map ||
      !rrt.map || !acc2.map || !cwc.map || !covc.map)
    return LKR_FAIL("buffer allocation");

  std::memcpy(y.map, frames, nLR * 4);
  std::memcpy(pb.map, p, size_t(Kall) * 24);
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

  const bool dct = (prm.dct_prior || prm.dct_fallback) && !prior;   // pipelines and buffers
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

  S.init_ms = now_ms() - t_start;
  if (!Optional(prm, dct)) return LKR_FAIL("optional pipelines");
  gpu_->resetSets();
  // A set of a pipeline this solve does not use (not created) stays null and is never dispatched.
  auto oset = [&](const Pipeline& pl, std::initializer_list<const Buffer*> bufs) {
    return pl.pipe ? gpu_->set(pl, bufs) : VkDescriptorSet(VK_NULL_HANDLE);
  };
  VkDescriptorSet sGuideStats = VK_NULL_HANDLE, sGuideWeight = VK_NULL_HANDLE;
  // Guide confidence needs whole Bayer quads; otherwise (odd size, holdout) the
  // residual confidence runs instead of failing the merge.
  const bool guide_on = (prm.guide_confidence > 0.0f || prm.trusted_static[2] > 0.0f) && !(h & 1) && !(w & 1) &&
                        !prm.holdout;
  const bool guide_stats_on = guide_on && prm.guide_confidence > 0.0f;
  if (guide_on) {
    // vec4 per Bayer quad: means for every frame, variances for the reference frame only.
    Buffer& gm = cached(guide_stats_on ? nLR * 4 : 16);
    Buffer& gv = cached(guide_stats_on ? size_t(h) * w * 4 : 16);
    if (!gm.map || !gv.map) return LKR_FAIL("guide buffers");
    sGuideStats = gpu_->set(guide_stats_, {&y, &gm, &gv});
    sGuideWeight = gpu_->set(weight_guide_, {&gm, &gv, &pb, &wrawb, &dloc, &wtr});
  }
  VkDescriptorSet sDctY = VK_NULL_HANDLE, sDctU = VK_NULL_HANDLE, sDctV = VK_NULL_HANDLE, sDctC = VK_NULL_HANDLE;
  if (dct) {
    sDctY = gpu_->set(dct_y_, {&z, &x, &dct_ns, &dct_iw, &dct_nv});
    sDctU = gpu_->set(dct_u_, {&z, &x, &dct_ns, &dct_iw, &dct_nv});
    sDctV = gpu_->set(dct_v_, {&z, &x, &dct_ns, &dct_iw, &dct_nv});
    sDctC = gpu_->set(dct_yuv_, {&x});
  }
  VkDescriptorSet sResid = gpu_->set(resid_, {&z, &y, &pb, &acc, &dloc});
  VkDescriptorSet sZ = gpu_->set(zstep_, {&z, &x, &acc});
  VkDescriptorSet sJac = gpu_->set(jac_, {&z, &g, &y, &pb, &part, &wtb, &dloc, &jbb});
  VkDescriptorSet sJB = gpu_->set(jacblk_, {&z, &g, &y, &pb, &blk, &wtb, &dloc, &jbb});
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
  // Per-frame LR passes over the HR image: frame-innermost workgroup order keeps one block's HR
  // taps in cache across the K frames (see resid_adj_w.comp); same result in either order.
  auto kdispatch = [&](const Pipeline& pp, VkDescriptorSet ss, const Push* qq, int kk) {
    const int bit = &pp == &radjw_ ? 1 : &pp == &residg_ ? 2 : 4;
    if (prm.frame_inner & bit) gpu_->dispatch(pp, ss, qq, gxLR * uint32_t(kk), gyLR, 1);
    else gpu_->dispatch(pp, ss, qq, gxLR, gyLR, uint32_t(kk));
  };
  VkDescriptorSet sR = gpu_->set(residg_, {&z, &y, &pb, &rr, &dloc});
  VkDescriptorSet sZG = oset(zgather_, {&z, &x, &rr, &pb});
  VkDescriptorSet sVR = gpu_->set(residg_, {&v, &y, &pb, &rr, &dloc});
  VkDescriptorSet sVZG = oset(zgather_, {&v, &x, &rr, &pb});
  VkDescriptorSet sE = gpu_->set(wexp_, {&rr, &y, &ebuf});
  VkDescriptorSet sW = gpu_->set(weight_, {&rr, &ebuf, &rwb, &wtb, &wvb});
  VkDescriptorSet sW0 = gpu_->set(weight_, {&rr, &ebuf, &rwb, &wrawb, &wvb});      // raw weights for the spread (rwb, wvb: rewritten by weight_min)
  VkDescriptorSet sWH = gpu_->set(wminh_, {&wrawb, &wtr, &wmtmp});
  VkDescriptorSet sWM = gpu_->set(wmin_, {&rr, &wmtmp, &rwb, &wtb, &wvb});
  VkDescriptorSet sRW = gpu_->set(radjw_, {&z, &y, &pb, &acc, &dloc, &wtb});
  VkDescriptorSet sZGW = oset(zgw_, {&z, &x, &rr, &pb, &dloc, &wtb});
  VkDescriptorSet sRT = gpu_->set(residg_, {&z0, &y, &pb, &rrt, &dloc});
  VkDescriptorSet sHV = gpu_->set(residg_, {&z, &y, &pb, &ebuf, &dloc});   // holdout: valid mask of the finished image
  VkDescriptorSet sTH = gpu_->set(wth_, {&rrt, &y, &rwt2, &wvt2, &tdetb});
  VkDescriptorSet sTDet = gpu_->set(wtdet_, {&rrt, &y, &tdetb});
  // ebuf is free during the temporal test (weight_e writes it later in the data step).
  VkDescriptorSet sWT = gpu_->set(wtemp_, {&rrt, &y, &rwt2, &wtr, &wvt2, &ebuf});
  // Column sums of the deep-shadow window: five buffers the data step rewrites before reading
  // (weighted residual, coverage, raw and final robust weights, robust residual).
  VkDescriptorSet sWV = gpu_->set(wdarkv_, {&ebuf, &rwt2, &wvt2, &rwb, &wvb, &wrawb, &wtb, &rr});
  VkDescriptorSet sWD = gpu_->set(wdark_, {&ebuf, &y, &wtr, &rwb, &wvb, &wrawb, &wtb, &rr});
  const bool temporal = prm.temporal_k > 0.0f && prm.robust_spread > 0;
  VkDescriptorSet sS = gpu_->set(scatter_, {&rwb, &pb, &acc, &dloc});
  VkDescriptorSet sSW = gpu_->set(scatter_, {&wvb, &pb, &acc2, &dloc});       // weighted coverage
  // Quad variants (prm.quad): same buffers, one invocation per 2x2 quad.
  const bool quad = prm.quad && (w % 2 == 0) && (h % 2 == 0);
  const uint32_t gxQ = div_up(uint32_t(w / 2), 16), gyQ = div_up(uint32_t(h / 2), 16);
  VkDescriptorSet sRq = oset(residq_, {&z, &y, &pb, &rr, &dloc});
  VkDescriptorSet sRTq = oset(residq_, {&z0, &y, &pb, &rrt, &dloc});
  VkDescriptorSet sRWq = oset(radjwq_, {&z, &y, &pb, &acc, &dloc, &wtb});
  VkDescriptorSet sSq = oset(scatq_, {&rwb, &pb, &acc, &dloc});
  VkDescriptorSet sSWq = oset(scatq_, {&wvb, &pb, &acc2, &dloc});
  const bool packz = (prm.pack_taps || prm.half_taps) && !quad;
  VkDescriptorSet sZP = oset(zpack_, {&z, &zq}), sZ0P = oset(zpack_, {&z0, &z0q});
  VkDescriptorSet sRzq = oset(residzq_, {&zq, &y, &pb, &rr, &dloc});
  VkDescriptorSet sRTzq = oset(residzq_, {&z0q, &y, &pb, &rrt, &dloc});
  VkDescriptorSet sRWzq = oset(radjwzq_, {&zq, &y, &pb, &acc, &dloc, &wtb});
  const uint32_t gxP = div_up(uint32_t(W), 16), gyP = div_up(uint32_t(H), 16);
  auto d_pack = [&](VkDescriptorSet s_, const Push& qq) { gpu_->dispatch(zpack_, s_, &qq, gxP, gyP, 3); };
  auto d_resid = [&](VkDescriptorSet sp, VkDescriptorSet sq, const Push& qq, VkDescriptorSet szq) {
    if (quad) gpu_->dispatch(residq_, sq, &qq, gxQ, gyQ, K);
    else if (packz) gpu_->dispatch(residzq_, szq, &qq, gxLR, gyLR, K);
    else kdispatch(residg_, sp, &qq, K);
  };
  auto d_scatter = [&](VkDescriptorSet sp, VkDescriptorSet sq, const Push& qq) {
    if (quad) gpu_->dispatch(scatq_, sq, &qq, gxQ, gyQ, K); else kdispatch(scatter_, sp, &qq, K);
  };
  VkDescriptorSet sZW = gpu_->set(zstep_, {&cw, &x, &acc2});           // acc2 -> cw (mode 1)
  const uint32_t gxHR = div_up(W, 16), gyHR = div_up(H, 16);
  // One "data step" = residual + adjoint (+ z update): scatter or gather.
  auto data_step = [&](const Push& q, bool on_v, bool first = true) {
    if (!on_v && q.rob_on > 0.5f && guide_on) {
      if (!first && !prm.robust_gather) {
        // Reuse this iteration's confidence, but update the residual at every
        // z-step. Reusing rwb here would repeat a stale gradient and diverge.
        if (quad) gpu_->dispatch(radjwq_, sRWq, &q, gxQ, gyQ, K);
        else if (packz) { d_pack(sZP, q); gpu_->dispatch(radjwzq_, sRWzq, &q, gxLR, gyLR, K); }
        else kdispatch(radjw_, sRW, &q, K);
        gpu_->dispatch(zstep_, sZ, &q, gHR1);
        return;
      }
      if (packz) d_pack(sZP, q);
      d_resid(sR, sRq, q, sRzq);
      if (first) {
        Push qg = q; qg.f0 = prm.guide_confidence;
        if (prm.trusted_static[2] > 0.0f) {
          gpu_->dispatch(wexp_, sE, &q, gxLR, gyLR, K);
          Push qw = q; qw.f0 = -1;
          gpu_->dispatch(weight_, sW0, &qw, gxLR, gyLR, K);
          qg.f0 = -1; qg.f1 = prm.trusted_static[0]; qg.f2 = prm.trusted_static[1];
          qg.f3 = prm.trusted_static[2]; qg.rob_k = prm.trusted_static[3];
        }
        gpu_->dispatch(weight_guide_, sGuideWeight, &qg, gxQ, gyQ, K);   // one invocation per quad
        Push qm = q; qm.f3 = float(prm.robust_spread);
        gpu_->dispatch(wminh_, sWH, &qm, gxLR, gyLR, K);
        gpu_->dispatch(wmin_, sWM, &qm, gxLR, gyLR, K);
      }
      if (prm.robust_gather) gpu_->dispatch(zgw_, sZGW, &q, gxHR, gyHR);
      else {
        d_scatter(sS, sSq, q);
        gpu_->dispatch(zstep_, sZ, &q, gHR1);
      }
      return;
    }
    if (!on_v && q.rob_on > 0.5f && prm.robust_spread > 0 && prm.robust_gather) {
      // gather: residual (first step: + weights), then the fused gather adjoint + z-update
      if (packz) d_pack(sZP, q);
      d_resid(sR, sRq, q, sRzq);
      if (first) {
        gpu_->dispatch(wexp_, sE, &q, gxLR, gyLR, K);
        { Push qw = q; qw.f0 = prm.weight_skip ? -1.0f : q.f0; gpu_->dispatch(weight_, sW0, &qw, gxLR, gyLR, K); }
        Push qm = q; qm.f3 = float(prm.robust_spread);
        gpu_->dispatch(wminh_, sWH, &qm, gxLR, gyLR, K);
        gpu_->dispatch(wmin_, sWM, &qm, gxLR, gyLR, K);
      }
      gpu_->dispatch(zgw_, sZGW, &q, gxHR, gyHR);
    } else if (!on_v && q.rob_on > 0.5f && prm.robust_spread > 0 && !first) {
      // z-steps 2..n: this iteration's weights, fused residual + weighted adjoint
      if (quad) gpu_->dispatch(radjwq_, sRWq, &q, gxQ, gyQ, K);
      else if (packz) { d_pack(sZP, q); gpu_->dispatch(radjwzq_, sRWzq, &q, gxLR, gyLR, K); }
      else kdispatch(radjw_, sRW, &q, K);   // frame-innermost order
      gpu_->dispatch(zstep_, sZ, &q, gHR1);
    } else if (!on_v && q.rob_on > 0.5f) {      // robust: resid -> weight -> scatter -> zstep
      if (packz) d_pack(sZP, q);
      d_resid(sR, sRq, q, sRzq);
      if (prm.robust_spread > 0) {
        gpu_->dispatch(wexp_, sE, &q, gxLR, gyLR, K);
        { Push qw = q; qw.f0 = prm.weight_skip ? -1.0f : q.f0; gpu_->dispatch(weight_, sW0, &qw, gxLR, gyLR, K); }
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
      kdispatch(residg_, on_v ? sVR : sR, &q, K);
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
        kdispatch(residg_, sVR, &q, K);
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
  if (guide_stats_on) {
    gpu_->begin();
    Push q = pc; q.rob_a = prm.noise_a; q.rob_b = prm.noise_b;
    gpu_->dispatch(guide_stats_, sGuideStats, &q, div_up(w / 2, 16), div_up(h / 2, 16), K);
    if (!gpu_->submit()) return LKR_FAIL("guide statistics");
    ++S.submits;
  }
  const bool eq = prm.equalize_noise && prm.robust && prm.noise_b > 0;
  if (eq) {   // plain coverage U^T 1 (v after the bound) for the contributing-frame ratio
    if (prm.L != 0.0f) {  // v not computed by the bound: compute U^T 1 now
      gpu_->begin();
      Push q = pc; q.mode = 2;
      if (prm.gather) {
        kdispatch(residg_, sVR, &q, K);
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

  const int last_prior = prm.prior_at.empty() ? prm.T - 1 : *std::max_element(prm.prior_at.begin(), prm.prior_at.end());
  bool x_from_staging = false;
  bool z0_packed = false;
  int motion_steps = 0;
  bool temporal_stale = true;
  // With no CPU/DCT prior, the intermediate iterations only touch GPU
  // buffers. Keep their existing barriers and arithmetic, but wait only at
  // readback points (and at most four iterations per command buffer).
  const bool batch_iterations = !prior && !dct && prm.debug_prefix.empty();
  bool iteration_recording = false;
  double iteration_gpu_start = 0;
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
    if (!iteration_recording) {
      iteration_gpu_start = now_ms();
      gpu_->begin();
      iteration_recording = true;
    }
    if (x_from_staging) { gpu_->copy(xc, x, nHR * 4); x_from_staging = false; }   // previous prior's output
    // Temporal weights depend on the fixed z0 and the motion only: recompute after a motion step.
    if (temporal && q.rob_on > 0.5f && temporal_stale) {
      temporal_stale = false;
      Push qt = q; qt.rob_k = prm.temporal_k; qt.f3 = prm.temporal_tau;
      if (packz && !z0_packed) { d_pack(sZ0P, qt); z0_packed = true; }   // z0 is fixed: pack once
      d_resid(sRT, sRTq, qt, sRTzq);
      Push qd = qt; qd.f0 = prm.temporal_detail;
      qd.f1 = prm.temporal_dark_wide ? 1.0f : 0.0f;
      qd.f2 = prm.temporal_dark_level;
      // weight_th reads f0 (detail on) but not f1: f1 < 0 selects its inline A/B reference.
      Push qh = qd; qh.f1 = prm.temporal_det_once ? 0.0f : -1.0f;
      if (qd.f0 > 0.0f && prm.temporal_det_once) gpu_->dispatch(wtdet_, sTDet, &qd, gxLR, gyLR, K);
      gpu_->dispatch(wth_, sTH, &qh, gxLR, gyLR, K);
      gpu_->dispatch(wtemp_, sWT, &qd, gxLR, gyLR, K);
      if (prm.temporal_dark_wide) {
        gpu_->dispatch(wdarkv_, sWV, &qd, gxLR, gyLR, K);
        gpu_->dispatch(wdark_, sWD, &qd, gxLR, gyLR, K);
      }
    }
    for (int zs = 0; zs < prm.z_steps; ++zs) data_step(q, false, zs == 0);  // Eq. (5)
    const bool refine_now = prm.motion_every <= 1 || t < prm.motion_warmup ||
                            ((t - prm.motion_warmup) % prm.motion_every) == prm.motion_every - 1;
    if (prm.refine_motion && K > 1 && refine_now) {                // Eq. (7)
      // jac / jacblk read no f3: f3 < 0 turns the shared per-pixel terms off (A/B reference).
      Push qj = q; qj.f3 = prm.jac_fuse && prm.local_block > 0 ? 0.0f : -1.0f;
      gpu_->dispatch(jac_, sJac, &qj, G, K - 1);
      gpu_->dispatch(solve_, sSolve, &q, K - 1);
      if (prm.local_block > 0) {                                   // local block translations
        gpu_->dispatch(jacblk_, sJB, &qj, nbx, nby, K - 1);
        Push qb = q;
        qb.f0 = prm.local_lambda;
        qb.f1 = prm.local_floor;
        gpu_->dispatch(solveblk_, sSB, &qb, div_up(uint32_t(K * nb), 64));
        gpu_->copy(dtmp, dloc, size_t(K) * nb * 8);   // merged frames only: a held-out frame keeps its start
      }
      ++motion_steps;
      temporal_stale = true;
    }
    const bool use_prior = prior_here(t);
    // dct_fallback without dct_prior: one x-step, after the last iteration, for the frames lost only.
    const bool dct_t = dct && (prm.dct_prior || t == last_prior);
    if (use_prior && !prior && !dct_t) gpu_->copy(z, x, nHR * 4);    // 'none': x = z
    if (use_prior && (prior || dct_t)) gpu_->copy(z, zc, nHR * 4);            // prior input to the CPU side
    // Weighted coverage feeds the x-step's noise map and, after the last one, the finishing
    // (FinishLook, debug maps): without an x-step only that last map is ever read.
    const bool cov_now = use_prior && eq && q.rob_on > 0.5f && (prior || dct_t || t == last_prior);
    if (cov_now) {
      d_scatter(sSW, sSWq, q);
      Push q1 = q; q1.mode = 1;
      gpu_->dispatch(zstep_, sZW, &q1, gHR1);
      gpu_->copy(cw, cwc, nHR * 4);
    }
    const bool flush_iteration = !batch_iterations || cov_now || t + 1 == prm.T || (t + 1) % 4 == 0;
    if (!flush_iteration) continue;
    if (!gpu_->submit()) {
      char m[96];
      std::snprintf(m, sizeof(m), "iteration %d submit, vk %d, wtemp pipe %p", t, gpu_->last_error, (void*)wtemp_.pipe);
      return LKR_FAIL(m);
    }
    ++S.submits;
    iteration_recording = false;
    S.gpu_ms += now_ms() - iteration_gpu_start;
    // Coverage is also needed by no-prior motion-region Bayer cleanup.
    if (use_prior) {
      if (cov_now) {
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
    if (use_prior && (prior || dct_t)) {                             // PnP x-step
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
      // Fallback cleanup: remove only the noise a pixel has in excess of fully merged ones
      // (variance factor ns^2 - 1); tiles where every frame was used skip the pass.
      std::vector<float> nfb;
      bool fb_skip = false;
      if (dct && !prm.dct_prior) {
        float mx = 0.0f;
        if (ns) {
          nfb.resize(size_t(H) * W);
          for (size_t i = 0; i < nfb.size(); ++i) {
            nfb[i] = std::sqrt(std::max(ns[i] * ns[i] - 1.0f, 0.0f));
            mx = std::max(mx, nfb[i]);
          }
          ns = nfb.data();
        }
        fb_skip = mx <= 0.0f;
        if (fb_skip) std::memcpy(xc.map, zc.map, nHR * 4);
      }
      if (fb_skip) {
      } else if (dct) {
        if (ns) std::memcpy(dct_ns.map, ns, size_t(H) * W * 4);
        const float sg = prm.dct_prior ? sig[t] * prm.prior_gain
                                       : prm.sigma_noise / std::sqrt(float(K));   // fallback: merged level; x (ns^2 - 1) = the excess
        const float denom = prm.noise_a * 0.18f + prm.noise_b;
        DctPush d{H, W, dct_ny, dct_nx, 0, 0, H / 4, ns ? 1 : 0, sg * sg,
                  denom > 0 ? prm.noise_a / denom : 0.0f, denom > 0 ? prm.noise_b / denom : 1.0f,
                  prm.dct_prior ? prm.dct_luma * prm.dct_luma : 0.0f,   // fallback: colour only, luma grain untouched
                  prm.dct_chroma * prm.dct_chroma,
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
  // Each frame minus the model's prediction of it (K x h x w, invalid = 0): host/lkr_align_overlay.py.
  trace("final_resid", rr.as<float>(), nLR);
  trace("final_x", x_out->data(), nHR);
  const double t_fin = now_ms();
  FinishLook(zc.as<float>(), x_out->data(), H, W, prm, nscale.empty() ? nullptr : nscale.data(),
             shadow.empty() ? nullptr : shadow.data());
  S.finish_ms = now_ms() - t_fin;
  trace("final_look", x_out->data(), nHR);
  if (prm.holdout) {
    // Align the held-out frame to the FINISHED image with the solver's own motion step (affine +
    // local blocks, robust weights), then take its residual. Its samples only ever set its own
    // motion, so the residual is an out-of-sample test of the image.
    const int ko = Kall - 1;
    std::memcpy(z.map, x_out->data(), nHR * 4);
    Push q = pc;
    q.K = Kall;
    q.mode = 0;
    q.f0 = 1e-9f;
    q.f1 = float(G * kSlots);
    q.f2 = prm.clip;
    q.rob_a = prm.noise_a;
    q.rob_b = prm.noise_b;
    q.rob_k = prm.robust_k;
    q.f3 = prm.robust_tau;
    q.rob_on = (prm.robust && prm.noise_b > 0) ? std::max(1.0f, prm.robust_pow) : 0.0f;
    const int steps = std::max(motion_steps, 6);
    for (int it = 0; it < steps; ++it) {
      gpu_->begin();
      if (q.rob_on > 0.5f) {
        kdispatch(residg_, sR, &q, Kall);
        gpu_->dispatch(wexp_, sE, &q, gxLR, gyLR, Kall);
        if (prm.robust_spread > 0) {
          { Push qw = q; qw.f0 = prm.weight_skip ? -1.0f : q.f0; gpu_->dispatch(weight_, sW0, &qw, gxLR, gyLR, Kall); }
          Push qm = q; qm.f3 = float(prm.robust_spread);
          gpu_->dispatch(wminh_, sWH, &qm, gxLR, gyLR, Kall);
          gpu_->dispatch(wmin_, sWM, &qm, gxLR, gyLR, Kall);
        } else {
          gpu_->dispatch(weight_, sW, &q, gxLR, gyLR, Kall);
        }
      }
      Push qj = q; qj.f3 = prm.jac_fuse && prm.local_block > 0 ? 0.0f : -1.0f;
      gpu_->dispatch(jac_, sJac, &qj, G, Kall - 1);
      gpu_->dispatch(solve_, sSolve, &q, Kall - 1);
      if (prm.local_block > 0) {
        gpu_->dispatch(jacblk_, sJB, &qj, nbx, nby, Kall - 1);
        Push qb = q;
        qb.f0 = prm.local_lambda;
        qb.f1 = prm.local_floor;
        gpu_->dispatch(solveblk_, sSB, &qb, div_up(uint32_t(Kall * nb), 64));
        gpu_->copy(dtmp, dloc, dloc.bytes);
      }
      if (!gpu_->submit()) return LKR_FAIL("holdout motion");
    }
    gpu_->begin();
    kdispatch(residg_, sR, &q, Kall);
    Push qv = q; qv.mode = 2;
    kdispatch(residg_, sHV, &qv, Kall);
    if (!gpu_->submit()) return LKR_FAIL("holdout residual");
    const size_t o = size_t(ko) * h * w, n1 = size_t(h) * w;
    S.ho_r.assign(rr.as<float>() + o, rr.as<float>() + o + n1);
    S.ho_valid.resize(n1);
    const float* vm = ebuf.as<float>() + o;
    for (size_t i = 0; i < n1; ++i) S.ho_valid[i] = (vm[i] > 0.5f && frames[o + i] < prm.clip) ? 1.0f : 0.0f;
  }
  std::memcpy(p, pb.map, size_t(Kall) * 24);
  S.dispatches = gpu_->dispatches();
  if (prm.local_out && prm.local_block > 0) std::memcpy(prm.local_out, dloc.map, size_t(Kall) * nb * 8);
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
      float* o = t.data() + size_t(y) * W;
      for (int x = 0; x < W; ++x) {
        float s = 0;
        if (x >= 2 && x + 2 < W) for (int d = -2; d <= 2; ++d) s += k5[d + 2] * r[x + d];
        else for (int d = -2; d <= 2; ++d) s += k5[d + 2] * r[refl(x + d, W)];
        o[x] = s;
      }
    }
    for (int y = 0; y < H; ++y) {
      const float* rows[5];
      for (int d = -2; d <= 2; ++d) rows[d + 2] = t.data() + size_t(refl(y + d, H)) * W;
      float* o = a.data() + size_t(y) * W;
      for (int x = 0; x < W; ++x) {
        float s = 0;
        for (int d = 0; d < 5; ++d) s += k5[d] * rows[d][x];
        o[x] = s;
      }
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
  if (!prm.notch && prm.grain <= 0.0f && !shadow && !prm.highlight_desat && prm.fallback_chroma <= 0.0f) return;
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
    // The chroma notch has consumed L/C; reuse those planes for the luma
    // notch. Grain and shadow processing below recompute their own luma.
    std::vector<float>& Lx = L;
    std::vector<float>& Ln = C;
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
  // Fallback false-colour repair: only where frames were lost (noise_scale > 1), chroma toward
  // its median. The reference frame alone aliases thin lines into colour stripes.
  if (prm.fallback_chroma > 0.0f && noise_scale) {
    std::vector<float> Lf(HW), Cc(HW), Cm(HW);
    for (size_t i = 0; i < HW; ++i) Lf[i] = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];
    auto med3 = [&](const std::vector<float>& a, std::vector<float>& o) {
      float v[9];
      for (int Y = 0; Y < H; ++Y)
        for (int X = 0; X < W; ++X) {
          int n = 0;
          for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx)
              v[n++] = a[size_t(std::clamp(Y + dy, 0, H - 1)) * W + std::clamp(X + dx, 0, W - 1)];
          std::nth_element(v, v + 4, v + 9);
          o[size_t(Y) * W + X] = v[4];
        }
    };
    for (int c = 0; c < 3; ++c) {
      for (size_t i = 0; i < HW; ++i) Cc[i] = prm.wb[c] * x[c * HW + i] - Lf[i];
      med3(Cc, Cm);
      std::vector<float> Cm2(HW);
      med3(Cm, Cm2);
      for (size_t i = 0; i < HW; ++i) {
        const float t = prm.fallback_chroma * std::min(std::max((noise_scale[i] - 1.0f) / 0.4142f, 0.0f), 1.0f);
        if (t > 0.0f) x[c * HW + i] = (Lf[i] + Cc[i] + t * (Cm2[i] - Cc[i])) / prm.wb[c];
      }
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
    std::vector<float> Cc(HW);
    for (int c = 0; c < 3; ++c) {
      for (size_t i = 0; i < HW; ++i) Cc[i] = prm.wb[c] * x[c * HW + i] - L[i];
      C = Cc;
      BoxBlur(C.data(), H, W, 3, tmp);
      BoxBlur(C.data(), H, W, 3, tmp);
      for (size_t i = 0; i < HW; ++i) {
        const float t = prm.shadow_chroma * shadow[i];
        x[c * HW + i] = (L[i] + Cc[i] + t * (C[i] - Cc[i])) / prm.wb[c];
      }
    }
  }
  // Neutralise only pixels with no remaining unsaturated colour channel.
  // One-channel clipping is common in sunsets: the other channels still
  // contain valid colour. Do not expand this mask into coloured neighbours.
  if (prm.highlight_desat) {
    const float lo = 0.92f * prm.clip, hi = prm.clip;
    for (size_t i = 0; i < HW; ++i) {
      const float mn = std::min(x[i], std::min(x[HW + i], x[2 * HW + i]));
      const float m = std::clamp((mn - lo) / std::max(hi - lo, 1e-6f), 0.0f, 1.0f);
      if (m <= 0.0f) continue;
      const float L = q[0] * x[i] + q[1] * x[HW + i] + q[2] * x[2 * HW + i];   // wb-space luma
      for (int c = 0; c < 3; ++c) {
        const float neutral = L / prm.wb[c];
        x[c * HW + i] += m * (neutral - x[c * HW + i]);
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
