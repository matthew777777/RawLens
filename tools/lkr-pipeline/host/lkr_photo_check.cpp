// SPDX-License-Identifier: GPL-3.0-or-later
#include "lkr_photo_denoise.h"
#include "lkr_denoise.h"
#include "lkr_host.h"
#include <cstdio>
#include <random>
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); return 1; } } while(0)
int main() {
  constexpr int w=512,h=512;
  std::vector<float> raw(w*h,.2f);
  float a,b;
  lkr::NoiseAB(raw.data(),h,w,&a,&b);
  CHECK(std::isfinite(a)&&std::isfinite(b)&&a>=0&&b>=0);
  CHECK(a*.2f+b<1e-8f);
  std::mt19937 rng(913); std::normal_distribution<float> normal(0,1);
  for(int y=0;y<h;++y)for(int x=0;x<w;++x) {
    const float signal=.05f+.7f*(x/32)/15;
    raw[y*w+x]=signal+normal(rng)*std::sqrt(.001f*signal+.00001f);
  }
  lkr::NoiseAB(raw.data(),h,w,&a,&b);
  CHECK(std::isfinite(a)&&std::isfinite(b)&&a>=0&&b>=0);
  for(float level:{.1f,.4f,.7f}) {
    const float ratio=(a*level+b)/(.001f*level+.00001f);
    CHECK(ratio>.7f && ratio<1.3f);
  }
  std::vector<uint16_t> rgba(64*64*4,lkr::WriteHalf(.2f));const auto original=rgba;
  const int valid[4]={0,1,1,2},invalid[4]={0,2,1,1};
  CHECK(lkr::DenoisePhoto(rgba.data(),64,64,valid,{2,1,1.8f},0,"missing","missing"));
  CHECK(!lkr::DenoisePhoto(rgba.data(),64,64,invalid,{2,1,1.8f},1,"missing","missing"));
  CHECK(!lkr::DenoisePhoto(rgba.data(),64,64,valid,{2,0,1.8f},1,"missing","missing"));
  CHECK(!lkr::DenoisePhoto(rgba.data(),64,64,valid,{2,1,1.8f},NAN,"missing","missing"));
  CHECK(!lkr::DenoisePhoto(rgba.data(),64,64,valid,{2,1,1.8f},1,"missing","missing"));
  CHECK(rgba==original);
  std::printf("PASS: constant/noisy CFA noise fits (a=%g b=%g), invalid settings, missing model, source rollback\n",a,b);
}
