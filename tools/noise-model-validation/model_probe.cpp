// SPDX-License-Identifier: GPL-3.0-or-later
// Executes production headers directly; no copied RawLens fitter.
#include <cmath>
#include <cstring>
#include <random>
#include <vector>
#ifndef MODEL_HEADER
#define MODEL_HEADER "../../app/src/main/cpp/wavelet/noise_model.h"
#endif
#include MODEL_HEADER
#include "../../app/src/main/cpp/wavelet/noise_estimate.h"

extern "C" int predict(const double* rows, int n, float iso, float ea, float eb,
                        int bins, int evidence, double* out) {
    std::vector<rawlens_noise::Sample> samples;
    for (int i = 0; i < n; ++i) {
        const double* r = rows + i * 6;
#ifdef LEGACY
        samples.push_back({(float)r[0], (float)r[1], (float)r[2], (int)r[3]});
#else
        rawlens_noise::addSample(samples, {(float)r[0], (float)r[1], (float)r[2],
                                (int)r[3], (float)r[4], (uint64_t)r[5]});
#endif
    }
    const auto m = rawlens_noise::fit(samples);
#ifdef LEGACY
    if (!m.ok) return 0;
#else
    if (!m.supports(iso)) return 0;
#endif
    auto c = rawlens_noise::combine(m, iso, ea, eb, bins, evidence != 0);
    out[0] = c.a; out[1] = c.b; out[2] = samples.size();
    return 1;
}

extern "C" int estimate_frame(float a, float b, int seed, int cfa, int texture, double* out) {
    const int w = 1536, h = 1024;
    std::vector<uint16_t> frame(size_t(w) * h * 4, 0);
    std::mt19937 rng(seed);
    std::normal_distribution<float> noise(0, 1);
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        // Eight brightness patches. Separate CFA green gains can be tested
        // by offsetting cfa; all samples here share the same noise law.
        float clean = 0.015f + 0.095f * (x / (w / 8));
        if (texture && x > w / 3) clean += 0.025f * std::sin(x * 0.7f) * std::cos(y * 0.9f);
        const float v = clean + noise(rng) * std::sqrt(a * clean + b);
        const _Float16 half = (_Float16)v;
        uint16_t bits; std::memcpy(&bits, &half, 2);
        frame[(size_t(y) * w + x) * 4 + 1] = bits;
    }
    const auto e = rawlens_noise::estimate((const uint8_t*)frame.data(), w, h, cfa);
    out[0] = e.a; out[1] = e.b; out[2] = e.bins;
    return e.ok;
}
