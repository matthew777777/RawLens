// SPDX-License-Identifier: Apache-2.0
// lkrl::Solver: LK-R's Solver::solve signature (lkrl_api.h) on top of LiteSolver, so the copied
// MergeFull (tiling, noise fit, coarse / tile / sub-tile LK, block search, blend) is unchanged.
// After the GPU solve it applies LK-R's no-prior finishing: x = z, FinishLook(z, x, noise_scale)
// (Bayer notch, stronger where frames were rejected; clip halo; highlight desaturation).
#include <chrono>
#include <future>
#include <cstdio>
#include <cstdlib>

#include "lkrl_prior.h"
#include "lkrl_api.h"

namespace lkrl {

namespace {
double now_ms() {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
}  // namespace

Solver::Solver(Gpu* gpu) : gpu_(gpu), lite_(gpu) { ok_ = lite_.ok(); }
Solver::~Solver() = default;
std::string Solver::profileNames() const { return lite_.profileNames(); }

struct PendingTile {
  SolveParams prm;
  std::vector<float> z, xd, nscale, delta;
  bool dct = false, prior_ok = false, done_sync = false;
  int H = 0, W = 0;
  double t0 = 0.0;
  SolveStats S;
  std::vector<float> x_sync;   // finished in Begin (motion look reads the frames buffer)
  std::future<void> prior;
  PriorStats pst; std::string perr;
};
// Wait for the tile's NPU prior (if started) and apply its correction to the DCT-cleaned copy too.
void waitPrior(PendingTile& T) {
  if (!T.prior.valid()) return;
  const double tw = now_ms();
  T.prior.wait();
  T.prior = std::future<void>();
  const double waited = now_ms() - tw;
  if (T.prior_ok) {
    if (T.dct && T.xd.size() == T.z.size()) for (size_t i = 0; i < T.z.size(); ++i) T.xd[i] = std::max(T.xd[i] + T.delta[i], 0.0f);
    if (std::getenv("LKRL_PRIOR_LOG")) std::fprintf(stderr, "lkr-lite prior: %dx%d on %s %.1f ms (waited %.1f ms)\n", T.W, T.H, T.pst.device.c_str(), T.pst.ms, waited);
  } else {
    static bool warned = false;
    if (!warned) { std::fprintf(stderr, "lkr-lite prior skipped: %s\n", T.perr.c_str()); warned = true; }
  }
}

void PendingDeleter::operator()(PendingTile* p) const {
  if (p && p->prior.valid()) p->prior.wait();
  delete p;
}

bool Solver::solve(const float* frames, int K, int h, int w, float* p, const SolveParams& prm,
                   const PriorFn& prior, std::vector<float>* x_out, SolveStats* st) {
  PendingPtr pend;
  if (!solveBegin(frames, K, h, w, p, prm, prior, &pend)) return false;
  return solveEnd(&pend, x_out, st);
}

bool Solver::solveBegin(const float* frames, int Kall, int h, int w, float* p, const SolveParams& prm,
                        const PriorFn& prior, PendingPtr* pend) {
  const double t0 = now_ms();
  pend->reset(new PendingTile);
  PendingTile& T = **pend;
  T.prm = prm; T.t0 = t0;
  SolveStats& S = T.S;
  S = SolveStats{};
  if (prior || (prm.dct_prior && prm.dct_aperture > 0)) {
    std::fprintf(stderr, "lkr-lite: only the plain DCT prior is implemented\n");
    return false;
  }
  const int K = prm.holdout ? Kall - 1 : Kall;   // the held-out frame never enters the merge
  if (K < 1) return false;
  LiteParams lp;
  lp.s = prm.s;
  lp.debug_prefix = prm.debug_prefix;
  lp.noise_a = prm.noise_a;
  lp.noise_b = prm.noise_b;
  lp.clip = prm.clip;
  lp.robust_k = prm.robust ? prm.robust_k : 0.0f;
  lp.robust_pow = prm.robust_pow;
  lp.robust_tau = prm.robust_tau;
  lp.robust_spread = prm.robust_spread;
  lp.guide = prm.guide_confidence;
  lp.guide_residual = prm.guide_residual;
  lp.prior_gpu = PriorEnabled() && !PriorNpuAvailable();   // Network on, no NPU: Vulkan fallback in the solver
  lp.robust = prm.robust;
  lp.temporal_k = prm.temporal_k; lp.temporal_tau = prm.temporal_tau; lp.temporal_detail = prm.temporal_detail;
  lp.temporal_dark_wide = prm.temporal_dark_wide; lp.temporal_dark_level = prm.temporal_dark_level;
  if (const char* v = std::getenv("LKRL_WONCE")) lp.lkr_weights_once = std::atoi(v) != 0;
  if (const char* v = std::getenv("LKRL_LKRW")) lp.lkr_weights = std::atoi(v) != 0;
  lp.local_lambda = prm.local_lambda;   // MergeFull already applied the noise scaling
  lp.local_floor = prm.local_floor;
  lp.auto_local_lambda = false;
  lp.profile = gpu_->profiling();
  // Research overrides (desktop A/B): LKRL_SCHEDULE, LKRL_COV_FLOOR, LKRL_DAMP, LKRL_MHC.
  if (const char* v = std::getenv("LKRL_SCHEDULE")) lp.schedule = v;
  if (const char* v = std::getenv("LKRL_STEPS")) lp.lkr_steps = float(std::atof(v));   // total over the schedule
  if (const char* v = std::getenv("LKRL_L")) lp.lkr_l = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_DAMP")) lp.jacobi_damp = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_MHC")) lp.mhc_mix = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_COVR")) lp.cov_smooth = std::atoi(v);
  if (const char* v = std::getenv("LKRL_GMODE")) lp.guide_mode = std::atoi(v);
  // DCT prior: one x-step on the final z at LK-R's last-iteration level (sigma_end * prior_gain).
  if (const char* v = std::getenv("LKRL_CAVG")) lp.chroma_from_avg = std::atoi(v);
  // DCT look (2026-11-17, one pass at the end on the merged noise):
  //  - always (base look): colour noise removed everywhere, following the measured merged noise
  //    (k dct_chroma = 2: no edge bleeding; strong only where colour noise is visible);
  //  - dct_prior ("DCT denoise" on): + noise-following luma: shadows keep at most dct_grain x
  //    mid-grey's relative grain, mid-tones / highlights untouched. Off = luma unchanged (DCT round trip, ~1e-7).
  //  --fallback 0 / LKRL_FB=0 = no DCT pass at all. LK-R's old full-image x-step is gone (no gain vs truth).
  const char* fb = std::getenv("LKRL_FB");
  if (fb ? std::atoi(fb) != 0 : prm.dct_fallback) {
    lp.dct = true; lp.dct_fallback = true; lp.dct_chroma_all = true;
    lp.dct_sigma = prm.sigma_noise / std::sqrt(float(K));
  }
  lp.dct_chroma = prm.dct_chroma;
  lp.dct_dark_level = prm.dct_dark_level; lp.dct_dark_cap = 1.0f; lp.dct_dark_luma = prm.dct_dark_luma;
  for (int c = 0; c < 3; ++c) lp.wb[c] = prm.wb[c];
  lp.dct_grain = 0.0f; lp.dct_luma = 0.0f;
  if (lp.dct && prm.dct_prior) { lp.dct_grain = prm.dct_grain; lp.dct_luma = prm.dct_luma; lp.dct_luma_floor = prm.dct_luma_floor; lp.dct_luma_cap = prm.dct_luma_cap; }
  lp.dct_grain_rel = prm.dct_grain_rel; lp.dct_grain_rho = prm.dct_grain_rho; lp.dct_chroma_norm = prm.dct_chroma_norm; lp.dct_grain_tilt = prm.dct_grain_tilt; lp.dct_grain_sub = prm.dct_grain_sub;
  if (const char* v = std::getenv("LKRL_GSUB")) lp.dct_grain_sub = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_GTILT")) lp.dct_grain_tilt = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_GREL")) lp.dct_grain_rel = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_GRHO")) lp.dct_grain_rho = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_CNORM")) lp.dct_chroma_norm = std::atoi(v) != 0;
  if (const char* v = std::getenv("LKRL_LCAP")) lp.dct_luma_cap = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_LFLOOR")) lp.dct_luma_floor = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_GRAIN")) lp.dct_grain = float(std::atof(v));
  if (lp.dct_grain > 0.0f && lp.dct_luma <= 0.0f) lp.dct_luma = prm.dct_luma;
  if (const char* v = std::getenv("LKRL_DSIG")) lp.dct_sigma *= float(std::atof(v));
  if (const char* v = std::getenv("LKRL_DLUMA")) lp.dct_luma = float(std::atof(v));
  if (const char* v = std::getenv("LKRL_DCHROMA")) lp.dct_chroma = float(std::atof(v));
  std::vector<float> pv(p, p + size_t(K) * 6), dloc;
  const int nb = ((w + 15) / 16) * ((h + 15) / 16);
  if (prm.local_init && prm.local_block > 0) dloc.assign(prm.local_init, prm.local_init + size_t(K) * nb * 2);
  std::vector<float> z, xd;
  LiteStats ls;
  std::string err;
  std::vector<float> nscale, rr, wt;
  // Motion look is off in Lite (2026-11-14: with the hybrid merge + lost-frames cleanup it changed real
  // images by ~0.01 / 255 on average and cost CPU time); LKRL_MLOOK=1 restores it for comparisons.
  static const bool mlook = std::getenv("LKRL_MLOOK") && std::atoi(std::getenv("LKRL_MLOOK")) != 0;
  const bool msmooth = mlook && prm.motion_smooth > 0.0f && prm.robust && prm.noise_b > 0 && K > 1 && !prm.holdout;
  if (!lite_.solve(frames, K, h, w, pv, dloc, lp, &z, &ls, &err, &nscale, msmooth ? &rr : nullptr,
                   (msmooth || prm.debug_maps) ? &wt : nullptr, lp.dct ? &xd : nullptr)) {
    std::fprintf(stderr, "lkr-lite: %s\n", err.c_str());
    return false;
  }
  std::copy(pv.begin(), pv.end(), p);
  // Self-supervised training data (research/prior/selfsup): in holdout mode with tracing, also save the
  // held-out frame's raw tile and its motion to the reference (front-end affine + 16 px block offsets),
  // so the merged image can be re-sampled at that frame's positions and Bayer pattern.
  if (prm.holdout && !prm.debug_prefix.empty() && Kall > K) {
    auto dump = [&](const char* tag, const void* d, size_t bytes) {
      if (FILE* f = std::fopen((prm.debug_prefix + "-" + tag).c_str(), "wb")) { std::fwrite(d, 1, bytes, f); std::fclose(f); }
    };
    dump("holdout-raw.f32", frames + size_t(K) * h * w, size_t(h) * w * 4);
    dump("holdout-affine.f32", p + size_t(K) * 6, 6 * 4);
    const int nbh = ((w + 15) / 16) * ((h + 15) / 16);
    if (prm.local_init && prm.local_block == 16) dump("holdout-local.f32", prm.local_init + size_t(K) * nbh * 2, size_t(nbh) * 2 * 4);
    const int32_t meta[4] = {h, w, prm.s, prm.local_init ? 16 : 0};
    dump("holdout-meta.i32", meta, sizeof(meta));
  }
  S.gpu_ms = ls.ms_total - ls.ms_host;
  S.submits = ls.submits;
  S.prior_ms = ls.ms_prior;
  S.prior_calls = ls.prior_calls;
  const int H = prm.s * h, W = prm.s * w;
  // Learned post-fit prior (opt-in, LKRL_PRIOR=1 / debug.lkr.prior=1): corrects Lite's merged image before
  // finishing, on the NPU only (lkrl_prior.cpp). Started asynchronously here: the caller fits its next
  // tile on the GPU meanwhile, solveEnd waits. With the DCT cleanup, its output gets the same correction.
  T.dct = lp.dct; T.H = H; T.W = W;
  T.z = std::move(z); T.xd = std::move(xd); T.nscale = std::move(nscale);
  if (const char* dump = std::getenv("LKRL_DUMP_NS")) {   // diagnostics: noise map handed to FinishLook
    FILE* f = std::fopen(dump, "wb");
    if (f) { std::fwrite(T.nscale.data(), 4, T.nscale.size(), f); std::fclose(f); }
  }
  if (prm.debug_maps && wt.size() == size_t(K) * h * w) {   // plane 0 = effective frames, as LK-R
    S.dbg.assign(size_t(kDebugMaps) * H * W, 0.0f);
    for (int Y = 0; Y < H; ++Y)
      for (int X = 0; X < W; ++X) {
        const int i = std::min(Y / prm.s, h - 1), j = std::min(X / prm.s, w - 1);
        float eff = 1.0f;
        for (int k = 1; k < K; ++k) eff += wt[(size_t(k) * h + i) * w + j];
        S.dbg[size_t(Y) * W + X] = eff;
      }
  }
  if (PriorEnabled() && PriorNpuAvailable()) {
    PendingTile* tp = &T;
    if (tp->dct) tp->delta.assign(tp->z.size(), 0.0f);
    tp->prior = std::async(std::launch::async, [tp]() {
      tp->prior_ok = PriorApply(tp->z.data(), tp->H, tp->W, tp->prm.noise_a, tp->dct ? tp->delta.data() : nullptr, &tp->pst, &tp->perr);
    });
  }
  if (msmooth) {   // motion look reads the frames buffer, which the caller reuses: do it now (before finishing)
    waitPrior(T);
    T.x_sync = T.dct ? T.xd : T.z;
    MotionLook(frames, K, h, w, rr.data(), wt.data(), pv.data(), dloc.data(), prm, T.z.data(), T.x_sync.data());
    T.done_sync = true;   // solveEnd: x = x_sync, then finishing
  }
  return true;
}

