// RawTherapee Capture Sharpening reference harness (Bayer path of
// RawImageSource::captureSharpening, noise options off, mask display off).
// Algorithm functions come verbatim from rt_extract.inc; the orchestration
// below mirrors capturesharpening.cc:827-972 at b2ee504.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "array2D.h"
#include "boxblur.h"
#include "gauss.h"
#include "opthelper.h"
#include "rt_math.h"

#define BENCHFUN
#define SIMDE_VERSION_CHECK(a, b, c) 0
namespace rtengine {
class ProgressListener {
public:
    void setProgress(double) {}
};
}

#include "rt_extract.inc"

namespace {

// Color::cachefy (color.cc:174-235) and LUTf float lookup (LUT.h:461-482).
struct LabYLut {
    static constexpr int size = 65536;
    std::vector<float> data;
    int maxs = size - 2;
    float maxsf = static_cast<float>(size - 2);
    LabYLut() : data(size) {
        constexpr double eps = 216.0 / 24389.0;
        constexpr double kappa = 24389.0 / 27.0;
        const int epsmaxint = rtengine::MAXVALD * eps;
        int i = 0;
        for (; i <= epsmaxint; i++) data[i] = 327.68 * (kappa * i / rtengine::MAXVALF);
        for (; i < size; i++) data[i] = 327.68 * (116.0 * std::cbrt((double)i / rtengine::MAXVALF) - 16.0);
    }
    float operator[](float index) const {
        int idx = (int)index;
        if (index < 0.f) return data[0];
        if (index > maxsf) idx = maxs;
        float diff = index - (float)idx;
        float p1 = data[idx];
        float p2 = data[idx + 1] - p1;
        return p1 + p2 * diff;
    }
};

const LabYLut cachefy;

// Color::computeXYZ2LabY (color.h:177). The > 65535 branch uses std::cbrt instead of sleef xcbrtf.
float computeXYZ2LabY(float f) {
    constexpr double kappa = 24389.0 / 27.0;
    if (f < 0.f) return 327.68f * (kappa * f / rtengine::MAXVALF);
    if (f > 65535.f) return 327.68f * (116.f * std::cbrt(f / rtengine::MAXVALF) - 16.f);
    return cachefy[f];
}

// Color::RGB2L scalar path (color.cc:1818-1825).
void RGB2L(const float* R, const float* G, const float* B, float* L, const float wp[3][3], int width) {
    for (int i = 0; i < width; ++i) {
        float y = wp[1][0] * R[i] + wp[1][1] * G[i] + wp[1][2] * B[i];
        L[i] = computeXYZ2LabY(y);
    }
}

// Color::RGB2Y scalar path (color.h:2003).
void RGB2Y(const float* R, const float* G, const float* B, float* Y1, float* Y2, int W) {
    for (int i = 0; i < W; ++i) {
        const float r = std::max(R[i], 0.f);
        const float g = std::max(G[i], 0.f);
        const float b = std::max(B[i], 0.f);
        Y1[i] = Y2[i] = 0.2627f * r + 0.6780f * g + 0.0593f * b;
    }
}

void readPlane(const std::string& path, array2D<float>& a, int W, int H) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) { std::perror(path.c_str()); std::exit(1); }
    for (int i = 0; i < H; ++i)
        if (std::fread(a[i], sizeof(float), W, f) != (size_t)W) { std::fprintf(stderr, "short read %s\n", path.c_str()); std::exit(1); }
    std::fclose(f);
}

void writePlane(const std::string& path, const array2D<float>& a, int W, int H) {
    FILE* f = std::fopen(path.c_str(), "wb");
    for (int i = 0; i < H; ++i) std::fwrite(a[i], sizeof(float), W, f);
    std::fclose(f);
}

}

