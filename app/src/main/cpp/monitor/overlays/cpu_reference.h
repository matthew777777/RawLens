#pragma once
#include <array>
#include <cstdint>

#include "monitoring_overlays/config_types.h"

namespace monitoring_overlays::cpu_reference {

struct PremulRgba {
    float r = 0, g = 0, b = 0, a = 0;
};

uint32_t logicalSeverityFromPhysicalNibble(uint16_t stateWord, uint32_t bitOffset) noexcept;
PremulRgba rawStateOverlay(uint16_t stateWord, const RawStateOverlayParams&) noexcept;

// Display-referred focus score. `n` is a row-major 3x3 display-luma neighborhood
// (Rec.709, 0..1). Score is Sobel magnitude plus Laplacian assist, lightly
// contrast-normalized.
float focusScore3x3(const std::array<float, 9>& n, const FocusPeakingParams&) noexcept;
float focusAlphaFromScore(float score, const FocusPeakingParams&) noexcept;
PremulRgba focusOverlay(const std::array<float, 9>& n, const FocusPeakingParams&) noexcept;
// 3x3 max-dilation over a per-pixel alpha image: bright 1px core with a dim
// halo (`haloScale` x neighbor max, 0..1) so edges read thin instead of as
// solid 3x3 blocks. `alphas`/`out` are row-major w*h.
void dilateFocusAlpha(const float* alphas, float* out, uint32_t w, uint32_t h, float haloScale = 0.4f) noexcept;

// Display-referred tonemap-shadow zebra.
bool tonemapShadowMask(float rPrime, float gPrime, float bPrime, const TonemapShadowParams&) noexcept;
PremulRgba tonemapShadowOverlay(int x, int y, float rPrime, float gPrime, float bPrime,
                                const TonemapShadowParams&) noexcept;

float encodedSignalIre(float rPrime, float gPrime, float bPrime) noexcept;
int falseColorRangeIndex(float ire, const FalseColorParams&) noexcept;
PremulRgba falseColorOverlay(float rPrime, float gPrime, float bPrime, const FalseColorParams&) noexcept;
PremulRgba over(PremulRgba top, PremulRgba bottom) noexcept;

}  // namespace monitoring_overlays::cpu_reference