bool Solver::solveEnd(PendingPtr* pend, std::vector<float>* x_out, SolveStats* st) {
  PendingTile& T = **pend;
  const SolveParams& prm = T.prm;
  SolveStats local;
  SolveStats& S = st ? *st : local;
  const int H = T.H, W = T.W;
  waitPrior(T);
  std::vector<float>& z = T.z;
  if (T.done_sync) *x_out = std::move(T.x_sync);
  else if (T.dct) *x_out = std::move(T.xd); else *x_out = z;   // no prior: x = z (LK-R 'none')
  const double tf = now_ms();
  // GPU finish for the default options (fallback_chroma / shadow stay on the CPU path); LKRL_FIN_CPU=1.
  static const bool fin_cpu = std::getenv("LKRL_FIN_CPU") != nullptr;
  bool fin_done = false;
  if (!fin_cpu && prm.fallback_chroma <= 0.0f) {
    LiteSolver::FinishArgs fa{{prm.wb[0], prm.wb[1], prm.wb[2]}, prm.clip, prm.grain, prm.equalize_strength,
                              prm.notch, prm.highlight_desat};
    std::string ferr;
    fin_done = lite_.finish(z.data(), x_out, H, W, fa, T.nscale.empty() ? nullptr : &T.nscale, &ferr);
    if (!fin_done) std::fprintf(stderr, "lkr-lite: gpu finish failed (%s), CPU path\n", ferr.c_str());
  }
  if (!fin_done) FinishLook(z.data(), x_out->data(), H, W, prm, T.nscale.empty() ? nullptr : T.nscale.data(), nullptr);
  T.S.finish_ms = now_ms() - tf;
  T.S.total_ms = now_ms() - T.t0;
  S = std::move(T.S);
  pend->reset();
  return true;
}

}  // namespace lkrl
