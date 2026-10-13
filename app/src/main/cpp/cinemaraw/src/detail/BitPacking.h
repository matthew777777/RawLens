// SPDX-License-Identifier: GPL-3.0-only
//
// Private bit packing for type 7 payloads: per-channel delta packing and the
// trailing bits/references metadata streams. The decoder consumes eight
// interleaved lanes, not a conventional contiguous bitstream; the packers
// below mirror that layout exactly.
#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>

#include "Simd.h"

namespace mediacinemaraw {
namespace detail {

// Payload geometry shared by the tile loop and the metadata streams.
constexpr int kTileWidth = 64;
constexpr int kTileHeight = 4;
constexpr int kChannelSamples = 64;
constexpr int kLaneCount = 8;
constexpr int kMetadataGroupSize = 64;
// The header's four-bit width field has no room for 16, so the 16-bit
// literal mode is spelled 15 on the wire.
constexpr int kLiteralBitWidth = 16;
constexpr int kLiteralWidthNibble = 15;

inline void AppendU32LE(std::vector<uint8_t>& out, uint32_t value) {
    for (int i = 0; i < 4; ++i)
        out.push_back(static_cast<uint8_t>(value >> (8 * i)));
}

inline void PatchU32LE(std::vector<uint8_t>& out, size_t position, uint32_t value) {
    for (int i = 0; i < 4; ++i)
        out[position + i] = static_cast<uint8_t>(value >> (8 * i));
}

// Narrowest supported width covering `maxDelta`. Zero writes nothing, 8
// truncates to bytes, 16 memcpys literals; see PackChannelDeltas().
inline int BitsForDeltaRange(uint16_t maxDelta) {
    if (maxDelta == 0)
        return 0;
    for (int width : {1, 2, 3, 4, 5, 6, 8, 10})
        if (maxDelta < (1u << width))
            return width;
    return kLiteralBitWidth;
}

// Packs 64 `bits`-wide deltas into eight interleaved lanes.
inline void PackChannelDeltas(std::vector<uint8_t>& out, const uint16_t* deltas, int bits) {
    if (bits == 0)
        return;
    const size_t offset = out.size();
    out.resize(offset + (bits == kLiteralBitWidth ? 128 : bits * 8));
    uint8_t* dst = out.data() + offset;
    if (bits == kLiteralBitWidth) {
        // Little-endian host assumed, matching the codec's u16 literals.
        std::memcpy(dst, deltas, 128);
    } else if (bits == 8) {
#if defined(MCRAW_USE_NEON)
        for (int i = 0; i < 64; i += 8)
            vst1_u8(dst + i, vmovn_u16(vld1q_u16(deltas + i)));
#elif defined(MCRAW_USE_SSE2)
        // Deltas are < 256, so saturating pack is exact.
        for (int i = 0; i < 64; i += 16)
            _mm_storeu_si128(
                reinterpret_cast<__m128i*>(dst + i),
                _mm_packus_epi16(
                    _mm_loadu_si128(reinterpret_cast<const __m128i*>(deltas + i)),
                    _mm_loadu_si128(reinterpret_cast<const __m128i*>(deltas + i + 8))));
#else
        for (int i = 0; i < 64; ++i)
            dst[i] = static_cast<uint8_t>(deltas[i]);
#endif
    } else if (bits == 3 || bits == 5 || bits == 6) {
        uint8_t* packed = dst;
        for (int lane = 0; lane < 8; ++lane) {
            uint16_t v[8];
            for (int g = 0; g < 8; ++g)
                v[g] = deltas[g * 8 + lane];
            if (bits == 3) {
                packed[lane] = static_cast<uint8_t>(v[0] | (v[1] << 3) | ((v[2] & 3) << 6));
                packed[8 + lane] = static_cast<uint8_t>(v[3] | (v[4] << 3) | ((v[5] & 3) << 6));
                packed[16 + lane] = static_cast<uint8_t>(v[6] | (v[7] << 3) | ((v[2] >> 2) << 6) |
                                                         ((v[5] >> 2) << 7));
            } else if (bits == 5) {
                packed[lane] = static_cast<uint8_t>(v[0] | ((v[5] & 7) << 5));
                packed[8 + lane] = static_cast<uint8_t>(v[1] | ((v[6] & 7) << 5));
                packed[16 + lane] = static_cast<uint8_t>(v[2] | ((v[7] & 7) << 5));
                packed[24 + lane] =
                    static_cast<uint8_t>(v[3] | ((v[5] >> 3) << 5) | (((v[7] >> 3) & 1) << 7));
                packed[32 + lane] =
                    static_cast<uint8_t>(v[4] | ((v[6] >> 3) << 5) | ((v[7] >> 4) << 7));
            } else {
                for (int g = 0; g < 6; ++g)
                    packed[g * 8 + lane] =
                        static_cast<uint8_t>(v[g] | (((v[6 + g / 3] >> (2 * (g % 3))) & 3) << 6));
            }
        }
    } else if (bits == 10) {
        for (int half = 0; half < 2; ++half) {
#if defined(MCRAW_USE_NEON)
            for (int i = 0; i < 32; i += 8)
                vst1_u8(dst + half * 40 + i, vmovn_u16(vld1q_u16(deltas + half * 32 + i)));
#elif defined(MCRAW_USE_SSE2)
            // Deltas reach 1023: mask to low bytes first, packus saturates.
            const __m128i lowBits = _mm_set1_epi16(0x00FF);
            for (int i = 0; i < 32; i += 16) {
                __m128i a = _mm_and_si128(
                    _mm_loadu_si128(reinterpret_cast<const __m128i*>(deltas + half * 32 + i)),
                    lowBits);
                __m128i b = _mm_and_si128(
                    _mm_loadu_si128(reinterpret_cast<const __m128i*>(deltas + half * 32 + i + 8)),
                    lowBits);
                _mm_storeu_si128(reinterpret_cast<__m128i*>(dst + half * 40 + i),
                                 _mm_packus_epi16(a, b));
            }
#else
            for (int i = 0; i < 32; ++i)
                dst[half * 40 + i] = static_cast<uint8_t>(deltas[half * 32 + i]);
#endif
            for (int lane = 0; lane < 8; ++lane) {
                uint8_t high = 0;
                for (int group = 0; group < 4; ++group)
                    high = static_cast<uint8_t>(
                        high | ((deltas[half * 32 + group * 8 + lane] >> 8) << (group * 2)));
                dst[half * 40 + 32 + lane] = high;
            }
        }
    } else {
        const int groups = 8 / bits;
        for (int base = 0; base < 64; base += groups * 8)
            for (int lane = 0; lane < 8; ++lane) {
                uint8_t v = 0;
                for (int g = 0; g < groups; ++g)
                    v = static_cast<uint8_t>(v | (deltas[base + g * 8 + lane] << (g * bits)));
                *dst++ = v;
            }
    }
}

// Appends one metadata stream (per-channel bit widths or reference values).
// Values are padded to a multiple of 64 and each group is stored as a
// 2-byte header (width nibble + 12-bit reference) plus packed deltas.
inline void AppendMetadataStream(std::vector<uint8_t>& out, std::vector<uint16_t>& values) {
    // Zero tail padding is a wire convention, not just filler: deployed
    // readers validate that entries past the unpadded count decode to zero,
    // so an in-range pad value here would break compatibility.
    values.resize((values.size() + 63) / 64 * 64, 0);
    AppendU32LE(out, static_cast<uint32_t>(values.size()));
    for (size_t i = 0; i < values.size(); i += kMetadataGroupSize) {
        const uint16_t groupMin = *std::min_element(values.begin() + i, values.begin() + i + 64);
        const uint16_t reference = std::min<uint16_t>(groupMin, 4095);
        uint16_t deltas[kChannelSamples];
        uint16_t maxDelta = 0;
        for (int j = 0; j < 64; ++j) {
            deltas[j] = static_cast<uint16_t>(values[i + j] - reference);
            maxDelta = std::max(maxDelta, deltas[j]);
        }
        const int bits = BitsForDeltaRange(maxDelta);
        const int widthNibble = (bits == kLiteralBitWidth) ? kLiteralWidthNibble : bits;
        out.push_back(static_cast<uint8_t>((widthNibble << 4) | (reference >> 8)));
        out.push_back(static_cast<uint8_t>(reference));
        PackChannelDeltas(out, deltas, bits);
    }
}

} // namespace detail
} // namespace mediacinemaraw
