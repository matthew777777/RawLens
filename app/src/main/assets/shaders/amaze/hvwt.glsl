#import amaze
// AMaZE pass 4+5 (fused): adaptive weights hvwt at R/B sites (C++ lines
// 741-783) and the nyquist texture test (C++ lines 836-857). One invocation per
// R/B site; the squared colour-difference difference of the test is taken from
// vcd', hcd' directly.
uniform sampler2D u_cd2;   // vcd', hcd', dgintv, dginth
uniform sampler2D u_grad;  // d0, d1, delhvsqsum
// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).

float dhs(ivec2 q) { return texelFetch(u_grad, q, 0).b; }   // delhvsqsum
float cds(ivec2 q) { vec2 t = texelFetch(u_cd2, q, 0).rg; return sq(t.r - t.g); }   // cddiffsq

layout(binding = 0, r32f) writeonly uniform highp image2D img_hvwt;
layout(binding = 1, r32f) writeonly uniform highp image2D img_nyq;

void main() {
    ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
    ivec2 p = ivec2(hp.x << 1, hp.y);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    ivec2 s = ivec2(site_x(p), p.y);
    float hv = 0.0, val = 0.0;
    if (inside(s, 6)) {
        vec4 c0 = texelFetch(u_cd2, s, 0);
        vec4 n1 = texelFetch(u_cd2, s + ivec2(0, -1), 0), n2 = texelFetch(u_cd2, s + ivec2(0, -2), 0);
        vec4 s1 = texelFetch(u_cd2, s + ivec2(0, 1), 0), s2 = texelFetch(u_cd2, s + ivec2(0, 2), 0);
        vec4 w1 = texelFetch(u_cd2, s + ivec2(-1, 0), 0), w2 = texelFetch(u_cd2, s + ivec2(-2, 0), 0);
        vec4 e1 = texelFetch(u_cd2, s + ivec2(1, 0), 0), e2 = texelFetch(u_cd2, s + ivec2(2, 0), 0);
        float n3 = texelFetch(u_cd2, s + ivec2(0, -3), 0).r, s3 = texelFetch(u_cd2, s + ivec2(0, 3), 0).r;
        float w3 = texelFetch(u_cd2, s + ivec2(-3, 0), 0).g, e3 = texelFetch(u_cd2, s + ivec2(3, 0), 0).g;
        float uave = c0.r + n1.r + n2.r + n3;
        float dave = c0.r + s1.r + s2.r + s3;
        float lave = c0.g + w1.g + w2.g + w3;
        float rave = c0.g + e1.g + e2.g + e3;
        float varu = sq(c0.r - uave) + sq(n1.r - uave) + sq(n2.r - uave) + sq(n3 - uave);
        float vard = sq(c0.r - dave) + sq(s1.r - dave) + sq(s2.r - dave) + sq(s3 - dave);
        float varl = sq(c0.g - lave) + sq(w1.g - lave) + sq(w2.g - lave) + sq(w3 - lave);
        float varr = sq(c0.g - rave) + sq(e1.g - rave) + sq(e2.g - rave) + sq(e3 - rave);
        vec3 gn = texelFetch(u_grad, s + ivec2(0, -1), 0).rgb, gs = texelFetch(u_grad, s + ivec2(0, 1), 0).rgb;
        vec3 gw = texelFetch(u_grad, s + ivec2(-1, 0), 0).rgb, ge = texelFetch(u_grad, s + ivec2(1, 0), 0).rgb;
        float hwt = gw.g / (gw.g + ge.g);
        float vwt = gn.r / (gs.r + gn.r);
        float vcdvar = EPSSQ + vwt * vard + (1.0 - vwt) * varu;
        float hcdvar = EPSSQ + hwt * varr + (1.0 - hwt) * varl;
        float varu1 = c0.b + n1.b + n2.b;
        float vard1 = c0.b + s1.b + s2.b;
        float varl1 = c0.a + w1.a + w2.a;
        float varr1 = c0.a + e1.a + e2.a;
        float vcdvar1 = EPSSQ + vwt * vard1 + (1.0 - vwt) * varu1;
        float hcdvar1 = EPSSQ + hwt * varr1 + (1.0 - hwt) * varl1;
        float varwt = hcdvar / (vcdvar + hcdvar);
        float diffwt = hcdvar1 / (vcdvar1 + hcdvar1);
        hv = ((0.5 - varwt) * (0.5 - diffwt) > 0.0 && abs(0.5 - diffwt) < abs(0.5 - varwt)) ? varwt : diffwt;

        val = GAUSSODD[0] * sq(c0.r - c0.g)
            + GAUSSODD[1] * (cds(s + ivec2(1, 1)) + cds(s + ivec2(1, -1)) + cds(s + ivec2(-1, -1)) + cds(s + ivec2(-1, 1)))
            + GAUSSODD[2] * (sq(n2.r - n2.g) + sq(w2.r - w2.g) + sq(e2.r - e2.g) + sq(s2.r - s2.g))
            + GAUSSODD[3] * (cds(s + ivec2(2, 2)) + cds(s + ivec2(2, -2)) + cds(s + ivec2(-2, -2)) + cds(s + ivec2(-2, 2)))
            - GAUSSGRAD[0] * dhs(s)
            - GAUSSGRAD[1] * (gn.b + ge.b + gw.b + gs.b)
            - GAUSSGRAD[2] * (dhs(s + ivec2(1, 1)) + dhs(s + ivec2(1, -1)) + dhs(s + ivec2(-1, -1)) + dhs(s + ivec2(-1, 1)))
            - GAUSSGRAD[3] * (dhs(s + ivec2(0, -2)) + dhs(s + ivec2(-2, 0)) + dhs(s + ivec2(2, 0)) + dhs(s + ivec2(0, 2)))
            - GAUSSGRAD[4] * (dhs(s + ivec2(-1, -2)) + dhs(s + ivec2(1, -2)) + dhs(s + ivec2(-2, -1)) + dhs(s + ivec2(2, -1))
                              + dhs(s + ivec2(-2, 1)) + dhs(s + ivec2(2, 1)) + dhs(s + ivec2(-1, 2)) + dhs(s + ivec2(1, 2)))
            - GAUSSGRAD[5] * (dhs(s + ivec2(2, 2)) + dhs(s + ivec2(2, -2)) + dhs(s + ivec2(-2, -2)) + dhs(s + ivec2(-2, 2)));
    }
    imageStore(img_hvwt, hp, vec4(hv));
    imageStore(img_nyq, hp, vec4(val));
}
