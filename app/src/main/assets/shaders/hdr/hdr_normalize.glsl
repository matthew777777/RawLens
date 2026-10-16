// SPDX-License-Identifier: GPL-3.0-or-later
// HDR bracket normalize: MergeSession.finish twin. One invocation per
// full-res pixel: pix = max(0, pix / (wt * white)) wherever wt > 0,
// otherwise the accumulator value passes through (zero init, like the CPU
// path whose untouched pixels stay 0).
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_accPix;
uniform sampler2D u_accWt;
uniform ivec2 u_size;
uniform float u_white;
layout(binding = 0, r32f) writeonly uniform highp image2D img_out;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    float pix = texelFetch(u_accPix, p, 0).r;
    float wt = texelFetch(u_accWt, p, 0).r;
    float v = pix;
    if (wt > 0.0) {
        v = max(0.0, pix / (wt * u_white));
    }
    imageStore(img_out, p, vec4(v));
}
