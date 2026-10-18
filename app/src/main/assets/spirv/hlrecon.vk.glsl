#version 450
// hand-maintained replica of the tools/srvk_shader_transform.py output
// (script absent from checkout); ESSL source of truth is shaders/hl/hlrecon.glsl.
// Regenerate with tools/hlrecon_transform.py after editing the source.
layout(binding = 18, std140) uniform SrUniforms {
    highp uvec3 u_dispatch_offset;
    int u_mode;
    int u_W;
    int u_H;
    int u_radius;
    int u_combineOp;
    int u_pitch;
    int u_hfw;
    int u_hfh;
    float u_eps;
    float u_whitePt;
    float u_clipPt;
    float u_blendPt;
    vec4 u_maxF;
    vec4 u_medF;
    vec4 u_xyzRow;
};
// SPDX-FileCopyrightText: 2026 RawLens contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Vulkan compute port of RawTherapee's RT "Color propagation"
// highlight-reconstruction GPU-mappable stages (HLRecovery_inpaint).
// CPU reference and parity gates: RtColorPropagation.kt, RtBoxBlur.kt,
// RtHighlightMap.kt, RtGuidedFilter.kt and RtHlEndToEndTest.
//
// INPUT DOMAIN: demosaiced full-resolution pre-white-balance camera RGB
// (the cameraLinear pre-WB tap), raw scale, NOT normalized: fp32 holds raw
// values exactly, so no whiteLevel roundtrip is needed. There is
// deliberately NO CFA hook: a CFA-domain port would not be RT parity.
//
// STAGE PARTITION (lossless by construction, see notes per mode):
//   - Separable ring blurs (guided-filter box means) -> BLUR_H / BLUR_V
//     (windowed-average form; identical mathematics to the CPU sliding
//     window, rounding within the measured ULP envelope; halo = radius).
//   - Per-pixel blend/clipfix/correction    -> RECONSTRUCT (scalar op order
//     identical to the CPU reference; `precise` forbids FMA fusion).
//   - Half-res last-wins resolve            -> RESOLVE (deterministic
//     reformulation of RT's sequential overwrite: highest scan-order
//     corrected pixel of each 2x2 block wins, computed independently).
//   - Guided-filter linear combines        -> COMBINE (exact scalar order;
//     op 4 ADDMUL closes the final a*guide+b without an extra pass).
//   - Gamma guide from half-res buffers    -> GUIDE (xyz luminance + the
//     pow2.2 table, truncation + lerp + clipping exactly like LUTf).
//   - Bilinear merge-back                   -> MERGE (manual 4 taps, exact).
// The directional extension scans stay on CPU (strictly sequential raster
// order cannot be parallelized bit-exactly); their maps enter here as
// small RGBA32F uploads. The accumulator, gates, downsample, and the
// rescale-nearest staging stay on CPU/host for the same reason.
//
// HUB CONVENTIONS (sources stay ESSL): plain uniforms become the UBO,
// u_gamma is a sampled 256x256 R32F table, storage images keep their ESSL
// bindings, and main() reads gl_GlobalInvocationID.xy — the generated
// .vk.glsl adds u_dispatch_offset for watchdog slicing. Local size is 8x8
// like every other hub shader.

precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;

layout(binding = 0) uniform sampler2D u_gamma;

