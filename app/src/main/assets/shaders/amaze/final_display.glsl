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

    if (u_write_gainmap != 0) {
        ivec2 gainPixel = outputPixel / 4;
        ivec2 samplePixel = min(gainPixel * 4 + ivec2(2), u_output_size - ivec2(1));
        if (all(equal(outputPixel, samplePixel))) {
            highp float displayLuma = dot(baseLinear, vec3(0.2126, 0.7152, 0.0722));
            highp float gain = smoothstep(0.30, 0.72, displayLuma);
            imageStore(img_gainmap, gainPixel, vec4(vec3(gain), 1.0));
        }
    }
}
