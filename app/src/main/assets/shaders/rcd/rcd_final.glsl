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

layout(binding = 0, rgba16f) writeonly uniform highp image2D img_out;
void main() {
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x >= u_outsize.x || o.y >= u_outsize.y) return;
    vec3 cameraRgb = max(texelFetch(u_rgb, o, 0).rgb, vec3(0.0));
    vec3 whiteBlendRgb = smoothstep(vec3(0.70), vec3(0.99), cameraRgb);
    float whiteBlend = max(whiteBlendRgb.r, max(whiteBlendRgb.g, whiteBlendRgb.b));
    cameraRgb = mix(cameraRgb, u_camera_white_normalized, whiteBlend);
    vec3 acescg = u_camera_to_acescg * cameraRgb;
    imageStore(img_out, o, vec4(acescg, 1.0));
}
