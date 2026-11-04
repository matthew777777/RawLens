// SPDX-License-Identifier: GPL-3.0-or-later
// Desktop full-frame / region run through the same MergeFull path the app uses.
//   lkr_full_run BURST_DIR OUT.dng [--s 1 --tile 512 --overlap 32 --crop W H --fp16 0 --frames K --model DIR]
#include <dirent.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "lkr_full.h"
#include "lkr_prior.h"
#include "srp_dng.h"

int main(int argc, char** argv) {
  if (argc < 3) return 2;
  std::string in = argv[1], outp = argv[2], model = "build/models";
  lkr::FullParams prm;
  prm.solve.T = 16;
  prm.solve.z_steps = 3;
  for (int t = 1; t < 16; t += 2) prm.solve.prior_at.push_back(t);
  bool fp16 = false;
  int frames_max = 0;
  for (int i = 3; i < argc; ++i) {
    std::string k = argv[i];
    auto nx = [&] { return std::atoi(argv[++i]); };
    if (k == "--s") prm.s = nx(); else if (k == "--tile") prm.tile = nx();
    else if (k == "--overlap") prm.overlap = nx(); else if (k == "--fp16") fp16 = nx() != 0;
    else if (k == "--frames") frames_max = nx(); else if (k == "--model") model = argv[++i];
    else if (k == "--crop") { prm.crop_w = nx(); prm.crop_h = nx(); }
    else if (k == "--at") { prm.crop_x = nx(); prm.crop_y = nx(); }
    else if (k == "--pgain") { prm.solve.prior_gain = float(std::atof(argv[++i])); prm.auto_look = false; }
    else if (k == "--grain") { prm.solve.grain = float(std::atof(argv[++i])); prm.auto_look = false; }
    else if (k == "--notch") prm.solve.notch = nx() != 0;
    else if (k == "--workers") prm.workers = nx();
    else if (k == "--robust") prm.solve.robust = nx() != 0;
    else if (k == "--rk") prm.solve.robust_k = prm.robust_k_day = prm.robust_k_night = float(std::atof(argv[++i]));
    else if (k == "--rpow") prm.solve.robust_pow = prm.robust_pow_day = prm.robust_pow_night = float(std::atof(argv[++i]));
    else if (k == "--rstart") prm.solve.robust_start = nx();
    else if (k == "--tau") prm.solve.robust_tau = float(std::atof(argv[++i]));
    else if (k == "--tk") prm.solve.temporal_k = float(std::atof(argv[++i]));
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
  if (frames_max > 0 && int(paths.size()) > frames_max) paths.resize(frames_max);
  auto t0 = std::chrono::steady_clock::now();
  srp::SrpDngMeta m;
  if (paths.empty() || srp::SrpReadDngMeta(paths[0].c_str(), &m) != srp::DngReadStatus::kOk) return 1;
  std::vector<std::vector<uint16_t>> px(paths.size(), std::vector<uint16_t>(size_t(m.width) * m.height));
  std::vector<lkr::RawFrame> fr;
  for (size_t k = 0; k < paths.size(); ++k) {
    if (srp::SrpReadDngPixels(paths[k].c_str(), px[k].data(), 0, 0, 0, 0) != srp::DngReadStatus::kOk) return 1;
    fr.push_back({px[k].data(), int(m.width)});
  }
  const double ms_load = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  const int cfa[4] = {m.cfa[0], m.cfa[1], m.cfa[2], m.cfa[3]};
  // srp black_level is R,G1,B,G2 plane order; MergeFull wants per CFA cell (row-major).
  float black[4];
  for (int c = 0; c < 4; ++c) black[c] = float(m.black_level[0] + m.black_level[1] + m.black_level[2] + m.black_level[3]) / 4.0f;
  std::array<float, 3> wb{1, 1, 1};
  if (m.has_as_shot_neutral) wb = {float(m.as_shot_neutral[1] / m.as_shot_neutral[0]), 1.0f, float(m.as_shot_neutral[1] / m.as_shot_neutral[2])};
  lkr::Gpu gpu;
  if (!gpu.ok()) return 1;
  lkr::FfdnetGatPrior ff;
  lkr::FfdnetGatPrior::Options o;
  o.fp16 = fp16;
  if (!ff.load(model + "/ffdnet.param", model + "/ffdnet.bin", o)) return 1;
  lkr::PriorFactory make = [&](float a, float b, float sig, const std::array<float, 3>& w) -> lkr::PriorFn {
    ff.setNoise(a, b, sig, w);
    return [&](const float* z, float* x, int H, int W, float sg, const float* ns) { return ff(z, x, H, W, sg, ns); };
  };
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
              m.height, ow, oh, prm.s, st.tiles, st.sigma, st.a, st.b, ff.backend().c_str(), fp16 ? " fp16" : "");
  std::printf("reference frame %d (sharpest)\n", st.ref_index);
  std::printf("look: prior %.2f grain %.2f%s  local lambda %.2f\n", st.prior_gain, st.grain,
              prm.auto_look ? " (auto from noise)" : "", st.local_lambda);
  std::printf("ms: load %.0f noise %.0f coarseLK %.0f tiles %.0f (gpu %.0f prior %.0f, %d calls) TOTAL(merge) %.0f\n",
              ms_load, st.ms_noise, st.ms_coarse, st.ms_tiles, st.ms_gpu, st.ms_prior, st.prior_calls, st.ms_total);
  return 0;
}
