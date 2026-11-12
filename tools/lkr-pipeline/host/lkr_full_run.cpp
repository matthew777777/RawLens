// SPDX-License-Identifier: Apache-2.0
// Desktop full-frame / region run through the same MergeFull path the app uses.
//   lkr_full_run BURST_DIR OUT.dng [--s 1 --tile 512 --overlap 32 --crop W H --frames K]
#include <dirent.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "lkr_full.h"
#include "lkr_pack.h"
#include "lkr_tuning.h"
#include "srp_dng.h"

int main(int argc, char** argv) {
  if (argc < 3) return 2;
  std::string in = argv[1], outp = argv[2];
  lkr::FullParams prm = lkr::AppDefaults();
  bool dct_prior = false;
  bool repeat_reference = false;
  int frames_max = 0;
  int reference = 0;
  int pack_bits = 0;
  bool profile = false;
  for (int i = 3; i < argc; ++i) {
    std::string k = argv[i];
    auto nx = [&] { return std::atoi(argv[++i]); };
    if (k == "--s") prm.s = nx(); else if (k == "--tile") prm.tile = nx();
    else if (k == "--overlap") prm.overlap = nx();
    else if (k == "--frames") frames_max = nx();
    else if (k == "--crop") { prm.crop_w = nx(); prm.crop_h = nx(); }
    else if (k == "--at") { prm.crop_x = nx(); prm.crop_y = nx(); }
    else if (k == "--pgain") { prm.solve.prior_gain = float(std::atof(argv[++i])); prm.auto_look = false; }
    else if (k == "--grain") { prm.solve.grain = float(std::atof(argv[++i])); prm.auto_look = false; }
    else if (k == "--repeat-reference") repeat_reference = nx() != 0;
    else if (k == "--prior") dct_prior = nx() != 0;   // 0 none (default), 1 DCT x-step
    else if (k == "--dluma") prm.solve.dct_luma = float(std::atof(argv[++i]));
    else if (k == "--dchroma") prm.solve.dct_chroma = float(std::atof(argv[++i]));
    else if (k == "--ddark") prm.solve.dct_dark_cap = float(std::atof(argv[++i]));
    else if (k == "--ddl") prm.solve.dct_dark_level = float(std::atof(argv[++i]));
    else if (k == "--ddy") prm.solve.dct_dark_luma = float(std::atof(argv[++i]));
    else if (k == "--iterations") {
      prm.solve.T = nx(); if (prm.solve.T < 2) return 2;
      prm.solve.prior_at.clear();
      for (int t = 1; t < prm.solve.T; t += 2) prm.solve.prior_at.push_back(t);
    }
    else if (k == "--auto-grain") prm.look_day_grain = prm.look_night_grain = float(std::atof(argv[++i]));
    else if (k == "--auto-prior") prm.look_day_prior = prm.look_night_prior = float(std::atof(argv[++i]));
    else if (k == "--refine") prm.solve.refine_motion = nx() != 0;
    else if (k == "--mean") prm.solve.mean_preserve = nx();
    else if (k == "--reference") reference = nx();
    else if (k == "--profile") profile = nx() != 0;
    else if (k == "--holdout") prm.holdout = nx();   // frame index (after --reference / --frames) kept out of the merge
    else if (k == "--smax") prm.smax_x = float(std::atof(argv[++i]));
    else if (k == "--notch") prm.solve.notch = nx() != 0;
    else if (k == "--workers") prm.workers = nx();
    else if (k == "--robust") prm.solve.robust = nx() != 0;
    else if (k == "--rk") prm.solve.robust_k = prm.robust_k_day = prm.robust_k_night = float(std::atof(argv[++i]));
    else if (k == "--rpow") prm.solve.robust_pow = prm.robust_pow_day = prm.robust_pow_night = float(std::atof(argv[++i]));
    else if (k == "--rstart") prm.solve.robust_start = nx();
    else if (k == "--tau") prm.solve.robust_tau = float(std::atof(argv[++i]));
    else if (k == "--tk") prm.solve.temporal_k = float(std::atof(argv[++i]));
    else if (k == "--tdet") prm.solve.temporal_detail = float(std::atof(argv[++i]));
    else if (k == "--tdw") prm.solve.temporal_dark_wide = nx() != 0;
    else if (k == "--tdl") prm.solve.temporal_dark_level = float(std::atof(argv[++i]));
    else if (k == "--sb") prm.solve.shadow_boost = float(std::atof(argv[++i]));
    else if (k == "--rgather") prm.solve.robust_gather = std::atoi(argv[++i]) != 0;
    else if (k == "--quad") prm.solve.quad = std::atoi(argv[++i]) != 0;
    else if (k == "--mev") prm.solve.motion_every = std::atoi(argv[++i]);
    else if (k == "--pack") prm.solve.pack_taps = std::atoi(argv[++i]) != 0;
    else if (k == "--eqday") prm.equalize_day = float(std::atof(argv[++i]));
    else if (k == "--sublk") prm.sub_lk = std::atoi(argv[++i]);
    else if (k == "--sc") prm.solve.shadow_chroma = float(std::atof(argv[++i]));
    else if (k == "--ttau") prm.solve.temporal_tau = float(std::atof(argv[++i]));
    else if (k == "--eq") prm.solve.equalize_noise = nx() != 0;
    else if (k == "--local") prm.solve.local_block = nx();
    else if (k == "--bsearch") prm.block_search = nx() != 0;
    else if (k == "--stage-bits") pack_bits = nx();   // stage the frames packed at this many bits (lkr_pack.h), as the app does
    else if (k == "--fallback") prm.solve.dct_fallback = nx() != 0;
    else if (k == "--paper") prm.paper_parity = nx() != 0;
    else if (k == "--spread") prm.solve.robust_spread = nx();
    else if (k == "--tilelk") prm.tile_lk = nx() != 0;
    else if (k == "--mhc") prm.solve.mhc_init = nx() != 0;
    else if (k == "--llam") { prm.solve.local_lambda = float(std::atof(argv[++i])); prm.auto_local_lambda = false; }
  }
  std::vector<std::string> paths;
  if (DIR* d = opendir(in.c_str())) {
    while (dirent* e = readdir(d)) {
      std::string n = e->d_name;
      if (n.size() > 4 && n.substr(n.size() - 4) == ".dng") paths.push_back(in + "/" + n);
    }
    closedir(d);
  }
  std::sort(paths.begin(), paths.end());
  if (reference < 0 || reference >= int(paths.size())) return 2;
  std::rotate(paths.begin(), paths.begin() + reference, paths.begin() + reference + 1);
  if (frames_max > 0 && int(paths.size()) > frames_max) paths.resize(frames_max);
  if (repeat_reference && !paths.empty()) std::fill(paths.begin(), paths.end(), paths.front());
  auto t0 = std::chrono::steady_clock::now();
  srp::SrpDngMeta m;
  if (paths.empty() || srp::SrpReadDngMeta(paths[0].c_str(), &m) != srp::DngReadStatus::kOk) return 1;
  std::vector<std::vector<uint16_t>> px(paths.size(), std::vector<uint16_t>(size_t(m.width) * m.height));
  std::vector<lkr::RawFrame> fr;
  for (size_t k = 0; k < paths.size(); ++k) {
    if (srp::SrpReadDngPixels(paths[k].c_str(), px[k].data(), 0, 0, 0, 0) != srp::DngReadStatus::kOk) return 1;
    fr.push_back({px[k].data(), int(m.width)});
  }
  std::vector<std::vector<uint8_t>> packed;
  if (pack_bits > 0 && pack_bits < 16) {
    packed.resize(fr.size());
    for (size_t k = 0; k < fr.size(); ++k) {
      packed[k].resize(lkr::PackedBytes(int(m.width), int(m.height), pack_bits));
      if (!lkr::PackRows(px[k].data(), int(m.width), int(m.height), int(m.width), pack_bits, packed[k].data())) {
        std::fprintf(stderr, "frame %zu has samples wider than %d bits\n", k, pack_bits);
        return 1;
      }
      std::vector<uint16_t>().swap(px[k]);
      fr[k] = {reinterpret_cast<const uint16_t*>(packed[k].data()), int(m.width), pack_bits};
    }
    std::printf("staged %zu frames at %d bits: %.1f MB each (16-bit: %.1f MB)\n", fr.size(), pack_bits,
                packed[0].size() / 1048576.0, double(m.width) * m.height * 2 / 1048576.0);
  }
  const double ms_load = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  const int cfa[4] = {m.cfa[0], m.cfa[1], m.cfa[2], m.cfa[3]};
  // DNG BlackLevel is in CFA cell order, already what MergeFull expects.
  // Averaging or rounding fractional offsets leaves a shadow colour cast.
  float black[4];
  for (int c = 0; c < 4; ++c) black[c] = float(m.black_level_precise[c]);
  std::array<float, 3> wb{1, 1, 1};
  if (m.has_as_shot_neutral) wb = {float(m.as_shot_neutral[1] / m.as_shot_neutral[0]), 1.0f, float(m.as_shot_neutral[1] / m.as_shot_neutral[2])};
  lkr::Gpu gpu;
  if (!gpu.ok()) return 1;
  gpu.profile(profile);
  prm.solve.dct_prior = dct_prior;
  const lkr::PriorFactory make;
  std::vector<float> rgb;
  int ow = 0, oh = 0;
  lkr::FullStats st;
  std::string err;
  if (!lkr::MergeFull(&gpu, fr, int(m.width), int(m.height), cfa, black, float(m.white_level), wb, prm, make, &rgb,
                      &ow, &oh, &st, &err)) {
    std::fprintf(stderr, "merge failed: %s\n", err.c_str());
    return 1;
  }
  for (float& v : rgb) v = std::clamp(v, 0.0f, 1.0f);
  srp::DngColorMeta color;
  std::memcpy(color.color_matrix1, m.color_matrix1, sizeof(color.color_matrix1));
  color.has_color_matrix1 = m.color_matrix_present;
  std::memcpy(color.as_shot_neutral, m.as_shot_neutral, sizeof(color.as_shot_neutral));
  color.has_as_shot_neutral = m.has_as_shot_neutral;
  // Keep the source's EXIF orientation (tag 274), as sr-pipeline's writer does,
  // plus Make/Model for raw converters.
  int orient = 0;
  srp::SrpReadDngOrientation(paths[0].c_str(), &orient);
  const uint16_t o16 = uint16_t(orient > 0 ? orient : 1);
  std::vector<srp::DngField> fields;
  srp::DngField fo;
  fo.tag = 274; fo.type = 3; fo.count = 1;
  fo.payload = reinterpret_cast<const uint8_t*>(&o16); fo.payload_size = 2;
  fields.push_back(fo);
  std::string mk = m.make, md = m.model;
  if (!mk.empty()) {
    srp::DngField f; f.tag = 271; f.type = 2; f.count = uint32_t(mk.size() + 1);
    f.payload = reinterpret_cast<const uint8_t*>(mk.c_str()); f.payload_size = mk.size() + 1; fields.push_back(f);
  }
  if (!md.empty()) {
    srp::DngField f; f.tag = 272; f.type = 2; f.count = uint32_t(md.size() + 1);
    f.payload = reinterpret_cast<const uint8_t*>(md.c_str()); f.payload_size = md.size() + 1; fields.push_back(f);
  }
  srp::DngWriteLinearRgb16(outp.c_str(), ow, oh, rgb.data(), fields.data(), fields.size(), &color, &err);
  FILE* f = std::fopen((outp + ".f32").c_str(), "wb");
  std::fwrite(rgb.data(), 4, rgb.size(), f);
  std::fclose(f);
  std::printf("%zu frames %ux%u -> %dx%d s=%d tiles %d  sigma %.5f a %.3g b %.3g  prior %s%s\n", fr.size(), m.width,
              m.height, ow, oh, prm.s, st.tiles, st.sigma, st.a, st.b, dct_prior ? "dct" : "none", "");
  std::printf("reference frame %d (sharpest)\n", st.ref_index);
  std::printf("look: prior %.2f grain %.2f%s  local lambda %.2f\n", st.prior_gain, st.grain,
              prm.auto_look ? " (auto from noise)" : "", st.local_lambda);
  std::printf("ms: load %.0f noise %.0f coarseLK %.0f tiles %.0f (gpu %.0f prior %.0f, %d calls) TOTAL(merge) %.0f\n",
              ms_load, st.ms_noise, st.ms_coarse, st.ms_tiles, st.ms_gpu, st.ms_prior, st.prior_calls, st.ms_total);
  std::printf("workers %d, tile sums ms: solve %.0f (gpu %.0f prior %.0f init %.0f finish %.0f other %.0f) prep %.0f (load %.0f) blend %.0f | setup %.0f wait %.0f\n",
              st.workers, st.ms_solve, st.ms_gpu, st.ms_prior, st.ms_init, st.ms_finish,
              st.ms_solve - st.ms_gpu - st.ms_prior - st.ms_init - st.ms_finish, st.ms_prep, st.ms_load, st.ms_blend, st.ms_setup, st.ms_wait);
  if (!st.gpu_profile.empty()) std::printf("gpu profile: %s\n", st.gpu_profile.c_str());
  if (!st.holdout.empty()) {
    // Held-out frame: residual energy against the expected noise, per 16 x 16 block of its samples.
    const int hw = st.holdout_w, hh = st.holdout_h, B = 16;
    const size_t plane = size_t(hw) * hh;
    const float* e = st.holdout.data();
    const float* v = e + plane;
    const float* sg = e + 2 * plane;
    std::vector<float> ratio;
    double all = 0, alln = 0, bias2 = 0;
    for (int by = 0; by + B <= hh; by += B)
      for (int bx = 0; bx + B <= hw; bx += B) {
        double se = 0, sv = 0, ss = 0;
        for (int i = 0; i < B; ++i)
          for (int j = 0; j < B; ++j) {
            const size_t o = size_t(by + i) * hw + bx + j;
            se += double(v[o]) * e[o]; sv += v[o]; ss += double(v[o]) * sg[o];
          }
        all += se; alln += sv;
        if (sv < 0.75 * B * B) continue;   // mostly clipped or outside the image
        ratio.push_back(float(se / sv));
        bias2 += (ss / sv) * (ss / sv);
      }
    FILE* hf = std::fopen((outp + ".holdout.f32").c_str(), "wb");
    if (hf) { std::fwrite(st.holdout.data(), 4, st.holdout.size(), hf); std::fclose(hf); }
    if (ratio.empty() || alln <= 0) {
      std::printf("holdout frame %d: no usable samples\n", prm.holdout);
    } else {
      std::sort(ratio.begin(), ratio.end());
      auto pct = [&](double q) { return ratio[size_t(q * (ratio.size() - 1))]; };
      size_t over15 = 0, over2 = 0;
      for (float r : ratio) { over15 += r > 1.5f; over2 += r > 2.0f; }
      const int merged = int(fr.size()) - 1;
      std::printf("holdout frame %d vs %d merged: residual / noise %.3f (all frames used: 1 .. %.3f)\n", prm.holdout,
                  merged, all / alln, 1.0 + 1.0 / merged);
      std::printf("holdout blocks (%d px, %zu): p10 %.3f p50 %.3f p90 %.3f  >1.5: %.1f%%  >2: %.1f%%  bias rms %.3f (noise alone %.3f)\n",
                  B, ratio.size(), pct(0.10), pct(0.50), pct(0.90), 100.0 * over15 / ratio.size(),
                  100.0 * over2 / ratio.size(), std::sqrt(bias2 / ratio.size()), 1.0 / B);
      std::printf("holdout maps: 3 x %d x %d float32 -> %s.holdout.f32\n", hh, hw, outp.c_str());
    }
  }
  return 0;
}
