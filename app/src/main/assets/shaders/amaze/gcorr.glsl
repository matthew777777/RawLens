#import amaze
// AMaZE pass 12: G interpolation using R+B, and Dgrb split (C++ lines 1312-1400)
uniform sampler2D u_pmrbint;   // pmwt', rbint (half-grid)
uniform sampler2D u_gd2;       // rgbgreen', dgrb0'
uniform sampler2D u_hvwt;      // firmed hvwt
uniform sampler2D u_grad;

// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
float hv(ivec2 q) { return texelFetch(u_hvwt, ivec2(q.x >> 1, q.y), 0).r; }
float pm(ivec2 q) { return texelFetch(u_pmrbint, ivec2(q.x >> 1, q.y), 0).r; }
float rbint(ivec2 q) { return texelFetch(u_pmrbint, ivec2(q.x >> 1, q.y), 0).g; }
float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }

float d0_new(ivec2 s) {   // re-interpolated colour difference at R/B site s
    float own_rb = rbint(s);
    // C++ half-grid offsets: -/+ rowStride lands two rows away, -/+ 1 one R/B site away
    float rbu = rbint(s + ivec2(0, -2)), rbd = rbint(s + ivec2(0, 2));
    float rbl = rbint(s + ivec2(-2, 0)), rbr = rbint(s + ivec2(2, 0));
    float cu = Cf(s + ivec2(0, -1)), cd = Cf(s + ivec2(0, 1)), cl = Cf(s + ivec2(-1, 0)), cr = Cf(s + ivec2(1, 0));
    float gu0 = texelFetch(u_grad, s + ivec2(0, -1), 0).r, gd0 = texelFetch(u_grad, s + ivec2(0, 1), 0).r;
    float gl1 = texelFetch(u_grad, s + ivec2(-1, 0), 0).g, gr1 = texelFetch(u_grad, s + ivec2(1, 0), 0).g;
    // colour ratios for G interpolation
    float cru = cu * 2.0 / (EPS + own_rb + rbu);
    float crd = cd * 2.0 / (EPS + own_rb + rbd);
    float crl = cl * 2.0 / (EPS + own_rb + rbl);
    float crr = cr * 2.0 / (EPS + own_rb + rbr);
    float gu = abs(1.0 - cru) < ARTHRESH ? own_rb * cru : cu + 0.5 * (own_rb - rbu);
    float gd = abs(1.0 - crd) < ARTHRESH ? own_rb * crd : cd + 0.5 * (own_rb - rbd);
    float gl = abs(1.0 - crl) < ARTHRESH ? own_rb * crl : cl + 0.5 * (own_rb - rbl);
    float gr = abs(1.0 - crr) < ARTHRESH ? own_rb * crr : cr + 0.5 * (own_rb - rbr);
    float gintv = (gu0 * gd + gd0 * gu) / (gd0 + gu0);
    float ginth = (gl1 * gr + gr1 * gl) / (gl1 + gr1);
    if (gintv < own_rb) {
        if (2.0 * gintv < own_rb) {
            gintv = median3(gintv, cu, cd);
        } else {
            float vw = 2.0 * (own_rb - gintv) / (EPS + gintv + own_rb);
            gintv = vw * gintv + (1.0 - vw) * median3(gintv, cu, cd);
        }
    }
    if (ginth < own_rb) {
        if (2.0 * ginth < own_rb) {
            ginth = median3(ginth, cl, cr);
        } else {
            float hw = 2.0 * (own_rb - ginth) / (EPS + ginth + own_rb);
            ginth = hw * ginth + (1.0 - hw) * median3(ginth, cl, cr);
        }
    }
    if (ginth > u_clip) ginth = median3(ginth, cl, cr);
    if (gintv > u_clip) gintv = median3(gintv, cu, cd);
    float hvf = hv(s);
    return ginth * (1.0 - hvf) + gintv * hvf - Cf(s);
}
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    vec4 o = vec4(0.0);
    if ((p.x & 1) == 1) {
        // odd columns carry only the per-pixel green value
        float g0 = texelFetch(u_gd2, p, 0).r;
        if (!isG(p.y, p.x) && inside(p, 12) && !(abs(0.5 - pm(p)) < abs(0.5 - hv(p)))) {
            o.r = Cf(p) + d0_new(p);
        } else {
            o.r = g0;
        }
        emit(p, o);
        return;
    }
    ivec2 s = ivec2(site_x(p), p.y);
    float d0;
    if (inside(s, 12) && !(abs(0.5 - pm(s)) < abs(0.5 - hv(s)))) {
        d0 = d0_new(s);
    } else {
        d0 = texelFetch(u_gd2, p, 0).g;   // dgrb0' at this half-grid texel
    }
    // RawTherapee only re-interpolates green at an R/B site.  At a measured
    // green site rgbgreen stays the original CFA value; applying d0_new()
    // there reads R/B-only half-grid data and produces alternating yellow /
    // magenta zippering.
    float green = s == p ? Cf(p) + d0 : Cf(p);
    // split G-B from G-R at B sites (C++ lines 1396-1400)
    if (FC(s.y, s.x) == 2) {
        o = vec4(green, 0.0, d0, 0.0);
    } else {
        o = vec4(green, d0, 0.0, 0.0);
    }
    emit(p, o);
}
