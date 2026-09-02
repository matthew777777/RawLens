#import amaze
// AMaZE pass 1+2 (fused): horizontal / vertical gradients (C++ lines 368-375)
// and directional colour differences (C++ lines 449-531).  The gradients are
// cheap to recompute, so they are evaluated into shared memory for the 12x12
// region the cd math reads (p +/- 2 of the 8x8 block); their own Cf taps
// (q +/- 2) exactly fill the staged tile.  The grad texture is still written
// (hvwt / gcorr consume it later).
const int HALO = 4;
const int TW = LW + 2 * HALO;
const int TH = LH + 2 * HALO;
const int TN = TW * TH;

// origin (incl. halo) of this workgroup's input tile
ivec2 T0() { return ivec2(gl_WorkGroupID.xy * uvec2(LW, LH)) - HALO; }
// shared-array index of global texel q (q must lie inside the tile)
int SIDX(ivec2 q) { ivec2 l = q - T0(); return l.y * TW + l.x; }
// half-grid index of q: hpos(lin(q)) == (q.x & ~1, q.y) for in-image q
// (lin(q)>>1 = y*(W/2) + (x>>1), and x>>1 < W/2 makes the mod a no-op),
// so the div/mod of hpos are skipped; all call sites are post-guard
int SIDX_H(ivec2 q) { ivec2 l = ivec2(q.x & ~1, q.y) - T0(); return l.y * TW + l.x; }

shared float s_cfa[TN];
shared vec4 s_grad[TN];
float Cf(ivec2 q) { return s_cfa[SIDX(q)]; }
float dw0(ivec2 p) { return s_grad[SIDX(p)].r; }
float dw1(ivec2 p) { return s_grad[SIDX(p)].g; }

void load_tile() {
    ivec2 t0 = T0();
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = clamp(t0 + ivec2(i % TW, i / TW), ivec2(0), u_size - 1);
        s_cfa[i] = texelFetch(u_cfa, q, 0).r;
    }
    memoryBarrierShared();
    barrier();
}

// no trailing comments on layout lines: GLInterface.getLayouts derives the
// uniform key from the last token of the line
layout(binding = 0, rgba16f) writeonly uniform highp image2D img_grad;
layout(binding = 1, rgba16f) writeonly uniform highp image2D img_a;
layout(binding = 2, rgba16f) writeonly uniform highp image2D img_b;
// img_grad: d0, d1, delhvsqsum, cfa (in .a); img_a: vcd, hcd, vcdalt, hcdalt; img_b: dgintv, dginth

// gradient weights at texel q; zero outside inside(q, 2)
vec4 grad_val(ivec2 q) {
    if (!inside(q, 2)) return vec4(0.0);
    float delh = abs(Cf(q + ivec2(1, 0)) - Cf(q + ivec2(-1, 0)));
    float delv = abs(Cf(q + ivec2(0, 1)) - Cf(q + ivec2(0, -1)));
    float d0 = EPS + abs(Cf(q + ivec2(0, 2)) - Cf(q)) + abs(Cf(q) - Cf(q + ivec2(0, -2))) + delv;
    float d1 = EPS + abs(Cf(q + ivec2(2, 0)) - Cf(q)) + abs(Cf(q) - Cf(q + ivec2(-2, 0))) + delh;
    return vec4(d0, d1, delh * delh + delv * delv, 0.0);
}

void main() {
    load_tile();
    ivec2 t0 = T0();
    ivec2 b0 = ivec2(gl_WorkGroupID.xy * uvec2(LW, LH));
    for (int i = int(gl_LocalInvocationIndex); i < TN; i += LW * LH) {
        ivec2 q = t0 + ivec2(i % TW, i / TW);
        vec4 g = vec4(0.0);
        if (q.x >= b0.x - 2 && q.x <= b0.x + 9 && q.y >= b0.y - 2 && q.y <= b0.y + 9) {
            g = grad_val(q);
        }
        s_grad[i] = g;
    }
    memoryBarrierShared();
    barrier();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    // .a carries the CFA forward at fp16 cost (lossless: the input is fp16),
    // so the r32f padded CFA texture dies with this pass
    imageStore(img_grad, p, vec4(s_grad[SIDX(p)].rgb, Cf(p)));
    if (!inside(p, 4)) {
        imageStore(img_a, p, vec4(0.0));
        imageStore(img_b, p, vec4(0.0));
        return;
    }
    float c = Cf(p);
    float clip8 = CLIP8_MULT * u_clip;
    // colour ratios in each cardinal direction
    float cru = Cf(p + ivec2(0, -1)) * (dw0(p + ivec2(0, -2)) + dw0(p)) /
                (dw0(p + ivec2(0, -2)) * (EPS + c) + dw0(p) * (EPS + Cf(p + ivec2(0, -2))));
    float crd = Cf(p + ivec2(0, 1)) * (dw0(p + ivec2(0, 2)) + dw0(p)) /
                (dw0(p + ivec2(0, 2)) * (EPS + c) + dw0(p) * (EPS + Cf(p + ivec2(0, 2))));
    float crl = Cf(p + ivec2(-1, 0)) * (dw1(p + ivec2(-2, 0)) + dw1(p)) /
                (dw1(p + ivec2(-2, 0)) * (EPS + c) + dw1(p) * (EPS + Cf(p + ivec2(-2, 0))));
    float crr = Cf(p + ivec2(1, 0)) * (dw1(p + ivec2(2, 0)) + dw1(p)) /
                (dw1(p + ivec2(2, 0)) * (EPS + c) + dw1(p) * (EPS + Cf(p + ivec2(2, 0))));
    // G via Hamilton-Adams in the four cardinal directions
    float guha = Cf(p + ivec2(0, -1)) + 0.5 * (c - Cf(p + ivec2(0, -2)));
    float gdha = Cf(p + ivec2(0, 1)) + 0.5 * (c - Cf(p + ivec2(0, 2)));
    float glha = Cf(p + ivec2(-1, 0)) + 0.5 * (c - Cf(p + ivec2(-2, 0)));
    float grha = Cf(p + ivec2(1, 0)) + 0.5 * (c - Cf(p + ivec2(2, 0)));
    // G via adaptive ratios
    float guar = abs(1.0 - cru) < ARTHRESH ? c * cru : guha;
    float gdar = abs(1.0 - crd) < ARTHRESH ? c * crd : gdha;
    float glar = abs(1.0 - crl) < ARTHRESH ? c * crl : glha;
    float grar = abs(1.0 - crr) < ARTHRESH ? c * crr : grha;
    float hwt = dw1(p + ivec2(-1, 0)) / (dw1(p + ivec2(-1, 0)) + dw1(p + ivec2(1, 0)));
    float vwt = dw0(p + ivec2(0, -1)) / (dw0(p + ivec2(0, 1)) + dw0(p + ivec2(0, -1)));
    float gintvha = vwt * gdha + (1.0 - vwt) * guha;
    float ginthha = hwt * grha + (1.0 - hwt) * glha;
    float sgn = isG(p.y, p.x) ? -1.0 : 1.0;
    float vcdalt = sgn * (gintvha - c);
    float hcdalt = sgn * (ginthha - c);
    float vcd = sgn * (vwt * gdar + (1.0 - vwt) * guar - c);
    float hcd = sgn * (hwt * grar + (1.0 - hwt) * glar - c);
    if (c > clip8 || gintvha > clip8 || ginthha > clip8) {
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
