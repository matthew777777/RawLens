// SPDX-License-Identifier: GPL-3.0-only
//
// 2x Bayer binning for the type-7 encoder's downscale path: each output
// sample covers one 2x2 source block. R and B take the plain 2x2 box —
// the block holds a single same-colour tap, and the square-lattice box
// already nulls their alias folds. Green averages the 4 G pixels forming
// a diamond around the output sample instead of the 2 in the block: the
// two in-block G's plus the two across the block corners in the
// right/below neighbours (rounded mean, so black/white levels survive).
//
// Original snippet by Eszdman (https://github.com/eszdman).
//
// Taps below are block-relative (sx, sy) = (2*ox, 2*oy) for output (ox,
// oy); `sample(x, y)` reads the cropped source row y (0 <= y < cropHeight)
// and column x (0 <= x < fullWidth):
//
//   RGGB/BGGR (green on odd parity): (1,0) top, (0,1) left, (2,1) right,
//     (1,2) bottom — the diamond around the block's (1,1) site.
//   GRBG/GBRG (green on even parity): (0,0), (1,1) in-block, (2,0) right,
//     (0,2) below — the same causal (right/below, never left/above)
//     construction on the even-parity green lattice.
//
// Edge clamps mirror to the same-colour in-block tap: the right tap is
// out of bounds only on the last output column (fullWidth is a multiple
// of 4, so 2*ox+2 >= fullWidth iff ox == encWidth-1); the bottom tap only
// on the last output row (2*oy+2 >= cropHeight iff oy == encHeight-1).
// `cfa` is the Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT numbering
// (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR); ValidateGeometry rejects the rest.
#pragma once

#include <cstdint>

namespace mediacinemaraw {
namespace detail {

// True when green sits on even (x + y) parity for this arrangement.
inline bool BinGreenOnEven(int cfa) {
    return cfa == 1 || cfa == 2;
}

// One binned output sample. `sample` is the cropped source reader; `ox`
// is an encoded column (0 <= ox < encWidth), `oy` an encoded row whose
// in-block taps satisfy 2*oy+1 < cropHeight.
template <typename Sample>
inline uint16_t BinnedSample(Sample sample, int ox, int oy, int encWidth,
                             int cropHeight, int cfa) {
    const int sx = ox * 2;
    const int sy = oy * 2;
    const bool isGreen = ((((ox + oy) & 1) == 0) == BinGreenOnEven(cfa));
    if (!isGreen) {
        // R/B: the block's single same-colour tap. Output (ox, oy) keeps
        // the source CFA phase, so the tap parity matches by construction
        // for every Bayer arrangement.
        return sample(sx + (ox & 1), sy + (oy & 1));
    }
    uint32_t t0, t1, t2, t3;
    if (!BinGreenOnEven(cfa)) {
        t0 = sample(sx + 1, sy);  // top
        t1 = sample(sx, sy + 1);  // left
        // Edge mirrors reuse the same-colour in-block tap (t1/t0), never a
        // foreign colour: the fallback index of a naive clamp would land on
        // the block's B site.
        t2 = (ox + 1 < encWidth) ? sample(sx + 2, sy + 1) : t1;  // right
        t3 = (sy + 2 < cropHeight) ? sample(sx + 1, sy + 2) : t0;  // below
    } else {
        t0 = sample(sx, sy);          // in-block
        t1 = sample(sx + 1, sy + 1);  // in-block
        t2 = (ox + 1 < encWidth) ? sample(sx + 2, sy) : t0;  // right
        t3 = (sy + 2 < cropHeight) ? sample(sx, sy + 2) : t0;  // below
    }
    return static_cast<uint16_t>((t0 + t1 + t2 + t3 + 2) >> 2);
}

} // namespace detail
} // namespace mediacinemaraw
