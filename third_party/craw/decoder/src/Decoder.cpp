// SPDX-License-Identifier: Apache-2.0
#include <craw/Decoder.h>

#include "Crc32c.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cstring>
#include <exception>
#include <mutex>
#include <thread>

namespace craw {
namespace {

constexpr int kRiceLimit = 24;
constexpr int kEscapeBits = 24;
constexpr int kBlock = 16;
constexpr int kMaxBlockK = 16;
constexpr size_t kHeaderSize = 24;

// Coded plane order G1, G2, R, B -> position in the 2x2 cell (py * 2 + px).
constexpr int kLayout[4][4] = {{1, 2, 0, 3}, {0, 3, 1, 2}, {0, 3, 2, 1}, {1, 2, 3, 0}};
// Plane index at each cell position: identity for Live codecs (cell order),
// the inverse of kLayout (plane -> position) for codec 1.
constexpr int kIdentity[4] = {0, 1, 2, 3};
constexpr auto kInverse = [] {
    std::array<std::array<int, 4>, 4> inv{};
    for (int c = 0; c < 4; ++c)
        for (int i = 0; i < 4; ++i) inv[c][kLayout[c][i]] = i;
    return inv;
}();

uint16_t rd16(const uint8_t* p) { return uint16_t(p[0] | p[1] << 8); }
uint32_t rd32(const uint8_t* p) { return uint32_t(p[0]) | p[1] << 8 | p[2] << 16 | uint32_t(p[3]) << 24; }

// MSB-first reader. Reading past the end yields zero bits and sets `overrun`,
// which the caller turns into a DecodeError.
class BitReader {
public:
    BitReader(const uint8_t* p, size_t n) : p_(p), end_(p + n), limit_(uint64_t{n} * 8) { refill(); }

    uint32_t get(int bits) {
        if (bits == 0) return 0;
        if (n_ < bits) refill();
        const uint32_t v = static_cast<uint32_t>(acc_ >> (64 - bits));
        consume(bits);
        return v;
    }

    // Counts zero bits before the next 1 bit, consuming both; at most `limit` zeros.
    int zeros(int limit) {
        if (n_ < limit + 1) refill();
        const int z = acc_ ? std::countl_zero(acc_) : 64;
        if (z >= limit) {
            consume(limit);
            if (get(1) != 1) overrun = true;
            return limit;
        }
        consume(z + 1);
        return z;
    }

    bool overrun = false;

private:
    void consume(int bits) {
        acc_ <<= bits;
        n_ -= bits;
        used_ += bits;
        if (used_ > limit_) overrun = true;
    }

    void refill() {
        for (; n_ <= 56; n_ += 8) acc_ |= uint64_t{p_ < end_ ? *p_++ : uint8_t{0}} << (56 - n_);
    }

