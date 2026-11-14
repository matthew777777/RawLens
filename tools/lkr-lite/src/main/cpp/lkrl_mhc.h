// SPDX-License-Identifier: Apache-2.0
#pragma once
// Line-by-line C++ port of app MhcReference.demosaicCoherent / runCoherent:
// the CPU twin of the encoded-video coherent-green MHC path (vf_mhc_green +
// vf_mhcyuv*.comp). Parity-checked against the Kotlin reference
// (tools/lkr-pipeline/host/lkr_mhc_check.cpp + app MhcLkrParityTest).

#include <cstdint>

namespace lkrl {

// Exact MhcReference.demosaicCoherent signature/semantics: crop [(left, top),
// width x height] of a sensor plane of codes (row pitch `pitch`), quad map
// `channels` (channels[0] = R quad, [1]/[2] = G quads, else B), per-quad black
// `levels`, `white`. Output interleaved RGB, width*height*3.
void MhcCoherentCodes(const uint16_t* codes, int sensor_w, int sensor_h, int pitch, int left, int top, int width,
                      int height, const int channels[4], const float levels[4], float white, float* rgb);

// LK-SR start image: same algorithm on a normalised RGGB tile (R at (0,0)),
// planar output 3 x h x w.
void MhcCoherentRggb(const float* raw, int h, int w, float* rgb_planar);

}  // namespace lkrl
