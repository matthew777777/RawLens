// SPDX-License-Identifier: Apache-2.0
#pragma once
namespace lkrl {
// Bilinear demosaic of an RGGB raw (scipy 'mirror' borders), planar RGB out.
void DemosaicBilinear(const float* raw, int h, int w, float* rgb);
// Bilinear upsample by s, align_corners = false (src = (dst + .5) / s - .5, clamped).
void UpsampleBilinear(const float* src, int C, int h, int w, int s, float* dst);
}  // namespace lkrl
