// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// UltraHDR (JPEG_R) container mux. Byte logic ported from RAWR
// (https://github.com/adityawarmanfw/rawr native/gainmap, itself ported from
// google/libultrahdr Apache-2.0/MIT: lib/src/multipictureformat.cpp,
// JpegR::appendGainMap + generateXmpFor*Image, uhdr_gainmap_metadata_frac).
// No libultrahdr dependency.
//
// Assembly (single file, backward compatible):
//   SOI [JFIF] EXIF? XMP-primary(GContainer) ISO-primary MPF-base-segments...
//   MPF SOS..EOI | SOI XMP-secondary(hdrgm) ISO-secondary map-scan..EOI
//
// Offsets follow upstream exactly: primary MPEntry size = full primary
// length (SOI..EOI), primary offset = 0, secondary offset = bytes from the
// end of the 8-byte MPF APP2 header (FF E2 LL LL 'MPF\0') to secondary SOI.
// XMP carries single-channel (channel-0) values like upstream; ISO 21496-1
// carries the full descriptor (decoders prefer ISO when both present).
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace rawlens::gainmap {

struct UltrahdrMuxParams {
    // Log2(content boost) bounds, gamma, offsets, capacities (log2 display).
    // minLog2 = 0 (identity floor): the file must never darken the base.
    float gainMapMinLog2 = 0.0f;
    float gainMapMaxLog2 = 4.7090998f;
    float gamma = 1.0f;
    float offsetSdr = 0.015625f;
    float offsetHdr = 0.015625f;
    float hdrCapacityMinLog2 = 0.0f;
    float hdrCapacityMaxLog2 = 4.7090998f;
    // RGB image hint. All components use the single shared ISO/XMP
    // parameter set; JPEG components determine the actual map channels.
    bool multiChannel = true;
};

struct UltrahdrMuxResult {
    bool ok = false;
    std::string error;
    // File offsets for diagnostics/tests.
    size_t primarySize = 0;    // SOI..EOI of primary
    size_t secondarySize = 0;  // SOI..EOI of gain map image
    size_t secondaryOffset = 0;  // file offset of secondary SOI
};

class UltrahdrMux {
   public:
    static bool validateParams(const UltrahdrMuxParams& params, const char** reason = nullptr) noexcept;
    // baseJpeg/mapJpeg: complete turbojpeg outputs (SOI..EOI). exifPayload:
    // APP1 content bytes (after the length field, i.e. "Exif\0\0"+TIFF);
    // empty means no EXIF segment. Output is the full UltraHDR file.
    static UltrahdrMuxResult assemble(const uint8_t* baseJpeg, size_t baseSize, const uint8_t* mapJpeg,
                                      size_t mapSize, const uint8_t* exifPayload, size_t exifSize,
                                      const UltrahdrMuxParams& params, std::vector<uint8_t>& outFile);
};

}  // namespace rawlens::gainmap
