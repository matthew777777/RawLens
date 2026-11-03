// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Blind Poisson-Gaussian noise estimate (variance = a*x + b, x normalized
// [0,1] camera-linear) from a demosaiced pre-WB RGBA16F frame. Only native
// green CFA sites are read (demosaic keeps them as raw samples, so their noise
// is the sensor's). Residual = green - mean of its 4 diagonal green neighbours
// (variance 1.25 sigma^2 on flat data). 8x8 residual blocks per green lattice;
// per brightness bin, a low quantile of block variances (flattest blocks, chi^2
// corrected) estimates the noise; weighted LS fit of a, b over bins.
// Validated on device DNGs: matches flat-sky measurements within ~2x, and
// catches Camera2 profiles that are 10x too high (base ISO) or 500x+ too low.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace rawlens_noise {

inline float halfToFloat(uint16_t h) {
    const uint32_t s = (uint32_t)(h & 0x8000u) << 16;
    uint32_t e = (h >> 10) & 0x1Fu;
    uint32_t m = h & 0x3FFu;
    uint32_t bits;
    if (e == 0) {
        if (m == 0) {
            bits = s;
        } else {  // subnormal
            e = 127 - 15 + 1;
            while (!(m & 0x400u)) { m <<= 1; e--; }
            m &= 0x3FFu;
            bits = s | (e << 23) | (m << 13);
        }
    } else if (e == 31) {
        bits = s | 0x7F800000u | (m << 13);
    } else {
        bits = s | ((e + 127 - 15) << 23) | (m << 13);
    }
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

struct Estimate {
    bool ok = false;
    float a = 0.0f, b = 0.0f;
    float medianX = 0.0f;  // median green level (exposure for visibility)
    int bins = 0;
};

// cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR (BayerPattern ordinal), at image origin.
inline Estimate estimate(const uint8_t* rgbaHalf, int w, int h, int cfa) {
    Estimate out;
    if (!rgbaHalf || w < 64 || h < 64 || cfa < 0 || cfa > 3) return out;
    const auto* px = reinterpret_cast<const uint16_t*>(rgbaHalf);
    auto G = [&](int x, int y) { return halfToFloat(px[((size_t)y * w + x) * 4 + 1]); };
    // Green sites: RGGB/BGGR -> (1,0),(0,1); GRBG/GBRG -> (0,0),(1,1).
    const bool diagMain = (cfa == 1 || cfa == 2);

    constexpr int B = 8;                       // block side on a green lattice
    constexpr int NB = 45;                     // brightness bins over [0, 0.9]
    constexpr float kQ = 0.1f;                 // low quantile
    // chi^2(63) 10% quantile / 63 ~= 0.7757; residual variance = 1.25 sigma^2.
    const float corr = 0.7757f * 1.25f;
    std::vector<std::vector<float>> binVar(NB);
    std::vector<double> binMean(NB, 0.0);
    std::vector<uint32_t> hist(1024, 0);
    // Each lattice: green sites with step 2 in x and y from an origin.
    const int origins[2][2] = {{diagMain ? 0 : 1, 0}, {diagMain ? 1 : 0, 1}};
    for (const auto& o : origins) {
        const int ox = o[0], oy = o[1];
        const int lw = (w - ox - 2) / 2, lh = (h - oy - 2) / 2;  // lattice extent (skip border)
        for (int by = 1; by + B <= lh; by += B) {
            for (int bx = 1; bx + B <= lw; bx += B) {
                double s = 0, s2 = 0, sm = 0;
                for (int j = 0; j < B; j++) {
                    const int y = oy + 2 * (by + j);
                    for (int i = 0; i < B; i++) {
                        const int x = ox + 2 * (bx + i);
                        const float c = G(x, y);
                        const float n = 0.25f * (G(x - 1, y - 1) + G(x + 1, y - 1) +
                                                 G(x - 1, y + 1) + G(x + 1, y + 1));
                        const float r = c - n;
                        s += r; s2 += (double)r * r; sm += c;
                        hist[std::clamp((int)(c * 1023.0f), 0, 1023)]++;
                    }
                }
                const double n = B * B;
                const double mean = sm / n;
                if (!(mean >= 0.0) || mean >= 0.9) continue;
                const double var = (s2 - s * s / n) / (n - 1) / corr;
                const int bin = std::min(NB - 1, (int)(mean / 0.9 * NB));
                binVar[bin].push_back((float)var);
                binMean[bin] += mean;
            }
        }
    }
    // Weighted LS: var = a*x + b over bins with enough blocks.
    double Sw = 0, Sx = 0, Sy = 0, Sxx = 0, Sxy = 0;
    std::vector<double> X, V, W;
    for (int i = 0; i < NB; i++) {
        auto& v = binVar[i];
        if (v.size() < 200) continue;
        const size_t k = (size_t)(kQ * (v.size() - 1));
        std::nth_element(v.begin(), v.begin() + k, v.end());
        const double x = binMean[i] / v.size(), y = v[k], wt = (double)v.size();
        X.push_back(x); V.push_back(y); W.push_back(wt);
        Sw += wt; Sx += wt * x; Sy += wt * y; Sxx += wt * x * x; Sxy += wt * x * y;
    }
    out.bins = (int)X.size();
    if (out.bins >= 1) {
        const double det = Sw * Sxx - Sx * Sx;
        double a = 0, b = 0;
        if (out.bins >= 3 && det > 0) {
            a = (Sw * Sxy - Sx * Sy) / det;
            b = (Sy - a * Sx) / Sw;
        }
        if (out.bins < 3 || det <= 0 || b < 0) {  // through origin
            b = 0;
            a = Sxx > 0 ? Sxy / Sxx : 0;
        }
        if (a < 0) { a = 0; b = Sy / Sw; }
        out.a = (float)a;
        out.b = (float)b;
        out.ok = std::isfinite(out.a) && std::isfinite(out.b) && (out.a > 0 || out.b > 0);
    }
    // Median green level.
    uint64_t total = 0, acc = 0;
    for (auto c : hist) total += c;
    for (int i = 0; i < 1024; i++) {
        acc += hist[i];
        if (acc * 2 >= total) { out.medianX = (i + 0.5f) / 1023.0f; break; }
    }
    return out;
}

// Noise visibility (relative noise at the median exposure) -> luma strength.
// Below ~0.08 relative noise, shrink luma progressively (down to 0.4x);
// chroma always keeps full strength.
inline float lumaScaleFor(float a, float b, float medianX) {
    const float x = std::max(medianX, 1e-3f);
    const float rel = std::sqrt(std::max(a * x + b, 0.0f)) / x;
    return std::clamp(rel / 0.08f, 0.4f, 1.0f);
}

}  // namespace rawlens_noise
