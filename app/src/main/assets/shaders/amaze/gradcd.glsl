#import amaze
// AMaZE pass 1+2 (fused): horizontal / vertical gradients (C++ lines 368-375)
// and directional colour differences (C++ lines 449-531). All taps lie on the
// row and column of p (+/- 4): 17 direct fetches, no shared memory.
float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_grad;
layout(binding = 1, rgba32f) writeonly uniform highp image2D img_a;
layout(binding = 2, rgba32f) writeonly uniform highp image2D img_b;
// img_grad: d0, d1, delhvsqsum; img_a: vcd, hcd, vcdalt, hcdalt; img_b: dgintv, dginth

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    float c = Cf(p);
    float n1 = 0.0, n2 = 0.0, s1 = 0.0, s2 = 0.0, w1 = 0.0, w2 = 0.0, e1 = 0.0, e2 = 0.0;
    vec4 g = vec4(0.0);
    if (inside(p, 2)) {
        n1 = Cf(p + ivec2(0, -1)); n2 = Cf(p + ivec2(0, -2));
        s1 = Cf(p + ivec2(0, 1)); s2 = Cf(p + ivec2(0, 2));
        w1 = Cf(p + ivec2(-1, 0)); w2 = Cf(p + ivec2(-2, 0));
        e1 = Cf(p + ivec2(1, 0)); e2 = Cf(p + ivec2(2, 0));
        float delh = abs(e1 - w1);
        float delv = abs(s1 - n1);
        g = vec4(EPS + abs(s2 - c) + abs(c - n2) + delv, EPS + abs(e2 - c) + abs(c - w2) + delh,
                 delh * delh + delv * delv, 0.0);
    }
    imageStore(img_grad, p, g);
    if (!inside(p, 4)) {
        imageStore(img_a, p, vec4(0.0));
        imageStore(img_b, p, vec4(0.0));
        return;
    }
    float n3 = Cf(p + ivec2(0, -3)), n4 = Cf(p + ivec2(0, -4));
    float s3 = Cf(p + ivec2(0, 3)), s4 = Cf(p + ivec2(0, 4));
    float w3 = Cf(p + ivec2(-3, 0)), w4 = Cf(p + ivec2(-4, 0));
    float e3 = Cf(p + ivec2(3, 0)), e4 = Cf(p + ivec2(4, 0));
    // gradient weights one and two pixels away
    float dn1 = EPS + abs(s1 - n1) + abs(n1 - n3) + abs(c - n2);
    float dn2 = EPS + abs(c - n2) + abs(n2 - n4) + abs(n1 - n3);
    float ds1 = EPS + abs(s3 - s1) + abs(s1 - n1) + abs(s2 - c);
    float ds2 = EPS + abs(s4 - s2) + abs(s2 - c) + abs(s3 - s1);
    float dwe1 = EPS + abs(e1 - w1) + abs(w1 - w3) + abs(c - w2);
    float dw2 = EPS + abs(c - w2) + abs(w2 - w4) + abs(w1 - w3);
    float de1 = EPS + abs(e3 - e1) + abs(e1 - w1) + abs(e2 - c);
    float de2 = EPS + abs(e4 - e2) + abs(e2 - c) + abs(e3 - e1);
    float g0 = g.x, g1 = g.y;
    float clip8 = CLIP8_MULT * u_clip;
    // colour ratios in each cardinal direction
    float cru = n1 * (dn2 + g0) /
                (dn2 * (EPS + c) + g0 * (EPS + n2));
    float crd = s1 * (ds2 + g0) /
                (ds2 * (EPS + c) + g0 * (EPS + s2));
    float crl = w1 * (dw2 + g1) /
                (dw2 * (EPS + c) + g1 * (EPS + w2));
    float crr = e1 * (de2 + g1) /
                (de2 * (EPS + c) + g1 * (EPS + e2));
    // G via Hamilton-Adams in the four cardinal directions
    float guha = n1 + 0.5 * (c - n2);
    float gdha = s1 + 0.5 * (c - s2);
    float glha = w1 + 0.5 * (c - w2);
    float grha = e1 + 0.5 * (c - e2);
    // G via adaptive ratios
    float guar = abs(1.0 - cru) < ARTHRESH ? c * cru : guha;
    float gdar = abs(1.0 - crd) < ARTHRESH ? c * crd : gdha;
    float glar = abs(1.0 - crl) < ARTHRESH ? c * crl : glha;
    float grar = abs(1.0 - crr) < ARTHRESH ? c * crr : grha;
    float hwt = dwe1 / (dwe1 + de1);
    float vwt = dn1 / (ds1 + dn1);
    float gintvha = vwt * gdha + (1.0 - vwt) * guha;
    float ginthha = hwt * grha + (1.0 - hwt) * glha;
    float sgn = isG(p.y, p.x) ? -1.0 : 1.0;
    float vcdalt = sgn * (gintvha - c);
    float hcdalt = sgn * (ginthha - c);
    float vcd = sgn * (vwt * gdar + (1.0 - vwt) * guar - c);
    float hcd = sgn * (hwt * grar + (1.0 - hwt) * glar - c);
    if (c > clip8 || gintvha > clip8 || ginthha > clip8) {
        // RawTherapee also replaces the directional estimates before computing
        // dgintv/dginth. Keeping ratio estimates here changes highlight weights.
        guar = guha;
        gdar = gdha;
        glar = glha;
        grar = grha;
        vcd = vcdalt;
        hcd = hcdalt;
    }
    // nb: min() over two inline sq() calls miscompiles on Mesa 26.1
    // radeonsi (returns the first argument); named temps are ok
    float dgi1 = sq(guha - gdha), dgi2 = sq(guar - gdar);
    float dgh1 = sq(glha - grha), dgh2 = sq(glar - grar);
    imageStore(img_a, p, vec4(vcd, hcd, vcdalt, hcdalt));
    imageStore(img_b, p, vec4(min(dgi1, dgi2), min(dgh1, dgh2), 0.0, 0.0));
}
