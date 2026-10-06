#include "monitoring_overlays/cpu_reference.h"

#include <algorithm>
#include <cmath>

namespace monitoring_overlays {

FalseColorParams FalseColorParams::defaultPreset() {
    FalseColorParams p{};
    p.enabled = true;
    p.rangeCount = 7;
    p.ranges[0] = {0.0f, 3.0f, {0.10f, 0.05f, 0.25f, 0.75f}};
    p.ranges[1] = {3.0f, 10.0f, {0.05f, 0.15f, 0.65f, 0.70f}};
    p.ranges[2] = {10.0f, 40.0f, {0.10f, 0.55f, 0.80f, 0.58f}};
    p.ranges[3] = {40.0f, 60.0f, {0.18f, 0.70f, 0.30f, 0.52f}};
    p.ranges[4] = {60.0f, 80.0f, {0.95f, 0.78f, 0.10f, 0.62f}};
    p.ranges[5] = {80.0f, 95.0f, {1.00f, 0.35f, 0.05f, 0.72f}};
    p.ranges[6] = {95.0f, 100.001f, {1.00f, 0.05f, 0.10f, 0.82f}};
    return p;
}

namespace cpu_reference {
namespace {
PremulRgba premul(const ColorRgba& c) noexcept {
    const float a = std::clamp(c.a, 0.0f, 1.0f);
    return {std::clamp(c.r, 0.0f, 1.0f) * a, std::clamp(c.g, 0.0f, 1.0f) * a, std::clamp(c.b, 0.0f, 1.0f) * a, a};
}
const ColorRgba* severityColor(const std::array<ColorRgba, 3>& colors, uint32_t severity) noexcept {
    return severity >= 1 && severity <= 3 ? &colors[severity - 1] : nullptr;
}
}  // namespace

uint32_t logicalSeverityFromPhysicalNibble(uint16_t stateWord, uint32_t bitOffset) noexcept {
    const uint16_t nibble = uint16_t((stateWord >> bitOffset) & 0xFu);
    const bool r = (nibble & 0x1u) != 0;
    const bool g = (nibble & 0x6u) != 0;  // G1 OR G2
    const bool b = (nibble & 0x8u) != 0;
    return uint32_t(r) + uint32_t(g) + uint32_t(b);
}

PremulRgba rawStateOverlay(uint16_t stateWord, const RawStateOverlayParams& p) noexcept {
    if (p.highlightEnabled) {
        const uint32_t s = logicalSeverityFromPhysicalNibble(stateWord, 0);
        if (const auto* c = severityColor(p.highlightSeverity, s)) return premul(*c);
    }
    if (p.shadowClippedEnabled) {
        const uint32_t s = logicalSeverityFromPhysicalNibble(stateWord, 12);
        if (const auto* c = severityColor(p.shadowClippedSeverity, s)) return premul(*c);
    }
    if (p.shadowWarningEnabled) {
        const uint32_t s = logicalSeverityFromPhysicalNibble(stateWord, 8);
        if (const auto* c = severityColor(p.shadowWarningSeverity, s)) return premul(*c);
    }
    return {};
}

float focusScore3x3(const std::array<float, 9>& n, const FocusPeakingParams& p) noexcept {
    // Display-referred detector: Sobel gradient magnitude plus a small Laplacian
    // assist so single-pixel dots still respond where Sobel cancels. Input is
    // display (tonemapped) luma 0..1, already perceptually compressed, so a
    // light contrast normalization suffices.
    const float gx = (n[2] + 2.0f * n[5] + n[8]) - (n[0] + 2.0f * n[3] + n[6]);
    const float gy = (n[6] + 2.0f * n[7] + n[8]) - (n[0] + 2.0f * n[1] + n[2]);
    const float sobel = std::fabs(gx) + std::fabs(gy);
    const float c = n[4];
    const float axial = std::fabs(4.0f * c - n[1] - n[3] - n[5] - n[7]);
    const float diag = std::fabs(4.0f * c - n[0] - n[2] - n[6] - n[8]);
    const float hf = sobel + 0.5f * (axial + 0.5f * diag);
    float localMean = 0.0f;
    for (float v : n) localMean += std::fabs(v);
    localMean *= (1.0f / 9.0f);
    const float absoluteFloor =
        std::max(0.0f, p.absoluteNoiseFloor) * (1.0f + 0.5f * std::sqrt(std::max(localMean, 0.0f)));
    const float cleaned = std::max(0.0f, hf - absoluteFloor);
    return cleaned / (localMean + std::max(1.0e-6f, p.normalizationFloor));
}

float focusAlphaFromScore(float score, const FocusPeakingParams& p) noexcept {
    if (!p.enabled) return 0.0f;
    const float s = std::clamp(p.sensitivity, 0.0f, 1.0f);
    const float threshold = 4.0f + (0.6f - 4.0f) * s;
    const float width = std::max(0.08f, threshold * 1.2f);
    const float t = std::clamp((score - threshold) / width, 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t) * std::clamp(p.color.a, 0.0f, 1.0f);
}

PremulRgba focusOverlay(const std::array<float, 9>& n, const FocusPeakingParams& p) noexcept {
    const float a = focusAlphaFromScore(focusScore3x3(n, p), p);
    return {std::clamp(p.color.r, 0.0f, 1.0f) * a, std::clamp(p.color.g, 0.0f, 1.0f) * a,
            std::clamp(p.color.b, 0.0f, 1.0f) * a, a};
}

void dilateFocusAlpha(const float* alphas, float* out, uint32_t w, uint32_t h, float haloScale) noexcept {
    if (!alphas || !out || w == 0 || h == 0) return;
    const float k = std::clamp(haloScale, 0.0f, 1.0f);
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const float core = alphas[size_t(y) * w + x];
            float m = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
                const int yy = std::clamp<int>(int(y) + dy, 0, int(h) - 1);
                for (int dx = -1; dx <= 1; ++dx) {
                    const int xx = std::clamp<int>(int(x) + dx, 0, int(w) - 1);
                    m = std::max(m, alphas[size_t(yy) * w + size_t(xx)]);
                }
            }
            // Bright 1px core with a dim halo: reads as a thin edge instead of
            // a solid 3x3 block.
            out[size_t(y) * w + x] = std::max(core, k * m);
        }
    }
}

