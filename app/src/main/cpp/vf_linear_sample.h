// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>

namespace vflinear {
// IEEE binary16 round-to-nearest-even for finite nonnegative normalized RAW.
// Integer conversion also works on ARMv7 without native half arithmetic.
inline uint16_t toHalf(float value) {
    uint32_t bits;
    memcpy(&bits, &value, sizeof(bits));
    const int exponent = static_cast<int>((bits >> 23) & 255) - 112;
    uint32_t mantissa = bits & 0x7fffff;
    if (exponent < -10) return 0;
    if (exponent <= 0) {
        mantissa |= 0x800000;
        const int shift = 14 - exponent;
        return static_cast<uint16_t>((mantissa + ((1u << (shift - 1)) - 1u) + ((mantissa >> shift) & 1u)) >> shift);
    }
    const uint32_t rounded = mantissa + 0xfffu + ((mantissa >> 13) & 1u);
    return static_cast<uint16_t>((static_cast<uint32_t>(exponent) << 10) + (rounded >> 13));
}
inline float fromHalf(uint16_t half) {
    const unsigned exponent = (half >> 10) & 31u;
    const unsigned mantissa = half & 1023u;
    if (exponent == 0) return static_cast<float>(mantissa) * (1.0f / 16777216.0f);
    const uint32_t bits = ((exponent + 112u) << 23) | (mantissa << 13);
    float value;
    memcpy(&value, &bits, sizeof(value));
    return value;
}
inline float normalize(uint16_t code, int blackQ, int denQ) {
    const int num = std::max(0, std::min(static_cast<int>(code) * 64 - blackQ, denQ));
    return static_cast<float>(num) / static_cast<float>(denQ);
}
}  // namespace vflinear
