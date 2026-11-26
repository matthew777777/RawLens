#import amaze
// AMaZE pass 15: final RGB assembly (C++ lines 1520-1577), with the tile
// skirt crop folded in: the dispatch covers the tile's interior (unpadded
// coords) while all texture reads are addressed in padded window coordinates
// (output pixel o reads window texel o + u_inner = o + PAD + BORDER), so the
// demosaiced skirt never reaches the output; stores land at o + u_outoff in
// the full-size output image.
uniform sampler2D u_chroma;   // D0', D1', rgbgreen
uniform sampler2D u_hvwt;     // firmed hvwt
uniform ivec2 u_outsize;      // tile interior size (tw, th)
uniform ivec2 u_outoff;       // tile origin in the output image
uniform ivec2 u_inner;        // interior origin in window coords (PAD + BORDER)
// GLSL column-vector matrix. Fusing this transform into AMaZE's final write avoids an
// illegal read-write rgba16f image and a second full-resolution RGBA16F allocation.
uniform highp mat3 u_camera_to_acescg;
// Camera-space neutral white (AsShotNeutral / max component). Highlight neutralization MUST
// happen before white balance / color conversion to prevent clipped-channel magenta casts.
uniform highp vec3 u_camera_white_normalized;
// White-blend window in pre-matrix camera RGB (see final_display.glsl).
// The highlight-reconstruction tap passes WHITE_WINDOW_OFF here (edges far
// above any pixel, blend stays 0): reconstruction must see true channel
// values and owns clipping itself, like RT's pre-desaturation recovery.
uniform highp vec2 u_white_window;
// Wavelet path: emit pre-matrix camera RGB (white-blend still applied, like
// darktable's post-highlight-reconstruction denoise input) instead of ACEScg.
uniform int u_camera_linear;
// Inverse of the executor's demosaic pre-balance D (balanced CFA basis back
// to true camera RGB). Also used to detect sensor-domain clipping.
uniform highp vec3 u_demosaic_unbalance;

// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
float hv(ivec2 q) { return texelFetch(u_hvwt, ivec2(q.x >> 1, q.y), 0).r; }
vec2 D01(ivec2 q) { return texelFetch(u_chroma, ivec2(q.x & ~1, q.y), 0).rg; }

// AMaZE pass-15 RGB at window texel p (negatives clipped like RawTherapee).
highp vec3 amaze_rgb(ivec2 p) {
    float g = texelFetch(u_chroma, p, 0).b;
    vec2 d;   // G - R, G - B
    if (isG(p.y, p.x)) {
        float wu = hv(p + ivec2(0, -1));
        float wd = hv(p + ivec2(0, 1));
        float wl = hv(p + ivec2(-1, 0));
        float wr = hv(p + ivec2(1, 0));
        float temp = 1.0 / (wu + 2.0 - wr - wl + wd);
        d = (wu * D01(p + ivec2(0, -1)) + (1.0 - wr) * D01(p + ivec2(1, 0))
             + (1.0 - wl) * D01(p + ivec2(-1, 0)) + wd * D01(p + ivec2(0, 1))) * temp;
    } else {
        d = D01(p);
    }
    return max(vec3(g - d.x, g, g - d.y), vec3(0.0));
}

// Balanced RGB of the workgroup block + CR ring, for the chroma cleanup.
const int CR = 2;
const int CW = LW + 2 * CR;
const int CN = CW * (LH + 2 * CR);
shared highp vec3 s_rgb[CN];
highp vec3 cc_white() { return max(u_camera_white_normalized, vec3(1e-4)); }
highp vec3 fetch_bal(ivec2 off) {
    ivec2 l = ivec2(gl_LocalInvocationID.xy) + CR + off;
    return s_rgb[l.y * CW + l.x];
}
#import cleanup
void stage_rgb() {
    if (u_fc_median == 0 && u_defringe <= 0.0) return;   // the cleanup is off: nothing reads s_rgb
    ivec2 c0 = ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) + u_inner - CR;
    highp vec3 inv = 1.0 / cc_white();
    for (int i = int(gl_LocalInvocationIndex); i < CN; i += LW * LH)
        s_rgb[i] = amaze_rgb(c0 + ivec2(i % CW, i / CW)) * inv;
}
#import luma

layout(binding = 0, rgba16f) writeonly uniform highp image2D img_out;
void main() {
    stage_rgb();
    memoryBarrierShared();
    barrier();
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x >= u_outsize.x || o.y >= u_outsize.y) return;
    ivec2 p = o + u_inner;
    // RawTherapee clips negative AMaZE reconstruction excursions here. Otherwise a
    // camera matrix can turn one channel's edge undershoot into a complementary fringe.
    highp vec3 cameraRgb = amaze_rgb(p) * (1.0 / cc_white());
    if (u_fc_median != 0 || u_defringe > 0.0) cameraRgb = chroma_cleanup(fetch_bal(ivec2(0)));
    cameraRgb *= cc_white();

    // Sensor-domain white-point protection. As any demosaiced channel approaches RAW clipping,
    // progressively remove chroma by converging on the camera's measured neutral white. This is
    // deliberately before WB/calibration/color matrices: doing it afterwards cannot undo the
    // magenta generated when a clipped channel is amplified by white balance.
    // AMaZE works in an attenuated WB-balanced basis. Detect saturation in
    // sensor units: green can clip while every balanced channel is below 0.70.
    highp vec3 sensorRgb = cameraRgb * u_demosaic_unbalance;
    highp vec3 whiteBlendRgb = smoothstep(vec3(u_white_window.x), vec3(u_white_window.y), sensorRgb);
    highp float whiteBlend = max(whiteBlendRgb.r, max(whiteBlendRgb.g, whiteBlendRgb.b));
    cameraRgb = mix(cameraRgb, u_camera_white_normalized, whiteBlend);

    if (u_camera_linear != 0) {
        imageStore(img_out, o + u_outoff, vec4(cameraRgb * u_demosaic_unbalance, 1.0));
        return;
    }
    cameraRgb += denoised_luma(o + u_outoff) / u_demosaic_unbalance;
    highp vec3 acescg = u_camera_to_acescg * cameraRgb;
    imageStore(img_out, o + u_outoff, vec4(acescg, 1.0));
}
