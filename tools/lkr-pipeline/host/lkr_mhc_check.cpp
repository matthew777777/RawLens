// SPDX-License-Identifier: GPL-3.0-or-later
// C++ side of the MHC parity check against app MhcReference.demosaicCoherent.
//   lkr_mhc_check IN.u16 META.txt OUT.f32
// META: sensorW sensorH pitch left top width height ch0 ch1 ch2 ch3 lv0 lv1 lv2 lv3 white
#include <cstdio>
#include <vector>
#include "lkr_mhc.h"
int main(int argc, char** argv) {
  if (argc < 4) return 2;
  int sw, sh, pitch, l, t, w, h, ch[4];
  float lv[4], white;
  FILE* m = std::fopen(argv[2], "r");
  if (!m || std::fscanf(m, "%d %d %d %d %d %d %d %d %d %d %d %f %f %f %f %f", &sw, &sh, &pitch, &l, &t, &w, &h, &ch[0],
                        &ch[1], &ch[2], &ch[3], &lv[0], &lv[1], &lv[2], &lv[3], &white) != 16) return 1;
  std::fclose(m);
  std::vector<uint16_t> codes(size_t(pitch) * sh);
  FILE* f = std::fopen(argv[1], "rb");
  if (!f || std::fread(codes.data(), 2, codes.size(), f) != codes.size()) return 1;
  std::fclose(f);
  std::vector<float> out(size_t(w) * h * 3);
  lkr::MhcCoherentCodes(codes.data(), sw, sh, pitch, l, t, w, h, ch, lv, white, out.data());
  FILE* o = std::fopen(argv[3], "wb");
  std::fwrite(out.data(), 4, out.size(), o);
  std::fclose(o);
  return 0;
}
