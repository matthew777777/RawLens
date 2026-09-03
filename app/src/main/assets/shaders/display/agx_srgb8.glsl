// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later

layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
layout(binding = 0, rgba16f) readonly uniform highp image2D img_scene;
layout(binding = 1, rgba8) writeonly uniform highp image2D img_encoded;
layout(binding = 2, rgba8) writeonly uniform highp image2D img_gainmap;
uniform highp ivec2 u_size;
uniform highp ivec2 u_gainmap_size;
uniform highp int u_display_p3;
uniform highp float u_agx_purity_boost;
uniform highp float u_agx_contrast;
uniform highp float u_agx_saturation;
uniform highp float u_agx_hue_preservation;
uniform highp float u_agx_shadow_ev;
uniform highp float u_agx_highlight_ev;
uniform highp float u_agx_gamut_compression;
uniform highp int u_write_gainmap;
uniform highp int u_gainmap_only;
uniform highp float u_grain_amount;
uniform highp float u_grain_size;
uniform highp uint u_grain_seed;

// Google Filament AgX Base. RawLens intentionally exposes only the BASE view transform.
// Filament's optional GOLDEN/PUNCHY creative looks are applied after the AgX sigmoid and can
// magnify low-level chroma variation (especially when the log-domain shadow range is extended),
// so they are deliberately not part of the camera development path.
// Filament's matrices are Rec.2020. RawLens explicitly converts ACEScg D60 to Rec.2020 D65.
const highp mat3 ACESCG_TO_REC2020_D65 = mat3(
    1.025877552449, -0.002232441770, -0.005013950857,
   -0.020020686312,  1.004568990995, -0.025282661381,
   -0.005775003430, -0.002349522759,  1.030082295555
);
const highp mat3 AGX_INSET = mat3(
    0.856627153315983, 0.137318972929847, 0.111898212999950,
    0.095121240538159, 0.761241990602591, 0.076799418603190,
    0.048251606145858, 0.101439036467562, 0.811302368396859
);
const highp mat3 AGX_OUTSET = mat3(
     1.127100581814437, -0.141329763498438, -0.141329763498438,
    -0.110606643096603,  1.157823702216272, -0.110606643096603,
    -0.016493938717835, -0.016493938717834,  1.251936406595040
);
const highp mat3 REC2020_TO_SRGB = mat3(
     1.6604910021, -0.1245504745, -0.0181507634,
    -0.5876411388,  1.1328998971, -0.1005788980,
    -0.0728498633, -0.0083494226,  1.1187296614
);
// Row-major Rec.2020 D65 -> Display P3 D65, transposed for GLSL's column-major constructor.
const highp mat3 REC2020_TO_DISPLAY_P3 = mat3(
     1.343578252570, -0.065297452837,  0.002821787226,
    -0.282179670449,  1.075787915784, -0.019598494598,
    -0.061398582051, -0.010490463088,  1.016776707234
);

highp vec3 agx_contrast(highp vec3 x) {
    highp vec3 x2 = x * x;
    highp vec3 x4 = x2 * x2;
    highp vec3 x6 = x4 * x2;
    return -17.86 * x6 * x + 78.01 * x6 - 126.7 * x4 * x + 92.06 * x4
         - 28.72 * x2 * x + 4.361 * x2 - 0.1718 * x + 0.002857;
}

highp vec3 agx_base(highp vec3 acescg) {
    highp vec3 scene = max(ACESCG_TO_REC2020_D65 * acescg, vec3(0.0));
    highp vec3 v = AGX_INSET * scene;
    v = log2(max(v, vec3(1e-10)));
    highp float minimumEv = -2.473931188 - u_agx_shadow_ev;
    highp float evRange = u_agx_shadow_ev + u_agx_highlight_ev;
    v = clamp((v - vec3(minimumEv)) / evRange, 0.0, 1.0);
    highp float pivot = u_agx_shadow_ev / evRange;
    v = clamp(vec3(pivot) + (v - vec3(pivot)) * u_agx_contrast, 0.0, 1.0);
    v = agx_contrast(v);
    // darktable AgX-style master purity control: 100% reproduces the pinned base outset,
    // while 0% bypasses it and 200% extrapolates the post-curve primary recovery.
    v = mix(v, AGX_OUTSET * v, u_agx_purity_boost);
    v = pow(max(v, vec3(0.0)), vec3(2.2));
    const highp vec3 REC2020_LUMA = vec3(0.2627, 0.6780, 0.0593);
    highp float mappedLuma = dot(v, REC2020_LUMA);
    highp float sceneLuma = dot(scene, REC2020_LUMA);
    if (u_agx_hue_preservation > 0.0 && sceneLuma > 1e-9) {
        highp vec3 ratioMapped = scene * (mappedLuma / sceneLuma);
        v = mix(v, ratioMapped, u_agx_hue_preservation);
    }
    highp float gradedLuma = dot(v, REC2020_LUMA);
    return vec3(gradedLuma) + u_agx_saturation * (v - vec3(gradedLuma));
}

