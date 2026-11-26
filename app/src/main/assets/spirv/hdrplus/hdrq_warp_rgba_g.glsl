// Shared body of hdrq_warp_rgba2 (padded float source) and hdrq_warp_rgba_raw
// (RAW16 source, -DRAW). RawLens mobile variants of hdrq_warp_rgba, same floats.
//
// hdr-plus-swift warp_texture_bayer followed by convert_to_rgba, fused: each RGBA
// texel warps the four raw samples of its 2x2 cell. When the cell origin s is even,
// the cell never straddles a half-tile boundary (half tiles are even), so its four
// samples share the four tile displacements (even, 2 * level-0 vector): every corner
// read is one aligned-to-s 2x2 block, i.e. one textureGather instead of four loads.
// Per sample, the weights, the out-of-frame zeros, the blend expression and the
// division are the scalar shader's, so the floats are the same. Odd origins (not
// produced by the current geometry) take the per-sample path; the RAW variant is
// only dispatched for even origins.
//
// RAW: applies hdrp_prepare's mapping (max(code + blackDelta[phase], 0) inside the
// frame, 0 in the padding) on the fly, so align-once passes after the first need no
// full-frame prepare copy.
layout(local_size_x = 16, local_size_y = 16) in;
#ifdef RAW
layout(set = 0, binding = 0) uniform highp usampler2D src;
#else
layout(set = 0, binding = 0) uniform highp sampler2D src;
#endif
layout(set = 0, binding = 1, rgba32f) writeonly uniform image2D rgba;
layout(std430, set = 0, binding = 2) readonly buffer Align { ivec2 v[]; } align;
layout(push_constant) uniform P {
    ivec2 size;        // RGBA extent
    ivec2 crop;        // raw-pixel offset into the padded frame
    ivec2 paddedSize;
    ivec2 tiles;       // level-0 tile grid
    int halfTileSize;  // level-0 tile size == full-res half tile
    ivec2 offset;
    int continuous;
    vec4 blackDelta;   // RAW: reference black - frame black, per raw phase
    ivec2 pad;         // RAW: padding of the prepared frame
    ivec2 rawSize;     // RAW
} p;

#ifdef RAW
// blackDelta[i] from registers (dynamic push-constant indexing costs loads on Mali).
float bdelta(int i) {
    vec4 d = p.blackDelta;
    return i == 0 ? d.x : (i == 1 ? d.y : (i == 2 ? d.z : d.w));
}
#endif

// One padded-frame sample, as hdrp_prepare + hdrq_warp_rgba's sampleComp give it.
float sampleOne(ivec2 s) {
    if (any(lessThan(s, ivec2(0))) || any(greaterThanEqual(s, p.paddedSize))) return 0.0;
#ifdef RAW
    ivec2 r = s - p.pad;
    if (any(lessThan(r, ivec2(0))) || any(greaterThanEqual(r, p.rawSize))) return 0.0;
    return max(float(texelFetch(src, r, 0).x) + bdelta((r.y & 1) * 2 + (r.x & 1)), 0.0);
#else
    return texelFetch(src, s, 0).x;
#endif
}

