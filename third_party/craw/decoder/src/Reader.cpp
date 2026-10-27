// SPDX-License-Identifier: Apache-2.0
#include <craw/Reader.h>

#include "Crc32c.h"

#include <cerrno>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>

#if defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#define CRAW_HAVE_WIN32_MAP 1
#elif defined(__unix__) || defined(__APPLE__)
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#define CRAW_HAVE_MMAP 1
#endif

namespace craw {
namespace {

uint32_t rd32(const uint8_t* p) { return uint32_t(p[0]) | p[1] << 8 | p[2] << 16 | uint32_t(p[3]) << 24; }
uint64_t rd64(const uint8_t* p) { return uint64_t(rd32(p)) | uint64_t(rd32(p + 4)) << 32; }

constexpr size_t kChunkHeader = 16, kChunkTail = 4, kFramHeader = 20;

}  // namespace

Reader::Reader(const std::string& path) {
#ifdef CRAW_HAVE_MMAP
    const int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) throw DecodeError("craw: cannot open " + path);
    struct stat st{};
    if (::fstat(fd, &st) == 0 && st.st_size > 0) {
        void* m = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
        if (m != MAP_FAILED) {
            data_ = static_cast<const uint8_t*>(m);
            size_ = static_cast<size_t>(st.st_size);
        }
    }
    ::close(fd);
#endif
#ifdef CRAW_HAVE_WIN32_MAP
    {
        const int wlen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
        std::wstring wpath(wlen > 0 ? wlen : 0, L'\0');
        if (wlen > 0) MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, wpath.data(), wlen);
        HANDLE file = CreateFileW(wpath.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                                  FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file == INVALID_HANDLE_VALUE) throw DecodeError("craw: cannot open " + path);
        LARGE_INTEGER sz{};
        if (GetFileSizeEx(file, &sz) && sz.QuadPart > 0 && static_cast<uint64_t>(sz.QuadPart) <= SIZE_MAX) {
            if (HANDLE mapping = CreateFileMappingW(file, nullptr, PAGE_READONLY, 0, 0, nullptr)) {
                if (void* view = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 0)) {
                    data_ = static_cast<const uint8_t*>(view);
                    size_ = static_cast<size_t>(sz.QuadPart);
                }
                CloseHandle(mapping);  // the view keeps the mapping alive
            }
        }
        CloseHandle(file);
    }
#endif
    if (!data_) {
        std::ifstream f(path, std::ios::binary);
        if (!f) throw DecodeError("craw: cannot open " + path);
        owned_.assign(std::istreambuf_iterator<char>(f), {});
        data_ = owned_.data();
        size_ = owned_.size();
    }
    scanOrRelease();
}

#ifndef _WIN32
Reader::Reader(int fd, const std::string& name) {
    struct stat st{};
    if (fd < 0 || ::fstat(fd, &st) != 0) throw DecodeError("craw: cannot open " + name);
#ifdef CRAW_HAVE_MMAP
    if (st.st_size > 0) {
        void* m = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
        if (m != MAP_FAILED) {
            data_ = static_cast<const uint8_t*>(m);
            size_ = static_cast<size_t>(st.st_size);
        }
    }
#endif
    if (!data_) {
        // No mmap: read the whole file through the descriptor.
        owned_.resize(static_cast<size_t>(st.st_size));
        size_t got = 0;
        while (got < owned_.size()) {
            const ssize_t n = ::pread(fd, owned_.data() + got, owned_.size() - got, static_cast<off_t>(got));
            if (n <= 0) throw DecodeError("craw: cannot read " + name);
            got += static_cast<size_t>(n);
        }
        data_ = owned_.data();
        size_ = owned_.size();
    }
    scanOrRelease();
}
#endif

void Reader::scanOrRelease() {
    try {
        scan();
    } catch (...) {
        release();
        throw;
    }
}

Reader::~Reader() { release(); }

void Reader::release() {
#ifdef CRAW_HAVE_MMAP
    if (data_ && owned_.empty()) ::munmap(const_cast<uint8_t*>(data_), size_);
#endif
#ifdef CRAW_HAVE_WIN32_MAP
    if (data_ && owned_.empty()) UnmapViewOfFile(data_);
#endif
    data_ = nullptr;
    size_ = 0;
}

