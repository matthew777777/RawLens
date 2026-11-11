// SPDX-License-Identifier: Apache-2.0
// Run on a Vulkan desktop: temporal rejection and independent shadow controls.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>
#include "lkr_solver.h"
#include "lkr_spv.h"

struct Push {
  int32_t h, w, s, K, H, W, kOff, mode;
  float eta, mu, scale, invScale, f0, f1, f2, f3, rob_a, rob_b, rob_k, rob_on;
};
int main() {
  lkr::Gpu gpu;
  if (!gpu.ok()) return 2;
  constexpr int W=128, H=128, K=2, N=W*H;
  auto r=gpu.alloc(K*N*4), y=gpu.alloc(K*N*4), tmp=gpu.alloc(K*N*16);
  auto wt=gpu.alloc(K*N*4), unused=gpu.alloc(K*N*4);
  auto rows=gpu.pipeline(lkr::spv::weight_th,sizeof(lkr::spv::weight_th),4,sizeof(Push));
  auto cols=gpu.pipeline(lkr::spv::weight_t,sizeof(lkr::spv::weight_t),6,sizeof(Push));
  auto rs=gpu.set(rows,{&r,&y,&tmp,&unused});
  auto cs=gpu.set(cols,{&r,&y,&tmp,&wt,&unused,&unused});
  Push p{}; p.h=H;p.w=W;p.H=H;p.W=W;p.s=1;p.K=K;p.rob_b=0.00005f;p.rob_k=3;
  std::fill(y.as<float>(),y.as<float>()+K*N,0.05f);
  std::mt19937 rng(42);std::normal_distribution<float> noise(0,0.01f);
  bool ok=true;
  // 3-6: model-error tolerance. Static period-4 detail with interpolation error
  // correlated with it; an object that left (flat prediction); a moved texture
  // (anti-phase prediction, detail in both).
  const char* names[]={"static noise","alternating motion","coherent motion","static detail, no tolerance","static detail + tolerance","object left + tolerance","moved texture + tolerance"};
  for(int test=0;test<7;++test) {
    p.f0=test>=4?0.5f:0.0f;
    for(int k=0;k<K;++k) for(int i=0;i<H;++i) for(int j=0;j<W;++j) {
      const float dev=(j%4<2)?0.05f:-0.05f;
      y.as<float>()[k*N+i*W+j]=test>=3?0.1f+dev:0.05f;
      if(test>=3){ r.as<float>()[k*N+i*W+j]=(test<=4?0.5f:(test==5?-1.0f:-2.0f))*dev; continue; }
      // Zero-mean displaced thin lines defeat a signed-mean-only detector.
      float v=test==0?noise(rng):(test==1?(j%2?0.04f:-0.04f):0.04f);
      r.as<float>()[k*N+i*W+j]=v;
    }
    gpu.begin();gpu.dispatch(rows,rs,&p,W/16,H/16,K);gpu.dispatch(cols,cs,&p,W/16,H/16,K);
    if(!gpu.submit()) return 2;
    double mean=0,ref=0;int n=0;
    for(int i=4;i<H-4;++i) for(int j=4;j<W-4;++j) {
      mean+=wt.as<float>()[N+i*W+j];ref+=wt.as<float>()[i*W+j];++n;
    }
    mean/=n;ref/=n;
    std::printf("temporal %s: mean=%g reference=%g\n",names[test],mean,ref);
    ok &= ref==1 && ((test==0||test==4)?mean>0.99:mean<0.01);
  }
  gpu.resetSets();gpu.destroy(rows);gpu.destroy(cols);
  for(auto* b:{&r,&y,&tmp,&wt,&unused})gpu.free(*b);
  {
    lkr::Solver solver(&gpu);
    std::vector<float> frames(2*32*32,0.01f),out;
    lkr::SolveParams s;s.s=1;s.T=2;s.z_steps=1;s.refine_motion=false;s.local_block=0;
    s.robust=false;s.equalize_noise=false;s.notch=false;s.grain=0;s.highlight_desat=false;
    s.debug_maps=true;s.noise_a=0.0001f;s.noise_b=0.000001f;s.shadow_boost=0;s.mean_preserve=0;
    for(float chroma:{0.0f,0.7f}) {
      float motion[12]={};s.shadow_chroma=chroma;lkr::SolveStats stats;bool unboosted=true;
      auto prior=[&](const float* z,float* x,int h,int w,float,const float* ns){
        if(ns)for(int i=0;i<h*w;++i)unboosted &= std::abs(ns[i]-1)<1e-6f;
        std::copy(z,z+3*h*w,x);return true;
      };
      if(!solver.solve(frames.data(),2,32,32,motion,s,prior,&out,&stats))return 2;
      float shadow=stats.dbg[3*32*32+16*32+16];
      std::printf("shadow chroma=%g luma_boost=0: mask=%g prior_unboosted=%d\n",chroma,shadow,unboosted);
      ok &= unboosted && (chroma==0?shadow==0:shadow>0.5f);
    }
    // The chroma finish must reduce colour variance without changing luma.
    constexpr int n=32*32;std::vector<float> x(3*n),z,mask(n,1);
    for(int i=0;i<n;++i){float d=i%2?0.003f:-0.003f;x[i]=0.02f+d;x[n+i]=0.02f;x[2*n+i]=0.02f-d;}
    z=x;lkr::FinishLook(z.data(),x.data(),32,32,s,nullptr,mask.data());
    double before=0,after=0;float err=0;
    for(int i=0;i<n;++i){before+=std::pow(z[i]-z[2*n+i],2);after+=std::pow(x[i]-x[2*n+i],2);err=std::max(err,std::abs(0.25f*x[i]+0.5f*x[n+i]+0.25f*x[2*n+i]-0.02f));}
    std::printf("chroma variance ratio=%g max luma change=%g\n",after/before,err);
    ok &= after<0.25*before && err<1e-7f;
  }
  {
    // A red-clipped sunset retains valid green/blue measurements. A fully
    // clipped neighbour must not expand a desaturation mask into it.
    constexpr int n=16*16;std::vector<float> x(3*n),z;
    for(int i=0;i<n;++i){x[i]=1.0f;x[n+i]=.65f;x[2*n+i]=.15f;}
    const int white=8*16+8;
    for(int c=0;c<3;++c)x[c*n+white]=1.0f;
    z=x;lkr::SolveParams p;p.wb={2,1,1.5f};p.notch=false;p.grain=0;p.highlight_desat=true;
    lkr::FinishLook(z.data(),x.data(),16,16,p,nullptr,nullptr);
    for(int i=0;i<n;++i)if(i!=white)for(int c=0;c<3;++c)ok &= x[c*n+i]==z[c*n+i];
    ok &= std::abs(x[white]*2-x[n+white])<1e-6f;
    ok &= std::abs(x[2*n+white]*1.5f-x[n+white])<1e-6f;
    std::puts("highlight: one-channel-clipped colour preserved, no neighbour spill");
  }
  std::puts(ok?"PASS":"FAIL");return ok?0:1;
}
