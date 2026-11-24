#version 450
// RawTherapee Capture Sharpening on the GPU (rtengine/capturesharpening.cc
// CaptureDeconvSharpening, rtengine/rt_algo.cc buildBlendMask).
// Copyright (c) 2019 Ingo Weyrich, Jacques Desmis 2024-2025 (RawTherapee);
// Vulkan port RawLens contributors 2026. GPL-3.0-or-later.
//
// Built per radius class by tools/build_rtcs_shader.sh (R, TILE, BORDER).
// Mobile design: one workgroup per RT 32 px tile runs every Richardson-Lucy
// iteration in shared memory (no image round trips); the Gaussian is applied
// as two 1D passes over 4-pixel register strips (RT's 2D kernel is a
// circularly truncated Gaussian; the separable one differs only in its far
// corners, < 1e-4 of the weight for the usual sigma <= 0.84).
#ifndef R
#define R 2
#endif
#ifndef TILE
#define TILE 32
#endif
#ifndef BORDER
#define BORDER 5
#endif
#define FTS (TILE + 2 * BORDER)
#define NCH ((FTS - 2 * R + 3) / 4)

layout(local_size_x = 256) in;
layout(binding = 0, r32f) uniform readonly image2D yImg; // Rec.2020 Y; sign bit = clipped pixel
layout(binding = 1, r32f) uniform image2D lImg;          // CIE L (mode 0), then Ynew
layout(binding = 2, r32f) uniform image2D aImg;          // blend mask
layout(binding = 3, r32f) uniform image2D bImg;          // blur scratch
layout(std140, binding = 4) uniform Params {
    ivec4 a;    // width, rows, mode, tilesX
    ivec4 b;    // coreLo, coreHi (band rows), imageTop (1 = band touches image row 0), imageBottom
    vec4 c;     // contrastThreshold, iterations, checkStop, unused
    vec4 k[4];  // 1D deconvolution Gaussian, taps -R..R
    vec4 g[4];  // 1D blend-mask blur Gaussian (sigma 2), taps -6..6
} p;

float kw(int i) { return p.k[i >> 2][i & 3]; }
float gw(int i) { return p.g[i >> 2][i & 3]; }

// RT calcBlendFactor: sigmoid, inflexion at (threshold, 0.5).
float blendFactor(float v, float t) {
    float x = -16.0 + (16.0 / t) * v;
    return 0.5 * (1.0 + x * inversesqrt(1.0 + x * x));
}

float L(int x, int y) { return imageLoad(lImg, ivec2(x, y)).r; }

// 3x3 median of |Y| (RT "noise before capture sharpening", median type):
// the deconvolution runs on it so isolated speckles are not amplified into
// salt & pepper; the gain is taken relative to it (see the final write).
#define SWAP(a, b) { float t_ = min(a, b); b = max(a, b); a = t_; }
float med9(int x, int y, int w, int rows) {
    float v0, v1, v2, v3, v4, v5, v6, v7, v8;
    #define Y_(dx, dy) abs(imageLoad(yImg, clamp(ivec2(x + dx, y + dy), ivec2(0), ivec2(w - 1, rows - 1))).r)
    v0 = Y_(-1, -1); v1 = Y_(0, -1); v2 = Y_(1, -1);
    v3 = Y_(-1, 0);  v4 = Y_(0, 0);  v5 = Y_(1, 0);
    v6 = Y_(-1, 1);  v7 = Y_(0, 1);  v8 = Y_(1, 1);
    #undef Y_
    // Paeth's 19-exchange median network.
    SWAP(v1, v2) SWAP(v4, v5) SWAP(v7, v8) SWAP(v0, v1) SWAP(v3, v4) SWAP(v6, v7)
    SWAP(v1, v2) SWAP(v4, v5) SWAP(v7, v8) SWAP(v0, v3) SWAP(v5, v8) SWAP(v4, v7)
    SWAP(v3, v6) SWAP(v1, v4) SWAP(v2, v5) SWAP(v4, v7) SWAP(v4, v2) SWAP(v6, v4)
    SWAP(v4, v2)
    return v4;
}

shared float sI[FTS * FTS];
shared float sT[FTS * FTS];
shared float sL[FTS * FTS];
shared float sH[FTS * FTS];
shared int sFlag;

