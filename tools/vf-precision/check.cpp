// SPDX-License-Identifier: GPL-3.0-or-later
// Synthetic flat field: inverse colored lens shading at 12-bit sensor precision.
// Run: c++ -std=c++17 -O2 tools/vf-precision/check.cpp -o /tmp/vf-precision && /tmp/vf-precision
#include "../../app/src/main/cpp/vf_linear_sample.h"
#include <cassert>
#include <cmath>
#include <cstdio>

int main() {
    // Exhaustively check the actual portable conversion against host IEEE half.
    for (int code = 0; code <= 65535; ++code) {
        const float value = vflinear::normalize(code, 64 * 64, (65535 - 64) * 64);
        const _Float16 reference = static_cast<_Float16>(value);
        uint16_t bits;
        memcpy(&bits, &reference, sizeof(bits));
        assert(bits == vflinear::toHalf(value));
        assert(std::abs(vflinear::fromHalf(bits) - value) <= 0.000245f);
    }
    double byteError = 0, halfError = 0;
    double maxByteChroma = 0, maxHalfChroma = 0;
    constexpr int count = 8192;
    for (int i = 0; i < count; ++i) {
        const double radius = static_cast<double>(i) / (count - 1);
        const double r2 = radius * radius;
        const double gains[3] = {1 + 1.5 * r2, 1 + 0.7 * r2, 1 + 2.5 * r2};
        double oldRgb[3], newRgb[3], reference[3];
        for (int ch = 0; ch < 3; ++ch) {
            const auto code = static_cast<uint16_t>(std::lround(64 + 0.025 / gains[ch] * (4095 - 64)));
            const double linear = vflinear::normalize(code, 64 * 64, (4095 - 64) * 64);
            reference[ch] = linear * gains[ch];
            oldRgb[ch] = std::round(linear * 255) / 255 * gains[ch];
            newRgb[ch] = vflinear::fromHalf(vflinear::toHalf(linear)) * gains[ch];
            byteError += std::pow(oldRgb[ch] - reference[ch], 2);
            halfError += std::pow(newRgb[ch] - reference[ch], 2);
        }
        // Added chroma relative to the sensor reference, excluding sensor quantization.
        for (int ch = 0; ch < 2; ++ch) {
            maxByteChroma = std::max(maxByteChroma, std::abs((oldRgb[ch] - oldRgb[2]) - (reference[ch] - reference[2])));
            maxHalfChroma = std::max(maxHalfChroma, std::abs((newRgb[ch] - newRgb[2]) - (reference[ch] - reference[2])));
        }
    }
    assert(halfError < byteError / 10000);
    assert(maxHalfChroma < maxByteChroma / 100);
    printf("flat-field RGB RMS: byte %.8f, half %.8f (%.1fx lower)\n",
           std::sqrt(byteError / (count * 3)), std::sqrt(halfError / (count * 3)), std::sqrt(byteError / halfError));
    printf("max added chroma: byte %.8f, half %.8f\n", maxByteChroma, maxHalfChroma);
    puts("65536 conversion/normalization cases and colored flat-field regression passed");
}
