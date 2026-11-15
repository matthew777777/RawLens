#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

namespace rawrcam::imaging {

// Fast block-CFA to Bayer for large stills (Xiaomi 200 MP stills arrive as 4x4 same-colour blocks).
// 1. Flat-field the fixed per-position gain pattern inside the blocks (PDAF / shared-lens sub-pixels),
//    measured on the frame itself.
// 2. Block means form a Bayer mosaic at block resolution. Green is interpolated along the smoother direction
//    (soft, inverse-gradient weights); red and blue are half colour-difference to green, half plain bilinear.
//    Measured against the old bilinear planes on synthetic scenes: +0.6 dB for the 200 MP Bayer.
// 3. Each output pixel keeps its own measured detail and takes the wanted colour by ratio:
//    out = want(x, y) * (pixel + e) / (own(x, y) + e), planes interpolated between block centres.
// The same model also gives the JPEG's 50 MP Bayer straight from the blocks (binBlockCfaToBayer): each output
// pixel is a 2x2 own-colour mean taking the wanted colour by the same ratio. Measured: +2.5 dB over
// remosaicking to 200 MP and binning that, and no 200 MP pass needed for it.
// One pass over the frame per output, rows split across threads. Clipped pixels are left as they are.
namespace cfafast {
// RawLens adaptation: reflected block borders preserve CFA parity.
inline int reflectBlock(int v, int size) {
    if (v < 0) return -v;
    if (v >= size) return 2 * size - 2 - v;
    return v;
}

inline int colourAt(uint32_t order, uint32_t py, uint32_t px) {
    // order: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. Returns 0 R, 1 G, 2 B.
    static constexpr uint32_t ry[4] = {0, 0, 1, 1}, rx[4] = {0, 1, 0, 1};
    const uint32_t o = order & 3u;
    if (py == ry[o] && px == rx[o]) return 0;
    if (py != ry[o] && px != rx[o]) return 2;
    return 1;
}

template <typename F>
inline void parallelRows(uint32_t rows, uint32_t threads, F&& fn) {
    threads = std::max(1u, std::min(threads, rows));
    std::vector<std::thread> pool;
    const uint32_t step = (rows + threads - 1) / threads;
    for (uint32_t t = 0; t < threads; ++t) {
        const uint32_t a = t * step, b = std::min(rows, a + step);
        if (a >= b) break;
        pool.emplace_back([&fn, a, b] { fn(a, b); });
    }
    for (auto& th : pool) th.join();
}
}  // namespace cfafast

// Flat-field tables and block-resolution colour planes of one block-CFA frame.
struct BlockCfaModel {
    static constexpr uint32_t TX = 16, TY = 12;
    uint32_t w = 0, h = 0, n = 0, bw = 0, bh = 0, order = 0;
    float black = 0.0f, white = 65535.0f, e = 1.0f;
    size_t nn = 0, tableSize = 0;
    std::vector<float> tileInv;
    std::vector<uint32_t> colTile, rowTile;
    std::vector<float> colF, rowF;
    std::array<std::vector<float>, 3> planes;

