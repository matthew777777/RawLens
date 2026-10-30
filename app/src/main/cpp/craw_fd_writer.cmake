# Build-tree FD adaptation; do not modify the vendored upstream CRAW files.
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/craw-fd/include/craw" "${CMAKE_CURRENT_BINARY_DIR}/craw-fd/src")
file(READ "${CRAW_DIR}/encoder/include/craw/Writer.h" craw_adapted)
set(craw_before [====[
    // `metaJson` is the META chunk (SPEC.md section 2). Throws std::runtime_error.
    Writer(const std::string& path, std::string_view metaJson, unsigned threads = 1);
    ~Writer();
    Writer(const Writer&) = delete;
]====])
set(craw_after [====[
    // `metaJson` is the META chunk (SPEC.md section 2). Throws std::runtime_error.
    Writer(const std::string& path, std::string_view metaJson, unsigned threads = 1);
    // Adopts stream ownership, including closing it if initialization fails.
    Writer(std::FILE* stream, std::string_view metaJson, unsigned threads = 1);
    ~Writer();
    Writer(const Writer&) = delete;
]====])
string(FIND "${craw_adapted}" "${craw_before}" craw_match)
if(craw_match LESS 0)
  message(FATAL_ERROR "CRAW upstream changed; review FD adaptation")
endif()
string(REPLACE "${craw_before}" "${craw_after}" craw_adapted "${craw_adapted}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/craw-fd/include/craw/Writer.h" "${craw_adapted}")
file(READ "${CRAW_DIR}/encoder/src/Writer.cpp" craw_adapted)
set(craw_before [====[

Writer::Writer(const std::string& path, std::string_view metaJson, unsigned threads)
    : threads_(threads) {
    f_ = std::fopen(path.c_str(), "wb");
    if (!f_) throw std::runtime_error("craw::Writer: cannot open " + path);
    std::vector<uint8_t> head{'C', 'R', 'A', 'W'};
    put<uint16_t>(head, 0);
]====])
set(craw_after [====[

Writer::Writer(const std::string& path, std::string_view metaJson, unsigned threads)
    : Writer(std::fopen(path.c_str(), "wb"), metaJson, threads) {}

Writer::Writer(std::FILE* stream, std::string_view metaJson, unsigned threads)
    : f_(stream), threads_(threads) {
    if (!f_) throw std::runtime_error("craw::Writer: cannot open destination");
    try {
    std::vector<uint8_t> head{'C', 'R', 'A', 'W'};
    put<uint16_t>(head, 0);
]====])
string(FIND "${craw_adapted}" "${craw_before}" craw_match)
if(craw_match LESS 0)
  message(FATAL_ERROR "CRAW upstream changed; review FD adaptation")
endif()
string(REPLACE "${craw_before}" "${craw_after}" craw_adapted "${craw_adapted}")
set(craw_before [====[
    putChunk("META", 0, scratch_);
    index_.clear();  // META is not indexed
}

]====])
set(craw_after [====[
    putChunk("META", 0, scratch_);
    index_.clear();  // META is not indexed
    } catch (...) {
        std::fclose(f_);
        f_ = nullptr;
        throw;
    }
}

]====])
string(FIND "${craw_adapted}" "${craw_before}" craw_match)
if(craw_match LESS 0)
  message(FATAL_ERROR "CRAW upstream changed; review FD adaptation")
endif()
string(REPLACE "${craw_before}" "${craw_after}" craw_adapted "${craw_adapted}")
set(craw_before [====[
        put<int64_t>(scratch_, e.ts);
    }
    putChunk("INDX", 0, scratch_);
    std::vector<uint8_t> trailer;
    put<uint64_t>(trailer, indexOffset);
]====])
set(craw_after [====[
        put<int64_t>(scratch_, e.ts);
    }
    try { putChunk("INDX", 0, scratch_); } catch (...) {
        std::fclose(f_);
        f_ = nullptr;
        throw;
    }
    std::vector<uint8_t> trailer;
    put<uint64_t>(trailer, indexOffset);
]====])
string(FIND "${craw_adapted}" "${craw_before}" craw_match)
if(craw_match LESS 0)
  message(FATAL_ERROR "CRAW upstream changed; review FD adaptation")
endif()
string(REPLACE "${craw_before}" "${craw_after}" craw_adapted "${craw_adapted}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/craw-fd/src/Writer.cpp" "${craw_adapted}")