highp vec3 compress_output_gamut(highp vec3 value) {
    if (u_agx_gamut_compression <= 0.0) return value;
    highp float anchor = clamp(dot(value, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
    highp vec3 delta = value - vec3(anchor);
    highp float scale = 1.0;
    for (int channel = 0; channel < 3; ++channel) {
        if (delta[channel] > 0.0) scale = min(scale, (1.0 - anchor) / delta[channel]);
        else if (delta[channel] < 0.0) scale = min(scale, -anchor / delta[channel]);
    }
    scale = mix(1.0, clamp(scale, 0.0, 1.0), u_agx_gamut_compression);
    return vec3(anchor) + scale * delta;
}

highp vec3 srgb_oetf(highp vec3 linear) {
    bvec3 low = lessThanEqual(linear, vec3(0.0031308));
    highp vec3 lo = 12.92 * linear;
    highp vec3 hi = 1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055;
    return mix(hi, lo, low);
}

highp float hash01(ivec2 p, uint channel) {
    uint bits = uint(p.x) * 0x1f123bb5u + uint(p.y) * 0x05491333u + channel * 0x68bc21ebu;
    bits ^= bits >> 16;
    bits *= 0x7feb352du;
    bits ^= bits >> 15;
    return float((bits >> 8) & 0x00ffffffu) / 16777216.0;
}
highp float grain_hash(ivec2 p, uint channel) {
    return hash01(p + ivec2(int(u_grain_seed & 65535u), int(u_grain_seed >> 16)), channel);
}

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    highp ivec2 scenePixel = pixel;
    if (u_gainmap_only != 0) {
        if (any(greaterThanEqual(pixel, u_gainmap_size))) return;
        // The platform permits smaller gainmaps. Center-sample each 4x4 source region.
        scenePixel = min(pixel * 4 + ivec2(2), u_size - ivec2(1));
    } else if (any(greaterThanEqual(pixel, u_size))) {
        return;
    }
    highp vec3 sceneAcescg = imageLoad(img_scene, scenePixel).rgb;
    highp vec3 rec2020DisplayLinear = agx_base(sceneAcescg);
    highp mat3 outputMatrix = u_display_p3 != 0 ? REC2020_TO_DISPLAY_P3 : REC2020_TO_SRGB;
    highp vec3 outputLinear = compress_output_gamut(outputMatrix * rec2020DisplayLinear);

    // The sole deliberate final display-gamut mapping/clipping boundary.
    highp vec3 baseLinear = clamp(outputLinear, 0.0, 1.0);
    highp vec3 encoded = srgb_oetf(baseLinear);
    if (u_gainmap_only == 0 && u_grain_amount > 0.0) {
        // Neutral, zero-mean display grain replaces sensor residual retention. Combining an
        // independent sample with a 2x2 value field gives film-like clumps without color noise.
        highp ivec2 cell = pixel / 2;
        highp vec2 phase = vec2(pixel & 1);
        highp float coarse = mix(mix(grain_hash(cell, 17u), grain_hash(cell + ivec2(1,0), 17u), phase.x*.5), mix(grain_hash(cell + ivec2(0,1),17u), grain_hash(cell+ivec2(1),17u),phase.x*.5), phase.y*.5) - .5;
        highp float fine = grain_hash(pixel, 23u) - .5;
        highp float grain = mix(fine, coarse, u_grain_size);
        highp float luma = dot(encoded, vec3(.2126,.7152,.0722));
        highp float exposureMask = smoothstep(.015,.12,luma) * (1.0-smoothstep(.78,.99,luma));
        encoded += vec3(grain * exposureMask * u_grain_amount * (3.0/255.0));
    }
    highp vec3 dither = vec3(
        hash01(pixel, 0u), hash01(pixel, 1u), hash01(pixel, 2u)
    ) - 0.5;
    encoded += dither / 255.0;
    // RGBA8 conversion performs the final bounded 8-bit quantization.
    if (u_gainmap_only == 0) imageStore(img_encoded, pixel, vec4(encoded, 1.0));

    // Android Gainmap metadata below maps this SDR base to a four-stop HDR rendition.
    // Gain must increase with the SDR highlight signal.  The former scene/base division gave
    // shadows a very large ratio whenever B was near zero, spatially inverting the map and
    // causing compliant viewers to suppress HDR.  This luma map is deliberately monotonic:
    // black/midtones stay SDR and only AgX-developed highlights receive HDR headroom.
    if (u_write_gainmap != 0 && u_gainmap_only != 0) {
        highp float displayLuma = dot(baseLinear, vec3(0.2126, 0.7152, 0.0722));
        highp float gain = smoothstep(0.30, 0.72, displayLuma);
        // RatioMin=1 and RatioMax=16 mean G directly encodes log2(gain ratio) / 4.
        imageStore(img_gainmap, pixel, vec4(vec3(gain), 1.0));
    }
}