bool tonemapShadowMask(float rPrime, float gPrime, float bPrime, const TonemapShadowParams& p) noexcept {
    if (!p.enabled) return false;
    // WYSIWYG: the input image is already tonemapped, so the Shadows/Blacks
    // sliders move pixels across this fixed crushed-black threshold by
    // themselves. Darkening (negative Shadows) pushes more pixels below it and
    // the zebra grows; lifting (positive Shadows) pulls pixels above it and the
    // zebra shrinks. No slider-dependent expansion: that fought the natural
    // response by adding zebra exactly when lifting removed crushed blacks.
    const float y = 0.2126f * std::clamp(rPrime, 0.0f, 1.0f) + 0.7152f * std::clamp(gPrime, 0.0f, 1.0f) +
                    0.0722f * std::clamp(bPrime, 0.0f, 1.0f);
    return 100.0f * y < std::clamp(p.thresholdIre, 0.0f, 100.0f);
}

PremulRgba tonemapShadowOverlay(int x, int y, float rPrime, float gPrime, float bPrime,
                                const TonemapShadowParams& p) noexcept {
    if (!tonemapShadowMask(rPrime, gPrime, bPrime, p)) return {};
    const float period = std::max(2.0f, p.stripePeriod);
    const long sum = long(x) + long(y);
    const long stripe = long(std::floor(double(sum) / double(period))) & 1L;
    const ColorRgba& c = (stripe == 0) ? p.stripeColorA : p.stripeColorB;
    return premul(c);
}

float encodedSignalIre(float r, float g, float b) noexcept {
    const float y =
        0.2126f * std::clamp(r, 0.0f, 1.0f) + 0.7152f * std::clamp(g, 0.0f, 1.0f) + 0.0722f * std::clamp(b, 0.0f, 1.0f);
    return 100.0f * y;
}

int falseColorRangeIndex(float ire, const FalseColorParams& p) noexcept {
    if (!p.enabled) return -1;
    const uint32_t count = std::min(p.rangeCount, kMaxFalseColorRanges);
    for (uint32_t i = 0; i < count; ++i) {
        const auto& r = p.ranges[i];
        const bool in = (ire >= r.lowIre) && (ire < r.highIre || (i + 1 == count && ire <= r.highIre));
        if (in) return int(i);
    }
    return -1;
}

PremulRgba falseColorOverlay(float r, float g, float b, const FalseColorParams& p) noexcept {
    const int i = falseColorRangeIndex(encodedSignalIre(r, g, b), p);
    return i >= 0 ? premul(p.ranges[size_t(i)].color) : PremulRgba{};
}

PremulRgba over(PremulRgba top, PremulRgba bottom) noexcept {
    const float k = 1.0f - std::clamp(top.a, 0.0f, 1.0f);
    return {top.r + bottom.r * k, top.g + bottom.g * k, top.b + bottom.b * k, top.a + bottom.a * k};
}

}  // namespace cpu_reference
}  // namespace monitoring_overlays
