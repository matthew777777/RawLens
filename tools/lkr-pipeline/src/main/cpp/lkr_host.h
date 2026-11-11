// SPDX-License-Identifier: Apache-2.0
#pragma once

// CPU-side stages of LK-Reloaded, mirrors of python/lkr (run.py, lkr.py):
// burst crop loading (RGGB-normalised), OUR OWN noise estimates (never the
// Camera2 / DNG NoiseProfile), and the coarse forward-additive pyramid
// Lucas-Kanade initialisation (paper Sec. 3.4).

#include <array>
#include <string>
#include <vector>

namespace lkr {

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
void NoiseAB(const float* raw, int h, int w, float* a, float* b);

// Grey image (mean of the bilinear demosaic) -- python lkr.grey.
void Grey(const float* raw, int h, int w, float* out);

// Forward-additive affine LK, 3-level pyramid: p with ref(A u + t) ~ mov(u)
// -- python lkr.lk_affine (iters per level 40, stop at |dt| < 1e-3).
std::array<double, 6> LkAffine(const float* ref, const float* mov, int h, int w, int iters = 40,
                               int levels = 3, const std::array<double, 6>* p0 = nullptr);
// Mean |ref(A u + t) - mov(u)| over valid pixels (alignment quality of p).
double LkResidual(const float* ref, const float* mov, int h, int w, const std::array<double, 6>& p);

}  // namespace lkr