// Binding table (the host binds dummies for modes that do not use an
// image — Vulkan requires every declared binding to be valid):
//   0-3   r32f      imgA/imgB/imgC in, imgOut out (BLUR_H/V, COMBINE planes)
//   4     rgba32f   rgbIn: raw-scale demosaiced ROI RGB (RECONSTRUCT in)
//   5-8   rgba32f   dir0In (k0..k3+D), dirAIn (k0..k3), dirBIn (k4..k7),
//                   dir4In (k0..k3+D), CPU scan maps (RECONSTRUCT in)
//   9     rgba32f   rgbBuf: full-res corrected-or-passthrough, raw scale
//                   (RECONSTRUCT write; RESOLVE/MERGE read)
//   10    r32f      maskBuf: full-res maskval/0 (RECONSTRUCT write; RESOLVE read)
//   11-13 r32f      halfR/halfG/halfB: half-res corrected-or-nearest, then
//                   guided (RESOLVE write; GUIDE read .rgb; guided passes
//                   rebind planes to 0-3; MERGE read)
//   14    r32f      maskHalf (RESOLVE write; guided passes rebind to 0-3;
//                   MERGE read)
//   15    r32f      guideHalf: gamma guide (GUIDE write; guided passes read
//                   via rebinding to 0-3)
//   16    rgba32f   mergedOut (MERGE write)
layout(binding = 1, r32f) readonly uniform highp image2D imgA;
layout(binding = 2, r32f) readonly uniform highp image2D imgB;
layout(binding = 3, r32f) readonly uniform highp image2D imgC;
layout(binding = 4, r32f) writeonly uniform highp image2D imgOut;
layout(binding = 5, rgba32f) readonly uniform highp image2D rgbIn;
layout(binding = 6, rgba32f) readonly uniform highp image2D dir0In;
layout(binding = 7, rgba32f) readonly uniform highp image2D dirAIn;
layout(binding = 8, rgba32f) readonly uniform highp image2D dirBIn;
layout(binding = 9, rgba32f) readonly uniform highp image2D dir4In;
layout(binding = 10, rgba32f) uniform highp image2D rgbBuf;
layout(binding = 11, r32f) uniform highp image2D maskBuf;
layout(binding = 12, r32f) uniform highp image2D halfR;
layout(binding = 13, r32f) uniform highp image2D halfG;
layout(binding = 14, r32f) uniform highp image2D halfB;
layout(binding = 15, r32f) uniform highp image2D maskHalf;
layout(binding = 16, r32f) uniform highp image2D guideHalf;
layout(binding = 17, rgba32f) writeonly uniform highp image2D mergedOut;

const float TRANS[3][3] = float[3][3](
    float[3](1.0, 1.0, 1.0),
    float[3](1.7320508, -1.7320508, 0.0),
    float[3](-1.0, -1.0, 2.0));
const float ITRANS[3][3] = float[3][3](
    float[3](1.0, 0.8660254, -0.5),
    float[3](1.0, -0.8660254, -0.5),
    float[3](1.0, 0.0, 1.0));

// RT boxblur ring recurrence, horizontal. Windowed-average form: identical
// mathematics to the CPU sliding window; radius 0 degenerates to an exact
// copy (single tap over 1.0), which is how the host spells boxMean(0).
//
// NOTE (measured, Apple M1/MoltenVK, tools/hlmeasure): a shared-memory
// tiled blur (cooperative staging, provably identical summation order) was
// tried and REVERTED — it measured ~40% slower at r=32 and neutral at r=4.
// Neighboring threads' overlapping windows already hit the L2/texture
// cache, so explicit staging only added barrier and occupancy cost without
// reducing DRAM traffic. The direct form below is the fast path.
void blurH(ivec2 o) {
    if (o.x >= u_W || o.y >= u_H) return;
    int r = u_radius;
    int lo = max(o.x - r, 0);
    int hi = min(o.x + r, u_W - 1);
    float s = 0.0;
    for (int k = lo; k <= hi; ++k) s += imageLoad(imgA, ivec2(k, o.y)).x;
    precise float v = s / float(hi - lo + 1);
    imageStore(imgOut, o, vec4(v, 0.0, 0.0, 0.0));
}

void blurV(ivec2 o) {
    if (o.x >= u_W || o.y >= u_H) return;
    int r = u_radius;
    int lo = max(o.y - r, 0);
    int hi = min(o.y + r, u_H - 1);
    float s = 0.0;
    for (int k = lo; k <= hi; ++k) s += imageLoad(imgA, ivec2(o.x, k)).x;
    precise float v = s / float(hi - lo + 1);
    imageStore(imgOut, o, vec4(v, 0.0, 0.0, 0.0));
}

float lim01(float x) { return clamp(x, 0.0, 1.0); }
float intp1(float a, float b, float c) {
    precise float v = a * (b - c) + c;
    return v;
}

