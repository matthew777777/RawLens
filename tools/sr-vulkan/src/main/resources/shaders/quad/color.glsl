// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PhotonCamera demosaicp2quad: green-guided color ratios with
// a linear fallback near black/clipping. Generalized to every Bayer order,
// sensor crop phase and defined color-preserving border reads.
#import quad
uniform sampler2D u_green;
uniform mat3 u_camera_to_acescg;
uniform vec3 u_camera_white_normalized;
layout(binding = 0, rgba16f) writeonly uniform highp image2D img_out;
float green(ivec2 p) { return texelFetch(u_green, bounded(p), 0).r; }
bool ratioSafe(float g) { return g >= 0.01 && g <= 0.99; }
float interpolate(ivec2 p, int target) {
    if (colorAt(p) == target) return raw(p);
    ivec2 shift = shiftAt(p);
    ivec2 lo = -1 - shift;
    ivec2 hi = 2 - shift;
    ivec2 taps[4];
    int count;
    if (colorAt(p + ivec2(lo.x, 0)) == target) {
        count = 2;
        taps[0] = p + ivec2(lo.x, 0); taps[1] = p + ivec2(hi.x, 0);
    } else if (colorAt(p + ivec2(0, lo.y)) == target) {
        count = 2;
        taps[0] = p + ivec2(0, lo.y); taps[1] = p + ivec2(0, hi.y);
    } else {
        count = 4;
        taps[0] = p + lo; taps[1] = p + ivec2(hi.x, lo.y);
        taps[2] = p + ivec2(lo.x, hi.y); taps[3] = p + hi;
    }
    float g = green(p);
    bool useRatio = ratioSafe(g);
    float average = 0.0, ratio = 0.0;
    for (int i = 0; i < count; ++i) {
        float tapGreen = green(taps[i]);
        float tapRaw = raw(taps[i]);
        useRatio = useRatio && ratioSafe(tapGreen);
        average += tapRaw;
        ratio += tapRaw / max(tapGreen, 0.000001);
    }
    return (useRatio ? g * ratio : average) / float(count);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    vec3 cameraRgb = max(vec3(interpolate(p, 0), green(p), interpolate(p, 2)), vec3(0));
    vec3 blend = smoothstep(vec3(0.70), vec3(0.99), cameraRgb);
    cameraRgb = mix(cameraRgb, u_camera_white_normalized, max(blend.r, max(blend.g, blend.b)));
    imageStore(img_out, p, vec4(u_camera_to_acescg * cameraRgb, 1.0));
}
