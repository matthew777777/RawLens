// SPDX-License-Identifier: GPL-3.0-or-later
// Prompt 4C linear guide: per-quad R/G/B from raw codes in the unshaded
// normalized domain, plus a rail mask. Channels follow the sensor CFA color per
// sample (any Bayer pattern/origin); G is the mean of the quad's two greens.
// Rail uses the code-domain per-phase noise model with the documented 3-sigma
// gate. Consumed only by the robustness pass.
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
uniform vec4 u_slope;
uniform vec4 u_offset;
uniform int u_model_valid;
layout(binding = 0, rgba32f) writeonly uniform highp image2D img_linear;
int colorOf(int phase) {
    return phase == 0 ? u_fc.x : phase == 1 ? u_fc.y : phase == 2 ? u_fc.z : u_fc.w;
}
float blackOf(int phase) {
    return phase == 0 ? u_black.x : phase == 1 ? u_black.y : phase == 2 ? u_black.z : u_black.w;
}
void sampleInfo(ivec2 p, out float value, out int color, out float rail) {
    int phase = ((p.y & 1) << 1) | (p.x & 1);
    float black = blackOf(phase);
    float code = float(texelFetch(u_raw, p, 0).r);
    value = (code - black) / (u_white - black);
    color = colorOf(phase);
    rail = 0.0;
    if (u_model_valid != 0) {
        float s = phase == 0 ? u_slope.x : phase == 1 ? u_slope.y : phase == 2 ? u_slope.z : u_slope.w;
        float o = phase == 0 ? u_offset.x : phase == 1 ? u_offset.y : phase == 2 ? u_offset.z : u_offset.w;
        float sigma = sqrt(max(s * code + o, 0.0));
        // Highlight-side rail only (mirrors the oracle): no shadow rail —
        // near-black taps are read-noise-limited data, not defects.
        if (code > u_white - 3.0 * sigma) rail = 1.0;
    }
}
void main() {
    ivec2 q = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(q, u_size))) return;
    ivec2 p = q * 2;
    float r = 0.0;
    float g = 0.0;
    float b = 0.0;
    float rail = 0.0;
    for (int i = 0; i < 2; i++) {
        for (int j = 0; j < 2; j++) {
            float value;
            int color;
            float sampleRail;
            sampleInfo(p + ivec2(j, i), value, color, sampleRail);
            if (color == 0) r = value;
            else if (color == 2) b = value;
            else g += 0.5 * value;
            rail = max(rail, sampleRail);
        }
    }
    imageStore(img_linear, q, vec4(r, g, b, rail));
}
