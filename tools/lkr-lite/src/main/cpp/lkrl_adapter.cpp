// SPDX-License-Identifier: Apache-2.0
// lkrl::Solver: LK-R's Solver::solve signature (lkrl_api.h) on top of LiteSolver, so the copied
// MergeFull (tiling, noise fit, coarse / tile / sub-tile LK, block search, blend) is unchanged.
// After the GPU solve it applies LK-R's no-prior finishing: x = z, FinishLook(z, x, noise_scale)
// (Bayer notch, stronger where frames were rejected; clip halo; highlight desaturation).
#include <chrono>
#include <cstdio>
#include <cstdlib>

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

bool Solver::solve(const float* frames, int Kall, int h, int w, float* p, const SolveParams& prm,
                   const PriorFn& prior, std::vector<float>* x_out, SolveStats* st) {
  const double t0 = now_ms();
  SolveStats local;
  SolveStats& S = st ? *st : local;
  S = SolveStats{};
  if (prior || (prm.dct_prior && prm.dct_aperture > 0)) {
    std::fprintf(stderr, "lkr-lite: only the plain DCT prior is implemented\n");
    return false;
  }
  const int K = prm.holdout ? Kall - 1 : Kall;   // the held-out frame never enters the merge
  if (K < 1) return false;
  LiteParams lp;
  lp.s = prm.s;
  lp.noise_a = prm.noise_a;
  lp.noise_b = prm.noise_b;
  lp.clip = prm.clip;
  lp.robust_k = prm.robust ? prm.robust_k : 0.0f;
  lp.robust_pow = prm.robust_pow;
  lp.robust_tau = prm.robust_tau;
  lp.robust_spread = prm.robust_spread;
  lp.guide = prm.guide_confidence;
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
  lp.dct = prm.dct_prior;
  lp.dct_sigma = prm.sigma_end * prm.prior_gain;
  // Fallback (lost frames only): LK-R's level = the merged noise sigma / sqrt(K); luma off by default
  // (LK-R: colour only), LKRL_FBLUMA = luma threshold.
  if (!prm.dct_prior && (prm.dct_fallback || std::getenv("LKRL_FB"))) {
    lp.dct = true; lp.dct_fallback = true;
    lp.dct_sigma = prm.sigma_noise / std::sqrt(float(K));
  }
  lp.dct_luma = prm.dct_luma; lp.dct_chroma = prm.dct_chroma;
  lp.dct_dark_level = prm.dct_dark_level; lp.dct_dark_cap = prm.dct_dark_cap; lp.dct_dark_luma = prm.dct_dark_luma;
  for (int c = 0; c < 3; ++c) lp.wb[c] = prm.wb[c];
  if (lp.dct_fallback) lp.dct_luma = std::getenv("LKRL_FBLUMA") ? float(std::atof(std::getenv("LKRL_FBLUMA"))) : 2.0f;
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
  S.gpu_ms = ls.ms_total - ls.ms_host;
  S.submits = ls.submits;
  const int H = prm.s * h, W = prm.s * w;
  if (lp.dct) *x_out = std::move(xd); else *x_out = z;   // no prior: x = z (LK-R 'none')
  if (const char* dump = std::getenv("LKRL_DUMP_NS")) {   // diagnostics: noise map handed to FinishLook
    FILE* f = std::fopen(dump, "wb");
    if (f) { std::fwrite(nscale.data(), 4, nscale.size(), f); std::fclose(f); }
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
  const double tf = now_ms();
  if (msmooth) MotionLook(frames, K, h, w, rr.data(), wt.data(), pv.data(), dloc.data(), prm, z.data(), x_out->data());
  // GPU finish for the default options (fallback_chroma / shadow stay on the CPU path); LKRL_FIN_CPU=1.
  static const bool fin_cpu = std::getenv("LKRL_FIN_CPU") != nullptr;
  bool fin_done = false;
  if (!fin_cpu && prm.fallback_chroma <= 0.0f) {
    LiteSolver::FinishArgs fa{{prm.wb[0], prm.wb[1], prm.wb[2]}, prm.clip, prm.grain, prm.equalize_strength,
                              prm.notch, prm.highlight_desat};
    std::string ferr;
    fin_done = lite_.finish(z.data(), x_out, H, W, fa, nscale.empty() ? nullptr : &nscale, &ferr);
    if (!fin_done) std::fprintf(stderr, "lkr-lite: gpu finish failed (%s), CPU path\n", ferr.c_str());
  }
  if (!fin_done) FinishLook(z.data(), x_out->data(), H, W, prm, nscale.empty() ? nullptr : nscale.data(), nullptr);
  S.finish_ms = now_ms() - tf;
  S.total_ms = now_ms() - t0;
  return true;
}

}  // namespace lkrl
