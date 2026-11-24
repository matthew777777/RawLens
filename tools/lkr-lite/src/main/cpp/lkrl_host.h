// SPDX-License-Identifier: Apache-2.0
#pragma once

// CPU-side stages of LK-Reloaded, mirrors of python/lkr (run.py, lkr.py):
// burst crop loading (RGGB-normalised), OUR OWN noise estimates (never the
// Camera2 / DNG NoiseProfile), and the coarse forward-additive pyramid
// Lucas-Kanade initialisation (paper Sec. 3.4).

#include <array>
#include <cstddef>
#include <string>
#include <vector>

namespace lkrl {

struct Burst {
  int K = 0, h = 0, w = 0;
  std::vector<float> frames;     // K*h*w, [0,1], RGGB phase at (0,0)
  std::array<float, 3> wb{1, 1, 1};  // R,G,B gains, G = 1 (1/AsShotNeutral)
  int x0 = 0, y0 = 0;            // sensor origin of the crop
};

// Reads `crop` x `crop` around (cx, cy) (centre; <0 = image centre) from every
// DNG, picking the origin parity that puts R at (0,0) for the file's CFA.
bool LoadBurstCrop(const std::vector<std::string>& paths, int crop, int cx, int cy, Burst* out,
                   std::string* err);

// Robust scalar noise sigma of one RGGB frame (MAD of a 5-point Laplacian on
// the (0,1) green plane) -- python run.noise_sigma.
float NoiseSigma(const float* raw, int h, int w);

// Poisson-Gaussian fit var = a x + b from one RGGB frame: block Laplacian
// energy, binned by level, bias-corrected fifth percentile, weighted NNLS.
// bins (optional): brightness levels the fit used, its confidence (learned-model evidence weight).
void NoiseAB(const float* raw, int h, int w, float* a, float* b, int* bins = nullptr);

// Grey image (mean of the bilinear demosaic) -- python lkr.grey.
void Grey(const float* raw, int h, int w, float* out);

// Forward-additive affine LK, 3-level pyramid: p with ref(A u + t) ~ mov(u)
// -- python lkr.lk_affine (iters per level 40, stop at |dt| < 1e-3).
std::array<double, 6> LkAffine(const float* ref, const float* mov, int h, int w, int iters = 40,
                               int levels = 3, const std::array<double, 6>* p0 = nullptr);
// The reference side of LkAffine (pyramid and gradients), built once and shared by every frame
// aligned to that reference; results are identical to the float* overloads.
struct LkRef {
  std::vector<std::vector<double>> r, gx, gy;   // per level
  // Lite: (r, gx, gy) interleaved per pixel, same values: LkAffine's three bilinear taps read
  // 2 cache lines instead of 6. Bit-identical; measured no gain on Mali or M1 (LkAffine, 61 % of
  // CPU on Mali, is compute-bound in double). Float taps: no gain and flipped a sub-tile decision.
  std::vector<std::vector<double>> rgg;
  std::vector<int> hs, ws;
};
LkRef LkPrepare(const float* ref, int h, int w, int levels);
std::array<double, 6> LkAffine(const LkRef& ref, const float* mov, int iters, const std::array<double, 6>* p0 = nullptr);
struct LkFitComparison {
  double seed_error = 0;
  double candidate_error = 0;
  std::size_t seed_samples = 0;
  std::size_t common_samples = 0;

  bool improves(double error_ratio = 1.0) const {
    // Require enough evidence for a six-parameter fit and retain most of the seed's support.
    return common_samples >= 16 && common_samples >= seed_samples - seed_samples / 10 &&
           candidate_error < error_ratio * seed_error;
  }
};
LkFitComparison LkCompareFits(const LkRef& ref, const float* mov,
                             const std::array<double, 6>& seed,
                             const std::array<double, 6>& candidate);
// LkAffine's pieces for the batched GPU version (lkrl_gpulk): the moving image's pyramid (same
// blur / decimation as LkAffine) and its 6x6 solve.
std::vector<std::vector<double>> LkMovPyramid(const LkRef& ref, const float* mov);
bool LkSolve6(double A[6][6], double b[6], double x[6]);

}  // namespace lkrl
