// SPDX-License-Identifier: Apache-2.0
//
// .CRAW container reader (SPEC.md section 1). Uses the INDX chunk when the
// trailer is intact, otherwise recovers a crashed recording by scanning.
#pragma once

#include <craw/Decoder.h>

#include <array>
#include <string>
#include <string_view>
#include <vector>

namespace craw {

struct ChunkRef {
    std::array<char, 4> fourcc;
    uint64_t offset;      // chunk header offset
    uint64_t size;        // payload size
    int64_t timestampNs;  // from INDX, or parsed for FRAM when recovered
    bool crcOk;
};

struct FrameRecord {
    uint64_t index;
    int64_t timestampNs;
    std::string metaJson;
    const uint8_t* payload;
    size_t payloadSize;
};

class Reader {
public:
    explicit Reader(const std::string& path);  // throws DecodeError; memory-maps the file
    ~Reader();
    Reader(const Reader&) = delete;
    Reader& operator=(const Reader&) = delete;

    const std::string& metaJson() const { return meta_; }
    const std::vector<ChunkRef>& chunks() const { return chunks_; }
    bool recovered() const { return recovered_; }
    size_t frameCount() const { return frames_.size(); }

    // Chunk payload bytes; verifies CRC lazily.
    std::string_view payload(const ChunkRef& c) const;
    FrameRecord frame(size_t i) const;

private:
    void scan();
    void release();

    const uint8_t* data_ = nullptr;
    size_t size_ = 0;
    std::vector<uint8_t> owned_;  // fallback when mmap is unavailable
    std::string meta_;
    std::vector<ChunkRef> chunks_;
    std::vector<size_t> frames_;  // indices into chunks_
    bool recovered_ = false;
};

}  // namespace craw