// Per-pixel blend estimate + hue weights + channel correction
// (hilite_recon.cc:979-1163), scalar op order identical to RtHighlightMap.
// Input rgbIn is raw scale, the scale the thresholds live in, so the host
// passes the SAME maxF/medF/whitePt/clipPt/blendPt/eps the CPU reference
// uses. Corrected output is stored raw scale.
void reconstruct(ivec2 o) {
    if (o.x >= u_W || o.y >= u_H) return;
    vec3 raw = imageLoad(rgbIn, o).rgb;
    imageStore(rgbBuf, o, vec4(raw, 1.0));
    imageStore(maskBuf, o, vec4(0.0, 0.0, 0.0, 0.0));
    precise vec3 pixel = raw;
    if (pixel.r < u_maxF.r && pixel.g < u_maxF.g && pixel.b < u_maxF.b) return;
    int i1 = min((o.y - o.y % u_pitch) / u_pitch, u_hfh - 1);
    int j1 = min((o.x - o.x % u_pitch) / u_pitch, u_hfw - 1);
    precise vec3 cam0 = pixel;
    precise vec3 cam1 = min(cam0, vec3(u_clipPt));
    precise vec3 lab0 = vec3(0.0);
    precise vec3 lab1 = vec3(0.0);
    for (int c = 0; c < 3; ++c) {
        precise float a0 = 0.0;
        precise float a1 = 0.0;
        for (int j = 0; j < 3; ++j) {
            a0 += TRANS[c][j] * (j == 0 ? cam0.r : (j == 1 ? cam0.g : cam0.b));
            a1 += TRANS[c][j] * (j == 0 ? cam1.r : (j == 1 ? cam1.g : cam1.b));
        }
        if (c == 0) { lab0.r = a0; lab1.r = a1; }
        if (c == 1) { lab0.g = a0; lab1.g = a1; }
        if (c == 2) { lab0.b = a0; lab1.b = a1; }
    }
    precise float sum0 = max(lab0.g * lab0.g + lab0.b * lab0.b, u_eps);
    precise float sum1 = lab1.g * lab1.g + lab1.b * lab1.b;
    precise float chratio = sqrt(sum1 / sum0);
    lab0.g *= chratio;
    lab0.b *= chratio;
    precise vec3 rgb;
    rgb.r = (ITRANS[0][0] * lab0.r + ITRANS[0][1] * lab0.g + ITRANS[0][2] * lab0.b) / 3.0;
    rgb.g = (ITRANS[1][0] * lab0.r + ITRANS[1][1] * lab0.g + ITRANS[1][2] * lab0.b) / 3.0;
    rgb.b = (ITRANS[2][0] * lab0.r + ITRANS[2][1] * lab0.g + ITRANS[2][2] * lab0.b) / 3.0;
    precise vec3 rb = vec3(0.0);
    if (pixel.r > u_blendPt) rb.r = intp1(lim01(u_medF.r * (pixel.r - u_blendPt)), rgb.r, pixel.r);
    if (pixel.g > u_blendPt) rb.g = intp1(lim01(u_medF.g * (pixel.g - u_blendPt)), rgb.g, pixel.g);
    if (pixel.b > u_blendPt) rb.b = intp1(lim01(u_medF.b * (pixel.b - u_blendPt)), rgb.b, pixel.b);
    precise float Y = u_eps + rb.r + rb.g + rb.b;
    precise vec3 rn = rb / Y;
    precise vec3 clipfix = vec3(0.0);
    bool totwt = false;
    precise float Yhi = 1.0 / (imageLoad(dir0In, ivec2(j1, i1)).r +
        imageLoad(dir0In, ivec2(j1, i1)).g + imageLoad(dir0In, ivec2(j1, i1)).b);
    // NOTE: squared sums are expanded manually, never dot(): dot() may use
    // any summation order, while RT accumulates channel by channel.
    if (Yhi < 2.0) {
        vec3 d0 = imageLoad(dir0In, ivec2(j1, i1)).rgb;
        vec3 e0 = rn - d0 * Yhi;
        precise float s0 = e0.r * e0.r + e0.g * e0.g + e0.b * e0.b;
        precise float dw = 1.0 / ((1.0 + 65535.0 * s0) * (imageLoad(dir0In, ivec2(j1, i1)).a + u_eps));
        totwt = true;
        clipfix = dw * d0;
    }
    for (int dir = 0; dir < 2; ++dir) {
        // dirA packs k0..k3, dirB packs k4..k7; density is .a of the same texel
        vec4 A = dir == 0 ? imageLoad(dirAIn, ivec2(j1, i1)) : imageLoad(dirBIn, ivec2(j1, i1));
        precise float Y2 = 1.0 / (A.r + A.g + A.b);
        if (Y2 < 2.0) {
            vec3 e = rn - A.rgb * Y2;
            precise float s2 = e.r * e.r + e.g * e.g + e.b * e.b;
            precise float dw = 1.0 / ((1.0 + 65535.0 * s2) * (A.a + u_eps));
            totwt = true;
            clipfix += dw * A.rgb;
        }
    }
    {
        vec4 D = imageLoad(dir4In, ivec2(j1, i1));
        precise float Y3 = 1.0 / (D.r + D.g + D.b);
        if (Y3 < 2.0) {
            vec3 e = rn - D.rgb * Y3;
            precise float s3 = e.r * e.r + e.g * e.g + e.b * e.b;
            precise float dw = 1.0 / ((1.0 + 65535.0 * s3) * (D.a + u_eps));
            totwt = true;
            clipfix += dw * D.rgb;
        }
    }
    if (!totwt) return;
    float maskval = 1.0;
    precise vec3 corrected = pixel;
    if (pixel.r > u_maxF.r && pixel.g > u_maxF.g && pixel.b > u_maxF.b) {
        precise float mult = u_whitePt / (0.299 * clipfix.r + 0.587 * clipfix.g + 0.114 * clipfix.b);
        corrected = clipfix * mult;
    } else {
        float nc0 = pixel.r <= u_maxF.r ? 1.0 : 0.0;
        float nc1 = pixel.g <= u_maxF.g ? 1.0 : 0.0;
        float nc2 = pixel.b <= u_maxF.b ? 1.0 : 0.0;
        if (nc0 == 0.0) {
            corrected.r = max(pixel.r, clipfix.r * ((nc1 * pixel.g + nc2 * pixel.b) /
                (nc1 * clipfix.g + nc2 * clipfix.b + u_eps)));
        }
        if (nc1 == 0.0) {
            corrected.g = max(pixel.g, clipfix.g * ((nc2 * pixel.b + nc0 * pixel.r) /
                (nc2 * clipfix.b + nc0 * clipfix.r + u_eps)));
        }
        if (nc2 == 0.0) {
            corrected.b = max(pixel.b, clipfix.b * ((nc0 * pixel.r + nc1 * pixel.g) /
                (nc0 * clipfix.r + nc1 * clipfix.g + u_eps)));
        }
        maskval = 1.0 - (nc0 + nc1 + nc2) / 5.0;
    }
    precise float Yc = 0.299 * corrected.r + 0.587 * corrected.g + 0.114 * corrected.b;
    if (Yc > u_whitePt) {
        precise float mult = u_whitePt / Yc;
        corrected *= mult;
    }
    imageStore(rgbBuf, o, vec4(corrected, 1.0));
    imageStore(maskBuf, o, vec4(maskval, 0.0, 0.0, 0.0));
}

