// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Legacy brightness fit: adaptive gainmap ceiling + shoulder + chroma
// compression, applied on the CPU wide-range map between GPU readback and map
// JPEG encode (no second render). Ported from RAWR
// (https://github.com/adityawarmanfw/rawr app/.../encoding/jpeg/UltraHdrMapFit.h).
// Header-only so host tests can include it directly.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <thread>
#include <vector>

#include "color/UltraHdrParams.h"

namespace rawlens::encoding::jpeg {

// Fast tanh for the chroma knee: 4097-entry LUT over [-3, 3] with linear
// interpolation (max error ~1e-7, far below 1 LSB). std::tanh costs
// 30-100ns/call and the knee evaluates it on most map texels (12.5M at
// full res): measured 4.3s per file on device. LUT brings it under ~150ms.
inline float fastTanh(float x) {
    static float lut[4097];
    static const bool init = [] {
        for (int i = 0; i <= 4096; ++i) lut[i] = std::tanh(-3.0f + 6.0f * i / 4096.0f);
        return true;
    }();
    (void)init;
    const float t = std::clamp((x + 3.0f) * (4096.0f / 6.0f), 0.0f, 4096.0f);
    const int i = static_cast<int>(t);
    const float f = t - static_cast<float>(i);
    const float a = lut[i];
    const float b = lut[i < 4096 ? i + 1 : 4096];
    return a + (b - a) * f;
}

// Returns false (leave pixels + metadata wide) when the map is degenerate
// or gamma != 1 (codes wouldn't decode linear). base (RGBA8 sRGB, baseW x
// baseH) drives the luma baseline floor; nullptr skips it. Map texels sample
// the base at the nearest scaled pixel (identity for full-res maps).
inline bool fitAndRemapMap(const unsigned char* src, std::uint32_t w, std::uint32_t h,
                           const color::UltraHdrParams& uh, std::vector<unsigned char>& dst, float& fittedMax,
                           const unsigned char* base = nullptr, std::uint32_t baseW = 0, std::uint32_t baseH = 0) {
    const std::size_t n = static_cast<std::size_t>(w) * h;
    const float wideMax = uh.gainMapMaxLog2;
    if (n == 0 || wideMax <= 0.0f || uh.gamma != 1.0f) return false;
    unsigned workers = std::thread::hardware_concurrency();
    if (workers < 1) workers = 1;
    if (workers > 4) workers = 4;
    if (n < 4096) workers = 1;
    // p99.9 of per-pixel channel-mean log-gain via a 2048-bin histogram
    // (~0.0023 EV bins at wide 4.7: exact enough, single pass, no 50MB sort).
    // Per-worker row-band histograms; integer sums keep it order-independent.
    constexpr int kBins = 2048;
    std::vector<std::vector<std::uint32_t>> bands(workers, std::vector<std::uint32_t>(kBins, 0));
    {
        std::vector<std::thread> threads;
        threads.reserve(workers);
        for (unsigned t = 0; t < workers; ++t) {
            threads.emplace_back([=, &src, &bands] {
                auto& band = bands[t];
                const std::size_t end = static_cast<std::size_t>(h * (t + 1) / workers) * w;
                for (std::size_t i = static_cast<std::size_t>(h * t / workers) * w; i < end; ++i) {
                    const float mean = (static_cast<float>(src[i * 4 + 0]) + static_cast<float>(src[i * 4 + 1]) +
                                        static_cast<float>(src[i * 4 + 2])) /
                                       765.0f * wideMax;
                    int b = static_cast<int>(mean / wideMax * kBins);
                    band[std::clamp(b, 0, kBins - 1)]++;
                }
            });
        }
        for (auto& th : threads) th.join();
    }
    std::vector<std::uint64_t> hist(kBins, 0);
    for (const auto& band : bands)
        for (int b = 0; b < kBins; ++b) hist[b] += band[b];
    const std::uint64_t target = static_cast<std::uint64_t>(static_cast<double>(n) * 0.999);
    std::uint64_t acc = 0;
    float p999 = 0.0f;
    for (int b = 0; b < kBins; ++b) {
        acc += hist[b];
        if (acc >= target) {
            p999 = (static_cast<float>(b) + 1.0f) / kBins * wideMax;
            break;
        }
    }
    if (!(p999 >= 0.0f) || !(p999 <= wideMax)) return false;
    float fitted = std::ceil((p999 + uh.gainMapFitMargin) / uh.gainMapFitStep) * uh.gainMapFitStep;
    fitted = std::clamp(fitted, uh.gainMapFitFloor, std::min(uh.gainMapDisplayCap, wideMax));
    fittedMax = fitted;
    const float knee = fitted - uh.shoulderKneeBelowMax;
    const float W = uh.shoulderWidth;
    const float T = uh.chromaKnee;
    // Luma baseline floor (UltraHdrParams::baseShare): sRGB EOTF via a
    // 256-entry LUT, Rec.709 luma, then fitted * share * Y^exp. Max with
    // the measured channels before the shoulder, so it never lowers a code.
    const bool useFloor = base != nullptr && baseW > 0 && baseH > 0 && uh.baseShare > 0.0f;
    float eotf[256];
    for (int i = 0; i < 256; ++i) {
        const float c = static_cast<float>(i) / 255.0f;
        eotf[i] = c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f);
    }
    const float floorScale = fitted * uh.baseShare;
    const float floorExp = uh.baseExponent;
    dst.resize(n * 4);
    const float inv255 = 1.0f / 255.0f;
    (void)fastTanh(0.0f);  // force LUT init once, outside the threaded loop
    const std::uint32_t h32 = h;
    std::vector<std::thread> threads;
    threads.reserve(workers);
    for (unsigned t = 0; t < workers; ++t) {
        const std::uint32_t y0 = h32 * t / workers;
        const std::uint32_t y1 = h32 * (t + 1) / workers;
        threads.emplace_back([=, &src, &dst, &eotf] {
            for (std::uint32_t y = y0; y < y1; ++y) {
                const std::size_t row = static_cast<std::size_t>(y) * w;
                const std::uint32_t by = useFloor ? std::min(baseH - 1, static_cast<std::uint32_t>(
                                                                            std::uint64_t(y) * baseH / h))
                                                  : 0;
                for (std::uint32_t x = 0; x < w; ++x) {
                    const std::size_t i = row + x;
                    float c[3];
                    for (int k = 0; k < 3; ++k) c[k] = static_cast<float>(src[i * 4 + k]) * inv255 * wideMax;
                    if (useFloor) {
                        const std::uint32_t bx =
                            std::min(baseW - 1, static_cast<std::uint32_t>(std::uint64_t(x) * baseW / w));
                        const unsigned char* p = base + (static_cast<std::size_t>(by) * baseW + bx) * 4;
                        const float luma = 0.2126f * eotf[p[0]] + 0.7152f * eotf[p[1]] + 0.0722f * eotf[p[2]];
                        const float fl = luma > 0.0f ? floorScale * std::pow(luma, floorExp) : 0.0f;
                        for (int k = 0; k < 3; ++k) c[k] = std::max(c[k], fl);
                    }
                    // Shoulder each channel, then average: mean of shouldered
                    // channels, not shoulder of the mean.
                    float ms = 0.0f;
                    for (int k = 0; k < 3; ++k) {
                        c[k] = (c[k] > knee) ? knee + W * (1.0f - std::exp(-(c[k] - knee) / W)) : c[k];
                        ms += c[k];
                    }
                    ms /= 3.0f;
                    for (int k = 0; k < 3; ++k) {
                        const float d = (c[k] - ms) / T;
                        float dk;
                        if (d > 3.0f) {
                            dk = T;
                        } else if (d < -3.0f) {
                            dk = -T;
                        } else if (d > -0.02f && d < 0.02f) {
                            dk = T * d;  // tanh(x) ~= x: skip the call, exact to 1e-3 LSB
                        } else {
                            dk = T * fastTanh(d);
                        }
                        const float yy = ms + dk;
                        const float q = std::round(std::clamp(yy / fitted, 0.0f, 1.0f) * 255.0f);
                        dst[i * 4 + k] = static_cast<unsigned char>(q);
                    }
                    dst[i * 4 + 3] = src[i * 4 + 3];
                }
            }
        });
    }
    for (auto& th : threads) th.join();
    return true;
}

}  // namespace rawlens::encoding::jpeg
