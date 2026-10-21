// in.f16 (RGBA half, normalized tap) out.f32 (RGB, normalized) W H white black y0 y1 y2
#include "hl_env.h"
#include <cstdlib>
#include <vector>
namespace rtengine { static const Settings kSettings; const Settings* settings = &kSettings; }
static float half2f(uint16_t h) {
    uint32_t s = (h & 0x8000u) << 16, e = (h >> 10) & 0x1f, m = h & 0x3ff, bits;
    if (e == 0) { if (m == 0) bits = s; else { e = 1; while (!(m & 0x400)) { m <<= 1; --e; } m &= 0x3ff; bits = s | ((e + 112) << 23) | (m << 13); } }
    else if (e == 31) bits = s | 0x7f800000u | (m << 13); else bits = s | ((e + 112) << 23) | (m << 13);
    float f; std::memcpy(&f, &bits, 4); return f;
}
int main(int argc, char** argv) {
    const int W = atoi(argv[3]), H = atoi(argv[4]);
    const float white = atof(argv[5]), black = atof(argv[6]), range = white - black;
    std::vector<uint16_t> in(size_t(W) * H * 4);
    FILE* f = fopen(argv[1], "rb"); fread(in.data(), 2, in.size(), f); fclose(f);
    std::vector<float> r(size_t(W) * H), g(r.size()), b(r.size());
    std::vector<float*> rr(H), gg(H), bb(H);
    for (int y = 0; y < H; ++y) { rr[y] = &r[size_t(y) * W]; gg[y] = &g[size_t(y) * W]; bb[y] = &b[size_t(y) * W]; }
    auto clampv = [&](float v) { return v != v ? white : v > 65504.f ? 65504.f : v < -65504.f ? -65504.f : v; };
    for (size_t i = 0; i < r.size(); ++i) {   // HlReconstructionStage.convertTap
        r[i] = clampv(half2f(in[i * 4]) * range + black);
        g[i] = clampv(half2f(in[i * 4 + 1]) * range + black);
        b[i] = clampv(half2f(in[i * 4 + 2]) * range + black);
    }
    rtengine::RawImageSource src;
    src.W = W; src.H = H;
    for (int c = 0; c < 3; ++c) { src.chmax[c] = white; src.clmax[c] = white - black; }
    for (int c = 0; c < 3; ++c) src.imatrices.xyz_cam[1][c] = atof(argv[7 + c]);
    src.HLRecovery_inpaint(rr.data(), gg.data(), bb.data(), 2);   // HLBL_DEFAULT = 2
    std::vector<float> out(r.size() * 3);
    for (size_t i = 0; i < r.size(); ++i) {   // pack: (raw - black) / range
        out[i * 3] = (r[i] - black) / range; out[i * 3 + 1] = (g[i] - black) / range; out[i * 3 + 2] = (b[i] - black) / range;
    }
    f = fopen(argv[2], "wb"); fwrite(out.data(), 4, out.size(), f); fclose(f);
}
