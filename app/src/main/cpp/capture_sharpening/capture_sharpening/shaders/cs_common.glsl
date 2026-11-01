// Shared declarations for the capture sharpening passes (RawTherapee
// capturesharpening.cc / rt_algo.cc port). All arithmetic runs in RT's
// domain: white-balanced camera RGB scaled so sensor white = 65535. The input
// image is pre-WB camera RGB normalized to white = 1; pc.toRt converts.

#define CS_FLAG_AUTO_RADIUS 1u
#define CS_FLAG_AUTO_CONTRAST 2u
#define CS_FLAG_ITER_CHECK 4u
#define CS_FLAG_CLIP_STATE 8u

// Params.state
#define CS_STATE_NEED_PASS1 1u
#define CS_STATE_NEED_REFINE 2u
#define CS_STATE_CONTRAST_READY 3u
#define CS_STATE_DONE 4u

layout(push_constant) uniform CsPush {
    ivec2 size;        // image width, height
    uint pattern;      // CFA pattern (cfa_common.glsl)
    uint flags;        // CS_FLAG_*
    vec4 toRt;         // rgb: per-channel input -> RT units (65535 * wb / min(wb)); a: unused
    float radiusOffset;
    uint iterations;
    uint mode;         // pass-specific
    uint gridW;        // pass-specific tile grid width
    uint gridH;        // pass-specific tile grid height
    uint tileSize;     // auto-contrast tile size
    uint tileSkip;     // auto-contrast tile stride
    uint tStride;      // row stride of the transposed scratch (padded context rows)
    // Strip mode: x = first context row, y = context rows (scratch and blend
    // mask hold rows [x, x + y)); rows [z, w) were already sharpened by an
    // earlier strip and are read from apronImage[apronSel] (saved originals).
    ivec4 strip;
    uint apronSel;
    uint pad0, pad1, pad2;
} pc;

layout(set = 0, binding = 0, rgba16f) uniform image2D rgbImage;
layout(set = 0, binding = 1, r16ui) uniform readonly uimage2D clipStateImage;
// Float scratch over the strip context rows: the raw blend mask (transposed)
// during the blur, then the original luminance Y (row-major) for the
// deconvolution.
layout(set = 0, binding = 2, std430) buffer CsScratch {
    float scratch[];
};
layout(set = 0, binding = 3, r16f) uniform image2D blendImage;  // strip context rows
layout(set = 0, binding = 4, std430) buffer CsParams {
    uint maxRatioBits;  // float bits of the accepted max green ratio (auto radius)
    float sigma;        // deconvolution radius
    float contrast;     // blend threshold (RT contrast / 100); 0 = blend 1 everywhere
    uint state;         // CS_STATE_*
    int tileY;          // chosen auto-contrast tile
    int tileX;
    uint tileSz;
    int refineY0;       // pass-1 refinement window
    int refineX0;
    int refineH;
    int refineW;
    float minVar;       // diagnostics: chosen tile variance
    // Young-van Vliet sigma-2 coefficients (gauss.cc calculateYvVFactors, M
    // already divided as in gaussHorizontal/gaussVertical), set by the host.
    float yvvB, yvvB1, yvvB2, yvvB3;
    float yvvM[9];
} params;
layout(set = 0, binding = 5, std430) buffer CsVariances {
    float variances[];
};
// Indirect dispatch sizes of the fixed-kernel deconvolution variants
// (3, 5, 7, 9, 13), written by cs_select mode 0: per variant, one entry per
// band of tile rows then the full grid. xyz used, w padding.
layout(set = 0, binding = 6, std430) buffer CsDispatch {
    uvec4 deconvDispatch[];
};
// Per 10x10 block (sum of L, sum of squared deviations from the block mean),
// block grid (W / 10) x (H / 10); see cs_luma_blocks.comp.
layout(set = 0, binding = 7, std430) buffer CsBlocks {
    vec2 blockStats[];
};
// Original RGB of rows a previous strip has sharpened (see pc.strip.zw).
layout(set = 0, binding = 8, rgba16f) uniform image2D apronImages[2];

#include "cfa_common.glsl"

#define CS_INF uintBitsToFloat(0x7f800000u)

