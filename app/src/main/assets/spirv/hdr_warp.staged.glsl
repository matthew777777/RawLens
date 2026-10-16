#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// HDR bracket warp: HdrRawMerge.sampleSmoothAt twin (same-colour bilinear).
// One invocation per full-res CFA pixel. Resamples the normalized moving CFA
// at reference coordinates plus a dense full-res flow (RG32F texture, RAW px;
// translation pre-align + FlowNet are combined on the host into one field,
// exactly like HdrBracketAligner.refinedFlow). Only taps carrying this
// pixel's own Bayer colour contribute, so R/G/B never mix and sub-pixel flow
// stays smooth with no 2px stair. Non-finite flow falls back to zero
// displacement (the host validates FlowNet output; the shader must never
// propagate NaN into the merge).
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_cfa;
uniform sampler2D u_flow;
uniform ivec2 u_size;
layout(binding = 0, r32f) writeonly uniform highp image2D img_warped;
bool finite(float x) { return !isnan(x) && !isinf(x); }
void main() {
    ivec2 p = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(p, u_size))) return;
    vec2 f = texelFetch(u_flow, p, 0).rg;
    float dx = finite(f.x) ? f.x : 0.0;
    float dy = finite(f.y) ? f.y : 0.0;
    float px = clamp(float(p.x) + dx, 0.0, float(u_size.x - 1));
    float py = clamp(float(p.y) + dy, 0.0, float(u_size.y - 1));
    int pxParity = p.x & 1;
    int pyParity = p.y & 1;
    int x0 = int(px);
    if ((x0 & 1) != pxParity) x0 -= 1;
    int y0 = int(py);
    if ((y0 & 1) != pyParity) y0 -= 1;
    x0 = clamp(x0, pxParity, u_size.x - 2 + pxParity);
    y0 = clamp(y0, pyParity, u_size.y - 2 + pyParity);
    int x1 = min(x0 + 2, u_size.x - 1 - ((u_size.x - 1 - pxParity) & 1));
    int y1 = min(y0 + 2, u_size.y - 1 - ((u_size.y - 1 - pyParity) & 1));
    float fx = clamp((px - float(x0)) / 2.0, 0.0, 1.0);
    float fy = clamp((py - float(y0)) / 2.0, 0.0, 1.0);
    float a = texelFetch(u_cfa, ivec2(x0, y0), 0).r;
    float b = texelFetch(u_cfa, ivec2(x1, y0), 0).r;
    float c = texelFetch(u_cfa, ivec2(x0, y1), 0).r;
    float d = texelFetch(u_cfa, ivec2(x1, y1), 0).r;
    float v = (a * (1.0 - fx) + b * fx) * (1.0 - fy) + (c * (1.0 - fx) + d * fx) * fy;
    imageStore(img_warped, p, vec4(v));
}
