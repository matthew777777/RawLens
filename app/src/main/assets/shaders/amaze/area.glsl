#import amaze
// AMaZE pass 7: area interpolation in nyquist regions (C++ lines 931-967).
// The bounding-box test is disabled: processing is gated per site by the
// dilated nyquist flags only.
uniform sampler2D u_nyq2;
uniform sampler2D u_hvwt;
uniform sampler2D u_grad;  // cfa rides in .a (written by gradcd)

const int HALO = 8;
const int TW = LW + 2 * HALO;
const int TH = LH + 2 * HALO;
const int TN = TW * TH;

ivec2 T0() { return ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) - HALO; }
int SIDX(ivec2 q) { ivec2 l = q - T0(); return l.y * TW + l.x; }
int SIDX_H(ivec2 q) { ivec2 l = ivec2(q.x & ~1, q.y) - T0(); return l.y * TW + l.x; }

shared float s_nyq2[TN];
shared float s_hvwt[TN];
shared float s_cfa[TN];
float n2(ivec2 q) { return s_nyq2[SIDX_H(q)]; }
float Cf(ivec2 q) { return s_cfa[SIDX(q)]; }

void load_tile() {
    ivec2 t0 = T0();
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = clamp(t0 + ivec2(i % TW, i / TW), ivec2(0), u_size - 1);
        s_nyq2[i] = texelFetch(u_nyq2, q, 0).r;
        s_hvwt[i] = texelFetch(u_hvwt, q, 0).r;
        s_cfa[i] = texelFetch(u_grad, q, 0).a;
    }
    memoryBarrierShared();
    barrier();
}

layout(binding = 0, r32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, float v) { imageStore(img_out, p, vec4(v)); }
void main() {
    load_tile();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    float base = s_hvwt[SIDX(p)];
    emit(p, base);
    if ((p.x & 1) == 1) return;
    ivec2 s = ivec2(site_x(p), p.y);
    if (n2(s) < 0.5) return;
    float sumcfa = 0.0, sumh = 0.0, sumv = 0.0, sumsqh = 0.0, sumsqv = 0.0, areawt = 0.0;
    for (int j = -6; j <= 6; j += 2) {
        for (int k = -6; k <= 6; k += 2) {
            ivec2 q = s + ivec2(j, k);
            if (n2(q) > 0.5) {
                float cf = Cf(q);
                sumcfa += cf;
                sumh += Cf(q + ivec2(-1, 0)) + Cf(q + ivec2(1, 0));
                sumv += Cf(q + ivec2(0, -1)) + Cf(q + ivec2(0, 1));
                sumsqh += sq(cf - Cf(q + ivec2(-1, 0))) + sq(cf - Cf(q + ivec2(1, 0)));
                sumsqv += sq(cf - Cf(q + ivec2(0, -1))) + sq(cf - Cf(q + ivec2(0, 1)));
                areawt += 1.0;
            }
        }
    }
    sumh = sumcfa - 0.5 * sumh;
    sumv = sumcfa - 0.5 * sumv;
    areawt = 0.5 * areawt;
    float hcdvar = EPSSQ + abs(areawt * sumsqh - sumh * sumh);
    float vcdvar = EPSSQ + abs(areawt * sumsqv - sumv * sumv);
    emit(p, hcdvar / (vcdvar + hcdvar));
}
