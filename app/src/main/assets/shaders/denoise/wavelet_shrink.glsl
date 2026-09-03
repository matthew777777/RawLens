#version 310 es
precision highp float;
precision highp int;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 0) uniform highp sampler2D u_detail;
layout(binding = 1) uniform highp sampler2D u_coarse;
layout(rgba32f, binding = 0) uniform coherent highp image2D u_accum;
uniform ivec2 u_size;
uniform vec3 u_noise_s;
uniform vec3 u_noise_o;
uniform float u_strength;
uniform int u_scale;

ivec2 clampP(ivec2 p) { return clamp(p, ivec2(0), u_size - 1); }
float shrink(float x, float t) { return sign(x) * max(abs(x) - t, 0.0); }

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    vec3 d = texelFetch(u_detail, p, 0).rgb;
    vec3 coarse = texelFetch(u_coarse, p, 0).rgb;

    // Local BayesShrink estimate. darktable uses the same 5-tap a-trous variance
    // progression; the chroma-only preset leaves Y0 force at zero and U0/V0 at 0.5.
    vec2 mean2 = vec2(0.0);
    for (int yy=-1; yy<=1; ++yy) for (int xx=-1; xx<=1; ++xx) {
        vec2 q = texelFetch(u_detail, clampP(p + ivec2(xx,yy)), 0).yz;
        mean2 += q*q;
    }
    mean2 /= 9.0;

    const float varf = 0.5229125; // sqrt(2 + 2*4^2 + 6^2) / 16
    float band = pow(varf, float(u_scale));
    float y = max(coarse.x, 0.0);
    vec2 baseVar = max(vec2(u_noise_s.y, u_noise_s.z) * y + vec2(u_noise_o.y, u_noise_o.z), vec2(1e-12));
    vec2 noiseVar = baseVar * (band * band) * max(u_strength*u_strength, 1e-6);
    vec2 signalStd = sqrt(max(mean2 - noiseVar, vec2(1e-10)));
    // darktable chroma-only curve is 0.5 at every band. Its threshold adjustment
    // resolves to 8x before BayesShrink; retain that calibration here.
    vec2 threshold = 8.0 * noiseVar / signalStd;

    vec3 kept = vec3(d.x, shrink(d.y, threshold.x), shrink(d.z, threshold.y));
    vec4 a = imageLoad(u_accum, p);
    imageStore(u_accum, p, vec4(a.rgb + kept, 1.0));
}
