#pragma once
// SPDX-License-Identifier: GPL-3.0-only
#include <cstddef>
#include <cstdint>
#include <vector>

namespace mediacinemaraw {
// Independent decoder-compatible MediaCinemaRAW encoder for compression type 7 readers.
// RAW10 is Android's four-pixel/five-byte packing.
void encode(const uint8_t* raw, size_t size, int width, int height, int stride,
            bool raw10, int cropTop, int cropHeight, bool bin,
            std::vector<uint8_t>& output);
// Multithreaded encode(): splits independent 64x4 tile rows across workers and
// concatenates in deterministic order, so output is byte-identical to encode().
// threads == 0 selects hardware_concurrency; threads == 1 (or tiny frames)
// runs the serial path with no thread overhead. Throws like encode().
void encode_parallel(const uint8_t* raw, size_t size, int width, int height, int stride,
                     bool raw10, int cropTop, int cropHeight, bool bin,
                     std::vector<uint8_t>& output, unsigned threads);
// Convenience full-frame lossless overload: cropTop = 0, cropHeight = height,
// bin = false. Use the 10-argument form for cropping or 4x downscaling (the
// downscale averages four same-colour samples, so it is lossy by nature).
void encode(const uint8_t* raw, size_t size, int width, int height, int stride,
            std::vector<uint8_t>& output, bool raw10 = false);
}
