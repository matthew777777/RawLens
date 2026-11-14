// SPDX-License-Identifier: Apache-2.0
#pragma once
// Lossless staging of RAW16 burst frames at the sensor's real bit depth: every row is a
// little-endian bit stream of `bits` bits per sample, rows start on a byte boundary so any
// row range can be read on its own. A staged buffer has kPackPad spare bytes at its end.

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace lkrl {

constexpr int kPackPad = 8;

inline size_t PackedRowBytes(int width, int bits) { return (size_t(width) * bits + 7) / 8; }
inline size_t PackedBytes(int width, int height, int bits) { return PackedRowBytes(width, bits) * height + kPackPad; }

// Packs h rows of w samples (source row pitch stride_px). Returns false, leaving dst undefined,
// when a sample does not fit in `bits` bits (the caller then keeps 16 bits).
inline bool PackRows(const uint16_t* src, int w, int h, int stride_px, int bits, uint8_t* dst) {
  const size_t rb = PackedRowBytes(w, bits);
  uint32_t seen = 0;
  for (int y = 0; y < h; ++y) {
    const uint16_t* s = src + size_t(y) * stride_px;
    uint8_t* d = dst + size_t(y) * rb;
    uint64_t acc = 0;
    int have = 0;
    for (int x = 0; x < w; ++x) {
      const uint32_t v = s[x];
      seen |= v;
      acc |= uint64_t(v) << have;
      have += bits;
      while (have >= 8) {
        *d++ = uint8_t(acc);
        acc >>= 8;
        have -= 8;
      }
    }
    if (have > 0) *d++ = uint8_t(acc);
  }
  std::memset(dst + rb * h, 0, kPackPad);
  return (seen >> bits) == 0;
}

// Samples [x0, x0 + n) of one packed row.
inline void UnpackRow(const uint8_t* row, int x0, int n, int bits, uint16_t* out) {
  const uint32_t mask = (1u << bits) - 1u;
  size_t bit = size_t(x0) * bits;
  for (int i = 0; i < n; ++i, bit += bits) {
    uint32_t v;
    std::memcpy(&v, row + (bit >> 3), 4);   // little-endian hosts (arm64, x86-64)
    out[i] = uint16_t((v >> (bit & 7)) & mask);
  }
}

}  // namespace lkrl
