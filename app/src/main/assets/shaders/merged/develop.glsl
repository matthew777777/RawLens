#version 310 es
// Prompt 5A: direct merged-camera-RGB development. Consumes the live merged
// linear camera-RGB texture (reference fallback already applied by the merge)
// plus the half-resolution accumulated-robustness Rc texture, and writes
// scene-linear working-space (ACEScg) output cropped to u_crop_size.
//
// This path never sees Bayer data: no demosaic, no RAW preprocessing. The
// confidence-weighted spatial denoise below runs only on this JPEG path, only
// after reference fallback, and never touches prime DNG pixels.
precision highp float;
precision highp int;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
// Merged linear camera RGB, post-fallback (rgba32f, full burst frame).
layout(rgba32f, binding = 0) uniform readonly highp image2D u_merged;
// Accumulated per-quad robustness Rc, i.e. effective frame support (r32f,
// exactly half the merged resolution: quad (x, y) covers merged 2x2 block).
layout(r32f, binding = 1) uniform readonly highp image2D u_rc;
// Scene-linear ACEScg output (rgba16f, u_crop_size).
layout(rgba16f, binding = 2) uniform writeonly highp image2D u_out;
uniform ivec2 u_crop_offset;
uniform ivec2 u_crop_size;
uniform ivec2 u_merged_size;
// GLSL column-major camera-to-ACEScg matrix resolved from the REFERENCE
// frame metadata (SceneLinearColorProcessor.resolve). No per-frame matrices.
uniform highp mat3 u_camera_to_acescg;
// Camera-space neutral white. Highlight neutralization MUST stay before the
// color matrix (mirrors amaze/final.glsl): WB-amplified clipped channels
// cannot be un-magentad afterwards.
uniform highp vec3 u_camera_white_normalized;
// 0 = pure transform path; otherwise the ceiling of the spatial blend.
// The per-pixel blend is u_denoise_blend_max * (1 - confidence).
uniform highp float u_denoise_blend_max;
// Accepted frame count; confidence = clamp(rc / u_rc_normalizer, 0, 1).
uniform highp float u_rc_normalizer;
uniform int u_denoise_enabled;

vec3 loadMerged(ivec2 q) {
    return imageLoad(u_merged, clamp(q, ivec2(0), u_merged_size - 1)).rgb;
}

float confidence(ivec2 q) {
    ivec2 quad = clamp(q / 2, ivec2(0), u_merged_size / 2 - 1);
    float rc = imageLoad(u_rc, quad).r;
    if (isnan(rc) || isinf(rc) || rc <= 0.0) return 0.0;
    return clamp(rc / u_rc_normalizer, 0.0, 1.0);
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_crop_size))) return;
    ivec2 q = p + u_crop_offset;
    vec3 center = max(loadMerged(q), vec3(0.0));
    vec3 base = center;
    if (u_denoise_enabled != 0) {
        // 3x3 kernel with weights 4/2/1 (/16). Replicated edges via clamp,
        // exactly like the CPU mirror (MergedDevelopWeights.developPixel).
        vec3 acc = 4.0 * center
            + 2.0 * (loadMerged(q + ivec2(1, 0)) + loadMerged(q + ivec2(-1, 0))
                + loadMerged(q + ivec2(0, 1)) + loadMerged(q + ivec2(0, -1)))
            + (loadMerged(q + ivec2(1, 1)) + loadMerged(q + ivec2(1, -1))
                + loadMerged(q + ivec2(-1, 1)) + loadMerged(q + ivec2(-1, -1)));
        vec3 avg = max(acc / 16.0, vec3(0.0));
        float blend = clamp(u_denoise_blend_max * (1.0 - confidence(q)), 0.0, 1.0);
        base = mix(center, avg, blend);
    }
    vec3 wb = smoothstep(vec3(0.70), vec3(0.99), base);
    float whiteBlend = max(wb.r, max(wb.g, wb.b));
    vec3 cameraRgb = mix(base, u_camera_white_normalized, whiteBlend);
    vec3 acescg = u_camera_to_acescg * cameraRgb;
    imageStore(u_out, p, vec4(acescg, 1.0));
}
