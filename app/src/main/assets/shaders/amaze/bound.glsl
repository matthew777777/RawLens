#import amaze
// AMaZE pass 3: variance selection + saturation bounding (C++ lines 599-686)
uniform sampler2D u_cda;   // vcd, hcd, vcdalt, hcdalt
uniform sampler2D u_grad;  // cfa rides in .a (written by gradcd)

const int HALO = 2;
const int TW = LW + 2 * HALO;
const int TH = LH + 2 * HALO;
const int TN = TW * TH;

ivec2 T0() { return ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) - HALO; }
int SIDX(ivec2 q) { ivec2 l = q - T0(); return l.y * TW + l.x; }
int SIDX_H(ivec2 q) { ivec2 l = ivec2(q.x & ~1, q.y) - T0(); return l.y * TW + l.x; }

shared vec4 s_cda[TN];
shared float s_cfa[TN];
float V(ivec2 p) { return s_cda[SIDX(p)].r; }
float H(ivec2 p) { return s_cda[SIDX(p)].g; }
float Va(ivec2 p) { return s_cda[SIDX(p)].b; }
float Ha(ivec2 p) { return s_cda[SIDX(p)].a; }
float Cf(ivec2 q) { return s_cfa[SIDX(q)]; }

void load_tile() {
    ivec2 t0 = T0();
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = clamp(t0 + ivec2(i % TW, i / TW), ivec2(0), u_size - 1);
        s_cda[i] = texelFetch(u_cda, q, 0);
        s_cfa[i] = texelFetch(u_grad, q, 0).a;
    }
    memoryBarrierShared();
    barrier();
}

layout(binding = 0, rgba16f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }
void main() {
    load_tile();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    if (!inside(p, 4)) { emit(p, vec4(0.0)); return; }
    float h0 = H(p), hm2 = H(p + ivec2(-2, 0)), hp2 = H(p + ivec2(2, 0));
    float v0 = V(p), vm2 = V(p + ivec2(0, -2)), vp2 = V(p + ivec2(0, 2));
    float hcdvar = 3.0 * (sq(hm2) + sq(h0) + sq(hp2)) - sq(hm2 + h0 + hp2);
    float hcdaltvar = 3.0 * (sq(Ha(p + ivec2(-2, 0))) + sq(Ha(p)) + sq(Ha(p + ivec2(2, 0))))
                      - sq(Ha(p + ivec2(-2, 0)) + Ha(p) + Ha(p + ivec2(2, 0)));
    float vcdvar = 3.0 * (sq(vm2) + sq(v0) + sq(vp2)) - sq(vm2 + v0 + vp2);
    float vcdaltvar = 3.0 * (sq(Va(p + ivec2(0, -2))) + sq(Va(p)) + sq(Va(p + ivec2(0, 2))))
                      - sq(Va(p + ivec2(0, -2)) + Va(p) + Va(p + ivec2(0, 2)));
    float hcd = hcdaltvar < hcdvar ? Ha(p) : h0;
    float vcd = vcdaltvar < vcdvar ? Va(p) : v0;
    float c = Cf(p);
    if (isG(p.y, p.x)) {
        float ginth = -hcd + c;
        float gintv = -vcd + c;
        if (hcd > 0.0) {
            if (3.0 * hcd > ginth + c) {
                hcd = -median3(ginth, Cf(p + ivec2(-1, 0)), Cf(p + ivec2(1, 0))) + c;
            } else {
                float hw = 1.0 - 3.0 * hcd / (EPS + ginth + c);
                hcd = hw * hcd + (1.0 - hw) * (-median3(ginth, Cf(p + ivec2(-1, 0)), Cf(p + ivec2(1, 0))) + c);
            }
        }
        if (vcd > 0.0) {
            if (3.0 * vcd > gintv + c) {
                vcd = -median3(gintv, Cf(p + ivec2(0, -1)), Cf(p + ivec2(0, 1))) + c;
            } else {
                float vw = 1.0 - 3.0 * vcd / (EPS + gintv + c);
                vcd = vw * vcd + (1.0 - vw) * (-median3(gintv, Cf(p + ivec2(0, -1)), Cf(p + ivec2(0, 1))) + c);
            }
        }
        if (ginth > u_clip) hcd = -median3(ginth, Cf(p + ivec2(-1, 0)), Cf(p + ivec2(1, 0))) + c;
        if (gintv > u_clip) vcd = -median3(gintv, Cf(p + ivec2(0, -1)), Cf(p + ivec2(0, 1))) + c;
        emit(p, vec4(vcd, hcd, 0.0, 0.0));
    } else {
        float ginth = hcd + c;
        float gintv = vcd + c;
        if (hcd < 0.0) {
            if (3.0 * hcd < -(ginth + c)) {
                hcd = median3(ginth, Cf(p + ivec2(-1, 0)), Cf(p + ivec2(1, 0))) - c;
            } else {
                float hw = 1.0 + 3.0 * hcd / (EPS + ginth + c);
                hcd = hw * hcd + (1.0 - hw) * (median3(ginth, Cf(p + ivec2(-1, 0)), Cf(p + ivec2(1, 0))) - c);
            }
        }
        if (vcd < 0.0) {
            if (3.0 * vcd < -(gintv + c)) {
                vcd = median3(gintv, Cf(p + ivec2(0, -1)), Cf(p + ivec2(0, 1))) - c;
            } else {
                float vw = 1.0 + 3.0 * vcd / (EPS + gintv + c);
                vcd = vw * vcd + (1.0 - vw) * (median3(gintv, Cf(p + ivec2(0, -1)), Cf(p + ivec2(0, 1))) - c);
            }
        }
        if (ginth > u_clip) hcd = median3(ginth, Cf(p + ivec2(-1, 0)), Cf(p + ivec2(1, 0))) - c;
        if (gintv > u_clip) vcd = median3(gintv, Cf(p + ivec2(0, -1)), Cf(p + ivec2(0, 1))) - c;
        emit(p, vec4(vcd, hcd, sq(vcd - hcd), 0.0));
    }
}