// Half-resolve (RT half-res buffer fill, hilite_recon.cc:1128-1163).
// RT's sequential loop overwrites each half-res cell for every corrected
// full-res pixel, so the last pixel in scan order wins. This dispatch reads
// each 2x2 block in the same order and keeps the last corrected one, which
// is bit-identical given identical inputs; uncorrected blocks fall back to
// the nearest full-res cell, exactly RT's rescaleNearest fallback (even and
// odd ROI widths both map block o to full-res 2o).
void resolveHalf(ivec2 o) {
    if (o.x >= u_W || o.y >= u_H) return;
    ivec2 base = 2 * o;
    precise float r = imageLoad(rgbBuf, base).r;
    precise float g = imageLoad(rgbBuf, base).g;
    precise float b = imageLoad(rgbBuf, base).b;
    precise float outM = imageLoad(maskBuf, base).x;
    for (int i = 0; i < 2; ++i) {
        for (int j = 0; j < 2; ++j) {
            ivec2 f = base + ivec2(j, i);
            precise float m = imageLoad(maskBuf, f).x;
            if (m > 0.0) {
                r = imageLoad(rgbBuf, f).r;
                g = imageLoad(rgbBuf, f).g;
                b = imageLoad(rgbBuf, f).b;
                outM = m;
            }
        }
    }
    imageStore(halfR, o, vec4(r, 0.0, 0.0, 0.0));
    imageStore(halfG, o, vec4(g, 0.0, 0.0, 0.0));
    imageStore(halfB, o, vec4(b, 0.0, 0.0, 0.0));
    imageStore(maskHalf, o, vec4(outM, 0.0, 0.0, 0.0));
}

// Guided-filter linear combines (guidedfilter.cc apply ops): out = f(a,b,c).
// combineOp: 0 MUL (c=a*b), 1 SUBMUL (r=c-a*b), 2 DIVEPSILON (r=a/(b+eps)),
// 3 SUBMUL2 (same shape as SUBMUL, separate pass in RT),
// 4 ADDMUL (r=c+a*b: the upsample-output a*guide+b; addition commutes
// bit-exactly, and `precise` keeps the multiply-then-add order).
void combine(ivec2 o) {
    if (o.x >= u_W || o.y >= u_H) return;
    precise float a = imageLoad(imgA, o).x;
    precise float b = imageLoad(imgB, o).x;
    precise float c = imageLoad(imgC, o).x;
    precise float r = c;
    if (u_combineOp == 0) r = a * b;
    if (u_combineOp == 1 || u_combineOp == 3) r = c - a * b;
    if (u_combineOp == 2) r = a / (b + u_eps);
    if (u_combineOp == 4) r = c + a * b;
    imageStore(imgOut, o, vec4(r, 0.0, 0.0, 0.0));
}

