// SPDX-License-Identifier: Apache-2.0
#include <craw/Reader.h>

#include "Crc32c.h"

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
        std::wstring wpath(wlen > 0 ? wlen - 1 : 0, L'\0');
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

    // Scan every chunk: cheap (headers only) and works for crashed files too.
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
        c.crcOk = detail::crc32c(body, static_cast<size_t>(size)) == rd32(body + size);
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

std::string_view Reader::payload(const ChunkRef& c) const {
    return {reinterpret_cast<const char*>(data_ + c.offset + kChunkHeader), static_cast<size_t>(c.size)};
}

FrameRecord Reader::frame(size_t i) const {
    const ChunkRef& c = chunks_.at(frames_.at(i));
    if (!c.crcOk) throw DecodeError("craw: FRAM chunk CRC mismatch");
    if (c.size < kFramHeader) throw DecodeError("craw: FRAM too small");
    const uint8_t* b = data_ + c.offset + kChunkHeader;
    const uint32_t metaLen = rd32(b + 16);
    if (metaLen > c.size - kFramHeader) throw DecodeError("craw: bad FRAM meta length");
    return {rd64(b), static_cast<int64_t>(rd64(b + 8)),
            std::string(reinterpret_cast<const char*>(b + kFramHeader), metaLen),
            b + kFramHeader + metaLen, static_cast<size_t>(c.size - kFramHeader - metaLen)};
}

}  // namespace craw
