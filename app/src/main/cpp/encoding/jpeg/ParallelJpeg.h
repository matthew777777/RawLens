// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Multi-threaded baseline JPEG. The image is cut into strips of whole MCU rows that
// libjpeg-turbo encodes independently with the same fixed Huffman tables; the strips become the
// restart intervals of one scan (DRI + RSTn). A restart only resets the DC predictor, so the
// quantized coefficients, and the decoded pixels, are those of the sequential encoder.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include <jpeglib.h>
#include <setjmp.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <thread>
#include <vector>

namespace rawlens::encoding::jpeg {

struct ParallelJpegInput {
    const unsigned char* rgba = nullptr;
    int width = 0, height = 0;
    size_t stride = 0;
    int quality = 95;
    bool yuv444 = false;   // else 4:2:2
    const unsigned char* icc = nullptr;
    size_t iccSize = 0;
};

// Byte ranges that make the file, in order.
struct ParallelJpegOutput {
    struct Piece { const unsigned char* data; size_t size; };
    std::vector<Piece> pieces;
    ParallelJpegOutput() = default;
    ParallelJpegOutput(const ParallelJpegOutput&) = delete;
    ParallelJpegOutput& operator=(const ParallelJpegOutput&) = delete;
    ~ParallelJpegOutput() { for (unsigned char* p : owned) std::free(p); }
    size_t size() const { size_t n = 0; for (const Piece& p : pieces) n += p.size; return n; }
    std::vector<unsigned char*> owned;   // malloc'ed by libjpeg
    std::vector<unsigned char> header;
};

namespace detail {

struct Error { jpeg_error_mgr pub; jmp_buf jump; char message[JMSG_LENGTH_MAX]; };
inline void onError(j_common_ptr cinfo) {
    auto* err = reinterpret_cast<Error*>(cinfo->err);
    (*cinfo->err->format_message)(cinfo, err->message);
    longjmp(err->jump, 1);
}

inline void writeIcc(j_compress_ptr cinfo, const unsigned char* data, size_t size) {
    constexpr size_t kHeader = 14, kMaxChunk = 65533 - kHeader;
    if (!data || size == 0) return;
    const int count = static_cast<int>((size + kMaxChunk - 1) / kMaxChunk);
    size_t offset = 0;
    for (int index = 1; index <= count; ++index) {
        const size_t chunk = std::min(kMaxChunk, size - offset);
        // jpeg_write_m_* keeps no heap object alive across a longjmp.
        jpeg_write_m_header(cinfo, JPEG_APP0 + 2, static_cast<unsigned int>(kHeader + chunk));
        static const char kTag[12] = {'I','C','C','_','P','R','O','F','I','L','E','\0'};
        for (char c : kTag) jpeg_write_m_byte(cinfo, c);
        jpeg_write_m_byte(cinfo, index);
        jpeg_write_m_byte(cinfo, count);
        for (size_t i = 0; i < chunk; ++i) jpeg_write_m_byte(cinfo, data[offset + i]);
        offset += chunk;
    }
}

struct Strip {
    unsigned char* data = nullptr;
    unsigned long size = 0;
    std::string error;
};

inline void encodeStrip(const ParallelJpegInput& in, int y0, int rows, bool first, Strip& out) {
    jpeg_compress_struct cinfo{};
    Error jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = onError;
    if (setjmp(jerr.jump)) {
        jpeg_destroy_compress(&cinfo);
        out.error = jerr.message;
        return;
    }
    jpeg_create_compress(&cinfo);
    jpeg_mem_dest(&cinfo, &out.data, &out.size);
    cinfo.image_width = static_cast<JDIMENSION>(in.width);
    cinfo.image_height = static_cast<JDIMENSION>(rows);
    cinfo.input_components = 4;
    cinfo.in_color_space = JCS_EXT_RGBA;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, std::max(1, std::min(100, in.quality)), TRUE);
    cinfo.optimize_coding = FALSE;
    if (in.quality >= 98) cinfo.dct_method = JDCT_ISLOW;
    for (int c = 0; c < 3; ++c) {
        cinfo.comp_info[c].h_samp_factor = (c == 0 && !in.yuv444) ? 2 : 1;
        cinfo.comp_info[c].v_samp_factor = 1;
    }
    jpeg_start_compress(&cinfo, TRUE);
    if (first) writeIcc(&cinfo, in.icc, in.iccSize);
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW r[8];
        const int n = std::min<int>(8, rows - static_cast<int>(cinfo.next_scanline));
        for (int i = 0; i < n; ++i)
            r[i] = const_cast<JSAMPROW>(in.rgba + (static_cast<size_t>(y0) + cinfo.next_scanline + i) * in.stride);
        jpeg_write_scanlines(&cinfo, r, static_cast<JDIMENSION>(n));
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
}

// Offset just past the marker segments: [sof] and [sos] are the positions of the 0xFF bytes.
inline bool findScan(const unsigned char* d, size_t n, size_t& sof, size_t& sos, size_t& scan) {
    size_t i = 2;
    sof = 0;
    while (i + 4 <= n) {
        if (d[i] != 0xff) return false;
        const int m = d[i + 1];
        const size_t len = (size_t(d[i + 2]) << 8) | d[i + 3];
        if (i + 2 + len > n) return false;
        if (m >= 0xc0 && m <= 0xc2) sof = i;
        if (m == 0xda) { sos = i; scan = i + 2 + len; return sof != 0 && n >= scan + 2; }
        i += 2 + len;
    }
    return false;
}

}  // namespace detail

