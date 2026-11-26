// The RAW denoiser's fine luma (grain and detail it took out of the CFA) as camera RGB before white
// balance, lens-shaded here like the CFA was. Added after the demosaic, highlight blend and cleanup.
uniform highp sampler2D u_luma;
uniform highp sampler2D u_lens;      // R, G even row, G odd row, B
uniform int u_apply_luma;
uniform int u_apply_lens;
uniform highp vec3 u_inv_wb;
uniform ivec2 u_sensor_origin;
uniform ivec4 u_active;
highp vec3 luma_lens_gain(ivec2 p) {
    if (u_apply_lens == 0) return vec3(1.0);
    ivec2 cells = textureSize(u_lens, 0) - 1;
    highp vec2 span = vec2(max(u_active.zw - u_active.xy - 1, ivec2(1)));
    highp vec2 at = clamp(vec2(u_sensor_origin + p - u_active.xy) / span, 0.0, 1.0) * vec2(cells);
    ivec2 p0 = ivec2(at), p1 = min(p0 + 1, cells);
    highp vec2 t = at - vec2(p0);
    highp vec4 g = mix(mix(texelFetch(u_lens, p0, 0), texelFetch(u_lens, ivec2(p1.x, p0.y), 0), t.x),
                       mix(texelFetch(u_lens, ivec2(p0.x, p1.y), 0), texelFetch(u_lens, p1, 0), t.x), t.y);
    return vec3(g.x, 0.5 * (g.y + g.z), g.w);
}
highp vec3 denoised_luma(ivec2 p) {
    if (u_apply_luma == 0) return vec3(0.0);
    return texelFetch(u_luma, p, 0).r * u_inv_wb * luma_lens_gain(p);
}
