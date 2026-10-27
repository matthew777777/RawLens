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

const int HALO = 2;
const int TW = LW + 2 * HALO;
const int TH = LH + 2 * HALO;
const int TN = TW * TH;

ivec2 T0() { return ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) + u_inner - HALO; }
int SIDX(ivec2 q) { ivec2 l = q - T0(); return l.y * TW + l.x; }
int SIDX_H(ivec2 q) { ivec2 l = ivec2(q.x & ~1, q.y) - T0(); return l.y * TW + l.x; }

shared vec4 s_chroma[TN];
shared float s_hvwt[TN];
float hv(ivec2 q) { return s_hvwt[SIDX_H(q)]; }
float D0(ivec2 q) { return s_chroma[SIDX_H(q)].r; }
float D1(ivec2 q) { return s_chroma[SIDX_H(q)].g; }

void load_tile() {
    ivec2 t0 = T0();
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = clamp(t0 + ivec2(i % TW, i / TW), ivec2(0), u_size - 1);
        s_chroma[i] = texelFetch(u_chroma, q, 0);
        s_hvwt[i] = texelFetch(u_hvwt, q, 0).r;
    }
    memoryBarrierShared();
    barrier();
}

layout(binding = 0, rgba8) writeonly uniform highp image2D img_out;
layout(binding = 1, rgba8) writeonly uniform highp image2D img_gainmap;

void main() {
    load_tile();
    ivec2 o = ivec2(gl_GlobalInvocationID.xy);
    if (o.x >= u_outsize.x || o.y >= u_outsize.y) return;
    ivec2 p = o + u_inner;
    float g = s_chroma[SIDX(p)].b;
    float r, b;
    if (isG(p.y, p.x)) {
        float wu = hv(p + ivec2(0, -1));
        float wd = hv(p + ivec2(0, 1));
        float wl = hv(p + ivec2(-1, 0));
        float wr = hv(p + ivec2(1, 0));
        float temp = 1.0 / (wu + 2.0 - wr - wl + wd);
        r = g - (wu * D0(p + ivec2(0, -1)) + (1.0 - wr) * D0(p + ivec2(1, 0))
                 + (1.0 - wl) * D0(p + ivec2(-1, 0)) + wd * D0(p + ivec2(0, 1))) * temp;
        b = g - (wu * D1(p + ivec2(0, -1)) + (1.0 - wr) * D1(p + ivec2(1, 0))
                 + (1.0 - wl) * D1(p + ivec2(-1, 0)) + wd * D1(p + ivec2(0, 1))) * temp;
    } else {
        r = g - D0(p);
        b = g - D1(p);
    }
    ivec2 outputPixel = o + u_outoff;
    // Match RawTherapee's AMaZE output boundary before applying the camera matrix.
    highp vec3 cameraRgb = max(vec3(r, g, b), vec3(0.0));

    // Sensor-domain white-point protection, identical to the non-fused path. Detect clipping
    // from 70%..99% in demosaiced camera RGB and blend toward camera-space neutral white before
    // WB/calibration/color conversion. This turns clipped magenta highlights into neutral white.
    // AMaZE works in an attenuated WB-balanced basis. Detect saturation in
    // sensor units: green can clip while every balanced channel is below 0.70.
    highp vec3 sensorRgb = cameraRgb * u_demosaic_unbalance;
    highp vec3 whiteBlendRgb = smoothstep(vec3(u_white_window.x), vec3(u_white_window.y), sensorRgb);
    highp float whiteBlend = max(whiteBlendRgb.r, max(whiteBlendRgb.g, whiteBlendRgb.b));
    cameraRgb = mix(cameraRgb, u_camera_white_normalized, whiteBlend);

    highp vec3 rec2020DisplayLinear = agx_base(u_camera_to_acescg * cameraRgb);
    highp mat3 outputMatrix = u_display_p3 != 0 ? REC2020_TO_DISPLAY_P3 : REC2020_TO_SRGB;
    highp vec3 baseLinear = clamp(
        compress_output_gamut(outputMatrix * rec2020DisplayLinear), 0.0, 1.0
    );
    highp vec3 encoded = srgb_oetf(baseLinear);
    highp vec3 dither = vec3(
        hash01(outputPixel, 0u), hash01(outputPixel, 1u), hash01(outputPixel, 2u)
    ) - 0.5;
    imageStore(img_out, outputPixel, vec4(encoded + dither / 255.0, 1.0));

    // RAWR-parity Ultra HDR gain map: scene-linear HDR to SDR-base ratio.
    // The HDR tap is the pre-tonemap demosaiced camera RGB in display-linear
    // space (camera -> ACEScg -> Rec.2020 -> display, without AgX); the SDR
    // tap is the clamped display-linear base. Same encode as agx_srgb8.glsl.
    if (u_write_gainmap != 0) {
        ivec2 gainPixel = outputPixel / 4;
        ivec2 samplePixel = min(gainPixel * 4 + ivec2(2), u_output_size - ivec2(1));
        if (all(equal(outputPixel, samplePixel))) {
            highp vec3 hdrScene = outputMatrix * (ACESCG_TO_REC2020_D65 * (u_camera_to_acescg * cameraRgb));
            highp vec3 hdrLin = max(hdrScene * max(u_hdr_exposure, 1e-6), vec3(0.0));
            highp vec3 sdrLin = max(baseLinear, vec3(0.0));
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
            highp float toneScale = smoothstep(0.03, 0.50, lumaSdr) * (1.0 - satW);
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
