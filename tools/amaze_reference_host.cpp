// SPDX-License-Identifier: GPL-3.0-or-later
// Independent host runner: invoke upstream directly, without the Android API wrapper.
#include "amaze_reference_compat.h"
int main() {
    uint32_t w,h,fc[4]; float gain;
    auto read=[](void* p,size_t n){std::cin.read(static_cast<char*>(p),n);};
    read(&w,4); read(&h,4); read(fc,16); read(&gain,4);
    if(w<34 || h<34 || w>2048 || h>2048 || w%2 || h%2) return 2;
    size_t n=size_t(w)*h;
    std::vector<float> raw(n),r(n),g(n),b(n);
    read(raw.data(),n*4); if(!std::cin) return 3;
    rtengine::array2D<float> input(raw.data(),w),red(r.data(),w),green(g.data(),w),blue(b.data(),w);
    rtengine::RawImageSource source;
    source.W=w; source.H=h; source.initialGain=gain;
    std::copy(fc,fc+4,source.pattern);
    source.amaze_demosaic_RT(0,0,w,h,input,red,green,blue,1,false);
    for(size_t i=0;i<n;++i) {
        float rgb[]={r[i],g[i],b[i]};
        std::cout.write(reinterpret_cast<char*>(rgb),12);
    }
}