// maxThreads: 0 = automatic (at most 4: the encode competes with the next capture).
inline bool encodeParallel(const ParallelJpegInput& in, ParallelJpegOutput& out, std::string& error, int maxThreads = 0) {
    if (!in.rgba || in.width <= 0 || in.height <= 0 || in.width > 65500 || in.height > 65500 ||
        in.stride < size_t(in.width) * 4) { error = "invalid JPEG input"; return false; }
    const int mcuW = in.yuv444 ? 8 : 16, mcuRows = (in.height + 7) / 8, perRow = (in.width + mcuW - 1) / mcuW;
    int threads = maxThreads > 0 ? maxThreads : int(std::min(4u, std::max(1u, std::thread::hardware_concurrency())));
    // Two strips per thread keeps big and little cores busy; DRI is 16 bit.
    int rowsPer = std::max(1, (mcuRows + 2 * threads - 1) / (2 * threads));
    rowsPer = std::max(1, std::min(rowsPer, 65535 / perRow));
    const int count = (mcuRows + rowsPer - 1) / rowsPer;
    threads = std::min(threads, count);
    std::vector<detail::Strip> strips(static_cast<size_t>(count));
    std::atomic<int> next{0};
    auto work = [&] {
        for (int k; (k = next.fetch_add(1)) < count;) {
            const int y0 = k * rowsPer * 8;
            detail::encodeStrip(in, y0, std::min(rowsPer * 8, in.height - y0), k == 0, strips[size_t(k)]);
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; ++t) {
        try { pool.emplace_back(work); } catch (const std::exception&) { break; }
    }
    work();
    for (std::thread& t : pool) t.join();
    for (detail::Strip& s : strips) {
        if (s.data) out.owned.push_back(s.data);
        if (!s.error.empty()) error = s.error;
        else if (!s.data && error.empty()) error = "JPEG strip has no data";
    }
    if (!error.empty()) return false;
    static const unsigned char kRst[8][2] = {{0xff,0xd0},{0xff,0xd1},{0xff,0xd2},{0xff,0xd3},{0xff,0xd4},{0xff,0xd5},{0xff,0xd6},{0xff,0xd7}};
    static const unsigned char kEoi[2] = {0xff, 0xd9};
    out.pieces.reserve(size_t(count) * 2 + 1);
    for (int k = 0; k < count; ++k) {
        const detail::Strip& s = strips[size_t(k)];
        size_t sof = 0, sos = 0, scan = 0;
        if (!detail::findScan(s.data, s.size, sof, sos, scan)) { error = "JPEG strip has no scan"; return false; }
        if (k == 0) {
            out.header.assign(s.data, s.data + sos);
            out.header[sof + 5] = static_cast<unsigned char>(in.height >> 8);
            out.header[sof + 6] = static_cast<unsigned char>(in.height & 0xff);
            if (count > 1) {
                const int dri = rowsPer * perRow;
                const unsigned char m[6] = {0xff, 0xdd, 0, 4, static_cast<unsigned char>(dri >> 8), static_cast<unsigned char>(dri & 0xff)};
                out.header.insert(out.header.end(), m, m + 6);
            }
            out.header.insert(out.header.end(), s.data + sos, s.data + scan);
            out.pieces.push_back({out.header.data(), out.header.size()});
        } else {
            out.pieces.push_back({kRst[(k - 1) & 7], 2});
        }
        out.pieces.push_back({s.data + scan, s.size - scan - 2});
    }
    out.pieces.push_back({kEoi, 2});
    return true;
}

}  // namespace rawlens::encoding::jpeg
