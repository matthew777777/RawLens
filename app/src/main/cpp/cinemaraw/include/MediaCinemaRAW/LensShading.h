// SPDX-License-Identifier: GPL-3.0-only
//
// Frame-level lens shading metadata, compatible with MotionCam Pro files
// and MotionCam Tools: a per-frame gain grid correcting vignetting.
//
// Schema. Each frame JSON may carry three keys (verified against
// MotionCam Pro recordings and the MotionCam Tools renderer, which reads
// them and bakes the correction into its DNG output):
//
//   "lensShadingMapWidth": 17, "lensShadingMapHeight": 13,
//   "lensShadingMap": [[R...], [Gr...], [Gb...], [B...]]
//
// `lensShadingMap` holds exactly four nested planes, one per color
// channel in Android color order: 0 = R, 1 = Gr (green in R rows),
// 2 = Gb (green in B rows), 3 = B. Each plane holds exactly
// `Width * Height` row-major finite floats, each greater than zero.
// This is NOT CFA tile order: pixels map to planes through the
// `sensorArrangment` value (`"rggb"`, `"bggr"`, `"grbg"`, or `"gbrg"`).
// MotionCam Tools ignores a missing or malformed map (flat arrays,
// strings, and ragged planes all render as if absent — verified), so
// files without these keys keep working everywhere.
//
// Geometry. Grid point (c, r) sits at pixel-center coordinates
// `x = (c + 0.5) * width / columns`, `y = (r + 0.5) * height / rows`,
// where width/height are the encoded frame dimensions. The gain at a pixel
// is the bilinear interpolation of the four surrounding grid points,
// edge-clamped so border pixels reuse the outer samples. Grids that are
// one wide or one tall are constant along the degenerate axis.
//
// The map covers the encoded frame (post-crop, post-binning). When frames
// are cropped (`cropTop`/`cropHeight`) or 4x-downscaled, the producer must
// crop or resample the sensor shading map to the encoded window before
// embedding it; the decoder always applies the map over the decoded
// width x height. Android producers can copy `LensShadingMap` channels
// directly: the plane order matches.
//
// Correction. The decoder applies
// `out = black + (in - black) * gain`, rounded to nearest and clamped to
// [0, 65535]. Black handling lives on the apply side; see the sibling
// decoder's `applyLensShadingMap()`.
//
// The shared section below (the struct, validation, channel mapping, and
// sampling) is duplicated in the sibling decoder repository, which must
// not depend on this one. Keep the two copies in sync.
#pragma once

#include <clocale>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace mediacinemaraw {

// Coarse per-channel gain grid covering one encoded frame: `columns` by
// `rows` grid points with four color-interleaved gains each, index
// `(r * columns + c) * 4 + ch`, channel order R, Gr, Gb, B.
struct LensShadingMap {
    int columns = 0;
    int rows = 0;
    std::vector<float> gains;
};

namespace lensshading_detail {

// Grid backstop: 512 x 512 x 4 gains stay under typical frame metadata
// sizes while real sensor maps are an order of magnitude smaller.
constexpr int kMaxGridExtent = 512;

// Unchecked bilinear sample. The caller must have validated the map,
// geometry, and channel. Never throws.
inline float SampleGain(const LensShadingMap& map, int x, int y, int width, int height,
                        int channel) noexcept {
    const int columns = map.columns;
    const int rows = map.rows;
    float gx =
        (static_cast<float>(x) + 0.5f) * static_cast<float>(columns) / static_cast<float>(width) -
        0.5f;
    float gy =
        (static_cast<float>(y) + 0.5f) * static_cast<float>(rows) / static_cast<float>(height) -
        0.5f;
    if (gx < 0.0f)
        gx = 0.0f;
    if (gx > static_cast<float>(columns - 1))
        gx = static_cast<float>(columns - 1);
    if (gy < 0.0f)
        gy = 0.0f;
    if (gy > static_cast<float>(rows - 1))
        gy = static_cast<float>(rows - 1);
    // Truncation is floor for the clamped non-negative coordinates.
    int c0 = static_cast<int>(gx);
    if (c0 > columns - 1)
        c0 = columns - 1;
    int c1 = c0 + 1;
    if (c1 > columns - 1)
        c1 = columns - 1;
    int r0 = static_cast<int>(gy);
    if (r0 > rows - 1)
        r0 = rows - 1;
    int r1 = r0 + 1;
    if (r1 > rows - 1)
        r1 = rows - 1;
    const float fx = gx - static_cast<float>(c0);
    const float fy = gy - static_cast<float>(r0);
    const size_t stride = static_cast<size_t>(columns) * 4;
    const float* gains = map.gains.data();
    const float g00 = gains[static_cast<size_t>(r0) * stride + static_cast<size_t>(c0) * 4 +
                            static_cast<size_t>(channel)];
    const float g01 = gains[static_cast<size_t>(r0) * stride + static_cast<size_t>(c1) * 4 +
                            static_cast<size_t>(channel)];
    const float g10 = gains[static_cast<size_t>(r1) * stride + static_cast<size_t>(c0) * 4 +
                            static_cast<size_t>(channel)];
    const float g11 = gains[static_cast<size_t>(r1) * stride + static_cast<size_t>(c1) * 4 +
                            static_cast<size_t>(channel)];
    return (g00 * (1.0f - fx) + g01 * fx) * (1.0f - fy) + (g10 * (1.0f - fx) + g11 * fx) * fy;
}

} // namespace lensshading_detail

