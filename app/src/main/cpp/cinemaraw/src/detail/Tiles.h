// SPDX-License-Identifier: GPL-3.0-only
//
// Private tile encoding: input validation plus the 64x4 tile loop that turns
// Bayer rows into packed per-channel deltas with bits/references side data.
#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

#include "BitPacking.h"
#include "Simd.h"

namespace mediacinemaraw {
namespace detail {

// Output geometry shared by encode() and encode_parallel() so both throw
// identical errors before any threading or output happens.
struct ValidatedGeometry {
    int width;       // Visible encoded width (post-downscale).
    int height;      // Visible encoded height (post-crop/downscale).
    int paddedWidth; // Width rounded up to the 64-pixel tile stride.
};

inline ValidatedGeometry ValidateGeometry(const uint8_t* rawData, size_t rawSize, int width,
                                          int height, int strideBytes, bool isPackedRaw10,
                                          int cropTop, int cropHeight, bool downscale4x) {
    if (rawData == nullptr || width <= 0 || height <= 0 || width > 65536 || height > 65536 ||
        (width & 1) || (isPackedRaw10 && width % 4) || cropTop < 0 || (cropTop & 1) ||
        cropHeight <= 0 || cropTop > height - cropHeight)
        throw std::invalid_argument("Invalid RAW geometry");
    const size_t rowBytes = isPackedRaw10 ? size_t(width) / 4 * 5 : size_t(width) * 2;
    if (strideBytes < 0 || size_t(strideBytes) < rowBytes ||
        rawSize < size_t(height - 1) * strideBytes + rowBytes)
        throw std::invalid_argument("Truncated RAW plane or invalid stride");
    // The final row may omit its padding, hence height - 1 strides above.
    const int encodedWidth = downscale4x ? width / 2 : width;
    const int encodedHeight = downscale4x ? cropHeight / 2 : cropHeight;
    if ((encodedWidth & 1) || encodedHeight % 4 || (downscale4x && width % 4))
        throw std::invalid_argument(
            "MediaCinemaRAW requires even width and height divisible by four");
    return {encodedWidth, encodedHeight, (encodedWidth + 63) / 64 * kTileWidth};
}

// Encodes tile rows [yBegin, yEnd) of 64x4 tiles, appending payload bytes and
// per-channel bits/references in deterministic (y, x, channel) order. Bands
// are independent: parallel workers run this on disjoint ranges and the
// caller concatenates in band order for byte-identical output.
inline void EncodeTileRows(const uint8_t* rawData, int strideBytes, bool isPackedRaw10, int cropTop,
                           bool downscale4x, int width, int paddedWidth, int yBegin, int yEnd,
                           std::vector<uint8_t>& tileBytes, std::vector<uint16_t>& channelBits,
                           std::vector<uint16_t>& channelRefs) {
    auto sample = [&](int x, int y) -> uint16_t {
        const uint8_t* row = rawData + size_t(y + cropTop) * strideBytes;
        if (isPackedRaw10)
            return static_cast<uint16_t>((uint16_t(row[x / 4 * 5 + x % 4]) << 2) |
                                         ((row[x / 4 * 5 + 4] >> (2 * (x % 4))) & 3));
        return static_cast<uint16_t>(uint16_t(row[x * 2]) | (uint16_t(row[x * 2 + 1]) << 8));
    };
    auto pixel = [&](int x, int y) -> uint16_t {
        // Padding is outside the visible width and is discarded by the
        // decoder.
        if (x >= width)
            return 0;
        if (!downscale4x)
            return sample(x, y);
        const int sx = (x / 2) * 4 + x % 2;
        const int sy = (y / 2) * 4 + y % 2;
        // Average four same-colour samples; retain the original black/white
        // levels.
        return static_cast<uint16_t>((uint32_t(sample(sx, sy)) + sample(sx + 2, sy) +
                                      sample(sx, sy + 2) + sample(sx + 2, sy + 2) + 2) /
                                     4);
    };

    for (int y = yBegin; y < yEnd; y += kTileHeight)
        for (int x = 0; x < paddedWidth; x += kTileWidth) {
            uint16_t channels[4][kChannelSamples];
            if (!isPackedRaw10 && !downscale4x) {
                // RAW16: deinterleave contiguous Bayer rows once, without
                // per-pixel format/crop branches. Partial edge tiles bound
                // the run to the visible pairs (reads must stay inside the
                // stride); the clamp below then fills padding with the
                // visible minimum.
                const int pairCount = std::min(32, (width - x) / 2);
                for (int row = 0; row < 4; ++row) {
                    const auto* src = reinterpret_cast<const uint16_t*>(
                        rawData + size_t(y + row + cropTop) * strideBytes + x * 2);
                    DeinterleaveEvenOdd(src, channels[(row % 2) * 2] + (row / 2) * 32,
                                        channels[(row % 2) * 2 + 1] + (row / 2) * 32, pairCount);
                }
            } else if (!downscale4x && x + 64 <= width) {
                // Packed RAW10 full tile: unpack 64 pixels per row, then the
                // same deinterleave. Partial RAW10 tiles stay on the pixel()
                // path.
                for (int row = 0; row < 4; ++row) {
                    const uint8_t* src =
                        rawData + size_t(y + row + cropTop) * strideBytes + size_t(x) / 4 * 5;
                    uint16_t unpacked[64];
                    for (int i = 0; i < 64; i += 4) {
                        const uint8_t* group = src + size_t(i) / 4 * 5;
                        const unsigned lowBits = group[4];
                        unpacked[i] =
                            static_cast<uint16_t>((uint16_t(group[0]) << 2) | (lowBits & 3));
                        unpacked[i + 1] =
                            static_cast<uint16_t>((uint16_t(group[1]) << 2) | ((lowBits >> 2) & 3));
                        unpacked[i + 2] =
                            static_cast<uint16_t>((uint16_t(group[2]) << 2) | ((lowBits >> 4) & 3));
                        unpacked[i + 3] =
                            static_cast<uint16_t>((uint16_t(group[3]) << 2) | (lowBits >> 6));
                    }
                    DeinterleaveEvenOdd(unpacked, channels[(row % 2) * 2] + (row / 2) * 32,
                                        channels[(row % 2) * 2 + 1] + (row / 2) * 32, 32);
                }
            } else {
                for (int c = 0; c < 4; ++c)
                    for (int i = 0; i < 64; ++i)
                        channels[c][i] = pixel(x + (i % 32) * 2 + c % 2, y + (i / 32) * 2 + c / 2);
            }
            const bool partialTile = (x + 64 > width);
            for (int c = 0; c < 4; ++c) {
                uint16_t* channel = channels[c];
                if (partialTile) {
                    // Padding (x >= width) is discarded by the decoder.
                    // Clamp it to the visible minimum so zero padding cannot
                    // inflate hi-lo (and the bit width) of edge tiles.
                    // Visible deltas are unchanged relative to the new lo,
                    // so visible pixels still decode to lo + delta.
                    uint16_t visibleMin = 65535;
                    for (int i = 0; i < 64; ++i)
                        if (x + (i % 32) * 2 + c % 2 < width)
                            visibleMin = std::min(visibleMin, channel[i]);
                    if (visibleMin == 65535)
                        visibleMin = 0; // Fully padded; unreachable for even w >= 2.
                    for (int i = 0; i < 64; ++i)
                        if (x + (i % 32) * 2 + c % 2 >= width)
                            channel[i] = visibleMin;
                }
                uint16_t lo, hi;
                ChannelMinMax(channel, lo, hi);
                const int bits = BitsForDeltaRange(static_cast<uint16_t>(hi - lo));
                channelBits.push_back(static_cast<uint16_t>(bits));
                channelRefs.push_back(lo);
                // PackChannelDeltas() ignores its input when bits == 0, so
                // skip the delta computation in that case.
                if (bits != 0)
                    SubtractReferenceInPlace(channel, lo);
                PackChannelDeltas(tileBytes, channel, bits);
            }
        }
}

} // namespace detail
} // namespace mediacinemaraw
