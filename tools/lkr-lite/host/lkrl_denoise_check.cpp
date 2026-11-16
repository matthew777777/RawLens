// SPDX-License-Identifier: Apache-2.0
#include "lkrl_api.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <cstring>

static const uint32_t hybrid[] = {
#include "hyb.inc"
};
static const uint32_t update[] = {
#include "zupd.inc"
};

// Eight identical 2x frames: each sensor sample averages one disjoint 2x2
// footprint of its Bayer colour. Exact geometry, no noise or rejection. A
// positive constant error on those footprints must shrink at every data step.
// The former smoothed divisor gives a step > 2 on red/blue footprints and
// increases this measured error, even though the observations are noiseless.
static bool repeatedFrameStability(lkrl::Gpu& gpu) {
  constexpr int H = 32, W = 32, n = H * W, K = 8;
  constexpr float scale = 33554432.0f;
  auto z = gpu.alloc(3 * n * 4, true), acc = gpu.alloc(3 * n * 4);
  auto cov = gpu.alloc(3 * n * 4), divisor = gpu.alloc(3 * n * 4);
  auto pipe = gpu.pipeline(update, sizeof(update), 4, 64);
  if (!pipe.pipe) return false;
  auto set = gpu.set(pipe, {&z, &acc, &cov, &divisor});
  struct Push {
    int h, w, s, K, H, W, LB, mode;
    float a, b, clip, robk, tau, omega, scale, dscale;
  };
  Push p{H / 2, W / 2, 2, K, H, W, 1, 1, 3, 1.8f, .98f, 0, 0, 1, scale, scale};
  std::fill_n(z.as<float>(), 3 * n, .21f);
  std::fill_n(cov.as<int>(), 3 * n, 0);
  auto residuals = [&](bool write) {
    double loss = 0;
    if (write) std::fill_n(acc.as<int>(), 3 * n, 0);
    for (int y = 0; y < H / 2; ++y) for (int x = 0; x < W / 2; ++x) {
      const int c = !(y & 1) && !(x & 1) ? 0 : ((y & 1) && (x & 1) ? 2 : 1);
      const int t = c * n + 2 * y * W + 2 * x;
      const float r = .25f * (z.as<float>()[t] + z.as<float>()[t + 1] +
                              z.as<float>()[t + W] + z.as<float>()[t + W + 1]) - .2f;
      loss += K * double(r) * r;
      if (write) for (int offset : {0, 1, W, W + 1}) {
        acc.as<int>()[t + offset] = int(std::round((K * .25f) * r * scale));
        cov.as<int>()[t + offset] = int((K * .25f) * scale);
      }
    }
    return loss;
  };
  bool ok = true;
  double initial = residuals(false), previous = initial;
  for (int step = 0; step < 8; ++step) {
    residuals(true);
    p.mode = step == 0 ? 1 : 0; // exercise both divisor creation and reuse
    gpu.begin();
    gpu.dispatch(pipe, set, &p, (3 * n + 255) / 256);
    if (!gpu.submit()) { ok = false; break; }
    const double loss = residuals(false);
    ok &= std::isfinite(loss) && loss <= previous * 1.0001 + 1e-12;
    previous = loss;
  }
  std::printf("identical-frame update loss %.9g -> %.9g (%s)\n", initial, previous, ok ? "stable" : "unstable");
  gpu.resetSets(); gpu.destroy(pipe); gpu.free(z); gpu.free(acc); gpu.free(cov); gpu.free(divisor);
  return ok;
}

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

// A brightness edge on a fixed-colour surface must not preserve a Bayer
// checker in fit chroma. Run the same edge clean and with alternating chroma;
// their difference isolates alias removal from the true edge response.
static bool brightnessEdge(lkrl::Gpu& gpu) {
  constexpr int H=32, W=32, n=H*W;
  auto z=gpu.alloc(3*n*4), avg=gpu.alloc(3*n*4), d=gpu.alloc(4*n*4), out=gpu.alloc(3*n*4,true);
  auto pipe=gpu.pipeline(hybrid,sizeof(hybrid),4,48);
  if(!pipe.pipe) return false;
  auto set=gpu.set(pipe,{&z,&avg,&d,&out});
  struct Push {int H,W,K,mode;float a,b,t,clip,wr,wg,wb,r;};
  bool ok=true;
  for(int radius : {1,2}) {
    std::vector<float> clean;
    for(int noisy=0;noisy<2;++noisy) {
      for(int y=0;y<H;++y) for(int x=0;x<W;++x) {
        float level=x<16 ? .05f : .25f;
        float e=noisy ? (x%2 ? .01f : -.01f) : 0;
        for(int c=0;c<3;++c) {
          float v=level*(c==0 ? 1.4f : c==1 ? 1.f : .7f);
          avg.as<float>()[c*n+y*W+x]=v;
          z.as<float>()[c*n+y*W+x]=v+(c==1 ? -e : e);
        }
      }
      Push p{H,W,8,0,8.21e-6f,1.61e-6f,0,.98f,1,1,1,float(radius)};
      gpu.begin();gpu.dispatch(pipe,set,&p,2,2);p.mode=1;gpu.dispatch(pipe,set,&p,2,2);
      if(!gpu.submit()) return false;
      if(!noisy) clean.assign(out.as<float>(),out.as<float>()+3*n);
      else {
        double error=0;
        for(int y=4;y<H-4;++y) for(int x=14;x<=17;++x) for(int c=0;c<3;++c)
          error=std::max(error,std::abs(double(out.as<float>()[c*n+y*W+x]-clean[c*n+y*W+x])));
        std::printf("brightness edge radius %d checker residual %.9g\n",radius,error);
        ok &= error<1e-6;
      }
    }
  }
  gpu.resetSets();gpu.destroy(pipe);gpu.free(z);gpu.free(avg);gpu.free(d);gpu.free(out);
  return ok;
}

// Exercise the shared CLI/JNI adapter, including its implicit lost-frame pass.
int main() {
  lkrl::Gpu gpu;
  if (!gpu.ok()) return 2;
  if (!colorEdge(gpu) || !brightnessEdge(gpu) || !repeatedFrameStability(gpu)) return 1;
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