// sH = horizontal Gaussian of src for every row, columns R..FTS-R-1.
#define HPASS(src)                                                          \
    for (int ch = lid; ch < FTS * NCH; ch += 256) {                         \
        int r = ch / NCH, c0 = R + (ch - r * NCH) * 4;                      \
        float v[4 + 2 * R];                                                 \
        for (int t = 0; t < 4 + 2 * R; ++t)                                 \
            v[t] = src[r * FTS + min(c0 - R + t, FTS - 1)];                 \
        for (int o = 0; o < 4; ++o) {                                       \
            float s = 0.0;                                                  \
            for (int t = 0; t <= 2 * R; ++t) s += kw(t) * v[o + t];         \
            if (c0 + o < FTS - R) sH[r * FTS + c0 + o] = s;                 \
        }                                                                   \
    }

void main() {
    int w = p.a.x, rows = p.a.y, mode = p.a.z;
    int lid = int(gl_LocalInvocationID.x);
    if (mode < 3) {
        int x = int(gl_GlobalInvocationID.x), y = int(gl_GlobalInvocationID.y);
        if (x >= w || y >= rows) return;
        if (mode == 0) {
            // RT buildBlendMask: computed on [2, H-3] x [2, W-3], borders copied
            // (clamping the centre is the same copy).
            int i = clamp(x, 2, w - 3), j = clamp(y, 2, rows - 3);
            float blend = 1.0;
            if (p.c.x > 0.0) {
                float d0 = L(i + 1, j) - L(i - 1, j), d1 = L(i, j + 1) - L(i, j - 1);
                float d2 = L(i + 2, j) - L(i - 2, j), d3 = L(i, j + 2) - L(i, j - 2);
                float contrast = sqrt(d0 * d0 + d1 * d1 + d2 * d2 + d3 * d3) * (0.0625 / 327.68);
                // RT buildClipMaskBayer footprint (5x5 diamond) around clipped pixels.
                float clip = 1.0;
                for (int dy = -2; dy <= 2; ++dy) {
                    int span = abs(dy) == 2 ? 1 : 2;
                    for (int dx = -span; dx <= span; ++dx) {
                        ivec2 q = clamp(ivec2(i + dx, j + dy), ivec2(0), ivec2(w - 1, rows - 1));
                        if (imageLoad(yImg, q).r < 0.0) clip = 0.0;
                    }
                }
                blend = clip * blendFactor(contrast, p.c.x);
            }
            imageStore(aImg, ivec2(x, y), vec4(blend));
        } else if (mode == 1) {
            float s = 0.0;
            for (int t = -6; t <= 6; ++t) s += gw(t + 6) * imageLoad(aImg, ivec2(clamp(x + t, 0, w - 1), y)).r;
            imageStore(bImg, ivec2(x, y), vec4(s));
        } else {
            if (p.c.x > 0.0) {
                float s = 0.0;
                for (int t = -6; t <= 6; ++t) s += gw(t + 6) * imageLoad(bImg, ivec2(x, clamp(y + t, 0, rows - 1))).r;
                imageStore(aImg, ivec2(x, y), vec4(s));
            }
            // L is consumed: from here on lImg holds the luminance gain (1 = unchanged).
            imageStore(lImg, ivec2(x, y), vec4(1.0));
        }
        return;
    }

    // ---- mode 3: RT CaptureDeconvSharpening, one tile per workgroup.
    int tx = int(gl_WorkGroupID.x), ty = int(gl_WorkGroupID.y);
    int coreLo = p.b.x, coreHi = p.b.y;
    int wantX = BORDER + tx * TILE, wantY = coreLo + ty * TILE;
    // End tiles shift inward like RT's; they only write what no other tile writes.
    int ox = min(wantX, w - BORDER - TILE), oy = min(wantY, coreHi - TILE);
    int bx = ox - BORDER, by = oy - BORDER;
    if (lid == 0) sFlag = 0;
    barrier();
    for (int idx = lid; idx < FTS * FTS; idx += 256) {
        int r = idx / FTS, c = idx - r * FTS;
        float v = med9(bx + c, by + r, w, rows);
        sI[idx] = v; sL[idx] = v; sT[idx] = 1.0;
    }
    barrier();
    // Core pixels of this thread (TILE*TILE / 256 of them) in registers.
    const int PER = (TILE * TILE + 255) / 256;
    float chk[PER], bl[PER];
    bool hasBlend = false;
    for (int q = 0; q < PER; ++q) {
        int idx = lid + q * 256;
        bl[q] = 0.0; chk[q] = -1.0;
        if (idx < TILE * TILE) {
            int r = idx / TILE, c = idx - r * TILE;
            bl[q] = imageLoad(aImg, ivec2(ox + c, oy + r)).r;
            chk[q] = sL[(r + BORDER) * FTS + c + BORDER] * bl[q] * 0.5;
            hasBlend = hasBlend || bl[q] >= 0.01; // RT minBlend: skip all-flat tiles
        }
    }
    if (hasBlend) atomicOr(sFlag, 1);
    barrier();
    if (sFlag == 0) return;
    barrier();
    int iterations = int(p.c.y);
    bool checkStop = p.c.z > 0.5;
    for (int it = 0; it < iterations; ++it) {
        // gauss*div2: T = L / max(G * I, 1e-5) on the interior.
        HPASS(sI)
        barrier();
        for (int ch = lid; ch < NCH * (FTS - 2 * R); ch += 256) {
            int c = R + (ch % (FTS - 2 * R));
            int r0 = R + (ch / (FTS - 2 * R)) * 4;
            float v[4 + 2 * R];
            for (int t = 0; t < 4 + 2 * R; ++t) v[t] = sH[min(r0 - R + t, FTS - 1) * FTS + c];
            for (int o = 0; o < 4; ++o) {
                float s = 0.0;
                for (int t = 0; t <= 2 * R; ++t) s += kw(t) * v[o + t];
                int r = r0 + o;
                if (r < FTS - R) sT[r * FTS + c] = sL[r * FTS + c] / max(s, 0.00001);
            }
        }
        barrier();
        // gauss*mult2: I *= G * T on the interior.
        HPASS(sT)
        barrier();
        for (int ch = lid; ch < NCH * (FTS - 2 * R); ch += 256) {
            int c = R + (ch % (FTS - 2 * R));
            int r0 = R + (ch / (FTS - 2 * R)) * 4;
            float v[4 + 2 * R];
            for (int t = 0; t < 4 + 2 * R; ++t) v[t] = sH[min(r0 - R + t, FTS - 1) * FTS + c];
            for (int o = 0; o < 4; ++o) {
                float s = 0.0;
                for (int t = 0; t <= 2 * R; ++t) s += kw(t) * v[o + t];
                int r = r0 + o;
                if (r < FTS - R) sI[r * FTS + c] *= s;
            }
        }
        if (lid == 0) sFlag = 0;
        barrier();
        // RT checkForStop: any core pixel below half its blended original.
        if (checkStop && it < iterations - 1) {
            bool stop = false;
            for (int q = 0; q < PER; ++q) {
                int idx = lid + q * 256;
                if (idx < TILE * TILE) {
                    int r = idx / TILE, c = idx - r * TILE;
                    stop = stop || sI[(r + BORDER) * FTS + c + BORDER] < chk[q];
                }
            }
            if (stop) atomicOr(sFlag, 1);
        }
        barrier();
        if (sFlag != 0) break;
        barrier();
    }
    for (int q = 0; q < PER; ++q) {
        int idx = lid + q * 256;
        if (idx >= TILE * TILE) continue;
        int r = idx / TILE, c = idx - r * TILE;
        int gx = ox + c, gy = oy + r;
        if (gx < wantX || gy < wantY || gy >= coreHi) continue;
        float yOld = sL[(r + BORDER) * FTS + c + BORDER];
        float yNew = sI[(r + BORDER) * FTS + c + BORDER];
        // RT intp, then the gain relative to the median base: edges get RT's
        // sharpening, a lone speckle keeps its own value (neither boosted nor removed).
        float blended = bl[q] * yNew + (1.0 - bl[q]) * yOld;
        imageStore(lImg, ivec2(gx, gy), vec4(blended / max(yOld, 0.00001)));
    }
}
