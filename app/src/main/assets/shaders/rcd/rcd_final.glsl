#import amaze
// RCD-to-ACEScg tail: the Vulkan RCD modes output camera-native RGB;
// this pass applies the AMaZE final-stage color contract (negative
// clip, sensor-white desaturation, camera-to-ACEScg) so the JPEG
// develop downstream sees the same scene-linear frame either backend
// produces. Reads the EGL-imported RGB (full-res, unpadded) and stores
// RGBA16F scene-linear.
uniform sampler2D u_rgb;      // RCD camera-native RGB (EGL import)
uniform ivec2 u_outsize;      // (W, H), full-res, no skirt
uniform highp mat3 u_camera_to_acescg;
uniform highp vec3 u_camera_white_normalized;
// Wavelet path: emit pre-matrix camera RGB instead of ACEScg.
uniform int u_camera_linear;
uniform highp vec2 u_white_window;

highp vec3 cc_white() { return max(u_camera_white_normalized, vec3(1e-4)); }
// Balanced RGB for the chroma cleanup (texture cache serves the 3x3 / 5x5 taps).
highp vec3 fetch_bal(ivec2 off) {
    ivec2 q = clamp(ivec2(gl_GlobalInvocationID.xy) + off, ivec2(0), u_outsize - 1);
    return max(texelFetch(u_rgb, q, 0).rgb, vec3(0.0)) / cc_white();
}
#import cleanup

layout(binding = 0, rgba16f) writeonly uniform highp image2D img_out;
void main() {
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x >= u_outsize.x || o.y >= u_outsize.y) return;
    vec3 cameraRgb = chroma_cleanup(fetch_bal(ivec2(0))) * cc_white();
    vec3 whiteBlendRgb = smoothstep(vec3(u_white_window.x), vec3(u_white_window.y), cameraRgb);
    float whiteBlend = max(whiteBlendRgb.r, max(whiteBlendRgb.g, whiteBlendRgb.b));
    if (u_camera_linear == 0) cameraRgb = mix(cameraRgb, u_camera_white_normalized, whiteBlend);
    vec3 acescg = u_camera_to_acescg * cameraRgb;
    vec3 outRgb = (u_camera_linear != 0) ? cameraRgb : acescg;
    imageStore(img_out, o, vec4(outRgb, 1.0));
}
