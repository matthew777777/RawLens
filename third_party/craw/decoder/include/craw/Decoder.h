// SPDX-License-Identifier: Apache-2.0
//
// Decoder for .CRAW codec 1 ("CRAW-Fast") frame payloads (SPEC.md section 3).
// Treats input as untrusted: every length and offset is bounds-checked.
#pragma once

#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace craw {

enum class Cfa : uint8_t { RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3 };

struct FrameInfo {
    uint32_t width = 0, height = 0;
    uint8_t bitDepth = 0;
    Cfa cfa = Cfa::RGGB;
    uint16_t tile = 0, riceInit = 0;
    uint32_t crc = 0;
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
