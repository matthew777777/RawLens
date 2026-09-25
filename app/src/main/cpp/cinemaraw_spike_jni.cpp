// SPDX-License-Identifier: GPL-3.0-or-later
// P0 spike bridge: RAW16 -> packed-RAW10 packer (planned P3 pack stage) plus
// direct access to the vendored MediaCinemaRAW type-7 encoder (GPL-3.0-only,
// app/src/main/cpp/cinemaraw, commit 14c3ddc). Timing is done in Kotlin with
// nanoTime(); these entry points do a single pack/encode each so per-stage
// medians isolate pack cost from encode cost (Path A vs Path B verdict).
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <vector>

#include <MediaCinemaRAW/Encoder.h>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace {

// Pack one row group: 4x uint16LE (10-bit values) -> 5 bytes
// (4 low bytes + 1 byte of packed 2-bit highs). Width must be a multiple of 4.
inline void pack4(const uint16_t* s, uint8_t* d) {
    d[0] = static_cast<uint8_t>(s[0]);
    d[1] = static_cast<uint8_t>(s[1]);
    d[2] = static_cast<uint8_t>(s[2]);
    d[3] = static_cast<uint8_t>(s[3]);
    d[4] = static_cast<uint8_t>(((s[0] >> 8) & 3) | (((s[1] >> 8) & 3) << 2) |
                                (((s[2] >> 8) & 3) << 4) | (((s[3] >> 8) & 3) << 6));
}

int packRaw10Impl(const uint8_t* src, int srcStride, int w, int h, uint8_t* dst) {
    if (!src || !dst || w <= 0 || h <= 0 || (w % 4) || srcStride < w * 2) return -1;
    const int dstStride = w / 4 * 5;
    for (int y = 0; y < h; ++y) {
        const uint8_t* srow = src + static_cast<size_t>(y) * srcStride;
        uint8_t* drow = dst + static_cast<size_t>(y) * dstStride;
#if defined(__ARM_NEON)
        int x = 0;
        // 8 pixels per iteration: low bytes via narrowing, highs combined scalar.
        for (; x + 8 <= w; x += 8) {
            uint16x8_t v = vld1q_u16(reinterpret_cast<const uint16_t*>(srow + x * 2));
            uint8x8_t lo = vmovn_u16(v);
            uint16x8_t hi = vshrq_n_u16(v, 8);
            uint8x8_t hi8 = vmovn_u16(hi);
            uint8_t hb[8];
            vst1_u8(hb, hi8);
            uint8_t lb[8];
            vst1_u8(lb, lo);
            drow[0] = lb[0];
            drow[1] = lb[1];
            drow[2] = lb[2];
            drow[3] = lb[3];
            drow[4] = (hb[0] & 3) | ((hb[1] & 3) << 2) | ((hb[2] & 3) << 4) | ((hb[3] & 3) << 6);
            drow[5] = lb[4];
            drow[6] = lb[5];
            drow[7] = lb[6];
            drow[8] = lb[7];
            drow[9] = (hb[4] & 3) | ((hb[5] & 3) << 2) | ((hb[6] & 3) << 4) | ((hb[7] & 3) << 6);
            drow += 10;
        }
        for (; x < w; x += 4) {
            pack4(reinterpret_cast<const uint16_t*>(srow + x * 2), drow);
            drow += 5;
        }
#else
        for (int x = 0; x < w; x += 4) {
            pack4(reinterpret_cast<const uint16_t*>(srow + x * 2), drow);
            drow += 5;
        }
#endif
    }
    return 0;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawSpike_packRaw10Native(
    JNIEnv* env, jobject /*thiz*/, jobject src, jint srcOffset, jint srcStride,
    jint w, jint h, jobject dst, jint dstOffset) {
    const uint8_t* s =
        static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    uint8_t* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!s || !d) return -2;
    return packRaw10Impl(s + srcOffset, srcStride, w, h, d + dstOffset);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CinemaRawSpike_encodeNative(
    JNIEnv* env, jobject /*thiz*/, jobject src, jint srcOffset, jint size,
    jint w, jint h, jint stride, jboolean raw10, jint cropTop,
    jint cropHeight, jboolean bin, jobject dst, jint dstOffset,
    jint dstCapacity) {
    const uint8_t* s =
        static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    uint8_t* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!s || !d || size < 0) return -2;
    try {
        thread_local std::vector<uint8_t> out;
        mediacinemaraw::encode(s + srcOffset, static_cast<size_t>(size), w, h,
                               stride, raw10, cropTop, cropHeight, bin, out);
        if (static_cast<jint>(out.size()) > dstCapacity) return -3;
        std::memcpy(d + dstOffset, out.data(), out.size());
        return static_cast<jint>(out.size());
    } catch (const std::invalid_argument&) {
        return -4;
    } catch (...) {
        return -5;
    }
}

}  // extern "C"
