// SPDX-License-Identifier: GPL-3.0-or-later
// RawLens host-only integration, 2026-11-01. No Android dependencies.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#define ALIGNED16 __attribute__((aligned(16)))
struct StopWatch { explicit StopWatch(const char*) {} };
namespace Glib { struct ustring { template<class... T> static std::string compose(T...) { return {}; } }; }
inline const char* M(const char* s) { return s; }
struct Listener { void setProgressStr(const std::string&) {} void setProgress(double) {} };
template<class T> class array2D {
    int width_;
    std::vector<T> data_;
public:
    array2D(int w, int h): width_(w), data_(size_t(w)*h, std::numeric_limits<T>::quiet_NaN()) {}
    T* operator[](int y) { return data_.data()+size_t(y)*width_; }
    const T* operator[](int y) const { return data_.data()+size_t(y)*width_; }
};
namespace rtengine {
// The three arithmetic helpers are generated verbatim from pinned rt_math.h.
#include "math_helpers.inc"
class RawImageSource {
public:
    int W, H;
    unsigned phase[2][2];
    array2D<float> rawData, red, green, blue;
    Listener* plistener = nullptr;
    std::string stagePrefix;
    RawImageSource(int w, int h): W(w), H(h), rawData(w,h), red(w,h), green(w,h), blue(w,h) {}
    unsigned FC(int y, int x) const { return phase[y&1][x&1]; }
    void igv_interpolate(int, int) { throw std::invalid_argument("non-Bayer CFA"); }
    void rcd_demosaic(size_t, bool);
    void border_interpolate(int,int,int,const array2D<float>&,array2D<float>&,array2D<float>&,array2D<float>&);
    void capture(const char* stage, const float* field, int stride, bool half, int rs, int re, int cs, int ce) {
        if(stagePrefix.empty()) return;
        // Only upstream-owned interior, never uninitialised tile halo values.
        std::ofstream out(stagePrefix+stage+".bin", std::ios::binary|std::ios::app);
        if(!out) throw std::runtime_error("stage output open failed");
        for(int y=rs+9; y<re-9; ++y) for(int x=cs+9; x<ce-9; ++x) {
            if((half || std::string(stage)=="opposite_r") && FC(y,x)==1) continue;
            int index=(y-rs)*stride+x-cs;
            float v=field[half ? index/2 : index];
            out.write(reinterpret_cast<const char*>(&v),sizeof(v));
        }
        if(!out) throw std::runtime_error("stage output write failed");
    }
};
}
