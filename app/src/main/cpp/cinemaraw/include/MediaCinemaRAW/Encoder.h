// SPDX-License-Identifier: GPL-3.0-only
//
// Lossless encoder for MediaCinemaRAW compression type 7 frame payloads.
//
// The encoder accepts Android RAW16 (one uint16 per pixel) or packed RAW10
// (four pixels in five bytes) and produces payloads readable by existing
// type 7 `.mcraw` readers. See docs/FORMAT.md for the wire layout.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace mediacinemaraw {

// Encodes one Bayer frame.
//
// `rawData` holds `height` rows of `strideBytes` bytes each, except that the
// final row may omit its padding (Android image planes sometimes do), so only
// `(height - 1) * strideBytes + rowBytes` bytes need to be readable, where
// `rowBytes` is `width * 2` for RAW16 and `width / 4 * 5` for packed RAW10.
//
// `cropTop`/`cropHeight` select an even-aligned row window to encode, which
// preserves the Bayer CFA phase. When `downscale4x` is set, each 2x2 source
// block bins to one output sample: R and B average the 4 nearest
// same-colour taps (2x2 on their square sublattice) while green averages
// the 4 G pixels forming a diamond around the output sample — the two
// in-block G's plus the two across the block corners in the right/below
// neighbours, rounded mean. That mode is lossy by nature and additionally requires
// `width % 4 == 0` (so the binned width stays even for the tile codec).
// `cfa` is the Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT numbering
// (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR); it routes the binning taps and is
// validated even when `downscale4x` is false.
//
// The output dimensions must satisfy: encoded width even, encoded height a
// multiple of four. `output` is cleared first; encoding into a reused vector
// reuses its capacity.
//
// Throws std::invalid_argument when the geometry is invalid (null input,
// odd width, `height % 4 != 0` where required, odd `cropTop`,
// `cropTop + cropHeight > height`, `strideBytes` below the row size, a
// truncated `rawSize`, or `cfa` outside [0, 3]).
void encode(const uint8_t* rawData, size_t rawSize, int width, int height, int strideBytes,
            bool isPackedRaw10, int cropTop, int cropHeight, bool downscale4x, int cfa,
            std::vector<uint8_t>& output);

// Multithreaded encode(). Tile rows are independent, so workers encode
// disjoint bands that are concatenated in order; the result is byte-identical
// to encode(). `threadCount == 0` selects std::thread::hardware_concurrency,
// `threadCount == 1` (and single-band frames) use the serial path with no
// thread overhead. Throws exactly like encode(), before spawning threads.
void encode_parallel(const uint8_t* rawData, size_t rawSize, int width, int height, int strideBytes,
                     bool isPackedRaw10, int cropTop, int cropHeight, bool downscale4x, int cfa,
                     std::vector<uint8_t>& output, unsigned threadCount);

// Full-frame lossless shorthand: `cropTop = 0`, `cropHeight = height`,
// `downscale4x = false`. Equivalent to the 11-argument form with `cfa = 0`.
void encode(const uint8_t* rawData, size_t rawSize, int width, int height, int strideBytes,
            std::vector<uint8_t>& output, bool isPackedRaw10 = false);

} // namespace mediacinemaraw
