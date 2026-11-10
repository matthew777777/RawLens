// SPDX-License-Identifier: GPL-3.0-or-later
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
#include "lkr_host.h"
#include "lkr_prior.h"
#include "srp_dng.h"
#define CHECK(v) do { if(!(v)){std::fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#v);return 1;} } while(0)
int main(int argc,char** argv){
  constexpr int h=1024,w=1024;std::mt19937 rng(8127);std::normal_distribution<float> normal(0,1);
  for(float quant:{0.0f,1.0f/960}) {
    constexpr float a=.00013f,b=.0000003f;
    std::vector<float> raw(h*w);
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){
      // Piecewise-flat patches cover shadow and midtone intensities. Grid
      // boundaries contribute structure; the flat-block selection must cope.
      float mu=.0025f*std::pow(100.f,float(x/64)/15.f);
      float v=mu+normal(rng)*std::sqrt(a*mu+b);
      if(quant>0)v=std::round(v/quant)*quant;
      raw[y*w+x]=v;
    }
    float fit_a,fit_b;lkr::NoiseAB(raw.data(),h,w,&fit_a,&fit_b);
    float expected=a*.003f+b+quant*quant/12;
    float ratio=(fit_a*.003f+fit_b)/expected;
    std::printf("quantum=%g fitted a=%g b=%g shadow variance ratio=%g\n",quant,fit_a,fit_b,ratio);
    CHECK(ratio>.8f&&ratio<1.2f);CHECK(fit_b>1e-7f);
  }
  lkr::FfdnetGatPrior p;p.setNoise(.001f,.00001f,1,{2,1,1.8f});
  float negative=0,zero=0;
  p.setCore([&](const float* in,float* out,int hh,int ww){
    negative=in[0];zero=in[1];std::copy(in,in+12*hh*ww,out);return true;
  },4,4,"identity-test");
  std::vector<float> in(3*8*8,.01f),out(in.size());in[0]=-.001f;in[2]=0;
  CHECK(p(in.data(),out.data(),8,8,.5f));
  std::printf("signed GAT: negative=%g zero=%g\n",negative,zero);
  CHECK(negative<zero);CHECK(out[0]<out[2]-.0005f);
  if(argc>1){
    srp::SrpDngMeta m;CHECK(srp::SrpReadDngMeta(argv[1],&m)==srp::DngReadStatus::kOk);
    const double expected[4]={63.75,63,63,63.5};
    for(int c=0;c<4;++c){std::printf("black cell %d: %.8f\n",c,m.black_level_precise[c]);CHECK(std::abs(m.black_level_precise[c]-expected[c])<1e-8);}
  }
  std::puts("PASS");return 0;
}
