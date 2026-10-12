#import amaze
// Wavelet tail: pre-WB camera-linear RGB (post wavelet denoise) to ACEScg.
// Applies the same folded camera-to-ACEScg matrix the demosaic finals use,
// so the JPEG develop downstream sees the identical scene-linear frame.
uniform ivec2 u_outsize;      // (W, H)
uniform highp mat3 u_camera_to_acescg;

layout(binding = 0, rgba16f) readonly uniform highp image2D u_camera;
layout(binding = 1, rgba16f) writeonly uniform highp image2D img_out;
void main() {
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x >= u_outsize.x || o.y >= u_outsize.y) return;
    vec3 cameraRgb = imageLoad(u_camera, o).rgb;
    vec3 acescg = u_camera_to_acescg * cameraRgb;
    imageStore(img_out, o, vec4(acescg, 1.0));
}
