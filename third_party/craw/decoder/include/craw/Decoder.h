// SPDX-License-Identifier: Apache-2.0
//
// Decoder for .CRAW frame payloads: codec 1 ("CRAW-Fast") and codec 2
// ("CRAW-Live") (SPEC.md section 3).
// Treats input as untrusted: every length and offset is bounds-checked.
#pragma once

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace craw {

// RGB (SPEC 3.8): cell frame carrying a half-size RGB image (codecs 2, 3 and 4).
#ifndef CRAW_CFA_DEFINED
#define CRAW_CFA_DEFINED
enum class Cfa : uint8_t { RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3, RGB = 4 };
#endif

struct FrameInfo {
    uint32_t width = 0, height = 0;
    uint8_t bitDepth = 0;
    Cfa cfa = Cfa::RGGB;
    uint16_t tile = 0, riceInit = 0;
    uint32_t crc = 0;
    uint8_t codec = 1;    // 1 Fast, 2 Live, 3 Live v2, 4 Compact
    bool hasCrc = true;   // frame CRC present (header flags bit 0 clear)
};

struct DecodeError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

// Parses only the payload header.
FrameInfo probeFrame(const uint8_t* payload, size_t size);

// Decodes into `out` (width * height samples, row-major) and verifies the
// frame CRC. Throws DecodeError on malformed or corrupt input.
FrameInfo decodeFrame(const uint8_t* payload, size_t size, std::vector<uint16_t>& out,
                      unsigned threads = 1);

}  // namespace craw
