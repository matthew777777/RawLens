// SPDX-License-Identifier: Apache-2.0
#include "lkrl_solver.h"
#include "lkrl_prior.h"

#include <algorithm>
#include <fstream>
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
static const uint32_t local_search[] = {
#include "local_search.inc"
};
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
static const uint32_t nes[] = {
#include "nes.inc"
};
static const uint32_t ne_apply[] = {
#include "ne_apply.inc"
};
static const uint32_t ne_zupd[] = {
#include "ne_zupd.inc"
};

static const uint32_t adj_s1[] = {   // adj.comp built per scale (ADJ_SCALE): fewer registers at 1x
#include "adj_s1.inc"
};
static const uint32_t adj_s2[] = {
#include "adj_s2.inc"
};
static const uint32_t zupd[] = {
#include "zupd.inc"
};
static const uint32_t edgeshift[] = {
#include "edgeshift.inc"
};
static const uint32_t cdupd[] = {
#include "cdupd.inc"
};
static const uint32_t cdrow[] = {
#include "cdrow.inc"
};
static const uint32_t le_tensor[] = {
#include "le_tensor.inc"
};
static const uint32_t le_blur[] = {
#include "le_blur.inc"
};
static const uint32_t le_apply[] = {
#include "le_apply.inc"
};
static const uint32_t dln_apply[] = {
#include "dln_apply.inc"
};
static const uint32_t axn_apply[] = {
#include "axn_apply.inc"
};
static const uint32_t gcf_apply[] = {
#include "gcf_apply.inc"
};
static const uint32_t canc_apply[] = {
#include "canc_apply.inc"
};
static const uint32_t rcd_start[] = {
#include "rcd_start.inc"
};
static const uint32_t prior_conv[] = {
#include "prior_conv.inc"
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
static const uint32_t kdyo[] = {   // previous one-block-per-invocation DCT (A/B)
#include "lkr_dct_xo_y.inc"
};
static const uint32_t kduo[] = {
#include "lkr_dct_xo_u.inc"
};
static const uint32_t kdvo[] = {
#include "lkr_dct_xo_v.inc"
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
static const uint32_t adj_cubic[] = {
#include "adj_cubic.inc"
};
static const uint32_t jac_cubic[] = {
#include "jac_cubic.inc"
};
static const uint32_t jacblk_cubic[] = {
#include "jacblk_cubic.inc"
};
static const uint32_t lkr_resid_cubic[] = {
#include "lkr_resid_cubic.inc"
};
static const uint32_t upfine[] = {
#include "upfine.inc"
};
static const uint32_t rescue_e[] = {
#include "rescue_e.inc"
};
static const uint32_t rescue_apply[] = {
#include "rescue_apply.inc"
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
  float grain_rel2, grain_rho;   // dct_x.glsl luma grain mode (> 0): kept relative grain^2, min kept share
  float chroma_norm;             // 1: colour thresholds in the colour channel's own noise units
  float grain_tilt;              // grain mode: coarse-vs-fine threshold tilt (log units)
  float grain_sub;               // grain mode: > 0 = detail-keeping subtraction gain, beta (dct_x.glsl)
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
// HDR bracket start image: the reference mosaic with its clipped samples (>= clip) filled from the
// other frames at the same scene point (inverse tile affine, same-parity Bayer sub-grid, bilinear),
// each in reference units already, weighted by its inverse noise variance a y / e + b / e^2.
// Samples no frame sees unclipped stay clipped. Only the start (demosaic) uses it; the data term
// keeps the reference's own samples.
static std::vector<float> HdrFillReference(const float* frames, int K, int h, int w, const std::vector<float>& p,
                                           const std::vector<float>& e, float clip, float na, float nb,
                                           std::vector<uint8_t>* unfilled) {
  std::vector<float> out(frames, frames + size_t(h) * w);
  // Lower bound: the reference is clipped here, so the scene is at least the reference's clip level
  // (clip x the shortest exposure, reference units). A darker sample from another frame is not this
  // scene: lamp flicker caught off by a short frame (IMG_20261120_151412_514: an LED tube at ~1 % of
  // its normal-frame level at 1/400 s) or motion. Such samples do not fill (3 sigma below 0.9 x that).
  float emin = 1.0f;
  for (float v : e) emin = std::min(emin, std::max(v, 1e-3f));
  const double ref_clip = double(clip) * emin;
  for (int i = 0; i < h; ++i)
    for (int j = 0; j < w; ++j) {
      const size_t o = size_t(i) * w + j;
      if (out[o] < clip) continue;
      const bool self_clip = out[o] >= 1.015f * clip;   // clipped itself (not only the colour ring)
      double sw = 0.0, sv = 0.0;
      for (int k = 1; k < K; ++k) {
        const float* A = p.data() + size_t(k) * 6;
        const double det = double(A[0]) * A[4] - double(A[1]) * A[3];
        if (std::fabs(det) < 1e-9) continue;
        const double rx = j + 0.5 - A[2], ry = i + 0.5 - A[5];   // reference point minus translation
        const double ux = (A[4] * rx - A[1] * ry) / det - 0.5, uy = (-A[3] * rx + A[0] * ry) / det - 0.5;
        const double fx = (ux - (j & 1)) * 0.5, fy = (uy - (i & 1)) * 0.5;   // same-parity sub-grid
        const int x0 = int(std::floor(fx)), y0 = int(std::floor(fy));
        const int sw2 = (w - (j & 1) + 1) / 2, sh2 = (h - (i & 1) + 1) / 2;
        if (x0 < 0 || y0 < 0 || x0 + 1 >= sw2 || y0 + 1 >= sh2) continue;
        const double ax = fx - x0, ay = fy - y0;
        const float* fk = frames + size_t(k) * h * w;
        double v = 0.0;
        bool ok = true;
        for (int dy = 0; dy < 2 && ok; ++dy)
          for (int dx = 0; dx < 2; ++dx) {
            const float t = fk[size_t(2 * (y0 + dy) + (i & 1)) * w + 2 * (x0 + dx) + (j & 1)];
            if (!(t < clip)) { ok = false; break; }
            v += t * (dx ? ax : 1.0 - ax) * (dy ? ay : 1.0 - ay);
          }
        if (!ok) continue;
        const double ek = std::max(double(e[size_t(k)]), 1e-3), yy = std::max(v, 0.0);
        if (self_clip && v < 0.9 * ref_clip - 3.0 * std::sqrt(std::max(na * yy / ek + nb / (ek * ek), 0.0))) continue;
        const double wk = (na * yy + nb) / std::max(na * yy / ek + nb / (ek * ek), 1e-20);
        sw += wk; sv += wk * v;
      }
      if (sw > 0.0) out[o] = float(sv / sw);
      else if (self_clip) {
        // No frame saw it unclipped: start from the lower bound (the reference's clip level), as a normal
        // burst would. The clipped mark (~4x that) made the RCD start ring next to it (R -0.8 beside a
        // lamp tube, red / blue edges). The solver marks these white after the solve (unfilled mask).
        out[o] = float(ref_clip);
        if (unfilled) (*unfilled)[o] = 1;
      }
    }
  return out;
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
  LKRL_PIPE(resid, 6); LKRL_PIPE(werr, 3); LKRL_PIPE(wavg, 2); LKRL_PIPE(wminh, 3); LKRL_PIPE(wminv, 5);
  LKRL_PIPE(adj, 7); LKRL_PIPE(adj_s1, 7); LKRL_PIPE(adj_s2, 7); LKRL_PIPE(edgeshift, 3); LKRL_PIPE(cdupd, 5); LKRL_PIPE(cdrow, 3); LKRL_PIPE(le_tensor, 2); LKRL_PIPE(le_blur, 2); LKRL_PIPE(le_apply, 3); LKRL_PIPE(dln_apply, 3); LKRL_PIPE(axn_apply, 3); LKRL_PIPE(gcf_apply, 3); LKRL_PIPE(canc_apply, 5); LKRL_PIPE(rcd_start, 4); LKRL_PIPE(prior_conv, 4); LKRL_PIPE(zupd, 4); LKRL_PIPE(jac, 6); LKRL_PIPE(jacblk, 6); LKRL_PIPE(solveblk, 3); LKRL_PIPE(gstats, 3); LKRL_PIPE(wguide, 5); LKRL_PIPE(wvalid, 5);
#define LKRL_KPIPE(name, nb) name##_ = gpu_->pipeline(spv::name, sizeof(spv::name), nb, sizeof(KPush))
  LKRL_KPIPE(kresid, 6); LKRL_KPIPE(ktdet, 4); LKRL_KPIPE(kth, 5); LKRL_KPIPE(kt, 6); LKRL_KPIPE(ktv, 8);
  LKRL_KPIPE(ktd, 8); LKRL_KPIPE(kguide, 6); LKRL_KPIPE(kminh, 3); LKRL_KPIPE(kmin, 5);
  kdy_ = gpu_->pipeline(spv::kdy, sizeof(spv::kdy), 5, sizeof(DctPush));
  kdu_ = gpu_->pipeline(spv::kdu, sizeof(spv::kdu), 5, sizeof(DctPush));
  kdv_ = gpu_->pipeline(spv::kdv, sizeof(spv::kdv), 5, sizeof(DctPush));
  kdyo_ = gpu_->pipeline(spv::kdyo, sizeof(spv::kdyo), 5, sizeof(DctPush));
  kduo_ = gpu_->pipeline(spv::kduo, sizeof(spv::kduo), 5, sizeof(DctPush));
  kdvo_ = gpu_->pipeline(spv::kdvo, sizeof(spv::kdvo), 5, sizeof(DctPush));
  kdyuv_ = gpu_->pipeline(spv::kdyuv, sizeof(spv::kdyuv), 1, sizeof(DctPush));
  hyb_ = gpu_->pipeline(spv::hyb, sizeof(spv::hyb), 4, 48);
  adjwg_ = gpu_->pipeline(spv::adj_wg, sizeof(spv::adj_wg), 7, sizeof(Push));
  fin_ = gpu_->pipeline(spv::fin, sizeof(spv::fin), 7, 80);
  ok_ = true;
  for (Pipeline* q : {&resid_, &werr_, &wavg_, &wminh_, &wminv_, &adj_, &adj_s1_, &adj_s2_, &zupd_, &jac_, &jacblk_, &solveblk_, &gstats_, &wguide_, &wvalid_, &kresid_, &ktdet_, &kth_, &kt_, &ktv_, &ktd_, &kguide_, &kminh_, &kmin_, &kdy_, &kdu_, &kdv_, &kdyuv_, &kdyo_, &kduo_, &kdvo_, &hyb_, &adjwg_, &fin_, &edgeshift_, &cdupd_, &cdrow_, &le_tensor_, &le_blur_, &le_apply_, &dln_apply_, &axn_apply_, &gcf_apply_, &canc_apply_, &rcd_start_, &prior_conv_})
    ok_ = ok_ && q->pipe != VK_NULL_HANDLE;
}

std::string LiteSolver::profileNames() const {
  std::string out;
  char b[64];
  const std::pair<const Pipeline*, const char*> names[] = {
      {&resid_, "resid"}, {&werr_, "werr"}, {&wavg_, "wavg"}, {&wminh_, "wminh"}, {&wminv_, "wminv"},
      {&adj_, "adj"}, {&adj_s1_, "adj_s1"}, {&adj_s2_, "adj_s2"}, {&nes_, "nes"}, {&ne_apply_, "ne_apply"}, {&ne_zupd_, "ne_zupd"}, {&zupd_, "zupd"}, {&jac_, "jac"}, {&jacblk_, "jacblk"}, {&solveblk_, "solveblk"},
      {&gstats_, "gstats"}, {&wguide_, "wguide"}, {&wvalid_, "wvalid"}, {&kresid_, "lkr_resid"},
      {&ktdet_, "lkr_weight_tdet"}, {&kth_, "lkr_weight_th"}, {&kt_, "lkr_weight_t"}, {&ktv_, "lkr_weight_tv"},
      {&ktd_, "lkr_weight_td"}, {&kguide_, "lkr_weight_guide"}, {&kminh_, "lkr_weight_minh"}, {&kmin_, "lkr_weight_min"},
      {&kdy_, "dct_y"}, {&kdu_, "dct_u"}, {&kdv_, "dct_v"}, {&kdyo_, "dct_y_old"}, {&kduo_, "dct_u_old"}, {&kdvo_, "dct_v_old"}, {&kdyuv_, "dct_yuv"},
      {&hyb_, "hyb"}, {&adjwg_, "adj_wg"}, {&fin_, "fin"}, {&edgeshift_, "edgeshift"}, {&cdupd_, "cdupd"}, {&cdrow_, "cdrow"}, {&le_tensor_, "le_tensor"}, {&le_blur_, "le_blur"}, {&le_apply_, "le_apply"}, {&dln_apply_, "dln_apply"}, {&axn_apply_, "axn_apply"}, {&gcf_apply_, "gcf_apply"}, {&canc_apply_, "canc_apply"}, {&rcd_start_, "rcd_start"}, {&prior_conv_, "prior_conv"}, {&upfine_, "upfine"}, {&local_search_, "local_search"}, {&rescue_e_, "rescue_e"}, {&rescue_apply_, "rescue_apply"}};
  for (const auto& n : names) {
    std::snprintf(b, sizeof(b), "%s%s=%p", out.empty() ? "" : " ", n.second, static_cast<const void*>(n.first));
    out += b;
  }
  return out;
}

LiteSolver::~LiteSolver() {
  if (!gpu_ || !gpu_->ok()) return;
  gpu_->destroy(local_search_);
  for (Pipeline* q : {&ne_zupd_, &upfine_, &rescue_e_, &rescue_apply_, &adj_cubic_, &jac_cubic_, &jacblk_cubic_, &kresid_cubic_}) if (q->pipe) gpu_->destroy(*q);
  for (Entry& e : cache_) gpu_->free(e.buf);
  for (Entry& e : dcache_) gpu_->free(e.buf);
  for (Entry& e : fcache_) gpu_->free(e.buf);
  for (Pipeline* q : {&resid_, &werr_, &wavg_, &wminh_, &wminv_, &adj_, &adj_s1_, &adj_s2_, &nes_, &ne_apply_, &zupd_, &jac_, &jacblk_, &solveblk_, &gstats_, &wguide_, &wvalid_, &kresid_, &ktdet_, &kth_, &kt_, &ktv_, &ktd_, &kguide_, &kminh_, &kmin_, &kdy_, &kdu_, &kdv_, &kdyuv_, &kdyo_, &kduo_, &kdvo_, &hyb_, &adjwg_, &fin_, &edgeshift_, &cdupd_, &cdrow_, &le_tensor_, &le_blur_, &le_apply_, &dln_apply_, &axn_apply_, &gcf_apply_, &canc_apply_, &rcd_start_, &prior_conv_}) gpu_->destroy(*q);
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
  // Opt-in stage audit. No extra submission or readback on the normal path.
  const std::string& trace_prefix = prm.debug_prefix;
  auto trace = [&](const std::string& tag, const void* data, size_t bytes) {
    if (trace_prefix.empty()) return;
    const std::string path = trace_prefix + "-" + tag;
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) { std::fprintf(stderr,"cannot write stage trace %s\n",path.c_str()); return; }
    const bool ok = std::fwrite(data,1,bytes,f)==bytes;
    if (std::fclose(f)!=0 || !ok) std::fprintf(stderr,"short stage trace %s\n",path.c_str());
  };
  if (!trace_prefix.empty()) {
    char meta[512];
    const int length=std::snprintf(meta,sizeof(meta),
      "{\"h\":%d,\"w\":%d,\"s\":%d,\"K\":%d,\"wb\":[%.9g,%.9g,%.9g],\"a\":%.9g,\"b\":%.9g}\n",
      h,w,s,K,prm.wb[0],prm.wb[1],prm.wb[2],prm.noise_a,prm.noise_b);
    trace("meta.json",meta,size_t(length));
    trace("raw.f32",frames,nLR*4);
  }
  if (p.size() != size_t(K) * 6) return fail("p size");
  if (dloc.empty()) dloc.assign(size_t(K) * nb * 2, 0.0f);
  if (dloc.size() != size_t(K) * nb * 2) return fail("dloc size");
  // Research replay: one displacement per sensor pixel, measured from its
  // centre in the moving frame to the reference. Reuse the same interpolation
  // in the forward model, confidence shaders and reconstruction adjoint.
  const char* dense_path = prm.dense_field_path.empty() ? std::getenv("LKRL_DENSE_FIELD")
                                                        : prm.dense_field_path.c_str();
  std::vector<float> dense;
  if (dense_path) {
    if ((h & 1) || (w & 1)) return fail("dense field requires even dimensions");
    if (!prm.exposure.empty()) return fail("dense field with exposure bracket");
    if (std::getenv("LKRL_MLOOK")) return fail("dense field replay requires motion look off");
    FILE* f = std::fopen(dense_path, "rb");
    if (!f) return fail("open dense field");
    int32_t shape[3];
    bool valid = std::fread(shape, sizeof(int32_t), 3, f) == 3 &&
                 shape[0] == K && shape[1] == h && shape[2] == w;
    if (valid) {
      // The optional tail stores one visibility value per sensor sample.
      dense.resize(nLR * 3, 1.0f);
      valid = std::fread(dense.data(), sizeof(float), nLR * 2, f) == nLR * 2;
      if (valid && std::fgetc(f) != EOF) {
        valid = std::fseek(f, -1, SEEK_CUR) == 0 &&
                std::fread(dense.data() + nLR * 2, sizeof(float), nLR, f) == nLR;
      }
      valid = valid && std::fgetc(f) == EOF &&
              std::all_of(dense.begin(), dense.end(), [](float x) { return std::isfinite(x); });
    }
    std::fclose(f);
    if (!valid) return fail("invalid dense field");
    std::fill_n(dense.begin(), size_t(h) * w * 2, 0.0f);
    std::fill_n(dense.begin() + nLR * 2, size_t(h) * w, 1.0f);
  }

  const int nbf = ((w + 7) / 8) * ((h + 7) / 8);   // 8 px field for the merge (local_fine)
  const bool fine = prm.local_fine && !dense_path && LB == 16 && K > 1;
  bool fine_pending = fine;
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
  Buffer &y = buf((nLR + 1) / 2 * 4), &pb = buf(size_t(K) * 24, true),
         &db = buf(dense_path ? dense.size() * sizeof(float) : size_t(K) * std::max(nb, fine ? nbf : 0) * 8, true);
  // 1x merge on summed normal equations (nes.comp / ne_apply.comp / ne_zupd.comp). Default since 2026-11-26
  // (96-101 dB vs the adjoint scatter, phone 1x ~-3 s); LKRL_NE=0 = the scatter path.
  static const bool ne_env = !(std::getenv("LKRL_NE") && std::atoi(std::getenv("LKRL_NE")) == 0);
  static const bool ne_exp = std::getenv("LKRL_CUBIC") || std::getenv("LKRL_FREQ") || std::getenv("LKRL_FITK") || std::getenv("LKRL_ADJ_WG");
  const bool ne = ne_env && !ne_exp && s == 1;
  Buffer &fxb = buf(fine ? size_t(K) * nbf * 8 : 16), &df = buf(fine ? size_t(K) * nbf * 8 : 16);
  Buffer &z = buf(nHR * 4, true), &z0 = buf(nHR * 4);
  Buffer &acc = buf(nHR * 4), &cov = buf(nHR * 4, true);
  Buffer &nelk = buf(ne ? nHR * 8 : 16), &ne2 = buf(ne ? nHR * 8 : 16);
  // Edge position correction / colour-difference fit scratch.
  Buffer &dlb = buf(size_t(H) * W * 16), &ztb = buf(nHR * 4);
  Buffer& cdb = buf(s == 2 ? size_t(H) * W * 24 : 16);   // cdrow / cdupd: row sums + kept coverage window sums
  Buffer& cov1 = buf(noise_scale ? nHR * 4 : 16, true);   // plain coverage U^T 1 (noise_scale only)
  Buffer &r = buf(nLR * 4), &vm = buf(nLR * 4), &e = buf(nLR * 4, resid_out != nullptr),
         &wt = buf(nLR * 4, weights_out != nullptr);
  const uint32_t jg = div_up(uint32_t(h * w), 256 * 8);   // jac.comp: 8 samples per lane (JAC_SPT)
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
  Buffer &zavgB = buf(gpu_hyb ? nHR * 4 : 16), &hybD = buf(gpu_hyb ? size_t(4) * H * W * 4 : 16),
         &hybO = buf(gpu_hyb ? nHR * 4 : 16, true);
  Buffer& y2 = buf(std::getenv("LKRL_FREQ") ? (nLR + 1) / 2 * 4 : 16, true);   // freq-robust samples (prototype)   // start image (hybrid modes >= 2)
  for (size_t i = 0; i < slot; ++i)
    if (!cache_[i].buf.map) return fail("buffer allocation");
  struct Reset { Gpu* g; ~Reset() { g->resetSets(); } } reset_sets{gpu_};

  // Start image (2026-11-19): RCD at 1x and 2x by default (user: everything for the 2x zipper on 1x too).
  const int rcd_env = std::getenv("LKRL_RCD") ? std::atoi(std::getenv("LKRL_RCD")) : (prm.direct_rgb ? 0 : 2);
  const int rcd_mode = prm.start_notch > 0 ? (rcd_env > 0 ? 1 : 0) : rcd_env;   // the notch is host-side
  const float mhc_mix = rcd_mode > 0 ? 1.0f : (prm.mhc_mix >= 0.0f ? prm.mhc_mix : (s == 1 ? 0.75f : 1.0f));
  const float jacobi_damp = prm.jacobi_damp >= 0.0f ? prm.jacobi_damp : 0.0f;
  // HDR bracket, physical lower bound (2026-11-20, real bracket IMG_20261120_151412_514): where the
  // reference is clipped the scene is at least its clip level (reference units: clip x the shortest
  // exposure). A frame's sample there that is darker (3 sigma below 0.9 x that) is not this scene: lamp
  // flicker caught off by a 1/400 s frame (LED tube at ~1 % of its normal level) or motion. Frame k's LR
  // point u maps to the reference at A u + t.
  std::vector<float> frames_lb;
  if (int(prm.exposure.size()) == K && std::any_of(prm.exposure.begin(), prm.exposure.end(), [](float v) { return std::fabs(v - 1.0f) > 1e-3f; })) {
    const float cl = HalfRound(prm.clip);
    float emin = 1.0f;
    for (float v : prm.exposure) emin = std::min(emin, std::max(v, 1e-3f));
    const double ref_clip = double(cl) * emin;
    frames_lb.assign(frames, frames + nLR);
    std::vector<uint8_t> rc(size_t(h) * w);   // reference sample clipped itself
    for (size_t o = 0; o < rc.size(); ++o) rc[o] = frames[o] >= 1.015f * cl;
    // Pass 1: per clipped reference sample, how many bracket samples of its colour land there and how many
    // are impossibly dark. Flicker contradicts nearly all of them over the lamp; sub-pixel misalignment at
    // the edge of a clipped sky with branches only a few (real bracket HDR 3 window: dropping on any single
    // contradiction blew out recovered sky detail). Contradicted = > half of those in the 5 x 5 window.
    std::vector<uint16_t> n_all(size_t(h) * w, 0), n_con(size_t(h) * w, 0);
    // The reference sample of the SAME CFA colour nearest to where frame k's sample (i, j) lands: the
    // bound holds per colour (near the sun the reference's blue clips while red is legitimately darker).
    auto refPos = [&](int k, int i, int j, int* ii, int* jj) {
      const float* A = p.data() + size_t(k) * 6;
      const double x = A[0] * (j + 0.5) + A[1] * (i + 0.5) + A[2], yv = A[3] * (j + 0.5) + A[4] * (i + 0.5) + A[5];
      const int pi = i & 1, pj = j & 1;   // same parity = same colour (RGGB; the two greens count as one)
      *ii = 2 * int(std::floor((yv - 0.5 - pi) * 0.5 + 0.5)) + pi;
      *jj = 2 * int(std::floor((x - 0.5 - pj) * 0.5 + 0.5)) + pj;
      return *ii >= 0 && *jj >= 0 && *ii < h && *jj < w;
    };
    for (int k = 1; k < K; ++k) {
      if (std::fabs(prm.exposure[size_t(k)] - 1.0f) <= 1e-3f) continue;
      const double ek = std::max(double(prm.exposure[size_t(k)]), 1e-3);
      const float* fk = frames + size_t(k) * h * w;
      for (int i = 0; i < h; ++i)
        for (int j = 0; j < w; ++j) {
          const float v = fk[size_t(i) * w + j];
          int ii, jj;
          if (v >= cl || !refPos(k, i, j, &ii, &jj) || !rc[size_t(ii) * w + jj]) continue;
          const size_t o = size_t(ii) * w + jj;
          ++n_all[o];
          const double yy = std::max(double(v), 0.0);
          if (v < 0.9 * ref_clip - 3.0 * std::sqrt(std::max(prm.noise_a * yy / ek + prm.noise_b / (ek * ek), 0.0)))
            ++n_con[o];
        }
    }
    std::vector<uint8_t> con(size_t(h) * w, 0);
    for (int i = 0; i < h; ++i)
      for (int j = 0; j < w; ++j) {
        if (!n_con[size_t(i) * w + j]) continue;
        int a = 0, c = 0;
        for (int di = -2; di <= 2; ++di)
          for (int dj = -2; dj <= 2; ++dj) {
            const int ii = i + di, jj = j + dj;
            if (ii < 0 || jj < 0 || ii >= h || jj >= w) continue;
            a += n_all[size_t(ii) * w + jj]; c += n_con[size_t(ii) * w + jj];
          }
        con[size_t(i) * w + j] = 2 * c > a;
      }
    // Pass 2: there and 2 px around, the bracket frames (exposure != 1) are dropped: the area merges
    // like a normal burst (the reference's clipped samples become white in the finish), no mixed
    // sources, no colour from the frames that saw another scene.
    std::vector<uint8_t> drop(size_t(h) * w, 0);
    for (int i = 0; i < h; ++i)
      for (int j = 0; j < w; ++j)
        if (con[size_t(i) * w + j])
          for (int di = -2; di <= 2; ++di)
            for (int dj = -2; dj <= 2; ++dj) {
              const int ii = i + di, jj = j + dj;
              if (ii >= 0 && jj >= 0 && ii < h && jj < w) drop[size_t(ii) * w + jj] = 1;
            }
    for (int k = 1; k < K; ++k) {
      if (std::fabs(prm.exposure[size_t(k)] - 1.0f) <= 1e-3f) continue;
      float* fk = frames_lb.data() + size_t(k) * h * w;
      for (int i = 0; i < h; ++i)
        for (int j = 0; j < w; ++j) {
          int ii, jj;
          if (refPos(k, i, j, &ii, &jj) && drop[size_t(ii) * w + jj] && fk[size_t(i) * w + j] < cl)
            fk[size_t(i) * w + j] = 1.01f * cl;   // invalid, not "clipped itself" (no white fill from it)
        }
    }
    frames = frames_lb.data();
  }
  PackHalf(frames, nLR, y.as<uint32_t>());   // fp16 frames, two per uint (shaders: yv())
  if (dense_path) {
    for (int k = 0; k < K; ++k) {
      const float identity[6] = {1, 0, 0, 0, 1, 0};
      std::copy_n(identity, 6, pb.as<float>() + size_t(k) * 6);
    }
    std::memcpy(db.map, dense.data(), dense.size() * sizeof(float));
  } else {
    std::memcpy(pb.map, p.data(), p.size() * 4);
    std::memcpy(db.map, dloc.data(), dloc.size() * 4);
    if (fine) {
      std::memcpy(fxb.map, prm.local_fine, size_t(K) * nbf * 8);
      if (!upfine_.pipe) upfine_ = gpu_->pipeline(spv::upfine, sizeof(spv::upfine), 3, sizeof(Push));
      if (!upfine_.pipe) return fail("upfine pipeline");
    }
  }
  // HDR bracket: the start images demosaic the reference with its clipped samples filled from the
  // other frames (the data term still excludes them). frames stays the reference elsewhere.
  std::vector<float> ref_fused;
  std::vector<uint8_t> hdr_unfilled;   // reference samples clipped in every frame (white after the solve)
  const float* ref_start = frames;
  if (int(prm.exposure.size()) == K && std::any_of(prm.exposure.begin(), prm.exposure.end(), [](float v) { return std::fabs(v - 1.0f) > 1e-3f; })) {
    hdr_unfilled.assign(size_t(h) * w, 0);
    ref_fused = HdrFillReference(frames, K, h, w, p, prm.exposure, HalfRound(prm.clip), prm.noise_a, prm.noise_b, &hdr_unfilled);
    ref_start = ref_fused.data();
    trace("hdr-reference.f32", ref_start, size_t(h) * w * 4);
  }
  Buffer& yref = buf(ref_fused.empty() ? 16 : (size_t(h) * w + 1) / 2 * 4, true);
  if (!ref_fused.empty()) PackHalf(ref_start, size_t(h) * w, yref.as<uint32_t>());
  {
    std::vector<float> rgb(size_t(3) * h * w), up(nHR);
    // Edge-directed start at 2x (RcdRggb, 2026-11-19): with the luma-guided chroma it removes the blue
    // trunk fringe; luma detail equal (+0.5..1 %). LKRL_RCD=0 = MHC, =1 also at 1x.
    // LKRL_RCD: 2 (default) = GPU RCD (rcd_start.comp, the app's vf_rcd), 1 = CPU RcdRggb, 0 = MHC.
    if (rcd_mode == 0 && mhc_mix > 0.0f) MhcCoherentRggb(ref_start, h, w, rgb.data());
    if (rcd_mode == 1) RcdRggb(ref_start, h, w, rgb.data());
    // z0 (weights / first motion steps) = LK-R's own start, pure MHC; only the merge starts from
    // the MHC + bilinear mix. Mixed z0 lowered chrome glints (IMG_20261107) vs LK-R.
    static const int z0_mhc = std::getenv("LKRL_Z0MHC") ? std::atoi(std::getenv("LKRL_Z0MHC")) : 1;
    if (z0_mhc && mhc_mix < 1.0f) {
      std::vector<float> m(rgb.size()), u0(nHR);
      if (mhc_mix > 0.0f) m = rgb; else MhcCoherentRggb(ref_start, h, w, m.data());
      UpsampleBilinear(m.data(), 3, h, w, s, u0.data());
      std::memcpy(z0.map, u0.data(), nHR * 4);
    }
    if (mhc_mix < 1.0f) {
      std::vector<float> bil(rgb.size());
      DemosaicBilinear(ref_start, h, w, bil.data());
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
    if (rcd_mode != 2) {
    UpsampleBilinear(rgb.data(), 3, h, w, s, up.data());
    std::memcpy(z.map, up.data(), nHR * 4);
    if (cavg >= 2) std::memcpy(zstart.map, up.data(), nHR * 4);
    if (!(z0_mhc && mhc_mix < 1.0f)) std::memcpy(z0.map, up.data(), nHR * 4);
    trace("seed.f32",up.data(),nHR*4);
    trace("reference.f32",z0.map,nHR*4);
    }
  }
  Push q{};
  q.h = h; q.w = w; q.s = s; q.K = K; q.H = H; q.W = W; q.LB = LB; q.mode = 0;
  if (dense_path) q.LB = 1;
  q.a = prm.noise_a; q.b = prm.noise_b; q.clip = HalfRound(prm.clip); q.robk = prm.robust_k;
  q.tau = prm.robust_tau; q.rpow = std::max(prm.robust_pow, 1.0f);
  q.scale = float(std::ldexp(1.0, 28) / K);   // |r| <= 1, <= 4 s^2 taps per frame and HR pixel
  // HDR bracket: samples (and residuals) reach the clip level (> 1) in reference units.
  const bool hdr_in = int(prm.exposure.size()) == K &&
                      std::any_of(prm.exposure.begin(), prm.exposure.end(), [](float v) { return std::fabs(v - 1.0f) > 1e-3f; });
  if (hdr_in) q.scale /= std::ceil(std::max(prm.clip, 1.0f));
  q.dscale = q.scale;

  const uint32_t gx = uint32_t(K) * div_up(uint32_t(w), 16), gy = div_up(uint32_t(h), 16);
  VkDescriptorSet sResid0 = gpu_->set(resid_, {&z0, &y, &pb, &db, &r, &vm});
  VkDescriptorSet sErr = gpu_->set(werr_, {&r, &y, &e});
  VkDescriptorSet sAvg = gpu_->set(wavg_, {&e, &r});      // r is dead after werr: holds pre-spread weights
  VkDescriptorSet sAvgRes = gpu_->set(wavg_, {&e, &wrawb});   // residual weights for the guide AND test
  VkDescriptorSet sMinH = gpu_->set(wminh_, {&r, &e, &vm});
  // HDR: per-frame exposure e_k (+ the weight normaliser at index K) for wvalid / wminv.
  const bool hdr = hdr_in;
  Buffer& fx = buf(size_t(K + 2) * 4, true);
  float hdr_fmax = 1.0f;
  {
    float* f = fx.as<float>();
    for (int k = 0; k < K; ++k) {
      f[k] = hdr ? std::max(prm.exposure[size_t(k)], 1e-3f) : 1.0f;
      hdr_fmax = std::max(hdr_fmax, f[k] * f[k]);   // read-noise limit of the inverse-variance ratio
    }
    f[K] = hdr_fmax;
    float emin = 1.0f;
    for (int k = 0; k < K; ++k) emin = std::min(emin, f[k]);
    f[K + 1] = hdr ? prm.clip * emin : 0.0f;   // reference's clip level (prm.clip = the shortest frame's)
  }
  VkDescriptorSet sMinV = gpu_->set(wminv_, {&e, &wt, &vm, &y, &fx});
  // adj.comp built for this scale (half the Mali registers at 1x); LKRL_ADJ_GENERIC = the pc.s build (A/B).
  static const bool adj_generic = std::getenv("LKRL_ADJ_GENERIC") && std::atoi(std::getenv("LKRL_ADJ_GENERIC")) != 0;
  const Pipeline& adjP = adj_generic ? adj_ : (s == 2 ? adj_s2_ : adj_s1_);
  VkDescriptorSet sAdj = gpu_->set(adjP, {&z, &y, &pb, &db, &wt, &acc, &cov});
  // Catmull-Rom forward model (experiment): LKRL_CUBIC bits 1 merge steps (U and U^T), 2 motion steps, 4 weight residual.
  static const int cubic = std::getenv("LKRL_CUBIC") ? std::atoi(std::getenv("LKRL_CUBIC")) : 0;
  auto lazy = [&](Pipeline& q, const uint32_t* code, size_t bytes, int nb_, size_t push) {
    if (!q.pipe) q = gpu_->pipeline(code, bytes, nb_, push);
    return q.pipe != VK_NULL_HANDLE;
  };
  if (((cubic & 1) && !lazy(adj_cubic_, spv::adj_cubic, sizeof(spv::adj_cubic), 7, sizeof(Push))) ||
      ((cubic & 2) && (!lazy(jac_cubic_, spv::jac_cubic, sizeof(spv::jac_cubic), 6, sizeof(Push)) ||
                       !lazy(jacblk_cubic_, spv::jacblk_cubic, sizeof(spv::jacblk_cubic), 6, sizeof(Push)))) ||
      ((cubic & 4) && !lazy(kresid_cubic_, spv::lkr_resid_cubic, sizeof(spv::lkr_resid_cubic), 6, sizeof(KPush))))
    return fail("cubic pipelines");
  const Pipeline& jacP = (cubic & 2) ? jac_cubic_ : jac_;
  const Pipeline& jacblkP = (cubic & 2) ? jacblk_cubic_ : jacblk_;
  const Pipeline& kresidP = (cubic & 4) ? kresid_cubic_ : kresid_;
  VkDescriptorSet sAdjC = (cubic & 1) ? gpu_->set(adj_cubic_, {&z, &y, &pb, &db, &wt, &acc, &cov}) : VK_NULL_HANDLE;
  // LKRL_ADJ_WG=1: workgroup-per-tile adjoint (bit-identical acc; phone A/B 2026-11-14: no gain, atomics
  // are not adj's cost -- off).
  static const bool adj_workgroup = std::getenv("LKRL_ADJ_WG") && std::atoi(std::getenv("LKRL_ADJ_WG")) != 0;
  const bool adj_old = dense_path || !adj_workgroup;
  VkDescriptorSet sAdjW = adj_old ? VK_NULL_HANDLE : gpu_->set(adjwg_, {&z, &y, &pb, &db, &wt, &acc, &cov});
  const uint32_t gxw = div_up(uint32_t(w), 16), gyw = div_up(uint32_t(h), 16);
  VkDescriptorSet sAdjF = gpu_->set(adjP, {&z, &y2, &pb, &db, &vm, &acc, &cov});   // freq-robust average
  VkDescriptorSet sGStats = gpu_->set(gstats_, {&y, &gm, &gv});
  VkDescriptorSet sGuide = gpu_->set(wguide_, {&gm, &gv, &pb, &db, &r});
  const uint32_t gqx = div_up(uint32_t(w / 2), 16), gqy = div_up(uint32_t(h / 2), 16);
  // LK-R weight chain descriptor sets (bindings exactly as lkr_solver.cpp; r, e: Lite scratch).
  VkDescriptorSet kRT = VK_NULL_HANDLE, kTDet = VK_NULL_HANDLE, kTH = VK_NULL_HANDLE, kWT = VK_NULL_HANDLE,
                  kWV = VK_NULL_HANDLE, kWD = VK_NULL_HANDLE, kG = VK_NULL_HANDLE, kMH = VK_NULL_HANDLE,
                  kM = VK_NULL_HANDLE, kV = VK_NULL_HANDLE;
  if (lkw) {
    kRT = gpu_->set(kresidP, {&z0, &y, &pb, &rrt, &db, &vm});
    kTDet = gpu_->set(ktdet_, {&rrt, &y, &tdetb, &db});
    kTH = gpu_->set(kth_, {&rrt, &y, &rwt2, &wvt2, &tdetb});
    kWT = gpu_->set(kt_, {&rrt, &y, &rwt2, &wtr, &wvt2, &kebuf});
    kWV = gpu_->set(ktv_, {&kebuf, &rwt2, &wvt2, &r, &e, &wrawb, &s3, &s4});
    kWD = gpu_->set(ktd_, {&kebuf, &y, &wtr, &r, &e, &wrawb, &s3, &s4});
    kG = gpu_->set(kguide_, {&gm, &gv, &pb, &wrawb, &db, &wtr});
    kMH = gpu_->set(kminh_, {&wrawb, &wtr, &kebuf});
    kM = gpu_->set(kmin_, {&rrt, &kebuf, &s3, &wl, &s5});
    kV = gpu_->set(wvalid_, {&wl, &vm, &wt, &y, &fx});
  }
  KPush kq{};
  kq.h = h; kq.w = w; kq.s = s; kq.K = K; kq.H = H; kq.W = W; kq.kOff = q.LB; kq.mode = 0;
  kq.f0 = 1e-9f; kq.f2 = HalfRound(prm.clip); kq.f3 = prm.robust_tau;
  kq.rob_a = prm.noise_a; kq.rob_b = prm.noise_b; kq.rob_k = prm.robust_k;
  kq.rob_on = (prm.robust && prm.noise_b > 0) ? std::max(1.0f, prm.robust_pow) : 0.0f;
  const bool ktemporal = prm.temporal_k > 0.0f && prm.robust_spread > 0;
  VkDescriptorSet sAdjPlain = noise_scale ? gpu_->set(adjP, {&z, &y, &pb, &db, &vm, &acc, &cov1}) : VK_NULL_HANDLE;
  // Final residual vs z (motion look): written to e, validity to r (both dead by then).
  VkDescriptorSet sResidZ = resid_out ? gpu_->set(resid_, {&z, &y, &pb, &db, &e, &r}) : VK_NULL_HANDLE;
  VkDescriptorSet sUpd = gpu_->set(zupd_, {&z, &acc, &cov, &dvb});
  VkDescriptorSet sJac = gpu_->set(jacP, {&z, &y, &pb, &db, &wt, &part});
  VkDescriptorSet sBlk = gpu_->set(jacblkP, {&z, &y, &pb, &db, &wt, &blk});
  Push qg = q;
  qg.mode = prm.gn_weights == 1 ? 1 : 0;   // 1: motion steps need no robust weights (refreshed lazily)
  VkDescriptorSet sSolveBlk = gpu_->set(solveblk_, {&blk, &db, &dtmp});
  float lam = prm.local_lambda;
  if (prm.auto_local_lambda) {
    const float sl = std::sqrt(std::max(prm.noise_a * 0.18f + prm.noise_b, 0.0f));
    lam = std::min(5.0f, lam * std::max(1.0f, (sl / 0.0025f) * (sl / 0.0025f)));
  }
  gpu_->profile(prm.profile);

  bool need_cov = true, weights_stale = false, chain_done = false, rescued = false;
  bool ne_stale = true;   // the sums follow the weights and the geometry
  bool ne_delta = false;  // the sums hold the rescue merge's weights (s4): add only the change
  bool ne_delta_vm = false;   // the delta's old weights are vm (else s4)
  bool wvalid_skip = false;   // weights(): leave wt to the rescue that follows
  bool cov1_done = false; // cov1 (plain coverage) taken from the rescue's weight-1 sums
  if (ne) {
    if (!nes_.pipe) nes_ = gpu_->pipeline(spv::nes, sizeof(spv::nes), 9, sizeof(Push));
    if (!ne_apply_.pipe) ne_apply_ = gpu_->pipeline(spv::ne_apply, sizeof(spv::ne_apply), 5, sizeof(Push));
    if (!ne_zupd_.pipe) ne_zupd_ = gpu_->pipeline(spv::ne_zupd, sizeof(spv::ne_zupd), 7, sizeof(Push));
    if (!nes_.pipe || !ne_apply_.pipe || !ne_zupd_.pipe) return fail("ne pipelines");
  }
  VkDescriptorSet sNes = ne ? gpu_->set(nes_, {&nelk, &y, &pb, &db, &wt, &acc, &cov, &ne2, &s4}) : VK_NULL_HANDLE;
  VkDescriptorSet sNesVm = ne ? gpu_->set(nes_, {&nelk, &y, &pb, &db, &vm, &acc, &cov, &ne2, &s4}) : VK_NULL_HANDLE;    // weights = validity
  VkDescriptorSet sNesDvm = ne ? gpu_->set(nes_, {&nelk, &y, &pb, &db, &wt, &acc, &cov, &ne2, &vm}) : VK_NULL_HANDLE;   // delta from validity
  VkDescriptorSet sNeApply = ne ? gpu_->set(ne_apply_, {&z, &nelk, &ne2, &acc, &cov}) : VK_NULL_HANDLE;
  VkDescriptorSet sNeZupd = ne ? gpu_->set(ne_zupd_, {&z, &nelk, &ne2, &dvb, &ztb, &acc, &cov}) : VK_NULL_HANDLE;
  // LKRL_NE_FUSE=0: separate ne_apply + zupd for the cached-divisor steps (A/B; the fused pass is bit-identical).
  static const bool ne_fuse = !(std::getenv("LKRL_NE_FUSE") && std::atoi(std::getenv("LKRL_NE_FUSE")) == 0) &&
                              !std::getenv("LKRL_CDFIT") && !std::getenv("LKRL_NO_DVCACHE") && !std::getenv("LKRL_UPDSMOOTH");
  bool ne_fused = false;
  Push ne_am{};
  const int fitK = std::getenv("LKRL_FITK") ? std::min(std::max(std::atoi(std::getenv("LKRL_FITK")), 1), K) : K;
  std::vector<float> zavg;
  auto weights = [&]() {   // robust weights vs the reference-only start image, current geometry
    ne_stale = true;
    ne_delta = false;
    if (lkw && chain_done && prm.lkr_weights_once) {   // keep LK-R's weights, new validity only
      gpu_->dispatch(resid_, sResid0, &q, gx, gy);
      gpu_->dispatch(wvalid_, kV, &q, div_up(uint32_t(nLR), 256));
      need_cov = true;
      weights_stale = false;
      return;
    }
    if (lkw) {   // LK-R's chain, same order and push values as lkr_solver.cpp (guide on)
      chain_done = true;
      if (ktemporal && kq.rob_on > 0.5f) {
        KPush qt = kq; qt.rob_k = prm.temporal_k; qt.f3 = prm.temporal_tau;
        // The temporal warp also writes validity for the final weight pass.
        gpu_->dispatch(kresidP, kRT, &qt, gx, gy, 1);
        KPush qd = qt; qd.f0 = prm.temporal_detail; qd.f1 = prm.temporal_dark_wide ? 1.0f : 0.0f;
        qd.f2 = prm.temporal_dark_level;
        KPush qh = qd; qh.f1 = 0.0f;
        const uint32_t g1 = div_up(uint32_t(w), 16);
        KPush qdet = qd;   // motion gate scale: the misregistration tolerance (LKRL_TDGATE A/B, 0 = off)
        static const float tdgate = std::getenv("LKRL_TDGATE") ? float(std::atof(std::getenv("LKRL_TDGATE"))) : prm.robust_tau;
        // Every scale (user 2026-11-19; at 1x it measured -10 % fine detail on the car-burst plate crop).
        qdet.rob_k = !dense_path && prm.local_block > 0 ? tdgate : 0.0f;
        static const float tdspread = std::getenv("LKRL_TDSPREAD") ? float(std::atof(std::getenv("LKRL_TDSPREAD"))) : 2.0f;
        static const float residual_sigma = std::getenv("LKRL_TDRESID")
            ? float(std::atof(std::getenv("LKRL_TDRESID"))) : 0.0f;
        // Brackets need exposure-specific residual variance before using this test.
        qdet.f1 = hdr ? 0.0f : residual_sigma;
        qdet.f2 = tdspread;   // 2 = field curvature (default), 1 = neighbour disagreement, 0 = offset size
        if (qd.f0 > 0.0f) gpu_->dispatch(ktdet_, kTDet, &qdet, g1, gy, uint32_t(K));
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
      // Guide AND residual (LK-R guide_residual): the guide's local colour means miss wind-moved
      // leaves (IMG_20261112_125318_179: 12.1 / 16 frames merged on them -> blur). w = min(guide,
      // residual^a), residual = Lite's weight_e Cauchy test vs the reference start, into wrawb.
      static const float gres = std::getenv("LKRL_GRES") ? float(std::atof(std::getenv("LKRL_GRES"))) : prm.guide_residual;
      if (gres > 0.0f) {
        gpu_->dispatch(resid_, sResid0, &q, gx, gy);
        gpu_->dispatch(werr_, sErr, &q, gx, gy);
        gpu_->dispatch(wavg_, sAvgRes, &q, gx, gy);
        qg.f1 = gres;
      } else if (!ktemporal || kq.rob_on <= 0.5f) {
        gpu_->dispatch(resid_, sResid0, &q, gx, gy);
      }
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
      if (!wvalid_skip) gpu_->dispatch(wvalid_, kV, &q, div_up(uint32_t(nLR), 256));
      need_cov = true;
      weights_stale = false;
      return;
    }
    gpu_->dispatch(resid_, sResid0, &q, gx, gy);
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
  if (rcd_mode == 2) {   // GPU RCD start: reference frame (fp16, frame 0 of y) -> LR RGB -> x s into z
    Buffer &rcdS = buf(size_t(h) * w * 16), &rcdC = buf(size_t(h) * w * 16);
    VkDescriptorSet rS = gpu_->set(rcd_start_, {ref_fused.empty() ? &y : &yref, &rcdS, &rcdC, &z});
    Push rq = q;
    for (int m = 0; m < 4; ++m) {
      rq.mode = m;
      gpu_->dispatch(rcd_start_, rS, &rq, div_up(uint32_t(w), m == 0 ? 8u : 16u), div_up(uint32_t(h), 8));
    }
    rq.mode = 4; gpu_->dispatch(rcd_start_, rS, &rq, div_up(uint32_t(W), 8), div_up(uint32_t(H), 8));
    gpu_->copy(z, z0, nHR * 4);
    if (cavg >= 2) gpu_->copy(z, zstart, nHR * 4);
  }
  gpu_->fill(acc, 0);
  if (guide) gpu_->dispatch(gstats_, sGStats, &q, gqx, gqy, uint32_t(K));
  weights_stale = true;
  // 2026-11-20: 1x 3 -> 1 motion step (GJJJ; the tile LK prefetch already aligns): vs GGJJJ 64-70 dB on 4
  // bursts (day, night, two shakier), fine detail -0.9..+0.5 %, no visible change. GGJJJ alone: phone -0.8 s.
  // 2026-11-14 (hybrid merge): 1x 5 -> 3 merge steps (real detail 0.99-1.01), 2x 10 -> 8 (finest band 0.94).
  // Finish geometry against the reference-only start before the average or
  // reconstruction can blur it. A late G fitted the intermediate merge instead.
  std::string sched = !prm.schedule.empty() ? prm.schedule : (s == 1 ? "GJJJ" : "GGGJJJJJJJJ");
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
  // Desktop experiment. Search once after the leading motion updates,
  // before the first weighted fusion pass. The normal path allocates nothing.
  const char* search_opt = std::getenv("LKRL_LOCAL_SEARCH");
  const int search_mode = search_opt ? std::atoi(search_opt) : 0;
  bool local_search_pending = search_mode > 0 && !dense_path &&
                              !hdr && K > 1 && !prm.direct_rgb;
  if (local_search_pending && !local_search_.pipe) {
    gpu_->destroy(local_search_);
    local_search_ = gpu_->pipeline(spv::local_search, sizeof(spv::local_search), 5, sizeof(Push));
  }
  local_search_pending = local_search_pending && local_search_.pipe;
  int trace_j = 0;
  trace("affine-initial.f32",pb.map,size_t(K)*24);
  const size_t field_bytes = dense_path ? nLR * 2 * sizeof(float) : size_t(K) * nb * 8;
  trace("local-initial.f32",db.map,field_bytes);
  for (size_t si = 0; si < sched.size(); ++si) {
    const char c = sched[si];
    if (c == 'J' || c == 'A') {
      // 'A': restart from the weighted aligned average z = U^T W y / U^T W 1 (shift-and-add: one
      // undamped Jacobi step from z = 0) -- every frame averaged instead of the reference start.
      const bool avg = c == 'A';
      if (local_search_pending) {
        const VkDescriptorSet search = gpu_->set(local_search_, {&z0, &y, &pb, &db, &dtmp});
        Push search_args = q;
        search_args.mode = search_mode;
        gpu_->dispatch(local_search_, search, &search_args, div_up(uint32_t(K * nb), 64));
        gpu_->copy(dtmp, db, size_t(K) * nb * 8);
        weights_stale = true;
        local_search_pending = false;
      }
      if (fine_pending) {   // motion steps done: the merge warps with the 8 px field
        Push u = q;
        gpu_->dispatch(upfine_, gpu_->set(upfine_, {&db, &fxb, &df}), &u, div_up(uint32_t(K * nbf), 64));
        gpu_->copy(df, db, size_t(K) * nbf * 8);
        q.LB = 8; kq.kOff = 8;
        weights_stale = true;
        fine_pending = false;
      }
      // Rejection rescue (on by default; LKRL_RESCUE=0 turns it off): the weights compare every frame with the reference alone, whose aliased fine
      // pattern (perforated panels, railings: 133623) no other frame matches, so all are rejected and its false colour
      // stays. A 1x merge WITHOUT rejection that explains a frame AND the reference to within noise over 5x5 samples,
      // on texture clearly above the noise, is a static scene: the frame's weight is restored there (rescue_e/_apply).
      static const int rescue = std::getenv("LKRL_RESCUE") ? std::atoi(std::getenv("LKRL_RESCUE")) : 1;
      // Auto-exposure drift (car burst 184641: 2.4 %) is not a bracket: the noise model still holds.
      const bool bracket = hdr && *std::max_element(prm.exposure.begin(), prm.exposure.end()) >
                                  1.1f * *std::min_element(prm.exposure.begin(), prm.exposure.end());
      static const bool rescue_ne = !(std::getenv("LKRL_RESCUE_NE") && std::atoi(std::getenv("LKRL_RESCUE_NE")) == 0);
      static const bool ne_inc = !(std::getenv("LKRL_NE_DELTA") && std::atoi(std::getenv("LKRL_NE_DELTA")) == 0);
      const bool rescue_now = rescue && !rescued && lkw && !dense_path && !bracket && K >= 6;
      const bool rne = ne && rescue_ne;
      // Without HDR the weight-1 merge's weights are the validity itself (wvalid of 1 = vm): the NE sums read vm,
      // LK-R's weights stay in wl (rescue_apply updates them in place), the delta scatter's old weights are vm.
      const bool rvm = rne && ne_inc && !hdr;
      // The chain's own validity pass would be overwritten by the rescue's before anything reads wt: skip it.
      wvalid_skip = rescue_now && rvm;
      if (weights_stale) weights();
      wvalid_skip = false;
      if (rescue_now) {
        rescued = true;
        if (!rescue_e_.pipe) rescue_e_ = gpu_->pipeline(spv::rescue_e, sizeof(spv::rescue_e), 4, sizeof(Push));
        if (!rescue_apply_.pipe) rescue_apply_ = gpu_->pipeline(spv::rescue_apply, sizeof(spv::rescue_apply), 5, sizeof(Push));
        if (!rescue_e_.pipe || !rescue_apply_.pipe) return fail("rescue pipelines");
        if (!rvm) {   // s4 keeps LK-R's weights, s3 the support (both free between weight refreshes).
          gpu_->copy(wl, s4, nLR * 4);
          gpu_->fill(wl, 0x3f800000u);
          gpu_->dispatch(wvalid_, kV, &q, div_up(uint32_t(nLR), 256));
        }
        Push p1 = q; p1.s = 1; p1.H = h; p1.W = w;
        const uint32_t n3 = div_up(uint32_t(3 * size_t(h) * w), 256);
        Pipeline& a1 = adj_s1_.pipe ? adj_s1_ : adj_;
        const VkDescriptorSet sA1 = gpu_->set(a1, {&z, &y, &pb, &db, &wt, &acc, &cov});
        gpu_->fill(z, 0); gpu_->fill(cov, 0); gpu_->fill(acc, 0);
        static const int rj = std::getenv("LKRL_RESCUE_J") ? std::atoi(std::getenv("LKRL_RESCUE_J")) : 1;
        // With NE (1x): one scatter of the weight-1 sums, then the average and the steps from them (no adjoint passes).
        if (rne) {
          gpu_->fill(nelk, 0);
          gpu_->fill(ne2, 0);
          Push sm = q; sm.mode = 1; sm.rpow = hdr_in ? -2.0f : -1.0f; sm.tau = 0.0f;
          gpu_->dispatch(nes_, rvm ? sNesVm : sNes, &sm, gx, gy);
        }
        for (int t = 0; t <= rj; ++t) {   // average, then rj merge steps (1x schedule's update)
          Push am = p1; am.mode = t <= 1 ? 1 : 0;
          if (t == 0) am.rpow = hdr_in ? -2.0f : -1.0f;
          if (rne) {
            Push an = q; an.mode = t == 0 ? 2 : (t == 1 ? 1 : 0);
            if (!ne_fuse) gpu_->dispatch(ne_apply_, sNeApply, &an, div_up(uint32_t(nHR), 256));
          } else {
          if (t == 1) gpu_->fill(cov, 0);
          gpu_->dispatch(a1, sA1, &am, uint32_t(K) * div_up(uint32_t(w), 16), gy);
          }
          Push um = p1; um.tau = 0.0f; um.LB = 0; um.a = t == 0 ? 0.0f : 8.0f; um.b = 1.45f * float(K) * 0.5f;
          um.clip = 0.0f; um.mode = t <= 1 ? 1 : 0; um.robk = 0.0f; um.rpow = t == 0 ? (hdr_in ? -2.0f : -1.0f) : 1.6f;
          if (rne && ne_fuse) {   // ne_apply + zupd fused (um.LB = 0, no filter)
            Push uf = um; uf.K = q.K; uf.scale = q.scale; uf.dscale = q.dscale; uf.H = H; uf.W = W;
            gpu_->dispatch(ne_zupd_, sNeZupd, &uf, div_up(uint32_t(nHR), 256));
            gpu_->copy(ztb, z, nHR * 4);
          } else {
            gpu_->dispatch(zupd_, sUpd, &um, n3);
          }
          if (t == 0) gpu_->fill(acc, 0);
        }
        gpu_->dispatch(resid_, gpu_->set(resid_, {&z, &y, &pb, &db, &r, &vm}), &p1, gx, gy);
        static const float lim = std::getenv("LKRL_RESCUE_LIM") ? float(std::atof(std::getenv("LKRL_RESCUE_LIM"))) : 4.0f;
        static const float snr = std::getenv("LKRL_RESCUE_SNR") ? float(std::atof(std::getenv("LKRL_RESCUE_SNR"))) : 0.5f;
        Push pe = q; pe.robk = lim; pe.tau = snr;
        const uint32_t tx = div_up(uint32_t(w), 16), ty = div_up(uint32_t(h), 16);
        gpu_->dispatch(rescue_e_, gpu_->set(rescue_e_, {&r, &vm, &y, &s3}), &pe, tx, ty, uint32_t(K));
        gpu_->dispatch(rescue_apply_, gpu_->set(rescue_apply_, {&s3, rvm ? &wl : &s4, &pb, &db, &wl}), &q, tx, ty, uint32_t(K));   // in place: own sample only
        if (std::getenv("LKRL_RESCUE_LOG")) {
          if (!submit()) return fail("rescue log");
          const float *W0 = s4.as<float>(), *W1 = wl.as<float>();
          size_t nres = 0;
          for (size_t i = size_t(h) * w; i < nLR; ++i) nres += W1[i] > W0[i] + 0.1f;
          std::fprintf(stderr, "rescue: %.1f %% of samples\n", 100.0 * nres / double(nLR));
          gpu_->begin();
        }
        gpu_->fill(acc, 0); gpu_->copy(z0, z, nHR * 4);
        // validity depends on geometry and clipping only: at 1x the rescue's residual pass already wrote it
        if (s != 1) gpu_->dispatch(resid_, sResid0, &q, gx, gy);
        if (rne && ne_inc && !rvm) gpu_->copy(wt, s4, nLR * 4);   // the rescue merge's weights, still in the sums
        // Its coverage (weights = validity: exposure weights are 1 without HDR) is the plain coverage of the final
        // geometry, which the noise scale needs: same sums and rounding as the plain adjoint pass it replaces.
        if (rne && noise_scale && !hdr && rj >= 1) { gpu_->copy(cov, cov1, nHR * 4); cov1_done = true; }
        gpu_->dispatch(wvalid_, kV, &q, div_up(uint32_t(nLR), 256));
        need_cov = true;
        ne_stale = true;
        ne_delta = rne && ne_inc;
        ne_delta_vm = rvm;
      }
      if (avg) { gpu_->fill(z, 0); need_cov = true; }
      if (need_cov) gpu_->fill(cov, 0);
      Push m = q; m.mode = need_cov ? 1 : 0;
      if (avg) m.rpow = hdr_in ? -2.0f : -1.0f;   // average: clipped samples count (all-clipped pixels stay blown, not 0)
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
        gpu_->dispatch(adjP, sAdjF, &m, gx, gy);
      } else if (ne) {
        if (ne_stale) {
          if (!ne_delta) {
            gpu_->fill(nelk, 0);
            gpu_->fill(ne2, 0);
          }
          Push sm = m; sm.mode = avg ? 1 : 0; sm.tau = ne_delta ? 1.0f : 0.0f;
          gpu_->dispatch(nes_, ne_delta && ne_delta_vm ? sNesDvm : sNes, &sm, gx, gy);
          ne_stale = false;
          ne_delta = false;
        }
        Push am = q; am.mode = avg ? 2 : (need_cov ? 1 : 0);
        ne_am = am;
        ne_fused = ne_fuse && trace_prefix.empty();   // gradient + update in one pass below (ne_zupd)
        if (!ne_fused) gpu_->dispatch(ne_apply_, sNeApply, &am, div_up(uint32_t(nHR), 256));
      } else if (!adj_old) {
        gpu_->dispatch(adjwg_, sAdjW, &m, gxw, gyw);
      } else {
        Push mk = m;
        if (!avg && fitK < K) mk.K = fitK;   // experiment: the fit on the first fitK frames only
        const bool cub = (cubic & 1) && !avg;
        gpu_->dispatch(cub ? adj_cubic_ : adjP, cub ? sAdjC : sAdj, &mk, uint32_t(mk.K) * div_up(uint32_t(w), 16), gy);
      }
      if (!avg) ++trace_j;
      if (!trace_prefix.empty() && !avg && trace_j==1) {
        if (!submit()) return fail("trace adjoint submit");
        trace("pre-J1.f32",z.map,nHR*4);
        trace("gradient-J1.i32",acc.map,nHR*4);
        trace("coverage-J1.i32",cov.map,nHR*4);
        trace("weights.f32",wt.map,nLR*4);
        trace("affine.f32",pb.map,size_t(K)*24);
        trace("local.f32",db.map,fine ? size_t(K) * nbf * 8 : field_bytes);
        trace("adjoint-push.bin",&m,sizeof(m));
        gpu_->begin();
      }
      const int Kf = avg ? K : fitK;
      Push u = q; u.tau = jacobi_damp * float(Kf) / float(s * s); u.LB = prm.cov_smooth >= 0 ? prm.cov_smooth : (s == 1 ? 0 : 1); u.a = steps_per_merge; u.b = (prm.lkr_l > 0.0f ? prm.lkr_l : (s == 1 ? 1.45f : 1.8f)) * float(Kf) * 0.5f / float(s * s);
      // Expected R/B coverage at 2x is K/16 under uniform sampling.
      static const float chroma_support = std::getenv("LKRL_CHROMA_SUPPORT")
          ? std::clamp(float(std::atof(std::getenv("LKRL_CHROMA_SUPPORT"))), 0.0f, 1.0f)
          : 0.0f;
      const float chroma_floor = s == 2 && !avg && !hdr && chroma_support > 0.0f
          ? chroma_support * float(Kf) / 16.0f : 0.0f;
      u.clip = chroma_floor;  // zupd uses clip for the experimental R/B coverage floor.
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
      if (avg) { u.a = 0.0f; u.rpow = hdr_in ? -2.0f : -1.0f; u.robk = 0.0f; u.LB = avg_r >= 0 ? avg_r : (s == 1 ? 0 : 1); }
      if (!avg && trace_j==1) trace("update-push.bin",&u,sizeof(u));
      // At native resolution, fit each camera channel independently. The shared
      // green step in the colour-difference fit moves all three by the same
      // raw increment, which shifts chromaticity along fine brightness edges
      // (green/magenta fringes on the 133623 railings). At 2x, the sparse R/B
      // footprints still benefit from the coupled fit's Bayer-zipper suppression.
      static const char* cd_radius = std::getenv("LKRL_CDFIT");
      const int cd_r = cd_radius ? std::atoi(cd_radius) : (s == 1 ? -1 : 2);
      static const float cd_k = std::getenv("LKRL_CDK") ? float(std::atof(std::getenv("LKRL_CDK"))) : 0.0f;
      if (!avg && !prm.direct_rgb && cd_r >= 0 && u.robk <= 0.0f) {
        static const float cd_b = std::getenv("LKRL_CDB") ? float(std::atof(std::getenv("LKRL_CDB"))) : 1.0f;
        Push pc2 = q; pc2.tau = u.tau; pc2.LB = cd_r; pc2.robk = cd_k; pc2.rpow = cd_b;
        static const int cd_cl = std::getenv("LKRL_CDCL") ? std::atoi(std::getenv("LKRL_CDCL")) : 97;
        pc2.K = cd_cl;
        pc2.mode = int(std::lround(256.0f * chroma_floor));  // coverage floor in Q8
        static const float gsup_env = std::getenv("LKRL_GSUP") ? float(std::atof(std::getenv("LKRL_GSUP"))) : -1.0f;
        const float gsup = gsup_env >= 0.0f ? gsup_env : prm.support_step;
        pc2.s = s == 2 ? int(std::lround(256.0f * gsup * float(Kf) / 4.0f)) : 0;   // support-adaptive green step (Q8)
        if (cd_k > 0.0f) {   // the edge stop reads neighbours' G: write a copy, then swap it in
          gpu_->dispatch(cdupd_, gpu_->set(cdupd_, {&z, &acc, &cov, &ztb, &cdb}), &pc2, div_up(uint32_t(H * W), 256));
          gpu_->copy(ztb, z, nHR * 4);
        } else {   // each invocation reads only its own z: update in place (no copy)
          // separable window: cdrow's row sums (coverage rows only when the coverage changed), cdupd the columns
          static const bool cd_sep = !(std::getenv("LKRL_CD_SEP") && std::atoi(std::getenv("LKRL_CD_SEP")) == 0);
          pc2.h = !cd_sep ? -1 : (need_cov ? 1 : 0);
          if (cd_sep) gpu_->dispatch(cdrow_, gpu_->set(cdrow_, {&acc, &cov, &cdb}), &pc2, div_up(uint32_t(H * W), 256));
          gpu_->dispatch(cdupd_, gpu_->set(cdupd_, {&z, &acc, &cov, &z, &cdb}), &pc2, div_up(uint32_t(H * W), 256));
        }
        gpu_->fill(acc, 0);
        need_cov = false;
        continue;
      }
      if (ne_fused && (u.LB != 0 || u.robk > 0.0f)) {   // not the per-pixel update: the two passes
        gpu_->dispatch(ne_apply_, sNeApply, &ne_am, div_up(uint32_t(nHR), 256));
        ne_fused = false;
      }
      if (ne_fused) {
        gpu_->dispatch(ne_zupd_, sNeZupd, &u, div_up(uint32_t(nHR), 256));
        gpu_->copy(ztb, z, nHR * 4);
        ne_fused = false;
      } else {
        gpu_->dispatch(zupd_, sUpd, &u, div_up(uint32_t(nHR), 256));
      }
      if (u.robk > 0.0f || avg) gpu_->fill(acc, 0);
      need_cov = avg;   // the next J recomputes its (gain) divisor
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
      if (!trace_prefix.empty()) {
        if (!submit()) return fail("trace merge submit");
        if (avg && gpu_hyb) trace("average.f32",zavgB.map,nHR*4);
        else trace("J"+std::to_string(trace_j)+".f32",z.map,nHR*4);
        if (!avg && trace_j==1) trace("divisor-J1.f32",dvb.map,nHR*4);
        gpu_->begin();
      }
    } else if (c == 'G') {
      if (K < 2 || dense_path) continue;
      if (weights_stale && qg.mode == 0) weights();
      static const bool g_noaff = std::getenv("LKRL_G_NOAFF") && std::atoi(std::getenv("LKRL_G_NOAFF")) != 0;
      if (!g_noaff) {   // the affine step (LKRL_G_NOAFF=1: block offsets only, no host round trip)
      gpu_->dispatch(jacP, sJac, &qg, jg, uint32_t(K - 1));
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
      }
      gpu_->dispatch(jacblkP, sBlk, &qg, uint32_t(nbx), uint32_t(nby), uint32_t(K - 1));
      // jacblk accumulates unnormalised squared camera-RGB gradients. Its
      // Hessian floor must have the same signal-squared units: a fixed .02
      // overwhelms dark wall texture and pins real subpixel misalignment.
      const float noise_floor = float(LB * LB) * std::max(prm.noise_a * 0.18f + prm.noise_b, 1e-12f);
      Push m = q; m.robk = lam; m.tau = std::min(prm.local_floor, noise_floor);
      gpu_->dispatch(solveblk_, sSolveBlk, &m, div_up(uint32_t(K * nb), 64));
      gpu_->copy(dtmp, db, size_t(K) * nb * 8);
      weights_stale = true;
    } else {
      return fail("schedule: only J, A and G");
    }
  }
  // Edge position correction after the fit (LKRL_ESHIFT = strength, LKRL_ESHT = contrast in
  // sigmas; prototype 2026-11-17): edgeshift.comp.
  static const float es_s = std::getenv("LKRL_ESHIFT") ? float(std::atof(std::getenv("LKRL_ESHIFT"))) : 0.0f;
  static const float es_t = std::getenv("LKRL_ESHT") ? float(std::atof(std::getenv("LKRL_ESHT"))) : 8.0f;
  if (es_s > 0.0f) {
    VkDescriptorSet se = gpu_->set(edgeshift_, {&z, &ztb, &dlb});
    static const float es_st = std::getenv("LKRL_ESHST") ? float(std::atof(std::getenv("LKRL_ESHST"))) : 0.9f;
    Push pe = q; pe.clip = es_s; pe.tau = es_t; pe.mode = 0; pe.robk = es_st;
    gpu_->dispatch(edgeshift_, se, &pe, div_up(uint32_t(W), 16), div_up(uint32_t(H), 16));
    pe.mode = 1;
    gpu_->dispatch(edgeshift_, se, &pe, div_up(uint32_t(W), 16), div_up(uint32_t(H), 16));
    gpu_->copy(ztb, z, nHR * 4);
  }
  if (noise_scale) {   // coverage of the final geometry: weighted (cov, up to date) vs plain
    if (weights_stale) weights();
    if (need_cov) {   // weights changed after the last merge step: weighted coverage too
      gpu_->fill(cov, 0);
      Push m = q; m.mode = 2;
      gpu_->dispatch(adjP, sAdj, &m, gx, gy);
    }
    if (!cov1_done) {
      gpu_->fill(cov1, 0);
      Push m = q; m.mode = 2;
      gpu_->dispatch(adjP, sAdjPlain, &m, gx, gy);
    }
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
    // Luma = the fit, always (2026-11-17). The noise-following shrink toward the average blurred moving leaves and the wall behind them
    // (IMG_20261112_125318_179 2x, noise reported 6 %: fine detail 0.067 -> 0.032) and removed the grain; LKRL_LT re-enables it.
    (void)rel; const float t_auto = 0.0f;
    const float t = std::getenv("LKRL_LT") ? float(std::atof(std::getenv("LKRL_LT"))) : t_auto;
    // Chroma: blurred fit chroma, r 1 at 1x / 2 at 2x (LKRL_CBLUR = radius; -1 = the average's chroma).
    const float cr = std::getenv("LKRL_CBLUR") ? float(std::atoi(std::getenv("LKRL_CBLUR"))) : (s == 1 ? 1.0f : 2.0f);
    HybPush hp{H, W, K, 0, prm.noise_a, prm.noise_b, t, prm.clip, prm.wb[0], prm.wb[1], prm.wb[2], cr};
    VkDescriptorSet sh = gpu_->set(hyb_, {&z, &zavgB, &hybD, &hybO});
    gpu_->dispatch(hyb_, sh, &hp, div_up(uint32_t(W), 16), div_up(uint32_t(H), 16));
    hp.mode = 1;
    gpu_->dispatch(hyb_, sh, &hp, div_up(uint32_t(W), 16), div_up(uint32_t(H), 16));
    // Long-straight-edge smoothing (le_*.comp, 2026-11-18): removes the dotting
    // on long straight thin lines (pole highlights) that AMaZE does not have; grass / leaves / rock texture
    // fail its straightness test and are untouched. Thresholds: LKRL_LE_K (edge vs noise), _COH, _TOL, _R.
    // Default ON since 2026-11-18 (full 2x car burst: 0.35 % of pixels changed, all on long straight
    // structure: poles, rails, road lines, car body lines; foliage / road / rocks 0.000-0.003 %, detail
    // equal; pole dotting -45 %; ~9 ms GPU per 1024 px tile on Mali-G615). LKRL_LONGEDGE=0 turns it off.
    // Directional lattice notch (dln_apply.comp, 2026-11-19), before the long-edge pass: removes the
    // lattice-period dashes / beads along thin near-axis lines at 2x (far poles, twigs, branch outlines;
    // IMG_20260919_184641_979 8 frames, far pole: dash energy at 4 HR px -75 %, at 2 px -51 %; foliage
    // detail -1.6 %). Gate = coherence of a radius-6 tensor + edge energy vs the noise model, so dashes on
    // a thin twig inside a leafy crown (low coherence there) are not reached.
    // LKRL_DLN=0 off.
    static const int dln_env = std::getenv("LKRL_DLN") ? std::atoi(std::getenv("LKRL_DLN")) : -1;
    // On at every scale (user 2026-11-19: all of today's fixes on for 1x, 1.5x and 2x; at 1x it measured
    // -5 % (dln) / -4 % (axn) fine detail on the car-burst plate crop). LKRL_DLN=0 off.
    if (dln_env != 0) {
      auto envf = [](const char* n, float d) { const char* v = std::getenv(n); return v ? float(std::atof(v)) : d; };
      const size_t HWs = size_t(H) * W;
      Buffer &dT = buf(HWs * 8), &dU = buf(HWs * 8), &dP = buf(HWs * 8);
      Push dq = q;
      dq.a = prm.noise_a / float(s * s); dq.b = prm.noise_b / float(s * s);   // merged noise at output px (prototype)
      dq.LB = int(envf("LKRL_DLN_R", 6.0f));
      const uint32_t ggx = div_up(uint32_t(W), 16), ggy = div_up(uint32_t(H), 16);
      gpu_->dispatch(le_tensor_, gpu_->set(le_tensor_, {&hybO, &dT}), &dq, ggx, ggy);
      dq.mode = 0; gpu_->dispatch(le_blur_, gpu_->set(le_blur_, {&dT, &dU}), &dq, ggx, ggy);
      dq.mode = 1; gpu_->dispatch(le_blur_, gpu_->set(le_blur_, {&dU, &dT}), &dq, ggx, ggy);
      dq.clip = envf("LKRL_DLN_K", 4.0f); dq.robk = envf("LKRL_DLN_C0", 0.5f); dq.tau = envf("LKRL_DLN_C1", 0.8f);
      dq.rpow = envf("LKRL_DLN_D", 1.0f);   // tap spacing (output px)
      dq.LB = int(envf("LKRL_DLN_N", 3.0f));  // triangle half-length (taps)
      VkDescriptorSet dS = gpu_->set(dln_apply_, {&hybO, &dT, &dP});
      dq.mode = 0; gpu_->dispatch(dln_apply_, dS, &dq, ggx, ggy);   // fp16 RGB copy (neighbour taps)
      dq.mode = 1; gpu_->dispatch(dln_apply_, dS, &dq, ggx, ggy);
      // Axis lattice notch (axn_apply.comp): thin near-axis poles in front of foliage, where the tensor
      // gate above stays shut (dots + textured background). Line test = sign consistency of the
      // across-line second difference over 17 px; short strokes (plate letters) are left alone.
      // 184641 8 frames: far lamp poles over bushes clean, car / plate / foliage detail -0.3..-3 %.
      // LKRL_AXN=0 off.
      static const bool axn_on = !std::getenv("LKRL_AXN") || std::atoi(std::getenv("LKRL_AXN")) != 0;
      if (axn_on) {
        dq.LB = int(envf("LKRL_AXN_N", 8.0f)); dq.clip = envf("LKRL_AXN_K", 4.0f);
        dq.robk = envf("LKRL_AXN_C0", 0.7f); dq.tau = envf("LKRL_AXN_C1", 0.9f);
        dq.rpow = envf("LKRL_AXN_M", 6.0f);   // along-line chroma half-length (taps)
        VkDescriptorSet aS = gpu_->set(axn_apply_, {&hybO, &dT, &dP});
        dq.mode = 0; gpu_->dispatch(axn_apply_, aS, &dq, ggx, ggy);
        dq.mode = 1; gpu_->dispatch(axn_apply_, aS, &dq, ggx, ggy);
      }
    }
    static const bool le_on = !std::getenv("LKRL_LONGEDGE") || std::atoi(std::getenv("LKRL_LONGEDGE")) != 0;
    if (le_on) {
      auto envf = [](const char* n, float d) { const char* v = std::getenv(n); return v ? float(std::atof(v)) : d; };
      const size_t HWs = size_t(H) * W;
      Buffer &leT = buf(HWs * 8), &leU = buf(HWs * 8), &leZ = buf(nHR * 4);
      Push lq = q; lq.a = prm.noise_a / 3.0f; lq.b = prm.noise_b / 3.0f;   // luma = mean of 3 planes
      lq.LB = 4;
      const uint32_t ggx = div_up(uint32_t(W), 16), ggy = div_up(uint32_t(H), 16);
      gpu_->dispatch(le_tensor_, gpu_->set(le_tensor_, {&hybO, &leT}), &lq, ggx, ggy);
      lq.mode = 0; gpu_->dispatch(le_blur_, gpu_->set(le_blur_, {&leT, &leU}), &lq, ggx, ggy);
      lq.mode = 1; gpu_->dispatch(le_blur_, gpu_->set(le_blur_, {&leU, &leT}), &lq, ggx, ggy);
      lq.clip = envf("LKRL_LE_K", 4.0f); lq.robk = envf("LKRL_LE_COH", 0.8f);
      lq.tau = std::cos(envf("LKRL_LE_TOL", 0.12f)); lq.LB = int(envf("LKRL_LE_R", 16.0f)); lq.rpow = envf("LKRL_LE_SIG", 1.5f);
      gpu_->copy(hybO, leZ, nHR * 4);   // le_apply reads the copy, writes only qualifying pixels in place
      gpu_->dispatch(le_apply_, gpu_->set(le_apply_, {&leZ, &leT, &hybO}), &lq, ggx, ggy);
    }
    // Luma-guided chroma (gcf_apply.comp, 2026-11-19): at 2x the sparse R / B planes put their edges
    // a little off the green edge (blue fringe / dots on a thin trunk against the sky, green fringes on
    // plate letters, IMG_20260919_184641_979). Colour differences refit as a local linear function of
    // luma over 5 x 5; luma unchanged. LKRL_GCF=0 off; LKRL_GCF_R / _K.
    static const int gcf_env = std::getenv("LKRL_GCF") ? std::atoi(std::getenv("LKRL_GCF")) : -1;
    if (gcf_env != 0) {   // 1x and 2x
      auto envf = [](const char* n, float d) { const char* v = std::getenv(n); return v ? float(std::atof(v)) : d; };
      const size_t HWs = size_t(H) * W;
      Buffer &gP = buf(HWs * 8), &gAB = buf(HWs * 16);
      Push gq = q;
      gq.a = prm.noise_a / float(s * s); gq.b = prm.noise_b / float(s * s);
      gq.LB = int(envf("LKRL_GCF_R", 2.0f)); gq.clip = envf("LKRL_GCF_K", 1.0f);
      const uint32_t ggx = div_up(uint32_t(W), 16), ggy = div_up(uint32_t(H), 16);
      VkDescriptorSet gS = gpu_->set(gcf_apply_, {&hybO, &gP, &gAB});
      for (int m = 0; m < 3; ++m) { gq.mode = m; gpu_->dispatch(gcf_apply_, gS, &gq, ggx, ggy); }
    }
    // Reference chroma anchor (canc_apply.comp, 2026-11-19): the merge's local-mean colour is moved to the
    // reference frame's (z0, its RCD start) where they differ beyond the reference's noise. Removes the
    // teal of a swaying trunk against the sky (other frames put sky colour on it; the reference alone is
    // right); luma and fine colour detail stay the merge's. Gated to pixels where most frames were
    // rejected (plate letters keep the merge's colour). 1x, 1.5x and 2x. LKRL_CANC=0 off; _R / _K.
    static const int canc_env = std::getenv("LKRL_CANC") ? std::atoi(std::getenv("LKRL_CANC")) : -1;
    if (canc_env != 0) {
      auto envf = [](const char* n, float d) { const char* v = std::getenv(n); return v ? float(std::atof(v)) : d; };
      const size_t HWs = size_t(H) * W;
      Buffer &cA = buf(HWs * 16), &cB = buf(HWs * 16);
      Push cq = q;
      cq.a = prm.noise_a; cq.b = prm.noise_b;
      cq.LB = int(envf("LKRL_CANC_R", 3.0f)); cq.clip = envf("LKRL_CANC_K", 3.0f);
      const uint32_t ggx = div_up(uint32_t(W), 16), ggy = div_up(uint32_t(H), 16);
      if (weights_stale) weights();   // final per-sample weights -> frames used per pixel
      VkDescriptorSet cS = gpu_->set(canc_apply_, {&hybO, &z0, &cA, &cB, &wt});
      for (int m = 0; m < 3; ++m) { cq.mode = m; gpu_->dispatch(canc_apply_, cS, &cq, ggx, ggy); }
    }
    hyb_done = true;
  }
  // Learned prior, Vulkan fallback (Network on, no usable NPU): the folded network, one dispatch per
  // layer, on the merged image in place (same weights / result as the NPU path, lkrl_prior.cpp).
  if (prm.prior_gpu && (hyb_done || prm.direct_rgb)) {
    Buffer& merged = hyb_done ? hybO : z;
    static std::vector<float> pwb; static std::vector<PriorLayerInfo> pinfo;
    static const bool have = PriorPacked(&pwb, &pinfo);
    if (have && !pinfo.empty()) {
      const size_t HWs = size_t(H) * W;
      Buffer &actA = buf(HWs * 24 * 2), &actB = buf(HWs * 24 * 2), &wbB = buf(pwb.size() * 4);
      std::memcpy(wbB.map, pwb.data(), pwb.size() * 4);
      Push cq = q;
      cq.a = 100.0f * std::sqrt(std::max(prm.noise_a, 1e-6f) * 0.18f);
      cq.b = PriorColourOnly() ? 0.0f : 1.0f;   // last layer: colour-only correction (prior_conv.comp)
      const uint32_t ggx = div_up(uint32_t(W), 16), ggy = div_up(uint32_t(H), 16);
      Buffer* in = &actA; Buffer* out = &actB;
      for (size_t li = 0; li < pinfo.size(); ++li) {
        const PriorLayerInfo& L = pinfo[li];
        cq.mode = li == 0 ? 0 : (li + 1 == pinfo.size() ? 2 : 1);
        cq.K = L.cin; cq.LB = L.cout; cq.s = L.relu; cq.h = L.woff; cq.w = L.boff;
        gpu_->dispatch(prior_conv_, gpu_->set(prior_conv_, {&merged, in, out, &wbB}), &cq, ggx, ggy, div_up(uint32_t(L.cout), 8));
        std::swap(in, out);
      }
    }
  }
  if (hyb_done && !trace_prefix.empty()) {
    if (!submit()) return fail("trace hybrid submit");
    trace("hybrid.f32", hybO.map, nHR * 4);
    gpu_->begin();
  }
  if (!submit()) return fail("final submit");
  if (hyb_done) z_out->assign(hybO.as<float>(), hybO.as<float>() + nHR);
  else z_out->assign(z.as<float>(), z.as<float>() + nHR);
  // NaN safety net (2026-11-20). A merge that overflows the int32 fixed-point accumulators (an exposure
  // bracket merged without its exposures) produced NaN, which the later passes (LFC, DCT) spread into
  // blank areas. Any bad output pixel (below) takes the reference frame's own colour at that point
  // (mean of its same-colour raw samples in the 3 x 3 LR neighbourhood, clipped samples at the clip
  // level): a local, plausible value, never NaN. Normal merges are unchanged (no non-finite pixels).
  {
    const size_t HWp = size_t(H) * W;
    float* zo = z_out->data();
    const float cl = HalfRound(prm.clip);
    // Bad = non-finite, dilated by 6 px: the overflow also leaves finite garbage next to each NaN and the
    // GPU post passes (gcf, canc) ran before this and smeared it into rings ~5 px out (2 px left visible
    // rings on My/HDR merged without exposures). No value-range test: merged lamps legitimately exceed
    // 2 x clip (a range test boxed every street light of IMG_20260921_194618_520).
    std::vector<uint8_t> bm(HWp, 0);
    bool any = false;
    for (size_t q = 0; q < HWp; ++q)
      for (int c = 0; c < 3; ++c) {
        const float v = zo[c * HWp + q];
        if (!std::isfinite(v)) { bm[q] = 1; any = true; break; }
      }
    if (any) {
      std::vector<uint8_t> d(bm);
      for (int Y = 0; Y < H; ++Y)
        for (int X = 0; X < W; ++X)
          if (bm[size_t(Y) * W + X])
            for (int dy = -6; dy <= 6; ++dy)
              for (int dx = -6; dx <= 6; ++dx) {
                const int yy = Y + dy, xx = X + dx;
                if (yy >= 0 && xx >= 0 && yy < H && xx < W) d[size_t(yy) * W + xx] = 1;
              }
      bm.swap(d);
    }
    size_t bad = 0;
    for (size_t q = 0; q < HWp && any; ++q) {
      if (!bm[q]) continue;
      ++bad;
      const int i = std::min(int(q / W) / s, h - 1), j = std::min(int(q % W) / s, w - 1);
      float sum[3] = {0, 0, 0}, cnt[3] = {0, 0, 0};
      for (int di = -1; di <= 1; ++di)
        for (int dj = -1; dj <= 1; ++dj) {
          const int ii = std::clamp(i + di, 0, h - 1), jj = std::clamp(j + dj, 0, w - 1);
          const float v = ref_start[size_t(ii) * w + jj];
          if (!std::isfinite(v)) continue;
          const int c = (ii & 1) == 0 ? ((jj & 1) == 0 ? 0 : 1) : ((jj & 1) == 0 ? 1 : 2);   // RGGB
          sum[c] += std::min(v, cl); cnt[c] += 1.0f;
        }
      for (int c = 0; c < 3; ++c) zo[c * HWp + q] = cnt[c] > 0.0f ? sum[c] / cnt[c] : 0.0f;
    }
    if (bad) {
      if (st) st->nonfinite += bad;
      std::fprintf(stderr, "lkr-lite: NaN safety net replaced %zu bad pixels in a %dx%d tile\n", bad, W, H);
    }
  }
  // HDR bracket: where no frame saw the scene unclipped (bare lamps, the sun) the solve has no data and
  // neighbouring edge samples pulled z down to ~ the reference's clip level, coloured (real bracket
  // IMG_20261120_151412_514: a ceiling tube at 1/4 of white, magenta with blue / orange beads). Such
  // pixels are at least the shortest frame's clip: set them clipped (all planes) so the finish turns
  // them neutral white like any clipped area. Mask = reference samples that clipped themselves and no
  // frame filled (HdrFillReference).
  if (!ref_fused.empty()) {
    const float cl = HalfRound(prm.clip);
    // Soft: strength f = clamp(2 x binomial 3 x 3 of the mask) (often only the green sites of a lamp clip:
    // the raw mask is a checkerboard; a hard fill gave a dotted / jagged line). z = max(z, f x white),
    // white = neutral after white balance.
    std::vector<float> f(size_t(h) * w, 0.0f);
    for (int i = 0; i < h; ++i)
      for (int j = 0; j < w; ++j) {
        float a = 0.0f;
        for (int di = -1; di <= 1; ++di)
          for (int dj = -1; dj <= 1; ++dj) {
            const int ii = std::clamp(i + di, 0, h - 1), jj = std::clamp(j + dj, 0, w - 1);
            a += float((2 - std::abs(di)) * (2 - std::abs(dj))) * hdr_unfilled[size_t(ii) * w + jj];
          }
        f[size_t(i) * w + j] = std::min(1.0f, a * (2.0f / 16.0f));
      }
    const size_t HWp = size_t(H) * W;
    float* zo = z_out->data();
    const float v = 1.02f * cl;
    for (int Y = 0; Y < H; ++Y)
      for (int X = 0; X < W; ++X) {
        // bilinear of the LR strength at the HR pixel centre
        const float fy = std::clamp((Y + 0.5f) / s - 0.5f, 0.0f, float(h - 1)), fx = std::clamp((X + 0.5f) / s - 0.5f, 0.0f, float(w - 1));
        const int y0 = int(fy), x0 = int(fx), y1 = std::min(y0 + 1, h - 1), x1 = std::min(x0 + 1, w - 1);
        const float ay = fy - y0, ax = fx - x0;
        const float st = (1 - ay) * ((1 - ax) * f[size_t(y0) * w + x0] + ax * f[size_t(y0) * w + x1]) +
                         ay * ((1 - ax) * f[size_t(y1) * w + x0] + ax * f[size_t(y1) * w + x1]);
        if (st <= 0.0f) continue;
        const size_t q = size_t(Y) * W + X;
        // neutral after white balance (equal camera RGB turned magenta); G reaches the clip mark at st = 1
        for (int c = 0; c < 3; ++c) zo[c * HWp + q] = std::max(zo[c * HWp + q], st * v * prm.wb[1] / std::max(prm.wb[c], 1e-3f));
      }
  }
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
      // Luma = the fit, always (2026-11-17); see the GPU path above. LKRL_LT re-enables the shrink.
      (void)rel; const float t_auto = 0.0f;
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
      if (hdr) {   // weights carry each frame's inverse noise variance / hdr_fmax: below 1 where the long frame cleans shadows
        const double inf = a > 0 ? b * hdr_fmax / a : 1.0;   // merged information vs plain coverage of usable samples
        (*noise_scale)[i] = std::clamp(float(std::sqrt(0.8 / std::max(inf, 1e-3))), 0.3f, 2.0f * std::sqrt(float(K)));
        continue;
      }
      (*noise_scale)[i] = std::min(std::sqrt(std::max(0.8f / std::max(rho, 1e-3f), 1.0f)), std::sqrt(float(K)));
    }
  }
  if (z_out) trace("final-z.f32", z_out->data(), nHR * 4);   // BM3D-lite prototype input
  if (noise_scale && noise_scale->size() == size_t(H) * W) trace("noise-scale.f32", noise_scale->data(), size_t(H) * W * 4);
  if (x_out && prm.dct) {
    if (!denoiseDct(*z_out, H, W, prm, noise_scale, x_out, &S, err)) return false;
    if (S.prior_calls) trace("final-x.f32", x_out->data(), nHR * 4);
  }
  std::memcpy(p.data(), pb.map, p.size() * 4);
  if (!dense_path && !fine) std::memcpy(dloc.data(), db.map, dloc.size() * 4);
  if (prm.profile) S.profile = gpu_->profileReport();
  S.ms_total = now_ms() - t0;
  if (st) *st = S;
  return true;
}

bool LiteSolver::denoiseDct(const std::vector<float>& fit, int H, int W, const LiteParams& prm,
                            const std::vector<float>* noise_scale, std::vector<float>* x_out,
                            LiteStats* st, std::string* err) {
  auto fail = [&](const char* message) {
    if (err) *err = message;
    gpu_->resetSets();
    return false;
  };
  const size_t nHR = size_t(H) * W * 3;
  if (prm.dct_fallback && noise_scale && noise_scale->size() == size_t(H) * W &&
      prm.dct_grain <= 0.0f && !prm.dct_chroma_all &&
      std::none_of(noise_scale->begin(), noise_scale->end(), [](float value) { return value > 1.0f; })) {
    *x_out = fit;
    return true;
  }
  const double prior_start = now_ms();
  size_t slot = 0;
  auto buf = [&](size_t bytes, bool host_read = false) -> Buffer& {
    if (dcache_.size() <= slot) dcache_.emplace_back();
    Entry& en = dcache_[slot++];
    if (!en.buf.map || en.bytes != bytes || en.host_read != host_read) {
      gpu_->free(en.buf);
      en.buf = gpu_->alloc(bytes, host_read);
      en.bytes = bytes;
      en.host_read = host_read;
    }
    return en.buf;
  };
  if (H < 8 || W < 8 || (H & 3) || (W & 3)) return fail("dct needs H, W >= 8 and divisible by 4");
  const int ny = (H - 8) / 4 + 1, nx = (W - 8) / 4 + 1;
  Buffer &z = buf(nHR * 4), &xb = buf(nHR * 4, true), &dns = buf(size_t(H) * W * 4), &diw = buf(size_t(H + W + 8) * 4),
         &dnv = buf(size_t(ny) * nx * 4);
  for (const auto& entry : dcache_) if (!entry.buf.map) return fail("dct buffers");
  std::memcpy(z.map, fit.data(), nHR * 4);
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
  // Measured merged noise (2026-11-17): sigma/sqrt(K) under-reads Lite's real merged noise ~3x on
  // real bursts (the fit does not average perfectly). In the noise-following modes the level is
  // scaled by the ratio measured on THIS image: per 8x8 block, pixel variance of the merged luma
  // over the model variance, on blocks with no lost frames; the 2nd percentile picks flat blocks
  // (texture only raises a block), divided by that percentile of chi^2(63)/63 (pure noise). 1..16.
  float noise_gain = 1.0f;
  if (prm.dct_chroma_all || prm.dct_grain > 0.0f) {
    static const float qf = std::getenv("LKRL_NQ") ? float(std::atof(std::getenv("LKRL_NQ"))) : 0.02f;
    const float* zz = fit.data();
    const size_t HW = size_t(H) * W;
    const float la = denom > 0 ? prm.noise_a / denom : 0.0f, lb = denom > 0 ? prm.noise_b / denom : 1.0f;
    const float s2 = prm.dct_sigma * prm.dct_sigma;
    std::vector<float> rr;
    for (int by = 0; by + 8 <= H; by += 8)
      for (int bx = 0; bx + 8 <= W; bx += 8) {
        double sm = 0, sq = 0; bool lostb = false;
        for (int y = by; y < by + 8; ++y)
          for (int x = bx; x < bx + 8; ++x) {
            const size_t i = size_t(y) * W + x;
            if (hns && (*noise_scale)[i] > 1.0f) lostb = true;
            const double v = 0.25 * (zz[i] * prm.wb[0] + zz[2 * HW + i] * prm.wb[2]) + 0.5 * zz[HW + i] * prm.wb[1];
            sm += v; sq += v * v;
          }
        if (lostb) continue;
        const double m = sm / 64, var = std::max(sq / 64 - m * m, 0.0) * 64.0 / 63.0;
        const double mv = s2 * std::max(la * std::max(m, 0.0) + lb, 0.0);
        if (mv > 0) rr.push_back(float(var / mv));
      }
    if (rr.size() >= 16) {
      const size_t k = size_t(qf * (rr.size() - 1));
      std::nth_element(rr.begin(), rr.begin() + k, rr.end());
      // percentile of chi^2(63)/63 for pure noise ~ 1 + z_q sqrt(2/63) (z from a logistic fit of the normal)
      const float zq = -1.7f * std::log((1.0f - qf) / qf) / 3.0f;
      const float pq = std::max(1.0f + zq * std::sqrt(2.0f / 63.0f), 0.3f);
      // Cap: a tile with no flat block cannot be measured (texture reads as noise); 16 = 4x the model sigma.
      noise_gain = std::min(std::max(rr[k] / pq, 1.0f), 16.0f);
    }
    if (std::getenv("LKRL_NGAIN_LOG")) std::fprintf(stderr, "dct noise gain %.2f (%zu blocks)\n", noise_gain, rr.size());
  }
  DctPush d{H, W, ny, nx, 0, 0, H / 4, hns ? 1 : 0, prm.dct_sigma * prm.dct_sigma * noise_gain,
            denom > 0 ? prm.noise_a / denom : 0.0f, denom > 0 ? prm.noise_b / denom : 1.0f,
            prm.dct_luma * prm.dct_luma, prm.dct_chroma * prm.dct_chroma, prm.wb[0], prm.wb[1], prm.wb[2],
            prm.dct_dark_level, prm.dct_dark_cap, prm.dct_dark_luma, prm.dct_luma_cap, prm.dct_chroma_all ? 1.0f : 0.0f, prm.dct_luma_floor,
            prm.dct_fallback ? prm.dct_grain * prm.dct_grain : 0.0f,
            prm.dct_grain_rel * prm.dct_grain_rel, prm.dct_grain_rho, prm.dct_chroma_norm ? 1.0f : 0.0f, prm.dct_grain_tilt,
            prm.dct_grain_sub};
  gpu_->resetSets();
  // LKRL_DCT_OLD (debug.lkr.dctold): the previous one-block-per-invocation shaders, for A/B.
  static const bool dct_old = std::getenv("LKRL_DCT_OLD") && std::atoi(std::getenv("LKRL_DCT_OLD")) != 0;
  const Pipeline &py_ = dct_old ? kdyo_ : kdy_, &pu_ = dct_old ? kduo_ : kdu_, &pv_ = dct_old ? kdvo_ : kdv_;
  VkDescriptorSet sy = gpu_->set(py_, {&z, &xb, &dns, &diw, &dnv}), su = gpu_->set(pu_, {&z, &xb, &dns, &diw, &dnv}),
                  sv = gpu_->set(pv_, {&z, &xb, &dns, &diw, &dnv}), sc = gpu_->set(kdyuv_, {&xb});
  gpu_->begin();
  gpu_->fill(xb, 0);
  const std::pair<const Pipeline*, VkDescriptorSet> ch[3] = {{&py_, sy}, {&pu_, su}, {&pv_, sv}};
  for (const auto& c : ch)
    for (d.py = 0; d.py < 2; ++d.py)
      for (d.px = 0; d.px < 2; ++d.px) {
        if (dct_old)
          gpu_->dispatch(*c.first, c.second, &d, uint32_t((nx + 1) / 2 + 3) / 4, uint32_t((ny + 1) / 2 + 3) / 4);
        else   // dct_x.glsl: 8 blocks (8 row invocations each) per workgroup, blocks of this phase in x.
          gpu_->dispatch(*c.first, c.second, &d, uint32_t(((nx + 1) / 2) * ((ny + 1) / 2) + 7) / 8, 1);
      }
  gpu_->dispatch(kdyuv_, sc, &d, uint32_t(W / 4 + 15) / 16, uint32_t(H + 15) / 16);
  if (!gpu_->submit()) return fail("dct submit");
  x_out->assign(xb.as<float>(), xb.as<float>() + nHR);
  st->ms_prior += now_ms() - prior_start;
  ++st->prior_calls;
  ++st->submits;
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
  const bool hns = noise_scale && noise_scale->size() == HW &&
      (fa.grain > 0.0f || (fa.notch && std::any_of(noise_scale->begin(), noise_scale->end(),
                                                 [](float factor) { return factor > 1.0f; })));
  Buffer &bx = buf(3 * HW * 4, true), &bz = buf(fa.grain > 0.0f ? 3 * HW * 4 : 16), &bns = buf(hns ? HW * 4 : 16), &bp = buf(4 * HW * 4),
         &bt = buf(fa.notch ? 3 * HW * 4 : 16), &bm = buf(2 * HW * 4), &bk = buf(3 * HW * 4);
  for (size_t i = 0; i < slot; ++i) if (!fcache_[i].buf.map) return fail("fin buffers");
  std::memcpy(bx.map, x->data(), 3 * HW * 4);
  if (fa.grain > 0.0f) std::memcpy(bz.map, z, 3 * HW * 4);
  if (hns) std::memcpy(bns.map, noise_scale->data(), HW * 4);
  struct FinPush { int32_t H, W, mode, pl, sm, has_ns, notch, desat; float wbr, wbg, wbb, clip, grain, eqs, p0, p1;
                   float hr, hg, hb, hrec; };
  static_assert(sizeof(FinPush) == 80, "fin push");
  FinPush f{H, W, 0, 0, 0, hns ? 1 : 0, fa.notch ? 1 : 0, fa.desat ? (std::getenv("LKRL_HL_OLD") ? 2 : 1) : 0, fa.wb[0], fa.wb[1], fa.wb[2],
            fa.clip, fa.grain, fa.eq_strength, 0.0f, 0.0f, fa.hl_ratio[0], fa.hl_ratio[1], fa.hl_ratio[2],
            fa.hl_rec ? 1.0f : 0.0f};
  gpu_->resetSets();
  VkDescriptorSet st = gpu_->set(fin_, {&bx, &bz, &bns, &bp, &bt, &bm, &bk});
  const uint32_t gx = div_up(uint32_t(W), 16), gy = div_up(uint32_t(H), 16);
  auto run = [&](int mode, int pl = 0, int sm = 0, uint32_t planes = 1) {
    FinPush p = f;
    p.mode = mode;
    p.pl = pl;
    p.sm = sm;
    gpu_->dispatch(fin_, st, &p, gx, gy, planes);
  };
  static const bool notch2 = std::getenv("LKRL_NOTCH2") != nullptr;
  // Three steps ping-pong P -> T -> P -> T, then one write-back (4 passes, was 6; same sums). LKRL_NOTCH2: separable.
  auto notch = [&](int pl, uint32_t planes = 1) {
    if (notch2) {
      for (int sm = 0; sm < 3; ++sm) { run(10, pl, sm, planes); run(11, pl, sm, planes); }
      return;
    }
    run(12, pl, 0, planes);
    run(14, pl, 1, planes);
    run(12, pl, 2, planes);
    run(13, pl, 2, planes);
  };
  gpu_->begin();
  if (fa.notch) {
    run(0); run(1); run(2);
    notch(1, 3);
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
