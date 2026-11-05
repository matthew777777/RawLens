// SPDX-License-Identifier: GPL-3.0-or-later
// Exact app standalone pipeline on an RGBA16F file, supplied by compare_ffdnet.py.
#include "lkr_photo_denoise.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
int main(int argc, char** argv) {
  if (argc < 12 || argc > 15) {
    std::fprintf(stderr, "usage: lkr_denoise_run IN.rgba16f OUT.rgba16f W H CFA4 WR WG WB STRENGTH PARAM BIN [CHROMA_STRIDE [LUMA [COLOUR_GUARD]]]\n");
    return 2;
  }
  const int w = std::atoi(argv[3]), h = std::atoi(argv[4]);
  if (w < 64 || h < 64 || w > 20000 || h > 20000) return 2;
  std::vector<uint16_t> rgba(size_t(w) * h * 4);
  FILE* f = std::fopen(argv[1], "rb");
  if (!f) return 1;
  const size_t count = std::fread(rgba.data(), 2, rgba.size(), f);
  std::fclose(f);
  if (count != rgba.size() || std::strlen(argv[5]) != 4) return 1;
  int cfa[4];
  for (int i=0; i<4; ++i) cfa[i] = argv[5][i] - '0';
  const std::array<float,3> wb{float(std::atof(argv[6])),float(std::atof(argv[7])),float(std::atof(argv[8]))};
  if (!lkr::DenoisePhoto(rgba.data(), w, h, cfa, wb, float(std::atof(argv[9])), argv[10], argv[11], "", "", argc >= 13 ? std::atoi(argv[12]) : 4, argc >= 14 ? float(std::atof(argv[13])) : 0.f, argc == 15 ? std::atoi(argv[14]) != 0 : true)) return 1;
  f = std::fopen(argv[2], "wb");
  if (!f) return 1;
  const bool ok = std::fwrite(rgba.data(), 2, rgba.size(), f) == rgba.size();
  return std::fclose(f) == 0 && ok ? 0 : 1;
}
