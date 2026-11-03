// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from RawTherapee PF_correct_RT.cc: Emil Martinec (2008–2010),
// Ingo Weyrich (2013, 2018). Streaming implementation: RawLens contributors, 2026.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <stdexcept>
#include <vector>

namespace rawlens_fringe {
struct Stats { double chromaMean=0; size_t corrected=0, scratchBytes=0; };
struct Config { float radius=2.f; int threshold=13; bool purpleOnly=true; };
struct RunOptions { float sharedMean=-1.f; bool analyzeOnly=false; int firstColumn=0,lastColumn=-1; };

inline float hueFactor(float a,float b,bool purpleOnly) {
    // Restricted hue selection instead of RT's arbitrary editable hue curve.
    // CIELab magenta/purple: positive a and negative b. Flat colour is still
    // protected by the local residual test; this gate is not a semantic classifier.
    return !purpleOnly || (a>0.f && b<0.f) ? 1.f : 0.f;
}

// The reader fills two chroma rows; the writer updates a row's chroma only.
// The global-mean pass completes before writes. During the second pass each
// original row is read ahead and retained until every dependent output is done,
// allowing safe in-place use without a full original-image copy.
template<class Reader, class Writer>
Stats defringe(int w,int h,const Config& cfg,Reader read,Writer write,const RunOptions& run={}) {
    if(w<1 || h<1 || w>65536 || h>65536 || !std::isfinite(cfg.radius) || cfg.radius<.5f || cfg.radius>4.f || cfg.threshold<0 || cfg.threshold>100)
        throw std::invalid_argument("Invalid defringe size/settings");
    const int first=run.firstColumn,last=run.lastColumn<0?w:run.lastColumn;
    if(first<0||last>w||first>=last||!std::isfinite(run.sharedMean)||run.sharedMean < -1.f)
        throw std::invalid_argument("Invalid defringe run options");
    const int r=static_cast<int>(std::ceil(3*cfg.radius));
    const int m=static_cast<int>(std::ceil(2*cfg.radius));
    const int br=2*r+1, wr=2*m+1;
    std::vector<float> kernel(br);
    double ks=0;
    for(int k=-r;k<=r;++k) { kernel[k+r]=std::exp(-.5f*k*k/(cfg.radius*cfg.radius));ks+=kernel[k+r]; }
    for(auto& k:kernel) k/=ks;
    // Original A/B + horizontal Gaussian A/B, rolling over only 2r+1 rows.
    std::vector<float> blur(static_cast<size_t>(br)*w*4), ba(w),bb(w), residual(w);
    auto row=[&](int y,int plane) { return blur.data()+(static_cast<size_t>(y%br)*4+plane)*w; };
    auto residualRows=[&](auto emit) {
        int loaded=-1;
        for(int y=0;y<h;++y) {
            const int through=std::min(h-1,y+r);
            while(loaded<through) {
                int j=++loaded;
                float *a=row(j,0),*b=row(j,1),*ha=row(j,2),*hb=row(j,3);
                read(j,a,b);
                for(int x=0;x<w;++x) {
                    double aa=0,bc=0;
                    for(int k=-r;k<=r;++k) { int xx=std::clamp(x+k,0,w-1);aa+=kernel[k+r]*a[xx];bc+=kernel[k+r]*b[xx]; }
                    ha[x]=aa;hb[x]=bc;
                }
            }
            std::fill(ba.begin(),ba.end(),0);std::fill(bb.begin(),bb.end(),0);
            for(int k=-r;k<=r;++k) {
                int yy=std::clamp(y+k,0,h-1);const float* a=row(yy,2);const float* b=row(yy,3);
                for(int x=0;x<w;++x) { ba[x]+=kernel[k+r]*a[x];bb[x]+=kernel[k+r]*b[x]; }
            }
            const float *a=row(y,0),*b=row(y,1);
            for(int x=0;x<w;++x) residual[x]=hueFactor(a[x],b[x],cfg.purpleOnly)*((a[x]-ba[x])*(a[x]-ba[x])+(b[x]-bb[x])*(b[x]-bb[x]));
            emit(y,a,b,residual.data());
        }
    };
    Stats stats;
    double total=0;
    if(run.sharedMean<0) residualRows([&](int,const float*,const float*,const float* e) { for(int x=first;x<last;++x) total+=e[x]; });
    stats.chromaMean=run.sharedMean>=0?run.sharedMean:total/(static_cast<double>(last-first)*h);
    if(run.analyzeOnly || !(stats.chromaMean>0)) { stats.scratchBytes=(blur.size()+ba.size()+bb.size()+residual.size()+kernel.size())*sizeof(float);return stats; }
    const float mean=stats.chromaMean;
    const float threshold=1.f/((cfg.threshold/33.f)*(cfg.threshold/33.f)*mean*5.f+mean);
    // Per row: original A/B, inverse residual weight, horizontal weighted A/B/W.
    std::vector<float> weights(static_cast<size_t>(wr)*w*6), va(w),vb(w),vw(w),outa(w),outb(w);
    auto wrptr=[&](int y,int plane) { return weights.data()+(static_cast<size_t>(y%wr)*6+plane)*w; };
    auto output=[&](int y) {
        const float *a=wrptr(y,0),*b=wrptr(y,1),*wt=wrptr(y,2);
        for(int x=0;x<w;++x) {
            if(wt[x]<threshold && vw[x]>0) { outa[x]=va[x]/vw[x];outb[x]=vb[x]/vw[x];if(x>=first&&x<last)++stats.corrected; }
            else { outa[x]=a[x];outb[x]=b[x]; }
        }
        write(y,outa.data(),outb.data());
    };
    int emitted=-1;
    residualRows([&](int y,const float* a,const float* b,const float* e) {
        if(y>=wr) for(int x=0;x<w;++x) { va[x]-=wrptr(y-wr,3)[x];vb[x]-=wrptr(y-wr,4)[x];vw[x]-=wrptr(y-wr,5)[x]; }
        float *aa=wrptr(y,0),*bb=wrptr(y,1),*wt=wrptr(y,2),*ha=wrptr(y,3),*hb=wrptr(y,4),*hw=wrptr(y,5);
        for(int x=0;x<w;++x) { aa[x]=a[x];bb[x]=b[x];wt[x]=1.f/(e[x]+mean); }
        double sa=0,sb=0,sw=0;
        for(int x=0;x<std::min(w,m+1);++x) { sa+=wt[x]*aa[x];sb+=wt[x]*bb[x];sw+=wt[x]; }
        for(int x=0;x<w;++x) {
            ha[x]=sa;hb[x]=sb;hw[x]=sw;
            if(x-m>=0) { sa-=wt[x-m]*aa[x-m];sb-=wt[x-m]*bb[x-m];sw-=wt[x-m]; }
            if(x+m+1<w) { sa+=wt[x+m+1]*aa[x+m+1];sb+=wt[x+m+1]*bb[x+m+1];sw+=wt[x+m+1]; }
            va[x]+=ha[x];vb[x]+=hb[x];vw[x]+=hw[x];
        }
        if(y>=m) { emitted=y-m;output(emitted); }
    });
    // Bottom edge uses a truncated (not replicated) weighted window, like RT.
    for(int y=emitted+1;y<h;++y) {
        int old=y-m-1;
        if(old>=0) for(int x=0;x<w;++x) { va[x]-=wrptr(old,3)[x];vb[x]-=wrptr(old,4)[x];vw[x]-=wrptr(old,5)[x]; }
        output(y);
    }
    stats.scratchBytes=(blur.size()+ba.size()+bb.size()+residual.size()+kernel.size()+weights.size()+va.size()+vb.size()+vw.size()+outa.size()+outb.size())*sizeof(float);
    return stats;
}

inline Stats defringeLab(float* lab,int w,int h,const Config& c) {
    return defringe(w,h,c,[&](int y,float*a,float*b) {
        for(int x=0;x<w;++x) { a[x]=lab[(static_cast<size_t>(y)*w+x)*3+1];b[x]=lab[(static_cast<size_t>(y)*w+x)*3+2]; }
    },[&](int y,const float*a,const float*b) {
        for(int x=0;x<w;++x) { lab[(static_cast<size_t>(y)*w+x)*3+1]=a[x];lab[(static_cast<size_t>(y)*w+x)*3+2]=b[x]; }
    });
}
}
