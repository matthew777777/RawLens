#version 450
uniform highp uvec3 u_dispatch_offset;
// SPDX-License-Identifier: GPL-3.0-or-later
// Jamy-L Alg. 7 guide image (`robustness.py::compute_guide_image`): per-quad
// sqrt(R), sqrt(mean of the two greens), sqrt(B) from raw codes in the
// unshaded normalized domain. Channels follow the sensor CFA color per
// sample (any Bayer pattern/origin). The .a lane is reserved 0 (binding
// stability with the RGBA32F guide texture). Consumed only by the
// robustness pass. Mirrors RawSrRobustness.linearGuide exactly.
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform highp usampler2D u_raw;
uniform ivec2 u_size;
uniform ivec4 u_fc;
uniform vec4 u_black;
uniform float u_white;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_linear;
int colorOf(int phase) {
    return phase == 0 ? u_fc.x : phase == 1 ? u_fc.y : phase == 2 ? u_fc.z : u_fc.w;
}
float blackOf(int phase) {
    return phase == 0 ? u_black.x : phase == 1 ? u_black.y : phase == 2 ? u_black.z : u_black.w;
}
void main() {
    ivec2 q = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (any(greaterThanEqual(q, u_size))) return;
    ivec2 p = q * 2;
    float r = 0.0;
    float g = 0.0;
    float b = 0.0;
    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            ivec2 t = p + ivec2(j, i);
            int phase = ((t.y & 1) << 1) | (t.x & 1);
            float black = blackOf(phase);
            float code = float(texelFetch(u_raw, t, 0).r);
            float value = (code - black) / (u_white - black);
            int color = colorOf(phase);
            if (color == 0) r = value;
            else if (color == 2) b = value;
            else g += 0.5 * value;
        }
    }
    imageStore(img_linear, q, vec4(sqrt(max(r, 0.0)), sqrt(max(g, 0.0)), sqrt(max(b, 0.0)), 0.0));
}
