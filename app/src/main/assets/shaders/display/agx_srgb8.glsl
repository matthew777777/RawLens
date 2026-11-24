// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Highlight compression adapted from Khronos PBR Neutral (2024 The Khronos Group, Inc.; Apache-2.0).

#ifndef RAPIDRAW_OUTPUT_FUSED
layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;
#endif
layout(binding = 0, rgba16f) readonly uniform highp image2D img_scene;
layout(binding = 1, rgba8) writeonly uniform highp image2D img_encoded;
layout(binding = 2, rgba8) writeonly uniform highp image2D img_gainmap;
uniform highp ivec2 u_size;
uniform highp ivec2 u_gainmap_size;
uniform highp int u_basic;
// PHOTO_CUBE
uniform highp int u_display_p3;
// HDR PQ stills (JXL): 1 preserves scene highlights above SDR white and
// encodes ST2084 instead of sRGB. 0 is the SDR path, bit-identical to
// before (every HDR branch below is gated on this; unset stays 0).
uniform highp int u_hdr_pq;
#ifdef RAWLENS_JXL
uniform highp ivec2 u_jxl_dispatch_offset;
#endif
const highp float HDR_HEADROOM = 4.0;
uniform highp float u_agx_purity_boost;
uniform highp int u_agx_punchy;
uniform highp float u_agx_contrast;
uniform highp float u_agx_saturation;
uniform highp float u_agx_hue_preservation;
uniform highp float u_agx_shadow_ev;
uniform highp float u_agx_highlight_ev;
uniform highp float u_agx_gamut_compression;
uniform highp float u_highlight_shoulder;
uniform highp int u_write_gainmap;
uniform highp int u_gainmap_only;
// RAWR-parity Ultra HDR gain-map encode parameters. The map is the scene-linear
// HDR to SDR-base ratio (log2, normalized by u_gainmap_min/max_log2), not a
// monotonic function of the SDR signal: only genuine headroom lights the map.
uniform highp float u_hdr_exposure;
uniform highp float u_gainmap_min_log2;
uniform highp float u_gainmap_max_log2;
uniform highp float u_gainmap_gamma;
uniform highp float u_gainmap_offset_sdr;
uniform highp float u_gainmap_offset_hdr;
uniform highp float u_sat_protect;
uniform highp int u_multi_channel;
uniform highp float u_grain_amount;
uniform highp float u_grain_size;
uniform highp uint u_grain_seed;
#ifdef RAPIDRAW_BASIC
#define EFFECTIVE_BASIC RAPIDRAW_BASIC
#else
#define EFFECTIVE_BASIC u_basic
#endif

#ifdef RAPIDRAW_OUTPUT_FUSED
#define EFFECTIVE_HDR_PQ 0
#define EFFECTIVE_GRAIN_AMOUNT 0.0
#define EFFECTIVE_GAINMAP_ONLY 0
#define EFFECTIVE_WRITE_GAINMAP RAPIDRAW_GAINMAP
#else
#ifdef RAWLENS_STILL_SDR
#define EFFECTIVE_HDR_PQ 0
#else
#define EFFECTIVE_HDR_PQ u_hdr_pq
#endif
#define EFFECTIVE_GRAIN_AMOUNT u_grain_amount
#define EFFECTIVE_GAINMAP_ONLY u_gainmap_only
#define EFFECTIVE_WRITE_GAINMAP u_write_gainmap
#endif

