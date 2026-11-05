// SPDX-License-Identifier: GPL-3.0-or-later
#include "lkr_denoise.h"
#include "lkr_prior.h"
#include <cstdio>
#include <random>
#include <limits>

#define CHECK(x) do { if (!(x)) { std::fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); return 1; } } while(0)
int main(int argc, char** argv) {
  const int w=674, h=530;
  std::vector<uint16_t> source(size_t(w)*h*4);
  std::vector<float> clean(size_t(w)*h*3);
  std::mt19937 rng(1234);
  std::normal_distribution<float> gaussian(0,1);
  constexpr float a=.001f,b=.00001f;
  for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
    for(int c=0;c<3;++c) {
      const float s=.1f+.35f*x/w+.04f*c+.04f*std::sin(y*.04f);
      clean[(size_t(y)*w+x)*3+c]=s;
      source[(size_t(y)*w+x)*4+c]=lkr::WriteHalf(s+gaussian(rng)*std::sqrt(a*s+b));
    }
    source[(size_t(y)*w+x)*4+3]=lkr::WriteHalf(.75f);
  }
  // Odd dimensions and partial cores, including exact intermediate-strength blending.
  for (float strength : {0.f,.35f,1.f}) {
    std::vector<uint16_t> odd(7*9*4,lkr::WriteHalf(.25f));
    for (size_t i=0;i<7*9;++i) odd[4*i+3]=lkr::WriteHalf(.7f);
    CHECK(lkr::DenoiseRgbaHalf(odd.data(),7,9,strength,
      [](const float*,float* out,int hh,int ww) {
        std::fill(out,out+size_t(hh)*ww*3,.125f);return true;
      },128));
    for (size_t i=0;i<7*9;++i) {
      for (int c=0;c<3;++c) CHECK(odd[4*i+c]==lkr::WriteHalf(.25f*(1-strength)+.125f*strength));
      CHECK(odd[4*i+3]==lkr::WriteHalf(.7f));
    }
  }
  auto copy=source;
  CHECK(lkr::DenoiseRgbaHalf(copy.data(),w,h,1,[](const float* in,float* out,int th,int tw){std::copy(in,in+size_t(th)*tw*3,out);return true;}));
  CHECK(copy==source);
  int calls=0;
  CHECK(!lkr::DenoiseRgbaHalf(copy.data(),w,h,1,[&](const float* in,float* out,int th,int tw){std::copy(in,in+size_t(th)*tw*3,out);return ++calls<2;}));
  CHECK(copy==source);
  CHECK(lkr::DenoiseRgbaHalf(copy.data(),w,h,0,[](const float*,float*,int,int){return false;}));
  CHECK(copy==source);
  CHECK(!lkr::DenoiseRgbaHalf(copy.data(),w,h,1,[](const float*,float* out,int th,int tw){std::fill(out,out+size_t(th)*tw*3,std::numeric_limits<float>::quiet_NaN());return true;}));
  CHECK(copy==source);
  for (int stride : {2,4}) {
    copy=source;
    const std::array<float,3> wb{2,1,1.8f};
    auto identity=[](const float* in,float* out,int th,int tw){std::copy(in,in+size_t(th)*tw*3,out);return true;};
    CHECK(lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,identity,stride));
    CHECK(copy==source);
    CHECK(lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,identity,stride,128));
    CHECK(copy==source);
    calls=0;
    CHECK(!lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,
      [&](const float* in,float* out,int th,int tw){std::copy(in,in+size_t(th)*tw*3,out);return ++calls<2;},stride));
    CHECK(copy==source);
    auto color=[](const float*,float* out,int th,int tw){
      for(int c=0;c<3;++c)std::fill(out+size_t(c)*th*tw,out+size_t(c+1)*th*tw,.1f*(c+1));
      return true;
    };
    CHECK(lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,color,stride));
    constexpr float weight[3]={.2126f,.7152f,.0722f};
    for(size_t i=0;i<size_t(w)*h;++i) {
      float before_y=0,after_y=0;
      for(int c=0;c<3;++c) {
        before_y+=weight[c]*wb[c]*lkr::ReadHalf(source[4*i+c]);
        after_y+=weight[c]*wb[c]*lkr::ReadHalf(copy[4*i+c]);
      }
      CHECK(std::abs(before_y-after_y)<.0006f);
      // Saturated opponent differences survive the transfer; no scalar desaturation.
      CHECK(std::abs((wb[2]*lkr::ReadHalf(copy[4*i+2])-wb[1]*lkr::ReadHalf(copy[4*i+1]))-(wb[2]*.3f-wb[1]*.2f))<.001f);
      CHECK(copy[4*i+3]==source[4*i+3]);
    }
    for (float amount : {.5f,1.f}) {
      copy=source;
      CHECK(lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,color,stride,512,amount));
      const float phase_y=.2126f*wb[0]*.1f+.7152f*wb[1]*.2f+.0722f*wb[2]*.3f;
      for(size_t i=0;i<size_t(w)*h;++i) {
        float before_y=0,after_y=0;
        for(int c=0;c<3;++c) {
          before_y+=weight[c]*wb[c]*lkr::ReadHalf(source[4*i+c]);
          after_y+=weight[c]*wb[c]*lkr::ReadHalf(copy[4*i+c]);
        }
        CHECK(std::abs(after_y-((1-amount)*before_y+amount*phase_y))<.0006f);
        CHECK(std::abs((wb[2]*lkr::ReadHalf(copy[4*i+2])-wb[1]*lkr::ReadHalf(copy[4*i+1]))-(wb[2]*.3f-wb[1]*.2f))<.001f);
        CHECK(copy[4*i+3]==source[4*i+3]);
      }
    }
    copy=source;
    CHECK(!lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,identity,stride,512,-1));
    CHECK(copy==source);
    CHECK(!lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,identity,stride,512,NAN));
    CHECK(copy==source);
    copy=source;
    CHECK(!lkr::DenoiseChromaRgbaHalf(source.data(),copy.data(),w,h,wb,
      [](const float*,float* out,int th,int tw){std::fill(out,out+size_t(th)*tw*3,INFINITY);return true;},stride));
    CHECK(copy==source);
  }
  std::puts("PASS: chroma strides 2/4 preserve luminance, colour differences, experimental luma transfer, alpha, phase/edge coverage, and transactional failure");
  // Exercise the actual GAT adapter with a failing backend (not only tile mocks).
  lkr::FfdnetGatPrior invalid_prior;
  invalid_prior.setNoise(.001f,.00001f,1,{1,1,1});
  std::vector<float> pin(3*8*8,.2f), pout(pin.size(),123.f);
  for (float bad : {INFINITY,-INFINITY,NAN}) {
    invalid_prior.setCore([bad](const float*,float* out,int hh,int ww) {
      std::fill(out,out+size_t(hh)*ww*12,bad);return true;
    },4,4,"test");
    CHECK(!invalid_prior(pin.data(),pout.data(),8,8,1));
    CHECK(std::all_of(pout.begin(),pout.end(),[](float v){return v==123.f;}));
  }
  CHECK(!invalid_prior(nullptr,pout.data(),8,8,1));
  CHECK(!invalid_prior(pin.data(),pout.data(),0,8,1));
  invalid_prior.setNoise(.001f,.00001f,0,{1,1,1});
  CHECK(!invalid_prior(pin.data(),pout.data(),8,8,1));
  copy=source;
  if(argc!=3) return 2;
  lkr::FfdnetGatPrior prior;
  lkr::FfdnetGatPrior::Options options;options.vulkan=false;
  CHECK(prior.load(argv[1],argv[2],options));
  prior.setNoise(a,b,1,{1,1,1});
  float lo=INFINITY,hi=-INFINITY;
  for(size_t i=0;i<size_t(w)*h;++i) for(int c=0;c<3;++c) {
    float v=2/a*std::sqrt(a*std::max(lkr::ReadHalf(source[4*i+c]),0.f)+.375f*a*a+b);
    lo=std::min(lo,v);hi=std::max(hi,v);
  }
  prior.setNormalizationRange(lo,hi);
  const int pw=w+64, ph=h+64;const size_t pn=size_t(pw)*ph;
  std::vector<float> in(3*pn),out(3*pn);
  for(int y=0;y<ph;++y) for(int x=0;x<pw;++x) for(int c=0;c<3;++c)
    in[c*pn+size_t(y)*pw+x]=lkr::ReadHalf(source[(size_t(std::clamp(y-32,0,h-1))*w+std::clamp(x-32,0,w-1))*4+c]);
  CHECK(prior(in.data(),out.data(),ph,pw,1));
  CHECK(lkr::DenoiseRgbaHalf(copy.data(),w,h,1,[&](const float* z,float* x,int th,int tw){return prior(z,x,th,tw,1);}));
  double before=0,after=0;float max_delta=0;
  for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
    CHECK(copy[(size_t(y)*w+x)*4+3]==source[(size_t(y)*w+x)*4+3]);
    for(int c=0;c<3;++c) {
      size_t i=size_t(y)*w+x;
      const float s=clean[i*3+c], v=lkr::ReadHalf(copy[i*4+c]);
      const float e=lkr::ReadHalf(source[i*4+c])-s;
      before+=e*e;after+=(v-s)*(v-s);
      max_delta=std::max(max_delta,std::abs(v-lkr::ReadHalf(lkr::WriteHalf(out[c*pn+size_t(y+32)*pw+x+32]))));
    }
  }
  CHECK(max_delta<=.001f);
  CHECK(after<before*.5);
  std::printf("PASS: identity, zero strength, transactional failure, invalid output, alpha, odd edge tiles; tiled/full max error %.8f; synthetic MSE %.8g -> %.8g\n",max_delta,before/clean.size(),after/clean.size());
}
