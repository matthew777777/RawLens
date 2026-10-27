// SPDX-License-Identifier: GPL-3.0-or-later
// Writer bridge: RAW16 plane -> Cinematic RAW Studio (.CRAW) encoder
// (Apache-2.0, third_party/craw/encoder) -> craw::Writer.
//
// Two frame paths match the recorder's N encode workers + one in-order
// committer:
// - containerEncodeFrame: encode only, straight into a caller pooled buffer.
//   Worker threads; each call is single-threaded (frame-level parallelism).
// - containerWriteFrame: commit an encoded payload. Committer thread only;
//   the writer rejects timestamp regressions.
// Audio (PCM16 or float32) and motion entry points are committer-thread-only like writes.
#include <jni.h>

#include <craw/Writer.h>

#include <sched.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#include <algorithm>
#include <fstream>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

craw::Writer* writerFrom(jlong h) { return reinterpret_cast<craw::Writer*>(h); }

// 2x RGB binning (SPEC 3.8): the full-res mosaic is demosaiced once, here,
// into a half-resolution RGB image whose pixels stand for the centres of the
// 2x2 source blocks. A binned *mosaic* would be demosaiced a second time by
// the reader and fringe magenta/green at every fine edge; RGB avoids that.
// Green: the 2 in-block greens at weight 6 plus the symmetric ring of 4
// nearest outer greens at weight 1 (sum 16). R/B: green + colour difference,
// where the difference at each R/B site uses an edge-directed green (average
// along the smaller green gradient, never across an edge) and is
// interpolated bilinearly (9,3,3,1) to the block centre. Blocks within 2
// samples of a near-clipped sample use plain same-colour interpolation, so
// highlights stay neutral. Measured on three real takes vs full-res AHD
// downscaled 2x: edge chroma error -15..-65 %, luma error about half and
// ~90 % detail, compared with the centre-aligned Bayer bin.
// Output is the cell frame: (row 0, col 0) R, (0,1) G, (1,0) B, (1,1) zero.
void binRgb(const uint8_t* src, int stride, int width, int rows, int cfa, int bitDepth,
            std::vector<uint16_t>& out) {
    const int ow = width / 2, oh = rows / 2;
    auto mirror = [](int i, int n) { return i < 0 ? -i : i >= n ? 2 * (n - 1) - i : i; };
    // Rows mirror by pointer; columns mirror only on the scalar edge paths, so
    // the vector loops read the camera buffer directly (no padded copy).
    auto row = [&](int y) {
        return reinterpret_cast<const uint16_t*>(src + static_cast<size_t>(mirror(y, rows)) * stride);
    };
    auto px = [&](const uint16_t* r, int x) -> int32_t { return r[mirror(x, width)]; };
    // In-block (x, y) of R and B for RGGB, GRBG, GBRG, BGGR.
    static constexpr int kR[4][2] = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
    static constexpr int kB[4][2] = {{1, 1}, {0, 1}, {1, 0}, {0, 0}};
    const int* cR = kR[cfa];
    const int* cB = kB[cfa];
    const bool greenEven = cfa == 1 || cfa == 2;
    const int white = (1 << bitDepth) - 1, clip = white - white / 20;
    // Vector loops cover site columns [1, vEnd): every read of x - 2 .. x + 17 stays in the row.
    const int vEnd = std::max(1, ow - 9);
    // Site planes (int16: |4C - 2g| <= 4 * white) with one mirrored column each side,
    // and per-block clip flags; both filled row by row just ahead of use.
    const int ps = ow + 2;
    thread_local std::vector<int16_t> dR, dB;
    thread_local std::vector<uint8_t> hot, nearRow;
    dR.resize(static_cast<size_t>(ps) * oh);
    dB.resize(static_cast<size_t>(ps) * oh);
    hot.resize(static_cast<size_t>(ow) * oh);
    nearRow.resize(ow + 16);
    // 4 x (C - edge-directed green) at the sites of one colour in output row j.
    auto diffRow = [&](const int* c, int j, int16_t* o) {
        const int y = 2 * j + c[1];
        const uint16_t *r0 = row(y), *ru = row(y - 1), *rd = row(y + 1), *ruu = row(y - 2), *rdd = row(y + 2);
        auto scalar = [&](int i) {
            const int x = 2 * i + c[0];
            const int32_t C = r0[x], l = px(r0, x - 1), r = px(r0, x + 1), u = ru[x], dn = rd[x];
            const int32_t dh = 2 * std::abs(l - r) + std::abs(2 * C - px(r0, x - 2) - px(r0, x + 2));
            const int32_t dv = 2 * std::abs(u - dn) + std::abs(2 * C - ruu[x] - rdd[x]);
            const int32_t g2 = dh < dv ? 2 * (l + r) : dv < dh ? 2 * (u + dn) : l + r + u + dn;
            o[i] = static_cast<int16_t>(4 * C - g2);
        };
        int i = 0;
        scalar(i++);
#if defined(__aarch64__)
        for (; i + 8 <= vEnd; i += 8) {
            const int x = 2 * i + c[0];
            const uint16x8x2_t m = vld2q_u16(r0 + x), mL = vld2q_u16(r0 + x - 2);
            const int16x8_t C = vreinterpretq_s16_u16(m.val[0]), r = vreinterpretq_s16_u16(m.val[1]);
            const int16x8_t a = vreinterpretq_s16_u16(mL.val[0]), l = vreinterpretq_s16_u16(mL.val[1]);
            const int16x8_t b = vreinterpretq_s16_u16(vld2q_u16(r0 + x + 2).val[0]);
            const int16x8_t u = vreinterpretq_s16_u16(vld2q_u16(ru + x).val[0]);
            const int16x8_t d = vreinterpretq_s16_u16(vld2q_u16(rd + x).val[0]);
            const int16x8_t uu = vreinterpretq_s16_u16(vld2q_u16(ruu + x).val[0]);
            const int16x8_t dd = vreinterpretq_s16_u16(vld2q_u16(rdd + x).val[0]);
            const int16x8_t C2 = vshlq_n_s16(C, 1);
            const int16x8_t dh = vaddq_s16(vshlq_n_s16(vabdq_s16(l, r), 1), vabsq_s16(vsubq_s16(vsubq_s16(C2, a), b)));
            const int16x8_t dv = vaddq_s16(vshlq_n_s16(vabdq_s16(u, d), 1), vabsq_s16(vsubq_s16(vsubq_s16(C2, uu), dd)));
            const int16x8_t h2 = vaddq_s16(l, r), v2 = vaddq_s16(u, d);
            int16x8_t g2 = vaddq_s16(h2, v2);
            g2 = vbslq_s16(vcltq_s16(dh, dv), vshlq_n_s16(h2, 1), g2);
            g2 = vbslq_s16(vcltq_s16(dv, dh), vshlq_n_s16(v2, 1), g2);
            vst1q_s16(o + i, vsubq_s16(vshlq_n_s16(C, 2), g2));
        }
#endif
        for (; i < ow; ++i) scalar(i);
        o[-1] = o[mirror(-1, ow)];
        o[ow] = o[mirror(ow, ow)];
    };
    // Block j: 2x2 max at or above 95 % of white.
    bool anyHot = false;
    auto hotRow = [&](int j) {
        const uint16_t *a = row(2 * j), *b = row(2 * j + 1);
        uint8_t* h = hot.data() + static_cast<size_t>(j) * ow;
        int i = 0;
#if defined(__aarch64__)
        const uint16x8_t vClip = vdupq_n_u16(static_cast<uint16_t>(clip));
        uint16x8_t any = vdupq_n_u16(0);
        for (; i + 8 <= ow; i += 8) {
            const uint16x8x2_t p = vld2q_u16(a + 2 * i), q = vld2q_u16(b + 2 * i);
            const uint16x8_t m = vcgeq_u16(vmaxq_u16(vmaxq_u16(p.val[0], p.val[1]), vmaxq_u16(q.val[0], q.val[1])), vClip);
            vst1_u8(h + i, vmovn_u16(vshrq_n_u16(m, 15)));
            any = vorrq_u16(any, m);
        }
        anyHot |= vmaxvq_u16(any) != 0;
#endif
        for (; i < ow; ++i) {
            const int m = std::max(std::max(a[2 * i], a[2 * i + 1]), std::max(b[2 * i], b[2 * i + 1]));
            h[i] = m >= clip;
            anyHot |= h[i] != 0;
        }
    };
    int ready = 0;  // diff/hot rows [0, ready) are filled
    auto fillTo = [&](int n) {
        for (; ready < std::min(n, oh); ++ready) {
            diffRow(cR, ready, dR.data() + static_cast<size_t>(ready) * ps + 1);
            diffRow(cB, ready, dB.data() + static_cast<size_t>(ready) * ps + 1);
            hotRow(ready);
        }
    };
    fillTo(2);
    out.resize(static_cast<size_t>(width) * rows);
    const int rStep = cR[0] == 0 ? 1 : -1, bStep = cB[0] == 0 ? 1 : -1;
    for (int j = 0; j < oh; ++j) {
        fillTo(j + 2);
        uint16_t* o0 = out.data() + static_cast<size_t>(2 * j) * width;
        uint16_t* o1 = o0 + width;
        const uint16_t *q0 = row(2 * j), *qu = row(2 * j - 1), *q1 = row(2 * j + 1), *q2 = row(2 * j + 2);
        // Near-clip flags for this output row: hot blocks dilated by one block.
        uint8_t* nr = nearRow.data() + 1;
        bool rowNear = false;
        if (anyHot) {
            const uint8_t* hu = hot.data() + static_cast<size_t>(std::max(j - 1, 0)) * ow;
            const uint8_t* hc = hot.data() + static_cast<size_t>(j) * ow;
            const uint8_t* hd = hot.data() + static_cast<size_t>(std::min(j + 1, oh - 1)) * ow;
            uint8_t acc = 0;
            for (int i = 0; i < ow; ++i) { nr[i] = hu[i] | hc[i] | hd[i]; acc |= nr[i]; }
            if (acc) {
                nr[-1] = nr[0]; nr[ow] = nr[ow - 1];
                uint8_t prev = 0;  // dilate horizontally (clamped at the edges)
                for (int i = 0; i < ow; ++i) {
                    const uint8_t cur = nr[i];
                    nr[i] = static_cast<uint8_t>(cur | (i > 0 ? prev : 0) | (i + 1 < ow ? nr[i + 1] : 0));
                    prev = cur;
                }
                rowNear = true;
            }
        }
        // Diff rows: own and the row toward the block centre (mirrored on the site grid).
        const int16_t* R0 = dR.data() + static_cast<size_t>(j) * ps + 1;
        const int16_t* R1 = dR.data() + static_cast<size_t>(mirror(j + (cR[1] == 0 ? 1 : -1), oh)) * ps + 1;
        const int16_t* B0 = dB.data() + static_cast<size_t>(j) * ps + 1;
        const int16_t* B1 = dB.data() + static_cast<size_t>(mirror(j + (cB[1] == 0 ? 1 : -1), oh)) * ps + 1;
        auto scalar = [&](int i) {
            const int x = 2 * i;
            const int32_t gin = greenEven ? q0[x] + q1[x + 1] : q0[x + 1] + q1[x];
            const int32_t ring = greenEven ? px(qu, x + 1) + px(q1, x - 1) + px(q0, x + 2) + q2[x]
                                           : px(q0, x - 1) + qu[x] + px(q1, x + 2) + q2[x + 1];
            const int32_t g16 = 6 * gin + ring;
            int32_t R, B;
            if (rowNear && nr[i]) {
                // Plain same-colour bilinear near clipping (keeps highlights neutral);
                // neighbours mirror on the same-colour site grid, like the diff planes.
                auto plain = [&](const int* c, int ti) {
                    const int tj = mirror(j + (c[1] == 0 ? 1 : -1), oh);
                    const uint16_t *own = row(2 * j + c[1]), *tow = row(2 * tj + c[1]);
                    const int sx = x + c[0], tx = 2 * ti + c[0];
                    return (9 * own[sx] + 3 * own[tx] + 3 * tow[sx] + tow[tx] + 8) >> 4;
                };
                R = plain(cR, mirror(i + rStep, ow));
                B = plain(cB, mirror(i + bStep, ow));
            } else {
                R = (4 * g16 + 9 * R0[i] + 3 * R0[i + rStep] + 3 * R1[i] + R1[i + rStep] + 32) >> 6;
                B = (4 * g16 + 9 * B0[i] + 3 * B0[i + bStep] + 3 * B1[i] + B1[i + bStep] + 32) >> 6;
            }
            o0[x] = static_cast<uint16_t>(std::clamp(R, 0, white));
            o0[x + 1] = static_cast<uint16_t>((g16 + 8) >> 4);
            o1[x] = static_cast<uint16_t>(std::clamp(B, 0, white));
            o1[x + 1] = 0;
        };
        int i = 0;
        scalar(i++);
#if defined(__aarch64__)
        const int16x8_t vWhite = vdupq_n_s16(static_cast<int16_t>(white));
        // (4 g16 + 9 d0 + 3 (d0' + d1) + d1' + 32) >> 6 in 32 bits, clamped to 0..white.
        auto colour = [&](uint16x8_t g16, const int16_t* d0, const int16_t* d1, int step) {
            const int16x8_t a = vld1q_s16(d0), bc = vaddq_s16(vld1q_s16(d0 + step), vld1q_s16(d1));
            const int16x8_t e = vld1q_s16(d1 + step);
            int32x4_t lo = vreinterpretq_s32_u32(vshll_n_u16(vget_low_u16(g16), 2));
            int32x4_t hi = vreinterpretq_s32_u32(vshll_high_n_u16(g16, 2));
            lo = vmlal_n_s16(lo, vget_low_s16(a), 9);
            hi = vmlal_high_n_s16(hi, a, 9);
            lo = vmlal_n_s16(lo, vget_low_s16(bc), 3);
            hi = vmlal_high_n_s16(hi, bc, 3);
            lo = vaddw_s16(lo, vget_low_s16(e));
            hi = vaddw_high_s16(hi, e);
            const int16x8_t v = vcombine_s16(vqrshrn_n_s32(lo, 6), vqrshrn_n_s32(hi, 6));
            return vreinterpretq_u16_s16(vminq_s16(vmaxq_s16(v, vdupq_n_s16(0)), vWhite));
        };
        for (; i + 8 <= vEnd; i += 8) {
            const int x = 2 * i;
            uint16x8_t gin, ring;
            if (greenEven) {
                gin = vaddq_u16(vld2q_u16(q0 + x).val[0], vld2q_u16(q1 + x).val[1]);
                ring = vaddq_u16(vaddq_u16(vld2q_u16(qu + x).val[1], vld2q_u16(q1 + x - 2).val[1]),
                                 vaddq_u16(vld2q_u16(q0 + x + 2).val[0], vld2q_u16(q2 + x).val[0]));
            } else {
                gin = vaddq_u16(vld2q_u16(q0 + x).val[1], vld2q_u16(q1 + x).val[0]);
                ring = vaddq_u16(vaddq_u16(vld2q_u16(q0 + x - 2).val[1], vld2q_u16(qu + x).val[0]),
                                 vaddq_u16(vld2q_u16(q1 + x + 2).val[0], vld2q_u16(q2 + x).val[1]));
            }
            const uint16x8_t g16 = vmlaq_n_u16(ring, gin, 6);
            uint16x8x2_t top, bottom;
            top.val[0] = colour(g16, R0 + i, R1 + i, rStep);
            top.val[1] = vrshrq_n_u16(g16, 4);
            bottom.val[0] = colour(g16, B0 + i, B1 + i, bStep);
            bottom.val[1] = vdupq_n_u16(0);
            vst2q_u16(o0 + x, top);
            vst2q_u16(o1 + x, bottom);
            // Rare: blocks near clipping redo the plain path.
            if (rowNear) {
                uint64_t flags;
                std::memcpy(&flags, nr + i, 8);
                if (flags)
                    for (int k = i; k < i + 8; ++k)
                        if (nr[k]) scalar(k);
            }
        }
#endif
        for (; i < ow; ++i) scalar(i);
    }
}

