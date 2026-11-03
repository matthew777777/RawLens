// SPDX-License-Identifier: GPL-3.0-or-later
// New host adapter, RawLens/Codex, 2026-11-01.
#include "oracle_shim.h"
static int number(const char* s) {
    size_t end=0; auto n=std::stoll(s,&end);
    if(s[end] || n<0 || n>100000) throw std::invalid_argument("invalid dimension");
    return int(n);
}
int main(int argc,char** argv) {
    try {
        if(argc!=13) throw std::invalid_argument("usage: oracle sensorW sensorH stride left top W H RGGB|GRBG|GBRG|BGGR input.f32 output.f32 normalized-rgb stagePrefix-or--");
        int sw=number(argv[1]),sh=number(argv[2]),stride=number(argv[3]);
        int left=number(argv[4]),top=number(argv[5]),w=number(argv[6]),h=number(argv[7]);
        if(w<19 || h<19 || sw<1 || sh<1 || stride<sw || left>sw-w || top>sh-h || size_t(stride)*sh>64000000)
            throw std::invalid_argument("invalid crop/stride or canonical size below 19 / input exceeds 64M samples");
        std::string pattern=argv[8];
        if(pattern!="RGGB" && pattern!="GRBG" && pattern!="GBRG" && pattern!="BGGR") throw std::invalid_argument("invalid CFA");
        std::vector<float> input(size_t(stride)*sh);
        std::ifstream in(argv[9],std::ios::binary);
        in.read(reinterpret_cast<char*>(input.data()),input.size()*sizeof(float));
        if(!in || in.peek()!=EOF) throw std::invalid_argument("incorrect input byte count");
        for(float v:input) if(!std::isfinite(v) || v<0 || v>1) throw std::invalid_argument("canonical normalized input must be finite in [0,1]");
        rtengine::RawImageSource image(w,h);
        for(int y=0;y<2;++y) for(int x=0;x<2;++x) {
            char c=pattern[((y+top)&1)*2+((x+left)&1)];
            image.phase[y][x]=c=='R'?0:c=='G'?1:2;
        }
        for(int y=0;y<h;++y) for(int x=0;x<w;++x) image.rawData[y][x]=input[size_t(y+top)*stride+x+left]*65536.f;
        if(std::string(argv[12])!="-") image.stagePrefix=argv[12];
        image.rcd_demosaic(1,false);
        // argv[11] declares output contract, avoiding ambiguous scaled output.
        if(std::string(argv[11])!="normalized-rgb") throw std::invalid_argument("output contract must be normalized-rgb");
        std::ofstream out(argv[10],std::ios::binary);
        if(!out) throw std::runtime_error("output open failed");
        for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
            float pixel[]={image.red[y][x]/65536.f,image.green[y][x]/65536.f,image.blue[y][x]/65536.f};
            for(float v:pixel) if(!std::isfinite(v)) throw std::runtime_error("nonfinite/unwritten output");
            out.write(reinterpret_cast<char*>(pixel),sizeof(pixel));
        }
        if(!out) throw std::runtime_error("output write failed");
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 2; }
}
