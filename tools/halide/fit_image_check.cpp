// SPDX-License-Identifier: GPL-3.0-or-later
// Replay BguImageParityInstrumentedTest's frozen low/full operator pairs.
// Link against the desired bgu_fit specialization; argv[2] is its curve alpha.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <vector>
#include "HalideBuffer.h"
#include "bgu_fit.h"
using Halide::Runtime::Buffer;
int main(int argc,char **argv) {
    if(argc!=4 && argc!=5) { std::fprintf(stderr,"pairs.bin curve_alpha output_prefix [RAW|JPEG|GUIDE_SRGB]\n");return 2; }
    std::ifstream in(argv[1],std::ios::binary);
    int dims[4];in.read(reinterpret_cast<char*>(dims),sizeof(dims));
    int lw=dims[0],lh=dims[1],w=dims[2],h=dims[3];
    if(!in || lw<=0 || lh<=0 || w<=0 || h<=0) return 2;
    int lowN=lw*lh,n=w*h;
    std::vector<float> lowGuide(lowN*3),lowDev(lowN*3),fullGuide(n*3),fullDev(n*3);
    for(auto *v:{&lowGuide,&lowDev,&fullGuide,&fullDev})
        in.read(reinterpret_cast<char*>(v->data()),v->size()*sizeof(float));
    if(!in) return 2;
    const bool encodedGuide=argc==5 && std::string(argv[4])=="GUIDE_SRGB";
    const bool linear=argc==5 && !encodedGuide;
    const bool raw=linear && std::string(argv[4])=="RAW";
    auto decode=[&](float v) {
        return raw ? std::pow(v,2.2f) : v<=0.04045f ? v/12.92f : std::pow((v+0.055f)/1.055f,2.4f);
    };
    auto encode=[&](float v) {
        return raw ? std::pow(v,1.f/2.2f) : v<=0.0031308f ? 12.92f*v : 1.055f*std::pow(v,1.f/2.4f)-0.055f;
    };
    if(linear) for(float &v:lowDev) v=decode(v);
    if(encodedGuide) {
        for(float &v:lowGuide) v=encode(v);
        for(float &v:fullGuide) v=encode(v);
    }
    float alpha=std::stof(argv[2]);
    Buffer<float> guide(lowGuide.data(),lw,lh,3),dev(lowDev.data(),lw,lh,3);
    for(int s:{16,8}) for(int bins:{8,16,32}) {
        int gw=(lw+s-1)/s,gh=(lh+s-1)/s,gz=bins+1;
        Buffer<float> grid(gw,gh,gz,12);
        auto start=std::chrono::steady_clock::now();
        if(bgu_fit(guide,dev,s,1.f/bins,1e-6f,grid)) return 1;
        double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        double absSum=0,darkSum=0,maxErr=0,blackSum=0;int darkN=0,blackN=0;
        std::vector<unsigned char> rgb(n*3);
        for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
            int i=x+w*y;
            float luma=0.25f*fullGuide[i]+0.5f*fullGuide[n+i]+0.25f*fullGuide[2*n+i];
            float coord[3]={(x+0.5f)/w*lw/s,(y+0.5f)/h*lh/s,
                luma/(1.f-alpha+alpha*luma)*bins};
            float matrix[12]={};
            for(int dz=0;dz<2;++dz) for(int dy=0;dy<2;++dy) for(int dx=0;dx<2;++dx) {
                int d[3]={dx,dy,dz},dim[3]={gw,gh,gz},cell[3];float wt=1;
                for(int k=0;k<3;++k) {
                    int base=(int)std::floor(coord[k]);float f=coord[k]-base;
                    wt*=d[k]?f:1-f;cell[k]=std::clamp(base+d[k],0,dim[k]-1);
                }
                for(int k=0;k<12;++k) matrix[k]+=wt*grid(cell[0],cell[1],cell[2],k);
            }
            for(int c=0;c<3;++c) {
                float got=matrix[c*4+3];
                for(int k=0;k<3;++k) got+=matrix[c*4+k]*fullGuide[k*n+i];
                got=std::clamp(got,0.f,1.f);
                if(linear) got=encode(got);
                double err=got-fullDev[c*n+i];
                absSum+=std::abs(err);maxErr=std::max(maxErr,std::abs(err));
                if(fullDev[c*n+i]<0.25f) { darkSum+=err;++darkN; }
                if(fullDev[c*n+i]<0.005f) { blackSum+=err;++blackN; }
                rgb[i*3+c]=(unsigned char)(got*255+0.5f);
            }
        }
        std::printf("linear=%d alpha=%.2f s=%d bins=%d fitMs=%.3f meanAbs=%.6f max=%.6f darkBias=%.6f darkN=%d blackBias=%.6f blackN=%d\n",
            linear,alpha,s,bins,ms,absSum/(n*3),maxErr,darkSum/std::max(darkN,1),darkN,blackSum/std::max(blackN,1),blackN);
        char name[1024];std::snprintf(name,sizeof(name),"%s-s%d-z%d.ppm",argv[3],s,bins);
        std::ofstream out(name,std::ios::binary);out<<"P6\n"<<w<<" "<<h<<"\n255\n";
        out.write(reinterpret_cast<char*>(rgb.data()),rgb.size());
    }
}