// The 2x2 block at b (b, b+(1,0), b+(0,1), b+(1,1)), same values as sampleOne.
vec4 sampleBlock(ivec2 b) {
    bvec2 in0 = bvec2(b.x >= 0 && b.x < p.paddedSize.x, b.y >= 0 && b.y < p.paddedSize.y);
    bvec2 in1 = bvec2(b.x + 1 >= 0 && b.x + 1 < p.paddedSize.x, b.y + 1 >= 0 && b.y + 1 < p.paddedSize.y);
#ifdef RAW
    ivec2 r = b - p.pad;
    in0 = bvec2(in0.x && r.x >= 0 && r.x < p.rawSize.x, in0.y && r.y >= 0 && r.y < p.rawSize.y);
    in1 = bvec2(in1.x && r.x + 1 >= 0 && r.x + 1 < p.rawSize.x, in1.y && r.y + 1 >= 0 && r.y + 1 < p.rawSize.y);
    uvec4 g = uvec4(texelFetch(src, r + ivec2(0, 1), 0).x, texelFetch(src, r + ivec2(1, 1), 0).x,
                    texelFetch(src, r + ivec2(1, 0), 0).x, texelFetch(src, r, 0).x);
    int px = r.x & 1, py = r.y & 1;
    vec4 v = vec4(max(float(g.w) + bdelta(py * 2 + px), 0.0),
                  max(float(g.z) + bdelta(py * 2 + (px ^ 1)), 0.0),
                  max(float(g.x) + bdelta((py ^ 1) * 2 + px), 0.0),
                  max(float(g.y) + bdelta((py ^ 1) * 2 + (px ^ 1)), 0.0));
#else
    vec4 g = textureGather(src, (vec2(b) + 1.0) / vec2(textureSize(src, 0)), 0);
    vec4 v = g.wzxy;
#endif
    return vec4(in0.x && in0.y ? v.x : 0.0, in1.x && in0.y ? v.y : 0.0,
                in0.x && in1.y ? v.z : 0.0, in1.x && in1.y ? v.w : 0.0);
}

void corners(ivec2 q, out ivec2 a0, out ivec2 a1, out ivec2 a2, out ivec2 a3) {
    float h = float(p.halfTileSize);
    vec2 grid = (vec2(q) + 0.5) / h - 1.0;
    ivec2 lo = ivec2(max(vec2(0.0), floor(grid)) + 0.1);
    ivec2 hi = ivec2(min(ceil(grid), vec2(p.tiles - 1)) + 0.1);
    a0 = 2 * align.v[lo.y * p.tiles.x + lo.x];
    a1 = 2 * align.v[lo.y * p.tiles.x + hi.x];
    a2 = 2 * align.v[hi.y * p.tiles.x + lo.x];
    a3 = 2 * align.v[hi.y * p.tiles.x + hi.x];
}

float blend(ivec2 q, float s0, float s1, float s2, float s3) {
    float h = float(p.halfTileSize);
    vec2 grid = (vec2(q) + 0.5) / h - 1.0;
    vec2 w = p.continuous != 0 ? clamp(grid - floor(grid), 0.0, 1.0) : (vec2(q % p.halfTileSize) + 0.5) / (2.0 * h);
    float w0 = (1.0 - w.x) * (1.0 - w.y), w1 = w.x * (1.0 - w.y), w2 = (1.0 - w.x) * w.y, w3 = w.x * w.y;
    float v = w0 * s0 + w1 * s1 + w2 * s2 + w3 * s3;
    return v / (w0 + w1 + w2 + w3);
}

float warpedOne(ivec2 q) {
    ivec2 a0, a1, a2, a3;
    corners(q, a0, a1, a2, a3);
    return blend(q, sampleOne(q + a0), sampleOne(q + a1), sampleOne(q + a2), sampleOne(q + a3));
}

void main() {
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(q, p.size))) return;
    ivec2 s = 2 * q + p.crop + p.offset;
    vec4 o;
#ifdef RAW
    // The host selects this variant only for even origins and even half tiles.
    {
#else
    if (((s.x | s.y) & 1) == 0 && (p.halfTileSize & 1) == 0) {
#endif
        ivec2 a0, a1, a2, a3;
        corners(s, a0, a1, a2, a3);
        vec4 b0 = sampleBlock(s + a0), b1 = sampleBlock(s + a1), b2 = sampleBlock(s + a2), b3 = sampleBlock(s + a3);
        o = vec4(blend(s, b0.x, b1.x, b2.x, b3.x), blend(s + ivec2(1, 0), b0.y, b1.y, b2.y, b3.y),
                 blend(s + ivec2(0, 1), b0.z, b1.z, b2.z, b3.z), blend(s + ivec2(1, 1), b0.w, b1.w, b2.w, b3.w));
    }
#ifndef RAW
    else {
        o = vec4(warpedOne(s), warpedOne(s + ivec2(1, 0)), warpedOne(s + ivec2(0, 1)), warpedOne(s + ivec2(1, 1)));
    }
#endif
    imageStore(rgba, q, o);
}
