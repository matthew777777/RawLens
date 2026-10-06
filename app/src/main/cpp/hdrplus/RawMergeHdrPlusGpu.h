#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

// HDR+ spatial-domain burst merge, ported from hdr-plus-swift (Burst Photo,
// "Fast" merging; see ../UPSTREAM.md). Tile-based coarse-to-fine alignment of
// raw Bayer frames, then a per-pixel robust average against the reference
// whose weight falls off with the blurred frame difference relative to noise.
namespace rawr::raw_merge_hdrplus_gpu {

struct Config {
    // Upstream "noise reduction" slider, 1..22 (23 = plain average upstream,
    // not exposed). Higher merges more aggressively (cleaner, more ghosting).
    float strength = 13.f;
    // Alignment tile size at the first (2x2-binned) pyramid level: 16 or 32
    // (upstream's 64 is not supported: the tile-cost shader stages tiles in
    // shared memory sized for 32).
    std::uint32_t tileSize = 32;
    // Pyramid stops once the coarsest level is <= this many pixels on the
    // short side: 128 (small motion) / 64 / 32 (large motion).
    std::uint32_t searchDistance = 64;
    // Frequency merge only: align each companion once (pass-0 padding) and
    // reuse the tile shifts in the other three passes (~4x less alignment).
    // Upstream's warp weights jump at every half-tile boundary; its four
    // differently gridded passes hide that, a shared grid would not (visible
    // seams on moving subjects). Align-once therefore warps with continuous
    // bilinear weights, which removes the seams. false = upstream-exact
    // per-pass alignment (parity reference, ~2x slower overall).
    bool frequencyAlignOnce = true;
};

inline bool valid(const Config& c) {
    return std::isfinite(c.strength) && c.strength >= 1.f && c.strength <= 22.f &&
           (c.tileSize == 16u || c.tileSize == 32u) &&
           (c.searchDistance == 32u || c.searchDistance == 64u || c.searchDistance == 128u);
}

// Upstream align_merge_spatial_domain: four strength steps double the
// tolerated difference (shot noise sd grows sqrt(2) per ISO stop).
inline float robustness(float strength) {
    const double rev = 0.5 * (36.0 - double(int(strength + 0.5f)));
    return float(0.12 * std::pow(1.3, rev) - 0.4529822);
}

struct LevelGeometry {
    std::uint32_t width = 0, height = 0;  // pyramid level extent
    std::uint32_t tileSize = 0;           // tiles overlap by tileSize/2
    std::uint32_t tilesX = 0, tilesY = 0;
};

struct Geometry {
    std::uint32_t width = 0, height = 0;          // RAW extent
    std::uint32_t padLeft = 0, padTop = 0;        // zero padding so every level tiles exactly
    std::uint32_t paddedWidth = 0, paddedHeight = 0;
    std::vector<LevelGeometry> levels;            // fine (index 0, 2x2-binned) to coarse
};

// Mirrors upstream's pyramid/padding derivation. Each level is a 2x average
// pool of the previous; level 0 bins the Bayer 2x2 cells. Search radius is 2
// at every level. Padding is split so both sides stay even (Bayer phase).
inline Geometry makeGeometry(std::uint32_t width, std::uint32_t height, const Config& c) {
    if (width == 0u || height == 0u || (width & 1u) || (height & 1u))
        throw std::invalid_argument("hdrplus geometry: RAW dimensions must be nonzero/even");
    if (!valid(c)) throw std::invalid_argument("hdrplus geometry: invalid config");
    std::vector<std::uint32_t> tiles{c.tileSize};
    std::uint32_t res = std::min(width, height) / 2u;
    std::uint32_t factor = 2u;
    while (res > c.searchDistance) {
        tiles.push_back(std::max(tiles.back() / 2u, 8u));
        factor *= 2u;
        res /= 2u;
    }
    const std::uint32_t tileFactor = tiles.back() * factor;
    Geometry g{};
    g.width = width;
    g.height = height;
    g.paddedWidth = (width + tileFactor - 1u) / tileFactor * tileFactor;
    g.paddedHeight = (height + tileFactor - 1u) / tileFactor * tileFactor;
    g.padLeft = ((g.paddedWidth - width) / 2u) & ~1u;
    g.padTop = ((g.paddedHeight - height) / 2u) & ~1u;
    std::uint32_t w = g.paddedWidth, h = g.paddedHeight;
    for (const auto tile : tiles) {
        w /= 2u;
        h /= 2u;
        LevelGeometry l{};
        l.width = w;
        l.height = h;
        l.tileSize = tile;
        if (w < tile || h < tile) throw std::invalid_argument("hdrplus geometry: level smaller than a tile");
        l.tilesX = w / (tile / 2u) - 1u;
        l.tilesY = h / (tile / 2u) - 1u;
        g.levels.push_back(l);
    }
    return g;
}

// Frequency-domain ("Higher quality") merge. Uniform-exposure constants from
// upstream align_merge_frequency_domain.
struct FrequencyNorms {
    float robustnessNorm = 0.f;  // scales the noise term of the Wiener shrinkage
    float readNoise = 0.f;       // added to the per-tile shot-noise estimate
    float maxMotionNorm = 1.f;   // extra denoising for low-mismatch tiles
};
inline FrequencyNorms frequencyNorms(float strength) {
    const double rev = 0.5 * (26.5 - double(int(strength + 0.5f)));
    FrequencyNorms n{};
    n.robustnessNorm = float(std::pow(2.0, -rev + 7.5));
    n.readNoise = float(std::pow(std::pow(2.0, -rev + 10.0), 1.6));
    n.maxMotionNorm = float(std::max(1.0, std::pow(1.3, 11.0 - rev)));
    return n;
}

// The frequency merge runs four passes over 8x8 RGBA tiles (16x16 raw
// pixels). Upstream (1-based pass i) shifts the padding by tile_size_merge
// raw pixels: left for even i, top for i < 3; raised-cosine windows make the
// four half-tile-shifted passes sum to one. Each pass pads the frame
// differently (same padded extent), so alignment runs per pass.
inline constexpr std::uint32_t kFrequencyTile = 8;  // tile_size_merge
// Align-once mode keeps each companion's level-0 tile shifts for the later passes.
inline constexpr std::uint32_t kMaxFrequencyFrames = 64;

struct FrequencyGeometry {
    Geometry align;  // pyramid levels for the padded extent (pads set per pass)
    std::uint32_t padAlignX = 0, padAlignY = 0;
    std::uint32_t cropX = 0, cropY = 0;  // raw pixels dropped before RGBA packing
    std::uint32_t rgbaWidth = 0, rgbaHeight = 0;
    std::uint32_t tilesX = 0, tilesY = 0;
    // pass: 0-based. Shifts in raw pixels (left/top) and in tiles (all sides).
    static bool shiftLeft(std::uint32_t pass) { return pass % 2u == 1u; }
    static bool shiftTop(std::uint32_t pass) { return pass < 2u; }
    std::uint32_t padLeft(std::uint32_t pass) const { return padAlignX + (shiftLeft(pass) ? kFrequencyTile : 0u); }
    std::uint32_t padTop(std::uint32_t pass) const { return padAlignY + (shiftTop(pass) ? kFrequencyTile : 0u); }
};

inline FrequencyGeometry makeFrequencyGeometry(std::uint32_t width, std::uint32_t height, const Config& c) {
    Geometry g = makeGeometry(width, height, c);  // pyramid tile sizes / level count
    const std::uint32_t tileFactor = g.levels.back().tileSize << g.levels.size();
    constexpr std::uint32_t merge = kFrequencyTile;
    const std::uint32_t paddedW = (width + merge + tileFactor - 1u) / tileFactor * tileFactor;
    const std::uint32_t paddedH = (height + merge + tileFactor - 1u) / tileFactor * tileFactor;
    FrequencyGeometry f{};
    f.padAlignX = (paddedW - width - merge) / 2u;
    f.padAlignY = (paddedH - height - merge) / 2u;
    if (((paddedW - width - merge) % 2u) || ((paddedH - height - merge) % 2u) || (f.padAlignX & 1u) ||
        (f.padAlignY & 1u))
        throw std::invalid_argument("hdrplus frequency geometry: padding would break the Bayer phase");
    f.cropX = f.padAlignX / (2u * merge) * (2u * merge);
    f.cropY = f.padAlignY / (2u * merge) * (2u * merge);
    f.rgbaWidth = (paddedW - 2u * f.cropX) / 2u;
    f.rgbaHeight = (paddedH - 2u * f.cropY) / 2u;
    f.tilesX = f.rgbaWidth / merge;
    f.tilesY = f.rgbaHeight / merge;
    g.paddedWidth = paddedW;
    g.paddedHeight = paddedH;
    g.padLeft = f.padAlignX;
    g.padTop = f.padAlignY;
    std::uint32_t w = paddedW, h = paddedH;
    for (auto& level : g.levels) {
        w /= 2u;
        h /= 2u;
        level.width = w;
        level.height = h;
        level.tilesX = w / (level.tileSize / 2u) - 1u;
        level.tilesY = h / (level.tileSize / 2u) - 1u;
    }
    f.align = g;
    return f;
}

}  // namespace rawr::raw_merge_hdrplus_gpu
