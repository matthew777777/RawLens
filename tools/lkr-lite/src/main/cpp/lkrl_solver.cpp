// SPDX-License-Identifier: Apache-2.0
#include "lkrl_solver.h"
#include "lkrl_kernelnet.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <deque>

#include "lkrl_image.h"
#include "lkrl_mhc.h"

namespace lkrl {
namespace spv {
static const uint32_t resid[] = {
#include "resid.inc"
};
static const uint32_t werr[] = {
#include "werr.inc"
};
static const uint32_t wavg[] = {
#include "wavg.inc"
};
static const uint32_t wminh[] = {
#include "wminh.inc"
};
static const uint32_t wminv[] = {
#include "wminv.inc"
};
static const uint32_t adj[] = {
#include "adj.inc"
};
static const uint32_t zupd[] = {
#include "zupd.inc"
};
static const uint32_t jac[] = {
#include "jac.inc"
};
static const uint32_t jacblk[] = {
#include "jacblk.inc"
};
static const uint32_t gstats[] = {
#include "gstats.inc"
};
static const uint32_t wguide[] = {
#include "wguide.inc"
};
static const uint32_t wvalid[] = {
#include "wvalid.inc"
};
static const uint32_t kdy[] = {
#include "lkr_dct_x_y.inc"
};
static const uint32_t kdu[] = {
#include "lkr_dct_x_u.inc"
};
static const uint32_t kdv[] = {
#include "lkr_dct_x_v.inc"
};
static const uint32_t kdyuv[] = {
#include "lkr_dct_yuv.inc"
};
static const uint32_t fin[] = {
#include "fin.inc"
};
static const uint32_t adj_wg[] = {
#include "adj_wg.inc"
};
static const uint32_t hyb[] = {
#include "hyb.inc"
};
static const uint32_t kn_conv[] = {
#include "kn_conv.inc"
};
static const uint32_t kn_dn[] = {
#include "kn_dn.inc"
};
static const uint32_t kresid[] = {
#include "lkr_resid.inc"
};
static const uint32_t ktdet[] = {
#include "lkr_weight_tdet.inc"
};
static const uint32_t kth[] = {
#include "lkr_weight_th.inc"
};
static const uint32_t kt[] = {
#include "lkr_weight_t.inc"
};
static const uint32_t ktv[] = {
#include "lkr_weight_tv.inc"
};
static const uint32_t ktd[] = {
#include "lkr_weight_td.inc"
};
static const uint32_t kguide[] = {
#include "lkr_weight_guide.inc"
};
static const uint32_t kminh[] = {
#include "lkr_weight_minh.inc"
};
static const uint32_t kmin[] = {
#include "lkr_weight_min.inc"
};
static const uint32_t solveblk[] = {
#include "solveblk.inc"
};
}  // namespace spv

namespace {
// LK-R's push block (lkr_common.glsl) for the verbatim weight shaders.
struct KPush {
  int32_t h, w, s, K;
  int32_t H, W, kOff, mode;
  float eta, mu, scale, invScale;
  float f0, f1, f2, f3;
  float rob_a, rob_b, rob_k, rob_on;
};
static_assert(sizeof(KPush) == 80, "LK-R push layout");

struct Push {
  int h, w, s, K;
  int H, W, LB, mode;
  float a, b, clip, robk;
  float tau, rpow, scale, dscale;
};
static_assert(sizeof(Push) == 64, "push block");

struct DctPush {   // = lkr_solver.cpp DctPush
  int32_t H, W, ny, nx, py, px, ixo, has_ns;
  float sigma2, lv_a, lv_b, k_y2, k_c2, wb_r, wb_g, wb_b;
  float dark_level, dark_cap, dark_luma;
  float aperture_scale, aperture_strength;
  float coherence_sigma2;
  float correlated_error;
};

// float -> IEEE half, round to nearest even (frames: normalised raw, |x| < 65504).
uint16_t ToHalf(float f) {
  uint32_t x; std::memcpy(&x, &f, 4);
  const uint32_t sign = (x >> 16) & 0x8000u;
  int32_t e = int32_t((x >> 23) & 0xff) - 127 + 15;
  uint32_t m = x & 0x7fffffu;
  if (((x >> 23) & 0xff) == 0xff) return uint16_t(sign | 0x7c00u | (m ? 0x200u : 0u));
  if (e >= 31) return uint16_t(sign | 0x7c00u);
  if (e <= 0) {
    if (e < -10) return uint16_t(sign);
    m |= 0x800000u;
    const uint32_t shift = uint32_t(14 - e);
    uint32_t hm = m >> shift;
    const uint32_t rem = m & ((1u << shift) - 1u), half = 1u << (shift - 1);
    if (rem > half || (rem == half && (hm & 1u))) ++hm;
    return uint16_t(sign | hm);
  }
  uint32_t h = sign | (uint32_t(e) << 10) | (m >> 13);
  const uint32_t rem = m & 0x1fffu;
  if (rem > 0x1000u || (rem == 0x1000u && (h & 1u))) ++h;
  return uint16_t(h);
}
float HalfRound(float f) {   // value of ToHalf(f) as float (clip level as the fp16 frames see it)
  const uint16_t h = ToHalf(f);
  const uint32_t sign = uint32_t(h & 0x8000u) << 16, e = (h >> 10) & 0x1f, m = h & 0x3ff;
  uint32_t x = e == 0 ? sign : (sign | ((e + 112u) << 23) | (m << 13));
  float out; std::memcpy(&out, &x, 4); return out;
}
void PackHalf(const float* src, size_t n, uint32_t* dst) {
  for (size_t i = 0; i < n; i += 2) {
    const uint32_t lo = ToHalf(src[i]), hi = i + 1 < n ? ToHalf(src[i + 1]) : 0u;
    dst[i / 2] = lo | (hi << 16);
  }
}

double now_ms() {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
uint32_t div_up(uint32_t a, uint32_t b) { return (a + b - 1) / b; }

bool solve6(double A[6][6], double b[6], double x[6]) {   // Gaussian elimination, partial pivoting
  double M[6][7];
  for (int i = 0; i < 6; ++i) { for (int j = 0; j < 6; ++j) M[i][j] = A[i][j]; M[i][6] = b[i]; }
  for (int c = 0; c < 6; ++c) {
    int piv = c;
    for (int r = c + 1; r < 6; ++r) if (std::fabs(M[r][c]) > std::fabs(M[piv][c])) piv = r;
    if (std::fabs(M[piv][c]) < 1e-30) return false;
    if (piv != c) for (int j = 0; j < 7; ++j) std::swap(M[c][j], M[piv][j]);
    for (int r = 0; r < 6; ++r) {
      if (r == c) continue;
      const double f = M[r][c] / M[c][c];
      for (int j = c; j < 7; ++j) M[r][j] -= f * M[c][j];
    }
  }
  for (int i = 0; i < 6; ++i) x[i] = M[i][6] / M[i][i];
  return true;
}
}  // namespace

#define LKRL_PIPE(name, nb) name##_ = gpu_->pipeline(spv::name, sizeof(spv::name), nb, sizeof(Push))

LiteSolver::LiteSolver(Gpu* gpu) : gpu_(gpu) {
  if (!gpu_ || !gpu_->ok()) return;
  LKRL_PIPE(resid, 6); LKRL_PIPE(werr, 3); LKRL_PIPE(wavg, 2); LKRL_PIPE(wminh, 3); LKRL_PIPE(wminv, 3);
  LKRL_PIPE(adj, 7); LKRL_PIPE(zupd, 4); LKRL_PIPE(jac, 6); LKRL_PIPE(jacblk, 6); LKRL_PIPE(solveblk, 3); LKRL_PIPE(gstats, 3); LKRL_PIPE(wguide, 5); LKRL_PIPE(wvalid, 3);
#define LKRL_KPIPE(name, nb) name##_ = gpu_->pipeline(spv::name, sizeof(spv::name), nb, sizeof(KPush))
  LKRL_KPIPE(kresid, 5); LKRL_KPIPE(ktdet, 3); LKRL_KPIPE(kth, 5); LKRL_KPIPE(kt, 6); LKRL_KPIPE(ktv, 8);
  LKRL_KPIPE(ktd, 8); LKRL_KPIPE(kguide, 6); LKRL_KPIPE(kminh, 3); LKRL_KPIPE(kmin, 5);
  kdy_ = gpu_->pipeline(spv::kdy, sizeof(spv::kdy), 5, sizeof(DctPush));
  kdu_ = gpu_->pipeline(spv::kdu, sizeof(spv::kdu), 5, sizeof(DctPush));
  kdv_ = gpu_->pipeline(spv::kdv, sizeof(spv::kdv), 5, sizeof(DctPush));
  kdyuv_ = gpu_->pipeline(spv::kdyuv, sizeof(spv::kdyuv), 1, sizeof(DctPush));
  knconv_ = gpu_->pipeline(spv::kn_conv, sizeof(spv::kn_conv), 3, 48);
  kndn_ = gpu_->pipeline(spv::kn_dn, sizeof(spv::kn_dn), 4, 48);
  hyb_ = gpu_->pipeline(spv::hyb, sizeof(spv::hyb), 4, 48);
  adjwg_ = gpu_->pipeline(spv::adj_wg, sizeof(spv::adj_wg), 7, sizeof(Push));
  fin_ = gpu_->pipeline(spv::fin, sizeof(spv::fin), 7, 64);
  ok_ = true;
  for (Pipeline* q : {&resid_, &werr_, &wavg_, &wminh_, &wminv_, &adj_, &zupd_, &jac_, &jacblk_, &solveblk_, &gstats_, &wguide_, &wvalid_, &kresid_, &ktdet_, &kth_, &kt_, &ktv_, &ktd_, &kguide_, &kminh_, &kmin_, &kdy_, &kdu_, &kdv_, &kdyuv_, &knconv_, &kndn_, &hyb_, &adjwg_, &fin_})
    ok_ = ok_ && q->pipe != VK_NULL_HANDLE;
}

std::string LiteSolver::profileNames() const {
  std::string out;
  char b[64];
  const std::pair<const Pipeline*, const char*> names[] = {
      {&resid_, "resid"}, {&werr_, "werr"}, {&wavg_, "wavg"}, {&wminh_, "wminh"}, {&wminv_, "wminv"},
      {&adj_, "adj"}, {&zupd_, "zupd"}, {&jac_, "jac"}, {&jacblk_, "jacblk"}, {&solveblk_, "solveblk"},
      {&gstats_, "gstats"}, {&wguide_, "wguide"}, {&wvalid_, "wvalid"}, {&kresid_, "lkr_resid"},
      {&ktdet_, "lkr_weight_tdet"}, {&kth_, "lkr_weight_th"}, {&kt_, "lkr_weight_t"}, {&ktv_, "lkr_weight_tv"},
      {&ktd_, "lkr_weight_td"}, {&kguide_, "lkr_weight_guide"}, {&kminh_, "lkr_weight_minh"}, {&kmin_, "lkr_weight_min"},
      {&kdy_, "dct_y"}, {&kdu_, "dct_u"}, {&kdv_, "dct_v"}, {&kdyuv_, "dct_yuv"}, {&knconv_, "kn_conv"},
      {&kndn_, "kn_dn"}, {&hyb_, "hyb"}, {&adjwg_, "adj_wg"}, {&fin_, "fin"}};
  for (const auto& n : names) {
    std::snprintf(b, sizeof(b), "%s%s=%p", out.empty() ? "" : " ", n.second, static_cast<const void*>(n.first));
    out += b;
  }
  return out;
}

LiteSolver::~LiteSolver() {
  if (!gpu_ || !gpu_->ok()) return;
  for (Entry& e : cache_) gpu_->free(e.buf);
  for (Entry& e : fcache_) gpu_->free(e.buf);
  for (Pipeline* q : {&resid_, &werr_, &wavg_, &wminh_, &wminv_, &adj_, &zupd_, &jac_, &jacblk_, &solveblk_, &gstats_, &wguide_, &wvalid_, &kresid_, &ktdet_, &kth_, &kt_, &ktv_, &ktd_, &kguide_, &kminh_, &kmin_, &kdy_, &kdu_, &kdv_, &kdyuv_, &knconv_, &kndn_, &hyb_, &adjwg_, &fin_}) gpu_->destroy(*q);
}

bool LiteSolver::solve(const float* frames, int K, int h, int w, std::vector<float>& p, std::vector<float>& dloc,
                       const LiteParams& prm, std::vector<float>* z_out, LiteStats* st, std::string* err,
                       std::vector<float>* noise_scale, std::vector<float>* resid_out,
                       std::vector<float>* weights_out, std::vector<float>* x_out) {
  auto fail = [&](const char* m) { if (err) *err = m; gpu_->resetSets(); return false; };
  if (!ok_) return fail("pipelines");
  if (prm.local_block != 16) return fail("local_block must be 16");
  if (prm.s != 1 && prm.s != 2) return fail("s must be 1 or 2");
  const double t0 = now_ms();
  LiteStats S;
  const int s = prm.s, H = s * h, W = s * w, LB = prm.local_block;
  // Hybrid merge mode; mode 5 is skipped (today's image, no extra cost) when the scene is bright enough
  // that its noise-following strength is 0 (relative noise at mid-grey <= 3 %).
  int cavg = prm.chroma_from_avg;
  // (The daylight skip is gone: the average start also removes the edge zipper, which every scene needs.)
  const int nbx = (w + LB - 1) / LB, nby = (h + LB - 1) / LB, nb = nbx * nby;
  const size_t nLR = size_t(K) * h * w, nHR = size_t(3) * H * W;
  if (p.size() != size_t(K) * 6) return fail("p size");
  if (dloc.empty()) dloc.assign(size_t(K) * nb * 2, 0.0f);
  if (dloc.size() != size_t(K) * nb * 2) return fail("dloc size");

  // Buffers persist per solver (one per worker) and are reused while the tile size matches:
  // per-tile allocation cost 3.4 s summed on Mali for a 63-tile 1x merge.
  size_t slot = 0;
  auto buf = [&](size_t bytes, bool host_read = false) -> Buffer& {
    if (cache_.size() <= slot) cache_.emplace_back();
    Entry& en = cache_[slot++];
    if (!en.buf.map || en.bytes != bytes || en.host_read != host_read) {
      gpu_->free(en.buf);
      en.buf = gpu_->alloc(bytes, host_read);
      en.bytes = bytes;
      en.host_read = host_read;
    }
    return en.buf;
  };
  Buffer &y = buf((nLR + 1) / 2 * 4), &pb = buf(size_t(K) * 24, true), &db = buf(size_t(K) * nb * 8, true);
  Buffer &z = buf(nHR * 4, true), &z0 = buf(nHR * 4);
  Buffer &acc = buf(nHR * 4), &cov = buf(nHR * 4, true);
  Buffer& cov1 = buf(noise_scale ? nHR * 4 : 16, true);   // plain coverage U^T 1 (noise_scale only)
  Buffer &r = buf(nLR * 4), &vm = buf(nLR * 4), &e = buf(nLR * 4, resid_out != nullptr),
         &wt = buf(nLR * 4, weights_out != nullptr);
  const uint32_t jg = div_up(uint32_t(h * w), 256);
  Buffer& part = buf(size_t(std::max(K - 1, 1)) * jg * 24 * 4, true);
  const bool guide = prm.guide > 0.0f && !(h & 1) && !(w & 1);
  Buffer &gm = buf(guide ? nLR * 4 : 16), &gv = buf(guide ? size_t(h) * w * 4 : 16);
  const bool lkw = prm.lkr_weights && guide;
  const size_t kb = lkw ? nLR * 4 : 16;
  Buffer &rrt = buf(kb), &rwt2 = buf(lkw ? nLR * 16 : 16), &wvt2 = buf(kb), &tdetb = buf(kb), &wtr = buf(kb);
  Buffer &kebuf = buf(kb), &wrawb = buf(kb), &wl = buf(kb), &s3 = buf(kb), &s4 = buf(kb), &s5 = buf(kb);
  Buffer &blk = buf(size_t(std::max(K - 1, 1)) * nb * 5 * 4), &dtmp = buf(size_t(K) * nb * 8);
  Buffer& dvb = buf(nHR * 4);   // merge-step divisor, recomputed when the coverage changes
  Buffer& zstart = buf(cavg >= 2 ? nHR * 4 : 16);
  // GPU combine (mode 5, chroma from the average: the default); LKRL_HYB_CPU=1 / LKRL_CT keep the host path.
  const bool gpu_hyb = cavg == 5 && !std::getenv("LKRL_CT") && !std::getenv("LKRL_HYB_CPU");
  Buffer &zavgB = buf(gpu_hyb ? nHR * 4 : 16), &hybD = buf(gpu_hyb ? size_t(2) * H * W * 4 : 16),
         &hybO = buf(gpu_hyb ? nHR * 4 : 16, true);
  Buffer& y2 = buf(std::getenv("LKRL_FREQ") ? (nLR + 1) / 2 * 4 : 16, true);   // freq-robust samples (prototype)   // start image (hybrid modes >= 2)
  for (size_t i = 0; i < slot; ++i)
    if (!cache_[i].buf.map) return fail("buffer allocation");
  struct Reset { Gpu* g; ~Reset() { g->resetSets(); } } reset_sets{gpu_};

  const float mhc_mix = prm.mhc_mix >= 0.0f ? prm.mhc_mix : (s == 1 ? 0.75f : 1.0f);
  const float jacobi_damp = prm.jacobi_damp >= 0.0f ? prm.jacobi_damp : 0.0f;
  PackHalf(frames, nLR, y.as<uint32_t>());   // fp16 frames, two per uint (shaders: yv())
  std::memcpy(pb.map, p.data(), p.size() * 4);
  std::memcpy(db.map, dloc.data(), dloc.size() * 4);
  {
    std::vector<float> rgb(size_t(3) * h * w), up(nHR);
    if (mhc_mix > 0.0f) MhcCoherentRggb(frames, h, w, rgb.data());
    // z0 (weights / first motion steps) = LK-R's own start, pure MHC; only the merge starts from
    // the MHC + bilinear mix. Mixed z0 lowered chrome glints (IMG_20261107) vs LK-R.
    static const int z0_mhc = std::getenv("LKRL_Z0MHC") ? std::atoi(std::getenv("LKRL_Z0MHC")) : 1;
    if (z0_mhc && mhc_mix < 1.0f) {
      std::vector<float> m(rgb.size()), u0(nHR);
      if (mhc_mix > 0.0f) m = rgb; else MhcCoherentRggb(frames, h, w, m.data());
      UpsampleBilinear(m.data(), 3, h, w, s, u0.data());
      std::memcpy(z0.map, u0.data(), nHR * 4);
    }
    if (mhc_mix < 1.0f) {
      std::vector<float> bil(rgb.size());
      DemosaicBilinear(frames, h, w, bil.data());
      const float a = mhc_mix;
      for (size_t o = 0; o < rgb.size(); ++o) rgb[o] = a > 0.0f ? a * rgb[o] + (1.0f - a) * bil[o] : bil[o];
    }
    if (prm.start_notch > 0) {
      // Demodulate the three Bayer-period carriers of Y, box-average, remodulate, subtract.
      const int rr = prm.start_notch;
      const size_t n = size_t(h) * w;
      std::vector<double> c(n), box(n), tmp(n);
      for (int mode = 0; mode < 3; ++mode) {
        auto sgn = [&](int i, int j) { return ((mode == 0 ? i + j : mode == 1 ? i : j) & 1) ? -1.0 : 1.0; };
        for (int i = 0; i < h; ++i)
          for (int j = 0; j < w; ++j) {
            const size_t o = size_t(i) * w + j;
            c[o] = sgn(i, j) * (rgb[o] + rgb[n + o] + rgb[2 * n + o]) / 3.0;
          }
        for (int i = 0; i < h; ++i)   // separable box, clamped borders
          for (int j = 0; j < w; ++j) {
            double a = 0;
            for (int d = -rr; d <= rr; ++d) a += c[size_t(i) * w + std::min(std::max(j + d, 0), w - 1)];
            tmp[size_t(i) * w + j] = a / (2 * rr + 1);
          }
        for (int i = 0; i < h; ++i)
          for (int j = 0; j < w; ++j) {
            double a = 0;
            for (int d = -rr; d <= rr; ++d) a += tmp[size_t(std::min(std::max(i + d, 0), h - 1)) * w + j];
            box[size_t(i) * w + j] = a / (2 * rr + 1);
          }
        for (int i = 0; i < h; ++i)
          for (int j = 0; j < w; ++j) {
            const size_t o = size_t(i) * w + j;
            const float v = float(sgn(i, j) * box[o]);
            rgb[o] -= v; rgb[n + o] -= v; rgb[2 * n + o] -= v;
          }
      }
    }
    UpsampleBilinear(rgb.data(), 3, h, w, s, up.data());
    std::memcpy(z.map, up.data(), nHR * 4);
    if (cavg >= 2) std::memcpy(zstart.map, up.data(), nHR * 4);
    if (!(z0_mhc && mhc_mix < 1.0f)) std::memcpy(z0.map, up.data(), nHR * 4);
  }
  Push q{};
  q.h = h; q.w = w; q.s = s; q.K = K; q.H = H; q.W = W; q.LB = LB; q.mode = 0;
  q.a = prm.noise_a; q.b = prm.noise_b; q.clip = HalfRound(prm.clip); q.robk = prm.robust_k;
  q.tau = prm.robust_tau; q.rpow = std::max(prm.robust_pow, 1.0f);
  q.scale = float(std::ldexp(1.0, 28) / K);   // |r| <= 1, <= 4 s^2 taps per frame and HR pixel
  q.dscale = q.scale;

  const uint32_t gx = uint32_t(K) * div_up(uint32_t(w), 16), gy = div_up(uint32_t(h), 16);
  VkDescriptorSet sResid0 = gpu_->set(resid_, {&z0, &y, &pb, &db, &r, &vm});
  VkDescriptorSet sErr = gpu_->set(werr_, {&r, &y, &e});
  VkDescriptorSet sAvg = gpu_->set(wavg_, {&e, &r});      // r is dead after werr: holds pre-spread weights
  VkDescriptorSet sMinH = gpu_->set(wminh_, {&r, &e, &vm});
  VkDescriptorSet sMinV = gpu_->set(wminv_, {&e, &wt, &vm});
  VkDescriptorSet sAdj = gpu_->set(adj_, {&z, &y, &pb, &db, &wt, &acc, &cov});
  // LKRL_ADJ_WG=1: workgroup-per-tile adjoint (bit-identical acc; phone A/B 2026-11-14: no gain, atomics
  // are not adj's cost -- off).
  static const bool adj_old = !(std::getenv("LKRL_ADJ_WG") && std::atoi(std::getenv("LKRL_ADJ_WG")) != 0);
  VkDescriptorSet sAdjW = adj_old ? VK_NULL_HANDLE : gpu_->set(adjwg_, {&z, &y, &pb, &db, &wt, &acc, &cov});
  const uint32_t gxw = div_up(uint32_t(w), 16), gyw = div_up(uint32_t(h), 16);
  VkDescriptorSet sAdjF = gpu_->set(adj_, {&z, &y2, &pb, &db, &vm, &acc, &cov});   // freq-robust average
  VkDescriptorSet sGStats = gpu_->set(gstats_, {&y, &gm, &gv});
  VkDescriptorSet sGuide = gpu_->set(wguide_, {&gm, &gv, &pb, &db, &r});
  const uint32_t gqx = div_up(uint32_t(w / 2), 16), gqy = div_up(uint32_t(h / 2), 16);
  // LK-R weight chain descriptor sets (bindings exactly as lkr_solver.cpp; r, e: Lite scratch).
  VkDescriptorSet kRT = VK_NULL_HANDLE, kTDet = VK_NULL_HANDLE, kTH = VK_NULL_HANDLE, kWT = VK_NULL_HANDLE,
                  kWV = VK_NULL_HANDLE, kWD = VK_NULL_HANDLE, kG = VK_NULL_HANDLE, kMH = VK_NULL_HANDLE,
                  kM = VK_NULL_HANDLE, kV = VK_NULL_HANDLE;
  if (lkw) {
    kRT = gpu_->set(kresid_, {&z0, &y, &pb, &rrt, &db});
    kTDet = gpu_->set(ktdet_, {&rrt, &y, &tdetb});
    kTH = gpu_->set(kth_, {&rrt, &y, &rwt2, &wvt2, &tdetb});
    kWT = gpu_->set(kt_, {&rrt, &y, &rwt2, &wtr, &wvt2, &kebuf});
    kWV = gpu_->set(ktv_, {&kebuf, &rwt2, &wvt2, &r, &e, &wrawb, &s3, &s4});
    kWD = gpu_->set(ktd_, {&kebuf, &y, &wtr, &r, &e, &wrawb, &s3, &s4});
    kG = gpu_->set(kguide_, {&gm, &gv, &pb, &wrawb, &db, &wtr});
    kMH = gpu_->set(kminh_, {&wrawb, &wtr, &kebuf});
    kM = gpu_->set(kmin_, {&rrt, &kebuf, &s3, &wl, &s5});
    kV = gpu_->set(wvalid_, {&wl, &vm, &wt});
  }
  KPush kq{};
  kq.h = h; kq.w = w; kq.s = s; kq.K = K; kq.H = H; kq.W = W; kq.kOff = LB; kq.mode = 0;
  kq.f0 = 1e-9f; kq.f2 = HalfRound(prm.clip); kq.f3 = prm.robust_tau;
  kq.rob_a = prm.noise_a; kq.rob_b = prm.noise_b; kq.rob_k = prm.robust_k;
  kq.rob_on = (prm.robust && prm.noise_b > 0) ? std::max(1.0f, prm.robust_pow) : 0.0f;
  const bool ktemporal = prm.temporal_k > 0.0f && prm.robust_spread > 0;
  VkDescriptorSet sAdjPlain = noise_scale ? gpu_->set(adj_, {&z, &y, &pb, &db, &vm, &acc, &cov1}) : VK_NULL_HANDLE;
  // Final residual vs z (motion look): written to e, validity to r (both dead by then).
  VkDescriptorSet sResidZ = resid_out ? gpu_->set(resid_, {&z, &y, &pb, &db, &e, &r}) : VK_NULL_HANDLE;
  VkDescriptorSet sUpd = gpu_->set(zupd_, {&z, &acc, &cov, &dvb});
  VkDescriptorSet sJac = gpu_->set(jac_, {&z, &y, &pb, &db, &wt, &part});
  VkDescriptorSet sBlk = gpu_->set(jacblk_, {&z, &y, &pb, &db, &wt, &blk});
  Push qg = q;
  qg.mode = prm.gn_weights == 1 ? 1 : 0;   // 1: motion steps need no robust weights (refreshed lazily)
  VkDescriptorSet sSolveBlk = gpu_->set(solveblk_, {&blk, &db, &dtmp});
  float lam = prm.local_lambda;
  if (prm.auto_local_lambda) {
    const float sl = std::sqrt(std::max(prm.noise_a * 0.18f + prm.noise_b, 0.0f));
    lam = std::min(5.0f, lam * std::max(1.0f, (sl / 0.0025f) * (sl / 0.0025f)));
  }
  gpu_->profile(prm.profile);

  bool need_cov = true, weights_stale = false, chain_done = false;
  const int fitK = std::getenv("LKRL_FITK") ? std::min(std::max(std::atoi(std::getenv("LKRL_FITK")), 1), K) : K;
  std::vector<float> zavg;
  auto weights = [&]() {   // robust weights vs the reference-only start image, current geometry
    gpu_->dispatch(resid_, sResid0, &q, gx, gy);   // validity (vm) of this geometry
    if (lkw && chain_done && prm.lkr_weights_once) {   // keep LK-R's weights, new validity only
      gpu_->dispatch(wvalid_, kV, &q, div_up(uint32_t(nLR), 256));
      need_cov = true;
      weights_stale = false;
      return;
    }
    if (lkw) {   // LK-R's chain, same order and push values as lkr_solver.cpp (guide on)
      chain_done = true;
      if (ktemporal && kq.rob_on > 0.5f) {
        KPush qt = kq; qt.rob_k = prm.temporal_k; qt.f3 = prm.temporal_tau;
        gpu_->dispatch(kresid_, kRT, &qt, gx, gy, 1);   // frame-innermost (gx = K blocks)
        KPush qd = qt; qd.f0 = prm.temporal_detail; qd.f1 = prm.temporal_dark_wide ? 1.0f : 0.0f;
        qd.f2 = prm.temporal_dark_level;
        KPush qh = qd; qh.f1 = 0.0f;
        const uint32_t g1 = div_up(uint32_t(w), 16);
        if (qd.f0 > 0.0f) gpu_->dispatch(ktdet_, kTDet, &qd, g1, gy, uint32_t(K));
        gpu_->dispatch(kth_, kTH, &qh, g1, gy, uint32_t(K));
        gpu_->dispatch(kt_, kWT, &qd, g1, gy, uint32_t(K));
        if (prm.temporal_dark_wide) {
          gpu_->dispatch(ktv_, kWV, &qd, g1, gy, uint32_t(K));
          gpu_->dispatch(ktd_, kWD, &qd, g1, gy, uint32_t(K));
        }
      } else {
        gpu_->fill(wtr, 0x3f800000u);   // 1.0f: no temporal gate
      }
      KPush qg = kq; qg.f0 = prm.guide; qg.f1 = 0.0f;
      gpu_->dispatch(kguide_, kG, &qg, gqx, gqy, uint32_t(K));
      KPush qm = kq; qm.f3 = float(prm.robust_spread);
      const uint32_t g1 = div_up(uint32_t(w), 16);
      gpu_->dispatch(kminh_, kMH, &qm, g1, gy, uint32_t(K));
      gpu_->dispatch(kmin_, kM, &qm, g1, gy, uint32_t(K));
      static const float tsm = std::getenv("LKRL_TSMOOTH") ? float(std::atof(std::getenv("LKRL_TSMOOTH"))) : 0.0f;
      if (tsm > 0.0f && K > 3) {
        // Steady rejection (prototype): each non-reference frame's weight blended with its neighbours'
        // (frames 1..K-1, same LR sample) by tsm, so a sample does not flip in and out frame to frame.
        if (!gpu_->submit()) return;
        gpu_->begin();
        float* Wl = wl.as<float>();
        const size_t n1 = size_t(h) * w;
        std::vector<float> o(size_t(K) * n1);
        std::memcpy(o.data(), Wl, o.size() * 4);
        for (int k = 1; k < K; ++k) {
          const int a = k > 1 ? k - 1 : k + 1, b = k < K - 1 ? k + 1 : k - 1;
          for (size_t i = 0; i < n1; ++i) {
            const float v = o[size_t(k) * n1 + i], nb = 0.5f * (o[size_t(a) * n1 + i] + o[size_t(b) * n1 + i]);
            Wl[size_t(k) * n1 + i] = (1.0f - tsm) * v + tsm * nb;
          }
        }
      }
      gpu_->dispatch(wvalid_, kV, &q, div_up(uint32_t(nLR), 256));
      need_cov = true;
      weights_stale = false;
      return;
    }
    gpu_->dispatch(werr_, sErr, &q, gx, gy);
    if (!guide || prm.guide_mode == 1) gpu_->dispatch(wavg_, sAvg, &q, gx, gy);
    if (guide) {
      Push g = q; g.robk = prm.guide; g.mode = prm.guide_mode;
      gpu_->dispatch(wguide_, sGuide, &g, gqx, gqy, uint32_t(K));
    }
    Push m = q; m.mode = prm.robust_spread;
    gpu_->dispatch(wminh_, sMinH, &m, gx, gy);
    gpu_->dispatch(wminv_, sMinV, &m, gx, gy);
    need_cov = true;
    weights_stale = false;
  };
  auto submit = [&]() -> bool { ++S.submits; return gpu_->submit(); };

  gpu_->begin();
  gpu_->fill(acc, 0);
  if (guide) gpu_->dispatch(gstats_, sGStats, &q, gqx, gqy, uint32_t(K));
  weights_stale = true;
  // 2026-11-14 (hybrid merge): 1x 5 -> 3 merge steps (real detail 0.99-1.01), 2x 10 -> 8 (finest band 0.94).
  std::string sched = !prm.schedule.empty() ? prm.schedule : (s == 1 ? "GGJJGJ" : "GGJJJJGJJJJ");
  // Hybrid chroma: the shift-and-add average after the first motion steps (its chroma, the fit's luma).
  if (cavg && sched.find('A') == std::string::npos) {
    const size_t at = sched.find('J');
    sched.insert(at == std::string::npos ? sched.size() : at, 1, 'A');
  }
  // LK-R's N data steps are emulated over the WHOLE schedule: each merge step applies the response of
  // N / (merge steps) LK-R steps. (Applying all N at every merge step over-converged sparsely covered
  // pixels: a Bayer colour maze on moving subjects and grass at 2x, 1.9x LK-R's.)
  const int n_merge = std::max(1, int(std::count(sched.begin(), sched.end(), 'J')));
  const float steps_per_merge = prm.lkr_steps / float(n_merge);
  for (size_t si = 0; si < sched.size(); ++si) {
    const char c = sched[si];
    if (c == 'J' || c == 'A') {
      // 'A': restart from the weighted aligned average z = U^T W y / U^T W 1 (shift-and-add: one
      // undamped Jacobi step from z = 0) -- every frame averaged instead of the reference start.
      const bool avg = c == 'A';
      if (weights_stale) weights();
      if (avg) { gpu_->fill(z, 0); need_cov = true; }
      if (need_cov) gpu_->fill(cov, 0);
      Push m = q; m.mode = need_cov ? 1 : 0;
      if (avg) m.rpow = -1.0f;   // average: clipped samples count (all-clipped pixels stay blown, not 0)
      static const float freq_c = std::getenv("LKRL_FREQ") ? float(std::atof(std::getenv("LKRL_FREQ"))) : 0.0f;
      if (avg && freq_c > 0.0f && K > 1) {
        // Frequency-domain robustness (HDR+ / IPOL merge, per sample): every frame enters with weight 1
        // (validity only) but as y' = U z0 + shrink(y - U z0), the residual against the reference start
        // shrunk per 8x8 DCT coefficient (per Bayer phase, half-overlapped sine windows) by
        // c s2 / (c s2 + X^2), s2 = residual noise (frame + reference, 2 (a y + b)). Small (noise-like)
        // differences pass -> averaged; large mismatches (motion, misalignment) are cut per frequency.
        gpu_->dispatch(resid_, sResid0, &q, gx, gy);
        if (!submit()) return fail("submit (freq resid)");
        gpu_->begin();
        const float* R = r.as<float>();
        const float* V = vm.as<float>();
        const float* Yin = frames;
        std::vector<float> Y2v(Yin, Yin + nLR);
        float* Y2 = Y2v.data();
        static float basis[8][8], win[8];
        static bool init = false;
        if (!init) {
          for (int u2 = 0; u2 < 8; ++u2)
            for (int x = 0; x < 8; ++x)
              basis[u2][x] = float((u2 == 0 ? std::sqrt(1.0 / 8) : std::sqrt(2.0 / 8)) * std::cos(M_PI * (2 * x + 1) * u2 / 16.0));
          for (int x = 0; x < 8; ++x) win[x] = float(std::sin(M_PI * (x + 0.5) / 8.0));
          init = true;
        }
        const int hp = h / 2, wp = w / 2;
        std::vector<float> P(size_t(hp) * wp), Qa(size_t(hp) * wp), Qw(size_t(hp) * wp), Mean(size_t(hp) * wp);
        for (int k = 1; k < K; ++k)
          for (int ph = 0; ph < 4; ++ph) {
            const int pi = ph >> 1, pj = ph & 1;
            for (int i = 0; i < hp; ++i)
              for (int j = 0; j < wp; ++j) {
                const size_t o = (size_t(k) * h + 2 * i + pi) * w + 2 * j + pj;
                P[size_t(i) * wp + j] = V[o] > 0.5f ? R[o] : 0.0f;
                Mean[size_t(i) * wp + j] = Yin[o];
              }
            std::fill(Qa.begin(), Qa.end(), 0.0f);
            std::fill(Qw.begin(), Qw.end(), 0.0f);
            for (int ty = -4; ty < hp; ty += 4)
              for (int tx = -4; tx < wp; tx += 4) {
                float blk[8][8], co[8][8], tmp[8][8];
                float ym = 0.0f; int nm = 0;
                for (int y = 0; y < 8; ++y)
                  for (int x = 0; x < 8; ++x) {
                    const int yy = ty + y, xx = tx + x;
                    const bool in = yy >= 0 && xx >= 0 && yy < hp && xx < wp;
                    blk[y][x] = in ? P[size_t(yy) * wp + xx] * win[y] * win[x] : 0.0f;
                    if (in) { ym += Mean[size_t(yy) * wp + xx]; ++nm; }
                  }
                ym = nm ? ym / float(nm) : 0.0f;
                const float s2 = 2.0f * std::max(prm.noise_a * ym + prm.noise_b, 1e-12f) * 0.5f;   // x mean(win^2) = 0.5 per axis^2 ~ 0.25..0.5
                for (int y = 0; y < 8; ++y)
                  for (int u2 = 0; u2 < 8; ++u2) { float a = 0; for (int x = 0; x < 8; ++x) a += blk[y][x] * basis[u2][x]; tmp[y][u2] = a; }
                for (int v2 = 0; v2 < 8; ++v2)
                  for (int u2 = 0; u2 < 8; ++u2) { float a = 0; for (int y = 0; y < 8; ++y) a += tmp[y][u2] * basis[v2][y]; co[v2][u2] = a; }
                for (int v2 = 0; v2 < 8; ++v2)
                  for (int u2 = 0; u2 < 8; ++u2) { const float x2 = co[v2][u2] * co[v2][u2]; co[v2][u2] *= freq_c * s2 / (freq_c * s2 + x2); }
                for (int v2 = 0; v2 < 8; ++v2)
                  for (int x = 0; x < 8; ++x) { float a = 0; for (int u2 = 0; u2 < 8; ++u2) a += co[v2][u2] * basis[u2][x]; tmp[v2][x] = a; }
                for (int y = 0; y < 8; ++y)
                  for (int x = 0; x < 8; ++x) {
                    float a = 0; for (int v2 = 0; v2 < 8; ++v2) a += tmp[v2][x] * basis[v2][y];
                    const int yy = ty + y, xx = tx + x;
                    if (yy < 0 || xx < 0 || yy >= hp || xx >= wp) continue;
                    Qa[size_t(yy) * wp + xx] += a * win[y] * win[x];
                    Qw[size_t(yy) * wp + xx] += win[y] * win[x] * win[y] * win[x];
                  }
              }
            for (int i = 0; i < hp; ++i)
              for (int j = 0; j < wp; ++j) {
                const size_t o = (size_t(k) * h + 2 * i + pi) * w + 2 * j + pj;
                if (V[o] < 0.5f) continue;
                const float rs = Qw[size_t(i) * wp + j] > 0 ? Qa[size_t(i) * wp + j] / Qw[size_t(i) * wp + j] : 0.0f;
                Y2[o] = Yin[o] - R[o] + rs;
              }
          }
        PackHalf(Y2, nLR, y2.as<uint32_t>());
        gpu_->dispatch(adj_, sAdjF, &m, gx, gy);
      } else if (!adj_old) {
        gpu_->dispatch(adjwg_, sAdjW, &m, gxw, gyw);
      } else {
        Push mk = m;
        if (!avg && fitK < K) mk.K = fitK;   // experiment: the fit on the first fitK frames only
        gpu_->dispatch(adj_, sAdj, &mk, uint32_t(mk.K) * div_up(uint32_t(w), 16), gy);
      }
      const int Kf = avg ? K : fitK;
      Push u = q; u.tau = jacobi_damp * float(Kf) / float(s * s); u.LB = prm.cov_smooth >= 0 ? prm.cov_smooth : (s == 1 ? 0 : 1); u.a = steps_per_merge; u.b = (prm.lkr_l > 0.0f ? prm.lkr_l : (s == 1 ? 1.45f : 1.8f)) * float(Kf) * 0.5f / float(s * s);
      u.mode = need_cov || std::getenv("LKRL_NO_DVCACHE") ? 1 : 0;   // 1: (re)compute the divisor, 0: cached
      // Update smoothing ([1 2 1] on the step, 2x) tried 2026-11-13: checker only partly lower, parity
      // 63.4 -> 58.9 dB on the cat crop. Off unless LKRL_UPDSMOOTH is set.
      static const bool upd_smooth = std::getenv("LKRL_UPDSMOOTH") != nullptr;
      u.robk = (s == 2 && upd_smooth) ? 1.0f : 0.0f;
      // Over-relaxation, 1x only (glints: IMG_20261107 1x 53.6 -> 54.4 dB vs LK-R); at 2x it cost 0.7-1.3 dB.
      static const float omega_env = std::getenv("LKRL_OMEGA") ? float(std::atof(std::getenv("LKRL_OMEGA"))) : 0.0f;
      const float omega = omega_env > 0.0f ? omega_env : (s == 1 ? 1.6f : 1.0f);
      u.rpow = omega;
      static const int avg_r = std::getenv("LKRL_AVGR") ? std::atoi(std::getenv("LKRL_AVGR")) : -1;
      if (avg) { u.a = 0.0f; u.rpow = -1.0f; u.robk = 0.0f; u.LB = avg_r >= 0 ? avg_r : (s == 1 ? 0 : 1); }
      gpu_->dispatch(zupd_, sUpd, &u, div_up(uint32_t(nHR), 256));
      if (u.robk > 0.0f || avg) gpu_->fill(acc, 0);
      need_cov = avg;   // the next J recomputes its (gain) divisor
      static const bool kn_on = std::getenv("LKRL_KN") != nullptr;
      if (avg && kn_on) {
        // KernelNet average (prototype, CPU): z_c(X) = sum w_k exp(-0.5 d^T Om(X)^-1 d) y / sum(...)
        // over every frame's channel-c samples within 3 raw px, d = HR pixel centre - warped sample
        // (raw px), Om(X) = MFSR's KernelNet kernel at X (reference frame). No weight: GPU average kept.
        if (!submit()) return fail("submit (kn)");
        gpu_->begin();
        static const float kscale = std::getenv("LKRL_KN_SCALE") ? float(std::atof(std::getenv("LKRL_KN_SCALE"))) : 0.25f;
        static const float kcap = std::getenv("LKRL_KN_CAP") ? float(std::atof(std::getenv("LKRL_KN_CAP"))) : 2.0f;
        std::vector<float> map; int mh = 0, mw = 0;
        if (KernelNetMap(frames, h, w, std::sqrt(std::max(0.5f * prm.noise_a + prm.noise_b, 0.0f)), &map, &mh, &mw)) {
          const size_t HW = size_t(H) * W;
          std::vector<float> inv(3 * HW), num(3 * HW, 0.0f), den(3 * HW, 0.0f);
          for (int Y = 0; Y < H; ++Y)
            for (int X = 0; X < W; ++X) {
              float om[3];
              KernelOmega(map, mh, mw, h, w, (X + 0.5f) / s, (Y + 0.5f) / s, kscale, kcap, om);
              const float dt = om[0] * om[2] - om[1] * om[1];
              const size_t i = size_t(Y) * W + X;
              inv[3 * i] = om[2] / dt; inv[3 * i + 1] = -om[1] / dt; inv[3 * i + 2] = om[0] / dt;
            }
          const float* P = pb.as<float>();
          const float* D = db.as<float>();
          const float* Wt = wt.as<float>();
          const int nbx2 = (w + LB - 1) / LB, nby2 = (h + LB - 1) / LB;
          const int R = 3 * s;
          for (int k = 0; k < K; ++k)
            for (int i = 0; i < h; ++i)
              for (int j = 0; j < w; ++j) {
                const size_t o = (size_t(k) * h + i) * w + j;
                const float yv = frames[o];
                float wk = Wt[o];
                if (yv >= prm.clip) wk = 1.0f;
                if (wk <= 0.0f) continue;
                const float ux = j + 0.5f, uy = i + 0.5f;
                const int bx = std::min(std::max(int(ux) / LB, 0), nbx2 - 1), by = std::min(std::max(int(uy) / LB, 0), nby2 - 1);
                const float* pk = P + 6 * k;
                const float* dk = D + 2 * ((size_t(k) * nby2 + by) * nbx2 + bx);
                const float qx = pk[0] * ux + pk[1] * uy + pk[2] + dk[0];
                const float qy = pk[3] * ux + pk[4] * uy + pk[5] + dk[1];
                const int c = ((i & 1) == 0 && (j & 1) == 0) ? 0 : (((i & 1) && (j & 1)) ? 2 : 1);
                const int Xc = int(std::floor(qx * s)), Yc = int(std::floor(qy * s));
                for (int Y = std::max(Yc - R, 0); Y <= std::min(Yc + R, H - 1); ++Y)
                  for (int X = std::max(Xc - R, 0); X <= std::min(Xc + R, W - 1); ++X) {
                    const float dx = (X + 0.5f) / s - qx, dy = (Y + 0.5f) / s - qy;
                    const size_t ip = size_t(Y) * W + X;
                    const float qf = inv[3 * ip] * dx * dx + 2.0f * inv[3 * ip + 1] * dx * dy + inv[3 * ip + 2] * dy * dy;
                    if (qf > 18.0f) continue;
                    const float wgt = wk * std::exp(-0.5f * qf);
                    num[c * HW + ip] += wgt * yv; den[c * HW + ip] += wgt;
                  }
              }
          float* Z = z.as<float>();
          for (size_t i = 0; i < 3 * HW; ++i)
            if (den[i] > 1e-3f) Z[i] = num[i] / den[i];
        }
      }
      if (avg && cavg && gpu_hyb) {   // keep the average on the GPU for the combine
        gpu_->copy(z, zavgB, nHR * 4);
        gpu_->copy(zstart, z, nHR * 4);
      } else if (avg && cavg) {   // hybrid: keep the average for its chroma
        if (!submit()) return fail("submit (avg)");
        zavg.assign(z.as<float>(), z.as<float>() + nHR);
        gpu_->begin();
        // the fit itself restarts from the usual start image: its luma stays exactly the current one
        // LKRL_FROMAVG=1: mode 5 fits from the average (tested 2026-11-14: no effect on the zipper; off).
        static const bool from_avg = std::getenv("LKRL_FROMAVG") && std::atoi(std::getenv("LKRL_FROMAVG")) != 0;
        if (cavg >= 2 && !(cavg == 5 && from_avg)) gpu_->copy(zstart, z, nHR * 4);
      }
    } else if (c == 'G') {
      if (K < 2) continue;
      if (weights_stale && qg.mode == 0) weights();
      gpu_->dispatch(jac_, sJac, &qg, jg, uint32_t(K - 1));
      if (!submit()) return fail("submit (jac)");
      const double th = now_ms();
      const float* P = part.as<float>();
      float* pm = pb.as<float>();
      const double hx = 0.5 * w, hy = 0.5 * h;   // jac.comp moments are about the tile centre
      for (int k = 1; k < K; ++k) {
        double M[24] = {0};
        for (uint32_t g = 0; g < jg; ++g)
          for (int c2 = 0; c2 < 24; ++c2) M[c2] += P[(size_t(k - 1) * jg + g) * 24 + c2];
        // J = (gx X, gx Y, gx, gy X, gy Y, gy), X = x - hx: blocks of (X^2, XY, Y^2, X, Y, 1).
        auto blk3 = [&](const double* q, double B[3][3]) {
          B[0][0] = q[0]; B[0][1] = B[1][0] = q[1]; B[1][1] = q[2];
          B[0][2] = B[2][0] = q[3]; B[1][2] = B[2][1] = q[4]; B[2][2] = q[5];
        };
        double Bxx[3][3], Bxy[3][3], Byy[3][3], A[6][6], bv[6], dp[6];
        blk3(M, Bxx); blk3(M + 6, Bxy); blk3(M + 12, Byy);
        for (int a = 0; a < 3; ++a)
          for (int b = 0; b < 3; ++b) {
            A[a][b] = Bxx[a][b]; A[a][3 + b] = A[3 + b][a] = Bxy[a][b]; A[3 + a][3 + b] = Byy[a][b];
          }
        for (int a = 0; a < 6; ++a) bv[a] = M[18 + a];
        double tr = 0;
        for (int a = 0; a < 6; ++a) tr += A[a][a];
        for (int a = 0; a < 6; ++a) A[a][a] += 1e-9 * tr;
        if (!(tr > 0) || !solve6(A, bv, dp)) continue;
        // Centred parameters -> ours: a X + b Y + c = a x + b y + (c - a hx - b hy).
        dp[2] -= dp[0] * hx + dp[1] * hy;
        dp[5] -= dp[3] * hx + dp[4] * hy;
        for (int a = 0; a < 6; ++a) pm[k * 6 + a] = float(double(pm[k * 6 + a]) + dp[a]);
      }
      S.ms_host += now_ms() - th;
      gpu_->begin();
      gpu_->dispatch(jacblk_, sBlk, &qg, uint32_t(nbx), uint32_t(nby), uint32_t(K - 1));
      Push m = q; m.robk = lam; m.tau = prm.local_floor;
      gpu_->dispatch(solveblk_, sSolveBlk, &m, div_up(uint32_t(K * nb), 64));
      gpu_->copy(dtmp, db, size_t(K) * nb * 8);
      weights_stale = true;
    } else {
      return fail("schedule: only J, A and G");
    }
  }
  if (noise_scale) {   // coverage of the final geometry: weighted (cov, up to date) vs plain
    if (weights_stale) weights();
    if (need_cov) {   // weights changed after the last merge step: weighted coverage too
      gpu_->fill(cov, 0);
      Push m = q; m.mode = 2;
      gpu_->dispatch(adj_, sAdj, &m, gx, gy);
    }
    gpu_->fill(cov1, 0);
    Push m = q; m.mode = 2;
    gpu_->dispatch(adj_, sAdjPlain, &m, gx, gy);
  }
  if (resid_out || weights_out) {
    if (weights_stale) weights();
    if (resid_out) gpu_->dispatch(resid_, sResidZ, &q, gx, gy);
  }
  bool hyb_done = false;
  if (gpu_hyb && sched.find('A') != std::string::npos) {
    struct HybPush { int32_t H, W, K, mode; float a, b, t, clip, wbr, wbg, wbb, pad; };
    static_assert(sizeof(HybPush) == 48, "hyb push");
    const float rel = std::sqrt(std::max(0.18f * prm.noise_a + prm.noise_b, 0.0f)) / 0.18f;
    const float t_auto = std::min(std::max((rel - 0.03f) / 0.03f, 0.0f), 1.0f);
    const float t = std::getenv("LKRL_LT") ? float(std::atof(std::getenv("LKRL_LT"))) : t_auto;
    HybPush hp{H, W, K, 0, prm.noise_a, prm.noise_b, t, prm.clip, prm.wb[0], prm.wb[1], prm.wb[2], 0.0f};
    VkDescriptorSet sh = gpu_->set(hyb_, {&z, &zavgB, &hybD, &hybO});
    gpu_->dispatch(hyb_, sh, &hp, div_up(uint32_t(W), 16), div_up(uint32_t(H), 16));
    hp.mode = 1;
    gpu_->dispatch(hyb_, sh, &hp, div_up(uint32_t(W), 16), div_up(uint32_t(H), 16));
    hyb_done = true;
  }
  if (!submit()) return fail("final submit");
  if (hyb_done) z_out->assign(hybO.as<float>(), hybO.as<float>() + nHR);
  else z_out->assign(z.as<float>(), z.as<float>() + nHR);
  if (!zavg.empty()) {   // luma of the fit (z), chroma (wb-space R-Y, B-Y) of the average
    const size_t HW = size_t(H) * W;
    const float* wb = prm.wb;
    float* o = z_out->data();
    // Mode 3: luma = Ya + w (Yf - Ya), w = structure / (structure + t noise): the average's clean luma
    // in flat areas, the fit's sharper luma on edges / texture (structure = 3x3 mean squared gradient
    // of the average's luma, noise = the per-sample model a y + b over K frames).
    if (cavg == 5) {
      // Mode 5: out = avg + w_c (fit - avg) per channel c in {Y, R-Y, B-Y} (wb space), Wiener weights
      // w_c = max(E_c - t_c nv_c, 0) / E_c from the 5x5 mean energy E_c of the detail layer and the
      // model noise of one merged sample nv = (a y + b) / K (chroma: t scaled by LKRL_CT).
      // Strength follows the measured noise (user 2026-11-14: ~1 sigma in dim scenes, off in daylight):
      // relative noise at mid-grey sqrt(0.18 a + b) / 0.18, ramp 3 % (off) .. 6 % (t = 1).
      const float rel = std::sqrt(std::max(0.18f * prm.noise_a + prm.noise_b, 0.0f)) / 0.18f;
      const float t_auto = std::min(std::max((rel - 0.03f) / 0.03f, 0.0f), 1.0f);
      const float t = std::getenv("LKRL_LT") ? float(std::atof(std::getenv("LKRL_LT"))) : t_auto;
      // Chroma: from the all-frame average by default (tc < 0). The 2x edge zipper is the fit's CHROMA
      // (car burst 184641: chroma zipper-line power 0.0152 vs luma 0.0021; average chroma 0.0055, fit luma
      // unchanged). LKRL_CT >= 0 = Wiener-shrunk fit chroma detail instead.
      static const float tc = std::getenv("LKRL_CT") ? float(std::atof(std::getenv("LKRL_CT"))) : -1.0f;
      std::vector<float> D[3], Ya(HW), Ua(HW), Va(HW), Yc(HW), tmp(HW), e(HW);
      for (int c = 0; c < 3; ++c) D[c].resize(HW);
      for (size_t i = 0; i < HW; ++i) {
        const float rf = wb[0] * o[i], gf = wb[1] * o[HW + i], bf = wb[2] * o[2 * HW + i];
        const float ra = wb[0] * zavg[i], ga = wb[1] * zavg[HW + i], ba = wb[2] * zavg[2 * HW + i];
        const float yf = 0.25f * rf + 0.5f * gf + 0.25f * bf, ya = 0.25f * ra + 0.5f * ga + 0.25f * ba;
        Ya[i] = ya; Ua[i] = ra - ya; Va[i] = ba - ya;
        D[0][i] = yf - ya; D[1][i] = (rf - yf) - Ua[i]; D[2][i] = (bf - yf) - Va[i];
        Yc[i] = 0.25f * zavg[i] + 0.5f * zavg[HW + i] + 0.25f * zavg[2 * HW + i];
      }
      const float wbl = 0.25f * wb[0] + 0.5f * wb[1] + 0.25f * wb[2];
      const int r = 2;
      for (int c = 0; c < 3; ++c) {
        for (int y = 0; y < H; ++y)
          for (int x = 0; x < W; ++x) {
            float a = 0; int n = 0;
            for (int dx = -r; dx <= r; ++dx) { const int xx = x + dx; if (xx < 0 || xx >= W) continue; const float v = D[c][size_t(y) * W + xx]; a += v * v; ++n; }
            tmp[size_t(y) * W + x] = a / float(n);
          }
        for (int y = 0; y < H; ++y)
          for (int x = 0; x < W; ++x) {
            float a = 0; int n = 0;
            for (int dy = -r; dy <= r; ++dy) { const int yy = y + dy; if (yy < 0 || yy >= H) continue; a += tmp[size_t(yy) * W + x]; ++n; }
            const size_t i = size_t(y) * W + x;
            const float E = a / float(n);
            const float nv = wbl * wbl * std::max(prm.noise_a * Yc[i] + prm.noise_b, 1e-12f) / float(K);
            const float tt = c == 0 ? t : t * tc;
            const float w = (c > 0 && tc < 0.0f) ? 0.0f : (E > 0.0f ? std::max(E - tt * nv, 0.0f) / E : 0.0f);
            e[i] = w;
          }
        if (c > 0 && tc < 0.0f) {
          // Near-clipped highlights (and 2 px around): each channel clips differently across frames, so
          // the average's chroma is false colour there (rainbow glints); keep the fit's chroma (FinishLook
          // neutralises it like LK-R's).
          std::vector<uint8_t> hot(HW, 0);
          for (size_t i = 0; i < HW; ++i) {
            const float m = std::max({o[i], o[HW + i], o[2 * HW + i], zavg[i], zavg[HW + i], zavg[2 * HW + i]});
            if (m >= 0.85f * prm.clip) hot[i] = 1;
          }
          for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
              bool near = false;
              for (int dy = -2; dy <= 2 && !near; ++dy)
                for (int dx = -2; dx <= 2 && !near; ++dx) {
                  const int yy = y + dy, xx = x + dx;
                  if (yy >= 0 && xx >= 0 && yy < H && xx < W && hot[size_t(yy) * W + xx]) near = true;
                }
              if (near) e[size_t(y) * W + x] = 1.0f;
            }
        }
        for (size_t i = 0; i < HW; ++i) D[c][i] *= e[i];
      }
      for (size_t i = 0; i < HW; ++i) {
        const float y = Ya[i] + D[0][i], u = Ua[i] + D[1][i], v = Va[i] + D[2][i];
        const float rr = y + u, bb = y + v, gg = (y - 0.25f * rr - 0.25f * bb) / 0.5f;
        o[i] = rr / wb[0]; o[HW + i] = gg / wb[1]; o[2 * HW + i] = bb / wb[2];
      }
      zavg.clear();
    }
    std::vector<float> lw;
    if (cavg == 4) {
      // Mode 4: Wiener shrinkage of the detail layer D = Yf - Ya (what the fit adds over the average):
      // w = max(E - t nv, 0) / E, E = 5x5 mean of D^2, nv = model noise of one merged sample (a y + b) / K.
      // Real texture (E well above what noise explains) keeps the fit; noise-only areas get the average.
      static const float t = std::getenv("LKRL_LT") ? float(std::atof(std::getenv("LKRL_LT"))) : 16.0f;
      const float wbl = 0.25f * wb[0] + 0.5f * wb[1] + 0.25f * wb[2];
      std::vector<float> d2(HW), Yc(HW), tmp(HW);
      for (size_t i = 0; i < HW; ++i) {
        const float yf = 0.25f * wb[0] * o[i] + 0.5f * wb[1] * o[HW + i] + 0.25f * wb[2] * o[2 * HW + i];
        const float ya = 0.25f * wb[0] * zavg[i] + 0.5f * wb[1] * zavg[HW + i] + 0.25f * wb[2] * zavg[2 * HW + i];
        d2[i] = (yf - ya) * (yf - ya);
        Yc[i] = 0.25f * zavg[i] + 0.5f * zavg[HW + i] + 0.25f * zavg[2 * HW + i];
      }
      const int r = 2;
      for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
          float a = 0; int n = 0;
          for (int dx = -r; dx <= r; ++dx) { const int xx = x + dx; if (xx < 0 || xx >= W) continue; a += d2[size_t(y) * W + xx]; ++n; }
          tmp[size_t(y) * W + x] = a / float(n);
        }
      lw.resize(HW);
      for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
          float a = 0; int n = 0;
          for (int dy = -r; dy <= r; ++dy) { const int yy = y + dy; if (yy < 0 || yy >= H) continue; a += tmp[size_t(yy) * W + x]; ++n; }
          const size_t i = size_t(y) * W + x;
          const float E = a / float(n);
          const float nv = wbl * wbl * std::max(prm.noise_a * Yc[i] + prm.noise_b, 1e-12f) / float(K);
          lw[i] = E > 0.0f ? std::max(E - t * nv, 0.0f) / E : 0.0f;
        }
    }
    if (cavg == 3) {
      static const float t = std::getenv("LKRL_LT") ? float(std::atof(std::getenv("LKRL_LT"))) : 1.0f;
      std::vector<float> Ya(HW), Yc(HW), gq(HW);
      for (size_t i = 0; i < HW; ++i) {
        Ya[i] = 0.25f * wb[0] * zavg[i] + 0.5f * wb[1] * zavg[HW + i] + 0.25f * wb[2] * zavg[2 * HW + i];
        Yc[i] = 0.25f * zavg[i] + 0.5f * zavg[HW + i] + 0.25f * zavg[2 * HW + i];
      }
      for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
          const size_t i = size_t(y) * W + x;
          const float gx = Ya[size_t(y) * W + std::min(x + 1, W - 1)] - Ya[size_t(y) * W + std::max(x - 1, 0)];
          const float gy = Ya[size_t(std::min(y + 1, H - 1)) * W + x] - Ya[size_t(std::max(y - 1, 0)) * W + x];
          gq[i] = 0.25f * (gx * gx + gy * gy);
        }
      lw.resize(HW);
      const float wbl = 0.25f * wb[0] + 0.5f * wb[1] + 0.25f * wb[2];
      for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
          float g = 0.0f; int n = 0;
          for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
              const int yy = y + dy, xx = x + dx;
              if (yy < 0 || xx < 0 || yy >= H || xx >= W) continue;
              g += gq[size_t(yy) * W + xx]; ++n;
            }
          g /= float(n);
          const size_t i = size_t(y) * W + x;
          const float nv = wbl * wbl * std::max(prm.noise_a * Yc[i] + prm.noise_b, 1e-12f) / float(K);
          lw[i] = g / (g + t * nv);
        }
    }
    for (size_t i = 0; i < (zavg.empty() ? 0 : HW); ++i) {
      float r = wb[0] * o[i], g = wb[1] * o[HW + i], b = wb[2] * o[2 * HW + i];
      float Yf = 0.25f * r + 0.5f * g + 0.25f * b;
      if (!lw.empty()) {
        const float Yav = 0.25f * wb[0] * zavg[i] + 0.5f * wb[1] * zavg[HW + i] + 0.25f * wb[2] * zavg[2 * HW + i];
        Yf = Yav + lw[i] * (Yf - Yav);
      }
      float ra = wb[0] * zavg[i], ga = wb[1] * zavg[HW + i], ba = wb[2] * zavg[2 * HW + i];
      const float Ya = 0.25f * ra + 0.5f * ga + 0.25f * ba;
      o[i] = (Yf + ra - Ya) / wb[0]; o[HW + i] = (Yf + ga - Ya) / wb[1]; o[2 * HW + i] = (Yf + ba - Ya) / wb[2];
    }
  }
  if (resid_out) resid_out->assign(e.as<float>(), e.as<float>() + nLR);
  if (weights_out) weights_out->assign(wt.as<float>(), wt.as<float>() + nLR);
  if (noise_scale) {
    const size_t HW = size_t(H) * W;
    const int* c1 = cov1.as<int>();
    const int* cw = cov.as<int>();
    noise_scale->resize(HW);
    if (std::getenv("LKRL_LDBG")) {
      double mx = 0; for (size_t i2 = 0; i2 < 3 * HW; ++i2) mx = std::max(mx, double(c1[i2]) / q.dscale);
      std::fprintf(stderr, "Ldbg max plain coverage %.3f  estimate K/(2 s^2) %.3f\n", mx, double(K) * 0.5 / (s * s));
    }
    for (size_t i = 0; i < HW; ++i) {
      const double a = double(c1[i]) + c1[HW + i] + c1[2 * HW + i];
      const double b = double(cw[i]) + cw[HW + i] + cw[2 * HW + i];
      const float rho = a > 0 ? float(std::min(b / a, 1.0)) : 1.0f;
      (*noise_scale)[i] = std::min(std::sqrt(std::max(0.8f / std::max(rho, 1e-3f), 1.0f)), std::sqrt(float(K)));
    }
  }
  static const float kndn_env = std::getenv("LKRL_KNDN") ? float(std::atof(std::getenv("LKRL_KNDN"))) : 0.0f;
  // Noise-gated (daylight keeps its fine grain and texture): x 0 at <= 4 % relative noise
  // at mid-grey, x 1 at >= 8 % (user 2026-11-14: "on" lost HF noise and detail on IMG_20210228, 3.7 %).
  const float kn_rel = std::sqrt(std::max(0.18f * prm.noise_a + prm.noise_b, 0.0f)) / 0.18f;
  const float kndn = kndn_env * std::min(std::max((kn_rel - 0.04f) / 0.04f, 0.0f), 1.0f);   // 4 % -> 8 %
  static const bool kn_cpu = std::getenv("LKRL_KNDN_CPU") != nullptr;
  if (kndn > 0.0f && !kn_cpu && noise_scale && noise_scale->size() == size_t(H) * W) {
    // GPU (as PhotonCamera runs it): KernelNet conv layers (kn_conv) then the denoise (kn_dn).
    static std::vector<float> wpk;
    if (wpk.empty() && !KernelNetPacked(&wpk)) return fail("KernelNet weights");
    std::vector<float> xin;
    KernelNetInput(frames, h, w, std::sqrt(std::max(0.5f * prm.noise_a + prm.noise_b, 0.0f)), &xin);
    const int lh = h / 2, lw = w / 2, mh = (lh - 1) / 2 + 1, mw = (lw - 1) / 2 + 1;
    const size_t HW = size_t(H) * W;
    Buffer &kw = buf(wpk.size() * 4), &kin = buf(xin.size() * 4), &ka0 = buf(size_t(16) * mh * mw * 4),
           &ka1 = buf(size_t(16) * mh * mw * 4), &kmap = buf(size_t(3) * mh * mw * 4),
           &kz = buf(3 * HW * 4), &kout = buf(3 * HW * 4, true), &kns = buf(HW * 4);
    std::memcpy(kw.map, wpk.data(), wpk.size() * 4);
    std::memcpy(kin.map, xin.data(), xin.size() * 4);
    std::memcpy(kz.map, z_out->data(), 3 * HW * 4);
    std::memcpy(kns.map, noise_scale->data(), HW * 4);
    struct ConvPush { int32_t cin, cout, inW, inH, outW, outH, stride, k, relu, wOff, bOff, mode; };
    struct DnPush { int32_t H, W, mh, mw, s, K, p0, p1; float a, b, strength, p2; };
    static_assert(sizeof(ConvPush) == 48 && sizeof(DnPush) == 48, "kn push");
    const int spec[6][5] = {{2, 16, 3, 2, 1}, {16, 16, 3, 1, 1}, {16, 16, 3, 1, 1},
                            {16, 16, 3, 1, 1}, {16, 16, 3, 1, 1}, {16, 3, 1, 1, 0}};
    gpu_->resetSets();
    gpu_->begin();
    const Buffer* in = &kin;
    int inW = lw, inH = lh, off = 0;
    const Buffer* acts[2] = {&ka0, &ka1};
    for (int li = 0; li < 6; ++li) {
      const int* l = spec[li];
      ConvPush cp{l[0], l[1], inW, inH, mw, mh, l[3], l[2], l[4], off, off + l[1] * l[0] * l[2] * l[2], li == 5 ? 2 : 1};
      off += l[1] * l[0] * l[2] * l[2] + l[1];
      const Buffer* out = li == 5 ? &kmap : acts[li % 2];
      VkDescriptorSet st = gpu_->set(knconv_, {in, out, &kw});
      gpu_->dispatch(knconv_, st, &cp, div_up(uint32_t(l[1] * mw * mh), 256));
      in = out; inW = mw; inH = mh;
    }
    DnPush dp{H, W, mh, mw, s, K, 0, 0, prm.noise_a, prm.noise_b, kndn, 0.0f};
    VkDescriptorSet sd = gpu_->set(kndn_, {&kz, &kout, &kns, &kmap});
    gpu_->dispatch(kndn_, sd, &dp, div_up(uint32_t(W), 16), div_up(uint32_t(H), 16));
    if (!gpu_->submit()) return fail("kernelnet submit");
    z_out->assign(kout.as<float>(), kout.as<float>() + 3 * HW);
  } else if (kndn > 0.0f && noise_scale && noise_scale->size() == size_t(H) * W) {
    // PhotonCamera dynamicgaussian_kernelnet.glsl (post-merge denoise, same math): KernelNet kernel
    // of the reference, area scaled by merged / single-frame variance (here per pixel: ns^2 / K, shadow
    // headroom x2 below 0.1), times the Wiener range weight N^2 / (dl^2 + N^2) on luma. Camera space.
    std::vector<float> map; int mh = 0, mw = 0;
    if (KernelNetMap(frames, h, w, std::sqrt(std::max(0.5f * prm.noise_a + prm.noise_b, 0.0f)), &map, &mh, &mw)) {
      const size_t HW = size_t(H) * W;
      const float* zi = z_out->data();
      std::vector<float> out(3 * HW);
      const int R = 4 * s;
      auto lum = [&](size_t i) { return 0.299f * zi[i] + 0.587f * zi[HW + i] + 0.114f * zi[2 * HW + i]; };
      const size_t mp = size_t(mh) * mw;
      for (int Y = 0; Y < H; ++Y)
        for (int X = 0; X < W; ++X) {
          const size_t i = size_t(Y) * W + X;
          float l = 0.0f; int nl = 0;
          for (int dy = -1; dy <= 1; ++dy)
            for (int dx = -1; dx <= 1; ++dx) {
              const int yy = std::min(std::max(Y + dy, 0), H - 1), xx = std::min(std::max(X + dx, 0), W - 1);
              l += lum(size_t(yy) * W + xx); ++nl;
            }
          l /= float(nl);
          const float preVar = prm.noise_a * std::max(l, 0.0f) + prm.noise_b;
          const float ns = (*noise_scale)[i];
          const float postVar = preVar * ns * ns / float(K);
          const float areaRatio = std::min(std::max(postVar / std::max(preVar, 1e-9f), 0.0f), 1.0f);
          const float t = std::min(std::max(l / 0.1f, 0.0f), 1.0f), sm = t * t * (3 - 2 * t);
          const float shadow = 2.0f + (1.0f - 2.0f) * sm;
          // map texel at this pixel (bilinear, uv = pixel centre); sigmas in map-grid units -> HR px
          const float u = (X + 0.5f) / W * mw - 0.5f, v = (Y + 0.5f) / H * mh - 0.5f;
          const int u0 = std::min(std::max(int(std::floor(u)), 0), mw - 1), v0 = std::min(std::max(int(std::floor(v)), 0), mh - 1);
          const int u1 = std::min(u0 + 1, mw - 1), v1 = std::min(v0 + 1, mh - 1);
          const float fu = std::min(std::max(u - u0, 0.0f), 1.0f), fv = std::min(std::max(v - v0, 0.0f), 1.0f);
          float kp[3];
          for (int cc = 0; cc < 3; ++cc) {
            const float* M = map.data() + cc * mp;
            const float a0 = M[size_t(v0) * mw + u0] + (M[size_t(v0) * mw + u1] - M[size_t(v0) * mw + u0]) * fu;
            const float a1 = M[size_t(v1) * mw + u0] + (M[size_t(v1) * mw + u1] - M[size_t(v1) * mw + u0]) * fu;
            kp[cc] = a0 + (a1 - a0) * fv;
          }
          // sigmaScale = strength * (post resolution / rawHalf resolution) = strength * 2 s
          const float corr = kndn * 2.0f * float(s) * std::sqrt(areaRatio * shadow);
          const float s1 = std::max(kp[0] * corr, 0.02f), s2 = std::max(kp[1] * corr, 0.02f);
          const float rho = std::min(std::max(kp[2], -0.95f), 0.95f);
          const float ir2 = 1.0f / (2.0f * (1.0f - rho * rho));
          const float i11 = ir2 / (s1 * s1), i22 = ir2 / (s2 * s2), i12 = -ir2 * 2.0f * rho / (s1 * s2);
          float sr = 0, sg = 0, sb = 0, ws = 0;
          for (int j = -R; j <= R; ++j)
            for (int q = -R; q <= R; ++q) {
              const float e = float(q * q) * i11 + float(q * j) * i12 + float(j * j) * i22;
              if (e > 12.0f) continue;
              const int yy = std::min(std::max(Y + j, 0), H - 1), xx = std::min(std::max(X + q, 0), W - 1);
              const size_t n = size_t(yy) * W + xx;
              const float dl = lum(n) - l;
              const float wgt = std::exp(-e) * postVar / (dl * dl + postVar);
              sr += wgt * zi[n]; sg += wgt * zi[HW + n]; sb += wgt * zi[2 * HW + n]; ws += wgt;
            }
          ws = std::max(ws, 1e-6f);
          out[i] = sr / ws; out[HW + i] = sg / ws; out[2 * HW + i] = sb / ws;
        }
      z_out->swap(out);
    }
  }
  bool dct_skip = false;
  if (x_out && prm.dct && prm.dct_fallback && noise_scale && noise_scale->size() == size_t(H) * W) {
    // Fallback: nothing to clean where no frame was lost (LK-R's fb_skip): x = z, no DCT pass.
    float mx = 0.0f;
    for (float v : *noise_scale) mx = std::max(mx, v);
    if (mx <= 1.0f) { dct_skip = true; *x_out = *z_out; }
  }
  if (x_out && prm.dct && !dct_skip) {   // LK-R's DCT x-step on the final z (H, W % 4 == 0)
    if ((H & 3) || (W & 3)) return fail("dct needs H, W % 4 == 0");
    std::memcpy(z.map, z_out->data(), nHR * 4);   // the final image (hybrid combine is host-side)
    const int ny = (H - 8) / 4 + 1, nx = (W - 8) / 4 + 1;
    Buffer &xb = buf(nHR * 4, true), &dns = buf(size_t(H) * W * 4), &diw = buf(size_t(H + W + 8) * 4),
           &dnv = buf(size_t(ny) * nx * 4);
    static const float win[8] = {0.03806023f, 0.30865828f, 0.69134172f, 0.96193977f,
                                 0.96193977f, 0.69134172f, 0.30865828f, 0.03806023f};
    float* iw = diw.as<float>();
    auto axis = [&](int n, int blocks, float* o) {
      std::vector<float> sum(size_t(n), 0.0f);
      for (int b = 0; b < blocks; ++b)
        for (int i = 0; i < 8; ++i) sum[size_t(b * 4 + i)] += win[i];
      for (int i = 0; i < n; ++i) o[i] = 1.0f / sum[size_t(i)];
    };
    axis(H, ny, iw);
    axis(W, nx, iw + H);
    const bool hns = noise_scale && noise_scale->size() == size_t(H) * W;
    if (hns && prm.dct_fallback) {   // only the noise left by lost frames: sqrt(max(ns^2 - 1, 0))
      float* d = dns.as<float>();
      for (size_t i = 0; i < size_t(H) * W; ++i) d[i] = std::sqrt(std::max((*noise_scale)[i] * (*noise_scale)[i] - 1.0f, 0.0f));
    } else if (hns) std::memcpy(dns.map, noise_scale->data(), size_t(H) * W * 4);
    const float denom = prm.noise_a * 0.18f + prm.noise_b;
    DctPush d{H, W, ny, nx, 0, 0, H / 4, hns ? 1 : 0, prm.dct_sigma * prm.dct_sigma,
              denom > 0 ? prm.noise_a / denom : 0.0f, denom > 0 ? prm.noise_b / denom : 1.0f,
              prm.dct_luma * prm.dct_luma, prm.dct_chroma * prm.dct_chroma, prm.wb[0], prm.wb[1], prm.wb[2],
              prm.dct_dark_level, prm.dct_dark_cap, prm.dct_dark_luma, float(s), 0.0f, denom / float(K), 0.0f};
    gpu_->resetSets();
    VkDescriptorSet sy = gpu_->set(kdy_, {&z, &xb, &dns, &diw, &dnv}), su = gpu_->set(kdu_, {&z, &xb, &dns, &diw, &dnv}),
                    sv = gpu_->set(kdv_, {&z, &xb, &dns, &diw, &dnv}), sc = gpu_->set(kdyuv_, {&xb});
    gpu_->begin();
    gpu_->fill(xb, 0);
    const std::pair<const Pipeline*, VkDescriptorSet> ch[3] = {{&kdy_, sy}, {&kdu_, su}, {&kdv_, sv}};
    for (const auto& c : ch)
      for (d.py = 0; d.py < 2; ++d.py)
        for (d.px = 0; d.px < 2; ++d.px)
          gpu_->dispatch(*c.first, c.second, &d, uint32_t((nx + 1) / 2 + 3) / 4, uint32_t((ny + 1) / 2 + 3) / 4);
    gpu_->dispatch(kdyuv_, sc, &d, uint32_t(W / 4 + 15) / 16, uint32_t(H + 15) / 16);
    if (!gpu_->submit()) return fail("dct submit");
    x_out->assign(xb.as<float>(), xb.as<float>() + nHR);
  }
  std::memcpy(p.data(), pb.map, p.size() * 4);
  std::memcpy(dloc.data(), db.map, dloc.size() * 4);
  if (prm.profile) S.profile = gpu_->profileReport();
  S.ms_total = now_ms() - t0;
  if (st) *st = S;
  return true;
}

