#import amaze
// AMaZE pass 8b: nyquist refinement using G curvatures (C++ lines 994-1012).
// The bounding-box test is disabled: refinement runs wherever the dilated
// per-site flags say so.
uniform sampler2D u_gd;      // rgbgreen, dgrb0, dgrb2h, dgrb2v
uniform sampler2D u_cd2;
uniform sampler2D u_nyq2;
uniform sampler2D u_grad;    // cfa rides in .a (written by gradcd)

const int HALO = 3;
const int TW = LW + 2 * HALO;
const int TH = LH + 2 * HALO;
const int TN = TW * TH;

ivec2 T0() { return ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) - HALO; }
int SIDX(ivec2 q) { ivec2 l = q - T0(); return l.y * TW + l.x; }
int SIDX_H(ivec2 q) { ivec2 l = ivec2(q.x & ~1, q.y) - T0(); return l.y * TW + l.x; }

shared vec4 s_gd[TN];
shared vec4 s_cd2[TN];
shared float s_nyq2[TN];
shared float s_cfa[TN];
float V(ivec2 p) { return s_cd2[SIDX(p)].r; }
float H(ivec2 p) { return s_cd2[SIDX(p)].g; }
float d2h(ivec2 q) { return s_gd[SIDX_H(q)].b; }
float d2v(ivec2 q) { return s_gd[SIDX_H(q)].a; }
float n2(ivec2 q) { return s_nyq2[SIDX_H(q)]; }
float Cf(ivec2 q) { return s_cfa[SIDX(q)]; }

void load_tile() {
    ivec2 t0 = T0();
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = clamp(t0 + ivec2(i % TW, i / TW), ivec2(0), u_size - 1);
        s_gd[i] = texelFetch(u_gd, q, 0);
        s_cd2[i] = texelFetch(u_cd2, q, 0);
        s_nyq2[i] = texelFetch(u_nyq2, q, 0).r;
        s_cfa[i] = texelFetch(u_grad, q, 0).a;
    }
    memoryBarrierShared();
    barrier();
}

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }
float d0ref(ivec2 s) {   // refined colour difference at a nyquist R/B site
    float gvarh = EPSSQ + (GQUINC[0] * d2h(s)
        + GQUINC[1] * (d2h(s + ivec2(1, 1)) + d2h(s + ivec2(1, -1)) + d2h(s + ivec2(-1, -1)) + d2h(s + ivec2(-1, 1)))
        + GQUINC[2] * (d2h(s + ivec2(0, -2)) + d2h(s + ivec2(-2, 0)) + d2h(s + ivec2(2, 0)) + d2h(s + ivec2(0, 2)))
        + GQUINC[3] * (d2h(s + ivec2(2, 2)) + d2h(s + ivec2(2, -2)) + d2h(s + ivec2(-2, -2)) + d2h(s + ivec2(-2, 2))));
    float gvarv = EPSSQ + (GQUINC[0] * d2v(s)
        + GQUINC[1] * (d2v(s + ivec2(1, 1)) + d2v(s + ivec2(1, -1)) + d2v(s + ivec2(-1, -1)) + d2v(s + ivec2(-1, 1)))
        + GQUINC[2] * (d2v(s + ivec2(0, -2)) + d2v(s + ivec2(-2, 0)) + d2v(s + ivec2(2, 0)) + d2v(s + ivec2(0, 2)))
        + GQUINC[3] * (d2v(s + ivec2(2, 2)) + d2v(s + ivec2(2, -2)) + d2v(s + ivec2(-2, -2)) + d2v(s + ivec2(-2, 2))));
    return (H(s) * gvarv + V(s) * gvarh) / (gvarv + gvarh);
}
void main() {
    load_tile();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    vec4 base = s_gd[SIDX(p)];
    vec4 o = vec4(base.r, base.g, 0.0, 0.0);
    if (!isG(p.y, p.x) && n2(p) > 0.5) {
        o.r = Cf(p) + d0ref(p);            // per-pixel green
    }
    if ((p.x & 1) == 0) {
        ivec2 s = ivec2(site_x(p), p.y);   // anchored site's half-grid d0
        if (n2(s) > 0.5) {
            o.g = d0ref(s);
        }
    }
    emit(p, o);
}
