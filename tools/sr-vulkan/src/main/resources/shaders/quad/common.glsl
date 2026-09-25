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
