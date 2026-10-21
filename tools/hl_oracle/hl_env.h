// Environment for the verbatim RawTherapee HLRecovery_inpaint body
// (references/RawTherapee/hilite_recon.cc == upstream dev). Real RT headers
// (array2D, LUT, sleef, rescale, guidedfilter, boxblur) are used unmodified;
// only the RawImageSource members it reads and Color::rgbLuminance (copied
// verbatim from rtengine/color.h) are provided here.
#pragma once
#include <cassert>
#include <cmath>
#include <cstdio>
#include "array2D.h"
#include "LUT.h"
#include "opthelper.h"
#include "rt_math.h"
#include "sleef.h"
#include "rescale.h"
#include "guidedfilter.h"
#include "boxblur.h"
#define BENCHFUN
namespace rtengine {
struct Settings { bool verbose = false; };
extern const Settings* settings;
namespace procparams { struct ProcParams {}; }
struct Listener { void setProgressStr(const char*) {} void setProgress(double) {} };
struct IMatrices { double xyz_cam[3][3] = {}; };
class Color {
public:
    // rtengine/color.h, verbatim
    static float rgbLuminance(float r, float g, float b, const float workingspace[3])
    {
        return r * workingspace[0] + g * workingspace[1] + b * workingspace[2];
    }
};
class RawImageSource {
public:
    int W = 0, H = 0;
    float chmax[4] = {0, 0, 0, 0};
    float clmax[4] = {0, 0, 0, 0};
    Listener* plistener = nullptr;
    IMatrices imatrices;
    void HLRecovery_inpaint(float** red, float** green, float** blue, int blur);
};
}
