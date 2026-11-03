// SPDX-License-Identifier: GPL-3.0-or-later
// CIELab D65 adapter for the RawTherapee-derived streaming defringe experiment.
#pragma once
#include "defringe.h"
#include <array>
#include <cstdint>
#include <thread>
#include <exception>
namespace rawlens_fringe {
struct Lab {float l,a,b;};
class SrgbLab {
    std::array<float,256> linear{};
    std::array<float,8193> ftable{},gamma{};
    static float labf(float t) {return t>216.f/24389.f ? std::cbrt(t) : (24389.f/27.f*t+16)/116;}
    static float invf(float t) {return t>6.f/29.f ? t*t*t : (116*t-16)*(27.f/24389.f);}
    float f(float t)const {
        float p=std::clamp(t,0.f,1.25f)*(8192/1.25f);int i=std::min(8191,static_cast<int>(p));
        return ftable[i]+(ftable[i+1]-ftable[i])*(p-i);
    }
    uint8_t encode(float v)const {
        float p=std::clamp(v,0.f,1.f)*8192;int i=std::min(8191,static_cast<int>(p));
        return static_cast<uint8_t>(std::clamp(std::lround(gamma[i]+(gamma[i+1]-gamma[i])*(p-i)),0L,255L));
    }
public:
    SrgbLab() {
        for(int i=0;i<256;++i) {float s=i/255.f;linear[i]=s<=.04045f ? s/12.92f : std::pow((s+.055f)/1.055f,2.4f);}
        for(int i=0;i<=8192;++i) {ftable[i]=labf(i*(1.25f/8192));float t=i/8192.f;gamma[i]=255*(t<=.0031308f ? 12.92f*t : 1.055f*std::pow(t,1/2.4f)-.055f);}
    }
    Lab decode(const uint8_t* rgba)const {
        const float r=linear[rgba[0]],g=linear[rgba[1]],b=linear[rgba[2]];
        float x=f((.4124564f*r+.3575761f*g+.1804375f*b)/.95047f);
        float y=f(.2126729f*r+.7151522f*g+.0721750f*b);
        float z=f((.0193339f*r+.1191920f*g+.9503041f*b)/1.08883f);
        return {116*y-16,500*(x-y),200*(y-z)};
    }
    void replaceChroma(uint8_t* rgba,const Lab& original,float a,float b)const {
        if(a==original.a && b==original.b)return; // Unselected pixels are bit-exact, including alpha.
        float y=(original.l+16)/116;
        float x=invf(y+a/500)*.95047f,z=invf(y-b/200)*1.08883f;y=invf(y);
        rgba[0]=encode(3.2404542f*x-1.5371385f*y-.4985314f*z);
        rgba[1]=encode(-.9692660f*x+1.8760108f*y+.0415560f*z);
        rgba[2]=encode(.0556434f*x-.2040259f*y+1.0572252f*z);
    }
};
inline Stats defringeRgba(uint8_t* rgba,int w,int h,size_t stride,const Config& config) {
    if(!rgba||w<1||h<1||stride<static_cast<size_t>(w)*4)throw std::invalid_argument("Invalid RGBA image");
    const SrgbLab color;
    auto stats=defringe(w,h,config,[&](int y,float*a,float*b){
        for(int x=0;x<w;++x) {auto lab=color.decode(rgba+static_cast<size_t>(y)*stride+x*4);a[x]=lab.a;b[x]=lab.b;}
    },[&](int y,const float*a,const float*b){
        for(int x=0;x<w;++x) {auto pixel=rgba+static_cast<size_t>(y)*stride+x*4;color.replaceChroma(pixel,color.decode(pixel),a[x],b[x]);}
    });
    stats.scratchBytes+=sizeof(color);return stats;
}
// Parallel vertical strips share the exact global residual mean. Immutable narrow
// halo snapshots prevent cross-strip read/write races during in-place correction.
inline Stats defringeRgbaParallel(uint8_t* rgba,int w,int h,size_t stride,const Config& config,int threadCount=4) {
    if(!rgba||w<1||h<1||stride<static_cast<size_t>(w)*4||!std::isfinite(config.radius)||config.radius<.5f||config.radius>4.f)
        throw std::invalid_argument("Invalid RGBA image/settings");
    const int count=std::clamp(threadCount,1,std::min(4,w));
    if(count==1)return defringeRgba(rgba,w,h,stride,config);
    const int halo=std::ceil(3*config.radius)+std::ceil(2*config.radius);
    const SrgbLab color;
    struct Strip {int first,last,left,right;std::vector<uint8_t> before,after;Stats stats;std::exception_ptr failure;};
    std::vector<Strip> strips(count);
    for(int n=0;n<count;++n) {
        auto& s=strips[n];s.first=w*n/count;s.last=w*(n+1)/count;s.left=std::max(0,s.first-halo);s.right=std::min(w,s.last+halo);
        s.before.resize(static_cast<size_t>(s.first-s.left)*h*4);s.after.resize(static_cast<size_t>(s.right-s.last)*h*4);
        for(int y=0;y<h;++y) {
            std::copy_n(rgba+static_cast<size_t>(y)*stride+s.left*4,(s.first-s.left)*4,s.before.data()+static_cast<size_t>(y)*(s.first-s.left)*4);
            std::copy_n(rgba+static_cast<size_t>(y)*stride+s.last*4,(s.right-s.last)*4,s.after.data()+static_cast<size_t>(y)*(s.right-s.last)*4);
        }
    }
    auto execute=[&](bool analyze,float mean) {
        std::vector<std::thread> workers;workers.reserve(count);
        auto join=[&] {for(auto& t:workers)if(t.joinable())t.join();};
        try {for(int n=0;n<count;++n)workers.emplace_back([&,n] {
            auto& s=strips[n];
            try {
                RunOptions options{mean,analyze,s.first-s.left,s.last-s.left};
                s.stats=defringe(s.right-s.left,h,config,[&](int y,float*a,float*b) {
                    for(int x=s.left;x<s.right;++x) {
                        const uint8_t* pixel;
                        if(x<s.first)pixel=s.before.data()+(static_cast<size_t>(y)*(s.first-s.left)+x-s.left)*4;
                        else if(x>=s.last)pixel=s.after.data()+(static_cast<size_t>(y)*(s.right-s.last)+x-s.last)*4;
                        else pixel=rgba+static_cast<size_t>(y)*stride+x*4;
                        auto lab=color.decode(pixel);a[x-s.left]=lab.a;b[x-s.left]=lab.b;
                    }
                },[&](int y,const float*a,const float*b) {
                    for(int x=s.first;x<s.last;++x) {auto pixel=rgba+static_cast<size_t>(y)*stride+x*4;color.replaceChroma(pixel,color.decode(pixel),a[x-s.left],b[x-s.left]);}
                },options);
            }catch(...){s.failure=std::current_exception();}
        });}catch(...){join();throw;}
        join();for(auto& s:strips)if(s.failure)std::rethrow_exception(s.failure);
    };
    execute(true,-1);
    double sum=0;for(auto& s:strips)sum+=s.stats.chromaMean*(s.last-s.first);
    const float mean=sum/w;
    execute(false,mean);
    Stats result;result.chromaMean=mean;result.scratchBytes=sizeof(color);
    for(auto& s:strips){result.corrected+=s.stats.corrected;result.scratchBytes+=s.stats.scratchBytes+s.before.size()+s.after.size();}
    return result;
}

}