// Cheap mode: half-resolution Bayer remosaiced from the RGB bin (demosaic,
// downscale, remosaic, as ISP Bayer rescalers do): each output site keeps
// its colour of the RGB pixel at that site. Sharper and lower luma error
// than plain same-colour binning (detail 0.75-0.92 vs 0.54-0.79 of the
// full-res reference on three real takes); a quarter of the samples of RGB.
// Any half-res mosaic still fringes somewhat once re-demosaiced.
void binRemosaic(const uint8_t* src, int stride, int width, int rows, int cfa, int bitDepth,
                 std::vector<uint16_t>& cells, std::vector<uint16_t>& out) {
    binRgb(src, stride, width, rows, cfa, bitDepth, cells);
    const int ow = width / 2, oh = rows / 2;
    // Cell column/row of each output parity's colour: R (0,0), G (1,0), B (0,1) in (x, y).
    static constexpr int kColour[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    static constexpr int kCell[3][2] = {{0, 0}, {1, 0}, {0, 1}};
    out.resize(static_cast<size_t>(ow) * oh);
    for (int j = 0; j < oh; ++j) {
        uint16_t* o = out.data() + static_cast<size_t>(j) * ow;
        for (int par = 0; par < 2; ++par) {
            const int* cell = kCell[kColour[cfa][(j & 1) * 2 + par]];
            const uint16_t* c = cells.data() + static_cast<size_t>(2 * j + cell[1]) * width + cell[0];
            for (int i = par; i < ow; i += 2) o[i] = c[2 * i];
        }
    }
}

// Off by default: in a live take the fast cores also run the viewfinder and
// camera service, and leaving the efficiency cores available measured faster.
bool pinWorkers = false;

// Pins the calling encode worker to the fastest CPU cluster (highest
// cpuinfo_max_freq) once per thread: little cores would hold the in-order
// committer back. Best effort; failures leave the scheduler in charge.
void pinToFastCores() {
    thread_local bool done = false;
    if (done) return;
    done = true;
    const long n = sysconf(_SC_NPROCESSORS_CONF);
    std::vector<long> freq(n > 0 ? n : 0, 0);
    long best = 0;
    for (long c = 0; c < n; ++c) {
        std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(c) + "/cpufreq/cpuinfo_max_freq");
        if (f >> freq[c]) best = std::max(best, freq[c]);
    }
    if (best == 0) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    int count = 0;
    for (long c = 0; c < n; ++c)
        if (freq[c] >= best * 9 / 10) { CPU_SET(c, &set); ++count; }
    if (count > 0 && count < n) sched_setaffinity(0, sizeof set, &set);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_rawlens_CrawWriter_containerOpen(JNIEnv* env, jobject, jstring path, jstring metadata) {
    try {
        return reinterpret_cast<jlong>(new craw::Writer(jstr(env, path), jstr(env, metadata)));
    } catch (...) {
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerEncodeFrame(
    JNIEnv* env, jobject, jobject src, jint srcOffset, jint size, jint w, jint h, jint stride,
    jint cropTop, jint cropHeight, jboolean bin, jboolean binRgbMode, jint cfa, jint bitDepth, jint codec, jobject dst,
    jint dstOffset, jint dstCapacity) {
    const auto* s = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    auto* d = static_cast<uint8_t*>(env->GetDirectBufferAddress(dst));
    if (!s || !d || size < 0 || stride % 2 || stride < w * 2) return -2;
    if (static_cast<int64_t>(cropTop + cropHeight - 1) * stride + int64_t{w} * 2 > size) return -4;
    (void)h;
    if (pinWorkers) pinToFastCores();
    try {
        thread_local std::vector<uint16_t> binned;
        const uint8_t* base = s + srcOffset + static_cast<size_t>(cropTop) * stride;
        craw::FrameDesc desc;
        const bool rgb = bin && binRgbMode;
        desc.cfa = rgb ? craw::Cfa::RGB : static_cast<craw::Cfa>(cfa & 3);
        desc.bitDepth = static_cast<uint8_t>(std::clamp<jint>(bitDepth, 1, 16));
        // Both live recording codecs are protected by the container chunk CRC.
        // Avoid an additional full RAW-frame checksum pass in either mode.
        desc.codec = (codec == 2 || codec == 3) ? static_cast<uint8_t>(codec) : 1;
        desc.frameCrc = false;
        const uint16_t* raw;
        size_t rawStride;
        if (bin && !rgb) {
            // Cheap mode: half-resolution Bayer remosaic (quarter of the samples).
            thread_local std::vector<uint16_t> cells;
            binRemosaic(base, stride, w & ~1, cropHeight & ~1, cfa & 3, desc.bitDepth, cells, binned);
            desc.width = static_cast<uint32_t>(w / 2);
            desc.height = static_cast<uint32_t>((cropHeight & ~1) / 2);
            raw = binned.data();
            rawStride = desc.width;
        } else if (rgb) {
            // RGB frames need a cell codec (2 or 3); the cell frame is w x cropHeight.
            if (desc.codec == 1) desc.codec = 3;
            binRgb(base, stride, w & ~1, cropHeight & ~1, cfa & 3, desc.bitDepth, binned);
            desc.width = static_cast<uint32_t>(w & ~1);
            desc.height = static_cast<uint32_t>(cropHeight & ~1);
            raw = binned.data();
            rawStride = desc.width;
        } else {
            desc.width = static_cast<uint32_t>(w);
            desc.height = static_cast<uint32_t>(cropHeight);
            raw = reinterpret_cast<const uint16_t*>(base);
            rawStride = static_cast<size_t>(stride / 2);
        }
        uint8_t* out = d + dstOffset;
        const auto capacity = static_cast<size_t>(std::max<jint>(dstCapacity, 0));
        size_t bytes;
        try {
            bytes = craw::encodeFrame(raw, rawStride, desc, out, capacity);
        } catch (const std::invalid_argument&) {
            // A sample above the declared white level (e.g. hot pixel): stay lossless.
            if (desc.bitDepth == 16) throw;
            desc.bitDepth = 16;
            bytes = craw::encodeFrame(raw, rawStride, desc, out, capacity);
        }
        return static_cast<jint>(bytes);
    } catch (const std::length_error&) {
        return -3;
    } catch (const std::invalid_argument&) {
        return -4;
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteFrame(
    JNIEnv* env, jobject, jlong handle, jobject payload, jint payloadOffset, jint payloadBytes,
    jlong timestampNs, jstring frameJson) {
    auto* writer = writerFrom(handle);
    const auto* p = static_cast<const uint8_t*>(env->GetDirectBufferAddress(payload));
    if (!writer || !p || payloadBytes <= 0) return -2;
    try {
        writer->addEncodedFrame(p + payloadOffset, static_cast<size_t>(payloadBytes), timestampNs,
                                jstr(env, frameJson));
        return payloadBytes;
    } catch (const std::invalid_argument&) {
        return -4;
    } catch (const std::logic_error&) {
        return -6;  // timestamp regression
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteAudio(
    JNIEnv* env, jobject, jlong handle, jobject buf, jint offset, jint frames, jint channels,
    jint sampleRate, jboolean isFloat, jlong timestampNs) {
    auto* writer = writerFrom(handle);
    const auto* b = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buf));
    if (!writer || !b || frames <= 0 || channels <= 0 || channels > 255 || sampleRate <= 0) return -2;
    try {
        const bool f32 = isFloat != JNI_FALSE;
        writer->addAudio(timestampNs, f32 ? craw::AudioFormat::F32 : craw::AudioFormat::S16,
                         static_cast<uint8_t>(channels), static_cast<uint32_t>(sampleRate), b + offset,
                         static_cast<uint32_t>(frames));
        return frames * channels * (f32 ? 4 : 2);
    } catch (...) {
        return -5;
    }
}

namespace {

jint writeMotion(JNIEnv* env, jlong handle, jlongArray tsArr, jfloatArray xyzArr, jint n,
                 craw::ImuSensor sensor) {
    auto* writer = writerFrom(handle);
    if (!writer || !tsArr || !xyzArr || n <= 0) return -2;
    if (env->GetArrayLength(tsArr) < n || env->GetArrayLength(xyzArr) < n * 3) return -4;
    std::vector<jlong> ts(n);
    std::vector<jfloat> xyz(static_cast<size_t>(n) * 3);
    env->GetLongArrayRegion(tsArr, 0, n, ts.data());
    env->GetFloatArrayRegion(xyzArr, 0, n * 3, xyz.data());
    std::vector<craw::ImuSample> samples(n);
    for (jint i = 0; i < n; ++i)
        samples[i] = {ts[i], xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]};
    try {
        writer->addImu(sensor, samples.data(), static_cast<uint32_t>(n));
        return n;
    } catch (...) {
        return -5;
    }
}

}  // namespace

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteGyro(JNIEnv* env, jobject, jlong handle,
                                                       jlongArray ts, jfloatArray xyz, jint n) {
    return writeMotion(env, handle, ts, xyz, n, craw::ImuSensor::Gyroscope);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteAccel(JNIEnv* env, jobject, jlong handle,
                                                        jlongArray ts, jfloatArray xyz, jint n) {
    return writeMotion(env, handle, ts, xyz, n, craw::ImuSensor::Accelerometer);
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerWriteShading(JNIEnv* env, jobject, jlong handle, jlong ts,
                                                          jint id, jint columns, jint rows,
                                                          jfloatArray gains) {
    auto* writer = writerFrom(handle);
    if (!writer || !gains || columns <= 0 || rows <= 0 || columns > 65535 || rows > 65535) return -2;
    const jsize n = static_cast<jsize>(columns) * rows * 4;
    if (env->GetArrayLength(gains) < n) return -4;
    std::vector<float> g(n);
    env->GetFloatArrayRegion(gains, 0, n, g.data());
    try {
        writer->addShading(ts, static_cast<uint32_t>(id), static_cast<uint16_t>(columns),
                           static_cast<uint16_t>(rows), g.data());
        return 0;
    } catch (...) {
        return -5;
    }
}

JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_CrawWriter_containerClose(JNIEnv*, jobject, jlong handle) {
    auto* writer = writerFrom(handle);
    if (!writer) return -2;
    int code = 0;
    try {
        writer->close();
    } catch (...) {
        code = -5;
    }
    delete writer;
    return code;
}

}  // extern "C"
