#pragma once
// Per-frame phase-response correction for 4x4 block (Hexadeca) mosaics.
//
// Each of the 16 positions inside a 4x4 block responds slightly differently
// (about +-8%), and the ratio to the block mean changes with brightness and
// from capture to capture. That shows as a faint repeating grid. The response
// is fitted per frame from the frame's own flat blocks as a curve
//   ratio[type 4][position 16][brightness bin 40]
// (type = block parity (by & 1) * 2 + (bx & 1)) and divided out of every
// sample before the exact 2x2 sum.
//
// Three stages, mirrored one to one by native/raw_ingress/shaders/
// raw_remosaic.comp (passes 10, 11, 12 and the fit branch of pass 6):
//   1. block sums: sum of the 16 raw samples of every 4x4 block;
//   2. statistics: over blocks on a stride-S lattice that are flat and bright
//      enough, integer sums per (type, bin) of the block sum and of each
//      position's sample;
//   3. finalize: ratio curve per (type, position) from the statistics;
//   4. apply: (raw - black) / ratio + black before the 2x2 sum.
// Input samples must be <= 4095 (block sums are stored in 16 bits).
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <thread>
#include <vector>

namespace rawrcam::imaging {

constexpr uint32_t kFitBins = 40;
constexpr uint32_t kFitStatsWords = 4u * kFitBins * 18u;   // n, block sum, 16 positions
constexpr uint32_t kFitLutWords = 4u * 16u * kFitBins;
// Brightness bins: geometric from 4 to 900 DN (above black), 40 bins.
constexpr float kFitLogMin = 1.3862944f;                   // ln 4
inline float fitLogStep() { return std::log(225.0f) / float(kFitBins); }

inline uint32_t fitStatsIndex(uint32_t type, uint32_t bin, uint32_t slot) { return (type * kFitBins + bin) * 18u + slot; }

// Stage 1: block sums into `sums` (bw x bh, bw = w / 4).
inline void fitBlockSums(const uint16_t* raw, uint32_t w, uint32_t h, uint16_t* sums, uint32_t threads = 1) {
    const uint32_t bw = w / 4, bh = h / 4;
    threads = std::max(1u, std::min(threads, bh));
    const auto rows = [&](uint32_t b0, uint32_t b1) {
        for (uint32_t by = b0; by < b1; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                uint32_t s = 0;
                for (uint32_t j = 0; j < 4; ++j)
                    for (uint32_t i = 0; i < 4; ++i) s += raw[size_t(by * 4 + j) * w + bx * 4 + i];
                sums[size_t(by) * bw + bx] = uint16_t(std::min(s, 65535u));
            }
    };
    std::vector<std::thread> pool;
    for (uint32_t t = 1; t < threads; ++t) pool.emplace_back(rows, bh * t / threads, bh * (t + 1) / threads);
    rows(0, bh / threads);
    for (auto& th : pool) th.join();
}

// Stage 2: statistics over the blocks (4 + gx * stride, 4 + gy * stride) that
// are at least 4 blocks from every edge. `stats` has kFitStatsWords entries.
inline void fitStatistics(const uint16_t* raw, const uint16_t* sums, uint32_t w, uint32_t h, float black,
                          uint32_t stride, uint32_t* stats, uint32_t threads = 1) {
    const uint32_t bw = w / 4, bh = h / 4;
    if (bw < 9 || bh < 9) return;
    const uint32_t gridX = (bw - 8) / stride + 1, gridY = (bh - 8) / stride + 1;
    threads = std::max(1u, std::min(threads, gridY));
    std::vector<std::vector<uint32_t>> local(threads, std::vector<uint32_t>(kFitStatsWords, 0u));
    const auto rows = [&](uint32_t t, uint32_t g0, uint32_t g1) {
        auto& acc = local[t];
        for (uint32_t gy = g0; gy < g1; ++gy)
            for (uint32_t gx = 0; gx < gridX; ++gx) {
                const uint32_t bx = 4 + gx * stride, by = 4 + gy * stride;
                const auto sum = [&](uint32_t x, uint32_t y) { return float(sums[size_t(y) * bw + x]); };
                const float m = sum(bx, by) / 16.0f - black;
                if (!(m > 4.0f)) continue;
                float dev = 0.0f;
                const uint32_t nx[4] = {bx + 2, bx - 2, bx, bx}, ny[4] = {by, by, by + 2, by - 2};
                for (int k = 0; k < 4; ++k) dev = std::max(dev, std::abs(sum(nx[k], ny[k]) / 16.0f - black - m));
                const float allowance = 0.03f * m + 2.5f * std::sqrt((0.5f * std::max(m, 0.0f) + 9.0f) / 16.0f * 2.0f);
                if (!(dev < allowance)) continue;
                const int bin = std::clamp(int(std::floor((std::log(m) - kFitLogMin) / fitLogStep())), 0, int(kFitBins) - 1);
                const uint32_t type = (by & 1u) * 2u + (bx & 1u);
                acc[fitStatsIndex(type, uint32_t(bin), 0)] += 1u;
                acc[fitStatsIndex(type, uint32_t(bin), 1)] += uint32_t(sums[size_t(by) * bw + bx]);
                for (uint32_t p = 0; p < 16; ++p)
                    acc[fitStatsIndex(type, uint32_t(bin), 2u + p)] += raw[size_t(by * 4 + (p >> 2)) * w + bx * 4 + (p & 3u)];
            }
    };
    std::vector<std::thread> pool;
    for (uint32_t t = 1; t < threads; ++t) pool.emplace_back(rows, t, gridY * t / threads, gridY * (t + 1) / threads);
    rows(0, 0, gridY / threads);
    for (auto& th : pool) th.join();
    for (const auto& l : local)
        for (uint32_t i = 0; i < kFitStatsWords; ++i) stats[i] += l[i];
}

// Stage 3: curve from statistics. `lut[(type * 16 + position) * kFitBins + bin]`;
// a type with too few flat blocks keeps ratio 1.
inline void fitFinalize(const uint32_t* stats, float black, uint32_t stride, float* lut) {
    const float scale = float(stride * stride);
    const float minBin = std::max(1.0f, std::floor(300.0f / scale)), minTotal = std::max(1.0f, std::floor(2000.0f / scale));
    for (uint32_t i = 0; i < kFitLutWords; ++i) lut[i] = 1.0f;
    for (uint32_t type = 0; type < 4; ++type) {
        float total = 0.0f;
        for (uint32_t b = 0; b < kFitBins; ++b) total += float(stats[fitStatsIndex(type, b, 0)]);
        if (total < minTotal) continue;
        float logMean[kFitBins], ratio[kFitBins][16];
        uint32_t used = 0;
        for (uint32_t b = 0; b < kFitBins; ++b) {
            const float n = float(stats[fitStatsIndex(type, b, 0)]);
            if (n < minBin) continue;
            const float meanM = float(stats[fitStatsIndex(type, b, 1)]) / 16.0f / n - black;
            if (!(meanM > 0.0f)) continue;
            float mean = 0.0f;
            for (uint32_t p = 0; p < 16; ++p) {
                ratio[used][p] = (float(stats[fitStatsIndex(type, b, 2u + p)]) / n - black) / meanM;
                mean += ratio[used][p];
            }
            mean /= 16.0f;
            for (uint32_t p = 0; p < 16; ++p) ratio[used][p] /= mean;
            logMean[used++] = std::log(meanM);
        }
        if (used < 3) continue;
        for (uint32_t p = 0; p < 16; ++p) {
            float curve[kFitBins];
            for (uint32_t b = 0; b < kFitBins; ++b) {
                const float x = kFitLogMin + (float(b) + 0.5f) * fitLogStep();  // ln of the bin centre
                if (x <= logMean[0]) {
                    curve[b] = ratio[0][p];
                } else if (x >= logMean[used - 1]) {
                    curve[b] = ratio[used - 1][p];
                } else {
                    uint32_t k = 0;
                    while (k + 2 < used && logMean[k + 1] < x) ++k;
                    const float f = (x - logMean[k]) / (logMean[k + 1] - logMean[k]);
                    curve[b] = ratio[k][p] + (ratio[k + 1][p] - ratio[k][p]) * f;
                }
            }
            for (uint32_t b = 0; b < kFitBins; ++b) {
                const float lo = curve[b > 0 ? b - 1 : 0], hi = curve[b + 1 < kFitBins ? b + 1 : kFitBins - 1];
                lut[(type * 16 + p) * kFitBins + b] = 0.25f * lo + 0.5f * curve[b] + 0.25f * hi;
            }
        }
    }
}

// Brightness lookup of one 4x4 block: bin position for its block sum.
struct FitBlockPos {
    uint32_t i0;
    float fr;
};
inline FitBlockPos fitBlockPos(uint32_t blockSum, float black) {
    const float m = std::max(float(blockSum) / 16.0f - black, 1.0f);
    const float f = std::clamp((std::log(m) - (kFitLogMin + 0.5f * fitLogStep())) / fitLogStep(), 0.0f, float(kFitBins) - 1.001f);
    const uint32_t i0 = uint32_t(f);
    return {i0, f - float(i0)};
}

// Stage 4 fused with the exact 2x2 sum: quarter-DN output (w/2 x h/2). Works block by
// block: one brightness lookup and the 16 position gains per 4x4 block, then each of its
// four output pixels (2x2 samples each) is sum((raw - black) / ratio) + 4 * black.
inline void fitSum2x2(const uint16_t* raw, const uint16_t* sums, uint32_t w, uint32_t h, float black, const float* lut,
                      uint16_t* out, uint32_t threads = 1) {
    const uint32_t ow = w / 2, bw = w / 4, bh = h / 4;
    threads = std::max(1u, std::min(threads, bh));
    const auto rows = [&](uint32_t b0, uint32_t b1) {
        for (uint32_t by = b0; by < b1; ++by)
            for (uint32_t bx = 0; bx < bw; ++bx) {
                const FitBlockPos pos = fitBlockPos(sums[size_t(by) * bw + bx], black);
                const float* curve = lut + (((by & 1u) << 1) | (bx & 1u)) * 16u * kFitBins + pos.i0;
                float gain[16];
                for (uint32_t p = 0; p < 16; ++p) {
                    const float* c = curve + p * kFitBins;
                    gain[p] = 1.0f / (c[0] * (1.0f - pos.fr) + c[1] * pos.fr);
                }
                const uint16_t* base = raw + size_t(by * 4) * w + bx * 4;
                uint16_t* dst = out + size_t(by * 2) * ow + bx * 2;
                for (uint32_t oj = 0; oj < 2; ++oj)
                    for (uint32_t oi = 0; oi < 2; ++oi) {
                        float s = 4.0f * black;
                        for (uint32_t j = 0; j < 2; ++j)
                            for (uint32_t i = 0; i < 2; ++i) {
                                const uint32_t p = ((oj * 2 + j) << 2) | (oi * 2 + i);
                                s += (float(base[size_t(oj * 2 + j) * w + oi * 2 + i]) - black) * gain[p];
                            }
                        dst[size_t(oj) * ow + oi] = uint16_t(std::clamp(std::floor(s + 0.5f), 0.0f, 65535.0f));
                    }
            }
    };
    std::vector<std::thread> pool;
    for (uint32_t t = 1; t < threads; ++t) pool.emplace_back(rows, bh * t / threads, bh * (t + 1) / threads);
    rows(0, bh / threads);
    for (auto& th : pool) th.join();
}

// Whole correction in one call: fits the frame and writes the corrected
// quarter-DN 2x2 sums (w/2 x h/2). Dimensions must be multiples of 8.
inline void hexadecaSumWithFit(const uint16_t* raw, uint32_t w, uint32_t h, float black, uint32_t stride, uint16_t* out,
                               uint32_t threads = 1) {
    std::vector<uint16_t> sums(size_t(w / 4) * (h / 4));
    fitBlockSums(raw, w, h, sums.data(), threads);
    std::vector<uint32_t> stats(kFitStatsWords, 0u);
    fitStatistics(raw, sums.data(), w, h, black, stride, stats.data(), threads);
    std::vector<float> lut(kFitLutWords);
    fitFinalize(stats.data(), black, stride, lut.data());
    fitSum2x2(raw, sums.data(), w, h, black, lut.data(), out, threads);
}

}  // namespace rawrcam::imaging