    const uint8_t* p_;
    const uint8_t* end_;
    uint64_t limit_, used_ = 0, acc_ = 0;
    int n_ = 0;
};

inline int32_t med(int32_t a, int32_t b, int32_t c) {
    const int32_t mx = std::max(a, b), mn = std::min(a, b);
    return c >= mx ? mn : c <= mn ? mx : a + b - c;
}

// SPEC 3.4: Rice parameter from causal block sums (`up` / `left` < 0 = absent).
inline int blockK(int64_t up, int64_t left, uint32_t riceInit) {
    int64_t s;
    if (up >= 0 && left >= 0) s = up + left;
    else if (up >= 0) s = 2 * up;
    else if (left >= 0) s = 2 * left;
    else s = int64_t{riceInit} * 2 * kBlock;
    const uint64_t v = (static_cast<uint64_t>(s) * 11) >> 4;
    return std::min(static_cast<int>(std::bit_width((v >> 5) + 1)) - 1, kMaxBlockK);
}

void decodePlane(BitReader& br, int32_t* p, int w, int h, int32_t firstPred, uint32_t riceInit,
                 std::vector<int64_t>& sums) {
    const int blocks = (w + kBlock - 1) / kBlock;
    sums.assign(2 * blocks, -1);
    int64_t* prevSums = sums.data();
    int64_t* curSums = sums.data() + blocks;
    for (int y = 0; y < h; ++y) {
        int32_t* row = p + y * w;
        const int32_t* up = row - w;
        for (int bx = 0; bx < blocks; ++bx) {
            const int x0 = bx * kBlock, n = std::min(kBlock, w - x0);
            const int k = blockK(y > 0 ? prevSums[bx] * kBlock / n : -1, bx > 0 ? curSums[bx - 1] : -1,
                                 riceInit);
            int64_t s = 0;
            for (int x = x0; x < x0 + n; ++x) {
                const int q = br.zeros(kRiceLimit);
                const uint32_t u = q < kRiceLimit ? (uint32_t(q) << k) | br.get(k) : br.get(kEscapeBits);
                s += u;
                const int32_t e = (u & 1) ? -static_cast<int32_t>((u + 1) >> 1) : static_cast<int32_t>(u >> 1);
                int32_t pred;
                if (y == 0) pred = x == 0 ? firstPred : row[x - 1];
                else if (x == 0) pred = up[0];
                else pred = med(row[x - 1], up[x], up[x - 1]);
                row[x] = pred + e;
            }
            curSums[bx] = s;
        }
        std::swap(prevSums, curSums);
    }
}

// Unpacks `cnt` fixed-width (w <= 18) little-endian bit fields from `data`.
// With 8 readable bytes past the block, each field is one unaligned 64-bit
// load at its own bit offset: fields are independent, so the loop pipelines
// instead of refilling a bit buffer byte by byte.
inline void unpackFields(const uint8_t* data, const uint8_t* end, uint32_t* dst, uint32_t cnt, int w) {
    if (w == 0) {
        std::fill(dst, dst + cnt, 0u);
        return;
    }
    const uint32_t mask = (1u << w) - 1;
    const size_t bytes = (size_t{cnt} * w + 7) / 8;
    if (static_cast<size_t>(end - data) >= bytes + 8) {
        for (uint32_t i = 0; i < cnt; ++i) {
            const size_t bp = size_t{i} * w;
            uint64_t v;
            std::memcpy(&v, data + (bp >> 3), 8);
            if constexpr (std::endian::native == std::endian::big) v = __builtin_bswap64(v);
            dst[i] = static_cast<uint32_t>(v >> (bp & 7)) & mask;
        }
        return;
    }
    uint64_t acc = 0;
    int bits = 0;
    for (uint32_t i = 0; i < cnt; ++i) {
        while (bits < w) { acc |= uint64_t{*data++} << bits; bits += 8; }
        dst[i] = static_cast<uint32_t>(acc) & mask;
        acc >>= w;
        bits -= w;
    }
}

// Zig-zag residual: 0, -1, 1, -2, 2, ...
inline int32_t unzig(uint32_t u) { return (u & 1) ? -static_cast<int32_t>((u + 1) >> 1) : static_cast<int32_t>(u >> 1); }

// CRAW-Live tile (SPEC 3.6) into four cell-order planes of tw x th.
// CRAW-Live v2 tile (SPEC 3.7): 4-neighbour prediction, plain or PFOR blocks.
void decodeTileLive2(const uint8_t* d, size_t len, uint32_t tw, uint32_t th, int32_t first, int32_t* planes) {
    if (len < 5) throw DecodeError("craw: truncated live2 tile");
    const uint32_t nibBytes = rd32(d + 1);
    if (nibBytes > len - 5) throw DecodeError("craw: bad live2 nibble length");
    const uint8_t* nib = d + 5;
    const uint8_t* data = nib + nibBytes;
    const uint8_t* end = d + len;
    size_t ni = 0;
    auto nextNibble = [&]() -> int {
        if (ni >= size_t{nibBytes} * 2) throw DecodeError("craw: live2 nibbles overrun");
        const int v = (nib[ni >> 1] >> (4 * (ni & 1))) & 15;
        ++ni;
        return v;
    };
    auto width = [&]() { const int v = nextNibble(); return v < 15 ? v : 15 + nextNibble(); };
    auto unpack = [&](uint32_t* dst, uint32_t cnt, int w) {
        if (w > 18) throw DecodeError("craw: bad live2 width");
        const size_t bytes = (size_t{cnt} * w + 7) / 8;
        if (bytes > static_cast<size_t>(end - data)) throw DecodeError("craw: live2 data overrun");
        unpackFields(data, end, dst, cnt, w);
        data += bytes;
    };
    const size_t n = size_t{tw} * th;
    uint32_t u[kBlock];
    // Residuals of one row for all four cell planes, parsed first (the bit
    // stream is cell-major); the four planes' left-neighbour chains are then
    // independent and run interleaved.
    thread_local std::vector<int32_t> rbuf, sbuf;
    rbuf.resize(4 * size_t{tw});
    sbuf.resize(4 * size_t{tw});
    for (uint32_t y = 0; y < th; ++y) {
        for (int cell = 0; cell < 4; ++cell) {
            int32_t* r = rbuf.data() + cell * size_t{tw};
            for (uint32_t x0 = 0; x0 < tw; x0 += kBlock) {
                const uint32_t cnt = std::min<uint32_t>(kBlock, tw - x0);
                const int t = nextNibble();
                if (t != 14) {
                    unpack(u, cnt, t < 15 ? t : 14 + nextNibble());
                } else {
                    const int k = width();
                    const int ne = nextNibble();
                    const int hb = width();
                    int idx[16];
                    for (int e = 0; e < ne; ++e) idx[e] = nextNibble();
                    unpack(u, cnt, k);
                    uint32_t high[16];
                    unpack(high, static_cast<uint32_t>(ne), hb);
                    for (int e = 0; e < ne; ++e) {
                        if (idx[e] >= static_cast<int>(cnt)) throw DecodeError("craw: bad live2 exception index");
                        u[idx[e]] |= high[e] << k;
                    }
                }
                for (uint32_t i = 0; i < cnt; ++i) r[x0 + i] = unzig(u[i]);
            }
        }
        int32_t* rows[4];
        for (int cell = 0; cell < 4; ++cell) rows[cell] = planes + cell * n + size_t{y} * tw;
        if (y == 0) {
            for (int cell = 0; cell < 4; ++cell) {
                const int32_t* r = rbuf.data() + cell * size_t{tw};
                int32_t prev = first;
                for (uint32_t x = 0; x < tw; ++x) rows[cell][x] = prev = prev + r[x];
            }
            continue;
        }
        // U + UL + UR + 2 per sample (UR clamps to U at the tile's right edge).
        for (int cell = 0; cell < 4; ++cell) {
            const int32_t* up = rows[cell] - tw;
            int32_t* sv = sbuf.data() + cell * size_t{tw};
            for (uint32_t x = 1; x + 1 < tw; ++x) sv[x] = up[x] + up[x - 1] + up[x + 1] + 2;
            if (tw > 1) sv[tw - 1] = 2 * up[tw - 1] + up[tw - 2] + 2;
            rows[cell][0] = up[0] + rbuf[cell * size_t{tw}];
        }
        const int32_t *r0 = rbuf.data(), *r1 = r0 + tw, *r2 = r1 + tw, *r3 = r2 + tw;
        const int32_t *s0 = sbuf.data(), *s1 = s0 + tw, *s2 = s1 + tw, *s3 = s2 + tw;
        int32_t l0 = rows[0][0], l1 = rows[1][0], l2 = rows[2][0], l3 = rows[3][0];
        for (uint32_t x = 1; x < tw; ++x) {
            rows[0][x] = l0 = ((l0 + s0[x]) >> 2) + r0[x];
            rows[1][x] = l1 = ((l1 + s1[x]) >> 2) + r1[x];
            rows[2][x] = l2 = ((l2 + s2[x]) >> 2) + r2[x];
            rows[3][x] = l3 = ((l3 + s3[x]) >> 2) + r3[x];
        }
    }
}

void decodeTileLive(const uint8_t* d, size_t len, uint32_t tw, uint32_t th, int32_t first, int32_t* planes) {
    if (len < 5) throw DecodeError("craw: truncated live tile");
    const uint32_t nibBytes = rd32(d + 1);
    if (nibBytes > len - 5) throw DecodeError("craw: bad live nibble length");
    const uint8_t* nib = d + 5;
    const uint8_t* data = nib + nibBytes;
    const uint8_t* end = d + len;
    size_t ni = 0;
    auto nextNibble = [&]() -> int {
        if (ni >= size_t{nibBytes} * 2) throw DecodeError("craw: live nibbles overrun");
        const int v = (nib[ni >> 1] >> (4 * (ni & 1))) & 15;
        ++ni;
        return v;
    };
    const size_t n = size_t{tw} * th;
    for (uint32_t y = 0; y < th; ++y)
        for (int cell = 0; cell < 4; ++cell) {
            int32_t* row = planes + cell * n + size_t{y} * tw;
            int32_t prev = y == 0 ? first : row[-static_cast<ptrdiff_t>(tw)];
            for (uint32_t x0 = 0; x0 < tw; x0 += kBlock) {
                const uint32_t cnt = std::min<uint32_t>(kBlock, tw - x0);
                int w = nextNibble();
                if (w == 15) w += nextNibble();
                if (w > 17) throw DecodeError("craw: bad live width");
                const size_t bytes = (size_t{cnt} * w + 7) / 8;
                if (bytes > static_cast<size_t>(end - data)) throw DecodeError("craw: live data overrun");
                uint32_t u[kBlock];
                unpackFields(data, end, u, cnt, w);
                for (uint32_t i = 0; i < cnt; ++i) row[x0 + i] = prev = prev + unzig(u[i]);
                data += bytes;
            }
        }
}

}  // namespace

FrameInfo probeFrame(const uint8_t* p, size_t size) {
    if (!p || size < kHeaderSize) throw DecodeError("craw: payload too small");
    if (p[0] < 1 || p[0] > 3) throw DecodeError("craw: unknown codec id");
    FrameInfo f;
    f.bitDepth = p[1];
    if (p[2] > 4 || (p[2] == 4 && p[0] == 1)) throw DecodeError("craw: bad CFA");
    f.cfa = static_cast<Cfa>(p[2]);
    f.width = rd32(p + 4);
    f.height = rd32(p + 8);
    f.tile = rd16(p + 12);
    f.riceInit = rd16(p + 14);
    f.crc = rd32(p + 16);
    f.codec = p[0];
    f.hasCrc = (p[3] & 1) == 0;
    if (f.bitDepth < 1 || f.bitDepth > 16 || f.width == 0 || f.height == 0 || f.width % 2 ||
        f.height % 2 || f.tile == 0 || f.width > 65536 || f.height > 65536)
        throw DecodeError("craw: bad frame header");
    return f;
}

FrameInfo decodeFrame(const uint8_t* p, size_t size, std::vector<uint16_t>& out, unsigned threads) {
    const FrameInfo f = probeFrame(p, size);
    const uint32_t pw = f.width / 2, ph = f.height / 2;
    const uint32_t tilesX = (pw + f.tile - 1) / f.tile, tilesY = (ph + f.tile - 1) / f.tile;
    const uint32_t count = rd32(p + 20);
    if (count != tilesX * tilesY) throw DecodeError("craw: tile count mismatch");
    const size_t tableEnd = kHeaderSize + 4 * (size_t{count} + 1);
    if (tableEnd > size) throw DecodeError("craw: truncated tile table");
    const uint8_t* table = p + kHeaderSize;
    const uint8_t* base = p + tableEnd;
    const size_t dataSize = size - tableEnd;
    for (uint32_t t = 0; t < count; ++t)
        if (rd32(table + 4 * t) > rd32(table + 4 * (t + 1)) || rd32(table + 4 * (t + 1)) > dataSize)
            throw DecodeError("craw: bad tile offsets");

    out.resize(size_t{f.width} * f.height);
    std::atomic<uint32_t> next{0};
    std::exception_ptr error;
    std::mutex errorMutex;

    auto worker = [&] {
        std::vector<int32_t> planes;
        std::vector<int64_t> sums;
        for (uint32_t t; (t = next.fetch_add(1)) < count;) {
            try {
                const uint32_t tx = (t % tilesX) * f.tile, ty = (t / tilesX) * f.tile;
                const uint32_t tw = std::min<uint32_t>(f.tile, pw - tx), th = std::min<uint32_t>(f.tile, ph - ty);
                const size_t n = size_t{tw} * th;
                const uint8_t* d = base + rd32(table + 4 * t);
                const size_t len = rd32(table + 4 * (t + 1)) - rd32(table + 4 * t);
                if (len < 1) throw DecodeError("craw: empty tile");
                planes.resize(4 * n);
                const bool live = f.codec == 2 || f.codec == 3;
                if (d[0] == 1) {
                    if (len != 1 + 8 * n) throw DecodeError("craw: bad stored tile size");
                    for (size_t i = 0; i < 4 * n; ++i) planes[i] = rd16(d + 1 + 2 * i);
                } else if (d[0] == 0 && f.codec == 3) {
                    decodeTileLive2(d, len, tw, th, 1 << (f.bitDepth - 1), planes.data());
                } else if (d[0] == 0 && live) {
                    decodeTileLive(d, len, tw, th, 1 << (f.bitDepth - 1), planes.data());
                } else if (d[0] == 0) {
                    BitReader br(d + 1, len - 1);
                    for (int i = 0; i < 4; ++i)
                        decodePlane(br, planes.data() + i * n, tw, th, i == 0 ? (1 << (f.bitDepth - 1)) : 0,
                                    f.riceInit, sums);
                    if (br.overrun) throw DecodeError("craw: tile bit stream overrun");
                    for (int i = 1; i < 4; ++i)
                        for (size_t s = 0; s < n; ++s) planes[i * n + s] += planes[s];
                } else {
                    throw DecodeError("craw: unknown tile mode");
                }
                const uint32_t maxv = (1u << f.bitDepth) - 1;
                const int* planeAt = live ? kIdentity : kInverse[static_cast<int>(f.cfa) & 3].data();  // plane of each cell position
                uint32_t bad = 0;
                for (int py = 0; py < 2; ++py) {
                    const int32_t* a = planes.data() + planeAt[py * 2] * n;
                    const int32_t* b = planes.data() + planeAt[py * 2 + 1] * n;
                    for (uint32_t y = 0; y < th; ++y) {
                        uint16_t* dst = out.data() + size_t(2 * (ty + y) + py) * f.width + 2 * tx;
                        const int32_t *ra = a + size_t{y} * tw, *rb = b + size_t{y} * tw;
                        for (uint32_t x = 0; x < tw; ++x) {
                            const uint32_t va = static_cast<uint32_t>(ra[x]), vb = static_cast<uint32_t>(rb[x]);
                            bad |= (va > maxv) | (vb > maxv);  // negative wraps above maxv
                            dst[2 * x] = static_cast<uint16_t>(va);
                            dst[2 * x + 1] = static_cast<uint16_t>(vb);
                        }
                    }
                }
                if (bad) throw DecodeError("craw: sample out of range");
            } catch (...) {
                std::lock_guard lock(errorMutex);
                if (!error) error = std::current_exception();
                next = count;
            }
        }
    };
    threads = std::clamp(threads, 1u, count);
    std::vector<std::thread> pool;
    for (unsigned i = 1; i < threads; ++i) pool.emplace_back(worker);
    worker();
    for (auto& t : pool) t.join();
    if (error) std::rethrow_exception(error);

    if (!f.hasCrc) return f;
    uint32_t crc = 0;
    if constexpr (std::endian::native == std::endian::little) {
        crc = detail::crc32c(out.data(), out.size() * 2);
    } else {
        for (uint16_t v : out) {
            const uint8_t le[2] = {uint8_t(v), uint8_t(v >> 8)};
            crc = detail::crc32c(le, 2, crc);
        }
    }
    if (crc != f.crc) throw DecodeError("craw: frame CRC mismatch");
    return f;
}

}  // namespace craw
