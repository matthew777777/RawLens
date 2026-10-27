// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#if defined(__ARM_FEATURE_CRC32)
#include <arm_acle.h>
#endif

namespace craw::detail {

inline uint32_t crc32c(const void* data, size_t n, uint32_t crc = 0) {
    auto p = static_cast<const uint8_t*>(data);
    crc = ~crc;
#if defined(__ARM_FEATURE_CRC32)
    for (; n >= 8; n -= 8, p += 8) {
        uint64_t v;
        __builtin_memcpy(&v, p, 8);
        crc = __crc32cd(crc, v);
    }
    for (; n; --n) crc = __crc32cb(crc, *p++);
#else
    static const auto table = [] {
        std::array<uint32_t, 256> t{};
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? (c >> 1) ^ 0x82F63B78u : c >> 1;
            t[i] = c;
        }
        return t;
    }();
    for (; n; --n) crc = table[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
#endif
    return ~crc;
}

}  // namespace craw::detail
