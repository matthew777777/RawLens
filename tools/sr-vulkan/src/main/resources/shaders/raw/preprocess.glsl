// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Direct RAW_SENSOR preparation. Integer sensor codes are normalized and lens-shading corrected
// into the unclamped R32F CFA consumed by AMaZE. CFA vectors use local top-left row-major order.
precision highp float;
precision highp int;
precision highp usampler2D;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

uniform highp usampler2D u_raw;
uniform sampler2D u_lens;
uniform ivec2 u_size;
uniform ivec2 u_sensor_origin;
uniform ivec4 u_fc;
uniform vec4 u_black;
uniform float u_white;
uniform ivec2 u_lens_size;
uniform ivec4 u_active;
uniform int u_apply_lens;
layout(binding = 0, r32f) writeonly uniform highp image2D img_out;

int phaseIndex(ivec2 p) {
    return ((p.y & 1) << 1) | (p.x & 1);
}

int colorAt(ivec2 p) {
    int phase = phaseIndex(p);
    return phase == 0 ? u_fc.x : phase == 1 ? u_fc.y : phase == 2 ? u_fc.z : u_fc.w;
}

float lensGain(ivec2 p) {
    if (u_apply_lens == 0) return 1.0;
    ivec2 sensor = u_sensor_origin + p;
    int activeWidth = u_active.z - u_active.x;
    int activeHeight = u_active.w - u_active.y;
    float nx = activeWidth == 1 ? 0.0
        : float(sensor.x - u_active.x) / float(activeWidth - 1);
    float ny = activeHeight == 1 ? 0.0
        : float(sensor.y - u_active.y) / float(activeHeight - 1);
    vec2 normalized = clamp(vec2(nx, ny), 0.0, 1.0);
    vec2 mapPosition = normalized * vec2(u_lens_size - ivec2(1));
    ivec2 p0 = ivec2(floor(mapPosition));
    ivec2 p1 = min(p0 + ivec2(1), u_lens_size - ivec2(1));
    vec2 t = mapPosition - vec2(p0);
    vec4 top = mix(texelFetch(u_lens, ivec2(p0.x, p0.y), 0),
                   texelFetch(u_lens, ivec2(p1.x, p0.y), 0), t.x);
    vec4 bottom = mix(texelFetch(u_lens, ivec2(p0.x, p1.y), 0),
                      texelFetch(u_lens, ivec2(p1.x, p1.y), 0), t.x);
    vec4 gains = mix(top, bottom, t.y);
    int color = colorAt(p);
    int channel = color == 0 ? 0 : color == 2 ? 3 : ((sensor.y & 1) == 0 ? 1 : 2);
    return gains[channel];
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    int phase = phaseIndex(p);
    float black = phase == 0 ? u_black.x : phase == 1 ? u_black.y
                                               : phase == 2 ? u_black.z : u_black.w;
    float code = float(texelFetch(u_raw, p, 0).r);
    float normalized = (code - black) / (u_white - black);
    imageStore(img_out, p, vec4(normalized * lensGain(p)));
}
