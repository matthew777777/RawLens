// Minimal .npy reader for proof harnesses (float32 C-order only).
//
// Loads the parity fixtures and /tmp reference dumps the Python side writes:
// NumPy v1.0 files, descr '<f4', fortran_order False, any dimensionality.
// Row-major element data lands in `data`; `shape` carries the dimensions.
// Dies loudly (stderr + false) on anything else — proofs must never
// silently compare against misparsed references.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace srp_proof {

struct NpyArray {
  std::vector<uint64_t> shape;
  std::vector<float> data;
};

inline uint64_t NpyCount(const NpyArray& a) {
  uint64_t n = 1;
  for (uint64_t d : a.shape) n *= d;
  return n;
}

inline bool LoadNpy(const char* path, NpyArray* out) {
  FILE* f = std::fopen(path, "rb");
  if (f == nullptr) {
    std::fprintf(stderr, "npy: cannot open %s\n", path);
    return false;
  }
  bool ok = false;
  uint8_t magic[6] = {};
  if (std::fread(magic, 1, 6, f) == 6 && magic[0] == 0x93 &&
      std::memcmp(magic + 1, "NUMPY", 5) == 0) {
    uint8_t ver[2] = {};
    if (std::fread(ver, 1, 2, f) == 2) {
      uint64_t header_len = 0;
      if (ver[0] == 1 && ver[1] == 0) {
        uint8_t len[2] = {};
        if (std::fread(len, 1, 2, f) == 2) {
          header_len = static_cast<uint64_t>(len[0]) |
                       (static_cast<uint64_t>(len[1]) << 8);
        }
      } else {
        uint8_t len[4] = {};
        if (std::fread(len, 1, 4, f) == 4) {
          header_len = static_cast<uint64_t>(len[0]) |
                       (static_cast<uint64_t>(len[1]) << 8) |
                       (static_cast<uint64_t>(len[2]) << 16) |
                       (static_cast<uint64_t>(len[3]) << 24);
        }
      }
      if (header_len > 0 && header_len < 16 * 1024 * 1024) {
        std::string header(header_len, '\0');
        if (std::fread(header.data(), 1, header_len, f) == header_len &&
            header.find("'descr': '<f4'") != std::string::npos &&
            header.find("'fortran_order': False") != std::string::npos) {
          const size_t shape_pos = header.find("'shape':");
          const size_t paren = header.find('(', shape_pos);
          const size_t close = header.find(')', paren);
          if (shape_pos != std::string::npos && paren != std::string::npos &&
              close != std::string::npos) {
            std::string dims = header.substr(paren + 1, close - paren - 1);
            NpyArray arr;
            size_t pos = 0;
            while (pos < dims.size()) {
              while (pos < dims.size() &&
                     (dims[pos] == ' ' || dims[pos] == ',')) {
                ++pos;
              }
              if (pos >= dims.size()) break;
              size_t end = pos;
              while (end < dims.size() && dims[end] >= '0' &&
                     dims[end] <= '9') {
                ++end;
              }
              if (end == pos) break;
              arr.shape.push_back(
                  static_cast<uint64_t>(std::stoull(dims.substr(pos, end - pos))));
              pos = end;
            }
            const uint64_t n = NpyCount(arr);
            if (!arr.shape.empty() && n > 0 &&
                n < static_cast<uint64_t>(1) << 32) {
              arr.data.resize(static_cast<size_t>(n));
              if (std::fread(arr.data.data(), sizeof(float),
                             static_cast<size_t>(n), f) ==
                  static_cast<size_t>(n)) {
                *out = std::move(arr);
                ok = true;
              }
            }
          }
        } else {
          std::fprintf(stderr, "npy: unsupported header in %s\n", path);
        }
      }
    }
  } else {
    std::fprintf(stderr, "npy: bad magic in %s\n", path);
  }
  std::fclose(f);
  return ok;
}

}  // namespace srp_proof
