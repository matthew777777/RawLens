// SPDX-License-Identifier: GPL-3.0-or-later
// HDR bracket spatial deghost: per-pixel PhotonCamera-style robust blend of
// the warped alternate toward the reference. Same Wiener family as the
// frequency-domain HdrTileDeghost bins (Hasinoff et al. 2016 section 5,
// hdr-plus-swift frequency.metal), evaluated spatially so the whole bracket
// merge stays on Vulkan:
//
//   D       = (ref - gain*alt)^2                      (gain-matched residual)
//   noise   = varRef + gain^2*varAlt*1.25             (DNG Poisson-Gaussian
//             profiles; the warped side is variance-inflated exactly like
//             HdrTileDeghost.WARP_VARIANCE_INFLATION)
//   w       = D / (D + strength*motion*hi*noise)      (collapse-to-reference
//             weight; strength already carries HdrPlusRobustness bracket
//             damping, motion the per-companion maxMotionForFactor ceiling,
//             hi the longer-companion highlightsNorm discount)
//   motionReject gates fully broken correspondence to the reference even
//   when the Wiener term is small (HdrTileDeghost motionReject twin).
//   kept    = w*ref*refScale + (1-w)*alt               (mov-domain output,
//             refScale = gain*calRef/calMov twin of referenceScale)
//
// Matched pixels keep the alternate (photon-weighted denoise downstream);
// motion blur, ghosts, and misregistration collapse to the sharp reference.
// Reference-clipped pixels are still rescued downstream by darktable's
// clipped-winner fallback in hdr_accumulate (clipped sites carry no
// matchable structure, like the tile path).
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
layout(local_size_x = 8, local_size_y = 8) in;
uniform sampler2D u_ref;
uniform sampler2D u_mov;
uniform ivec2 u_size;
uniform ivec4 u_fc;
uniform vec4 u_noiseSRef;
uniform vec4 u_noiseORef;
uniform vec4 u_noiseSMov;
uniform vec4 u_noiseOMov;
uniform float u_gain;
uniform float u_refScale;
uniform float u_strength;
uniform float u_maxMotion;
uniform float u_factor;
layout(binding = 0, r32f) writeonly uniform highp image2D img_kept;
layout(binding = 1, r32f) writeonly uniform highp image2D img_w;
bool finite(float x) { return !isnan(x) && !isinf(x); }
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, u_size))) return;
    int ch = u_fc[((p.y & 1) << 1) | (p.x & 1)];
    float ref = texelFetch(u_ref, p, 0).r;
    float mov = texelFetch(u_mov, p, 0).r;
    if (!finite(ref) || !finite(mov)) {
        imageStore(img_kept, p, vec4(mov));
        imageStore(img_w, p, vec4(1.0));
        return;
    }
    float g = max(u_gain, 1e-9);
    float matched = mov * g;
    float d = ref - matched;
    float varR = u_noiseSRef[ch] * max(ref, 0.0) + u_noiseORef[ch];
    float varM = (u_noiseSMov[ch] * max(mov / g, 0.0) + u_noiseOMov[ch]) * g * g * 1.25;
    float noise = max(varR + varM, 1e-12);
    float sigma = sqrt(0.5 * (varR + varM) + 1e-12);
    float mismatch = 1.0;
    if (finite(sigma) && sigma > 0.0) {
        mismatch = clamp(abs(d) / sigma * 0.15, 0.0, 1.0);
    }
    float ceiling = max(u_maxMotion, 1.0);
    float motion = clamp(ceiling - (mismatch - 0.02) * (ceiling - 1.0) / 0.15, 1.0, ceiling);
    float motionReject = clamp((mismatch - 0.3) / 0.3, 0.0, 1.0);
    float hi = 1.0;
    if (u_factor > 1.001) {
        float f = clamp((mov - 0.5) / 0.49, 0.0, 1.0);
        hi = clamp((1.0 - f) * (1.0 - f), 0.04 / min(u_factor, 4.0), 1.0);
    }
    float d2 = d * d;
    float den = u_strength * motion * hi * noise;
    float w = clamp(d2 / (d2 + den), 0.0, 1.0);
    w = max(w, motionReject);
    float kept = w * ref * u_refScale + (1.0 - w) * mov;
    imageStore(img_kept, p, vec4(kept));
    imageStore(img_w, p, vec4(w));
}