// usage: cs_ref <dir>
// dir/params.txt: W H white00 white01 white10 white11 clipVal fc0 fc1 autoRadius radius autoContrast contrast radiusOffset iterations iterCheck
// dir/{raw,r,g,b}.f32 row-major float32; writes dir/ref_{r,g,b,blend,clipmask,L,ynew}.f32 and dir/ref_info.txt
int main(int argc, char** argv) {
    if (argc != 2) { std::fprintf(stderr, "usage: %s <dir>\n", argv[0]); return 2; }
    const std::string dir = argv[1];
    FILE* pf = std::fopen((dir + "/params.txt").c_str(), "r");
    if (!pf) { std::perror("params.txt"); return 1; }
    int W, H, fc0, fc1, autoRadius, autoContrast, iterations, iterCheck;
    float wh[4], clipVal;
    double radius, conrastThreshold, radiusOffset;
    if (std::fscanf(pf, "%d %d %f %f %f %f %f %d %d %d %lf %d %lf %lf %d %d", &W, &H, &wh[0], &wh[1], &wh[2], &wh[3], &clipVal,
                    &fc0, &fc1, &autoRadius, &radius, &autoContrast, &conrastThreshold, &radiusOffset, &iterations, &iterCheck) != 16) {
        std::fprintf(stderr, "bad params.txt\n");
        return 1;
    }
    std::fclose(pf);

    array2D<float> rawData(W, H), redVals(W, H), greenVals(W, H), blueVals(W, H);
    readPlane(dir + "/raw.f32", rawData, W, H);
    readPlane(dir + "/r.f32", redVals, W, H);
    readPlane(dir + "/g.f32", greenVals, W, H);
    readPlane(dir + "/b.f32", blueVals, W, H);
    array2D<float> red(W, H), green(W, H), blue(W, H);

    // ---- capturesharpening.cc:605-611
    constexpr float xyz_rgb[3][3] = {
        { 0.412453, 0.357580, 0.180423 },
        { 0.212671, 0.715160, 0.072169 },
        { 0.019334, 0.119193, 0.950227 }
    };
    float contrast = conrastThreshold / 100.0;

    // ---- capturesharpening.cc:827-839
    array2D<float> clipMask(W, H);
    constexpr float maxSigma = 2.f;
    const float whites[2][2] = {{wh[0], wh[1]}, {wh[2], wh[3]}};
    buildClipMaskBayer(rawData, W, H, clipMask, whites);
    writePlane(dir + "/ref_clipmask.f32", clipMask, W, H);
    const unsigned int fc[2] = {(unsigned)fc0, (unsigned)fc1};
    if (autoRadius) {
        radius = std::min(calcRadiusBayer(rawData, W, H, 1000.f, clipVal, fc), maxSigma);
    }

    FILE* info = std::fopen((dir + "/ref_info.txt").c_str(), "w");
    if (std::isnan(radius)) {
        std::fprintf(info, "radius nan\n");
        std::fclose(info);
        return 0;
    }

    // ---- capturesharpening.cc:917-951
    array2D<float> L(W, H), YOld(W, H), YNew(W, H);
    for (int i = 0; i < H; ++i) {
        RGB2L(redVals[i], greenVals[i], blueVals[i], L[i], xyz_rgb, W);
        RGB2Y(redVals[i], greenVals[i], blueVals[i], YOld[i], YNew[i], W);
    }
    writePlane(dir + "/ref_L.f32", L, W, H);
    rtengine::buildBlendMask(L, clipMask, W, H, contrast, autoContrast != 0, clipMask);
    writePlane(dir + "/ref_blend.f32", clipMask, W, H);
    conrastThreshold = contrast * 100.f;
    CaptureDeconvSharpening(YNew, YOld, clipMask, W, H, radius, radiusOffset, iterations, iterCheck != 0, nullptr, 0.2, 0.9);
    writePlane(dir + "/ref_ynew.f32", YNew, W, H);

    // ---- capturesharpening.cc:960-972
    for (int i = 0; i < H; ++i) {
        for (int j = 0; j < W; ++j) {
            const float factor = YNew[i][j] / std::max(YOld[i][j], 0.00001f);
            red[i][j] = redVals[i][j] * factor;
            green[i][j] = greenVals[i][j] * factor;
            blue[i][j] = blueVals[i][j] * factor;
        }
    }
    writePlane(dir + "/ref_r.f32", red, W, H);
    writePlane(dir + "/ref_g.f32", green, W, H);
    writePlane(dir + "/ref_b.f32", blue, W, H);
    std::fprintf(info, "radius %.9g\ncontrast %.9g\n", radius, conrastThreshold);
    std::fclose(info);
    return 0;
}
