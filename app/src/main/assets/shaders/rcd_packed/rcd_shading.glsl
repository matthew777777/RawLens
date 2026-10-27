// SPDX-License-Identifier: GPL-3.0-or-later
// Camera2 R/Gr/Gb/B gains sampled in full sensor coordinates, pre-WB.
layout(binding = 4) uniform sampler2D shadeMap;
vec3 applyLensShading(vec3 rgb, ivec2 source, ivec4 dims, vec4 activeArea) {
    if (dims.z == 0) return rgb;
    ivec2 sensor = source + p.u_origin;
    vec2 normalized = clamp((vec2(sensor) - activeArea.xy) * activeArea.zw, 0.0, 1.0);
    vec2 mapPosition = normalized * vec2(dims.y - 1, dims.x - 1);
    vec2 uv = (mapPosition + 0.5) / vec2(dims.y, dims.x);
    vec4 gains = texture(shadeMap, uv);
    return rgb * vec3(gains.r, 0.5 * (gains.g + gains.b), gains.a);
}
