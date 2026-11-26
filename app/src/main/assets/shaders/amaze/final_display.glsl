#import amaze
#import agx_output
// AMaZE final RGB assembly fused with AgX, final gamut mapping, sRGB encoding and UHDR gain map.
uniform sampler2D u_chroma;
uniform sampler2D u_hvwt;
uniform ivec2 u_outsize;
uniform ivec2 u_outoff;
uniform ivec2 u_inner;
uniform ivec2 u_output_size;
uniform highp mat3 u_camera_to_acescg;
// Camera-space neutral white (AsShotNeutral / max component). Highlight neutralization MUST
// happen before white balance / color conversion to prevent clipped-channel magenta casts.
uniform highp vec3 u_camera_white_normalized;
// White-blend window in pre-matrix camera RGB: single frames use 0.70/0.99
// (near-white is clipped), HDR merges use 0.995/1.0 (only the all-frames-
// clipped fallback is achromatic; valid short-frame highlights keep color).
uniform highp vec2 u_white_window;
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

layout(binding = 0, rgba8) writeonly uniform highp image2D img_out;
layout(binding = 1, rgba8) writeonly uniform highp image2D img_gainmap;

void main() {
    stage_rgb();
    memoryBarrierShared();
    barrier();
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x >= u_outsize.x || o.y >= u_outsize.y) return;
    ivec2 p = o + u_inner;
    ivec2 outputPixel = o + u_outoff;
    // Match RawTherapee's AMaZE output boundary before applying the camera matrix.
    highp vec3 cameraRgb = amaze_rgb(p) * (1.0 / cc_white());
    if (u_fc_median != 0 || u_defringe > 0.0) cameraRgb = chroma_cleanup(fetch_bal(ivec2(0)));
    cameraRgb *= cc_white();

    // Sensor-domain white-point protection, identical to the non-fused path. Detect clipping
    // from 70%..99% in demosaiced camera RGB and blend toward camera-space neutral white before
    // WB/calibration/color conversion. This turns clipped magenta highlights into neutral white.
    // AMaZE works in an attenuated WB-balanced basis. Detect saturation in
    // sensor units: green can clip while every balanced channel is below 0.70.
    highp vec3 sensorRgb = cameraRgb * u_demosaic_unbalance;
    highp vec3 whiteBlendRgb = smoothstep(vec3(u_white_window.x), vec3(u_white_window.y), sensorRgb);
    highp float whiteBlend = max(whiteBlendRgb.r, max(whiteBlendRgb.g, whiteBlendRgb.b));
    cameraRgb = mix(cameraRgb, u_camera_white_normalized, whiteBlend);

    cameraRgb += denoised_luma(outputPixel) / u_demosaic_unbalance;
    highp vec3 sceneAcescg = u_camera_to_acescg * cameraRgb;
    highp vec3 balancedAcescg = camera_tone_balance(sceneAcescg);
    highp vec3 balancedRec2020 = ACESCG_TO_REC2020_D65 * balancedAcescg;
    highp mat3 outputMatrix = u_display_p3 != 0 ? REC2020_TO_DISPLAY_P3 : REC2020_TO_SRGB;
    highp vec3 outputLinear;
    highp vec3 basicEncoded = vec3(0.0);
    if (u_basic == 3) outputLinear = aces2_output(tl_aces_pre(balancedAcescg), u_display_p3);
    else if (u_basic == 4) outputLinear = odrt(balancedRec2020, u_display_p3);
    else if (u_basic == 2) outputLinear = outputMatrix * dt_sigmoid(balancedRec2020);
    else if (u_basic == 1) {
        basicEncoded = basic_encoded(outputMatrix * tl_basic_pre(balancedRec2020));
        outputLinear = vec3(0.0);
        if (u_write_gainmap != 0) outputLinear = basic_decode(basicEncoded);
    }
    else outputLinear = compress_output_gamut(outputMatrix * agx_base(balancedAcescg));
    highp vec3 baseLinear = clamp(outputLinear, 0.0, 1.0);
    highp vec3 encoded = photoCube((u_basic == 1) ? basicEncoded : srgb_oetf(baseLinear));
    highp vec3 dither = vec3(
        hash01(outputPixel, 0u), hash01(outputPixel, 1u), hash01(outputPixel, 2u)
    ) - 0.5;
    imageStore(img_out, outputPixel, vec4(encoded + dither / 255.0, 1.0));

    // Same highlight extension and gain-map encode as agx_srgb8.glsl.
    if (u_write_gainmap != 0) {
        ivec2 gainPixel = outputPixel / 4;
        ivec2 samplePixel = min(gainPixel * 4 + ivec2(2), u_output_size - ivec2(1));
        if (all(equal(outputPixel, samplePixel))) {
            highp vec3 sdrLin = baseLinear;
            highp vec3 hdrLin = hdr_gainmap_target(sdrLin, balancedRec2020);
            highp float sdrMaxChan = max(sdrLin.x, max(sdrLin.y, sdrLin.z));
            highp float sdrMinChan = min(sdrLin.x, min(sdrLin.y, sdrLin.z));
            highp float sdrSat = (sdrMaxChan - sdrMinChan) / max(sdrMaxChan, 1e-3);
            highp float satW = u_sat_protect * smoothstep(0.5, 0.9, sdrMaxChan) * clamp(sdrSat, 0.0, 1.0);
            highp vec3 logGain3 =
                log2(max((hdrLin + max(u_gainmap_offset_hdr, 1e-6)) / (sdrLin + max(u_gainmap_offset_sdr, 1e-6)), vec3(1e-9)));
            highp float lumaHdr = dot(hdrLin, vec3(0.2126, 0.7152, 0.0722));
            highp float lumaSdr = dot(sdrLin, vec3(0.2126, 0.7152, 0.0722));
            highp float logGainLuma =
                log2(max((lumaHdr + max(u_gainmap_offset_hdr, 1e-6)) / (lumaSdr + max(u_gainmap_offset_sdr, 1e-6)), 1e-9));
            highp float toneScale = 1.0 - satW;
            highp float hdrMaxChan = max(hdrLin.x, max(hdrLin.y, hdrLin.z));
            highp float hdrMinChan = min(hdrLin.x, min(hdrLin.y, hdrLin.z));
            highp float hdrSat = (hdrMaxChan - hdrMinChan) / max(hdrMaxChan, 1e-3);
            highp float hdrWhiteW = (1.0 - smoothstep(0.05, 0.15, hdrSat)) * smoothstep(0.5, 0.9, lumaHdr);
            highp float mcOn = (u_multi_channel != 0) ? 1.0 : 0.0;
            highp float useLuma = max(1.0 - mcOn, hdrWhiteW * mcOn);
            highp vec3 logFinal = mix(logGain3 * toneScale, vec3(logGainLuma * toneScale), useLuma);
            highp vec3 norm3 = clamp(
                (logFinal - vec3(u_gainmap_min_log2)) / max(u_gainmap_max_log2 - u_gainmap_min_log2, 1e-6), 0.0, 1.0);
            highp vec3 recovery3 = pow(norm3, vec3(u_gainmap_gamma));
            highp vec3 dithered3 = recovery3 + (dither / 255.0);
            highp vec3 stored3 = clamp(floor(dithered3 * 255.0 + 0.5) / 255.0, 0.0, 1.0);
            imageStore(img_gainmap, gainPixel, vec4(stored3, 1.0));
        }
    }
}
