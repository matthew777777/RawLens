// Proof for the TinyDNG v3 raw reader (SrpReadDngMeta/Pixels).
//
// Usage: srp_proof_dngread <frame.dng> <ground_truth_dir>
// Compares the full-frame uint16 decode against <ground_truth_dir>/f00_raw.npy
// (rawpy raw_image), the 64x64 even-aligned region at (2008,1498) against
// f00_region.npy, and prints metadata + noise profile for eyeball comparison
// against the rawpy values (white 1023, black 4x64, pattern GRBG [[1,2],[0,1]]
// after the 3->1 G2 remap, alpha/beta from the NoiseProfile tag).
// Prints numbers; exits nonzero on any pixel mismatch.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "srp_dng.h"

namespace {

std::vector<uint8_t> ReadFile(const char* path) {
  std::vector<uint8_t> bytes;
  if (FILE* f = std::fopen(path, "rb")) {
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    if (n > 0) {
      bytes.resize(static_cast<size_t>(n));
      bytes.resize(std::fread(bytes.data(), 1, bytes.size(), f));
    }
    std::fclose(f);
  }
  return bytes;
}

// Minimal .npy uint16 C-order loader (no pickle): parses the v1.0 header
// for shape, then compares raw payload bytes.
bool LoadU16Npy(const char* path, std::vector<uint16_t>* out, size_t* h,
                size_t* w) {
  const std::vector<uint8_t> bytes = ReadFile(path);
  if (bytes.size() < 10) return false;
  if (bytes[0] != 0x93 || bytes[1] != 'N' || bytes[2] != 'U' ||
      bytes[3] != 'M' || bytes[4] != 'P' || bytes[5] != 'Y') {
    return false;
  }
  const size_t hlen = static_cast<size_t>(bytes[8]) |
                      (static_cast<size_t>(bytes[9]) << 8);
  if (10 + hlen > bytes.size()) return false;
  const std::string header(reinterpret_cast<const char*>(bytes.data() + 10),
                           hlen);
  // Expect shape (H, W); parse the two integers after 'shape'.
  const size_t sp = header.find("shape");
  if (sp == std::string::npos) return false;
  unsigned long dims[2] = {0, 0};
  int got = 0;
  bool in_num = false;
  unsigned long cur = 0;
  for (size_t i = sp; i < header.size() && got < 2; ++i) {
    const char c = header[i];
    if (c >= '0' && c <= '9') {
      cur = cur * 10 + static_cast<unsigned long>(c - '0');
      in_num = true;
    } else if (in_num) {
      dims[got++] = cur;
      cur = 0;
      in_num = false;
    }
  }
  if (got != 2) return false;
  *h = dims[0];
  *w = dims[1];
  const size_t off = 10 + hlen;
  if (off + dims[0] * dims[1] * 2 > bytes.size()) return false;
  out->resize(dims[0] * dims[1]);
  std::memcpy(out->data(), bytes.data() + off, dims[0] * dims[1] * 2);
  return true;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 3) {
    std::fprintf(stderr, "usage: %s <frame.dng> <ground_truth_dir>\n", argv[0]);
    return 2;
  }
  const char* dng = argv[1];
  const std::string gt = argv[2];

  srp::SrpDngMeta meta{};
  if (srp::SrpReadDngMeta(dng, &meta) != srp::DngReadStatus::kOk) {
    std::fprintf(stderr, "FAIL: meta read failed\n");
    return 1;
  }
  std::printf("meta: %ux%u white=%u black=[%d,%d,%d,%d] cfa=[%u,%u,%u,%u] noise_count=%u\n",
              meta.width, meta.height, meta.white_level, meta.black_level[0],
              meta.black_level[1], meta.black_level[2], meta.black_level[3],
              meta.cfa[0], meta.cfa[1], meta.cfa[2], meta.cfa[3],
              meta.noise_count);
  std::printf("noise: [%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g]\n",
              meta.noise[0], meta.noise[1], meta.noise[2], meta.noise[3],
              meta.noise[4], meta.noise[5], meta.noise[6], meta.noise[7]);

  int failures = 0;
  std::vector<uint16_t> want_full, want_reg;
  size_t fh = 0, fw = 0, rh = 0, rw = 0;
  if (!LoadU16Npy((gt + "/f00_raw.npy").c_str(), &want_full, &fh, &fw) ||
      !LoadU16Npy((gt + "/f00_region.npy").c_str(), &want_reg, &rh, &rw)) {
    std::fprintf(stderr, "FAIL: ground truth missing\n");
    return 1;
  }
  std::vector<uint16_t> got_full(fw * fh), got_reg(rw * rh);
  if (srp::SrpReadDngPixels(dng, got_full.data(), 0, 0, 0, 0) !=
      srp::DngReadStatus::kOk) {
    std::fprintf(stderr, "FAIL: full decode failed\n");
    return 1;
  }
  // Region origin recorded with the fixtures: x=2008, y=1498.
  if (srp::SrpReadDngPixels(dng, got_reg.data(), 2008, 1498,
                            static_cast<uint32_t>(rw),
                            static_cast<uint32_t>(rh)) !=
      srp::DngReadStatus::kOk) {
    std::fprintf(stderr, "FAIL: region decode failed\n");
    return 1;
  }
  size_t bad_full = 0, bad_reg = 0;
  for (size_t i = 0; i < want_full.size(); ++i) {
    if (got_full[i] != want_full[i] && bad_full < 5) {
      std::printf("full mismatch @%zu: got %u want %u\n", i, got_full[i],
                  want_full[i]);
    }
    bad_full += (got_full[i] != want_full[i]);
  }
  for (size_t i = 0; i < want_reg.size(); ++i) {
    if (got_reg[i] != want_reg[i] && bad_reg < 5) {
      std::printf("region mismatch @%zu: got %u want %u\n", i, got_reg[i],
                  want_reg[i]);
    }
    bad_reg += (got_reg[i] != want_reg[i]);
  }
  std::printf("full: n=%zu mismatches=%zu %s\n", want_full.size(), bad_full,
              bad_full == 0 ? "PASS" : "FAIL");
  std::printf("region: n=%zu mismatches=%zu %s\n", want_reg.size(), bad_reg,
              bad_reg == 0 ? "PASS" : "FAIL");
  failures = (bad_full == 0 && bad_reg == 0) ? 0 : 1;
  return failures;
}
