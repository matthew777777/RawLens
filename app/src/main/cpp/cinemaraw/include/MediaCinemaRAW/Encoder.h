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
}