    bool valid() const { return n >= 2 && bw >= 4 && bh >= 4; }
    float invGainAt(uint32_t bx, uint32_t by, size_t k) const {
        const uint32_t tx0 = colTile[bx], ty0 = rowTile[by];
        const float fxt = colF[bx], fyt = rowF[by];
        const float* t00 = tileInv.data() + (size_t(ty0) * TX + tx0) * tableSize;
        const float* t01 = t00 + tableSize;
        const float* t10 = t00 + size_t(TX) * tableSize;
        const float* t11 = t10 + tableSize;
        const float top = t00[k] + (t01[k] - t00[k]) * fxt;
        const float bot = t10[k] + (t11[k] - t10[k]) * fxt;
        return top + (bot - top) * fyt;
    }
    // Plane c bilinearly between block centres; (sy, sx) in block units from the first centre.
    float sample(int c, float sy, float sx) const {
        const int y0 = std::clamp(int(std::floor(sy)), 0, int(bh) - 2);
        const int x0 = std::clamp(int(std::floor(sx)), 0, int(bw) - 2);
        const float fy = std::clamp(sy - float(y0), 0.0f, 1.0f), fx = std::clamp(sx - float(x0), 0.0f, 1.0f);
        const float* p = planes[c].data() + size_t(y0) * bw + x0;
        const float top = p[0] + (p[1] - p[0]) * fx;
        const float bot = p[bw] + (p[bw + 1] - p[bw]) * fx;
        return top + (bot - top) * fy;
    }
};

inline BlockCfaModel buildBlockCfaModel(const uint16_t* raw, uint32_t w, uint32_t h, uint32_t n, uint32_t order,
                                        float black, float white, uint32_t threads) {
    using namespace cfafast;
    BlockCfaModel m;
    if (!raw || n < 2 || w < 4 * n || h < 4 * n) return m;
    m.w = w;
    m.h = h;
    m.n = n;
    m.order = order;
    m.bw = w / n;
    m.bh = h / n;
    m.black = black;
    m.white = white <= black ? 65535.0f : white;
    m.e = std::max(1.0f, 0.004f * (m.white - black));
    m.nn = size_t(n) * n;
    m.tableSize = 3 * m.nn;
    const uint32_t bw = m.bw, bh = m.bh, TX = BlockCfaModel::TX, TY = BlockCfaModel::TY;
    const size_t nn = m.nn, tableSize = m.tableSize;
    white = m.white;

    // 1. Intra-block gain per (block colour, position), per tile of a TX x TY grid (the pattern drifts across
    //    the field), mid-tone unclipped blocks only; interpolated between tile centres below.
    std::vector<double> sum(size_t(TX) * TY * tableSize, 0.0);
    std::vector<double> msum(size_t(TX) * TY * tableSize, 0.0);
    std::vector<uint32_t> cnt(size_t(TX) * TY * tableSize, 0);
    parallelRows(TY, threads, [&](uint32_t ta, uint32_t tb) {
        for (uint32_t ty = ta; ty < tb; ++ty) {
            const uint32_t by0 = ty * bh / TY, by1 = (ty + 1) * bh / TY;
            for (uint32_t by = by0; by < by1; ++by)
                for (uint32_t bx = 0; bx < bw; ++bx) {
                    const uint32_t tx = std::min(TX - 1, bx * TX / bw);
                    float mean = 0.0f, mx = 0.0f;
                    for (uint32_t iy = 0; iy < n; ++iy)
                        for (uint32_t ix = 0; ix < n; ++ix) {
                            const float v = float(raw[size_t(by * n + iy) * w + bx * n + ix]);
                            mean += v;
                            mx = std::max(mx, v);
                        }
                    mean = mean / float(nn) - black;
                    // Ratio of sums (pixel sum / block-mean sum): dim channels (red under warm light) still count.
                    if (mean < 2.0f || mx >= white - 1.0f) continue;
                    const int c = colourAt(order, by & 1u, bx & 1u);
                    const size_t base = (size_t(ty) * TX + tx) * tableSize + size_t(c) * nn;
                    for (uint32_t iy = 0; iy < n; ++iy)
                        for (uint32_t ix = 0; ix < n; ++ix) {
                            sum[base + iy * n + ix] += float(raw[size_t(by * n + iy) * w + bx * n + ix]) - black;
                            msum[base + iy * n + ix] += mean;
                            ++cnt[base + iy * n + ix];
                        }
                }
        }
    });
    // Per tile: normalise each colour's gains to mean 1; thin tiles borrow the frame-wide table.
    std::vector<double> gsum(tableSize, 0.0), gmsum(tableSize, 0.0);
    std::vector<uint64_t> gcnt(tableSize, 0);
    for (size_t t = 0; t < size_t(TX) * TY; ++t)
        for (size_t k = 0; k < tableSize; ++k) {
            gsum[k] += sum[t * tableSize + k];
            gmsum[k] += msum[t * tableSize + k];
            gcnt[k] += cnt[t * tableSize + k];
        }
    const auto buildTable = [&](const double* s, const double* ms, const auto* c, float* outInv, uint64_t minCount) {
        bool ok = true;
        for (int col = 0; col < 3; ++col) {
            double mean = 0.0;
            bool colOk = true;
            for (size_t i = 0; i < nn; ++i) {
                const size_t k = size_t(col) * nn + i;
                if (uint64_t(c[k]) < minCount || ms[k] <= 0.0) colOk = false;
                else mean += s[k] / ms[k];
            }
            if (!colOk || mean <= 0.0) {
                ok = false;
                continue;
            }
            mean /= double(nn);
            for (size_t i = 0; i < nn; ++i) {
                const size_t k = size_t(col) * nn + i;
                const double g = (s[k] / ms[k]) / mean;
                outInv[k] = (g > 0.5 && g < 2.0) ? float(1.0 / g) : 1.0f;
            }
        }
        return ok;
    };
    std::vector<float> globalInv(tableSize, 1.0f);
    buildTable(gsum.data(), gmsum.data(), gcnt.data(), globalInv.data(), 64);
    m.tileInv.assign(size_t(TX) * TY * tableSize, 1.0f);
    for (size_t t = 0; t < size_t(TX) * TY; ++t) {
        float* dst = m.tileInv.data() + t * tableSize;
        std::copy(globalInv.begin(), globalInv.end(), dst);
        std::vector<float> local(tableSize, 1.0f);
        if (buildTable(sum.data() + t * tableSize, msum.data() + t * tableSize, cnt.data() + t * tableSize, local.data(), 256))
            std::copy(local.begin(), local.end(), dst);
    }
    // Tile-centre bilinear weights, per block row / column.
    const auto tileAxis = [](uint32_t b, uint32_t blocks, uint32_t tiles, uint32_t& t0, float& f) {
        const float s = (float(b) + 0.5f) * float(tiles) / float(blocks) - 0.5f;
        const int i = std::clamp(int(std::floor(s)), 0, int(tiles) - 2);
        t0 = uint32_t(i);
        f = std::clamp(s - float(i), 0.0f, 1.0f);
    };
    m.colTile.resize(bw);
    m.rowTile.resize(bh);
    m.colF.resize(bw);
    m.rowF.resize(bh);
    for (uint32_t bx = 0; bx < bw; ++bx) tileAxis(bx, bw, TX, m.colTile[bx], m.colF[bx]);
    for (uint32_t by = 0; by < bh; ++by) tileAxis(by, bh, TY, m.rowTile[by], m.rowF[by]);

    // 2. Flat-fielded block means.
    std::vector<float> means(size_t(bw) * bh);
    parallelRows(bh, threads, [&](uint32_t a, uint32_t b) {
        for (uint32_t by = a; by < b; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                const int c = colourAt(order, by & 1u, bx & 1u);
                float mean = 0.0f;
                for (uint32_t iy = 0; iy < n; ++iy)
                    for (uint32_t ix = 0; ix < n; ++ix)
                        mean += (float(raw[size_t(by * n + iy) * w + bx * n + ix]) - black) *
                                m.invGainAt(bx, by, size_t(c) * nn + iy * n + ix);
                means[size_t(by) * bw + bx] = std::max(0.0f, mean / float(nn));
            }
    });
    const auto at = [&](int y, int x) {
        return means[size_t(reflectBlock(y, int(bh))) * bw + size_t(reflectBlock(x, int(bw)))];
    };
    for (auto& p : m.planes) p.assign(means.size(), 0.0f);
    // Green: soft directional (weights 1 / gradient^2) where the block is not green.
    parallelRows(bh, threads, [&](uint32_t a, uint32_t b) {
        for (uint32_t by = a; by < b; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                const int y = int(by), x = int(bx);
                const float c0 = at(y, x);
                float g = c0;
                if (colourAt(order, by & 1u, bx & 1u) != 1) {
                    const float gh = std::fabs(at(y, x - 1) - at(y, x + 1)) +
                                     std::fabs(2.0f * c0 - at(y, x - 2) - at(y, x + 2)) + 1.0f;
                    const float gv = std::fabs(at(y - 1, x) - at(y + 1, x)) +
                                     std::fabs(2.0f * c0 - at(y - 2, x) - at(y + 2, x)) + 1.0f;
                    const float wh = 1.0f / (gh * gh), wv = 1.0f / (gv * gv);
                    const float hAvg = 0.5f * (at(y, x - 1) + at(y, x + 1));
                    const float vAvg = 0.5f * (at(y - 1, x) + at(y + 1, x));
                    g = std::max(0.0f, (wh * hAvg + wv * vAvg) / (wh + wv));
                }
                m.planes[1][size_t(by) * bw + bx] = g;
            }
    });
    // Red and blue: half colour difference to green, half plain bilinear (3x3 1-2-1 weights).
    parallelRows(bh, threads, [&](uint32_t a, uint32_t b) {
        static constexpr float k[3][3] = {{1, 2, 1}, {2, 4, 2}, {1, 2, 1}};
        const float* G = m.planes[1].data();
        for (uint32_t by = a; by < b; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                const int own = colourAt(order, by & 1u, bx & 1u);
                const size_t i = size_t(by) * bw + bx;
                for (int c : {0, 2}) {
                    if (c == own) {
                        m.planes[c][i] = means[i];
                        continue;
                    }
                    float diff = 0.0f, plain = 0.0f, wt = 0.0f;
                    for (int dy = -1; dy <= 1; ++dy)
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int yy = reflectBlock(int(by) + dy, int(bh));
                            const int xx = reflectBlock(int(bx) + dx, int(bw));
                            if (colourAt(order, uint32_t(yy) & 1u, uint32_t(xx) & 1u) != c) continue;
                            const size_t j = size_t(yy) * bw + xx;
                            const float kw = k[dy + 1][dx + 1];
                            diff += kw * (means[j] - G[j]);
                            plain += kw * means[j];
                            wt += kw;
                        }
                    m.planes[c][i] =
                        wt > 0.0f ? 0.5f * std::max(0.0f, G[i] + diff / wt) + 0.5f * (plain / wt) : G[i];
                }
            }
    });
    return m;
}

