// Standalone RawTherapee AMaZE (b2ee504). Usage: amaze_ref <dir>
// dir/amaze_params.txt: W H fc00 fc01 fc10 fc11 initialGain ; dir/raw.f32 (scaled CFA, white ~65535)
// writes dir/amaze_{r,g,b}.f32
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <string>

#include "array2D.h"
#include "median.h"
#include "opthelper.h"
#include "rt_math.h"
#include "sleef.h"
#include "StopWatch.h"

#define SIMDE_VERSION_CHECK(a, b, c) 0
namespace Glib { struct ustring { template <class... A> static std::string compose(A...) { return {}; } }; }
inline std::string M(const char*) { return {}; }

namespace rtengine {
class ProgressListener {
public:
    void setProgressStr(const std::string&) {}
    void setProgress(double) {}
};

// Minimal stand-in for the RawImageSource members AMaZE uses.
class RawImageSource {
public:
    int W = 0, H = 0;
    ProgressListener* plistener = nullptr;
    double initialGain = 1.0;
    int border = 0;  // 0 => AMaZE calls border_interpolate for the outer pixels
    unsigned cfa[2][2] = {{0, 1}, {1, 2}};
    unsigned FC(int row, int col) const { return cfa[row & 1][col & 1]; }
    void amaze_demosaic_RT(int winx, int winy, int winw, int winh, const array2D<float>& rawData, array2D<float>& red,
                           array2D<float>& green, array2D<float>& blue, size_t chunkSize, bool measure);
    void border_interpolate(int winw, int winh, int lborders, const array2D<float>& rawData, array2D<float>& red,
                            array2D<float>& green, array2D<float>& blue);
};
}

#include "amaze_extract.inc"


int main(int argc, char** argv) {
    if (argc != 2) { std::fprintf(stderr, "usage: %s <dir>\n", argv[0]); return 2; }
    const std::string dir = argv[1];
    rtengine::RawImageSource s;
    FILE* pf = std::fopen((dir + "/amaze_params.txt").c_str(), "r");
    if (!pf || std::fscanf(pf, "%d %d %u %u %u %u %lf", &s.W, &s.H, &s.cfa[0][0], &s.cfa[0][1], &s.cfa[1][0], &s.cfa[1][1], &s.initialGain) != 7) {
        std::fprintf(stderr, "bad amaze_params.txt\n");
        return 1;
    }
    std::fclose(pf);
    const int W = s.W, H = s.H;
    ::array2D<float> raw(W, H), r(W, H), g(W, H), b(W, H);
    FILE* f = std::fopen((dir + "/raw.f32").c_str(), "rb");
    for (int i = 0; i < H; ++i) if (std::fread(raw[i], 4, W, f) != (size_t)W) { std::fprintf(stderr, "short read\n"); return 1; }
    std::fclose(f);
    s.amaze_demosaic_RT(0, 0, W, H, raw, r, g, b, 1, true);
    const char* names[3] = {"r", "g", "b"};
    ::array2D<float>* planes[3] = {&r, &g, &b};
    for (int c = 0; c < 3; ++c) {
        FILE* o = std::fopen((dir + "/amaze_" + names[c] + ".f32").c_str(), "wb");
        for (int i = 0; i < H; ++i) std::fwrite((*planes[c])[i], 4, W, o);
        std::fclose(o);
    }
    return 0;
}
