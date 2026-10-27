// SPDX-License-Identifier: Apache-2.0
#include <craw/Decoder.h>

#include "Crc32c.h"

#include <algorithm>
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

}  // namespace

FrameInfo probeFrame(const uint8_t* p, size_t size) {
    if (!p || size < kHeaderSize) throw DecodeError("craw: payload too small");
    if (p[0] != 1) throw DecodeError("craw: unknown codec id");
    FrameInfo f;
    f.bitDepth = p[1];
    if (p[2] > 3) throw DecodeError("craw: bad CFA");
    f.cfa = static_cast<Cfa>(p[2]);
    f.width = rd32(p + 4);
    f.height = rd32(p + 8);
    f.tile = rd16(p + 12);
    f.riceInit = rd16(p + 14);
    f.crc = rd32(p + 16);
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
    const int* L = kLayout[static_cast<int>(f.cfa)];
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
                if (d[0] == 1) {
                    if (len != 1 + 8 * n) throw DecodeError("craw: bad stored tile size");
                    for (size_t i = 0; i < 4 * n; ++i) planes[i] = rd16(d + 1 + 2 * i);
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
                const int32_t maxv = (1 << f.bitDepth) - 1;
                for (int i = 0; i < 4; ++i) {
                    const int py = L[i] >> 1, px = L[i] & 1;
                    const int32_t* src = planes.data() + i * n;
                    for (uint32_t y = 0; y < th; ++y) {
                        uint16_t* dst = out.data() + size_t(2 * (ty + y) + py) * f.width + 2 * tx + px;
                        for (uint32_t x = 0; x < tw; ++x) {
                            const int32_t v = src[y * tw + x];
                            if (v < 0 || v > maxv) throw DecodeError("craw: sample out of range");
                            dst[2 * x] = static_cast<uint16_t>(v);
                        }
                    }
                }
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
