// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <vector>

#ifndef LKRL_KN_DEFAULT_BIN
#define LKRL_KN_DEFAULT_BIN ""
#endif

namespace lkrl {
// KernelNet (s1, s2, rho) map of a normalised RGGB tile (h x w): 3 x mh x mw.
bool KernelNetMap(const float* raw, int h, int w, float sigma, std::vector<float>* map, int* mh, int* mw);
// fp32 weights+biases packed [w0 b0 .. w5 b5] (conv order oc, ic, ky, kx) for kn_conv.comp.
bool KernelNetPacked(std::vector<float>* wb);
// Network input (2 x h/2 x w/2): sqrt quad luma, constant sigma plane.
void KernelNetInput(const float* raw, int h, int w, float sigma, std::vector<float>* x);
// Kernel covariance (xx, xy, yy, raw px^2) at raw position (xr, yr), MFSR's shaping.
void KernelOmega(const std::vector<float>& map, int mh, int mw, int h, int w, float xr, float yr,
                 float sigma_scale, float sigma_cap, float om[3]);
}  // namespace lkrl
