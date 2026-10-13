// SPDX-License-Identifier: GPL-3.0-only
//
// Private SIMD helpers for the encoder hot paths. NEON on ARM, SSE2 on x86,
// portable scalar fallback elsewhere. All loads/stores are unaligned and all
// u16 element access assumes a little-endian host, matching the codec's
// literal representation (Android and desktop targets qualify).
#pragma once

#include <algorithm>
#include <cstdint>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#define MCRAW_USE_NEON 1
#elif defined(__SSE2__) || defined(_M_X64) || (defined(_M_IX86_FP) && _M_IX86_FP >= 2)
#include <emmintrin.h>
#define MCRAW_USE_SSE2 1
#endif

namespace mediacinemaraw {
namespace detail {

// Splits 2*pairCount contiguous Bayer samples into pairCount even entries
// followed by pairCount odd entries. `src` must be 2-pixel aligned (even x).
inline void DeinterleaveEvenOdd(const uint16_t* src, uint16_t* even, uint16_t* odd, int pairCount) {
#if defined(MCRAW_USE_NEON)
    int i = 0;
    for (; i + 8 <= pairCount; i += 8) {
        uint16x8x2_t split = vld2q_u16(src + i * 2);
        vst1q_u16(even + i, split.val[0]);
        vst1q_u16(odd + i, split.val[1]);
    }
    for (; i < pairCount; ++i) {
        even[i] = src[i * 2];
        odd[i] = src[i * 2 + 1];
    }
#elif defined(MCRAW_USE_SSE2)
    int i = 0;
    for (; i + 4 <= pairCount; i += 4) {
        __m128i v = _mm_loadu_si128(reinterpret_cast<const __m128i*>(src + i * 2));
        // t = [e0 e1 o0 o1 e2 e3 o2 o3]; s rotates dwords to [d2 d3 d0 d1]
        // so unpacklo interleaves [d0 d2 d1 d3] = evens, odds.
        __m128i t = _mm_shufflehi_epi16(_mm_shufflelo_epi16(v, _MM_SHUFFLE(3, 1, 2, 0)),
                                        _MM_SHUFFLE(3, 1, 2, 0));
        __m128i s = _mm_shuffle_epi32(t, _MM_SHUFFLE(1, 0, 3, 2));
        __m128i u = _mm_unpacklo_epi32(t, s);
        _mm_storel_epi64(reinterpret_cast<__m128i*>(even + i), u);
        _mm_storel_epi64(reinterpret_cast<__m128i*>(odd + i), _mm_srli_si128(u, 8));
    }
    for (; i < pairCount; ++i) {
        even[i] = src[i * 2];
        odd[i] = src[i * 2 + 1];
    }
#else
    for (int i = 0; i < pairCount; ++i) {
        even[i] = src[i * 2];
        odd[i] = src[i * 2 + 1];
    }
#endif
}

// Min/max over one 64-sample channel.
inline void ChannelMinMax(const uint16_t* channel, uint16_t& lo, uint16_t& hi) {
    lo = 65535;
    hi = 0;
#if defined(MCRAW_USE_NEON)
    uint16x8_t vlo = vdupq_n_u16(65535);
    uint16x8_t vhi = vdupq_n_u16(0);
    for (int i = 0; i < 64; i += 8) {
        uint16x8_t v = vld1q_u16(channel + i);
        vlo = vminq_u16(vlo, v);
        vhi = vmaxq_u16(vhi, v);
    }
    uint16_t mins[8], maxs[8];
    vst1q_u16(mins, vlo);
    vst1q_u16(maxs, vhi);
    for (int i = 0; i < 8; ++i) {
        lo = std::min(lo, mins[i]);
        hi = std::max(hi, maxs[i]);
    }
#elif defined(MCRAW_USE_SSE2)
    // SSE2 min/max are signed only: flip the sign bit so unsigned order
    // matches signed order, then flip back on reduction.
    const __m128i bias = _mm_set1_epi16(static_cast<short>(0x8000));
    __m128i vlo = _mm_set1_epi16(static_cast<short>(0x7FFF));
    __m128i vhi = _mm_set1_epi16(static_cast<short>(0x8000));
    for (int i = 0; i < 64; i += 8) {
        __m128i v =
            _mm_xor_si128(_mm_loadu_si128(reinterpret_cast<const __m128i*>(channel + i)), bias);
        vlo = _mm_min_epi16(vlo, v);
        vhi = _mm_max_epi16(vhi, v);
    }
    uint16_t mins[8], maxs[8];
    _mm_storeu_si128(reinterpret_cast<__m128i*>(mins), vlo);
    _mm_storeu_si128(reinterpret_cast<__m128i*>(maxs), vhi);
    for (int i = 0; i < 8; ++i) {
        lo = std::min(lo, static_cast<uint16_t>(mins[i] ^ 0x8000));
        hi = std::max(hi, static_cast<uint16_t>(maxs[i] ^ 0x8000));
    }
#else
    for (int i = 0; i < 64; ++i) {
        lo = std::min(lo, channel[i]);
        hi = std::max(hi, channel[i]);
    }
#endif
}

// In-place `channel[i] -= reference` over one 64-sample channel.
inline void SubtractReferenceInPlace(uint16_t* channel, uint16_t reference) {
#if defined(MCRAW_USE_NEON)
    uint16x8_t ref = vdupq_n_u16(reference);
    for (int i = 0; i < 64; i += 8)
        vst1q_u16(channel + i, vsubq_u16(vld1q_u16(channel + i), ref));
#elif defined(MCRAW_USE_SSE2)
    __m128i ref = _mm_set1_epi16(static_cast<short>(reference));
    for (int i = 0; i < 64; i += 8)
        _mm_storeu_si128(
            reinterpret_cast<__m128i*>(channel + i),
            _mm_sub_epi16(_mm_loadu_si128(reinterpret_cast<const __m128i*>(channel + i)), ref));
#else
    for (int i = 0; i < 64; ++i)
        channel[i] -= reference;
#endif
}

} // namespace detail
} // namespace mediacinemaraw
