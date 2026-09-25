#version 450
// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PhotonCamera demosaicp0quad/demosaicp12quad: directional
// within-group gradients guide green interpolation at every original photosite.
// SPDX-License-Identifier: GPL-3.0-or-later
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_raw;
uniform ivec2 u_size;
uniform ivec2 u_origin;
uniform ivec4 u_fc;
uniform vec3 u_balance;

// Extend by the 4x4 CFA period. Plain clamping could substitute another color.
ivec2 bounded(ivec2 p) {
    ivec2 phase = ((p % 4) + 4) % 4;
    return clamp(p, phase, u_size - 1 - ((u_size - 1 - phase) % 4));
}
int colorAt(ivec2 p) {
    ivec2 group = (bounded(p) + u_origin) / 2;
    return u_fc[((group.y & 1) << 1) | (group.x & 1)];
}
float raw(ivec2 p) {
    return texelFetch(u_raw, bounded(p), 0).r * u_balance[colorAt(p)];
}
ivec2 shiftAt(ivec2 p) { return (p + u_origin) & 1; }
// Within-group differences, corresponding to PhotonCamera demosaicp0quad.
vec2 gradient(ivec2 p) {
    ivec2 shift = shiftAt(p);
    return 0.5 * (vec2(raw(p + ivec2(1 - 2 * shift.x, 0)),
                       raw(p + ivec2(0, 1 - 2 * shift.y))) - raw(p));
}

layout(binding = 0, r32f) writeonly uniform highp image2D img_out;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    if (colorAt(p) == 1) { imageStore(img_out, p, vec4(raw(p))); return; }
    ivec2 shift = shiftAt(p);
    vec4 g = vec4(raw(p + ivec2(0, -1 - shift.y)),
                  raw(p + ivec2(-1 - shift.x, 0)),
                  raw(p + ivec2(2 - shift.x, 0)),
                  raw(p + ivec2(0, 2 - shift.y)));
    vec2 center = gradient(p);
    vec4 energy = vec4(center.y, center.x, center.x, center.y);
    energy *= energy;
    for (int i = 1; i <= 2; ++i) {
        vec4 t = vec4(gradient(p + ivec2(0, -i)).y,
                      gradient(p + ivec2(-i, 0)).x,
                      gradient(p + ivec2(i, 0)).x,
                      gradient(p + ivec2(0, i)).y);
        energy += t * t;
    }
    vec4 weight = inversesqrt(energy + vec4(0.0001));
    vec2 hv = vec2(0.0001);
    for (int y = -2; y <= 2; ++y) for (int x = -2; x <= 2; ++x) {
        float gaussian = exp(-0.5 * float(x*x + y*y) / (1.5*1.5));
        hv += abs(gradient(p + ivec2(x, y))) * gaussian;
    }
    float green = hv.y > hv.x
        ? dot(g.yz, weight.yz) / (weight.y + weight.z)
        : dot(g.xw, weight.xw) / (weight.x + weight.w);
    imageStore(img_out, p, vec4(green));
}
