// SPDX-License-Identifier: Apache-2.0
#include <craw/Encoder.h>

#include "Crc32c.h"

#include <algorithm>
#include <atomic>
#include <bit>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <thread>

#if defined(__ARM_NEON) && defined(__aarch64__)
#include <arm_neon.h>
#endif
#ifdef CRAW_PROFILE
#include <chrono>
#endif

namespace craw {
namespace {

constexpr int kRiceLimit = 24;
constexpr int kEscapeBits = 24;

// (G1, G2, R, B) positions in the 2x2 cell, index = py * 2 + px.
constexpr int kLayout[4][4] = {{1, 2, 0, 3}, {0, 3, 1, 2}, {0, 3, 2, 1}, {1, 2, 3, 0}};

// MSB-first bit writer into a buffer pre-sized for the worst case, so the hot
// loop never checks capacity. Flushes 32 bits at a time.
class BitWriter {
public:
    explicit BitWriter(uint8_t* out) : p_(out), begin_(out) {}

    void put(uint32_t value, int bits) {  // bits <= 32, value < 2^bits
        acc_ = (acc_ << bits) | value;
        n_ += bits;
        // Branchless flush: always store the top 32 pending bits, advance only
        // when 32 are complete (the buffer has slack for the extra store).
        const int full = n_ >= 32;
        const uint32_t w = static_cast<uint32_t>(acc_ >> (n_ - 32 * full));
        const uint32_t be = __builtin_bswap32(w);
        std::memcpy(p_, &be, 4);
        p_ += 4 * full;
        n_ -= 32 * full;
    }