void Reader::scan() {
    const uint8_t* d = data_;
    const size_t n = size_;
    if (n < 16 || std::memcmp(d, "CRAW", 4) != 0) throw DecodeError("craw: not a CRAW file");
    if ((d[4] | d[5] << 8) != 0 || (d[6] | d[7] << 8) < 2) throw DecodeError("craw: unsupported major version");

    if (scanIndex()) return;

    // Crashed recording (no valid trailer): walk every chunk header.
    bool sawIndex = false;
    for (size_t pos = 16; pos + kChunkHeader + kChunkTail <= n;) {
        if (std::memcmp(d + pos, "CRAWEND", 7) == 0) break;
        const uint64_t size = rd64(d + pos + 8);
        if (size > n - pos - kChunkHeader - kChunkTail) break;  // truncated
        ChunkRef c{};
        std::memcpy(c.fourcc.data(), d + pos, 4);
        c.offset = pos;
        c.size = size;
        const uint8_t* body = d + pos + kChunkHeader;
        if (std::memcmp(d + pos, "FRAM", 4) == 0 && size >= kFramHeader)
            c.timestampNs = static_cast<int64_t>(rd64(body + 8));
        else if ((std::memcmp(d + pos, "AUDI", 4) == 0 || std::memcmp(d + pos, "LSHD", 4) == 0) && size >= 8)
            c.timestampNs = static_cast<int64_t>(rd64(body));
        pos += kChunkHeader + size + kChunkTail;
        if (std::memcmp(c.fourcc.data(), "INDX", 4) == 0) { sawIndex = true; break; }
        if (std::memcmp(c.fourcc.data(), "META", 4) == 0 && meta_.empty()) {
            meta_.assign(reinterpret_cast<const char*>(body), size);
            continue;
        }
        if (std::memcmp(c.fourcc.data(), "FRAM", 4) == 0) frames_.push_back(chunks_.size());
        chunks_.push_back(c);
    }
    recovered_ = !sawIndex;
}

bool Reader::crcOk(const ChunkRef& c) const {
    const uint8_t* body = data_ + c.offset + kChunkHeader;
    return detail::crc32c(body, static_cast<size_t>(c.size)) == rd32(body + c.size);
}

// A cleanly closed file lists every chunk but META and INDX in file order
// (SPEC 7); chunks are contiguous, so each payload size follows from the
// next entry's offset. Opening then touches only the header, META, the
// trailer and INDX — no frame data (large files behind FUSE open at once).
bool Reader::scanIndex() {
    const uint8_t* d = data_;
    const size_t n = size_;
    if (n < 16 + 16 || std::memcmp(d + n - 8, "CRAWEND\0", 8) != 0) return false;
    const uint64_t ix = rd64(d + n - 16);
    if (ix < 16 || ix > n - 16 - kChunkHeader - kChunkTail - 4 || std::memcmp(d + ix, "INDX", 4) != 0) return false;
    const uint64_t ixSize = rd64(d + ix + 8);
    if (ixSize != n - 16 - ix - kChunkHeader - kChunkTail) return false;
    const uint8_t* body = d + ix + kChunkHeader;
    if (detail::crc32c(body, static_cast<size_t>(ixSize)) != rd32(body + ixSize)) return false;
    const uint32_t count = rd32(body);
    if (ixSize != 4 + uint64_t{count} * 24) return false;
    // META is the first chunk.
    if (std::memcmp(d + 16, "META", 4) != 0) return false;
    const uint64_t metaSize = rd64(d + 24);
    if (metaSize > ix - 16 - kChunkHeader - kChunkTail) return false;
    std::vector<ChunkRef> chunks;
    std::vector<size_t> frames;
    chunks.reserve(count);
    uint64_t expected = 16 + kChunkHeader + metaSize + kChunkTail;
    for (uint32_t i = 0; i < count; ++i) {
        const uint8_t* e = body + 4 + size_t{i} * 24;
        ChunkRef c{};
        std::memcpy(c.fourcc.data(), e, 4);
        c.offset = rd64(e + 8);
        c.timestampNs = static_cast<int64_t>(rd64(e + 16));
        if (c.offset != expected) return false;  // not contiguous: fall back to the walk
        const uint64_t next = i + 1 < count ? rd64(e + 24 + 8) : ix;
        if (next < c.offset + kChunkHeader + kChunkTail) return false;
        c.size = next - c.offset - kChunkHeader - kChunkTail;
        expected = next;
        if (std::memcmp(c.fourcc.data(), "FRAM", 4) == 0) {
            if (c.size < kFramHeader) return false;
            frames.push_back(chunks.size());
        }
        chunks.push_back(c);
    }
    meta_.assign(reinterpret_cast<const char*>(d + 16 + kChunkHeader), metaSize);
    chunks_ = std::move(chunks);
    frames_ = std::move(frames);
    recovered_ = false;
    return true;
}

std::string_view Reader::payload(const ChunkRef& c) const {
    return {reinterpret_cast<const char*>(data_ + c.offset + kChunkHeader), static_cast<size_t>(c.size)};
}

FrameRecord Reader::frame(size_t i) const {
    const ChunkRef& c = chunks_.at(frames_.at(i));
    if (rd64(data_ + c.offset + 8) != c.size) throw DecodeError("craw: FRAM chunk size mismatch");
    if (!crcOk(c)) throw DecodeError("craw: FRAM chunk CRC mismatch");
    if (c.size < kFramHeader) throw DecodeError("craw: FRAM too small");
    const uint8_t* b = data_ + c.offset + kChunkHeader;
    const uint32_t metaLen = rd32(b + 16);
    if (metaLen > c.size - kFramHeader) throw DecodeError("craw: bad FRAM meta length");
    return {rd64(b), static_cast<int64_t>(rd64(b + 8)),
            std::string(reinterpret_cast<const char*>(b + kFramHeader), metaLen),
            b + kFramHeader + metaLen, static_cast<size_t>(c.size - kFramHeader - metaLen)};
}

}  // namespace craw
