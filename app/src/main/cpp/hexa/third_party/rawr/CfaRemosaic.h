#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <utility>
#include <vector>

namespace rawrcam::imaging {

// Remosaic of a block CFA (quad: 2x2 same-colour blocks, or 4x4) to Bayer at
// full resolution, in the same block-level colour order (CfaPattern code,
// 0=RGGB 1=GRBG 2=GBRG 3=BGGR). Some sensor modes (in-sensor crops) hand the
// app the block mosaic while reporting Bayer.
//
// Three passes, mirrored by native/raw_ingress/shaders/raw_remosaic_*.comp
// (keep both in step; the Vulkan host test compares them):
//  A. green everywhere: at R/B pixels, the two nearest green samples along
//     each axis (the neighbouring blocks) interpolated linearly, the axes
//     blended by inverse squared gradient; rounded to stored integer codes
//     (whole DN by default; CPU fixed-point inputs may retain fractional DN);
//  B. per R/B block, the mean of raw - green;
//  C. Bayer out: green from A; R/B at their own sites the raw sample, at
//     foreign sites green plus the colour difference interpolated bilinearly
//     between the nearest blocks of that colour.
// Colour differences cancel the black level, so raw values go in unchanged.

// Colour at block level: 0 R, 1 G, 2 B.
inline uint32_t cfaChannel(uint32_t order, uint32_t x, uint32_t y) {
    static constexpr uint8_t kChannels[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    return kChannels[order & 3u][(y & 1u) * 2u + (x & 1u)];
}

// Cubic Hermite between p0 and p1 (t in 0..1) with end slopes m0, m1 (per unit
// t), kept inside [p0, p1] so an edge cannot overshoot. Mirrored by the shader.
inline float cfaHermite(float p0, float p1, float m0, float m1, float t) {
    const float t2 = t * t, t3 = t2 * t;
    const float v = (2.0f * t3 - 3.0f * t2 + 1.0f) * p0 + (t3 - 2.0f * t2 + t) * m0 + (-2.0f * t3 + 3.0f * t2) * p1 +
                    (t3 - t2) * m1;
    return std::clamp(v, std::min(p0, p1), std::max(p0, p1));
}

struct CfaRemosaicGreen {
    // Pass A at (x, y): raw sample when green, else the directional estimate.
    // `cubic` replaces the two linear estimates by Hermite curves whose end
    // slopes come from the next green pixel beyond each end (Detail quality).
    static float at(const uint16_t* raw, uint32_t w, uint32_t h, uint32_t n, uint32_t order, uint32_t x, uint32_t y, float codesPerDn = 1.0f, bool cubic = false) {
        const auto px = [&](int32_t xx, int32_t yy) {
            xx = std::clamp<int32_t>(xx, 0, int32_t(w) - 1);
            yy = std::clamp<int32_t>(yy, 0, int32_t(h) - 1);
            return float(raw[size_t(yy) * w + size_t(xx)]);
        };
        const uint32_t c = cfaChannel(order, x / n, y / n);
        if (c == 1u) return px(int32_t(x), int32_t(y));
        const int32_t N = int32_t(n), X = int32_t(x), Y = int32_t(y);
        const int32_t x0 = X / N * N, y0 = Y / N * N;
        // Nearest greens: the last column/row of the previous block, the first of the next.
        // At the frame edge only one side exists; use it for both.
        const int32_t xl = x0 - 1 >= 0 ? x0 - 1 : x0 + N, xr = x0 + N < int32_t(w) ? x0 + N : xl;
        const int32_t yt = y0 - 1 >= 0 ? y0 - 1 : y0 + N, yb = y0 + N < int32_t(h) ? y0 + N : yt;
        const float gl = px(xl, Y), gr = px(xr, Y), gt = px(X, yt), gb = px(X, yb);
        const float th = xr != xl ? float(X - xl) / float(xr - xl) : 0.5f;
        const float tv = yb != yt ? float(Y - yt) / float(yb - yt) : 0.5f;
        float gh = gl + (gr - gl) * th, gv = gt + (gb - gt) * tv;
        if (cubic) {
            if (xr != xl) {
                const float len = float(xr - xl);
                gh = cfaHermite(gl, gr, (gl - px(std::max(xl - 1, 0), Y)) * len,
                                (px(std::min(xr + 1, int32_t(w) - 1), Y) - gr) * len, th);
            }
            if (yb != yt) {
                const float len = float(yb - yt);
                gv = cfaHermite(gt, gb, (gt - px(X, std::max(yt - 1, 0))) * len,
                                (px(X, std::min(yb + 1, int32_t(h) - 1)) - gb) * len, tv);
            }
        }
        // Gradients: across the greens, across this block, across the same-colour blocks beyond the greens.
        const float ah = std::abs(gl - gr) + std::abs(px(x0, Y) - px(x0 + N - 1, Y)) +
                         0.5f * std::abs(px(x0 - 2 * N + N - 1, Y) - px(x0 + 2 * N, Y));
        const float av = std::abs(gt - gb) + std::abs(px(X, y0) - px(X, y0 + N - 1)) +
                         0.5f * std::abs(px(X, y0 - 2 * N + N - 1) - px(X, y0 + 2 * N));
        const float kEps = codesPerDn;  // One DN in stored units; keeps flat areas an even blend
        const float wh = 1.0f / ((ah + kEps) * (ah + kEps)), wv = 1.0f / ((av + kEps) * (av + kEps));
        return (wh * gh + wv * gv) / (wh + wv);
    }
};

// Block-centre bilinear weights of colour `c`'s lattice at pixel (x, y): the
// four block indices (clamped) and weights. Blocks of one colour repeat every
// 2 blocks; (py, px) is that colour's block parity.
struct CfaBlockTap {
    int32_t bx0, by0, bx1, by1;
    float tx, ty;
};
inline CfaBlockTap cfaBlockTap(uint32_t n, uint32_t bw, uint32_t bh, uint32_t py, uint32_t px, uint32_t x, uint32_t y) {
    const float half = float(n - 1) * 0.5f;
    const float fx = (float(x) - float(px * n) - half) / float(2 * n);
    const float fy = (float(y) - float(py * n) - half) / float(2 * n);
    const int32_t kxMax = int32_t((bw - 1 - px) / 2), kyMax = int32_t((bh - 1 - py) / 2);
    const int32_t kx = std::clamp(int32_t(std::floor(fx)), 0, kxMax), ky = std::clamp(int32_t(std::floor(fy)), 0, kyMax);
    const int32_t kx1 = std::min(kx + 1, kxMax), ky1 = std::min(ky + 1, kyMax);
    CfaBlockTap t;
    t.bx0 = kx * 2 + int32_t(px);
    t.bx1 = kx1 * 2 + int32_t(px);
    t.by0 = ky * 2 + int32_t(py);
    t.by1 = ky1 * 2 + int32_t(py);
    t.tx = std::clamp(fx - float(kx), 0.0f, 1.0f);
    t.ty = std::clamp(fy - float(ky), 0.0f, 1.0f);
    return t;
}

// Colour difference at a pixel from the four nearest blocks of the wanted
// colour: bilinear weights, each scaled by how close the block's mean green
// is to the pixel's green. Without that, a block across a strong edge (a
// bright leaf next to a dark wall) pushes R/B on the dark side to black: a
// dark green fringe. `tap(bx, by)` returns {diff, block green}.
template <typename Tap>
inline float cfaBlendDiff(const CfaBlockTap& t, float green, Tap&& tap, float codesPerDn = 1.0f) {
    const int32_t bxs[2] = {t.bx0, t.bx1}, bys[2] = {t.by0, t.by1};
    const float wx[2] = {1.0f - t.tx, t.tx}, wy[2] = {1.0f - t.ty, t.ty};
    float sum = 0.0f, weight = 0.0f;
    for (int j = 0; j < 2; ++j)
        for (int i = 0; i < 2; ++i) {
            const auto [d, g] = tap(bxs[i], bys[j]);
            // Similar within ~10% of the brighter green (plus a few DN of noise).
            const float sigma = 0.1f * std::max(green, g) + 4.0f * codesPerDn;
            const float r = (g - green) / sigma;
            const float wgt = (wx[i] * wy[j] + 1e-4f) / (1.0f + r * r);
            sum += wgt * d;
            weight += wgt;
        }
    return sum / weight;
}

// Fast (live): no green interpolation. A pixel keeps its own sample where
// its block has the wanted colour; elsewhere the wanted colour is the
// block means of that colour interpolated linearly (binned colour, sharp
// luminance only where the pixel's own colour carries it). One mean pass.
// Standard (live, GPU and CPU alike): the passes above.
// High (stills, CPU): R/B colour difference from every 2x2 sub-block of the
// wanted colour within 2n pixels (the nearest blocks in all directions, a
// block straddling an edge no longer averaged into one value), weighted by
// distance and green similarity.
//
// Detail (quad only, block 2; other block sizes run High): green is cubic (Hermite between the
// neighbouring blocks' greens, the two axes blended by gradient). Red/blue at a foreign site lie on a
// colour line T = a * G + b fitted to the wanted colour's 2x2 blocks within kDetailRadius pixels: each
// block enters as its moments (mean green, mean sample, green variance, green/sample covariance) with a
// Gaussian distance weight and a quartic similarity of its mean green to the pixel's green, and the line
// is evaluated at the pixel's own green. A blended edge pixel lies on the line between its two colours,
// so edges keep their colour; flat areas average 40-60 samples, so the chroma stays free of the 3-6 px
// blotches a per-pixel estimate leaves (added-noise test on real quad frames: chroma noise -40%, luma
// -20%, detail unchanged). The GPU builds the block moments once per red/blue block into a scratch image.

// ---- Detail block moments (CPU and shader evaluate the same expressions) ----
constexpr float kDetailRadius = 7.0f;         // block centres within this many pixels of the pixel
constexpr float kDetailSpatial = 0.06f;       // distance weight exp(-d^2 * kDetailSpatial)
constexpr float kDetailSimilarity = 0.5f;     // similarity scale as a share of the brighter green
constexpr float kDetailSlopeLambda = 4.0f;    // slope regulariser (codes^2 per codesPerDn^2), pulls toward 1
struct CfaBlockMoments {
    float g = 0.0f, v = 0.0f, gg = 0.0f, gv = 0.0f;  // means; sums of squared/cross deviations
};
inline CfaBlockMoments cfaBlockMoments(const float g[4], const float v[4]) {
    CfaBlockMoments m;
    m.g = 0.25f * ((g[0] + g[1]) + (g[2] + g[3]));
    m.v = 0.25f * ((v[0] + v[1]) + (v[2] + v[3]));
    for (int k = 0; k < 4; ++k) {
        const float dg = g[k] - m.g;
        m.gg += dg * dg;
        m.gv += dg * (v[k] - m.v);
    }
    return m;
}
struct CfaDetailTap {
    int32_t dbx, dby;
    float spatial;
};
// Per pixel phase (y & 3, x & 3): the wanted colour's blocks within kDetailRadius of the pixel (block
// offsets from the pixel's block), in the order the shader walks them.
inline std::vector<std::vector<std::vector<CfaDetailTap>>> cfaDetailTaps(uint32_t order) {
    std::vector<std::vector<std::vector<CfaDetailTap>>> taps(4, std::vector<std::vector<CfaDetailTap>>(4));
    const int32_t reach = int32_t(kDetailRadius / 2.0f) + 1;
    for (int32_t py = 0; py < 4; ++py)
        for (int32_t px = 0; px < 4; ++px) {
            const uint32_t want = cfaChannel(order, uint32_t(px), uint32_t(py));
            if (want == 1u || cfaChannel(order, uint32_t(px) / 2, uint32_t(py) / 2) == want) continue;
            for (int32_t dby = -reach; dby <= reach; ++dby)
                for (int32_t dbx = -reach; dbx <= reach; ++dbx) {
                    const int32_t bx = px / 2 + dbx, by = py / 2 + dby;
                    if (cfaChannel(order, uint32_t(bx) & 1u, uint32_t(by) & 1u) != want) continue;
                    // Twice the axis distances to the block centre (odd integers); the weight is separable.
                    const int32_t tx = 4 * bx + 1 - 2 * px, ty = 4 * by + 1 - 2 * py;
                    if (float(tx * tx + ty * ty) > 4.0f * kDetailRadius * kDetailRadius) continue;
                    // Same expression as the shader: exp2 of the squared distance in quarter-pixel units.
                    constexpr float kLog2 = -0.25f * kDetailSpatial * 1.44269504f;
                    taps[py][px].push_back({dbx, dby, std::exp2(float(tx * tx + ty * ty) * kLog2)});
                }
        }
    return taps;
}
// Weighted sums of the line fit v = a * u + b, u and v measured from the pixel's green g0.
struct CfaLineSums {
    float w = 0.0f, u = 0.0f, v = 0.0f, uu = 0.0f, uv = 0.0f, umin = 1e30f, umax = -1e30f;
    void add(const CfaBlockMoments& m, float g0, float spatial, float codesPerDn) {
        const float d = m.g - g0, e = m.v - g0;
        const float sg = kDetailSimilarity * std::max(g0, m.g) + 4.0f * codesPerDn;
        const float s2 = sg * sg, s4 = s2 * s2, d2 = d * d;
        const float wgt = spatial * s4 / (s4 + d2 * d2);  // per sample
        w += 4.0f * wgt;
        u += 4.0f * wgt * d;
        v += 4.0f * wgt * e;
        uu += wgt * (m.gg + 4.0f * d * d);
        uv += wgt * (m.gv + 4.0f * d * e);
        if (wgt > 1e-3f) {
            umin = std::min(umin, d);
            umax = std::max(umax, d);
        }
    }
    // The line at the pixel's own green, slope pulled toward 1 (the colour-difference model); trusted up to
    // one green range beyond the blocks.
    float fit(float g0, float codesPerDn) const {
        if (!(w > 0.0f)) return g0;
        const float mu = u / w, mv = v / w;
        const float var = uu / w - mu * mu, cov = uv / w - mu * mv;
        const float lambda = kDetailSlopeLambda * codesPerDn * codesPerDn;
        const float slope = (cov + lambda) / (var + lambda);
        const float margin = umax - umin;
        const float uHere = std::clamp(0.0f, umin - margin, umax + margin);
        return g0 + mv + slope * (uHere - mu) - uHere;
    }
};

enum class CfaRemosaicQuality : uint32_t { Fast = 0, Standard = 1, High = 2, Detail = 3 };

// Fast at (x, y) from block means. Mirrored by the shader's fast pass.
inline float cfaFastAt(const uint16_t* raw, const float* means, uint32_t w, uint32_t bw, uint32_t bh, uint32_t n,
                       uint32_t order, const uint32_t parity[3][2], uint32_t x, uint32_t y) {
    const uint32_t want = cfaChannel(order, x, y), bx = x / n, by = y / n;
    if (cfaChannel(order, bx, by) == want) return float(raw[size_t(y) * w + x]);
    const auto mean = [&](int32_t bx2, int32_t by2) { return means[size_t(by2) * bw + size_t(bx2)]; };
    if (want == 1u) {
        // The four edge neighbours are green; mirror at the frame edge.
        const int32_t bl = bx > 0 ? int32_t(bx) - 1 : int32_t(bx) + 1, br = bx + 1 < bw ? int32_t(bx) + 1 : bl;
        const int32_t bt = by > 0 ? int32_t(by) - 1 : int32_t(by) + 1, bb = by + 1 < bh ? int32_t(by) + 1 : bt;
        // Pixel within its block (0..1), neighbour centres at -0.5 and 1.5.
        const float tx = ((float(x % n) + 0.5f) / float(n) + 0.5f) * 0.5f;
        const float ty = ((float(y % n) + 0.5f) / float(n) + 0.5f) * 0.5f;
        const float gh = mean(bl, int32_t(by)) + (mean(br, int32_t(by)) - mean(bl, int32_t(by))) * tx;
        const float gv = mean(int32_t(bx), bt) + (mean(int32_t(bx), bb) - mean(int32_t(bx), bt)) * ty;
        return 0.5f * (gh + gv);
    }
    const auto t = cfaBlockTap(n, bw, bh, parity[want][0], parity[want][1], x, y);
    const float top = mean(t.bx0, t.by0) + (mean(t.bx1, t.by0) - mean(t.bx0, t.by0)) * t.tx;
    const float bottom = mean(t.bx0, t.by1) + (mean(t.bx1, t.by1) - mean(t.bx0, t.by1)) * t.tx;
    return top + (bottom - top) * t.ty;
}

// Remosaics `raw` (w x h packed RAW16) in place. n = block side (2 or 4);
// w and h are multiples of 2n on every sensor this serves (others still work,
// edge blocks are partial). Single-threaded unless `threads` > 1.
// codesPerDn scales DN-valued weight constants for fixed-point input (e.g. 4
// for quarter-DN codes). Caller must keep samples within uint16 range.
// Output and intermediate green retain the same fixed-point units.
inline void remosaicBlockCfa(uint16_t* raw, uint32_t w, uint32_t h, uint32_t n, uint32_t order, uint32_t threads = 1,
                             CfaRemosaicQuality quality = CfaRemosaicQuality::Standard, float codesPerDn = 1.0f,
                             float blackLevel = 0.0f) {
    if (n < 2 || !raw || w < 2 * n || h < 2 * n || !std::isfinite(codesPerDn) || codesPerDn <= 0.0f) return;
    if (quality == CfaRemosaicQuality::Detail && n != 2) quality = CfaRemosaicQuality::High;
    const bool detail = quality == CfaRemosaicQuality::Detail;
    (void)blackLevel;  // kept for callers; Detail's slope regulariser no longer depends on the level
    const uint32_t bw = (w + n - 1) / n, bh = (h + n - 1) / n;
    // Block parity of R and B.
    uint32_t parity[3][2] = {};
    for (uint32_t i = 0; i < 4; ++i) {
        const uint32_t c = cfaChannel(order, i & 1u, i >> 1);
        parity[c][0] = i >> 1;
        parity[c][1] = i & 1u;
    }
    if (quality == CfaRemosaicQuality::Fast) {
        std::vector<float> means(size_t(bw) * bh);
        for (uint32_t by = 0; by < bh; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                float sum = 0.0f;
                uint32_t count = 0;
                for (uint32_t y = by * n; y < std::min(h, by * n + n); ++y)
                    for (uint32_t x = bx * n; x < std::min(w, bx * n + n); ++x, ++count) sum += float(raw[size_t(y) * w + x]);
                means[size_t(by) * bw + bx] = sum / float(count);
            }
        // Own-colour sites stay, so writing in place is safe.
        for (uint32_t y = 0; y < h; ++y)
            for (uint32_t x = 0; x < w; ++x)
                raw[size_t(y) * w + x] = uint16_t(std::clamp(
                    std::lround(cfaFastAt(raw, means.data(), w, bw, bh, n, order, parity, x, y)), 0l, 65535l));
        return;
    }
    // Integer codes in the input units (including fixed-point), so half the RAM of
    // a float plane at 50 MP: this is the largest allocation of the remosaic.
    std::vector<uint16_t> green(size_t(w) * h);
    // High reads only the 2x2 sub-block statistics below. Do not allocate
    // or compute the Standard block planes for that path.
    std::vector<float> diff, blockGreen;
    const auto parallelRows = [&](uint32_t rows, auto&& body) {
        threads = std::max(1u, std::min(threads, rows));
        if (threads == 1) {
            body(0u, rows);
            return;
        }
        std::vector<std::thread> pool;
        for (uint32_t t = 0; t < threads; ++t)
            pool.emplace_back([&, t] { body(rows * t / threads, rows * (t + 1) / threads); });
        for (auto& th : pool) th.join();
    };
    if (!detail)
        parallelRows(h, [&](uint32_t y0, uint32_t y1) {
            for (uint32_t y = y0; y < y1; ++y)
                for (uint32_t x = 0; x < w; ++x)
                    green[size_t(y) * w + x] =
                        uint16_t(std::clamp(std::lround(CfaRemosaicGreen::at(raw, w, h, n, order, x, y, codesPerDn)), 0l, 65535l));
        });
    if (quality == CfaRemosaicQuality::Standard) {
        diff.assign(size_t(bw) * bh, 0.0f);
        blockGreen.assign(size_t(bw) * bh, 0.0f);
        parallelRows(bh, [&](uint32_t b0, uint32_t b1) {
            for (uint32_t by = b0; by < b1; ++by)
                for (uint32_t bx = 0; bx < bw; ++bx) {
                    if (cfaChannel(order, bx, by) == 1u) continue;
                    float sum = 0.0f, greenSum = 0.0f;
                    uint32_t count = 0;
                    for (uint32_t y = by * n; y < std::min(h, by * n + n); ++y)
                        for (uint32_t x = bx * n; x < std::min(w, bx * n + n); ++x, ++count) {
                            sum += float(raw[size_t(y) * w + x]) - float(green[size_t(y) * w + x]);
                            greenSum += float(green[size_t(y) * w + x]);
                        }
                    diff[size_t(by) * bw + bx] = count ? sum / float(count) : 0.0f;
                    blockGreen[size_t(by) * bw + bx] = count ? greenSum / float(count) : 0.0f;
                }
        });
    }
    if (detail) {
        // Green (cubic) in one pass per 2x2 group. A
        // group lies inside one block; the horizontal terms belong to its rows
        // and the vertical ones to its columns, so each is loaded once for the
        // group (the shader's greenGroup does the same). Groups within 4 pixels
        // of an edge use the clamped per-pixel estimate.
        parallelRows((h + 1) / 2, [&](uint32_t g0, uint32_t g1) {
            const float eps = codesPerDn;
            const auto store = [&](uint32_t x, uint32_t y, float v, bool) {
                green[size_t(y) * w + x] = uint16_t(std::clamp(std::lround(v), 0l, 65535l));
            };
            for (uint32_t gy = g0; gy < g1; ++gy)
                for (uint32_t gx = 0; gx < (w + 1) / 2; ++gx) {
                    const uint32_t x0 = gx * 2, y0 = gy * 2;
                    const bool colour = cfaChannel(order, gx, gy) != 1u;
                    const bool interior = x0 >= 4 && y0 >= 4 && x0 + 5 < w && y0 + 5 < h;
                    if (!colour) {
                        for (uint32_t j = 0; j < 2 && y0 + j < h; ++j)
                            for (uint32_t i = 0; i < 2 && x0 + i < w; ++i) green[size_t(y0 + j) * w + x0 + i] = raw[size_t(y0 + j) * w + x0 + i];
                        continue;
                    }
                    if (!interior) {
                        for (uint32_t j = 0; j < 2 && y0 + j < h; ++j)
                            for (uint32_t i = 0; i < 2 && x0 + i < w; ++i)
                                store(x0 + i, y0 + j, CfaRemosaicGreen::at(raw, w, h, 2, order, x0 + i, y0 + j, codesPerDn, true), true);
                        continue;
                    }
                    float gh[2][2], ah[2], gv[2][2], av[2];
                    for (uint32_t j = 0; j < 2; ++j) {
                        const uint16_t* r = raw + size_t(y0 + j) * w;
                        const float gl = r[x0 - 1], gr = r[x0 + 2];
                        ah[j] = std::abs(gl - gr) + std::abs(float(r[x0]) - float(r[x0 + 1])) +
                                0.5f * std::abs(float(r[x0 - 3]) - float(r[x0 + 4]));
                        for (uint32_t i = 0; i < 2; ++i)
                            gh[j][i] = cfaHermite(gl, gr, (gl - float(r[x0 - 2])) * 3.0f, (float(r[x0 + 3]) - gr) * 3.0f,
                                                  float(i + 1) / 3.0f);
                    }
                    for (uint32_t i = 0; i < 2; ++i) {
                        const uint16_t* c = raw + x0 + i;
                        const float gt = c[size_t(y0 - 1) * w], gb = c[size_t(y0 + 2) * w];
                        av[i] = std::abs(gt - gb) + std::abs(float(c[size_t(y0) * w]) - float(c[size_t(y0 + 1) * w])) +
                                0.5f * std::abs(float(c[size_t(y0 - 3) * w]) - float(c[size_t(y0 + 4) * w]));
                        for (uint32_t j = 0; j < 2; ++j)
                            gv[j][i] = cfaHermite(gt, gb, (gt - float(c[size_t(y0 - 2) * w])) * 3.0f,
                                                  (float(c[size_t(y0 + 3) * w]) - gb) * 3.0f, float(j + 1) / 3.0f);
                    }
                    for (uint32_t j = 0; j < 2; ++j)
                        for (uint32_t i = 0; i < 2; ++i) {
                            const float a = (ah[j] + eps) * (ah[j] + eps), b = (av[i] + eps) * (av[i] + eps);
                            store(x0 + i, y0 + j, (gh[j][i] * b + gv[j][i] * a) / (a + b), true);
                        }
                }
        });
        // Moments of every red/blue block (rounded green, raw sample): mean green, mean sample, and the green
        // variance and green/sample covariance sums about those means. Red/blue blocks of a block row share
        // one x parity, so bx >> 1 indexes them.
        const uint32_t mw = (bw + 1) / 2;
        std::vector<CfaBlockMoments> moments(size_t(mw) * bh);
        parallelRows(bh, [&](uint32_t b0, uint32_t b1) {
            for (uint32_t by = b0; by < b1; ++by)
                for (uint32_t bx = 0; bx < bw; ++bx) {
                    if (cfaChannel(order, bx, by) == 1u) continue;
                    float g[4], v[4];
                    uint32_t k = 0;
                    for (uint32_t j = 0; j < 2; ++j)
                        for (uint32_t i = 0; i < 2; ++i, ++k) {
                            const size_t p = size_t(std::min(h - 1, 2 * by + j)) * w + std::min(w - 1, 2 * bx + i);
                            g[k] = float(green[p]);
                            v[k] = float(raw[p]);
                        }
                    moments[size_t(by) * mw + (bx >> 1)] = cfaBlockMoments(g, v);
                }
        });
        // The wanted colour's blocks around a pixel depend only on its phase (x & 3, y & 3): one list per phase.
        const auto taps = cfaDetailTaps(order);
        parallelRows(h, [&](uint32_t y0, uint32_t y1) {
            for (uint32_t y = y0; y < y1; ++y)
                for (uint32_t x = 0; x < w; ++x) {
                    const size_t i = size_t(y) * w + x;
                    const uint32_t want = cfaChannel(order, x, y);
                    const float g0 = float(green[i]);
                    float v;
                    if (want == 1u) {
                        v = g0;
                    } else if (cfaChannel(order, x / 2, y / 2) == want) {
                        continue;  // own site: the sample stays
                    } else {
                        CfaLineSums sums;
                        const int32_t bx0 = int32_t(x / 2), by0 = int32_t(y / 2);
                        for (const auto& t : taps[y & 3u][x & 3u]) {
                            const int32_t bx = bx0 + t.dbx, by = by0 + t.dby;
                            if (bx < 0 || by < 0 || bx >= int32_t(bw) || by >= int32_t(bh)) continue;
                            sums.add(moments[size_t(by) * mw + size_t(bx >> 1)], g0, t.spatial, codesPerDn);
                        }
                        v = sums.fit(g0, codesPerDn);
                    }
                    raw[i] = uint16_t(std::clamp(std::lround(v), 0l, 65535l));
                }
        });
        return;
    }
    // High: colour difference and green per 2x2 sub-block (= the block for quad).
    const uint32_t sw = (w + 1) / 2, sh = (h + 1) / 2;
    std::vector<float> subDiff, subGreen;
    if (quality == CfaRemosaicQuality::High) {
        subDiff.assign(size_t(sw) * sh, 0.0f);
        subGreen.assign(size_t(sw) * sh, 0.0f);
        parallelRows(sh, [&](uint32_t s0, uint32_t s1) {
            for (uint32_t sy = s0; sy < s1; ++sy)
                for (uint32_t sx = 0; sx < sw; ++sx) {
                    // High interpolation only requests red/blue sub-blocks.
                    if (cfaChannel(order, sx * 2 / n, sy * 2 / n) == 1u) continue;
                    float d = 0.0f, g = 0.0f;
                    uint32_t count = 0;
                    for (uint32_t y = sy * 2; y < std::min(h, sy * 2 + 2); ++y)
                        for (uint32_t x = sx * 2; x < std::min(w, sx * 2 + 2); ++x, ++count) {
                            d += float(raw[size_t(y) * w + x]) - float(green[size_t(y) * w + x]);
                            g += float(green[size_t(y) * w + x]);
                        }
                    subDiff[size_t(sy) * sw + sx] = d / float(count);
                    subGreen[size_t(sy) * sw + sx] = g / float(count);
                }
        });
    }
    const int32_t radius = int32_t(n);  // in sub-blocks: 2n pixels
    const float spatial = 1.0f / (float(n) * float(n));
    // The taps repeat: sub-block colours have period n (in sub-blocks), so
    // the wanted-colour offsets and their distance weights depend only on the
    // pixel's sub-block phase, its position inside the sub-block and the colour.
    struct HighTap {
        int32_t ox, oy;
        float spatialWeight;
    };
    std::vector<HighTap> highTaps[4][4][4][2];  // [cy % n][cx % n][(y & 1) * 2 + (x & 1)][want == 2]
    if (quality == CfaRemosaicQuality::High) {
        for (uint32_t phy = 0; phy < n; ++phy)
            for (uint32_t phx = 0; phx < n; ++phx)
                for (uint32_t sub = 0; sub < 4; ++sub)
                    for (uint32_t blue = 0; blue < 2; ++blue) {
                        const uint32_t want = blue ? 2u : 0u;
                        // Pixel position in sub-block units relative to its sub-block, centres at +0.5.
                        const float px = float(sub & 1u) * 0.5f + 0.25f, py = float(sub >> 1) * 0.5f + 0.25f;
                        auto& taps = highTaps[phy][phx][sub][blue];
                        for (int32_t oy = -radius; oy <= radius; ++oy)
                            for (int32_t ox = -radius; ox <= radius; ++ox) {
                                // Colour of the sub-block at phase + offset (period n in both axes).
                                const uint32_t sx = uint32_t(int32_t(phx) + ox + 4 * int32_t(n)) % n;
                                const uint32_t sy = uint32_t(int32_t(phy) + oy + 4 * int32_t(n)) % n;
                                if (cfaChannel(order, sx * 2 / n, sy * 2 / n) != want) continue;
                                const float dx = float(ox) + 0.5f - px, dy = float(oy) + 0.5f - py;
                                taps.push_back({ox, oy, std::exp(-(dx * dx + dy * dy) * spatial)});
                            }
                    }
    }
    const auto highDiff = [&](uint32_t want, uint32_t x, uint32_t y, float g0) {
        const int32_t cx = int32_t(x / 2), cy = int32_t(y / 2);
        const auto& taps = highTaps[uint32_t(cy) % n][uint32_t(cx) % n][(y & 1u) * 2u + (x & 1u)][want == 2u];
        float sum = 0.0f, weight = 0.0f;
        for (const auto& t : taps) {
            const int32_t sx = cx + t.ox, sy = cy + t.oy;
            if (sx < 0 || sy < 0 || sx >= int32_t(sw) || sy >= int32_t(sh)) continue;
            const size_t b = size_t(sy) * sw + size_t(sx);
            const float sigma = 0.1f * std::max(g0, subGreen[b]) + 4.0f * codesPerDn;
            const float r = (subGreen[b] - g0) / sigma;
            const float wgt = t.spatialWeight / (1.0f + r * r * r * r);
            sum += wgt * subDiff[b];
            weight += wgt;
        }
        return weight > 0.0f ? sum / weight : 0.0f;
    };
    parallelRows(h, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y)
            for (uint32_t x = 0; x < w; ++x) {
                const size_t i = size_t(y) * w + x;
                const uint32_t want = cfaChannel(order, x, y);
                float v;
                const float greenHere = float(green[i]);
                if (want == 1u) {
                    v = greenHere;
                } else if (cfaChannel(order, x / n, y / n) == want) {
                    continue;  // own site: raw sample stays
                } else {
                    if (quality == CfaRemosaicQuality::High) {
                        v = greenHere + highDiff(want, x, y, greenHere);
                    } else {
                        const auto t = cfaBlockTap(n, bw, bh, parity[want][0], parity[want][1], x, y);
                        v = greenHere + cfaBlendDiff(t, greenHere, [&](int32_t bx, int32_t by) {
                                const size_t b = size_t(by) * bw + size_t(bx);
                                return std::pair{diff[b], blockGreen[b]};
                            }, codesPerDn);
                    }
                }
                raw[i] = uint16_t(std::clamp(std::lround(v), 0l, 65535l));
            }
    });
}

// Packed RAW16 bytes (little-endian, as the snapshots hold them).
inline void remosaicBlockCfa(std::vector<uint8_t>& raw16, uint32_t w, uint32_t h, uint32_t n, uint32_t order,
                             uint32_t threads = 1, CfaRemosaicQuality quality = CfaRemosaicQuality::Standard,
                             float codesPerDn = 1.0f, float blackLevel = 0.0f) {
    if (raw16.size() < size_t(w) * h * 2) return;
    remosaicBlockCfa(reinterpret_cast<uint16_t*>(raw16.data()), w, h, n, order, threads, quality, codesPerDn,
                     blackLevel);
}

}  // namespace rawrcam::imaging