// 3. Per pixel: wanted colour from its own detail, in place (block CFA in, Bayer out).
inline void remosaicBlockCfaWithModel(uint16_t* raw, const BlockCfaModel& m, uint32_t threads) {
    using namespace cfafast;
    if (!raw || !m.valid()) return;
    const uint32_t w = m.w, h = m.h, n = m.n, bw = m.bw, bh = m.bh;
    const float half = 0.5f * float(n - 1);
    std::vector<int> x0(w);
    std::vector<float> fx(w);
    for (uint32_t x = 0; x < w; ++x) {
        const float s = (float(x) - half) / float(n);
        x0[x] = std::clamp(int(std::floor(s)), 0, int(bw) - 2);
        fx[x] = std::clamp(s - float(x0[x]), 0.0f, 1.0f);
    }
    parallelRows(h, threads, [&](uint32_t a, uint32_t b) {
        for (uint32_t y = a; y < b; ++y) {
            const float s = (float(y) - half) / float(n);
            const int y0 = std::clamp(int(std::floor(s)), 0, int(bh) - 2);
            const float fy = std::clamp(s - float(y0), 0.0f, 1.0f);
            const uint32_t by = y / n, iy = y % n;
            uint16_t* row = raw + size_t(y) * w;
            for (uint32_t x = 0; x < w; ++x) {
                const uint32_t bx = std::min(x / n, bw - 1), ix = x % n;
                const int own = colourAt(m.order, by & 1u, bx & 1u);
                const int want = colourAt(m.order, y & 1u, x & 1u);
                const float v = float(row[x]);
                if (v >= m.white - 1.0f) continue;  // clipped: keep
                const float f = std::max(0.0f, (v - m.black) * m.invGainAt(bx, by, size_t(own) * m.nn + iy * n + ix));
                float out = f;
                if (own != want) {
                    const size_t i00 = size_t(y0) * bw + x0[x];
                    const auto sample = [&](int c) {
                        const float* p = m.planes[c].data();
                        const float top = p[i00] + (p[i00 + 1] - p[i00]) * fx[x];
                        const float bot = p[i00 + bw] + (p[i00 + bw + 1] - p[i00 + bw]) * fx[x];
                        return top + (bot - top) * fy;
                    };
                    out = sample(want) * (f + m.e) / (sample(own) + m.e);
                }
                row[x] = uint16_t(std::clamp(out + m.black, 0.0f, 65535.0f));
            }
        }
    });
}