// Google Filament AgX Base and Punchy looks.
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
// d(agx_contrast)/dx at x=1 (white-point slope for the HDR linear tail:
// -17.86*7 + 78.01*6 - 126.7*5 + 92.06*4 - 28.72*3 + 4.361*2 - 0.1718).
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
    // Scene-linear soft shoulder, mirror of AgxDisplayTransform.compressHighlight.
    // C1 at knee 0.9, asymptote 1.7: 1.0->0.994, 1.95->1.49, 16->1.7 at full strength.
    // Strength 0 = pinned Filament AgX only.
    if (u_highlight_shoulder > 0.0) {
        for (int c = 0; c < 3; ++c) {
            if (scene[c] > 0.9) {
                highp float t = (scene[c] - 0.9) / 0.8;
                highp float compressed = 0.9 + 0.8 * (1.0 - exp(-t));
                scene[c] = mix(scene[c], compressed, clamp(u_highlight_shoulder, 0.0, 1.0));
            }
        }
    }
    highp vec3 v = AGX_INSET * scene;
    v = log2(max(v, vec3(1e-10)));
    highp float minimumEv = -2.473931188 - u_agx_shadow_ev;
    highp float evRange = u_agx_shadow_ev + u_agx_highlight_ev;
    highp vec3 normV = (v - vec3(minimumEv)) / evRange;
    v = clamp(normV, 0.0, 1.0);
    highp float pivot = u_agx_shadow_ev / evRange;
    highp vec3 contrastV = vec3(pivot) + (v - vec3(pivot)) * u_agx_contrast;
    v = agx_contrast(clamp(contrastV, 0.0, 1.0));
    if (u_agx_punchy != 0) {
        highp float lookLuma = dot(v, vec3(0.2126, 0.7152, 0.0722));
        v = vec3(lookLuma) + 1.4 * (pow(max(v, vec3(0.0)), vec3(1.35)) - vec3(lookLuma));
    }
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
    highp float ceiling = 1.0;
    highp float anchor = clamp(dot(value, vec3(0.2126, 0.7152, 0.0722)), 0.0, ceiling);
    highp vec3 delta = value - vec3(anchor);
    highp float scale = 1.0;
    for (int channel = 0; channel < 3; ++channel) {
        if (delta[channel] > 0.0) scale = min(scale, (ceiling - anchor) / delta[channel]);
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

// SIGMOID_FUNCTIONS

// Photographic neutral: Khronos highlight compression, without the PBR shadow offset.
highp vec3 basic_encoded(highp vec3 scene) {
    scene = max(scene, vec3(0.0));
    highp float peak = max(scene.r, max(scene.g, scene.b));
    highp float knee = 0.76 - 0.16 * clamp(u_highlight_shoulder, 0.0, 1.0);
    if (peak > knee) {
        highp float d = 1.0 - knee;
        highp float mapped = 1.0 - d * (d / (peak + d - knee));
        scene *= mapped / peak;
        highp float white = 1.0 - 1.0 / (1.0 + 0.15 * (peak - mapped));
        scene = mix(scene, vec3(mapped), white);
    }
    return srgb_oetf(clamp(scene, 0.0, 1.0));
}
highp vec3 basic_decode(highp vec3 encoded) {
    return mix(pow((encoded + 0.055) / 1.055, vec3(2.4)),
               encoded / 12.92, lessThanEqual(encoded, vec3(0.04045)));
}

highp vec3 hdr_highlights(highp vec3 graded, highp float gradedY, highp vec3 sceneRec2020) {
    const highp vec3 rec2020Luma = vec3(0.2627, 0.6780, 0.0593);
    const highp float sceneDiffuseWhite = 0.60;
    highp float sceneY = dot(sceneRec2020, rec2020Luma);
    // Keep diffuse colors from the selected look. Blend into a scene-linear
    // highlight extension using the signal upstream of its SDR shoulder.
    highp float blend = smoothstep(0.35, 0.85, sceneY);
    highp float targetY = mix(gradedY, max(gradedY, sceneY / sceneDiffuseWhite), blend);
    highp vec3 hdr = graded * (1.0 + (targetY - gradedY) / max(gradedY, 1e-6));
    highp float peak = max(hdr.r, max(hdr.g, hdr.b));
    // As in RapidRAW, start the soft clip at three quarters of HDR peak.
    // Scale RGB together to keep highlight chromaticity through the shoulder.
    const highp float shoulder = 0.75 * HDR_HEADROOM;
    const highp float span = HDR_HEADROOM - shoulder;
    if (peak > shoulder) {
        highp float mapped = shoulder + span * (1.0 - exp((shoulder - peak) / span));
        hdr *= mapped / peak;
    }
    return hdr;
}

highp vec3 hdr_gainmap_target(highp vec3 sdr, highp vec3 sceneRec2020) {
    highp vec3 displayLuma = u_display_p3 != 0
        ? vec3(0.228975, 0.691739, 0.079287) : vec3(0.2126, 0.7152, 0.0722);
    return hdr_highlights(sdr, dot(sdr, displayLuma), sceneRec2020 * u_hdr_exposure);
}

// ST2084 (PQ) opto-electronic transfer, SMPTE ST 2084: absolute nits in
// [0, 10000] map to codes in [0, 1]. HDR stills treat graded linear 1.0
// (SDR white) as 203 nits (ITU-R BT.2408 reference white, what Chrome,
// Windows and Lightroom use); brighter scene highlights extend above it.
highp vec3 pq_oetf(highp vec3 nits) {
    highp vec3 y = clamp(nits / vec3(10000.0), vec3(0.0), vec3(1.0));
    highp vec3 ym1 = pow(y, vec3(0.15930176));
    highp vec3 num = vec3(0.8359375) + vec3(18.8515625) * ym1;
    highp vec3 den = vec3(1.0) + vec3(18.6875) * ym1;
    return pow(num / den, vec3(78.84375));
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
    #ifdef RAPIDRAW_OUTPUT_FUSED
    prepareCleanupTile();
    #endif
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    #ifdef RAPIDRAW_OUTPUT_FUSED
    pixel += u_dispatch_offset;
    #endif
    #ifdef RAWLENS_JXL
    pixel += u_jxl_dispatch_offset;
    #endif
    highp ivec2 scenePixel = pixel;
    if (EFFECTIVE_GAINMAP_ONLY != 0) {
        if (any(greaterThanEqual(pixel, u_gainmap_size))) return;
        // The platform permits smaller gainmaps. Center-sample each 4x4 source region.
        scenePixel = min(pixel * 4 + ivec2(2), u_size - ivec2(1));
    } else if (any(greaterThanEqual(pixel, u_size))) {
        return;
    }
    #ifdef RAPIDRAW_OUTPUT_FUSED
    highp vec3 sceneAcescg = cleanupPixel(scenePixel);
    // Preserve the existing RGBA16F intermediate's rounding without storing it.
    sceneAcescg = vec3(unpackHalf2x16(packHalf2x16(sceneAcescg.rg)),
        unpackHalf2x16(packHalf2x16(vec2(sceneAcescg.b, 0.0))).r);
    ivec2 gainPixel = pixel / 4;
    bool writeGain = EFFECTIVE_WRITE_GAINMAP != 0 && all(equal(pixel, min(gainPixel * 4 + ivec2(2), u_size - 1)));
    #else
    highp vec4 sceneSample = imageLoad(img_scene, scenePixel);
    highp vec3 sceneAcescg = sceneSample.rgb;
    ivec2 gainPixel = pixel;
    bool writeGain = EFFECTIVE_WRITE_GAINMAP != 0 && EFFECTIVE_GAINMAP_ONLY != 0;
    #endif
    highp vec3 balancedAcescg = camera_tone_balance(sceneAcescg);
    highp vec3 balancedRec2020 = ACESCG_TO_REC2020_D65 * balancedAcescg;
    highp mat3 outputMatrix = u_display_p3 != 0 ? REC2020_TO_DISPLAY_P3 : REC2020_TO_SRGB;
    highp vec3 outputLinear;
    highp vec3 basicEncoded = vec3(0.0);
    if (u_basic == 3) outputLinear = aces2_output(tl_aces_pre(balancedAcescg), u_display_p3);
    else if (u_basic == 4) outputLinear = odrt(balancedRec2020, u_display_p3);
    else if (u_basic == 2) outputLinear = outputMatrix * dt_sigmoid(balancedRec2020);
    else if (EFFECTIVE_BASIC == 1) {
        highp vec3 sceneOutput = outputMatrix * tl_basic_pre(balancedRec2020);
        basicEncoded = basic_encoded(sceneOutput);
        outputLinear = vec3(0.0);
        // Ordinary SDR goes straight to encoded output, skipping decode/re-encode.
        if (writeGain) outputLinear = basic_decode(basicEncoded);
    }
    else outputLinear = compress_output_gamut(outputMatrix * agx_base(balancedAcescg));

    highp vec3 baseLinear = clamp(outputLinear, 0.0, 1.0);
    highp vec3 sdr = (EFFECTIVE_BASIC == 1) ? basicEncoded : srgb_oetf(baseLinear);
    highp vec3 encoded = photoCube(sdr);
    #ifdef RAWLENS_JXL
    if (EFFECTIVE_HDR_PQ != 0) {
        highp vec3 graded = (EFFECTIVE_BASIC == 1 || u_photoCubeEnabled)
            ? basic_decode(encoded) : baseLinear;
        highp mat3 toRec2020 = u_display_p3 != 0
            ? inverse(REC2020_TO_DISPLAY_P3) : inverse(REC2020_TO_SRGB);
        highp vec3 gradedRec2020 = max(toRec2020 * graded, vec3(0.0));
        highp vec3 hdr = hdr_highlights(
            gradedRec2020, dot(gradedRec2020, vec3(0.2627, 0.6780, 0.0593)), balancedRec2020);
        encoded = pq_oetf(hdr * vec3(203.0));
    }
    #endif
    if (EFFECTIVE_GAINMAP_ONLY == 0 && EFFECTIVE_GRAIN_AMOUNT > 0.0) {
        // Neutral, zero-mean display grain replaces sensor residual retention. Combining an
        // independent sample with a 2x2 value field gives film-like clumps without color noise.
        highp ivec2 cell = pixel / 2;
        highp vec2 phase = vec2(pixel & 1);
        highp float coarse = mix(mix(grain_hash(cell, 17u), grain_hash(cell + ivec2(1,0), 17u), phase.x*.5), mix(grain_hash(cell + ivec2(0,1),17u), grain_hash(cell+ivec2(1),17u),phase.x*.5), phase.y*.5) - .5;
        highp float fine = grain_hash(pixel, 23u) - .5;
        highp float grain = mix(fine, coarse, u_grain_size);
        highp float luma = dot(encoded, vec3(.2126,.7152,.0722));
        highp float exposureMask = smoothstep(.015,.12,luma) * (1.0-smoothstep(.78,.99,luma));
        encoded += vec3(grain * exposureMask * EFFECTIVE_GRAIN_AMOUNT * (3.0/255.0));
    }
    #ifdef RAWLENS_JXL
    // Alpha carries write validity through readback and is discarded by RGB packing.
    bool valid = sceneSample.a == 1.0 && !any(isnan(sceneAcescg)) && !any(isinf(sceneAcescg))
        && !any(isnan(encoded)) && !any(isinf(encoded));
    imageStore(img_encoded, pixel, valid ? vec4(encoded, 1.0) : vec4(0.0));
    #else
    highp vec3 dither = vec3(
        hash01(pixel, 0u), hash01(pixel, 1u), hash01(pixel, 2u)
    ) - 0.5;
    encoded += dither / 255.0;
    // RGBA8 conversion performs the final bounded 8-bit quantization.
    if (EFFECTIVE_GAINMAP_ONLY == 0) imageStore(img_encoded, pixel, vec4(encoded, 1.0));

    #endif

    // Ultra HDR gain map: highlight-extended HDR to SDR-base ratio.
    // Compute the exposure ratio before the creative LUT so HDR playback
    // expands the graded base instead of undoing its look.
    if (writeGain) {
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
        // Clipped-core hue fallback: a hue-less (white) HDR tap with a colored
        // SDR base keeps the luma gain so decoded HDR keeps the SDR hue.
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
        highp vec3 gainDither = vec3(hash01(gainPixel, 0u), hash01(gainPixel, 1u), hash01(gainPixel, 2u)) - 0.5;
        highp vec3 dithered3 = recovery3 + (gainDither / 255.0);
        highp vec3 stored3 = clamp(floor(dithered3 * 255.0 + 0.5) / 255.0, 0.0, 1.0);
        imageStore(img_gainmap, gainPixel, vec4(stored3, 1.0));
    }
}
