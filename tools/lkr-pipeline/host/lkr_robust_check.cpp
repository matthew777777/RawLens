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
  auto rows=gpu.pipeline(lkr::spv::weight_th,sizeof(lkr::spv::weight_th),5,sizeof(Push));
  auto cols=gpu.pipeline(lkr::spv::weight_t,sizeof(lkr::spv::weight_t),6,sizeof(Push));
  auto rs=gpu.set(rows,{&r,&y,&tmp,&unused,&unused});
  auto cs=gpu.set(cols,{&r,&y,&tmp,&wt,&unused,&unused});
  Push p{}; p.h=H;p.w=W;p.H=H;p.W=W;p.s=1;p.K=K;p.rob_b=0.00005f;p.rob_k=3;
  p.f1=-1; // exercise the shader's inline detail reference, not an uninitialized TDET buffer
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
    // Independent guide: exact static RGB agreement, large colour change,
    // and a spatially bounded oracle override. No evolving HR image is bound.
    auto raw=gpu.alloc(K*N*4), means=gpu.alloc(K*N*4), vars=gpu.alloc(K*N*4);
    auto motion=gpu.alloc(K*6*4), local=gpu.alloc(16), weights=gpu.alloc(K*N*4), temporal=gpu.alloc(K*N*4);
    auto stats=gpu.pipeline(lkr::spv::guide_stats,sizeof(lkr::spv::guide_stats),3,sizeof(Push));
    auto guide=gpu.pipeline(lkr::spv::weight_guide,sizeof(lkr::spv::weight_guide),6,sizeof(Push));
    auto ss=gpu.set(stats,{&raw,&means,&vars});
    auto gs=gpu.set(guide,{&means,&vars,&motion,&weights,&local,&temporal});
    std::fill(motion.as<float>(),motion.as<float>()+K*6,0);
    for(int k=0;k<K;++k){motion.as<float>()[k*6]=1;motion.as<float>()[k*6+4]=1;}
    Push q{};q.h=H;q.w=W;q.K=K;q.rob_a=1e-5f;q.rob_b=1e-6f;q.f0=2;
    for(int changed=0;changed<2;++changed){
      std::fill(raw.as<float>(),raw.as<float>()+N,.1f);
      std::fill(raw.as<float>()+N,raw.as<float>()+K*N,changed?.4f:.1f);
      std::fill(temporal.as<float>(),temporal.as<float>()+K*N,.2f);
      gpu.begin();gpu.dispatch(stats,ss,&q,W/32,H/32,K);gpu.dispatch(guide,gs,&q,W/32,H/32,K);
      if(!gpu.submit())return 2;
      float w=weights.as<float>()[N+32*W+32];
      ok &= weights.as<float>()[32*W+32]==1 && (changed?w==0:w==1);
      ok &= temporal.as<float>()[N+32*W+32]==.2f;
      std::printf("RGB guide changed=%d weight=%g reference=1\n",changed,w);
    }
    q.f0=-1;q.f1=16;q.f2=16;q.f3=32;q.rob_k=32;
    gpu.begin();gpu.dispatch(guide,gs,&q,W/32,H/32,K);if(!gpu.submit())return 2;
    ok &= weights.as<float>()[N+32*W+32]==1 && temporal.as<float>()[N+32*W+32]==1;
    ok &= weights.as<float>()[N+64*W+64]==0 && temporal.as<float>()[N+64*W+64]==.2f;
    std::puts("known-static override: bounded in reference coordinates");
    gpu.resetSets();gpu.destroy(stats);gpu.destroy(guide);
    for(auto* b:{&raw,&means,&vars,&motion,&local,&weights,&temporal})gpu.free(*b);
  }
  {
    // An all-static oracle must agree with rejection disabled over multiple
    // gradient steps. This catches stale weighted residual reuse in new paths.
    constexpr int n=64*64;
    std::vector<float> frames(2*n),plain,trusted;
    for(int k=0;k<2;++k)for(int y=0;y<64;++y)for(int x=0;x<64;++x)
      frames[k*n+y*64+x]=.2f+.05f*std::sin(float(x)*.6f)+.03f*std::cos(float(y)*.5f)+(k?-.003f:.003f);
    lkr::SolveParams s;s.s=2;s.T=6;s.z_steps=3;s.refine_motion=false;s.local_block=0;
    s.robust=false;s.equalize_noise=false;s.temporal_k=0;s.notch=false;s.grain=0;
    s.highlight_desat=false;s.shadow_boost=0;s.shadow_chroma=0;s.mean_preserve=0;
    s.noise_a=1e-5f;s.noise_b=1e-6f;
    lkr::Solver solver(&gpu);
    float p0[12]={1,0,0,0,1,0,1,0,0,0,1,0};
    float p1[12]={1,0,0,0,1,0,1,0,0,0,1,0};
    if(!solver.solve(frames.data(),2,64,64,p0,s,{},&plain,nullptr))return 2;
    s.robust=true;s.trusted_static={0,0,64,64};
    if(!solver.solve(frames.data(),2,64,64,p1,s,{},&trusted,nullptr))return 2;
    float error=0;
    for(size_t i=0;i<plain.size();++i)error=std::max(error,std::abs(plain[i]-trusted[i]));
    std::printf("multi-step all-static oracle vs rejection off: max=%g\n",error);
    ok &= error<1e-5f;
    std::vector<float> zero_error(128*128,0),zero_out;
    s.robust=false;s.trusted_static={0,0,0,0};s.dct_prior=true;s.dct_aperture=1;
    s.model_error=zero_error.data();
    float p2[12]={1,0,0,0,1,0,1,0,0,0,1,0};
    if(!solver.solve(frames.data(),2,64,64,p2,s,{},&zero_out,nullptr))return 2;
    std::printf("zero model-error prior: byte-identical=%d\n",plain==zero_out);
    ok &= plain==zero_out;
    // Band rejection is a guide-only research mode; production now defaults off.
    s.dct_prior=false;s.dct_aperture=0;s.band_rejection=true;s.guide_confidence=2;
    float p3[12]={1,0,0,0,1,0,1,0,0,0,1,0};
    if(!solver.solve(frames.data(),2,64,64,p3,s,{},&zero_out,nullptr))return 2;
    std::printf("zero band-error map: byte-identical=%d\n",plain==zero_out);ok &= plain==zero_out;
  }
  {
    // Frequency confidence must retain LL and the reference while rejecting
    // inconsistent same-CFA high bands. Exercise all four block phases.
    auto rr=gpu.alloc(K*N*4),ee=gpu.alloc(K*N*4),ww=gpu.alloc(K*N*4),out=gpu.alloc(K*N*4);
    auto motion=gpu.alloc(K*6*4),model=gpu.alloc(N*4),local=gpu.alloc(16);
    std::fill(ww.as<float>(),ww.as<float>()+K*N,1.f);
    std::fill(model.as<float>(),model.as<float>()+N,10.f);
    std::fill(motion.as<float>(),motion.as<float>()+K*6,0.f);
    for(int k=0;k<K;++k){motion.as<float>()[k*6]=1;motion.as<float>()[k*6+4]=1;}
    auto band=gpu.pipeline(lkr::spv::weight_band,sizeof(lkr::spv::weight_band),7,sizeof(Push));
    auto set=gpu.set(band,{&rr,&ee,&ww,&out,&motion,&model,&local});
    Push q{};q.h=H;q.w=W;q.H=H;q.W=W;q.s=1;q.K=K;q.rob_k=1;
    for(int test=0;test<2;++test){
      for(int k=0;k<K;++k)for(int y=0;y<H;++y)for(int x=0;x<W;++x){
        int o=k*N+y*W+x;float v=test?(((x/2+y/2)&1)?-.04f:.04f):.02f;
        rr.as<float>()[o]=v;ee.as<float>()[o]=v*v/1e-6f;
      }
      gpu.begin();gpu.dispatch(band,set,&q,W/16,H/16,K);if(!gpu.submit())return 2;
      float reference=0;double ratio=0;int count=0;
      for(int y=8;y<H-8;++y)for(int x=8;x<W-8;++x){
        int o=y*W+x;reference=std::max(reference,std::abs(out.as<float>()[o]-rr.as<float>()[o]));
        ratio+=std::abs(out.as<float>()[N+o]/rr.as<float>()[N+o]);++count;
      }
      ratio/=count;std::printf("band confidence high=%d residual ratio=%g reference_error=%g\n",test,ratio,reference);
      ok &= reference==0 && (test?ratio<.01:std::abs(ratio-1)<1e-6);
    }
    gpu.resetSets();gpu.destroy(band);
    for(auto* b:{&rr,&ee,&ww,&out,&motion,&model,&local})gpu.free(*b);
  }
  {
    // Both DCT kernels must preserve a constant color, including when the
    // same solver switches back from the experimental aperture pipeline.
    lkr::Solver solver(&gpu);std::vector<float> frames(2*32*32),out;
    for(int k=0;k<2;++k)for(int y=0;y<32;++y)for(int x=0;x<32;++x)
      frames[k*32*32+y*32+x]=(y%2==0&&x%2==0)?.12f:((y%2&&x%2)?.08f:.1f);
    lkr::SolveParams s;s.s=2;s.T=4;s.z_steps=2;s.refine_motion=false;s.local_block=0;
    s.robust=false;s.equalize_noise=false;s.notch=false;s.grain=0;s.highlight_desat=false;
    s.shadow_boost=0;s.shadow_chroma=0;s.mean_preserve=0;s.dct_prior=true;
    s.noise_a=1e-5f;s.noise_b=1e-6f;s.wb={1.7f,1.f,1.3f};
    std::vector<float> first;
    for(float aperture:{0.f,1.f,0.f}){
      s.dct_aperture=aperture;float motion[12]={1,0,0,0,1,0,1,0,0,0,1,0};
      if(!solver.solve(frames.data(),2,32,32,motion,s,{},&out,nullptr))return 2;
      float error=0;const float expected[3]={.12f,.1f,.08f};
      for(int c=0;c<3;++c)for(int y=8;y<56;++y)for(int x=8;x<56;++x)
        error=std::max(error,std::abs(out[c*64*64+y*64+x]-expected[c]));
      if(first.empty())first=out;
      else if(aperture==0)ok &= first==out;
      std::printf("DCT constant color aperture=%g max=%g\n",aperture,error);ok &= error<2e-5f;
    }
  }
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
