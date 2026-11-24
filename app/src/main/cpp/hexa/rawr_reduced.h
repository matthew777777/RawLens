// SPDX-License-Identifier: GPL-3.0-only
// RawLens adapter for rawr 49b013db3914927aef81969451db084c1ec3c2dd.
#pragma once
#include "third_party/rawr/CfaRemosaic.h"
#include "third_party/rawr/PhaseFit.h"
#include <stdexcept>

namespace rawlens {
inline void rawrReduced(const uint16_t* src, uint32_t w, uint32_t h, uint32_t order,
                        float black, uint16_t* dst, bool phaseFit, uint32_t threads) {
    if (!src || !dst || w < 16 || h < 16 || w % 8 || h % 8 || order > 3 ||
        !std::isfinite(black) || black < 0 || black >= 1023)
        throw std::invalid_argument("Invalid Hexadeca reduction parameters");
    // Match ReducedRaw::submit's source bounds before the fit's integer statistics.
    for (size_t i = 0; i < size_t(w) * h; ++i)
        if (src[i] > 1023) throw std::invalid_argument("Hexadeca source exceeds RAW10");
    threads = std::clamp(threads, 1u, 8u);
    if (phaseFit) {
        rawrcam::imaging::hexadecaSumWithFit(src, w, h, black, 3, dst, threads);
    } else {
        for (uint32_t y = 0; y < h / 2; ++y)
            for (uint32_t x = 0; x < w / 2; ++x) {
                const size_t i = size_t(y * 2) * w + x * 2;
                dst[size_t(y) * (w / 2) + x] = src[i] + src[i + 1] + src[i + w] + src[i + w + 1];
            }
    }
    // Exact upstream CPU reference for ReducedRaw's Detail Quad remosaic.
    // Four sum codes represent one original DN; no RGB intermediate or normalization.
    rawrcam::imaging::remosaicBlockCfa(dst, w / 2, h / 2, 2, order, threads,
        rawrcam::imaging::CfaRemosaicQuality::Detail, 4.0f, black * 4.0f);
}
}