    size_t finish() {
        while (n_ >= 8) { n_ -= 8; *p_++ = static_cast<uint8_t>(acc_ >> n_); }
        if (n_) *p_++ = static_cast<uint8_t>(acc_ << (8 - n_));
        n_ = 0;
        return static_cast<size_t>(p_ - begin_);
    }

private:
    uint8_t* p_;
    uint8_t* begin_;
    uint64_t acc_ = 0;
    int n_ = 0;
};

void putU16(std::vector<uint8_t>& o, uint16_t v) { o.push_back(v & 0xFF); o.push_back(v >> 8); }
void putU32(std::vector<uint8_t>& o, uint32_t v) {
    for (int i = 0; i < 4; ++i) o.push_back(static_cast<uint8_t>(v >> (8 * i)));
}

constexpr int kBlock = 16;
constexpr int kMaxBlockK = 16;

inline int32_t med(int32_t a, int32_t b, int32_t c) {
    const int32_t mx = std::max(a, b), mn = std::min(a, b);
    return c >= mx ? mn : c <= mn ? mx : a + b - c;
}

inline uint32_t zigzag(int32_t e) { return e >= 0 ? 2u * uint32_t(e) : 2u * uint32_t(-e) - 1u; }

// SPEC 3.4: Rice parameter from the causal block sums (`up` < 0 = absent).
inline int blockK(int64_t up, int64_t left, uint32_t riceInit) {
    int64_t s;
    if (up >= 0 && left >= 0) s = up + left;
    else if (up >= 0) s = 2 * up;
    else if (left >= 0) s = 2 * left;
    else s = int64_t{riceInit} * 2 * kBlock;
    const uint64_t v = (static_cast<uint64_t>(s) * 11) >> 4;
    return std::min(static_cast<int>(std::bit_width((v >> 5) + 1)) - 1, kMaxBlockK);
}

inline void putCode(BitWriter& bw, uint32_t u, int k) {
    const uint32_t q = u >> k;
    if (q < kRiceLimit) [[likely]] {
        if (q + 1 + k <= 32) {
            bw.put((1u << k) | (u & ((1u << k) - 1)), static_cast<int>(q) + 1 + k);
        } else {
            bw.put(1, static_cast<int>(q) + 1);
            bw.put(u & ((1u << k) - 1), k);
        }
    } else {
        bw.put(1, kRiceLimit + 1);
        bw.put(u & ((1u << kEscapeBits) - 1), kEscapeBits);
    }
}

// Codes `n` samples sharing Rice parameter k. Common case: two code words
// with q < 24 fit together in one 32-bit put, halving the bit-writer chain.
inline void putBlock(BitWriter& bw, const uint32_t* u, int n, int k) {
    const uint32_t lowMask = (1u << k) - 1, mark = 1u << k;
    int x = 0;
    for (; x + 2 <= n; x += 2) {
        const uint32_t q0 = u[x] >> k, q1 = u[x + 1] >> k;
        const uint32_t len0 = q0 + 1 + k, len1 = q1 + 1 + k;
        if (q0 < kRiceLimit && q1 < kRiceLimit && len0 + len1 <= 32) [[likely]] {
            const uint32_t c0 = mark | (u[x] & lowMask), c1 = mark | (u[x + 1] & lowMask);
            bw.put(len1 == 32 ? c1 : (c0 << len1) | c1, static_cast<int>(len0 + len1));
        } else {
            putCode(bw, u[x], k);
            putCode(bw, u[x + 1], k);
        }
    }
    if (x < n) putCode(bw, u[x], k);
}

// `u` receives the zigzag residuals of the plane, `sums` the block sums of
// the previous and current row.
void encodePlane(BitWriter& bw, const int32_t* p, int w, int h, int32_t firstPred,
                 uint32_t riceInit, std::vector<uint32_t>& u, std::vector<int64_t>& sums) {
    const int blocks = (w + kBlock - 1) / kBlock;
    u.resize(w);
    sums.assign(2 * blocks, -1);
    int64_t* prevSums = sums.data();
    int64_t* curSums = sums.data() + blocks;
    for (int y = 0; y < h; ++y) {
        const int32_t* row = p + y * w;
        const int32_t* up = row - w;
        // Residuals for the whole row first: no dependency on coding state,
        // so the compiler vectorises the interior loop.
        if (y == 0) {
            u[0] = zigzag(row[0] - firstPred);
            for (int x = 1; x < w; ++x) u[x] = zigzag(row[x] - row[x - 1]);
        } else {
            u[0] = zigzag(row[0] - up[0]);
            int x = 1;
#if defined(__ARM_NEON) && defined(__aarch64__)
            for (; x + 4 <= w; x += 4) {
                const int32x4_t a = vld1q_s32(row + x - 1), b = vld1q_s32(up + x), c = vld1q_s32(up + x - 1);
                const int32x4_t mx = vmaxq_s32(a, b), mn = vminq_s32(a, b);
                const int32x4_t grad = vsubq_s32(vaddq_s32(a, b), c);
                const int32x4_t pred = vbslq_s32(vcgeq_s32(c, mx), mn, vbslq_s32(vcleq_s32(c, mn), mx, grad));
                const int32x4_t e = vsubq_s32(vld1q_s32(row + x), pred);
                vst1q_u32(u.data() + x, vreinterpretq_u32_s32(veorq_s32(vshlq_n_s32(e, 1), vshrq_n_s32(e, 31))));
            }
#endif
            for (; x < w; ++x) u[x] = zigzag(row[x] - med(row[x - 1], up[x], up[x - 1]));
        }
        for (int bx = 0; bx < blocks; ++bx) {
            const int x0 = bx * kBlock, n = std::min(kBlock, w - x0);
            int64_t s = 0;
            int x = x0;
#if defined(__ARM_NEON) && defined(__aarch64__)
            if (n == kBlock) {
                const uint32_t* q = u.data() + x0;
                uint64x2_t acc = vpaddlq_u32(vld1q_u32(q));
                acc = vpadalq_u32(acc, vld1q_u32(q + 4));
                acc = vpadalq_u32(acc, vld1q_u32(q + 8));
                acc = vpadalq_u32(acc, vld1q_u32(q + 12));
                s = static_cast<int64_t>(vaddvq_u64(acc));
                x = x0 + n;
            }
#endif
            for (; x < x0 + n; ++x) s += u[x];
            curSums[bx] = s;
        }
        for (int bx = 0; bx < blocks; ++bx) {
            const int x0 = bx * kBlock, n = std::min(kBlock, w - x0);
            const int64_t upS = y > 0 ? prevSums[bx] * kBlock / n : -1;
            const int k = blockK(upS, bx > 0 ? curSums[bx - 1] : -1, riceInit);
#ifdef CRAW_PROFILE_SKIP_EMIT
            bw.put(k & 1, 1);
#else
            putBlock(bw, u.data() + x0, n, k);
#endif
        }
        std::swap(prevSums, curSums);
    }
}

struct TileJob {
    const uint16_t* raw;
    size_t stride;
    const FrameDesc* d;
    uint32_t riceInit;
};

// Splits a tile into the four coded planes (SPEC 3.2): G1, G2-G1, R-G1, B-G1,
// and tracks the largest raw sample for the bit-depth check.
void extractTile(const TileJob& j, const int* L, uint32_t tx, uint32_t ty, uint32_t tw, uint32_t th,
                 int32_t* planes, size_t n, uint16_t& maxSample) {
    uint16_t mx = maxSample;
    for (uint32_t y = 0; y < th; ++y) {
        const uint16_t* r0 = j.raw + (2 * (ty + y)) * j.stride + 2 * tx;
        const uint16_t* r1 = r0 + j.stride;
        int32_t* dst[4];
        for (int i = 0; i < 4; ++i) dst[i] = planes + i * n + size_t{y} * tw;
        uint32_t x = 0;
#if defined(__ARM_NEON) && defined(__aarch64__)
        uint16x8_t vmx = vdupq_n_u16(mx);
        for (; x + 8 <= tw; x += 8) {
            const uint16x8x2_t a = vld2q_u16(r0 + 2 * x), b = vld2q_u16(r1 + 2 * x);
            uint16x8_t pos[4] = {a.val[0], a.val[1], b.val[0], b.val[1]};
            vmx = vmaxq_u16(vmx, vmaxq_u16(vmaxq_u16(pos[0], pos[1]), vmaxq_u16(pos[2], pos[3])));
            const uint16x8_t g1 = pos[L[0]];
            vst1q_s32(dst[0] + x, vreinterpretq_s32_u32(vmovl_u16(vget_low_u16(g1))));
            vst1q_s32(dst[0] + x + 4, vreinterpretq_s32_u32(vmovl_high_u16(g1)));
            for (int i = 1; i < 4; ++i) {
                const uint16x8_t v = pos[L[i]];
                vst1q_s32(dst[i] + x, vreinterpretq_s32_u32(vsubl_u16(vget_low_u16(v), vget_low_u16(g1))));
                vst1q_s32(dst[i] + x + 4, vreinterpretq_s32_u32(vsubl_high_u16(v, g1)));
            }
        }
        mx = vmaxvq_u16(vmx);
#endif
        for (; x < tw; ++x) {
            const uint16_t pos[4] = {r0[2 * x], r0[2 * x + 1], r1[2 * x], r1[2 * x + 1]};
            mx = std::max({mx, pos[0], pos[1], pos[2], pos[3]});
            const int32_t g1 = pos[L[0]];
            dst[0][x] = g1;
            for (int i = 1; i < 4; ++i) dst[i][x] = int32_t(pos[L[i]]) - g1;
        }
    }
    maxSample = mx;
}

#ifdef CRAW_PROFILE
}  // namespace
std::atomic<int64_t> gProfNs[3];
namespace {
struct ProfScope {
    int i; std::chrono::steady_clock::time_point t0 = std::chrono::steady_clock::now();
    ~ProfScope() { gProfNs[i] += (std::chrono::steady_clock::now() - t0).count(); }
};
#define CRAW_PROF(i) ProfScope prof_##i{i}
#else
#define CRAW_PROF(i)
#endif

void encodeTile(const TileJob& j, uint32_t tx, uint32_t ty, uint32_t tw, uint32_t th,
                std::vector<int32_t>& planes, std::vector<uint32_t>& u, std::vector<int64_t>& sums,
                uint8_t* scratch, std::vector<uint8_t>& out, uint16_t& maxSample) {
    const int* L = kLayout[static_cast<int>(j.d->cfa)];
    const size_t n = size_t{tw} * th;
    planes.resize(4 * n);
    {
    CRAW_PROF(0);
    extractTile(j, L, tx, ty, tw, th, planes.data(), n, maxSample);
    }

    const size_t stored = 1 + 8 * n;
    scratch[0] = 0;
    BitWriter bw(scratch + 1);
    CRAW_PROF(1);
    for (int i = 0; i < 4; ++i)
        encodePlane(bw, planes.data() + i * n, tw, th,
                    i == 0 ? (1 << (j.d->bitDepth - 1)) : 0, j.riceInit, u, sums);
    const size_t coded = 1 + bw.finish();
    if (coded < stored) {
        out.assign(scratch, scratch + coded);
        return;
    }
    // Stored form uses the untransformed planes.
    out.resize(stored);
    out[0] = 1;
    for (size_t i = 0; i < 4 * n; ++i) {
        const int32_t v = i < n ? planes[i] : planes[i] + planes[i % n];
        out[1 + 2 * i] = uint8_t(v);
        out[2 + 2 * i] = uint8_t(v >> 8);
    }
}

// Worst-case coded tile: 25 prefix + 24 escape bits per sample.
size_t scratchBytes(uint32_t tile) { return 1 + (size_t{4} * tile * tile * 49 + 7) / 8 + 8; }

uint32_t crcRows(const uint16_t* raw, size_t stride, uint32_t width, uint32_t y0, uint32_t y1) {
    uint32_t crc = 0;
    for (uint32_t y = y0; y < y1; ++y) {
        const uint16_t* row = raw + y * stride;
        if constexpr (std::endian::native == std::endian::little) {
            crc = detail::crc32c(row, width * 2, crc);
        } else {
            for (uint32_t x = 0; x < width; ++x) {
                const uint8_t le[2] = {static_cast<uint8_t>(row[x]), static_cast<uint8_t>(row[x] >> 8)};
                crc = detail::crc32c(le, 2, crc);
            }
        }
    }
    return crc;
}

}  // namespace

uint16_t defaultRiceInit(uint8_t bitDepth) {
    return static_cast<uint16_t>(std::max(1, 1 << std::max(bitDepth - 8, 0)) * 4);
}

namespace {

// Encodes all tiles; `emit(header, tiles)` assembles the payload.
template <typename Emit>
void encodeTiles(const uint16_t* raw, size_t stride, const FrameDesc& d, unsigned threads, Emit&& emit) {
    if (!raw || d.width == 0 || d.height == 0 || d.width % 2 || d.height % 2 ||
        d.bitDepth < 1 || d.bitDepth > 16 || d.tile == 0 || stride < d.width ||
        static_cast<int>(d.cfa) > 3)
        throw std::invalid_argument("craw::encodeFrame: invalid frame geometry");

    const uint32_t pw = d.width / 2, ph = d.height / 2;
    const uint32_t tilesX = (pw + d.tile - 1) / d.tile, tilesY = (ph + d.tile - 1) / d.tile;
    const uint32_t count = tilesX * tilesY;
    const TileJob job{raw, stride, &d, d.riceInit ? d.riceInit : defaultRiceInit(d.bitDepth)};

    // Jobs [0, tilesY) checksum one band of image rows each; the rest encode tiles.
    std::vector<std::vector<uint8_t>> tiles(count);
    std::vector<uint32_t> bandCrc(tilesY);
    std::vector<uint16_t> maxSample(threads ? threads : 1, 0);
    std::atomic<uint32_t> next{0};
    auto worker = [&](unsigned id) {
        std::vector<int32_t> planes;
        std::vector<uint32_t> u;
        std::vector<int64_t> sums;
        std::unique_ptr<uint8_t[]> scratch(new uint8_t[scratchBytes(d.tile)]);
        for (uint32_t jb; (jb = next.fetch_add(1)) < tilesY + count;) {
            if (jb < tilesY) {
                const uint32_t y0 = 2 * jb * d.tile, y1 = std::min<uint32_t>(d.height, y0 + 2 * d.tile);
                bandCrc[jb] = crcRows(raw, stride, d.width, y0, y1);
                continue;
            }
            const uint32_t t = jb - tilesY;
            const uint32_t tx = (t % tilesX) * d.tile, ty = (t / tilesX) * d.tile;
            encodeTile(job, tx, ty, std::min<uint32_t>(d.tile, pw - tx), std::min<uint32_t>(d.tile, ph - ty),
                       planes, u, sums, scratch.get(), tiles[t], maxSample[id]);
        }
    };
    threads = std::clamp(threads, 1u, count);
    maxSample.assign(threads, 0);
    std::vector<std::thread> pool;
    for (unsigned i = 1; i < threads; ++i) pool.emplace_back(worker, i);
    worker(0);
    for (auto& t : pool) t.join();

    if (*std::max_element(maxSample.begin(), maxSample.end()) >= (1u << d.bitDepth))
        throw std::invalid_argument("craw::encodeFrame: sample exceeds bit depth");
    uint32_t crc = bandCrc[0];
    for (uint32_t b = 1; b < tilesY; ++b) {
        const uint32_t rows = std::min<uint32_t>(d.height, 2 * (b + 1) * d.tile) - 2 * b * d.tile;
        crc = detail::crc32cCombine(crc, bandCrc[b], uint64_t{rows} * d.width * 2);
    }

    std::vector<uint8_t> head;
    head.reserve(24 + 4 * (size_t{count} + 1));
    head.push_back(1);
    head.push_back(d.bitDepth);
    head.push_back(static_cast<uint8_t>(d.cfa));
    head.push_back(0);
    putU32(head, d.width);
    putU32(head, d.height);
    putU16(head, d.tile);
    putU16(head, static_cast<uint16_t>(job.riceInit));
    putU32(head, crc);
    putU32(head, count);
    uint32_t off = 0;
    putU32(head, 0);
    for (auto& t : tiles) putU32(head, off += static_cast<uint32_t>(t.size()));
    emit(head, tiles);
}

}  // namespace

void encodeFrame(const uint16_t* raw, size_t stride, const FrameDesc& d,
                 std::vector<uint8_t>& out, unsigned threads) {
    encodeTiles(raw, stride, d, threads, [&](const std::vector<uint8_t>& head,
                                             const std::vector<std::vector<uint8_t>>& tiles) {
        out.assign(head.begin(), head.end());
        for (auto& t : tiles) out.insert(out.end(), t.begin(), t.end());
    });
}

size_t encodeFrame(const uint16_t* raw, size_t stride, const FrameDesc& d, uint8_t* dst,
                   size_t capacity, unsigned threads) {
    size_t written = 0;
    encodeTiles(raw, stride, d, threads, [&](const std::vector<uint8_t>& head,
                                             const std::vector<std::vector<uint8_t>>& tiles) {
        size_t total = head.size();
        for (auto& t : tiles) total += t.size();
        if (!dst || total > capacity) throw std::length_error("craw::encodeFrame: destination too small");
        std::memcpy(dst, head.data(), head.size());
        written = head.size();
        for (auto& t : tiles) {
            std::memcpy(dst + written, t.data(), t.size());
            written += t.size();
        }
    });
    return written;
}

}  // namespace craw
