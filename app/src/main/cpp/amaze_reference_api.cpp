// SPDX-License-Identifier: GPL-3.0-or-later
#include "amaze_reference_compat.h"
#include "amaze_reference_api.h"

std::vector<float> amazeReference(const float* raw,int width,int height,
                                const unsigned* pattern,float initialGain) {
    if (width < 34 || height < 34 || width%2 || height%2 ||
        !std::isfinite(initialGain) || initialGain <= 0)
        throw std::invalid_argument("AMaZE reference requires even dimensions >=34 and positive initialGain");
    const size_t n=size_t(width)*height;
    for(size_t i=0;i<n;++i)
        if(!std::isfinite(raw[i])) throw std::invalid_argument("Non-finite CFA input");
    std::vector<float> r(n),g(n),b(n);
    rtengine::array2D<float> input(const_cast<float*>(raw),width);
    rtengine::array2D<float> red(r.data(),width),green(g.data(),width),blue(b.data(),width);
    rtengine::RawImageSource source;
    source.W=width; source.H=height; source.initialGain=initialGain;
    std::copy(pattern,pattern+4,source.pattern);
    source.amaze_demosaic_RT(0,0,width,height,input,red,green,blue,1,false);
    std::vector<float> rgb(n*3);
    for(size_t i=0;i<n;++i) { rgb[i*3]=r[i]; rgb[i*3+1]=g[i]; rgb[i*3+2]=b[i]; }
    return rgb;
}
