#import amaze
// AMaZE pass 9+10 (fused): diagonal gradients delp/delm + R-B variances (C++
// lines 1044-1060) and the diagonal interpolation of the other chroma (C++
// lines 1139-1218). One invocation per R/B site, direct cfa fetches.
// R/B-only planes are stored at half width: column x >> 1 (the C++ half grid).
float Cf(ivec2 q) { return texelFetch(u_cfa, q, 0).r; }

layout(binding = 0, rgba32f) writeonly uniform highp image2D img_out;
void emit(ivec2 p, vec4 v) { imageStore(img_out, p, v); }

void main() {
    ivec2 hp = ivec2(gl_GlobalInvocationID.xy);
    ivec2 p = ivec2(hp.x << 1, hp.y);
    if (p.x >= u_size.x || p.y >= u_size.y) return;
    ivec2 s = ivec2(site_x(p), p.y);
    if (!inside(s, 8)) { emit(hp, vec4(0.0)); return; }
    float c = Cf(s);
    float cse = Cf(s + ivec2(1, 1)), cnw = Cf(s + ivec2(-1, -1)), cne = Cf(s + ivec2(1, -1)), csw = Cf(s + ivec2(-1, 1));
    float cse2 = Cf(s + ivec2(2, 2)), cnw2 = Cf(s + ivec2(-2, -2)), cne2 = Cf(s + ivec2(2, -2)), csw2 = Cf(s + ivec2(-2, 2));
    // diagonal colour ratios (m = SE/NW diag, p = NE/SW diag)
    float crse = 2.0 * cse / (EPS + c + cse2);
    float crnw = 2.0 * cnw / (EPS + c + cnw2);
    float crne = 2.0 * cne / (EPS + c + cne2);
    float crsw = 2.0 * csw / (EPS + c + csw2);
    float rbse = abs(1.0 - crse) < ARTHRESH ? c * crse : cse + 0.5 * (c - cse2);
    float rbnw = abs(1.0 - crnw) < ARTHRESH ? c * crnw : cnw + 0.5 * (c - cnw2);
    float rbne = abs(1.0 - crne) < ARTHRESH ? c * crne : cne + 0.5 * (c - cne2);
    float rbsw = abs(1.0 - crsw) < ARTHRESH ? c * crsw : csw + 0.5 * (c - csw2);
    // delm / delp of the sites along each diagonal
    float dm0 = abs(cse - cnw), dp0 = abs(cne - csw);
    float wtse = EPS + dm0 + abs(cse2 - c) + abs(Cf(s + ivec2(3, 3)) - cse);
    float wtnw = EPS + dm0 + abs(c - cnw2) + abs(cnw - Cf(s + ivec2(-3, -3)));
    float wtne = EPS + dp0 + abs(cne2 - c) + abs(Cf(s + ivec2(3, -3)) - cne);
    float wtsw = EPS + dp0 + abs(c - csw2) + abs(csw - Cf(s + ivec2(-3, 3)));
    float rbm = (wtse * rbnw + wtnw * rbse) / (wtse + wtnw);
    float rbp = (wtne * rbsw + wtsw * rbne) / (wtne + wtsw);
    // green samples around s
    float gm2m3 = Cf(s + ivec2(-2, -3));
    float gp0m3 = Cf(s + ivec2(0, -3));
    float gp2m3 = Cf(s + ivec2(2, -3));
    float gm3m2 = Cf(s + ivec2(-3, -2));
    float gm1m2 = Cf(s + ivec2(-1, -2));
    float gp1m2 = Cf(s + ivec2(1, -2));
    float gp3m2 = Cf(s + ivec2(3, -2));
    float gm2m1 = Cf(s + ivec2(-2, -1));
    float gp0m1 = Cf(s + ivec2(0, -1));
    float gp2m1 = Cf(s + ivec2(2, -1));
    float gm3p0 = Cf(s + ivec2(-3, 0));
    float gm1p0 = Cf(s + ivec2(-1, 0));
    float gp1p0 = Cf(s + ivec2(1, 0));
    float gp3p0 = Cf(s + ivec2(3, 0));
    float gm2p1 = Cf(s + ivec2(-2, 1));
    float gp0p1 = Cf(s + ivec2(0, 1));
    float gp2p1 = Cf(s + ivec2(2, 1));
    float gm3p2 = Cf(s + ivec2(-3, 2));
    float gm1p2 = Cf(s + ivec2(-1, 2));
    float gp1p2 = Cf(s + ivec2(1, 2));
    float gp3p2 = Cf(s + ivec2(3, 2));
    float gm2p3 = Cf(s + ivec2(-2, 3));
    float gp0p3 = Cf(s + ivec2(0, 3));
    float gp2p3 = Cf(s + ivec2(2, 3));
    float rbvarp = EPSSQ + GAUSSEVEN[0] * ((sq(gp0m1 - gm1p0) + sq(gp0m1 - gp1m2))
        + (sq(gm1p0 - gm2p1) + sq(gm1p0 - gp0m1))
        + (sq(gp1p0 - gp0p1) + sq(gp1p0 - gp2m1))
        + (sq(gp0p1 - gm1p2) + sq(gp0p1 - gp1p0)))
        + GAUSSEVEN[1] * ((sq(gm1m2 - gm2m1) + sq(gm1m2 - gp0m3))
        + (sq(gp1m2 - gp0m1) + sq(gp1m2 - gp2m3))
        + (sq(gm2m1 - gm3p0) + sq(gm2m1 - gm1m2))
        + (sq(gp2m1 - gp1p0) + sq(gp2m1 - gp3m2))
        + (sq(gm2p1 - gm3p2) + sq(gm2p1 - gm1p0))
        + (sq(gp2p1 - gp1p2) + sq(gp2p1 - gp3p0))
        + (sq(gm1p2 - gm2p3) + sq(gm1p2 - gp0p1))
        + (sq(gp1p2 - gp0p3) + sq(gp1p2 - gp2p1)));
    float rbvarm = EPSSQ + GAUSSEVEN[0] * ((sq(gp0m1 - gm1m2) + sq(gp0m1 - gp1p0))
        + (sq(gm1p0 - gm2m1) + sq(gm1p0 - gp0p1))
        + (sq(gp1p0 - gp0m1) + sq(gp1p0 - gp2p1))
        + (sq(gp0p1 - gm1p0) + sq(gp0p1 - gp1p2)))
        + GAUSSEVEN[1] * ((sq(gm1m2 - gm2m3) + sq(gm1m2 - gp0m1))
        + (sq(gp1m2 - gp0m3) + sq(gp1m2 - gp2m1))
        + (sq(gm2m1 - gm3m2) + sq(gm2m1 - gm1p0))
        + (sq(gp2m1 - gp1m2) + sq(gp2m1 - gp3p0))
        + (sq(gm2p1 - gm3p0) + sq(gm2p1 - gm1p2))
        + (sq(gp2p1 - gp1p0) + sq(gp2p1 - gp3p2))
        + (sq(gm1p2 - gm2p1) + sq(gm1p2 - gp0p3))
        + (sq(gp1p2 - gp0p1) + sq(gp1p2 - gp2p3)));
    float pmwt = rbvarm / (rbvarp + rbvarm);
    // bound the interpolation in regions of high saturation
    if (rbp < c) {
        if (2.0 * rbp < c) {
            rbp = median3(rbp, cne, csw);
        } else {
            float pw = 2.0 * (c - rbp) / (EPS + rbp + c);
            rbp = pw * rbp + (1.0 - pw) * median3(rbp, cne, csw);
        }
    }
    if (rbm < c) {
        if (2.0 * rbm < c) {
            rbm = median3(rbm, cse, cnw);
        } else {
            float mw = 2.0 * (c - rbm) / (EPS + rbm + c);
            rbm = mw * rbm + (1.0 - mw) * median3(rbm, cse, cnw);
        }
    }
    if (rbp > u_clip) rbp = median3(rbp, cne, csw);
    if (rbm > u_clip) rbm = median3(rbm, cse, cnw);
    emit(hp, vec4(rbm, rbp, pmwt, 0.0));
}
