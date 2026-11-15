// SPDX-License-Identifier: Apache-2.0
#include "lkrl_api.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <cstring>

static const uint32_t hybrid[] = {
#include "hyb.inc"
};

// A noiseless green leaf against grey must retain its colour at both sides of
// the boundary. The old unconditional binomial blur fails this test.
static bool colorEdge(lkrl::Gpu& gpu) {
  constexpr int H=32, W=32, n=H*W;
  auto z=gpu.alloc(3*n*4), avg=gpu.alloc(3*n*4), d=gpu.alloc(4*n*4), out=gpu.alloc(3*n*4,true);
  for(int y=0;y<H;++y) for(int x=0;x<W;++x) for(int c=0;c<3;++c)
    z.as<float>()[c*n+y*W+x]= x<W/2 ? (c==1 ? .16f : .06f) : .10f;
  std::memcpy(avg.map,z.map,3*n*4);
  auto pipe=gpu.pipeline(hybrid,sizeof(hybrid),4,48);
  if(!pipe.pipe) return false;
  auto set=gpu.set(pipe,{&z,&avg,&d,&out});
  struct Push {int H,W,K,mode;float a,b,t,clip,wr,wg,wb,r;};
  for(int r : {1,2}) {
    Push p{H,W,16,0,0,1e-8f,0,.98f,1,1,1,float(r)};
    gpu.begin();gpu.dispatch(pipe,set,&p,2,2);p.mode=1;gpu.dispatch(pipe,set,&p,2,2);
    if(!gpu.submit()) return false;
    double error=0;
    for(int i=0;i<3*n;++i) error=std::max(error,std::abs(double(out.as<float>()[i]-z.as<float>()[i])));
    std::printf("chroma radius %d edge max error %.9g\n",r,error);
    if(error>1e-6) return false;
  }
  gpu.resetSets();gpu.destroy(pipe);gpu.free(z);gpu.free(avg);gpu.free(d);gpu.free(out);
  return true;
}

// Exercise the shared CLI/JNI adapter, including its implicit lost-frame pass.
int main() {
  lkrl::Gpu gpu;
  if (!gpu.ok()) return 2;
  if (!colorEdge(gpu)) return 1;
  lkrl::Solver solver(&gpu);
  constexpr int h = 64, w = 64, K = 4;
  std::mt19937 rng(71);
  std::normal_distribution<float> noise(0, 0.006f);
  std::vector<float> frames(K*h*w);
  for (int k=0; k<K; ++k) for (int y=0; y<h; ++y) for (int x=0; x<w; ++x) {
    const bool leaf = x > 24 + 5*k && x < 40 + 5*k;
    const int c = (y%2 == 0) ? (x%2 == 0 ? 0 : 1) : (x%2 == 0 ? 1 : 2);
    frames[(k*h+y)*w+x] = (leaf ? (c==1 ? .16f : .06f) : .10f) + noise(rng);
  }
  for (int scale : {1,2}) {
    std::vector<float> out[3];
    for (int mode=0; mode<3; ++mode) {
      lkrl::SolveParams p;
      p.s=scale; p.noise_a=0; p.noise_b=.000036f;
      p.sigma_noise=.006f; p.sigma_end=.006f; p.prior_gain=1;
      p.kernelnet_denoise=0; p.notch=false; p.highlight_desat=false; p.grain=0;
      p.dct_fallback=mode==1; p.dct_prior=mode==2;
      std::vector<float> motion(K*6,0);
      for (int k=0;k<K;++k) {motion[k*6]=1;motion[k*6+4]=1;}
      lkrl::SolveStats st;
      if (!solver.solve(frames.data(),K,h,w,motion.data(),p,{},&out[mode],&st)) return 1;
      if (mode>0 && st.prior_calls==0) return 1;
    }
    const int n=scale*scale*h*w;
    double maxLuma=0, priorLuma=0, chroma=0;
    for(int i=0;i<n;++i) {
      auto luma=[&](int m){return .25*out[m][i]+.5*out[m][n+i]+.25*out[m][2*n+i];};
      maxLuma=std::max(maxLuma,std::abs(luma(1)-luma(0)));
      priorLuma+=std::abs(luma(2)-luma(0));
      chroma+=std::abs(double(out[1][i]-out[0][i]));
      for(int m=0;m<3;++m) for(int c=0;c<3;++c) if(!std::isfinite(out[m][c*n+i])) return 1;
    }
    std::printf("%dx fallback luma max %.9g, colour MAE %.9g, enabled prior luma MAE %.9g\n",scale,maxLuma,chroma/n,priorLuma/n);
    if(maxLuma>2e-6 || chroma/n<1e-7 || priorLuma/n<1e-7) return 1;
  }
}