// Half-size Bayer (same CFA order, black and white) straight from the block CFA: each output pixel is the
// flat-fielded mean of a 2x2 own-colour group, taking the wanted colour by ratio. `out` holds (w/2)*(h/2).
inline void binBlockCfaToBayer(const uint16_t* raw, const BlockCfaModel& m, uint16_t* out, uint32_t threads) {
    using namespace cfafast;
    if (!raw || !out || !m.valid() || m.n % 2 != 0) return;
    const uint32_t w = m.w, n = m.n, bw = m.bw, bh = m.bh, ow = m.w / 2, oh = m.h / 2;
    const float half = 0.5f * float(n - 1);
    parallelRows(oh, threads, [&](uint32_t a, uint32_t b) {
        for (uint32_t y = a; y < b; ++y) {
            const uint32_t y2 = 2 * y, by = std::min(y2 / n, bh - 1), iy = y2 % n;
            const float sy = (float(y2) + 0.5f - half) / float(n);
            const uint16_t* r0 = raw + size_t(y2) * w;
            const uint16_t* r1 = r0 + w;
            uint16_t* o = out + size_t(y) * ow;
            for (uint32_t x = 0; x < ow; ++x) {
                const uint32_t x2 = 2 * x, bx = std::min(x2 / n, bw - 1), ix = x2 % n;
                const int own = colourAt(m.order, by & 1u, bx & 1u);
                const int want = colourAt(m.order, y & 1u, x & 1u);
                const uint16_t p[4] = {r0[x2], r0[x2 + 1], r1[x2], r1[x2 + 1]};
                const uint16_t mx = std::max(std::max(p[0], p[1]), std::max(p[2], p[3]));
                float value;
                if (float(mx) >= m.white - 1.0f) {
                    // Clipped group: keep the plain mean, as binning the remosaicked frame did.
                    value = 0.25f * (float(p[0]) + p[1] + p[2] + p[3]) - m.black;
                } else {
                    const size_t base = size_t(own) * m.nn;
                    float f = 0.0f;
                    for (uint32_t k = 0; k < 4; ++k)
                        f += (float(p[k]) - m.black) * m.invGainAt(bx, by, base + (iy + (k >> 1)) * n + ix + (k & 1u));
                    f = std::max(0.0f, 0.25f * f);
                    value = f;
                    if (own != want) {
                        const float sx = (float(x2) + 0.5f - half) / float(n);
                        value = m.sample(want, sy, sx) * (f + m.e) / (m.sample(own, sy, sx) + m.e);
                    }
                }
                o[x] = uint16_t(std::clamp(value + m.black + 0.5f, 0.0f, 65535.0f));
            }
        }
    });
}

inline void remosaicBlockCfaFast(uint16_t* raw, uint32_t w, uint32_t h, uint32_t n, uint32_t order, float black,
                                 float white, uint32_t threads) {
    const auto model = buildBlockCfaModel(raw, w, h, n, order, black, white, threads);
    remosaicBlockCfaWithModel(raw, model, threads);
}

}  // namespace rawrcam::imaging
