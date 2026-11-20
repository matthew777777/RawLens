// SPDX-License-Identifier: Apache-2.0
#pragma once

namespace lkrl {

struct LfcStats { float s2u = 0, s2v = 0, wu = 0, wv = 0, epsY = 0; };

// Low-frequency colour noise removal on the finished image (lkrl_lfc.cpp). rgb: interleaved camera RGB,
// w x h; wb: white balance; k: Wiener strength in multiples of the measured blotch-band noise (0 = off);
// na, nb: noise model (var = na y + nb), only its shape over brightness is used (scale is measured).
void LowFreqChroma(float* rgb, int w, int h, const float wb[3], float k, float na, float nb, int scale = 1,
                   LfcStats* st = nullptr);
// scale: output px per sensor px (the low-res grid is 4 x scale output px).

}  // namespace lkrl