// Validates a map against the schema above. Throws std::invalid_argument
// on out-of-range extents, a gain-count mismatch, or a gain that is not
// finite and positive.
inline void validateLensShadingMap(const LensShadingMap& map) {
    if (map.columns < 1 || map.columns > lensshading_detail::kMaxGridExtent)
        throw std::invalid_argument("lens shading columns out of range");
    if (map.rows < 1 || map.rows > lensshading_detail::kMaxGridExtent)
        throw std::invalid_argument("lens shading rows out of range");
    const size_t expected = static_cast<size_t>(map.columns) * static_cast<size_t>(map.rows) * 4;
    if (map.gains.size() != expected)
        throw std::invalid_argument("lens shading gain count mismatch");
    for (float gain : map.gains) {
        if (!std::isfinite(gain))
            throw std::invalid_argument("lens shading gain not finite");
        if (gain <= 0.0f)
            throw std::invalid_argument("lens shading gain not positive");
    }
}

// Maps pixel (x, y) to its color channel index (0 = R, 1 = Gr, 2 = Gb,
// 3 = B) through the 2x2 CFA tile named by `sensorArrangement`, one of
// `"rggb"`, `"bggr"`, `"grbg"`, `"gbrg"` (lowercase, as MotionCam writes
// it). A green pixel in an R row is Gr, in a B row Gb. Only the pixel
// parity matters. Throws std::invalid_argument on any other arrangement.
inline int lensShadingChannelIndex(const std::string& sensorArrangement, int x, int y) {
    if (sensorArrangement != "rggb" && sensorArrangement != "bggr" && sensorArrangement != "grbg" &&
        sensorArrangement != "gbrg")
        throw std::invalid_argument("lens shading sensor arrangement");
    const int tx = ((x % 2) + 2) % 2;
    const int ty = ((y % 2) + 2) % 2;
    const char color = sensorArrangement[size_t(ty) * 2 + size_t(tx)];
    if (color == 'r')
        return 0;
    if (color == 'b')
        return 3;
    const bool rowHasRed =
        sensorArrangement[size_t(ty) * 2] == 'r' || sensorArrangement[size_t(ty) * 2 + 1] == 'r';
    return rowHasRed ? 1 : 2;
}

// Samples one color-channel gain at pixel (x, y) of a width x height
// frame; `channel` is 0 = R, 1 = Gr, 2 = Gb, 3 = B. Validates the map and
// throws std::invalid_argument like validateLensShadingMap(), plus on bad
// geometry, out-of-range coordinates, or a channel outside [0, 3].
inline float lensShadingGainAt(const LensShadingMap& map, int x, int y, int width, int height,
                               int channel) {
    validateLensShadingMap(map);
    if (width <= 0 || height <= 0)
        throw std::invalid_argument("lens shading dimensions");
    if (x < 0 || x >= width || y < 0 || y >= height)
        throw std::invalid_argument("lens shading coordinates");
    if (channel < 0 || channel > 3)
        throw std::invalid_argument("lens shading channel");
    return lensshading_detail::SampleGain(map, x, y, width, height, channel);
}

// Serializes a map to the `"lensShadingMapWidth": ..., ...` triplet for
// splicing into the frame JSON, e.g. `"{\"width\":64,...," +
// buildLensShadingJson(map) + "}"`. Gains print with %.9g, which
// round-trips float32 exactly, and always with a '.' decimal point
// regardless of the C numeric locale. Validates first and throws like
// validateLensShadingMap().
inline std::string buildLensShadingJson(const LensShadingMap& map) {
    validateLensShadingMap(map);
    const char* separator = std::localeconv()->decimal_point;
    const bool fixSeparator = separator != nullptr && separator[0] != '\0' &&
                              !(separator[0] == '.' && separator[1] == '\0');
    std::string out = "\"lensShadingMapWidth\":";
    out += std::to_string(map.columns);
    out += ",\"lensShadingMapHeight\":";
    out += std::to_string(map.rows);
    out += ",\"lensShadingMap\":[";
    out.reserve(out.size() + map.gains.size() * 12);
    char text[32];
    const size_t plane = static_cast<size_t>(map.columns) * static_cast<size_t>(map.rows);
    for (int ch = 0; ch < 4; ++ch) {
        if (ch != 0)
            out += ',';
        out += '[';
        for (size_t i = 0; i < plane; ++i) {
            if (i != 0)
                out += ',';
            std::snprintf(text, sizeof(text), "%.9g",
                          static_cast<double>(map.gains[i * 4 + size_t(ch)]));
            if (fixSeparator) {
                // %.9g never emits grouping separators, so at most one
                // decimal point needs rewriting to the JSON '.'.
                std::string token = text;
                const size_t found = token.find(separator);
                if (found != std::string::npos)
                    token.replace(found, std::strlen(separator), ".");
                out += token;
            } else {
                out += text;
            }
        }
        out += ']';
    }
    out += "]";
    return out;
}

} // namespace mediacinemaraw