// Gamma guide from half-res buffers (hilite_recon.cc:950-957): luminance by
// the xyz_cam row, then the pow2.2 table with truncation + lerp + clipping
// exactly like LUTf float indexing. The table is a 256x256 R32F sampled
// image uploaded once (white-level independent).
float lutGamma(int idx, float index, float diff) {
    precise float p1 = texelFetch(u_gamma, ivec2(idx & 255, idx >> 8), 0).r;
    precise float p2 = texelFetch(u_gamma, ivec2((idx + 1) & 255, (idx + 1) >> 8), 0).r - p1;
    precise float v = p1 + p2 * diff;
    return v;
}

void guide(ivec2 o) {
    if (o.x >= u_W || o.y >= u_H) return;
    precise float r = imageLoad(halfR, o).x;
    precise float g = imageLoad(halfG, o).x;
    precise float b = imageLoad(halfB, o).x;
    precise float lum = r * u_xyzRow.r + g * u_xyzRow.g + b * u_xyzRow.b;
    precise float gv;
    int idx = int(lum);
    if (lum < 0.0) {
        gv = texelFetch(u_gamma, ivec2(0, 0), 0).r;
    } else if (lum > 65534.0) {
        gv = texelFetch(u_gamma, ivec2(255, 255), 0).r;
    } else {
        gv = lutGamma(idx, lum, lum - float(idx));
    }
    imageStore(guideHalf, o, vec4(gv, 0.0, 0.0, 0.0));
}

// Bilinear merge-back (hilite_recon.cc:1190-1211): manual 4 taps per channel
// with edge clamp and RT's lerp order (intp(m, guided, orig) =
// m*(guided-orig)+orig). Half dims derive from the full-res dispatch dims
// exactly like the host's w2 = width/2 split.
float bilinTap(int plane, ivec2 p) {
    if (plane == 0) return imageLoad(halfR, p).x;
    if (plane == 1) return imageLoad(halfG, p).x;
    return imageLoad(halfB, p).x;
}

float bilinPlane(ivec2 base, int xi1, int yi1, float xfr, float yfr, int plane) {
    precise float bl = bilinTap(plane, base);
    precise float br = bilinTap(plane, ivec2(xi1, base.y));
    precise float tl = bilinTap(plane, ivec2(base.x, yi1));
    precise float tr = bilinTap(plane, ivec2(xi1, yi1));
    precise float bv = xfr * (br - bl) + bl;
    precise float tv = xfr * (tr - tl) + tl;
    precise float v = yfr * (tv - bv) + bv;
    return v;
}

void mergeBack(ivec2 o) {
    if (o.x >= u_W || o.y >= u_H) return;
    int xx = o.x / 2;
    int yy = o.y / 2;
    precise float m = imageLoad(maskHalf, ivec2(xx, yy)).x;
    vec3 orig = imageLoad(rgbBuf, o).rgb;
    if (m <= 0.0) {
        imageStore(mergedOut, o, vec4(orig, 1.0));
        return;
    }
    float fx = float(o.x) * 0.5;
    float fy = float(o.y) * 0.5;
    int xi = int(fx);
    int yi = int(fy);
    float xfr = fx - float(xi);
    float yfr = fy - float(yi);
    int halfW = u_W / 2;
    int halfH = u_H / 2;
    int xi1 = min(xi + 1, halfW - 1);
    int yi1 = min(yi + 1, halfH - 1);
    ivec2 base = ivec2(xi, yi);
    precise float gr = bilinPlane(base, xi1, yi1, xfr, yfr, 0);
    precise float gg = bilinPlane(base, xi1, yi1, xfr, yfr, 1);
    precise float gb = bilinPlane(base, xi1, yi1, xfr, yfr, 2);
    precise vec3 g = vec3(gr, gg, gb);
    precise vec3 outc = m * (g - orig) + orig;
    imageStore(mergedOut, o, vec4(outc, 1.0));
}

void main() {
    ivec2 o = ivec2((gl_GlobalInvocationID + u_dispatch_offset).xy);
    if (u_mode == 0) { blurH(o); return; }
    if (u_mode == 1) { blurV(o); return; }
    if (u_mode == 2) { reconstruct(o); return; }
    if (u_mode == 3) { resolveHalf(o); return; }
    if (u_mode == 4) { combine(o); return; }
    if (u_mode == 5) { guide(o); return; }
    mergeBack(o);
}
