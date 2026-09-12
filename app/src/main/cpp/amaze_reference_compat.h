// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal environment for the unmodified pinned RawTherapee AMaZE body.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

// Define a reproducible scalar reference on every ABI. SIMD is a different
// implementation of ordered updates, not merely a throughput switch.
#undef __SSE2__
#undef RT_SIMDE
#undef _OPENMP
using std::min;
template<class T> constexpr T SQR(T x) { return x*x; }
template<class T> constexpr T intp(T a, T b, T c) { return a*(b-c)+c; }
template<class T> T median(T a, T b, T c) {
    return std::max(std::min(a,b), std::min(c,std::max(a,b)));
}
// RawTherapee sleef.h exponent helpers, preserving their bit-level behavior.
inline float xdivf(float d, int n) {
    union { float floatval; int intval; } uflint;
    uflint.floatval=d;
    if (uflint.intval & 0x7FFFFFFF) uflint.intval -= n << 23;
    return uflint.floatval;
}
inline float xdiv2f(float d) { return xdivf(d,1); }
inline float xmul2f(float d) {
    union { float floatval; int intval; } uflint;
    uflint.floatval=d;
    if (uflint.intval & 0x7FFFFFFF) uflint.intval += 1 << 23;
    return uflint.floatval;
}
struct StopWatch { explicit StopWatch(const char*) {} };
inline const char* M(const char* s) { return s; }
namespace Glib { struct ustring {
    static std::string compose(const char*, const char*) { return {}; }
}; }
namespace rtengine {
template<class T> class array2D {
    T* data_;
    int width_;
public:
    array2D(T* data,int width):data_(data),width_(width) {}
    T* operator[](int row) { return data_ + size_t(row)*width_; }
    const T* operator[](int row) const { return data_ + size_t(row)*width_; }
};
struct NullListener {
    void setProgressStr(const std::string&) {}
    void setProgress(double) {}
};
class RawImageSource {
public:
    int W, H;
    float initialGain;
    int border=4;
    unsigned pattern[4];
    NullListener* plistener=nullptr;
    unsigned FC(int y,int x) const { return pattern[(y&1)*2+(x&1)]; }
    void amaze_demosaic_RT(int,int,int,int,const array2D<float>&,
        array2D<float>&,array2D<float>&,array2D<float>&,size_t,bool);
    void border_interpolate(int,int,int,const array2D<float>&,
        array2D<float>&,array2D<float>&,array2D<float>&) {
        throw std::logic_error("Reference contract requires border >= 4");
    }
};
}
