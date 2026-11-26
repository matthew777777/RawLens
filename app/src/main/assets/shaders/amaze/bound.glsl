#import amaze
// AMaZE pass 3: variance selection + saturation bounding (C++ lines 599-686)
uniform sampler2D u_cda;   // vcd, hcd, vcdalt, hcdalt

uniform sampler2D u_cdb;   // dgintv, dginth

float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }
vec4 cda(ivec2 p) { return texelFetch(u_cda, p, 0); }

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    if (!inside(p, 4)) { emit(p, vec4(0.0)); return; }
    vec4 a0 = cda(p), aw = cda(p + ivec2(-2, 0)), ae = cda(p + ivec2(2, 0));
    vec4 an = cda(p + ivec2(0, -2)), aso = cda(p + ivec2(0, 2));
    float h0 = a0.g, hm2 = aw.g, hp2 = ae.g;
    float v0 = a0.r, vm2 = an.r, vp2 = aso.r;
    float hcdvar = 3.0 * (sq(hm2) + sq(h0) + sq(hp2)) - sq(hm2 + h0 + hp2);
    float hcdaltvar = 3.0 * (sq(aw.a) + sq(a0.a) + sq(ae.a))
                      - sq(aw.a + a0.a + ae.a);
    float vcdvar = 3.0 * (sq(vm2) + sq(v0) + sq(vp2)) - sq(vm2 + v0 + vp2);
    float vcdaltvar = 3.0 * (sq(an.b) + sq(a0.b) + sq(aso.b))
                      - sq(an.b + a0.b + aso.b);
    float hcd = hcdaltvar < hcdvar ? a0.a : h0;
    float vcd = vcdaltvar < vcdvar ? a0.b : v0;
    float c = Cf(p);
    float cw = Cf(p + ivec2(-1, 0)), ce = Cf(p + ivec2(1, 0)), cn = Cf(p + ivec2(0, -1)), cs = Cf(p + ivec2(0, 1));
    vec2 dg = texelFetch(u_cdb, p, 0).xy;
    if (isG(p.y, p.x)) {
        float ginth = -hcd + c;
        float gintv = -vcd + c;
        if (hcd > 0.0) {
            if (3.0 * hcd > ginth + c) {
                hcd = -median3(ginth, cw, ce) + c;
            } else {
                float hw = 1.0 - 3.0 * hcd / (EPS + ginth + c);
                hcd = hw * hcd + (1.0 - hw) * (-median3(ginth, cw, ce) + c);
            }
        }
        if (vcd > 0.0) {
            if (3.0 * vcd > gintv + c) {
                vcd = -median3(gintv, cn, cs) + c;
            } else {
                float vw = 1.0 - 3.0 * vcd / (EPS + gintv + c);
                vcd = vw * vcd + (1.0 - vw) * (-median3(gintv, cn, cs) + c);
            }
        }
        if (ginth > u_clip) hcd = -median3(ginth, cw, ce) + c;
        if (gintv > u_clip) vcd = -median3(gintv, cn, cs) + c;
        emit(p, vec4(vcd, hcd, dg));
    } else {
        float ginth = hcd + c;
        float gintv = vcd + c;
        if (hcd < 0.0) {
            if (3.0 * hcd < -(ginth + c)) {
                hcd = median3(ginth, cw, ce) - c;
            } else {
                float hw = 1.0 + 3.0 * hcd / (EPS + ginth + c);
                hcd = hw * hcd + (1.0 - hw) * (median3(ginth, cw, ce) - c);
            }
        }
        if (vcd < 0.0) {
            if (3.0 * vcd < -(gintv + c)) {
                vcd = median3(gintv, cn, cs) - c;
            } else {
                float vw = 1.0 + 3.0 * vcd / (EPS + gintv + c);
                vcd = vw * vcd + (1.0 - vw) * (median3(gintv, cn, cs) - c);
            }
        }
        if (ginth > u_clip) hcd = median3(ginth, cw, ce) - c;
        if (gintv > u_clip) vcd = median3(gintv, cn, cs) - c;
        emit(p, vec4(vcd, hcd, dg));
    }
}
