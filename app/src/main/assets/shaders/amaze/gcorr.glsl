#import amaze
// AMaZE pass 12: G interpolation using R+B, and Dgrb split (C++ lines 1312-1400)
uniform sampler2D u_pmrbint;   // pmwt', rbint (half-grid)
uniform sampler2D u_gd2;       // rgbgreen', dgrb0'
uniform sampler2D u_hvwt;      // firmed hvwt
uniform sampler2D u_grad;

const int HALO = 3;
const int TW = LW + 2 * HALO;
const int TH = LH + 2 * HALO;
const int TN = TW * TH;

ivec2 T0() { return ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) - HALO; }
int SIDX(ivec2 q) { ivec2 l = q - T0(); return l.y * TW + l.x; }
int SIDX_H(ivec2 q) { ivec2 l = ivec2(q.x & ~1, q.y) - T0(); return l.y * TW + l.x; }

shared vec4 s_pmrbint[TN];
shared vec4 s_gd2[TN];
shared float s_hvwt[TN];
shared vec4 s_grad[TN];   // .a carries the cfa (written by gradcd)
float hv(ivec2 q) { return s_hvwt[SIDX_H(q)]; }
float pm(ivec2 q) { return s_pmrbint[SIDX_H(q)].r; }
float rbint(int s_lin, int dh) { return s_pmrbint[SIDX(hh((s_lin >> 1) + dh))].g; }
float dw0(ivec2 p) { return s_grad[SIDX(p)].r; }
float dw1(ivec2 p) { return s_grad[SIDX(p)].g; }
float Cf(ivec2 q) { return s_grad[SIDX(q)].a; }

void load_tile() {
    ivec2 t0 = T0();
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = clamp(t0 + ivec2(i % TW, i / TW), ivec2(0), u_size - 1);
        s_pmrbint[i] = texelFetch(u_pmrbint, q, 0);
        s_gd2[i] = texelFetch(u_gd2, q, 0);
        s_hvwt[i] = texelFetch(u_hvwt, q, 0).r;
        s_grad[i] = texelFetch(u_grad, q, 0);
    }
    memoryBarrierShared();
    barrier();
}

layout(binding = 0, rgba16f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }

float d0_new(ivec2 s) {   // re-interpolated colour difference at R/B site s
    int l = lin(s);
    float own_rb = s_pmrbint[SIDX_H(s)].g;
    // colour ratios for G interpolation
    float cru = Cf(s + ivec2(0, -1)) * 2.0 / (EPS + own_rb + rbint(l, -u_size.x));
    float crd = Cf(s + ivec2(0, 1)) * 2.0 / (EPS + own_rb + rbint(l, u_size.x));
    float crl = Cf(s + ivec2(-1, 0)) * 2.0 / (EPS + own_rb + rbint(l, -1));
    float crr = Cf(s + ivec2(1, 0)) * 2.0 / (EPS + own_rb + rbint(l, 1));
    float gu = abs(1.0 - cru) < ARTHRESH ? own_rb * cru : Cf(s + ivec2(0, -1)) + 0.5 * (own_rb - rbint(l, -u_size.x));
    float gd = abs(1.0 - crd) < ARTHRESH ? own_rb * crd : Cf(s + ivec2(0, 1)) + 0.5 * (own_rb - rbint(l, u_size.x));
    float gl = abs(1.0 - crl) < ARTHRESH ? own_rb * crl : Cf(s + ivec2(-1, 0)) + 0.5 * (own_rb - rbint(l, -1));
    float gr = abs(1.0 - crr) < ARTHRESH ? own_rb * crr : Cf(s + ivec2(1, 0)) + 0.5 * (own_rb - rbint(l, 1));
    float gintv = (dw0(s + ivec2(0, -1)) * gd + dw0(s + ivec2(0, 1)) * gu) / (dw0(s + ivec2(0, 1)) + dw0(s + ivec2(0, -1)));
    float ginth = (dw1(s + ivec2(-1, 0)) * gr + dw1(s + ivec2(1, 0)) * gl) / (dw1(s + ivec2(-1, 0)) + dw1(s + ivec2(1, 0)));
    if (gintv < own_rb) {
        if (2.0 * gintv < own_rb) {
            gintv = median3(gintv, Cf(s + ivec2(0, -1)), Cf(s + ivec2(0, 1)));
        } else {
            float vw = 2.0 * (own_rb - gintv) / (EPS + gintv + own_rb);
            gintv = vw * gintv + (1.0 - vw) * median3(gintv, Cf(s + ivec2(0, -1)), Cf(s + ivec2(0, 1)));
        }
    }
    if (ginth < own_rb) {
        if (2.0 * ginth < own_rb) {
            ginth = median3(ginth, Cf(s + ivec2(-1, 0)), Cf(s + ivec2(1, 0)));
        } else {
            float hw = 2.0 * (own_rb - ginth) / (EPS + ginth + own_rb);
            ginth = hw * ginth + (1.0 - hw) * median3(ginth, Cf(s + ivec2(-1, 0)), Cf(s + ivec2(1, 0)));
        }
    }
    if (ginth > u_clip) ginth = median3(ginth, Cf(s + ivec2(-1, 0)), Cf(s + ivec2(1, 0)));
    if (gintv > u_clip) gintv = median3(gintv, Cf(s + ivec2(0, -1)), Cf(s + ivec2(0, 1)));
    float hvf = hv(s);
    return ginth * (1.0 - hvf) + gintv * hvf - Cf(s);
}
void main() {
    load_tile();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    vec4 o = vec4(0.0);
    if ((p.x & 1) == 1) {
        // odd columns carry only the per-pixel green value
        float g0 = s_gd2[SIDX(p)].r;
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
        d0 = s_gd2[SIDX(p)].g;   // dgrb0' at this half-grid texel
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