vec3 toRt(vec3 rgb) { return rgb * pc.toRt.rgb; }
// Original (unsharpened) input RGB at image position p.
vec3 loadOriginal(ivec2 p) {
    if (p.y >= pc.strip.z && p.y < pc.strip.w) {
        ivec2 a = p - ivec2(0, pc.strip.z);
        return pc.apronSel == 0u ? imageLoad(apronImages[0], a).rgb : imageLoad(apronImages[1], a).rgb;
    }
    return imageLoad(rgbImage, p).rgb;
}
vec3 loadRt(ivec2 p) { return toRt(loadOriginal(p)); }
// Strip-local scratch and blend mask at image position p.
uint scratchRow(ivec2 p) { return uint(p.y - pc.strip.x) * uint(pc.size.x) + uint(p.x); }
float blendAt(ivec2 p) { return imageLoad(blendImage, p - ivec2(0, pc.strip.x)).r; }

// Color::RGB2Y (color.h:2003).
float rtY(vec3 c) {
    c = max(c, vec3(0.0));
    return 0.2627 * c.r + 0.6780 * c.g + 0.0593 * c.b;
}

// Color::RGB2L with xyz_rgb row 1 and computeXYZ2LabY; the LUT is replaced by
// its exact formula (the LUT linearly interpolates the same curve).
float rtL(vec3 c) {
    const float kappa = 24389.0 / 27.0;
    const float epsMax = 65535.0 * 216.0 / 24389.0;
    float y = 0.212671 * c.r + 0.715160 * c.g + 0.072169 * c.b;
    if (y <= epsMax) return 327.68 * (kappa * y / 65535.0);
    return 327.68 * (116.0 * pow(y / 65535.0, 1.0 / 3.0) - 16.0);
}

float lumL(ivec2 p) { return rtL(loadRt(p)); }

// Value of the CFA sample at sensor site q, read back from the demosaiced
// image (demosaicers keep native samples), in RT units.
float nativeRt(ivec2 q) {
    int ch = rawrCfaChannelAt(pc.pattern, q);
    vec3 c = loadRt(q);
    return ch == 0 ? c.r : (ch == 3 ? c.b : c.g);
}

// buildClipMaskBayer: site q counts as clipped from the sensor clip state when
// bound, otherwise from its native sample against 0.95 of white.
bool siteClipped(ivec2 q) {
    int ch = rawrCfaChannelAt(pc.pattern, q);
    if ((pc.flags & CS_FLAG_CLIP_STATE) != 0u) {
        uint bits = imageLoad(clipStateImage, q >> 1).r;
        return (bits & (1u << uint(ch))) != 0u;
    }
    vec3 c = loadOriginal(q);
    float v = ch == 0 ? c.r : (ch == 3 ? c.b : c.g);
    return v >= 0.95;
}

// 1 unless a clipped site in rows/cols [2, size-2) lies in the 21-site diamond
// around p (capturesharpening.cc:61-73).
float clipMaskAt(ivec2 p) {
    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            if (abs(dy) == 2 && abs(dx) == 2) continue;
            ivec2 q = p + ivec2(dx, dy);
            if (q.x < 2 || q.y < 2 || q.x >= pc.size.x - 2 || q.y >= pc.size.y - 2) continue;
            if (siteClipped(q)) return 0.0;
        }
    }
    return 1.0;
}

// rt_algo.cc:49 calcBlendFactor.
float blendFactor(float val, float threshold) {
    float x = -16.0 + (16.0 / threshold) * val;
    return 0.5 * (1.0 + x * inversesqrt(1.0 + x * x));
}

// Gradient magnitude used by buildBlendMask and calcContrastThreshold.
float contrastAt(ivec2 p) {
    float a = lumL(p + ivec2(1, 0)) - lumL(p - ivec2(1, 0));
    float b = lumL(p + ivec2(0, 1)) - lumL(p - ivec2(0, 1));
    float c = lumL(p + ivec2(2, 0)) - lumL(p - ivec2(2, 0));
    float d = lumL(p + ivec2(0, 2)) - lumL(p - ivec2(0, 2));
    return sqrt(a * a + b * b + c * c + d * d) * (0.0625 / 327.68);
}
