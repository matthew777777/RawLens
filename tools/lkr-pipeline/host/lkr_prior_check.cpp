// SPDX-License-Identifier: GPL-3.0-or-later
// ncnn FFDNet core vs torch reference (python/lkr/export_ncnn.py ref_in/ref_out).
//   lkr_prior_check MODEL_DIR [fp16]
#include <chrono>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "lkr_prior.h"
#include "npy.h"

int main(int argc, char** argv) {
  if (argc < 2) return 2;
  const std::string dir = argv[1];
  lkr::FfdnetGatPrior pr;
  lkr::FfdnetGatPrior::Options o;
  o.fp16 = argc > 2 && std::string(argv[2]) == "fp16";
  if (!pr.load(dir + "/ffdnet.param", dir + "/ffdnet.bin", o)) { std::fprintf(stderr, "load failed\n"); return 1; }
  srp_proof::NpyArray in, ref;
  if (!srp_proof::LoadNpy((dir + "/ref_in.npy").c_str(), &in) || !srp_proof::LoadNpy((dir + "/ref_out.npy").c_str(), &ref)) return 1;
  const int H2 = int(in.shape[1]), W2 = int(in.shape[2]);
  std::vector<float> out(ref.data.size());
  if (!pr.runCore(in.data.data(), out.data(), H2, W2)) { std::fprintf(stderr, "run failed\n"); return 1; }
  double mx = 0, se = 0, pk = 0;
  for (size_t i = 0; i < out.size(); ++i) {
    const double d = double(out[i]) - ref.data[i];
    mx = std::max(mx, std::fabs(d)); se += d * d; pk = std::max(pk, std::fabs(double(ref.data[i])));
  }
  const double rmse = std::sqrt(se / out.size());
  std::printf("%s %s: max |d| %.3g  rms %.3g  PSNR %.1f dB\n", pr.backend().c_str(), o.fp16 ? "fp16" : "fp32", mx, rmse,
              20 * std::log10(pk / rmse));
  return 0;
}
