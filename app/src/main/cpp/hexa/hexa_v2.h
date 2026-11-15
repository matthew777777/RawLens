// SPDX-License-Identifier: GPL-3.0-or-later
// Hexadeca (4x4-block GBRG CFA) 200 MP -> 50 MP linear RGB, version 2:
// per-frame tiled intra-block gain estimate, ratio-initialised residual-interpolation
// demosaic at full resolution on Vulkan, 2x2 area downsample. Mirrors
// tools/lapis-profile/hexadeca/research/hexa_v2_proto.py demosaic_ri.
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace hexa {

constexpr int kGainTilesY = 12, kGainTilesX = 16;

/** gains[((ty*TX+tx)*3+c)*16 + iy*4+ix]; per (tile, colour) the 16 gains average 1. */
struct GainTable {
    int ty = kGainTilesY, tx = kGainTilesX;
    std::vector<float> g;
};

GainTable estimateGains(const uint16_t* raw, size_t rowStridePx, int w, int h, float black, float white,
                        int tilesY = kGainTilesY, int tilesX = kGainTilesX);

struct Timing { double gainsMs = 0, gpuMs = 0, totalMs = 0; int tiles = 0; };

/**
 * raw: w x h 16-bit samples (row stride in pixels). out: (w/2)*(h/2)*3 uint16, linear RGB normalised
 * so that (white - black) after gain correction maps to 65535 (clamped). Returns false on any Vulkan
 * failure, with a reason in *error.
 */
bool convert(const uint16_t* raw, size_t rowStridePx, int w, int h, float black, float white,
             const GainTable& gains, const std::vector<uint32_t>& filterSpv, const std::vector<uint32_t>& pointSpv,
             const std::vector<uint32_t>& boxSpv, uint16_t* out, Timing* timing, std::string* error, int interior = 768);

}  // namespace hexa
