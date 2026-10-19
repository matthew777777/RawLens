// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Ported from RAWR native/gainmap/src/UltrahdrMux.cpp
// (https://github.com/adityawarmanfw/rawr, itself a byte-logic port of
// google/libultrahdr). See UltrahdrMux.h for the assembly contract.
#include "gainmap/UltrahdrMux.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>

namespace rawlens::gainmap {
namespace {

// --- float -> rational (libultrahdr gainmapmath.cpp) ---
bool floatToUnsignedFractionImpl(float v, uint32_t maxNumerator, uint32_t* numerator, uint32_t* denominator) {
    if (std::isnan(v) || v < 0 || v > maxNumerator) return false;
    const uint64_t maxD = (v <= 1) ? UINT32_MAX : (uint64_t)floor(maxNumerator / v);
    *denominator = 1;
    uint32_t previousD = 0;
    double currentV = (double)v - floor(v);
    const int maxIter = 39;
    for (int iter = 0; iter < maxIter; ++iter) {
        const double numeratorDouble = (double)(*denominator) * v;
        if (numeratorDouble > maxNumerator) return false;
        *numerator = (uint32_t)round(numeratorDouble);
        if (fabs(numeratorDouble - (*numerator)) == 0.0) return true;
        currentV = 1.0 / currentV;
        const double newD = previousD + floor(currentV) * (*denominator);
        if (newD > maxD) return true;
        previousD = *denominator;
        if (newD > (double)UINT32_MAX) return false;
        *denominator = (uint32_t)newD;
        currentV -= floor(currentV);
    }
    *numerator = (uint32_t)round((double)(*denominator) * v);
    return true;
}

bool floatToSignedFraction(float v, int32_t* numerator, uint32_t* denominator) {
    uint32_t pn = 0;
    if (!floatToUnsignedFractionImpl(fabsf(v), INT32_MAX, &pn, denominator)) return false;
    *numerator = (int32_t)pn;
    if (v < 0) *numerator *= -1;
    return true;
}

bool floatToUnsignedFraction(float v, uint32_t* numerator, uint32_t* denominator) {
    return floatToUnsignedFractionImpl(v, UINT32_MAX, numerator, denominator);
}

// --- byte helpers (big-endian JPEG/MPF order) ---
void putU8(std::vector<uint8_t>& out, uint8_t v) { out.push_back(v); }
void putU16BE(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back((v >> 8) & 0xff);
    out.push_back(v & 0xff);
}
void putU32BE(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back((v >> 24) & 0xff);
    out.push_back((v >> 16) & 0xff);
    out.push_back((v >> 8) & 0xff);
    out.push_back(v & 0xff);
}
void putS32BE(std::vector<uint8_t>& out, int32_t v) { putU32BE(out, (uint32_t)v); }

std::string ftoa(float v) {
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.8g", (double)v);
    return std::string(buf);
}

constexpr char kXmpNs[] = "http://ns.adobe.com/xap/1.0/";
constexpr char kIsoNs[] = "urn:iso:std:iso:ts:21496:-1";

std::string buildPrimaryXmp(size_t secondaryLength) {
    std::string xml;
    // Standard XMP packet wrapper: Adobe-toolkit readers (Photos) locate the
    // packet via <?xpacket?>. A bare <x:xmpmeta> without it is ignored.
    xml += "<?xpacket begin=\"\xef\xbb\xbf\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>";
    xml += "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">";
    xml += "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">";
    // rdf:about is required by the XMP/RDF spec (may be empty). Without it
    // strict parsers drop the whole Container directory and the file reads
    // as SDR (no HDR ramp in Photos).
    xml += "<rdf:Description rdf:about=\"\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"";
    xml += " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"";
    xml += " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\">";
    xml += "<Container:Directory><rdf:Seq>";
    xml += "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\"";
    xml += " Item:Mime=\"image/jpeg\"/></rdf:li>";
    xml += "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\"";
    xml += " Item:Mime=\"image/jpeg\" Item:Length=\"" + std::to_string(secondaryLength) + "\"/></rdf:li>";
    xml += "</rdf:Seq></Container:Directory>";
    xml += "</rdf:Description></rdf:RDF></x:xmpmeta>";
    xml += "<?xpacket end=\"w\"?>";
    return xml;
}

std::string buildSecondaryXmp(const UltrahdrMuxParams& p) {
    std::string xml;
    xml += "<?xpacket begin=\"\xef\xbb\xbf\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>";
    xml += "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">";
    xml += "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">";
    xml += "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"";
    xml += " hdrgm:Version=\"1.0\"";
    xml += " hdrgm:GainMapMin=\"" + ftoa(p.gainMapMinLog2) + "\"";
    xml += " hdrgm:GainMapMax=\"" + ftoa(p.gainMapMaxLog2) + "\"";
    xml += " hdrgm:Gamma=\"" + ftoa(p.gamma) + "\"";
    xml += " hdrgm:OffsetSDR=\"" + ftoa(p.offsetSdr) + "\"";
    xml += " hdrgm:OffsetHDR=\"" + ftoa(p.offsetHdr) + "\"";
    xml += " hdrgm:HDRCapacityMin=\"" + ftoa(p.hdrCapacityMinLog2) + "\"";
    xml += " hdrgm:HDRCapacityMax=\"" + ftoa(p.hdrCapacityMaxLog2) + "\"";
    xml += " hdrgm:BaseRenditionIsHDR=\"False\"";
    xml += "/></rdf:RDF></x:xmpmeta>";
    xml += "<?xpacket end=\"w\"?>";
    return xml;
}

// ISO 21496-1 metadata: one shared parameter set for gray or RGB maps.
bool buildIsoBox(const UltrahdrMuxParams& p, std::vector<uint8_t>& out, std::string& error) {
    int32_t minN = 0, maxN = 0, offSN = 0, offHN = 0;
    uint32_t minD = 0, maxD = 0, gamN = 0, gamD = 0, offSD = 0, offHD = 0, capMinN = 0, capMinD = 0, capMaxN = 0,
             capMaxD = 0;
    if (!floatToSignedFraction(p.gainMapMinLog2, &minN, &minD) ||
        !floatToSignedFraction(p.gainMapMaxLog2, &maxN, &maxD) ||
        !floatToUnsignedFraction(p.gamma, &gamN, &gamD) ||
        !floatToSignedFraction(p.offsetSdr, &offSN, &offSD) ||
        !floatToSignedFraction(p.offsetHdr, &offHN, &offHD) ||
        !floatToUnsignedFraction(p.hdrCapacityMinLog2, &capMinN, &capMinD) ||
        !floatToUnsignedFraction(p.hdrCapacityMaxLog2, &capMaxN, &capMaxD)) {
        error = "ultrahdr mux: metadata not representable as rational";
        return false;
    }
    putU16BE(out, 0);  // min_version
    putU16BE(out, 0);  // writer_version
    // One shared parameter set, including for an RGB gain-map JPEG. The
    // channel-count flag describes metadata sets, not JPEG components.
    putU8(out, 0x40);  // shared metadata + use base color space
    putU32BE(out, capMinN);
    putU32BE(out, capMinD);
    putU32BE(out, capMaxN);
    putU32BE(out, capMaxD);
    putS32BE(out, minN);
    putU32BE(out, minD);
    putS32BE(out, maxN);
    putU32BE(out, maxD);
    putU32BE(out, gamN);
    putU32BE(out, gamD);
    putS32BE(out, offSN);
    putU32BE(out, offSD);
    putS32BE(out, offHN);
    putU32BE(out, offHD);
    return true;
}

// MPF APP2 payload (86 bytes), big-endian, ported from multipictureformat.cpp.
void buildMpfPayload(uint32_t primarySize, uint32_t secondarySize, uint32_t secondaryOffset,
                     std::vector<uint8_t>& out) {
    const uint8_t sig[4] = {'M', 'P', 'F', 0};
    out.insert(out.end(), sig, sig + 4);
    const uint8_t be[4] = {'M', 'M', 0, 0x2A};
    out.insert(out.end(), be, be + 4);
    putU32BE(out, 8);  // index IFD offset
    putU16BE(out, 3);  // 3 tags
    // MPFVersion UNDEFINED[4] = "0100" (inline)
    putU16BE(out, 0xB000);
    putU16BE(out, 0x0007);
    putU32BE(out, 4);
    out.push_back('0');
    out.push_back('1');
    out.push_back('0');
    out.push_back('0');
    // NumberOfImages LONG[1] = 2 (inline)
    putU16BE(out, 0xB001);
    putU16BE(out, 0x0004);
    putU32BE(out, 1);
    putU32BE(out, 2);
    // MPEntry UNDEFINED[32], offset to entries past this IFD
    putU16BE(out, 0xB002);
    putU16BE(out, 0x0007);
    putU32BE(out, 32);
    // bytesWritten(after this field, excl sig) + 4(this offset) + 4(attr IFD)
    const uint32_t mpEntryOffset = (uint32_t)(out.size() - 4 + 4 + 4);
    putU32BE(out, mpEntryOffset);
    putU32BE(out, 0);  // attribute IFD offset: none
    // Primary entry: Baseline MP Primary + JPEG
    putU32BE(out, 0x030000u | 0x000000u);
    putU32BE(out, primarySize);
    putU32BE(out, 0);
    putU16BE(out, 0);
    putU16BE(out, 0);
    // Gain map entry: JPEG, size + offset
    putU32BE(out, 0x000000u);
    putU32BE(out, secondarySize);
    putU32BE(out, secondaryOffset);
    putU16BE(out, 0);
    putU16BE(out, 0);
}

void putMarker(std::vector<uint8_t>& out, uint8_t marker) {
    out.push_back(0xFF);
    out.push_back(marker);
}

// Scans a complete JPEG (SOI..EOI). Returns SOS offset, JFIF segment range,
// and copies length-prefixed segments [2, sos) except APPn (keeps APP13).
struct JpegScan {
    bool ok = false;
    std::string error;
    size_t sosOffset = 0;
    bool hasJfif = false;
    size_t jfifStart = 0;  // at marker FF
    size_t jfifTotal = 0;  // marker + length bytes + payload
    std::vector<std::pair<size_t, size_t>> keepRanges;  // (start,total) segments to copy
};

JpegScan scanBase(const uint8_t* data, size_t size) {
    JpegScan s;
    if (size < 4 || data[0] != 0xFF || data[1] != 0xD8) {
        s.error = "ultrahdr mux: base missing SOI";
        return s;
    }
    size_t pos = 2;
    if (pos + 4 <= size && data[pos] == 0xFF && data[pos + 1] == 0xE0) {
        const size_t segLen = ((size_t)data[pos + 2] << 8) | data[pos + 3];
        if (segLen < 2 || pos + 2 + segLen > size) {
            s.error = "ultrahdr mux: truncated JFIF";
            return s;
        }
        s.hasJfif = true;
        s.jfifStart = pos;
        s.jfifTotal = 2 + segLen;
        pos += 2 + segLen;
    }
    while (pos < size) {
        if (data[pos] != 0xFF) break;
        if (pos + 1 >= size) break;
        uint8_t marker = data[pos + 1];
        if (marker == 0xFF) {
            while (pos + 2 < size && data[pos + 1] == 0xFF) ++pos;
            if (pos + 1 >= size || data[pos + 1] == 0xFF) break;
            marker = data[pos + 1];
        }
        if (marker == 0xDA) {
            s.sosOffset = pos;
            break;
        }
        if (marker == 0x00) {
            pos += 2;
            continue;
        }
        if (marker >= 0xD0 && marker <= 0xD7) {
            pos += 2;
            continue;
        }
        if (marker == 0xD9) break;
        if (pos + 4 > size) break;
        const size_t segLen = ((size_t)data[pos + 2] << 8) | data[pos + 3];
        if (segLen < 2 || pos + 2 + segLen > size) break;
        const bool isApp = marker >= 0xE0 && marker <= 0xEF;
        if (!isApp || marker == 0xED) s.keepRanges.emplace_back(pos, 2 + segLen);
        pos += 2 + segLen;
    }
    if (s.sosOffset == 0) {
        s.error = "ultrahdr mux: SOS not found in base JPEG";
        return s;
    }
    s.ok = true;
    return s;
}

}  // namespace

bool UltrahdrMux::validateParams(const UltrahdrMuxParams& p, const char** reason) noexcept {
    auto fail = [&](const char* msg) {
        if (reason) *reason = msg;
        return false;
    };
    if (!(p.gainMapMaxLog2 > p.gainMapMinLog2)) return fail("ultrahdr: max must exceed min");
    if (!(p.gamma > 0.0f) || !std::isfinite(p.gamma)) return fail("ultrahdr: gamma must be > 0");
    if (!(p.offsetSdr >= 0.0f) || !std::isfinite(p.offsetSdr)) return fail("ultrahdr: offsetSdr must be >= 0");
    if (!(p.offsetHdr >= 0.0f) || !std::isfinite(p.offsetHdr)) return fail("ultrahdr: offsetHdr must be >= 0");
    if (!(p.hdrCapacityMaxLog2 > p.hdrCapacityMinLog2))
        return fail("ultrahdr: hdrCapacityMax must exceed hdrCapacityMin");
    if (!(p.hdrCapacityMinLog2 >= 0.0f)) return fail("ultrahdr: hdrCapacityMin must be >= 0");
    return true;
}

UltrahdrMuxResult UltrahdrMux::assemble(const uint8_t* baseJpeg, size_t baseSize, const uint8_t* mapJpeg,
                                        size_t mapSize, const uint8_t* exifPayload, size_t exifSize,
                                        const UltrahdrMuxParams& params, std::vector<uint8_t>& outFile) {
    UltrahdrMuxResult result;
    const char* reason = nullptr;
    if (!validateParams(params, &reason)) {
        result.error = reason ? reason : "invalid params";
        return result;
    }
    if (!baseJpeg || baseSize < 4 || !mapJpeg || mapSize < 4) {
        result.error = "ultrahdr mux: null/empty input JPEG";
        return result;
    }
    if (mapJpeg[0] != 0xFF || mapJpeg[1] != 0xD8) {
        result.error = "ultrahdr mux: gain map missing SOI";
        return result;
    }
    JpegScan scan = scanBase(baseJpeg, baseSize);
    if (!scan.ok) {
        result.error = scan.error;
        return result;
    }

    const std::string xmpSecondary = buildSecondaryXmp(params);
    std::vector<uint8_t> isoSecondary;
    std::string isoError;
    if (!buildIsoBox(params, isoSecondary, isoError)) {
        result.error = isoError;
        return result;
    }
    const size_t xmpNsLen = sizeof(kXmpNs);  // includes NUL
    const size_t isoNsLen = sizeof(kIsoNs);  // includes NUL
    const size_t xmpSecField = 2 + xmpNsLen + xmpSecondary.size();
    const size_t isoSecField = 2 + isoNsLen + isoSecondary.size();
    const size_t secondarySize = 2 + (2 + xmpSecField) + (2 + isoSecField) + (mapSize - 2);
    if (secondarySize > (size_t)std::numeric_limits<uint32_t>::max()) {
        result.error = "ultrahdr mux: gain map image too large";
        return result;
    }
    const std::string xmpPrimary = buildPrimaryXmp(secondarySize);
    const size_t xmpPrimField = 2 + xmpNsLen + xmpPrimary.size();
    // ISO primary: namespace + min_version(2) + writer_version(2).
    const size_t isoPrimField = 2 + isoNsLen + 4;
    if (xmpPrimField + 2 > 65535 || xmpSecField + 2 > 65535 || isoSecField + 2 > 65535 ||
        (exifSize > 0 && exifSize + 2 > 65535)) {
        result.error = "ultrahdr mux: APP segment too large";
        return result;
    }

    std::vector<uint8_t> out;
    out.reserve(baseSize + mapSize + xmpPrimary.size() + xmpSecondary.size() + 512);
    // SOI
    out.push_back(0xFF);
    out.push_back(0xD8);
    // JFIF passthrough (libjpeg-turbo emits APP0 first)
    if (scan.hasJfif) out.insert(out.end(), baseJpeg + scan.jfifStart, baseJpeg + scan.jfifStart + scan.jfifTotal);
    // EXIF APP1
    if (exifSize > 0 && exifPayload) {
        putMarker(out, 0xE1);
        putU16BE(out, (uint16_t)(exifSize + 2));
        out.insert(out.end(), exifPayload, exifPayload + exifSize);
    }
    // XMP primary APP1
    putMarker(out, 0xE1);
    putU16BE(out, (uint16_t)xmpPrimField);
    out.insert(out.end(), kXmpNs, kXmpNs + xmpNsLen);
    out.insert(out.end(), xmpPrimary.begin(), xmpPrimary.end());
    // ISO primary APP2 (version only)
    putMarker(out, 0xE2);
    putU16BE(out, (uint16_t)isoPrimField);
    out.insert(out.end(), kIsoNs, kIsoNs + isoNsLen);
    out.insert(out.end(), {0, 0, 0, 0});
    // Remaining base segments (DQT/SOF/DHT/APP13...) up to SOS
    for (auto [start, total] : scan.keepRanges) out.insert(out.end(), baseJpeg + start, baseJpeg + start + total);
    // MPF APP2 (offsets need final primary size: pos + marker(2) + field + rest)
    const size_t mpfMarkerPos = out.size();
    std::vector<uint8_t> mpfPayload;
    const size_t mpfField = 2 + 86;
    const size_t primarySize = mpfMarkerPos + 2 + mpfField + (baseSize - scan.sosOffset);
    if (primarySize > (size_t)std::numeric_limits<uint32_t>::max()) {
        result.error = "ultrahdr mux: primary image too large";
        return result;
    }
    const uint32_t secondaryOffset = (uint32_t)(primarySize - mpfMarkerPos - 8);
    buildMpfPayload((uint32_t)primarySize, (uint32_t)secondarySize, secondaryOffset, mpfPayload);
    putMarker(out, 0xE2);
    putU16BE(out, (uint16_t)mpfField);
    out.insert(out.end(), mpfPayload.begin(), mpfPayload.end());
    // SOS..EOI of base
    out.insert(out.end(), baseJpeg + scan.sosOffset, baseJpeg + baseSize);
    // Secondary SOI
    const size_t secondarySoi = out.size();
    out.push_back(0xFF);
    out.push_back(0xD8);
    // XMP secondary APP1
    putMarker(out, 0xE1);
    putU16BE(out, (uint16_t)xmpSecField);
    out.insert(out.end(), kXmpNs, kXmpNs + xmpNsLen);
    out.insert(out.end(), xmpSecondary.begin(), xmpSecondary.end());
    // ISO secondary APP2
    putMarker(out, 0xE2);
    putU16BE(out, (uint16_t)isoSecField);
    out.insert(out.end(), kIsoNs, kIsoNs + isoNsLen);
    out.insert(out.end(), isoSecondary.begin(), isoSecondary.end());
    // Map scan data (skip its SOI)
    out.insert(out.end(), mapJpeg + 2, mapJpeg + mapSize);

    result.ok = true;
    result.primarySize = primarySize;
    result.secondarySize = secondarySize;
    result.secondaryOffset = secondarySoi;
    outFile = std::move(out);
    return result;
}

}  // namespace rawlens::gainmap