bool LiteSolver::finish(const float* z, std::vector<float>* x, int H, int W, const FinishArgs& fa,
                        const std::vector<float>* noise_scale, std::string* err) {
  auto fail = [&](const char* m) { if (err) *err = m; gpu_->resetSets(); return false; };
  if (!ok_ || !fin_.pipe) return fail("fin pipeline");
  const size_t HW = size_t(H) * W;
  size_t slot = 0;
  auto buf = [&](size_t bytes, bool host_read = false) -> Buffer& {
    if (fcache_.size() <= slot) fcache_.emplace_back();
    Entry& en = fcache_[slot++];
    if (!en.buf.map || en.bytes != bytes || en.host_read != host_read) {
      gpu_->free(en.buf);
      en.buf = gpu_->alloc(bytes, host_read);
      en.bytes = bytes; en.host_read = host_read;
    }
    return en.buf;
  };
  const bool hns = noise_scale && noise_scale->size() == HW;
  Buffer &bx = buf(3 * HW * 4, true), &bz = buf(3 * HW * 4), &bns = buf(hns ? HW * 4 : 16), &bp = buf(4 * HW * 4),
         &bt = buf(HW * 4), &bm = buf(2 * HW * 4), &bk = buf(3 * HW * 4);
  for (size_t i = 0; i < slot; ++i) if (!fcache_[i].buf.map) return fail("fin buffers");
  std::memcpy(bx.map, x->data(), 3 * HW * 4);
  std::memcpy(bz.map, z, 3 * HW * 4);
  if (hns) std::memcpy(bns.map, noise_scale->data(), HW * 4);
  struct FinPush { int32_t H, W, mode, pl, sm, has_ns, notch, desat; float wbr, wbg, wbb, clip, grain, eqs, p0, p1; };
  static_assert(sizeof(FinPush) == 64, "fin push");
  FinPush f{H, W, 0, 0, 0, hns ? 1 : 0, fa.notch ? 1 : 0, fa.desat ? 1 : 0, fa.wb[0], fa.wb[1], fa.wb[2],
            fa.clip, fa.grain, fa.eq_strength, 0.0f, 0.0f};
  gpu_->resetSets();
  VkDescriptorSet st = gpu_->set(fin_, {&bx, &bz, &bns, &bp, &bt, &bm, &bk});
  const uint32_t gx = div_up(uint32_t(W), 16), gy = div_up(uint32_t(H), 16);
  auto run = [&](int mode, int pl = 0, int sm = 0) { FinPush p = f; p.mode = mode; p.pl = pl; p.sm = sm; gpu_->dispatch(fin_, st, &p, gx, gy); };
  static const bool notch2 = std::getenv("LKRL_NOTCH2") != nullptr;   // unfused row / column passes
  auto notch = [&](int pl) { for (int sm = 0; sm < 3; ++sm) { if (notch2) { run(10, pl, sm); run(11, pl, sm); } else { run(12, pl, sm); run(13, pl, sm); } } };
  gpu_->begin();
  gpu_->fill(bm, 0);
  if (fa.notch) {
    run(0); run(1); run(2);
    notch(1); notch(2); notch(3);
    run(3);
    if (hns) { notch(1); run(4); }
  }
  if (fa.grain > 0.0f || fa.notch) run(5);
  if (fa.grain > 0.0f && fa.notch) notch(0);
  run(6);
  if (!gpu_->submit()) return fail("fin submit");
  x->assign(bx.as<float>(), bx.as<float>() + 3 * HW);
  return true;
}

}  // namespace lkrl
